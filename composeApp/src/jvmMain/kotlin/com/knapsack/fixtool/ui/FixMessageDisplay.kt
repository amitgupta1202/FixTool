package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession.ViewMode
import com.knapsack.fixtool.model.Separator
import com.knapsack.fixtool.service.ConversationRows
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun FixMessageDisplay(
    messages: List<AppMessage>,
    viewMode: ViewMode,
    dictionary: FixDictionary,
    wrapText: Boolean = true,
    selectedMessage: FixMessage? = null,
    recentlySentMessageTimestamp: java.time.LocalDateTime? = null,
    assertionResults: Map<FixMessage, com.knapsack.fixtool.model.scenario.StepResult> = emptyMap(),
    onSelectMessage: ((FixMessage?) -> Unit)? = null,
    onDiffSelected: ((FixMessage, FixMessage) -> Unit)? = null,
    onPasteMessage: ((String) -> Unit)? = null,
    showDetailPanel: Boolean = false,
    searchVisible: Boolean = false,
    hideProtocolTags: Boolean = true,
    gridViewColumns: List<Int> = emptyList(),
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    onToggleSearch: () -> Unit = {},
    // Latency tracking parameters
    showLatencyColumn: Boolean = false,
    getLatencyForMessage: ((String) -> Long?)? = null,
    latencyWarningThresholdMicros: Long = 100_000L,
    latencyCriticalThresholdMicros: Long = 500_000L,
    /**
     * Group the grid by business exchange. A SECOND, independent switch — deliberately not a third
     * state of the RAW/PARSED toggle, because how a row renders and how rows relate are orthogonal
     * axes, and folding them into one selector would lose "grouped, in raw form".
     */
    groupByConversation: Boolean = false,
    collapsedConversations: Set<String> = emptySet(),
    onToggleConversation: (String) -> Unit = {},
    onAtBottomChanged: (Boolean) -> Unit = {},
    scrollToBottomTrigger: Int = 0,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var detailPanelSplitRatio by remember { mutableStateOf(0.3f) } // Detail panel takes 30% by default

    // Auto-scroll mode - automatically scroll to bottom when new messages arrive (default true, like terminal)
    var autoScroll by remember { mutableStateOf(true) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableStateOf(0) }

    // Find all matches in messages
    val searchMatches =
        remember(messages, searchQuery) {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                messages.flatMapIndexed { index, message ->
                    val text = message.toDisplayString() // Search in full display text
                    val matches = mutableListOf<Pair<Int, Int>>() // message index, char offset
                    var startIndex = 0
                    while (startIndex < text.length) {
                        val matchIndex = text.indexOf(searchQuery, startIndex, ignoreCase = true)
                        if (matchIndex != -1) {
                            matches.add(index to matchIndex)
                            startIndex = matchIndex + 1
                        } else {
                            break
                        }
                    }
                    matches
                }
            }
        }

    // Track if user is at the bottom
    // Simple check: if we can't scroll forward (down), we're at the bottom.
    // remember is load-bearing: without it a fresh DerivedState is allocated on every
    // recomposition, so the caching derivedStateOf exists to provide never happens and the
    // dependency on listState is re-subscribed each time.
    val isAtBottom by remember(listState) {
        derivedStateOf { !listState.canScrollForward }
    }

    // The RAW list's render rows. Every scroll on this listState must target a ROW index: with
    // grouping on, headers lengthen the list and a collapsed group shortens it, so a message
    // index used directly points at the wrong row. (The grid owns its own listState and rows.)
    val rawRows =
        remember(messages, groupByConversation, collapsedConversations, dictionary) {
            if (!groupByConversation) {
                ConversationRows.identityRows(messages.size)
            } else {
                ConversationRows.build(messages, dictionary, collapsedConversations)
            }
        }

    /** The row showing [messageIndex], or -1 while a collapsed group hides it. */
    fun rawRowIndexOf(messageIndex: Int): Int =
        rawRows.indexOfFirst { it is ConversationRows.Row.Message && it.index == messageIndex }

    // When a new message arrives (or the grouping refolds), scroll to the last row if autoScroll
    // is enabled — in grouped mode that is the bottom of the table, not the newest arrival.
    LaunchedEffect(rawRows.size) {
        if (rawRows.isNotEmpty() && autoScroll) {
            listState.scrollToItem(rawRows.size - 1)
        }
    }

    // Scroll to current search match — by row; a match hidden inside a collapsed group scrolls
    // nowhere rather than to an unrelated row.
    LaunchedEffect(currentMatchIndex, searchMatches.size, rawRows) {
        if (searchMatches.isNotEmpty() && currentMatchIndex in searchMatches.indices) {
            val (messageIndex, _) = searchMatches[currentMatchIndex]
            val rowIndex = rawRowIndexOf(messageIndex)
            if (rowIndex >= 0) {
                listState.scrollToItem(rowIndex)
            }
        }
    }

    // Search navigation functions
    fun nextMatch() {
        if (searchMatches.isNotEmpty()) {
            currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
        }
    }

    fun previousMatch() {
        if (searchMatches.isNotEmpty()) {
            currentMatchIndex =
                if (currentMatchIndex > 0) {
                    currentMatchIndex - 1
                } else {
                    searchMatches.size - 1
                }
        }
    }

    // Reset match index when search query changes
    LaunchedEffect(searchQuery) {
        currentMatchIndex = 0
    }

    // Message navigation functions for keyboard navigation
    fun navigateToPreviousMessage() {
        val fixMessages = messages.filterIsInstance<FixMessage>()
        if (fixMessages.isEmpty() || onSelectMessage == null) return

        val currentIndex =
            if (selectedMessage != null) {
                fixMessages.indexOf(selectedMessage)
            } else {
                fixMessages.size // Start from end if nothing selected
            }

        val previousIndex = if (currentIndex > 0) currentIndex - 1 else 0
        onSelectMessage(fixMessages[previousIndex])

        // Scroll to the selected message, by row
        coroutineScope.launch {
            val rowIndex = rawRowIndexOf(messages.indexOf(fixMessages[previousIndex]))
            if (rowIndex >= 0) {
                listState.scrollToItem(rowIndex)
            }
        }
    }

    fun navigateToNextMessage() {
        val fixMessages = messages.filterIsInstance<FixMessage>()
        if (fixMessages.isEmpty() || onSelectMessage == null) return

        val currentIndex =
            if (selectedMessage != null) {
                fixMessages.indexOf(selectedMessage)
            } else {
                -1 // Start from beginning if nothing selected
            }

        val nextIndex = if (currentIndex < fixMessages.size - 1) currentIndex + 1 else fixMessages.size - 1
        onSelectMessage(fixMessages[nextIndex])

        // Scroll to the selected message, by row
        coroutineScope.launch {
            val rowIndex = rawRowIndexOf(messages.indexOf(fixMessages[nextIndex]))
            if (rowIndex >= 0) {
                listState.scrollToItem(rowIndex)
            }
        }
    }

    // Detect when user scrolls and manage auto-scroll state
    // Disable auto-scroll when user scrolls up, re-enable when they scroll back to bottom
    LaunchedEffect(Unit) {
        snapshotFlow {
            listState.isScrollInProgress to isAtBottom
        }.collect { (isScrolling, atBottom) ->
            if (isScrolling) {
                // If user is actively scrolling and not at bottom, disable auto-scroll
                if (!atBottom) {
                    autoScroll = false
                } else {
                    // If user scrolled back to bottom, re-enable auto-scroll
                    autoScroll = true
                }
            }
        }
    }

    // Wrap content in split pane if detail panel is shown
    if (showDetailPanel) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val maxWidthPx = with(density) { maxWidth.toPx() }

            Row(modifier = Modifier.fillMaxSize()) {
                // Left panel - Message display
                Box(modifier = Modifier.weight(1f)) {
                    MessageDisplayContent(
                        viewMode = viewMode,
                        messages = messages,
                        rows = rawRows,
                        listState = listState,
                        isAtBottom = isAtBottom,
                        autoScroll = autoScroll,
                        coroutineScope = coroutineScope,
                        wrapText = wrapText,
                        selectedMessage = selectedMessage,
                        onSelectMessage = onSelectMessage,
                        dictionary = dictionary,
                        recentlySentMessageTimestamp = recentlySentMessageTimestamp,
                        assertionResults = assertionResults,
                        onEnableAutoScroll = { autoScroll = true },
                        searchQuery = searchQuery,
                        searchMatches = searchMatches,
                        currentMatchIndex = currentMatchIndex,
                        searchVisible = searchVisible,
                        hideProtocolTags = hideProtocolTags,
                        gridViewColumns = gridViewColumns,
                        appSettings = appSettings,
                        onToggleSearch = onToggleSearch,
                        onSearchQueryChange = { searchQuery = it },
                        onNextMatch = ::nextMatch,
                        onPreviousMatch = ::previousMatch,
                        onNavigatePrevious = ::navigateToPreviousMessage,
                        onNavigateNext = ::navigateToNextMessage,
                        showLatencyColumn = showLatencyColumn,
                        getLatencyForMessage = getLatencyForMessage,
                        latencyWarningThresholdMicros = latencyWarningThresholdMicros,
                        latencyCriticalThresholdMicros = latencyCriticalThresholdMicros,
                        onAtBottomChanged = onAtBottomChanged,
                        scrollToBottomTrigger = scrollToBottomTrigger,
                        groupByConversation = groupByConversation,
                        collapsedConversations = collapsedConversations,
                        onToggleConversation = onToggleConversation,
                    )
                }

                // Resizable divider
                Box(
                    modifier =
                        Modifier
                            .width(AppTheme.Separators.panelSeparatorWidth)
                            .fillMaxHeight()
                            .background(AppTheme.Separators.color)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    // Invert drag direction since detail panel is on the right
                                    val newOffset = detailPanelSplitRatio - (dragAmount.x / maxWidthPx)
                                    detailPanelSplitRatio = newOffset.coerceIn(0.2f, 0.6f)
                                }
                            },
                )

                // Right panel - Message detail
                Box(
                    modifier =
                        Modifier.width(
                            with(density) { (maxWidthPx * detailPanelSplitRatio).toDp() },
                        ),
                ) {
                    MessageDetailPanel(
                        message = selectedMessage,
                        dictionary = dictionary,
                        onClose = { onSelectMessage?.invoke(null) },
                        onPasteMessage = onPasteMessage,
                        appSettings = appSettings,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    } else {
        MessageDisplayContent(
            viewMode = viewMode,
            messages = messages,
            rows = rawRows,
            listState = listState,
            isAtBottom = isAtBottom,
            autoScroll = autoScroll,
            coroutineScope = coroutineScope,
            wrapText = wrapText,
            selectedMessage = selectedMessage,
            onSelectMessage = onSelectMessage,
            onDiffSelected = onDiffSelected,
            dictionary = dictionary,
            recentlySentMessageTimestamp = recentlySentMessageTimestamp,
            assertionResults = assertionResults,
            onEnableAutoScroll = { autoScroll = true },
            searchQuery = searchQuery,
            searchMatches = searchMatches,
            currentMatchIndex = currentMatchIndex,
            searchVisible = searchVisible,
            hideProtocolTags = hideProtocolTags,
            gridViewColumns = gridViewColumns,
            appSettings = appSettings,
            onToggleSearch = onToggleSearch,
            onSearchQueryChange = { searchQuery = it },
            onNextMatch = ::nextMatch,
            onPreviousMatch = ::previousMatch,
            onNavigatePrevious = ::navigateToPreviousMessage,
            onNavigateNext = ::navigateToNextMessage,
            showLatencyColumn = showLatencyColumn,
            getLatencyForMessage = getLatencyForMessage,
            latencyWarningThresholdMicros = latencyWarningThresholdMicros,
            latencyCriticalThresholdMicros = latencyCriticalThresholdMicros,
            onAtBottomChanged = onAtBottomChanged,
            scrollToBottomTrigger = scrollToBottomTrigger,
            groupByConversation = groupByConversation,
            collapsedConversations = collapsedConversations,
            onToggleConversation = onToggleConversation,
            modifier = modifier,
        )
    }
}

