/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.db.daos.SpaceRecDao
import com.metrolist.shared.db.entities.SpaceFeedCacheEntity
import com.metrolist.shared.db.entities.SpacePlayEventEntity
import com.metrolist.shared.db.entities.SpaceShownEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.SpaceFilterSpec
import com.metrolist.shared.spaces.SpaceFilterTaxonomy
import com.metrolist.shared.spacesrec.SpaceRecDbAdapter.toEntity
import com.metrolist.shared.spacesrec.SpaceRecDbAdapter.toRecord
import com.metrolist.shared.spacesrec.SpaceRecDbAdapter.toState
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.getCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Abstraction over [GlobalYouTubeRepository.instance] so [SpaceRecEngine] uses zero new HTTP
 * code in production while remaining fully deterministic under virtual time in tests.
 */
interface SpaceYouTubeSource {
    suspend fun getHomeSectionsContinuation(
        token: String,
        loadAllPages: Boolean,
    ): Pair<List<HomeSection>, String?>

    suspend fun startRadioQueue(
        videoId: String,
        playlistId: String,
    ): RadioQueuePage

    suspend fun continueRadioQueue(continuation: String): RadioQueuePage

    suspend fun startArtistRadio(channelId: String): List<SongItem>

    suspend fun getAlbumSongs(albumId: String): List<SongItem>

    suspend fun getPlaylistSongs(playlistId: String): List<SongItem>

    suspend fun searchSongs(query: String): List<SongItem>
}

object GlobalSpaceYouTubeSource : SpaceYouTubeSource {
    override suspend fun getHomeSectionsContinuation(
        token: String,
        loadAllPages: Boolean,
    ): Pair<List<HomeSection>, String?> =
        GlobalYouTubeRepository.instance.getHomeSectionsContinuation(
            token = token,
            isPersonal = false,
            loadAllPages = loadAllPages,
        )

    override suspend fun startRadioQueue(
        videoId: String,
        playlistId: String,
    ): RadioQueuePage = GlobalYouTubeRepository.instance.startRadioQueue(videoId, playlistId = playlistId)

    override suspend fun continueRadioQueue(continuation: String): RadioQueuePage =
        GlobalYouTubeRepository.instance.continueRadioQueue(continuation)

    override suspend fun startArtistRadio(channelId: String): List<SongItem> = GlobalYouTubeRepository.instance.startArtistRadio(channelId)

    override suspend fun getAlbumSongs(albumId: String): List<SongItem> =
        GlobalYouTubeRepository.instance
            .getAlbum(albumId, loadAllPages = false)
            ?.songs
            .orEmpty()

    override suspend fun getPlaylistSongs(playlistId: String): List<SongItem> =
        GlobalYouTubeRepository.instance
            .getPlaylist(playlistId)
            .values
            .flatten()
            .filterIsInstance<SongItem>()

    override suspend fun searchSongs(query: String): List<SongItem> =
        GlobalYouTubeRepository.instance
            .searchFiltered(
                query = query,
                filter = SearchFilter.SONG,
                recordSearchHistory = false,
                allowOfflineFallback = false,
            ).items
            .filterIsInstance<SongItem>()
}

data class SpaceHomeInput(
    val primarySections: List<HomeSection>,
    val continuation: String? = null,
    val localQuickPicks: List<SongItem> = emptyList(),
    val localKeepListening: List<SongItem> = emptyList(),
    val localForgottenFavorites: List<SongItem> = emptyList(),
    val shelfLabels: SpaceShelfLabels,
)

data class SpaceRefreshStats(
    val spaceId: String,
    val phase: SpaceFeedPhase,
    val elapsedMs: Long,
    val cacheHit: Boolean = false,
    val candidateCount: Int = 0,
    val dedupedCount: Int = 0,
    val acceptCount: Int = 0,
    val rejectCount: Int = 0,
    val maybeCount: Int = 0,
    val dropsByReason: Map<GateDropReason, Int> = emptyMap(),
    val assembledCount: Int = 0,
    val familiarShare: Double = 0.0,
    val uniqueArtists: Int = 0,
    val maxPerArtist: Int = 0,
    val stageATimeout: Boolean = false,
    val hardDeadlineHit: Boolean = false,
    val l5ResolvedArtists: Int = 0,
    val l5ResolvedTracks: Int = 0,
    val l5ElapsedMs: Long = 0L,
    val circuitOpen: Boolean = false,
)

data class SpaceFeedEmission(
    val phase: SpaceFeedPhase,
    val shelves: List<AssembledShelf>,
    val quickPicks: List<SongItem>,
    val keepListening: List<SongItem>,
    val forgottenFavorites: List<SongItem>,
    val stats: SpaceRefreshStats,
)

