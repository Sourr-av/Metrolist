/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeSpacesTest {
    private fun createStore(
        initialSpaces: String = "[]",
        initialActiveId: String = "",
        clock: () -> Long = { 1000L },
    ): HomeSpaceStore {
        var spacesJson = initialSpaces
        var activeId = initialActiveId
        return HomeSpaceStore(
            readSpaces = { spacesJson },
            writeSpaces = { spacesJson = it },
            readActiveId = { activeId },
            writeActiveId = { activeId = it },
            clock = clock,
        )
    }

    @Test
    fun createPersistsSpaceAssignsUniqueIdsAndSelectsIt() {
        val store = createStore()
        val first = store.create("Workout", "Space")
        val second = store.create("   ", "Space")
        val third = store.create("", "Space")

        assertEquals("Workout", first.name)
        assertEquals("Space 1", second.name)
        assertEquals("Space 2", third.name)
        assertEquals(listOf(first, second, third), store.all())
        assertEquals(third.id, store.activeId())
    }

    @Test
    fun renameUpdatesNameAndIgnoresBlankInput() {
        val store = createStore()
        val space = store.create("Focus", "Space")

        store.rename(space.id, "Deep Focus")
        assertEquals("Deep Focus", store.all().single().name)

        store.rename(space.id, "   ")
        assertEquals("Deep Focus", store.all().single().name)
    }

    @Test
    fun deleteRemovesSpaceAndClearsActiveSelectionOnlyWhenActive() {
        val store = createStore()
        val first = store.create("Chill", "Space")
        val second = store.create("Drive", "Space")

        store.select(first.id)
        assertEquals(first.id, store.activeId())

        store.delete(second.id)
        assertEquals(listOf(first), store.all())
        assertEquals(first.id, store.activeId())

        store.delete(first.id)
        assertEquals(emptyList(), store.all())
        assertNull(store.activeId())
    }

    @Test
    fun malformedStorageAndStaleActiveIdRecoverGracefully() {
        val store = createStore(initialSpaces = "not-json", initialActiveId = "missing")
        assertEquals(emptyList(), store.all())
        assertNull(store.activeId())
        assertEquals("Space 1", store.nextDefaultName("Space"))
    }

    @Test
    fun createAndUpdatePersistNormalizedLanguageAndGenreFilters() {
        val store = createStore()
        val created =
            store.create(
                name = "English Hip-Hop",
                defaultPrefix = "Space",
                languages = listOf("english", " English ", ""),
                genres = listOf("hip_hop", "trap", "hip_hop"),
            )

        assertTrue(created.hasFilters)
        assertEquals(listOf("english"), created.languages)
        assertEquals(listOf("hip_hop", "trap"), created.genres)

        store.update(
            id = created.id,
            newName = "Japanese Soundtracks",
            languages = listOf("japanese"),
            genres = listOf("soundtracks_video_game"),
        )
        val updated = store.all().single()
        assertEquals("Japanese Soundtracks", updated.name)
        assertEquals(listOf("japanese"), updated.languages)
        assertEquals(listOf("soundtracks_video_game"), updated.genres)
    }

    @Test
    fun decodesLegacySpacesWithoutFiltersField() {
        val store = createStore(initialSpaces = """[{"id":"space_1","name":"Classic"}]""")
        val space = store.all().single()
        assertEquals("Classic", space.name)
        assertFalse(space.hasFilters)
        assertEquals(emptyList(), space.languages)
        assertEquals(emptyList(), space.genres)
    }
}
