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
import androidx.compose.ui.graphics.Color
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
import com.knapsack.fixtool.model.FixMessage.Direction.INCOMING
import com.knapsack.fixtool.model.FixMessage.Direction.OUTGOING
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.Separator
import java.awt.Cursor

enum class SplitOrientation {
    HORIZONTAL,
    VERTICAL,
}

@Composable
fun SplitView(
    sessions: List<FixMessageSession>,
    dictionary: FixDictionary,
    onCloseSession: (Int) -> Unit,
    onMoveSession: ((Int, Int) -> Unit)? = null,
    selectedMessage: FixMessage? = null,
    onSelectMessage: ((FixMessage?) -> Unit)? = null,
    onPasteMessage: ((String) -> Unit)? = null,
    orientation: SplitOrientation = SplitOrientation.HORIZONTAL,
    gridViewColumns: List<Int> = emptyList(),
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No active sessions. Click the connection button to connect to a FIX server.",
                color = emptyStateTextColor,
                fontSize = 14.sp,
            )
        }
        return
    }

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
                                SessionPanel(
                                    session = sessions[index],
                                    dictionary = dictionary,
                                    onClose = { onCloseSession(index) },
                                    onMoveLeft =
                                        if (index > 0 && onMoveSession != null) {
                                            { onMoveSession(index, index - 1) }
                                        } else {
                                            null
                                        },
                                    onMoveRight =
                                        if (index < sessions.size - 1 && onMoveSession != null) {
                                            { onMoveSession(index, index + 1) }
                                        } else {
                                            null
                                        },
                                    onConnect = { sessions[index].reconnect() },
                                    onDisconnect = { sessions[index].disconnect() },
                                    selectedMessage = selectedMessage,
                                    onSelectMessage = onSelectMessage,
                                    onPasteMessage = onPasteMessage,
                                    gridViewColumns = gridViewColumns,
                                    appSettings = appSettings,
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
    dictionary: FixDictionary,
    onClose: (() -> Unit)?,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
    onConnect: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    selectedMessage: FixMessage? = null,
    onSelectMessage: ((FixMessage?) -> Unit)? = null,
    onPasteMessage: ((String) -> Unit)? = null,
    gridViewColumns: List<Int> = emptyList(),
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    modifier: Modifier = Modifier,
) {
    val messages by session.messages.collectAsState()
    val sessionViewMode by session.viewMode.collectAsState()
    val wrapText by session.wrapText.collectAsState()
    val searchVisible by session.searchVisible.collectAsState()
    val filterVisible by session.filterVisible.collectAsState()
    val filterRegex by session.filterRegex.collectAsState()
    val filterShowIncoming by session.filterShowIncoming.collectAsState()
    val filterShowOutgoing by session.filterShowOutgoing.collectAsState()
    val filterShowSeparator by session.filterShowSeparator.collectAsState()
    val filterMessageTypes by session.filterMessageTypes.collectAsState()
    val connectionState by session.connectionState.collectAsState()
    val hideProtocolTags by session.hideProtocolTags.collectAsState()

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
            // Connect/Disconnect toggle button (replaces drag handle)
            if (connectionState.canConnect() && onConnect != null) {
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
                modifier = Modifier.weight(1f),
            )

            // Wrap text toggle (RAW mode only)
            Spacer(modifier = Modifier.width(4.dp))

            if (sessionViewMode == FixMessageSession.ViewMode.RAW) {
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

            // Protocol tags toggle (only visible in PARSED mode)
            if (sessionViewMode == FixMessageSession.ViewMode.PARSED) {
                Spacer(modifier = Modifier.width(2.dp))

                TooltipIconButton(
                    tooltip = if (hideProtocolTags) "Show Protocol Tags" else "Hide Protocol Tags",
                    onClick = { session.toggleHideProtocolTags() },
                    modifier = Modifier.size(buttonSize),
                ) {
                    Icon(
                        imageVector = if (hideProtocolTags) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (hideProtocolTags) "Show Protocol Tags" else "Hide Protocol Tags",
                        tint = iconTintColor,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }

            // View mode toggle for this session - shows destination icon (matching global toolbar)
            val viewModeTooltip =
                when (sessionViewMode) {
                    FixMessageSession.ViewMode.RAW -> "Switch to Grid View"
                    FixMessageSession.ViewMode.PARSED -> "Switch to Terminal View"
                }

            Spacer(modifier = Modifier.width(2.dp))

            TooltipIconButton(
                tooltip = viewModeTooltip,
                onClick = { session.toggleViewMode() },
                modifier = Modifier.size(24.dp),
            ) {
                val icon =
                    when (sessionViewMode) {
                        FixMessageSession.ViewMode.RAW -> Icons.Default.Apps  // Show grid icon when in terminal (click to switch to grid)
                        FixMessageSession.ViewMode.PARSED -> Icons.Default.Terminal  // Show terminal icon when in grid (click to switch to terminal)
                    }
                Icon(
                    imageVector = icon,
                    contentDescription = "Toggle View",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(16.dp),
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
                    Text(
                        text = "◀",
                        color = iconTintColor,
                        fontSize = 12.sp,
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
                    Text(
                        text = "▶",
                        color = iconTintColor,
                        fontSize = 12.sp,
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

        // Filter input section - compact single row
        if (filterVisible) {
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

        // Apply filter to messages
        val filteredMessages =
            remember(
                messages,
                filterRegex,
                filterShowIncoming,
                filterShowOutgoing,
                filterShowSeparator,
                filterMessageTypes,
            ) {
                messages.filter { message ->
                    // Direction filter
                    val directionMatch =
                        when (message) {
                            is Separator -> return@filter filterShowSeparator
                            is FixMessage ->
                                when (message.direction) {
                                    INCOMING -> filterShowIncoming
                                    OUTGOING -> filterShowOutgoing
                                }
                        }

                    if (!directionMatch) return@filter false

                    // Message type filter
                    val messageTypeMatch =
                        filterMessageTypes.isBlank() ||
                            run {
                                val types = filterMessageTypes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                types.isEmpty() || types.any { it.equals(message.messageType, ignoreCase = true) }
                            }

                    if (!messageTypeMatch) return@filter false

                    // Regex filter
                    val regexMatch =
                        if (filterRegex.isBlank()) {
                            true
                        } else {
                            try {
                                val regex = Regex(filterRegex, RegexOption.IGNORE_CASE)
                                message.toDisplayString().contains(regex)
                            } catch (e: Exception) {
                                // If regex is invalid, include the message
                                true
                            }
                        }

                    regexMatch
                }
            }

        // Panel content
        FixMessageDisplay(
            messages = filteredMessages,
            viewMode = sessionViewMode,
            dictionary = dictionary,
            wrapText = wrapText,
            selectedMessage = selectedMessage,
            onSelectMessage = onSelectMessage,
            onPasteMessage = onPasteMessage,
            showDetailPanel = false,
            searchVisible = searchVisible,
            hideProtocolTags = hideProtocolTags,
            gridViewColumns = gridViewColumns,
            appSettings = appSettings,
            onToggleSearch = { session.toggleSearch() },
            modifier = Modifier.weight(1f),
        )
    }
}

// Constants
private val emptyStateTextColor = AppTheme.Colors.textDisabled
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
