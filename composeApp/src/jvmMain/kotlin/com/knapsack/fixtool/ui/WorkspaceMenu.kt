package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * **The workspace switcher, where the app's name used to sit.**
 *
 * These items lived in Quick Connect ▾ for one release-that-never-shipped, and they were in the wrong
 * menu. Quick Connect is a *session* control — pick a profile, it connects — and opening a workspace
 * changes which profiles exist at all. One dropdown mixing "connect this" with "replace everything"
 * asks the reader to hold two scopes at once.
 *
 * So it is where every editor puts the project: top left, showing what is open, click to change it.
 * The name is always shown, including `Default`, because "which workspace am I in" should never need
 * a menu opened to answer.
 */
@Composable
fun WorkspaceMenu(
    state: WorkspaceMenuState,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(Page.ROOT) }
    val close = {
        expanded = false
        page = Page.ROOT
    }
    val hasActions = state.onNew != null || state.onBrowse != null

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clickable(enabled = hasActions) { expanded = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("workspace-menu"),
        ) {
            Text(text = "FixTool", color = AppTheme.Colors.text, fontSize = 14.sp)
            if (state.name.isNotBlank()) {
                Text(
                    text = " · ${state.name}",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.testTag("workspace-menu-name"),
                )
            }
            if (hasActions) {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Workspace",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = close,
            modifier = Modifier.background(AppTheme.Colors.surface).widthIn(min = 220.dp),
        ) {
            when (page) {
                Page.OPEN -> OpenPage(state, close) { page = Page.ROOT }
                Page.RECENT -> RecentPage(state, close) { page = Page.ROOT }
                Page.ROOT -> RootPage(state, close) { page = it }
            }
        }
    }
}

private enum class Page { ROOT, OPEN, RECENT }

@Composable
private fun RootPage(
    state: WorkspaceMenuState,
    close: () -> Unit,
    goTo: (Page) -> Unit,
) {
    Item(text = "Workspace: ${state.name}", enabled = false, testTag = "workspace-current") {}
    HorizontalDivider(
        color = AppTheme.Separators.color,
        thickness = AppTheme.Separators.dividerThickness,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    state.onNew?.let { onNew ->
        Item(text = "New workspace…", testTag = "workspace-new") {
            close()
            onNew()
        }
    }
    if (state.onBrowse != null) {
        Item(text = "Open workspace", trailing = true, testTag = "workspace-open") { goTo(Page.OPEN) }
    }
    if (state.recents.isNotEmpty() && state.onOpenRecent != null) {
        Item(text = "Recent workspaces", trailing = true, testTag = "workspace-recent") { goTo(Page.RECENT) }
    }
    // Absent rather than disabled on Default: Close is what RETURNS you there, so on Default it is not
    // an action that is unavailable, it is an action that has already happened.
    if (!state.isDefault && state.onClose != null) {
        Item(text = "Close workspace", testTag = "workspace-close") {
            close()
            state.onClose.invoke()
        }
    }
}

@Composable
private fun OpenPage(
    state: WorkspaceMenuState,
    close: () -> Unit,
    back: () -> Unit,
) {
    Back(label = "Open workspace", testTag = "workspace-open-back", onClick = back)
    state.onBrowse?.let { onBrowse ->
        Item(text = "Browse…", testTag = "workspace-browse") {
            close()
            onBrowse()
        }
    }
    if (state.examples.isNotEmpty()) {
        HorizontalDivider(
            color = AppTheme.Separators.color,
            thickness = AppTheme.Separators.dividerThickness,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        state.examples.forEach { example ->
            TwoLineItem(
                title = example.displayName,
                subtitle = example.note,
                testTag = "workspace-example-${example.id}",
            ) {
                close()
                state.onOpenExample?.invoke(example.id)
            }
        }
    }
}

@Composable
private fun RecentPage(
    state: WorkspaceMenuState,
    close: () -> Unit,
    back: () -> Unit,
) {
    Back(label = "Recent workspaces", testTag = "workspace-recent-back", onClick = back)
    state.recents.forEach { workspace ->
        TwoLineItem(
            title = workspace.name,
            // The workspace's own path, not its parent. Workspaces default to one `workspaces/` folder,
            // so a parent is the same string for every entry and two siblings read as duplicates.
            subtitle = shortPath(workspace),
            testTag = "workspace-recent-${workspace.name}",
        ) {
            close()
            state.onOpenRecent?.invoke(workspace)
        }
    }
}

@Composable
private fun Item(
    text: String,
    testTag: String,
    enabled: Boolean = true,
    trailing: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = if (enabled) AppTheme.Colors.text else AppTheme.Colors.textDisabled,
                fontSize = if (enabled) 11.sp else 10.sp,
            )
        },
        trailingIcon = {
            if (trailing) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

@Composable
private fun TwoLineItem(
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(text = title, color = AppTheme.Colors.text, fontSize = 11.sp)
                Text(text = subtitle, color = AppTheme.Colors.textDisabled, fontSize = 9.sp, maxLines = 1)
            }
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

@Composable
private fun Back(
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = label, color = AppTheme.Colors.textSecondary, fontSize = 11.sp) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = "Back",
                tint = AppTheme.Colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

/**
 * Everything the switcher shows and every way it can be used.
 *
 * One object rather than nine parameters threaded through [Toolbar], which has enough of those.
 */
data class WorkspaceMenuState(
    val name: String = "",
    /** The installation's own directory is open, so there is nothing to close. */
    val isDefault: Boolean = true,
    val recents: List<File> = emptyList(),
    /** Bundled examples. Open offers these below Browse. */
    val examples: List<ExampleEntry> = emptyList(),
    val onNew: (() -> Unit)? = null,
    val onBrowse: (() -> Unit)? = null,
    val onOpenExample: ((String) -> Unit)? = null,
    val onOpenRecent: ((File) -> Unit)? = null,
    val onClose: (() -> Unit)? = null,
)

/** `~/.fixtool/workspaces/fx-venue`, because a home directory is the same on every line. */
internal fun shortPath(file: File): String {
    val home = System.getProperty("user.home").orEmpty()
    val path = file.absolutePath
    return if (home.isNotBlank() && path.startsWith(home)) "~" + path.removePrefix(home) else path
}

/**
 * A bundled example as the switcher shows it.
 *
 * [note] carries where it will land and whether it is already there, because Open is idempotent:
 * someone who has opened the FX venue before is returning to their copy, not being handed a new one,
 * and the menu is the only place to say so before they click.
 */
data class ExampleEntry(
    val id: String,
    val displayName: String,
    val note: String,
)
