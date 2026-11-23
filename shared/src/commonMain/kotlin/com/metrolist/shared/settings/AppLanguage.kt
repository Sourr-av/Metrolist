package com.metrolist.shared.settings

import androidx.compose.ui.text.intl.Locale
import com.metrolist.shared.state.GlobalYouTube

const val HINGLISH_LANGUAGE_TAG = "hi-Latn"

fun canonicalAppLanguageTag(tag: String): String = if (tag.equals("hin", ignoreCase = true)) HINGLISH_LANGUAGE_TAG else tag

internal fun resolveAppLanguageTag(
    configuredLanguage: String,
    systemLanguageTags: List<String>,
): String {
    val selectedLanguage = configuredLanguage.trim()
    val requested =
        if (selectedLanguage.isBlank() || selectedLanguage.equals(GlobalYouTube.SYSTEM, ignoreCase = true)) {
            systemLanguageTags.firstOrNull().orEmpty()
        } else {
            selectedLanguage
        }
    return requested
        .trim()
        .replace('_', '-')
        .takeIf(String::isNotBlank)
        ?.let { Locale(canonicalAppLanguageTag(it)).toLanguageTag() }
        .orEmpty()
}
