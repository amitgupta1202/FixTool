package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.ExampleWorkspaces
import kotlinx.coroutines.launch
import java.io.File

/**
 * Copy a bundled example into a workspace of your own.
 *
 * Three questions, all of them answered before the dialog opens: what to call it, where to put it,
 * and which FIX version its sessions speak. Enter takes the defaults, which is the path a viewer
 * seeing FixTool for the first time should be able to walk without reading anything — the whole
 * reason the old Start Demo Server button needed no dialog at all.
 *
 * What it is NOT is a second installer. The copy is an ordinary workspace read by the same services
 * that read every other one, so nothing here validates or versions the example beyond the copy
 * succeeding.
 */
@Composable
fun OpenExampleDialog(
    example: ExampleWorkspaces.Example,
    defaultLocation: File,
    onDismiss: () -> Unit,
    onOpen: (name: String, location: File, fixVersion: FixVersion) -> Unit,
) {
    var name by remember(example) { mutableStateOf(example.defaultWorkspaceName) }
    var location by remember(defaultLocation) { mutableStateOf(defaultLocation.absolutePath) }
    var fixVersion by remember { mutableStateOf(FixVersion.DEFAULT) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    val valid = name.isNotBlank() && location.isNotBlank()

    fun open() {
        if (valid) {
            onOpen(name.trim(), File(location), fixVersion)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(6.dp), color = AppTheme.Colors.surface) {
            OpenExampleForm(
                example = example,
                defaultLocation = defaultLocation,
                name = name,
                onNameChange = { name = it },
                location = location,
                onLocationChange = { location = it },
                fixVersion = fixVersion,
                onFixVersionChange = { fixVersion = it },
                nameFocus = focus,
                valid = valid,
                onBrowse = {
                    scope.launch {
                        chooseDirectory(
                            title = "Choose a folder for the workspace",
                            startIn = dialogStartDirectory(location, namesDirectory = true),
                        )?.let { chosen -> location = chosen.absolutePath }
                    }
                },
                onOpen = ::open,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * The dialog's three questions and its two buttons.
 *
 * Separated from [OpenExampleDialog] so the state lives in one place and the layout in another: the
 * dialog owns what the user has typed, and this owns nothing at all.
 */
@Composable
@Suppress("LongParameterList")
private fun OpenExampleForm(
    example: ExampleWorkspaces.Example,
    defaultLocation: File,
    name: String,
    onNameChange: (String) -> Unit,
    location: String,
    onLocationChange: (String) -> Unit,
    fixVersion: FixVersion,
    onFixVersionChange: (FixVersion) -> Unit,
    nameFocus: FocusRequester,
    valid: Boolean,
    onBrowse: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .width(460.dp)
                .padding(14.dp)
                .testTag("open-example-dialog")
                // Enter accepts the defaults from anywhere in the dialog, including the moment it
                // opens, so the fast path is one keystroke and needs no tabbing to a button first.
                .onPreviewKeyEvent { event ->
                    val entered = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (event.type == KeyEventType.KeyDown && entered) {
                        onOpen()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Text("Open example: ${example.displayName}", color = AppTheme.Colors.text, fontSize = 13.sp)
        Text(
            "${example.summary} Copied into a workspace of its own, so what you change is yours " +
                "and there is nothing to uninstall.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 10.sp,
        )

        LabelledRow("Name") {
            SlimField(
                value = name,
                onValueChange = onNameChange,
                placeholder = example.defaultWorkspaceName,
                modifier = Modifier.weight(1f).focusRequester(nameFocus).testTag("open-example-name"),
            )
        }

        LabelledRow("Location") {
            SlimField(
                value = location,
                onValueChange = onLocationChange,
                placeholder = defaultLocation.absolutePath,
                modifier = Modifier.weight(1f).testTag("open-example-location"),
            )
            TooltipIconButton(
                tooltip = "Choose a folder for the workspace",
                onClick = onBrowse,
                modifier = Modifier.size(24.dp).testTag("open-example-browse"),
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Browse",
                    tint = AppTheme.Colors.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        LabelledRow("FIX version") {
            SlimDropdown(
                value = fixVersion,
                options = FixVersion.entries.toList(),
                onValueChange = { onFixVersionChange(it ?: FixVersion.DEFAULT) },
                displayText = { version -> versionLabel(version) },
                modifier = Modifier.weight(1f).testTag("open-example-version"),
            )
        }

        Text(
            "Goes in ${File(location, ExampleWorkspaces.slug(name)).absolutePath}",
            color = AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
            modifier = Modifier.testTag("open-example-target"),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            SlimButton("Cancel", onClick = onDismiss, color = AppTheme.Colors.textSecondary)
            SlimButton(
                "Open",
                onClick = onOpen,
                color = if (valid) AppTheme.Colors.primary else AppTheme.Colors.textDisabled,
                enabled = valid,
                modifier = Modifier.testTag("open-example-confirm"),
            )
        }
    }
}

/** A dialog row: a fixed-width label, then whatever the row is for. */
@Composable
private fun LabelledRow(
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = AppTheme.Colors.textSecondary,
            fontSize = 10.sp,
            modifier = Modifier.width(70.dp),
        )
        content()
    }
}

private fun versionLabel(version: FixVersion): String =
    if (version == FixVersion.DEFAULT) "${version.displayName}  (default)" else version.displayName
