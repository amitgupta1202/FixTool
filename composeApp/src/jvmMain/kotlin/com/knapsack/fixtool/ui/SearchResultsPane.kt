package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.groupCountSafe
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import java.time.format.DateTimeFormatter

/**
 * Search results pane that displays search results in a flat grid view at the bottom of the screen.
 * Similar to IntelliJ's search results pane.
 */
@Composable
fun SearchResultsPane(
    searchResults: List<FixMessageViewModel.SearchResult>,
    selectedMessage: FixMessage?,
    dictionary: FixDictionary,
    appSettings: AppSettings,
    onSelectResult: (FixMessageViewModel.SearchResult) -> Unit,
    onClose: () -> Unit,
    /** What the columns have been dragged to — held by the dock, so it outlasts switching to the Ledger and back. */
    columnWidths: GridColumnWidths = remember { GridColumnWidths() },
    modifier: Modifier = Modifier,
) {
    // Create a stable username to color mapping based on session usernames
    val usernameColorMap =
        remember(searchResults) {
            val uniqueUsernames = searchResults.map { it.sessionUsername }.distinct()
            uniqueUsernames
                .mapIndexed { index, username ->
                    username to AppTheme.Colors.usernameColors[index % AppTheme.Colors.usernameColors.size]
                }.toMap()
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.background),
    ) {
        // The shared dock header. Its title carries the count, the way the dock tab's does, and its
        // trailing control stays a **Close** rather than becoming a Hide: unpinning the results loses them,
        // and the grammar reserves Close for the things that hold content.
        DockHeader(
            title = "Search results · ${searchResults.size}",
            onHide = onClose,
            hideTooltip = "Close",
            hideTag = "search-results-close",
            tag = "search-results-header",
        )

        val columns = SearchColumns(columnWidths, appSettings.gridViewColumns)

        // Grid with scrollbars
        Box(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            val horizontalScrollState = rememberScrollState()

            // Main content with horizontal scroll
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScrollState),
            ) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    // Column headers
                    SearchResultsGridHeader(
                        columns = columns,
                        dictionary = dictionary,
                        onFit = { key ->
                            columnWidths.toggleFit(key) { fittedSearchColumn(key, searchResults, dictionary) }
                        },
                    )

                    HorizontalDivider(color = AppTheme.Colors.border)

                    // Results grid
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                    ) {
                        items(searchResults) { result ->
                            SearchResultsGridRow(
                                result = result,
                                isSelected = selectedMessage == result.message,
                                usernameColorMap = usernameColorMap,
                                columns = columns,
                                appSettings = appSettings,
                                onClick = { onSelectResult(result) },
                            )
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
}

private val SESSION_WIDTH = 100.dp
private val TIME_WIDTH = 120.dp
private val DIR_WIDTH = 50.dp
private val SEQ_WIDTH = 70.dp
private val MSGTYPE_WIDTH = 100.dp
private val SUMMARY_WIDTH = 200.dp
private val TAG_WIDTH = 120.dp

/**
 * **The search results' columns at the widths they are now.**
 *
 * The header and the rows each wrote the same seven literals out for themselves, which agreed only while nothing
 * could change them. Both read them from here, and a getter reads the dragged width where the cell draws.
 */
private class SearchColumns(
    private val widths: GridColumnWidths,
    val tags: List<Int>,
) {
    val session get() = widths.widthOf("Session", SESSION_WIDTH)
    val time get() = widths.widthOf("Time", TIME_WIDTH)
    val dir get() = widths.widthOf("Dir", DIR_WIDTH)
    val seq get() = widths.widthOf("SeqNum", SEQ_WIDTH)
    val msgType get() = widths.widthOf("MsgType", MSGTYPE_WIDTH)
    val summary get() = widths.widthOf("Summary", SUMMARY_WIDTH)

    fun tag(tag: Int): Dp = widths.widthOf("Tag_$tag", TAG_WIDTH)

    fun resize(
        key: String,
        default: Dp,
    ): (Dp) -> Unit = { delta -> widths.resizeBy(key, delta, default) }
}

/**
 * The search results' columns, on the shared [GridHeader].
 *
 * It drew its own 150-line copy of a row the trace and the message grid each drew their own copy of. One
 * list of columns now; the row itself belongs to `GridHeader.kt`.
 */
@Composable
private fun SearchResultsGridHeader(
    columns: SearchColumns,
    dictionary: FixDictionary,
    onFit: (String) -> Unit,
) {
    fun resizable(
        key: String,
        label: String,
        width: Dp,
        default: Dp,
        align: Alignment = Alignment.Center,
    ) = GridColumn(
        label = label,
        width = width,
        align = align,
        tag = "search-column-$key",
        onResize = columns.resize(key, default),
        onDoubleClick = { onFit(key) },
    )

    GridHeader(
        listOf(
            resizable("Session", "Session", columns.session, SESSION_WIDTH),
            resizable("Time", "Time", columns.time, TIME_WIDTH),
            resizable("Dir", "Dir", columns.dir, DIR_WIDTH),
            resizable("SeqNum", "SeqNum", columns.seq, SEQ_WIDTH),
            resizable("MsgType", "MsgType", columns.msgType, MSGTYPE_WIDTH),
            resizable("Summary", "Summary", columns.summary, SUMMARY_WIDTH, Alignment.CenterStart),
        ) +
            columns.tags.map { tag ->
                resizable("Tag_$tag", dictionary.getFieldName(tag) ?: tag.toString(), columns.tag(tag), TAG_WIDTH)
            },
    )
}

/** The width a double-click fits a search-results column to: its header word and every result's value in it. */
private fun fittedSearchColumn(
    key: String,
    results: List<FixMessageViewModel.SearchResult>,
    dictionary: FixDictionary,
): Dp {
    val tag = key.removePrefix("Tag_").toIntOrNull()
    val all = results.asSequence()
    val values: Sequence<String> =
        when {
            key == "Session" -> all.map { it.sessionUsername }
            key == "Time" -> sequenceOf("HH:mm:ss.SSS")
            key == "Dir" -> sequenceOf("OUT")
            key == "SeqNum" -> all.map { it.msgSeqNum?.toString().orEmpty() }
            key == "MsgType" -> all.map { it.message.messageType }
            key == "Summary" -> all.map { it.messageTypeDescription }
            tag != null ->
                all.map { extractTopLevelFieldValue(it.message, tag) } + sequenceOf(dictionary.getFieldName(tag) ?: key)
            else -> emptySequence()
        }
    return fittedColumnWidth(sequenceOf(key) + values)
}

/**
 * Individual row in the search results grid
 */
@Composable
private fun SearchResultsGridRow(
    result: FixMessageViewModel.SearchResult,
    isSelected: Boolean,
    usernameColorMap: Map<String, Color>,
    columns: SearchColumns,
    appSettings: AppSettings,
    onClick: () -> Unit,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS") }
    val directionLabel =
        when (result.message.direction) {
            FixMessage.Direction.INCOMING -> "IN"
            FixMessage.Direction.OUTGOING -> "OUT"
        }

    val usernameColor = usernameColorMap[result.sessionUsername] ?: AppTheme.Colors.textSecondary

    // Get direction color using the same logic as session grid view
    val directionColor =
        appSettings.messageColorScheme.getMessageColor(
            result.message.direction,
            result.message.isRejectionOrLogout(),
            true,
        )

    val backgroundColor =
        if (isSelected) {
            Color(0xFF2D5A8C) // Same selection color as grid view
        } else {
            AppTheme.Colors.background
        }

    val cellBorderColor = AppTheme.Colors.border
    val textColor = AppTheme.Colors.text
    val tagNumberColor = AppTheme.Colors.tagNumber

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .height(24.dp),
    ) {
        // Session username
        Box(
            modifier =
                Modifier
                    .width(columns.session)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = result.sessionUsername,
                fontSize = 10.sp,
                color = usernameColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        // Time
        Box(
            modifier =
                Modifier
                    .width(columns.time)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = result.message.timestamp.format(timeFormatter),
                fontSize = 10.sp,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }

        // Direction
        Box(
            modifier =
                Modifier
                    .width(columns.dir)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = directionLabel,
                fontSize = 10.sp,
                color = directionColor,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Sequence number
        Box(
            modifier =
                Modifier
                    .width(columns.seq)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = result.msgSeqNum?.toString() ?: "",
                fontSize = 10.sp,
                color = tagNumberColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }

        // Message type code (single character like "D", "8")
        val msgTypeCode =
            try {
                result.message.quickfixMessage.header
                    .getString(35)
            } catch (e: Exception) {
                ""
            }

        Box(
            modifier =
                Modifier
                    .width(columns.msgType)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = msgTypeCode,
                fontSize = 10.sp,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }

        // Summary (message type description)
        Box(
            modifier =
                Modifier
                    .width(columns.summary)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = result.messageTypeDescription,
                fontSize = 10.sp,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // Custom columns from settings
        columns.tags.forEach { tag ->
            val fieldValue = extractTopLevelFieldValue(result.message, tag)
            Box(
                modifier =
                    Modifier
                        .width(columns.tag(tag))
                        .fillMaxHeight()
                        .border(0.5.dp, cellBorderColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fieldValue,
                    fontSize = 10.sp,
                    color = AppTheme.Colors.fieldValue,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }

        // Spacer to fill remaining space
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * Extract top-level field value from a FIX message (header, body, or trailer)
 * Matches the implementation from HierarchicalGridView.kt
 *
 * `internal` so the Trace panel's grid draws its `gridViewColumns` cells from the same function this
 * one does. Those two panels share a bottom slot and a column setting, and a reader switching between
 * them is entitled to the same value under the same heading — two copies of this would drift, and the
 * repeating-group case (`[3]` rather than a value) is exactly the kind of detail one copy would lose.
 */
internal fun extractTopLevelFieldValue(message: FixMessage, tag: Int): String {
    try {
        val qfMessage = message.quickfixMessage

        // Check header first (for fields like MsgSeqNum/34)
        if (qfMessage.header.isSetField(tag)) {
            return qfMessage.header.getString(tag)
        }

        // Check body
        if (qfMessage.isSetField(tag)) {
            // Check if it's a repeating group (we want to skip these)
            val groupCount =
                try {
                    qfMessage.groupCountSafe(tag)
                } catch (e: Exception) {
                    0
                }

            // If it's a repeating group, return the count instead of the value
            return if (groupCount > 0) {
                "[$groupCount]"
            } else {
                // Get the field value
                qfMessage.getString(tag)
            }
        }

        // Check trailer
        if (qfMessage.trailer.isSetField(tag)) {
            return qfMessage.trailer.getString(tag)
        }

        return ""
    } catch (e: Exception) {
        return ""
    }
}
