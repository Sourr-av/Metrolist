/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "other_language_item")
data class OtherLanguageItem(
    @PrimaryKey val videoId: String,
    val payload: String,
    val addedAt: Long,
)
