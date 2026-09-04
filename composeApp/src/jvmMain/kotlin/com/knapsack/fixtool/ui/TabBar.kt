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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixMessageSession

@Composable
fun TabBar(
    /** Every pane. Minimized ones get a chip above rather than a tab. */
    sessions: List<FixMessageSession>,
    activeSession: FixMessageSession?,
    viewMode: FixMessageSession.ViewMode,
    onTabClick: (FixMessageSession) -> Unit,
    onCloseTab: (FixMessageSession) -> Unit,
    onToggleWrapText: (FixMessageSession) -> Unit,
    onConnect: (FixMessageSession) -> Unit,
    onDisconnect: (FixMessageSession) -> Unit,
    onMinimize: (FixMessageSession, Boolean) -> Unit = { session, on -> session.setMinimized(on) },
    onEditVenueRules: ((FixMessageSession) -> Unit)? = null,
    isAtBottom: Boolean = true,
    onScrollToBottom: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Same reading as the split grid takes, for the same reason: one combined collector keyed on the
    // pane identities, never a composable called inside a loop over a mutable list. See [SplitView].
    val identities = sessions.joinToString(",") { it.id }
    val minimizedFlags by remember(identities) {
        val flows = sessions.map { it.minimized }
        if (flows.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyList<Boolean>())
        } else {
            kotlinx.coroutines.flow.combine(flows) { flags -> flags.toList() }
        }
    }.collectAsState(initial = sessions.map { it.minimized.value })
    val minimized = sessions.filterIndexed { i, _ -> minimizedFlags.getOrElse(i) { false } }
    val visible = sessions.filterIndexed { i, _ -> !minimizedFlags.getOrElse(i) { false } }

    Column(modifier = modifier) {
        // Top border
        androidx.compose.material3.HorizontalDivider(
            color = AppTheme.Separators.color,
            thickness = AppTheme.Separators.dividerThickness,
        )

        // One strip, in both layouts. A venue starts minimized whichever layout is showing, so its live
        // state has to be readable here too, and putting it anywhere else would be a second answer to
        // the same question.
        MinimizedStrip(
            minimized = minimized,
            allSessions = sessions,
            targetSession = activeSession,
            onRestore = { onMinimize(it, false) },
            onRestoreAll = { minimized.forEach { session -> onMinimize(session, false) } },
            onEditRules = onEditVenueRules,
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surface)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tab items. Scenario documents no longer share this strip — they live in the scenario dock
            // (see [ScenarioDock]), so a session tab is active on identity alone.
            visible.forEach { session ->
                Tab(
                    session = session,
                    isActive = session === activeSession,
                    onClick = { onTabClick(session) },
                    onClose = { onCloseTab(session) },
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Toolbar buttons for the active session, unless it is in the strip — a minimized pane keeps
            // its session and its log, but it has no grid on screen for these to act on.
            if (activeSession != null && activeSession in visible) {
                val connectionState by activeSession.connectionState.collectAsState()
                val filterVisible by activeSession.filterVisible.collectAsState()
                val groupedByConversation by activeSession.groupByConversation.collectAsState()

                // Grid controls, so a venue gets none of them: its pane draws a client list, not a
                // message log, and every message on the venue belongs to one of its clients. The same
                // gate as the split layout applies (see SplitView) — one behaviour, both layouts.
                if (!activeSession.isVenue) {
                    // RAW mode specific buttons (wrap, search)
                    if (viewMode == FixMessageSession.ViewMode.RAW) {
                        RawViewActions(activeSession, onToggleWrapText)
                    }

                    // Filter button (available for both RAW and PARSED modes - regex, message type, direction filters)
                    TooltipIconButton(
                        tooltip = if (filterVisible) "Hide Filter" else "Show Filter (Regex)",
                        onClick = { activeSession.toggleFilter() },
                        modifier = toolbarButtonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterAlt,
                            contentDescription = "Toggle Filter",
                            tint = if (filterVisible) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                            modifier = toolbarIconSize,
                        )
                    }

                    // Group this session's grid by business exchange — per session, like the filter.
                    TooltipIconButton(
                        tooltip =
                            if (groupedByConversation) {
                                "Conversations: On (click for a flat list)"
                            } else {
                                "Conversations: Off (click to group by exchange)"
                            },
                        onClick = { activeSession.toggleGroupByConversation() },
                        modifier = toolbarButtonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = "Group by Conversation",
                            tint = if (groupedByConversation) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                            modifier = toolbarIconSize,
                        )
                    }

                    // Add separator button
                    TooltipIconButton(
                        tooltip = "Add Separator",
                        onClick = { activeSession.addSeparator() },
                        modifier = toolbarButtonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Separator",
                            tint = AppTheme.Colors.textSecondary,
                            modifier = toolbarIconSize,
                        )
                    }

                    // Clear session button
                    TooltipIconButton(
                        tooltip = "Clear All Messages",
                        onClick = { activeSession.clearMessages() },
                        modifier = toolbarButtonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear All Messages",
                            tint = AppTheme.Colors.textSecondary,
                            modifier = toolbarIconSize,
                        )
                    }

                    // Scroll to bottom button
                    TooltipIconButton(
                        tooltip = "Scroll to Bottom",
                        onClick = onScrollToBottom,
                        enabled = !isAtBottom,
                        modifier = toolbarButtonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Scroll to bottom",
                            tint = if (!isAtBottom) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                            modifier = toolbarIconSize,
                        )
                    }
                }

                // Sends this tab to the strip above. Not a close: the session keeps running.
                TooltipIconButton(
                    tooltip = "Minimize Pane",
                    onClick = { onMinimize(activeSession, true) },
                    modifier = toolbarButtonSize,
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Minimize Pane",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = toolbarIconSize,
                    )
                }

                // Connect/Disconnect toggle button. Withheld for a venue, whose equivalent is the named
                // Start/Stop its overview and its chip both carry — an unlabelled power icon never said
                // that it unbinds a port every client on the venue is sitting on.
                if (activeSession.isVenue) {
                    Unit
                } else if (connectionState.canConnect()) {
                    // Show connect button when disconnected
                    TooltipIconButton(
                        tooltip = "Connect Session",
                        onClick = { onConnect(activeSession) },
                        modifier = toolbarButtonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Connect",
                            tint = AppTheme.Colors.textSecondary,
                            modifier = toolbarIconSize,
                        )
                    }
                } else if (connectionState.canDisconnect()) {
                    // Show disconnect button when connected
                    TooltipIconButton(
                        tooltip = "Disconnect Session",
                        onClick = { onDisconnect(activeSession) },
                        modifier = toolbarButtonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Disconnect",
                            tint = AppTheme.Colors.success,
                            modifier = toolbarIconSize,
                        )
                    }
                }
            }
        }
    }
}

