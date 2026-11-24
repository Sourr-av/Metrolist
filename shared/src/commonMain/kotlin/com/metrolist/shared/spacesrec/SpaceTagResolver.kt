/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lastfm.LastFmArtistProfile
import com.metrolist.shared.api.lastfm.LastFmTag
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.SpaceFilterSpec
import com.metrolist.shared.spaces.LastFmSpaceTagProvider
import com.metrolist.shared.spaces.SpaceCandidateSource
import com.metrolist.shared.spaces.SpaceContentFilter
import com.metrolist.shared.spaces.SpaceFilterDecision
import com.metrolist.shared.spaces.SpaceFilterTaxonomy
import com.metrolist.shared.spaces.SpaceTagProvider
import com.metrolist.shared.utils.getCurrentTimeMillis
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ArtistTagResolution(
    val artistId: String?,
    val artistName: String,
    val detectedLanguages: Set<String>,
    val detectedGenres: Set<String>,
    val rawTags: List<LastFmTag> = emptyList(),
    val bioLanguages: Set<String> = emptySet(),
    val needsTrackDisambiguation: Boolean = false,
)

data class TrackTagResolution(
    val videoId: String,
    val language: String?,
    val languageConfidence: Double,
    val genre: String?,
    val evidence: String,
)

interface SpaceTagResolver {
    val isAvailable: Boolean
    val isCircuitOpen: Boolean

    suspend fun resolveArtist(
        artistId: String?,
        artistName: String,
        filter: SpaceFilterSpec,
    ): ArtistTagResolution?

    suspend fun resolveTrack(
        song: SongItem,
        artistResolution: ArtistTagResolution?,
        filter: SpaceFilterSpec,
    ): TrackTagResolution?
}

/**
 * L5 network resolver backed by Last.fm artist/track/album lookups.
 * Reuses [SpaceContentFilter.evaluateSignals] (never playlist handling) and wraps every
 * [LastFM] call in [CircuitBreaker].
 */
