/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.SongItem
import kotlinx.serialization.Serializable

enum class CandidateSource {
    HOME,
    HOME_CARD,
    SEED_RADIO,
    ARTIST_RADIO,
    SEARCH,
}

enum class GateVerdict {
    ACCEPT,
    REJECT,
    MAYBE,
}

enum class LanguageStep {
    BYPASS_EMPTY,
    BYPASS_INSTRUMENTAL,
    L0_TRACK_CACHE,
    L1_SCRIPT,
    L2_ARTIST_PROFILE,
    L3_ON_DEVICE,
    L4_PROVENANCE,
    UNRESOLVED_MAYBE,
}

data class LanguageCascadeResult(
    val verdict: GateVerdict,
    val step: LanguageStep,
    val detectedLanguage: String? = null,
    val confidence: Double = 0.0,
    val highPriorityForL5: Boolean = false,
)

data class ArtistProfileRecord(
    val artistId: String,
    val name: String,
    val languageVotes: Map<String, Double>,
    val totalVotes: Double,
    val updatedAt: Long,
) {
    fun settledLanguage(
        nowMs: Long,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): String? {
        val ttlMs = config.artistProfileTtlDays.toLong() * SpaceRecConfig.MILLIS_PER_DAY
        if (nowMs - updatedAt > ttlMs) return null
        if (totalVotes < config.artistSettledMinVotes || totalVotes <= 0.0) return null
        val top = languageVotes.maxByOrNull { it.value } ?: return null
        val share = top.value / totalVotes
        return if (share >= config.artistSettledMinShare) top.key else null
    }

    fun addVote(
        languageCode: String,
        weight: Double,
        nowMs: Long,
    ): ArtistProfileRecord {
        val normalized = SpaceLanguageRegistry.canonicalCode(languageCode) ?: return this
        if (weight <= 0.0) return this
        val updatedVotes = languageVotes.toMutableMap()
        updatedVotes[normalized] = (updatedVotes[normalized] ?: 0.0) + weight
        return copy(
            languageVotes = updatedVotes,
            totalVotes = totalVotes + weight,
            updatedAt = nowMs,
        )
    }
}

data class TrackClassRecord(
    val videoId: String,
    val language: String?,
    val languageConfidence: Double,
    val genre: String?,
    val evidence: String,
    val checkedAt: Long,
)

interface ArtistProfileLookup {
    suspend fun getArtistProfile(artistId: String): ArtistProfileRecord?
}

interface TrackClassLookup {
    suspend fun getTrackClass(videoId: String): TrackClassRecord?
}

data class PlayRecord(
    val videoId: String,
    val artistIds: List<String>,
    val playedMs: Long,
    val durationMs: Long?,
    val playedAtMs: Long,
) {
    fun isCompleted(config: SpaceRecConfig = SpaceRecConfig.Default): Boolean {
        if (playedMs >= config.completionMinPlayedMs) return true
        val dur = durationMs ?: return false
        if (dur <= 0L) return false
        return playedMs >= (dur.toDouble() * config.completionMinDurationRatio).toLong()
    }
}

@Serializable
data class SeedArmState(
    val spaceId: String,
    val artistId: String,
    val seedVideoId: String,
    val completions: Int = 0,
    val skips: Int = 0,
    val lastUsedSeq: Int = -1,
)

data class CandidateTrack(
    val song: SongItem,
    val sources: Set<CandidateSource>,
    val seedArtistId: String? = null,
    val seedArtistName: String? = null,
    val seedLanguageAccepted: Boolean = false,
    val seedGenreAccepted: Boolean = false,
    val homeShelfTitle: String? = null,
    val homeShelfBrowseId: String? = null,
) {
    val primaryArtistKey: String
        get() =
            song.artists
                .firstOrNull()
                ?.let { it.id?.takeIf(String::isNotBlank) ?: it.name.trim().lowercase() }
                .orEmpty()

    val primaryArtistId: String?
        get() = song.artists.firstOrNull()?.id?.takeIf(String::isNotBlank)

    val albumKey: String?
        get() = song.album?.let { it.id?.takeIf(String::isNotBlank) ?: it.name.trim().lowercase().takeIf(String::isNotBlank) }
}

data class GatedCandidate(
    val candidate: CandidateTrack,
    val trust: Double,
    val languageResult: LanguageCascadeResult,
    val genreVerdict: GateVerdict,
) {
    val isFullyAccepted: Boolean
        get() = languageResult.verdict == GateVerdict.ACCEPT && genreVerdict == GateVerdict.ACCEPT

    val isMaybe: Boolean
        get() =
            languageResult.verdict != GateVerdict.REJECT &&
                genreVerdict != GateVerdict.REJECT &&
                !isFullyAccepted
}

data class RankedCandidate(
    val gated: GatedCandidate,
    val affinity: Double,
    val sourceScore: Double,
    val trust: Double,
    val novelty: Double,
    val discovery: Double,
    val score: Double,
) {
    val candidate: CandidateTrack get() = gated.candidate
    val song: SongItem get() = gated.candidate.song
}

data class SpaceShelfLabels(
    val freshPicksTitle: String,
    val moreLikeFormat: (artistName: String) -> String,
)

data class AssembledShelf(
    val key: String,
    val title: String,
    val browseId: String? = null,
    val songs: List<SongItem>,
    val isGenerated: Boolean = false,
)

data class AssembledFeed(
    val songs: List<RankedCandidate>,
    val shelves: List<AssembledShelf>,
    val familiarShare: Double,
    val uniqueArtistCount: Int,
    val maxSongsPerArtist: Int,
)
