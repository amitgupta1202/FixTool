package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import java.awt.Cursor

enum class SplitOrientation {
    HORIZONTAL,
    VERTICAL,
}

@Composable
fun SplitView(
    sessions: List<FixMessageSession>,
    dictionary: FixDictionary,
    viewMode: FixMessageSession.ViewMode,
    onCloseSession: (FixMessageSession) -> Unit,
    /**
     * Put the first session where the second one is.
     *
     * Sessions rather than indices, and that is the whole point of it. The grid is drawn from the
     * *visible* panes, so a `index - 1` computed here is a position in visible order while the model
     * orders every pane, minimized ones included — off by however many are hidden to the left. Naming
     * the neighbour instead of counting to it makes the two orders impossible to confuse, and it is the
     * same reason [onCloseSession] takes a session: a stale index closes the wrong pane silently, which
     * is destructive, because closing disconnects.
     */
    onMoveSession: ((FixMessageSession, FixMessageSession) -> Unit)? = null,
    /** Bring one session to the front — a venue's overview lists its clients and each one is a way in. */
    onFocusSession: (FixMessageSession) -> Unit = {},
    /** Where the editor's Send goes, so a minimized chip can say so. See [MinimizedStrip]. */
    activeSession: FixMessageSession? = null,
    /** Opens a venue's rules in the connection panel, from its pane or its chip. */
    onEditVenueRules: ((FixMessageSession) -> Unit)? = null,
    /**
     * Minimize or restore a pane. Routed out rather than written straight onto the session because the
     * *decision* is remembered across restarts — see `FixMessageViewModel.setSessionMinimized`. The
     * default keeps this composable usable on its own, which the tests rely on.
     */
    onMinimize: (FixMessageSession, Boolean) -> Unit = { session, on -> session.setMinimized(on) },
    selectedMessage: FixMessage? = null,
    onSelectMessage: ((FixMessage?) -> Unit)? = null,
    onDiffSelected: ((FixMessage, FixMessage) -> Unit)? = null,
    onPasteMessage: ((String) -> Unit)? = null,
    orientation: SplitOrientation = SplitOrientation.HORIZONTAL,
    gridViewColumns: List<Int> = emptyList(),
    assertionResults: Map<FixMessage, com.knapsack.fixtool.model.scenario.StepResult> = emptyMap(),
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    /** The toolbar's filter, ANDed into every pane — never written into one. See [MessageFilters]. */
    globalFilter: MessageFilters.Global = MessageFilters.Global.NONE,
    /** The followed trace's messages, or null when nothing is followed. See [MessageFilters.apply]. */
    followedUids: Set<Long>? = null,
    /** Every correlation value in the followed trace — what makes a group header show as followed. */
    followedTraceIds: Set<String> = emptySet(),
    onFollowTrace: ((String) -> Unit)? = null,
    onUnfollowTrace: (() -> Unit)? = null,
    /** For the empty state only. See [NoSessionsPlaceholder]. */
    hasProfiles: Boolean = false,
    examples: List<ExampleEntry> = emptyList(),
    onOpenExample: ((String) -> Unit)? = null,
    onOpenWorkspace: (() -> Unit)? = null,
    onOpenConnectionPanel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) {
        NoSessionsPlaceholder(
            hasProfiles = hasProfiles,
            examples = examples,
            onOpenExample = onOpenExample,
            onOpenWorkspace = onOpenWorkspace,
            onOpenConnectionPanel = onOpenConnectionPanel,
            modifier = modifier,
        )
        return
    }

    // Which panes are in the layout at all.
    //
    // One collector over the combined flows, keyed on the session *identities* rather than on the count.
    // `sessions.map { it.minimized.collectAsState() }` would call a composable inside a loop over a
    // mutable list, so the number and order of composition slots would depend on how many panes were
    // open — the defect the toolbar's grouping indicator already carries a comment about. Keying on the
    // ids and not the size also survives a reorder, which leaves the count alone and would otherwise
    // leave `combine` reading the flows in the old order.
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

    Column(modifier = modifier.fillMaxSize()) {
        MinimizedStrip(
            minimized = minimized,
            allSessions = sessions,
            targetSession = activeSession,
            onRestore = { onMinimize(it, false) },
            onRestoreAll = { minimized.forEach { session -> onMinimize(session, false) } },
            onEditRules = onEditVenueRules,
        )

        if (visible.isEmpty()) {
            AllMinimizedPlaceholder(count = minimized.size, modifier = Modifier.weight(1f))
            return@Column
        }

        SplitGrid(
            sessions = visible,
            allSessions = sessions,
            dictionary = dictionary,
            viewMode = viewMode,
            onCloseSession = onCloseSession,
            onMoveSession = onMoveSession,
            onFocusSession = onFocusSession,
            onEditVenueRules = onEditVenueRules,
            onMinimize = onMinimize,
            selectedMessage = selectedMessage,
            onSelectMessage = onSelectMessage,
            onDiffSelected = onDiffSelected,
            onPasteMessage = onPasteMessage,
            orientation = orientation,
            gridViewColumns = gridViewColumns,
            assertionResults = assertionResults,
            appSettings = appSettings,
            globalFilter = globalFilter,
            followedUids = followedUids,
            followedTraceIds = followedTraceIds,
            onFollowTrace = onFollowTrace,
            onUnfollowTrace = onUnfollowTrace,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Nothing left in the grid, because every pane is in the strip.
 *
 * Reachable in one gesture from a two-pane layout, so it needs an answer rather than a blank region.
 */
@Composable
private fun AllMinimizedPlaceholder(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(panelBackgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text =
                if (count == 1) {
                    "One pane is minimized. Click its chip above to bring it back."
                } else {
                    "$count panes are minimized. Click a chip above, or “restore all”."
                },
            color = AppTheme.Colors.textDisabled,
            fontSize = 12.sp,
        )
    }
}

/**
 * The grid itself, over the panes that are in it.
 *
 * Lifted out of [SplitView] when minimizing arrived, because everything here indexes into the list it
 * is given and that list is now the *visible* panes rather than every pane. Separating them is what
 * makes that impossible to get wrong by accident: nothing in this function can see a minimized pane.
 */
@Composable
private fun SplitGrid(
    sessions: List<FixMessageSession>,
    /** Every pane, only so a venue can find its clients — they may be minimized, it still owns them. */
    allSessions: List<FixMessageSession>,
    dictionary: FixDictionary,
    viewMode: FixMessageSession.ViewMode,
    onCloseSession: (FixMessageSession) -> Unit,
    onMoveSession: ((FixMessageSession, FixMessageSession) -> Unit)?,
    onFocusSession: (FixMessageSession) -> Unit,
    onEditVenueRules: ((FixMessageSession) -> Unit)?,
    onMinimize: (FixMessageSession, Boolean) -> Unit,
    selectedMessage: FixMessage?,
    onSelectMessage: ((FixMessage?) -> Unit)?,
    onDiffSelected: ((FixMessage, FixMessage) -> Unit)?,
    onPasteMessage: ((String) -> Unit)?,
    orientation: SplitOrientation,
    gridViewColumns: List<Int>,
    assertionResults: Map<FixMessage, com.knapsack.fixtool.model.scenario.StepResult>,
    appSettings: com.knapsack.fixtool.model.AppSettings,
    globalFilter: MessageFilters.Global,
    followedUids: Set<Long>?,
    followedTraceIds: Set<String>,
    onFollowTrace: ((String) -> Unit)?,
    onUnfollowTrace: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Calculate grid dimensions based on orientation
    val sessionCount = sessions.size
    val columns: Int
    val rows: Int

    when (orientation) {
        SplitOrientation.HORIZONTAL -> {
            // Left to right: max 4 columns, calculate rows based on that
            columns =
                when {
                    sessionCount == 1 -> 1
                    sessionCount <= 2 -> 2
                    sessionCount <= 4 -> 2
                    sessionCount <= 6 -> 3
                    else -> 4
                }
            rows = (sessionCount + columns - 1) / columns
        }

        SplitOrientation.VERTICAL -> {
            // Top to bottom: max 2 rows, calculate columns based on that
            rows = minOf(sessionCount, 2)
            columns = (sessionCount + rows - 1) / rows
        }
    }

    // Track column widths as weights (default: equal distribution)
    val columnWeights =
        remember(columns) {
            mutableStateListOf<Float>().apply {
                repeat(columns) { add(1f / columns) }
            }
        }

    // Track row heights as weights (default: equal distribution)
    val rowWeights =
        remember(rows) {
            mutableStateListOf<Float>().apply {
                repeat(rows) { add(1f / rows) }
            }
        }

    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val totalHeightPx = with(density) { maxHeight.toPx() }

        Column(modifier = Modifier.fillMaxSize()) {
            for (row in 0 until rows) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { (totalHeightPx * rowWeights[row]).toDp() }),
                ) {
                    for (col in 0 until columns) {
                        val index = row * columns + col
                        if (index < sessions.size) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(with(density) { (totalWidthPx * columnWeights[col]).toDp() })
                                        .fillMaxHeight(),
                            ) {
                                val pane = sessions[index]
                                SessionPanel(
                                    session = pane,
                                    // From every pane, not the visible ones: a venue owns its clients
                                    // whether or not they are in the grid, and the overview counts them.
                                    venueClients = allSessions.filter { it.isClientOf(pane) },
                                    onFocusClient = onFocusSession,
                                    onEditVenueRules = onEditVenueRules?.let { edit -> { edit(pane) } },
                                    onMinimize = { onMinimize(pane, true) },
                                    dictionary = dictionary,
                                    viewMode = viewMode,
                                    onClose = { onCloseSession(pane) },
                                    // Move to the *visible* neighbour, and withhold the button at visible
                                    // position 0 rather than at real index 0 — otherwise the leftmost pane
                                    // grows a dead arrow whenever something is minimized ahead of it.
                                    onMoveLeft =
                                        if (index > 0 && onMoveSession != null) {
                                            { onMoveSession(pane, sessions[index - 1]) }
                                        } else {
                                            null
                                        },
                                    onMoveRight =
                                        if (index < sessions.size - 1 && onMoveSession != null) {
                                            { onMoveSession(pane, sessions[index + 1]) }
                                        } else {
                                            null
                                        },
                                    onConnect = { pane.reconnect() },
                                    onDisconnect = { pane.disconnect() },
                                    selectedMessage = selectedMessage,
                                    onSelectMessage = onSelectMessage,
                                    onDiffSelected = onDiffSelected,
                                    onPasteMessage = onPasteMessage,
                                    gridViewColumns = gridViewColumns,
                                    assertionResults = assertionResults,
                                    appSettings = appSettings,
                                    globalFilter = globalFilter,
                                    followedUids = followedUids,
                                    followedTraceIds = followedTraceIds,
                                    onFollowTrace = onFollowTrace,
                                    onUnfollowTrace = onUnfollowTrace,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            // Add vertical draggable divider between columns (except last column)
                            if (col < columns - 1 && index + 1 < sessions.size) {
                                VerticalDivider(
                                    onDrag = { dragAmount ->
                                        val deltaRatio = dragAmount / totalWidthPx
                                        val newLeftWeight = (columnWeights[col] + deltaRatio).coerceIn(0.1f, 0.9f)
                                        val newRightWeight = (columnWeights[col + 1] - deltaRatio).coerceIn(0.1f, 0.9f)

                                        // Only update if both weights are valid
                                        if (newLeftWeight > 0.1f && newRightWeight > 0.1f) {
                                            columnWeights[col] = newLeftWeight
                                            columnWeights[col + 1] = newRightWeight
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // Add horizontal draggable divider between rows (except last row)
                if (row < rows - 1) {
                    HorizontalDivider(
                        onDrag = { dragAmount ->
                            val deltaRatio = dragAmount / totalHeightPx
                            val newTopWeight = (rowWeights[row] + deltaRatio).coerceIn(0.1f, 0.9f)
                            val newBottomWeight = (rowWeights[row + 1] - deltaRatio).coerceIn(0.1f, 0.9f)

                            // Only update if both weights are valid
                            if (newTopWeight > 0.1f && newBottomWeight > 0.1f) {
                                rowWeights[row] = newTopWeight
                                rowWeights[row + 1] = newBottomWeight
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalDivider(
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .width(AppTheme.Separators.panelSeparatorWidth)
                .fillMaxHeight()
                .background(AppTheme.Separators.color)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x)
                    }
                },
    )
}

@Composable
private fun HorizontalDivider(
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(borderColor)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    }
                },
    )
}

@Composable
private fun SessionPanel(
    session: FixMessageSession,
    venueClients: List<FixMessageSession>,
    onFocusClient: (FixMessageSession) -> Unit,
    /** Opens this venue's rules, when this pane is one. Null for a conversation, which has none. */
    onEditVenueRules: (() -> Unit)? = null,
    /** Sends this pane to the strip. See [MinimizedStrip]. */
    onMinimize: () -> Unit = { session.setMinimized(true) },
    dictionary: FixDictionary,
    viewMode: FixMessageSession.ViewMode,
    onClose: (() -> Unit)?,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
    onConnect: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    selectedMessage: FixMessage? = null,
    onSelectMessage: ((FixMessage?) -> Unit)? = null,
    onDiffSelected: ((FixMessage, FixMessage) -> Unit)? = null,
    onPasteMessage: ((String) -> Unit)? = null,
    gridViewColumns: List<Int> = emptyList(),
    assertionResults: Map<FixMessage, com.knapsack.fixtool.model.scenario.StepResult> = emptyMap(),
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    globalFilter: MessageFilters.Global = MessageFilters.Global.NONE,
    followedUids: Set<Long>? = null,
    followedTraceIds: Set<String> = emptySet(),
    onFollowTrace: ((String) -> Unit)? = null,
    onUnfollowTrace: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var isAtBottom by remember { mutableStateOf(true) }
    var scrollToBottomTrigger by remember { mutableStateOf(0) }

    val messages by session.messages.collectAsState()
    val wrapText by session.wrapText.collectAsState()
    val searchVisible by session.searchVisible.collectAsState()
    val filterVisible by session.filterVisible.collectAsState()
    val groupedByConversation by session.groupByConversation.collectAsState()
    val filterRegex by session.filterRegex.collectAsState()
    val filterShowIncoming by session.filterShowIncoming.collectAsState()
    val filterShowOutgoing by session.filterShowOutgoing.collectAsState()
    val filterShowSeparator by session.filterShowSeparator.collectAsState()
    val filterMessageTypes by session.filterMessageTypes.collectAsState()
    val connectionState by session.connectionState.collectAsState()
    val recentlySentMessageTimestamp by session.recentlySentMessageTimestamp.collectAsState()
    val latencyTrackingEnabled by session.latencyTrackingEnabled.collectAsState()

    Column(
        modifier =
            modifier
                .border(1.dp, borderColor)
                .background(panelBackgroundColor),
    ) {
        // Panel header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(headerBackgroundColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Connect/Disconnect toggle button (replaces drag handle).
            //
            // Not for a venue: on one, this unbinds a port that every client on its list is sitting on,
            // and an unlabelled power icon says none of that. The overview draws it as a named Stop or
            // Start instead — the same button, in the same words, that its minimized chip carries.
            if (session.isVenue) {
                Unit
            } else if (connectionState.canConnect() && onConnect != null) {
                // Show connect button when disconnected
                TooltipIconButton(
                    tooltip = "Connect Session",
                    onClick = onConnect,
                    modifier = Modifier.size(buttonSize),
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Connect",
                        tint = iconTintColor, // Gray when disconnected
                        modifier = Modifier.size(iconSize),
                    )
                }
            } else if (connectionState.canDisconnect() && onDisconnect != null) {
                // Show disconnect button when connected
                TooltipIconButton(
                    tooltip = "Disconnect Session",
                    onClick = onDisconnect,
                    modifier = Modifier.size(buttonSize),
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Disconnect",
                        tint = connectedStateColor, // Green when connected
                        modifier = Modifier.size(iconSize),
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = session.title,
                color = titleTextColor,
                fontSize = 12.sp,
            )

            // **Messages this session threw away.** Shown in the header, next to the name, because it is a
            // fact about the session's whole log: everything below it is missing this many messages, and a
            // reader who does not know that will reasonably conclude the venue never sent them. Absent while
            // the count is zero — a badge that is always there is furniture, not a warning.
            val discarded by session.discarded.collectAsState()
            if (discarded > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                TooltipIconButton(
                    tooltip =
                        "$discarded message(s) received and discarded — FixTool could not ingest them fast " +
                            "enough, so they are missing from this log entirely. Not a venue problem, and " +
                            "not recoverable: they were never stored. Raising the session buffer deepens the " +
                            "burst it can absorb but does not raise the rate.",
                    onClick = {},
                    modifier = Modifier.size(buttonSize),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "$discarded messages discarded",
                        tint = AppTheme.Colors.warning,
                        modifier = Modifier.size(iconSize),
                    )
                }
                Text(
                    text = "$discarded lost",
                    color = AppTheme.Colors.warning,
                    fontSize = 10.sp,
                )
            }
            // The count its minimized chip also shows, so a pane reduces to something already seen
            // rather than to a new readout. Sits in the gap the header already leaves empty.
            if (!session.isVenue) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${messages.size}",
                    color = AppTheme.Colors.textDisabled,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Everything from here to the scroll button acts on the message grid, so a venue gets
            // none of it. Its pane has no grid: every message on the venue belongs to one of its
            // clients (see QuickFixService.deliver, whose every venue branch routes to a client
            // channel), so filtering, grouping, blank lines, clearing and scroll-to-bottom were
            // eleven controls of which four did anything. The filter was the worst of them, drawing
            // a working-looking regex box above a list it could not touch.
            if (!session.isVenue) {
                // Wrap text toggle (RAW mode only)
                Spacer(modifier = Modifier.width(4.dp))

                if (viewMode == FixMessageSession.ViewMode.RAW) {
                    TooltipIconButton(
                        tooltip = if (wrapText) "Wrap: On (click to unwrap)" else "Wrap: Off (click to wrap)",
                        onClick = { session.toggleWrapText() },
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            imageVector = if (wrapText) Icons.Default.WrapText else Icons.Default.Notes,
                            contentDescription = "Toggle Text Wrap",
                            tint = iconTintColor,
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Search button (RAW mode only)
                    Spacer(modifier = Modifier.width(2.dp))

                    TooltipIconButton(
                        tooltip = if (searchVisible) "Hide Search" else "Show Search (Ctrl+F)",
                        onClick = { session.toggleSearch() },
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Toggle Search",
                            tint = if (searchVisible) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Filter button (available for both RAW and PARSED modes)
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip = if (filterVisible) "Hide Filter" else "Show Filter (Regex)",
                    onClick = { session.toggleFilter() },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = "Toggle Filter",
                        tint = if (filterVisible) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Group this pane's grid by business exchange — per session, like the filter.
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip =
                        if (groupedByConversation) {
                            "Conversations: On (click for a flat list)"
                        } else {
                            "Conversations: Off (click to group by exchange)"
                        },
                    onClick = { session.toggleGroupByConversation() },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = "Group by Conversation",
                        tint = if (groupedByConversation) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Add blank line button
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip = "Add Blank Line",
                    onClick = { session.addSeparator() },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Blank Line",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Clear session button
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip = "Clear All Messages",
                    onClick = { session.clearMessages() },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear All Messages",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Scroll to bottom button
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip = "Scroll to Bottom",
                    onClick = { scrollToBottomTrigger++ },
                    enabled = !isAtBottom,
                    modifier = Modifier.size(buttonSize),
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Scroll to bottom",
                        tint = if (!isAtBottom) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }

            // Leaves the layout for a chip in the strip above (see MinimizedStrip). Not a close:
            // the session keeps running and keeps its log, which closing does not.
            Spacer(modifier = Modifier.width(2.dp))

            TooltipIconButton(
                tooltip = "Minimize Pane",
                onClick = onMinimize,
                modifier = Modifier.size(buttonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Minimize Pane",
                    tint = iconTintColor,
                    modifier = Modifier.size(iconSize),
                )
            }

            // Move left button
            if (onMoveLeft != null) {
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip = "Move Session Left",
                    onClick = onMoveLeft,
                    modifier = Modifier.size(buttonSize),
                ) {
                    // An icon rather than a "◀" glyph: these two were the only text buttons in a row of
                    // icons, so they were the only ones with no content description — invisible to a
                    // screen reader and to a test.
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Move Session Left",
                        tint = iconTintColor,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }

            // Move right button
            if (onMoveRight != null) {
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip = "Move Session Right",
                    onClick = onMoveRight,
                    modifier = Modifier.size(buttonSize),
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Move Session Right",
                        tint = iconTintColor,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }

            if (onClose != null) {
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip = "Close Session",
                    onClick = onClose,
                    modifier = Modifier.size(buttonSize),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Session",
                        tint = iconTintColor,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }

        // A venue leaves first, above the filter bar rather than below it. Drawn after, the bar rendered
        // its whole regex box over the client list and filtered nothing — a control that looks live and
        // is not, on the one pane in the app with no message log to apply it to.
        if (session.isVenue) {
            AcceptorOverviewPane(
                venue = session,
                clients = venueClients,
                onFocusClient = onFocusClient,
                onEditRules = onEditVenueRules,
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        // Filter input section — the same bar the TABS layout draws (see SessionFilterBar).
        if (filterVisible) SessionFilterBar(session)

        // What this pane shows: its own filters, the toolbar's, and the followed trace — one function,
        // shared with the TABS layout so the two cannot answer differently. See [MessageFilters].
        val paneFilters =
            MessageFilters.Pane(
                regex = filterRegex,
                showIncoming = filterShowIncoming,
                showOutgoing = filterShowOutgoing,
                showSeparator = filterShowSeparator,
                messageTypes = filterMessageTypes,
            )
        val filteredMessages =
            remember(messages, paneFilters, globalFilter, followedUids) {
                MessageFilters.apply(messages, paneFilters, globalFilter, followedUids)
            }

        FixMessageDisplay(
            messages = filteredMessages,
            viewMode = viewMode,
            dictionary = dictionary,
            wrapText = wrapText,
            selectedMessage = selectedMessage,
            recentlySentMessageTimestamp = recentlySentMessageTimestamp,
            assertionResults = assertionResults,
            onSelectMessage = onSelectMessage,
            onDiffSelected = onDiffSelected,
            onPasteMessage = onPasteMessage,
            showDetailPanel = false,
            searchVisible = searchVisible,
            hideProtocolTags = appSettings.hideProtocolTags,
            gridViewColumns = gridViewColumns,
            appSettings = appSettings,
            onToggleSearch = { session.toggleSearch() },
            showLatencyColumn = latencyTrackingEnabled && appSettings.showLatencyColumn,
            getLatencyForMessage = if (latencyTrackingEnabled) { rawMessage -> session.getLatencyForMessage(rawMessage) } else null,
            latencyWarningThresholdMicros = appSettings.latencyWarningThresholdMicros,
            latencyCriticalThresholdMicros = appSettings.latencyCriticalThresholdMicros,
            onAtBottomChanged = { isAtBottom = it },
            scrollToBottomTrigger = scrollToBottomTrigger,
            // The pane's own way of looking, read off its own session — see FixMessageSession.
            groupByConversation = session.groupByConversation.collectAsState().value,
            collapsedConversations = session.collapsedConversations.collectAsState().value,
            onToggleConversation = { key -> session.toggleConversationCollapsed(key) },
            followedTraceIds = followedTraceIds,
            onFollowTrace = onFollowTrace,
            onUnfollowTrace = onUnfollowTrace,
            modifier = Modifier.weight(1f),
        )
    }
}

// Constants
private val borderColor = AppTheme.Colors.border
private val panelBackgroundColor = AppTheme.Colors.background
private val headerBackgroundColor = AppTheme.Colors.surface
private val titleTextColor = AppTheme.Colors.text
private val iconTintColor = AppTheme.Colors.textSecondary
private val connectedStateColor = AppTheme.Colors.success
private val activeColor = AppTheme.Colors.primary
private val filterPanelBackgroundColor = AppTheme.Colors.surfaceVariant
private val textFieldBackgroundColor = AppTheme.Colors.surface
private val placeholderTextColor = AppTheme.Colors.textDisabled
private val checkboxIconColor = AppTheme.Colors.background

private val iconSize = 16.dp
private val smallIconSize = 12.dp
private val buttonSize = 24.dp
private val textFieldBorderRadius = RoundedCornerShape(2.dp)

/**
 * **One pane's own filter panel** — regex, message types, direction and blank lines.
 *
 * Lifted out of [SessionPanel] because the TABS layout needs the same bar. Its button in the TabBar
 * toggled `filterVisible` and nothing was ever drawn or applied, so a filter typed in split view
 * vanished when the user switched layouts. One composable, one behaviour, both layouts.
 *
 * Every control writes straight to the session, which is where a pane's own way of looking belongs —
 * see [FixMessageSession.filterRegex]. The toolbar's global filter and the followed trace are ANDed on
 * top at render time and never appear here, because neither of them is this pane's to change.
 */
@Composable
internal fun SessionFilterBar(session: FixMessageSession) {
    val filterRegex by session.filterRegex.collectAsState()
    val filterShowIncoming by session.filterShowIncoming.collectAsState()
    val filterShowOutgoing by session.filterShowOutgoing.collectAsState()
    val filterShowSeparator by session.filterShowSeparator.collectAsState()
    val filterMessageTypes by session.filterMessageTypes.collectAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(filterPanelBackgroundColor)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Regex filter
        Text(
            text = "Regex:",
            color = iconTintColor,
            fontSize = 10.sp,
        )

        val regexInteractionSource = remember { MutableInteractionSource() }
        val regexIsFocused by regexInteractionSource.collectIsFocusedAsState()

        Box(
            modifier =
                Modifier
                    .weight(0.3f)
                    .height(22.dp),
        ) {
            BasicTextField(
                value = filterRegex,
                onValueChange = { session.setFilterRegex(it) },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(textFieldBackgroundColor, textFieldBorderRadius)
                        .border(
                            width = 1.dp,
                            color = if (regexIsFocused) activeColor else borderColor,
                            shape = textFieldBorderRadius,
                        ).padding(horizontal = 6.dp, vertical = 3.dp),
                textStyle =
                    TextStyle(
                        fontSize = 10.sp,
                        color = titleTextColor,
                        fontFamily = FontFamily.Monospace,
                    ),
                singleLine = true,
                cursorBrush = SolidColor(activeColor),
                interactionSource = regexInteractionSource,
                decorationBox = { innerTextField ->
                    if (filterRegex.isEmpty() && !regexIsFocused) {
                        Text(
                            text = "pattern...",
                            style =
                                TextStyle(
                                    fontSize = 10.sp,
                                    color = placeholderTextColor,
                                    fontFamily = FontFamily.Monospace,
                                ),
                        )
                    }
                    innerTextField()
                },
            )
        }

        // Message type filter
        Text(
            text = "MsgType:",
            color = iconTintColor,
            fontSize = 10.sp,
        )

        val msgTypeInteractionSource = remember { MutableInteractionSource() }
        val msgTypeIsFocused by msgTypeInteractionSource.collectIsFocusedAsState()

        Box(
            modifier =
                Modifier
                    .weight(0.3f)
                    .height(22.dp),
        ) {
            BasicTextField(
                value = filterMessageTypes,
                onValueChange = { session.setFilterMessageTypes(it) },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(textFieldBackgroundColor, textFieldBorderRadius)
                        .border(
                            width = 1.dp,
                            color = if (msgTypeIsFocused) activeColor else borderColor,
                            shape = textFieldBorderRadius,
                        ).padding(horizontal = 6.dp, vertical = 3.dp),
                textStyle =
                    TextStyle(
                        fontSize = 10.sp,
                        color = titleTextColor,
                        fontFamily = FontFamily.Monospace,
                    ),
                singleLine = true,
                cursorBrush = SolidColor(activeColor),
                interactionSource = msgTypeInteractionSource,
                decorationBox = { innerTextField ->
                    if (filterMessageTypes.isEmpty() && !msgTypeIsFocused) {
                        Text(
                            text = "R,S,AJ...",
                            style =
                                TextStyle(
                                    fontSize = 10.sp,
                                    color = placeholderTextColor,
                                    fontFamily = FontFamily.Monospace,
                                ),
                        )
                    }
                    innerTextField()
                },
            )
        }

        // Direction filter
        Text(
            text = "Dir:",
            color = iconTintColor,
            fontSize = 10.sp,
        )

        // Incoming checkbox
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .padding(end = 6.dp)
                    .clickable { session.setFilterShowIncoming(!filterShowIncoming) },
        ) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .background(
                            color = if (filterShowIncoming) activeColor else textFieldBackgroundColor,
                            shape = textFieldBorderRadius,
                        ).border(
                            width = 1.dp,
                            color = if (filterShowIncoming) activeColor else placeholderTextColor,
                            shape = textFieldBorderRadius,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (filterShowIncoming) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = checkboxIconColor,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
            Text(
                text = "In",
                color = iconTintColor,
                fontSize = 9.sp,
            )
        }

        // Outgoing checkbox
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .padding(end = 6.dp)
                    .clickable { session.setFilterShowOutgoing(!filterShowOutgoing) },
        ) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .background(
                            color = if (filterShowOutgoing) activeColor else textFieldBackgroundColor,
                            shape = textFieldBorderRadius,
                        ).border(
                            width = 1.dp,
                            color = if (filterShowOutgoing) activeColor else placeholderTextColor,
                            shape = textFieldBorderRadius,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (filterShowOutgoing) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = checkboxIconColor,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
            Text(
                text = "Out",
                color = iconTintColor,
                fontSize = 9.sp,
            )
        }

        // Blank line checkbox
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clickable { session.setFilterShowSeparator(!filterShowSeparator) },
        ) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .background(
                            color = if (filterShowSeparator) activeColor else textFieldBackgroundColor,
                            shape = textFieldBorderRadius,
                        ).border(
                            width = 1.dp,
                            color = if (filterShowSeparator) activeColor else placeholderTextColor,
                            shape = textFieldBorderRadius,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (filterShowSeparator) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = checkboxIconColor,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
            Text(
                text = "Blank",
                color = iconTintColor,
                fontSize = 9.sp,
            )
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // Close filter button
        TooltipIconButton(
            tooltip = "Hide Filter",
            onClick = { session.toggleFilter() },
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Hide Filter",
                tint = iconTintColor,
                modifier = Modifier.size(smallIconSize),
            )
        }
    }
}
