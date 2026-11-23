/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spaces

import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lastfm.LastFmTag
import com.metrolist.shared.model.Chip
import com.metrolist.shared.settings.SpaceFilterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpaceFilterTaxonomyTest {
    @Test
    fun searchingSoundtracksReturnsAllThreeOptions() {
        val results = SpaceFilterTaxonomy.searchGenres("soundtracks")
        val ids = results.take(3).map { it.id }
        assertEquals(
            listOf("soundtracks", "soundtracks_video_game", "soundtracks_movie"),
            ids,
        )
    }

    @Test
    fun popRockMatchesBothPopAndRockSpaces() {
        val tags = listOf("pop rock")
        assertTrue(SpaceFilterTaxonomy.tagsMatchRequestedGenre(tags, "pop"))
        assertTrue(SpaceFilterTaxonomy.tagsMatchRequestedGenre(tags, "rock"))
        assertFalse(SpaceFilterTaxonomy.tagsMatchRequestedGenre(tags, "hip_hop"))
    }

    @Test
    fun soundtrackHierarchyRespectsAllVsVideoGameVsMovie() {
        val vgmTags = listOf("video game music", "soundtrack")
        assertTrue(SpaceFilterTaxonomy.tagsMatchRequestedGenre(vgmTags, "soundtracks"))
        assertTrue(SpaceFilterTaxonomy.tagsMatchRequestedGenre(vgmTags, "soundtracks_video_game"))
        assertFalse(SpaceFilterTaxonomy.tagsMatchRequestedGenre(vgmTags, "soundtracks_movie"))

        val movieTags = listOf("film score", "movie soundtrack")
        assertTrue(SpaceFilterTaxonomy.tagsMatchRequestedGenre(movieTags, "soundtracks"))
        assertTrue(SpaceFilterTaxonomy.tagsMatchRequestedGenre(movieTags, "soundtracks_movie"))
        assertFalse(SpaceFilterTaxonomy.tagsMatchRequestedGenre(movieTags, "soundtracks_video_game"))
    }

    @Test
    fun scriptAndLatinDetectorsIdentifyLanguagesAggressively() {
        assertEquals(setOf("japanese"), SpaceFilterTaxonomy.detectLanguagesFromScript("夜に駆ける YOASOBI"))
        assertEquals(setOf("korean"), SpaceFilterTaxonomy.detectLanguagesFromScript("봄날 BTS"))
        assertEquals(setOf("hindi"), SpaceFilterTaxonomy.detectLanguagesFromScript("तुम ही हो Arijit Singh"))
        assertEquals(setOf("malayalam"), SpaceFilterTaxonomy.detectLanguagesFromScript("മലയാളം പാട്ട്"))
        assertTrue("spanish" in SpaceFilterTaxonomy.detectLatinLanguageHints("Corazón Sin Vida con Amor"))
        assertTrue("punjabi" in SpaceFilterTaxonomy.detectLatinLanguageHints("Softly Karan Aujla"))
        assertTrue("malayalam" in SpaceFilterTaxonomy.detectLatinLanguageHints("Illuminati Sushin Shyam Dabzee"))
        assertTrue("hindi" in SpaceFilterTaxonomy.detectLatinLanguageHints("Gully Boy Mere Gully Mein"))
        assertTrue(SpaceFilterTaxonomy.hasNonEnglishLatinDiacritics("Tití Me Preguntó"))
        assertFalse(SpaceFilterTaxonomy.hasNonEnglishLatinDiacritics("Café Society"))
    }

    @Test
    fun bioLanguageDetectorIdentifiesOriginsWithoutDescentFalsePositives() {
        assertEquals(
            setOf("english"),
            SpaceFilterTaxonomy.detectLanguagesFromBio("Kendrick Lamar is an American rapper and songwriter from Compton, California."),
        )
        assertEquals(
            setOf("english"),
            SpaceFilterTaxonomy.detectLanguagesFromBio("Big Pun was an American rapper of Puerto Rican descent from the Bronx."),
        )
        assertTrue(
            "hindi" in SpaceFilterTaxonomy.detectLanguagesFromBio("DIVINE is an Indian rapper from Mumbai."),
        )
        assertTrue(
            "spanish" in SpaceFilterTaxonomy.detectLanguagesFromBio("Bad Bunny is a Puerto Rican rapper and singer."),
        )
        assertTrue(
            "malayalam" in SpaceFilterTaxonomy.detectLanguagesFromBio("Vedan is a Malayalam rapper from Kerala."),
        )
    }

    @Test
    fun matchingChipsAndRotatingFallbackQueriesAlignWithFilter() {
        val chips =
            listOf(
                Chip(title = "Podcasts", params = "p1"),
                Chip(title = "Relax", params = "p2"),
                Chip(title = "Hip-hop", params = "p3"),
            )
        val matched = SpaceFilterTaxonomy.findMatchingChips(chips, listOf("hip_hop"))
        assertEquals("Hip-hop", matched.first().title)

        val spec = SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop"))
        val queries0 = SpaceFilterTaxonomy.buildFallbackSearchQueries(spec, rotationSeed = 0)
        val queries1 = SpaceFilterTaxonomy.buildFallbackSearchQueries(spec, rotationSeed = 1)
        assertTrue(queries0.any { it.contains("english") && it.contains("hip hop") })
        assertNotEquals(queries0.first(), queries1.first())
    }

    @Test
    fun parsesLastFmTopTagsAndArtistProfileResponses() {
        val arrayJson = """{"toptags":{"tag":[{"name":"Hip-Hop","count":100},{"name":"rap","count":"85"}]}}"""
        assertEquals(
            listOf(LastFmTag("Hip-Hop", 100), LastFmTag("rap", 85)),
            LastFM.parseTopTagsResponse(arrayJson),
        )

        val singleJson = """{"toptags":{"tag":{"name":"pop rock","count":70}}}"""
        assertEquals(
            listOf(LastFmTag("pop rock", 70)),
            LastFM.parseTopTagsResponse(singleJson),
        )

        val notFoundJson = """{"error":6,"message":"Track not found"}"""
        assertEquals(emptyList(), LastFM.parseTopTagsResponse(notFoundJson))

        val profileJson =
            """{"artist":{"name":"Kendrick Lamar","tags":{"tag":[{"name":"hip-hop"},{"name":"rap"}]},"bio":{"summary":"Kendrick Lamar is an American rapper. <a href=\"https://last.fm\">Read more on Last.fm</a>"}}}"""
        val profile = LastFM.parseArtistProfileResponse(profileJson)!!
        assertEquals(listOf("hip-hop", "rap"), profile.tags.map { it.name })
        assertEquals("Kendrick Lamar is an American rapper.", profile.bioSummary)
    }
}
