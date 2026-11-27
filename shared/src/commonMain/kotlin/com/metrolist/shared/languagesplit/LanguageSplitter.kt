/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.languagesplit

import com.metrolist.shared.db.daos.LanguageSplitDao
import com.metrolist.shared.db.entities.ArtistLanguage
import com.metrolist.shared.db.entities.OtherLanguageItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.getCurrentTimeMillis
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * The single Home split point. Every path (primary load, pagination, chip feeds,
 * startup restore) calls [split]; sections that become empty are dropped.
 * Persisting moved items and learned artist rows happens once per call, after
 * the per-item loop, never inside it.
 */
class LanguageSplitter(
    private val classifier: LanguageClassifier,
    private val dao: LanguageSplitDao,
) {
    private val itemJson =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "_type"
        }

    suspend fun split(sections: List<HomeSection>): List<HomeSection> {
        val start = TimeSource.Monotonic.markNow()
        var kept = 0
        var moved = 0
        var unknown = 0
        val movedItems = mutableListOf<YTItem>()
        val learned = linkedMapOf<String, ArtistLanguageLearn>()
        val result = ArrayList<HomeSection>(sections.size)
        for (section in sections) {
            if (section.items.isEmpty()) {
                result.add(section)
                continue
            }
            val stay = ArrayList<YTItem>(section.items.size)
            for (item in section.items) {
                val classification = classifier.classify(item)
                classification.learned?.let { learn ->
                    if (!learned.containsKey(learn.artistId)) learned[learn.artistId] = learn
                }
                when (classification.language) {
                    LanguageClass.OTHER -> {
                        moved++
                        movedItems.add(item)
                    }
                    LanguageClass.UNKNOWN -> {
                        unknown++
                        stay.add(item)
                    }
                    LanguageClass.ENGLISH -> {
                        kept++
                        stay.add(item)
                    }
                }
            }
            if (stay.isNotEmpty()) {
                result.add(section.copy(items = stay))
            }
            // Sections emptied by the split are dropped.
        }
        if (movedItems.isNotEmpty()) {
            val now = getCurrentTimeMillis()
            val rows =
                movedItems
                    .distinctBy { it.id }
                    .map { item -> OtherLanguageItem(item.id, itemJson.encodeToString(YTItem.serializer(), item), now) }
            dao.upsertOtherLanguageItems(rows)
            dao.trimOtherLanguageItems(OTHER_LANGUAGES_STORE_CAP)
        }
        persistLearned(learned.values)
        Logger.d(
            LOG_TAG,
            "kept=$kept moved=$moved unknown=$unknown timeMs=${start.elapsedNow().inWholeMilliseconds}",
        )
        return result
    }

    /**
     * Loads the Other languages screen content: stored items that still classify
     * as OTHER (self-healing after an override), pruning rows that no longer do.
     */
    suspend fun loadStoredOtherLanguageItems(): List<YTItem> {
        val rows = dao.getAllOtherLanguageItems()
        if (rows.isEmpty()) return emptyList()
        val decoded =
            rows.mapNotNull { row ->
                runCatching { itemJson.decodeFromString(YTItem.serializer(), row.payload) }.getOrNull()
            }
        val classifications = decoded.map { it to classifier.classify(it) }
        persistLearned(classifications.mapNotNull { it.second.learned })
        val other = classifications.filter { it.second.language == LanguageClass.OTHER }.map { it.first }
        val keepIds = other.map { it.id }.toSet()
        if (keepIds.isEmpty()) {
            dao.deleteAllOtherLanguageItems()
        } else {
            dao.deleteOtherLanguageItemsExcept(keepIds)
        }
        return other
    }

    private suspend fun persistLearned(learned: Collection<ArtistLanguageLearn>) {
        if (learned.isEmpty()) return
        val blocked =
            dao
                .getArtistLanguages(learned.map { it.artistId })
                .filter { it.source == ArtistLanguageCache.SOURCE_USER }
                .mapTo(mutableSetOf()) { it.artistId }
        val rows =
            learned
                .filter { it.artistId !in blocked }
                .map { ArtistLanguage(it.artistId, it.language.name, it.source, getCurrentTimeMillis()) }
        if (rows.isNotEmpty()) {
            dao.upsertArtistLanguages(rows)
        }
    }

    companion object {
        const val LOG_TAG = "LangSplit"
        const val OTHER_LANGUAGES_STORE_CAP = 300
    }
}
