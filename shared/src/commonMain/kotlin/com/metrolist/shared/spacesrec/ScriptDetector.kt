/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

data class ScriptDetectionResult(
    val dominantScript: WritingScript?,
    val scriptCounts: Map<WritingScript, Int>,
    val totalLetters: Int,
) {
    val hasNonLatin: Boolean
        get() = scriptCounts.any { (script, count) -> script != WritingScript.LATIN && count > 0 }

    val dominantIsNonLatin: Boolean
        get() = dominantScript != null && dominantScript != WritingScript.LATIN
}

/**
 * Pure Kotlin Unicode-block script detector over letters of song title + artist names.
 */
object ScriptDetector {
    private const val NON_LATIN_DOMINANCE_MIN_PERCENT = 25
    private const val NON_LATIN_DOMINANCE_MIN_LETTERS = 3

    fun detect(text: String): ScriptDetectionResult {
        if (text.isBlank()) {
            return ScriptDetectionResult(dominantScript = null, scriptCounts = emptyMap(), totalLetters = 0)
        }

        val counts = mutableMapOf<WritingScript, Int>()
        var totalLetters = 0

        for (ch in text) {
            val script = classifyChar(ch) ?: continue
            counts[script] = (counts[script] ?: 0) + 1
            totalLetters++
        }

        if (totalLetters == 0) {
            return ScriptDetectionResult(dominantScript = null, scriptCounts = emptyMap(), totalLetters = 0)
        }

        // Japanese titles routinely mix Kana and Han (Kanji); Kana uniquely identifies Japanese,
        // so merge Han counts into Kana whenever both appear.
        val effectiveCounts = counts.toMutableMap()
        val kanaCount = effectiveCounts[WritingScript.KANA] ?: 0
        val hanCount = effectiveCounts[WritingScript.HAN] ?: 0
        if (kanaCount > 0 && hanCount > 0) {
            effectiveCounts[WritingScript.KANA] = kanaCount + hanCount
            effectiveCounts.remove(WritingScript.HAN)
        }

        val latinCount = effectiveCounts[WritingScript.LATIN] ?: 0
        val nonLatinEntries = effectiveCounts.entries.filter { it.key != WritingScript.LATIN && it.value > 0 }
        val nonLatinTotal = totalLetters - latinCount

        val dominant =
            if (nonLatinEntries.isEmpty()) {
                WritingScript.LATIN
            } else {
                val topNonLatin = nonLatinEntries.maxBy { it.value }
                val nonLatinShareSufficient =
                    nonLatinTotal * 100 >= totalLetters * NON_LATIN_DOMINANCE_MIN_PERCENT ||
                        topNonLatin.value >= NON_LATIN_DOMINANCE_MIN_LETTERS
                if (nonLatinShareSufficient) topNonLatin.key else WritingScript.LATIN
            }

        return ScriptDetectionResult(
            dominantScript = dominant,
            scriptCounts = counts,
            totalLetters = totalLetters,
        )
    }

    fun countLetters(text: String): Int {
        var count = 0
        for (ch in text) {
            if (classifyChar(ch) != null) count++
        }
        return count
    }

    internal fun classifyChar(ch: Char): WritingScript? {
        val code = ch.code
        return when (code) {
            in 0x0041..0x005A,
            in 0x0061..0x007A,
            in 0x00C0..0x00D6,
            in 0x00D8..0x00F6,
            in 0x00F8..0x024F,
            in 0x1E00..0x1EFF,
            -> WritingScript.LATIN

            in 0x0D00..0x0D7F -> WritingScript.MALAYALAM

            in 0x0900..0x097F -> WritingScript.DEVANAGARI

            in 0x0B80..0x0BFF -> WritingScript.TAMIL

            in 0x0C00..0x0C7F -> WritingScript.TELUGU

            in 0x0A00..0x0A7F -> WritingScript.GURMUKHI

            in 0x0C80..0x0CFF -> WritingScript.KANNADA

            in 0x0980..0x09FF -> WritingScript.BENGALI

            in 0x0A80..0x0AFF -> WritingScript.GUJARATI

            in 0xAC00..0xD7AF,
            in 0x1100..0x11FF,
            in 0x3130..0x318F,
            -> WritingScript.HANGUL

            in 0x3040..0x30FF,
            in 0x31F0..0x31FF,
            -> WritingScript.KANA

            in 0x4E00..0x9FFF,
            in 0x3400..0x4DBF,
            -> WritingScript.HAN

            in 0x0600..0x06FF,
            in 0x0750..0x077F,
            in 0x08A0..0x08FF,
            -> WritingScript.ARABIC

            in 0x0400..0x04FF,
            in 0x0500..0x052F,
            -> WritingScript.CYRILLIC

            in 0x0E00..0x0E7F -> WritingScript.THAI

            in 0x0370..0x03FF,
            in 0x1F00..0x1FFF,
            -> WritingScript.GREEK

            else -> null
        }
    }
}