class LastFmSpaceTagResolver(
    private val httpClient: HttpClient,
    private val config: SpaceRecConfig = SpaceRecConfig.Default,
    clock: () -> Long = ::getCurrentTimeMillis,
    private val circuitBreaker: CircuitBreaker =
        CircuitBreaker(
            failureThreshold = config.circuitBreakerFailureThreshold,
            openDurationMs = config.circuitBreakerOpenDurationMs,
            clock = clock,
        ),
) : SpaceTagResolver {
    private val cacheMutex = Mutex()
    private val artistProfileCache = LinkedHashMap<String, LastFmArtistProfile>()
    private val trackTagCache = LinkedHashMap<String, List<LastFmTag>>()
    private val albumTagCache = LinkedHashMap<String, List<LastFmTag>>()
    private var cachedApiKey: String = ""

    private val tagProvider: SpaceTagProvider =
        object : SpaceTagProvider {
            override val isConfigured: Boolean
                get() = LastFM.apiKeyConfigured() && !LastFM.hasInvalidApiKey()

            override suspend fun trackTags(
                artist: String,
                track: String,
            ): List<LastFmTag>? = fetchTrackTags(artist, track)

            override suspend fun artistTags(artist: String): List<LastFmTag>? = fetchArtistTags(artist)

            override suspend fun albumTags(
                artist: String,
                album: String,
            ): List<LastFmTag>? = fetchAlbumTags(artist, album)

            override suspend fun artistProfile(artist: String): LastFmArtistProfile? = fetchArtistProfile(artist)
        }

    private val contentFilter = SpaceContentFilter(tagProvider)

    override val isAvailable: Boolean
        get() = tagProvider.isConfigured

    override val isCircuitOpen: Boolean
        get() = circuitBreaker.isOpenNow

    override suspend fun resolveArtist(
        artistId: String?,
        artistName: String,
        filter: SpaceFilterSpec,
    ): ArtistTagResolution? {
        if (!isAvailable || circuitBreaker.isOpen()) return null
        val cleanName = LastFmSpaceTagProvider.cleanArtistName(artistName)
        if (cleanName.isBlank()) return null

        val profile = fetchArtistProfile(cleanName)
        if (profile == null && circuitBreaker.isOpen()) return null

        val rawTags =
            when {
                profile != null && profile.tags.isNotEmpty() -> profile.tags
                else -> fetchArtistTags(cleanName).orEmpty()
            }
        val bioLanguages =
            profile
                ?.bioSummary
                ?.takeIf { it.isNotBlank() }
                ?.let(SpaceFilterTaxonomy::detectLanguagesFromBio)
                .orEmpty()

        if (rawTags.isEmpty() && bioLanguages.isEmpty()) {
            return ArtistTagResolution(
                artistId = artistId,
                artistName = cleanName,
                detectedLanguages = emptySet(),
                detectedGenres = emptySet(),
                rawTags = emptyList(),
                bioLanguages = emptySet(),
                needsTrackDisambiguation = true,
            )
        }

        val normalizedFilter = filter.normalized()
        val detectedLanguages =
            extractCanonicalLanguages(
                text = cleanName,
                titleOnly = cleanName,
                primaryTags = rawTags,
                fallbackTags = emptyList(),
                bioLanguages = bioLanguages,
                requestedLanguages = normalizedFilter.languages,
            )
        val detectedGenres =
            extractCanonicalGenres(
                primaryTags = rawTags,
                fallbackTags = emptyList(),
                requestedGenres = normalizedFilter.genres,
            )

        val needsTrack =
            shouldDisambiguateTrack(
                rawTags = rawTags,
                detectedLanguages = detectedLanguages,
                detectedGenres = detectedGenres,
                filter = normalizedFilter,
            )

        return ArtistTagResolution(
            artistId = artistId,
            artistName = cleanName,
            detectedLanguages = detectedLanguages,
            detectedGenres = detectedGenres,
            rawTags = rawTags,
            bioLanguages = bioLanguages,
            needsTrackDisambiguation = needsTrack,
        )
    }

    override suspend fun resolveTrack(
        song: SongItem,
        artistResolution: ArtistTagResolution?,
        filter: SpaceFilterSpec,
    ): TrackTagResolution? {
        if (!isAvailable || circuitBreaker.isOpen()) return null

        val primaryArtist =
            song.artists
                .firstOrNull()
                ?.name
                ?.let(LastFmSpaceTagProvider::cleanArtistName)
                .orEmpty()
        val cleanTitle = LastFmSpaceTagProvider.cleanMediaTitle(song.title)
        if (primaryArtist.isBlank() || cleanTitle.isBlank()) return null

        val normalizedFilter = filter.normalized()
        val trackTags = fetchTrackTags(primaryArtist, cleanTitle).orEmpty()
        val albumTags =
            if (trackTags.isEmpty() && !song.album?.name.isNullOrBlank()) {
                fetchAlbumTags(primaryArtist, song.album!!.name).orEmpty()
            } else {
                emptyList()
            }

        val primaryTags =
            buildList {
                addAll(trackTags)
                addAll(albumTags)
                song.genre?.takeIf { it.isNotBlank() }?.let { add(LastFmTag(it, 60)) }
            }
        val fallbackTags = artistResolution?.rawTags.orEmpty()
        val bioLanguages = artistResolution?.bioLanguages.orEmpty()
        if (primaryTags.isEmpty() && fallbackTags.isEmpty() && bioLanguages.isEmpty()) return null

        val combinedText = LanguageCascade.buildSongLanguageText(song)
        val detectedLangs =
            extractCanonicalLanguages(
                text = combinedText,
                titleOnly = cleanTitle,
                primaryTags = primaryTags,
                fallbackTags = fallbackTags,
                bioLanguages = bioLanguages,
                requestedLanguages = normalizedFilter.languages,
            )
        val detectedGenres =
            extractCanonicalGenres(
                primaryTags = primaryTags,
                fallbackTags = fallbackTags,
                requestedGenres = normalizedFilter.genres,
            )

        val resolvedLang = detectedLangs.firstOrNull()
        val resolvedGenre = detectedGenres.takeIf { it.isNotEmpty() }?.joinToString(",")
        if (resolvedLang == null && resolvedGenre == null) return null

        return TrackTagResolution(
            videoId = song.id,
            language = resolvedLang,
            languageConfidence = if (resolvedLang != null) 1.0 else 0.0,
            genre = resolvedGenre,
            evidence = "lastfm",
        )
    }

    private fun extractCanonicalLanguages(
        text: String,
        titleOnly: String,
        primaryTags: List<LastFmTag>,
        fallbackTags: List<LastFmTag>,
        bioLanguages: Set<String>,
        requestedLanguages: List<String>,
    ): Set<String> {
        val result = linkedSetOf<String>()

        for (bioLang in bioLanguages) {
            SpaceLanguageRegistry.canonicalCode(bioLang)?.let(result::add)
        }
        val allTags = primaryTags + fallbackTags
        for (tag in allTags) {
            val detected = SpaceFilterTaxonomy.detectLanguageFromTag(tag.name)
            if (detected != null) {
                if (detected == SpaceLanguageRegistry.INSTRUMENTAL_SLUG) continue
                SpaceLanguageRegistry.canonicalCode(detected)?.let(result::add)
            } else {
                // Match custom free-text language slugs directly against normalized Last.fm tags.
                val normTag = SpaceFilterTaxonomy.normalizeTag(tag.name)
                for (req in requestedLanguages) {
                    val reqClean = req.trim().lowercase()
                    if (SpaceLanguageRegistry.resolve(reqClean) == null && normTag == reqClean) {
                        result += reqClean
                    }
                }
            }
        }

        // Reuse SpaceContentFilter.evaluateSignals for artist/track/album language verdicts
        // (handles conflict priority, English fallback, and custom language rules).
        val targets = SpaceLanguageRegistry.parseTargets(requestedLanguages)
        if (!targets.isEmpty) {
            val langOnlyFilter = SpaceFilterSpec(languages = requestedLanguages)
            val decision =
                contentFilter.evaluateSignals(
                    text = text,
                    primaryTags = primaryTags,
                    fallbackTags = fallbackTags,
                    filter = langOnlyFilter,
                    source = SpaceCandidateSource.HOME_FEED,
                    titleOnly = titleOnly,
                    bioLanguages = bioLanguages,
                )
            when (decision) {
                SpaceFilterDecision.ALLOW -> {
                    val matchingExisting = result.filter { targets.matchesCode(it) }
                    if (matchingExisting.isNotEmpty()) {
                        return matchingExisting.toSet()
                    }
                    if (targets.canonicalCodes.size == 1) {
                        result += targets.canonicalCodes.first()
                    }
                }

                SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT -> {
                    // Keep only non-requested languages so L0/L2 rejects cleanly.
                    val conflicting = result.filterNot { targets.matchesCode(it) }
                    if (conflicting.isNotEmpty()) {
                        return conflicting.toSet()
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        return result
    }

    private fun extractCanonicalGenres(
        primaryTags: List<LastFmTag>,
        fallbackTags: List<LastFmTag>,
        requestedGenres: List<String>,
    ): Set<String> {
        val useful = (primaryTags + fallbackTags).filterNot { SpaceFilterTaxonomy.isNoiseTag(it.name) }
        val result = linkedSetOf<String>()
        for (tag in useful) {
            for (genreId in SpaceFilterTaxonomy.detectGenresFromTag(tag.name)) {
                result += genreId
            }
            val norm = SpaceFilterTaxonomy.normalizeTag(tag.name)
            if (norm == SpaceLanguageRegistry.INSTRUMENTAL_SLUG || norm == "no vocals") {
                result += SpaceLanguageRegistry.INSTRUMENTAL_SLUG
            }
        }

        if (requestedGenres.isNotEmpty()) {
            val genreOnlyFilter = SpaceFilterSpec(genres = requestedGenres)
            val decision =
                contentFilter.evaluateSignals(
                    text = "",
                    primaryTags = primaryTags,
                    fallbackTags = fallbackTags,
                    filter = genreOnlyFilter,
                    source = SpaceCandidateSource.HOME_FEED,
                )
            if (decision == SpaceFilterDecision.ALLOW) {
                val matching =
                    requestedGenres.filter { req ->
                        result.any { g ->
                            g.equals(req, ignoreCase = true) ||
                                SpaceFilterTaxonomy.tagsMatchRequestedGenre(listOf(g), req)
                        }
                    }
                if (matching.isNotEmpty()) {
                    return linkedSetOf<String>().apply {
                        addAll(matching)
                        addAll(result)
                    }
                }
            }
        }

        return result
    }

    private fun shouldDisambiguateTrack(
        rawTags: List<LastFmTag>,
        detectedLanguages: Set<String>,
        detectedGenres: Set<String>,
        filter: SpaceFilterSpec,
    ): Boolean {
        val langTargets = SpaceLanguageRegistry.parseTargets(filter.languages)
        if (!langTargets.isEmpty && detectedLanguages.isEmpty()) return true
        if (filter.genres.isNotEmpty() && detectedGenres.isEmpty()) return true
        if (filter.genres.any { it == "soundtracks_video_game" || it == "soundtracks_movie" }) return true
        if (filter.genres.isNotEmpty()) {
            val requestedRoots =
                filter.genres.mapTo(mutableSetOf()) { id ->
                    SpaceFilterTaxonomy.genreOption(id)?.let { it.parentId ?: it.id } ?: id
                }
            var matchingMaxCount = 0
            var unrelatedMaxCount = 0
            val strongUnrelatedRoots = mutableSetOf<String>()
            for (tag in rawTags) {
                if (SpaceFilterTaxonomy.isNoiseTag(tag.name)) continue
                val roots =
                    SpaceFilterTaxonomy
                        .detectGenresFromTag(tag.name)
                        .mapTo(mutableSetOf()) { id ->
                            SpaceFilterTaxonomy.genreOption(id)?.let { it.parentId ?: it.id } ?: id
                        }
                if (roots.isEmpty()) continue
                if (roots.any { it in requestedRoots }) {
                    matchingMaxCount = maxOf(matchingMaxCount, tag.count)
                } else {
                    unrelatedMaxCount = maxOf(unrelatedMaxCount, tag.count)
                    if (tag.count >= 55) {
                        strongUnrelatedRoots.addAll(roots)
                    }
                }
            }
            // If the artist's dominant genre tag clearly matches the requested genre (e.g. hip-hop/rap at 88–100
            // with secondary crossover tags like rnb/pop), accept artist-level classification without forcing
            // extra per-track Last.fm round-trips.
            if (matchingMaxCount >= 60 && matchingMaxCount >= unrelatedMaxCount) {
                return false
            }
            if (strongUnrelatedRoots.size >= 2) return true
        }
        return false
    }

    private suspend fun invalidateCacheIfApiKeyChanged() {
        val currentKey = LastFM.effectiveApiKey()
        if (currentKey != cachedApiKey) {
            artistProfileCache.clear()
            trackTagCache.clear()
            albumTagCache.clear()
            cachedApiKey = currentKey
        }
    }

    private suspend fun fetchArtistProfile(artist: String): LastFmArtistProfile? {
        val key = artist.lowercase()
        cacheMutex.withLock {
            invalidateCacheIfApiKeyChanged()
            artistProfileCache[key]?.let { return it }
        }
        val call =
            circuitBreaker.execute(config.perCallTimeoutMs) {
                LastFM.getArtistProfile(httpClient, artist)
            }
        return when (call) {
            is CircuitCallResult.Success -> {
                cacheMutex.withLock {
                    if (!LastFM.hasInvalidApiKey()) {
                        putBounded(artistProfileCache, key, call.value)
                    }
                }
                call.value
            }

            else -> {
                null
            }
        }
    }

    private suspend fun fetchArtistTags(artist: String): List<LastFmTag>? {
        val call =
            circuitBreaker.execute(config.perCallTimeoutMs) {
                LastFM.getArtistTopTags(httpClient, artist)
            }
        return (call as? CircuitCallResult.Success)?.value
    }

    private suspend fun fetchTrackTags(
        artist: String,
        track: String,
    ): List<LastFmTag>? {
        val key = "${artist.lowercase()}|${track.lowercase()}"
        cacheMutex.withLock {
            invalidateCacheIfApiKeyChanged()
            trackTagCache[key]?.let { return it }
        }
        val call =
            circuitBreaker.execute(config.perCallTimeoutMs) {
                LastFM.getTrackTopTags(httpClient, artist, track)
            }
        return when (call) {
            is CircuitCallResult.Success -> {
                cacheMutex.withLock {
                    if (!LastFM.hasInvalidApiKey()) {
                        putBounded(trackTagCache, key, call.value)
                    }
                }
                call.value
            }

            else -> {
                null
            }
        }
    }

    private suspend fun fetchAlbumTags(
        artist: String,
        album: String,
    ): List<LastFmTag>? {
        val key = "${artist.lowercase()}|${album.lowercase()}"
        cacheMutex.withLock {
            invalidateCacheIfApiKeyChanged()
            albumTagCache[key]?.let { return it }
        }
        val call =
            circuitBreaker.execute(config.perCallTimeoutMs) {
                LastFM.getAlbumTopTags(httpClient, artist, album)
            }
        return when (call) {
            is CircuitCallResult.Success -> {
                cacheMutex.withLock {
                    if (!LastFM.hasInvalidApiKey()) {
                        putBounded(albumTagCache, key, call.value)
                    }
                }
                call.value
            }

            else -> {
                null
            }
        }
    }

    private fun <V> putBounded(
        cache: LinkedHashMap<String, V>,
        key: String,
        value: V,
    ) {
        if (cache.size >= MAX_CACHE_ENTRIES) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
        cache[key] = value
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 2_000
    }
}
