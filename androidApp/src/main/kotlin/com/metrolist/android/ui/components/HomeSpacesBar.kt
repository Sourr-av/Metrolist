/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package com.metrolist.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_cannot_be_undone
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.space_add_custom_filter
import com.metrolist.shared.generated.resources.space_delete_title
import com.metrolist.shared.generated.resources.space_edit_action
import com.metrolist.shared.generated.resources.space_edit_filters
import com.metrolist.shared.generated.resources.space_filtered_empty_body
import com.metrolist.shared.generated.resources.space_filtered_empty_title
import com.metrolist.shared.generated.resources.space_filters_note
import com.metrolist.shared.generated.resources.space_genres_hint
import com.metrolist.shared.generated.resources.space_genres_label
import com.metrolist.shared.generated.resources.space_languages_hint
import com.metrolist.shared.generated.resources.space_languages_label
import com.metrolist.shared.generated.resources.space_lastfm_key_invalid
import com.metrolist.shared.generated.resources.space_lastfm_key_label
import com.metrolist.shared.generated.resources.space_lastfm_key_missing
import com.metrolist.shared.generated.resources.space_lastfm_key_save
import com.metrolist.shared.generated.resources.space_name_label
import com.metrolist.shared.generated.resources.space_new
import com.metrolist.shared.generated.resources.space_options_content_description
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.settings.HomeSpace
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SpaceFilterSpec
import com.metrolist.shared.spaces.SpaceFilterTaxonomy
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

private val SpaceChipHeight = 34.dp

@Composable
fun HomeSpacesBar(
    spaces: List<HomeSpace>,
    activeSpaceId: String?,
    onSelectSpace: (String?) -> Unit,
    onCreateSpaceClick: () -> Unit,
    onSpaceOptionsClick: (HomeSpace) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    scrollState: ScrollState = rememberScrollState(),
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clipToBounds()
                .focusGroup()
                .selectableGroup()
                .horizontalScroll(scrollState)
                .padding(contentPadding)
                .padding(top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpaceSwitcherChip(
            label = stringResource(Res.string.home),
            selected = activeSpaceId == null,
            leadingIcon = {
                Icon(
                    imageVector = if (activeSpaceId == null) MetrolistIcons.Filled.Home else MetrolistIcons.Outlined.Home,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            onClick = { onSelectSpace(null) },
        )

        spaces.forEach { space ->
            val selected = activeSpaceId == space.id
            SpaceSwitcherChip(
                label = space.name,
                selected = selected,
                leadingIcon =
                    if (space.hasFilters) {
                        {
                            Icon(
                                imageVector = MetrolistIcons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    } else {
                        null
                    },
                trailingIcon =
                    if (selected) {
                        {
                            Icon(
                                imageVector = MetrolistIcons.Outlined.MoreVert,
                                contentDescription = stringResource(Res.string.space_options_content_description),
                                modifier =
                                    Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(50))
                                        .clickable(role = Role.Button) { onSpaceOptionsClick(space) },
                            )
                        }
                    } else {
                        null
                    },
                onClick = { onSelectSpace(space.id) },
                onLongClick = { onSpaceOptionsClick(space) },
            )
        }

        val addShape = RoundedCornerShape(50)
        Surface(
            shape = addShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .height(SpaceChipHeight)
                    .clip(addShape)
                    .tvFocusHighlight(addShape)
                    .clickable(role = Role.Button, onClick = onCreateSpaceClick),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MetrolistIcons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(Res.string.space_new),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SpaceActiveFiltersBar(
    space: HomeSpace,
    lastFmConfigured: Boolean,
    lastFmKeyInvalid: Boolean = false,
    onEditFiltersClick: () -> Unit,
    onConfigureLastFmKeyClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
) {
    val scrollState = rememberScrollState()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clipToBounds()
                .horizontalScroll(scrollState)
                .padding(contentPadding)
                .padding(top = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        space.languages.forEach { langId ->
            FilterBadgePill(
                label = SpaceFilterTaxonomy.languageDisplayName(langId),
                icon = MetrolistIcons.Outlined.Language,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onEditFiltersClick,
            )
        }
        space.genres.forEach { genreId ->
            FilterBadgePill(
                label = SpaceFilterTaxonomy.genreDisplayName(genreId),
                icon = MetrolistIcons.Outlined.MusicNote,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = onEditFiltersClick,
            )
        }
        FilterBadgePill(
            label = stringResource(Res.string.space_edit_filters),
            icon = MetrolistIcons.Outlined.Tune,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onEditFiltersClick,
        )
        if (!lastFmConfigured || lastFmKeyInvalid) {
            FilterBadgePill(
                label =
                    stringResource(
                        if (lastFmKeyInvalid) Res.string.space_lastfm_key_invalid else Res.string.space_lastfm_key_missing,
                    ),
                icon = MetrolistIcons.Outlined.Warning,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onConfigureLastFmKeyClick,
            )
        }
    }
}

@Composable
fun SpaceFilteredEmptyState(
    space: HomeSpace,
    onEditFiltersClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.space_filtered_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val summary = SpaceFilterTaxonomy.formatFilterSummary(space.filterSpec)
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(Res.string.space_filtered_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onEditFiltersClick, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.space_edit_filters))
                }
                TextButton(onClick = onRetryClick, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.try_again))
                }
            }
        }
    }
}

