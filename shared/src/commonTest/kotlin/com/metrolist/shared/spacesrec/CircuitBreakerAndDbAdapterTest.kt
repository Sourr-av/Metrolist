/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.db.daos.SpacePlayHistoryJoinedRow
import com.metrolist.shared.db.daos.SpaceRecDao
import com.metrolist.shared.db.entities.ArtistProfileEntity
import com.metrolist.shared.db.entities.SpaceFeedCacheEntity
import com.metrolist.shared.db.entities.SpacePlayEventEntity
import com.metrolist.shared.db.entities.SpaceSeedArmEntity
import com.metrolist.shared.db.entities.SpaceShownEntity
import com.metrolist.shared.db.entities.TrackClassEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircuitBreakerAndDbAdapterTest {
    @Test
    fun circuitBreakerOpensAfterFiveConsecutiveFailuresAndReopensAfterCooldown() =
        runTest {
            var nowMs = 10_000L
            val breaker =
                CircuitBreaker(
                    failureThreshold = 5,
                    openDurationMs = 60_000L,
                    clock = { nowMs },
                )

            // 4 failures keep circuit closed.
            repeat(4) {
                val res = breaker.execute(timeoutMs = 1_000L) { error("boom") }
                assertNull(res)
                assertFalse(breaker.isOpen())
            }

            // 5th consecutive failure opens the circuit for 60s.
            assertNull(breaker.execute(timeoutMs = 1_000L) { error("boom 5") })
            assertTrue(breaker.isOpen())

            // Calls while open short-circuit without invoking block.
            var invokedWhileOpen = false
            val skipped =
                breaker.execute(timeoutMs = 1_000L) {
                    invokedWhileOpen = true
                    "ok"
                }
            assertNull(skipped)
            assertFalse(invokedWhileOpen)

            // After 60s cooldown, probe is allowed and success closes circuit.
            nowMs += 60_000L
            assertFalse(breaker.isOpen())
            val recovered = breaker.execute(timeoutMs = 1_000L) { "recovered" }
            assertEquals("recovered", recovered)
            assertFalse(breaker.isOpen())
        }

    @Test
    fun circuitBreakerCountsTimeoutsAsFailuresButRethrowsStructuredCancellation() =
        runTest {
            var nowMs = 1_000L
            val breaker =
                CircuitBreaker(
                    failureThreshold = 2,
                    openDurationMs = 60_000L,
                    clock = { nowMs },
                )

            // Timeouts count toward failure threshold.
            assertNull(
                breaker.execute(timeoutMs = 100L) {
                    delay(5_000L)
                    "never"
                },
            )
            assertFalse(breaker.isOpen())

            // Structured CancellationException must propagate and NOT trip the breaker.
            assertFailsWith<CancellationException> {
                breaker.execute(timeoutMs = 1_000L) {
                    throw CancellationException("caller cancelled")
                }
            }
            assertFalse(breaker.isOpen())

            // One more timeout reaches threshold = 2 and opens circuit.
            assertNull(
                breaker.execute(timeoutMs = 100L) {
                    delay(5_000L)
                    "never"
                },
            )
            assertTrue(breaker.isOpen())
        }

    @Test
    fun batchPreloadChunksRequestsOverFiveHundredIds() =
        runTest {
            val trackChunkSizes = mutableListOf<Int>()
            val artistChunkSizes = mutableListOf<Int>()
            val dao =
                object : StubSpaceRecDao() {
                    override suspend fun getTrackClasses(videoIds: List<String>): List<TrackClassEntity> {
                        trackChunkSizes += videoIds.size
                        return videoIds.map { id ->
                            TrackClassEntity(videoId = id, language = "en", checkedAt = 1L)
                        }
                    }

                    override suspend fun getArtistProfiles(artistIds: List<String>): List<ArtistProfileEntity> {
                        artistChunkSizes += artistIds.size
                        return artistIds.map { id ->
                            ArtistProfileEntity(
                                artistId = id,
                                name = "Artist $id",
                                languageVotesJson = """{"en":3.0}""",
                                totalVotes = 3.0,
                                updatedAt = 1L,
                                genreVotesJson = """{"rock":3.0}""",
                            )
                        }
                    }
                }

            val videoIds = (1..1_230).map { "v_$it" } + listOf("v_1", "v_2") // includes duplicates
            val artistIds = (1..650).map { "a_$it" } + listOf("")

            val preloaded = SpaceRecDbAdapter.preload(dao, videoIds, artistIds)

            assertEquals(listOf(500, 500, 230), trackChunkSizes)
            assertEquals(listOf(500, 150), artistChunkSizes)
            assertTrue(trackChunkSizes.all { it <= SpaceRecDbAdapter.MAX_SQLITE_BIND_PARAMS })
            assertTrue(artistChunkSizes.all { it <= SpaceRecDbAdapter.MAX_SQLITE_BIND_PARAMS })
            assertEquals(1_230, preloaded.trackClasses.size)
            assertEquals(650, preloaded.artistProfiles.size)
            assertEquals("en", preloaded.trackClassLookup.getTrackClass("v_1230")?.language)
            assertEquals(3.0, preloaded.artistProfileLookup.getArtistProfile("a_650")?.genreVotes?.get("rock"))
        }

    @Test
    fun groupPlayHistoryMergesMultipleArtistsPerEventRow() {
        val rows =
            listOf(
                SpacePlayHistoryJoinedRow(10L, "v1", 1000L, 45_000L, 200, "a1"),
                SpacePlayHistoryJoinedRow(10L, "v1", 1000L, 45_000L, 200, "a2"),
                SpacePlayHistoryJoinedRow(11L, "v2", 2000L, 12_000L, 0, null),
            )

        val plays = SpaceRecDbAdapter.groupPlayHistory(rows)
        assertEquals(2, plays.size)
        assertEquals(listOf("a1", "a2"), plays[0].artistIds)
        assertEquals(200_000L, plays[0].durationMs)
        assertEquals(emptyList(), plays[1].artistIds)
        assertNull(plays[1].durationMs)
    }
}

