package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.util.NotifyingLogger
import java.awt.Cursor
import java.awt.Toolkit

data class FixField(
    val tag: String = "",
    val value: String = "",
    val excluded: Boolean = false,
) {
    companion object {
        fun List<FixField>.toRawMessage(): String = this.joinToString("|") { "${it.tag}=${it.value}" } + "|"
    }
}

@Composable
private fun SlimTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle = TextStyle(fontSize = 10.sp, color = Color(0xFFE0E0E0)),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .background(Color(0xFF2B2B2B), RoundedCornerShape(2.dp))
                .border(
                    width = 1.dp,
                    color = if (isFocused) Color(0xFF4EC9B0) else Color(0xFF3A3A3A),
                    shape = RoundedCornerShape(2.dp),
                ).padding(horizontal = 4.dp, vertical = 4.dp),
        textStyle = textStyle,
        singleLine = singleLine,
        cursorBrush = SolidColor(Color(0xFF4EC9B0)),
        interactionSource = interactionSource,
    )
}

@Composable
fun MessageEditorPanel(
    sessions: List<FixMessageSession>,
    selectedSessionIndex: Int,
    dictionary: FixDictionary,
    fields: List<FixField>,
    selectedFieldIndex: Int,
    selectedFieldIndices: List<Int> = listOf(selectedFieldIndex),
    onFieldUpdate: (Int, FixField) -> Unit,
    onFieldAdd: () -> Unit,
    onFieldDelete: (Int) -> Unit,
    onFieldMoveUp: (Int) -> Unit,
    onFieldMoveDown: (Int) -> Unit,
    onFieldSelect: (Int, Boolean, Boolean) -> Unit,
    onClearFields: () -> Unit,
    onClose: () -> Unit,
    onSend: (sessionIndex: Int, fields: List<FixField>) -> Unit,
    onValidate: (fields: List<FixField>) -> List<String>,
    validationErrors: List<String>,
    onClearValidationErrors: () -> Unit,
    onSetValidationErrors: (List<String>) -> Unit = {},
    onDescriptionVisibilityChanged: ((Boolean) -> Unit)? = null,
    onSaveMessage: ((name: String, fields: List<FixField>, profileId: String) -> Unit)? = null,
    savedMessages: List<com.knapsack.fixtool.model.SavedFixMessage> = emptyList(),
    onLoadMessage: ((com.knapsack.fixtool.model.SavedFixMessage) -> Unit)? = null,
    onDeleteMessage: ((messageId: String, profileId: String) -> Unit)? = null,
    connectionProfiles: List<com.knapsack.fixtool.model.FixConnectionProfile> = emptyList(),
    currentProfileId: String? = null,
    currentLoadedMessageName: String? = null,
    onSessionChange: ((Int) -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Create logger with notification support
    val logger =
        remember(onError) {
            NotifyingLogger(object {}.javaClass.enclosingClass, onError)
        }

    // QuickFIX/J managed tags - these are always auto-managed by QuickFIX/J
    // 8=BeginString, 9=BodyLength, 10=CheckSum, 34=MsgSeqNum, 49=SenderCompID, 50=SenderSubID,
    // 52=SendingTime, 56=TargetCompID, 57=TargetSubID, 142=SenderLocationID, 143=TargetLocationID
    val managedTags = remember { setOf("8", "9", "10", "34", "49", "50", "52", "56", "57", "142", "143") }

    var currentSessionIndex by remember { mutableStateOf(selectedSessionIndex) }
    var previewPanelRatio by remember { mutableStateOf(0.2f) }
    var previewText by remember { mutableStateOf("") }
    var isUpdatingFromFields by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(true) } // Toggle for Description column - enabled by default
    var showIndentation by remember { mutableStateOf(false) } // Toggle for Group indentation (off by default)
    val density = LocalDensity.current

    // Get the current session's connection state
    val currentSession = sessions.getOrNull(currentSessionIndex)
    val connectionState by currentSession?.connectionState?.collectAsState() ?: remember {
        mutableStateOf(
            FixConnectionState.DISCONNECTED,
        )
    }
    val canSend = connectionState == FixConnectionState.LOGGED_ON

    // Check if data dictionary is configured (has fields loaded)
    val hasDataDictionary = dictionary.isLoaded()

    // Track validation state - true when validation passed, false when needs validation
    var validationPassed by remember { mutableStateOf(false) }

    // Notify parent about initial description visibility on component load
    LaunchedEffect(Unit) {
        onDescriptionVisibilityChanged?.invoke(showDescription)
    }

    // Sync fields to preview text - create a key that changes when field contents change
    val fieldsKey = fields.map { "${it.tag}:${it.value}" }.joinToString("|")
    LaunchedEffect(fieldsKey) {
        isUpdatingFromFields = true
        previewText = buildPreviewMessage(fields, managedTags)
        isUpdatingFromFields = false
        // Reset validation state when fields change
        validationPassed = false
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E)),
    ) {
        // Top border
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // Header
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B2B2B))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Message Editor",
                    color = Color(0xFFE0E0E0),
                    fontSize = 11.sp,
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // Session selector, toolbar buttons, and Send button
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            val availableWidth = maxWidth
            // Session dropdown: ~108dp + 8dp spacing = 116dp
            // Each button: 28dp + 4dp spacing = 32dp
            // Chevron button: 28dp
            // Calculate how many buttons can fit
            val sessionAndChevronWidth = 116.dp + 28.dp + 8.dp // session + spacing + chevron + spacing
            val availableForButtons = availableWidth - sessionAndChevronWidth
            val buttonWidth = 32.dp
            val buttonsCount =
                11 // Add, Delete, MoveUp, MoveDown, Clear, Validate, Send, Save, Load, Indent, Description
            val visibleButtonsCount = (availableForButtons / buttonWidth).toInt().coerceIn(0, buttonsCount)
            val needsOverflow = visibleButtonsCount < buttonsCount

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Session dropdown - first in toolbar
                SlimDropdown(
                    value = if (currentSessionIndex >= 0) sessions.getOrNull(currentSessionIndex) else null,
                    options = sessions,
                    onValueChange = { session ->
                        val newIndex = if (session != null) sessions.indexOf(session) else -1
                        currentSessionIndex = newIndex
                        onSessionChange?.invoke(newIndex)
                    },
                    displayText = { it.title },
                    placeholder = "Session",
                    allowUnselect = true,
                    modifier = Modifier.widthIn(max = 108.dp),
                )

                // Progressive overflow: show as many buttons as fit, overflow the rest
                var showOverflowPopup by remember { mutableStateOf(false) }

                Spacer(modifier = Modifier.width(8.dp))

                // Button 0: Send
                if (visibleButtonsCount > 0) {
                    val sendTooltip =
                        if (canSend) {
                            "Send Message (QuickFIX/J manages header/trailer fields)"
                        } else {
                            "Cannot send - Session not logged on (${connectionState.getDisplayText()})"
                        }
                    TooltipIconButton(
                        tooltip = sendTooltip,
                        onClick = {
                            onClearValidationErrors()
                            try {
                                val fieldsToSend =
                                    fields.filter {
                                        !it.excluded &&
                                            it.tag.isNotBlank() &&
                                            it.value.isNotBlank() &&
                                            it.tag !in managedTags
                                    }
                                if (fieldsToSend.isEmpty()) {
                                    onSetValidationErrors(
                                        listOf("No fields to send. Add at least one field with tag and value."),
                                    )
                                } else {
                                    onSend(currentSessionIndex, fieldsToSend)
                                }
                            } catch (e: Exception) {
                                val sendError = "Send Error: ${e.message ?: e.toString()}"
                                onSetValidationErrors(
                                    listOf(
                                        sendError,
                                    ),
                                )
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(18.dp),
                            tint = if (canSend) Color(0xFF4EC9B0) else Color(0xFF4A4A4A),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 1: Validate
                if (visibleButtonsCount > 1) {
                    TooltipIconButton(
                        tooltip =
                            if (hasDataDictionary) {
                                if (validationPassed) "Validation Passed" else "Validate Message against Data Dictionary"
                            } else {
                                "Validation disabled - No Data Dictionary configured"
                            },
                        onClick = {
                            onClearValidationErrors()
                            val fieldsToValidate =
                                fields.filter { !it.excluded && it.tag.isNotBlank() }
                            if (fieldsToValidate.isNotEmpty()) {
                                val errors = onValidate(fieldsToValidate)
                                validationPassed = errors.isEmpty()
                            }
                        },
                        enabled = hasDataDictionary,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Validate",
                            modifier = Modifier.size(18.dp),
                            tint =
                                when {
                                    hasDataDictionary.not() -> Color(0xFF4A4A4A)
                                    validationPassed -> Color(0xFF98C379)
                                    else ->
                                        Color(
                                            0xFFCE9178,
                                        )
                                },
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 2: Load
                if (visibleButtonsCount > 2 && onLoadMessage != null && savedMessages.isNotEmpty()) {
                    var showLoadMenu by remember { mutableStateOf(false) }
                    Box {
                        TooltipIconButton(
                            tooltip = "Load Message Template",
                            onClick = { showLoadMenu = true },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Load",
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFFB0B0B0),
                            )
                        }

                        DropdownMenu(
                            expanded = showLoadMenu,
                            onDismissRequest = { showLoadMenu = false },
                            modifier = Modifier.background(Color(0xFF2B2B2B)).widthIn(min = 200.dp),
                        ) {
                            savedMessages.sortedByDescending { it.lastUsedAt }.forEach { savedMsg ->
                                val profileName = connectionProfiles.find { it.id == savedMsg.profileId }?.name
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    savedMsg.name,
                                                    color = Color(0xFFE0E0E0),
                                                    fontSize = 14.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                )
                                                if (profileName != null) {
                                                    Text(
                                                        profileName,
                                                        color = Color(0xFF888888),
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                    )
                                                }
                                            }
                                            IconButton(
                                                onClick = {
                                                    onDeleteMessage?.invoke(savedMsg.id, savedMsg.profileId)
                                                    showLoadMenu = false
                                                },
                                                modifier = Modifier.size(24.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Template",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = Color(0xFFFF5555),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        onLoadMessage(savedMsg)
                                        showLoadMenu = false
                                    },
                                    colors =
                                        MenuDefaults.itemColors(
                                            textColor = Color(0xFFE0E0E0),
                                        ),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 3: Save
                if (visibleButtonsCount > 3 && onSaveMessage != null) {
                    var showSaveDialog by remember { mutableStateOf(false) }
                    TooltipIconButton(
                        tooltip = "Save Message Template",
                        onClick = { showSaveDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFB0B0B0),
                        )
                    }

                    if (showSaveDialog) {
                        var messageName by remember { mutableStateOf(currentLoadedMessageName ?: "") }
                        var selectedProfileId by remember {
                            mutableStateOf(
                                currentProfileId ?: connectionProfiles.firstOrNull()?.id ?: "",
                            )
                        }
                        val isDuplicate =
                            savedMessages.any {
                                it.name == messageName &&
                                    messageName.isNotBlank() &&
                                    it.name != currentLoadedMessageName &&
                                    it.profileId == selectedProfileId
                            }
                        val focusRequester = remember { FocusRequester() }
                        val nameFieldInteractionSource = remember { MutableInteractionSource() }
                        val isNameFieldFocused by nameFieldInteractionSource.collectIsFocusedAsState()
                        var showProfileDropdown by remember { mutableStateOf(false) }

                        androidx.compose.ui.window.Dialog(onDismissRequest = { showSaveDialog = false }) {
                            Column(
                                modifier =
                                    Modifier
                                        .background(Color(0xFF2B2B2B), RoundedCornerShape(8.dp))
                                        .padding(16.dp)
                                        .width(400.dp),
                            ) {
                                Text(
                                    "Save Message Template",
                                    color = Color(0xFFE0E0E0),
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )

                                // Profile selector (FIRST)
                                if (connectionProfiles.isNotEmpty()) {
                                    Text(
                                        "Connection Profile",
                                        color = Color(0xFFB0B0B0),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                    Box {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF2B2B2B), RoundedCornerShape(2.dp))
                                                    .border(
                                                        width = 1.dp,
                                                        color =
                                                            if (showProfileDropdown) {
                                                                Color(0xFF4EC9B0)
                                                            } else {
                                                                Color(
                                                                    0xFF555555,
                                                                )
                                                            },
                                                        shape = RoundedCornerShape(2.dp),
                                                    ).pointerInput(Unit) {
                                                        detectTapGestures { showProfileDropdown = true }
                                                    }.padding(horizontal = 4.dp, vertical = 4.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    connectionProfiles.find { it.id == selectedProfileId }?.name
                                                        ?: "Select Profile",
                                                    fontSize = 14.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Color(0xFFE0E0E0),
                                                )
                                                Icon(
                                                    imageVector = if (showProfileDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint =
                                                        if (showProfileDropdown) {
                                                            Color(0xFF4EC9B0)
                                                        } else {
                                                            Color(
                                                                0xFF888888,
                                                            )
                                                        },
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = showProfileDropdown,
                                            onDismissRequest = { showProfileDropdown = false },
                                            modifier =
                                                Modifier
                                                    .background(Color(0xFF2B2B2B))
                                                    .widthIn(min = 280.dp),
                                        ) {
                                            connectionProfiles.forEach { profile ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            profile.name,
                                                            color = Color(0xFFE0E0E0),
                                                            fontSize = 13.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                        )
                                                    },
                                                    onClick = {
                                                        selectedProfileId = profile.id
                                                        showProfileDropdown = false
                                                    },
                                                    modifier =
                                                        Modifier.background(
                                                            if (profile.id ==
                                                                selectedProfileId
                                                            ) {
                                                                Color(0xFF3A3A3A)
                                                            } else {
                                                                Color.Transparent
                                                            },
                                                        ),
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                // Template Name field (SECOND)
                                Text(
                                    "Template Name",
                                    color = Color(0xFFB0B0B0),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                BasicTextField(
                                    value = messageName,
                                    onValueChange = { messageName = it },
                                    singleLine = true,
                                    textStyle =
                                        TextStyle(
                                            color = Color(0xFFE0E0E0),
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    cursorBrush = SolidColor(Color(0xFF4EC9B0)),
                                    interactionSource = nameFieldInteractionSource,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF2B2B2B), RoundedCornerShape(2.dp))
                                            .border(
                                                width = 1.dp,
                                                color =
                                                    if (isDuplicate) {
                                                        Color(0xFFFF5555)
                                                    } else if (isNameFieldFocused) {
                                                        Color(
                                                            0xFF4EC9B0,
                                                        )
                                                    } else {
                                                        Color(0xFF555555)
                                                    },
                                                shape = RoundedCornerShape(2.dp),
                                            ).padding(horizontal = 4.dp, vertical = 8.dp)
                                            .focusRequester(focusRequester),
                                )

                                if (isDuplicate) {
                                    Text(
                                        "A template with this name already exists for this profile",
                                        color = Color(0xFFFF5555),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(16.dp))
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    SlimButton(
                                        text = "Cancel",
                                        onClick = { showSaveDialog = false },
                                        containerColor = Color(0xFF3A3A3A),
                                        contentColor = AppTheme.Colors.textSecondary,
                                        modifier = Modifier.width(90.dp),
                                    )
                                    SlimButton(
                                        text = "Save",
                                        onClick = {
                                            if (messageName.isNotBlank() &&
                                                !isDuplicate &&
                                                selectedProfileId.isNotBlank()
                                            ) {
                                                onSaveMessage(
                                                    messageName,
                                                    fields.filter { !it.excluded && it.tag.isNotBlank() },
                                                    selectedProfileId,
                                                )
                                                showSaveDialog = false
                                            }
                                        },
                                        enabled =
                                            messageName.isNotBlank() && !isDuplicate && selectedProfileId.isNotBlank(),
                                        containerColor = AppTheme.Colors.primary,
                                        contentColor = AppTheme.Colors.background,
                                        modifier = Modifier.width(90.dp),
                                    )
                                }
                            }
                        }

                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Button 4: Add
                if (visibleButtonsCount > 4) {
                    TooltipIconButton(tooltip = "Add Field", onClick = onFieldAdd, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFB0B0B0),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 5: Delete
                if (visibleButtonsCount > 5) {
                    TooltipIconButton(
                        tooltip = "Delete Field",
                        onClick = { onFieldDelete(selectedFieldIndex) },
                        enabled = fields.size > 1,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Delete",
                            modifier = Modifier.size(18.dp),
                            tint = if (fields.size > 1) Color(0xFFB0B0B0) else Color(0xFF4A4A4A),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 6: Move Up
                if (visibleButtonsCount > 6) {
                    TooltipIconButton(
                        tooltip = "Move Up",
                        onClick = { onFieldMoveUp(selectedFieldIndex) },
                        enabled = selectedFieldIndex > 0,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move Up",
                            modifier = Modifier.size(18.dp),
                            tint = if (selectedFieldIndex > 0) Color(0xFFB0B0B0) else Color(0xFF4A4A4A),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 7: Move Down
                if (visibleButtonsCount > 7) {
                    TooltipIconButton(
                        tooltip = "Move Down",
                        onClick = { onFieldMoveDown(selectedFieldIndex) },
                        enabled = selectedFieldIndex < fields.size - 1,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move Down",
                            modifier = Modifier.size(18.dp),
                            tint = if (selectedFieldIndex < fields.size - 1) Color(0xFFB0B0B0) else Color(0xFF4A4A4A),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 8: Clear
                if (visibleButtonsCount > 8) {
                    TooltipIconButton(
                        tooltip = "Clear All Fields",
                        onClick = {
                            previewText = ""
                            onClearFields()
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFB0B0B0),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 9: Toggle Indentation
                if (visibleButtonsCount > 9) {
                    TooltipIconButton(
                        tooltip = if (showIndentation) "Hide Group Indentation" else "Show Group Indentation",
                        onClick = { showIndentation = !showIndentation },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatIndentIncrease,
                            contentDescription = "Toggle Indentation",
                            modifier = Modifier.size(18.dp),
                            tint = if (showIndentation) Color(0xFF4EC9B0) else Color(0xFFB0B0B0),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 10: Toggle Description
                if (visibleButtonsCount > 10) {
                    TooltipIconButton(
                        tooltip = if (showDescription) "Hide Description column" else "Show Description column",
                        onClick = {
                            showDescription = !showDescription
                            onDescriptionVisibilityChanged?.invoke(showDescription)
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (showDescription) Icons.Default.ViewModule else Icons.Default.ViewList,
                            contentDescription = "Toggle Description",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFB0B0B0),
                        )
                    }
                }

                // Overflow button if needed
                if (needsOverflow) {
                    Spacer(modifier = Modifier.weight(1f))

                    Box {
                        TooltipIconButton(
                            tooltip = "More Options",
                            onClick = { showOverflowPopup = !showOverflowPopup },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "More Options",
                                tint = Color(0xFFB0B0B0),
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        // Popup with only hidden buttons
                        if (showOverflowPopup) {
                            Popup(
                                alignment = Alignment.TopEnd,
                                onDismissRequest = { showOverflowPopup = false },
                                properties = PopupProperties(focusable = true),
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .background(Color(0xFF2B2B2B), RoundedCornerShape(4.dp))
                                            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Button 0: Send
                                    if (visibleButtonsCount <= 0) {
                                        TooltipIconButton(
                                            tooltip = if (canSend) "Send Message" else "Cannot send",
                                            onClick = {
                                                onClearValidationErrors()
                                                try {
                                                    val fieldsToSend =
                                                        fields.filter {
                                                            !it.excluded &&
                                                                it.tag.isNotBlank() &&
                                                                it.value.isNotBlank() &&
                                                                it.tag !in managedTags
                                                        }
                                                    if (fieldsToSend.isEmpty()) {
                                                        onSetValidationErrors(listOf("No fields to send."))
                                                    } else {
                                                        onSend(currentSessionIndex, fieldsToSend)
                                                    }
                                                } catch (e: Exception) {
                                                    onSetValidationErrors(listOf("Send Error: ${e.message}"))
                                                }
                                            },
                                            enabled = canSend,
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Send,
                                                contentDescription = "Send",
                                                modifier = Modifier.size(18.dp),
                                                tint = if (canSend) Color(0xFF4EC9B0) else Color(0xFF4A4A4A),
                                            )
                                        }
                                    }
                                    // Button 1: Validate
                                    if (visibleButtonsCount <= 1) {
                                        TooltipIconButton(
                                            tooltip =
                                                if (hasDataDictionary) {
                                                    if (validationPassed) "Validation Passed" else "Validate"
                                                } else {
                                                    "Validation disabled"
                                                },
                                            onClick = {
                                                onClearValidationErrors()
                                                val fieldsToValidate =
                                                    fields.filter { !it.excluded && it.tag.isNotBlank() }
                                                if (fieldsToValidate.isNotEmpty()) {
                                                    validationPassed = onValidate(fieldsToValidate).isEmpty()
                                                }
                                            },
                                            enabled = hasDataDictionary,
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Validate",
                                                modifier = Modifier.size(18.dp),
                                                tint =
                                                    when {
                                                        hasDataDictionary.not() -> Color(0xFF4A4A4A)
                                                        validationPassed ->
                                                            Color(
                                                                0xFF98C379,
                                                            )
                                                        ; else -> Color(0xFFCE9178)
                                                    },
                                            )
                                        }
                                    }
                                    // Button 2: Load
                                    if (visibleButtonsCount <= 2 &&
                                        onLoadMessage != null &&
                                        savedMessages.isNotEmpty()
                                    ) {
                                        TooltipIconButton(
                                            tooltip = "Load Message Template",
                                            onClick = { /* handled by dropdown */ },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FolderOpen,
                                                contentDescription = "Load",
                                                modifier = Modifier.size(18.dp),
                                                tint = Color(0xFFB0B0B0),
                                            )
                                        }
                                    }
                                    // Button 3: Save
                                    if (visibleButtonsCount <= 3 && onSaveMessage != null) {
                                        var showSaveDialog by remember { mutableStateOf(false) }
                                        TooltipIconButton(
                                            tooltip = "Save Message Template",
                                            onClick = { showSaveDialog = true },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Save,
                                                contentDescription = "Save",
                                                modifier = Modifier.size(18.dp),
                                                tint = Color(0xFFB0B0B0),
                                            )
                                        }
                                    }
                                    // Button 4: Add
                                    if (visibleButtonsCount <= 4) {
                                        TooltipIconButton(
                                            tooltip = "Add Field",
                                            onClick = { onFieldAdd() },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add",
                                                modifier = Modifier.size(18.dp),
                                                tint = Color(0xFFB0B0B0),
                                            )
                                        }
                                    }
                                    // Button 5: Delete
                                    if (visibleButtonsCount <= 5) {
                                        TooltipIconButton(
                                            tooltip = "Delete Field",
                                            onClick = { onFieldDelete(selectedFieldIndex) },
                                            enabled = fields.size > 1,
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Remove,
                                                contentDescription = "Delete",
                                                modifier = Modifier.size(18.dp),
                                                tint = if (fields.size > 1) Color(0xFFB0B0B0) else Color(0xFF4A4A4A),
                                            )
                                        }
                                    }
                                    // Button 6: Move Up
                                    if (visibleButtonsCount <= 6) {
                                        TooltipIconButton(
                                            tooltip = "Move Up",
                                            onClick = { onFieldMoveUp(selectedFieldIndex) },
                                            enabled = selectedFieldIndex > 0,
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowUpward,
                                                contentDescription = "Move Up",
                                                modifier = Modifier.size(18.dp),
                                                tint =
                                                    if (selectedFieldIndex > 0) {
                                                        Color(0xFFB0B0B0)
                                                    } else {
                                                        Color(
                                                            0xFF4A4A4A,
                                                        )
                                                    },
                                            )
                                        }
                                    }
                                    // Button 7: Move Down
                                    if (visibleButtonsCount <= 7) {
                                        TooltipIconButton(
                                            tooltip = "Move Down",
                                            onClick = { onFieldMoveDown(selectedFieldIndex) },
                                            enabled = selectedFieldIndex < fields.size - 1,
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowDownward,
                                                contentDescription = "Move Down",
                                                modifier = Modifier.size(18.dp),
                                                tint =
                                                    if (selectedFieldIndex <
                                                        fields.size - 1
                                                    ) {
                                                        Color(0xFFB0B0B0)
                                                    } else {
                                                        Color(
                                                            0xFF4A4A4A,
                                                        )
                                                    },
                                            )
                                        }
                                    }
                                    // Button 8: Clear
                                    if (visibleButtonsCount <= 8) {
                                        TooltipIconButton(
                                            tooltip = "Clear All Fields",
                                            onClick = {
                                                previewText = ""
                                                onClearFields()
                                            },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Clear",
                                                modifier = Modifier.size(18.dp),
                                                tint = Color(0xFFB0B0B0),
                                            )
                                        }
                                    }
                                    // Button 9: Toggle Indentation
                                    if (visibleButtonsCount <= 9) {
                                        TooltipIconButton(
                                            tooltip = if (showIndentation) "Hide Group Indentation" else "Show Group Indentation",
                                            onClick = { showIndentation = !showIndentation },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FormatIndentIncrease,
                                                contentDescription = "Toggle Indentation",
                                                modifier = Modifier.size(18.dp),
                                                tint = if (showIndentation) Color(0xFF4EC9B0) else Color(0xFFB0B0B0),
                                            )
                                        }
                                    }
                                    // Button 10: Toggle Description
                                    if (visibleButtonsCount <= 10) {
                                        TooltipIconButton(
                                            tooltip = if (showDescription) "Hide Description column" else "Show Description column",
                                            onClick = {
                                                showDescription =
                                                    !showDescription
                                                onDescriptionVisibilityChanged?.invoke(
                                                    showDescription,
                                                )
                                            },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (showDescription) Icons.Default.ViewModule else Icons.Default.ViewList,
                                                contentDescription = "Toggle Description",
                                                modifier = Modifier.size(18.dp),
                                                tint = Color(0xFFB0B0B0),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // Validation error display section
        if (validationErrors.isNotEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3A1F1F))
                        .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Validation Errors",
                            tint = Color(0xFFE06C75),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Validation Errors (${validationErrors.size})",
                            color = Color(0xFFE06C75),
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                    TooltipIconButton(
                        tooltip = "Dismiss Errors",
                        onClick = onClearValidationErrors,
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color(0xFFB0B0B0),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                // Display each error
                validationErrors.forEach { error ->
                    Text(
                        text = "• $error",
                        color = Color(0xFFE06C75),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 22.dp),
                    )
                }
            }
            HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        }

        // Field editor header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B2B2B))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Spacer for eye icon column
            Spacer(modifier = Modifier.width(24.dp))

            Text(
                text = "Tag",
                color = Color(0xFFB0B0B0),
                fontSize = 10.sp,
                modifier = Modifier.width(48.dp),
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = "Field Name",
                color = Color(0xFFB0B0B0),
                fontSize = 10.sp,
                modifier = Modifier.width(120.dp),
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = "Value",
                color = Color(0xFFB0B0B0),
                fontSize = 10.sp,
                modifier = Modifier.width(180.dp),
            )

            if (showDescription) {
                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "Description",
                    color = Color(0xFFB0B0B0),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Resizable field list and preview
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val maxHeightPx = with(density) { maxHeight.toPx() }

            Column(modifier = Modifier.fillMaxSize()) {
                // Field list
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { (maxHeightPx * (1f - previewPanelRatio)).toDp() }),
                ) {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxSize(),
                    ) {
                        // Calculate indent levels and instance numbers for all fields (only if indentation is enabled)
                        val (indentLevels, instanceNumbers) =
                            if (showIndentation) {
                                calculateIndentLevels(fields, dictionary)
                            } else {
                                Pair(
                                    List(fields.size) { 0 },
                                    List(fields.size) { null },
                                ) // All zeros and nulls when indentation is disabled
                            }

                        itemsIndexed(fields) { index, field ->
                            // Mark managed fields as disabled/dimmed
                            val isManaged = field.tag in managedTags && field.tag.isNotBlank()

                            FieldEditorRow(
                                field = field,
                                dictionary = dictionary,
                                isSelected = index in selectedFieldIndices,
                                isPrimarySelection = selectedFieldIndex == index,
                                isManaged = isManaged,
                                onFieldChange = { newField ->
                                    onFieldUpdate(index, newField)
                                },
                                onClick = { isCtrl, isShift ->
                                    onFieldSelect(index, isCtrl, isShift)
                                },
                                showDescription = showDescription,
                                indentLevel = indentLevels.getOrElse(index) { 0 },
                                instanceNumber = instanceNumbers.getOrElse(index) { null },
                            )
                        }
                    }
                }

                // Resizable divider
                Box(
                    modifier =
                        Modifier
                            .height(1.dp)
                            .fillMaxWidth()
                            .background(Color(0xFF3A3A3A))
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                            .pointerInput(maxHeightPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val deltaRatio = -dragAmount.y / maxHeightPx
                                    previewPanelRatio = (previewPanelRatio + deltaRatio).coerceIn(0.1f, 0.5f)
                                }
                            },
                )

                // Preview
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { (maxHeightPx * previewPanelRatio).toDp() }),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF252525), RoundedCornerShape(4.dp))
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
                                color = Color(0xFFB0B0B0),
                                fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Copy button
                                TooltipIconButton(
                                    tooltip = "Copy to Clipboard",
                                    onClick = {
                                        try {
                                            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                            val stringSelection = java.awt.datatransfer.StringSelection(previewText)
                                            clipboard.setContents(stringSelection, null)
                                        } catch (e: Exception) {
                                            logger.error(
                                                "Failed to copy to clipboard: ${e.message}",
                                                e,
                                                notifyUser = true,
                                            )
                                        }
                                    },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = Color(0xFFB0B0B0),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }

                                // Paste button
                                TooltipIconButton(
                                    tooltip = "Paste from Clipboard",
                                    onClick = {
                                        try {
                                            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                            val contents = clipboard.getContents(null)
                                            if (contents != null &&
                                                contents.isDataFlavorSupported(
                                                    java.awt.datatransfer.DataFlavor.stringFlavor,
                                                )
                                            ) {
                                                val clipboardText =
                                                    contents.getTransferData(
                                                        java.awt.datatransfer.DataFlavor.stringFlavor,
                                                    ) as String
                                                previewText = clipboardText
                                                // Parse and update fields - store ALL fields regardless of auto-manage state
                                                parseRawMessageToFields(clipboardText)?.let { parsedFields ->
                                                    if (!fieldsAreEqual(fields, parsedFields)) {
                                                        // Save current selection
                                                        val savedIndex = selectedFieldIndex
                                                        val savedIndices = selectedFieldIndices.toList()

                                                        onClearFields()
                                                        // Update first field (which clearFields created)
                                                        if (parsedFields.isNotEmpty()) {
                                                            onFieldUpdate(0, parsedFields[0])
                                                        }
                                                        // Add remaining fields
                                                        for (i in 1 until parsedFields.size) {
                                                            onFieldAdd()
                                                            onFieldUpdate(i, parsedFields[i])
                                                        }

                                                        // Restore selection if valid, otherwise select last field
                                                        val newIndex =
                                                            if (savedIndex <
                                                                parsedFields.size
                                                            ) {
                                                                savedIndex
                                                            } else {
                                                                parsedFields.size - 1
                                                            }
                                                        onFieldSelect(newIndex, false, false)
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            logger.error(
                                                "Failed to paste from clipboard: ${e.message}",
                                                e,
                                                notifyUser = true,
                                            )
                                        }
                                    },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste",
                                        tint = Color(0xFFB0B0B0),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(
                            value = previewText,
                            onValueChange = { newText ->
                                if (!isUpdatingFromFields) {
                                    previewText = newText
                                    // Parse the raw message and update fields - store ALL fields
                                    parseRawMessageToFields(newText)?.let { parsedFields ->
                                        if (!fieldsAreEqual(fields, parsedFields)) {
                                            updateFieldsFromParsed(
                                                currentFields = fields,
                                                parsedFields = parsedFields,
                                                onFieldUpdate = onFieldUpdate,
                                                onFieldAdd = onFieldAdd,
                                                onFieldDelete = onFieldDelete,
                                            )
                                        }
                                    }
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = Color(0xFFE0E0E0),
                                    unfocusedTextColor = Color(0xFFE0E0E0),
                                    cursorColor = Color(0xFF4EC9B0),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                            textStyle =
                                LocalTextStyle.current.copy(
                                    fontSize = 10.sp,
                                    color = Color(0xFFE0E0E0),
                                    fontFamily = FontFamily.Monospace,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Calculate indent levels and instance transitions for all fields based on repeating group structure.
 * Returns a pair of lists: (indent levels, show instance header)
 * Show instance header is the instance number (1-based) if this field is the FIRST field of a new group instance.
 *
 * This follows the same logic as MessageDetailPanel:
 * - Group tags (NoXxx) define the start of a repeating group
 * - The value of the group tag indicates how many instances follow
 * - All fields in each instance get the same indent level
 * - The first field of each instance shows an instance header like [1], [2], etc.
 * - Nested groups (groups within groups) increase the indent level further
 * - When a tag doesn't fit the expected group pattern, we close the group
 */
private fun calculateIndentLevels(fields: List<FixField>, dictionary: FixDictionary): Pair<List<Int>, List<Int?>> {
    val indentLevels = mutableListOf<Int>()
    val instanceNumbers = mutableListOf<Int?>()
    var currentIndent = 0

    // Stack of active groups: (groupTag, groupCount, fieldsPerInstance, currentInstance, groupFieldTags)
    data class GroupState(
        val groupTag: Int,
        val totalInstances: Int,
        var fieldsPerInstance: Int, // Number of fields in each group instance (learned from first instance)
        var currentInstance: Int, // Which instance we're currently in (1-based)
        var fieldsInCurrentInstance: Int, // How many fields we've seen in current instance
        val groupFieldTags: MutableSet<Int>, // Tags that belong to this group (learned from first instance)
    )

    val groupStack = mutableListOf<GroupState>()

    var i = 0
    while (i < fields.size) {
        val field = fields[i]
        val tagInt = field.tag.toIntOrNull()

        if (tagInt == null) {
            indentLevels.add(currentIndent)
            instanceNumbers.add(null)
            i++
            continue
        }

        // Check if this is a group tag (NoXxx fields)
        val isGroupTag = dictionary.isGroupTag(tagInt)

        if (isGroupTag) {
            val groupCount = field.value.toIntOrNull() ?: 0

            // Add the group tag itself at current indent level (no instance number for group tags)
            indentLevels.add(currentIndent)
            instanceNumbers.add(null)

            if (groupCount > 0) {
                // Start a new group
                val newGroup =
                    GroupState(
                        groupTag = tagInt,
                        totalInstances = groupCount,
                        fieldsPerInstance = -1, // Will be learned from first instance
                        currentInstance = 1,
                        fieldsInCurrentInstance = 0,
                        groupFieldTags = mutableSetOf(),
                    )
                groupStack.add(newGroup)
                currentIndent++
            }

            i++
            continue
        }

        // Regular field - check if it belongs to an active group
        if (groupStack.isNotEmpty()) {
            val currentGroup = groupStack.last()

            // Check if this is a structural/header field that should close all groups
            val isStructuralField = tagInt in setOf(8, 9, 35, 49, 56, 34, 52, 10)

            if (isStructuralField) {
                // Close all groups and add this field at indent 0
                groupStack.clear()
                currentIndent = 0
                indentLevels.add(0)
                instanceNumbers.add(null)
                i++
                continue
            }

            // Check if this field belongs to the current group
            val belongsToGroup =
                if (currentGroup.fieldsPerInstance == -1) {
                    // Still learning the first instance - accept any non-group, non-structural field
                    true
                } else {
                    // We know the group structure - check if this tag is in the expected set
                    tagInt in currentGroup.groupFieldTags
                }

            if (belongsToGroup) {
                // This field is part of the current group instance
                indentLevels.add(currentIndent)

                // Only show instance number on the FIRST field of each instance
                val isFirstFieldOfInstance = currentGroup.fieldsInCurrentInstance == 0
                instanceNumbers.add(if (isFirstFieldOfInstance) currentGroup.currentInstance else null)

                // Track this tag for the group
                if (currentGroup.fieldsPerInstance == -1) {
                    currentGroup.groupFieldTags.add(tagInt)
                }

                currentGroup.fieldsInCurrentInstance++

                // Check if we've completed the first instance (learning phase)
                if (currentGroup.fieldsPerInstance == -1) {
                    // Look ahead to see if the next field starts a new instance or exits the group
                    if (i + 1 < fields.size) {
                        val nextField = fields[i + 1]
                        val nextTagInt = nextField.tag.toIntOrNull()

                        if (nextTagInt != null) {
                            // If next tag is in our group tags or is a new group tag, we've learned the pattern
                            val nextIsInGroup = nextTagInt in currentGroup.groupFieldTags
                            val nextIsGroupTag = dictionary.isGroupTag(nextTagInt)
                            val nextIsStructural = nextTagInt in setOf(8, 9, 35, 49, 56, 34, 52, 10)

                            if (nextIsInGroup || nextIsGroupTag || nextIsStructural) {
                                // We've seen all fields in the first instance
                                currentGroup.fieldsPerInstance = currentGroup.fieldsInCurrentInstance
                                currentGroup.currentInstance = 2
                                currentGroup.fieldsInCurrentInstance = 0
                            }
                        } else if (i + 1 == fields.size) {
                            // Last field in the list - finalize first instance
                            currentGroup.fieldsPerInstance = currentGroup.fieldsInCurrentInstance
                        }
                    } else {
                        // Last field in the list
                        currentGroup.fieldsPerInstance = currentGroup.fieldsInCurrentInstance
                    }
                } else {
                    // Check if we've completed an instance
                    if (currentGroup.fieldsInCurrentInstance >= currentGroup.fieldsPerInstance) {
                        currentGroup.currentInstance++
                        currentGroup.fieldsInCurrentInstance = 0

                        // Check if we've completed all instances
                        if (currentGroup.currentInstance > currentGroup.totalInstances) {
                            // Group is complete - pop it from the stack
                            groupStack.removeLast()
                            currentIndent--
                        }
                    }
                }

                i++
                continue
            } else {
                // This field doesn't belong to the current group
                // Close the current group and try again
                groupStack.removeLast()
                currentIndent--
                // Don't increment i - re-process this field with the new state
                continue
            }
        } else {
            // No active group - add at indent level 0
            indentLevels.add(0)
            instanceNumbers.add(null)
            i++
        }
    }

    return Pair(indentLevels, instanceNumbers)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditorRow(
    field: FixField,
    dictionary: FixDictionary,
    isSelected: Boolean,
    isPrimarySelection: Boolean,
    isManaged: Boolean,
    onFieldChange: (FixField) -> Unit,
    onClick: (isCtrl: Boolean, isShift: Boolean) -> Unit,
    showDescription: Boolean,
    indentLevel: Int = 0,
    instanceNumber: Int? = null,
) {
    val tagInt = field.tag.toIntOrNull()
    val fieldName =
        if (tagInt != null) {
            dictionary.getFieldName(tagInt) ?: ""
        } else {
            ""
        }

    // Check if this is a group tag
    val isGroupTag = tagInt?.let { dictionary.isGroupTag(it) } ?: false

    // Get value description if available
    val valueDescription =
        if (tagInt != null && field.value.isNotBlank()) {
            dictionary.getFieldValueDescription(tagInt, field.value)
        } else {
            null
        }

    // Only show description if it's different from the value
    val hasValueDescription = valueDescription != null && valueDescription != field.value

    // Check if field has enum values
    val hasEnumValues = tagInt?.let { dictionary.hasFieldValues(it) } ?: false
    val enumValues =
        if (hasEnumValues) {
            tagInt?.let {
                val values = dictionary.getFieldEnumValues(it)
                if (values.isNotEmpty()) {
                    println("DEBUG: Tag $it has ${values.size} enum values: ${values.take(3)}")
                }
                values
            } ?: emptyList()
        } else {
            emptyList()
        }

    // Determine background color based on selection state
    val backgroundColor =
        when {
            isPrimarySelection -> Color(0xFF2D5A8C) // Primary selection - darker blue
            isSelected -> Color(0xFF1E4A6B) // Part of multi-selection - lighter blue
            else -> Color(0xFF1E1E1E) // Not selected
        }

    Column {
        // Show instance number as a header if present (first field of each group instance)
        if (instanceNumber != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF202020))
                        .padding(start = (8 + indentLevel * 8).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    text = "[$instanceNumber]",
                    color = Color(0xFF9CDCFE),
                    fontSize = 9.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                    // Get modifiers directly from the pointer event
                                    val modifiers = event.keyboardModifiers
                                    val isCtrl = modifiers.isCtrlPressed || modifiers.isMetaPressed
                                    val isShift = modifiers.isShiftPressed

                                    onClick(isCtrl, isShift)
                                }
                            }
                        }
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Add indent spacing before everything (including eye icon)
            if (indentLevel > 0) {
                Spacer(modifier = Modifier.width((indentLevel * 8).dp))
            }

            // Eye icon to toggle exclusion (or show managed status)
            TooltipIconButton(
                tooltip =
                    when {
                        isManaged -> "QuickFIX/J managed field (not sent)"
                        field.excluded -> "Include (field is excluded)"
                        else -> "Exclude (field is included)"
                    },
                onClick = {
                    if (!isManaged) {
                        onFieldChange(field.copy(excluded = !field.excluded))
                    }
                },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector =
                        if (field.excluded ||
                            isManaged
                        ) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                    contentDescription = if (field.excluded) "Excluded" else "Included",
                    tint =
                        when {
                            isManaged -> Color(0xFF4A4A4A)
                            field.excluded -> Color(0xFF6A6A6A)
                            isGroupTag -> Color(0xFFFFAA00) // Orange for group tags
                            else -> Color(0xFF4EC9B0) // Green for regular fields
                        },
                    modifier = Modifier.size(14.dp),
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            if (isSelected) {
                // Editable view - show SlimTextFields
                // Tag input
                SlimTextField(
                    value = field.tag,
                    onValueChange = { onFieldChange(field.copy(tag = it)) },
                    modifier = Modifier.width(42.dp).height(24.dp),
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Field name (read-only)
                Text(
                    text = fieldName,
                    color =
                        when {
                            isManaged -> Color(0xFF6A6A6A)
                            isGroupTag -> Color(0xFFFFAA00) // Orange for group tags
                            else -> Color(0xFF4EC9B0) // Green for regular fields
                        },
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp),
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Value input
                SlimTextField(
                    value = field.value,
                    onValueChange = { onFieldChange(field.copy(value = it)) },
                    modifier = Modifier.width(180.dp).height(24.dp),
                )

                if (showDescription) {
                    Spacer(modifier = Modifier.width(4.dp))

                    // Show dropdown for enum fields, otherwise show description text
                    if (hasEnumValues && enumValues.isNotEmpty()) {
                        // Enum dropdown - shows value description but allows changing the value
                        SlimDropdown(
                            value = field.value,
                            options = enumValues.map { it.first },
                            onValueChange = { newValue ->
                                if (newValue != null) {
                                    onFieldChange(field.copy(value = newValue))
                                }
                            },
                            displayText = { value ->
                                // Find the description for this value
                                val desc = enumValues.find { it.first == value }?.second
                                if (desc != null && desc != value) {
                                    "$value ($desc)"
                                } else {
                                    value
                                }
                            },
                            placeholder = "Select value...",
                            allowUnselect = false,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        // Value description (read-only text)
                        Text(
                            text = if (hasValueDescription) valueDescription!! else "",
                            color = if (isManaged) Color(0xFF6A6A6A) else Color(0xFF9A9A9A),
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                // Read-only view - show as text (like message detail row) with text selection enabled
                SelectionContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = field.tag,
                            color = if (isManaged || field.excluded) Color(0xFF6A6A6A) else Color(0xFFE0E0E0),
                            fontSize = 10.sp,
                            modifier = Modifier.width(48.dp),
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        // Field name
                        Text(
                            text = fieldName,
                            color =
                                when {
                                    isManaged || field.excluded -> Color(0xFF6A6A6A)
                                    isGroupTag -> Color(0xFFFFAA00) // Orange for group tags
                                    else -> Color(0xFF4EC9B0) // Green for regular fields
                                },
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.width(120.dp),
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        Text(
                            text = field.value,
                            color = if (isManaged || field.excluded) Color(0xFF6A6A6A) else Color(0xFFE0E0E0),
                            fontSize = 10.sp,
                            modifier = Modifier.width(180.dp),
                        )

                        if (showDescription) {
                            Spacer(modifier = Modifier.width(4.dp))

                            Text(
                                text = if (hasValueDescription) valueDescription!! else "",
                                color = if (isManaged || field.excluded) Color(0xFF6A6A6A) else Color(0xFF9A9A9A),
                                fontSize = 10.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    } // Close Column
}

private fun buildPreviewMessage(
    fields: List<FixField>,
    managedTags: Set<String>,
): String {
    // Filter out excluded fields and QuickFIX/J managed tags
    val fieldsToShow =
        fields.filter {
            !it.excluded && (it.tag.isNotBlank() || it.value.isNotBlank()) && it.tag !in managedTags
        }

    if (fieldsToShow.isEmpty()) return ""

    return fieldsToShow.joinToString("|") { "${it.tag}=${it.value}" } + "|"
}

private fun parseRawMessageToFields(rawMessage: String): List<FixField>? {
    if (rawMessage.isBlank()) return listOf(FixField())

    // Managed tags that QuickFIX/J handles automatically
    // 8=BeginString, 9=BodyLength, 10=CheckSum, 34=MsgSeqNum, 49=SenderCompID, 50=SenderSubID,
    // 52=SendingTime, 56=TargetCompID, 57=TargetSubID, 142=SenderLocationID, 143=TargetLocationID
    val managedTags = setOf("8", "9", "10", "34", "49", "50", "52", "56", "57", "142", "143")

    return try {
        val fields =
            rawMessage
                .trim()
                .trimEnd('|')
                .split('|')
                .filter { it.isNotBlank() }
                .mapNotNull { pair ->
                    val parts = pair.split('=', limit = 2)
                    if (parts.size == 2) {
                        val tag = parts[0].trim()
                        val value = parts[1].trim()
                        // Skip QuickFIX/J managed fields when parsing
                        if (tag !in managedTags) {
                            FixField(tag = tag, value = value)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }

        if (fields.isEmpty()) listOf(FixField()) else fields
    } catch (e: Exception) {
        null // Return null if parsing fails, don't update fields
    }
}

private fun fieldsAreEqual(fields1: List<FixField>, fields2: List<FixField>): Boolean {
    if (fields1.size != fields2.size) return false
    return fields1.zip(fields2).all { (f1, f2) -> f1.tag == f2.tag && f1.value == f2.value }
}

private fun updateFieldsFromParsed(
    currentFields: List<FixField>,
    parsedFields: List<FixField>,
    onFieldUpdate: (Int, FixField) -> Unit,
    onFieldAdd: () -> Unit,
    onFieldDelete: (Int) -> Unit,
) {
    // Update existing fields
    val minSize = minOf(currentFields.size, parsedFields.size)
    for (i in 0 until minSize) {
        if (currentFields[i] != parsedFields[i]) {
            onFieldUpdate(i, parsedFields[i])
        }
    }

    // Add new fields if parsed has more
    if (parsedFields.size > currentFields.size) {
        for (i in currentFields.size until parsedFields.size) {
            onFieldAdd()
            onFieldUpdate(i, parsedFields[i])
        }
    }

    // Remove extra fields if current has more
    if (currentFields.size > parsedFields.size) {
        for (i in currentFields.size - 1 downTo parsedFields.size) {
            onFieldDelete(i)
        }
    }
}

@Composable
private fun SlimButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    containerColor: Color = AppTheme.Colors.primary,
    contentColor: Color = AppTheme.Colors.background,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(32.dp)
                .background(
                    color = if (enabled) containerColor else Color(0xFF3A3A3A),
                    shape = RoundedCornerShape(4.dp),
                ).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else AppTheme.Colors.textTertiary,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
    }
}
