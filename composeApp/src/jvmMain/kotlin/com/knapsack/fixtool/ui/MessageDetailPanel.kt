package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import quickfix.Field
import quickfix.FieldMap
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun MessageDetailPanel(
    message: FixMessage?,
    dictionary: FixDictionary,
    onClose: () -> Unit,
    onPasteMessage: ((String) -> Unit)? = null,
    appSettings: com.knapsack.fixtool.model.AppSettings =
        com.knapsack.fixtool.model.AppSettings
            .default(),
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var rawMessageSplitRatio by remember { mutableStateOf(0.2f) } // Raw message section takes 30% by default
    var hideProtocolTags by remember { mutableStateOf(appSettings.hideProtocolTagsByDefault) } // Use setting
    var searchQuery by remember { mutableStateOf("") }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .background(panelBackgroundColor),
    ) {
        // Always show the panel structure, with raw message section for paste functionality
        run {
            // State for expanded groups (moved up to be accessible by toolbar buttons)
            var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }

            Column(modifier = Modifier.fillMaxSize()) {
                // Top border
                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                // Header
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(headerBackgroundColor)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Message Details",
                        color = headerTextColor,
                        fontSize = 11.sp,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Only show message-specific buttons when a message is selected
                    if (message != null) {
                        // Toggle expand/collapse all button
                        val allGroupKeys = remember(message) { collectAllGroupKeys(message) }
                        val allExpanded = allGroupKeys.isNotEmpty() && expandedGroups.containsAll(allGroupKeys)

                        TooltipIconButton(
                            tooltip = if (allExpanded) "Collapse All Groups" else "Expand All Groups",
                            onClick = {
                                expandedGroups = if (allExpanded) emptySet() else allGroupKeys
                            },
                            modifier = buttonSize,
                        ) {
                            Icon(
                                imageVector = if (allExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                contentDescription = if (allExpanded) "Collapse All" else "Expand All",
                                tint = iconTintColor,
                                modifier = iconSize,
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Toggle protocol tags button
                        TooltipIconButton(
                            tooltip = if (hideProtocolTags) "Show Protocol Tags" else "Hide Protocol Tags",
                            onClick = { hideProtocolTags = !hideProtocolTags },
                            modifier = buttonSize,
                        ) {
                            Icon(
                                imageVector = if (hideProtocolTags) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (hideProtocolTags) "Show Protocol Tags" else "Hide Protocol Tags",
                                tint = iconTintColor,
                                modifier = iconSize,
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Close button
                    TooltipIconButton(
                        tooltip = "Close Detail Panel",
                        onClick = onClose,
                        modifier = buttonSize,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = iconTintColor,
                            modifier = iconSize,
                        )
                    }
                }

                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                // Search bar
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(headerBackgroundColor)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = searchIconColor,
                        modifier = searchIconSize,
                    )

                    val searchInteractionSource = remember { MutableInteractionSource() }
                    val searchIsFocused by searchInteractionSource.collectIsFocusedAsState()

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(22.dp)
                                .background(panelBackgroundColor, searchFieldShape)
                                .border(
                                    width = 1.dp,
                                    color = getSearchBorderColor(searchIsFocused),
                                    shape = searchFieldShape,
                                ).padding(horizontal = 6.dp, vertical = 3.dp),
                        textStyle = searchTextStyle,
                        singleLine = true,
                        cursorBrush = SolidColor(focusedBorderColor),
                        interactionSource = searchInteractionSource,
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty() && !searchIsFocused) {
                                Text(
                                    text = "Search tags, names, or values...",
                                    style = searchPlaceholderStyle,
                                )
                            }
                            innerTextField()
                        },
                    )
                }

                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                // Message direction and timestamp (only when message is selected)
                if (message != null) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(metadataBackgroundColor)
                                .padding(12.dp),
                    ) {
                        // Match session coloring: blue for outgoing, red for incoming rejects, green for other incoming
                        val (directionColor, directionText) = getDirectionInfo(message)

                        Text(
                            text = directionText,
                            color = directionColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message.timestamp.toString(),
                            color = timestampColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                    )
                }

                // Resizable split between fields list and raw message
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val totalHeightPx = with(density) { maxHeight.toPx() }
                    val fieldsHeight = if (message != null) maxHeight * (1f - rawMessageSplitRatio) else 0.dp
                    val rawMessageHeight = if (message != null) maxHeight * rawMessageSplitRatio else maxHeight

                    Column {
                        // Fields list (only when message is selected)
                        if (message != null) {
                            Box(modifier = Modifier.height(fieldsHeight)) {
                                SelectionContainer {
                                    LazyColumn(
                                        state = listState,
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .padding(end = 16.dp),
                                    ) {
                                        // Render using QuickFIX Message object if available, otherwise fallback to fieldTree
                                        renderQuickFixMessage(
                                            message = message.quickfixMessage,
                                            dictionary = dictionary,
                                            hideProtocolTags = hideProtocolTags,
                                            searchQuery = searchQuery,
                                            protocolTags = appSettings.protocolTags,
                                            expandedGroups = expandedGroups,
                                            onToggleGroup = { key ->
                                                expandedGroups =
                                                    if (key in expandedGroups) {
                                                        expandedGroups - key
                                                    } else {
                                                        expandedGroups + key
                                                    }
                                            },
                                        )
                                    }
                                }

                                // Vertical Scrollbar
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(listState),
                                    modifier =
                                        Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                            .padding(end = 4.dp),
                                )
                            }

                            // Draggable divider (only shown when message is selected)
                            var isHovering by remember { mutableStateOf(false) }
                            Box(
                                modifier =
                                    Modifier
                                        .height(AppTheme.Separators.dividerThickness)
                                        .fillMaxWidth()
                                        .background(
                                            if (isHovering) AppTheme.Separators.hoverColor else AppTheme.Separators.color,
                                        ).pointerHoverIcon(
                                            PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)),
                                        ).hoverable(
                                            interactionSource =
                                                remember { MutableInteractionSource() }
                                                    .also { source ->
                                                        LaunchedEffect(source) {
                                                            source.interactions.collect { interaction ->
                                                                isHovering =
                                                                    interaction is androidx.compose.foundation.interaction.HoverInteraction.Enter
                                                            }
                                                        }
                                                    },
                                        ).pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                val deltaRatio = dragAmount.y / totalHeightPx
                                                rawMessageSplitRatio =
                                                    (rawMessageSplitRatio - deltaRatio).coerceIn(0.2f, 0.8f)
                                            }
                                        },
                            )
                        }

                        // Raw message
                        Column(
                            modifier =
                                Modifier
                                    .height(rawMessageHeight)
                                    .fillMaxWidth()
                                    .background(metadataBackgroundColor)
                                    .padding(12.dp),
                        ) {
                            // Header with label and copy/paste buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "RAW MESSAGE",
                                    color = timestampColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    // Paste button
                                    if (onPasteMessage != null) {
                                        TooltipIconButton(
                                            tooltip = "Paste Message from Clipboard",
                                            onClick = {
                                                try {
                                                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                                    val data =
                                                        clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor)
                                                    if (data is String && data.isNotBlank()) {
                                                        onPasteMessage(data)
                                                    }
                                                } catch (e: Exception) {
                                                    // Clipboard error - ignore
                                                }
                                            },
                                            modifier = rawActionButtonSize,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentPaste,
                                                contentDescription = "Paste",
                                                tint = iconTintColor,
                                                modifier = rawActionIconSize,
                                            )
                                        }
                                    }

                                    // Copy button (only when message exists)
                                    if (message != null) {
                                        TooltipIconButton(
                                            tooltip = "Copy All",
                                            onClick = {
                                                if (message.rawMessage.isNotBlank()) {
                                                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                                    clipboard.setContents(
                                                        StringSelection(message.rawMessage),
                                                        null,
                                                    )
                                                }
                                            },
                                            modifier = rawActionButtonSize,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy All",
                                                tint = iconTintColor,
                                                modifier = rawActionIconSize,
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (message != null) {
                                    // Show message raw content when selected
                                    SelectionContainer {
                                        val rawScrollState = rememberScrollState()
                                        Text(
                                            text = message.rawMessage,
                                            color = rawMessageTextColor,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rawScrollState)
                                                    .padding(end = 16.dp),
                                        )
                                    }
                                } else {
                                    // Show prompt to paste when no message selected
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Paste a FIX message to visualize it\n(Click the paste button above)",
                                            color = placeholderTextColor,
                                            fontSize = 11.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }

                                // Scrollbar for raw message
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(rememberScrollState()),
                                    modifier =
                                        Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                            .padding(end = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(
    tag: Int,
    value: String,
    dictionary: FixDictionary,
    indentLevel: Int = 0,
) {
    val fieldName = dictionary.getFieldName(tag) ?: tag.toString()
    val translation = dictionary.getFieldValueDescription(tag, value)

    val displayValue =
        if (translation != null && translation != value) {
            "$value [$translation]"
        } else {
            value
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(fieldRowBackgroundColor)
                .padding(start = (8 + indentLevel * 8).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        // Tag number
        Text(
            text = tag.toString(),
            color = tagNumberColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(35.dp),
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Field name
        Text(
            text = fieldName,
            color = fieldNameColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(130.dp),
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Value
        Text(
            text = displayValue,
            color = fieldValueColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GroupHeaderRow(
    tag: Int,
    count: Int,
    dictionary: FixDictionary,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    indentLevel: Int = 0,
) {
    val groupName = dictionary.getFieldName(tag) ?: "Group $tag"

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(groupHeaderBackgroundColor)
                .clickable { onToggle() }
                .padding(start = (8 + indentLevel * 8).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        // Group indicator icon (collapsible)
        Text(
            text = if (isExpanded) "▼" else "▶",
            color = groupHeaderTextColor,
            fontSize = 8.sp,
            modifier = Modifier.width(35.dp),
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Group name
        Text(
            text = "$groupName ($count)",
            color = groupHeaderTextColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GroupInstanceHeader(
    instanceNumber: Int,
    indentLevel: Int = 0,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(groupInstanceBackgroundColor)
                .padding(start = (8 + indentLevel * 8).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = "[$instanceNumber]",
            color = groupInstanceTextColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Renders a QuickFIX Message using its native hierarchical structure
 */
private fun LazyListScope.renderQuickFixMessage(
    message: quickfix.Message,
    dictionary: FixDictionary,
    hideProtocolTags: Boolean,
    searchQuery: String,
    protocolTags: Set<Int>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    indentLevel: Int = 0,
    parentKey: String = "",
) {
    // Render header fields
    renderFieldMap(
        fieldMap = message.header,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        searchQuery = searchQuery,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        onToggleGroup = onToggleGroup,
        indentLevel = indentLevel,
        parentKey = parentKey,
    )

    // Render body fields
    renderFieldMap(
        fieldMap = message,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        searchQuery = searchQuery,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        onToggleGroup = onToggleGroup,
        indentLevel = indentLevel,
        parentKey = parentKey,
    )

    // Render trailer fields
    renderFieldMap(
        fieldMap = message.trailer,
        dictionary = dictionary,
        hideProtocolTags = hideProtocolTags,
        searchQuery = searchQuery,
        protocolTags = protocolTags,
        expandedGroups = expandedGroups,
        onToggleGroup = onToggleGroup,
        indentLevel = indentLevel,
        parentKey = parentKey,
    )
}

/**
 * Renders a FieldMap (which can be header, body, trailer, or a group)
 */
private fun LazyListScope.renderFieldMap(
    fieldMap: FieldMap,
    dictionary: FixDictionary,
    hideProtocolTags: Boolean,
    searchQuery: String,
    protocolTags: Set<Int>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    indentLevel: Int = 0,
    parentKey: String = "",
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
                // This is a repeating group count field
                val groupKey =
                    if (parentKey.isEmpty()) {
                        "${tag}_$groupCount"
                    } else {
                        "$parentKey/${tag}_$groupCount"
                    }

                // Check if the group or any of its children match the search query
                val matchesSearch =
                    if (searchQuery.isNotBlank()) {
                        groupMatchesSearch(
                            fieldMap = fieldMap,
                            tag = tag,
                            groupCount = groupCount,
                            dictionary = dictionary,
                            searchQuery = searchQuery,
                            hideProtocolTags = hideProtocolTags,
                            protocolTags = protocolTags,
                        )
                    } else {
                        true
                    }

                if (!matchesSearch) {
                    continue
                }

                val isExpanded = expandedGroups.contains(groupKey)

                // Render group header
                item {
                    GroupHeaderRow(
                        tag = tag,
                        count = groupCount,
                        dictionary = dictionary,
                        isExpanded = isExpanded,
                        onToggle = { onToggleGroup(groupKey) },
                        indentLevel = indentLevel,
                    )
                }

                // Render group instances if expanded
                if (isExpanded) {
                    for (i in 1..groupCount) {
                        try {
                            val group = fieldMap.getGroup(i, tag)

                            item {
                                GroupInstanceHeader(
                                    instanceNumber = i,
                                    indentLevel = indentLevel + 1,
                                )
                            }

                            // Recursively render group fields
                            renderFieldMap(
                                fieldMap = group,
                                dictionary = dictionary,
                                hideProtocolTags = hideProtocolTags,
                                searchQuery = searchQuery,
                                protocolTags = protocolTags,
                                expandedGroups = expandedGroups,
                                onToggleGroup = onToggleGroup,
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
                // Apply search filter for regular fields
                if (searchQuery.isNotBlank()) {
                    if (!fieldMatchesSearch(tag, value, dictionary, searchQuery)) {
                        continue
                    }
                }

                item {
                    FieldRow(
                        tag = tag,
                        value = value,
                        dictionary = dictionary,
                        indentLevel = indentLevel,
                    )
                }
            }
        } catch (e: Exception) {
            // If getGroupCount throws an exception, treat as regular field
            // Apply search filter
            if (searchQuery.isNotBlank()) {
                if (!fieldMatchesSearch(tag, value, dictionary, searchQuery)) {
                    continue
                }
            }

            item {
                FieldRow(
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
 * Checks if a field matches the search query
 */
private fun fieldMatchesSearch(
    tag: Int,
    value: String,
    dictionary: FixDictionary,
    searchQuery: String,
): Boolean {
    val query = searchQuery.lowercase()
    val fieldName = dictionary.getFieldName(tag)?.lowercase() ?: ""
    val translation = dictionary.getFieldValueDescription(tag, value)?.lowercase() ?: ""

    return tag.toString().contains(query) ||
        fieldName.contains(query) ||
        value.lowercase().contains(query) ||
        translation.contains(query)
}

/**
 * Recursively checks if a group or any of its children match the search query
 */
private fun groupMatchesSearch(
    fieldMap: FieldMap,
    tag: Int,
    groupCount: Int,
    dictionary: FixDictionary,
    searchQuery: String,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
): Boolean {
    val query = searchQuery.lowercase()
    val groupName = dictionary.getFieldName(tag)?.lowercase() ?: ""

    // Check if the group header itself matches
    if (tag.toString().contains(query) || groupName.contains(query)) {
        return true
    }

    // Check if any child field in any group instance matches
    for (i in 1..groupCount) {
        try {
            val group = fieldMap.getGroup(i, tag)
            if (fieldMapMatchesSearch(group, dictionary, searchQuery, hideProtocolTags, protocolTags)) {
                return true
            }
        } catch (e: Exception) {
            // Skip invalid groups
        }
    }

    return false
}

/**
 * Recursively checks if any field in a FieldMap matches the search query
 */
private fun fieldMapMatchesSearch(
    fieldMap: FieldMap,
    dictionary: FixDictionary,
    searchQuery: String,
    hideProtocolTags: Boolean,
    protocolTags: Set<Int>,
): Boolean {
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

        // Check if this field matches
        if (fieldMatchesSearch(tag, value, dictionary, searchQuery)) {
            return true
        }

        // Check if this is a nested group and any of its children match
        try {
            val groupCount = fieldMap.getGroupCount(tag)
            if (groupCount > 0) {
                if (groupMatchesSearch(
                        fieldMap = fieldMap,
                        tag = tag,
                        groupCount = groupCount,
                        dictionary = dictionary,
                        searchQuery = searchQuery,
                        hideProtocolTags = hideProtocolTags,
                        protocolTags = protocolTags,
                    )
                ) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Not a group, skip
        }
    }

    return false
}

/**
 * Collects all group keys from a message for expand all functionality
 */
private fun collectAllGroupKeys(message: FixMessage): Set<String> {
    val keys = mutableSetOf<String>()

    collectGroupKeysFromFieldMap(message.quickfixMessage.header, keys)
    collectGroupKeysFromFieldMap(message.quickfixMessage, keys)
    collectGroupKeysFromFieldMap(message.quickfixMessage.trailer, keys)

    return keys
}

/**
 * Recursively collects group keys from a FieldMap
 */
private fun collectGroupKeysFromFieldMap(
    fieldMap: FieldMap,
    keys: MutableSet<String>,
    parentKey: String = "",
) {
    val iterator = fieldMap.iterator()

    while (iterator.hasNext()) {
        @Suppress("UNCHECKED_CAST")
        val field = iterator.next() as Field<*>
        val tag = field.tag

        try {
            val groupCount = fieldMap.getGroupCount(tag)
            if (groupCount > 0) {
                val groupKey =
                    if (parentKey.isEmpty()) {
                        "${tag}_$groupCount"
                    } else {
                        "$parentKey/${tag}_$groupCount"
                    }
                keys.add(groupKey)

                // Recursively collect keys from nested groups
                for (i in 1..groupCount) {
                    try {
                        val group = fieldMap.getGroup(i, tag)
                        collectGroupKeysFromFieldMap(group, keys, groupKey)
                    } catch (e: Exception) {
                        // Skip invalid groups
                    }
                }
            }
        } catch (e: Exception) {
            // Not a group, skip
        }
    }
}

// Color constants
private val panelBackgroundColor = Color(0xFF1E1E1E)
private val headerBackgroundColor = Color(0xFF2B2B2B)
private val metadataBackgroundColor = Color(0xFF252525)
private val fieldRowBackgroundColor = Color(0xFF1E1E1E)
private val groupHeaderBackgroundColor = Color(0xFF252525)
private val groupInstanceBackgroundColor = Color(0xFF202020)

private val headerTextColor = Color(0xFFE0E0E0)
private val iconTintColor = Color(0xFFB0B0B0)
private val searchIconColor = Color(0xFF6A6A6A)
private val timestampColor = Color(0xFFB0B0B0)
private val rawMessageTextColor = Color(0xFFE0E0E0)
private val placeholderTextColor = Color(0xFF6A6A6A)

private val tagNumberColor = Color(0xFF6A6A6A)
private val fieldNameColor = Color(0xFF4EC9B0)
private val fieldValueColor = Color(0xFFE0E0E0)
private val groupHeaderTextColor = Color(0xFFD4A574)
private val groupInstanceTextColor = Color(0xFF9CDCFE)

private val focusedBorderColor = Color(0xFF4EC9B0)
private val unfocusedBorderColor = Color(0xFF3A3A3A)

private val outgoingMessageColor = Color(0xFF569CD6)
private val incomingMessageColor = Color(0xFF4EC9B0)
private val rejectionMessageColor = Color(0xFFE06C75)

// Modifier constants
private val buttonSize = Modifier.size(24.dp)
private val iconSize = Modifier.size(16.dp)
private val searchIconSize = Modifier.size(16.dp)
private val rawActionButtonSize = Modifier.size(20.dp)
private val rawActionIconSize = Modifier.size(14.dp)
private val searchFieldShape = RoundedCornerShape(2.dp)

// Text styles
private val searchTextStyle =
    TextStyle(
        fontSize = 10.sp,
        color = Color(0xFFE0E0E0),
        fontFamily = FontFamily.Monospace,
    )

private val searchPlaceholderStyle =
    TextStyle(
        fontSize = 10.sp,
        color = Color(0xFF6A6A6A),
        fontFamily = FontFamily.Monospace,
    )

// Helper functions
private fun getSearchBorderColor(isFocused: Boolean): Color =
    if (isFocused) focusedBorderColor else unfocusedBorderColor

private fun getDirectionInfo(message: FixMessage): Pair<Color, String> =
    when (message.direction) {
        FixMessage.Direction.INCOMING ->
            if (message.isRejectionOrLogout()) {
                rejectionMessageColor to "INCOMING"
            } else {
                incomingMessageColor to "INCOMING"
            }
        FixMessage.Direction.OUTGOING -> outgoingMessageColor to "OUTGOING"
    }
