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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
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
import kotlinx.coroutines.launch
import quickfix.Field
import quickfix.FieldMap
import java.awt.Cursor
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
                }
                .background(Color.Transparent),
    )
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
    recentlySentMessageTimestamp: LocalDateTime? = null,
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Track which messages are expanded (key: message timestamp)
    val expandedMessages = remember { mutableStateMapOf<String, Boolean>() }

    // Track which groups are expanded (key: "messageId_groupKey")
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // Column width state management
    val originalWidths =
        remember {
            mapOf(
                "Icon" to 40.dp,
                "Time" to 120.dp,
                "Dir" to 50.dp,
                "SeqNum" to 70.dp,
                "MsgType" to 100.dp,
                "Summary" to 200.dp,
            ) + gridViewColumns.associate { tag -> "Tag_$tag" to 120.dp }
        }

    val columnWidths =
        remember {
            mutableStateMapOf<String, androidx.compose.ui.unit.Dp>().apply {
                putAll(originalWidths)
            }
        }

    // Track which columns have been auto-fitted
    val autoFittedColumns = remember { mutableStateSetOf<String>() }

    // Auto-scroll state
    var autoScroll by remember { mutableStateOf(true) }

    // Track if user is at the bottom
    val isAtBottom by derivedStateOf {
        !listState.canScrollForward
    }

    // When a new message arrives, scroll to bottom if autoScroll is enabled
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && autoScroll) {
            // Wait for layout to complete before scrolling
            kotlinx.coroutines.delay(50)
            // Double-check messages still exist and index is valid
            if (messages.isNotEmpty()) {
                val targetIndex = messages.size - 1
                // Only scroll if the target index is within bounds of the layout info
                if (targetIndex >= 0 && targetIndex < messages.size) {
                    listState.scrollToItem(targetIndex)
                }
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
                    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                    contentSamples.add(msg.timestamp.format(timeFormatter))
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

    // Scroll to selected message when it changes
    LaunchedEffect(selectedMessage) {
        if (selectedMessage != null) {
            val messageIndex = messages.indexOf(selectedMessage)
            if (messageIndex >= 0) {
                coroutineScope.launch {
                    listState.animateScrollToItem(messageIndex)
                }
            }
        }
    }

    // Horizontal scroll state for columns that don't fit
    val horizontalScrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize().background(mainBackgroundColor)) {
        // Main content with horizontal scroll support
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
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
            // Icon column
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
                            }
                        )
                        .fillMaxHeight()
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
                                }
                            )
                            .fillMaxHeight()
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

                // Message rows
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                ) {
                    messages.forEach { message ->
                        val messageId = message.timestamp.toString()
                        val isExpanded = expandedMessages[messageId] ?: false

                        when (message) {
                            is Separator -> {
                                // Separator row
                                item(key = messageId) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(5000.dp) // Fixed width for horizontal scroll
                                                .height(20.dp)
                                                .background(separatorBackgroundColor),
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
                                        recentlySentMessageTimestamp = recentlySentMessageTimestamp,
                                        onToggleExpand = {
                                            expandedMessages[messageId] = !isExpanded
                                        },
                                        onSelectMessage = onSelectMessage,
                                        appSettings = appSettings,
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
                                    )
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

        // Scroll to bottom button (shown when not at bottom and there are messages)
        if (!isAtBottom && messages.isNotEmpty()) {
            TooltipFloatingActionButton(
                tooltip = "Scroll to Bottom (Resume Auto-Scroll)",
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                        autoScroll = true // Re-enable auto-scroll mode
                    }
                },
                containerColor = fabBackgroundColor,
                contentColor = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 16.dp)
                        .size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Scroll to bottom",
                    modifier = Modifier.size(iconSize),
                )
            }
        }
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
    recentlySentMessageTimestamp: LocalDateTime? = null,
    onToggleExpand: () -> Unit,
    onSelectMessage: ((FixMessage?) -> Unit)? = null,
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
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

    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    val msgTypeDesc = dictionary.getFieldValueDescription(35, message.messageType) ?: message.messageType

    // Check if this message was recently sent (outgoing message within a few milliseconds of the sent timestamp)
    val isRecentlySent = if (recentlySentMessageTimestamp != null && message.direction == FixMessage.Direction.OUTGOING) {
        val durationMillis = java.time.Duration.between(recentlySentMessageTimestamp, message.timestamp).abs().toMillis()
        durationMillis < 500
    } else {
        false
    }

    // Background color: highlight if recently sent, selected, or default
    val backgroundColor = when {
        isRecentlySent -> {
            AppTheme.Colors.messageRecentlySent
        }
        isSelected -> selectedRowBackgroundColor
        else -> mainBackgroundColor
    }

    // Calculate minimum width needed for all columns
    val minWidth = (columnWidths["Icon"] ?: 40.dp) +
            (columnWidths["Time"] ?: 120.dp) +
            (columnWidths["Dir"] ?: 50.dp) +
            (columnWidths["SeqNum"] ?: 70.dp) +
            (columnWidths["MsgType"] ?: 100.dp) +
            (columnWidths["Summary"] ?: 200.dp) +
            gridViewColumns.sumOf { tag -> (columnWidths["Tag_$tag"] ?: 120.dp).value.toInt() }.dp +
            200.dp // Extra space for spacer

    Row(
        modifier =
            Modifier
                .height(24.dp)
                .widthIn(min = minWidth),
    ) {
        // Expand/collapse icon - click only expands/collapses
        Box(
            modifier =
                Modifier
                    .width(columnWidths["Icon"] ?: 40.dp)
                    .fillMaxHeight()
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

        // Rest of the row - single click selects, double click expands
        Row(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .background(backgroundColor)
                    .testTag("message-row-${message.timestamp}")
                    .combinedClickable(
                        onClick = {
                            // Single click: select message and show detail panel
                            onSelectMessage?.invoke(message)
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
    }
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
) {
    // Render header fields
    renderFieldMap(
        fieldMap = message.header,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        indentLevel = 0,
        parentKey = messageId,
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
            val groupCount = fieldMap.getGroupCount(tag)
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
) {
    val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
    val rawValueDesc = dictionary.getFieldValueDescription(tag, value)
    // Only show value description if it differs from the value
    val valueDesc = if (rawValueDesc != null && rawValueDesc != value) rawValueDesc else ""
    val indent = (indentLevel * 16).dp

    Row(
        modifier =
            Modifier
                .height(20.dp)
                .width(5000.dp) // Fixed width for horizontal scroll
                .background(fieldRowBackgroundColor),
    ) {
        // Empty space for indent + icon column
        Box(
            modifier =
                Modifier
                    .width(40.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
        )

        // Tag
        Box(
            modifier =
                Modifier
                    .width(120.dp)
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
                    )
                }
            }
        }

        // Tag Description
        Box(
            modifier =
                Modifier
                    .width(200.dp)
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
                    .width(150.dp)
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
                    .weight(1f)
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
) {
    val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
    val indent = (indentLevel * 16).dp

    Row(
        modifier =
            Modifier
                .height(20.dp)
                .width(5000.dp) // Fixed width for horizontal scroll
                .background(separatorBackgroundColor)
                .clickable { onToggle() },
    ) {
        // Expand icon
        Box(
            modifier =
                Modifier
                    .width(40.dp)
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
                    .width(120.dp)
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
                    )
                }
            }
        }

        // Tag Description with count
        Box(
            modifier =
                Modifier
                    .width(200.dp)
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
                    .width(150.dp)
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
                    .weight(1f)
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
) {
    val indent = (indentLevel * 16).dp

    Row(
        modifier =
            Modifier
                .height(20.dp)
                .width(5000.dp) // Fixed width for horizontal scroll
                .background(groupInstanceBackgroundColor),
    ) {
        // Empty icon column
        Box(
            modifier =
                Modifier
                    .width(40.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
        )

        // Instance label spanning first columns
        Box(
            modifier =
                Modifier
                    .width(320.dp)
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
                    .width(150.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
        )

        Box(
            modifier =
                Modifier
                    .weight(1f)
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
                    message.getGroupCount(tag)
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
private val outgoingColor = AppTheme.Colors.messageOutgoing
private val incomingColor = AppTheme.Colors.messageIncoming
private val rejectionColor = AppTheme.Colors.messageRejection
private val selectedRowBackgroundColor = AppTheme.Colors.selectionPrimary
private val tagNumberColor = AppTheme.Colors.tagNumber
private val valueColor = AppTheme.Colors.fieldValue
private val fieldRowBackgroundColor = AppTheme.Colors.surfaceVariant
private val groupInstanceBackgroundColor = AppTheme.Colors.surfaceHeader

private val tooltipCornerRadius = RoundedCornerShape(4.dp)
private val iconSize = 14.dp
private val fabBackgroundColor = AppTheme.Colors.primary

// Helper function for direction color
private fun getDirectionColor(message: FixMessage, appSettings: com.knapsack.fixtool.model.AppSettings): Color =
    appSettings.messageColorScheme.getMessageColor(
        message.direction,
        message.isRejectionOrLogout(appSettings.rejectionRules),
        true
    )
