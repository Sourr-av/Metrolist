/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.settings.SpaceFilterSpec
import com.metrolist.shared.spaces.SpaceFilterTaxonomy

enum class GateDropReason {
    STRUCTURAL_BLANK_ID,
    STRUCTURAL_PODCAST,
    PERMANENT_BLOCKED,
    HEARD_RECENTLY,
    CURRENTLY_QUEUED,
    SHOWN_RECENTLY,
    TRUST_BELOW_GATE,
    LANGUAGE_REJECTED,
    GENRE_REJECTED,
}

data class GateStats(
    val inputCount: Int,
    val dedupedCount: Int,
    val drops: Map<GateDropReason, Int>,
    val acceptCount: Int,
    val rejectCount: Int,
    val maybeCount: Int,
    val shownExclusionRelaxed: Boolean = false,
)

data class GatePipelineResult(
    val survivors: List<GatedCandidate>,
    val stats: GateStats,
)

data class GateContext(
    val nowMs: Long,
    val filter: SpaceFilterSpec,
    val userKnownArtistIds: Set<String> = emptySet(),
    val recentlyHeardVideoIds: Set<String> = emptySet(),
    val currentlyQueuedVideoIds: Set<String> = emptySet(),
    val recentlyShownVideoIds: Set<String> = emptySet(),
    val isBlocked: (SongItem) -> Boolean = { false },
    val relaxShownExclusion: Boolean = false,
)

/**
 * Stage 2 gates (cheapest first, over the full candidate pool, zero network calls awaited).
 * Also implements the empty-constraint bypass (trust gate + heard exclusion only).
 */
