/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustAndDedupeTest {
    private fun song(
        id: String,
        title: String,
        artistName: String = "The Weeknd",
        artistId: String? = "UC_weeknd",
        albumId: String? = "MPREb_1",
        durationSec: Long? = 200L,
        musicVideoType: String? = "MUSIC_VIDEO_TYPE_ATV",
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            thumbnail = null,
            artists = listOf(ArtistTiny(artistId, artistName)),
            album = albumId?.let { AlbumTiny(it, "Album") },
            duration = durationSec,
            musicVideoType = musicVideoType,
        )

    @Test
    fun trustScoreAppliesAllBonusesPenaltiesAndClamping() {
        // Full official label track + user known = 0.35 + 0.20 + 0.20 + 0.25 = 1.0
        val high = song("s1", "Blinding Lights")
        assertTrue(abs(1.0 - TrustScorer.score(high, isUserKnown = true)) < 1e-9)
        assertTrue(abs(0.75 - TrustScorer.score(high, isUserKnown = false)) < 1e-9)

        // Missing artist ID (-0.40) + AI cover (-0.30) + short clip 30s (-0.20) on UGC -> clamped to 0.0
        val slop =
            song(
                id = "s2",
                title = "Blinding Lights (AI Cover)",
                artistName = "Random Uploader",
                artistId = null,
                albumId = null,
                durationSec = 30L,
                musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
            )
        assertEquals(0.0, TrustScorer.score(slop, isUserKnown = false))

        // Long mix > 900s gets -0.20 penalty: 0.35 + 0.20 + 0.20 - 0.20 = 0.55
        val longMix = song("s3", "Full Album Mix", durationSec = 1200L)
        assertTrue(abs(0.55 - TrustScorer.score(longMix, isUserKnown = false)) < 1e-9)
    }

    @Test
    fun aiMarkerWordBoundaryAvoidsFalsePositives() {
        // "Audio" contains "udio" as a substring — must NOT trigger AI marker!
        assertFalse(TrustScorer.hasAiMarker(song("s1", "Song (Official Audio)", artistName = "Audio Slave")))
        // Lowercase "Ai" in a real artist's name must NOT trigger exact "AI" token!
        assertFalse(TrustScorer.hasAiMarker(song("s2", "Sakuranbo", artistName = "Ai Otsuka")))
        assertFalse(TrustScorer.hasAiMarker(song("s3", "Daisy", artistName = "DAISY")))

        // True AI markers MUST trigger.
        assertTrue(TrustScorer.hasAiMarker(song("s4", "Made on Suno", artistName = "Someone")))
        assertTrue(TrustScorer.hasAiMarker(song("s5", "Track", artistName = "Nova AI")))
        assertTrue(TrustScorer.hasAiMarker(song("s6", "Hit Song [AI-Generated]", artistName = "Someone")))
        assertTrue(TrustScorer.hasAiMarker(song("s7", "Hit Song (Udio Remix)", artistName = "Someone")))
    }

    @Test
    fun dedupeNormalizerStripsNoiseAndPrefersAtvOverUgc() {
        val atv =
            CandidateTrack(
                song = song("id_atv", "Blinding Lights", musicVideoType = "MUSIC_VIDEO_TYPE_ATV"),
                sources = setOf(CandidateSource.HOME),
            )
        val omv =
            CandidateTrack(
                song = song("id_omv", "Blinding Lights (Official Music Video)", musicVideoType = "MUSIC_VIDEO_TYPE_OMV"),
                sources = setOf(CandidateSource.SEED_RADIO),
                seedLanguageAccepted = true,
            )
        val ugc =
            CandidateTrack(
                song =
                    song(
                        "id_ugc",
                        "Blinding Lights [Lyrics] feat. Someone",
                        artistName = "The Weeknd - Topic",
                        musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
                    ),
                sources = setOf(CandidateSource.SEARCH),
            )

        assertEquals(TrackDeduplicator.normalizedKey(atv.song), TrackDeduplicator.normalizedKey(omv.song))
        assertEquals(TrackDeduplicator.normalizedKey(atv.song), TrackDeduplicator.normalizedKey(ugc.song))

        val deduped = TrackDeduplicator.deduplicate(listOf(ugc, omv, atv)) { TrustScorer.score(it, isUserKnown = false) }
        assertEquals(1, deduped.size)
        val winner = deduped.first()
        assertEquals("id_atv", winner.song.id)
        assertEquals(setOf(CandidateSource.HOME, CandidateSource.SEED_RADIO, CandidateSource.SEARCH), winner.sources)
        assertTrue(winner.seedLanguageAccepted)
    }

    @Test
    fun dedupeKeepsUgcWhenNoAtvCounterpartExists() {
        val ugcOnly =
            CandidateTrack(
                song = song("id_ugc", "Rare Unreleased Live Cut", musicVideoType = "MUSIC_VIDEO_TYPE_UGC"),
                sources = setOf(CandidateSource.SEED_RADIO),
            )
        val deduped = TrackDeduplicator.deduplicate(listOf(ugcOnly)) { TrustScorer.score(it, isUserKnown = false) }
        assertEquals(1, deduped.size)
        assertEquals("id_ugc", deduped.first().song.id)
    }
}
