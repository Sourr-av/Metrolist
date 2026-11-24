/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.db.daos.SpacePlayHistoryJoinedRow
import com.metrolist.shared.db.daos.SpaceRecDao
import com.metrolist.shared.db.entities.ArtistProfileEntity
import com.metrolist.shared.db.entities.SpaceSeedArmEntity
import com.metrolist.shared.db.entities.TrackClassEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PreloadedSpaceMetadata(
    val trackClasses: Map<String, TrackClassRecord>,
    val artistProfiles: Map<String, ArtistProfileRecord>,
) {
    val trackClassLookup: TrackClassLookup =
        object : TrackClassLookup {
            override suspend fun getTrackClass(videoId: String): TrackClassRecord? = trackClasses[videoId]
        }

    val artistProfileLookup: ArtistProfileLookup =
        object : ArtistProfileLookup {
            override suspend fun getArtistProfile(artistId: String): ArtistProfileRecord? = artistProfiles[artistId]
        }
}

/**
 * Room3 / JSON adapter helpers for [SpaceRecEngine]:
 * - Chunked (`<= 500`) batch preloading of `track_class` and `artist_profile` to stay well
 *   below SQLite's bind-variable limit and turn a 150-song pool into 2 DB calls instead of 300.
 * - Pure conversions between Room entities and domain records.
 */
object SpaceRecDbAdapter {
    const val BATCH_CHUNK_SIZE = 500

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun preload(
        dao: SpaceRecDao,
        videoIds: Collection<String>,
        artistIds: Collection<String>,
    ): PreloadedSpaceMetadata {
        val distinctVideos =
            videoIds
                .asSequence()
                .filter(String::isNotBlank)
                .distinct()
                .toList()
        val distinctArtists =
            artistIds
                .asSequence()
                .filter(String::isNotBlank)
                .distinct()
                .toList()

        val tracks = HashMap<String, TrackClassRecord>(distinctVideos.size)
        for (chunk in distinctVideos.chunked(BATCH_CHUNK_SIZE)) {
            for (entity in dao.getTrackClasses(chunk)) {
                tracks[entity.videoId] = entity.toRecord()
            }
        }

        val artists = HashMap<String, ArtistProfileRecord>(distinctArtists.size)
        for (chunk in distinctArtists.chunked(BATCH_CHUNK_SIZE)) {
            for (entity in dao.getArtistProfiles(chunk)) {
                artists[entity.artistId] = entity.toRecord()
            }
        }

        return PreloadedSpaceMetadata(trackClasses = tracks, artistProfiles = artists)
    }

    fun groupPlayHistory(rows: List<SpacePlayHistoryJoinedRow>): List<PlayRecord> {
        if (rows.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<Long, MutableList<SpacePlayHistoryJoinedRow>>(rows.size)
        for (row in rows) {
            grouped.getOrPut(row.eventId) { mutableListOf() }.add(row)
        }
        return grouped.values.map { eventRows ->
            val first = eventRows.first()
            val artistIds =
                eventRows
                    .mapNotNull { it.artistId?.takeIf(String::isNotBlank) }
                    .distinct()
            val durationMs =
                first.durationSeconds
                    .takeIf { it > 0 }
                    ?.toLong()
                    ?.times(1000L)
            PlayRecord(
                videoId = first.videoId,
                artistIds = artistIds,
                playedMs = first.playTimeMs,
                durationMs = durationMs,
                playedAtMs = first.playedAtMs,
            )
        }
    }

    fun TrackClassEntity.toRecord(): TrackClassRecord =
        TrackClassRecord(
            videoId = videoId,
            language = language,
            languageConfidence = languageConfidence.toDouble(),
            genre = genre,
            evidence = evidence,
            checkedAt = checkedAt,
        )

    fun TrackClassRecord.toEntity(): TrackClassEntity =
        TrackClassEntity(
            videoId = videoId,
            language = language,
            languageConfidence = languageConfidence.toFloat(),
            genre = genre,
            evidence = evidence,
            checkedAt = checkedAt,
        )

    fun ArtistProfileEntity.toRecord(): ArtistProfileRecord =
        ArtistProfileRecord(
            artistId = artistId,
            name = name,
            languageVotes = decodeVotes(languageVotesJson),
            totalVotes = totalVotes.toDouble(),
            updatedAt = updatedAt,
            genreVotes = decodeVotes(genreVotesJson),
        )

    fun ArtistProfileRecord.toEntity(): ArtistProfileEntity =
        ArtistProfileEntity(
            artistId = artistId,
            name = name,
            languageVotesJson = encodeVotes(languageVotes),
            totalVotes = totalVotes.toFloat(),
            updatedAt = updatedAt,
            genreVotesJson = encodeVotes(genreVotes),
        )

    fun SpaceSeedArmEntity.toState(): SeedArmState =
        SeedArmState(
            spaceId = spaceId,
            artistId = artistId,
            seedVideoId = seedVideoId,
            completions = completions,
            skips = skips,
            lastUsedSeq = lastUsedSeq,
        )

    fun SeedArmState.toEntity(): SpaceSeedArmEntity =
        SpaceSeedArmEntity(
            spaceId = spaceId,
            artistId = artistId,
            seedVideoId = seedVideoId,
            completions = completions,
            skips = skips,
            lastUsedSeq = lastUsedSeq,
        )

    fun encodeCachedPayload(payload: CachedSpaceFeedPayload): String = json.encodeToString(payload)

    fun decodeCachedPayload(payloadJson: String): CachedSpaceFeedPayload? =
        runCatching { json.decodeFromString<CachedSpaceFeedPayload>(payloadJson) }.getOrNull()

    fun encodeVotes(votes: Map<String, Double>): String = json.encodeToString(votes)

    fun decodeVotes(votesJson: String): Map<String, Double> =
        if (votesJson.isBlank() || votesJson == "{}") {
            emptyMap()
        } else {
            runCatching { json.decodeFromString<Map<String, Double>>(votesJson) }.getOrDefault(emptyMap())
        }
}