@Composable
private fun MessageDisplayContent(
    viewMode: ViewMode,
    messages: List<AppMessage>,
    /**
     * The render rows for [messages], built ONCE by the caller.
     *
     * This used to be rebuilt here, in a second `remember` with the same keys as the caller's — so a
     * grouped pane ran the whole grouping twice per 100ms message update, over the whole buffer, to
     * produce two identical lists. The caller needs its copy for scroll targeting; this needs the same
     * one to draw. Passing it is what makes them the same list rather than two derivations that agree
     * by construction until one of them changes.
     */
    rows: List<ConversationRows.Row>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isAtBottom: Boolean,
    autoScroll: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    wrapText: Boolean,
    selectedMessage: FixMessage?,
    onSelectMessage: ((FixMessage?) -> Unit)?,
    onDiffSelected: ((FixMessage, FixMessage) -> Unit)? = null,
    dictionary: FixDictionary,
    recentlySentMessageTimestamp: java.time.LocalDateTime? = null,
    assertionResults: Map<FixMessage, com.knapsack.fixtool.model.scenario.StepResult> = emptyMap(),
    onEnableAutoScroll: () -> Unit = {},
    searchQuery: String = "",
    searchMatches: List<Pair<Int, Int>> = emptyList(),
    currentMatchIndex: Int = 0,
    searchVisible: Boolean = false,
    hideProtocolTags: Boolean = true,
    gridViewColumns: List<Int> = emptyList(),
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    onToggleSearch: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onNextMatch: () -> Unit = {},
    onPreviousMatch: () -> Unit = {},
    onNavigatePrevious: () -> Unit = {},
    onNavigateNext: () -> Unit = {},
    // Latency tracking parameters
    showLatencyColumn: Boolean = false,
    getLatencyForMessage: ((String) -> Long?)? = null,
    latencyWarningThresholdMicros: Long = 100_000L,
    latencyCriticalThresholdMicros: Long = 500_000L,
    onAtBottomChanged: (Boolean) -> Unit = {},
    scrollToBottomTrigger: Int = 0,
    groupByConversation: Boolean = false,
    collapsedConversations: Set<String> = emptySet(),
    onToggleConversation: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    // Request focus when the component is first displayed
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    when (viewMode) {
        ViewMode.PARSED -> {
            // Parsed view only - hierarchical grid with all messages
            // Messages are already filtered in SplitView, just display them
            Box(
                modifier =
                    modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        onNavigatePrevious()
                                        true
                                    }

                                    Key.DirectionDown -> {
                                        onNavigateNext()
                                        true
                                    }

                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
            ) {
                HierarchicalGridView(
                    messages = messages,
                    dictionary = dictionary,
                    hideProtocolTags = hideProtocolTags,
                    gridViewColumns = gridViewColumns,
                    selectedMessage = selectedMessage,
                    onSelectMessage = onSelectMessage,
                    onDiffSelected = onDiffSelected,
                    recentlySentMessageTimestamp = recentlySentMessageTimestamp,
                    assertionResults = assertionResults,
                    appSettings = appSettings,
                    showLatencyColumn = showLatencyColumn,
                    getLatencyForMessage = getLatencyForMessage,
                    latencyWarningThresholdMicros = latencyWarningThresholdMicros,
                    latencyCriticalThresholdMicros = latencyCriticalThresholdMicros,
                    onAtBottomChanged = onAtBottomChanged,
                    scrollToBottomTrigger = scrollToBottomTrigger,
                    groupByConversation = groupByConversation,
                    collapsedConversations = collapsedConversations,
                    onToggleConversation = onToggleConversation,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(messageBackgroundColor),
                )
            }
        }

        ViewMode.RAW -> {
            // Raw view only
            val horizontalScrollState = rememberScrollState()

            // Scroll to selected message when it changes — by row; -1 means a collapsed group
            // hides it, and scrolling nowhere beats jumping to an unrelated row.
            LaunchedEffect(selectedMessage, rows) {
                if (selectedMessage != null) {
                    val messageIndex = messages.indexOf(selectedMessage)
                    val rowIndex =
                        if (messageIndex < 0) {
                            -1
                        } else {
                            rows.indexOfFirst { it is ConversationRows.Row.Message && it.index == messageIndex }
                        }
                    if (rowIndex >= 0) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(rowIndex)
                        }
                    }
                }
            }

            // Report isAtBottom changes to parent
            LaunchedEffect(isAtBottom) {
                onAtBottomChanged(isAtBottom)
            }

            // React to scroll-to-bottom trigger from parent
            LaunchedEffect(scrollToBottomTrigger) {
                if (scrollToBottomTrigger > 0 && messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                    onEnableAutoScroll()
                }
            }

            Box(
                modifier =
                    modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        onNavigatePrevious()
                                        true
                                    }

                                    Key.DirectionDown -> {
                                        onNavigateNext()
                                        true
                                    }

                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(if (!wrapText) Modifier.horizontalScroll(horizontalScrollState) else Modifier),
                ) {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(messageBackgroundColor)
                                    .padding(
                                        start = 8.dp,
                                        top = 8.dp,
                                        bottom = if (wrapText) 8.dp else 24.dp,
                                        end = 16.dp,
                                    ),
                        ) {
                            itemsIndexed(rows) { _, row ->
                                if (row is ConversationRows.Row.Header) {
                                    ConversationHeaderRow(
                                        header = row,
                                        onToggle = { onToggleConversation(row.key) },
                                    )
                                    return@itemsIndexed
                                }
                                val index = (row as ConversationRows.Row.Message).index
                                val message = messages[index]
                                // Find matches for this message
                                val messageMatches = searchMatches.filter { it.first == index }
                                val isCurrentMatch =
                                    searchMatches.isNotEmpty() &&
                                        currentMatchIndex in searchMatches.indices &&
                                        searchMatches[currentMatchIndex].first == index

                                MessageRow(
                                    message = message,
                                    messageIndex = index,
                                    wrapText = wrapText,
                                    isSelected = message == selectedMessage,
                                    onSelect =
                                        if (onSelectMessage != null) {
                                            { onSelectMessage(message as FixMessage) }
                                        } else {
                                            null
                                        },
                                    searchQuery = searchQuery,
                                    messageMatches = messageMatches,
                                    currentMatchOffset =
                                        if (isCurrentMatch && searchMatches.isNotEmpty()) {
                                            searchMatches[currentMatchIndex].second
                                        } else {
                                            -1
                                        },
                                    appSettings = appSettings,
                                )
                            }
                        }
                    }
                }

                // Scrollbar
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(end = 4.dp),
                )

                // Horizontal scrollbar (shown when text is not wrapped)
                if (!wrapText) {
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(horizontalScrollState),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 20.dp, bottom = 4.dp)
                                .height(8.dp),
                    )
                }

                // Search bar
                if (searchVisible) {
                    SearchBar(
                        searchQuery = searchQuery,
                        onSearchQueryChange = onSearchQueryChange,
                        searchMatches = searchMatches,
                        currentMatchIndex = currentMatchIndex,
                        onNextMatch = onNextMatch,
                        onPreviousMatch = onPreviousMatch,
                        onClose = onToggleSearch,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                    )
                }

            }
        }
    }
}

