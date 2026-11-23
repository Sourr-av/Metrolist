package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.settings.HomeSpace
import com.metrolist.shared.settings.HomeSpaces
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.spaces.SpaceFallbackShelfTitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.metrolist.shared.viewmodels.HomeViewModel as SharedHomeViewModel

class HomeViewModel(
    private val database: MusicDatabase,
    private val likedSongsName: String,
) : ViewModel() {
    private data class SpaceHomeSession(
        val scope: CoroutineScope,
        val model: SharedHomeViewModel,
    )

    val shared = SharedHomeViewModel(database, viewModelScope, likedSongsName = likedSongsName)

    private val _spaces = MutableStateFlow(HomeSpaces.all())
    val spaces: StateFlow<List<HomeSpace>> = _spaces.asStateFlow()

    private val _activeSpaceId = MutableStateFlow(HomeSpaces.activeId(_spaces.value))
    val activeSpaceId: StateFlow<String?> = _activeSpaceId.asStateFlow()

    private val spaceSessions = mutableMapOf<String, SpaceHomeSession>()

    val activeShared: SharedHomeViewModel
        get() = homeModelFor(_activeSpaceId.value)

    fun activeSpace(): HomeSpace? {
        val id = _activeSpaceId.value ?: return null
        return _spaces.value.firstOrNull { it.id == id }
    }

    fun homeModelFor(
        spaceId: String?,
        shelfTitles: SpaceFallbackShelfTitles = SpaceFallbackShelfTitles(),
    ): SharedHomeViewModel {
        val space = spaceId?.let { id -> _spaces.value.firstOrNull { it.id == id } } ?: return shared
        return spaceSessions
            .getOrPut(space.id) {
                val spaceScope =
                    CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
                SpaceHomeSession(
                    scope = spaceScope,
                    model =
                        SharedHomeViewModel(
                            database = database,
                            scope = spaceScope,
                            likedSongsName = likedSongsName,
                            persistLastHomePage = false,
                            initialSpaceFilter = space.filterSpec.takeIf { it.isActive },
                            spaceShelfTitles = shelfTitles,
                        ),
                )
            }.model
    }

    fun selectSpace(spaceId: String?) {
        _activeSpaceId.value = HomeSpaces.select(spaceId)
    }

    fun createSpace(
        name: String,
        defaultPrefix: String,
        languages: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        shelfTitles: SpaceFallbackShelfTitles = SpaceFallbackShelfTitles(),
    ): HomeSpace {
        val created =
            HomeSpaces.create(
                name = name,
                defaultPrefix = defaultPrefix,
                languages = languages,
                genres = genres,
            )
        _spaces.value = HomeSpaces.all()
        _activeSpaceId.value = created.id
        homeModelFor(created.id, shelfTitles = shelfTitles)
        return created
    }

    fun renameSpace(
        spaceId: String,
        newName: String,
    ) {
        _spaces.value = HomeSpaces.rename(spaceId, newName)
    }

    fun updateSpace(
        spaceId: String,
        newName: String,
        languages: List<String>,
        genres: List<String>,
    ) {
        val updatedSpaces =
            HomeSpaces.update(
                id = spaceId,
                newName = newName,
                languages = languages,
                genres = genres,
            )
        _spaces.value = updatedSpaces
        val updatedSpace = updatedSpaces.firstOrNull { it.id == spaceId }
        val newFilter = updatedSpace?.filterSpec?.takeIf { it.isActive }
        spaceSessions[spaceId]?.model?.updateSpaceFilter(newFilter)
    }

    fun saveLastFmApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        Settings.shared.spacesLastFmApiKey = trimmed
        LastFM.setSpacesApiKey(trimmed)
        val active = activeSpace()
        if (active != null && active.hasFilters) {
            homeModelFor(active.id).refresh {}
        }
    }

    fun deleteSpace(spaceId: String) {
        spaceSessions.remove(spaceId)?.scope?.cancel()
        val updated = HomeSpaces.delete(spaceId)
        _spaces.value = updated
        if (_activeSpaceId.value == spaceId) {
            _activeSpaceId.value = null
        }
    }

    fun nextDefaultSpaceName(defaultPrefix: String): String = HomeSpaces.nextDefaultName(defaultPrefix, _spaces.value)

    override fun onCleared() {
        spaceSessions.values.forEach { it.scope.cancel() }
        spaceSessions.clear()
        super.onCleared()
    }
}