internal open class StubSpaceRecDao : SpaceRecDao {
    override suspend fun getTrackClass(videoId: String): TrackClassEntity? = null

    override suspend fun getTrackClasses(videoIds: List<String>): List<TrackClassEntity> = emptyList()

    override suspend fun upsertTrackClass(entry: TrackClassEntity) = Unit

    override suspend fun upsertTrackClasses(entries: List<TrackClassEntity>) = Unit

    override suspend fun getArtistProfile(artistId: String): ArtistProfileEntity? = null

    override suspend fun getArtistProfiles(artistIds: List<String>): List<ArtistProfileEntity> = emptyList()

    override suspend fun upsertArtistProfile(profile: ArtistProfileEntity) = Unit

    override suspend fun upsertArtistProfiles(profiles: List<ArtistProfileEntity>) = Unit

    override suspend fun getSeedArms(spaceId: String): List<SpaceSeedArmEntity> = emptyList()

    override suspend fun getSeedArm(
        spaceId: String,
        artistId: String,
    ): SpaceSeedArmEntity? = null

    override suspend fun upsertSeedArm(arm: SpaceSeedArmEntity) = Unit

    override suspend fun upsertSeedArms(arms: List<SpaceSeedArmEntity>) = Unit

    override suspend fun getRecentlyShownVideoIds(
        spaceId: String,
        minRefreshSeq: Int,
    ): List<String> = emptyList()

    override suspend fun upsertShown(entries: List<SpaceShownEntity>) = Unit

    override suspend fun pruneShownBefore(
        spaceId: String,
        minRefreshSeq: Int,
    ) = Unit

    override suspend fun getFeedCache(spaceId: String): SpaceFeedCacheEntity? = null

    override suspend fun upsertFeedCache(cache: SpaceFeedCacheEntity) = Unit

    override suspend fun deleteFeedCache(spaceId: String) = Unit

    override suspend fun insertPlayEvent(event: SpacePlayEventEntity) = Unit

    override suspend fun getPlayEventsForSpace(
        spaceId: String,
        sinceMs: Long,
    ): List<SpacePlayEventEntity> = emptyList()

    override suspend fun getPlayHistoryWithDurationSince(sinceMs: Long): List<SpacePlayHistoryJoinedRow> = emptyList()

    override suspend fun getAllEverPlayedVideoIds(): List<String> = emptyList()

    override suspend fun getSongDurationSeconds(videoId: String): Int? = null
}
