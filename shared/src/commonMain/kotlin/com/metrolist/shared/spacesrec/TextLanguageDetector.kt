/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

data class DetectedLanguage(
    val isoCode: String,
    val confidence: Double,
)

/**
 * On-device text language detector (L3).
 * No ML Kit dependency is added due to Metrolist's FOSS / IzzyOnDroid build target;
 * [NoOpTextLanguageDetector] skips L3 cleanly.
 */
interface TextLanguageDetector {
    suspend fun detect(text: String): DetectedLanguage?
}

object NoOpTextLanguageDetector : TextLanguageDetector {
    override suspend fun detect(text: String): DetectedLanguage? = null
}
