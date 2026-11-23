/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.SongItem
import kotlin.random.Random

data class HomeShelfSeed(
    val key: String,
    val title: String,
    val browseId: String? = null,
    val survivingSongIds: List<String>,
)

/**
 * Stage 4 feed assembly: greedy single-pass selection enforcing
 * - MAYBE quota (0% when >= 40 ACCEPT exist, <= 25% otherwise)
 * - No two consecutive songs by the same artist
 * - Max 2 songs by the same artist in any sliding window of 20
 * - Soft penalties (-0.30 per artist occurrence, -0.15 per album occurrence in window)
 * - Familiar cap (<= 40% when non-familiar candidates exist; overflows cleanly if pool is mostly familiar)
 * - Explore slot every 7th position (A < 0.1 && N == 1.0, weighted random by T)
 * - Home shelf top-up (>= 6 items), drop empty shelves, single appearance across shelves, up to 2 generated shelves
 */
object FeedAssembler {
    private const val GENERATED_SHELF_MAX_ITEMS = 12

    fun assemble(
        ranked: List<RankedCandidate>,
        homeShelves: List<HomeShelfSeed> = emptyList(),
        shelfLabels: SpaceShelfLabels,
        config: SpaceRecConfig = SpaceRecConfig.Default,
        random: Random = Random.Default,
    ): AssembledFeed {
        if (ranked.isEmpty()) {
            return AssembledFeed(
                songs = emptyList(),
                shelves = emptyList(),
                familiarShare = 0.0,
                uniqueArtistCount = 0,
                maxSongsPerArtist = 0,
            )
        }

        val targetCount = config.feedTargetSongs
        val acceptedPool = ranked.filter { it.gated.isFullyAccepted }
        val maybePool = ranked.filter { it.gated.isMaybe }

        val maxMaybesAllowed =
            when {
                acceptedPool.size >= config.minSongsBeforeMaybeAllowed -> 0
                maybePool.isEmpty() -> 0
                acceptedPool.isEmpty() -> (targetCount * config.maybeMaxShare).toInt()
                else -> {
                    val shareCapFromTarget = (targetCount * config.maybeMaxShare).toInt()
                    val shareCapFromAccepted =
                        ((acceptedPool.size.toDouble() * config.maybeMaxShare) / (1.0 - config.maybeMaxShare)).toInt()
                    minOf(shareCapFromTarget, shareCapFromAccepted)
                }
            }

        val workingPool =
            if (maxMaybesAllowed == 0) {
                acceptedPool.toMutableList()
            } else {
                ranked.filter { it.gated.isFullyAccepted || it.gated.isMaybe }.toMutableList()
            }

        val expectedSize = minOf(targetCount, acceptedPool.size + minOf(maybePool.size, maxMaybesAllowed))
        val familiarCap = (expectedSize * config.familiarMaxShare).toInt()

        val assembled = mutableListOf<RankedCandidate>()
        var familiarCount = 0
        var maybeCount = 0

        while (assembled.size < targetCount && workingPool.isNotEmpty()) {
            val pos = assembled.size
            val isExploreSlot = config.exploreEveryNth > 0 && ((pos + 1) % config.exploreEveryNth == 0)

            val window =
                if (config.artistWindowSize <= 1) {
                    emptyList()
                } else {
                    assembled.takeLast(config.artistWindowSize - 1)
                }
            val lastArtistKey = assembled.lastOrNull()?.candidate?.primaryArtistKey.orEmpty()

            val windowArtistCounts = mutableMapOf<String, Int>()
            val windowAlbumCounts = mutableMapOf<String, Int>()
            for (item in window) {
                val aKey = item.candidate.primaryArtistKey
                if (aKey.isNotEmpty()) {
                    windowArtistCounts[aKey] = (windowArtistCounts[aKey] ?: 0) + 1
                }
                val albKey = item.candidate.albumKey
                if (!albKey.isNullOrEmpty()) {
                    windowAlbumCounts[albKey] = (windowAlbumCounts[albKey] ?: 0) + 1
                }
            }

            // Filter by hard constraints: MAYBE cap, no consecutive same artist, max N per artist in window.
            val diversityEligible =
                workingPool.filter { candidate ->
                    if (candidate.gated.isMaybe && maybeCount >= maxMaybesAllowed) return@filter false
                    val aKey = candidate.candidate.primaryArtistKey
                    if (aKey.isNotEmpty()) {
                        if (aKey == lastArtistKey) return@filter false
                        if ((windowArtistCounts[aKey] ?: 0) >= config.artistMaxPerWindow) return@filter false
                    }
                    true
                }

            if (diversityEligible.isEmpty()) break

            val chosen: RankedCandidate =
                if (isExploreSlot) {
                    pickExploreCandidate(diversityEligible, random, config)
                        ?: pickGreedyCandidate(
                            eligible = diversityEligible,
                            familiarCount = familiarCount,
                            familiarCap = familiarCap,
                            windowArtistCounts = windowArtistCounts,
                            windowAlbumCounts = windowAlbumCounts,
                            config = config,
                        )
                } else {
                    pickGreedyCandidate(
                        eligible = diversityEligible,
                        familiarCount = familiarCount,
                        familiarCap = familiarCap,
                        windowArtistCounts = windowArtistCounts,
                        windowAlbumCounts = windowAlbumCounts,
                        config = config,
                    )
                }

            assembled += chosen
            workingPool.remove(chosen)
            if (chosen.affinity >= config.familiarThreshold) familiarCount++
            if (chosen.gated.isMaybe) maybeCount++
        }

        val shelves = buildShelves(assembled, homeShelves, shelfLabels, config)

        val artistFreq =
            assembled
                .map { it.candidate.primaryArtistKey }
                .filter(String::isNotEmpty)
                .groupingBy { it }
                .eachCount()

        val familiarShare =
            if (assembled.isEmpty()) 0.0 else familiarCount.toDouble() / assembled.size.toDouble()

        return AssembledFeed(
            songs = assembled,
            shelves = shelves,
            familiarShare = familiarShare,
            uniqueArtistCount = artistFreq.size,
            maxSongsPerArtist = artistFreq.values.maxOrNull() ?: 0,
        )
    }

