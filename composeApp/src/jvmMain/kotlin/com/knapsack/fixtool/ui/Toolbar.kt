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
    demoServerRunning: Boolean = false,
    connectionProfiles: List<FixConnectionProfile> = emptyList(),
    isDictionaryValid: Boolean = true,
    activeSessionViewMode: FixMessageSession.ViewMode? = null,
    onOpenMessageEditor: (() -> Unit)? = null,
    onToggleDetailPanel: (() -> Unit)? = null,
    onToggleConnectionPanel: (() -> Unit)? = null,
    onToggleDemoServer: (() -> Unit)? = null,
    onToggleGridView: (() -> Unit)? = null,
    onQuickConnect: ((String, FixConnectionProfile) -> Unit)? = null,
    onGetProfileConnectionState: ((String) -> FixConnectionState)? = null,
    onAddSeparatorToAll: (() -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color(0xFF2B2B2B))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Message editor button (controls left panel)
        if (onOpenMessageEditor != null) {
            TooltipIconButton(
                tooltip = if (showMessageEditor) "Message Editor: On (click to hide)" else "Message Editor: Off (click to show)",
                onClick = onOpenMessageEditor,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = "Message Editor",
                    tint = if (showMessageEditor) Color(0xFF4EC9B0) else Color(0xFFB0B0B0),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Text(
            text = "FixTool",
            color = Color(0xFFE0E0E0),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        // Quick Connect Dropdown
        if (onQuickConnect != null && connectionProfiles.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }

            Box {
                Row(
                    modifier =
                        Modifier
                            .height(28.dp)
                            .background(Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Quick Connect",
                        tint = Color(0xFF4EC9B0),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Quick Connect",
                        color = Color(0xFFE0E0E0),
                        fontSize = 11.sp,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Dropdown",
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(16.dp),
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier =
                        Modifier
                            .background(Color(0xFF2B2B2B))
                            .widthIn(min = 200.dp),
                ) {
                    connectionProfiles.forEach { profile ->
                        val connectionState =
                            onGetProfileConnectionState?.invoke(profile.id) ?: FixConnectionState.DISCONNECTED
                        val stateColor =
                            when (connectionState) {
                                FixConnectionState.DISCONNECTED -> Color(0xFF6A6A6A) // Gray
                                FixConnectionState.CONNECTING -> Color(0xFFFFA500) // Orange
                                FixConnectionState.CONNECTED -> Color(0xFF7AD67A) // Light Green
                                FixConnectionState.LOGGED_ON -> Color(0xFF4EC9B0) // Teal/Green
                                FixConnectionState.ERROR -> Color(0xFFFF6B6B) // Red
                            }

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
                                        color = Color(0xFFE0E0E0),
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

        // Add Separator to All Sessions
        if (onAddSeparatorToAll != null) {
            TooltipIconButton(
                tooltip = "Add Separator to All Sessions",
                onClick = onAddSeparatorToAll,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Notes,
                    contentDescription = "Add Separator to All",
                    tint = Color(0xFFB0B0B0),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Clear All Sessions
        if (onClearAll != null) {
            TooltipIconButton(
                tooltip = "Clear All Sessions",
                onClick = onClearAll,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear All",
                    tint = Color(0xFFB0B0B0),
                    modifier = Modifier.size(20.dp),
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
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector =
                    when (viewMode) {
                        ViewMode.TABS -> Icons.Default.Tab
                        ViewMode.SPLIT_HORIZONTAL -> Icons.Default.ViewAgenda
                        ViewMode.SPLIT_VERTICAL -> Icons.Default.ViewArray
                    },
                contentDescription = "Toggle Layout",
                tint = Color(0xFFB0B0B0),
                modifier = Modifier.size(20.dp),
            )
        }

        // View toggle (Terminal <-> Grid) - applies to all sessions
        if (onToggleGridView != null) {
            TooltipIconButton(
                tooltip =
                    if (activeSessionViewMode != null) {
                        when (activeSessionViewMode) {
                            FixMessageSession.ViewMode.RAW -> "Switch All Sessions to Grid View"
                            FixMessageSession.ViewMode.PARSED -> "Switch All Sessions to Terminal View"
                        }
                    } else {
                        "View Toggle (no sessions)"
                    },
                onClick = onToggleGridView,
                enabled = activeSessionViewMode != null,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector =
                        when (activeSessionViewMode) {
                            FixMessageSession.ViewMode.RAW -> Icons.Default.Apps
                            FixMessageSession.ViewMode.PARSED -> Icons.Default.Terminal
                            null -> Icons.Default.Apps
                        },
                    contentDescription = "Toggle View for All Sessions",
                    tint = if (activeSessionViewMode != null) Color(0xFFB0B0B0) else Color(0xFF6A6A6A),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Visual separator after view mode controls
        Spacer(modifier = Modifier.width(8.dp))

        // Demo Server Toggle
        if (onToggleDemoServer != null) {
            TooltipIconButton(
                tooltip =
                    if (demoServerRunning) {
                        "Demo Server: Running (click to stop)\n4 demo profiles available in connection panel"
                    } else {
                        "Demo Server: Stopped (click to start)\nWill create 4 demo user profiles"
                    },
                onClick = onToggleDemoServer,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = "Toggle Demo Server",
                    tint = if (demoServerRunning) Color(0xFF4EC9B0) else Color(0xFFB0B0B0),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Connection panel toggle
        if (onToggleConnectionPanel != null) {
            TooltipIconButton(
                tooltip = if (showConnectionPanel) "Connection: On (click to hide)" else "Connection: Off (click to show)",
                onClick = onToggleConnectionPanel,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ElectricalServices,
                    contentDescription = "Toggle Connection Panel",
                    tint = if (showConnectionPanel) Color(0xFF4EC9B0) else Color(0xFFB0B0B0),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Settings button
        if (onOpenSettings != null) {
            TooltipIconButton(
                tooltip = if (isDictionaryValid) "Settings" else "Settings - Data Dictionary Configuration Required!",
                onClick = onOpenSettings,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = if (isDictionaryValid) Color(0xFFB0B0B0) else Color(0xFFE06C75), // Red when invalid
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Message detail panel toggle (rightmost)
        if (onToggleDetailPanel != null) {
            TooltipIconButton(
                tooltip = if (showDetailPanel) "Message Detail: On (click to hide)" else "Message Detail: Off (click to show)",
                onClick = onToggleDetailPanel,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Article,
                    contentDescription = "Toggle Message Detail Panel",
                    tint = if (showDetailPanel) Color(0xFF4EC9B0) else Color(0xFFB0B0B0),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
