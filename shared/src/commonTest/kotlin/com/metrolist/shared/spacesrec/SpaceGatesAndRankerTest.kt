/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.SpaceFilterSpec
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpaceGatesAndRankerTest {
    private val nowMs = 1_700_000_000_000L

    private class EmptyTrackLookup(
        private val map: Map<String, TrackClassRecord> = emptyMap(),
    ) : TrackClassLookup {
        override suspend fun getTrackClass(videoId: String): TrackClassRecord? = map[videoId]
    }

    private class EmptyArtistLookup(
        private val map: Map<String, ArtistProfileRecord> = emptyMap(),
    ) : ArtistProfileLookup {
        override suspend fun getArtistProfile(artistId: String): ArtistProfileRecord? = map[artistId]
    }

    private fun song(
        id: String,
        title: String = "Track $id",
        artistId: String? = "artist_$id",
        artistName: String = "Artist $id",
        albumId: String? = "album_$id",
        durationSec: Long? = 210L,
        musicVideoType: String? = "MUSIC_VIDEO_TYPE_ATV",
        playCountText: String? = null,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            thumbnail = null,
            artists = listOf(ArtistTiny(artistId, artistName)),
            album = albumId?.let { AlbumTiny(it, "Album") },
            duration = durationSec,
            musicVideoType = musicVideoType,
            playCountText = playCountText,
        )

    @Test
    fun emptyConstraintBypassPreservesOrderAndAppliesTrustWithoutHeardExclusionOnHome() {
        val trackLookup = EmptyTrackLookup()
        val gates = SpaceGates(trackLookup, LanguageCascade(trackLookup, EmptyArtistLookup()))

        val good1 = song("s1", "First")
        val heard = song("s2", "Heard Yesterday")
        val lowTrust = song("s3", "AI Cover", artistId = null, albumId = null, musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
        val lowTrustKnownArtist =
            song("s4", "Bootleg", artistId = "known_a", albumId = null, durationSec = 40L, musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
        val good2 = song("s5", "Last")
        val podcast = song("s6", "Episode 1", musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")

        val result =
            gates.bypassEmptyConstraints(
                homeSongs = listOf(good1, heard, lowTrust, lowTrustKnownArtist, good2, podcast),
                userKnownArtistIds = setOf("known_a"),
                recentlyHeardVideoIds = setOf("s2"),
            )

        // Spec 0a: Home shelves are exempt from heard exclusion; only non-musical, blocked, and low-trust are dropped.
        assertEquals(listOf("s1", "s2", "s4", "s5"), result.map { it.id })
    }

    @Test
    fun completionRuleAndHeardExclusionHandleShortAndLongTracks() {
        // 30s played on 200s song -> completed
        assertTrue(PlayRecord("v1", listOf("a1"), playedMs = 30_000L, durationMs = 200_000L, playedAtMs = nowMs).isCompleted())
        // 20s played on 35s short track (>= 50% of duration) -> completed
        assertTrue(PlayRecord("v2", listOf("a1"), playedMs = 20_000L, durationMs = 35_000L, playedAtMs = nowMs).isCompleted())
        // 15s played on 200s song -> skip (NOT completed)
        assertFalse(PlayRecord("v3", listOf("a1"), playedMs = 15_000L, durationMs = 200_000L, playedAtMs = nowMs).isCompleted())
        // 15s played with unknown duration -> skip
        assertFalse(PlayRecord("v4", listOf("a1"), playedMs = 15_000L, durationMs = null, playedAtMs = nowMs).isCompleted())

        val plays =
            listOf(
                PlayRecord("recent_done", listOf("a1"), 35_000L, 200_000L, nowMs - 10L * SpaceRecConfig.MILLIS_PER_DAY),
                PlayRecord("recent_skip", listOf("a1"), 5_000L, 200_000L, nowMs - 2L * SpaceRecConfig.MILLIS_PER_DAY),
                PlayRecord("old_done", listOf("a1"), 60_000L, 200_000L, nowMs - 60L * SpaceRecConfig.MILLIS_PER_DAY),
            )
        assertEquals(setOf("recent_done"), SpaceGates.recentlyHeardIds(plays, nowMs))
    }

    @Test
    fun exclusionsAndShownRelaxationNeverRelaxHeardOrLanguage() =
        runTest {
            val trackLookup = EmptyTrackLookup()
            val gates = SpaceGates(trackLookup, LanguageCascade(trackLookup, EmptyArtistLookup()))

            val candidates =
                listOf(
                    CandidateTrack(song("heard_id", "മലയാളം ഒന്ന്"), setOf(CandidateSource.SEED_RADIO)),
                    CandidateTrack(song("queued_id", "മലയാളം രണ്ട്"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("blocked_id", "മലയാളം മൂന്ന്"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("wrong_lang", "봄날", artistName = "BTS"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("shown_id", "മലയാളം നാല്"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("fresh_id", "മലയാളം അഞ്ച്"), setOf(CandidateSource.HOME)),
                )

            val context =
                GateContext(
                    nowMs = nowMs,
                    filter = SpaceFilterSpec(languages = listOf("malayalam")),
                    recentlyHeardVideoIds = setOf("heard_id"),
                    currentlyQueuedVideoIds = setOf("queued_id"),
                    recentlyShownVideoIds = setOf("shown_id"),
                    isBlocked = { it.id == "blocked_id" },
                )

            // Strict pass drops heard_id (SEED_RADIO) and shown_id.
            val strict = gates.evaluate(candidates, context)
            assertEquals(listOf("fresh_id"), strict.survivors.map { it.candidate.song.id })
            assertEquals(1, strict.stats.drops[GateDropReason.HEARD_RECENTLY])
            assertEquals(1, strict.stats.drops[GateDropReason.SHOWN_RECENTLY])

            // Because survivors (1) < feedTargetSongs / 2 (30), relaxation restores ONLY shown_id!
            val relaxed = gates.evaluateWithShownRelaxation(candidates, context)
            assertTrue(relaxed.stats.shownExclusionRelaxed)
            assertEquals(listOf("shown_id", "fresh_id"), relaxed.survivors.map { it.candidate.song.id })
        }

    @Test
    fun heardExclusionExemptsHomeAndLocalHistoryButExcludesRadioAndSearch() =
        runTest {
            val trackLookup = EmptyTrackLookup()
            val gates = SpaceGates(trackLookup, LanguageCascade(trackLookup, EmptyArtistLookup()))

            val candidates =
                listOf(
                    CandidateTrack(song("home_heard", "മലയാളം ഒന്ന്"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("card_heard", "മലയാളം രണ്ട്"), setOf(CandidateSource.HOME_CARD)),
                    CandidateTrack(song("local_heard", "മലയാളം മൂന്ന്"), setOf(CandidateSource.LOCAL_HISTORY)),
                    CandidateTrack(song("seed_heard", "മലയാളം നാല്"), setOf(CandidateSource.SEED_RADIO)),
                    CandidateTrack(song("artist_heard", "മലയാളം അഞ്ച്"), setOf(CandidateSource.ARTIST_RADIO)),
                    CandidateTrack(song("search_heard", "മലയാളം ആറ്"), setOf(CandidateSource.SEARCH)),
                )

            val context =
                GateContext(
                    nowMs = nowMs,
                    filter = SpaceFilterSpec(languages = listOf("malayalam")),
                    recentlyHeardVideoIds = candidates.mapTo(hashSetOf()) { it.song.id },
                )

            val result = gates.evaluate(candidates, context)
            assertEquals(listOf("home_heard", "card_heard", "local_heard"), result.survivors.map { it.candidate.song.id })
            assertEquals(3, result.stats.drops[GateDropReason.HEARD_RECENTLY])
        }

    @Test
    fun genreGateChecksTrackThenSettledArtistGenreThenSeedThenHighPriorityMaybe() =
        runTest {
            val trackLookup =
                EmptyTrackLookup(
                    mapOf(
                        "t_track_ok" to
                            TrackClassRecord(
                                videoId = "t_track_ok",
                                language = "en",
                                languageConfidence = 1.0,
                                genre = "jazz",
                                evidence = "lastfm",
                                checkedAt = nowMs,
                            ),
                        "t_track_bad" to
                            TrackClassRecord(
                                videoId = "t_track_bad",
                                language = "en",
                                languageConfidence = 1.0,
                                genre = "metal",
                                evidence = "lastfm",
                                checkedAt = nowMs,
                            ),
                    ),
                )
            val artistLookup =
                EmptyArtistLookup(
                    mapOf(
                        "a_jazz" to
                            ArtistProfileRecord(
                                artistId = "a_jazz",
                                name = "Miles",
                                languageVotes = mapOf("en" to 5.0),
                                totalVotes = 5.0,
                                updatedAt = nowMs,
                                genreVotes = mapOf("jazz" to 5.0),
                            ),
                        "a_rock" to
                            ArtistProfileRecord(
                                artistId = "a_rock",
                                name = "RockBand",
                                languageVotes = mapOf("en" to 5.0),
                                totalVotes = 5.0,
                                updatedAt = nowMs,
                                genreVotes = mapOf("rock" to 5.0),
                            ),
                    ),
                )
            val gates = SpaceGates(trackLookup, LanguageCascade(trackLookup, artistLookup), artistProfileLookup = artistLookup)

            val candidates =
                listOf(
                    CandidateTrack(song("t_track_ok", artistId = "a_unknown"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("t_track_bad", artistId = "a_jazz"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("t_artist_ok", artistId = "a_jazz"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("t_artist_bad", artistId = "a_rock"), setOf(CandidateSource.HOME)),
                    CandidateTrack(song("t_seed_ok", artistId = "a_unknown2"), setOf(CandidateSource.SEED_RADIO), seedGenreAccepted = true),
                    CandidateTrack(song("t_unknown", artistId = "a_unknown3"), setOf(CandidateSource.HOME)),
                )

            val result = gates.evaluate(candidates, GateContext(nowMs = nowMs, filter = SpaceFilterSpec(genres = listOf("jazz"))))
            val byId = result.survivors.associateBy { it.candidate.song.id }
            assertEquals(setOf("t_track_ok", "t_artist_ok", "t_seed_ok", "t_unknown"), byId.keys)
            assertEquals(GateVerdict.ACCEPT, byId.getValue("t_track_ok").genreVerdict)
            assertEquals(GateVerdict.ACCEPT, byId.getValue("t_artist_ok").genreVerdict)
            assertEquals(GateVerdict.ACCEPT, byId.getValue("t_seed_ok").genreVerdict)
            assertEquals(GateVerdict.MAYBE, byId.getValue("t_unknown").genreVerdict)
            assertTrue(byId.getValue("t_unknown").genreHighPriorityForL5)
            assertEquals(2, result.stats.drops[GateDropReason.GENRE_REJECTED])
        }

    @Test
    fun rankerFeaturesAndPlayCountParsing() {
        assertEquals(12_000_000L, SpaceRanker.parsePlayCountText("12M"))
        assertEquals(1_400L, SpaceRanker.parsePlayCountText("1.4K plays"))
        assertEquals(2_500_000_000L, SpaceRanker.parsePlayCountText("2.5B"))
        assertEquals(450L, SpaceRanker.parsePlayCountText("450"))
        assertNull(SpaceRanker.parsePlayCountText("Millions of plays"))
        assertNull(SpaceRanker.parsePlayCountText(""))
        assertNull(SpaceRanker.parsePlayCountText(null))

        // Unknown playCountText falls back to 0.5
        assertEquals(0.5, SpaceRanker.discoveryScore("not a number"))
        // 1B plays -> 1 - 9/9 = 0.0
        assertTrue(abs(0.0 - SpaceRanker.discoveryScore("1B")) < 1e-9)

        // Source multi-bonus capped at +0.20
        val singleHome = SpaceRanker.sourceScore(setOf(CandidateSource.HOME))
        val multiThree = SpaceRanker.sourceScore(setOf(CandidateSource.HOME, CandidateSource.SEED_RADIO, CandidateSource.SEARCH))
        val multiFour =
            SpaceRanker.sourceScore(
                setOf(CandidateSource.HOME, CandidateSource.SEED_RADIO, CandidateSource.ARTIST_RADIO, CandidateSource.SEARCH),
            )
        assertTrue(abs(0.30 - singleHome) < 1e-9)
        assertTrue(abs(0.50 - multiThree) < 1e-9)
        assertTrue(abs(0.50 - multiFour) < 1e-9)
    }
}