    private fun pickExploreCandidate(
        eligible: List<RankedCandidate>,
        random: Random,
        config: SpaceRecConfig,
    ): RankedCandidate? {
        val explorePool =
            eligible.filter {
                it.affinity < config.exploreMaxAffinity && it.novelty >= config.exploreRequiredNovelty
            }
        if (explorePool.isEmpty()) return null

        val totalWeight = explorePool.sumOf { it.trust.coerceAtLeast(0.01) }
        var target = random.nextDouble() * totalWeight
        for (item in explorePool) {
            target -= item.trust.coerceAtLeast(0.01)
            if (target <= 0.0) return item
        }
        return explorePool.last()
    }

    private fun pickGreedyCandidate(
        eligible: List<RankedCandidate>,
        familiarCount: Int,
        familiarCap: Int,
        windowArtistCounts: Map<String, Int>,
        windowAlbumCounts: Map<String, Int>,
        config: SpaceRecConfig,
    ): RankedCandidate {
        val pool =
            if (familiarCount >= familiarCap) {
                val nonFamiliar = eligible.filter { it.affinity < config.familiarThreshold }
                // Familiar share is a cap, not a quota: if no non-familiar remain, overflow cleanly.
                nonFamiliar.ifEmpty { eligible }
            } else {
                eligible
            }

        return pool.maxWith(
            compareBy<RankedCandidate> { candidate ->
                val aKey = candidate.candidate.primaryArtistKey
                val albKey = candidate.candidate.albumKey
                val aCount = if (aKey.isNotEmpty()) windowArtistCounts[aKey] ?: 0 else 0
                val albCount = if (!albKey.isNullOrEmpty()) windowAlbumCounts[albKey] ?: 0 else 0
                candidate.score -
                    config.diversityArtistPenalty * aCount -
                    config.diversityAlbumPenalty * albCount
            }.thenBy { it.score }
                .thenByDescending { it.song.id },
        )
    }

