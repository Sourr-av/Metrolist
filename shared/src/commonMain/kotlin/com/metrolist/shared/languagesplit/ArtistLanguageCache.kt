/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.languagesplit

import com.metrolist.shared.db.entities.ArtistLanguage

/**
 * In-memory per-artist language cache loaded once from Room at startup.
 * Copy-on-write so the per-song classification path never touches disk.
 * USER rows (manual overrides) are never overwritten by learned rows.
 */
class ArtistLanguageCache {
    data class Entry(
        val language: LanguageClass,
        val source: String,
    )

    @Volatile
    private var entries: Map<String, Entry> = emptyMap()

    fun get(artistId: String): Entry? = entries[artistId]

    fun put(
        artistId: String,
        language: LanguageClass,
        source: String,
    ) {
        val existing = entries[artistId]
        if (existing != null && existing.source == SOURCE_USER && source != SOURCE_USER) return
        entries = entries + (artistId to Entry(language, source))
    }

    fun load(rows: List<ArtistLanguage>) {
        if (rows.isEmpty()) return
        val merged = entries.toMutableMap()
        for (row in rows) {
            val language = runCatching { LanguageClass.valueOf(row.language) }.getOrNull() ?: continue
            val existing = merged[row.artistId]
            if (existing != null && existing.source == SOURCE_USER && row.source != SOURCE_USER) continue
            merged[row.artistId] = Entry(language, row.source)
        }
        entries = merged
    }

    fun clear() {
        entries = emptyMap()
    }

    companion object {
        const val SOURCE_USER = "USER"

        val shared = ArtistLanguageCache()
    }
}
