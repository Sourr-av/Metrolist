/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import com.metrolist.shared.utils.getCurrentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HomeSpace(
    val id: String,
    val name: String,
)

class HomeSpaceStore(
    private val readSpaces: () -> String,
    private val writeSpaces: (String) -> Unit,
    private val readActiveId: () -> String,
    private val writeActiveId: (String) -> Unit,
    private val clock: () -> Long = ::getCurrentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<HomeSpace> {
        val raw = readSpaces()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<HomeSpace>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
    }

    fun activeId(spaces: List<HomeSpace> = all()): String? {
        val saved = readActiveId().trim()
        if (saved.isBlank()) return null
        return saved.takeIf { id -> spaces.any { it.id == id } }
    }

    fun nextDefaultName(
        prefix: String,
        spaces: List<HomeSpace> = all(),
    ): String {
        val base = prefix.trim().ifBlank { "Space" }
        val existingNames = spaces.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
        var index = 1
        while ("$base $index".lowercase() in existingNames) {
            index++
        }
        return "$base $index"
    }

    fun create(
        name: String,
        defaultPrefix: String = "Space",
    ): HomeSpace {
        val current = all()
        val resolvedName = name.trim().ifBlank { nextDefaultName(defaultPrefix, current) }
        val existingIds = current.mapTo(mutableSetOf()) { it.id }
        val baseId = "space_${clock()}"
        var candidateId = baseId
        var suffix = 1
        while (candidateId in existingIds) {
            candidateId = "${baseId}_$suffix"
            suffix++
        }
        val created = HomeSpace(id = candidateId, name = resolvedName)
        save(current + created)
        writeActiveId(created.id)
        return created
    }

    fun rename(
        id: String,
        newName: String,
    ): List<HomeSpace> {
        val trimmed = newName.trim()
        val current = all()
        if (trimmed.isBlank()) return current
        val updated = current.map { space -> if (space.id == id) space.copy(name = trimmed) else space }
        if (updated != current) {
            save(updated)
        }
        return updated
    }

    fun delete(id: String): List<HomeSpace> {
        val current = all()
        val updated = current.filterNot { it.id == id }
        if (updated.size != current.size) {
            save(updated)
        }
        if (readActiveId().trim() == id) {
            writeActiveId("")
        }
        return updated
    }

    fun select(id: String?): String? {
        val resolved = id?.trim()?.takeIf { candidate -> all().any { it.id == candidate } }
        writeActiveId(resolved.orEmpty())
        return resolved
    }

    private fun save(spaces: List<HomeSpace>) {
        writeSpaces(json.encodeToString(spaces))
    }
}

object HomeSpaces {
    private val store =
        HomeSpaceStore(
            readSpaces = { Settings.shared.homeSpacesJson },
            writeSpaces = { Settings.shared.homeSpacesJson = it },
            readActiveId = { Settings.shared.activeHomeSpaceId },
            writeActiveId = { Settings.shared.activeHomeSpaceId = it },
        )

    fun all(): List<HomeSpace> = store.all()

    fun activeId(spaces: List<HomeSpace> = all()): String? = store.activeId(spaces)

    fun nextDefaultName(
        prefix: String,
        spaces: List<HomeSpace> = all(),
    ): String = store.nextDefaultName(prefix, spaces)

    fun create(
        name: String,
        defaultPrefix: String = "Space",
    ): HomeSpace = store.create(name, defaultPrefix)

    fun rename(
        id: String,
        newName: String,
    ): List<HomeSpace> = store.rename(id, newName)

    fun delete(id: String): List<HomeSpace> = store.delete(id)

    fun select(id: String?): String? = store.select(id)
}
