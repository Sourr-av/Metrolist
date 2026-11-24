/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.LocalStartupPresentation
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.android.ui.component.tabContentTopPadding
import com.metrolist.android.ui.components.DeleteSpaceDialog
import com.metrolist.android.ui.components.ErrorBox
import com.metrolist.android.ui.components.HomeSpacesBar
import com.metrolist.android.ui.components.SpaceActiveFiltersBar
import com.metrolist.android.ui.components.SpaceEditorSheet
import com.metrolist.android.ui.components.SpaceFilteredEmptyState
import com.metrolist.android.ui.components.SpaceOptionsSheet
import com.metrolist.android.ui.components.handleItemClick
import com.metrolist.android.ui.utils.playSong
import com.metrolist.android.viewmodels.HomeViewModel
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.create
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.home_load_failed
import com.metrolist.shared.generated.resources.home_offline_empty
import com.metrolist.shared.generated.resources.no_playable_items
import com.metrolist.shared.generated.resources.pin_to_speed_dial
import com.metrolist.shared.generated.resources.player_not_ready
import com.metrolist.shared.generated.resources.recognize_music
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.generated.resources.refreshing
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.space_create_title
import com.metrolist.shared.generated.resources.space_default_name
import com.metrolist.shared.generated.resources.space_edit_title
import com.metrolist.shared.generated.resources.space_shelf_albums
import com.metrolist.shared.generated.resources.space_shelf_artists
import com.metrolist.shared.generated.resources.space_shelf_fresh_picks
import com.metrolist.shared.generated.resources.space_shelf_more_like
import com.metrolist.shared.generated.resources.space_shelf_playlists
import com.metrolist.shared.generated.resources.space_shelf_songs
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.generated.resources.unpin_from_speed_dial
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isAvailableOffline
import com.metrolist.shared.settings.HomeSpace
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.spaces.SpaceFallbackShelfTitles
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.HomeLoadingPlaceholder
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import com.metrolist.shared.ui.screens.HomeContent
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.homeContentText
import com.metrolist.shared.ui.tv.RequestTvFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.wrapped.WrappedHomeCard
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun HomeScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPlaylist: (String, String?) -> Unit = { _, _ -> },
    onNavigateToPodcast: (String) -> Unit = {},
    onNavigateToRecognition: () -> Unit = {},
    onNavigateToWrapped: () -> Unit = {},
    onNavigateToBrowse: (String, String?, String) -> Unit = { _, _, _ -> },
    onNavigateToVideoBrowse: (String, String?, String) -> Unit = onNavigateToBrowse,
    onNavigateToHomeSection: (HomeShelf) -> Unit = {},
    onNavigateToLibrary: (LibraryFilterType?) -> Unit = {},
    floatingActionsBottomInset: State<Dp>,
    scrollToTopEvents: Flow<Long> = emptyFlow(),
    viewModel: HomeViewModel = koinViewModel<HomeViewModel>(),
) {
    val playerNotReadyText = stringResource(Res.string.player_not_ready)
    val noPlayableItemsText = stringResource(Res.string.no_playable_items)
    val shuffleText = stringResource(Res.string.shuffle)
    val defaultSpacePrefix = stringResource(Res.string.space_default_name)
    val spaceShelfSongs = stringResource(Res.string.space_shelf_songs)
    val spaceShelfAlbums = stringResource(Res.string.space_shelf_albums)
    val spaceShelfPlaylists = stringResource(Res.string.space_shelf_playlists)
    val spaceShelfArtists = stringResource(Res.string.space_shelf_artists)
    val spaceShelfFreshPicks = stringResource(Res.string.space_shelf_fresh_picks)
    val spaceShelfMoreLikeTemplate = stringResource(Res.string.space_shelf_more_like, "{artist}")
    val spaceShelfTitles =
        remember(
            spaceShelfSongs,
            spaceShelfAlbums,
            spaceShelfPlaylists,
            spaceShelfArtists,
            spaceShelfFreshPicks,
            spaceShelfMoreLikeTemplate,
        ) {
            SpaceFallbackShelfTitles(
                songs = spaceShelfSongs,
                albums = spaceShelfAlbums,
                playlists = spaceShelfPlaylists,
                artists = spaceShelfArtists,
                freshPicks = spaceShelfFreshPicks,
                moreLikeTemplate = spaceShelfMoreLikeTemplate,
            )
        }

    val rootActive = LocalRootContentActive.current
    val context = LocalContext.current
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val activeSpaceId by viewModel.activeSpaceId.collectAsStateWithLifecycle()
    val activeSpace = remember(spaces, activeSpaceId) { spaces.firstOrNull { it.id == activeSpaceId } }
    val activeHome = remember(activeSpaceId, spaceShelfTitles) { viewModel.homeModelFor(activeSpaceId, spaceShelfTitles) }
    val uiState by activeHome.uiState.collectAsStateWithLifecycle()
    val startupPresentation = LocalStartupPresentation.current
    SideEffect { startupPresentation?.homeCacheReady = !uiState.isRestoringCache }
    val playerConnection = LocalPlayerConnection.current
    val currentSong = playerConnection?.currentSong?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val isPlaying = playerConnection?.isPlaying?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val database: MusicDatabase = koinInject()
    val scope = rememberCoroutineScope()
    val networkState by PlaybackNetworkStateStore.state.collectAsStateWithLifecycle()
    val cachedSongIds by remember(database) {
        database
            .dao()
            .cachedAudioIds()
            .map { it.toSet() }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val isOffline = !networkState.connected
    val isHomeItemEnabled: (YTItem) -> Boolean = { item ->
        !isOffline || item.isAvailableOffline(cachedSongIds)
    }

    val listStates = remember { mutableMapOf<String?, LazyListState>() }
    LaunchedEffect(spaces) {
        val validIds = spaces.mapTo(mutableSetOf<String?>()) { it.id } + null
        listStates.keys.retainAll(validIds)
    }
    val listState = remember(activeSpaceId) { listStates.getOrPut(activeSpaceId) { LazyListState() } }
    val currentListState by rememberUpdatedState(listState)
    val spacesScrollState = rememberScrollState()

    var lastScrollToTopEvent by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(scrollToTopEvents) {
        scrollToTopEvents.collect { event ->
            if (event != lastScrollToTopEvent) {
                lastScrollToTopEvent = event
                currentListState.scrollToItem(0)
            }
        }
    }

    var selectedMenuItem by remember { mutableStateOf<YTItem?>(null) }
    var showSpeedDialSurpriseMenu by remember { mutableStateOf(false) }
    var showCreateSpaceSheet by remember { mutableStateOf(false) }
    var createSpaceName by remember { mutableStateOf("") }
    var showSpaceOptionsSheet by remember { mutableStateOf(false) }
    var spaceOptionsTarget by remember { mutableStateOf<HomeSpace?>(null) }
    var showEditSpaceSheet by remember { mutableStateOf(false) }
    var editSpaceTarget by remember { mutableStateOf<HomeSpace?>(null) }
    var expandLastFmKeyInEditor by remember { mutableStateOf(false) }
    var spaceToDelete by remember { mutableStateOf<HomeSpace?>(null) }
    var lastFmConfigVersion by remember { mutableLongStateOf(0L) }
    val lastFmConfigured = remember(lastFmConfigVersion) { LastFM.apiKeyConfigured() }
    val lastFmKeyInvalid =
        remember(lastFmConfigVersion, uiState.isLoading, uiState.isRefreshing, uiState.data?.size) {
            LastFM.hasInvalidApiKey()
        }
    val authManager = remember { AuthManager() }
    var isRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(activeSpaceId) { isRefreshing = false }
    val showTopRefreshIndicator = isRefreshing || uiState.isRefreshing
    val isTv = LocalIsTv.current
    var showFloatingActions by remember { mutableStateOf(true) }
    val fabNestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        when {
                            available.y < -1f -> showFloatingActions = false
                            available.y > 1f -> showFloatingActions = true
                        }
                    }
                    return Offset.Zero
                }
            }
        }

    val selectedChip = uiState.selectedChip

    val miniPlayerInset = LocalMiniPlayerBottomInset.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val enablePullToRefresh = !isTv
    val topPadding = tabContentTopPadding()

    val initialTvFocusRequester = remember { FocusRequester() }
    RequestTvFocus(initialTvFocusRequester, enabled = rootActive && !showTopRefreshIndicator)

    fun shuffleHomeContent() {
        if (playerConnection == null) {
            Toast.makeText(context, playerNotReadyText, Toast.LENGTH_SHORT).show()
            return
        }
        val songs = uiState.loadedHomeSongs().filter(isHomeItemEnabled)
        if (songs.isEmpty()) {
            Toast.makeText(context, noPlayableItemsText, Toast.LENGTH_SHORT).show()
            return
        }
        playerConnection.shuffleQueue(
            songs,
            queueTitle = shuffleText,
        )
    }

    val homeContent: @Composable () -> Unit = {
        val sharedText = homeContentText()
        val onHomeItemClick: (YTItem) -> Unit = { item ->
            handleItemClick(
                item,
                onNavigateToAlbum,
                onNavigateToArtist,
                onNavigateToPlaylist,
                onNavigateToPodcast,
                playerConnection,
                scope,
                onNavigateToBrowse,
                startVideo = false,
            )
        }
        key(activeSpaceId) {
            HomeContent(
                uiState = uiState,
                shelfCache = activeHome.shelfCache,
                onLoadMore = activeHome::loadMoreSections,
                text = sharedText,
                onVideoItemClick = { item ->
                    when (item) {
                        is SongItem -> playSong(item, playerConnection, scope)
                        is BrowseItem -> onNavigateToVideoBrowse(item.id, item.params, item.title)
                        else -> Unit
                    }
                },
                onItemClick = onHomeItemClick,
                onSpeedDialItemClick = { item ->
                    if (item is SongItem) {
                        playerConnection?.startRadio(item)
                    } else {
                        onHomeItemClick(item)
                    }
                },
                onItemLongClick = { selectedMenuItem = it },
                onShuffleSongs = { songs -> playerConnection?.shuffleQueue(songs, queueTitle = sharedText.shuffle) },
                onPlaySongs = { songs -> playerConnection?.playQueue(songs, queueTitle = sharedText.quickPicks) },
                onLibraryClick = onNavigateToLibrary,
                onLocalShelfClick = onNavigateToHomeSection,
                onArtistClick = { artistId -> onNavigateToArtist(artistId) },
                onPlayAlbum = { album ->
                    playerConnection?.playAlbum(album.id, album.title)
                },
                isItemEnabled = isHomeItemEnabled,
                onShowSurpriseMenu = { showSpeedDialSurpriseMenu = true },
                currentSongId = remember(currentSong) { derivedStateOf { currentSong.value?.id } },
                isPlaying = isPlaying,
                isLandscape = isLandscape,
                speedDialSurpriseAtEndOfFirstPage = true,
                listState = listState,
                contentPadding =
                    PaddingValues(
                        top = topPadding,
                        bottom = miniPlayerInset + 16.dp,
                    ),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            startupPresentation?.homeLaidOut = true
                        }.focusGroup()
                        .focusRestorer(),
                header = {
                    if (isTv) {
                        item(key = "tv_refresh") {
                            Button(
                                onClick = {
                                    isRefreshing = true
                                    activeHome.refresh { isRefreshing = false }
                                },
                                enabled = rootActive && !showTopRefreshIndicator,
                                modifier =
                                    Modifier
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .focusRequester(initialTvFocusRequester)
                                        .tvFocusHighlight(),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(if (showTopRefreshIndicator) Res.string.refreshing else Res.string.refresh))
                            }
                        }
                    }
                    item(key = "home_spaces_bar") {
                        HomeSpacesBar(
                            spaces = spaces,
                            activeSpaceId = activeSpaceId,
                            onSelectSpace = viewModel::selectSpace,
                            onCreateSpaceClick = {
                                createSpaceName = viewModel.nextDefaultSpaceName(defaultSpacePrefix)
                                expandLastFmKeyInEditor = false
                                showCreateSpaceSheet = true
                            },
                            onSpaceOptionsClick = { space ->
                                spaceOptionsTarget = space
                                showSpaceOptionsSheet = true
                            },
                            scrollState = spacesScrollState,
                        )
                    }
                    if (activeSpace?.hasFilters == true) {
                        item(key = "home_space_active_filters") {
                            SpaceActiveFiltersBar(
                                space = activeSpace,
                                lastFmConfigured = lastFmConfigured,
                                lastFmKeyInvalid = lastFmKeyInvalid,
                                onEditFiltersClick = {
                                    editSpaceTarget = activeSpace
                                    expandLastFmKeyInEditor = false
                                    showEditSpaceSheet = true
                                },
                                onConfigureLastFmKeyClick = {
                                    editSpaceTarget = activeSpace
                                    expandLastFmKeyInEditor = true
                                    showEditSpaceSheet = true
                                },
                            )
                        }
                    }
                    when {
                        uiState.showLoadingPlaceholder -> {
                            item(key = "home_loading") {
                                HomeLoadingPlaceholder(isLandscape = isLandscape)
                            }
                        }

                        isOffline && uiState.hasNoHomeContent() -> {
                            item(key = "home_offline") {
                                Text(stringResource(Res.string.home_offline_empty), modifier = Modifier.padding(24.dp))
                            }
                        }

                        !isOffline && !uiState.error.isNullOrBlank() -> {
                            item {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                ) {
                                    Text(stringResource(Res.string.error_occurred))
                                    ErrorBox(stringResource(Res.string.home_load_failed))
                                    TextButton(onClick = { activeHome.refresh {} }, shapes = ButtonDefaults.shapes()) {
                                        Text(stringResource(Res.string.try_again))
                                    }
                                }
                            }
                        }

                        activeSpace?.hasFilters == true && uiState.hasNoHomeContent() -> {
                            item(key = "space_filtered_empty") {
                                SpaceFilteredEmptyState(
                                    space = activeSpace,
                                    onEditFiltersClick = {
                                        editSpaceTarget = activeSpace
                                        expandLastFmKeyInEditor = false
                                        showEditSpaceSheet = true
                                    },
                                    onRetryClick = { activeHome.refresh {} },
                                )
                            }
                        }

                        activeSpace?.hasFilters == true -> {
                            // Filtered Spaces use their own language/genre filter bar instead of global mood chips.
                        }

                        else -> {
                            item {
                                ChipShelf(
                                    options = uiState.chips,
                                    selectedOption = selectedChip,
                                    onSelectionChange = activeHome::toggleChip,
                                    label = { it.title },
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                            }
                            item(key = "wrapped_card") {
                                WrappedHomeCard(
                                    onClick = onNavigateToWrapped,
                                    onDismissed = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().nestedScroll(fabNestedScrollConnection),
    ) {
        if (enablePullToRefresh) {
            val pullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    activeHome.refresh { isRefreshing = false }
                },
                state = pullToRefreshState,
                indicator = {
                    RefreshIndicator(
                        state = pullToRefreshState,
                        isRefreshing = isRefreshing,
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = topPadding),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                homeContent()
            }
        } else {
            homeContent()
        }

        if (!isTv) {
            AnimatedVisibility(
                visible = (LocalIsTv.current || showFloatingActions),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            bottom = floatingActionsBottomInset.value,
                        ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SmallFloatingActionButton(
                        onClick = onNavigateToRecognition,
                        shape = RoundedCornerShape(8.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Outlined.AudioCapture,
                            contentDescription = stringResource(Res.string.recognize_music),
                        )
                    }
                    FloatingActionButton(
                        onClick = ::shuffleHomeContent,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Rounded.Shuffle,
                            contentDescription = stringResource(Res.string.shuffle),
                        )
                    }
                }
            }
        }
    }

    HomeItemMenu(
        selectedItem = selectedMenuItem,
        onDismiss = { selectedMenuItem = null },
        onNavigateToAlbum = onNavigateToAlbum,
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToPodcast = onNavigateToPodcast,
        onSongMetadataUpdated = activeHome::updateSongMetadata,
        active = rootActive,
    )

    PopupVisibility(visible = rootActive && showSpeedDialSurpriseMenu) {
        HomeSpeedDialSurpriseMenu(
            isPinned = uiState.speedDialSurprisePinned,
            onDismiss = { showSpeedDialSurpriseMenu = false },
            onSetPinned = activeHome::setSpeedDialSurprisePinned,
        )
    }

    PopupVisibility(
        visible = rootActive && showSpaceOptionsSheet && spaceOptionsTarget != null,
        onHidden = { spaceOptionsTarget = null },
    ) {
        spaceOptionsTarget?.let { space ->
            SpaceOptionsSheet(
                space = space,
                onDismiss = { showSpaceOptionsSheet = false },
                onEditClick = {
                    editSpaceTarget = space
                    expandLastFmKeyInEditor = false
                    showEditSpaceSheet = true
                },
                onDeleteClick = { spaceToDelete = space },
            )
        }
    }

    PopupVisibility(visible = rootActive && showCreateSpaceSheet) {
        SpaceEditorSheet(
            title = stringResource(Res.string.space_create_title),
            confirmLabel = stringResource(Res.string.create),
            initialName = createSpaceName,
            initialLanguages = emptyList(),
            initialGenres = emptyList(),
            allowBlankName = true,
            expandLastFmKeyInitially = expandLastFmKeyInEditor,
            onSaveLastFmApiKey = { key ->
                viewModel.saveLastFmApiKey(key)
                lastFmConfigVersion++
            },
            onDismiss = { showCreateSpaceSheet = false },
            onConfirm = { name, languages, genres ->
                viewModel.createSpace(
                    name = name,
                    defaultPrefix = defaultSpacePrefix,
                    languages = languages,
                    genres = genres,
                    shelfTitles = spaceShelfTitles,
                )
                showCreateSpaceSheet = false
            },
        )
    }

    PopupVisibility(
        visible = rootActive && showEditSpaceSheet && editSpaceTarget != null,
        onHidden = {
            editSpaceTarget = null
            expandLastFmKeyInEditor = false
        },
    ) {
        editSpaceTarget?.let { space ->
            SpaceEditorSheet(
                title = stringResource(Res.string.space_edit_title),
                confirmLabel = stringResource(Res.string.save),
                initialName = space.name,
                initialLanguages = space.languages,
                initialGenres = space.genres,
                allowBlankName = false,
                expandLastFmKeyInitially = expandLastFmKeyInEditor,
                onSaveLastFmApiKey = { key ->
                    viewModel.saveLastFmApiKey(key)
                    lastFmConfigVersion++
                },
                onDismiss = { showEditSpaceSheet = false },
                onConfirm = { name, languages, genres ->
                    viewModel.updateSpace(
                        spaceId = space.id,
                        newName = name,
                        languages = languages,
                        genres = genres,
                    )
                    showEditSpaceSheet = false
                },
            )
        }
    }

    if (rootActive) {
        spaceToDelete?.let { space ->
            DeleteSpaceDialog(
                space = space,
                onDismiss = { spaceToDelete = null },
                onConfirm = {
                    viewModel.deleteSpace(space.id)
                    spaceToDelete = null
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeSpeedDialSurpriseMenu(
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(Res.string.shuffle),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ListItem(
                leadingContent = {
                    Icon(
                        if (isPinned) MetrolistIcons.Outlined.KeepOff else MetrolistIcons.Filled.PushPin,
                        contentDescription = null,
                    )
                },
                modifier =
                    Modifier.clickable {
                        onSetPinned(!isPinned)
                        onDismiss()
                    },
            ) {
                Text(stringResource(if (isPinned) Res.string.unpin_from_speed_dial else Res.string.pin_to_speed_dial))
            }
        }
    }
}
