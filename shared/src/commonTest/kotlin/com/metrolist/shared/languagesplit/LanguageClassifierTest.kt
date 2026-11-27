/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.languagesplit

import com.metrolist.shared.db.daos.LanguageSplitDao
import com.metrolist.shared.db.entities.ArtistLanguage
import com.metrolist.shared.db.entities.OtherLanguageItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageClassifierTest {
    private fun song(
        id: String,
        title: String,
        vararg artists: Pair<String?, String>,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            thumbnail = null,
            artists = artists.map { (artistId, name) -> ArtistTiny(artistId, name) },
            album = null,
        )

    private fun classifier(
        cache: ArtistLanguageCache = ArtistLanguageCache(),
        signal: LanguageSignal = NoOpLanguageSignal,
    ) = LanguageClassifier(signal, cache)

    @Test
    fun englishRapTitleStaysOnHome() =
        runBlocking {
            val result = classifier().classify(song("v1", "HUMBLE.", "UC1" to "Kendrick Lamar"))
            assertEquals(LanguageClass.UNKNOWN, result.language)
        }

    @Test
    fun plainEnglishTitleStaysUnknown() =
        runBlocking {
            val result = classifier().classify(song("v2", "Shape of You", "UC2" to "Ed Sheeran"))
            assertEquals(LanguageClass.UNKNOWN, result.language)
        }

    @Test
    fun malayalamScriptMoves() =
        runBlocking {
            val result = classifier().classify(song("v3", "എന്റെ സ്നേഹം", "UC3" to "Shreya Ghoshal"))
            assertEquals(LanguageClass.OTHER, result.language)
        }

    @Test
    fun hindiDevanagariScriptMoves() =
        runBlocking {
            val result = classifier().classify(song("v4", "तेरी मेरी बातें", "UC4" to "Arijit Singh"))
            assertEquals(LanguageClass.OTHER, result.language)
        }

    @Test
    fun romanizedMalayalamTitleMoves() =
        runBlocking {
            val result = classifier().classify(song("v5", "Ente Daivam Njan", "UC5" to "K.S. Chithra"))
            assertEquals(LanguageClass.OTHER, result.language)
        }

    @Test
    fun romanizedHindiTitleMoves() =
        runBlocking {
            val result = classifier().classify(song("v6", "Tera Mera Dil", "UC6" to "Lata Mangeshkar"))
            assertEquals(LanguageClass.OTHER, result.language)
        }

    @Test
    fun romanizedPunjabiTitleMoves() =
        runBlocking {
            val result = classifier().classify(song("v7", "Veere Di Gallan", "UC7" to "Karan Aujla"))
            assertEquals(LanguageClass.OTHER, result.language)
        }

    @Test
    fun primaryArtistOtherForcesSongOther() =
        runBlocking {
            val cache = ArtistLanguageCache()
            cache.put("UC8", LanguageClass.OTHER, "SCRIPT")
            val result = classifier(cache).classify(song("v8", "Midnight Drive", "UC8" to "Yo Yo Honey Singh", "UC9" to "Some Guest"))
            assertEquals(LanguageClass.OTHER, result.language)
        }

    @Test
    fun userOverrideBeatsScriptDetection() =
        runBlocking {
            val cache = ArtistLanguageCache()
            cache.put("UC10", LanguageClass.ENGLISH, "USER")
            val result = classifier(cache).classify(song("v10", "എന്റെ പേര്", "UC10" to "Armaan Malik"))
            assertEquals(LanguageClass.ENGLISH, result.language)
        }

    @Test
    fun singleRomanizedHitNeedsMlKitCorroboration() =
        runBlocking {
            val title = "Njan Udan"
            val withSignal =
                classifier(signal = object : LanguageSignal {
                    override suspend fun identify(text: String): LanguageGuess? = LanguageGuess("ml", 0.93f)
                }).classify(song("v11", title, "UC11" to "Vidhu Prathap"))
            assertEquals(LanguageClass.OTHER, withSignal.language)

            val withoutSignal = classifier().classify(song("v12", title, "UC11" to "Vidhu Prathap"))
            assertEquals(LanguageClass.UNKNOWN, withoutSignal.language)
        }

    @Test
    fun splitRemovesOtherSongsAndDropsEmptiedSections() =
        runBlocking {
            val dao = FakeLanguageSplitDao()
            val splitter = LanguageSplitter(classifier(), dao)
            val english = song("en1", "Blinding Lights", "UC20" to "The Weeknd")
            val malayalam = song("ml1", "Ente Daivam Njan", "UC21" to "K.S. Chithra")
            val sections =
                listOf(
                    HomeSection(title = "Mixed", items = listOf(english, malayalam)),
                    HomeSection(title = "All other", items = listOf(malayalam.copy(id = "ml2"))),
                )

            val result = splitter.split(sections)

            assertEquals(1, result.size)
            assertEquals(listOf("en1"), result[0].items.map { it.id })
            assertEquals(setOf("ml1", "ml2"), dao.items.mapTo(mutableSetOf()) { it.videoId })
        }

    @Test
    fun storedOtherLanguageItemsSelfHealAfterOverride() =
        runBlocking {
            val dao = FakeLanguageSplitDao()
            val splitter = LanguageSplitter(classifier(), dao)
            val malayalam = song("ml3", "Ente Daivam Njan", "UC22" to "K.S. Chithra")
            val english = song("en2", "Blinding Lights", "UC23" to "The Weeknd")
            val payload = Json { ignoreUnknownKeys = true; classDiscriminator = "_type" }
            dao.upsertOtherLanguageItems(
                listOf(
                    OtherLanguageItem("ml3", payload.encodeToString(YTItem.serializer(), malayalam), 1L),
                    OtherLanguageItem("en2", payload.encodeToString(YTItem.serializer(), english), 2L),
                ),
            )

            val stored = splitter.loadStoredOtherLanguageItems()

            assertEquals(listOf("ml3"), stored.map { it.id })
            assertEquals(listOf("ml3"), dao.items.map { it.videoId })
        }

    @Test
    fun learnedArtistRowsNeverOverwriteUserRowsInStore() =
        runBlocking {
            val dao = FakeLanguageSplitDao()
            dao.upsertArtistLanguages(listOf(ArtistLanguage("UC30", LanguageClass.ENGLISH.name, "USER", 1L)))
            val splitter = LanguageSplitter(classifier(), dao)

            // A script-based learn for an artist whose stored row is USER must not clobber it.
            splitter.split(
                listOf(HomeSection(title = "S", items = listOf(song("x1", "എന്റെ ദൈവം", "UC30" to "Someone")))),
            )

            val rows = dao.getAllArtistLanguages().associateBy { it.artistId }
            assertEquals("USER", rows.getValue("UC30").source)
            assertEquals(LanguageClass.ENGLISH.name, rows.getValue("UC30").language)
        }
}

