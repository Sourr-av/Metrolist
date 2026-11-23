/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

enum class WritingScript(
    val isAmbiguous: Boolean,
) {
    LATIN(isAmbiguous = false),
    MALAYALAM(isAmbiguous = false),
    DEVANAGARI(isAmbiguous = true),
    TAMIL(isAmbiguous = false),
    TELUGU(isAmbiguous = false),
    GURMUKHI(isAmbiguous = false),
    KANNADA(isAmbiguous = false),
    BENGALI(isAmbiguous = false),
    GUJARATI(isAmbiguous = false),
    HANGUL(isAmbiguous = false),
    KANA(isAmbiguous = false),
    HAN(isAmbiguous = true),
    ARABIC(isAmbiguous = true),
    CYRILLIC(isAmbiguous = true),
    THAI(isAmbiguous = false),
    GREEK(isAmbiguous = false),
}

data class SpaceLanguageEntry(
    val slug: String,
    val isoCode: String?,
    val scripts: Set<WritingScript>,
    val commonlyRomanized: Boolean,
    val isInstrumental: Boolean = false,
)

/**
 * Complete bidirectional mapping for every language slug defined in SpaceFilterTaxonomy.kt (lines 33-286),
 * plus ISO 639-1 lookup for the spec's romanized-language set (ml, hi, ta, te, kn, bn, pa, gu, mr, ur, ar, ko, ja, zh, ru, el).
 *
 * Never falls back to any default language.
 */
object SpaceLanguageRegistry {
    const val INSTRUMENTAL_SLUG = "instrumental"

