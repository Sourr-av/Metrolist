/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.languagesplit

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem

/** Classification result for a single Home item. UNKNOWN keeps the item on Home. */
enum class LanguageClass { ENGLISH, OTHER, UNKNOWN }

/** One optional on-device ML Kit language identification guess. */
data class LanguageGuess(
    val language: String,
    val confidence: Float,
)

/** Platform-provided romanized-language signal. ML Kit on Android, no-op elsewhere. */
interface LanguageSignal {
    suspend fun identify(text: String): LanguageGuess?
}

object NoOpLanguageSignal : LanguageSignal {
    override suspend fun identify(text: String): LanguageGuess? = null
}

/** A per-artist decision worth caching (source USER is only written by the override menu). */
data class ArtistLanguageLearn(
    val artistId: String,
    val language: LanguageClass,
    val source: String,
)

data class LanguageClassification(
    val language: LanguageClass,
    val learned: ArtistLanguageLearn? = null,
)

/**
 * Tiered classifier, fast to slow:
 * 1. USER override in the artist cache (beats every other source, including script).
 * 2. Unicode script detection on title + artist names.
 * 3. Cached language of the primary artist.
 * 4. Latin-script heuristics: romanized word lists, with ML Kit as one corroborating signal only.
 *
 * Low confidence never guesses: an inconclusive item is UNKNOWN and stays on Home.
 */
