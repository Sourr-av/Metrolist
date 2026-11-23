/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spaces

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.isNonMusicalVideo
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.mergeAndDeduplicateHomeSections
import com.metrolist.shared.settings.SpaceFilterSpec
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlin.random.Random

data class SpaceFallbackShelfTitles(
    val songs: String = "Recommended songs",
    val albums: String = "Matching albums",
    val playlists: String = "Matching playlists",
    val artists: String = "Matching artists",
)

data class SpaceFilteredFeedResult(
    val sections: List<HomeSection>,
    val fallbackSongs: List<SongItem> = emptyList(),
    val quickPicks: List<SongItem> = emptyList(),
    val keepListening: List<SongItem> = emptyList(),
    val forgottenFavorites: List<SongItem> = emptyList(),
    val nextContinuation: String? = null,
)

object SpaceFeedSupplement {
    // Target personalized items to harvest from the user's own Home + continuations + local history before stopping Stage 1.
    private const val PERSONALIZED_TARGET_ITEMS = 18

    // Only invoke personalized chip / radio expansion if the user's own Home + history yielded fewer than this.
    private const val MIN_PERSONALIZED_BEFORE_CHIP_OR_RADIO = 10

    // Only invoke cold Moods & Genres / YouTube Search fallback if ALL personalized sources yielded fewer than this.
    private const val MIN_PERSONALIZED_BEFORE_COLD_FALLBACK = 8

