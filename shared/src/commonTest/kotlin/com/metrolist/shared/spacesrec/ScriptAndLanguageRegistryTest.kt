/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.spaces.SpaceFilterTaxonomy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptAndLanguageRegistryTest {
    @Test
    fun registryCoversAll24TaxonomySlugs() {
        val taxonomyIds = SpaceFilterTaxonomy.languages.map { it.id }
        val registryIds = SpaceLanguageRegistry.taxonomyEntries.map { it.slug }

        assertEquals(24, taxonomyIds.size)
        assertEquals(taxonomyIds, registryIds)

        for (slug in taxonomyIds) {
            val resolved = SpaceLanguageRegistry.resolve(slug)
            assertTrue(resolved != null, "Missing SpaceLanguageRegistry entry for slug '$slug'")
            if (slug == SpaceLanguageRegistry.INSTRUMENTAL_SLUG) {
                assertTrue(resolved.isInstrumental)
                assertNull(resolved.isoCode)
                assertTrue(resolved.scripts.isEmpty())
                assertFalse(resolved.commonlyRomanized)
            } else {
                assertFalse(resolved.isInstrumental)
                assertTrue(!resolved.isoCode.isNullOrBlank(), "Expected ISO code for '$slug'")
                assertTrue(resolved.scripts.isNotEmpty(), "Expected script(s) for '$slug'")
                // Reverse ISO lookup resolves back to same entry.
                assertEquals(resolved, SpaceLanguageRegistry.resolve(resolved.isoCode))
            }
        }
    }

    @Test
    fun commonlyRomanizedFlagMatchesExpectedLanguages() {
        val romanizedSlugs =
            setOf(
                "hindi",
                "japanese",
                "korean",
                "malayalam",
                "tamil",
                "telugu",
                "punjabi",
                "russian",
                "arabic",
                "chinese",
            )
        for (entry in SpaceLanguageRegistry.taxonomyEntries) {
            assertEquals(
                entry.slug in romanizedSlugs,
                entry.commonlyRomanized,
                "Unexpected commonlyRomanized flag for '${entry.slug}'",
            )
        }
        // Extended ISO romanized languages from spec.
        for (code in listOf("kn", "bn", "gu", "mr", "ur", "el")) {
            val resolved = SpaceLanguageRegistry.resolve(code)
            assertTrue(resolved != null && resolved.commonlyRomanized, "Expected '$code' to be commonlyRomanized")
        }
    }

    @Test
    fun instrumentalAndCustomFreeTextSlugsParseCorrectly() {
        val instrumentalOnly = SpaceLanguageRegistry.parseTargets(listOf("instrumental"))
        assertTrue(instrumentalOnly.hasInstrumental)
        assertTrue(instrumentalOnly.isEmpty)
        assertNull(SpaceLanguageRegistry.canonicalCode("instrumental"))

        val custom = SpaceLanguageRegistry.parseTargets(listOf("bhojpuri"))
        assertTrue(custom.hasCustomSlugs)
        assertEquals(setOf("bhojpuri"), custom.customSlugs)
        assertEquals(setOf("bhojpuri"), custom.canonicalCodes)
        assertTrue(custom.acceptedScripts.isEmpty())
        assertTrue(custom.matchesCode("Bhojpuri"))
    }

    @Test
    fun scriptDetectorIdentifiesAllSupportedScripts() {
        assertEquals(WritingScript.LATIN, ScriptDetector.detect("Blinding Lights The Weeknd").dominantScript)
        assertEquals(WritingScript.MALAYALAM, ScriptDetector.detect("മലയാളം പാട്ട്").dominantScript)
        assertEquals(WritingScript.DEVANAGARI, ScriptDetector.detect("तुम ही हो Arijit Singh").dominantScript)
        assertEquals(WritingScript.TAMIL, ScriptDetector.detect("காதல் பாடல்").dominantScript)
        assertEquals(WritingScript.TELUGU, ScriptDetector.detect("తెలుగు పాట").dominantScript)
        assertEquals(WritingScript.GURMUKHI, ScriptDetector.detect("ਪੰਜਾਬੀ ਗੀਤ").dominantScript)
        assertEquals(WritingScript.KANNADA, ScriptDetector.detect("ಕನ್ನಡ ಹಾಡು").dominantScript)
        assertEquals(WritingScript.BENGALI, ScriptDetector.detect("বাংলা গান").dominantScript)
        assertEquals(WritingScript.GUJARATI, ScriptDetector.detect("ગુજરાતી ગીત").dominantScript)
        assertEquals(WritingScript.HANGUL, ScriptDetector.detect("봄날 BTS").dominantScript)
        assertEquals(WritingScript.KANA, ScriptDetector.detect("夜に駆ける YOASOBI").dominantScript)
        assertEquals(WritingScript.HAN, ScriptDetector.detect("晴天 周杰伦").dominantScript)
        assertEquals(WritingScript.ARABIC, ScriptDetector.detect("أغنية عربية").dominantScript)
        assertEquals(WritingScript.CYRILLIC, ScriptDetector.detect("Группа крови Кино").dominantScript)
        assertEquals(WritingScript.THAI, ScriptDetector.detect("เพลงไทย").dominantScript)
        assertEquals(WritingScript.GREEK, ScriptDetector.detect("Ελληνικό τραγούδι").dominantScript)
        assertNull(ScriptDetector.detect("12345 !!!").dominantScript)
    }
}
