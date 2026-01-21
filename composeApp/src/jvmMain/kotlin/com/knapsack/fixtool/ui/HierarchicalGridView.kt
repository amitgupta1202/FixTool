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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Save
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
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

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
    recentlySentMessageTimestamp: LocalDateTime? = null,
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    // Latency tracking parameters
    showLatencyColumn: Boolean = false,
    getLatencyForMessage: ((String) -> Long?)? = null,
    latencyWarningThresholdMicros: Long = 100_000L,
    latencyCriticalThresholdMicros: Long = 500_000L,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Track which messages are expanded (key: message timestamp-index for uniqueness)
    val expandedMessages = remember { mutableStateMapOf<String, Boolean>() }

    // Track which groups are expanded (key: "messageId_groupKey")
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // Multi-selection state
    val selectedMessageIds = remember { mutableStateSetOf<String>() }
    var lastClickedIndex by remember { mutableStateOf<Int?>(null) }

    // Helper function to get message ID
    fun getMessageId(message: AppMessage, index: Int): String = "${message.timestamp}-$index"

    // Helper to get selected FixMessages in order
    fun getSelectedFixMessages(): List<FixMessage> =
        messages.mapIndexedNotNull { index, msg ->
            if (msg is FixMessage && selectedMessageIds.contains(getMessageId(msg, index))) msg else null
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
                val messageId = getMessageId(message, index)
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
        if (selected.isNotEmpty()) {
            val fileChooser =
                JFileChooser().apply {
                    dialogTitle = "Save Messages to File"
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    fileFilter = FileNameExtensionFilter("FIX Message Files (*.fix, *.txt)", "fix", "txt")
                    selectedFile = File("messages_${System.currentTimeMillis()}.fix")
                }

            if (fileChooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                var file = fileChooser.selectedFile
                // Ensure file has extension
                if (!file.name.endsWith(".fix") && !file.name.endsWith(".txt")) {
                    file = File(file.absolutePath + ".fix")
                }
                file.writeText(selected.joinToString("\n") { it.rawMessage })
            }
        }
    }

    // Select all messages
    fun selectAll() {
        messages.forEachIndexed { index, message ->
            if (message is FixMessage) {
                selectedMessageIds.add(getMessageId(message, index))
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

    // Function to calculate optimal widths for expanded grid columns based on message content
    fun calculateExpandedGridWidths(message: quickfix.Message): Map<String, androidx.compose.ui.unit.Dp> {
        val minWidth = 50.dp
        val maxWidth = 500.dp
        val charWidth = 7 // pixels per character approximately

        val samples =
            mutableMapOf(
                "Tag" to mutableListOf<String>(),
                "TagDescription" to mutableListOf<String>(),
                "Value" to mutableListOf<String>(),
                "ValueDescription" to mutableListOf<String>(),
            )

        // Helper function to collect field samples
        fun collectFieldSamples(fieldMap: FieldMap) {
            val iterator = fieldMap.iterator()
            while (iterator.hasNext()) {
                @Suppress("UNCHECKED_CAST")
                val field = iterator.next() as Field<*>
                val tag = field.tag
                val value = field.getObject().toString()

                samples["Tag"]?.add(tag.toString())

                val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
                samples["TagDescription"]?.add(fieldName)

                samples["Value"]?.add(value)

                val valueDesc = dictionary.getFieldValueDescription(tag, value)
                if (valueDesc != null && valueDesc != value) {
                    samples["ValueDescription"]?.add(valueDesc)
                }

                // Process groups recursively
                try {
                    val groupCount = fieldMap.getGroupCount(tag)
                    if (groupCount > 0) {
                        for (i in 1..groupCount) {
                            try {
                                val group = fieldMap.getGroup(i, tag)
                                collectFieldSamples(group)
                            } catch (e: Exception) {
                                // Skip invalid groups
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Not a group
                }
            }
        }

        // Collect samples from all sections
        collectFieldSamples(message.header)
        collectFieldSamples(message)
        collectFieldSamples(message.trailer)

        // Calculate widths based on samples
        val result = mutableMapOf<String, androidx.compose.ui.unit.Dp>()

        result["IconColumn"] = 40.dp // Fixed size

        samples.forEach { (columnKey, sampleList) ->
            val maxLength = sampleList.maxOfOrNull { it.length } ?: 10
            val calculatedWidth = (maxLength * charWidth + 16).dp
            result[columnKey] = calculatedWidth.coerceIn(minWidth, maxWidth)
        }

        return result
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
                    ) {
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
                        val allSelected =
                            allFixMessages.isNotEmpty() &&
                                allFixMessages.all { msg ->
                                    val idx = messages.indexOf(msg)
                                    selectedMessageIds.contains(getMessageId(msg, idx))
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

                    // Message rows
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                    ) {
                        messages.forEachIndexed { index, message ->
                            val messageId = "${message.timestamp}-$index"
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
                                            isMultiSelected = selectedMessageIds.contains(messageId),
                                            messageIndex = index,
                                            recentlySentMessageTimestamp = recentlySentMessageTimestamp,
                                            onToggleExpand = {
                                                val wasExpanded = isExpanded
                                                expandedMessages[messageId] = !isExpanded

                                                // Auto-fit columns when expanding for the first time
                                                if (!wasExpanded) {
                                                    val optimalWidths = calculateExpandedGridWidths(message.quickfixMessage)
                                                    expandedGridColumnWidths.putAll(optimalWidths)
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
    isMultiSelected: Boolean = false,
    messageIndex: Int = 0,
    recentlySentMessageTimestamp: LocalDateTime? = null,
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

    // Background color: highlight if recently sent, selected, multi-selected, or default
    val backgroundColor =
        when {
            isRecentlySent -> {
                AppTheme.Colors.messageRecentlySent
            }

            isSelected -> selectedRowBackgroundColor
            isMultiSelected -> multiSelectedRowBackgroundColor
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

    Row(
        modifier =
            Modifier
                .height(24.dp)
                .widthIn(min = minWidth),
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
    val indent = (indentLevel * 16).dp

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
    val indent = (indentLevel * 16).dp

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
    val indent = (indentLevel * 16).dp

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
private val fabBackgroundColor = AppTheme.Colors.primary

// Helper function for direction color
private fun getDirectionColor(message: FixMessage, appSettings: com.knapsack.fixtool.model.AppSettings): Color =
    appSettings.messageColorScheme.getMessageColor(
        message.direction,
        message.isRejectionOrLogout(appSettings.rejectionRules),
        true,
    )
