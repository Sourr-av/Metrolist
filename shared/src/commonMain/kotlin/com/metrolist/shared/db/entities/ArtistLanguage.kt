/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "artist_language")
data class ArtistLanguage(
    @PrimaryKey val artistId: String,
    val language: String,
    val source: String,
    val updatedAt: Long,
)
