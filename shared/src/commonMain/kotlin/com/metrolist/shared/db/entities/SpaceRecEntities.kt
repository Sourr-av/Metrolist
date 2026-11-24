/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "artist_profile")
data class ArtistProfileEntity(
    @PrimaryKey val artistId: String,
    val name: String,
    val languageVotesJson: String,
    val totalVotes: Float,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'{}'") val genreVotesJson: String = "{}",
)

@Entity(tableName = "track_class")
data class TrackClassEntity(
    @PrimaryKey val videoId: String,
    val language: String?,
    val languageConfidence: Float,
    val genre: String?,
    val evidence: String,
    val checkedAt: Long,
)

@Entity(tableName = "space_feed_cache")
data class SpaceFeedCacheEntity(
    @PrimaryKey val spaceId: String,
    val payloadJson: String,
    val savedAt: Long,
    val refreshSeq: Int,
)

@Entity(
    tableName = "space_shown",
    primaryKeys = ["spaceId", "videoId"],
    indices = [
        Index(value = ["spaceId", "refreshSeq"]),
    ],
)
data class SpaceShownEntity(
    val spaceId: String,
    val videoId: String,
    val refreshSeq: Int,
    val shownAt: Long,
)

@Entity(
    tableName = "space_seed_arm",
    primaryKeys = ["spaceId", "artistId"],
)
data class SpaceSeedArmEntity(
    val spaceId: String,
    val artistId: String,
    val seedVideoId: String,
    val completions: Int,
    val skips: Int,
    val lastUsedSeq: Int,
)

@Entity(
    tableName = "space_play_event",
    indices = [
        Index(value = ["spaceId", "playedAt"]),
        Index(value = ["videoId"]),
    ],
)
data class SpacePlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val spaceId: String,
    val seedArtistId: String?,
    val source: String,
    val playedAt: Long,
)