    private fun buildShelves(
        assembled: List<RankedCandidate>,
        homeShelves: List<HomeShelfSeed>,
        labels: SpaceShelfLabels,
        config: SpaceRecConfig,
    ): List<AssembledShelf> {
        if (assembled.isEmpty()) return emptyList()

        val bySongId = assembled.associateBy { it.song.id }
        val placedIds = linkedSetOf<String>()
        val shelves = mutableListOf<AssembledShelf>()

        // 1. Keep surviving Home shelves; drop empty ones; top up shelves with < minShelfItems.
        for (homeShelf in homeShelves) {
            val shelfSongs = mutableListOf<SongItem>()
            for (songId in homeShelf.survivingSongIds) {
                if (songId in placedIds) continue
                val ranked = bySongId[songId] ?: continue
                shelfSongs += ranked.song
                placedIds += songId
            }
            if (shelfSongs.isEmpty()) continue

            if (shelfSongs.size < config.minShelfItems) {
                for (candidate in assembled) {
                    if (shelfSongs.size >= config.minShelfItems) break
                    val id = candidate.song.id
                    if (id in placedIds) continue
                    val candidateArtistKey = candidate.candidate.primaryArtistKey
                    val lastShelfArtistKey =
                        shelfSongs
                            .lastOrNull()
                            ?.artists
                            ?.firstOrNull()
                            ?.let { it.id?.takeIf(String::isNotBlank) ?: it.name.trim().lowercase() }
                            .orEmpty()
                    if (candidateArtistKey.isNotEmpty()) {
                        if (candidateArtistKey == lastShelfArtistKey) continue
                        val artistCountInShelf =
                            shelfSongs.count { existing ->
                                val k =
                                    existing.artists
                                        .firstOrNull()
                                        ?.let { it.id?.takeIf(String::isNotBlank) ?: it.name.trim().lowercase() }
                                        .orEmpty()
                                k == candidateArtistKey
                            }
                        if (artistCountInShelf >= config.artistMaxPerWindow) continue
                    }
                    shelfSongs += candidate.song
                    placedIds += id
                }
            }

            shelves +=
                AssembledShelf(
                    key = homeShelf.key,
                    title = homeShelf.title,
                    browseId = homeShelf.browseId,
                    songs = shelfSongs,
                    isGenerated = false,
                )
        }

        // 2. Add up to maxGeneratedShelves (2) generated shelves from unused assembled songs.
        var generatedCount = 0

        if (generatedCount < config.maxGeneratedShelves) {
            val freshSongs =
                assembled
                    .asSequence()
                    .filter { it.song.id !in placedIds }
                    .take(GENERATED_SHELF_MAX_ITEMS)
                    .map { it.song }
                    .toList()
            if (freshSongs.isNotEmpty()) {
                freshSongs.forEach { placedIds += it.id }
                shelves +=
                    AssembledShelf(
                        key = "space_fresh_picks",
                        title = labels.freshPicksTitle,
                        songs = freshSongs,
                        isGenerated = true,
                    )
                generatedCount++
            }
        }

        if (generatedCount < config.maxGeneratedShelves) {
            val remaining = assembled.filter { it.song.id !in placedIds }
            if (remaining.isNotEmpty()) {
                val topSeed =
                    remaining
                        .mapNotNull { item ->
                            val seedId = item.candidate.seedArtistId
                            val seedName = item.candidate.seedArtistName?.takeIf(String::isNotBlank)
                            if (seedId != null && seedName != null) seedId to seedName else null
                        }.groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key

                val anchorName =
                    topSeed?.second
                        ?: remaining.first().song.artists.firstOrNull()?.name?.takeIf(String::isNotBlank)

                if (anchorName != null) {
                    val primaryMatch =
                        if (topSeed != null) {
                            remaining.filter { it.candidate.seedArtistId == topSeed.first }
                        } else {
                            emptyList()
                        }
                    val moreLikeSongs =
                        (primaryMatch + remaining)
                            .distinctBy { it.song.id }
                            .take(GENERATED_SHELF_MAX_ITEMS)
                            .map { it.song }

                    if (moreLikeSongs.isNotEmpty()) {
                        moreLikeSongs.forEach { placedIds += it.id }
                        shelves +=
                            AssembledShelf(
                                key = "space_more_like_${topSeed?.first ?: anchorName.lowercase()}",
                                title = labels.moreLikeFormat(anchorName),
                                songs = moreLikeSongs,
                                isGenerated = true,
                            )
                    }
                }
            }
        }

        return shelves
    }
}