class SpaceRecEngine(
    private val dao: SpaceRecDao,
    private val resolver: SpaceTagResolver,
    private val config: SpaceRecConfig = SpaceRecConfig.Default,
    private val clock: () -> Long = ::getCurrentTimeMillis,
    private val random: Random = Random.Default,
    private val youtubeSource: SpaceYouTubeSource = GlobalSpaceYouTubeSource,
    private val isBlocked: (SongItem) -> Boolean = BlockedContent::isBlocked,
    private val textLanguageDetector: TextLanguageDetector = NoOpTextLanguageDetector,
) {
    private val provenanceMutex = Mutex()
    private val provenanceByVideoId = LinkedHashMap<String, TrackProvenance>()

    fun refresh(
        spaceId: String,
        filter: SpaceFilterSpec,
        homeInput: SpaceHomeInput,
        forceNetwork: Boolean = false,
    ): Flow<SpaceFeedEmission> =
        channelFlow {
            val normalizedFilter = filter.normalized()
            // Spec 0b: inactive filter never enters the engine.
            if (!normalizedFilter.isActive) return@channelFlow

            val refreshStartMs = clock()

            // --- Step 1: Fast CACHE emission (< 300ms) ---
            val cachedRow =
                withTimeoutOrNull(config.firstPaintFromCacheMs) {
                    runCatchingNonCancellable { dao.getFeedCache(spaceId) }
                }
            val cachedPayload = cachedRow?.let { SpaceRecDbAdapter.decodeCachedPayload(it.payloadJson) }
            val cacheAgeMs = cachedRow?.let { (clock() - it.savedAt).coerceAtLeast(0L) }
            val cacheIsFresh = cacheAgeMs != null && cacheAgeMs <= config.cacheTtlMs

            if (cachedPayload != null && !forceNetwork) {
                recordCachedProvenance(spaceId, cachedPayload)
                val cacheStats =
                    SpaceRefreshStats(
                        spaceId = spaceId,
                        phase = SpaceFeedPhase.CACHE,
                        elapsedMs = (clock() - refreshStartMs).coerceAtLeast(0L),
                        cacheHit = true,
                        assembledCount = cachedPayload.shelves.sumOf { it.songs.size },
                    )
                send(
                    SpaceFeedEmission(
                        phase = SpaceFeedPhase.CACHE,
                        shelves = cachedPayload.shelves,
                        quickPicks = cachedPayload.quickPicks,
                        keepListening = cachedPayload.keepListening,
                        forgottenFavorites = cachedPayload.forgottenFavorites,
                        stats = cacheStats,
                    ),
                )
                if (cacheIsFresh) {
                    logSummary(cacheStats)
                    return@channelFlow
                }
            }

            // --- Step 2: Load DB inputs (history, exclusions, affinity, seed arms) ---
            val nextRefreshSeq = (cachedRow?.refreshSeq ?: 0) + 1
            val dbInputs = loadDbInputs(spaceId, nextRefreshSeq, refreshStartMs)

            // Build initial candidates from primary Home sections + local history shelves.
            val candidateMutex = Mutex()
            val candidatePool = mutableListOf<CandidateTrack>()
            val homeShelves = mutableListOf<HomeShelfSeed>()
            val homeCards = mutableListOf<HomeCardRef>()

            ingestHomeSections(
                sections = homeInput.primarySections,
                candidatePool = candidatePool,
                homeShelves = homeShelves,
                homeCards = homeCards,
            )

            val allSeedArms =
                buildEligibleSeedArms(
                    spaceId = spaceId,
                    existingArms = dbInputs.seedArms,
                    plays = dbInputs.plays,
                    primaryCandidates = candidatePool,
                    nowMs = refreshStartMs,
                )
            val eligibleArms = filterConflictingSeedArms(allSeedArms, normalizedFilter, refreshStartMs)
            val stageASeeds =
                ThompsonSampler.selectSeeds(
                    arms = eligibleArms,
                    currentRefreshSeq = nextRefreshSeq,
                    count = config.seedsPerRefresh,
                    config = config,
                    random = random,
                )
            val usedSeedArms = LinkedHashMap<String, SeedArmState>()
            stageASeeds.forEach { usedSeedArms[it.artistId] = it }

            val topAffinityArtistIds =
                selectTopAffinityArtists(
                    affinityTable = dbInputs.affinityTable,
                    filter = normalizedFilter,
                    nowMs = refreshStartMs,
                )

            val radioSemaphore = Semaphore(config.radioConcurrency)

            // --- Step 3: Stage A (parallel A1 + A2 + A3, bounded by stageADeadlineMs) ---
            val remainingStageAMs = (config.stageADeadlineMs - (clock() - refreshStartMs)).coerceAtLeast(1L)
            val stageACompleted =
                withTimeoutOrNull(remainingStageAMs) {
                    supervisorScope {
                        // A1: Home continuations (up to 3 pages, loadAllPages = false).
                        if (!homeInput.continuation.isNullOrBlank() && config.homeContinuationsMax > 0) {
                            launch {
                                var token: String? = homeInput.continuation
                                var pagesLoaded = 0
                                while (!token.isNullOrBlank() && pagesLoaded < config.homeContinuationsMax) {
                                    val result =
                                        withTimeoutOrNull(config.perCallTimeoutMs) {
                                            runCatchingNonCancellable {
                                                youtubeSource.getHomeSectionsContinuation(token!!, loadAllPages = false)
                                            }
                                        } ?: break
                                    val (sections, nextToken) = result
                                    if (sections.isNotEmpty()) {
                                        candidateMutex.withLock {
                                            ingestHomeSections(sections, candidatePool, homeShelves, homeCards)
                                        }
                                    }
                                    token = nextToken
                                    pagesLoaded++
                                }
                            }
                        }

                        // A2: Seeded radios (startRadioQueue + 1 continueRadioQueue).
                        for (seed in stageASeeds) {
                            launch {
                                val tracks = fetchSeededRadio(seed, radioSemaphore)
                                if (tracks.isNotEmpty()) {
                                    candidateMutex.withLock {
                                        candidatePool.addAll(tracks)
                                    }
                                }
                            }
                        }

                        // A3: Artist radios for top 2 highest-affinity artists.
                        for (artistId in topAffinityArtistIds) {
                            launch {
                                val songs =
                                    radioSemaphore
                                        .withPermit {
                                            withTimeoutOrNull(config.perCallTimeoutMs) {
                                                runCatchingNonCancellable { youtubeSource.startArtistRadio(artistId) }
                                            }
                                        }.orEmpty()
                                if (songs.isNotEmpty()) {
                                    val mapped =
                                        songs.map { song ->
                                            CandidateTrack(
                                                song = song,
                                                sources = setOf(CandidateSource.ARTIST_RADIO),
                                                seedArtistId = artistId,
                                            )
                                        }
                                    candidateMutex.withLock {
                                        candidatePool.addAll(mapped)
                                    }
                                }
                            }
                        }
                    }
                    true
                }
            val stageATimeout = stageACompleted == null

            // Emit PARTIAL right after Stage A completes or hits stageADeadlineMs.
            val stageASnapshot = candidateMutex.withLock { candidatePool.toList() }
            val stageAShelvesSnapshot = candidateMutex.withLock { homeShelves.toList() }
            val partialEval =
                evaluateAndAssemble(
                    candidates = stageASnapshot,
                    homeShelves = stageAShelvesSnapshot,
                    homeInput = homeInput,
                    filter = normalizedFilter,
                    dbInputs = dbInputs,
                    nowMs = clock(),
                )
            recordFeedProvenance(spaceId, partialEval)
            send(
                SpaceFeedEmission(
                    phase = SpaceFeedPhase.PARTIAL,
                    shelves = partialEval.feed.shelves,
                    quickPicks = partialEval.quickPicks,
                    keepListening = partialEval.keepListening,
                    forgottenFavorites = partialEval.forgottenFavorites,
                    stats =
                        buildStats(
                            spaceId = spaceId,
                            phase = SpaceFeedPhase.PARTIAL,
                            elapsedMs = (clock() - refreshStartMs).coerceAtLeast(0L),
                            cacheHit = cachedPayload != null && !forceNetwork,
                            eval = partialEval,
                            stageATimeout = stageATimeout,
                            hardDeadlineHit = false,
                        ),
                ),
            )

            // --- Step 4: Stage B (additional seeds + Home cards) & Stage C (cold-start search) ---
            var hardDeadlineHit = false
            var currentSurvivorCount = partialEval.gateResult.survivors.size

            // Stage B: run when survivors < feedTargetSongs and budget remains before hardDeadlineMs.
            val remainingForStageB = (config.hardDeadlineMs - (clock() - refreshStartMs)).coerceAtLeast(0L)
            if (currentSurvivorCount < config.feedTargetSongs && remainingForStageB > 0L) {
                val stageBSeeds =
                    selectStageBSeeds(
                        spaceId = spaceId,
                        eligibleArms = eligibleArms,
                        partialSurvivors = partialEval.gateResult.survivors,
                        alreadyUsedArtistIds = usedSeedArms.keys,
                        nextRefreshSeq = nextRefreshSeq,
                    )
                stageBSeeds.forEach { usedSeedArms[it.artistId] = it }

                val cardsToExpand =
                    selectHomeCards(
                        cards = candidateMutex.withLock { homeCards.toList() },
                        languages = normalizedFilter.languages,
                    )

                if (stageBSeeds.isNotEmpty() || cardsToExpand.isNotEmpty()) {
                    val stageBCompleted =
                        withTimeoutOrNull(remainingForStageB) {
                            supervisorScope {
                                for (seed in stageBSeeds) {
                                    launch {
                                        val tracks = fetchSeededRadio(seed, radioSemaphore)
                                        if (tracks.isNotEmpty()) {
                                            candidateMutex.withLock { candidatePool.addAll(tracks) }
                                        }
                                    }
                                }
                                for (card in cardsToExpand) {
                                    launch {
                                        val songs =
                                            radioSemaphore
                                                .withPermit {
                                                    withTimeoutOrNull(minOf(config.perCallTimeoutMs, remainingForStageB)) {
                                                        runCatchingNonCancellable {
                                                            when (card.kind) {
                                                                HomeCardKind.ALBUM -> youtubeSource.getAlbumSongs(card.id)
                                                                HomeCardKind.PLAYLIST -> youtubeSource.getPlaylistSongs(card.id)
                                                            }
                                                        }
                                                    }
                                                }.orEmpty()
                                        if (songs.isNotEmpty()) {
                                            val mapped =
                                                songs.map { song ->
                                                    CandidateTrack(
                                                        song = song,
                                                        sources = setOf(CandidateSource.HOME_CARD),
                                                        homeShelfTitle = card.shelfTitle,
                                                        homeShelfBrowseId = card.shelfBrowseId,
                                                    )
                                                }
                                            candidateMutex.withLock { candidatePool.addAll(mapped) }
                                        }
                                    }
                                }
                            }
                            true
                        }
                    if (stageBCompleted == null) hardDeadlineHit = true
                }

                // Re-estimate survivors before deciding whether Stage C cold-start search is needed.
                val postBSnapshot = candidateMutex.withLock { candidatePool.toList() }
                if (postBSnapshot.size > stageASnapshot.size) {
                    val postBPreload = preloadForCandidates(postBSnapshot, homeInput)
                    val postBGates = createGates(postBPreload)
                    currentSurvivorCount =
                        postBGates
                            .evaluateWithShownRelaxation(
                                postBSnapshot,
                                buildGateContext(normalizedFilter, dbInputs, clock()),
                            ).survivors.size
                }
            }

            // Stage C: cold-start search fallback when pool still below coldStartPoolThreshold (40).
            val remainingForStageC = (config.hardDeadlineMs - (clock() - refreshStartMs)).coerceAtLeast(0L)
            if (currentSurvivorCount < config.coldStartPoolThreshold && remainingForStageC > 0L) {
                val queries = buildColdStartQueries(normalizedFilter)
                if (queries.isNotEmpty()) {
                    val stageCCompleted =
                        withTimeoutOrNull(remainingForStageC) {
                            supervisorScope {
                                for (query in queries) {
                                    launch {
                                        val searchSongs =
                                            radioSemaphore
                                                .withPermit {
                                                    withTimeoutOrNull(minOf(config.perCallTimeoutMs, remainingForStageC)) {
                                                        runCatchingNonCancellable { youtubeSource.searchSongs(query) }
                                                    }
                                                }.orEmpty()
                                        if (searchSongs.isEmpty()) return@launch

                                        val searchCandidates =
                                            searchSongs.map { song ->
                                                CandidateTrack(song = song, sources = setOf(CandidateSource.SEARCH))
                                            }
                                        candidateMutex.withLock { candidatePool.addAll(searchCandidates) }

                                        val highTrustSeeds =
                                            searchSongs
                                                .filter { song ->
                                                    val known = SpaceGates.isSongArtistKnown(song, dbInputs.userKnownArtistIds)
                                                    TrustScorer.score(song, known, config) >= config.coldStartTrustMin
                                                }.take(config.coldStartSeedsPerCombo)

                                        for (seedSong in highTrustSeeds) {
                                            val page =
                                                radioSemaphore.withPermit {
                                                    withTimeoutOrNull(minOf(config.perCallTimeoutMs, remainingForStageC)) {
                                                        runCatchingNonCancellable {
                                                            youtubeSource.startRadioQueue(seedSong.id, "RDAMVM${seedSong.id}")
                                                        }
                                                    }
                                                }
                                            val radioSongs = page?.songs.orEmpty()
                                            if (radioSongs.isNotEmpty()) {
                                                val seedArtist = seedSong.artists.firstOrNull()
                                                val mapped =
                                                    radioSongs.map { song ->
                                                        CandidateTrack(
                                                            song = song,
                                                            sources = setOf(CandidateSource.SEED_RADIO),
                                                            seedArtistId = seedArtist?.id,
                                                            seedArtistName = seedArtist?.name,
                                                        )
                                                    }
                                                candidateMutex.withLock { candidatePool.addAll(mapped) }
                                            }
                                        }
                                    }
                                }
                            }
                            true
                        }
                    if (stageCCompleted == null) hardDeadlineHit = true
                }
            }

            // --- Step 5: FINAL evaluation, persistence, provenance, and logging ---
            val finalCandidates = candidateMutex.withLock { candidatePool.toList() }
            val finalHomeShelves = candidateMutex.withLock { homeShelves.toList() }
            val finalNowMs = clock()
            val finalEval =
                evaluateAndAssemble(
                    candidates = finalCandidates,
                    homeShelves = finalHomeShelves,
                    homeInput = homeInput,
                    filter = normalizedFilter,
                    dbInputs = dbInputs,
                    nowMs = finalNowMs,
                )

            recordFeedProvenance(spaceId, finalEval)
            persistFinalState(
                spaceId = spaceId,
                refreshSeq = nextRefreshSeq,
                nowMs = finalNowMs,
                eval = finalEval,
                usedSeedArms = usedSeedArms.values.toList(),
            )

            val finalStats =
                buildStats(
                    spaceId = spaceId,
                    phase = SpaceFeedPhase.FINAL,
                    elapsedMs = (clock() - refreshStartMs).coerceAtLeast(0L),
                    cacheHit = cachedPayload != null && !forceNetwork,
                    eval = finalEval,
                    stageATimeout = stageATimeout,
                    hardDeadlineHit = hardDeadlineHit,
                )
            logSummary(finalStats)

            send(
                SpaceFeedEmission(
                    phase = SpaceFeedPhase.FINAL,
                    shelves = finalEval.feed.shelves,
                    quickPicks = finalEval.quickPicks,
                    keepListening = finalEval.keepListening,
                    forgottenFavorites = finalEval.forgottenFavorites,
                    stats = finalStats,
                ),
            )

            // --- Step 6: Background L5 resolution (never blocks CACHE / PARTIAL / FINAL) ---
            val remainingL5Ms = (config.l5WindowMs - (clock() - refreshStartMs)).coerceAtLeast(0L)
            if (remainingL5Ms > 0L && resolver.isAvailable && !resolver.isCircuitOpen) {
                val l5StartedAt = clock()
                val l5Outcome =
                    runL5Resolution(
                        ranked = finalEval.ranked,
                        filter = normalizedFilter,
                        windowMs = remainingL5Ms,
                    )
                val l5ElapsedMs = (clock() - l5StartedAt).coerceAtLeast(0L)

                if (l5Outcome.writtenRecords > 0) {
                    val updatedNowMs = clock()
                    val resolvedEval =
                        evaluateAndAssemble(
                            candidates = finalCandidates,
                            homeShelves = finalHomeShelves,
                            homeInput = homeInput,
                            filter = normalizedFilter,
                            dbInputs = dbInputs,
                            nowMs = updatedNowMs,
                        )
                    val verdictsChanged =
                        resolvedEval.gateResult.stats.acceptCount != finalEval.gateResult.stats.acceptCount ||
                            resolvedEval.gateResult.stats.rejectCount != finalEval.gateResult.stats.rejectCount ||
                            resolvedEval.gateResult.stats.maybeCount != finalEval.gateResult.stats.maybeCount ||
                            resolvedEval.feed.songs.map { it.song.id } != finalEval.feed.songs.map { it.song.id }

                    if (verdictsChanged) {
                        recordFeedProvenance(spaceId, resolvedEval)
                        persistFeedCacheOnly(spaceId, nextRefreshSeq, updatedNowMs, resolvedEval)
                        val resolvedStats =
                            buildStats(
                                spaceId = spaceId,
                                phase = SpaceFeedPhase.RESOLVED_UPDATE,
                                elapsedMs = (clock() - refreshStartMs).coerceAtLeast(0L),
                                cacheHit = false,
                                eval = resolvedEval,
                                stageATimeout = stageATimeout,
                                hardDeadlineHit = hardDeadlineHit,
                                l5ResolvedArtists = l5Outcome.resolvedArtists,
                                l5ResolvedTracks = l5Outcome.resolvedTracks,
                                l5ElapsedMs = l5ElapsedMs,
                            )
                        send(
                            SpaceFeedEmission(
                                phase = SpaceFeedPhase.RESOLVED_UPDATE,
                                shelves = resolvedEval.feed.shelves,
                                quickPicks = resolvedEval.quickPicks,
                                keepListening = resolvedEval.keepListening,
                                forgottenFavorites = resolvedEval.forgottenFavorites,
                                stats = resolvedStats,
                            ),
                        )
                    }
                }

                Logger.i(
                    LOG_TAG,
                    "L5 done spaceId=$spaceId resolvedArtists=${l5Outcome.resolvedArtists} " +
                        "resolvedTracks=${l5Outcome.resolvedTracks} written=${l5Outcome.writtenRecords} " +
                        "elapsedMs=$l5ElapsedMs circuitOpen=${resolver.isCircuitOpen}",
                )
            }
        }

    /**
     * Records a completed or skipped local play for Space provenance and bandit learning.
     * Writes a `space_play_event` row ONLY when [videoId] has active Space provenance,
     * and updates `space_seed_arm` via [ThompsonSampler.recordOutcome].
     */
    suspend fun recordPlay(
        videoId: String,
        playedMs: Long,
        durationMs: Long? = null,
    ) {
        if (videoId.isBlank()) return
        val provenance = provenanceMutex.withLock { provenanceByVideoId[videoId] } ?: return
        val nowMs = clock()
        val resolvedDurationMs =
            durationMs?.takeIf { it > 0L }
                ?: provenance.durationMs?.takeIf { it > 0L }
                ?: runCatching { dao.getSongDurationSeconds(videoId) }
                    .getOrNull()
                    ?.takeIf { it > 0 }
                    ?.toLong()
                    ?.times(1000L)

        runCatching {
            dao.insertPlayEvent(
                SpacePlayEventEntity(
                    videoId = videoId,
                    spaceId = provenance.spaceId,
                    seedArtistId = provenance.seedArtistId,
                    source = provenance.source.name,
                    playedAt = nowMs,
                ),
            )
        }

        val seedArtistId = provenance.seedArtistId ?: return
        runCatching {
            val armEntity = dao.getSeedArm(provenance.spaceId, seedArtistId) ?: return
            val play =
                PlayRecord(
                    videoId = videoId,
                    artistIds = listOf(seedArtistId),
                    playedMs = playedMs,
                    durationMs = resolvedDurationMs,
                    playedAtMs = nowMs,
                )
            val updated = ThompsonSampler.recordOutcome(armEntity.toState(), play, config)
            dao.upsertSeedArm(updated.toEntity())
        }
    }

    internal suspend fun provenanceFor(videoId: String): TrackProvenance? = provenanceMutex.withLock { provenanceByVideoId[videoId] }

    private suspend fun loadDbInputs(
        spaceId: String,
        nextRefreshSeq: Int,
        nowMs: Long,
    ): LoadedDbInputs {
        val historyRows = runCatching { dao.getPlayHistoryWithDurationSince(0L) }.getOrDefault(emptyList())
        val plays = SpaceRecDbAdapter.groupPlayHistory(historyRows)
        val everPlayedIds = runCatching { dao.getAllEverPlayedVideoIds().toSet() }.getOrDefault(emptySet())
        val recentlyHeardIds = SpaceGates.recentlyHeardIds(plays, nowMs, config)
        val userKnownArtistIds = SpaceRanker.userKnownArtistIds(plays, config)
        val affinityTable = SpaceRanker.buildArtistAffinityTable(plays, nowMs, config)
        val minShownSeq = (nextRefreshSeq - config.shownExclusionRefreshes + 1).coerceAtLeast(1)
        val recentlyShownIds = runCatching { dao.getRecentlyShownVideoIds(spaceId, minShownSeq).toSet() }.getOrDefault(emptySet())
        val seedArms = runCatching { dao.getSeedArms(spaceId).map { it.toState() } }.getOrDefault(emptyList())
        return LoadedDbInputs(
            plays = plays,
            everPlayedIds = everPlayedIds,
            recentlyHeardIds = recentlyHeardIds,
            userKnownArtistIds = userKnownArtistIds,
            affinityTable = affinityTable,
            recentlyShownIds = recentlyShownIds,
            seedArms = seedArms,
        )
    }

    private fun ingestHomeSections(
        sections: List<HomeSection>,
        candidatePool: MutableList<CandidateTrack>,
        homeShelves: MutableList<HomeShelfSeed>,
        homeCards: MutableList<HomeCardRef>,
    ) {
        val seenKeys = homeShelves.mapTo(hashSetOf()) { it.key }
        for ((index, section) in sections.withIndex()) {
            val shelfSongs = mutableListOf<String>()
            for (item in section.items) {
                when (item) {
                    is SongItem -> {
                        shelfSongs += item.id
                        candidatePool +=
                            CandidateTrack(
                                song = item,
                                sources = setOf(CandidateSource.HOME),
                                homeShelfTitle = section.title,
                                homeShelfBrowseId = section.browseId,
                            )
                    }

                    is AlbumItem -> {
                        val titleAndArtist =
                            buildString {
                                append(item.title)
                                if (item.artists.isNotEmpty()) {
                                    append(' ')
                                    append(item.artists.joinToString(" ") { it.name })
                                }
                            }
                        homeCards +=
                            HomeCardRef(
                                id = item.id,
                                kind = HomeCardKind.ALBUM,
                                titleAndArtist = titleAndArtist,
                                shelfTitle = section.title,
                                shelfBrowseId = section.browseId,
                            )
                    }

                    is PlaylistItem -> {
                        val titleAndAuthor = listOfNotNull(item.title, item.author).joinToString(" ")
                        homeCards +=
                            HomeCardRef(
                                id = item.id,
                                kind = HomeCardKind.PLAYLIST,
                                titleAndArtist = titleAndAuthor,
                                shelfTitle = section.title,
                                shelfBrowseId = section.browseId,
                            )
                    }

                    else -> {
                        Unit
                    }
                }
            }
            if (shelfSongs.isNotEmpty()) {
                val baseKey = section.browseId?.takeIf(String::isNotBlank) ?: "home_${section.title.lowercase()}_$index"
                val uniqueKey = if (baseKey in seenKeys) "${baseKey}_${homeShelves.size}" else baseKey
                seenKeys += uniqueKey
                homeShelves +=
                    HomeShelfSeed(
                        key = uniqueKey,
                        title = section.title,
                        browseId = section.browseId,
                        survivingSongIds = shelfSongs.distinct(),
                    )
            }
        }
    }

    private fun buildEligibleSeedArms(
        spaceId: String,
        existingArms: List<SeedArmState>,
        plays: List<PlayRecord>,
        primaryCandidates: List<CandidateTrack>,
        nowMs: Long,
    ): List<SeedArmState> {
        val armsByArtist = LinkedHashMap<String, SeedArmState>()
        for (arm in existingArms) {
            armsByArtist[arm.artistId] = arm
        }

        val cutoffMs = nowMs - config.seedEligibilityDays.toLong() * SpaceRecConfig.MILLIS_PER_DAY
        for (play in plays) {
            if (play.playedAtMs < cutoffMs || !play.isCompleted(config)) continue
            val primaryArtistId = play.artistIds.firstOrNull()?.takeIf(String::isNotBlank) ?: continue
            if (primaryArtistId !in armsByArtist) {
                armsByArtist[primaryArtistId] =
                    SeedArmState(
                        spaceId = spaceId,
                        artistId = primaryArtistId,
                        seedVideoId = play.videoId,
                    )
            }
        }

        // Supplement with top Home song seeds if history has few seeds.
        var homeSeedsAdded = 0
        for (candidate in primaryCandidates) {
            if (homeSeedsAdded >= config.homeTopSeedsLimit) break
            val artistId = candidate.primaryArtistId ?: continue
            if (artistId !in armsByArtist) {
                armsByArtist[artistId] =
                    SeedArmState(
                        spaceId = spaceId,
                        artistId = artistId,
                        seedVideoId = candidate.song.id,
                    )
                homeSeedsAdded++
            }
        }

        return armsByArtist.values.toList()
    }

    private suspend fun filterConflictingSeedArms(
        arms: List<SeedArmState>,
        filter: SpaceFilterSpec,
        nowMs: Long,
    ): List<SeedArmState> {
        if (arms.isEmpty()) return emptyList()
        val preloaded =
            SpaceRecDbAdapter.preload(
                dao = dao,
                videoIds = arms.map { it.seedVideoId },
                artistIds = arms.map { it.artistId },
            )
        val langTargets = SpaceLanguageRegistry.parseTargets(filter.languages)
        return arms.filter { arm ->
            val profile = preloaded.artistProfiles[arm.artistId]
            val settledLang = profile?.settledLanguage(nowMs, config)
            if (!langTargets.isEmpty && settledLang != null && !langTargets.matchesCode(settledLang)) {
                return@filter false
            }
            val settledGenre = profile?.settledGenre(nowMs, config)
            if (filter.genres.isNotEmpty() && settledGenre != null) {
                val genreOk =
                    filter.genres.any { req ->
                        settledGenre.equals(req, ignoreCase = true) ||
                            SpaceFilterTaxonomy.tagsMatchRequestedGenre(listOf(settledGenre), req)
                    }
                if (!genreOk) return@filter false
            }
            val track = preloaded.trackClasses[arm.seedVideoId]
            val trackLang = track?.language?.takeIf(String::isNotBlank)
            if (!langTargets.isEmpty && trackLang != null && !langTargets.matchesCode(trackLang)) {
                return@filter false
            }
            true
        }
    }

    private suspend fun selectTopAffinityArtists(
        affinityTable: ArtistAffinityTable,
        filter: SpaceFilterSpec,
        nowMs: Long,
    ): List<String> {
        if (affinityTable.scoresByArtistId.isEmpty() || config.artistRadioTopArtists <= 0) return emptyList()
        val rankedIds =
            affinityTable.scoresByArtistId.entries
                .sortedByDescending { it.value }
                .take(config.artistRadioTopArtists * 4)
                .map { it.key }
        val preloaded = SpaceRecDbAdapter.preload(dao, emptyList(), rankedIds)
        val langTargets = SpaceLanguageRegistry.parseTargets(filter.languages)

        return rankedIds
            .filter { artistId ->
                val profile = preloaded.artistProfiles[artistId] ?: return@filter true
                val settledLang = profile.settledLanguage(nowMs, config)
                if (!langTargets.isEmpty && settledLang != null && !langTargets.matchesCode(settledLang)) {
                    return@filter false
                }
                val settledGenre = profile.settledGenre(nowMs, config)
                if (filter.genres.isNotEmpty() && settledGenre != null) {
                    val ok =
                        filter.genres.any { req ->
                            settledGenre.equals(req, ignoreCase = true) ||
                                SpaceFilterTaxonomy.tagsMatchRequestedGenre(listOf(settledGenre), req)
                        }
                    if (!ok) return@filter false
                }
                true
            }.take(config.artistRadioTopArtists)
    }

    private suspend fun fetchSeededRadio(
        seed: SeedArmState,
        semaphore: Semaphore,
    ): List<CandidateTrack> {
        val page1 =
            semaphore.withPermit {
                withTimeoutOrNull(config.perCallTimeoutMs) {
                    runCatchingNonCancellable {
                        youtubeSource.startRadioQueue(seed.seedVideoId, "RDAMVM${seed.seedVideoId}")
                    }
                }
            } ?: return emptyList()

        val allSongs = mutableListOf<SongItem>()
        allSongs.addAll(page1.songs)

        val token = page1.continuation
        if (!token.isNullOrBlank() && config.radioPagesPerSeed >= 2) {
            val page2 =
                semaphore.withPermit {
                    withTimeoutOrNull(config.perCallTimeoutMs) {
                        runCatchingNonCancellable { youtubeSource.continueRadioQueue(token) }
                    }
                }
            if (page2 != null) {
                allSongs.addAll(page2.songs)
            }
        }

        val seedArtistName =
            allSongs
                .firstOrNull { song -> song.artists.any { it.id == seed.artistId } }
                ?.artists
                ?.firstOrNull { it.id == seed.artistId }
                ?.name

        return allSongs.map { song ->
            CandidateTrack(
                song = song,
                sources = setOf(CandidateSource.SEED_RADIO),
                seedArtistId = seed.artistId,
                seedArtistName = seedArtistName,
                seedLanguageAccepted = true,
                seedGenreAccepted = true,
            )
        }
    }

    private fun selectStageBSeeds(
        spaceId: String,
        eligibleArms: List<SeedArmState>,
        partialSurvivors: List<GatedCandidate>,
        alreadyUsedArtistIds: Set<String>,
        nextRefreshSeq: Int,
    ): List<SeedArmState> {
        val unusedArms = eligibleArms.filter { it.artistId !in alreadyUsedArtistIds }
        val picked =
            ThompsonSampler
                .selectSeeds(
                    arms = unusedArms,
                    currentRefreshSeq = nextRefreshSeq,
                    count = config.stageBSeedsCount,
                    config = config,
                    random = random,
                ).toMutableList()
        val pickedArtists = (alreadyUsedArtistIds + picked.map { it.artistId }).toMutableSet()

        if (picked.size < config.stageBSeedsCount) {
            for (survivor in partialSurvivors.sortedByDescending { it.trust }) {
                if (picked.size >= config.stageBSeedsCount) break
                val artistId = survivor.candidate.primaryArtistId ?: continue
                if (artistId in pickedArtists) continue
                picked +=
                    SeedArmState(
                        spaceId = spaceId,
                        artistId = artistId,
                        seedVideoId = survivor.candidate.song.id,
                    )
                pickedArtists += artistId
            }
        }
        return picked
    }

    private suspend fun selectHomeCards(
        cards: List<HomeCardRef>,
        languages: List<String>,
    ): List<HomeCardRef> {
        if (cards.isEmpty() || config.homeCardsMax <= 0) return emptyList()
        val cascade =
            LanguageCascade(
                trackClassLookup = EmptyTrackLookup,
                artistProfileLookup = EmptyArtistLookup,
                textLanguageDetector = textLanguageDetector,
                config = config,
            )
        val accepted = mutableListOf<HomeCardRef>()
        val seenIds = hashSetOf<String>()
        for (card in cards) {
            if (accepted.size >= config.homeCardsMax) break
            if (!seenIds.add(card.id)) continue
            val verdict = cascade.evaluateText(card.titleAndArtist, languages).verdict
            if (verdict != GateVerdict.REJECT) {
                accepted += card
            }
        }
        return accepted
    }

    private fun buildColdStartQueries(filter: SpaceFilterSpec): List<String> {
        val langLabels =
            filter.languages
                .filterNot { SpaceLanguageRegistry.isInstrumental(it) }
                .map { slug -> SpaceFilterTaxonomy.languageOption(slug)?.searchQueryTerm ?: slug }
        val genreLabels =
            filter.genres.map { slug ->
                SpaceFilterTaxonomy.genreOption(slug)?.searchQueryTerm ?: slug
            }
        val queries = linkedSetOf<String>()
        when {
            langLabels.isNotEmpty() && genreLabels.isNotEmpty() -> {
                for (lang in langLabels.take(2)) {
                    for (genre in genreLabels.take(2)) {
                        queries += "$lang $genre"
                    }
                }
            }

            langLabels.isNotEmpty() -> {
                for (lang in langLabels.take(3)) {
                    queries += "$lang songs"
                }
            }

            genreLabels.isNotEmpty() -> {
                for (genre in genreLabels.take(3)) {
                    queries += "$genre songs"
                }
            }
        }
        return queries.toList()
    }

    private suspend fun preloadForCandidates(
        candidates: List<CandidateTrack>,
        homeInput: SpaceHomeInput,
    ): PreloadedSpaceMetadata {
        val totalCount =
            candidates.size +
                homeInput.localQuickPicks.size +
                homeInput.localKeepListening.size +
                homeInput.localForgottenFavorites.size
        val allSongs =
            buildList(totalCount) {
                candidates.forEach { add(it.song) }
                addAll(homeInput.localQuickPicks)
                addAll(homeInput.localKeepListening)
                addAll(homeInput.localForgottenFavorites)
            }
        val videoIds = allSongs.map { it.id }
        val artistIds = allSongs.flatMap { song -> song.artists.mapNotNull { it.id } }
        return SpaceRecDbAdapter.preload(dao, videoIds, artistIds)
    }

    private fun createGates(preloaded: PreloadedSpaceMetadata): SpaceGates {
        val cascade =
            LanguageCascade(
                trackClassLookup = preloaded.trackClassLookup,
                artistProfileLookup = preloaded.artistProfileLookup,
                textLanguageDetector = textLanguageDetector,
                config = config,
            )
        return SpaceGates(
            trackClassLookup = preloaded.trackClassLookup,
            languageCascade = cascade,
            artistProfileLookup = preloaded.artistProfileLookup,
            config = config,
        )
    }

    private fun buildGateContext(
        filter: SpaceFilterSpec,
        dbInputs: LoadedDbInputs,
        nowMs: Long,
        relaxShown: Boolean = false,
    ): GateContext =
        GateContext(
            nowMs = nowMs,
            filter = filter,
            userKnownArtistIds = dbInputs.userKnownArtistIds,
            recentlyHeardVideoIds = dbInputs.recentlyHeardIds,
            recentlyShownVideoIds = dbInputs.recentlyShownIds,
            isBlocked = isBlocked,
            relaxShownExclusion = relaxShown,
        )

    private suspend fun evaluateAndAssemble(
        candidates: List<CandidateTrack>,
        homeShelves: List<HomeShelfSeed>,
        homeInput: SpaceHomeInput,
        filter: SpaceFilterSpec,
        dbInputs: LoadedDbInputs,
        nowMs: Long,
    ): EvaluationSnapshot {
        val preloaded = preloadForCandidates(candidates, homeInput)
        val gates = createGates(preloaded)
        val gateContext = buildGateContext(filter, dbInputs, nowMs)
        val gateResult = gates.evaluateWithShownRelaxation(candidates, gateContext)
        val ranked =
            SpaceRanker.rank(
                candidates = gateResult.survivors,
                affinityTable = dbInputs.affinityTable,
                everPlayedVideoIds = dbInputs.everPlayedIds,
                config = config,
            )
        val assembled =
            FeedAssembler.assemble(
                ranked = ranked,
                homeShelves = homeShelves,
                shelfLabels = homeInput.shelfLabels,
                recentlyHeardVideoIds = dbInputs.recentlyHeardIds,
                config = config,
                random = random,
            )

        // Local history shelves are exempt from heard exclusion (CandidateSource.LOCAL_HISTORY) and shown exclusion.
        val localContext = gateContext.copy(relaxShownExclusion = true)
        val gatedQuickPicks = gateLocalShelf(homeInput.localQuickPicks, gates, localContext)
        val gatedKeepListening = gateLocalShelf(homeInput.localKeepListening, gates, localContext)
        val gatedForgotten = gateLocalShelf(homeInput.localForgottenFavorites, gates, localContext)

        return EvaluationSnapshot(
            gateResult = gateResult,
            ranked = ranked,
            feed = assembled,
            quickPicks = gatedQuickPicks,
            keepListening = gatedKeepListening,
            forgottenFavorites = gatedForgotten,
        )
    }

    private suspend fun gateLocalShelf(
        songs: List<SongItem>,
        gates: SpaceGates,
        context: GateContext,
    ): List<SongItem> {
        if (songs.isEmpty()) return emptyList()
        val candidates = songs.map { CandidateTrack(song = it, sources = setOf(CandidateSource.LOCAL_HISTORY)) }
        val result = gates.evaluate(candidates, context)
        return result.survivors.map { it.candidate.song }
    }

    private suspend fun runL5Resolution(
        ranked: List<RankedCandidate>,
        filter: SpaceFilterSpec,
        windowMs: Long,
    ): L5Outcome {
        val unresolved = ranked.filter { !it.gated.isFullyAccepted }
        if (unresolved.isEmpty()) return L5Outcome()

        // Group unresolved tracks by distinct artist and order:
        // 1. MAYBE-high-priority first, 2. highest rank score descending.
        val byArtist =
            unresolved
                .groupBy { item ->
                    item.candidate.primaryArtistId
                        ?: item.song.artists
                            .firstOrNull()
                            ?.name
                            ?.trim()
                            ?.lowercase()
                            .orEmpty()
                }.filterKeys(String::isNotBlank)
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<RankedCandidate>>> { (_, list) ->
                        list.any { it.gated.highPriorityForL5 }
                    }.thenByDescending { (_, list) ->
                        list.maxOf { it.score }
                    },
                ).take(config.maxNetworkClassifyPerRefresh)

        if (byArtist.isEmpty()) return L5Outcome()

        val l5Semaphore = Semaphore(config.classifyNetworkConcurrency)
        val countersMutex = Mutex()
        var resolvedArtists = 0
        var resolvedTracks = 0
        var writtenRecords = 0

        withTimeoutOrNull(windowMs) {
            supervisorScope {
                byArtist
                    .map { (_, artistTracks) ->
                        async {
                            if (resolver.isCircuitOpen) return@async
                            val sampleSong = artistTracks.first().song
                            val artist = sampleSong.artists.firstOrNull() ?: return@async
                            val artistId = artist.id?.takeIf(String::isNotBlank)
                            val artistName = artist.name

                            val artistRes =
                                l5Semaphore.withPermit {
                                    withTimeoutOrNull(config.perCallTimeoutMs) {
                                        runCatchingNonCancellable {
                                            resolver.resolveArtist(artistId, artistName, filter)
                                        }
                                    }
                                } ?: return@async

                            val nowMs = clock()
                            var localWritten = 0
                            var localTracks = 0

                            val canonicalLangs =
                                artistRes.detectedLanguages.map { lang ->
                                    SpaceLanguageRegistry.canonicalCode(lang) ?: lang
                                }

                            // Persist artist-level language & genre votes (weight 1.5).
                            if (artistId != null &&
                                (canonicalLangs.isNotEmpty() || artistRes.detectedGenres.isNotEmpty())
                            ) {
                                val existing =
                                    runCatching { dao.getArtistProfile(artistId)?.toRecord() }.getOrNull()
                                        ?: ArtistProfileRecord(
                                            artistId = artistId,
                                            name = artistName,
                                            languageVotes = emptyMap(),
                                            totalVotes = 0.0,
                                            updatedAt = nowMs,
                                            genreVotes = emptyMap(),
                                        )
                                var updated = existing.copy(name = artistName)
                                for (lang in canonicalLangs) {
                                    updated = updated.addVote(lang, config.voteWeightNetwork, nowMs)
                                }
                                for (genre in artistRes.detectedGenres) {
                                    updated = updated.addGenreVote(genre, config.voteWeightNetwork, nowMs)
                                }
                                runCatching {
                                    dao.upsertArtistProfile(updated.toEntity())
                                    localWritten++
                                }
                            }

                            // Cover all artist's tracks directly if artist resolution is clean,
                            // otherwise disambiguate top tracks individually.
                            if (!artistRes.needsTrackDisambiguation &&
                                (canonicalLangs.isNotEmpty() || artistRes.detectedGenres.isNotEmpty())
                            ) {
                                val lang = canonicalLangs.firstOrNull()
                                val genre = artistRes.detectedGenres.takeIf { it.isNotEmpty() }?.joinToString(",")
                                val records =
                                    artistTracks.map { item ->
                                        TrackClassRecord(
                                            videoId = item.song.id,
                                            language = lang,
                                            languageConfidence = if (lang != null) 1.0 else 0.0,
                                            genre = genre,
                                            evidence = "lastfm_artist",
                                            checkedAt = nowMs,
                                        ).toEntity()
                                    }
                                runCatching {
                                    dao.upsertTrackClasses(records)
                                    localTracks += records.size
                                    localWritten += records.size
                                }
                            } else if (!resolver.isCircuitOpen) {
                                val trackCandidates = artistTracks.sortedByDescending { it.score }.take(2)
                                for (item in trackCandidates) {
                                    if (resolver.isCircuitOpen) break
                                    val trackRes =
                                        l5Semaphore.withPermit {
                                            withTimeoutOrNull(config.perCallTimeoutMs) {
                                                runCatchingNonCancellable {
                                                    resolver.resolveTrack(item.song, artistRes, filter)
                                                }
                                            }
                                        } ?: continue
                                    runCatching {
                                        dao.upsertTrackClass(
                                            TrackClassRecord(
                                                videoId = trackRes.videoId,
                                                language = trackRes.language,
                                                languageConfidence = trackRes.languageConfidence,
                                                genre = trackRes.genre,
                                                evidence = trackRes.evidence,
                                                checkedAt = clock(),
                                            ).toEntity(),
                                        )
                                        localTracks++
                                        localWritten++
                                    }
                                }
                            }

                            countersMutex.withLock {
                                resolvedArtists++
                                resolvedTracks += localTracks
                                writtenRecords += localWritten
                            }
                        }
                    }.awaitAll()
            }
        }

        return L5Outcome(
            resolvedArtists = resolvedArtists,
            resolvedTracks = resolvedTracks,
            writtenRecords = writtenRecords,
        )
    }

    private suspend fun persistFinalState(
        spaceId: String,
        refreshSeq: Int,
        nowMs: Long,
        eval: EvaluationSnapshot,
        usedSeedArms: List<SeedArmState>,
    ) {
        val shownSongs =
            buildSet {
                eval.feed.shelves.forEach { shelf ->
                    shelf.songs.forEach { add(it.id) }
                }
            }
        if (shownSongs.isNotEmpty()) {
            val entries =
                shownSongs.map { videoId ->
                    SpaceShownEntity(
                        spaceId = spaceId,
                        videoId = videoId,
                        refreshSeq = refreshSeq,
                        shownAt = nowMs,
                    )
                }
            runCatching {
                dao.upsertShown(entries)
                val minKeepSeq = (refreshSeq - config.shownExclusionRefreshes + 1).coerceAtLeast(1)
                dao.pruneShownBefore(spaceId, minKeepSeq)
            }
        }

        val updatedArms = usedSeedArms.map { it.copy(lastUsedSeq = refreshSeq).toEntity() }
        if (updatedArms.isNotEmpty()) {
            runCatching { dao.upsertSeedArms(updatedArms) }
        }

        persistFeedCacheOnly(spaceId, refreshSeq, nowMs, eval)
    }

    private suspend fun persistFeedCacheOnly(
        spaceId: String,
        refreshSeq: Int,
        nowMs: Long,
        eval: EvaluationSnapshot,
    ) {
        val payload =
            CachedSpaceFeedPayload(
                shelves = eval.feed.shelves,
                quickPicks = eval.quickPicks,
                keepListening = eval.keepListening,
                forgottenFavorites = eval.forgottenFavorites,
            )
        val encoded = SpaceRecDbAdapter.encodeCachedPayload(payload)
        runCatching {
            dao.upsertFeedCache(
                SpaceFeedCacheEntity(
                    spaceId = spaceId,
                    payloadJson = encoded,
                    savedAt = nowMs,
                    refreshSeq = refreshSeq,
                ),
            )
        }
    }

    private suspend fun recordFeedProvenance(
        spaceId: String,
        eval: EvaluationSnapshot,
    ) {
        provenanceMutex.withLock {
            for (item in eval.feed.songs) {
                val song = item.song
                val primarySource = item.candidate.sources.firstOrNull() ?: CandidateSource.HOME
                putProvenanceLocked(
                    song.id,
                    TrackProvenance(
                        spaceId = spaceId,
                        seedArtistId = item.candidate.seedArtistId,
                        source = primarySource,
                        durationMs = song.duration?.takeIf { it > 0L }?.times(1000L),
                    ),
                )
            }
            for (song in eval.quickPicks + eval.keepListening + eval.forgottenFavorites) {
                if (song.id !in provenanceByVideoId) {
                    putProvenanceLocked(
                        song.id,
                        TrackProvenance(
                            spaceId = spaceId,
                            seedArtistId = null,
                            source = CandidateSource.LOCAL_HISTORY,
                            durationMs = song.duration?.takeIf { it > 0L }?.times(1000L),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun recordCachedProvenance(
        spaceId: String,
        payload: CachedSpaceFeedPayload,
    ) {
        provenanceMutex.withLock {
            for (shelf in payload.shelves) {
                val source = if (shelf.isGenerated) CandidateSource.SEED_RADIO else CandidateSource.HOME
                for (song in shelf.songs) {
                    putProvenanceLocked(
                        song.id,
                        TrackProvenance(
                            spaceId = spaceId,
                            seedArtistId = null,
                            source = source,
                            durationMs = song.duration?.takeIf { it > 0L }?.times(1000L),
                        ),
                    )
                }
            }
            for (song in payload.quickPicks + payload.keepListening + payload.forgottenFavorites) {
                putProvenanceLocked(
                    song.id,
                    TrackProvenance(
                        spaceId = spaceId,
                        seedArtistId = null,
                        source = CandidateSource.LOCAL_HISTORY,
                        durationMs = song.duration?.takeIf { it > 0L }?.times(1000L),
                    ),
                )
            }
        }
    }

    private fun putProvenanceLocked(
        videoId: String,
        provenance: TrackProvenance,
    ) {
        if (provenanceByVideoId.size >= MAX_PROVENANCE_ENTRIES && videoId !in provenanceByVideoId) {
            val oldest = provenanceByVideoId.keys.firstOrNull()
            if (oldest != null) provenanceByVideoId.remove(oldest)
        }
        provenanceByVideoId[videoId] = provenance
    }

    private fun buildStats(
        spaceId: String,
        phase: SpaceFeedPhase,
        elapsedMs: Long,
        cacheHit: Boolean,
        eval: EvaluationSnapshot,
        stageATimeout: Boolean,
        hardDeadlineHit: Boolean,
        l5ResolvedArtists: Int = 0,
        l5ResolvedTracks: Int = 0,
        l5ElapsedMs: Long = 0L,
    ): SpaceRefreshStats =
        SpaceRefreshStats(
            spaceId = spaceId,
            phase = phase,
            elapsedMs = elapsedMs,
            cacheHit = cacheHit,
            candidateCount = eval.gateResult.stats.inputCount,
            dedupedCount = eval.gateResult.stats.dedupedCount,
            acceptCount = eval.gateResult.stats.acceptCount,
            rejectCount = eval.gateResult.stats.rejectCount,
            maybeCount = eval.gateResult.stats.maybeCount,
            dropsByReason = eval.gateResult.stats.drops,
            assembledCount = eval.feed.songs.size,
            familiarShare = eval.feed.familiarShare,
            uniqueArtists = eval.feed.uniqueArtistCount,
            maxPerArtist = eval.feed.maxSongsPerArtist,
            stageATimeout = stageATimeout,
            hardDeadlineHit = hardDeadlineHit,
            l5ResolvedArtists = l5ResolvedArtists,
            l5ResolvedTracks = l5ResolvedTracks,
            l5ElapsedMs = l5ElapsedMs,
            circuitOpen = resolver.isCircuitOpen,
        )

    private fun logSummary(stats: SpaceRefreshStats) {
        val familiarPct = (stats.familiarShare * 100.0).roundToInt()
        Logger.i(
            LOG_TAG,
            "refresh spaceId=${stats.spaceId} phase=${stats.phase} elapsedMs=${stats.elapsedMs} " +
                "cacheHit=${stats.cacheHit} candidates=${stats.candidateCount} deduped=${stats.dedupedCount} " +
                "accept=${stats.acceptCount} reject=${stats.rejectCount} maybe=${stats.maybeCount} " +
                "assembled=${stats.assembledCount} familiarPct=$familiarPct uniqueArtists=${stats.uniqueArtists} " +
                "maxPerArtist=${stats.maxPerArtist} stageATimeout=${stats.stageATimeout} " +
                "hardDeadlineHit=${stats.hardDeadlineHit} drops=${stats.dropsByReason}",
        )
    }

    private data class LoadedDbInputs(
        val plays: List<PlayRecord>,
        val everPlayedIds: Set<String>,
        val recentlyHeardIds: Set<String>,
        val userKnownArtistIds: Set<String>,
        val affinityTable: ArtistAffinityTable,
        val recentlyShownIds: Set<String>,
        val seedArms: List<SeedArmState>,
    )

    private enum class HomeCardKind {
        ALBUM,
        PLAYLIST,
    }

    private data class HomeCardRef(
        val id: String,
        val kind: HomeCardKind,
        val titleAndArtist: String,
        val shelfTitle: String,
        val shelfBrowseId: String?,
    )

    private data class EvaluationSnapshot(
        val gateResult: GatePipelineResult,
        val ranked: List<RankedCandidate>,
        val feed: AssembledFeed,
        val quickPicks: List<SongItem>,
        val keepListening: List<SongItem>,
        val forgottenFavorites: List<SongItem>,
    )

    private data class L5Outcome(
        val resolvedArtists: Int = 0,
        val resolvedTracks: Int = 0,
        val writtenRecords: Int = 0,
    )

    private inline fun <T> runCatchingNonCancellable(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    companion object {
        const val LOG_TAG = "SpacesRec"
        private const val MAX_PROVENANCE_ENTRIES = 2_000

        private val EmptyTrackLookup =
            object : TrackClassLookup {
                override suspend fun getTrackClass(videoId: String): TrackClassRecord? = null
            }

        private val EmptyArtistLookup =
            object : ArtistProfileLookup {
                override suspend fun getArtistProfile(artistId: String): ArtistProfileRecord? = null
            }
    }
}
