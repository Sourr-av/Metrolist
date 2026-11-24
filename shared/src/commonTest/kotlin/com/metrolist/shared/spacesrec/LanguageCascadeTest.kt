/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageCascadeTest {
    private val nowMs = 1_700_000_000_000L

    private class FakeTrackLookup(
        private val map: Map<String, TrackClassRecord> = emptyMap(),
    ) : TrackClassLookup {
        override suspend fun getTrackClass(videoId: String): TrackClassRecord? = map[videoId]
    }

    private class FakeArtistLookup(
        private val map: Map<String, ArtistProfileRecord> = emptyMap(),
    ) : ArtistProfileLookup {
        override suspend fun getArtistProfile(artistId: String): ArtistProfileRecord? = map[artistId]
    }

    private class FakeTextDetector(
        private val result: DetectedLanguage?,
    ) : TextLanguageDetector {
        var calls = 0

        override suspend fun detect(text: String): DetectedLanguage? {
            calls++
            return result
        }
    }

    private fun candidate(
        id: String = "v1",
        title: String,
        artistName: String = "Artist",
        artistId: String? = "a1",
        seedLanguageAccepted: Boolean = false,
    ): CandidateTrack =
        CandidateTrack(
            song =
                SongItem(
                    id = id,
                    title = title,
                    thumbnail = null,
                    artists = listOf(ArtistTiny(artistId, artistName)),
                    album = null,
                ),
            sources = setOf(CandidateSource.HOME),
            seedLanguageAccepted = seedLanguageAccepted,
        )

    @Test
    fun l0TrackCacheDecidesImmediately() =
        runTest {
            val trackCache =
                FakeTrackLookup(
                    mapOf(
                        "v_ml" to TrackClassRecord("v_ml", "ml", 1.0, null, "network", nowMs),
                        "v_es" to TrackClassRecord("v_es", "es", 1.0, null, "network", nowMs),
                    ),
                )
            val cascade = LanguageCascade(trackCache, FakeArtistLookup())

            val acc = cascade.evaluate(candidate(id = "v_ml", title = "Illuminati"), listOf("malayalam"), nowMs)
            assertEquals(GateVerdict.ACCEPT, acc.verdict)
            assertEquals(LanguageStep.L0_TRACK_CACHE, acc.step)

            val rej = cascade.evaluate(candidate(id = "v_es", title = "Dakiti"), listOf("malayalam"), nowMs)
            assertEquals(GateVerdict.REJECT, rej.verdict)
            assertEquals(LanguageStep.L0_TRACK_CACHE, rej.step)
        }

    @Test
    fun l1UnambiguousScriptAcceptsAndRejects() =
        runTest {
            val cascade = LanguageCascade(FakeTrackLookup(), FakeArtistLookup())

            val acc = cascade.evaluate(candidate(title = "മലയാളം പാട്ട്"), listOf("malayalam"), nowMs)
            assertEquals(GateVerdict.ACCEPT, acc.verdict)
            assertEquals(LanguageStep.L1_SCRIPT, acc.step)
            assertEquals("ml", acc.detectedLanguage)

            val rej = cascade.evaluate(candidate(title = "봄날", artistName = "BTS"), listOf("malayalam"), nowMs)
            assertEquals(GateVerdict.REJECT, rej.verdict)
            assertEquals(LanguageStep.L1_SCRIPT, rej.step)
            assertEquals("ko", rej.detectedLanguage)
        }

    @Test
    fun l1AmbiguousScriptRequiresAgreementToAccept() =
        runTest {
            val devanagariTrack = candidate(title = "तुम ही हो", artistName = "Arijit Singh", artistId = "a_arijit")

            // Devanagari in an English Space -> REJECT.
            val cascadeNoData = LanguageCascade(FakeTrackLookup(), FakeArtistLookup())
            val rej = cascadeNoData.evaluate(devanagariTrack, listOf("english"), nowMs)
            assertEquals(GateVerdict.REJECT, rej.verdict)
            assertEquals(LanguageStep.L1_SCRIPT, rej.step)

            // Devanagari in a Hindi Space with no artist profile / no L3 -> MAYBE (ambiguous: could be Marathi/Nepali).
            val maybe = cascadeNoData.evaluate(devanagariTrack, listOf("hindi"), nowMs)
            assertEquals(GateVerdict.MAYBE, maybe.verdict)
            assertEquals(LanguageStep.L1_SCRIPT, maybe.step)

            // Devanagari in a Hindi Space WITH settled Hindi artist profile -> ACCEPT.
            val settledHindi = ArtistProfileRecord("a_arijit", "Arijit Singh", mapOf("hi" to 4.0), 4.0, nowMs)
            val cascadeWithArtist = LanguageCascade(FakeTrackLookup(), FakeArtistLookup(mapOf("a_arijit" to settledHindi)))
            val acc = cascadeWithArtist.evaluate(devanagariTrack, listOf("hindi"), nowMs)
            assertEquals(GateVerdict.ACCEPT, acc.verdict)
            assertEquals(LanguageStep.L1_SCRIPT, acc.step)
        }

    @Test
    fun l2SettledArtistVerdictResolvesRomanizedTracks() =
        runTest {
            val settledMl = ArtistProfileRecord("a_sushin", "Sushin Shyam", mapOf("ml" to 4.5, "en" to 0.5), 5.0, nowMs)
            val unsettled = ArtistProfileRecord("a_mix", "Mixed", mapOf("ml" to 2.0, "hi" to 2.0), 4.0, nowMs)
            val expired =
                ArtistProfileRecord(
                    "a_old",
                    "Old",
                    mapOf("ml" to 10.0),
                    10.0,
                    updatedAt = nowMs - 200L * SpaceRecConfig.MILLIS_PER_DAY,
                )
            val cascade =
                LanguageCascade(
                    FakeTrackLookup(),
                    FakeArtistLookup(
                        mapOf(
                            "a_sushin" to settledMl,
                            "a_mix" to unsettled,
                            "a_old" to expired,
                        ),
                    ),
                )

            val acc = cascade.evaluate(candidate(title = "Illuminati", artistId = "a_sushin"), listOf("malayalam"), nowMs)
            assertEquals(GateVerdict.ACCEPT, acc.verdict)
            assertEquals(LanguageStep.L2_ARTIST_PROFILE, acc.step)

            val rej = cascade.evaluate(candidate(title = "Illuminati", artistId = "a_sushin"), listOf("english"), nowMs)
            assertEquals(GateVerdict.REJECT, rej.verdict)
            assertEquals(LanguageStep.L2_ARTIST_PROFILE, rej.step)

            val unsettledRes = cascade.evaluate(candidate(title = "Song", artistId = "a_mix"), listOf("malayalam"), nowMs)
            assertEquals(GateVerdict.MAYBE, unsettledRes.verdict)

            val expiredRes = cascade.evaluate(candidate(title = "Song", artistId = "a_old"), listOf("malayalam"), nowMs)
            assertEquals(GateVerdict.MAYBE, expiredRes.verdict)
        }

    @Test
    fun l3RespectsRomanizedGuardAndMinLetterThreshold() =
        runTest {
            val detectorEn = FakeTextDetector(DetectedLanguage("en", 0.96))

            // Short title + artist (< 12 letters) skips L3.
            val cascadeShort = LanguageCascade(FakeTrackLookup(), FakeArtistLookup(), detectorEn)
            val shortRes = cascadeShort.evaluate(candidate(title = "Hi", artistName = "Jo"), listOf("spanish"), nowMs)
            assertEquals(0, detectorEn.calls)
            assertEquals(GateVerdict.MAYBE, shortRes.verdict)

            // Non-romanized Space (Spanish) + confident English L3 -> REJECT.
            val spanishRes =
                cascadeShort.evaluate(
                    candidate(title = "Walking Through The Rain", artistName = "Some Band"),
                    listOf("spanish"),
                    nowMs,
                )
            assertEquals(1, detectorEn.calls)
            assertEquals(GateVerdict.REJECT, spanishRes.verdict)
            assertEquals(LanguageStep.L3_ON_DEVICE, spanishRes.step)

            // Romanized Space (Malayalam) + Latin-script track + confident English L3 -> MAYBE, NEVER REJECT!
            val romanizedRes =
                cascadeShort.evaluate(
                    candidate(title = "Illuminati From Aavesham", artistName = "Sushin Shyam"),
                    listOf("malayalam"),
                    nowMs,
                )
            assertEquals(GateVerdict.MAYBE, romanizedRes.verdict)
            assertEquals(LanguageStep.L3_ON_DEVICE, romanizedRes.step)
        }

    @Test
    fun l4ProvenanceMarksHighPriorityMaybeAndNeverAccepts() =
        runTest {
            val cascade = LanguageCascade(FakeTrackLookup(), FakeArtistLookup(), NoOpTextLanguageDetector)
            val res =
                cascade.evaluate(
                    candidate(title = "Unknown Romanized Track", artistName = "New Artist", seedLanguageAccepted = true),
                    listOf("malayalam"),
                    nowMs,
                )
            assertEquals(GateVerdict.MAYBE, res.verdict)
            assertEquals(LanguageStep.L4_PROVENANCE, res.step)
            assertTrue(res.highPriorityForL5)

            val nonSeed =
                cascade.evaluate(
                    candidate(title = "Unknown Romanized Track", artistName = "New Artist", seedLanguageAccepted = false),
                    listOf("malayalam"),
                    nowMs,
                )
            assertEquals(GateVerdict.MAYBE, nonSeed.verdict)
            assertEquals(LanguageStep.UNRESOLVED_MAYBE, nonSeed.step)
            assertFalse(nonSeed.highPriorityForL5)
        }

    @Test
    fun instrumentalAndCustomSlugsFollowDecisions() =
        runTest {
            val cascade = LanguageCascade(FakeTrackLookup(), FakeArtistLookup())

            // "instrumental" is not a language -> language gate accepts.
            val inst = cascade.evaluate(candidate(title = "Any Track"), listOf("instrumental"), nowMs)
            assertEquals(GateVerdict.ACCEPT, inst.verdict)
            assertEquals(LanguageStep.BYPASS_INSTRUMENTAL, inst.step)

            // Custom free-text slug has no script/code -> always MAYBE unless artist votes or track_class match.
            val customUnresolved = cascade.evaluate(candidate(title = "봄날", artistName = "BTS"), listOf("bhojpuri"), nowMs)
            assertEquals(GateVerdict.MAYBE, customUnresolved.verdict)

            val customSettled = ArtistProfileRecord("a_bhojpuri", "Singer", mapOf("bhojpuri" to 4.0), 4.0, nowMs)
            val cascadeCustom = LanguageCascade(FakeTrackLookup(), FakeArtistLookup(mapOf("a_bhojpuri" to customSettled)))
            val customAccepted =
                cascadeCustom.evaluate(
                    candidate(title = "Track", artistId = "a_bhojpuri"),
                    listOf("bhojpuri"),
                    nowMs,
                )
            assertEquals(GateVerdict.ACCEPT, customAccepted.verdict)
            assertEquals(LanguageStep.L2_ARTIST_PROFILE, customAccepted.step)
        }

    @Test
    fun rule3UnknownOrLowConfidenceLanguageReturnsMaybeNeverAccept() =
        runTest {
            val lowConfTrackCache =
                FakeTrackLookup(
                    mapOf(
                        "v_low_conf" to TrackClassRecord("v_low_conf", "en", 0.45, null, "l3", nowMs),
                        "v_null_lang" to TrackClassRecord("v_null_lang", null, 0.0, "soundtracks_video_game", "lastfm", nowMs),
                    ),
                )
            val lowConfDetector = FakeTextDetector(DetectedLanguage("en", 0.55))
            val cascade = LanguageCascade(lowConfTrackCache, FakeArtistLookup(), lowConfDetector)

            // Low-confidence L0 cache entry must be MAYBE, not ACCEPT.
            val lowL0 = cascade.evaluate(candidate(id = "v_low_conf", title = "Some Song Title"), listOf("english"), nowMs)
            assertEquals(GateVerdict.MAYBE, lowL0.verdict)
            assertEquals(LanguageStep.L0_TRACK_CACHE, lowL0.step)
            assertTrue(lowL0.highPriorityForL5)

            // Unknown language in track_class (language == null) with low-confidence L3 must be MAYBE.
            val unknownL0 =
                cascade.evaluate(
                    candidate(id = "v_null_lang", title = "Orchestral Suite Theme", artistName = "Composer Name"),
                    listOf("english"),
                    nowMs,
                )
            assertEquals(GateVerdict.MAYBE, unknownL0.verdict)
            assertEquals(LanguageStep.L3_ON_DEVICE, unknownL0.step)

            // No cache, no artist profile, no detector -> unknown language -> MAYBE, never ACCEPT.
            val noSignalCascade = LanguageCascade(FakeTrackLookup(), FakeArtistLookup(), NoOpTextLanguageDetector)
            val unknown = noSignalCascade.evaluate(candidate(id = "v_none", title = "Main Theme"), listOf("english"), nowMs)
            assertEquals(GateVerdict.MAYBE, unknown.verdict)
            assertEquals(LanguageStep.UNRESOLVED_MAYBE, unknown.step)
        }
}
