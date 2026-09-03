package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.Separator
import com.knapsack.fixtool.service.ConversationRows
import com.knapsack.fixtool.service.Conversations
import com.knapsack.fixtool.service.groupCountSafe
import kotlinx.coroutines.launch
import quickfix.Field
import quickfix.FieldMap
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Resize handle for adjusting column widths
 */
@Composable
private fun ResizeHandle(
    columnKey: String,
    columnWidths: MutableMap<String, androidx.compose.ui.unit.Dp>,
    modifier: Modifier = Modifier,
) {
    var dragOffset by remember { mutableStateOf(0f) }

    Box(
        modifier =
            modifier
                .width(1.dp)
                .fillMaxHeight()
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(columnKey) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            dragOffset = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount

                        // Update column width
                        val currentWidth = columnWidths[columnKey] ?: 120.dp
                        val newWidth = (currentWidth.value + dragOffset).dp

                        // Enforce min/max constraints
                        val constrainedWidth = newWidth.coerceIn(50.dp, 400.dp)
                        columnWidths[columnKey] = constrainedWidth

                        // Reset drag offset after updating width
                        dragOffset = 0f
                    }
                }.background(Color.Transparent),
    )
}

/**
 * Header row for the expanded grid showing column names and resize handles
 */
@Composable
private fun ExpandedGridHeader(
    columnWidths: MutableMap<String, androidx.compose.ui.unit.Dp>,
    modifier: Modifier = Modifier,
) {
    val totalWidth =
        (columnWidths["IconColumn"] ?: 40.dp) +
            (columnWidths["Tag"] ?: 120.dp) +
            (columnWidths["TagDescription"] ?: 200.dp) +
            (columnWidths["Value"] ?: 150.dp) +
            (columnWidths["ValueDescription"] ?: 250.dp)

    Row(
        modifier =
            modifier
                .height(20.dp)
                .width(totalWidth)
                .background(headerBackgroundColor),
    ) {
        // Icon column
        Box(
            modifier =
                Modifier
                    .width(columnWidths["IconColumn"] ?: 40.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "",
                color = headerTextColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Tag column
        Box(
            modifier =
                Modifier
                    .width((columnWidths["Tag"] ?: 120.dp) - 1.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Tag",
                color = headerTextColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        ResizeHandle("Tag", columnWidths)

        // Tag Description column
        Box(
            modifier =
                Modifier
                    .width((columnWidths["TagDescription"] ?: 200.dp) - 1.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Tag Description",
                color = headerTextColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        ResizeHandle("TagDescription", columnWidths)

        // Value column
        Box(
            modifier =
                Modifier
                    .width((columnWidths["Value"] ?: 150.dp) - 1.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Value",
                color = headerTextColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        ResizeHandle("Value", columnWidths)

        // Value Description column
        Box(
            modifier =
                Modifier
                    .width((columnWidths["ValueDescription"] ?: 250.dp) - 1.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Value Description",
                color = headerTextColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        ResizeHandle("ValueDescription", columnWidths)
    }
}

/**
 * Hierarchical grid view showing one row per FIX message
 *
 * Click behavior:
 * - Click chevron: Expand/collapse row (no message detail)
 * - Click row: Select message and show detail panel (no expand)
 * - Double-click row: Expand/collapse row (no message detail)
 *
 * Features:
 * - Search/filter support: Messages are filtered by parent component
 * - Keyboard navigation with arrow keys
 * - Expandable rows showing all FIX fields hierarchically
 * - Uses QuickFIX/J Message directly for accurate group handling
 */
@Composable
fun HierarchicalGridView(
    messages: List<AppMessage>,
    dictionary: FixDictionary,
    hideProtocolTags: Boolean,
    gridViewColumns: List<Int> = emptyList(),
    selectedMessage: FixMessage? = null,
    onSelectMessage: ((FixMessage?) -> Unit)? = null,
    /** The grid renders multi-selection; this is the door out for it — *"Diff selected"* on exactly two rows. */
    onDiffSelected: ((FixMessage, FixMessage) -> Unit)? = null,
    recentlySentMessageTimestamp: LocalDateTime? = null,
    assertionResults: Map<FixMessage, com.knapsack.fixtool.model.scenario.StepResult> = emptyMap(),
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    // Latency tracking parameters
    showLatencyColumn: Boolean = false,
    getLatencyForMessage: ((String) -> Long?)? = null,
    latencyWarningThresholdMicros: Long = 100_000L,
    latencyCriticalThresholdMicros: Long = 500_000L,
    onAtBottomChanged: (Boolean) -> Unit = {},
    scrollToBottomTrigger: Int = 0,
    /** Group the grid by business exchange (Option B) — off renders today's grid unchanged. */
    groupByConversation: Boolean = false,
    collapsedConversations: Set<String> = emptySet(),
    onToggleConversation: (String) -> Unit = {},
    /** Every correlation value in the app's followed trace — see [FixMessageDisplay]. */
    followedTraceIds: Set<String> = emptySet(),
    onFollowTrace: ((String) -> Unit)? = null,
    onUnfollowTrace: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Track which messages are expanded (key: AppMessage.uid — position-independent, see getMessageId)
    val expandedMessages = remember { mutableStateMapOf<String, Boolean>() }

    // Track which groups are expanded (key: "messageId_groupKey")
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // Multi-selection state
    val selectedMessageIds = remember { mutableStateSetOf<String>() }
    var lastClickedIndex by remember { mutableStateOf<Int?>(null) }

    // Helper function to get message ID.
    // Keyed on the message's own identity, never its list position: the session list is a ring
    // buffer, so an index-derived key changes for every surviving row once the buffer fills, which
    // both thrashes LazyColumn and reattaches expansion/selection to the wrong messages.
    fun getMessageId(message: AppMessage): String = message.uidKey

    // Helper to get selected FixMessages in order
    fun getSelectedFixMessages(): List<FixMessage> =
        messages.mapNotNull { msg ->
            if (msg is FixMessage && selectedMessageIds.contains(getMessageId(msg))) msg else null
        }

    // Clear multi-selection
    fun clearSelection() {
        selectedMessageIds.clear()
        lastClickedIndex = null
    }

    // Toggle single message selection (Ctrl/Cmd+Click)
    fun toggleMessageSelection(messageId: String, messageIndex: Int) {
        if (selectedMessageIds.contains(messageId)) {
            selectedMessageIds.remove(messageId)
        } else {
            selectedMessageIds.add(messageId)
        }
        lastClickedIndex = messageIndex
    }

    // Range selection (Shift+Click)
    fun selectRange(toIndex: Int) {
        val fromIndex = lastClickedIndex ?: toIndex
        val range = if (fromIndex <= toIndex) fromIndex..toIndex else toIndex..fromIndex

        messages.forEachIndexed { index, message ->
            if (message is FixMessage && index in range) {
                val messageId = getMessageId(message)
                selectedMessageIds.add(messageId)
            }
        }
        lastClickedIndex = toIndex
    }

    // Copy selected messages to clipboard
    fun copySelectedToClipboard() {
        val selected = getSelectedFixMessages()
        if (selected.isNotEmpty()) {
            val rawMessages = selected.joinToString("\n") { it.rawMessage }
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(rawMessages), null)
        }
    }

    // Save selected messages to file
    fun saveSelectedToFile() {
        val selected = getSelectedFixMessages()
        if (selected.isEmpty()) {
            return
        }
        coroutineScope.launch {
            val chosen =
                chooseFileToSave(
                    suggestedName = "messages_${System.currentTimeMillis()}",
                    extension = "fix",
                ) ?: return@launch
            withDefaultExtension(chosen, allowed = setOf("fix", "txt"), default = "fix")
                .writeText(selected.joinToString("\n") { it.rawMessage })
        }
    }

    // Select all messages
    fun selectAll() {
        messages.forEach { message ->
            if (message is FixMessage) {
                selectedMessageIds.add(getMessageId(message))
            }
        }
    }

    // Column width state management
    val originalWidths =
        remember(showLatencyColumn) {
            val base =
                mapOf(
                    "Icon" to 40.dp,
                    "Time" to 120.dp,
                    "Dir" to 50.dp,
                    "SeqNum" to 70.dp,
                    "MsgType" to 100.dp,
                    "Summary" to 200.dp,
                )
            val latencyWidth = if (showLatencyColumn) mapOf("Latency" to 90.dp) else emptyMap()
            base + latencyWidth + gridViewColumns.associate { tag -> "Tag_$tag" to 120.dp }
        }

    val columnWidths =
        remember {
            mutableStateMapOf<String, androidx.compose.ui.unit.Dp>().apply {
                putAll(originalWidths)
            }
        }

    // What each expanded message needs of the shared columns, kept per message so the widths can
    // fit all of them rather than only the one opened last — see fitExpandedGridWidths.
    val expandedGridWidthContributions =
        remember { mutableStateMapOf<String, Map<String, androidx.compose.ui.unit.Dp>>() }

    // Expanded grid column widths (for the detail view when a message is expanded)
    val expandedGridColumnWidths =
        remember {
            mutableStateMapOf<String, androidx.compose.ui.unit.Dp>().apply {
                put("IconColumn", 40.dp)
                put("Tag", 120.dp)
                put("TagDescription", 200.dp)
                put("Value", 150.dp)
                put("ValueDescription", 250.dp)
            }
        }

    // Track which columns have been auto-fitted
    val autoFittedColumns = remember { mutableStateSetOf<String>() }

    // Auto-scroll state
    var autoScroll by remember { mutableStateOf(true) }

    // Track if user is at the bottom.
    // remember is load-bearing: without it a fresh DerivedState is allocated on every
    // recomposition, so the caching derivedStateOf exists to provide never happens and the
    // The render list: flat indices, or conversations spliced in. Hoisted above the scroll
    // effects because scroll targets are ROW indices — in grouped mode the header rows offset
    // every position, and a collapsed group removes its members from the list entirely, so a
    // message index used as an item index points at the wrong row (or past the end).
    val renderRows =
        remember(messages, groupByConversation, collapsedConversations, dictionary) {
            if (!groupByConversation) {
                ConversationRows.identityRows(messages.size)
            } else {
                ConversationRows.build(messages, dictionary, collapsedConversations)
            }
        }

    /** The row showing [messageIndex], or -1 while a collapsed group hides it. */
    fun rowIndexOf(messageIndex: Int): Int =
        renderRows.indexOfFirst { it is ConversationRows.Row.Message && it.index == messageIndex }

    // dependency on listState is re-subscribed each time.
    val isAtBottom by remember(listState) {
        derivedStateOf { !listState.canScrollForward }
    }

    // When a new message arrives (or the grouping refolds), scroll to the table's bottom if
    // autoScroll is enabled. Row count, not message count: in grouped mode the last row may be
    // the Ungrouped section rather than the newest arrival — "stick to the bottom of the table"
    // is the honest reading of auto-scroll there.
    LaunchedEffect(renderRows.size) {
        if (renderRows.isNotEmpty() && autoScroll) {
            // Wait for layout to complete before scrolling
            kotlinx.coroutines.delay(50)
            if (renderRows.isNotEmpty()) {
                listState.scrollToItem(renderRows.size - 1)
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

    // Report isAtBottom changes to parent
    LaunchedEffect(isAtBottom) {
        onAtBottomChanged(isAtBottom)
    }

    // React to scroll-to-bottom trigger from parent
    LaunchedEffect(scrollToBottomTrigger) {
        if (scrollToBottomTrigger > 0 && renderRows.isNotEmpty()) {
            listState.animateScrollToItem(renderRows.size - 1)
            autoScroll = true
        }
    }

    // Function to calculate optimal width for a column
    fun calculateOptimalWidth(
        columnKey: String,
        messages: List<AppMessage>,
    ): androidx.compose.ui.unit.Dp {
        // Minimum and maximum width constraints
        val minWidth = 50.dp
        val maxWidth = 400.dp

        // Estimate character widths (approximate for monospace font at 10sp)
        val charWidth = 7 // pixels per character approximately

        val contentSamples = mutableListOf<String>()

        when (columnKey) {
            "Icon" -> return 40.dp // Fixed size for icon
            "Time" -> contentSamples.add("HH:mm:ss.SSS") // Header template
            "Dir" -> contentSamples.add("[R]")
            "SeqNum" -> contentSamples.add("SeqNum")
            "MsgType" -> contentSamples.add("MsgType")
            "Summary" -> contentSamples.add("Summary") // Will get actual data below
            else -> {
                // Custom tag column
                val tag = columnKey.removePrefix("Tag_").toIntOrNull()
                if (tag != null) {
                    val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
                    contentSamples.add(fieldName)
                }
            }
        }

        // Sample last 100 messages for content (prioritizes recent/visible messages)
        // Using takeLast instead of take gives better representation of what user is likely viewing
        messages.filterIsInstance<FixMessage>().takeLast(100).forEach { msg ->
            when (columnKey) {
                "Time" -> {
                    contentSamples.add(msg.timestamp.format(GRID_TIME_FORMATTER))
                }

                "Dir" -> {} // Already handled above
                "SeqNum" -> {
                    // Sample the sequence number (tag 34)
                    val seqNum = extractTopLevelFieldValue(msg.quickfixMessage, 34)
                    if (seqNum.isNotEmpty()) {
                        contentSamples.add(seqNum)
                    }
                }

                "MsgType" -> {
                    // Only sample the message type value (e.g., "D", "8"), not the description
                    contentSamples.add(msg.messageType)
                }

                "Summary" -> {
                    // Sample the message type description (e.g., "NewOrderSingle")
                    val msgTypeDesc = dictionary.getFieldValueDescription(35, msg.messageType) ?: msg.messageType
                    contentSamples.add(msgTypeDesc)
                }

                else -> {
                    // Custom tag column
                    val tag = columnKey.removePrefix("Tag_").toIntOrNull()
                    if (tag != null) {
                        val value = extractTopLevelFieldValue(msg.quickfixMessage, tag)
                        if (value.isNotEmpty()) {
                            contentSamples.add(value)
                        }
                    }
                }
            }
        }

        // Calculate max content width
        val maxContentLength = contentSamples.maxOfOrNull { it.length } ?: 10
        val calculatedWidth = (maxContentLength * charWidth + 16).dp // +16 for padding

        return calculatedWidth.coerceIn(minWidth, maxWidth)
    }

    // Function to toggle column width between auto-fit and original
    fun toggleColumnWidth(columnKey: String) {
        if (autoFittedColumns.contains(columnKey)) {
            // Restore to original width
            columnWidths[columnKey] = originalWidths[columnKey] ?: 120.dp
            autoFittedColumns.remove(columnKey)
        } else {
            // Auto-fit to content
            columnWidths[columnKey] = calculateOptimalWidth(columnKey, messages)
            autoFittedColumns.add(columnKey)
        }
    }

    // Scroll to selected message when it changes — by ROW index, which differs from the message
    // index whenever headers are spliced in. -1 means a collapsed group hides it; scrolling
    // nowhere is right, jumping to an unrelated row was not.
    LaunchedEffect(selectedMessage, renderRows) {
        if (selectedMessage != null) {
            val rowIndex = messages.indexOf(selectedMessage).takeIf { it >= 0 }?.let(::rowIndexOf) ?: -1
            if (rowIndex >= 0) {
                coroutineScope.launch {
                    listState.animateScrollToItem(rowIndex)
                }
            }
        }
    }

    // Horizontal scroll state for columns that don't fit
    val horizontalScrollState = rememberScrollState()

    // Platform-specific shortcut display
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    val copyShortcut = if (isMac) "Cmd+C" else "Ctrl+C"
    val selectAllShortcut = if (isMac) "Cmd+A" else "Ctrl+A"

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(mainBackgroundColor)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        val isCtrlOrCmd = event.isCtrlPressed || event.isMetaPressed
                        when {
                            // Cmd/Ctrl+C - Copy selected messages
                            isCtrlOrCmd && event.key == Key.C -> {
                                if (selectedMessageIds.isNotEmpty()) {
                                    copySelectedToClipboard()
                                    true
                                } else {
                                    false
                                }
                            }
                            // Cmd/Ctrl+A - Select all
                            isCtrlOrCmd && event.key == Key.A -> {
                                selectAll()
                                true
                            }
                            // Escape - Clear selection
                            event.key == Key.Escape -> {
                                clearSelection()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Selection action bar (appears when messages are selected)
            if (selectedMessageIds.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(AppTheme.Colors.surfaceHeader)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Selection count
                    Text(
                        text = "${selectedMessageIds.size} message${if (selectedMessageIds.size > 1) "s" else ""} selected",
                        color = AppTheme.Colors.text,
                        fontSize = 11.sp,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Diff selected — enabled at exactly two. A message with no wireRaw is refused at the
                        // click, in words, by the ViewModel (invariant 3), not by a silently disabled button.
                        if (onDiffSelected != null) {
                            val selected = getSelectedFixMessages()
                            val canDiff = selected.size == 2
                            val diffClick =
                                if (canDiff) {
                                    Modifier.clickable { onDiffSelected(selected[0], selected[1]) }
                                } else {
                                    Modifier
                                }
                            Text(
                                text = "⇄ Diff selected",
                                color = if (canDiff) AppTheme.Colors.primary else AppTheme.Colors.textDisabled,
                                fontSize = 11.sp,
                                modifier =
                                    Modifier
                                        .then(diffClick)
                                        .padding(horizontal = 6.dp)
                                        .testTag("grid-diff-selected"),
                            )
                        }
                        // Copy button
                        TooltipIconButton(
                            tooltip = "Copy Selected Messages ($copyShortcut)",
                            onClick = { copySelectedToClipboard() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = AppTheme.Colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        // Save to file button
                        TooltipIconButton(
                            tooltip = "Save Selected Messages to File",
                            onClick = { saveSelectedToFile() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save",
                                tint = AppTheme.Colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        // Clear selection button
                        TooltipIconButton(
                            tooltip = "Clear Selection (Esc)",
                            onClick = { clearSelection() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Selection",
                                tint = AppTheme.Colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Main content with horizontal scroll support
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScrollState),
            ) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    // Header row
                    Row(
                        modifier =
                            Modifier
                                .background(headerBackgroundColor)
                                .height(24.dp),
                    ) {
                        // Checkbox column for Select All
                        val allFixMessages = messages.filterIsInstance<FixMessage>()
                        // Identity-keyed, so no `messages.indexOf(msg)` per element: that was an O(N^2)
                        // scan on every header recomposition, and it resolved by equality, so two
                        // identical messages both reported the first one's index.
                        val allSelected =
                            allFixMessages.isNotEmpty() &&
                                allFixMessages.all { msg ->
                                    selectedMessageIds.contains(getMessageId(msg))
                                }
                        val someSelected = selectedMessageIds.isNotEmpty() && !allSelected

                        Box(
                            modifier =
                                Modifier
                                    .width(24.dp)
                                    .fillMaxHeight()
                                    .border(0.5.dp, headerBorderColor)
                                    .clickable {
                                        if (allSelected) {
                                            // Deselect all
                                            clearSelection()
                                        } else {
                                            // Select all
                                            selectAll()
                                        }
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector =
                                    when {
                                        allSelected -> Icons.Default.CheckBox
                                        someSelected -> Icons.Default.IndeterminateCheckBox
                                        else -> Icons.Default.CheckBoxOutlineBlank
                                    },
                                contentDescription = if (allSelected) "Deselect All" else "Select All",
                                tint = if (allSelected || someSelected) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }

                        // Icon column (expand/collapse)
                        Box(
                            modifier =
                                Modifier
                                    .width(columnWidths["Icon"] ?: 40.dp)
                                    .fillMaxHeight()
                                    .border(0.5.dp, headerBorderColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "",
                                color = headerTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        // Time column
                        Box(
                            modifier =
                                Modifier
                                    .width((columnWidths["Time"] ?: 120.dp) - 1.dp)
                                    .fillMaxHeight()
                                    .border(0.5.dp, headerBorderColor)
                                    .combinedClickable(
                                        onDoubleClick = { toggleColumnWidth("Time") },
                                        onClick = {},
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Time",
                                color = headerTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        // Resize handle
                        ResizeHandle("Time", columnWidths)

                        // Dir column
                        Box(
                            modifier =
                                Modifier
                                    .width((columnWidths["Dir"] ?: 50.dp) - 1.dp)
                                    .fillMaxHeight()
                                    .border(0.5.dp, headerBorderColor)
                                    .combinedClickable(
                                        onDoubleClick = { toggleColumnWidth("Dir") },
                                        onClick = {},
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Dir",
                                color = headerTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        // Resize handle
                        ResizeHandle("Dir", columnWidths)

                        // SeqNum column
                        Box(
                            modifier =
                                Modifier
                                    .width((columnWidths["SeqNum"] ?: 70.dp) - 1.dp)
                                    .fillMaxHeight()
                                    .border(0.5.dp, headerBorderColor)
                                    .combinedClickable(
                                        onDoubleClick = { toggleColumnWidth("SeqNum") },
                                        onClick = {},
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "SeqNum",
                                color = headerTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        // Resize handle
                        ResizeHandle("SeqNum", columnWidths)

                        // MsgType column
                        Box(
                            modifier =
                                Modifier
                                    .width((columnWidths["MsgType"] ?: 100.dp) - 1.dp)
                                    .fillMaxHeight()
                                    .border(0.5.dp, headerBorderColor)
                                    .combinedClickable(
                                        onDoubleClick = { toggleColumnWidth("MsgType") },
                                        onClick = {},
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "MsgType",
                                color = headerTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        // Resize handle
                        ResizeHandle("MsgType", columnWidths)

                        // Summary column (moved before custom columns)
                        Box(
                            modifier =
                                Modifier
                                    .width(
                                        if (gridViewColumns.isEmpty()) {
                                            // Summary is last column - don't subtract
                                            columnWidths["Summary"] ?: 200.dp
                                        } else {
                                            // Summary is not last - subtract for resize handle
                                            (columnWidths["Summary"] ?: 200.dp) - 1.dp
                                        },
                                    ).fillMaxHeight()
                                    .border(0.5.dp, headerBorderColor)
                                    .combinedClickable(
                                        onDoubleClick = { toggleColumnWidth("Summary") },
                                        onClick = {},
                                    ),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "Summary",
                                color = headerTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }

                        // Resize handle (always add after Summary for resizing functionality)
                        ResizeHandle("Summary", columnWidths)

                        // Latency column (optional, shown when latency tracking is enabled)
                        if (showLatencyColumn) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(
                                            if (gridViewColumns.isEmpty()) {
                                                columnWidths["Latency"] ?: 90.dp
                                            } else {
                                                (columnWidths["Latency"] ?: 90.dp) - 1.dp
                                            },
                                        ).fillMaxHeight()
                                        .border(0.5.dp, headerBorderColor)
                                        .combinedClickable(
                                            onDoubleClick = { toggleColumnWidth("Latency") },
                                            onClick = {},
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Latency",
                                    color = headerTextColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            ResizeHandle("Latency", columnWidths)
                        }

                        // Dynamic columns for configured tags (moved after Summary)
                        gridViewColumns.forEachIndexed { index, tag ->
                            val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
                            val columnKey = "Tag_$tag"
                            val isLastColumn = index == gridViewColumns.size - 1

                            Box(
                                modifier =
                                    Modifier
                                        .width(
                                            if (isLastColumn) {
                                                // Last column - don't subtract for alignment
                                                columnWidths[columnKey] ?: 120.dp
                                            } else {
                                                // Not last column - subtract for resize handle
                                                (columnWidths[columnKey] ?: 120.dp) - 1.dp
                                            },
                                        ).fillMaxHeight()
                                        .border(0.5.dp, headerBorderColor)
                                        .combinedClickable(
                                            onDoubleClick = { toggleColumnWidth(columnKey) },
                                            onClick = {},
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = fieldName,
                                    color = headerTextColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }

                            // Resize handle (always add for resizing functionality)
                            ResizeHandle(columnKey, columnWidths)
                        }

                        // Spacer to fill remaining width
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Message rows (renderRows is hoisted above the scroll effects — see there)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        renderRows.forEach { row ->
                            if (row is ConversationRows.Row.Header) {
                                // "conv:" cannot collide with message keys, which are numeric uids.
                                item(key = "conv:" + row.key) {
                                    ConversationGroupRow(
                                        header = row,
                                        gridViewColumns = gridViewColumns,
                                        columnWidths = columnWidths,
                                        showLatencyColumn = showLatencyColumn,
                                        onToggle = { onToggleConversation(row.key) },
                                        following = row.label in followedTraceIds,
                                        onFollow = onFollowTrace,
                                        onUnfollow = onUnfollowTrace,
                                    )
                                }
                                return@forEach
                            }
                            val index = (row as ConversationRows.Row.Message).index
                            val message = messages[index]
                            val messageId = getMessageId(message)
                            val isExpanded = expandedMessages[messageId] ?: false

                            when (message) {
                                is Separator -> {
                                    // Separator row - match MessageSummaryRow dimensions exactly
                                    item(key = messageId) {
                                        val latencyColumnWidth = if (showLatencyColumn) (columnWidths["Latency"] ?: 90.dp) else 0.dp
                                        val minWidth =
                                            24.dp + // Checkbox column
                                                (columnWidths["Icon"] ?: 40.dp) +
                                                (columnWidths["Time"] ?: 120.dp) +
                                                (columnWidths["Dir"] ?: 50.dp) +
                                                (columnWidths["SeqNum"] ?: 70.dp) +
                                                (columnWidths["MsgType"] ?: 100.dp) +
                                                (columnWidths["Summary"] ?: 200.dp) +
                                                latencyColumnWidth +
                                                gridViewColumns.sumOf { tag -> (columnWidths["Tag_$tag"] ?: 120.dp).value.toInt() }.dp +
                                                200.dp // Extra space for spacer
                                        Box(
                                            modifier =
                                                Modifier
                                                    .widthIn(min = minWidth)
                                                    .height(24.dp)
                                                    .background(separatorBackgroundColor)
                                                    .border(0.5.dp, cellBorderColor),
                                        )
                                    }
                                }

                                is FixMessage -> {
                                    // Message summary row
                                    item(key = messageId) {
                                        MessageSummaryRow(
                                            message = message,
                                            dictionary = dictionary,
                                            gridViewColumns = gridViewColumns,
                                            columnWidths = columnWidths,
                                            isExpanded = isExpanded,
                                            isSelected = message == selectedMessage,
                                            isMultiSelected = selectedMessageIds.contains(messageId),
                                            messageIndex = index,
                                            recentlySentMessageTimestamp = recentlySentMessageTimestamp,
                                            stepResult = assertionResults[message],
                                            onToggleExpand = {
                                                val wasExpanded = isExpanded
                                                expandedMessages[messageId] = !isExpanded

                                                // Auto-fit columns when expanding for the first time,
                                                // to fit every expanded message rather than only
                                                // the one just opened — see fitExpandedGridWidths.
                                                if (!wasExpanded) {
                                                    expandedGridWidthContributions[messageId] =
                                                        calculateExpandedGridWidths(message.quickfixMessage, dictionary)
                                                    expandedGridColumnWidths.putAll(
                                                        fitExpandedGridWidths(expandedGridWidthContributions.values),
                                                    )
                                                }
                                            },
                                            onSelectMessage = onSelectMessage,
                                            onMultiSelectClick = { isCtrlOrCmd, isShift ->
                                                if (isShift) {
                                                    selectRange(index)
                                                } else if (isCtrlOrCmd) {
                                                    toggleMessageSelection(messageId, index)
                                                }
                                            },
                                            appSettings = appSettings,
                                            showLatencyColumn = showLatencyColumn,
                                            latencyMicros = getLatencyForMessage?.invoke(message.rawMessage),
                                            latencyWarningThresholdMicros = latencyWarningThresholdMicros,
                                            latencyCriticalThresholdMicros = latencyCriticalThresholdMicros,
                                            onFollowTrace = onFollowTrace,
                                        )
                                    }

                                    // Expanded field details
                                    if (isExpanded) {
                                        renderQuickFixMessage(
                                            message = message.quickfixMessage,
                                            dictionary = dictionary,
                                            hideProtocolTags = hideProtocolTags,
                                            protocolTags = appSettings.protocolTags,
                                            expandedGroups = expandedGroups,
                                            messageId = messageId,
                                            expandedGridColumnWidths = expandedGridColumnWidths,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Vertical scrollbar
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 4.dp),
        )

        // Horizontal scrollbar
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
}

/**
 * **The conversation view's summary row** — one per exchange when *Group by conversation* is on.
 *
 * It uses the grid's own columns rather than a full-width banner: chevron in the Icon column, the
 * label and instrument across the identity columns, composition and status in Summary, round-trip in
 * Latency, and the exchange's ids under the very Tag columns the member rows put them in. Same 24dp
 * height, same borders, same width arithmetic as [MessageSummaryRow], so folding a group never makes
 * the table jump.
 *
 * Everything on it is quotation or arithmetic over what arrived — see [Conversations.Summary] for the
 * rule. The status text deliberately stays neutral in colour: "Rejected" is a status too, and a green
 * chip would be the header forming an opinion.
 */
@Composable
private fun ConversationGroupRow(
    header: ConversationRows.Row.Header,
    gridViewColumns: List<Int>,
    columnWidths: Map<String, androidx.compose.ui.unit.Dp>,
    showLatencyColumn: Boolean,
    onToggle: () -> Unit,
    /** This group is the app's followed trace, sliced onto this pane. */
    following: Boolean = false,
    onFollow: ((String) -> Unit)? = null,
    onUnfollow: (() -> Unit)? = null,
) {
    val summary = header.summary
    val latencyColumnWidth = if (showLatencyColumn) (columnWidths["Latency"] ?: 90.dp) else 0.dp
    val minWidth =
        24.dp +
            (columnWidths["Icon"] ?: 40.dp) +
            (columnWidths["Time"] ?: 120.dp) +
            (columnWidths["Dir"] ?: 50.dp) +
            (columnWidths["SeqNum"] ?: 70.dp) +
            (columnWidths["MsgType"] ?: 100.dp) +
            (columnWidths["Summary"] ?: 200.dp) +
            latencyColumnWidth +
            gridViewColumns.sumOf { tag -> (columnWidths["Tag_$tag"] ?: 120.dp).value.toInt() }.dp +
            200.dp
    // The identity span: Time+Dir+SeqNum+MsgType, which a group row has no per-message values for.
    val identityWidth =
        (columnWidths["Time"] ?: 120.dp) +
            (columnWidths["Dir"] ?: 50.dp) +
            (columnWidths["SeqNum"] ?: 70.dp) +
            (columnWidths["MsgType"] ?: 100.dp)
    val background = AppTheme.Colors.surfaceVariant

    @Composable
    fun cell(width: androidx.compose.ui.unit.Dp, alignment: Alignment = Alignment.CenterStart, content: @Composable () -> Unit) {
        Box(
            modifier =
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .background(background)
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = alignment,
        ) { content() }
    }

    Row(
        modifier =
            Modifier
                .height(24.dp)
                .widthIn(min = minWidth)
                .clickable { onToggle() },
    ) {
        cell(24.dp) {}
        cell(columnWidths["Icon"] ?: 40.dp, Alignment.Center) {
            Text(
                text = if (header.collapsed) "▶" else "▼",
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        cell(identityWidth) {
            Text(
                text =
                    buildString {
                        append(header.label)
                        summary?.instrument?.let { append(" · ").append(it) }
                        summary?.quantity?.let { append(" ").append(it) }
                    },
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        cell(columnWidths["Summary"] ?: 200.dp) {
            Text(
                text =
                    buildString {
                        if (summary != null) {
                            append(summary.composition.joinToString(" · ") { "${it.name ?: it.messageType} ×${it.count}" })
                            summary.status?.let { append(" · ").append(it.valueName ?: it.value) }
                            // No Latency column to carry the round-trip: it rides here instead.
                            if (!showLatencyColumn) append(" · ").append(summary.elapsedMillis).append("ms")
                        } else {
                            append("${header.count} message${if (header.count == 1) "" else "s"} · no correlation id")
                        }
                    },
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (showLatencyColumn) {
            cell(columnWidths["Latency"] ?: 90.dp, Alignment.CenterEnd) {
                Text(
                    text = summary?.let { "${it.elapsedMillis}ms" } ?: "—",
                    color = if (summary != null) AppTheme.Colors.warning else AppTheme.Colors.textDisabled,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        gridViewColumns.forEach { tag ->
            cell(columnWidths["Tag_$tag"] ?: 120.dp) {
                Text(
                    text = header.idsByTag[tag] ?: "",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        // Follow across sessions. Last of the painted cells, so it never displaces a column a reader
        // is scanning — and absent on the ungrouped bucket, which is rows that belong to no exchange.
        if (onFollow != null && header.key != ConversationRows.UNGROUPED_KEY) {
            Box(
                modifier =
                    Modifier
                        .width(28.dp)
                        .fillMaxHeight()
                        .background(background)
                        .border(0.5.dp, cellBorderColor),
                contentAlignment = Alignment.Center,
            ) {
                FollowTraceButton(
                    following = following,
                    onClick = { if (following) onUnfollow?.invoke() else onFollow(header.label) },
                )
            }
        }
        // Unpainted, exactly as MessageSummaryRow ends: painting the group background here drew a
        // phantom extra column past the grid's real width.
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * Summary row for a message (collapsed state)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageSummaryRow(
    message: FixMessage,
    dictionary: FixDictionary,
    gridViewColumns: List<Int> = emptyList(),
    columnWidths: Map<String, androidx.compose.ui.unit.Dp> = emptyMap(),
    isExpanded: Boolean,
    isSelected: Boolean = false,
    isMultiSelected: Boolean = false,
    messageIndex: Int = 0,
    recentlySentMessageTimestamp: LocalDateTime? = null,
    stepResult: com.knapsack.fixtool.model.scenario.StepResult? = null,
    onToggleExpand: () -> Unit,
    onSelectMessage: ((FixMessage?) -> Unit)? = null,
    onMultiSelectClick: ((isCtrlOrCmd: Boolean, isShift: Boolean) -> Unit)? = null,
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    // Latency tracking parameters
    showLatencyColumn: Boolean = false,
    latencyMicros: Long? = null,
    latencyWarningThresholdMicros: Long = 100_000L,
    latencyCriticalThresholdMicros: Long = 500_000L,
    /**
     * Follow the exchange this message belongs to. Null leaves the row with no context menu at all.
     *
     * Takes the id rather than the message because the app follows a *value*: which of this message's
     * ids opened the exchange is a question for [Conversations.idsOf], answered when the menu opens.
     */
    onFollowTrace: ((String) -> Unit)? = null,
) {
    // Extract top-level field values (excluding repeating groups)
    val columnValues =
        remember(message, gridViewColumns) {
            gridViewColumns.map { tag ->
                extractTopLevelFieldValue(message.quickfixMessage, tag)
            }
        }
    // Match session coloring: blue for outgoing, red for incoming rejects, green for other incoming
    val directionColor = getDirectionColor(message, appSettings)

    val timeFormatter = GRID_TIME_FORMATTER
    val msgTypeDesc = dictionary.getFieldValueDescription(35, message.messageType) ?: message.messageType

    // Check if this message was recently sent (outgoing message within a few milliseconds of the sent timestamp)
    val isRecentlySent =
        if (recentlySentMessageTimestamp != null && message.direction == FixMessage.Direction.OUTGOING) {
            val durationMillis =
                java.time.Duration
                    .between(recentlySentMessageTimestamp, message.timestamp)
                    .abs()
                    .toMillis()
            durationMillis < 500
        } else {
            false
        }

    // Background color: selection wins, then scenario assertion result (green/red/diagnosed), then
    // recently sent, then default. The assertion tint marks messages a played scenario matched.
    val assertionTint =
        stepResult?.let {
            when {
                // A diagnosis is not a verdict on this message — see AppTheme.Colors.diagnosisBackground.
                it.kind == "diagnosis" -> AppTheme.Colors.diagnosisBackground
                it.passed -> AppTheme.Colors.notificationSuccessBackground
                else -> AppTheme.Colors.notificationErrorBackground
            }
        }
    val backgroundColor =
        when {
            isSelected -> selectedRowBackgroundColor
            isMultiSelected -> multiSelectedRowBackgroundColor
            assertionTint != null -> assertionTint
            isRecentlySent -> AppTheme.Colors.messageRecentlySent
            else -> mainBackgroundColor
        }

    // Calculate minimum width needed for all columns
    val latencyColumnWidth = if (showLatencyColumn) (columnWidths["Latency"] ?: 90.dp) else 0.dp
    val minWidth =
        24.dp + // Checkbox column
            (columnWidths["Icon"] ?: 40.dp) +
            (columnWidths["Time"] ?: 120.dp) +
            (columnWidths["Dir"] ?: 50.dp) +
            (columnWidths["SeqNum"] ?: 70.dp) +
            (columnWidths["MsgType"] ?: 100.dp) +
            (columnWidths["Summary"] ?: 200.dp) +
            latencyColumnWidth +
            gridViewColumns.sumOf { tag -> (columnWidths["Tag_$tag"] ?: 120.dp).value.toInt() }.dp +
            200.dp // Extra space for spacer

    // The grid had no row menu of any kind. This is the whole of it — one item — because one gesture is
    // what this slice adds, and a menu that grows by accident is how a grid ends up with two ways to do
    // everything and no way to find either.
    var followMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .height(24.dp)
                .widthIn(min = minWidth)
                .then(
                    if (onFollowTrace == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                        followMenuOpen = true
                                    }
                                }
                            }
                        }
                    },
                ),
    ) {
        // Track mouse event for Shift detection
        var lastCheckboxMouseEvent by remember { mutableStateOf<java.awt.event.MouseEvent?>(null) }

        // Checkbox column for multi-selection
        Box(
            modifier =
                Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .background(backgroundColor)
                    .border(0.5.dp, cellBorderColor)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent
                                if (awtEvent != null) {
                                    lastCheckboxMouseEvent = awtEvent
                                }
                            }
                        }
                    }.clickable {
                        // Check if Shift is held for range selection
                        val isShift = lastCheckboxMouseEvent?.isShiftDown == true
                        onMultiSelectClick?.invoke(!isShift, isShift)
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isMultiSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (isMultiSelected) "Selected" else "Not Selected",
                tint = if (isMultiSelected) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }

        // Expand/collapse icon - click only expands/collapses
        Box(
            modifier =
                Modifier
                    .width(columnWidths["Icon"] ?: 40.dp)
                    .fillMaxHeight()
                    .background(backgroundColor)
                    .border(0.5.dp, cellBorderColor)
                    .clickable {
                        onToggleExpand()
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = headerTextColor,
                modifier = Modifier.size(iconSize),
            )
        }

        // Track mouse event for modifier detection on row
        var lastRowMouseEvent by remember { mutableStateOf<java.awt.event.MouseEvent?>(null) }

        // Rest of the row - single click selects for detail panel, double click expands
        // Ctrl/Cmd+Click toggles selection, Shift+Click for range selection
        Row(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .background(backgroundColor)
                    .testTag("message-row-${message.timestamp}")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent
                                if (awtEvent != null) {
                                    lastRowMouseEvent = awtEvent
                                }
                            }
                        }
                    }.combinedClickable(
                        onClick = {
                            val awtEvent = lastRowMouseEvent
                            val isCtrlOrCmd = awtEvent?.isControlDown == true || awtEvent?.isMetaDown == true
                            val isShift = awtEvent?.isShiftDown == true

                            when {
                                isShift -> {
                                    // Shift+Click: range selection
                                    onMultiSelectClick?.invoke(false, true)
                                }
                                isCtrlOrCmd -> {
                                    // Ctrl/Cmd+Click: toggle selection
                                    onMultiSelectClick?.invoke(true, false)
                                }
                                else -> {
                                    // Normal click: select message and show detail panel
                                    onSelectMessage?.invoke(message)
                                }
                            }
                        },
                        onDoubleClick = {
                            // Double click: expand/collapse (don't show detail panel)
                            onToggleExpand()
                        },
                    ),
        ) {
            // Time
            Box(
                modifier =
                    Modifier
                        .width(columnWidths["Time"] ?: 120.dp)
                        .fillMaxHeight()
                        .border(0.5.dp, cellBorderColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message.timestamp.format(timeFormatter),
                    color = textPrimaryColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Direction
            Box(
                modifier =
                    Modifier
                        .width(columnWidths["Dir"] ?: 50.dp)
                        .fillMaxHeight()
                        .border(0.5.dp, cellBorderColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (message.direction == FixMessage.Direction.INCOMING) "IN" else "OUT",
                    color = directionColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Sequence Number (tag 34)
            Box(
                modifier =
                    Modifier
                        .width(columnWidths["SeqNum"] ?: 70.dp)
                        .fillMaxHeight()
                        .border(0.5.dp, cellBorderColor),
                contentAlignment = Alignment.Center,
            ) {
                val seqNum = extractTopLevelFieldValue(message.quickfixMessage, 34)
                Text(
                    text = seqNum,
                    color = tagNumberColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Message Type
            Box(
                modifier =
                    Modifier
                        .width(columnWidths["MsgType"] ?: 100.dp)
                        .fillMaxHeight()
                        .border(0.5.dp, cellBorderColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message.messageType,
                    color = tagNumberColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Summary (moved before custom columns)
            Box(
                modifier =
                    Modifier
                        .width(columnWidths["Summary"] ?: 200.dp)
                        .fillMaxHeight()
                        .border(0.5.dp, cellBorderColor),
                contentAlignment = Alignment.CenterStart,
            ) {
                TooltipArea(
                    tooltip = {
                        Text(
                            text = msgTypeDesc,
                            modifier =
                                Modifier
                                    .shadow(4.dp, tooltipCornerRadius)
                                    .background(tooltipBackgroundColor, tooltipCornerRadius)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            color = textPrimaryColor,
                            fontSize = 11.sp,
                        )
                    },
                    delayMillis = 600,
                    tooltipPlacement =
                        TooltipPlacement.ComponentRect(
                            anchor = Alignment.BottomCenter,
                            alignment = Alignment.BottomCenter,
                            offset = DpOffset(0.dp, 4.dp),
                        ),
                ) {
                    SelectionContainer {
                        Text(
                            text = msgTypeDesc,
                            color = textPrimaryColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            // Latency column (optional)
            if (showLatencyColumn) {
                Box(
                    modifier =
                        Modifier
                            .width(columnWidths["Latency"] ?: 90.dp)
                            .fillMaxHeight()
                            .border(0.5.dp, cellBorderColor),
                    contentAlignment = Alignment.Center,
                ) {
                    if (latencyMicros != null) {
                        val latencyColor =
                            when {
                                latencyMicros >= latencyCriticalThresholdMicros -> AppTheme.Colors.error
                                latencyMicros >= latencyWarningThresholdMicros -> AppTheme.Colors.warning
                                else -> AppTheme.Colors.primary
                            }
                        val latencyText =
                            when {
                                latencyMicros < 1000 -> "${latencyMicros}us"
                                latencyMicros < 1_000_000 -> String.format("%.2fms", latencyMicros / 1000.0)
                                else -> String.format("%.2fs", latencyMicros / 1_000_000.0)
                            }
                        Text(
                            text = latencyText,
                            color = latencyColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(
                            text = "-",
                            color = textSecondaryColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            // Dynamic columns for configured tags (moved after Summary)
            columnValues.forEachIndexed { index, value ->
                val tag = gridViewColumns[index]
                val columnKey = "Tag_$tag"
                Box(
                    modifier =
                        Modifier
                            .width(columnWidths[columnKey] ?: 120.dp)
                            .fillMaxHeight()
                            .border(0.5.dp, cellBorderColor),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    SelectionContainer {
                        Text(
                            text = value,
                            color = valueColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        // Spacer to fill remaining width (outside the selectable row to avoid highlighting)
        Spacer(modifier = Modifier.weight(1f))

        // The row's context menu. A child of the Row rather than a Box around it: a collapsed
        // DropdownMenu composes nothing and an expanded one is a Popup, so it takes no space either
        // way — and wrapping the row would have re-indented every cell in it for no layout gain.
        if (onFollowTrace != null) {
            DropdownMenu(expanded = followMenuOpen, onDismissRequest = { followMenuOpen = false }) {
                // Read only while the menu is open: asking every row in the window for its ids on
                // every recomposition would be a second field walk per message, ten times a second.
                val firstId = remember(message) { Conversations.idsOf(message, dictionary).firstOrNull()?.second }
                DropdownMenuItem(
                    text = {
                        Text(
                            // Named, so a reader knows what will be followed before they commit to it —
                            // and disabled rather than hidden when there is none, because "this message
                            // carries no correlation id" is the answer, not an empty menu.
                            text =
                                if (firstId == null) {
                                    "No correlation id to follow"
                                } else {
                                    "Follow $firstId across sessions"
                                },
                            fontSize = 11.sp,
                        )
                    },
                    enabled = firstId != null,
                    onClick = {
                        firstId?.let { onFollowTrace(it) }
                        followMenuOpen = false
                    },
                )
            }
        }
    }
}

/**
 * Horizontal offset the expanded grid adds to a row's Tag cell for each level of group nesting.
 * The value lives in [FixIndent], with the other surfaces' steps beside it and the note on why
 * they differ; this alias is what the width arithmetic and its test read.
 */
internal const val EXPANDED_GRID_INDENT_STEP = FixIndent.GRID_STEP

/**
 * Hoisted out of the row composable: this was allocated per visible row per recomposition, and
 * `DateTimeFormatter.ofPattern` re-parses the pattern string on every call. The formatter is
 * immutable and thread-safe, so one instance serves every row.
 */
private val GRID_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private const val EXPANDED_GRID_CHAR_WIDTH = 7 // pixels per character approximately
private const val EXPANDED_GRID_CELL_PADDING = 16
private const val EXPANDED_GRID_MIN_COLUMN_WIDTH = 50
private const val EXPANDED_GRID_MAX_COLUMN_WIDTH = 500

/**
 * The widths that fit **every** expanded message — the widest requirement per column, not the
 * newest.
 *
 * The grid draws all of its expanded messages through one set of column widths, so those widths are
 * a property of the whole expanded set and not of whichever message was opened last. Each expansion
 * used to write its own answer straight over the shared map, which held only while the messages
 * happened to want the same room: open a deeply nested message, then a shallow one, and the shallow
 * one's narrower Tag column replaced the wide one the first message was still being drawn with,
 * putting issue #37 back a second time. A tag clipped in a FIX grid does not read as clipped — 671
 * shown as "67" reads as tag 67.
 *
 * Folding over each expanded message's own contribution — rather than over whatever the shared map
 * happens to hold — is what keeps the *first* expansion able to fit tightly. Maxing against the
 * seeded defaults instead would floor every column at them, and a grid that can only ever widen is
 * not auto-fitting, it is padding.
 *
 * A contribution is kept when its message is collapsed, so a column can be wider than the messages
 * still open strictly need. That is the safe direction to be wrong in, and the alternative —
 * recomputing on every collapse — buys back whitespace by moving the widths under the author while
 * they read.
 */
internal fun fitExpandedGridWidths(
    contributions: Collection<Map<String, androidx.compose.ui.unit.Dp>>,
): Map<String, androidx.compose.ui.unit.Dp> =
    contributions
        .flatMap { it.keys }
        .toSet()
        .associateWith { column -> contributions.mapNotNull { it[column] }.maxOrNull() ?: 0.dp }

/**
 * Optimal widths for the expanded grid's columns, auto-fitted to one message's content.
 *
 * A row's tag number is drawn inside the fixed-width Tag cell, offset by
 * [EXPANDED_GRID_INDENT_STEP] per level of group nesting, so how much room a tag needs depends on
 * how deep its group sits. Sizing the column on text alone clips the numbers of deeply nested
 * fields — at three levels down, 671 renders as "67" (issue #37).
 *
 * This is one message's answer; [fitExpandedGridWidths] combines it with what the other expanded
 * messages need.
 */
internal fun calculateExpandedGridWidths(
    message: quickfix.Message,
    dictionary: FixDictionary,
): Map<String, androidx.compose.ui.unit.Dp> {
    val required = mutableMapOf("Tag" to 0, "TagDescription" to 0, "Value" to 0, "ValueDescription" to 0)

    fun fit(
        columnKey: String,
        text: String,
        indent: Int = 0,
    ) {
        val width = indent + text.length * EXPANDED_GRID_CHAR_WIDTH + EXPANDED_GRID_CELL_PADDING
        required[columnKey] = maxOf(required.getValue(columnKey), width)
    }

    fun collectFieldWidths(
        fieldMap: FieldMap,
        indentLevel: Int,
    ) {
        val indent = FixIndent.startValue(indentLevel, FixIndent.GRID_STEP)
        val iterator = fieldMap.iterator()
        while (iterator.hasNext()) {
            @Suppress("UNCHECKED_CAST")
            val field = iterator.next() as Field<*>
            val tag = field.tag
            val value = field.getObject().toString()
            val fieldName = dictionary.getFieldName(tag) ?: tag.toString()

            fit("Tag", tag.toString(), indent)
            fit("Value", value)

            val valueDesc = dictionary.getFieldValueDescription(tag, value)
            if (valueDesc != null && valueDesc != value) {
                fit("ValueDescription", valueDesc)
            }

            val groupCount =
                try {
                    fieldMap.groupCountSafe(tag)
                } catch (e: Exception) {
                    0 // Not a group
                }

            if (groupCount > 0) {
                // A group header spells out its instance count, and its fields sit one level deeper.
                fit("TagDescription", "$fieldName ($groupCount instances)")
                for (i in 1..groupCount) {
                    try {
                        collectFieldWidths(fieldMap.getGroup(i, tag), indentLevel + 1)
                    } catch (e: Exception) {
                        // Skip invalid groups
                    }
                }
            } else {
                fit("TagDescription", fieldName)
            }
        }
    }

    collectFieldWidths(message.header, 0)
    collectFieldWidths(message, 0)
    collectFieldWidths(message.trailer, 0)

    val result = mutableMapOf<String, androidx.compose.ui.unit.Dp>()
    result["IconColumn"] = 40.dp // Fixed size
    required.forEach { (columnKey, width) ->
        result[columnKey] = width.dp.coerceIn(EXPANDED_GRID_MIN_COLUMN_WIDTH.dp, EXPANDED_GRID_MAX_COLUMN_WIDTH.dp)
    }
    return result
}

/**
 * Renders a QuickFIX Message in the LazyColumn
 */
private fun LazyListScope.renderQuickFixMessage(
    message: quickfix.Message,
    dictionary: FixDictionary,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
    expandedGroups: MutableMap<String, Boolean>,
    messageId: String,
    expandedGridColumnWidths: MutableMap<String, androidx.compose.ui.unit.Dp>,
) {
    // Render the header row for the expanded grid
    item(key = "${messageId}_expanded_header") {
        ExpandedGridHeader(columnWidths = expandedGridColumnWidths)
    }

    // Render header fields
    renderFieldMap(
        fieldMap = message.header,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        indentLevel = 0,
        parentKey = messageId,
        columnWidths = expandedGridColumnWidths,
    )

    // Render body fields
    renderFieldMap(
        fieldMap = message,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        indentLevel = 0,
        parentKey = messageId,
        columnWidths = expandedGridColumnWidths,
    )

    // Render trailer fields
    renderFieldMap(
        fieldMap = message.trailer,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        indentLevel = 0,
        parentKey = messageId,
        columnWidths = expandedGridColumnWidths,
    )
}

/**
 * Renders a FieldMap (header, body, trailer, or group) recursively
 */
private fun LazyListScope.renderFieldMap(
    fieldMap: FieldMap,
    dictionary: FixDictionary,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
    expandedGroups: MutableMap<String, Boolean>,
    indentLevel: Int,
    parentKey: String,
    columnWidths: Map<String, androidx.compose.ui.unit.Dp>,
) {
    val iterator = fieldMap.iterator()

    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag

        // Skip protocol tags if hidden
        if (hideProtocolTags && tag in protocolTags) {
            continue
        }

        val value = field.getObject().toString()

        // Check if this is a repeating group
        try {
            val groupCount = fieldMap.groupCountSafe(tag)
            if (groupCount > 0) {
                // This is a repeating group
                val groupKey = "$parentKey/${tag}_$groupCount"
                val isExpanded = expandedGroups[groupKey] ?: false

                // Render group header
                item {
                    HierarchicalGroupHeaderRow(
                        tag = tag,
                        count = groupCount,
                        dictionary = dictionary,
                        isExpanded = isExpanded,
                        onToggle = {
                            expandedGroups[groupKey] = !isExpanded
                        },
                        indentLevel = indentLevel,
                        columnWidths = columnWidths,
                    )
                }

                // Render group instances if expanded
                if (isExpanded) {
                    for (i in 1..groupCount) {
                        try {
                            val group = fieldMap.getGroup(i, tag)

                            item {
                                HierarchicalGroupInstanceHeader(
                                    instanceNumber = i,
                                    indentLevel = indentLevel + 1,
                                    columnWidths = columnWidths,
                                )
                            }

                            // Recursively render group fields
                            renderFieldMap(
                                fieldMap = group,
                                dictionary = dictionary,
                                hideProtocolTags = hideProtocolTags,
                                protocolTags = protocolTags,
                                expandedGroups = expandedGroups,
                                indentLevel = indentLevel + 1,
                                parentKey = groupKey,
                                columnWidths = columnWidths,
                            )
                        } catch (e: Exception) {
                            // Skip invalid groups
                        }
                    }
                }
            } else {
                // Regular field (not a group)
                item {
                    HierarchicalFieldRow(
                        tag = tag,
                        value = value,
                        dictionary = dictionary,
                        indentLevel = indentLevel,
                        columnWidths = columnWidths,
                    )
                }
            }
        } catch (e: Exception) {
            // If getGroupCount throws an exception, treat as regular field
            item {
                HierarchicalFieldRow(
                    tag = tag,
                    value = value,
                    dictionary = dictionary,
                    indentLevel = indentLevel,
                    columnWidths = columnWidths,
                )
            }
        }
    }
}

/**
 * Single field row with 4 columns: Tag, Tag Description, Value, Value Description
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HierarchicalFieldRow(
    tag: Int,
    value: String,
    dictionary: FixDictionary,
    indentLevel: Int,
    columnWidths: Map<String, androidx.compose.ui.unit.Dp>,
) {
    val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
    val rawValueDesc = dictionary.getFieldValueDescription(tag, value)
    // Only show value description if it differs from the value
    val valueDesc = if (rawValueDesc != null && rawValueDesc != value) rawValueDesc else ""
    val indent = FixIndent.start(indentLevel, FixIndent.GRID_STEP)

    val totalWidth =
        (columnWidths["IconColumn"] ?: 40.dp) +
            (columnWidths["Tag"] ?: 120.dp) +
            (columnWidths["TagDescription"] ?: 200.dp) +
            (columnWidths["Value"] ?: 150.dp) +
            (columnWidths["ValueDescription"] ?: 250.dp)

    Row(
        modifier =
            Modifier
                .height(20.dp)
                .width(totalWidth)
                .background(fieldRowBackgroundColor),
    ) {
        // Empty space for indent + icon column
        Box(
            modifier =
                Modifier
                    .width(columnWidths["IconColumn"] ?: 40.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
        )

        // Tag
        Box(
            modifier =
                Modifier
                    .width(columnWidths["Tag"] ?: 120.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            SelectionContainer {
                Row(modifier = Modifier.padding(start = indent).padding(start = 4.dp)) {
                    Text(
                        text = tag.toString(),
                        color = tagNumberColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        // A wrapped tag number loses its tail to the row's fixed height, and the
                        // stub left behind still reads as a valid tag (671 -> 67). If the cell is
                        // ever too narrow — the user can drag it — say so with an ellipsis.
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Tag Description
        Box(
            modifier =
                Modifier
                    .width(columnWidths["TagDescription"] ?: 200.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            TooltipArea(
                tooltip = {
                    Text(
                        text = fieldName,
                        modifier =
                            Modifier
                                .shadow(4.dp, tooltipCornerRadius)
                                .background(tooltipBackgroundColor, tooltipCornerRadius)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = textPrimaryColor,
                        fontSize = 11.sp,
                    )
                },
                delayMillis = 600,
                tooltipPlacement =
                    TooltipPlacement.ComponentRect(
                        anchor = Alignment.BottomCenter,
                        alignment = Alignment.BottomCenter,
                        offset = DpOffset(0.dp, 4.dp),
                    ),
            ) {
                SelectionContainer {
                    Text(
                        text = fieldName,
                        color = textPrimaryColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // Value
        Box(
            modifier =
                Modifier
                    .width(columnWidths["Value"] ?: 150.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            TooltipArea(
                tooltip = {
                    Text(
                        text = value,
                        modifier =
                            Modifier
                                .shadow(4.dp, tooltipCornerRadius)
                                .background(tooltipBackgroundColor, tooltipCornerRadius)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = textPrimaryColor,
                        fontSize = 11.sp,
                    )
                },
                delayMillis = 600,
                tooltipPlacement =
                    TooltipPlacement.ComponentRect(
                        anchor = Alignment.BottomCenter,
                        alignment = Alignment.BottomCenter,
                        offset = DpOffset(0.dp, 4.dp),
                    ),
            ) {
                SelectionContainer {
                    Text(
                        text = value,
                        color = valueColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // Value Description
        Box(
            modifier =
                Modifier
                    .width(columnWidths["ValueDescription"] ?: 250.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            TooltipArea(
                tooltip = {
                    Text(
                        text = valueDesc,
                        modifier =
                            Modifier
                                .shadow(4.dp, tooltipCornerRadius)
                                .background(tooltipBackgroundColor, tooltipCornerRadius)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = textPrimaryColor,
                        fontSize = 11.sp,
                    )
                },
                delayMillis = 600,
                tooltipPlacement =
                    TooltipPlacement.ComponentRect(
                        anchor = Alignment.BottomCenter,
                        alignment = Alignment.BottomCenter,
                        offset = DpOffset(0.dp, 4.dp),
                    ),
            ) {
                SelectionContainer {
                    Text(
                        text = valueDesc,
                        color = textPrimaryColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Group header row showing count
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HierarchicalGroupHeaderRow(
    tag: Int,
    count: Int,
    dictionary: FixDictionary,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    indentLevel: Int,
    columnWidths: Map<String, androidx.compose.ui.unit.Dp>,
) {
    val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
    val indent = FixIndent.start(indentLevel, FixIndent.GRID_STEP)

    val totalWidth =
        (columnWidths["IconColumn"] ?: 40.dp) +
            (columnWidths["Tag"] ?: 120.dp) +
            (columnWidths["TagDescription"] ?: 200.dp) +
            (columnWidths["Value"] ?: 150.dp) +
            (columnWidths["ValueDescription"] ?: 250.dp)

    Row(
        modifier =
            Modifier
                .height(20.dp)
                .width(totalWidth)
                .background(separatorBackgroundColor)
                .clickable { onToggle() },
    ) {
        // Expand icon
        Box(
            modifier =
                Modifier
                    .width(columnWidths["IconColumn"] ?: 40.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = headerTextColor,
                modifier = Modifier.size(iconSize),
            )
        }

        // Tag
        Box(
            modifier =
                Modifier
                    .width(columnWidths["Tag"] ?: 120.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            SelectionContainer {
                Row(modifier = Modifier.padding(start = indent).padding(start = 4.dp)) {
                    Text(
                        text = tag.toString(),
                        color = tagNumberColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Tag Description with count
        Box(
            modifier =
                Modifier
                    .width(columnWidths["TagDescription"] ?: 200.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            TooltipArea(
                tooltip = {
                    Text(
                        text = "$fieldName ($count instances)",
                        modifier =
                            Modifier
                                .shadow(4.dp, tooltipCornerRadius)
                                .background(tooltipBackgroundColor, tooltipCornerRadius)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = textPrimaryColor,
                        fontSize = 11.sp,
                    )
                },
                delayMillis = 600,
                tooltipPlacement =
                    TooltipPlacement.ComponentRect(
                        anchor = Alignment.BottomCenter,
                        alignment = Alignment.BottomCenter,
                        offset = DpOffset(0.dp, 4.dp),
                    ),
            ) {
                SelectionContainer {
                    Text(
                        text = "$fieldName ($count instances)",
                        color = valueColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // Value
        Box(
            modifier =
                Modifier
                    .width(columnWidths["Value"] ?: 150.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            SelectionContainer {
                Text(
                    text = count.toString(),
                    color = valueColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // Value Description (empty for groups)
        Box(
            modifier =
                Modifier
                    .width(columnWidths["ValueDescription"] ?: 250.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
        )
    }
}

/**
 * Group instance header
 */
@Composable
private fun HierarchicalGroupInstanceHeader(
    instanceNumber: Int,
    indentLevel: Int,
    columnWidths: Map<String, androidx.compose.ui.unit.Dp>,
) {
    val indent = FixIndent.start(indentLevel, FixIndent.GRID_STEP)

    val totalWidth =
        (columnWidths["IconColumn"] ?: 40.dp) +
            (columnWidths["Tag"] ?: 120.dp) +
            (columnWidths["TagDescription"] ?: 200.dp) +
            (columnWidths["Value"] ?: 150.dp) +
            (columnWidths["ValueDescription"] ?: 250.dp)

    Row(
        modifier =
            Modifier
                .height(20.dp)
                .width(totalWidth)
                .background(groupInstanceBackgroundColor),
    ) {
        // Empty icon column
        Box(
            modifier =
                Modifier
                    .width(columnWidths["IconColumn"] ?: 40.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
        )

        // Instance label spanning first columns (Tag + Tag Description)
        Box(
            modifier =
                Modifier
                    .width((columnWidths["Tag"] ?: 120.dp) + (columnWidths["TagDescription"] ?: 200.dp))
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            SelectionContainer {
                Row(modifier = Modifier.padding(start = indent).padding(start = 4.dp)) {
                    Text(
                        text = "[$instanceNumber]",
                        color = valueColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // Empty columns
        Box(
            modifier =
                Modifier
                    .width(columnWidths["Value"] ?: 150.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
        )

        Box(
            modifier =
                Modifier
                    .width(columnWidths["ValueDescription"] ?: 250.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
        )
    }
}

/**
 * Extracts a top-level field value from a QuickFIX message.
 * Checks header, body, and trailer at the top level, excluding repeating groups.
 */
private fun extractTopLevelFieldValue(message: quickfix.Message, tag: Int): String {
    try {
        // Check header first (for fields like MsgSeqNum/34)
        if (message.header.isSetField(tag)) {
            return message.header.getString(tag)
        }

        // Check body
        if (message.isSetField(tag)) {
            // Check if it's a repeating group (we want to skip these)
            val groupCount =
                try {
                    message.groupCountSafe(tag)
                } catch (e: Exception) {
                    0
                }

            // If it's a repeating group, return the count instead of the value
            return if (groupCount > 0) {
                "[$groupCount]"
            } else {
                // Get the field value
                message.getString(tag)
            }
        }

        // Check trailer
        if (message.trailer.isSetField(tag)) {
            return message.trailer.getString(tag)
        }

        return ""
    } catch (e: Exception) {
        return ""
    }
}

// Constants
private val mainBackgroundColor = AppTheme.Colors.background
private val headerBackgroundColor = Color(0xFF2D2D2D) // Keep unique header background
private val headerTextColor = AppTheme.Colors.textSecondary
private val headerBorderColor = Color(0xFF454545) // Keep unique header border
private val separatorBackgroundColor = Color(0xFF2A2A2A) // Keep unique separator background
private val cellBorderColor = AppTheme.Colors.border
private val tooltipBackgroundColor = AppTheme.Colors.border
private val textPrimaryColor = AppTheme.Colors.text
private val textSecondaryColor = AppTheme.Colors.textSecondary
private val outgoingColor = AppTheme.Colors.messageOutgoing
private val incomingColor = AppTheme.Colors.messageIncoming
private val rejectionColor = AppTheme.Colors.messageRejection
private val selectedRowBackgroundColor = AppTheme.Colors.selectionPrimary
private val multiSelectedRowBackgroundColor = AppTheme.Colors.selectionSecondary
private val tagNumberColor = AppTheme.Colors.tagNumber
private val valueColor = AppTheme.Colors.fieldValue
private val fieldRowBackgroundColor = AppTheme.Colors.surfaceVariant
private val groupInstanceBackgroundColor = AppTheme.Colors.surfaceHeader

private val tooltipCornerRadius = RoundedCornerShape(4.dp)
private val iconSize = 14.dp

// Helper function for direction color
private fun getDirectionColor(message: FixMessage, appSettings: com.knapsack.fixtool.model.AppSettings): Color =
    appSettings.messageColorScheme.getMessageColor(
        message.direction,
        message.isRejectionOrLogout(appSettings.rejectionRules),
        true,
    )
