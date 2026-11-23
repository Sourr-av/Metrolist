/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.MusicVideoType
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.parseMusicVideoType

/**
 * Computes a clamped [0.0, 1.0] trust score to down-rank or filter out AI-generated
 * and low-quality user uploads.
 */
object TrustScorer {
    fun score(
        song: SongItem,
        isUserKnown: Boolean,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): Double {
        var total = 0.0

        val videoType = parseMusicVideoType(song.musicVideoType)
        if (videoType == MusicVideoType.ATV || videoType == MusicVideoType.OMV) {
            total += config.trustAtvOrOmvBonus
        }

        if (song.album != null && !song.album.id.isNullOrBlank()) {
            total += config.trustAlbumBonus
        }

        val allArtistsHaveIds = song.artists.isNotEmpty() && song.artists.all { !it.id.isNullOrBlank() }
        if (allArtistsHaveIds) {
            total += config.trustAllArtistsHaveIdBonus
        } else {
            total += config.trustMissingArtistIdPenalty
        }

        if (isUserKnown) {
            total += config.trustUserKnownBonus
        }

        if (hasAiMarker(song, config)) {
            total += config.trustAiMarkerPenalty
        }

        val durationSec = song.duration
        if (durationSec != null && (durationSec < config.trustMinDurationSeconds || durationSec > config.trustMaxDurationSeconds)) {
            total += config.trustBadDurationPenalty
        }

        return total.coerceIn(0.0, 1.0)
    }

    fun hasAiMarker(
        song: SongItem,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): Boolean {
        val title = song.title
        for (marker in config.aiMarkers) {
            if (containsWordBoundary(title, marker, ignoreCase = true)) return true
        }
        for (artist in song.artists) {
            val name = artist.name
            for (marker in config.aiMarkers) {
                if (containsWordBoundary(name, marker, ignoreCase = true)) return true
            }
            if (config.aiArtistExactToken.isNotEmpty() &&
                containsWordBoundary(name, config.aiArtistExactToken, ignoreCase = false)
            ) {
                return true
            }
        }
        return false
    }

    internal fun containsWordBoundary(
        haystack: String,
        needle: String,
        ignoreCase: Boolean,
    ): Boolean {
        if (haystack.isEmpty() || needle.isEmpty() || needle.length > haystack.length) return false
        var startIndex = 0
        while (startIndex <= haystack.length - needle.length) {
            val found = haystack.indexOf(needle, startIndex = startIndex, ignoreCase = ignoreCase)
            if (found < 0) return false
            val beforeOk = found == 0 || !haystack[found - 1].isLetterOrDigit()
            val afterPos = found + needle.length
            val afterOk = afterPos == haystack.length || !haystack[afterPos].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            startIndex = found + 1
        }
        return false
    }
}
