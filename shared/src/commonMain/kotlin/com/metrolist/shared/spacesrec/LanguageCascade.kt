/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.SongItem

/**
 * Pure L0 -> L1 -> L2 -> L3 -> L4 language cascade. Never awaits any network call.
 *
 * Stop at the first decisive step:
 *  L0: track_class cache by videoId
 *  L1: Unicode-block script detector over title + artist names
 *  L2: settled artist_profile verdict (totalVotes >= minVotes && topShare >= minShare)
 *  L3: on-device TextLanguageDetector when >= 12 letters (with romanized-language guard)
 *  L4: provenance (seedLanguageAccepted -> MAYBE with high L5 priority; never ACCEPT)
 */
class LanguageCascade(
    private val trackClassLookup: TrackClassLookup,
    private val artistProfileLookup: ArtistProfileLookup,
    private val textLanguageDetector: TextLanguageDetector = NoOpTextLanguageDetector,
    private val config: SpaceRecConfig = SpaceRecConfig.Default,
) {
    suspend fun evaluate(
        candidate: CandidateTrack,
        spaceLanguages: List<String>,
        nowMs: Long,
    ): LanguageCascadeResult {
        if (spaceLanguages.isEmpty()) {
            return LanguageCascadeResult(verdict = GateVerdict.ACCEPT, step = LanguageStep.BYPASS_EMPTY)
        }

        val targets = SpaceLanguageRegistry.parseTargets(spaceLanguages)
        if (targets.isEmpty) {
            // Only "instrumental" (or blank entries) was configured; language gate accepts and genre gate handles instrumental.
            return LanguageCascadeResult(
                verdict = GateVerdict.ACCEPT,
                step = if (targets.hasInstrumental) LanguageStep.BYPASS_INSTRUMENTAL else LanguageStep.BYPASS_EMPTY,
            )
        }

        val song = candidate.song
        val text = buildSongLanguageText(song)

        // --- L0: track_class cache by videoId ---
        val cachedTrack = trackClassLookup.getTrackClass(song.id)
        val cachedLang = cachedTrack?.language?.takeIf(String::isNotBlank)
        if (cachedLang != null) {
            val canonical = SpaceLanguageRegistry.canonicalCode(cachedLang) ?: cachedLang.lowercase()
            // Rule (3): low-confidence language classification must be MAYBE, never pass as ACCEPT.
            if (cachedTrack.languageConfidence < config.l3AcceptConfidence) {
                return LanguageCascadeResult(
                    verdict = GateVerdict.MAYBE,
                    step = LanguageStep.L0_TRACK_CACHE,
                    detectedLanguage = canonical,
                    confidence = cachedTrack.languageConfidence,
                    highPriorityForL5 = true,
                )
            }
            val verdict = if (targets.matchesCode(cachedLang)) GateVerdict.ACCEPT else GateVerdict.REJECT
            return LanguageCascadeResult(
                verdict = verdict,
                step = LanguageStep.L0_TRACK_CACHE,
                detectedLanguage = canonical,
                confidence = cachedTrack.languageConfidence,
            )
        }

        // --- L1: script detector over letters of title + artist names ---
        val scriptResult = ScriptDetector.detect(text)
        val dominantScript = scriptResult.dominantScript
        if (dominantScript != null && dominantScript != WritingScript.LATIN) {
            val scriptMatchesSpace = targets.acceptsScript(dominantScript)
            if (!dominantScript.isAmbiguous) {
                val detectedIso = SpaceLanguageRegistry.unambiguousScriptToIso[dominantScript]
                if (scriptMatchesSpace) {
                    return LanguageCascadeResult(
                        verdict = GateVerdict.ACCEPT,
                        step = LanguageStep.L1_SCRIPT,
                        detectedLanguage = detectedIso,
                        confidence = config.voteWeightScript,
                    )
                }
                // Custom free-text slugs have no known script, so only reject when no custom slugs are present.
                if (!targets.hasCustomSlugs) {
                    return LanguageCascadeResult(
                        verdict = GateVerdict.REJECT,
                        step = LanguageStep.L1_SCRIPT,
                        detectedLanguage = detectedIso,
                        confidence = config.voteWeightScript,
                    )
                }
            } else {
                // Ambiguous script (Han, Devanagari, Arabic, Cyrillic):
                // REJECT if no Space language uses that script (and no custom slugs exist).
                if (!scriptMatchesSpace && !targets.hasCustomSlugs) {
                    return LanguageCascadeResult(
                        verdict = GateVerdict.REJECT,
                        step = LanguageStep.L1_SCRIPT,
                        confidence = config.voteWeightScript,
                    )
                }
                if (scriptMatchesSpace) {
                    // ACCEPT only when artist verdict agrees OR on-device ID agrees; otherwise MAYBE.
                    val artistSettled = lookupSettledArtistLanguage(candidate.primaryArtistId, nowMs)
                    if (artistSettled != null && targets.matchesCode(artistSettled)) {
                        return LanguageCascadeResult(
                            verdict = GateVerdict.ACCEPT,
                            step = LanguageStep.L1_SCRIPT,
                            detectedLanguage = artistSettled,
                            confidence = config.voteWeightScript,
                        )
                    }
                    val onDevice = detectOnDeviceIfEligible(text, scriptResult.totalLetters)
                    if (onDevice != null &&
                        targets.matchesCode(onDevice.isoCode) &&
                        onDevice.confidence >= config.l3AcceptConfidence
                    ) {
                        return LanguageCascadeResult(
                            verdict = GateVerdict.ACCEPT,
                            step = LanguageStep.L1_SCRIPT,
                            detectedLanguage = SpaceLanguageRegistry.canonicalCode(onDevice.isoCode),
                            confidence = onDevice.confidence,
                        )
                    }
                    return LanguageCascadeResult(
                        verdict = GateVerdict.MAYBE,
                        step = LanguageStep.L1_SCRIPT,
                        highPriorityForL5 = candidate.seedLanguageAccepted,
                    )
                }
            }
        }

        // --- L2: artist verdict from artist_profile ---
        val artistSettled = lookupSettledArtistLanguage(candidate.primaryArtistId, nowMs)
        if (artistSettled != null) {
            val verdict = if (targets.matchesCode(artistSettled)) GateVerdict.ACCEPT else GateVerdict.REJECT
            return LanguageCascadeResult(
                verdict = verdict,
                step = LanguageStep.L2_ARTIST_PROFILE,
                detectedLanguage = artistSettled,
                confidence = 1.0,
            )
        }

        // --- L3: on-device language ID over title + artist names (>= 12 letters) ---
        val onDevice = detectOnDeviceIfEligible(text, scriptResult.totalLetters)
        if (onDevice != null) {
            val detectedCode = SpaceLanguageRegistry.canonicalCode(onDevice.isoCode) ?: onDevice.isoCode.lowercase()
            if (targets.matchesCode(detectedCode) && onDevice.confidence >= config.l3AcceptConfidence) {
                return LanguageCascadeResult(
                    verdict = GateVerdict.ACCEPT,
                    step = LanguageStep.L3_ON_DEVICE,
                    detectedLanguage = detectedCode,
                    confidence = onDevice.confidence,
                )
            }
            if (!targets.matchesCode(detectedCode) && onDevice.confidence >= config.l3RejectConfidence) {
                // Romanized-language guard: if any Space language is commonly written in Latin script,
                // Latin-script tracks are NEVER rejected by L3 — they become MAYBE.
                // Custom free-text slugs also cannot be rejected by L3.
                val protectRomanized =
                    targets.hasCommonlyRomanized &&
                        (dominantScript == WritingScript.LATIN || !scriptResult.hasNonLatin)
                if (protectRomanized || targets.hasCustomSlugs) {
                    return LanguageCascadeResult(
                        verdict = GateVerdict.MAYBE,
                        step = LanguageStep.L3_ON_DEVICE,
                        detectedLanguage = detectedCode,
                        confidence = onDevice.confidence,
                        highPriorityForL5 = candidate.seedLanguageAccepted,
                    )
                }
                return LanguageCascadeResult(
                    verdict = GateVerdict.REJECT,
                    step = LanguageStep.L3_ON_DEVICE,
                    detectedLanguage = detectedCode,
                    confidence = onDevice.confidence,
                )
            }
            // Rule (3): low-confidence on-device detection must be MAYBE, never pass as ACCEPT.
            return LanguageCascadeResult(
                verdict = GateVerdict.MAYBE,
                step = LanguageStep.L3_ON_DEVICE,
                detectedLanguage = detectedCode,
                confidence = onDevice.confidence,
                highPriorityForL5 = candidate.seedLanguageAccepted,
            )
        }

        // --- L4: provenance (seedLanguageAccepted -> MAYBE with high L5 priority, never ACCEPT) ---
        if (candidate.seedLanguageAccepted) {
            return LanguageCascadeResult(
                verdict = GateVerdict.MAYBE,
                step = LanguageStep.L4_PROVENANCE,
                highPriorityForL5 = true,
            )
        }

        return LanguageCascadeResult(
            verdict = GateVerdict.MAYBE,
            step = LanguageStep.UNRESOLVED_MAYBE,
            highPriorityForL5 = false,
        )
    }

    /** Convenience helper for evaluating card title + artist text (e.g. Home album/playlist cards). */
    suspend fun evaluateText(
        titleAndArtists: String,
        spaceLanguages: List<String>,
    ): LanguageCascadeResult {
        if (spaceLanguages.isEmpty()) {
            return LanguageCascadeResult(verdict = GateVerdict.ACCEPT, step = LanguageStep.BYPASS_EMPTY)
        }
        val targets = SpaceLanguageRegistry.parseTargets(spaceLanguages)
        if (targets.isEmpty) {
            return LanguageCascadeResult(
                verdict = GateVerdict.ACCEPT,
                step = if (targets.hasInstrumental) LanguageStep.BYPASS_INSTRUMENTAL else LanguageStep.BYPASS_EMPTY,
            )
        }
        val scriptResult = ScriptDetector.detect(titleAndArtists)
        val dominant = scriptResult.dominantScript
        if (dominant != null && dominant != WritingScript.LATIN) {
            val matches = targets.acceptsScript(dominant)
            if (!dominant.isAmbiguous) {
                val iso = SpaceLanguageRegistry.unambiguousScriptToIso[dominant]
                if (matches) return LanguageCascadeResult(GateVerdict.ACCEPT, LanguageStep.L1_SCRIPT, iso, config.voteWeightScript)
                if (!targets.hasCustomSlugs) {
                    return LanguageCascadeResult(GateVerdict.REJECT, LanguageStep.L1_SCRIPT, iso, config.voteWeightScript)
                }
            } else {
                if (!matches && !targets.hasCustomSlugs) {
                    return LanguageCascadeResult(GateVerdict.REJECT, LanguageStep.L1_SCRIPT, confidence = config.voteWeightScript)
                }
                if (matches) {
                    val onDevice = detectOnDeviceIfEligible(titleAndArtists, scriptResult.totalLetters)
                    if (onDevice != null && targets.matchesCode(onDevice.isoCode) && onDevice.confidence >= config.l3AcceptConfidence) {
                        return LanguageCascadeResult(
                            GateVerdict.ACCEPT,
                            LanguageStep.L1_SCRIPT,
                            SpaceLanguageRegistry.canonicalCode(onDevice.isoCode),
                            onDevice.confidence,
                        )
                    }
                    return LanguageCascadeResult(GateVerdict.MAYBE, LanguageStep.L1_SCRIPT)
                }
            }
        }

        val onDevice = detectOnDeviceIfEligible(titleAndArtists, scriptResult.totalLetters)
        if (onDevice != null) {
            val code = SpaceLanguageRegistry.canonicalCode(onDevice.isoCode) ?: onDevice.isoCode.lowercase()
            if (targets.matchesCode(code) && onDevice.confidence >= config.l3AcceptConfidence) {
                return LanguageCascadeResult(GateVerdict.ACCEPT, LanguageStep.L3_ON_DEVICE, code, onDevice.confidence)
            }
            if (!targets.matchesCode(code) && onDevice.confidence >= config.l3RejectConfidence) {
                val protectRomanized = targets.hasCommonlyRomanized && !scriptResult.hasNonLatin
                return if (protectRomanized || targets.hasCustomSlugs) {
                    LanguageCascadeResult(GateVerdict.MAYBE, LanguageStep.L3_ON_DEVICE, code, onDevice.confidence)
                } else {
                    LanguageCascadeResult(GateVerdict.REJECT, LanguageStep.L3_ON_DEVICE, code, onDevice.confidence)
                }
            }
        }
        return LanguageCascadeResult(GateVerdict.MAYBE, LanguageStep.UNRESOLVED_MAYBE)
    }

    private suspend fun lookupSettledArtistLanguage(
        artistId: String?,
        nowMs: Long,
    ): String? {
        if (artistId.isNullOrBlank()) return null
        val profile = artistProfileLookup.getArtistProfile(artistId) ?: return null
        return profile.settledLanguage(nowMs, config)
    }

    private suspend fun detectOnDeviceIfEligible(
        text: String,
        letterCount: Int,
    ): DetectedLanguage? {
        if (letterCount < config.l3MinLetters) return null
        return textLanguageDetector.detect(text)
    }

    companion object {
        fun buildSongLanguageText(song: SongItem): String =
            buildString {
                append(song.title)
                if (song.artists.isNotEmpty()) {
                    append(' ')
                    append(song.artists.joinToString(" ") { it.name })
                }
            }
    }
}
