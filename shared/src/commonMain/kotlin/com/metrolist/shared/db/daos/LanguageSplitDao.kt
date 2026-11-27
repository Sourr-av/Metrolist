/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.metrolist.shared.db.entities.ArtistLanguage
import com.metrolist.shared.db.entities.OtherLanguageItem

@Dao
interface LanguageSplitDao {
    @Query("SELECT * FROM artist_language")
    suspend fun getAllArtistLanguages(): List<ArtistLanguage>

    @Query("SELECT * FROM artist_language WHERE artistId IN (:artistIds)")
    suspend fun getArtistLanguages(artistIds: List<String>): List<ArtistLanguage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistLanguages(rows: List<ArtistLanguage>)

    @Query("SELECT * FROM other_language_item ORDER BY addedAt DESC, videoId DESC")
    suspend fun getAllOtherLanguageItems(): List<OtherLanguageItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOtherLanguageItems(rows: List<OtherLanguageItem>)

    @Query("DELETE FROM other_language_item WHERE videoId NOT IN (:keepIds)")
    suspend fun deleteOtherLanguageItemsExcept(keepIds: List<String>)

    @Query("DELETE FROM other_language_item")
    suspend fun deleteAllOtherLanguageItems()

    @Query(
        "DELETE FROM other_language_item WHERE videoId NOT IN " +
            "(SELECT videoId FROM other_language_item ORDER BY addedAt DESC, videoId DESC LIMIT :cap)",
    )
    suspend fun trimOtherLanguageItems(cap: Int)
}