    /**
     * Personalized-first, strict filtering + smart sourcing pipeline for a filtered Space:
     * 1. Screen the user's OWN personalized Home Page 1 + Home continuation pages (up to 2-3 pages)
     *    + the user's local listening history (Quick Picks, Listen Again, Forgotten Favorites) strictly.
     *    If the user already listens to this Space's language/genre, STOP here — no cold fallback!
     * 2. If personalized Home + history are sparse (< 10 items):
     *    2a. Check YouTube's closest personalized Home chip (e.g. Hip-hop, Pop, Rock, Energize).
     *    2b. If we have at least 1 verified matched song, expand via YouTube Radio (`startRadio`)
     *        seeded from a matched song rotated by [refreshSeed] so refreshes feel fresh & personalized.
     *    2c. If still < 8 items, check YouTube Music's Moods & Genres browse page lazily.
     * 3. Only if ALL above still yield < 8 items, query targeted YouTube Music Search (without recording
     *    search history), rotating queries by [refreshSeed] and aggressively rejecting any language/genre conflict.
     */
    suspend fun buildFilteredFeed(
        rawHomeSections: List<HomeSection>,
        homeChips: List<Chip>,
        exploreSections: List<HomeSection> = emptyList(),
        filter: SpaceFilterSpec,
        contentFilter: SpaceContentFilter,
        shelfTitles: SpaceFallbackShelfTitles = SpaceFallbackShelfTitles(),
        signedIn: Boolean = false,
        initialContinuation: String? = null,
        localQuickPicks: List<SongItem> = emptyList(),
        localKeepListening: List<SongItem> = emptyList(),
        localForgottenFavorites: List<SongItem> = emptyList(),
        refreshSeed: Int = 0,
        previousItemIds: Set<String> = emptySet(),
    ): SpaceFilteredFeedResult {
        val normalized = filter.normalized()
        if (!normalized.isActive) {
            return SpaceFilteredFeedResult(
                sections = rawHomeSections,
                nextContinuation = initialContinuation,
            )
        }

        val collectedSections = mutableListOf<HomeSection>()
        val seenIds = mutableSetOf<String>()
        var currentContinuation = initialContinuation

        fun addVerifiedSections(sections: List<HomeSection>) {
            for (section in sections) {
                val uniqueItems =
                    section.items.filter { item ->
                        seenIds.add(canonicalHomeItemId(item))
                    }
                if (uniqueItems.isNotEmpty()) {
                    collectedSections += section.copy(items = uniqueItems)
                }
            }
        }

        // --- Stage 1a: Screen User's Personalized Home Page 1 + Local Listening History in parallel ---
        val homeCandidates = prepareCandidateSections(rawHomeSections)
        val cleanLocalQuickPicks =
            localQuickPicks
                .filterNot { it.isPodcast || it.isNonMusicalVideo }
                .distinctBy(::canonicalHomeItemId)
        val cleanLocalKeepListening =
            localKeepListening
                .filterNot { it.isPodcast || it.isNonMusicalVideo }
                .distinctBy(::canonicalHomeItemId)
        val cleanLocalForgotten =
            localForgottenFavorites
                .filterNot { it.isPodcast || it.isNonMusicalVideo }
                .distinctBy(::canonicalHomeItemId)

        val verifiedLocalMap: Map<String, SongItem>
        val page1Verified: List<HomeSection>
        supervisorScope {
            val page1Deferred =
                async {
                    contentFilter.filterSections(
                        sections = homeCandidates,
                        filter = normalized,
                        source = SpaceCandidateSource.HOME_FEED,
                        maxCandidates = 80,
                    )
                }
            val localUnion =
                (cleanLocalQuickPicks + cleanLocalKeepListening + cleanLocalForgotten)
                    .distinctBy(::canonicalHomeItemId)
            val localDeferred =
                async {
                    if (localUnion.isEmpty()) {
                        emptyList()
                    } else {
                        contentFilter
                            .filterItems(
                                items = localUnion,
                                filter = normalized,
                                source = SpaceCandidateSource.HOME_FEED,
                                maxCandidates = 45,
                            ).filterIsInstance<SongItem>()
                    }
                }
            page1Verified = page1Deferred.await()
            verifiedLocalMap = localDeferred.await().associateBy(::canonicalHomeItemId)
        }

        addVerifiedSections(page1Verified)

        val verifiedLocalQuickPicks =
            cleanLocalQuickPicks.mapNotNull { verifiedLocalMap[canonicalHomeItemId(it)] }
        val verifiedLocalKeepListening =
            cleanLocalKeepListening.mapNotNull { verifiedLocalMap[canonicalHomeItemId(it)] }
        val verifiedLocalForgotten =
            cleanLocalForgotten.mapNotNull { verifiedLocalMap[canonicalHomeItemId(it)] }

        // Count verified local songs toward personalized pool so we don't prematurely trigger cold search!
        val allPersonalizedLocalIds =
            (verifiedLocalQuickPicks + verifiedLocalKeepListening + verifiedLocalForgotten)
                .mapTo(mutableSetOf(), ::canonicalHomeItemId)
        val personalizedCount = { (seenIds + allPersonalizedLocalIds).size }

        // --- Stage 1b: Fetch up to 2 personalized Home continuation pages (3 on refresh if mostly seen) ---
        val maxContinuationPages = if (refreshSeed > 0) 3 else 2
        var fetchedContinuationPages = 0
        while (currentContinuation != null && fetchedContinuationPages < maxContinuationPages) {
            val unseenCount = (seenIds + allPersonalizedLocalIds).count { it !in previousItemIds }
            val hasEnough =
                personalizedCount() >= PERSONALIZED_TARGET_ITEMS &&
                    (previousItemIds.isEmpty() || unseenCount >= 6)
            if (hasEnough) break

            val token = currentContinuation
            val nextPage =
                safeRequest {
                    GlobalYouTubeRepository.instance.getHomeSectionsContinuation(
                        token = token,
                        isPersonal = signedIn,
                        loadAllPages = false,
                    )
                } ?: break
            fetchedContinuationPages++
            currentContinuation = nextPage.second
            val nextCandidates = prepareCandidateSections(nextPage.first)
            if (nextCandidates.isNotEmpty()) {
                addVerifiedSections(
                    contentFilter.filterSections(
                        sections = nextCandidates,
                        filter = normalized,
                        source = SpaceCandidateSource.HOME_FEED,
                        maxCandidates = 55,
                    ),
                )
            }
        }

        // --- Stage 2a: Closest personalized YouTube Home chip (only if user's Home + history < 10 matches) ---
        if (personalizedCount() < MIN_PERSONALIZED_BEFORE_CHIP_OR_RADIO && normalized.genres.isNotEmpty()) {
            val matchingChips = SpaceFilterTaxonomy.findMatchingChips(homeChips, normalized.genres)
            val matchingChip =
                if (matchingChips.isNotEmpty()) {
                    matchingChips[((refreshSeed % matchingChips.size) + matchingChips.size) % matchingChips.size]
                } else {
                    null
                }
            if (matchingChip != null) {
                val chipPage =
                    safeRequest {
                        GlobalYouTubeRepository.instance.getHomePageData(
                            params = matchingChip.params,
                            browseId = matchingChip.browseId ?: "FEmusic_home",
                        )
                    }
                if (chipPage != null) {
                    val chipCandidates = prepareCandidateSections(chipPage.sections)
                    addVerifiedSections(
                        contentFilter.filterSections(
                            sections = chipCandidates,
                            filter = normalized,
                            source = SpaceCandidateSource.HOME_FEED,
                            maxCandidates = 45,
                        ),
                    )
                }
            }
        }

        // --- Stage 2b: Personalized Radio expansion from user's OWN verified matched songs ---
        val matchedPersonalizedSongs =
            (
                collectedSections.flatMap { it.items.filterIsInstance<SongItem>() } +
                    verifiedLocalQuickPicks +
                    verifiedLocalKeepListening +
                    verifiedLocalForgotten
            ).distinctBy(::canonicalHomeItemId)

        val fallbackSongs = mutableListOf<SongItem>()
        if (personalizedCount() < MIN_PERSONALIZED_BEFORE_CHIP_OR_RADIO && matchedPersonalizedSongs.isNotEmpty()) {
            val seedIndex = ((refreshSeed % matchedPersonalizedSongs.size) + matchedPersonalizedSongs.size) % matchedPersonalizedSongs.size
            val seedSong = matchedPersonalizedSongs[seedIndex]
            val radioSongs =
                safeRequest {
                    GlobalYouTubeRepository.instance.startRadio(seedSong.id)
                }.orEmpty()
                    .filterNot { it.isPodcast || it.isNonMusicalVideo }
                    .distinctBy(::canonicalHomeItemId)
                    .filterNot { canonicalHomeItemId(it) in seenIds }
            if (radioSongs.isNotEmpty()) {
                val verifiedRadio =
                    contentFilter
                        .filterItems(
                            items = radioSongs,
                            filter = normalized,
                            source = SpaceCandidateSource.HOME_FEED,
                            maxCandidates = 25,
                        ).filterIsInstance<SongItem>()
                        .filter { seenIds.add(canonicalHomeItemId(it)) }
                if (verifiedRadio.isNotEmpty()) {
                    fallbackSongs += verifiedRadio
                    collectedSections +=
                        HomeSection(
                            title = shelfTitles.songs,
                            items = verifiedRadio,
                            isListStyle = true,
                            numItemsPerColumn = 4,
                        )
                }
            }
        }

        // --- Stage 2c: Moods & Genres browse page (lazy — only if STILL < 8 matches) ---
        if (personalizedCount() < MIN_PERSONALIZED_BEFORE_COLD_FALLBACK && normalized.genres.isNotEmpty()) {
            val moodBrowseItems =
                (rawHomeSections + exploreSections)
                    .flatMap { it.items }
                    .filterIsInstance<BrowseItem>()
                    .ifEmpty {
                        safeRequest {
                            GlobalYouTubeRepository.instance
                                .getBrowsePageData(browseId = "FEmusic_moods_and_genres", loadAllPages = false)
                                .sections
                                .flatMap { it.items }
                                .filterIsInstance<BrowseItem>()
                        }.orEmpty()
                    }
            val matchingMoods = SpaceFilterTaxonomy.findMatchingMoodItems(moodBrowseItems, normalized.genres)
            val matchingMood =
                if (matchingMoods.isNotEmpty()) {
                    matchingMoods[((refreshSeed % matchingMoods.size) + matchingMoods.size) % matchingMoods.size]
                } else {
                    null
                }
            if (matchingMood != null) {
                val moodPage =
                    safeRequest {
                        GlobalYouTubeRepository.instance.getBrowsePageData(
                            browseId = matchingMood.id,
                            params = matchingMood.params,
                            loadAllPages = false,
                        )
                    }
                if (moodPage != null) {
                    val moodCandidates = prepareCandidateSections(moodPage.sections)
                    addVerifiedSections(
                        contentFilter.filterSections(
                            sections = moodCandidates,
                            filter = normalized,
                            source = SpaceCandidateSource.HOME_FEED,
                            maxCandidates = 45,
                        ),
                    )
                }
            }
        }

        // --- Stage 3: Targeted YouTube Music Search fallback (ONLY if personalized + radio + moods < 8 matches) ---
        if (personalizedCount() < MIN_PERSONALIZED_BEFORE_COLD_FALLBACK) {
            val queries = SpaceFilterTaxonomy.buildFallbackSearchQueries(normalized, rotationSeed = refreshSeed)
            if (queries.isNotEmpty()) {
                val primaryQuery = queries.first()
                val secondaryQuery = queries.getOrNull(1)
                supervisorScope {
                    val songs1Deferred = async { safeSearch(primaryQuery, SearchFilter.SONG) }
                    val songs2Deferred =
                        async {
                            if (secondaryQuery != null && secondaryQuery != primaryQuery) {
                                safeSearch(secondaryQuery, SearchFilter.SONG)
                            } else {
                                emptyList()
                            }
                        }
                    val albumsDeferred = async { safeSearch(primaryQuery, SearchFilter.ALBUM) }
                    val playlistsDeferred = async { safeSearch(primaryQuery, SearchFilter.COMMUNITY_PLAYLIST) }
                    val artistsDeferred = async { safeSearch(primaryQuery, SearchFilter.ARTIST) }

                    val rawSongs =
                        (songs1Deferred.await() + songs2Deferred.await())
                            .filterIsInstance<SongItem>()
                            .filterNot { it.isPodcast || it.isNonMusicalVideo }
                            .distinctBy(::canonicalHomeItemId)
                            .filterNot { canonicalHomeItemId(it) in seenIds }
                    val rawAlbums =
                        albumsDeferred
                            .await()
                            .filterIsInstance<AlbumItem>()
                            .distinctBy(::canonicalHomeItemId)
                            .filterNot { canonicalHomeItemId(it) in seenIds }
                    val rawPlaylists =
                        playlistsDeferred
                            .await()
                            .filterIsInstance<PlaylistItem>()
                            .distinctBy(::canonicalHomeItemId)
                            .filterNot { canonicalHomeItemId(it) in seenIds }
                    val rawArtists =
                        artistsDeferred
                            .await()
                            .filterIsInstance<ArtistItem>()
                            .distinctBy(::canonicalHomeItemId)
                            .filterNot { canonicalHomeItemId(it) in seenIds }

                    val verifiedSongsDeferred =
                        async {
                            contentFilter
                                .filterItems(rawSongs, normalized, SpaceCandidateSource.TARGETED_SEARCH, maxCandidates = 26)
                                .filterIsInstance<SongItem>()
                        }
                    val verifiedAlbumsDeferred =
                        async {
                            contentFilter
                                .filterItems(rawAlbums, normalized, SpaceCandidateSource.TARGETED_SEARCH, maxCandidates = 12)
                                .filterIsInstance<AlbumItem>()
                        }
                    val verifiedPlaylistsDeferred =
                        async {
                            contentFilter
                                .filterItems(rawPlaylists, normalized, SpaceCandidateSource.TARGETED_SEARCH, maxCandidates = 10)
                                .filterIsInstance<PlaylistItem>()
                        }
                    val verifiedArtistsDeferred =
                        async {
                            contentFilter
                                .filterItems(rawArtists, normalized, SpaceCandidateSource.TARGETED_SEARCH, maxCandidates = 10)
                                .filterIsInstance<ArtistItem>()
                        }

                    val songsList =
                        verifiedSongsDeferred
                            .await()
                            .filter { seenIds.add(canonicalHomeItemId(it)) }
                    val albumsList =
                        verifiedAlbumsDeferred
                            .await()
                            .filter { seenIds.add(canonicalHomeItemId(it)) }
                    val playlistsList =
                        verifiedPlaylistsDeferred
                            .await()
                            .filter { seenIds.add(canonicalHomeItemId(it)) }
                    val artistsList =
                        verifiedArtistsDeferred
                            .await()
                            .filter { seenIds.add(canonicalHomeItemId(it)) }

                    fallbackSongs += songsList
                    if (songsList.isNotEmpty()) {
                        collectedSections +=
                            HomeSection(
                                title = shelfTitles.songs,
                                items = songsList,
                                isListStyle = true,
                                numItemsPerColumn = 4,
                            )
                    }
                    if (albumsList.isNotEmpty()) {
                        collectedSections +=
                            HomeSection(
                                title = shelfTitles.albums,
                                items = albumsList,
                            )
                    }
                    if (playlistsList.isNotEmpty()) {
                        collectedSections +=
                            HomeSection(
                                title = shelfTitles.playlists,
                                items = playlistsList,
                            )
                    }
                    if (artistsList.isNotEmpty()) {
                        collectedSections +=
                            HomeSection(
                                title = shelfTitles.artists,
                                items = artistsList,
                            )
                    }
                }
            }
        }

        val merged = mergeAndDeduplicateHomeSections(collectedSections, userPlaylistIds = emptySet())
        val rotatedSections =
            merged.mapIndexed { index, section ->
                section.copy(
                    items =
                        orderItemsForRefresh(
                            items = section.items,
                            refreshSeed = refreshSeed,
                            previousItemIds = previousItemIds,
                            salt = index + 1,
                        ),
                )
            }

        val apiQuickPicks =
            rotatedSections
                .filter { it.browseId?.startsWith("FEmusic_quickpicks") == true }
                .flatMap { it.items.filterIsInstance<SongItem>() }
        val apiKeepListening =
            rotatedSections
                .filter { it.browseId?.startsWith("FEmusic_listen_again") == true }
                .flatMap { it.items.filterIsInstance<SongItem>() }
        val apiForgotten =
            rotatedSections
                .filter { it.browseId?.startsWith("FEmusic_forgotten_favorites") == true }
                .flatMap { it.items.filterIsInstance<SongItem>() }

        val mergedQuickPicks =
            orderItemsForRefresh(
                items = (apiQuickPicks + verifiedLocalQuickPicks).distinctBy(::canonicalHomeItemId),
                refreshSeed = refreshSeed,
                previousItemIds = previousItemIds,
                salt = 101,
            )
        val mergedKeepListening =
            orderItemsForRefresh(
                items = (apiKeepListening + verifiedLocalKeepListening).distinctBy(::canonicalHomeItemId),
                refreshSeed = refreshSeed,
                previousItemIds = previousItemIds,
                salt = 102,
            )
        val mergedForgotten =
            orderItemsForRefresh(
                items = (apiForgotten + verifiedLocalForgotten).distinctBy(::canonicalHomeItemId),
                refreshSeed = refreshSeed,
                previousItemIds = previousItemIds,
                salt = 103,
            )

        return SpaceFilteredFeedResult(
            sections = rotatedSections,
            fallbackSongs =
                orderItemsForRefresh(
                    items = fallbackSongs.distinctBy(::canonicalHomeItemId),
                    refreshSeed = refreshSeed,
                    previousItemIds = previousItemIds,
                    salt = 104,
                ),
            quickPicks = mergedQuickPicks,
            keepListening = mergedKeepListening,
            forgottenFavorites = mergedForgotten,
            nextContinuation = currentContinuation,
        )
    }