/** The RAW-view-only buttons (wrap, search), lifted out so the tab bar itself stays readable. */
@Composable
private fun RawViewActions(session: FixMessageSession, onToggleWrapText: (FixMessageSession) -> Unit) {
    val wrapText by session.wrapText.collectAsState()
    val searchVisible by session.searchVisible.collectAsState()

    TooltipIconButton(
        tooltip = if (wrapText) "Wrap: On (click to unwrap)" else "Wrap: Off (click to wrap)",
        onClick = { onToggleWrapText(session) },
        modifier = toolbarButtonSize,
    ) {
        Icon(
            imageVector = if (wrapText) Icons.Default.WrapText else Icons.Default.Notes,
            contentDescription = "Toggle Text Wrap",
            tint = AppTheme.Colors.textSecondary,
            modifier = toolbarIconSize,
        )
    }

    TooltipIconButton(
        tooltip = if (searchVisible) "Hide Search" else "Show Search (Ctrl+F)",
        onClick = { session.toggleSearch() },
        modifier = toolbarButtonSize,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Toggle Search",
            tint = if (searchVisible) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
            modifier = toolbarIconSize,
        )
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
    val backgroundColor = if (isActive) AppTheme.Colors.background else AppTheme.Colors.surface
    val textColor = if (isActive) AppTheme.Colors.text else AppTheme.Colors.textSecondary

    Row(
        modifier =
            modifier
                .clip(tabShape)
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
                modifier = tabCloseButtonSize,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Tab",
                    tint = textColor,
                    modifier = tabCloseIconSize,
                )
            }
        }
    }
}

// No local color constants needed - all colors now use AppTheme.Colors

// Modifier constants
private val toolbarButtonSize = Modifier.size(28.dp)
private val toolbarIconSize = Modifier.size(18.dp)
private val tabCloseButtonSize = Modifier.size(16.dp)
private val tabCloseIconSize = Modifier.size(14.dp)
private val tabShape = RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp)
