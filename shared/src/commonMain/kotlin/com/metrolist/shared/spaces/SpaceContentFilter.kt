/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spaces

import com.metrolist.shared.api.lastfm.LastFM
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
}

class LastFmSpaceTagProvider(
    private val httpClient: HttpClient,
    maxConcurrentRequests: Int = 6,
    private val maxCacheEntries: Int = 2000,
) : SpaceTagProvider {
    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<String, List<LastFmTag>>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<List<LastFmTag>?>>()
    private val semaphore = Semaphore(maxConcurrentRequests)

    override val isConfigured: Boolean
        get() = LastFM.apiKeyConfigured()

    override suspend fun trackTags(
        artist: String,
        track: String,
    ): List<LastFmTag>? {
        val cleanArtist = cleanArtistName(artist)
        val cleanTrack = cleanMediaTitle(track)
        if (cleanArtist.isBlank() || cleanTrack.isBlank()) return emptyList()
        return cachedLookup("track:${cleanArtist.lowercase()}|${cleanTrack.lowercase()}") {
            LastFM.getTrackTopTags(httpClient, cleanArtist, cleanTrack)
        }
    }

    override suspend fun artistTags(artist: String): List<LastFmTag>? {
        val cleanArtist = cleanArtistName(artist)
        if (cleanArtist.isBlank()) return emptyList()
        return cachedLookup("artist:${cleanArtist.lowercase()}") {
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
        return cachedLookup("album:${cleanArtist.lowercase()}|${cleanAlbum.lowercase()}") {
            LastFM.getAlbumTopTags(httpClient, cleanArtist, cleanAlbum)
        }
    }

    private suspend fun cachedLookup(
        key: String,
        fetch: suspend () -> List<LastFmTag>?,
    ): List<LastFmTag>? {
        if (!isConfigured) return null

        val existingDeferred: CompletableDeferred<List<LastFmTag>?>?
        val createdDeferred: CompletableDeferred<List<LastFmTag>?>?

        cacheMutex.withLock {
            cache[key]?.let { return it }
            val active = inFlight[key]
            if (active != null) {
                existingDeferred = active
                createdDeferred = null
            } else {
                val created = CompletableDeferred<List<LastFmTag>?>()
                inFlight[key] = created
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
                inFlight.remove(key)
                if (result != null) {
                    if (cache.size >= maxCacheEntries) {
                        val oldestKey = cache.keys.firstOrNull()
                        if (oldestKey != null) cache.remove(oldestKey)
                    }
                    cache[key] = result
                }
            }
            deferred.complete(result)
            result
        } catch (e: Throwable) {
            cacheMutex.withLock {
                inFlight.remove(key)
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
        maxCandidates: Int = 90,
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
        val artistName = song.artists.firstOrNull()?.name.orEmpty()
        val combinedText =
            buildString {
                append(song.title)
                if (song.artists.isNotEmpty()) {
                    append(' ')
                    append(song.artists.joinToString(" ") { it.name })
                }
                song.album?.name?.takeIf { it.isNotBlank() }?.let {
                    append(' ')
                    append(it)
                }
            }

        // Fast-path: reject obvious non-matching scripts before making any Last.fm HTTP requests.
        if (filter.languages.isNotEmpty()) {
            val fastScriptDecision = quickScriptCheck(combinedText, filter.languages)
            if (fastScriptDecision == SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }

        val trackTags =
            if (artistName.isNotBlank()) {
                tagProvider.trackTags(artistName, song.title).orEmpty()
            } else {
                emptyList()
            }
        val artistTags =
            if (artistName.isNotBlank() && shouldFetchArtistFallback(trackTags, filter)) {
                tagProvider.artistTags(artistName).orEmpty()
            } else {
                emptyList()
            }

        val extraGenres = buildList {
            song.genre?.takeIf { it.isNotBlank() }?.let { add(LastFmTag(it, 60)) }
            extractSoundtrackTitleTags(combinedText).forEach { add(LastFmTag(it, 45)) }
        }

        return evaluateSignals(
            text = combinedText,
            primaryTags = trackTags + extraGenres,
            fallbackTags = artistTags,
            filter = filter,
            source = source,
        )
    }

    private suspend fun evaluateAlbum(
        album: AlbumItem,
        filter: SpaceFilterSpec,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val artistName = album.artists.firstOrNull()?.name.orEmpty()
        val combinedText =
            buildString {
                append(album.title)
                if (album.artists.isNotEmpty()) {
                    append(' ')
                    append(album.artists.joinToString(" ") { it.name })
                }
            }

        if (filter.languages.isNotEmpty()) {
            if (quickScriptCheck(combinedText, filter.languages) == SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }

        val albumTags =
            if (artistName.isNotBlank()) {
                tagProvider.albumTags(artistName, album.title).orEmpty()
            } else {
                emptyList()
            }
        val artistTags =
            if (artistName.isNotBlank() && shouldFetchArtistFallback(albumTags, filter)) {
                tagProvider.artistTags(artistName).orEmpty()
            } else {
                emptyList()
            }
        val extraGenres = extractSoundtrackTitleTags(combinedText).map { LastFmTag(it, 55) }

        return evaluateSignals(
            text = combinedText,
            primaryTags = albumTags + extraGenres,
            fallbackTags = artistTags,
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
            if (quickScriptCheck(combinedText, filter.languages) == SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT) {
                return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
            }
        }
        val artistTags = tagProvider.artistTags(artist.title).orEmpty()
        return evaluateSignals(
            text = combinedText,
            primaryTags = artistTags,
            fallbackTags = emptyList(),
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

        return evaluateSignals(
            text = combinedText,
            primaryTags = extractedLanguageTags + extractedGenreTags,
            fallbackTags = emptyList(),
            filter = filter,
            source = source,
        )
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
    ): SpaceFilterDecision {
        // --- Stage 1: Language (highest priority) ---
        if (filter.languages.isNotEmpty()) {
            val languageDecision =
                evaluateLanguage(
                    text = text,
                    primaryTags = primaryTags,
                    fallbackTags = fallbackTags,
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

    private fun quickScriptCheck(
        text: String,
        requestedLanguages: List<String>,
    ): SpaceFilterDecision? {
        val scriptLangs = SpaceFilterTaxonomy.detectLanguagesFromScript(text)
        if (scriptLangs.isEmpty()) return null
        val requestedIds = requestedLanguages.mapTo(mutableSetOf()) { resolveCanonicalLanguageId(it) }
        val hasMatchingScript = scriptLangs.any { it in requestedIds }
        // Chinese Han characters can appear in Japanese titles without Kana; allow Last.fm tags to confirm if Japanese is selected.
        if (!hasMatchingScript && !(scriptLangs == setOf("chinese") && "japanese" in requestedIds)) {
            return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
        }
        return null
    }

    private fun evaluateLanguage(
        text: String,
        primaryTags: List<LastFmTag>,
        fallbackTags: List<LastFmTag>,
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
            addScore(lang, 350)
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

        scoreTags(primaryTags, multiplier = 3, floor = 35)
        scoreTags(fallbackTags, multiplier = 2, floor = 20)

        // If Japanese is requested and title had Han-only script (detected as "chinese") while Last.fm confirms Japanese,
        // neutralize the ambiguous Han-only "chinese" script penalty.
        if ("japanese" in requestedIds && scriptLangs == setOf("chinese") && (scores["japanese"] ?: 0) > 0) {
            scores.remove("chinese")
        }

        // Reject any conflicting non-Latin script immediately.
        val conflictingScript = scriptLangs.any { it !in requestedIds && (it != "chinese" || "japanese" !in requestedIds || (scores["japanese"] ?: 0) == 0) }
        if (conflictingScript) {
            return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
        }

        val bestRequested = requestedIds.maxOfOrNull { scores[it] ?: 0 } ?: 0
        val bestOther = scores.entries.filter { it.key !in requestedIds }.maxOfOrNull { it.value } ?: 0

        // Strict conflict check: any non-requested language signal that dominates or appears without requested language confirmation.
        if (bestOther > 0 && (bestRequested == 0 || bestOther >= bestRequested)) {
            return SpaceFilterDecision.REJECT_LANGUAGE_CONFLICT
        }

        // Positive confirmation!
        if (bestRequested > 0) {
            return SpaceFilterDecision.ALLOW
        }

        // English inference: Last.fm rarely adds a literal "english" tag to mainstream English songs,
        // while non-English tracks carry origin/language tags or non-Latin scripts.
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
            SpaceFilterTaxonomy.isPureLatinText(text)
        ) {
            if (hasRecognizedMusicTags || SpaceFilterTaxonomy.hasStrongEnglishWordSignal(text)) {
                return SpaceFilterDecision.ALLOW
            }
        }

        // No language signal at all: strict on Home feed, allow untagged from targeted search fallback.
        return if (source == SpaceCandidateSource.TARGETED_SEARCH) {
            SpaceFilterDecision.ALLOW
        } else {
            SpaceFilterDecision.REJECT_LANGUAGE_UNVERIFIED
        }
    }

    private fun evaluateGenre(
        primaryTags: List<LastFmTag>,
        fallbackTags: List<LastFmTag>,
        requestedGenres: List<String>,
        source: SpaceCandidateSource,
    ): SpaceFilterDecision {
        val primaryNames = primaryTags.map { it.name }.filterNot(SpaceFilterTaxonomy::isNoiseTag)
        val fallbackNames = fallbackTags.map { it.name }.filterNot(SpaceFilterTaxonomy::isNoiseTag)
        val allNames = primaryNames + fallbackNames

        // Check if any requested genre matches track/album tags first.
        if (primaryNames.isNotEmpty() && requestedGenres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(primaryNames, it) }) {
            return SpaceFilterDecision.ALLOW
        }

        // If track/album already has strong recognized genre tags that do NOT match, don't let generic artist tags override
        // unless the artist tags strongly match AND the track tags aren't an outright contradictory genre.
        val primaryHasRecognizedGenre = primaryNames.any { SpaceFilterTaxonomy.detectGenresFromTag(it).isNotEmpty() }

        if (!primaryHasRecognizedGenre &&
            fallbackNames.isNotEmpty() &&
            requestedGenres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(fallbackNames, it) }
        ) {
            return SpaceFilterDecision.ALLOW
        }

        // Also check combined tags for compound/hierarchical matches (e.g. track="soundtrack", artist="video game music").
        if (allNames.isNotEmpty() &&
            !primaryHasRecognizedGenre &&
            requestedGenres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(allNames, it) }
        ) {
            return SpaceFilterDecision.ALLOW
        }

        // Special case: if track is tagged generic "soundtrack"/"ost" and artist is tagged "video game music"
        // (or vice versa), allow combining primary + fallback tags for Soundtrack subgenres!
        val primaryIsGenericSoundtrack =
            primaryNames.any { SpaceFilterTaxonomy.normalizeTag(it) in setOf("soundtrack", "ost", "score", "original soundtrack") }
        if (primaryIsGenericSoundtrack &&
            requestedGenres.any { SpaceFilterTaxonomy.tagsMatchRequestedGenre(allNames, it) }
        ) {
            return SpaceFilterDecision.ALLOW
        }

        val anyRecognizedGenre = allNames.any { SpaceFilterTaxonomy.detectGenresFromTag(it).isNotEmpty() }
        if (anyRecognizedGenre) {
            // Last.fm gave us recognized genres and NONE of them matched our Space -> hard conflict!
            return SpaceFilterDecision.REJECT_GENRE_CONFLICT
        }

        // No genre tags on Last.fm at all: strict on Home feed, allow untagged from targeted search fallback.
        return if (source == SpaceCandidateSource.TARGETED_SEARCH) {
            SpaceFilterDecision.ALLOW
        } else {
            SpaceFilterDecision.REJECT_GENRE_UNVERIFIED
        }
    }

    private fun shouldFetchArtistFallback(
        primaryTags: List<LastFmTag>,
        filter: SpaceFilterSpec,
    ): Boolean {
        if (!tagProvider.isConfigured) return false
        if (primaryTags.isEmpty()) return true
        val usefulTags = primaryTags.filterNot { SpaceFilterTaxonomy.isNoiseTag(it.name) }
        if (usefulTags.isEmpty()) return true
        val needsLang =
            filter.languages.isNotEmpty() &&
                usefulTags.none { SpaceFilterTaxonomy.detectLanguageFromTag(it.name) != null }
        val needsGenre =
            filter.genres.isNotEmpty() &&
                usefulTags.none { SpaceFilterTaxonomy.detectGenresFromTag(it.name).isNotEmpty() }
        return needsLang || needsGenre
    }

    private fun resolveCanonicalLanguageId(input: String): String =
        SpaceFilterTaxonomy.languageOption(input)?.id ?: SpaceFilterTaxonomy.normalizeTag(input)

    private fun extractSoundtrackTitleTags(text: String): List<String> {
        val norm = SpaceFilterTaxonomy.normalizeTag(text)
        if (norm.isBlank()) return emptyList()
        val found = mutableListOf<String>()
        if (norm.contains("video game soundtrack") ||
            norm.contains("video game ost") ||
            norm.contains("game soundtrack") ||
            norm.contains("game ost") ||
            Regex("""\bvgm\b""").containsMatchIn(norm)
        ) {
            found += "video game soundtrack"
        }
        if (norm.contains("motion picture soundtrack") ||
            norm.contains("movie soundtrack") ||
            norm.contains("film soundtrack") ||
            norm.contains("film score") ||
            norm.contains("original motion picture")
        ) {
            found += "movie soundtrack"
        }
        if (norm.contains("soundtrack") || Regex("""\bost\b""").containsMatchIn(norm)) {
            found += "soundtrack"
        }
        return found.distinct()
    }

    private fun extractPlaylistGenreTags(text: String): List<String> {
        val norm = SpaceFilterTaxonomy.normalizeTag(text)
        if (norm.isBlank()) return emptyList()
        val found = extractSoundtrackTitleTags(text).toMutableList()
        for (genre in SpaceFilterTaxonomy.genres) {
            val label = SpaceFilterTaxonomy.normalizeTag(genre.displayName.substringBefore(" ("))
            if (label.length >= 3 && Regex("""\b${Regex.escape(label)}\b""").containsMatchIn(norm)) {
                found += label
            }
            for (keyword in genre.searchKeywords) {
                val kw = SpaceFilterTaxonomy.normalizeTag(keyword)
                if (kw.length >= 4 && Regex("""\b${Regex.escape(kw)}\b""").containsMatchIn(norm)) {
                    found += kw
                }
            }
        }
        return found.distinct()
    }

    private fun extractTextLanguageTags(text: String): List<String> {
        val norm = SpaceFilterTaxonomy.normalizeTag(text)
        if (norm.isBlank()) return emptyList()
        val found = mutableListOf<String>()
        for (lang in SpaceFilterTaxonomy.languages) {
            val label = SpaceFilterTaxonomy.normalizeTag(lang.displayName.substringBefore(" ("))
            if (label.length >= 4 && Regex("""\b${Regex.escape(label)}\b""").containsMatchIn(norm)) {
                found += lang.id
            }
        }
        return found.distinct()
    }

    private fun <K, V> MutableMap<K, V>.putIfMissing(
        key: K,
        value: V,
    ) {
        if (key !in this) put(key, value)
    }
}
