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
import com.knapsack.fixtool.service.ExampleWorkspaces
import kotlinx.coroutines.launch
import java.io.File

/**
 * A new, empty workspace: what to call it and where to put it.
 *
 * Two questions, both answered before the dialog opens, so Enter is the whole interaction. There is
 * no third question about the FIX version, and that is a correction rather than a simplification: the
 * field used to be here and it was theatre. A loaded data dictionary overrides a profile's
 * `beginString` at connect time, and one is essentially always loaded, so picking 4.2 here produced a
 * 4.4 session. The note at the bottom says what the sessions will actually speak and points at the
 * setting that decides it.
 *
 * Starting from the bundled example is **not** here either. That is Open's job — an example is one of
 * the things Open can open, not a kind of workspace this dialog has to know about.
 */
@Composable
fun NewWorkspaceDialog(
    defaultLocation: File,
    /** What the sessions in this workspace will speak, and where that is decided. Blank hides the note. */
    wireVersionNote: String = "",
    onDismiss: () -> Unit,
    onCreate: (name: String, location: File) -> Unit,
) {
    var name by remember { mutableStateOf("Workspace") }
    var location by remember(defaultLocation) { mutableStateOf(defaultLocation.absolutePath) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    val valid = name.isNotBlank() && location.isNotBlank()

    fun create() {
        if (valid) {
            onCreate(name.trim(), File(location))
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(6.dp), color = AppTheme.Colors.surface) {
            NewWorkspaceForm(
                defaultLocation = defaultLocation,
                wireVersionNote = wireVersionNote,
                name = name,
                onNameChange = { name = it },
                location = location,
                onLocationChange = { location = it },
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
                onCreate = ::create,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun NewWorkspaceForm(
    defaultLocation: File,
    wireVersionNote: String,
    name: String,
    onNameChange: (String) -> Unit,
    location: String,
    onLocationChange: (String) -> Unit,
    nameFocus: FocusRequester,
    valid: Boolean,
    onBrowse: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .width(460.dp)
                .padding(14.dp)
                .testTag("new-workspace-dialog")
                // Enter accepts the defaults from anywhere in the dialog, including the moment it
                // opens, so the fast path is one keystroke and needs no tabbing to a button first.
                .onPreviewKeyEvent { event ->
                    val entered = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (event.type == KeyEventType.KeyDown && entered) {
                        onCreate()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Text("New workspace", color = AppTheme.Colors.text, fontSize = 13.sp)
        Text(
            "A folder holding its own profiles, saved messages, scenarios and session store. " +
                "The dictionary, the window layout and your settings stay with the installation.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 10.sp,
        )

        LabelledRow("Name") {
            SlimField(
                value = name,
                onValueChange = onNameChange,
                placeholder = "Workspace",
                modifier = Modifier.weight(1f).focusRequester(nameFocus).testTag("new-workspace-name"),
            )
        }

        LocationRow(
            location = location,
            onLocationChange = onLocationChange,
            defaultLocation = defaultLocation,
            onBrowse = onBrowse,
        )

        Text(
            "Goes in ${File(location, ExampleWorkspaces.slug(name)).absolutePath}",
            color = AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
            modifier = Modifier.testTag("new-workspace-target"),
        )
        if (wireVersionNote.isNotBlank()) {
            Text(
                text = wireVersionNote,
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                modifier = Modifier.testTag("new-workspace-wire-version"),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            SlimButton("Cancel", onClick = onDismiss, color = AppTheme.Colors.textSecondary)
            SlimButton(
                "Create",
                onClick = onCreate,
                color = if (valid) AppTheme.Colors.primary else AppTheme.Colors.textDisabled,
                enabled = valid,
                modifier = Modifier.testTag("new-workspace-create"),
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

/** The location field and the button that fills it from a native folder dialog. */
@Composable
private fun LocationRow(
    location: String,
    onLocationChange: (String) -> Unit,
    defaultLocation: File,
    onBrowse: () -> Unit,
) {
    LabelledRow("Location") {
        SlimField(
            value = location,
            onValueChange = onLocationChange,
            placeholder = defaultLocation.absolutePath,
            modifier = Modifier.weight(1f).testTag("new-workspace-location"),
        )
        TooltipIconButton(
            tooltip = "Choose a folder for the workspace",
            onClick = onBrowse,
            modifier = Modifier.size(24.dp).testTag("new-workspace-browse"),
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Browse",
                tint = AppTheme.Colors.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
