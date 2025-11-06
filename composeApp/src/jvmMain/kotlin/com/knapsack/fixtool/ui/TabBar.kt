package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixMessageSession

@Composable
fun TabBar(
    sessions: List<FixMessageSession>,
    activeIndex: Int,
    onTabClick: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onToggleViewMode: (Int) -> Unit,
    onToggleWrapText: (Int) -> Unit,
    onConnect: (Int) -> Unit,
    onDisconnect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Top border
        androidx.compose.material3.HorizontalDivider(
            color = AppTheme.Separators.color,
            thickness = AppTheme.Separators.dividerThickness,
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B2B2B))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tab items
            sessions.forEachIndexed { index, session ->
                Tab(
                    session = session,
                    isActive = index == activeIndex,
                    onClick = { onTabClick(index) },
                    onClose = { onCloseTab(index) },
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Toolbar buttons for active session
            if (sessions.isNotEmpty() && activeIndex >= 0 && activeIndex < sessions.size) {
                val activeSession = sessions[activeIndex]
                val viewMode by activeSession.viewMode.collectAsState()
                val wrapText by activeSession.wrapText.collectAsState()
                val connectionState by activeSession.connectionState.collectAsState()
                val searchVisible by activeSession.searchVisible.collectAsState()
                val filterVisible by activeSession.filterVisible.collectAsState()
                val hideProtocolTags by activeSession.hideProtocolTags.collectAsState()

                // RAW mode specific buttons (wrap, search)
                if (viewMode == FixMessageSession.ViewMode.RAW) {
                    // Wrap text toggle
                    TooltipIconButton(
                        tooltip = if (wrapText) "Wrap: On (click to unwrap)" else "Wrap: Off (click to wrap)",
                        onClick = { onToggleWrapText(activeIndex) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (wrapText) Icons.Default.WrapText else Icons.Default.Notes,
                            contentDescription = "Toggle Text Wrap",
                            tint = Color(0xFFB0B0B0),
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // Search button (RAW mode only)
                    TooltipIconButton(
                        tooltip = if (searchVisible) "Hide Search" else "Show Search (Ctrl+F)",
                        onClick = { activeSession.toggleSearch() },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Toggle Search",
                            tint = if (searchVisible) AppTheme.Colors.primary else Color(0xFFB0B0B0),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Filter button (available for both RAW and PARSED modes - regex, message type, direction filters)
                TooltipIconButton(
                    tooltip = if (filterVisible) "Hide Filter" else "Show Filter (Regex)",
                    onClick = { activeSession.toggleFilter() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = "Toggle Filter",
                        tint = if (filterVisible) AppTheme.Colors.primary else Color(0xFFB0B0B0),
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Add separator button
                TooltipIconButton(
                    tooltip = "Add Separator",
                    onClick = { activeSession.addSeparator() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Separator",
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Clear session button
                TooltipIconButton(
                    tooltip = "Clear All Messages",
                    onClick = { activeSession.clearMessages() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear All Messages",
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Toggle view mode button
                val tooltipText =
                    when (viewMode) {
                        FixMessageSession.ViewMode.RAW -> "View: Terminal (click for Grid)"
                        FixMessageSession.ViewMode.PARSED -> "View: Grid (click for Terminal)"
                    }

                TooltipIconButton(
                    tooltip = tooltipText,
                    onClick = { onToggleViewMode(activeIndex) },
                    modifier = Modifier.size(28.dp),
                ) {
                    val icon =
                        when (viewMode) {
                            FixMessageSession.ViewMode.RAW -> Icons.Default.Terminal
                            FixMessageSession.ViewMode.PARSED -> Icons.Default.Apps
                        }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Toggle View (${viewMode.name})",
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Connect/Disconnect toggle button
                if (connectionState.canConnect()) {
                    // Show connect button when disconnected
                    TooltipIconButton(
                        tooltip = "Connect Session",
                        onClick = { onConnect(activeIndex) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Connect",
                            tint = Color(0xFFB0B0B0), // Gray when disconnected
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else if (connectionState.canDisconnect()) {
                    // Show disconnect button when connected
                    TooltipIconButton(
                        tooltip = "Disconnect Session",
                        onClick = { onDisconnect(activeIndex) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Disconnect",
                            tint = Color(0xFF7AD67A), // Green when connected
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Tab(
    session: FixMessageSession,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isActive) Color(0xFF1E1E1E) else Color(0xFF2B2B2B)
    val textColor = if (isActive) Color(0xFFE0E0E0) else Color(0xFFB0B0B0)

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = session.title,
            color = textColor,
            fontSize = 11.sp,
        )

        if (onClose != null) {
            Spacer(modifier = Modifier.width(8.dp))
            TooltipIconButton(
                tooltip = "Close Tab",
                onClick = onClose,
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Tab",
                    tint = textColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