    /** Every slug in SpaceFilterTaxonomy.languages in exact definition order. */
    val taxonomyEntries: List<SpaceLanguageEntry> =
        listOf(
            SpaceLanguageEntry("english", "en", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("spanish", "es", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("hindi", "hi", setOf(WritingScript.DEVANAGARI), commonlyRomanized = true),
            SpaceLanguageEntry("japanese", "ja", setOf(WritingScript.KANA, WritingScript.HAN), commonlyRomanized = true),
            SpaceLanguageEntry("korean", "ko", setOf(WritingScript.HANGUL), commonlyRomanized = true),
            SpaceLanguageEntry("malayalam", "ml", setOf(WritingScript.MALAYALAM), commonlyRomanized = true),
            SpaceLanguageEntry("tamil", "ta", setOf(WritingScript.TAMIL), commonlyRomanized = true),
            SpaceLanguageEntry("telugu", "te", setOf(WritingScript.TELUGU), commonlyRomanized = true),
            SpaceLanguageEntry("punjabi", "pa", setOf(WritingScript.GURMUKHI), commonlyRomanized = true),
            SpaceLanguageEntry("french", "fr", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("german", "de", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("portuguese", "pt", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("italian", "it", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("russian", "ru", setOf(WritingScript.CYRILLIC), commonlyRomanized = true),
            SpaceLanguageEntry("arabic", "ar", setOf(WritingScript.ARABIC), commonlyRomanized = true),
            SpaceLanguageEntry("chinese", "zh", setOf(WritingScript.HAN), commonlyRomanized = true),
            SpaceLanguageEntry("turkish", "tr", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("indonesian", "id", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("thai", "th", setOf(WritingScript.THAI), commonlyRomanized = false),
            SpaceLanguageEntry("vietnamese", "vi", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("polish", "pl", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("swedish", "sv", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry("dutch", "nl", setOf(WritingScript.LATIN), commonlyRomanized = false),
            SpaceLanguageEntry(
                slug = INSTRUMENTAL_SLUG,
                isoCode = null,
                scripts = emptySet(),
                commonlyRomanized = false,
                isInstrumental = true,
            ),
        )

    /** Additional ISO-backed languages referenced by the spec's romanized set and script detector. */
    private val extendedEntries: List<SpaceLanguageEntry> =
        listOf(
            SpaceLanguageEntry("kannada", "kn", setOf(WritingScript.KANNADA), commonlyRomanized = true),
            SpaceLanguageEntry("bengali", "bn", setOf(WritingScript.BENGALI), commonlyRomanized = true),
            SpaceLanguageEntry("gujarati", "gu", setOf(WritingScript.GUJARATI), commonlyRomanized = true),
            SpaceLanguageEntry("marathi", "mr", setOf(WritingScript.DEVANAGARI), commonlyRomanized = true),
            SpaceLanguageEntry("urdu", "ur", setOf(WritingScript.ARABIC), commonlyRomanized = true),
            SpaceLanguageEntry("greek", "el", setOf(WritingScript.GREEK), commonlyRomanized = true),
        )

    private val allEntries: List<SpaceLanguageEntry> = taxonomyEntries + extendedEntries

    private val bySlug: Map<String, SpaceLanguageEntry> = allEntries.associateBy { it.slug }

    private val byIso: Map<String, SpaceLanguageEntry> =
        allEntries.mapNotNull { entry -> entry.isoCode?.let { it to entry } }.toMap()

    /** Unambiguous non-Latin scripts mapped to their single ISO 639-1 language code. */
    val unambiguousScriptToIso: Map<WritingScript, String> =
        mapOf(
            WritingScript.MALAYALAM to "ml",
            WritingScript.TAMIL to "ta",
            WritingScript.TELUGU to "te",
            WritingScript.GURMUKHI to "pa",
            WritingScript.KANNADA to "kn",
            WritingScript.BENGALI to "bn",
            WritingScript.GUJARATI to "gu",
            WritingScript.HANGUL to "ko",
            WritingScript.KANA to "ja",
            WritingScript.THAI to "th",
            WritingScript.GREEK to "el",
        )

    /** Resolves a Space config slug or ISO 639-1 code (case-insensitive). Returns null for custom free-text slugs. */
    fun resolve(slugOrCode: String): SpaceLanguageEntry? {
        val cleaned = slugOrCode.trim().lowercase()
        if (cleaned.isEmpty()) return null
        bySlug[cleaned]?.let { return it }
        val primarySubtag = cleaned.substringBefore('-').substringBefore('_')
        return byIso[primarySubtag]
    }

    /**
     * Canonical key used in track_class.language and artist_profile.languageVotes:
     * returns the ISO 639-1 code when known, or the normalized lowercase slug for custom free-text languages.
     * Returns null for blank input or "instrumental".
     */
    fun canonicalCode(slugOrCode: String): String? {
        val cleaned = slugOrCode.trim().lowercase()
        if (cleaned.isEmpty()) return null
        val entry = resolve(cleaned)
        if (entry != null) {
            return if (entry.isInstrumental) null else entry.isoCode
        }
        return cleaned
    }

    fun isInstrumental(slugOrCode: String): Boolean =
        resolve(slugOrCode)?.isInstrumental == true

    /**
     * Resolves a Space's configured language list into structured targets for the language gate.
     * Strips "instrumental" (which is handled through the genre gate, not the language gate).
     */
    fun parseTargets(spaceLanguages: List<String>): ResolvedLanguageTargets {
        val knownEntries = mutableListOf<SpaceLanguageEntry>()
        val customSlugs = mutableSetOf<String>()
        var hasInstrumental = false

        for (raw in spaceLanguages) {
            val cleaned = raw.trim().lowercase()
            if (cleaned.isEmpty()) continue
            val entry = resolve(cleaned)
            when {
                entry == null -> customSlugs += cleaned
                entry.isInstrumental -> hasInstrumental = true
                else -> knownEntries += entry
            }
        }

        val canonicalCodes =
            buildSet {
                knownEntries.mapNotNullTo(this) { it.isoCode }
                addAll(customSlugs)
            }
        val acceptedScripts = knownEntries.flatMapTo(mutableSetOf()) { it.scripts }
        val hasCommonlyRomanized = knownEntries.any { it.commonlyRomanized }

        return ResolvedLanguageTargets(
            knownEntries = knownEntries.distinctBy { it.slug },
            customSlugs = customSlugs,
            canonicalCodes = canonicalCodes,
            acceptedScripts = acceptedScripts,
            hasCommonlyRomanized = hasCommonlyRomanized,
            hasInstrumental = hasInstrumental,
        )
    }
}

data class ResolvedLanguageTargets(
    val knownEntries: List<SpaceLanguageEntry>,
    val customSlugs: Set<String>,
    val canonicalCodes: Set<String>,
    val acceptedScripts: Set<WritingScript>,
    val hasCommonlyRomanized: Boolean,
    val hasInstrumental: Boolean,
) {
    val isEmpty: Boolean
        get() = canonicalCodes.isEmpty()

    val hasCustomSlugs: Boolean
        get() = customSlugs.isNotEmpty()

    fun matchesCode(codeOrSlug: String): Boolean {
        val canonical = SpaceLanguageRegistry.canonicalCode(codeOrSlug) ?: return false
        return canonical in canonicalCodes
    }

    fun acceptsScript(script: WritingScript): Boolean = script in acceptedScripts
}
