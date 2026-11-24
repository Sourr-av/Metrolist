/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.db.daos.SpacePlayHistoryJoinedRow
import com.metrolist.shared.db.entities.ArtistProfileEntity
import com.metrolist.shared.db.entities.SpaceFeedCacheEntity
import com.metrolist.shared.db.entities.SpacePlayEventEntity
import com.metrolist.shared.db.entities.SpaceSeedArmEntity
import com.metrolist.shared.db.entities.SpaceShownEntity
import com.metrolist.shared.db.entities.TrackClassEntity
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.SpaceFilterSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceRecEngineTest {
    private val baseTimeMs = 1_700_000_000_000L

    private val defaultLabels =
        SpaceShelfLabels(
            freshPicksTitle = "Fresh picks",
            moreLikeFormat = { artist -> "More like $artist" },
        )

    private fun song(
        id: String,
        title: String = "Track $id",
        artistId: String = "artist_$id",
        artistName: String = "Artist $id",
        durationSec: Long? = 210L,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            thumbnail = null,
            artists = listOf(ArtistTiny(artistId, artistName)),
            album = AlbumTiny("album_$id", "Album"),
            duration = durationSec,
            musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
        )

    @Test
    fun inactiveFilterNeverInvokesEngineDaoOrYouTube() =
        runTest {
            val dao = FakeSpaceRecDao()
            val yt = FakeSpaceYouTubeSource()
            val resolver = FakeSpaceTagResolver()
            val engine =
                SpaceRecEngine(
                    dao = dao,
                    resolver = resolver,
                    clock = { baseTimeMs + currentTime },
                    random = Random(1),
                    youtubeSource = yt,
                    isBlocked = { false },
                )

            val emissions =
                engine
                    .refresh(
                        spaceId = "space_empty",
                        filter = SpaceFilterSpec(),
                        homeInput = SpaceHomeInput(primarySections = emptyList(), shelfLabels = defaultLabels),
                    ).toList()

            assertTrue(emissions.isEmpty())
            assertEquals(0, dao.readCount)
            assertEquals(0, yt.callCount)
            assertEquals(0, resolver.artistCalls)
        }

    @Test
    fun freshCacheEmitsWithin300MsAndSkipsNetworkWhileForceNetworkBypassesCache() =
        runTest {
            val dao = FakeSpaceRecDao(feedCacheDelayMs = 40L)
            val yt = FakeSpaceYouTubeSource()
            val cachedSong = song("cached_1", "മലയാളം പാട്ട്")
            val cachedPayload =
                CachedSpaceFeedPayload(
                    shelves = listOf(AssembledShelf("home_1", "Quick Picks", songs = listOf(cachedSong))),
                    quickPicks = listOf(cachedSong),
                )
            dao.feedCache["space_ml"] =
                SpaceFeedCacheEntity(
                    spaceId = "space_ml",
                    payloadJson = SpaceRecDbAdapter.encodeCachedPayload(cachedPayload),
                    savedAt = baseTimeMs - 60_000L, // 1 min old (fresh within 1h TTL)
                    refreshSeq = 3,
                )

            val engine =
                SpaceRecEngine(
                    dao = dao,
                    resolver = FakeSpaceTagResolver(isAvailable = false),
                    clock = { baseTimeMs + currentTime },
                    random = Random(2),
                    youtubeSource = yt,
                    isBlocked = { false },
                )

            // Fresh cache -> single CACHE emission within 300ms, no network calls.
            val freshEmissions =
                engine
                    .refresh(
                        spaceId = "space_ml",
                        filter = SpaceFilterSpec(languages = listOf("malayalam")),
                        homeInput = SpaceHomeInput(primarySections = emptyList(), shelfLabels = defaultLabels),
                        forceNetwork = false,
                    ).toList()

            assertEquals(listOf(SpaceFeedPhase.CACHE), freshEmissions.map { it.phase })
            assertTrue(freshEmissions.first().stats.elapsedMs < 300L)
            assertEquals(
                listOf("cached_1"),
                freshEmissions
                    .first()
                    .shelves
                    .first()
                    .songs
                    .map { it.id },
            )
            assertEquals(0, yt.callCount)

            // Pull-to-refresh (forceNetwork = true) -> skips CACHE, emits PARTIAL and FINAL.
            val freshSongs = listOf(song("live_1", "മലയാളം ലൈവ്"))
            val forcedEmissions =
                engine
                    .refresh(
                        spaceId = "space_ml",
                        filter = SpaceFilterSpec(languages = listOf("malayalam")),
                        homeInput =
                            SpaceHomeInput(
                                primarySections = listOf(HomeSection("Home", freshSongs)),
                                shelfLabels = defaultLabels,
                            ),
                        forceNetwork = true,
                    ).toList()

            assertFalse(forcedEmissions.any { it.phase == SpaceFeedPhase.CACHE })
            assertEquals(listOf(SpaceFeedPhase.PARTIAL, SpaceFeedPhase.FINAL), forcedEmissions.map { it.phase })
            assertEquals(4, dao.feedCache["space_ml"]?.refreshSeq)
        }

    @Test
    fun partialEmitsAtStageADeadlineAndFinalByHardDeadlineWhenEverythingHangs() =
        runTest {
            val config = SpaceRecConfig.Default
            val dao = FakeSpaceRecDao()
            dao.seedArms +=
                SpaceSeedArmEntity(
                    spaceId = "space_hang",
                    artistId = "a_seed",
                    seedVideoId = "v_seed",
                    completions = 0,
                    skips = 0,
                    lastUsedSeq = -1,
                )
            dao.playHistory +=
                SpacePlayHistoryJoinedRow(
                    eventId = 1L,
                    videoId = "v_seed",
                    playTimeMs = 60_000L,
                    playedAtMs = baseTimeMs - 2L * SpaceRecConfig.MILLIS_PER_DAY,
                    durationSeconds = 200,
                    artistId = "a_seed",
                )

            val hangingYt = FakeSpaceYouTubeSource(hangForever = true)
            val engine =
                SpaceRecEngine(
                    dao = dao,
                    resolver = FakeSpaceTagResolver(isAvailable = false),
                    config = config,
                    clock = { baseTimeMs + currentTime },
                    random = Random(3),
                    youtubeSource = hangingYt,
                    isBlocked = { false },
                )

            val primarySongs = (1..6).map { i -> song("p_$i", "മലയാളം പാട്ട് $i", artistId = "pa_$i") }
            val emissionTimes = mutableListOf<Pair<SpaceFeedPhase, Long>>()

            val emissions =
                engine
                    .refresh(
                        spaceId = "space_hang",
                        filter = SpaceFilterSpec(languages = listOf("malayalam")),
                        homeInput =
                            SpaceHomeInput(
                                primarySections = listOf(HomeSection("For You", primarySongs)),
                                continuation = "cont_token",
                                shelfLabels = defaultLabels,
                            ),
                    ).toList()
                    .also { list ->
                        list.forEach { emissionTimes += it.phase to it.stats.elapsedMs }
                    }

            assertEquals(listOf(SpaceFeedPhase.PARTIAL, SpaceFeedPhase.FINAL), emissions.map { it.phase })

            val partial = emissions[0]
            assertTrue(partial.stats.stageATimeout)
            assertEquals(config.stageADeadlineMs, partial.stats.elapsedMs)
            assertTrue(partial.shelves.isNotEmpty())

            val final = emissions[1]
            assertTrue(final.stats.hardDeadlineHit)
            assertTrue(final.stats.elapsedMs <= config.hardDeadlineMs)
            assertEquals(config.hardDeadlineMs, final.stats.elapsedMs)
        }

    @Test
    fun l5RunsAfterFinalWithoutBlockingAndEmitsResolvedUpdateWhenVerdictsChange() =
        runTest {
            val dao = FakeSpaceRecDao()
            val yt = FakeSpaceYouTubeSource()
            val resolver =
                FakeSpaceTagResolver(
                    isAvailable = true,
                    delayMs = 500L,
                    artistResults =
                        mapOf(
                            "a_ambig" to
                                ArtistTagResolution(
                                    artistId = "a_ambig",
                                    artistName = "AmbigArtist",
                                    detectedLanguages = setOf("hi"),
                                    detectedGenres = setOf("pop"),
                                    needsTrackDisambiguation = false,
                                ),
                        ),
                )
            val engine =
                SpaceRecEngine(
                    dao = dao,
                    resolver = resolver,
                    clock = { baseTimeMs + currentTime },
                    random = Random(4),
                    youtubeSource = yt,
                    isBlocked = { false },
                )

            // Ambiguous ASCII song in a Malayalam space -> MAYBE before L5, REJECTED after L5 classifies artist as Hindi!
            val clearMalayalam = (1..6).map { i -> song("ml_$i", "മലയാളം $i", artistId = "a_ml_$i") }
            val ambigHindiSong = song("ambig_1", "Tum Hi Ho", artistId = "a_ambig", artistName = "AmbigArtist")

            val emissions =
                engine
                    .refresh(
                        spaceId = "space_l5",
                        filter = SpaceFilterSpec(languages = listOf("malayalam")),
                        homeInput =
                            SpaceHomeInput(
                                primarySections = listOf(HomeSection("Home", clearMalayalam + ambigHindiSong)),
                                shelfLabels = defaultLabels,
                            ),
                    ).toList()

            assertEquals(
                listOf(SpaceFeedPhase.PARTIAL, SpaceFeedPhase.FINAL, SpaceFeedPhase.RESOLVED_UPDATE),
                emissions.map { it.phase },
            )

            val finalEmission = emissions[1]
            val resolvedEmission = emissions[2]

            // FINAL arrived BEFORE L5's 500ms delay!
            assertTrue(finalEmission.stats.elapsedMs < 500L)
            assertTrue(resolvedEmission.stats.elapsedMs >= 500L)

            // Before L5, ambig_1 survived as MAYBE; after L5 wrote Hindi to artist_profile & track_class, it is rejected!
            assertEquals(1, finalEmission.stats.maybeCount)
            assertEquals(0, resolvedEmission.stats.maybeCount)
            assertEquals(1, resolvedEmission.stats.rejectCount)
            assertFalse(resolvedEmission.shelves.flatMap { it.songs }.any { it.id == "ambig_1" })
            assertNotNull(dao.artistProfiles["a_ambig"])
            assertEquals("hi", dao.trackClasses["ambig_1"]?.language)
            assertTrue(dao.artistProfiles["a_ambig"]!!.genreVotesJson.contains("pop"))
        }

    @Test
    fun heardExclusionExemptsHomeAndLocalShelvesWhileExcludingRadioAndGeneratedShelves() =
        runTest {
            val dao = FakeSpaceRecDao()
            val heardHomeSong = song("heard_home", "മലയാളം ഹോം", artistId = "a_hh")
            val heardLocalSong = song("heard_local", "മലയാളം ലോക്കൽ", artistId = "a_hl")
            val heardRadioSong = song("heard_radio", "മലയാളം റേഡിയോ", artistId = "a_hr")
            val freshRadioSong = song("fresh_radio", "മലയാളം ഫ്രഷ്", artistId = "a_fr")

            // Mark all three as completed yesterday.
            listOf(heardHomeSong, heardLocalSong, heardRadioSong).forEachIndexed { idx, s ->
                dao.playHistory +=
                    SpacePlayHistoryJoinedRow(
                        eventId = idx.toLong() + 1L,
                        videoId = s.id,
                        playTimeMs = 90_000L,
                        playedAtMs = baseTimeMs - SpaceRecConfig.MILLIS_PER_DAY,
                        durationSeconds = 210,
                        artistId = s.artists.first().id,
                    )
            }
            dao.seedArms +=
                SpaceSeedArmEntity(
                    spaceId = "space_heard",
                    artistId = "a_seed",
                    seedVideoId = "v_seed",
                    completions = 0,
                    skips = 0,
                    lastUsedSeq = -1,
                )

            val yt =
                FakeSpaceYouTubeSource(
                    radioSongs = listOf(heardRadioSong, freshRadioSong),
                )
            val engine =
                SpaceRecEngine(
                    dao = dao,
                    resolver = FakeSpaceTagResolver(isAvailable = false),
                    clock = { baseTimeMs + currentTime },
                    random = Random(5),
                    youtubeSource = yt,
                    isBlocked = { false },
                )

            val otherHome = (1..5).map { i -> song("h_$i", "മലയാളം $i", artistId = "a_h_$i") }
            val final =
                engine
                    .refresh(
                        spaceId = "space_heard",
                        filter = SpaceFilterSpec(languages = listOf("malayalam")),
                        homeInput =
                            SpaceHomeInput(
                                primarySections = listOf(HomeSection("Home Mix", listOf(heardHomeSong) + otherHome)),
                                localQuickPicks = listOf(heardLocalSong),
                                localKeepListening = listOf(heardLocalSong),
                                localForgottenFavorites = listOf(heardLocalSong),
                                shelfLabels = defaultLabels,
                            ),
                    ).toList()
                    .last { it.phase == SpaceFeedPhase.FINAL }

            val homeShelfSongs =
                final.shelves
                    .filterNot { it.isGenerated }
                    .flatMap { it.songs }
                    .map { it.id }
            val generatedShelfSongs =
                final.shelves
                    .filter { it.isGenerated }
                    .flatMap { it.songs }
                    .map { it.id }

            assertTrue("heard_home" in homeShelfSongs)
            assertTrue("heard_local" in final.quickPicks.map { it.id })
            assertTrue("heard_local" in final.keepListening.map { it.id })
            assertTrue("heard_local" in final.forgottenFavorites.map { it.id })
            assertFalse("heard_radio" in homeShelfSongs)
            assertFalse("heard_radio" in generatedShelfSongs)
            assertFalse("heard_home" in generatedShelfSongs)
        }

    @Test
    fun recordPlayWritesEventOnlyForSpaceProvenanceAndUpdatesSeedArmBandit() =
        runTest {
            val dao = FakeSpaceRecDao()
            dao.seedArms +=
                SpaceSeedArmEntity(
                    spaceId = "space_bandit",
                    artistId = "a_seed",
                    seedVideoId = "v_seed",
                    completions = 0,
                    skips = 0,
                    lastUsedSeq = -1,
                )
            val radioSong = song("r_1", "മലയാളം റേഡിയോ", artistId = "a_r1", durationSec = null)
            dao.songDurations["r_1"] = 180 // fallback duration from song table

            val yt = FakeSpaceYouTubeSource(radioSongsBySeed = mapOf("v_seed" to listOf(radioSong)))
            val engine =
                SpaceRecEngine(
                    dao = dao,
                    resolver = FakeSpaceTagResolver(isAvailable = false),
                    clock = { baseTimeMs + currentTime },
                    random = Random(6),
                    youtubeSource = yt,
                    isBlocked = { false },
                )

            // Populate provenance via refresh.
            val homeSongs = (1..6).map { i -> song("m_$i", "മലയാളം $i", artistId = "a_m_$i") }
            engine
                .refresh(
                    spaceId = "space_bandit",
                    filter = SpaceFilterSpec(languages = listOf("malayalam")),
                    homeInput =
                        SpaceHomeInput(
                            primarySections = listOf(HomeSection("Home", homeSongs)),
                            shelfLabels = defaultLabels,
                        ),
                ).toList()

            assertNotNull(engine.provenanceFor("r_1"))
            assertNull(engine.provenanceFor("not_from_space"))

            // Non-space play -> ignored, no DB event!
            engine.recordPlay("not_from_space", playedMs = 60_000L, durationMs = 200_000L)
            assertTrue(dao.playEvents.isEmpty())

            // Completed play on Space radio song (duration resolved via song table fallback) -> event + completions + 1!
            engine.recordPlay("r_1", playedMs = 45_000L, durationMs = null)
            assertEquals(1, dao.playEvents.size)
            assertEquals("space_bandit", dao.playEvents.first().spaceId)
            assertEquals("a_seed", dao.playEvents.first().seedArtistId)
            val updatedArm = dao.seedArms.first { it.artistId == "a_seed" }
            assertEquals(1, updatedArm.completions)
            assertEquals(0, updatedArm.skips)
        }
}

