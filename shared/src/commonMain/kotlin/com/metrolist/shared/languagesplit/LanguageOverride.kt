/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.languagesplit

import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.ArtistLanguage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.utils.getCurrentTimeMillis

/**
 * Manual override from the song menu: writes the song's primary artist with
 * source USER, which beats every other source. Applies on the next refresh —
 * the list the user is looking at is never reshuffled here.
 */
suspend fun MusicDatabase.applyLanguageOverride(
    song: SongItem,
    language: LanguageClass,
) {
    val artistId = song.artists.firstOrNull { !it.id.isNullOrBlank() }?.id ?: return
    val row = ArtistLanguage(artistId, language.name, ArtistLanguageCache.SOURCE_USER, getCurrentTimeMillis())
    languageSplitDao().upsertArtistLanguages(listOf(row))
    ArtistLanguageCache.shared.put(artistId, language, ArtistLanguageCache.SOURCE_USER)
}
