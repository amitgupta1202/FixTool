package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.Environment
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import java.io.File

enum class ViewMode {
    TABS,
    SPLIT_HORIZONTAL,
    SPLIT_VERTICAL,
}

@Composable
fun Toolbar(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    showMessageEditor: Boolean = false,
    showDetailPanel: Boolean = false,
    showConnectionPanel: Boolean = false,
    showLatencyPanel: Boolean = false,
    showOrderBookPanel: Boolean = false,
    connectionProfiles: List<FixConnectionProfile> = emptyList(),
    isDictionaryValid: Boolean = true,
    globalSessionViewMode: FixMessageSession.ViewMode,
    globalFilterRegex: String = "",
    globalFilterShowIncoming: Boolean = true,
    globalFilterShowOutgoing: Boolean = true,
    hideProtocolTags: Boolean = true,
    groupByConversation: Boolean = false,
    /** The Trace panel is on screen. Independent of following: the Ledger lists every trace. */
    tracePanelOpen: Boolean = false,
    /**
     * The followed trace's label, or null when nothing is followed — the chip's whole condition.
     *
     * A label rather than a flag plus a lookup: the chip's job is to *name* what every pane is narrowed
     * to, because a narrowing nobody can name is the silent-filter defect this feature exists to remove.
     */
    followingLabel: String? = null,
    followingSessionCount: Int = 0,
    followingMessageCount: Int = 0,
    /** Panes whose ring dropped a message of this trace, by title. See `Traces.Trace.truncatedSessions`. */
    followingTruncatedOn: List<String> = emptyList(),
    onUnfollow: (() -> Unit)? = null,
    onOpenMessageEditor: (() -> Unit)? = null,
    onToggleDetailPanel: (() -> Unit)? = null,
    onToggleConnectionPanel: (() -> Unit)? = null,
    onToggleLatencyPanel: (() -> Unit)? = null,
    onToggleOrderBookPanel: (() -> Unit)? = null,
    onToggleGridView: (() -> Unit)? = null,
    onQuickConnect: ((String, FixConnectionProfile) -> Unit)? = null,
    onGetProfileConnectionState: ((String) -> FixConnectionState)? = null,
    /**
     * The workspace's environments. Empty — which is every workspace until someone extracts some —
     * leaves Quick Connect exactly as it was: pick a profile and it connects.
     */
    environments: List<Environment> = emptyList(),
    onConnectProfileIn: ((FixConnectionProfile, Environment) -> Unit)? = null,
    /** A project workspace is open rather than the installation's own directory. Decides Close vs nothing. */
    workspaceOpen: Boolean = false,
    /** The name of the open workspace, shown on the menu's own header row. */
    workspaceName: String? = null,
    /** Copies a bundled example into a workspace and opens it. Null hides the item. */
    onOpenExample: (() -> Unit)? = null,
    /** Opens an existing workspace folder. Null hides the item. */
    onOpenWorkspace: (() -> Unit)? = null,
    onCloseWorkspace: (() -> Unit)? = null,
    /** Workspaces opened before, newest first. Empty hides the submenu. */
    recentWorkspaces: List<File> = emptyList(),
    onOpenRecentWorkspace: ((File) -> Unit)? = null,
    onSearchAllSessions: (() -> Unit)? = null,
    onAddSeparatorToAll: (() -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    onGlobalFilterChange: ((String) -> Unit)? = null,
    onGlobalFilterIncomingChange: ((Boolean) -> Unit)? = null,
    onGlobalFilterOutgoingChange: ((Boolean) -> Unit)? = null,
    onToggleHideProtocolTags: (() -> Unit)? = null,
    onToggleGroupByConversation: (() -> Unit)? = null,
    onToggleTracePanel: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
    onOpenScenarios: (() -> Unit)? = null,
    onCaptureScenario: (() -> Unit)? = null,
    showTerminal: Boolean = false,
    onToggleTerminal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Message editor button (controls left panel)
        if (onOpenMessageEditor != null) {
            TooltipIconButton(
                tooltip = if (showMessageEditor) "Message Editor: On (click to hide)" else "Message Editor: Off (click to show)",
                onClick = onOpenMessageEditor,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = "Message Editor",
                    tint = toggleActiveColor(showMessageEditor, AppTheme.Colors.primary, AppTheme.Colors.textSecondary),
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Scenarios button (repeatable scenarios + assertion results)
        if (onOpenScenarios != null) {
            TooltipIconButton(
                tooltip = "Repeatable Scenarios",
                onClick = onOpenScenarios,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.PlaylistPlay,
                    contentDescription = "Repeatable Scenarios",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = tooltipIconModifier,
                )
            }
        }

        Text(
            text = "FixTool",
            color = AppTheme.Colors.text,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        // The followed trace, named. It sits beside the global filter box rather than replacing it:
        // they are two filters of different kinds and both are in force, so hiding one while the other
        // is on would be the app narrowing a view without saying so.
        if (followingLabel != null) {
            Row(
                modifier =
                    Modifier
                        .height(28.dp)
                        .background(AppTheme.Colors.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("following-chip"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text =
                        buildString {
                            append("Following ").append(followingLabel)
                            append(" · ").append(followingSessionCount).append(" session")
                            if (followingSessionCount != 1) append("s")
                            append(" · ").append(followingMessageCount).append(" message")
                            if (followingMessageCount != 1) append("s")
                            // What a first row cannot say for itself: this exchange opened before what
                            // the pane still holds. Better said here than silently absent.
                            if (followingTruncatedOn.isNotEmpty()) {
                                append(" · history lost on ").append(followingTruncatedOn.joinToString(", "))
                            }
                        },
                    color = AppTheme.Colors.background,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("following-chip-label"),
                )
                Text(
                    text = "✕",
                    color = AppTheme.Colors.background,
                    fontSize = 11.sp,
                    modifier =
                        Modifier
                            .testTag("unfollow-chip")
                            .clickable { onUnfollow?.invoke() },
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }

        // Global Filter Text Field
        if (onGlobalFilterChange != null) {
            Row(
                modifier =
                    Modifier
                        .height(28.dp)
                        .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.FilterAlt,
                    contentDescription = "Filter",
                    tint = if (globalFilterRegex.isNotEmpty()) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                androidx.compose.foundation.text.BasicTextField(
                    value = globalFilterRegex,
                    onValueChange = onGlobalFilterChange,
                    modifier = Modifier.width(180.dp),
                    singleLine = true,
                    textStyle =
                        androidx.compose.ui.text.TextStyle(
                            fontSize = 11.sp,
                            color = AppTheme.Colors.text,
                        ),
                    cursorBrush =
                        androidx.compose.ui.graphics
                            .SolidColor(AppTheme.Colors.primary),
                    decorationBox = { innerTextField ->
                        Box {
                            if (globalFilterRegex.isEmpty()) {
                                Text(
                                    text = "Filter all sessions (regex)...",
                                    fontSize = 11.sp,
                                    color = AppTheme.Colors.textSecondary,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }

        // Global Filter Direction Checkboxes
        if (onGlobalFilterIncomingChange != null && onGlobalFilterOutgoingChange != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Incoming checkbox
                Row(
                    modifier =
                        Modifier
                            .height(28.dp)
                            .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                            .clickable { onGlobalFilterIncomingChange(!globalFilterShowIncoming) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier.size(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = globalFilterShowIncoming,
                            onCheckedChange = onGlobalFilterIncomingChange,
                            modifier = Modifier.scale(0.75f),
                            colors =
                                androidx.compose.material3.CheckboxDefaults.colors(
                                    checkedColor = AppTheme.Colors.primary,
                                    uncheckedColor = AppTheme.Colors.textSecondary,
                                    checkmarkColor = AppTheme.Colors.surface,
                                ),
                        )
                    }
                    Text(
                        text = "In",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.text,
                    )
                }

                // Outgoing checkbox
                Row(
                    modifier =
                        Modifier
                            .height(28.dp)
                            .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                            .clickable { onGlobalFilterOutgoingChange(!globalFilterShowOutgoing) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier.size(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = globalFilterShowOutgoing,
                            onCheckedChange = onGlobalFilterOutgoingChange,
                            modifier = Modifier.scale(0.75f),
                            colors =
                                androidx.compose.material3.CheckboxDefaults.colors(
                                    checkedColor = AppTheme.Colors.primary,
                                    uncheckedColor = AppTheme.Colors.textSecondary,
                                    checkmarkColor = AppTheme.Colors.surface,
                                ),
                        )
                    }
                    Text(
                        text = "Out",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.text,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
        }

        // **Quick Connect ▾ — the profile selector, and the demo workspace's home.**
        //
        // Shown even with no saved profiles, because that is the one moment the trailing item matters: a
        // fresh install has nothing to connect to, and the demo is how it gets something. The item sits
        // after the profiles the way "Edit Configurations…" trails a run-configuration list — the things
        // you can pick, then the thing that makes more of them. Once the workspace is installed its three
        // profiles are ordinary rows above with their own state dots, so there is no second status light
        // here to disagree with them; the item just turns into Stop.
        val workspaceItemsShown = onOpenExample != null || onOpenWorkspace != null
        if ((onQuickConnect != null && connectionProfiles.isNotEmpty()) || workspaceItemsShown) {
            var expanded by remember { mutableStateOf(false) }

            // Recent workspaces are asked for inside the same popup — the list replaces the profiles
            // until Back or a pick — rather than opening a second popup over the first. Reset with the
            // menu so it never reopens on the recent page.
            var pickingRecent by remember { mutableStateOf(false) }
            // Which profile is being asked "in which environment?". Null means the profile list is
            // showing. Reset with the menu, so it never reopens on an environment page.
            var pickingEnvironmentFor by remember { mutableStateOf<FixConnectionProfile?>(null) }
            val close = {
                expanded = false
                pickingRecent = false
                pickingEnvironmentFor = null
            }

            Box {
                Row(
                    modifier =
                        Modifier
                            .height(28.dp)
                            .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("quick-connect"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Quick Connect",
                        tint = AppTheme.Colors.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Quick Connect",
                        color = AppTheme.Colors.text,
                        fontSize = 11.sp,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Dropdown",
                        tint = AppTheme.Colors.text,
                        modifier = Modifier.size(16.dp),
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = close,
                    modifier =
                        Modifier
                            .background(AppTheme.Colors.surface)
                            .widthIn(min = 200.dp),
                ) {
                    val choosingEnvironment = pickingEnvironmentFor
                    if (choosingEnvironment != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${choosingEnvironment.name} in…",
                                    color = AppTheme.Colors.textSecondary,
                                    fontSize = 11.sp,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Back",
                                    tint = AppTheme.Colors.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = { pickingEnvironmentFor = null },
                            modifier = Modifier.testTag("environment-back"),
                        )
                        environments.forEach { environment ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(text = environment.name, color = AppTheme.Colors.text, fontSize = 11.sp)
                                        Text(
                                            text = environment.host.ifBlank { "the profile's own host" },
                                            color = AppTheme.Colors.textDisabled,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                        )
                                    }
                                },
                                onClick = {
                                    close()
                                    onConnectProfileIn?.invoke(choosingEnvironment, environment)
                                },
                                modifier = Modifier.testTag("environment-${environment.name}"),
                            )
                        }
                        // The endpoint the profile already names is an environment nobody extracted, and
                        // refusing to offer it would make the feature a downgrade for anything it did
                        // not manage to classify.
                        HorizontalDivider(
                            color = AppTheme.Separators.color,
                            thickness = AppTheme.Separators.dividerThickness,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "As saved (${choosingEnvironment.config.host})",
                                    color = AppTheme.Colors.text,
                                    fontSize = 11.sp,
                                )
                            },
                            onClick = {
                                close()
                                onQuickConnect?.invoke(choosingEnvironment.id, choosingEnvironment)
                            },
                            modifier = Modifier.testTag("environment-as-saved"),
                        )
                    } else if (pickingRecent) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Recent workspaces",
                                    color = AppTheme.Colors.textSecondary,
                                    fontSize = 11.sp,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Back",
                                    tint = AppTheme.Colors.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = { pickingRecent = false },
                            modifier = Modifier.testTag("workspace-recent-back"),
                        )
                        recentWorkspaces.forEach { workspace ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(text = workspace.name, color = AppTheme.Colors.text, fontSize = 11.sp)
                                        Text(
                                            text = workspace.parent.orEmpty(),
                                            color = AppTheme.Colors.textDisabled,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                        )
                                    }
                                },
                                onClick = {
                                    close()
                                    onOpenRecentWorkspace?.invoke(workspace)
                                },
                                modifier = Modifier.testTag("workspace-recent-${workspace.name}"),
                            )
                        }
                    } else {
                        if (onQuickConnect != null) {
                            if (connectionProfiles.isEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "No saved profiles",
                                            color = AppTheme.Colors.textDisabled,
                                            fontSize = 11.sp,
                                        )
                                    },
                                    enabled = false,
                                    onClick = {},
                                )
                            }
                            connectionProfiles.forEach { profile ->
                                val connectionState =
                                    onGetProfileConnectionState?.invoke(profile.id) ?: FixConnectionState.DISCONNECTED
                                val stateColor = connectionState.getColor()

                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            // Status indicator dot
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .size(8.dp)
                                                        .background(stateColor, CircleShape),
                                            )
                                            Text(
                                                text = profile.name,
                                                color = AppTheme.Colors.text,
                                                fontSize = 11.sp,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        if (environments.isNotEmpty()) {
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "Choose an environment",
                                                tint = AppTheme.Colors.textSecondary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    },
                                    onClick = {
                                        if (environments.isEmpty()) {
                                            close()
                                            onQuickConnect(profile.id, profile)
                                        } else {
                                            pickingEnvironmentFor = profile
                                        }
                                    },
                                    modifier = Modifier.testTag("quick-connect-${profile.name}"),
                                )
                            }
                        }

                        if (workspaceItemsShown) {
                            if (onQuickConnect != null) {
                                HorizontalDivider(
                                    color = AppTheme.Separators.color,
                                    thickness = AppTheme.Separators.dividerThickness,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            if (workspaceName != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Workspace: $workspaceName",
                                            color = AppTheme.Colors.textDisabled,
                                            fontSize = 10.sp,
                                        )
                                    },
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.testTag("workspace-current"),
                                )
                            }
                            if (onOpenExample != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(text = "Open example…", color = AppTheme.Colors.text, fontSize = 11.sp)
                                    },
                                    onClick = {
                                        close()
                                        onOpenExample()
                                    },
                                    modifier = Modifier.testTag("workspace-open-example"),
                                )
                            }
                            if (onOpenWorkspace != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(text = "Open workspace…", color = AppTheme.Colors.text, fontSize = 11.sp)
                                    },
                                    onClick = {
                                        close()
                                        onOpenWorkspace()
                                    },
                                    modifier = Modifier.testTag("workspace-open"),
                                )
                            }
                            if (recentWorkspaces.isNotEmpty() && onOpenRecentWorkspace != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(text = "Recent workspaces", color = AppTheme.Colors.text, fontSize = 11.sp)
                                    },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "Choose a recent workspace",
                                            tint = AppTheme.Colors.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = { pickingRecent = true },
                                    modifier = Modifier.testTag("workspace-recent"),
                                )
                            }
                            if (workspaceOpen && onCloseWorkspace != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(text = "Close workspace", color = AppTheme.Colors.text, fontSize = 11.sp)
                                    },
                                    onClick = {
                                        close()
                                        onCloseWorkspace()
                                    },
                                    modifier = Modifier.testTag("workspace-close"),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
        }

        // Embedded terminal — a primary action, sat right of Quick Connect: opens a terminal where QA can
        // run `claude` and watch it drive FixTool over MCP without leaving the app.
        if (onToggleTerminal != null) {
            TooltipIconButton(
                tooltip = if (showTerminal) "Terminal (click to hide)" else "Terminal (click to show)",
                onClick = onToggleTerminal,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Terminal",
                    tint = toggleActiveColor(showTerminal, AppTheme.Colors.primary, AppTheme.Colors.textSecondary),
                    modifier = tooltipIconModifier,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }

        // Capture scenario — turn the whole flow across all sessions into an editable scenario. It lives with the
        // other all-sessions *actions* (search / separator / clear), not the left pane-toggles: it is a one-shot
        // that opens the editor directly (curation is editing — there is no separate read-only review screen).
        if (onCaptureScenario != null) {
            TooltipIconButton(
                tooltip = "Capture Scenario from All Sessions",
                onClick = onCaptureScenario,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = "Capture Scenario",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Search All Sessions
        if (onSearchAllSessions != null) {
            TooltipIconButton(
                tooltip = "Search All Sessions",
                onClick = onSearchAllSessions,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search All Sessions",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Add Separator to All Sessions
        if (onAddSeparatorToAll != null) {
            TooltipIconButton(
                tooltip = "Add Blank Line to All Sessions",
                onClick = onAddSeparatorToAll,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Blank Line",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Clear All Sessions
        if (onClearAll != null) {
            TooltipIconButton(
                tooltip = "Clear All Sessions",
                onClick = onClearAll,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear All",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Layout toggle (cycles through TABS -> SPLIT_HORIZONTAL -> SPLIT_VERTICAL -> TABS)
        TooltipIconButton(
            tooltip =
                when (viewMode) {
                    ViewMode.TABS -> "Layout: Tabs (click for Horizontal Split)"
                    ViewMode.SPLIT_HORIZONTAL -> "Layout: Horizontal Split (click for Vertical Split)"
                    ViewMode.SPLIT_VERTICAL -> "Layout: Vertical Split (click for Tabs)"
                },
            onClick = {
                val newMode =
                    when (viewMode) {
                        ViewMode.TABS -> ViewMode.SPLIT_HORIZONTAL
                        ViewMode.SPLIT_HORIZONTAL -> ViewMode.SPLIT_VERTICAL
                        ViewMode.SPLIT_VERTICAL -> ViewMode.TABS
                    }
                onViewModeChange(newMode)
            },
            modifier = tooltipModifier,
        ) {
            Icon(
                imageVector =
                    when (viewMode) {
                        ViewMode.TABS -> Icons.Default.Tab
                        ViewMode.SPLIT_HORIZONTAL -> Icons.Default.ViewAgenda
                        ViewMode.SPLIT_VERTICAL -> Icons.Default.ViewArray
                    },
                contentDescription = "Toggle Layout",
                tint = AppTheme.Colors.textSecondary,
                modifier = tooltipIconModifier,
            )
        }

        // View toggle (Terminal <-> Grid) - applies to all sessions
        if (onToggleGridView != null) {
            TooltipIconButton(
                tooltip =
                    when (globalSessionViewMode) {
                        FixMessageSession.ViewMode.RAW -> "Switch All Sessions to Grid View"
                        FixMessageSession.ViewMode.PARSED -> "Switch All Sessions to Terminal View"
                    },
                onClick = onToggleGridView,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector =
                        when (globalSessionViewMode) {
                            FixMessageSession.ViewMode.RAW -> Icons.Default.Apps
                            // Subject (raw-text lines), not Terminal — the Terminal glyph now belongs to the
                            // embedded terminal button; this "Terminal View" is really the raw FIX text view.
                            FixMessageSession.ViewMode.PARSED -> Icons.Default.Subject
                        },
                    contentDescription = "Toggle View for All Sessions",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Hide/Show Protocol Tags Toggle (applies to all sessions)
        if (onToggleHideProtocolTags != null) {
            TooltipIconButton(
                tooltip = if (hideProtocolTags) "Show Protocol Tags" else "Hide Protocol Tags",
                onClick = onToggleHideProtocolTags,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = if (hideProtocolTags) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (hideProtocolTags) "Show Protocol Tags" else "Hide Protocol Tags",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Group the grid by business exchange. Independent of RAW/PARSED above: that says how a row
        // renders, this says how rows relate, and they compose.
        if (onToggleGroupByConversation != null) {
            TooltipIconButton(
                tooltip =
                    if (groupByConversation) {
                        "Conversations: On (click for a flat list)"
                    } else {
                        "Conversations: Off (click to group by exchange)"
                    },
                onClick = onToggleGroupByConversation,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = "Group by conversation",
                    tint = AppTheme.Helpers.activeColor(groupByConversation),
                    modifier = tooltipIconModifier,
                )
            }
        }

        // The Ledger, next to Group because it is the same relation one level up: Group answers "what
        // happened to RFQ-A1 on this pane", this answers "what happened to RFQ-A1". A toggle rather
        // than only a side effect of Follow, so the panel can be read before anything is followed —
        // which is the point, since which exchanges crossed a session is read off its headers.
        if (onToggleTracePanel != null) {
            TooltipIconButton(
                tooltip =
                    if (tracePanelOpen) {
                        "Trace: On (click to hide the Ledger)"
                    } else {
                        "Trace: Off (click for every exchange across every session)"
                    },
                onClick = onToggleTracePanel,
                modifier = tooltipModifier.testTag("toggle-trace-panel"),
            ) {
                Icon(
                    imageVector = Icons.Default.AltRoute,
                    contentDescription = "Trace across sessions",
                    tint = AppTheme.Helpers.activeColor(tracePanelOpen),
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Visual separator after view mode controls
        Spacer(modifier = Modifier.width(8.dp))

        // Connection panel toggle
        if (onToggleConnectionPanel != null) {
            TooltipIconButton(
                tooltip = if (showConnectionPanel) "Connection: On (click to hide)" else "Connection: Off (click to show)",
                onClick = onToggleConnectionPanel,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.ElectricalServices,
                    contentDescription = "Toggle Connection Panel",
                    tint = toggleActiveColor(showConnectionPanel, AppTheme.Colors.primary, AppTheme.Colors.textSecondary),
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Settings button
        if (onOpenSettings != null) {
            TooltipIconButton(
                tooltip = if (isDictionaryValid) "Settings" else "Settings - Data Dictionary Configuration Required!",
                onClick = onOpenSettings,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = if (isDictionaryValid) AppTheme.Colors.textSecondary else AppTheme.Colors.error,
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Help button
        if (onOpenHelp != null) {
            TooltipIconButton(
                tooltip = "Help & Documentation",
                onClick = onOpenHelp,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.Help,
                    contentDescription = "Help",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Message detail panel toggle
        if (onToggleDetailPanel != null) {
            TooltipIconButton(
                tooltip = if (showDetailPanel) "Message Detail: On (click to hide)" else "Message Detail: Off (click to show)",
                onClick = onToggleDetailPanel,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.Article,
                    contentDescription = "Toggle Message Detail Panel",
                    tint = toggleActiveColor(showDetailPanel, AppTheme.Colors.primary, AppTheme.Colors.textSecondary),
                    modifier = tooltipIconModifier,
                )
            }
        }

        // The venue's own memory. Offered beside the latency toggle because both answer "what is this
        // acceptor doing" rather than "what is on the wire", and neither belongs in the message grid.
        if (onToggleOrderBookPanel != null) {
            TooltipIconButton(
                tooltip =
                    if (showOrderBookPanel) {
                        "Order Book: On (click to hide)"
                    } else {
                        "Order Book: what this venue is holding, per counterparty"
                    },
                onClick = onToggleOrderBookPanel,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.ListAlt,
                    contentDescription = "Toggle Order Book",
                    tint = toggleActiveColor(showOrderBookPanel, AppTheme.Colors.primary, AppTheme.Colors.textSecondary),
                    modifier = tooltipIconModifier,
                )
            }
        }

        // Latency panel toggle (rightmost)
        if (onToggleLatencyPanel != null) {
            TooltipIconButton(
                tooltip = if (showLatencyPanel) "Latency Stats: On (click to hide)" else "Latency Stats: Off (click to show)",
                onClick = onToggleLatencyPanel,
                modifier = tooltipModifier,
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = "Toggle Latency Panel",
                    tint = toggleActiveColor(showLatencyPanel, AppTheme.Colors.primary, AppTheme.Colors.textSecondary),
                    modifier = tooltipIconModifier,
                )
            }
        }
    }
}

// Helper functions now take colors as parameters to use AppTheme.Colors
private fun toggleActiveColor(condition: Boolean, activeColor: Color, inactiveColor: Color) =
    if (condition) activeColor else inactiveColor

private fun toggleDisabledColor(condition: Boolean, enabledColor: Color, disabledColor: Color) =
    if (condition) enabledColor else disabledColor

private val tooltipModifier = Modifier.size(32.dp)
private val tooltipIconModifier = Modifier.size(20.dp)