private class FakeLanguageSplitDao : LanguageSplitDao {
    val artists = mutableListOf<ArtistLanguage>()
    val items = mutableListOf<OtherLanguageItem>()

    override suspend fun getAllArtistLanguages(): List<ArtistLanguage> = artists.toList()

    override suspend fun getArtistLanguages(artistIds: List<String>): List<ArtistLanguage> =
        artists.filter { it.artistId in artistIds }

    override suspend fun upsertArtistLanguages(rows: List<ArtistLanguage>) {
        rows.forEach { row ->
            artists.removeAll { it.artistId == row.artistId }
            artists.add(row)
        }
    }

    override suspend fun getAllOtherLanguageItems(): List<OtherLanguageItem> =
        items.sortedWith(compareByDescending<OtherLanguageItem> { it.addedAt }.thenByDescending { it.videoId })

    override suspend fun upsertOtherLanguageItems(rows: List<OtherLanguageItem>) {
        rows.forEach { row ->
            items.removeAll { it.videoId == row.videoId }
            items.add(row)
        }
    }

    override suspend fun deleteOtherLanguageItemsExcept(keepIds: List<String>) {
        items.removeAll { it.videoId !in keepIds }
    }

    override suspend fun deleteAllOtherLanguageItems() {
        items.clear()
    }

    override suspend fun trimOtherLanguageItems(cap: Int) {
        val keep = getAllOtherLanguageItems().take(cap).mapTo(mutableSetOf()) { it.videoId }
        items.removeAll { it.videoId !in keep }
    }
}