private class FakeSpaceRecDao(
    private val feedCacheDelayMs: Long = 0L,
) : StubSpaceRecDao() {
    var readCount = 0
    val trackClasses = mutableMapOf<String, TrackClassEntity>()
    val artistProfiles = mutableMapOf<String, ArtistProfileEntity>()
    val seedArms = mutableListOf<SpaceSeedArmEntity>()
    val shownEntries = mutableListOf<SpaceShownEntity>()
    val feedCache = mutableMapOf<String, SpaceFeedCacheEntity>()
    val playEvents = mutableListOf<SpacePlayEventEntity>()
    val playHistory = mutableListOf<SpacePlayHistoryJoinedRow>()
    val songDurations = mutableMapOf<String, Int>()

    override suspend fun getTrackClasses(videoIds: List<String>): List<TrackClassEntity> {
        readCount++
        return videoIds.mapNotNull { trackClasses[it] }
    }

    override suspend fun upsertTrackClass(entity: TrackClassEntity) {
        trackClasses[entity.videoId] = entity
    }

    override suspend fun upsertTrackClasses(entities: List<TrackClassEntity>) {
        entities.forEach { trackClasses[it.videoId] = it }
    }

    override suspend fun getArtistProfile(artistId: String): ArtistProfileEntity? {
        readCount++
        return artistProfiles[artistId]
    }

    override suspend fun getArtistProfiles(artistIds: List<String>): List<ArtistProfileEntity> {
        readCount++
        return artistIds.mapNotNull { artistProfiles[it] }
    }

    override suspend fun upsertArtistProfile(entity: ArtistProfileEntity) {
        artistProfiles[entity.artistId] = entity
    }

    override suspend fun getSeedArms(spaceId: String): List<SpaceSeedArmEntity> {
        readCount++
        return seedArms.filter { it.spaceId == spaceId }
    }

    override suspend fun getSeedArm(
        spaceId: String,
        artistId: String,
    ): SpaceSeedArmEntity? {
        readCount++
        return seedArms.firstOrNull { it.spaceId == spaceId && it.artistId == artistId }
    }

    override suspend fun upsertSeedArm(entity: SpaceSeedArmEntity) {
        seedArms.removeAll { it.spaceId == entity.spaceId && it.artistId == entity.artistId }
        seedArms += entity
    }

    override suspend fun upsertSeedArms(entities: List<SpaceSeedArmEntity>) {
        entities.forEach { upsertSeedArm(it) }
    }

    override suspend fun getRecentlyShownVideoIds(
        spaceId: String,
        minRefreshSeq: Int,
    ): List<String> {
        readCount++
        return shownEntries.filter { it.spaceId == spaceId && it.refreshSeq >= minRefreshSeq }.map { it.videoId }.distinct()
    }

    override suspend fun upsertShown(entries: List<SpaceShownEntity>) {
        shownEntries += entries
    }

    override suspend fun pruneShownBefore(
        spaceId: String,
        minRefreshSeq: Int,
    ) {
        shownEntries.removeAll { it.spaceId == spaceId && it.refreshSeq < minRefreshSeq }
    }

    override suspend fun getFeedCache(spaceId: String): SpaceFeedCacheEntity? {
        readCount++
        if (feedCacheDelayMs > 0L) delay(feedCacheDelayMs)
        return feedCache[spaceId]
    }

    override suspend fun upsertFeedCache(entity: SpaceFeedCacheEntity) {
        feedCache[entity.spaceId] = entity
    }

    override suspend fun insertPlayEvent(event: SpacePlayEventEntity): Long {
        playEvents += event
        return playEvents.size.toLong()
    }

    override suspend fun getPlayHistoryWithDurationSince(sinceTimestampMs: Long): List<SpacePlayHistoryJoinedRow> {
        readCount++
        return playHistory.filter { it.playedAtMs >= sinceTimestampMs }
    }

    override suspend fun getAllEverPlayedVideoIds(): List<String> {
        readCount++
        return playHistory.map { it.videoId }.distinct()
    }

    override suspend fun getSongDurationSeconds(videoId: String): Int? = songDurations[videoId]
}

