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

data class SpaceFallbackShelfTitles(
    val songs: String = "Recommended songs",
    val albums: String = "Matching albums",
    val playlists: String = "Matching playlists",
    val artists: String = "Matching artists",
)

data class SpaceFilteredFeedResult(
    val sections: List<HomeSection>,
    val fallbackSongs: List<SongItem> = emptyList(),
)

object SpaceFeedSupplement {
    private const val MIN_VERIFIED_ITEMS_AFTER_HOME = 14
    private const val MIN_VERIFIED_ITEMS_AFTER_CHIPS = 16

    /**
     * Multi-stage strict filtering + smart sourcing pipeline for a filtered Space:
     * 1. Screen the normal Home feed strictly via Last.fm tags + script/language detection.
     * 2. If too few items pass, check YouTube's closest Home chip (e.g. Pop, Relax, Energize)
     *    and YouTube Music's Moods & Genres browse page for matching genre(s), screening strictly.
     * 3. If still too few items pass, query YouTube Music search (without recording search history)
     *    for the Space's language + genre, strictly rejecting any language/genre conflict while
     *    allowing untagged tracks returned by the targeted search.
     */
    suspend fun buildFilteredFeed(
        rawHomeSections: List<HomeSection>,
        homeChips: List<Chip>,
        exploreSections: List<HomeSection>,
        filter: SpaceFilterSpec,
        contentFilter: SpaceContentFilter,
        shelfTitles: SpaceFallbackShelfTitles = SpaceFallbackShelfTitles(),
        signedIn: Boolean = false,
    ): SpaceFilteredFeedResult {
        val normalized = filter.normalized()
        if (!normalized.isActive) return SpaceFilteredFeedResult(sections = rawHomeSections)

        val collectedSections = mutableListOf<HomeSection>()
        val seenIds = mutableSetOf<String>()

        fun addVerifiedSections(sections: List<HomeSection>) {
            for (section in sections) {
                val uniqueItems =
                    section.items.filter { item ->
                        val key = canonicalHomeItemId(item)
                        seenIds.add(key)
                    }
                if (uniqueItems.isNotEmpty()) {
                    collectedSections += section.copy(items = uniqueItems)
                }
            }
        }

        // --- Stage 1: Screen normal Home feed (strict HOME_FEED mode) ---
        val homeCandidates = prepareCandidateSections(rawHomeSections)
        addVerifiedSections(
            contentFilter.filterSections(
                sections = homeCandidates,
                filter = normalized,
                source = SpaceCandidateSource.HOME_FEED,
                maxCandidates = 85,
            ),
        )

        // --- Stage 2a: Closest YouTube Home chip (e.g. Hip-hop, Pop, Relax, Energize) ---
        if (seenIds.size < MIN_VERIFIED_ITEMS_AFTER_HOME && normalized.genres.isNotEmpty()) {
            val matchingChip = SpaceFilterTaxonomy.findMatchingChips(homeChips, normalized.genres).firstOrNull()
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
                            maxCandidates = 50,
                        ),
                    )
                }
            }
        }

        // --- Stage 2b: YouTube Music Moods & Genres browse page matching requested genre(s) ---
        if (seenIds.size < MIN_VERIFIED_ITEMS_AFTER_HOME && normalized.genres.isNotEmpty()) {
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
            val matchingMood = SpaceFilterTaxonomy.findMatchingMoodItems(moodBrowseItems, normalized.genres).firstOrNull()
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
                            maxCandidates = 55,
                        ),
                    )
                }
            }
        }

        // --- Stage 2c: Unpersonalized (signed-out) Home page pass if signed in and still sparse ---
        if (seenIds.size < MIN_VERIFIED_ITEMS_AFTER_HOME && signedIn) {
            val anonPage =
                safeRequest {
                    GlobalYouTubeRepository.instance.getHomePageData(
                        browseId = "FEmusic_home",
                        setLogin = false,
                    )
                }
            if (anonPage != null) {
                val anonCandidates = prepareCandidateSections(anonPage.sections)
                addVerifiedSections(
                    contentFilter.filterSections(
                        sections = anonCandidates,
                        filter = normalized,
                        source = SpaceCandidateSource.HOME_FEED,
                        maxCandidates = 45,
                    ),
                )
            }
        }

        // --- Stage 3: Targeted YouTube Music Search fallback (strict conflict rejection, trust untagged) ---
        val fallbackSongs = mutableListOf<SongItem>()
        if (seenIds.size < MIN_VERIFIED_ITEMS_AFTER_CHIPS) {
            val queries = SpaceFilterTaxonomy.buildFallbackSearchQueries(normalized)
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
                                .filterItems(rawSongs, normalized, SpaceCandidateSource.TARGETED_SEARCH, maxCandidates = 30)
                                .filterIsInstance<SongItem>()
                        }
                    val verifiedAlbumsDeferred =
                        async {
                            contentFilter
                                .filterItems(rawAlbums, normalized, SpaceCandidateSource.TARGETED_SEARCH, maxCandidates = 14)
                                .filterIsInstance<AlbumItem>()
                        }
                    val verifiedPlaylistsDeferred =
                        async {
                            contentFilter
                                .filterItems(rawPlaylists, normalized, SpaceCandidateSource.TARGETED_SEARCH, maxCandidates = 12)
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
        return SpaceFilteredFeedResult(
            sections = merged,
            fallbackSongs = fallbackSongs,
        )
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