class SpaceGates(
    private val trackClassLookup: TrackClassLookup,
    private val languageCascade: LanguageCascade,
    private val artistProfileLookup: ArtistProfileLookup = NoOpArtistProfileLookup,
    private val config: SpaceRecConfig = SpaceRecConfig.Default,
) {
    /**
     * Space with empty genres AND empty languages:
     * return the Home pipeline songs unchanged except for the trust gate.
     * Home songs are exempt from heard exclusion.
     */
    fun bypassEmptyConstraints(
        homeSongs: List<SongItem>,
        userKnownArtistIds: Set<String>,
        recentlyHeardVideoIds: Set<String>,
        isBlocked: (SongItem) -> Boolean = { false },
    ): List<SongItem> =
        homeSongs.filter { song ->
            if (song.id.isBlank() || song.isPodcast || isBlocked(song)) return@filter false
            val isUserKnown = isSongArtistKnown(song, userKnownArtistIds)
            val trust = TrustScorer.score(song, isUserKnown = isUserKnown, config = config)
            trust >= config.trustGate || isUserKnown
        }

    suspend fun evaluate(
        candidates: List<CandidateTrack>,
        context: GateContext,
    ): GatePipelineResult {
        val drops = mutableMapOf<GateDropReason, Int>()

        fun recordDrop(reason: GateDropReason) {
            drops[reason] = (drops[reason] ?: 0) + 1
        }

        val normalizedFilter = context.filter.normalized()
        val langTargets = SpaceLanguageRegistry.parseTargets(normalizedFilter.languages)
        val effectiveGenres =
            buildList {
                addAll(normalizedFilter.genres)
                if (langTargets.hasInstrumental && none { it.equals(SpaceLanguageRegistry.INSTRUMENTAL_SLUG, ignoreCase = true) }) {
                    add(SpaceLanguageRegistry.INSTRUMENTAL_SLUG)
                }
            }

        // G0 structural + G1 permanent before deduplication.
        val structuralPassed =
            buildList {
                for (candidate in candidates) {
                    val song = candidate.song
                    when {
                        song.id.isBlank() -> recordDrop(GateDropReason.STRUCTURAL_BLANK_ID)
                        song.isPodcast -> recordDrop(GateDropReason.STRUCTURAL_PODCAST)
                        context.isBlocked(song) -> recordDrop(GateDropReason.PERMANENT_BLOCKED)
                        else -> add(candidate)
                    }
                }
            }

        val trustCache = HashMap<String, Double>(structuralPassed.size)

        fun trustFor(song: SongItem): Double =
            trustCache.getOrPut(song.id) {
                TrustScorer.score(song, isUserKnown = isSongArtistKnown(song, context.userKnownArtistIds), config = config)
            }

        // G0 duplicate collapse by normalized (title, artist), keeping highest trust and preferring ATV.
        val deduped = TrackDeduplicator.deduplicate(structuralPassed, ::trustFor)

        val survivors = mutableListOf<GatedCandidate>()
        var acceptCount = 0
        var rejectCount = 0
        var maybeCount = 0

        for (candidate in deduped) {
            val song = candidate.song

            // G2 heard / queued / shown exclusions.
            // Heard exclusion applies only to SEED_RADIO, ARTIST_RADIO, and SEARCH (not HOME, HOME_CARD, or LOCAL_HISTORY).
            val heardExempt = candidate.sources.any { it.isHeardExclusionExempt }
            if (!heardExempt && song.id in context.recentlyHeardVideoIds) {
                recordDrop(GateDropReason.HEARD_RECENTLY)
                continue
            }
            if (song.id in context.currentlyQueuedVideoIds) {
                recordDrop(GateDropReason.CURRENTLY_QUEUED)
                continue
            }
            if (!context.relaxShownExclusion && song.id in context.recentlyShownVideoIds) {
                recordDrop(GateDropReason.SHOWN_RECENTLY)
                continue
            }

            // G3 trust gate.
            val isUserKnown = isSongArtistKnown(song, context.userKnownArtistIds)
            val trust = trustFor(song)
            if (trust < config.trustGate && !isUserKnown) {
                recordDrop(GateDropReason.TRUST_BELOW_GATE)
                continue
            }

            // G4 language gate (only when the Space has languages).
            val languageResult =
                if (normalizedFilter.languages.isEmpty()) {
                    LanguageCascadeResult(GateVerdict.ACCEPT, LanguageStep.BYPASS_EMPTY)
                } else {
                    languageCascade.evaluate(candidate, normalizedFilter.languages, context.nowMs)
                }
            if (languageResult.verdict == GateVerdict.REJECT) {
                recordDrop(GateDropReason.LANGUAGE_REJECTED)
                rejectCount++
                continue
            }

            // G5 genre gate (only when the Space has genres or "instrumental").
            // Order: track_class.genre -> settled artist genre verdict -> seedGenreAccepted (seed track only) -> MAYBE (high L5 priority).
            val genreEvaluation =
                if (effectiveGenres.isEmpty()) {
                    GenreGateEvaluation(
                        verdict = GateVerdict.ACCEPT,
                        rule = GenreAcceptanceRule.BYPASS_EMPTY,
                        highPriorityForL5 = false,
                    )
                } else {
                    evaluateGenreGate(candidate, effectiveGenres, context.nowMs)
                }
            if (genreEvaluation.verdict == GateVerdict.REJECT) {
                recordDrop(GateDropReason.GENRE_REJECTED)
                rejectCount++
                continue
            }

            val gated =
                GatedCandidate(
                    candidate = candidate,
                    trust = trust,
                    languageResult = languageResult,
                    genreVerdict = genreEvaluation.verdict,
                    genreHighPriorityForL5 = genreEvaluation.highPriorityForL5,
                    genreRule = genreEvaluation.rule,
                )
            if (gated.isFullyAccepted) acceptCount++ else maybeCount++
            survivors += gated
        }

        return GatePipelineResult(
            survivors = survivors,
            stats =
                GateStats(
                    inputCount = candidates.size,
                    dedupedCount = deduped.size,
                    drops = drops,
                    acceptCount = acceptCount,
                    rejectCount = rejectCount,
                    maybeCount = maybeCount,
                    shownExclusionRelaxed = context.relaxShownExclusion,
                ),
        )
    }

    /**
     * Evaluates gates and, if post-exclusion survivors are under half of feedTargetSongs,
     * relaxes ONLY the shown exclusion (never heard, permanent, trust, language, or genre).
     */
    suspend fun evaluateWithShownRelaxation(
        candidates: List<CandidateTrack>,
        context: GateContext,
    ): GatePipelineResult {
        val strict = evaluate(candidates, context.copy(relaxShownExclusion = false))
        val minBeforeRelax = config.feedTargetSongs / 2
        val effectiveCount = if (context.filter.isActive) strict.stats.acceptCount else strict.survivors.size
        if (effectiveCount >= minBeforeRelax || context.recentlyShownVideoIds.isEmpty()) {
            return strict
        }
        return evaluate(candidates, context.copy(relaxShownExclusion = true))
    }

    private data class GenreGateEvaluation(
        val verdict: GateVerdict,
        val rule: GenreAcceptanceRule,
        val highPriorityForL5: Boolean,
    )

    private suspend fun evaluateGenreGate(
        candidate: CandidateTrack,
        requestedGenres: List<String>,
        nowMs: Long,
    ): GenreGateEvaluation {
        val cached = trackClassLookup.getTrackClass(candidate.song.id)
        val cachedGenreRaw = cached?.genre?.trim().orEmpty()

        if (cachedGenreRaw.isNotEmpty()) {
            val tags =
                cachedGenreRaw
                    .split(',', ';', '|')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            val verdict = if (tagsMatchAnyRequestedGenre(tags, requestedGenres)) GateVerdict.ACCEPT else GateVerdict.REJECT
            return GenreGateEvaluation(
                verdict = verdict,
                rule = GenreAcceptanceRule.TRACK_CLASS_GENRE,
                highPriorityForL5 = false,
            )
        }

        // Artist-level genre votes verdict (handles both single-dominant settledGenre and multi-tag subgenres).
        val artistId = candidate.primaryArtistId
        if (!artistId.isNullOrBlank()) {
            val profile = artistProfileLookup.getArtistProfile(artistId)
            val ttlMs = config.artistProfileTtlDays.toLong() * SpaceRecConfig.MILLIS_PER_DAY
            if (profile != null && nowMs - profile.updatedAt <= ttlMs) {
                val totalGenreVotes = profile.totalGenreVotes
                if (totalGenreVotes >= config.artistSettledMinVotes && totalGenreVotes > 0.0) {
                    val matchingVotes =
                        profile.genreVotes.entries
                            .filter { (genreSlug, _) -> tagsMatchAnyRequestedGenre(listOf(genreSlug), requestedGenres) }
                            .sumOf { it.value }
                    val settledGenre = profile.settledGenre(nowMs, config)
                    when {
                        settledGenre != null && tagsMatchAnyRequestedGenre(listOf(settledGenre), requestedGenres) -> {
                            return GenreGateEvaluation(
                                verdict = GateVerdict.ACCEPT,
                                rule = GenreAcceptanceRule.ARTIST_PROFILE_GENRE_VOTES,
                                highPriorityForL5 = false,
                            )
                        }

                        matchingVotes >= config.artistSettledMinVotes * 0.5 &&
                            matchingVotes / totalGenreVotes >= MULTI_GENRE_ACCEPT_MIN_SHARE -> {
                            return GenreGateEvaluation(
                                verdict = GateVerdict.ACCEPT,
                                rule = GenreAcceptanceRule.ARTIST_PROFILE_GENRE_VOTES,
                                highPriorityForL5 = false,
                            )
                        }

                        matchingVotes == 0.0 && settledGenre != null -> {
                            return GenreGateEvaluation(
                                verdict = GateVerdict.REJECT,
                                rule = GenreAcceptanceRule.ARTIST_PROFILE_GENRE_VOTES,
                                highPriorityForL5 = false,
                            )
                        }
                    }
                }
            }
        }

        // Song-level metadata genre if YouTube / library provided one directly.
        val songGenreRaw = candidate.song.genre?.trim().orEmpty()
        if (songGenreRaw.isNotEmpty()) {
            val songTags = songGenreRaw.split(',', ';', '|').map(String::trim).filter(String::isNotEmpty)
            if (tagsMatchAnyRequestedGenre(songTags, requestedGenres)) {
                return GenreGateEvaluation(
                    verdict = GateVerdict.ACCEPT,
                    rule = GenreAcceptanceRule.TRACK_CLASS_GENRE,
                    highPriorityForL5 = false,
                )
            }
        }

        // Rule (2): seedGenreAccepted applies ONLY to the seed track itself, never to tracks pulled from its radio.
        val isSeedTrackItself =
            candidate.seedGenreAccepted &&
                when {
                    candidate.seedVideoId != null -> candidate.song.id == candidate.seedVideoId
                    else -> CandidateSource.SEED_RADIO !in candidate.sources
                }
        if (isSeedTrackItself) {
            return GenreGateEvaluation(
                verdict = GateVerdict.ACCEPT,
                rule = GenreAcceptanceRule.SEED_GENRE_ACCEPTED,
                highPriorityForL5 = false,
            )
        }

        // Otherwise MAYBE with high L5 priority.
        return GenreGateEvaluation(
            verdict = GateVerdict.MAYBE,
            rule = GenreAcceptanceRule.MAYBE_PASSTHROUGH,
            highPriorityForL5 = true,
        )
    }

    companion object {
        private const val MULTI_GENRE_ACCEPT_MIN_SHARE = 0.35

        private val NoOpArtistProfileLookup =
            object : ArtistProfileLookup {
                override suspend fun getArtistProfile(artistId: String): ArtistProfileRecord? = null
            }

        internal fun tagsMatchAnyRequestedGenre(
            tags: List<String>,
            requestedGenres: List<String>,
        ): Boolean =
            requestedGenres.any { requested ->
                if (requested.equals(SpaceLanguageRegistry.INSTRUMENTAL_SLUG, ignoreCase = true)) {
                    tags.any { tag ->
                        val norm = SpaceFilterTaxonomy.normalizeTag(tag)
                        norm == "instrumental" || norm.contains("instrumental") || norm == "no vocals"
                    }
                } else {
                    tags.any { tag ->
                        tag.equals(requested, ignoreCase = true) ||
                            canonicalGenreMatchesRequested(tag, requested)
                    } ||
                        SpaceFilterTaxonomy.tagsMatchRequestedGenre(tags, requested)
                }
            }

        private fun canonicalGenreMatchesRequested(
            storedGenreId: String,
            requestedGenreId: String,
        ): Boolean {
            val storedOpt = SpaceFilterTaxonomy.genreOption(storedGenreId.trim().lowercase()) ?: return false
            val reqId = requestedGenreId.trim().lowercase()
            return storedOpt.id == reqId || storedOpt.parentId == reqId
        }

        fun isSongArtistKnown(
            song: SongItem,
            userKnownArtistIds: Set<String>,
        ): Boolean = song.artists.any { !it.id.isNullOrBlank() && it.id in userKnownArtistIds }

        fun recentlyHeardIds(
            plays: List<PlayRecord>,
            nowMs: Long,
            config: SpaceRecConfig = SpaceRecConfig.Default,
        ): Set<String> {
            val cutoffMs = nowMs - config.heardExclusionDays.toLong() * SpaceRecConfig.MILLIS_PER_DAY
            return plays
                .asSequence()
                .filter { it.playedAtMs >= cutoffMs && it.isCompleted(config) }
                .map { it.videoId }
                .toSet()
        }
    }
}