class LanguageClassifier(
    private val signal: LanguageSignal = NoOpLanguageSignal,
    private val cache: ArtistLanguageCache = ArtistLanguageCache.shared,
) {
    suspend fun classify(item: YTItem): LanguageClassification {
        val primaryArtistId: String?
        val text: String
        when (item) {
            is SongItem -> {
                primaryArtistId = item.artists.firstOrNull { !it.id.isNullOrBlank() }?.id
                text = songClassificationText(item)
            }
            is AlbumItem -> {
                primaryArtistId = item.artists.firstOrNull { !it.id.isNullOrBlank() }?.id
                text = (listOf(item.title) + item.artists.map { it.name }).joinToString(" ")
            }
            is PlaylistItem -> {
                primaryArtistId = item.authorId?.takeIf { it.isNotBlank() }
                text = (listOf(item.title) + listOfNotNull(item.author)).joinToString(" ")
            }
            is ArtistItem -> {
                primaryArtistId = item.id.takeIf { it.isNotBlank() }
                text = item.title
            }
            else -> {
                // Podcasts, episodes and browse rows are never classified.
                return LanguageClassification(LanguageClass.UNKNOWN)
            }
        }

        val cached = primaryArtistId?.let { cache.get(it) }
        if (cached?.source == "USER") {
            return LanguageClassification(cached.language)
        }

        if (hasTargetScript(text)) {
            val learn =
                if (primaryArtistId != null && cached == null) {
                    ArtistLanguageLearn(primaryArtistId, LanguageClass.OTHER, SOURCE_SCRIPT)
                } else {
                    null
                }
            learn?.let { cache.put(it.artistId, it.language, it.source) }
            return LanguageClassification(LanguageClass.OTHER, learn)
        }

        if (cached != null) {
            return LanguageClassification(cached.language)
        }

        val language = classifyLatinText(text)
        val learn =
            if (language != LanguageClass.UNKNOWN && primaryArtistId != null) {
                ArtistLanguageLearn(primaryArtistId, language, SOURCE_HEURISTIC)
            } else {
                null
            }
        learn?.let { cache.put(it.artistId, it.language, it.source) }
        return LanguageClassification(language, learn)
    }

    private fun songClassificationText(item: SongItem): String =
        (
            listOf(item.customTitle ?: item.title) +
                item.artists.map { it.name } +
                listOfNotNull(item.customArtist)
        ).joinToString(" ")

    private suspend fun classifyLatinText(text: String): LanguageClass {
        val hits = romanizedWords(text).count { it in ROMANIZED_WORDS }
        return when {
            // Two or more distinct romanized-word hits is deterministic enough to move.
            hits >= 2 -> LanguageClass.OTHER
            // A single hit needs corroboration from the ML Kit signal; otherwise stay put.
            hits == 1 -> {
                val guess = signal.identify(text)
                if (guess != null && guess.language in ML_TARGET_LANGUAGES && guess.confidence >= ML_CORROBORATION_CONFIDENCE) {
                    LanguageClass.OTHER
                } else {
                    LanguageClass.UNKNOWN
                }
            }
            // ML Kit alone never moves an item; it only lets us cache confident English.
            else -> {
                val guess = signal.identify(text)
                if (guess != null && guess.language == "en" && guess.confidence >= ML_ENGLISH_CONFIDENCE) {
                    LanguageClass.ENGLISH
                } else {
                    LanguageClass.UNKNOWN
                }
            }
        }
    }

    private fun romanizedWords(text: String): Set<String> =
        text
            .lowercase()
            .split(NON_LETTER_REGEX)
            .filter { it.length >= MIN_WORD_LENGTH }
            .toSet()

    private fun hasTargetScript(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val high = text[index]
            val codePoint =
                if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    index++
                    ((high.code - 0xD800) shl 10) + (text[index].code - 0xDC00) + 0x10000
                } else {
                    high.code
                }
            if (isTargetScriptCodePoint(codePoint)) return true
            index++
        }
        return false
    }

    private fun isTargetScriptCodePoint(code: Int): Boolean =
        code in DEVANAGARI ||
            code in BENGALI ||
            code in GURMUKHI ||
            code in TAMIL ||
            code in TELUGU ||
            code in KANNADA ||
            code in MALAYALAM ||
            ARABIC.any { code in it } ||
            HANGUL.any { code in it } ||
            KANA.any { code in it } ||
            CJK.any { code in it }

    private companion object {
        const val SOURCE_SCRIPT = "SCRIPT"
        const val SOURCE_HEURISTIC = "HEURISTIC"
        const val MIN_WORD_LENGTH = 3
        const val ML_CORROBORATION_CONFIDENCE = 0.5f
        const val ML_ENGLISH_CONFIDENCE = 0.7f

        val NON_LETTER_REGEX = Regex("[^a-z]+")

        val ML_TARGET_LANGUAGES =
            setOf("hi", "pa", "ml", "ta", "te", "kn", "mr", "gu", "bn", "ur", "ne", "or", "si")

        val DEVANAGARI = 0x0900..0x097F
        val BENGALI = 0x0980..0x09FF
        val GURMUKHI = 0x0A00..0x0A7F
        val TAMIL = 0x0B80..0x0BFF
        val TELUGU = 0x0C00..0x0C7F
        val KANNADA = 0x0C80..0x0CFF
        val MALAYALAM = 0x0D00..0x0D7F
        val ARABIC =
            listOf(
                0x0600..0x06FF,
                0x0750..0x077F,
                0x08A0..0x08FF,
                0xFB50..0xFDFF,
                0xFE70..0xFEFF,
            )
        val HANGUL =
            listOf(
                0x1100..0x11FF,
                0x3130..0x318F,
                0xA960..0xA97F,
                0xAC00..0xD7A3,
                0xD7B0..0xD7FF,
            )
        val KANA =
            listOf(
                0x3040..0x309F,
                0x30A0..0x30FF,
                0x31F0..0x31FF,
            )
        val CJK =
            listOf(
                0x3400..0x4DBF,
                0x4E00..0x9FFF,
                0xF900..0xFAFF,
            )

        /**
         * Very common romanized words for Hindi, Punjabi, Malayalam, Tamil and Telugu.
         * A song needs at least two distinct hits (or one hit plus ML Kit corroboration)
         * before it is considered non-English — single-word collisions stay UNKNOWN.
         */
        val ROMANIZED_WORDS: Set<String> =
            setOf(
                // Hindi
                "mera", "meri", "tera", "teri", "tum", "tumhara", "tumhari", "aap", "kya",
                "hai", "nahi", "nahin", "pyar", "mohabbat", "dil", "jaan", "zindagi", "raat",
                "baar", "chale", "bula", "tujhe", "mujhe", "tumhe", "unka", "humara", "kaise",
                "kyunki", "haan", "suno", "bolo", "chahat", "ishq", "yaad", "phir", "bahut",
                "accha", "theek", "baat", "yaar", "dost", "ghar", "bhai", "behan", "ladka",
                "ladki", "sapna", "khwab", "sansar", "manzil", "muskaan", "udaas", "khushi",
                "aaj", "woh", "tumse", "mujhko", "tujhko", "banke", "bana", "banao", "chahin",
                // Punjabi
                "veere", "veera", "munda", "kudi", "pind", "gallan", "gall", "sohna", "sohniya",
                "nakhra", "kurta", "haal", "balle", "channa", "tenu", "mainu", "dass", "chalda",
                "reha", "rehti", "bhabhi", "jindadi", "mitti", "khet", "charha",
                // Malayalam
                "njan", "ente", "ninte", "daivam", "sneham", "penn", "mone", "chetta", "amma",
                "achan", "nalla", "varum", "varunnu", "illa", "undu", "ivide", "angane",
                "ingane", "swapna", "manasu", "hrudayam", "prasnam", "vazhi", "veedu",
                "kudumbam", "priyan", "snehi", "cheyyum", "snehikunnu", "njangal", "checcha",
                "pustakam", "paatu", "kadha", "snehathin", "udbhavam", "surya", "chandran",
                // Tamil
                "enna", "naan", "romba", "kadal", "anbu", "thalai", "vaazhga", "uyir", "machi",
                "nanban", "thanga", "sollu", "pannu", "irukku", "naanga", "ungal", "enakku",
                "kanna", "uyire", "ponna", "ney", "vazhga", "thalaiva", "sakoda", "nanbi",
                // Telugu
                "nuvvu", "nenu", "chala", "istam", "prema", "abbayi", "ammayi", "bagundi",
                "avunu", "ledu", "chinni", "roju", "gurinchi", "enti", "inka", "ante", "randi",
                "gundelu", "hrudayam", "nannu", "ninu", "chustanu", "vundhi",
            )
    }
}
