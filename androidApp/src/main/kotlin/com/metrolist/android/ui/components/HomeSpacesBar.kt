/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_cannot_be_undone
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.rename
import com.metrolist.shared.generated.resources.space_delete_title
import com.metrolist.shared.generated.resources.space_name_label
import com.metrolist.shared.generated.resources.space_new
import com.metrolist.shared.generated.resources.space_options_content_description
import com.metrolist.shared.settings.HomeSpace
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
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = space.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ListItem(
                leadingContent = { Icon(MetrolistIcons.Outlined.Edit, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        onDismiss()
                        onRenameClick()
                    },
            ) {
                Text(stringResource(Res.string.rename))
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
fun SpaceNameDialog(
    title: String,
    confirmLabel: String,
    value: String,
    allowBlankConfirm: Boolean,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val canSubmit = allowBlankConfirm || value.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(Res.string.space_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSubmit) onConfirm() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
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
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = space.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(Res.string.action_cannot_be_undone))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
