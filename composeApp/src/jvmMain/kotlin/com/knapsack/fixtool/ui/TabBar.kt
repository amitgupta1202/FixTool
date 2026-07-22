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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixMessageSession

@Composable
fun TabBar(
    sessions: List<FixMessageSession>,
    activeIndex: Int,
    viewMode: FixMessageSession.ViewMode,
    onTabClick: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onToggleWrapText: (Int) -> Unit,
    onConnect: (Int) -> Unit,
    onDisconnect: (Int) -> Unit,
    isAtBottom: Boolean = true,
    onScrollToBottom: () -> Unit = {},
    /**
     * Scenario documents, in the same strip as the sessions — an IDE mixes editors and diffs with its files.
     * A document tab does **not** move the active session: [activeDocumentId] is the centre pane's selection
     * and `null` is the session view. See [ScenarioDoc].
     *
     * These are [DocumentTab]s and not documents, because a document of a scenario cannot say on its own what
     * that scenario is called or whether it is dirty — the draft belongs to the scenario now. See
     * [documentTabsOf].
     */
    documents: List<DocumentTab> = emptyList(),
    activeDocumentId: String? = null,
    confirmingCloseId: String? = null,
    onFocusDocument: (String) -> Unit = {},
    onRequestCloseDocument: (String) -> Unit = {},
    onConfirmCloseDocument: (String) -> Unit = {},
    onCancelCloseDocument: () -> Unit = {},
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
                    .background(AppTheme.Colors.surface)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tab items
            sessions.forEachIndexed { index, session ->
                Tab(
                    session = session,
                    isActive = index == activeIndex && activeDocumentId == null,
                    onClick = { onTabClick(index) },
                    onClose = { onCloseTab(index) },
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            DocumentTabs(
                tabs = documents,
                activeId = activeDocumentId,
                confirmingCloseId = confirmingCloseId,
                onFocus = onFocusDocument,
                onRequestClose = onRequestCloseDocument,
                onConfirmClose = onConfirmCloseDocument,
                onCancelClose = onCancelCloseDocument,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Toolbar buttons for active session
            if (sessions.isNotEmpty() && activeIndex >= 0 && activeIndex < sessions.size) {
                val activeSession = sessions[activeIndex]
                val connectionState by activeSession.connectionState.collectAsState()
                val filterVisible by activeSession.filterVisible.collectAsState()
                val groupedByConversation by activeSession.groupByConversation.collectAsState()

                // RAW mode specific buttons (wrap, search)
                if (viewMode == FixMessageSession.ViewMode.RAW) {
                    RawViewActions(activeSession, activeIndex, onToggleWrapText)
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

                // Connect/Disconnect toggle button
                if (connectionState.canConnect()) {
                    // Show connect button when disconnected
                    TooltipIconButton(
                        tooltip = "Connect Session",
                        onClick = { onConnect(activeIndex) },
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
                        onClick = { onDisconnect(activeIndex) },
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
private fun RawViewActions(session: FixMessageSession, index: Int, onToggleWrapText: (Int) -> Unit) {
    val wrapText by session.wrapText.collectAsState()
    val searchVisible by session.searchVisible.collectAsState()

    TooltipIconButton(
        tooltip = if (wrapText) "Wrap: On (click to unwrap)" else "Wrap: Off (click to wrap)",
        onClick = { onToggleWrapText(index) },
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
