/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

data class ArtistAffinityTable(
    val scoresByArtistId: Map<String, Double>,
) {
    fun affinityFor(artistIds: List<String>): Double {
        if (artistIds.isEmpty()) return 0.0
        var best = 0.0
        for (id in artistIds) {
            val score = scoresByArtistId[id] ?: 0.0
            if (score > best) best = score
        }
        return best
    }
}

/**
 * Pure Stage 3 ranker:
 *   score = 0.35*A + 0.20*S + 0.20*T + 0.15*N + 0.10*P
 */
object SpaceRanker {
    private val PLAY_COUNT_REGEX = Regex("""^(\d+(?:\.\d+)?)\s*([kmb])?$""", RegexOption.IGNORE_CASE)

    /**
     * Computes decayed artist affinity over completed plays in [plays]:
     *   raw(artist) = sum(0.5 ^ (ageDays / halfLifeDays))
     *   A = (ln(1 + raw) / 3.0).coerceIn(0.0, 1.0)
     */
    fun buildArtistAffinityTable(
        plays: List<PlayRecord>,
        nowMs: Long,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): ArtistAffinityTable {
        val rawTotals = mutableMapOf<String, Double>()
        for (play in plays) {
            if (!play.isCompleted(config)) continue
            val ageMs = (nowMs - play.playedAtMs).coerceAtLeast(0L)
            val ageDays = ageMs.toDouble() / SpaceRecConfig.MILLIS_PER_DAY.toDouble()
            val decay = 0.5.pow(ageDays / config.affinityHalfLifeDays)
            for (artistId in play.artistIds) {
                if (artistId.isBlank()) continue
                rawTotals[artistId] = (rawTotals[artistId] ?: 0.0) + decay
            }
        }
        val normalized =
            rawTotals.mapValues { (_, raw) ->
                (ln(1.0 + raw) / config.affinityNormDivisor).coerceIn(0.0, 1.0)
            }
        return ArtistAffinityTable(normalized)
    }

    /**
     * Returns the set of artist IDs the user has completed >= [SpaceRecConfig.userKnownMinCompletedPlays] times.
     */
    fun userKnownArtistIds(
        plays: List<PlayRecord>,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): Set<String> {
        val counts = mutableMapOf<String, Int>()
        for (play in plays) {
            if (!play.isCompleted(config)) continue
            for (artistId in play.artistIds) {
                if (artistId.isBlank()) continue
                counts[artistId] = (counts[artistId] ?: 0) + 1
            }
        }
        return counts.filterValues { it >= config.userKnownMinCompletedPlays }.keys
    }

    fun sourceScore(
        sources: Set<CandidateSource>,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): Double {
        if (sources.isEmpty()) return 0.0
        val maxBase =
            sources.maxOf { source ->
                when (source) {
                    CandidateSource.HOME -> config.sourceScoreHome
                    CandidateSource.SEED_RADIO -> config.sourceScoreSeedRadio
                    CandidateSource.ARTIST_RADIO -> config.sourceScoreArtistRadio
                    CandidateSource.HOME_CARD -> config.sourceScoreHomeCard
                    CandidateSource.SEARCH -> config.sourceScoreSearch
                }
            }
        val extraCount = (sources.size - 1).coerceAtLeast(0)
        val multiBonus = (extraCount * config.sourceMultiBonus).coerceAtMost(config.sourceMultiBonusMax)
        return (maxBase + multiBonus).coerceIn(0.0, 1.0)
    }

    fun noveltyScore(
        videoId: String,
        everPlayedVideoIds: Set<String>,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): Double = if (videoId !in everPlayedVideoIds) config.noveltyUnplayed else config.noveltyOldPlay

    /**
     * Parses `playCountText` only when it strictly matches digits with an optional
     * decimal and optional K / M / B suffix (e.g. "12M", "1.4K", "450", "12M plays").
     * Returns null on any other format so discovery cleanly falls back to 0.5.
     */
    fun parsePlayCountText(playCountText: String?): Long? {
        if (playCountText.isNullOrBlank()) return null
        val trimmed = playCountText.trim()
        val token = trimmed.substringBefore(' ').replace(",", "")
        val match = PLAY_COUNT_REGEX.matchEntire(token) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        if (!number.isFinite() || number < 0.0) return null
        val multiplier =
            when (match.groupValues[2].lowercase()) {
                "k" -> 1_000.0
                "m" -> 1_000_000.0
                "b" -> 1_000_000_000.0
                "" -> 1.0
                else -> return null
            }
        return (number * multiplier).toLong()
    }

    fun discoveryScore(
        playCountText: String?,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): Double {
        val plays = parsePlayCountText(playCountText) ?: return config.discoveryDefault
        if (plays <= 0L) return config.discoveryDefault
        return (1.0 - log10(plays.toDouble()) / config.discoveryLogDivisor).coerceIn(0.0, 1.0)
    }

    fun rank(
        candidates: List<GatedCandidate>,
        affinityTable: ArtistAffinityTable,
        everPlayedVideoIds: Set<String>,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): List<RankedCandidate> {
        val weights = config.scoreWeights
        return candidates
            .map { gated ->
                val song = gated.candidate.song
                val artistIds = song.artists.mapNotNull { it.id?.takeIf(String::isNotBlank) }
                val a = affinityTable.affinityFor(artistIds)
                val s = sourceScore(gated.candidate.sources, config)
                val t = gated.trust.coerceIn(0.0, 1.0)
                val n = noveltyScore(song.id, everPlayedVideoIds, config)
                val p = discoveryScore(song.playCountText, config)
                val total =
                    weights.affinity * a +
                        weights.source * s +
                        weights.trust * t +
                        weights.novelty * n +
                        weights.discovery * p
                RankedCandidate(
                    gated = gated,
                    affinity = a,
                    sourceScore = s,
                    trust = t,
                    novelty = n,
                    discovery = p,
                    score = total,
                )
            }.sortedWith(
                compareByDescending<RankedCandidate> { it.score }
                    .thenByDescending { it.trust }
                    .thenBy { it.song.id },
            )
    }
}
