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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion

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
    demoServerRunning: Boolean = false,
    demoServerFixVersion: FixVersion? = null,
    connectionProfiles: List<FixConnectionProfile> = emptyList(),
    isDictionaryValid: Boolean = true,
    globalSessionViewMode: FixMessageSession.ViewMode,
    globalFilterRegex: String = "",
    globalFilterShowIncoming: Boolean = true,
    globalFilterShowOutgoing: Boolean = true,
    hideProtocolTags: Boolean = true,
    onOpenMessageEditor: (() -> Unit)? = null,
    onToggleDetailPanel: (() -> Unit)? = null,
    onToggleConnectionPanel: (() -> Unit)? = null,
    onToggleLatencyPanel: (() -> Unit)? = null,
    onStartDemoServer: ((FixVersion) -> Unit)? = null,
    onStopDemoServer: (() -> Unit)? = null,
    onToggleGridView: (() -> Unit)? = null,
    onQuickConnect: ((String, FixConnectionProfile) -> Unit)? = null,
    onGetProfileConnectionState: ((String) -> FixConnectionState)? = null,
    onSearchAllSessions: (() -> Unit)? = null,
    onAddSeparatorToAll: (() -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    onGlobalFilterChange: ((String) -> Unit)? = null,
    onGlobalFilterIncomingChange: ((Boolean) -> Unit)? = null,
    onGlobalFilterOutgoingChange: ((Boolean) -> Unit)? = null,
    onToggleHideProtocolTags: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
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

        Text(
            text = "FixTool",
            color = AppTheme.Colors.text,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

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

        // Quick Connect Dropdown
        if (onQuickConnect != null && connectionProfiles.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }

            Box {
                Row(
                    modifier =
                        Modifier
                            .height(28.dp)
                            .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
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
                    onDismissRequest = { expanded = false },
                    modifier =
                        Modifier
                            .background(AppTheme.Colors.surface)
                            .widthIn(min = 200.dp),
                ) {
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
                            onClick = {
                                expanded = false
                                onQuickConnect(profile.id, profile)
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
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
                            FixMessageSession.ViewMode.PARSED -> Icons.Default.Terminal
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

        // Visual separator after view mode controls
        Spacer(modifier = Modifier.width(8.dp))

        // Demo Server Dropdown
        if (onStartDemoServer != null && onStopDemoServer != null) {
            var expanded by remember { mutableStateOf(false) }

            Box {
                Row(
                    modifier =
                        Modifier
                            .height(28.dp)
                            .background(
                                if (demoServerRunning) AppTheme.Colors.primary.copy(alpha = 0.2f) else AppTheme.Colors.border,
                                RoundedCornerShape(4.dp),
                            ).clickable { expanded = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = "Demo Server",
                        tint = if (demoServerRunning) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (demoServerRunning) "Demo: ${demoServerFixVersion?.displayName ?: "Running"}" else "Demo Server",
                        color = if (demoServerRunning) AppTheme.Colors.primary else AppTheme.Colors.text,
                        fontSize = 11.sp,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Dropdown",
                        tint = if (demoServerRunning) AppTheme.Colors.primary else AppTheme.Colors.text,
                        modifier = Modifier.size(16.dp),
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier =
                        Modifier
                            .background(AppTheme.Colors.surface)
                            .widthIn(min = 180.dp),
                ) {
                    if (demoServerRunning) {
                        // Show stop option when running
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop",
                                        tint = AppTheme.Colors.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Stop Demo Server",
                                        color = AppTheme.Colors.error,
                                        fontSize = 11.sp,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onStopDemoServer()
                            },
                        )
                    } else {
                        // Show FIX version options when not running
                        Text(
                            text = "Start Demo Server",
                            color = AppTheme.Colors.textSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        FixVersion.entries.forEach { version ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Start",
                                            tint = AppTheme.Colors.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = version.displayName,
                                            color = AppTheme.Colors.text,
                                            fontSize = 11.sp,
                                        )
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    onStartDemoServer(version)
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
        }

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
