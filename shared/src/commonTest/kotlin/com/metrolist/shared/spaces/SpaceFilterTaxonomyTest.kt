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
    fun scriptAndLatinDetectorsIdentifyLanguages() {
        assertEquals(setOf("japanese"), SpaceFilterTaxonomy.detectLanguagesFromScript("夜に駆ける YOASOBI"))
        assertEquals(setOf("korean"), SpaceFilterTaxonomy.detectLanguagesFromScript("봄날 BTS"))
        assertEquals(setOf("hindi"), SpaceFilterTaxonomy.detectLanguagesFromScript("तुम ही हो Arijit Singh"))
        assertEquals(setOf("malayalam"), SpaceFilterTaxonomy.detectLanguagesFromScript("മലയാളം പാട്ട്"))
        assertTrue("spanish" in SpaceFilterTaxonomy.detectLatinLanguageHints("Corazón Sin Vida con Amor"))
    }

    @Test
    fun matchingChipsAndFallbackQueriesAlignWithFilter() {
        val chips =
            listOf(
                Chip(title = "Podcasts", params = "p1"),
                Chip(title = "Relax", params = "p2"),
                Chip(title = "Hip-hop", params = "p3"),
            )
        val matched = SpaceFilterTaxonomy.findMatchingChips(chips, listOf("hip_hop"))
        assertEquals("Hip-hop", matched.first().title)

        val queries =
            SpaceFilterTaxonomy.buildFallbackSearchQueries(
                SpaceFilterSpec(languages = listOf("english"), genres = listOf("hip_hop")),
            )
        assertTrue(queries.any { it.contains("english") && it.contains("hip hop") })
    }

    @Test
    fun parsesLastFmTopTagsArraySingleObjectAndErrorSix() {
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
    }
}
