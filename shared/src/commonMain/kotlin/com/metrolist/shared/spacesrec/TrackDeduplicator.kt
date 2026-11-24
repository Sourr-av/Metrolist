/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.MusicVideoType
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.parseMusicVideoType
import kotlin.math.abs

/**
 * Deduplicates candidate songs by normalized (title, primary artist) key and selects
 * the highest-trust representative, preferring label-distributed audio tracks (ATV)
 * over videos and keeping user-uploaded videos (UGC) only when no ATV counterpart exists.
 */
object TrackDeduplicator {
    private val BRACKETED_SEGMENT_REGEX = Regex("""[\(\[]([^\)\]]*)[\)\]]""")
    private val TRAILING_FEAT_REGEX = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private val STRIP_BRACKET_KEYWORDS =
        listOf(
            "official music video",
            "official video",
            "official audio",
            "lyric video",
            "lyrics",
            "audio",
            "feat.",
            "feat ",
            "ft.",
            "ft ",
            "featuring",
        )

    fun normalizeTitle(rawTitle: String): String {
        val withoutNoiseBrackets =
            BRACKETED_SEGMENT_REGEX.replace(rawTitle) { match ->
                val inner = match.groupValues[1].trim().lowercase()
                val isNoise =
                    inner == "audio" ||
                        inner == "lyrics" ||
                        inner == "official video" ||
                        inner == "official music video" ||
                        inner == "official audio" ||
                        STRIP_BRACKET_KEYWORDS.any { keyword -> inner.contains(keyword) }
                if (isNoise) " " else match.value
            }
        val withoutTrailingFeat = TRAILING_FEAT_REGEX.replace(withoutNoiseBrackets, " ")
        return stripPunctuationAndNormalize(withoutTrailingFeat)
    }

    fun normalizeArtist(rawArtist: String): String =
        stripPunctuationAndNormalize(
            rawArtist
                .substringBefore(" - Topic")
                .substringBefore(" • "),
        )

    fun normalizedKey(song: SongItem): String {
        val normTitle = normalizeTitle(song.title)
        val primaryArtist =
            song.artists
                .firstOrNull()
                ?.name
                .orEmpty()
        val normArtist = normalizeArtist(primaryArtist)
        return "$normTitle|$normArtist"
    }

    fun deduplicate(
        candidates: List<CandidateTrack>,
        trustProvider: (SongItem) -> Double,
    ): List<CandidateTrack> {
        if (candidates.size <= 1) return candidates

        // Group by normalized key (preserving first-seen insertion order).
        val groups = linkedMapOf<String, MutableList<CandidateTrack>>()
        for (candidate in candidates) {
            val key = normalizedKey(candidate.song).ifBlank { candidate.song.id }
            groups.getOrPut(key) { mutableListOf() }.add(candidate)
        }

        return groups.values.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                selectWinner(group, trustProvider)
            }
        }
    }

    private fun selectWinner(
        group: List<CandidateTrack>,
        trustProvider: (SongItem) -> Double,
    ): CandidateTrack {
        val hasAtv = group.any { parseMusicVideoType(it.song.musicVideoType) == MusicVideoType.ATV }
        val eligible =
            if (hasAtv) {
                // Keep user-uploaded song videos (UGC) only when no label audio counterpart exists.
                group.filterNot { parseMusicVideoType(it.song.musicVideoType) == MusicVideoType.UGC }
            } else {
                group
            }.ifEmpty { group }

        val winner =
            eligible.reduce { best, next ->
                val bestTrust = trustProvider(best.song)
                val nextTrust = trustProvider(next.song)
                when {
                    nextTrust - bestTrust > 1e-9 -> next

                    abs(nextTrust - bestTrust) <= 1e-9 &&
                        typePreferenceRank(next.song) > typePreferenceRank(best.song) -> next

                    else -> best
                }
            }

        val mergedSources = group.flatMapTo(linkedSetOf()) { it.sources }
        val homeCopy = group.firstOrNull { it.homeShelfTitle != null }
        val seedCopy = group.firstOrNull { it.seedArtistId != null }

        return winner.copy(
            sources = mergedSources,
            seedArtistId = winner.seedArtistId ?: seedCopy?.seedArtistId,
            seedArtistName = winner.seedArtistName ?: seedCopy?.seedArtistName,
            seedLanguageAccepted = group.any { it.seedLanguageAccepted },
            seedGenreAccepted = group.any { it.seedGenreAccepted },
            homeShelfTitle = winner.homeShelfTitle ?: homeCopy?.homeShelfTitle,
            homeShelfBrowseId = winner.homeShelfBrowseId ?: homeCopy?.homeShelfBrowseId,
        )
    }

    private fun typePreferenceRank(song: SongItem): Int =
        when (parseMusicVideoType(song.musicVideoType)) {
            MusicVideoType.ATV -> 4
            MusicVideoType.OMV -> 3
            MusicVideoType.UNKNOWN -> 2
            MusicVideoType.VIDEO, MusicVideoType.LIVE -> 1
            MusicVideoType.UGC -> 0
            MusicVideoType.PODCAST_EPISODE -> -1
        }

    private fun stripPunctuationAndNormalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(' ')
            }
        }
        return WHITESPACE_REGEX.replace(sb.toString(), " ").trim()
    }
}
