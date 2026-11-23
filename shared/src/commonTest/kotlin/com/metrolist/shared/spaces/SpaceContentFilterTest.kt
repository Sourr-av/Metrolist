/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spaces

import com.metrolist.shared.api.lastfm.LastFmTag
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.SpaceFilterSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SpaceContentFilterTest {
    private class FakeTagProvider(
        private val trackMap: Map<Pair<String, String>, List<LastFmTag>> = emptyMap(),
        private val artistMap: Map<String, List<LastFmTag>> = emptyMap(),
        private val albumMap: Map<Pair<String, String>, List<LastFmTag>> = emptyMap(),
    ) : SpaceTagProvider {
        override val isConfigured: Boolean = true

        override suspend fun trackTags(
            artist: String,
            track: String,
        ): List<LastFmTag> = trackMap[artist.lowercase() to track.lowercase()].orEmpty()

        override suspend fun artistTags(artist: String): List<LastFmTag> = artistMap[artist.lowercase()].orEmpty()

        override suspend fun albumTags(
            artist: String,
            album: String,
        ): List<LastFmTag> = albumMap[artist.lowercase() to album.lowercase()].orEmpty()
    }

    private fun song(
        id: String,
        title: String,
        artist: String,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            thumbnail = null,
            artists = listOf(ArtistTiny(id = "a_$id", name = artist)),
            album = null,
        )

    @Test
    fun languageTakesStrictPriorityOverGenre() =
        runTest {
            val provider =
                FakeTagProvider(
                    trackMap =
                        mapOf(
                            ("kendrick lamar" to "humble") to listOf(LastFmTag("hip-hop", 100), LastFmTag("rap", 90), LastFmTag("american", 60)),
                            ("bad bunny" to "monaco") to listOf(LastFmTag("hip-hop", 100), LastFmTag("latin trap", 95), LastFmTag("spanish", 90)),
                            ("creepy nuts" to "bling-bang-bang-born") to listOf(LastFmTag("hip-hop", 100), LastFmTag("japanese", 95)),
                            ("divine" to "mirchi") to listOf(LastFmTag("hip-hop", 100), LastFmTag("hindi", 90), LastFmTag("indian", 80)),
                        ),
                )
            val filter = SpaceContentFilter(provider)
            val spec = SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop"))

            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(song("1", "HUMBLE", "Kendrick Lamar"), spec, SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(song("2", "MONACO", "Bad Bunny"), spec, SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(song("3", "Bling-Bang-Bang-Born", "Creepy Nuts"), spec, SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(song("4", "Mirchi", "DIVINE"), spec, SpaceCandidateSource.HOME_FEED),
            )
            // Even in targeted search fallback, a conflicting language MUST still be rejected.
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(song("2", "MONACO", "Bad Bunny"), spec, SpaceCandidateSource.TARGETED_SEARCH),
            )
        }

    @Test
    fun popRockSongPassesBothPopAndRockSpaces() =
        runTest {
            val provider =
                FakeTagProvider(
                    trackMap =
                        mapOf(
                            ("maroon 5" to "this love") to listOf(LastFmTag("pop rock", 100), LastFmTag("american", 50)),
                        ),
                )
            val filter = SpaceContentFilter(provider)
            val track = song("1", "This Love", "Maroon 5")

            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(track, SpaceFilterSpec(languages = listOf("english"), genres = listOf("pop")), SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(track, SpaceFilterSpec(languages = listOf("english"), genres = listOf("rock")), SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.REJECT_GENRE_CONFLICT,
                filter.evaluate(track, SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop")), SpaceCandidateSource.HOME_FEED),
            )
        }

    @Test
    fun untaggedSongIsRejectedOnHomeFeedButAllowedFromTargetedSearchIfUnconflicted() =
        runTest {
            val provider = FakeTagProvider()
            val filter = SpaceContentFilter(provider)
            val spec = SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop"))

            val untaggedEnglish = song("1", "Brand New Cypher", "Indie MC")
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_UNVERIFIED,
                filter.evaluate(untaggedEnglish, spec, SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(untaggedEnglish, spec, SpaceCandidateSource.TARGETED_SEARCH),
            )

            // Non-Latin script song MUST still be rejected even in TARGETED_SEARCH!
            val japaneseSong = song("2", "新しい曲", "日本人")
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(japaneseSong, spec, SpaceCandidateSource.TARGETED_SEARCH),
            )
        }

    @Test
    fun artistTagsFallbackWhenTrackHasNoTags() =
        runTest {
            val provider =
                FakeTagProvider(
                    artistMap =
                        mapOf(
                            "koji kondo" to listOf(LastFmTag("video game music", 100), LastFmTag("soundtrack", 90)),
                        ),
                )
            val filter = SpaceContentFilter(provider)
            val track = song("1", "Lost Woods", "Koji Kondo")

            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(track, SpaceFilterSpec(genres = listOf("soundtracks")), SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(track, SpaceFilterSpec(genres = listOf("soundtracks_video_game")), SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.REJECT_GENRE_CONFLICT,
                filter.evaluate(track, SpaceFilterSpec(genres = listOf("soundtracks_movie")), SpaceCandidateSource.HOME_FEED),
            )
        }

    @Test
    fun cleanMediaTitleStripsYouTubeSuffixes() {
        assertEquals(
            "HUMBLE.",
            LastFmSpaceTagProvider.cleanMediaTitle("HUMBLE. (Official Music Video)"),
        )
        assertEquals(
            "Sicko Mode",
            LastFmSpaceTagProvider.cleanMediaTitle("Sicko Mode (feat. Drake) [Official Audio]"),
        )
    }
}
