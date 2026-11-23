/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spaces

import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lastfm.LastFmArtistProfile
import com.metrolist.shared.api.lastfm.LastFmTag
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.SpaceFilterSpec
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class SpaceCandidateSource {
    HOME_FEED,
    TARGETED_SEARCH,
}

enum class SpaceFilterDecision {
    ALLOW,
    REJECT_UNSUPPORTED_TYPE,
    REJECT_LANGUAGE_CONFLICT,
    REJECT_LANGUAGE_UNVERIFIED,
    REJECT_GENRE_CONFLICT,
    REJECT_GENRE_UNVERIFIED,
}

interface SpaceTagProvider {
    val isConfigured: Boolean

    suspend fun trackTags(
        artist: String,
        track: String,
    ): List<LastFmTag>?

    suspend fun artistTags(artist: String): List<LastFmTag>?

    suspend fun albumTags(
        artist: String,
        album: String,
    ): List<LastFmTag>?

    suspend fun artistProfile(artist: String): LastFmArtistProfile? = null
}

class LastFmSpaceTagProvider(
    private val httpClient: HttpClient,
    maxConcurrentRequests: Int = 10,
    private val maxCacheEntries: Int = 2500,
) : SpaceTagProvider {
    private val cacheMutex = Mutex()
    private val tagCache = LinkedHashMap<String, List<LastFmTag>>()
    private val tagInFlight = mutableMapOf<String, CompletableDeferred<List<LastFmTag>?>>()
    private val profileCache = LinkedHashMap<String, LastFmArtistProfile>()
    private val profileInFlight = mutableMapOf<String, CompletableDeferred<LastFmArtistProfile?>>()
    private val semaphore = Semaphore(maxConcurrentRequests)
    private var cachedApiKey: String = ""

    override val isConfigured: Boolean
        get() = LastFM.apiKeyConfigured() && !LastFM.hasInvalidApiKey()

    suspend fun clearCache() {
        cacheMutex.withLock {
            tagCache.clear()
            profileCache.clear()
            cachedApiKey = LastFM.effectiveApiKey()
        }
    }

    override suspend fun trackTags(
        artist: String,
        track: String,
    ): List<LastFmTag>? {
        val cleanArtist = cleanArtistName(artist)
        val cleanTrack = cleanMediaTitle(track)
        if (cleanArtist.isBlank() || cleanTrack.isBlank()) return emptyList()
        return cachedTagLookup("track:${cleanArtist.lowercase()}|${cleanTrack.lowercase()}") {
            LastFM.getTrackTopTags(httpClient, cleanArtist, cleanTrack)
        }
    }

    override suspend fun artistTags(artist: String): List<LastFmTag>? {
        val cleanArtist = cleanArtistName(artist)
        if (cleanArtist.isBlank()) return emptyList()
        return cachedTagLookup("artist:${cleanArtist.lowercase()}") {
            LastFM.getArtistTopTags(httpClient, cleanArtist)
        }
    }

    override suspend fun albumTags(
        artist: String,
        album: String,
    ): List<LastFmTag>? {
        val cleanArtist = cleanArtistName(artist)
        val cleanAlbum = cleanMediaTitle(album)
        if (cleanArtist.isBlank() || cleanAlbum.isBlank()) return emptyList()
        return cachedTagLookup("album:${cleanArtist.lowercase()}|${cleanAlbum.lowercase()}") {
            LastFM.getAlbumTopTags(httpClient, cleanArtist, cleanAlbum)
        }
    }

    override suspend fun artistProfile(artist: String): LastFmArtistProfile? {
        val cleanArtist = cleanArtistName(artist)
        if (cleanArtist.isBlank()) return LastFmArtistProfile()
        return cachedProfileLookup("profile:${cleanArtist.lowercase()}") {
            LastFM.getArtistProfile(httpClient, cleanArtist)
        }
    }

    private suspend fun invalidateIfApiKeyChangedLocked() {
        val currentKey = LastFM.effectiveApiKey()
        if (currentKey != cachedApiKey) {
            tagCache.clear()
            profileCache.clear()
            cachedApiKey = currentKey
        }
    }

    private suspend fun cachedTagLookup(
        key: String,
        fetch: suspend () -> List<LastFmTag>?,
    ): List<LastFmTag>? {
        if (!isConfigured) return null

        val existingDeferred: CompletableDeferred<List<LastFmTag>?>?
        val createdDeferred: CompletableDeferred<List<LastFmTag>?>?

        cacheMutex.withLock {
            invalidateIfApiKeyChangedLocked()
            tagCache[key]?.let { return it }
            val active = tagInFlight[key]
            if (active != null) {
                existingDeferred = active
                createdDeferred = null
            } else {
                val created = CompletableDeferred<List<LastFmTag>?>()
                tagInFlight[key] = created
                existingDeferred = null
                createdDeferred = created
            }
        }

        if (existingDeferred != null) {
            return existingDeferred.await()
        }

        val deferred = createdDeferred!!
        return try {
            val result = semaphore.withPermit { fetch() }
            cacheMutex.withLock {
                tagInFlight.remove(key)
                // Cache empty results (and transient failures if key is valid) to avoid hammering Last.fm on reload.
                if (result != null && !LastFM.hasInvalidApiKey()) {
                    if (tagCache.size >= maxCacheEntries) {
                        val oldestKey = tagCache.keys.firstOrNull()
                        if (oldestKey != null) tagCache.remove(oldestKey)
                    }
                    tagCache[key] = result
                }
            }
            deferred.complete(result)
            result
        } catch (e: Throwable) {
            cacheMutex.withLock {
                tagInFlight.remove(key)
            }
            deferred.complete(null)
            throw e
        }
    }

    private suspend fun cachedProfileLookup(
        key: String,
        fetch: suspend () -> LastFmArtistProfile?,
    ): LastFmArtistProfile? {
        if (!isConfigured) return null

        val existingDeferred: CompletableDeferred<LastFmArtistProfile?>?
        val createdDeferred: CompletableDeferred<LastFmArtistProfile?>?

        cacheMutex.withLock {
            invalidateIfApiKeyChangedLocked()
            profileCache[key]?.let { return it }
            val active = profileInFlight[key]
            if (active != null) {
                existingDeferred = active
                createdDeferred = null
            } else {
                val created = CompletableDeferred<LastFmArtistProfile?>()
                profileInFlight[key] = created
                existingDeferred = null
                createdDeferred = created
            }
        }

        if (existingDeferred != null) {
            return existingDeferred.await()
        }

        val deferred = createdDeferred!!
        return try {
            val result = semaphore.withPermit { fetch() }
            cacheMutex.withLock {
                profileInFlight.remove(key)
                if (result != null && !LastFM.hasInvalidApiKey()) {
                    if (profileCache.size >= maxCacheEntries) {
                        val oldestKey = profileCache.keys.firstOrNull()
                        if (oldestKey != null) profileCache.remove(oldestKey)
                    }
                    profileCache[key] = result
                }
            }
            deferred.complete(result)
            result
        } catch (e: Throwable) {
            cacheMutex.withLock {
                profileInFlight.remove(key)
            }
            deferred.complete(null)
            throw e
        }
    }

    companion object {
        private val TITLE_NOISE_REGEX =
            Regex(
                """\s*[\(\[]\s*(?:official\s+(?:music\s+)?(?:video|audio|lyric\s+video|visualizer)|lyric\s+video|lyrics|audio|video|visualizer|hd|4k|hq|explicit|clean|remastered(?:\s+\d{4})?|feat\.?[^\)\]]*|ft\.?[^\)\]]*|featuring[^\)\]]*|from\s+["“][^"”]*["”])\s*[\)\]]""",
                RegexOption.IGNORE_CASE,
            )

        private val TRAILING_FEAT_REGEX =
            Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

        fun cleanMediaTitle(raw: String): String =
            raw
                .replace(TITLE_NOISE_REGEX, "")
                .replace(TRAILING_FEAT_REGEX, "")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifBlank { raw.trim() }

        fun cleanArtistName(raw: String): String =
            raw
                .substringBefore(" - Topic")
                .substringBefore(" • ")
                .trim()
    }
}

