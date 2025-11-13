package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import java.time.format.DateTimeFormatter

@Composable
fun SearchAllSessionsDialog(
    searchQuery: String,
    searchResults: List<FixMessageViewModel.SearchResult>,
    onQueryChange: (String) -> Unit,
    onResultClick: (FixMessageViewModel.SearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    // Create a stable username to color mapping based on session usernames
    val usernameColorMap = remember(searchResults) {
        val uniqueUsernames = searchResults.map { it.sessionUsername }.distinct()
        uniqueUsernames.mapIndexed { index, username ->
            username to AppTheme.Colors.usernameColors[index % AppTheme.Colors.usernameColors.size]
        }.toMap()
    }

    // Focus requester for auto-focusing the search input
    val focusRequester = remember { FocusRequester() }

    // Request focus when dialog opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .width(1000.dp)
                .heightIn(max = 700.dp),
            shape = RoundedCornerShape(8.dp),
            color = AppTheme.Colors.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppTheme.Colors.background)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Search All Sessions",
                        fontSize = 16.sp,
                        color = AppTheme.Colors.text,
                    )

                    TooltipIconButton(
                        tooltip = "Close",
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = AppTheme.Colors.text,
                        )
                    }
                }

                HorizontalDivider(color = AppTheme.Colors.background)

                // Search Input
                SearchInputField(
                    query = searchQuery,
                    onQueryChange = onQueryChange,
                    focusRequester = focusRequester,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )

                HorizontalDivider(color = AppTheme.Colors.background)

                // Results Count
                Text(
                    text = "${searchResults.size} result${if (searchResults.size != 1) "s" else ""} found",
                    fontSize = 12.sp,
                    color = AppTheme.Colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )

                // Results List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(AppTheme.Colors.background),
                ) {
                    items(searchResults) { result ->
                        SearchResultItem(
                            result = result,
                            usernameColorMap = usernameColorMap,
                            onClick = {
                                onResultClick(result)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .background(
                color = if (isFocused) AppTheme.Colors.surface else AppTheme.Colors.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
            )
            .border(
                width = 1.dp,
                color = if (isFocused) AppTheme.Colors.primary else AppTheme.Colors.border,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = "Search",
            tint = AppTheme.Colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                fontSize = 13.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(AppTheme.Colors.primary),
            interactionSource = interactionSource,
            singleLine = true,
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Enter search text or regex pattern...",
                            fontSize = 13.sp,
                            color = AppTheme.Colors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun SearchResultItem(
    result: FixMessageViewModel.SearchResult,
    usernameColorMap: Map<String, androidx.compose.ui.graphics.Color>,
    onClick: () -> Unit,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS") }
    val directionLabel = when (result.message.direction) {
        com.knapsack.fixtool.model.FixMessage.Direction.INCOMING -> "IN"
        com.knapsack.fixtool.model.FixMessage.Direction.OUTGOING -> "OUT"
    }

    // Get username color from session username
    val usernameColor = usernameColorMap[result.sessionUsername] ?: AppTheme.Colors.textSecondary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(AppTheme.Colors.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(
                width = 1.dp,
                color = AppTheme.Colors.border,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(12.dp),
    ) {
        // Header Row: Username • Timestamp • MessageType • Direction (all in one row)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left side: Username, timestamp, message type
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                // Session Username (prominent with color)
                Text(
                    text = result.sessionUsername,
                    fontSize = 13.sp,
                    color = usernameColor,
                    fontFamily = FontFamily.Monospace,
                )

                Text(
                    text = "•",
                    fontSize = 11.sp,
                    color = AppTheme.Colors.textSecondary,
                )

                // Timestamp
                Text(
                    text = result.message.timestamp.format(timeFormatter),
                    fontSize = 11.sp,
                    color = AppTheme.Colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                )

                Text(
                    text = "•",
                    fontSize = 11.sp,
                    color = AppTheme.Colors.textSecondary,
                )

                // Message Type
                Text(
                    text = result.messageTypeDescription,
                    fontSize = 11.sp,
                    color = AppTheme.Colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Right side: Direction
            Text(
                text = directionLabel,
                fontSize = 11.sp,
                color = if (result.message.direction == com.knapsack.fixtool.model.FixMessage.Direction.INCOMING) {
                    AppTheme.Colors.success
                } else {
                    AppTheme.Colors.warning
                },
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Detail Section: Raw FIX message only
        Text(
            text = result.message.rawMessage,
            fontSize = 11.sp,
            color = AppTheme.Colors.text,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
}