@Composable
private fun FilterBadgePill(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier =
            Modifier
                .height(28.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpaceSwitcherChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val shape = if (selected) RoundedCornerShape(10.dp) else RoundedCornerShape(50)
    val containerColor =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier =
            Modifier
                .height(SpaceChipHeight)
                .clip(shape)
                .tvFocusHighlight(shape)
                .semantics { this.selected = selected }
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
    ) {
        Row(
            modifier =
                Modifier.padding(
                    start = 12.dp,
                    end = if (trailingIcon != null) 8.dp else 12.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailingIcon?.invoke()
        }
    }
}

@Composable
fun SpaceOptionsSheet(
    space: HomeSpace,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = space.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (space.hasFilters) {
                Text(
                    text = SpaceFilterTaxonomy.formatFilterSummary(space.filterSpec),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            ListItem(
                leadingContent = { Icon(MetrolistIcons.Outlined.Edit, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        onDismiss()
                        onEditClick()
                    },
            ) {
                Text(stringResource(Res.string.space_edit_action))
            }
            ListItem(
                leadingContent = { Icon(MetrolistIcons.Outlined.Delete, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        onDismiss()
                        onDeleteClick()
                    },
            ) {
                Text(stringResource(Res.string.delete))
            }
        }
    }
}

@Composable
fun SpaceEditorSheet(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialLanguages: List<String>,
    initialGenres: List<String>,
    allowBlankName: Boolean,
    expandLastFmKeyInitially: Boolean = false,
    onSaveLastFmApiKey: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, languages: List<String>, genres: List<String>) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var nameManuallyEdited by remember(initialName) { mutableStateOf(!allowBlankName) }
    var selectedLanguages by remember(initialLanguages) { mutableStateOf(initialLanguages) }
    var selectedGenres by remember(initialGenres) { mutableStateOf(initialGenres) }
    var languageQuery by remember { mutableStateOf("") }
    var genreQuery by remember { mutableStateOf("") }
    var lastFmConfigured by remember { mutableStateOf(LastFM.apiKeyConfigured()) }
    var showLastFmInput by remember(expandLastFmKeyInitially) { mutableStateOf(expandLastFmKeyInitially || !LastFM.apiKeyConfigured()) }
    var lastFmKeyInput by remember { mutableStateOf(Settings.shared.spacesLastFmApiKey) }

    val canConfirm = allowBlankName || name.isNotBlank()

    fun syncSuggestedName(
        languages: List<String>,
        genres: List<String>,
    ) {
        if (!nameManuallyEdited && allowBlankName) {
            val summary = SpaceFilterTaxonomy.formatFilterSummary(SpaceFilterSpec(languages, genres))
            name = summary.ifBlank { initialName }
        }
    }

    fun addLanguage(idOrCustom: String) {
        val cleaned = idOrCustom.trim()
        if (cleaned.isBlank()) return
        if (selectedLanguages.none { it.equals(cleaned, ignoreCase = true) }) {
            val updated = selectedLanguages + cleaned
            selectedLanguages = updated
            syncSuggestedName(updated, selectedGenres)
        }
        languageQuery = ""
    }

    fun removeLanguage(idOrCustom: String) {
        val updated = selectedLanguages.filterNot { it.equals(idOrCustom, ignoreCase = true) }
        selectedLanguages = updated
        syncSuggestedName(updated, selectedGenres)
    }

    fun addGenre(idOrCustom: String) {
        val cleaned = idOrCustom.trim()
        if (cleaned.isBlank()) return
        if (selectedGenres.none { it.equals(cleaned, ignoreCase = true) }) {
            val updated = selectedGenres + cleaned
            selectedGenres = updated
            syncSuggestedName(selectedLanguages, updated)
        }
        genreQuery = ""
    }

    fun removeGenre(idOrCustom: String) {
        val updated = selectedGenres.filterNot { it.equals(idOrCustom, ignoreCase = true) }
        selectedGenres = updated
        syncSuggestedName(selectedLanguages, updated)
    }

    val languageSuggestions =
        remember(languageQuery, selectedLanguages) {
            SpaceFilterTaxonomy
                .searchLanguages(languageQuery)
                .filterNot { opt -> selectedLanguages.any { it.equals(opt.id, ignoreCase = true) } }
                .take(8)
        }

    val genreSuggestions =
        remember(genreQuery, selectedGenres) {
            SpaceFilterTaxonomy
                .searchGenres(genreQuery)
                .filterNot { opt -> selectedGenres.any { it.equals(opt.id, ignoreCase = true) } }
                .take(10)
        }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Button(
                        onClick = {
                            if (canConfirm) {
                                val resolvedName =
                                    name.trim().ifBlank {
                                        SpaceFilterTaxonomy
                                            .formatFilterSummary(SpaceFilterSpec(selectedLanguages, selectedGenres))
                                            .ifBlank { initialName }
                                    }
                                onConfirm(resolvedName, selectedLanguages, selectedGenres)
                            }
                        },
                        enabled = canConfirm,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(confirmLabel)
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameManuallyEdited = true
                },
                label = { Text(stringResource(Res.string.space_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            // --- Languages (priority) ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(Res.string.space_languages_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                if (selectedLanguages.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        selectedLanguages.forEach { langId ->
                            SelectedFilterChip(
                                label = SpaceFilterTaxonomy.languageDisplayName(langId),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                onRemove = { removeLanguage(langId) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = languageQuery,
                    onValueChange = { languageQuery = it },
                    placeholder = { Text(stringResource(Res.string.space_languages_hint)) },
                    leadingIcon = { Icon(MetrolistIcons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon =
                        if (languageQuery.isNotBlank()) {
                            {
                                IconButton(onClick = { languageQuery = "" }) {
                                    Icon(MetrolistIcons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        } else {
                            null
                        },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val trimmedLangQuery = languageQuery.trim()
                val showCustomLang =
                    trimmedLangQuery.length >= 2 &&
                        languageSuggestions.none {
                            it.displayName.equals(trimmedLangQuery, ignoreCase = true) ||
                                it.id.equals(trimmedLangQuery, ignoreCase = true)
                        } &&
                        selectedLanguages.none { it.equals(trimmedLangQuery, ignoreCase = true) }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    languageSuggestions.forEach { option ->
                        SuggestionChipPill(
                            label = option.displayName,
                            onClick = { addLanguage(option.id) },
                        )
                    }
                    if (showCustomLang) {
                        SuggestionChipPill(
                            label = stringResource(Res.string.space_add_custom_filter, trimmedLangQuery),
                            highlighted = true,
                            onClick = { addLanguage(trimmedLangQuery) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // --- Genres (with hierarchical sub-options like Soundtracks -> All / Video Game / Movie) ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(Res.string.space_genres_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                if (selectedGenres.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        selectedGenres.forEach { genreId ->
                            SelectedFilterChip(
                                label = SpaceFilterTaxonomy.genreDisplayName(genreId),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                onRemove = { removeGenre(genreId) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = genreQuery,
                    onValueChange = { genreQuery = it },
                    placeholder = { Text(stringResource(Res.string.space_genres_hint)) },
                    leadingIcon = { Icon(MetrolistIcons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon =
                        if (genreQuery.isNotBlank()) {
                            {
                                IconButton(onClick = { genreQuery = "" }) {
                                    Icon(MetrolistIcons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        } else {
                            null
                        },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val trimmedGenreQuery = genreQuery.trim()
                val showCustomGenre =
                    trimmedGenreQuery.length >= 2 &&
                        genreSuggestions.none {
                            it.displayName.equals(trimmedGenreQuery, ignoreCase = true) ||
                                it.displayName.substringBefore(" (").equals(trimmedGenreQuery, ignoreCase = true) ||
                                it.id.equals(trimmedGenreQuery, ignoreCase = true)
                        } &&
                        selectedGenres.none { it.equals(trimmedGenreQuery, ignoreCase = true) }

                if (trimmedGenreQuery.isNotBlank()) {
                    // Structured option list when typing so broad genres like "soundtracks" clearly show
                    // all grouped choices (e.g. Soundtracks (All), Video Game Soundtracks, Movie Soundtracks).
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            genreSuggestions.forEachIndexed { index, option ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { addGenre(option.id) }
                                            .padding(
                                                start = if (option.parentId != null) 24.dp else 14.dp,
                                                end = 14.dp,
                                                top = 10.dp,
                                                bottom = 10.dp,
                                            ),
                                ) {
                                    Text(
                                        text = option.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    option.description?.let { desc ->
                                        Text(
                                            text = desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (showCustomGenre) {
                                if (genreSuggestions.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                }
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { addGenre(trimmedGenreQuery) }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = MetrolistIcons.Filled.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(Res.string.space_add_custom_filter, trimmedGenreQuery),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        genreSuggestions.forEach { option ->
                            SuggestionChipPill(
                                label = option.displayName.substringBefore(" ("),
                                onClick = { addGenre(option.id) },
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(Res.string.space_filters_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- Optional inline Last.fm API key entry when not configured or rejected by Last.fm ---
            val lastFmInvalid = LastFM.hasInvalidApiKey()
            if (!lastFmConfigured || lastFmInvalid || showLastFmInput) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color =
                        if (lastFmInvalid) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (lastFmInvalid) MetrolistIcons.Outlined.Warning else MetrolistIcons.Outlined.Key,
                                contentDescription = null,
                                tint = if (lastFmInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text =
                                    stringResource(
                                        if (lastFmInvalid) Res.string.space_lastfm_key_invalid else Res.string.space_lastfm_key_missing,
                                    ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = lastFmKeyInput,
                                onValueChange = { lastFmKeyInput = it },
                                label = { Text(stringResource(Res.string.space_lastfm_key_label)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    onSaveLastFmApiKey(lastFmKeyInput)
                                    lastFmConfigured = LastFM.apiKeyConfigured()
                                    if (lastFmConfigured) showLastFmInput = false
                                },
                                enabled = lastFmKeyInput.isNotBlank(),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(Res.string.space_lastfm_key_save))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SelectedFilterChip(
    label: String,
    containerColor: Color,
    contentColor: Color,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier =
            Modifier
                .height(30.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onRemove),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Icon(
                imageVector = MetrolistIcons.Outlined.Close,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun SuggestionChipPill(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .height(30.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MetrolistIcons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun DeleteSpaceDialog(
    space: HomeSpace,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.space_delete_title)) },
        text = { Text("${space.name}\n\n${stringResource(Res.string.action_cannot_be_undone)}") },
        confirmButton = {
            TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
