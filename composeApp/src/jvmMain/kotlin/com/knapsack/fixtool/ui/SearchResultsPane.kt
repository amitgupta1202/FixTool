package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
        // Header row with title and close button
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2D2D)) // Same as grid header
                    .height(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Search Results (${searchResults.size})",
                fontSize = 10.sp,
                color = AppTheme.Colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            TooltipIconButton(
                tooltip = "Close",
                onClick = onClose,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        HorizontalDivider(color = AppTheme.Colors.border)

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
                        gridViewColumns = appSettings.gridViewColumns,
                        dictionary = dictionary,
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
                                gridViewColumns = appSettings.gridViewColumns,
                                dictionary = dictionary,
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

/**
 * Header row for the search results grid showing column names
 */
@Composable
private fun SearchResultsGridHeader(
    gridViewColumns: List<Int>,
    dictionary: FixDictionary,
) {
    val headerBackgroundColor = Color(0xFF2D2D2D)
    val headerTextColor = AppTheme.Colors.textSecondary
    val headerBorderColor = Color(0xFF454545)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(headerBackgroundColor)
                .height(24.dp),
    ) {
        // Session column
        Box(
            modifier =
                Modifier
                    .width(100.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Session",
                fontSize = 10.sp,
                color = headerTextColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }

        // Time column
        Box(
            modifier =
                Modifier
                    .width(120.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Time",
                fontSize = 10.sp,
                color = headerTextColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }

        // Direction column
        Box(
            modifier =
                Modifier
                    .width(50.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Dir",
                fontSize = 10.sp,
                color = headerTextColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }

        // Sequence number column
        Box(
            modifier =
                Modifier
                    .width(70.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "SeqNum",
                fontSize = 10.sp,
                color = headerTextColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }

        // Message type column
        Box(
            modifier =
                Modifier
                    .width(100.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "MsgType",
                fontSize = 10.sp,
                color = headerTextColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }

        // Summary column
        Box(
            modifier =
                Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, headerBorderColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Summary",
                fontSize = 10.sp,
                color = headerTextColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // Custom columns from settings
        gridViewColumns.forEach { tag ->
            val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
            Box(
                modifier =
                    Modifier
                        .width(120.dp)
                        .fillMaxHeight()
                        .border(0.5.dp, headerBorderColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fieldName,
                    fontSize = 10.sp,
                    color = headerTextColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Spacer to fill remaining space
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * Individual row in the search results grid
 */
@Composable
private fun SearchResultsGridRow(
    result: FixMessageViewModel.SearchResult,
    isSelected: Boolean,
    usernameColorMap: Map<String, Color>,
    gridViewColumns: List<Int>,
    dictionary: FixDictionary,
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
                    .width(100.dp)
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
                    .width(120.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = result.message.timestamp.format(timeFormatter),
                fontSize = 10.sp,
                color = textColor,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Direction
        Box(
            modifier =
                Modifier
                    .width(50.dp)
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
                    .width(70.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = result.msgSeqNum?.toString() ?: "",
                fontSize = 10.sp,
                color = tagNumberColor,
                fontFamily = FontFamily.Monospace,
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
                    .width(100.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, cellBorderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = msgTypeCode,
                fontSize = 10.sp,
                color = textColor,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Summary (message type description)
        Box(
            modifier =
                Modifier
                    .width(200.dp)
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
        gridViewColumns.forEach { tag ->
            val fieldValue = extractTopLevelFieldValue(result.message, tag)
            Box(
                modifier =
                    Modifier
                        .width(120.dp)
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
