package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.SavedFixMessage

/**
 * View mode for browsing saved messages
 */
private enum class BrowserViewMode {
    ALL,        // Flat list sorted by lastUsedAt
    RECENT,     // Top 9 with numbered shortcuts
    BY_TYPE,    // Grouped by FIX message type (tag 35)
    BY_CATEGORY // Grouped by userTags
}

/**
 * A searchable popup browser for saved messages with grouping support.
 * Replaces the simple dropdown menu with a full-featured browser.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SavedMessagesBrowserPopup(
    savedMessages: List<SavedFixMessage>,
    connectionProfiles: List<FixConnectionProfile>,
    dictionary: FixDictionary,
    currentProfileId: String?,
    onSelectMessage: (SavedFixMessage) -> Unit,
    onDeleteMessage: ((messageId: String, profileId: String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(BrowserViewMode.RECENT) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }

    val focusRequester = remember { FocusRequester() }

    // Filter messages by search query (supports IntelliJ-style abbreviation matching)
    val filteredMessages = remember(savedMessages, searchQuery) {
        if (searchQuery.isBlank()) {
            savedMessages
        } else {
            savedMessages.filter { msg ->
                matchesSearch(msg.name, searchQuery)
            }
        }
    }

    // Sort by lastUsedAt (most recent first)
    val sortedMessages = remember(filteredMessages) {
        filteredMessages.sortedByDescending { it.lastUsedAt }
    }

    // Recent messages (top 9 for keyboard shortcuts)
    val recentMessages = remember(sortedMessages) {
        sortedMessages.take(9)
    }

    // Group by message type
    val groupedByType = remember(sortedMessages, dictionary) {
        sortedMessages.groupBy { msg ->
            val msgType = msg.getMessageType()
            if (msgType != null) {
                val description = dictionary.getFieldValueDescription(35, msgType)
                if (description != null && description != msgType) {
                    "$msgType - $description"
                } else {
                    msgType
                }
            } else {
                "Unknown"
            }
        }.toSortedMap()
    }

    // Group by user category
    val groupedByCategory = remember(sortedMessages, connectionProfiles) {
        val result = mutableMapOf<String, MutableList<SavedFixMessage>>()

        sortedMessages.forEach { msg ->
            val tags = msg.getAllUserTags()
            when {
                tags.isEmpty() -> {
                    result.getOrPut("Uncategorized") { mutableListOf() }.add(msg)
                }
                tags.size > 1 -> {
                    result.getOrPut("Shared") { mutableListOf() }.add(msg)
                }
                else -> {
                    val tagId = tags.first()
                    val profileName = connectionProfiles.find { it.id == tagId }?.name ?: tagId
                    result.getOrPut(profileName) { mutableListOf() }.add(msg)
                }
            }
        }

        result.toSortedMap()
    }

    // Request focus when popup opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Keyboard handler for number shortcuts (1-9)
    val keyboardHandler: (KeyEvent) -> Boolean = { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown && viewMode == BrowserViewMode.RECENT) {
            val digit = when (keyEvent.key) {
                Key.One -> 0
                Key.Two -> 1
                Key.Three -> 2
                Key.Four -> 3
                Key.Five -> 4
                Key.Six -> 5
                Key.Seven -> 6
                Key.Eight -> 7
                Key.Nine -> 8
                else -> -1
            }
            if (digit in 0 until recentMessages.size) {
                onSelectMessage(recentMessages[digit])
                true
            } else {
                false
            }
        } else if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
            onDismiss()
            true
        } else {
            false
        }
    }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .width(450.dp)
                .heightIn(max = 500.dp)
                .onKeyEvent(keyboardHandler),
            shape = RoundedCornerShape(8.dp),
            color = AppTheme.Colors.surface,
            shadowElevation = 8.dp,
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
                        text = "Load Message Template",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.Colors.text,
                    )

                    TooltipIconButton(
                        tooltip = "Close",
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = AppTheme.Colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                HorizontalDivider(color = AppTheme.Colors.border)

                // Search Input
                BrowserSearchInput(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    focusRequester = focusRequester,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                )

                // View Mode Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ViewModeTab("All", BrowserViewMode.ALL, viewMode) { viewMode = it }
                    ViewModeTab("Recent", BrowserViewMode.RECENT, viewMode) { viewMode = it }
                    ViewModeTab("By Type", BrowserViewMode.BY_TYPE, viewMode) { viewMode = it }
                    ViewModeTab("By User", BrowserViewMode.BY_CATEGORY, viewMode) { viewMode = it }
                }

                HorizontalDivider(color = AppTheme.Colors.border)

                // Results Count
                Text(
                    text = "${sortedMessages.size} template${if (sortedMessages.size != 1) "s" else ""}",
                    fontSize = 11.sp,
                    color = AppTheme.Colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )

                // Content based on view mode
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (viewMode) {
                        BrowserViewMode.ALL -> {
                            items(sortedMessages, key = { it.id }) { message ->
                                MessageItem(
                                    message = message,
                                    connectionProfiles = connectionProfiles,
                                    currentProfileId = currentProfileId,
                                    onSelect = { onSelectMessage(message) },
                                    onDelete = onDeleteMessage,
                                )
                            }
                        }

                        BrowserViewMode.RECENT -> {
                            items(recentMessages.withIndex().toList(), key = { it.value.id }) { (index, message) ->
                                MessageItem(
                                    message = message,
                                    connectionProfiles = connectionProfiles,
                                    currentProfileId = currentProfileId,
                                    onSelect = { onSelectMessage(message) },
                                    onDelete = onDeleteMessage,
                                    shortcutNumber = index + 1,
                                )
                            }

                            if (recentMessages.isEmpty()) {
                                item {
                                    EmptyState("No saved messages")
                                }
                            }
                        }

                        BrowserViewMode.BY_TYPE -> {
                            groupedByType.forEach { (groupName, messages) ->
                                item(key = "header-type-$groupName") {
                                    GroupHeader(
                                        title = groupName,
                                        count = messages.size,
                                        isExpanded = groupName in expandedGroups,
                                        onToggle = {
                                            expandedGroups = if (groupName in expandedGroups) {
                                                expandedGroups - groupName
                                            } else {
                                                expandedGroups + groupName
                                            }
                                        },
                                    )
                                }

                                if (groupName in expandedGroups) {
                                    items(messages, key = { "type-$groupName-${it.id}" }) { message ->
                                        MessageItem(
                                            message = message,
                                            connectionProfiles = connectionProfiles,
                                            currentProfileId = currentProfileId,
                                            onSelect = { onSelectMessage(message) },
                                            onDelete = onDeleteMessage,
                                            indented = true,
                                        )
                                    }
                                }
                            }

                            if (groupedByType.isEmpty()) {
                                item {
                                    EmptyState("No saved messages")
                                }
                            }
                        }

                        BrowserViewMode.BY_CATEGORY -> {
                            groupedByCategory.forEach { (groupName, messages) ->
                                item(key = "header-cat-$groupName") {
                                    GroupHeader(
                                        title = groupName,
                                        count = messages.size,
                                        isExpanded = groupName in expandedGroups,
                                        onToggle = {
                                            expandedGroups = if (groupName in expandedGroups) {
                                                expandedGroups - groupName
                                            } else {
                                                expandedGroups + groupName
                                            }
                                        },
                                    )
                                }

                                if (groupName in expandedGroups) {
                                    items(messages, key = { "cat-$groupName-${it.id}" }) { message ->
                                        MessageItem(
                                            message = message,
                                            connectionProfiles = connectionProfiles,
                                            currentProfileId = currentProfileId,
                                            onSelect = { onSelectMessage(message) },
                                            onDelete = onDeleteMessage,
                                            indented = true,
                                            showCategory = false,
                                        )
                                    }
                                }
                            }

                            if (groupedByCategory.isEmpty()) {
                                item {
                                    EmptyState("No saved messages")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewModeTab(
    text: String,
    mode: BrowserViewMode,
    currentMode: BrowserViewMode,
    onSelect: (BrowserViewMode) -> Unit,
) {
    val isSelected = mode == currentMode

    Box(
        modifier = Modifier
            .height(28.dp)
            .background(
                color = if (isSelected) AppTheme.Colors.primary else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { onSelect(mode) }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = if (isSelected) AppTheme.Colors.background else AppTheme.Colors.textSecondary,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun BrowserSearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .height(32.dp)
            .background(
                color = if (isFocused) AppTheme.Colors.surface else AppTheme.Colors.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
            )
            .border(
                width = 1.dp,
                color = if (isFocused) AppTheme.Colors.primary else AppTheme.Colors.border,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = "Search",
            tint = AppTheme.Colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                fontSize = 12.sp,
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
                            text = "Search messages...",
                            fontSize = 12.sp,
                            color = AppTheme.Colors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    innerTextField()
                }
            },
        )

        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Clear",
                tint = AppTheme.Colors.textSecondary,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .background(AppTheme.Colors.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = AppTheme.Colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )

        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.Colors.text,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = "($count)",
            fontSize = 11.sp,
            color = AppTheme.Colors.textSecondary,
        )
    }
}

@Composable
private fun MessageItem(
    message: SavedFixMessage,
    connectionProfiles: List<FixConnectionProfile>,
    currentProfileId: String?,
    onSelect: () -> Unit,
    onDelete: ((messageId: String, profileId: String) -> Unit)?,
    shortcutNumber: Int? = null,
    indented: Boolean = false,
    showCategory: Boolean = true,
) {
    val userTags = message.getAllUserTags()
    val profileNames = userTags.mapNotNull { tagId ->
        connectionProfiles.find { it.id == tagId }?.name
    }
    val profileNamesText = when {
        !showCategory -> null
        profileNames.isEmpty() -> null
        profileNames.size == 1 -> profileNames.first()
        profileNames.size <= 2 -> profileNames.joinToString(", ")
        else -> "${profileNames.take(2).joinToString(", ")} +${profileNames.size - 2}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(
                start = if (indented) 28.dp else 12.dp,
                end = 8.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Shortcut number badge
        if (shortcutNumber != null) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(
                        color = AppTheme.Colors.primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = shortcutNumber.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.Colors.primary,
                )
            }
        }

        // Message name and profile
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.name,
                fontSize = 13.sp,
                color = AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
            )
            if (profileNamesText != null) {
                Text(
                    text = profileNamesText,
                    fontSize = 10.sp,
                    color = AppTheme.Colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Delete button
        if (onDelete != null) {
            IconButton(
                onClick = {
                    val deleteProfileId = currentProfileId ?: userTags.firstOrNull() ?: ""
                    onDelete(message.id, deleteProfileId)
                },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = AppTheme.Colors.error,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            color = AppTheme.Colors.textSecondary,
        )
    }
}

/**
 * IntelliJ-style search matching.
 * Supports:
 * - Partial name match: "quote" matches "QuoteRequestTemplate"
 * - CamelCase initials: "QRT" matches "QuoteRequestTemplate"
 * - Snake_case initials: "QRT" matches "Quote_Request_Template"
 * - Partial initials: "QR" matches above
 */
private fun matchesSearch(name: String, query: String): Boolean {
    if (query.isBlank()) return true

    // 1. Partial name match (case-insensitive)
    if (name.lowercase().contains(query.lowercase().trim())) return true

    // 2. Abbreviation match (for uppercase queries like "QRT" or "QR")
    if (query.all { it.isUpperCase() || it.isDigit() }) {
        val initials = extractInitials(name)
        if (initials.startsWith(query, ignoreCase = true)) return true
    }

    return false
}

/**
 * Extracts initials from various naming conventions:
 * - CamelCase: "QuoteRequestTemplate" → "QRT"
 * - snake_case: "Quote_Request_Template" → "QRT"
 * - kebab-case: "quote-request-template" → "QRT"
 * - Mixed: "Quote_Request-template" → "QRT"
 */
private fun extractInitials(name: String): String {
    return name
        .split(Regex("[-_]|(?=[A-Z])"))
        .filter { it.isNotEmpty() }
        .map { it.first().uppercaseChar() }
        .joinToString("")
}