private class FakeSpaceYouTubeSource(
    private val hangForever: Boolean = false,
    private val radioSongs: List<SongItem> = emptyList(),
    private val radioSongsBySeed: Map<String, List<SongItem>> = emptyMap(),
    private val searchSongsList: List<SongItem> = emptyList(),
) : SpaceYouTubeSource {
    var callCount = 0

    override suspend fun getHomeSectionsContinuation(
        token: String,
        loadAllPages: Boolean,
    ): Pair<List<HomeSection>, String?> {
        callCount++
        if (hangForever) delay(60_000L)
        return emptyList<HomeSection>() to null
    }

    override suspend fun startRadioQueue(
        videoId: String,
        playlistId: String,
    ): RadioQueuePage {
        callCount++
        if (hangForever) delay(60_000L)
        val songs = if (radioSongsBySeed.isNotEmpty()) radioSongsBySeed[videoId].orEmpty() else radioSongs
        return RadioQueuePage(songs = songs, chips = emptyList(), continuation = null)
    }

    override suspend fun continueRadioQueue(continuation: String): RadioQueuePage {
        callCount++
        if (hangForever) delay(60_000L)
        return RadioQueuePage(songs = emptyList(), chips = emptyList(), continuation = null)
    }

    override suspend fun startArtistRadio(channelId: String): List<SongItem> {
        callCount++
        if (hangForever) delay(60_000L)
        return emptyList()
    }

    override suspend fun getAlbumSongs(albumId: String): List<SongItem> {
        callCount++
        if (hangForever) delay(60_000L)
        return emptyList()
    }

    override suspend fun getPlaylistSongs(playlistId: String): List<SongItem> {
        callCount++
        if (hangForever) delay(60_000L)
        return emptyList()
    }

    override suspend fun searchSongs(query: String): List<SongItem> {
        callCount++
        if (hangForever) delay(60_000L)
        return searchSongsList
    }
}

private class FakeSpaceTagResolver(
    override val isAvailable: Boolean = true,
    private val delayMs: Long = 0L,
    private val artistResults: Map<String, ArtistTagResolution> = emptyMap(),
) : SpaceTagResolver {
    var artistCalls = 0
    override val isCircuitOpen: Boolean = false

    override suspend fun resolveArtist(
        artistId: String?,
        artistName: String,
        filter: SpaceFilterSpec,
    ): ArtistTagResolution? {
        artistCalls++
        if (delayMs > 0L) delay(delayMs)
        return artistResults[artistId] ?: artistResults[artistName]
    }

    override suspend fun resolveTrack(
        song: SongItem,
        artistResolution: ArtistTagResolution?,
        filter: SpaceFilterSpec,
    ): TrackTagResolution? = null
}
