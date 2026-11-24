/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThompsonSamplerAndFeedAssemblyTest {
    private val labels =
        SpaceShelfLabels(
            freshPicksTitle = "Fresh picks",
            moreLikeFormat = { artist -> "More like $artist" },
        )

    private fun rankedCandidate(
        id: String,
        artistId: String,
        albumId: String? = "alb_$id",
        affinity: Double = 0.2,
        trust: Double = 0.8,
        novelty: Double = 1.0,
        score: Double = 0.6,
        isMaybe: Boolean = false,
        seedArtistId: String? = null,
        seedArtistName: String? = null,
    ): RankedCandidate {
        val song =
            SongItem(
                id = id,
                title = "Song $id",
                thumbnail = null,
                artists = listOf(ArtistTiny(artistId, "Artist $artistId")),
                album = albumId?.let { AlbumTiny(it, "Album $it") },
                duration = 200L,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            )
        val candidate =
            CandidateTrack(
                song = song,
                sources = setOf(CandidateSource.SEED_RADIO),
                seedArtistId = seedArtistId,
                seedArtistName = seedArtistName,
            )
        val langRes =
            LanguageCascadeResult(
                verdict = if (isMaybe) GateVerdict.MAYBE else GateVerdict.ACCEPT,
                step = if (isMaybe) LanguageStep.UNRESOLVED_MAYBE else LanguageStep.L1_SCRIPT,
            )
        val gated =
            GatedCandidate(
                candidate = candidate,
                trust = trust,
                languageResult = langRes,
                genreVerdict = GateVerdict.ACCEPT,
            )
        return RankedCandidate(
            gated = gated,
            affinity = affinity,
            sourceScore = 0.2,
            trust = trust,
            novelty = novelty,
            discovery = 0.5,
            score = score,
        )
    }

    @Test
    fun thompsonSamplerIsDeterministicWithSeedAndFavorsHigherCompletionRate() {
        val a1 = ThompsonSampler.sampleBeta(10.0, 2.0, Random(42))
        val a2 = ThompsonSampler.sampleBeta(10.0, 2.0, Random(42))
        assertEquals(a1, a2)

        val rng = Random(12345)
        var strongWins = 0
        val strongArm = SeedArmState("sp1", "a_strong", "v_s", completions = 20, skips = 1, lastUsedSeq = -1)
        val weakArm = SeedArmState("sp1", "a_weak", "v_w", completions = 1, skips = 20, lastUsedSeq = -1)
        repeat(200) {
            val pick = ThompsonSampler.selectSeeds(listOf(strongArm, weakArm), currentRefreshSeq = 10, count = 1, random = rng)
            if (pick.first().artistId == "a_strong") strongWins++
        }
        assertTrue(strongWins > 180, "Expected strong arm to win >180/200 draws, got $strongWins")
    }

    @Test
    fun thompsonSamplerEnforcesCooldownAndFallsBackWhenPoolSmall() {
        val arms =
            listOf(
                SeedArmState("sp1", "a1", "v1", completions = 10, skips = 0, lastUsedSeq = 9), // cooldown (10 - 9 <= 2)
                SeedArmState("sp1", "a2", "v2", completions = 10, skips = 0, lastUsedSeq = 8), // cooldown (10 - 8 <= 2)
                SeedArmState("sp1", "a3", "v3", completions = 2, skips = 0, lastUsedSeq = 5), // eligible
                SeedArmState("sp1", "a4", "v4", completions = 2, skips = 0, lastUsedSeq = 4), // eligible
            )

        // When enough non-cooldown arms exist (2 needed, 2 eligible), cooldown arms are skipped.
        val pickedTwo = ThompsonSampler.selectSeeds(arms, currentRefreshSeq = 10, count = 2, random = Random(7))
        assertEquals(setOf("a3", "a4"), pickedTwo.map { it.artistId }.toSet())

        // When 3 are needed and only 2 are off-cooldown, falls back cleanly to include a cooldown arm.
        val pickedThree = ThompsonSampler.selectSeeds(arms, currentRefreshSeq = 10, count = 3, random = Random(7))
        assertEquals(3, pickedThree.size)
        assertTrue(pickedThree.take(2).map { it.artistId }.toSet() == setOf("a3", "a4"))
    }

    @Test
    fun propertyTestDiversityAcross100RandomPools() {
        val config = SpaceRecConfig.Default

        for (seed in 0 until 100) {
            val rng = Random(seed + 1000)
            val artistCount = rng.nextInt(3, 35)
            val poolSize = rng.nextInt(15, 160)
            val pool =
                (0 until poolSize).map { i ->
                    val artistIdx = rng.nextInt(artistCount)
                    val albumIdx = rng.nextInt(1, 6)
                    rankedCandidate(
                        id = "s_${seed}_$i",
                        artistId = "art_$artistIdx",
                        albumId = "alb_${artistIdx}_$albumIdx",
                        affinity = rng.nextDouble(0.0, 1.0),
                        trust = rng.nextDouble(0.3, 1.0),
                        novelty = if (rng.nextBoolean()) 1.0 else 0.4,
                        score = rng.nextDouble(0.1, 1.0),
                        isMaybe = rng.nextInt(5) == 0,
                    )
                }

            val feed = FeedAssembler.assemble(pool, shelfLabels = labels, config = config, random = Random(seed + 5000))
            val songs = feed.songs

            // Invariant 1: No two consecutive songs by the same artist.
            for (i in 1 until songs.size) {
                assertNotEquals(
                    songs[i - 1].candidate.primaryArtistKey,
                    songs[i].candidate.primaryArtistKey,
                    "Consecutive artist repeat at index $i in seed $seed",
                )
            }

            // Invariant 2: Max 2 songs by the same artist in any sliding window of 20.
            for (start in songs.indices) {
                val end = minOf(songs.size, start + config.artistWindowSize)
                val counts = songs.subList(start, end).groupingBy { it.candidate.primaryArtistKey }.eachCount()
                val maxInWindow = counts.values.maxOrNull() ?: 0
                assertTrue(
                    maxInWindow <= config.artistMaxPerWindow,
                    "Artist appeared $maxInWindow times in window [$start, $end) for seed $seed",
                )
            }
        }
    }

    @Test
    fun familiarShareIsCappedAt40PercentAndOverflowsCleanlyWhenPoolIsAllFamiliar() {
        // Pool with 50 familiar (high score 0.95) + 50 non-familiar (lower score 0.50), all distinct artists.
        val mixedPool =
            (0 until 50).map { i ->
                rankedCandidate("fam_$i", "afam_$i", affinity = 0.85, score = 0.95)
            } +
                (0 until 50).map { i ->
                    rankedCandidate("new_$i", "anew_$i", affinity = 0.05, score = 0.50)
                }

        val mixedFeed = FeedAssembler.assemble(mixedPool, shelfLabels = labels, random = Random(11))
        assertEquals(60, mixedFeed.songs.size)
        assertTrue(mixedFeed.familiarShare <= 0.40 + 1e-9, "Expected familiarShare <= 0.40, got ${mixedFeed.familiarShare}")

        // Overflow test: pool is 100% familiar (60 songs, distinct artists) -> still fills all 60!
        val allFamiliarPool =
            (0 until 60).map { i ->
                rankedCandidate("onlyfam_$i", "aonlyfam_$i", affinity = 0.9, score = 0.9 - i * 0.001)
            }
        val overflowFeed = FeedAssembler.assemble(allFamiliarPool, shelfLabels = labels, random = Random(11))
        assertEquals(60, overflowFeed.songs.size)
        assertEquals(1.0, overflowFeed.familiarShare)
    }

    @Test
    fun maybeQuotaZeroWhenAtLeast40AcceptAndCappedAt25PercentOtherwise() {
        val fortyAcceptPlusMaybes =
            (0 until 45).map { i ->
                rankedCandidate("acc_$i", "a_acc_$i", score = 0.5, isMaybe = false)
            } +
                (0 until 20).map { i ->
                    rankedCandidate("m_$i", "a_m_$i", score = 0.99, isMaybe = true)
                }

        val feedNoMaybe = FeedAssembler.assemble(fortyAcceptPlusMaybes, shelfLabels = labels, allowMaybe = true, random = Random(99))
        assertTrue(feedNoMaybe.songs.none { it.gated.isMaybe }, "No MAYBE allowed when >= 40 ACCEPT exist")

        val thirtyAcceptPlusMaybes =
            (0 until 30).map { i ->
                rankedCandidate("acc2_$i", "a_acc2_$i", score = 0.5, isMaybe = false)
            } +
                (0 until 30).map { i ->
                    rankedCandidate("m2_$i", "a_m2_$i", score = 0.99, isMaybe = true)
                }
        // Rule (1): default allowMaybe = false excludes all MAYBE tracks even when < 40 ACCEPT exist.
        val feedStrictNoMaybe = FeedAssembler.assemble(thirtyAcceptPlusMaybes, shelfLabels = labels, random = Random(99))
        assertTrue(feedStrictNoMaybe.songs.none { it.gated.isMaybe }, "Default constrained assembly must exclude all MAYBE tracks")

        val feedWithMaybe = FeedAssembler.assemble(thirtyAcceptPlusMaybes, shelfLabels = labels, allowMaybe = true, random = Random(99))
        val maybeCount = feedWithMaybe.songs.count { it.gated.isMaybe }
        val share = maybeCount.toDouble() / feedWithMaybe.songs.size.toDouble()
        assertTrue(maybeCount > 0, "Expected some MAYBEs when allowMaybe = true and < 40 ACCEPT exist")
        assertTrue(share <= 0.25 + 1e-9, "Expected MAYBE share <= 0.25, got $share")
    }

    @Test
    fun exploreSlotEvery7thAndShelfTopUpNoDuplicatesAcrossShelves() {
        val highScoreOld =
            (0 until 30).map { i ->
                rankedCandidate(
                    id = "old_$i",
                    artistId = "a_old_$i",
                    affinity = 0.35,
                    novelty = 0.4,
                    score = 0.95 - i * 0.001,
                    seedArtistId = "seed_1",
                    seedArtistName = "Sushin Shyam",
                )
            }
        val exploreTracks =
            (0 until 15).map { i ->
                rankedCandidate(
                    id = "exp_$i",
                    artistId = "a_exp_$i",
                    affinity = 0.02,
                    novelty = 1.0,
                    trust = 0.9,
                    score = 0.25,
                )
            }

        val homeShelves =
            listOf(
                HomeShelfSeed(key = "home_empty", title = "Empty shelf", survivingSongIds = listOf("nonexistent")),
                HomeShelfSeed(key = "home_small", title = "Quick picks", survivingSongIds = listOf("old_0", "old_1")),
            )

        val feed =
            FeedAssembler.assemble(
                ranked = highScoreOld + exploreTracks,
                homeShelves = homeShelves,
                shelfLabels = labels,
                random = Random(77),
            )

        // Position 6 (7th item) must be an explore track (A < 0.1 && N == 1.0).
        val seventh = feed.songs[6]
        assertTrue(seventh.affinity < 0.1 && seventh.novelty >= 1.0, "7th slot must be an explore track")

        // Empty shelf dropped; small shelf topped up to >= 6; generated shelves added (<= 2).
        assertTrue(feed.shelves.none { it.key == "home_empty" })
        val quickPicks = feed.shelves.first { it.key == "home_small" }
        assertEquals(6, quickPicks.songs.size)

        val generatedShelves = feed.shelves.filter { it.isGenerated }
        assertTrue(generatedShelves.size in 1..2)
        assertEquals("Fresh picks", generatedShelves.first().title)

        // No song appears twice across shelves.
        val allShelfSongIds = feed.shelves.flatMap { it.songs.map { s -> s.id } }
        assertEquals(allShelfSongIds.size, allShelfSongIds.toSet().size)
    }
}