    /**
     * On refresh ([refreshSeed] > 0), floats freshly discovered items (not in [previousItemIds])
     * ahead of previously displayed items and rotates within each group so pulling to refresh
     * visibly updates recommendations just like the default Home tab.
     */
    internal fun <T : YTItem> orderItemsForRefresh(
        items: List<T>,
        refreshSeed: Int,
        previousItemIds: Set<String>,
        salt: Int = 0,
    ): List<T> {
        if (items.size <= 1 || refreshSeed <= 0) return items
        val random = Random(refreshSeed * 131 + salt * 17)
        val (previouslyShown, unseen) = items.partition { canonicalHomeItemId(it) in previousItemIds }
        val freshOrder = if (unseen.size > 1) unseen.shuffled(random) else unseen
        val repeatOrder = if (previouslyShown.size > 1) previouslyShown.shuffled(random) else previouslyShown
        return freshOrder + repeatOrder
    }

    private fun prepareCandidateSections(sections: List<HomeSection>): List<HomeSection> =
        sections
            .filterNot { section ->
                val browseId = section.browseId
                browseId != null &&
                    (
                        browseId.startsWith("FEmusic_immersive") ||
                            browseId.startsWith("FEmusic_moods_and_genres") ||
                            browseId.startsWith("FEmusic_listening_review") ||
                            browseId.startsWith("FEmusic_library")
                    )
            }.mapNotNull { section ->
                val musicItems =
                    section.items.filter { item ->
                        when (item) {
                            is SongItem -> !item.isPodcast && !item.isNonMusicalVideo
                            is AlbumItem, is ArtistItem, is PlaylistItem -> true
                            else -> false
                        }
                    }
                if (musicItems.isEmpty()) null else section.copy(items = musicItems)
            }

    private suspend fun safeSearch(
        query: String,
        filter: SearchFilter,
    ): List<YTItem> =
        safeRequest {
            GlobalYouTubeRepository.instance
                .searchFiltered(
                    query = query,
                    filter = filter,
                    recordSearchHistory = false,
                    allowOfflineFallback = false,
                ).items
        }.orEmpty()

    private suspend fun <T> safeRequest(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}
