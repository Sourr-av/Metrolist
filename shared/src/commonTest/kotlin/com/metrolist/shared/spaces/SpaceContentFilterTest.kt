/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spaces

import com.metrolist.shared.api.lastfm.LastFmArtistProfile
import com.metrolist.shared.api.lastfm.LastFmTag
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.SpaceFilterSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceContentFilterTest {
    private class FakeTagProvider(
        private val trackMap: Map<Pair<String, String>, List<LastFmTag>> = emptyMap(),
        private val artistMap: Map<String, List<LastFmTag>> = emptyMap(),
        private val albumMap: Map<Pair<String, String>, List<LastFmTag>> = emptyMap(),
        private val profileMap: Map<String, LastFmArtistProfile> = emptyMap(),
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

        override suspend fun artistProfile(artist: String): LastFmArtistProfile? = profileMap[artist.lowercase()]
    }

    private fun song(
        id: String,
        title: String,
        vararg artists: String,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            thumbnail = null,
            artists = artists.mapIndexed { idx, name -> ArtistTiny(id = "a_${id}_$idx", name = name) },
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
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(song("2", "MONACO", "Bad Bunny"), spec, SpaceCandidateSource.TARGETED_SEARCH),
            )
        }

    @Test
    fun artistBioVerifiesEnglishHipHopAndRejectsForeignRappersWithGenericHipHopTags() =
        runTest {
            val provider =
                FakeTagProvider(
                    profileMap =
                        mapOf(
                            "travis scott" to
                                LastFmArtistProfile(
                                    tags = listOf(LastFmTag("hip-hop", 100), LastFmTag("trap", 88), LastFmTag("rap", 76)),
                                    bioSummary = "Travis Scott is an American rapper and record producer from Houston, Texas.",
                                ),
                            "seedhe maut" to
                                LastFmArtistProfile(
                                    tags = listOf(LastFmTag("hip-hop", 100), LastFmTag("rap", 88)),
                                    bioSummary = "Seedhe Maut is an Indian hip-hop duo from New Delhi.",
                                ),
                            "jul" to
                                LastFmArtistProfile(
                                    tags = listOf(LastFmTag("hip-hop", 100), LastFmTag("rap", 88)),
                                    bioSummary = "Jul is a French rapper from Marseille.",
                                ),
                        ),
                )
            val filter = SpaceContentFilter(provider)
            val spec = SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop"))

            // Even with a single non-dictionary title and no track tags, artist bio + tags verify English Hip-Hop!
            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(song("1", "FE!N", "Travis Scott"), spec, SpaceCandidateSource.HOME_FEED),
            )
            // Foreign rappers tagged only ["hip-hop", "rap"] without language tags MUST be rejected via bio!
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(song("2", "Nanchaku", "Seedhe Maut"), spec, SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(song("3", "Tchikita", "Jul"), spec, SpaceCandidateSource.HOME_FEED),
            )
        }

    @Test
    fun weakTrackTagDoesNotBlockUserHipHopArtistTrack() =
        runTest {
            val provider =
                FakeTagProvider(
                    profileMap =
                        mapOf(
                            "kanye west" to
                                LastFmArtistProfile(
                                    tags = listOf(LastFmTag("hip-hop", 100), LastFmTag("rap", 90), LastFmTag("pop", 60), LastFmTag("rnb", 50)),
                                    bioSummary = "Kanye West is an American rapper and producer from Chicago.",
                                ),
                        ),
                    trackMap =
                        mapOf(
                            ("kanye west" to "flashing lights") to listOf(LastFmTag("electronic", 20)),
                            ("kanye west" to "only one") to listOf(LastFmTag("pop", 95), LastFmTag("ballad", 80)),
                        ),
                )
            val filter = SpaceContentFilter(provider)
            val spec = SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop"))

            // Weak unrelated track tag (count=20) should NOT override strong hip-hop artist profile.
            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(song("1", "Flashing Lights", "Kanye West"), spec, SpaceCandidateSource.HOME_FEED),
            )
            // Strong unrelated track tag (count=95) with zero hip-hop tag on a crossover artist still gets filtered out.
            assertEquals(
                SpaceFilterDecision.REJECT_GENRE_CONFLICT,
                filter.evaluate(song("2", "Only One", "Kanye West"), spec, SpaceCandidateSource.HOME_FEED),
            )
        }

    @Test
    fun collaboratorForeignLanguageIsRejectedFromEnglishSpace() =
        runTest {
            val provider =
                FakeTagProvider(
                    profileMap =
                        mapOf(
                            "metro boomin" to
                                LastFmArtistProfile(
                                    tags = listOf(LastFmTag("hip-hop", 100), LastFmTag("trap", 90)),
                                    bioSummary = "Metro Boomin is an American record producer.",
                                ),
                            "bad bunny" to
                                LastFmArtistProfile(
                                    tags = listOf(LastFmTag("spanish", 100), LastFmTag("reggaeton", 90)),
                                    bioSummary = "Bad Bunny is a Puerto Rican rapper and singer.",
                                ),
                        ),
                )
            val filter = SpaceContentFilter(provider)
            val spec = SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop"))

            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(song("1", "Midnight Track", "Metro Boomin", "Bad Bunny"), spec, SpaceCandidateSource.HOME_FEED),
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
    fun untaggedEnglishRulesAreAggressiveAgainstRomanizedForeignTitles() =
        runTest {
            val provider = FakeTagProvider()
            val filter = SpaceContentFilter(provider)
            val spec = SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop"))

            val clearEnglish = song("1", "Never Back Down Tonight", "Indie MC")
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_UNVERIFIED,
                filter.evaluate(clearEnglish, spec, SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(clearEnglish, spec, SpaceCandidateSource.TARGETED_SEARCH),
            )

            // Ambiguous / Romanized non-English untagged titles MUST be rejected even in TARGETED_SEARCH!
            val romanizedForeign = song("2", "Cheques", "Unknown Artist")
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_UNVERIFIED,
                filter.evaluate(romanizedForeign, spec, SpaceCandidateSource.TARGETED_SEARCH),
            )

            val japaneseSong = song("3", "新しい曲", "日本人")
            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT,
                filter.evaluate(japaneseSong, spec, SpaceCandidateSource.TARGETED_SEARCH),
            )
        }

    @Test
    fun genericLanguageLessPlaylistIsRejectedWhenLanguageFilterActive() =
        runTest {
            val filter = SpaceContentFilter(FakeTagProvider())
            val spec = SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop"))

            val genericPlaylist = PlaylistItem(id = "PL1", title = "Hip-Hop Hits", author = "YouTube Music", thumbnail = null)
            val explicitEnglishPlaylist = PlaylistItem(id = "PL2", title = "US Hip-Hop Essentials", author = "YouTube Music", thumbnail = null)

            assertEquals(
                SpaceFilterDecision.REJECT_LANGUAGE_UNVERIFIED,
                filter.evaluate(genericPlaylist, spec, SpaceCandidateSource.HOME_FEED),
            )
            assertEquals(
                SpaceFilterDecision.ALLOW,
                filter.evaluate(explicitEnglishPlaylist, spec, SpaceCandidateSource.HOME_FEED),
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
    fun refreshOrderingFloatsUnseenItemsAheadOfPreviouslyShown() {
        val items =
            listOf(
                song("1", "Old Song 1", "Artist"),
                song("2", "Old Song 2", "Artist"),
                song("3", "Fresh Song 3", "Artist"),
                song("4", "Fresh Song 4", "Artist"),
            )
        val reordered =
            SpaceFeedSupplement.orderItemsForRefresh(
                items = items,
                refreshSeed = 1,
                previousItemIds = setOf("1", "2"),
            )
        assertTrue(reordered.take(2).map { it.id }.toSet() == setOf("3", "4"))
        assertTrue(reordered.drop(2).map { it.id }.toSet() == setOf("1", "2"))
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
