/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.languagesplit

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device ML Kit language identification — one corroborating signal for the
 * romanized-word heuristics, never a decider on its own. Runs off the main
 * thread; failures degrade to a no-op signal.
 */
class MlKitLanguageSignal : LanguageSignal {
    private val client by lazy { LanguageIdentification.getClient() }

    override suspend fun identify(text: String): LanguageGuess? =
        withContext(Dispatchers.IO) {
            try {
                val languages = Tasks.await(client.identifyPossibleLanguages(text.take(MAX_TEXT_LENGTH)))
                languages
                    .maxByOrNull { it.confidence }
                    ?.let { LanguageGuess(it.languageCode, it.confidence) }
            } catch (_: Exception) {
                null
            }
        }

    private companion object {
        const val MAX_TEXT_LENGTH = 300
    }
}
