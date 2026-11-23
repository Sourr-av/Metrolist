/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.metrolist.shared.db.entities.ArtistProfileEntity
import com.metrolist.shared.db.entities.SpaceFeedCacheEntity
import com.metrolist.shared.db.entities.SpacePlayEventEntity
import com.metrolist.shared.db.entities.SpaceSeedArmEntity
import com.metrolist.shared.db.entities.SpaceShownEntity
import com.metrolist.shared.db.entities.TrackClassEntity

/**
 * Joined row from `event LEFT JOIN song LEFT JOIN song_artist_map` so completion
 * (`playTimeMs >= 30_000 || playTimeMs >= 0.5 * durationSeconds * 1000`), heard exclusions,
 * and artist affinity can be computed cleanly in pure Kotlin.
 */
data class SpacePlayHistoryJoinedRow(
    val eventId: Long,
    val videoId: String,
    val playTimeMs: Long,
    val playedAtMs: Long,
    val durationSeconds: Int,
    val artistId: String?,
)

@Dao
interface SpaceRecDao {
    // --- artist_profile ---

    @Query("SELECT * FROM artist_profile WHERE artistId = :artistId")
    suspend fun getArtistProfile(artistId: String): ArtistProfileEntity?

    @Query("SELECT * FROM artist_profile WHERE artistId IN (:artistIds)")
    suspend fun getArtistProfiles(artistIds: List<String>): List<ArtistProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistProfile(entity: ArtistProfileEntity)

    // --- track_class ---

    @Query("SELECT * FROM track_class WHERE videoId = :videoId")
    suspend fun getTrackClass(videoId: String): TrackClassEntity?

    @Query("SELECT * FROM track_class WHERE videoId IN (:videoIds)")
    suspend fun getTrackClasses(videoIds: List<String>): List<TrackClassEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackClass(entity: TrackClassEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackClasses(entities: List<TrackClassEntity>)

    // --- space_feed_cache ---

    @Query("SELECT * FROM space_feed_cache WHERE spaceId = :spaceId")
    suspend fun getFeedCache(spaceId: String): SpaceFeedCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeedCache(entity: SpaceFeedCacheEntity)

    @Query("DELETE FROM space_feed_cache WHERE spaceId = :spaceId")
    suspend fun deleteFeedCache(spaceId: String)

    // --- space_shown ---

    @Query(
        """
        SELECT videoId FROM space_shown
        WHERE spaceId = :spaceId AND refreshSeq >= :minRefreshSeq
        """,
    )
    suspend fun getRecentlyShownVideoIds(
        spaceId: String,
        minRefreshSeq: Int,
    ): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShown(entries: List<SpaceShownEntity>)

    @Query("DELETE FROM space_shown WHERE spaceId = :spaceId AND refreshSeq < :minRefreshSeq")
    suspend fun pruneShownBefore(
        spaceId: String,
        minRefreshSeq: Int,
    )

    @Query("DELETE FROM space_shown WHERE spaceId = :spaceId")
    suspend fun clearShownForSpace(spaceId: String)

    // --- space_seed_arm ---

    @Query("SELECT * FROM space_seed_arm WHERE spaceId = :spaceId")
    suspend fun getSeedArms(spaceId: String): List<SpaceSeedArmEntity>

    @Query("SELECT * FROM space_seed_arm WHERE spaceId = :spaceId AND artistId = :artistId")
    suspend fun getSeedArm(
        spaceId: String,
        artistId: String,
    ): SpaceSeedArmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeedArm(entity: SpaceSeedArmEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeedArms(entities: List<SpaceSeedArmEntity>)

    @Query("DELETE FROM space_seed_arm WHERE spaceId = :spaceId")
    suspend fun deleteSeedArmsForSpace(spaceId: String)

    // --- space_play_event ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlayEvent(event: SpacePlayEventEntity): Long

    @Query(
        """
        SELECT * FROM space_play_event
        WHERE spaceId = :spaceId AND playedAt >= :sinceMs
        ORDER BY playedAt DESC
        """,
    )
    suspend fun getRecentPlayEventsForSpace(
        spaceId: String,
        sinceMs: Long,
    ): List<SpacePlayEventEntity>

    @Query(
        """
        SELECT * FROM space_play_event
        WHERE videoId = :videoId AND playedAt >= :sinceMs
        ORDER BY playedAt DESC
        """,
    )
    suspend fun getPlayEventsForSongSince(
        videoId: String,
        sinceMs: Long,
    ): List<SpacePlayEventEntity>

    @Query("DELETE FROM space_play_event WHERE playedAt < :cutoffMs")
    suspend fun prunePlayEventsBefore(cutoffMs: Long)

    // --- event JOIN song JOIN song_artist_map ---

    @Query(
        """
        SELECT
            e.id AS eventId,
            e.songId AS videoId,
            e.playTime AS playTimeMs,
            e.timestamp AS playedAtMs,
            COALESCE(s.duration, -1) AS durationSeconds,
            sam.artistId AS artistId
        FROM event e
        LEFT JOIN song s ON s.id = e.songId
        LEFT JOIN song_artist_map sam ON sam.songId = e.songId
        WHERE e.timestamp >= :sinceTimestampMs
        ORDER BY e.timestamp DESC, sam.position ASC
        """,
    )
    suspend fun getPlayHistoryWithDurationSince(sinceTimestampMs: Long): List<SpacePlayHistoryJoinedRow>

    @Query("SELECT DISTINCT songId FROM event")
    suspend fun getAllEverPlayedVideoIds(): List<String>
}
