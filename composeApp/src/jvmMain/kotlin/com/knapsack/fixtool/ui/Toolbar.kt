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
    connectionProfiles: List<FixConnectionProfile> = emptyList(),
    isDictionaryValid: Boolean = true,
    globalSessionViewMode: FixMessageSession.ViewMode,
    globalFilterRegex: String = "",
    globalFilterShowIncoming: Boolean = true,
    globalFilterShowOutgoing: Boolean = true,
    hideProtocolTags: Boolean = true,
    groupByConversation: Boolean = false,
    onOpenMessageEditor: (() -> Unit)? = null,
    onToggleDetailPanel: (() -> Unit)? = null,
    onToggleConnectionPanel: (() -> Unit)? = null,
    onToggleLatencyPanel: (() -> Unit)? = null,
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
    onToggleGroupByConversation: (() -> Unit)? = null,
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