class SpaceContentFilter(
    private val tagProvider: SpaceTagProvider,
) {
    suspend fun evaluate(
        item: YTItem,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val normalized = filter.normalized()
        if (!normalized.isActive) return SpaceFilterDecision.ALLOW

        return when (item) {
            is SongItem -> evaluateSong(item, normalized, source)
            is AlbumItem -> evaluateAlbum(item, normalized, source)
            is ArtistItem -> evaluateArtist(item, normalized, source)
            is PlaylistItem -> evaluatePlaylist(item, normalized, source)
            else -> SpaceFilterDecision.REJECT_UNSUPPORTED_TYPE
        }
    }

    suspend fun filterItems(
        items: List<YTItem>,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
        maxCandidates: Int = 80,
    ): List<YTItem> {
        val normalized = filter.normalized()
        if (!normalized.isActive || items.isEmpty()) return items
        val bounded = items.take(maxCandidates)
        return coroutineScope {
            bounded
                .map { item ->
                    async {
                        val decision = evaluate(item, normalized, source)
                        if (decision == SpaceFilterDecision.ALLOW) item else null
                    }
                }.awaitAll()
                .filterNotNull()
        }
    }

    suspend fun filterSections(
        sections: List<HomeSection>,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
        maxCandidates: Int = 100,
    ): List<HomeSection> {
        val normalized = filter.normalized()
        if (!normalized.isActive || sections.isEmpty()) return sections

        val uniqueCandidates = linkedMapOf<String, YTItem>()
        for (section in sections) {
            for (item in section.items) {
                if (uniqueCandidates.size >= maxCandidates) break
                uniqueCandidates.putIfMissing(item.id, item)
            }
            if (uniqueCandidates.size >= maxCandidates) break
        }

        val allowedIds =
            coroutineScope {
                uniqueCandidates.values
                    .map { item ->
                        async {
                            val decision = evaluate(item, normalized, source)
                            if (decision == SpaceFilterDecision.ALLOW) item.id else null
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .toSet()
            }

        return sections.mapNotNull { section ->
            val filtered = section.items.filter { it.id in allowedIds }
            if (filtered.isEmpty()) null else section.copy(items = filtered)
        }
    }

    private suspend fun evaluateSong(
        song: SongItem,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val artistNames = song.artists.map { it.name.trim() }.filter { it.isNotBlank() }
        val primaryArtist = artistNames.firstOrNull().orEmpty()
        val combinedText =
            buildString {
                append(song.title)
                if (artistNames.isNotEmpty()) {
                    append(' ')
                    append(artistNames.joinToString(" "))
                }
                song.album?.name?.takeIf { it.isNotBlank() }?.let {
                    append(' ')
                    append(it)
                }
            }

        // Fast-path: reject obvious non-matching scripts or Romanized foreign markers before any HTTP call.
        if (filter.languages.isNotEmpty()) {
            val fastDecision = quickTextLanguageCheck(combinedText, filter.languages)
            if (fastDecision == SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }

        // Artist-first lookup: one cached Last.fm artist.getInfo call covers ALL songs by that artist
        // and gives us BOTH top artist tags AND their English bio/origin nationality.
        val artistSignals = fetchArtistsSignals(artistNames, checkCollaborators = filter.languages.isNotEmpty())

        // Fast short-circuit if the artist's bio/tags clearly conflict with the requested language.
        if (filter.languages.isNotEmpty() && (artistSignals.tags.isNotEmpty() || artistSignals.bioLanguages.isNotEmpty())) {
            val artistLangDecision =
                evaluateLanguage(
                    text = combinedText,
                    titleOnly = song.title,
                    primaryTags = emptyList(),
                    fallbackTags = artistSignals.tags,
                    bioLanguages = artistSignals.bioLanguages,
                    requestedLanguages = filter.languages,
                    source = source,
                )
            if (artistLangDecision == SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }

        val extraGenres =
            buildList {
                song.genre?.takeIf { it.isNotBlank() }?.let { add(LastFmTag(it, 60)) }
                extractSoundtrackTitleTags(combinedText).forEach { add(LastFmTag(it, 45)) }
            }

        val trackTags =
            if (primaryArtist.isNotBlank() && shouldFetchTrackTags(artistSignals, extraGenres, filter)) {
                tagProvider.trackTags(primaryArtist, song.title).orEmpty()
            } else {
                emptyList()
            }

        return evaluateSignals(
            text = combinedText,
            titleOnly = song.title,
            primaryTags = trackTags + extraGenres,
            fallbackTags = artistSignals.tags,
            bioLanguages = artistSignals.bioLanguages,
            filter = filter,
            source = source,
        )
    }

    private suspend fun evaluateAlbum(
        album: AlbumItem,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val artistNames = album.artists.map { it.name.trim() }.filter { it.isNotBlank() }
        val primaryArtist = artistNames.firstOrNull().orEmpty()
        val combinedText =
            buildString {
                append(album.title)
                if (artistNames.isNotEmpty()) {
                    append(' ')
                    append(artistNames.joinToString(" "))
                }
            }

        if (filter.languages.isNotEmpty()) {
            if (quickTextLanguageCheck(combinedText, filter.languages) == SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }

        val artistSignals = fetchArtistsSignals(artistNames, checkCollaborators = filter.languages.isNotEmpty())
        if (filter.languages.isNotEmpty() && (artistSignals.tags.isNotEmpty() || artistSignals.bioLanguages.isNotEmpty())) {
            val artistLangDecision =
                evaluateLanguage(
                    text = combinedText,
                    titleOnly = album.title,
                    primaryTags = emptyList(),
                    fallbackTags = artistSignals.tags,
                    bioLanguages = artistSignals.bioLanguages,
                    requestedLanguages = filter.languages,
                    source = source,
                )
            if (artistLangDecision == SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }

        val extraGenres = extractSoundtrackTitleTags(combinedText).map { LastFmTag(it, 55) }
        val albumTags =
            if (primaryArtist.isNotBlank() && shouldFetchTrackTags(artistSignals, extraGenres, filter)) {
                tagProvider.albumTags(primaryArtist, album.title).orEmpty()
            } else {
                emptyList()
            }

        return evaluateSignals(
            text = combinedText,
            titleOnly = album.title,
            primaryTags = albumTags + extraGenres,
            fallbackTags = artistSignals.tags,
            bioLanguages = artistSignals.bioLanguages,
            filter = filter,
            source = source,
        )
    }

    private suspend fun evaluateArtist(
        artist: ArtistItem,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val combinedText = artist.title
        if (filter.languages.isNotEmpty()) {
            if (quickTextLanguageCheck(combinedText, filter.languages) == SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }
        val artistSignals = fetchArtistsSignals(listOf(artist.title), checkCollaborators = false)
        return evaluateSignals(
            text = combinedText,
            titleOnly = artist.title,
            primaryTags = artistSignals.tags,
            fallbackTags = emptyList(),
            bioLanguages = artistSignals.bioLanguages,
            filter = filter,
            source = source,
        )
    }

    private fun evaluatePlaylist(
        playlist: PlaylistItem,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val combinedText =
            listOfNotNull(playlist.title, playlist.author, playlist.subtitle, playlist.description)
                .filter { it.isNotBlank() }
                .joinToString(" ")

        val extractedLanguageTags = extractTextLanguageTags(combinedText).map { LastFmTag(it, 80) }
        val extractedGenreTags = extractPlaylistGenreTags(combinedText).map { LastFmTag(it, 80) }

        // Strict playlist language policy: when a language filter is active, generic language-less playlists
        // (like "Hip-Hop Hits" or "Top Rap", which often localize to non-English regional hits) must NOT pass.
        if (filter.languages.isNotEmpty()) {
            val scriptLangs = SpaceFilterTaxonomy.detectLanguagesFromScript(combinedText)
            val latinHints = SpaceFilterTaxonomy.detectLatinLanguageHints(combinedText)
            val requestedIds = filter.languages.mapTo(mutableSetOf()) { resolveCanonicalLanguageId(it) }
            val hasExplicitRequestedLang =
                extractedLanguageTags.any { SpaceFilterTaxonomy.detectLanguageFromTag(it.name) in requestedIds } ||
                    scriptLangs.any { it in requestedIds } ||
                    latinHints.any { it in requestedIds }
            if (!hasExplicitRequestedLang) {
                return SpaceFilterDecision.REJECT_LANGUAGE_UNVERIFIED
            }
        }

        return evaluateSignals(
            text = combinedText,
            titleOnly = playlist.title,
            primaryTags = extractedLanguageTags + extractedGenreTags,
            fallbackTags = emptyList(),
            bioLanguages = emptySet(),
            filter = filter,
            source = source,
        )
    }

    private data class ArtistSignalBundle(
        val tags: List<LastFmTag> = emptyList(),
        val bioLanguages: Set<String> = emptySet(),
    )

    private suspend fun fetchArtistsSignals(
        artistNames: List<String>,
        checkCollaborators: Boolean,
    ): ArtistSignalBundle {
        val targets = if (checkCollaborators) artistNames.take(3) else artistNames.take(1)
        if (targets.isEmpty()) return ArtistSignalBundle()

        val profiles =
            coroutineScope {
                targets
                    .mapIndexed { index, name ->
                        async {
                            val profile = tagProvider.artistProfile(name)
                            val tags =
                                when {
                                    profile != null && profile.tags.isNotEmpty() -> profile.tags
                                    index == 0 -> tagProvider.artistTags(name).orEmpty()
                                    else -> emptyList()
                                }
                            val bioLangs =
                                profile
                                    ?.bioSummary
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(SpaceFilterTaxonomy::detectLanguagesFromBio)
                                    .orEmpty()
                            tags to bioLangs
                        }
                    }.awaitAll()
            }

        val primaryTags = profiles.firstOrNull()?.first.orEmpty()
        // Include collaborator tags only for language detection so a featured foreign artist flags language conflicts,
        // while keeping genre evaluation anchored to the primary artist.
        val collaboratorLanguageTags =
            profiles
                .drop(1)
                .flatMap { it.first }
                .filter { SpaceFilterTaxonomy.detectLanguageFromTag(it.name) != null }
        val allBioLanguages = profiles.flatMapTo(mutableSetOf()) { it.second }

        return ArtistSignalBundle(
            tags = primaryTags + collaboratorLanguageTags,
            bioLanguages = allBioLanguages,
        )
    }

    /**
     * Decides whether a per-track/album Last.fm lookup is needed after we already have the artist profile.
     * Skipping track lookups when the artist profile already cleanly confirms language + single-focus genre
     * cuts HTTP calls by ~65-80% and lets the user's own tracks pass even when track-level tags are sparse.
     */
    private fun shouldFetchTrackTags(
        artistSignals: ArtistSignalBundle,
        extraGenres: List<LastFmTag>,
        filter: SpaceFilterSpec,
    ): Boolean {
        val artistUsefulTags = artistSignals.tags.map { it.name }.filterNot(SpaceFilterTaxonomy::isNoiseTag)
        val combinedTags = artistUsefulTags + extraGenres.map { it.name }

        // Soundtrack subgenres (Video Game vs Movie) need track/album-level cues to distinguish accurately.
        val needsSoundtrackSubgenre =
            filter.genres.any { it == "soundtracks_video_game" || it == "soundtracks_movie" }
        if (needsSoundtrackSubgenre) return true

        // If artist returned nothing at all, definitely try track-level tags.
        if (artistUsefulTags.isEmpty() && artistSignals.bioLanguages.isEmpty()) return true

        val requestedLangIds = filter.languages.mapTo(mutableSetOf()) { resolveCanonicalLanguageId(it) }
        val artistConfirmedLanguage =
            filter.languages.isEmpty() ||
                artistSignals.bioLanguages.any { it in requestedLangIds } ||
                artistUsefulTags.any { SpaceFilterTaxonomy.detectLanguageFromTag(it) in requestedLangIds }

        val artistConfirmedGenre =
            filter.genres.isEmpty() ||
                filter.genres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(combinedTags, it) }

        if (!artistConfirmedLanguage || !artistConfirmedGenre) {
            return true
        }

        // If a genre filter is active and the artist is a broad crossover artist (has 2+ unrelated top genres
        // alongside the requested genre, e.g. Drake with hip-hop + pop + rnb), fetch track tags to screen
        // out tracks from the artist's non-matching genre.
        if (filter.genres.isNotEmpty()) {
            val requestedGenreRoots =
                filter.genres.mapTo(mutableSetOf()) { id ->
                    SpaceFilterTaxonomy.genreOption(id)?.let { it.parentId ?: it.id } ?: id
                }
            val artistTopGenreRoots =
                artistUsefulTags
                    .flatMap { SpaceFilterTaxonomy.detectGenresFromTag(it) }
                    .mapTo(mutableSetOf()) { id ->
                        SpaceFilterTaxonomy.genreOption(id)?.let { it.parentId ?: it.id } ?: id
                    }
            val unrelatedTopGenres = artistTopGenreRoots - requestedGenreRoots
            if (unrelatedTopGenres.size >= 2) {
                return true
            }
        }

        return false
    }

    /**
     * Core two-stage filter:
     *   Stage 1 (STRICT PRIORITY): Language check.
     *   Stage 2: Genre check (supports hierarchical parent/child + compound tags like "pop rock").
     */
    internal fun evaluateSignals(
        text: String,
        primaryTags: List<LastFmTag>,
        fallbackTags: List<LastFmTag>,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
        titleOnly: String = text,
        bioLanguages: Set<String> = emptySet(),
    ): SpaceFilterDecision {
        // --- Stage 1: Language (highest priority) ---
        if (filter.languages.isNotEmpty()) {
            val languageDecision =
                evaluateLanguage(
                    text = text,
                    titleOnly = titleOnly,
                    primaryTags = primaryTags,
                    fallbackTags = fallbackTags,
                    bioLanguages = bioLanguages,
                    requestedLanguages = filter.languages,
                    source = source,
                )
            if (languageDecision != SpaceFilterDecision.ALLOW) {
                return languageDecision
            }
        }

        // --- Stage 2: Genre ---
        if (filter.genres.isNotEmpty()) {
            val genreDecision =
                evaluateGenre(
                    primaryTags = primaryTags,
                    fallbackTags = fallbackTags,
                    requestedGenres = filter.genres,
                    source = source,
                )
            if (genreDecision != SpaceFilterDecision.ALLOW) {
                return genreDecision
            }
        }

        return SpaceFilterDecision.ALLOW
    }

    private fun quickTextLanguageCheck(
        text: String,
        requestedLanguages: List<String>,
    ): SpaceFilterDecision? {
        val requestedIds = requestedLanguages.mapTo(mutableSetOf()) { resolveCanonicalLanguageId(it) }
        val scriptLangs = SpaceFilterTaxonomy.detectLanguagesFromScript(text)
        if (scriptLangs.isNotEmpty()) {
            val hasMatchingScript = scriptLangs.any { it in requestedIds }
            // Chinese Han characters can appear in Japanese titles without Kana; allow Last.fm tags to confirm if Japanese is selected.
            if (!hasMatchingScript && !(scriptLangs == setOf("chinese") && "japanese" in requestedIds)) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }
        val latinHints = SpaceFilterTaxonomy.detectLatinLanguageHints(text)
        if (latinHints.isNotEmpty() && latinHints.none { it in requestedIds }) {
            return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
        }
        return null
    }

    private fun evaluateLanguage(
        text: String,
        titleOnly: String,
        primaryTags: List<LastFmTag>,
        fallbackTags: List<LastFmTag>,
        bioLanguages: Set<String>,
        requestedLanguages: List<String>,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val requestedIds = requestedLanguages.mapTo(mutableSetOf()) { resolveCanonicalLanguageId(it) }
        val scores = mutableMapOf<String, Int>()

        fun addScore(
            langId: String,
            weight: Int,
        ) {
            scores[langId] = (scores[langId] ?: 0) + weight
        }

        val scriptLangs = SpaceFilterTaxonomy.detectLanguagesFromScript(text)
        for (lang in scriptLangs) {
            addScore(lang, 1000)
        }

        val latinHints = SpaceFilterTaxonomy.detectLatinLanguageHints(text)
        for (lang in latinHints) {
            addScore(lang, 450)
        }

        for (lang in bioLanguages) {
            addScore(lang, 320)
        }

        fun scoreTags(
            tags: List<LastFmTag>,
            multiplier: Int,
            floor: Int,
        ) {
            for (tag in tags) {
                val detected = SpaceFilterTaxonomy.detectLanguageFromTag(tag.name)
                if (detected != null) {
                    // Only count "instrumental" as a language conflict if it's a very strong tag or explicitly requested.
                    if (detected == "instrumental" && "instrumental" !in requestedIds && tag.count < 60) {
                        continue
                    }
                    addScore(detected, maxOf(floor, tag.count * multiplier))
                } else {
                    // Support custom user-typed languages directly against Last.fm tags.
                    val normTag = SpaceFilterTaxonomy.normalizeTag(tag.name)
                    for (req in requestedIds) {
                        if (SpaceFilterTaxonomy.languageOption(req) == null && normTag == req) {
                            addScore(req, maxOf(floor, tag.count * multiplier))
                        }
                    }
                }
            }
        }

        scoreTags(primaryTags, multiplier = 3, floor = 40)
        scoreTags(fallbackTags, multiplier = 3, floor = 35)

        // If Japanese is requested and title had Han-only script (detected as "chinese") while Last.fm confirms Japanese,
        // neutralize the ambiguous Han-only "chinese" script penalty.
        if ("japanese" in requestedIds && scriptLangs == setOf("chinese") && (scores["japanese"] ?: 0) > 0) {
            scores.remove("chinese")
        }

        // Reject any conflicting non-Latin script immediately.
        val conflictingScript =
            scriptLangs.any {
                it !in requestedIds && (it != "chinese" || "japanese" !in requestedIds || (scores["japanese"] ?: 0) == 0)
            }
        if (conflictingScript) {
            return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
        }

        // Aggressive diacritic check when ONLY English (and/or Instrumental) is requested:
        // Titles with non-English Latin accents (é, á, í, ó, ú, ç, ü, ä, ö, ñ, ã, õ, etc.) are rejected unless
        // the artist/track explicitly confirms English AND has no foreign signals.
        val hasForeignDiacritics =
            "english" in requestedIds &&
                requestedIds.all { it == "english" || it == "instrumental" } &&
                SpaceFilterTaxonomy.hasNonEnglishLatinDiacritics(titleOnly)
        if (hasForeignDiacritics) {
            addScore("_foreign_diacritics", 380)
        }

        val bestRequested = requestedIds.maxOfOrNull { scores[it] ?: 0 } ?: 0
        val bestOther = scores.entries.filter { it.key !in requestedIds }.maxOfOrNull { it.value } ?: 0

        // Aggressive conflict check:
        // 1. Any non-requested language signal with no requested confirmation -> REJECT.
        // 2. Non-requested signal >= requested signal -> REJECT.
        // 3. Strong non-requested signal (>= 250, e.g. artist bio/origin, Latin marker, or strong tag)
        //    unless requested confirmation overwhelmingly dominates (>= 2x) -> REJECT.
        if (bestOther > 0 &&
            (bestRequested == 0 || bestOther >= bestRequested || (bestOther >= 250 && bestRequested < bestOther * 2))
        ) {
            return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
        }

        // Positive confirmation!
        if (bestRequested > 0) {
            return SpaceFilterDecision.ALLOW
        }

        // Strict English fallback when Last.fm has no explicit Anglophone tag or bio:
        // NEVER blindly allow any Latin text with genre tags (that leaks Romanized Hindi/Punjabi/Malayalam/Spanish/French!).
        // Require pure ASCII/Latin text with zero foreign markers AND genuine English title word verification.
        val allTags = primaryTags + fallbackTags
        val hasRecognizedMusicTags =
            allTags.any { tag ->
                !SpaceFilterTaxonomy.isNoiseTag(tag.name) &&
                    SpaceFilterTaxonomy.detectGenresFromTag(tag.name).isNotEmpty()
            }
        if ("english" in requestedIds &&
            bestOther == 0 &&
            scriptLangs.isEmpty() &&
            latinHints.isEmpty() &&
            !hasForeignDiacritics &&
            SpaceFilterTaxonomy.isPureLatinText(text)
        ) {
            val cleanTitle = LastFmSpaceTagProvider.cleanMediaTitle(titleOnly)
            val titleWordCount = countWords(cleanTitle)
            val englishTitleWords = SpaceFilterTaxonomy.countEnglishWords(cleanTitle)
            val strongEnglishTitle =
                englishTitleWords >= 2 ||
                    (englishTitleWords >= 1 && titleWordCount in 1..2 && englishTitleWords == titleWordCount)
            if (strongEnglishTitle && (hasRecognizedMusicTags || source == SpaceCandidateSource.TARGETED_SEARCH)) {
                return SpaceFilterDecision.ALLOW
            }
        }

        // For non-English languages on targeted search, still allow untagged search hits only when there is zero conflict
        // and the requested language isn't English (English uses the strict title/bio verification above).
        if (source == SpaceCandidateSource.TARGETED_SEARCH && "english" !in requestedIds) {
            return SpaceFilterDecision.ALLOW
        }

        return SpaceFilterDecision.REJECT_LANGUAGE_UNVERIFIED
    }

    private fun evaluateGenre(
        primaryTags: List<LastFmTag>,
        fallbackTags: List<LastFmTag>,
        requestedGenres: List<String>,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val primaryUseful = primaryTags.filterNot { SpaceFilterTaxonomy.isNoiseTag(it.name) }
        val fallbackUseful = fallbackTags.filterNot { SpaceFilterTaxonomy.isNoiseTag(it.name) }
        val primaryNames = primaryUseful.map { it.name }
        val fallbackNames = fallbackUseful.map { it.name }
        val allNames = primaryNames + fallbackNames

        // 1. Direct match on track/album tags.
        if (primaryNames.isNotEmpty() && requestedGenres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(primaryNames, it) }) {
            return SpaceFilterDecision.ALLOW
        }

        // 2. Artist tag match: allow unless the track itself carries a strong dominant unrelated genre (count >= 55)
        //    with zero matching genre tags (prevents e.g. a pure pop/afrobeats track by a hip-hop crossover artist
        //    while allowing sparse/weakly-tagged hip-hop tracks from the user's favorite hip-hop artists!).
        val maxPrimaryUnrelatedCount =
            primaryUseful
                .filter { tag ->
                    val detected = SpaceFilterTaxonomy.detectGenresFromTag(tag.name)
                    detected.isNotEmpty() && requestedGenres.none { SpaceFilterTaxonomy.tagsMatchRequestedGenre(listOf(tag.name), it) }
                }.maxOfOrNull { it.count } ?: 0
        val trackHasStrongContradictingGenre = maxPrimaryUnrelatedCount >= 55

        if (!trackHasStrongContradictingGenre &&
            fallbackNames.isNotEmpty() &&
            requestedGenres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(fallbackNames, it) }
        ) {
            return SpaceFilterDecision.ALLOW
        }

        // 3. Combined tags for compound/hierarchical matches (e.g. track="soundtrack", artist="video game music").
        if (allNames.isNotEmpty() &&
            !trackHasStrongContradictingGenre &&
            requestedGenres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(allNames, it) }
        ) {
            return SpaceFilterDecision.ALLOW
        }

        // Special case: if track is tagged generic "soundtrack"/"ost" and artist is tagged "video game music"
        // (or vice versa), allow combining primary + fallback tags for Soundtrack subgenres.
        val primaryIsGenericSoundtrack =
            primaryNames.any {
                val n = SpaceFilterTaxonomy.normalizeTag(it)
                n == "soundtrack" || n == "ost" || n == "score"
            }
        if (primaryIsGenericSoundtrack &&
            fallbackNames.isNotEmpty() &&
            requestedGenres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(allNames, it) }
        ) {
            return SpaceFilterDecision.ALLOW
        }

        val anyRecognizedGenre = allNames.any { SpaceFilterTaxonomy.detectGenresFromTag(it).isNotEmpty() }
        if (anyRecognizedGenre) {
            return SpaceFilterDecision.REJECT_GENRE_CONFLICT
        }

        // Untagged on genre: strict on Home feed, allow untagged from targeted search fallback.
        return if (source == SpaceCandidateSource.TARGETED_SEARCH) {
            SpaceFilterDecision.ALLOW
        } else {
            SpaceFilterDecision.REJECT_GENRE_UNVERIFIED
        }
    }

    private fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            if (ch.isLetter() || ch == '\'') {
                if (!inWord) {
                    count++
                    inWord = true
                }
            } else {
                inWord = false
            }
        }
        return count
    }

    private fun resolveCanonicalLanguageId(raw: String): String {
        SpaceFilterTaxonomy.languageOption(raw)?.let { return it.id }
        SpaceFilterTaxonomy.detectLanguageFromTag(raw)?.let { return it }
        return SpaceFilterTaxonomy.normalizeTag(raw)
    }

    private fun extractTextLanguageTags(text: String): List<String> {
        val lower = text.lowercase()
        val found = mutableListOf<String>()
        for (option in SpaceFilterTaxonomy.languages) {
            val matched =
                option.searchKeywords.any { kw ->
                    kw.length >= 3 && containsWholePhrase(lower, kw.lowercase())
                } ||
                    option.lastFmTags.any { tag ->
                        tag.length >= 3 && containsWholePhrase(lower, tag.lowercase())
                    }
            if (matched) {
                found += option.id
            }
        }
        return found.distinct()
    }

    private fun extractSoundtrackTitleTags(text: String): List<String> {
        val lower = text.lowercase()
        val found = mutableListOf<String>()
        if (lower.contains("original motion picture soundtrack") ||
            lower.contains("original film score") ||
            lower.contains("movie soundtrack") ||
            lower.contains("film soundtrack")
        ) {
            found += "movie soundtrack"
            found += "soundtrack"
        }
        if (lower.contains("original game soundtrack") ||
            lower.contains("video game soundtrack") ||
            lower.contains("game ost") ||
            lower.contains("video game music")
        ) {
            found += "video game soundtrack"
            found += "soundtrack"
        }
        if (lower.contains("original soundtrack") ||
            containsWholePhrase(lower, "ost") ||
            containsWholePhrase(lower, "soundtrack")
        ) {
            found += "soundtrack"
        }
        return found.distinct()
    }

    private fun extractPlaylistGenreTags(text: String): List<String> {
        val lower = text.lowercase()
        val found = mutableListOf<String>()
        found += extractSoundtrackTitleTags(text)
        for (option in SpaceFilterTaxonomy.genres) {
            val matched =
                option.searchKeywords.any { kw ->
                    kw.length >= 3 && containsWholePhrase(lower, kw.lowercase())
                }
            if (matched) {
                found += option.searchQueryTerm
            }
        }
        return found.distinct()
    }

    private fun containsWholePhrase(
        haystack: String,
        needle: String,
    ): Boolean {
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val beforeOk = index == 0 || !haystack[index - 1].isLetterOrDigit()
            val afterIndex = index + needle.length
            val afterOk = afterIndex >= haystack.length || !haystack[afterIndex].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }

    private fun <K, V> MutableMap<K, V>.putIfMissing(
        key: K,
        value: V,
    ) {
        if (!containsKey(key)) put(key, value)
    }
}
