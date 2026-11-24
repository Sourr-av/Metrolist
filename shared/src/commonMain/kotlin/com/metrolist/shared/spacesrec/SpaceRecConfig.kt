/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

data class ScoreWeights(
    val affinity: Double = 0.35,
    val source: Double = 0.20,
    val trust: Double = 0.20,
    val novelty: Double = 0.15,
    val discovery: Double = 0.10,
)

data class SpaceRecConfig(
    // Timing & cache
    val cacheTtlMs: Long = 1_800_000L,
    val firstPaintFromCacheMs: Long = 300L,
    val stageADeadlineMs: Long = 2_500L,
    val hardDeadlineMs: Long = 8_000L,
    val perCallTimeoutMs: Long = 4_000L,
    // Concurrency & network classification
    val radioConcurrency: Int = 4,
    val classifyNetworkConcurrency: Int = 4,
    val maxNetworkClassifyPerRefresh: Int = 20,
    val circuitBreakerFailureThreshold: Int = 5,
    val circuitBreakerOpenDurationMs: Long = 60_000L,
    // Candidate generation
    val homeContinuationsMax: Int = 3,
    val homeCardsMax: Int = 2,
    val seedsPerRefresh: Int = 5,
    val radioPagesPerSeed: Int = 2,
    val artistRadioTopArtists: Int = 2,
    val stageBSeedsCount: Int = 5,
    val coldStartPoolThreshold: Int = 40,
    val coldStartTrustMin: Double = 0.5,
    val coldStartSeedsPerCombo: Int = 3,
    val seedEligibilityDays: Int = 90,
    val seedCooldownRefreshes: Int = 2,
    val homeTopSeedsLimit: Int = 10,
    // Feed targets & shelf policy
    val feedTargetSongs: Int = 60,
    val candidatePoolTarget: Int = 150,
    val minShelfItems: Int = 6,
    val minSongsBeforeMaybeAllowed: Int = 40,
    val maybeMaxShare: Double = 0.25,
    val maxGeneratedShelves: Int = 2,
    // Exclusions
    val heardExclusionDays: Int = 45,
    val shownExclusionRefreshes: Int = 3,
    // Completion thresholds
    val completionMinPlayedMs: Long = 30_000L,
    val completionMinDurationRatio: Double = 0.5,
    // Affinity, familiarity & exploration
    val affinityHalfLifeDays: Double = 60.0,
    val affinityNormDivisor: Double = 3.0,
    val familiarThreshold: Double = 0.5,
    val familiarMaxShare: Double = 0.40,
    val exploreEveryNth: Int = 7,
    val exploreMaxAffinity: Double = 0.1,
    val exploreRequiredNovelty: Double = 1.0,
    // Diversity
    val artistMaxPerWindow: Int = 2,
    val artistWindowSize: Int = 20,
    val diversityArtistPenalty: Double = 0.30,
    val diversityAlbumPenalty: Double = 0.15,
    // Queue on tap
    val queueArtistMaxFirst20: Int = 3,
    val queueFirstWindowSize: Int = 20,
    val queueMaxConsecutiveSameArtist: Int = 2,
    val queueFirstPageTimeoutMs: Long = 2_000L,
    val queueMinFirstPageSongs: Int = 8,
    val queueFallbackMaybeMaxShare: Double = 0.30,
    val queueMaxUpcomingSongs: Int = 50,
    val queueMaxPages: Int = 3,
    // Trust & language cascade
    val trustGate: Double = 0.30,
    val trustAtvOrOmvBonus: Double = 0.35,
    val trustAlbumBonus: Double = 0.20,
    val trustAllArtistsHaveIdBonus: Double = 0.20,
    val trustUserKnownBonus: Double = 0.25,
    val trustMissingArtistIdPenalty: Double = -0.40,
    val trustAiMarkerPenalty: Double = -0.30,
    val trustBadDurationPenalty: Double = -0.20,
    val trustMinDurationSeconds: Long = 60L,
    val trustMaxDurationSeconds: Long = 900L,
    val userKnownMinCompletedPlays: Int = 2,
    val artistSettledMinVotes: Double = 3.0,
    val artistSettledMinShare: Double = 0.7,
    val artistProfileTtlDays: Int = 180,
    val l3MinLetters: Int = 12,
    val l3TimeoutMs: Long = 200L,
    val l3AcceptConfidence: Double = 0.80,
    val l3RejectConfidence: Double = 0.90,
    val voteWeightScript: Double = 1.0,
    val voteWeightNetwork: Double = 1.5,
    // Ranking features
    val scoreWeights: ScoreWeights = ScoreWeights(),
    val sourceScoreHome: Double = 0.30,
    val sourceScoreSeedRadio: Double = 0.20,
    val sourceScoreArtistRadio: Double = 0.20,
    val sourceScoreHomeCard: Double = 0.20,
    val sourceScoreSearch: Double = 0.05,
    val sourceMultiBonus: Double = 0.10,
    val sourceMultiBonusMax: Double = 0.20,
    val noveltyUnplayed: Double = 1.0,
    val noveltyOldPlay: Double = 0.4,
    val discoveryDefault: Double = 0.5,
    val discoveryLogDivisor: Double = 9.0,
    // AI-generated content markers (case-insensitive word-boundary phrases)
    val aiMarkers: List<String> = DEFAULT_AI_MARKERS,
    val aiArtistExactToken: String = "AI",
) {
    companion object {
        const val MILLIS_PER_DAY: Long = 86_400_000L

        val DEFAULT_AI_MARKERS: List<String> =
            listOf(
                "ai cover",
                "ai generated",
                "ai-generated",
                "ai voice",
                "ai version",
                "made with ai",
                "ai remix",
                "suno",
                "udio",
            )

        val Default = SpaceRecConfig()
    }
}