/**
 * **The summary line, which is the feature** — the nesting is only how you drill in.
 *
 * A reader who does not know that `150=F` means Trade can still read
 * *"RFQ-A1 · EUR/USD 10,000,000 · QuoteRequest ×1, Quote ×2 · Filled · 643ms"*, and that is the whole
 * reason this view exists. Everything on it is quotation or arithmetic over what arrived — see
 * [Conversations.Summary] for the rule about what a header may claim.
 */
@Composable
private fun ConversationHeaderRow(
    header: ConversationRows.Row.Header,
    onToggle: () -> Unit,
) {
    val summary = header.summary
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .background(AppTheme.Colors.surfaceVariant)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (header.collapsed) "▶" else "▼",
            color = AppTheme.Colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = header.label,
            color = AppTheme.Colors.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        // Everything after the label is secondary: the label is what a reader scans for.
        Text(
            text = buildString {
                summary?.instrument?.let { append(" · ").append(it) }
                summary?.quantity?.let { append(" ").append(it) }
                append(" · ")
                append(
                    summary
                        ?.composition
                        ?.joinToString(", ") { "${it.name ?: it.messageType} ×${it.count}" }
                        ?: "${header.count} message${if (header.count == 1) "" else "s"}",
                )
                // The dictionary's word for what the counterparty last said about state. Absent where
                // nothing said anything — never inferred from the absence.
                summary?.status?.valueName?.let { append(" · ").append(it) }
                summary?.let { append(" · ").append(it.elapsedMillis).append("ms") }
            },
            color = AppTheme.Colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun MessageRow(
    message: AppMessage,
    messageIndex: Int,
    wrapText: Boolean = true,
    isSelected: Boolean = false,
    onSelect: (() -> Unit)? = null,
    searchQuery: String = "",
    messageMatches: List<Pair<Int, Int>> = emptyList(),
    currentMatchOffset: Int = -1,
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
) {
    // Special handling for separator/blank lines
    when (message) {
        is Separator -> {
            // Use a large fixed width to ensure visibility in unwrapped/horizontal scroll mode
            // fillMaxWidth() doesn't work inside horizontal scroll context
            val separatorWidth = if (wrapText) Modifier.fillMaxWidth() else Modifier.width(5000.dp)

            Box(
                modifier =
                    Modifier
                        .then(separatorWidth)
                        .height(20.dp) // Minimum height for blank line to be visible
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Dotted line separator for visual indication
                Canvas(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .height(1.dp),
                ) {
                    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    drawLine(
                        color = separatorLineColor,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1f,
                        pathEffect = pathEffect,
                    )
                }
            }
        }

        is FixMessage -> {
            val textColor = getMessageTextColor(message, messageIndex, appSettings)
            val backgroundColor = if (isSelected) selectedBackgroundColor else Color.Transparent
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .hoverable(interactionSource)
                        .then(
                            if (onSelect != null) {
                                Modifier.clickable { onSelect() }
                            } else {
                                Modifier
                            },
                        ).padding(vertical = 2.dp),
            ) {
                // Text with proper wrapping and search highlighting
                val displayText = message.toDisplayString()
                val highlightedText =
                    if (searchQuery.isNotBlank() && messageMatches.isNotEmpty()) {
                        buildAnnotatedString {
                            var lastIndex = 0

                            // Get all match offsets for this message (sorted)
                            // Offsets are now already in displayText coordinates (since we search in toDisplayString())
                            val matchOffsets = messageMatches.map { it.second }.sorted()

                            for (offset in matchOffsets) {
                                // Add text before match with default color
                                if (offset > lastIndex) {
                                    withStyle(SpanStyle(color = textColor)) {
                                        append(displayText.substring(lastIndex, offset))
                                    }
                                }

                                // Add highlighted match
                                val matchEnd = (offset + searchQuery.length).coerceAtMost(displayText.length)
                                val isCurrentMatch = offset == currentMatchOffset

                                withStyle(
                                    SpanStyle(
                                        background =
                                            if (isCurrentMatch) {
                                                currentMatchHighlightColor // Bright orange for current match
                                            } else {
                                                otherMatchHighlightColor.copy(alpha = 0.4f) // Semi-transparent yellow for other matches
                                            },
                                        color = if (isCurrentMatch) Color.Black else textColor,
                                    ),
                                ) {
                                    append(displayText.substring(offset, matchEnd))
                                }

                                lastIndex = matchEnd
                            }

                            // Add remaining text with default color
                            if (lastIndex < displayText.length) {
                                withStyle(SpanStyle(color = textColor)) {
                                    append(displayText.substring(lastIndex))
                                }
                            }
                        }
                    } else {
                        AnnotatedString(displayText)
                    }

                Text(
                    text = highlightedText,
                    color = if (searchQuery.isBlank() || messageMatches.isEmpty()) textColor else Color.Unspecified,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    softWrap = wrapText,
                    maxLines = if (wrapText) Int.MAX_VALUE else 1,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(end = if (isHovered || isSelected) 28.dp else 0.dp),
                )

                // Copy button positioned at the right side (shown on hover or when selected)
                if (isHovered || isSelected) {
                    Row(
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TooltipIconButton(
                            tooltip = "Copy Message",
                            onClick = {
                                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                clipboard.setContents(StringSelection(message.rawMessage), null)
                            },
                            modifier = Modifier.size(iconSize),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = iconTintColor,
                                modifier = Modifier.size(smallIconSize),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchMatches: List<Pair<Int, Int>>,
    currentMatchIndex: Int,
    onNextMatch: () -> Unit,
    onPreviousMatch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    // Auto-focus when search bar becomes visible
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier =
            modifier
                .background(searchBarBackgroundColor)
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Search input
        Box(
            modifier =
                Modifier
                    .width(200.dp)
                    .height(28.dp),
        ) {
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .background(textFieldBackgroundColor, textFieldBorderRadius)
                        .border(
                            width = 1.dp,
                            color = if (isFocused) activeColor else borderColor,
                            shape = textFieldBorderRadius,
                        ).padding(horizontal = 6.dp, vertical = 6.dp),
                textStyle =
                    TextStyle(
                        fontSize = 11.sp,
                        color = textPrimaryColor,
                    ),
                singleLine = true,
                cursorBrush = SolidColor(activeColor),
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    if (searchQuery.isEmpty() && !isFocused) {
                        Text(
                            text = "Search...",
                            style =
                                TextStyle(
                                    fontSize = 11.sp,
                                    color = placeholderTextColor,
                                ),
                        )
                    }
                    innerTextField()
                },
            )
        }

        // Match counter
        if (searchMatches.isNotEmpty()) {
            Text(
                text = "${currentMatchIndex + 1} of ${searchMatches.size}",
                color = iconTintColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // Previous button
        IconButton(
            onClick = onPreviousMatch,
            enabled = searchMatches.isNotEmpty(),
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Previous match",
                tint = if (searchMatches.isNotEmpty()) iconTintColor else placeholderTextColor,
                modifier = Modifier.size(iconSize),
            )
        }

        // Next button
        IconButton(
            onClick = onNextMatch,
            enabled = searchMatches.isNotEmpty(),
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Next match",
                tint = if (searchMatches.isNotEmpty()) iconTintColor else placeholderTextColor,
                modifier = Modifier.size(iconSize),
            )
        }

        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close search",
                tint = iconTintColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

// Constants
private val messageBackgroundColor = AppTheme.Colors.background
private val selectedBackgroundColor = AppTheme.Colors.border
private val borderColor = AppTheme.Colors.border
private val rejectionBrightColor = AppTheme.Colors.messageRejection
private val rejectionDullColor = Color(0xFFC55A64) // Keep unique dull variant
private val incomingBrightColor = AppTheme.Colors.messageIncoming
private val incomingDullColor = Color(0xFF3DA89F) // Keep unique dull variant
private val outgoingBrightColor = AppTheme.Colors.messageOutgoing
private val outgoingDullColor = Color(0xFF4A7DAF) // Keep unique dull variant
private val iconTintColor = AppTheme.Colors.textSecondary
private val placeholderTextColor = AppTheme.Colors.textDisabled
private val textPrimaryColor = AppTheme.Colors.text
private val searchBarBackgroundColor = Color(0xFF2D2D2D) // Keep unique search bar color
private val textFieldBackgroundColor = AppTheme.Colors.surface
private val separatorLineColor = Color(0xFF4A4A4A) // Keep unique separator line color
private val currentMatchHighlightColor = AppTheme.Colors.highlightCurrent
private val otherMatchHighlightColor = Color(0xFFFFEB3B) // Keep unique yellow for other matches
private val activeColor = AppTheme.Colors.primary

private val textFieldBorderRadius = RoundedCornerShape(2.dp)
private val iconSize = 20.dp
private val smallIconSize = 14.dp

// Helper function for message row text color
private fun getMessageTextColor(
    message: FixMessage,
    messageIndex: Int,
    appSettings: com.knapsack.fixtool.model.AppSettings,
): Color {
    val isBright = messageIndex % 2 == 1
    return appSettings.messageColorScheme.getMessageColor(
        message.direction,
        message.isRejectionOrLogout(),
        isBright,
    )
}
