package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.service.FixMessageHelper.normalizeFixMessage
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.compare.GroupOverlay
import com.knapsack.fixtool.util.NotifyingLogger
import java.awt.Cursor
import java.awt.Toolkit

/** What the editor needs to know to show, and finish, a rule's reply step. */
data class ReplyStepEditing(
    val profileName: String,
    val ruleIndex: Int,
    val stepIndex: Int,
    val onApply: () -> Unit,
    val onCancel: () -> Unit,
)

data class FixField(
    val tag: String = "",
    val value: String = "",
    val excluded: Boolean = false,
) {
    companion object {
        fun List<FixField>.toRawMessage(): String = this.joinToString("|") { "${it.tag}=${it.value}" } + "|"

        /**
         * Evaluates template expressions in field values and returns new fields with resolved values.
         * For example, ${UUID.randomUUID()} will be replaced with an actual UUID.
         * Can also reference previous messages: ${incoming["D"].valueOfTag(11)}
         * Shorthand syntax is also supported: ${D.11} or ${D.ClOrdID}
         * Variables assigned in one field can be reused in subsequent fields.
         *
         * PERFORMANCE OPTIMIZED: Uses batch evaluation to extract message data once
         * and reuse helper code across all expressions.
         *
         * @param incomingMessages Map of latest incoming messages by type
         * @param outgoingMessages Map of latest outgoing messages by type
         * @param dictionary Optional FIX data dictionary for tag name resolution in shorthand syntax
         * @param seedVariables Pre-defined variables available to expressions (e.g. per-session
         *                      values like sessionIndex/sessionSenderCompID during bulk send)
         */
        fun List<FixField>.resolveTemplates(
            incomingMessages: Map<String, com.knapsack.fixtool.model.FixMessage> = emptyMap(),
            outgoingMessages: Map<String, com.knapsack.fixtool.model.FixMessage> = emptyMap(),
            dictionary: FixDictionaryAdapter? = null,
            seedVariables: Map<String, String> = emptyMap(),
        ): List<FixField> {
            // Collect all fields that need template evaluation
            val fieldsWithExpressions =
                this.mapIndexedNotNull { index, field ->
                    if (FixMessageTemplate.hasTemplateExpressions(field.value)) {
                        index to field.value
                    } else {
                        null
                    }
                }

            // If no expressions, return as-is (fast path)
            if (fieldsWithExpressions.isEmpty()) {
                return this
            }

            // Batch evaluate all expressions at once (extracts message data only once)
            val variables = seedVariables.toMutableMap()
            val resolvedValues =
                FixMessageTemplate.evaluateBatch(
                    fieldsWithExpressions,
                    incomingMessages,
                    outgoingMessages,
                    variables,
                    dictionary,
                )

            // Apply resolved values back to fields
            return this.mapIndexed { index, field ->
                val resolvedValue = resolvedValues[index]
                if (resolvedValue != null) {
                    field.copy(value = resolvedValue)
                } else {
                    field
                }
            }
        }

        /**
         * Converts SCREAMING_SNAKE_CASE to camelCase.
         * Example: LIST_ID -> listId, CL_ORD_ID -> clOrdId
         */
        private fun toVariableName(snakeCase: String): String {
            val words = snakeCase.lowercase().split('_')
            if (words.isEmpty()) return snakeCase.lowercase()

            return words.first() +
                words.drop(1).joinToString("") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }
        }

        /**
         * Transforms Cucumber test template special values to our template expression format.
         */
        private fun transformCucumberValue(value: String): String {
            val trimmed = value.trim()

            // Handle CREATE_AND_CAPTURE_AS: VARNAME -> ${varName = UUID.randomUUID()}
            if (trimmed.startsWith("CREATE_AND_CAPTURE_AS:")) {
                val varName = trimmed.substringAfter("CREATE_AND_CAPTURE_AS:").trim()
                val camelCase = toVariableName(varName)
                return "\${$camelCase = UUID.randomUUID()}"
            }

            // Handle CAPTURED_VALUE: VARNAME -> ${varName}
            if (trimmed.startsWith("CAPTURED_VALUE:")) {
                val varName = trimmed.substringAfter("CAPTURED_VALUE:").trim()
                val camelCase = toVariableName(varName)
                return "\${$camelCase}"
            }

            // Handle template variables <varName> -> empty string
            if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
                return ""
            }

            // Handle MATCHES_REGEX: pattern -> empty string
            if (trimmed.startsWith("MATCHES_REGEX:")) {
                return ""
            }

            // Strip trailing comments in square brackets: "2 [Disclosed style]" -> "2"
            val withoutComment = trimmed.replace(Regex("""\s*\[.*?\]\s*$"""), "")

            return withoutComment
        }

        /**
         * Parses Cucumber test template format into FixField list.
         *
         * Supports full cucumber step format:
         * ```
         * When 'user@example.com' sends the 'R[QUOTE_REQUEST]' FIX message
         * """
         * [ListID]    66 = CREATE_AND_CAPTURE_AS: LIST_ID
         * [BidType]   394 = 2 [Disclosed style]
         * ######### COMMENT #########
         * @@includeIf:<condition>
         * ... content ...
         * @@/includeIf
         * """
         * ```
         *
         * The message type is extracted from patterns like 'R[QUOTE_REQUEST]' or 'D[NEW_ORDER_SINGLE]'
         * and added as tag 35 (MsgType) at the beginning of the fields list.
         * Triple-quote delimiters are optional and gracefully ignored.
         */
        fun parseCucumberTemplateFormat(text: String): List<FixField> {
            val fields = mutableListOf<FixField>()

            // Try to extract message type from cucumber step header
            // Pattern matches: 'X[MESSAGE_NAME]' where X is the FIX message type code
            val msgTypePattern = Regex("""'([A-Za-z0-9]+)\[[A-Z_]+\]'""")
            val msgTypeMatch = msgTypePattern.find(text)
            if (msgTypeMatch != null) {
                val msgType = msgTypeMatch.groupValues[1]
                fields.add(FixField(tag = "35", value = msgType))
            }

            val lines = text.lines()

            for (line in lines) {
                val trimmed = line.trim()

                // Skip empty lines
                if (trimmed.isEmpty()) continue

                // Skip triple-quote delimiters (""" at start or end, or partial """")
                if (trimmed.startsWith("\"\"\"") || trimmed == "\"\"") continue

                // Skip cucumber step lines (When/Then/And/Given ... FIX message)
                if (trimmed.matches(Regex("""^(When|Then|And|Given)\s+.*FIX\s+message.*"""))) continue

                // Skip conditional directives (but not the content inside them)
                if (trimmed.startsWith("@@includeIf:") || trimmed.startsWith("@@/includeIf")) {
                    continue
                }

                // Skip comment lines
                if (trimmed.startsWith("#")) continue

                // Parse field line: [FieldName]   tag = value [optional comment]
                val fieldPattern = Regex("""^\[.*?\]\s*(\d+)\s*=\s*(.+)$""")
                val match = fieldPattern.find(trimmed)

                if (match != null) {
                    val tag = match.groupValues[1]
                    val rawValue = match.groupValues[2]
                    val transformedValue = transformCucumberValue(rawValue)

                    fields.add(FixField(tag = tag, value = transformedValue))
                }
            }

            return if (fields.isEmpty()) listOf(FixField()) else fields
        }
    }
}

@Composable
private fun SlimTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle = TextStyle(fontSize = 10.sp, color = AppTheme.Colors.text),
    backgroundColor: Color = AppTheme.Colors.surface,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .background(backgroundColor, inputShape)
                .border(
                    width = 1.dp,
                    color = if (isFocused) AppTheme.Colors.primary else AppTheme.Colors.border,
                    shape = inputShape,
                ).padding(horizontal = 4.dp, vertical = 4.dp),
        textStyle = textStyle,
        singleLine = singleLine,
        cursorBrush = SolidColor(AppTheme.Colors.primary),
        interactionSource = interactionSource,
    )
}

@Composable
fun MessageEditorPanel(
    sessions: List<FixMessageSession>,
    selectedSession: FixMessageSession?,
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
    onSend: (fields: List<FixField>) -> Unit,
    onSendToAll: ((fields: List<FixField>) -> Unit)? = null,
    /** The third send mode: issue this message N times, or at a rate, across a profile's lanes. Opens the load dialog. */
    onLoad: ((fields: List<FixField>) -> Unit)? = null,
    onValidate: (fields: List<FixField>) -> List<String>,
    validationErrors: List<String>,
    onClearValidationErrors: () -> Unit,
    onSetValidationErrors: (List<String>) -> Unit = {},
    onDescriptionVisibilityChanged: ((Boolean) -> Unit)? = null,
    onSaveMessage: ((name: String, fields: List<FixField>, profileId: String, userTags: Set<String>) -> Unit)? = null,
    onSaveMessageAs: ((name: String, fields: List<FixField>, profileId: String, userTags: Set<String>) -> Unit)? = null,
    savedMessages: List<com.knapsack.fixtool.model.SavedFixMessage> = emptyList(),
    onLoadMessage: ((com.knapsack.fixtool.model.SavedFixMessage) -> Unit)? = null,
    onDeleteMessage: ((messageId: String, profileId: String) -> Unit)? = null,
    onToggleFavorite: ((messageId: String) -> Unit)? = null,
    connectionProfiles: List<com.knapsack.fixtool.model.FixConnectionProfile> = emptyList(),
    currentProfileId: String? = null,
    editorState: com.knapsack.fixtool.model.MessageEditorState = com.knapsack.fixtool.model.MessageEditorState.New,
    onSessionChange: ((FixMessageSession?) -> Unit)? = null,
    onGetProfileConnectionState: ((String) -> com.knapsack.fixtool.model.FixConnectionState)? = null,
    selectedEditorProfile: com.knapsack.fixtool.model.FixConnectionProfile? = null,
    onEditorProfileChange: ((com.knapsack.fixtool.model.FixConnectionProfile?) -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    /**
     * Set while the editor holds one step of an acceptor rule's reply rather than a message.
     *
     * The grid, the enum pickers and the raw preview are the same in both modes — that is the whole
     * reason a step is edited here rather than in a second grid grown inside the connection panel.
     * What changes is the terminal action: a template has no session to go to, so Send is replaced by
     * Apply, which writes the step back to the staged rule.
     */
    replyStep: ReplyStepEditing? = null,
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

    // Use selectedSession directly - no local state needed
    var previewPanelRatio by remember { mutableStateOf(0.2f) }
    var previewText by remember { mutableStateOf("") }
    var isUpdatingFromFields by remember { mutableStateOf(false) }

    // Toggle for Description column - enabled by default, but forced off when no dictionary
    var showDescription by remember { mutableStateOf(true) }
    var showIndentation by remember { mutableStateOf(false) } // Toggle for Group indentation (off by default)
    var searchQuery by remember { mutableStateOf("") } // Search query for highlighting fields
    val density = LocalDensity.current

    // Get the current session's connection state
    val connectionState by selectedSession?.connectionState?.collectAsState() ?: remember {
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

    // Force showDescription to false when dictionary is not loaded
    LaunchedEffect(hasDataDictionary) {
        if (!hasDataDictionary) {
            showDescription = false
        }
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
                .background(AppTheme.Colors.background),
    ) {
        // Top border
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // Header
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Message Editor",
                    color = AppTheme.Colors.text,
                    fontSize = 11.sp,
                )

                IconButton(
                    onClick = onClose,
                    modifier = iconSize24,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )
                }
            }
        }

        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // What the editor is editing, when it is not a message. Above the session controls rather than
        // in place of them: which session is selected still means something for the rest of the panel,
        // and hiding it would make returning to a message feel like a different editor.
        replyStep?.let { editing ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(AppTheme.Colors.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Reply · rule ${editing.ruleIndex + 1}, step ${editing.stepIndex + 1} — ${editing.profileName}",
                    color = AppTheme.Colors.text,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                SlimButton(text = "Apply", onClick = editing.onApply, color = AppTheme.Colors.primary)
                SlimButton(text = "Cancel", onClick = editing.onCancel)
            }
            HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        }

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
                // Profile dropdown - shows ALL profiles with connection status indicators
                // Sorted by connection state (CONNECTED/LOGGED_ON, CONNECTING, DISCONNECTED/ERROR) then alphabetically

                // Helper to get connection state priority for sorting
                fun getConnectionPriority(state: com.knapsack.fixtool.model.FixConnectionState): Int =
                    when (state) {
                        com.knapsack.fixtool.model.FixConnectionState.CONNECTED,
                        com.knapsack.fixtool.model.FixConnectionState.LOGGED_ON,
                        -> 0 // Highest priority
                        com.knapsack.fixtool.model.FixConnectionState.CONNECTING -> 1 // Medium priority
                        else -> 2 // Lowest priority (DISCONNECTED, ERROR)
                    }

                // Helper to get status indicator text
                fun getStatusIndicator(state: com.knapsack.fixtool.model.FixConnectionState): String =
                    when (state) {
                        com.knapsack.fixtool.model.FixConnectionState.CONNECTED,
                        com.knapsack.fixtool.model.FixConnectionState.LOGGED_ON,
                        -> "\u25CF" // ●
                        com.knapsack.fixtool.model.FixConnectionState.CONNECTING -> "\u25CF" // ●
                        else -> "\u25CB" // ○
                    }

                // Helper to get status color
                fun getStatusColor(state: com.knapsack.fixtool.model.FixConnectionState): androidx.compose.ui.graphics.Color =
                    when (state) {
                        com.knapsack.fixtool.model.FixConnectionState.CONNECTED,
                        com.knapsack.fixtool.model.FixConnectionState.LOGGED_ON,
                        ->
                            androidx.compose.ui.graphics
                                .Color(0xFF4CAF50) // Green
                        com.knapsack.fixtool.model.FixConnectionState.CONNECTING ->
                            androidx.compose.ui.graphics
                                .Color(0xFFFFA726) // Orange
                        else ->
                            androidx.compose.ui.graphics
                                .Color(0xFF9E9E9E) // Gray
                    }

                // Sort profiles by connection state then alphabetically
                // Read all session connection states to trigger recomposition when they change
                val sessionStates =
                    sessions.map { session ->
                        session.connectionState.collectAsState().value
                    }
                val loggedOnSessionCount = sessionStates.count { it == FixConnectionState.LOGGED_ON }

                val sortedProfiles =
                    remember(connectionProfiles, sessionStates) {
                        connectionProfiles.sortedWith(
                            compareBy<com.knapsack.fixtool.model.FixConnectionProfile> {
                                val state =
                                    onGetProfileConnectionState?.invoke(it.id)
                                        ?: com.knapsack.fixtool.model.FixConnectionState.DISCONNECTED
                                getConnectionPriority(state)
                            }.thenBy { it.name.lowercase() },
                        )
                    }

                SlimDropdownWithColor(
                    value = selectedEditorProfile,
                    options = sortedProfiles,
                    onValueChange = { profile: com.knapsack.fixtool.model.FixConnectionProfile? ->
                        logger.info("MessageEditorPanel profile dropdown changed to: ${profile?.name} (ID: ${profile?.id})")
                        // Notify that editor profile selection has changed
                        onEditorProfileChange?.invoke(profile)
                    },
                    displayText = { profile: com.knapsack.fixtool.model.FixConnectionProfile ->
                        val state =
                            onGetProfileConnectionState?.invoke(profile.id)
                                ?: com.knapsack.fixtool.model.FixConnectionState.DISCONNECTED
                        "${getStatusIndicator(state)} ${profile.name}"
                    },
                    textColor = { profile: com.knapsack.fixtool.model.FixConnectionProfile ->
                        val state =
                            onGetProfileConnectionState?.invoke(profile.id)
                                ?: com.knapsack.fixtool.model.FixConnectionState.DISCONNECTED
                        getStatusColor(state)
                    },
                    placeholder = "Profile",
                    allowUnselect = true,
                    modifier = Modifier.widthIn(max = 140.dp),
                )

                // Progressive overflow: show as many buttons as fit, overflow the rest
                var showOverflowPopup by remember { mutableStateOf(false) }

                Spacer(modifier = Modifier.width(8.dp))

                // Button 0: Send. Withheld while a rule's step is loaded — a template has no session
                // to go to, and the action that finishes it is Apply, in the bar above.
                if (visibleButtonsCount > 0 && replyStep == null) {
                    // A profile that has never been connected owns no session, so name it rather
                    // than reporting the state of a session that does not exist.
                    val unconnectedProfileName =
                        if (selectedSession == null) selectedEditorProfile?.name else null
                    val sendTooltip =
                        when {
                            canSend -> "Send Message (QuickFIX/J manages header/trailer fields)"
                            unconnectedProfileName != null -> "Cannot send - $unconnectedProfileName is not connected"
                            else -> "Cannot send - Session not logged on (${connectionState.getDisplayText()})"
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
                                } else if (selectedSession == null) {
                                    onSetValidationErrors(
                                        listOf(
                                            unconnectedProfileName?.let { "$it is not connected. Connect it to send message." }
                                                ?: "No session selected. Select a session to send message.",
                                        ),
                                    )
                                } else {
                                    // Validate required fields before sending
                                    val validationErrors = mutableListOf<String>()

                                    // Check for tag 35 (message type) - required for all FIX messages
                                    val hasMessageType = fieldsToSend.any { it.tag == "35" }
                                    if (!hasMessageType) {
                                        validationErrors.add("Missing required field: Tag 35 (MsgType/Message Type)")
                                    }

                                    if (validationErrors.isNotEmpty()) {
                                        onSetValidationErrors(validationErrors)
                                    } else {
                                        logger.info(
                                            "MessageEditorPanel: Calling onSend with selectedSession: ${selectedSession.title} (ID: ${selectedSession.id})",
                                        )
                                        onSend(fieldsToSend)
                                    }
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
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            modifier = iconSize18,
                            tint = if (canSend) AppTheme.Colors.primary else disabledIconColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 0b: Send to all logged-on sessions (rendered with Send - they are a pair).
                // Templates are re-resolved per session, so e.g. ${UUID.randomUUID()} in MDReqID
                // yields a unique value per session.
                if (visibleButtonsCount > 0 && onSendToAll != null && replyStep == null) {
                    val canSendToAll = loggedOnSessionCount > 0
                    val sendToAllTooltip =
                        if (canSendToAll) {
                            "Send to all logged-on sessions ($loggedOnSessionCount)"
                        } else {
                            "Cannot send - no session is logged on"
                        }
                    TooltipIconButton(
                        tooltip = sendToAllTooltip,
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
                                when {
                                    fieldsToSend.isEmpty() ->
                                        onSetValidationErrors(
                                            listOf("No fields to send. Add at least one field with tag and value."),
                                        )
                                    fieldsToSend.none { it.tag == "35" } ->
                                        onSetValidationErrors(
                                            listOf("Missing required field: Tag 35 (MsgType/Message Type)"),
                                        )
                                    else -> {
                                        logger.info(
                                            "MessageEditorPanel: Calling onSendToAll for $loggedOnSessionCount logged-on sessions",
                                        )
                                        onSendToAll(fieldsToSend)
                                    }
                                }
                            } catch (e: Exception) {
                                onSetValidationErrors(listOf("Send Error: ${e.message ?: e.toString()}"))
                            }
                        },
                        enabled = canSendToAll,
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = "Send to All Sessions",
                            modifier = iconSize18,
                            tint = if (canSendToAll) AppTheme.Colors.primary else disabledIconColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 0c: Load run. Send is one message on one session, Send to All is one message on
                // every session once, and this is thousands with the accounting afterwards. The editor's
                // fields are the template; the dialog decides everything else.
                if (visibleButtonsCount > 0 && onLoad != null && replyStep == null) {
                    val loadable =
                        fields.filter { !it.excluded && it.tag.isNotBlank() && it.value.isNotBlank() && it.tag !in managedTags }
                    val asTemplate = LoadTemplate("message editor", loadable.mapNotNull { f -> f.tag.toIntOrNull()?.let { it to f.value } })
                    val canLoad = asTemplate.msgType != null && asTemplate.inferMatch() != null
                    TooltipIconButton(
                        tooltip =
                            if (canLoad) {
                                "Load run: issue this message across a profile's sessions and account for every reply"
                            } else {
                                "Cannot load: the message needs a MsgType (35) and a correlation tag such as ClOrdID (11)"
                            },
                        onClick = {
                            onClearValidationErrors()
                            onLoad(loadable)
                        },
                        enabled = canLoad,
                        modifier = iconSize28.testTag("editor-load"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Load run",
                            modifier = iconSize18,
                            tint = if (canLoad) AppTheme.Colors.primary else disabledIconColor,
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
                                "Requires FIX data dictionary"
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
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Validate",
                            modifier = iconSize18,
                            tint =
                                when {
                                    hasDataDictionary.not() -> disabledIconColor
                                    validationPassed -> AppTheme.Colors.success
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
                    var showLoadPopup by remember { mutableStateOf(false) }
                    Box {
                        TooltipIconButton(
                            tooltip = "Load Message Template",
                            onClick = { showLoadPopup = true },
                            modifier = iconSize28,
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Load",
                                modifier = iconSize18,
                                tint = AppTheme.Colors.textSecondary,
                            )
                        }

                        if (showLoadPopup) {
                            SavedMessagesBrowserPopup(
                                savedMessages = savedMessages,
                                connectionProfiles = connectionProfiles,
                                dictionary = dictionary,
                                currentProfileId = currentProfileId,
                                selectedEditorProfile = selectedEditorProfile,
                                onSelectMessage = { savedMessage ->
                                    onLoadMessage(savedMessage)
                                    showLoadPopup = false
                                },
                                onDeleteMessage = onDeleteMessage,
                                onToggleFavorite = onToggleFavorite,
                                onDismiss = { showLoadPopup = false },
                            )
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
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save",
                            modifier = iconSize18,
                            tint = AppTheme.Colors.textSecondary,
                        )
                    }

                    if (showSaveDialog) {
                        var messageName by remember { mutableStateOf(editorState.messageNameOrNull() ?: "") }
                        // Multi-select user tags: initialize with existing tags or current profile
                        var selectedUserTags by remember {
                            mutableStateOf(
                                editorState.allUserTags().ifEmpty {
                                    currentProfileId?.let { setOf(it) } ?: emptySet()
                                },
                            )
                        }
                        // Duplicate check for "Save as New" - checks ALL templates (don't exclude current)
                        val isDuplicateForSaveAsNew =
                            savedMessages.any {
                                it.name.trim().equals(messageName.trim(), ignoreCase = true) &&
                                    messageName.isNotBlank()
                            }

                        // Duplicate check for "Update" - checks OTHER templates (exclude current by ID)
                        val isDuplicateForUpdate =
                            savedMessages.any {
                                it.name.trim().equals(messageName.trim(), ignoreCase = true) &&
                                    messageName.isNotBlank() &&
                                    it.id != editorState.messageIdOrNull()
                            }

                        val originalName = editorState.messageNameOrNull() ?: ""
                        val nameWasModified = messageName.trim() != originalName.trim() && originalName.isNotEmpty()
                        val focusRequester = remember { FocusRequester() }
                        val nameFieldInteractionSource = remember { MutableInteractionSource() }
                        val isNameFieldFocused by nameFieldInteractionSource.collectIsFocusedAsState()

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
                                    color = AppTheme.Colors.text,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )

                                // Template Name field (FIRST)
                                Text(
                                    "Template Name",
                                    color = AppTheme.Colors.textSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                BasicTextField(
                                    value = messageName,
                                    onValueChange = { messageName = it },
                                    singleLine = true,
                                    textStyle =
                                        TextStyle(
                                            color = AppTheme.Colors.text,
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    cursorBrush = SolidColor(AppTheme.Colors.primary),
                                    interactionSource = nameFieldInteractionSource,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF2B2B2B), RoundedCornerShape(2.dp))
                                            .border(
                                                width = 1.dp,
                                                color =
                                                    if (isDuplicateForUpdate) {
                                                        deleteColor
                                                    } else if (isNameFieldFocused) {
                                                        Color(
                                                            0xFF4EC9B0,
                                                        )
                                                    } else {
                                                        AppTheme.Colors.borderDark
                                                    },
                                                shape = RoundedCornerShape(2.dp),
                                            ).padding(horizontal = 4.dp, vertical = 8.dp)
                                            .focusRequester(focusRequester),
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // User Tags selector (SECOND) - multi-select checkboxes
                                if (connectionProfiles.isNotEmpty()) {
                                    Text(
                                        "Share with Users (select one or more)",
                                        color = AppTheme.Colors.textSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )

                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 200.dp)
                                                .verticalScroll(rememberScrollState())
                                                .background(Color(0xFF252525), RoundedCornerShape(4.dp))
                                                .padding(8.dp),
                                    ) {
                                        connectionProfiles.forEach { profile ->
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            selectedUserTags =
                                                                if (profile.id in selectedUserTags) {
                                                                    selectedUserTags - profile.id
                                                                } else {
                                                                    selectedUserTags + profile.id
                                                                }
                                                        }.padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Checkbox(
                                                    checked = profile.id in selectedUserTags,
                                                    onCheckedChange = { checked ->
                                                        selectedUserTags =
                                                            if (checked) {
                                                                selectedUserTags + profile.id
                                                            } else {
                                                                selectedUserTags - profile.id
                                                            }
                                                    },
                                                    colors =
                                                        CheckboxDefaults.colors(
                                                            checkedColor = AppTheme.Colors.primary,
                                                            uncheckedColor = AppTheme.Colors.border,
                                                            checkmarkColor = AppTheme.Colors.background,
                                                        ),
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    profile.name,
                                                    color = AppTheme.Colors.text,
                                                    fontSize = 13.sp,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                // Validation message
                                if (isDuplicateForUpdate) {
                                    Text(
                                        "A template with this name already exists",
                                        color = deleteColor,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                                    )
                                } else if (selectedUserTags.isEmpty()) {
                                    Text(
                                        "Please select at least one user to share with",
                                        color = AppTheme.Colors.warning,
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
                                    // Cancel button (always shown)
                                    SlimButton(
                                        text = "Cancel",
                                        onClick = { showSaveDialog = false },
                                        containerColor = AppTheme.Colors.border,
                                        contentColor = AppTheme.Colors.textSecondary,
                                        modifier = Modifier.width(90.dp),
                                    )

                                    // Secondary button (only shown when editing existing template)
                                    if (!editorState.isNew() && onSaveMessageAs != null) {
                                        SlimButton(
                                            text = if (nameWasModified) "Rename & Update" else "Save as New",
                                            onClick = {
                                                val isDuplicateCheck = if (nameWasModified) isDuplicateForUpdate else isDuplicateForSaveAsNew
                                                if (messageName.isNotBlank() &&
                                                    !isDuplicateCheck &&
                                                    selectedUserTags.isNotEmpty()
                                                ) {
                                                    val primaryProfileId = currentProfileId ?: selectedUserTags.first()
                                                    if (nameWasModified) {
                                                        // Rename & Update: update existing template with new name
                                                        onSaveMessage(
                                                            messageName,
                                                            fields.filter { !it.excluded && it.tag.isNotBlank() },
                                                            primaryProfileId,
                                                            selectedUserTags,
                                                        )
                                                    } else {
                                                        // Save as New: create copy with same name
                                                        onSaveMessageAs(
                                                            messageName,
                                                            fields.filter { !it.excluded && it.tag.isNotBlank() },
                                                            primaryProfileId,
                                                            selectedUserTags,
                                                        )
                                                    }
                                                    showSaveDialog = false
                                                }
                                            },
                                            enabled =
                                                messageName.isNotBlank() &&
                                                    !(if (nameWasModified) isDuplicateForUpdate else isDuplicateForSaveAsNew) &&
                                                    selectedUserTags.isNotEmpty(),
                                            containerColor = AppTheme.Colors.border,
                                            contentColor = AppTheme.Colors.text,
                                            modifier = Modifier.width(130.dp),
                                        )
                                    }

                                    // Primary button (always shown, changes based on scenario)
                                    SlimButton(
                                        text =
                                            when {
                                                editorState.isNew() -> "Save as New"
                                                nameWasModified -> "Save as New"
                                                else -> "Update Existing"
                                            },
                                        onClick = {
                                            val isDuplicateCheck =
                                                if (editorState.isNew() ||
                                                    nameWasModified
                                                ) {
                                                    isDuplicateForSaveAsNew
                                                } else {
                                                    isDuplicateForUpdate
                                                }
                                            if (messageName.isNotBlank() &&
                                                !isDuplicateCheck &&
                                                selectedUserTags.isNotEmpty()
                                            ) {
                                                val primaryProfileId = currentProfileId ?: selectedUserTags.first()
                                                if (editorState.isNew() || nameWasModified) {
                                                    // New file OR renamed: Save as New
                                                    if (onSaveMessageAs != null && nameWasModified) {
                                                        onSaveMessageAs(
                                                            messageName,
                                                            fields.filter { !it.excluded && it.tag.isNotBlank() },
                                                            primaryProfileId,
                                                            selectedUserTags,
                                                        )
                                                    } else {
                                                        onSaveMessage(
                                                            messageName,
                                                            fields.filter { !it.excluded && it.tag.isNotBlank() },
                                                            primaryProfileId,
                                                            selectedUserTags,
                                                        )
                                                    }
                                                } else {
                                                    // Existing file, name unchanged: Update Existing
                                                    onSaveMessage(
                                                        messageName,
                                                        fields.filter { !it.excluded && it.tag.isNotBlank() },
                                                        primaryProfileId,
                                                        selectedUserTags,
                                                    )
                                                }
                                                showSaveDialog = false
                                            }
                                        },
                                        enabled =
                                            messageName.isNotBlank() &&
                                                !(if (editorState.isNew() || nameWasModified) isDuplicateForSaveAsNew else isDuplicateForUpdate) &&
                                                selectedUserTags.isNotEmpty(),
                                        containerColor = AppTheme.Colors.primary,
                                        contentColor = AppTheme.Colors.background,
                                        modifier = Modifier.width(130.dp),
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
                    TooltipIconButton(tooltip = "Add Field", onClick = onFieldAdd, modifier = iconSize28) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            modifier = iconSize18,
                            tint = AppTheme.Colors.textSecondary,
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
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Delete",
                            modifier = iconSize18,
                            tint = if (fields.size > 1) AppTheme.Colors.textSecondary else disabledIconColor,
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
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move Up",
                            modifier = iconSize18,
                            tint = if (selectedFieldIndex > 0) AppTheme.Colors.textSecondary else disabledIconColor,
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
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move Down",
                            modifier = iconSize18,
                            tint = if (selectedFieldIndex < fields.size - 1) AppTheme.Colors.textSecondary else disabledIconColor,
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
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear",
                            modifier = iconSize18,
                            tint = AppTheme.Colors.textSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 9: Toggle Indentation
                if (visibleButtonsCount > 9) {
                    TooltipIconButton(
                        tooltip =
                            if (!hasDataDictionary) {
                                "Requires FIX data dictionary"
                            } else if (showIndentation) {
                                "Hide Group Indentation"
                            } else {
                                "Show Group Indentation"
                            },
                        onClick = { showIndentation = !showIndentation },
                        enabled = hasDataDictionary,
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatIndentIncrease,
                            contentDescription = "Toggle Indentation",
                            modifier = iconSize18,
                            tint =
                                when {
                                    !hasDataDictionary -> disabledIconColor
                                    showIndentation -> AppTheme.Colors.primary
                                    else -> AppTheme.Colors.textSecondary
                                },
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Button 10: Toggle Description
                if (visibleButtonsCount > 10) {
                    TooltipIconButton(
                        tooltip =
                            if (!hasDataDictionary) {
                                "Requires FIX data dictionary"
                            } else if (showDescription) {
                                "Hide Description column"
                            } else {
                                "Show Description column"
                            },
                        onClick = {
                            showDescription = !showDescription
                            onDescriptionVisibilityChanged?.invoke(showDescription)
                        },
                        enabled = hasDataDictionary,
                        modifier = iconSize28,
                    ) {
                        Icon(
                            imageVector = if (showDescription) Icons.Default.ViewModule else Icons.Default.ViewList,
                            contentDescription = "Toggle Description",
                            modifier = iconSize18,
                            tint = if (hasDataDictionary) AppTheme.Colors.textSecondary else disabledIconColor,
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
                            modifier = iconSize28,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "More Options",
                                tint = AppTheme.Colors.textSecondary,
                                modifier = iconSize18,
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
                                            .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(4.dp))
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
                                                    } else if (selectedSession == null) {
                                                        onSetValidationErrors(listOf("No session selected."))
                                                    } else {
                                                        // Validate required fields before sending
                                                        val validationErrors = mutableListOf<String>()

                                                        // Check for tag 35 (message type) - required for all FIX messages
                                                        val hasMessageType = fieldsToSend.any { it.tag == "35" }
                                                        if (!hasMessageType) {
                                                            validationErrors.add("Missing required field: Tag 35 (MsgType/Message Type)")
                                                        }

                                                        if (validationErrors.isNotEmpty()) {
                                                            onSetValidationErrors(validationErrors)
                                                        } else {
                                                            onSend(fieldsToSend)
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    onSetValidationErrors(listOf("Send Error: ${e.message}"))
                                                }
                                            },
                                            enabled = canSend,
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Send,
                                                contentDescription = "Send",
                                                modifier = iconSize18,
                                                tint = if (canSend) AppTheme.Colors.primary else disabledIconColor,
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
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Validate",
                                                modifier = iconSize18,
                                                tint =
                                                    when {
                                                        hasDataDictionary.not() -> disabledIconColor
                                                        validationPassed ->
                                                            Color(
                                                                0xFF98C379,
                                                            )
                                                        ; else -> AppTheme.Colors.warning
                                                    },
                                            )
                                        }
                                    }
                                    // Button 2: Load
                                    if (visibleButtonsCount <= 2 &&
                                        onLoadMessage != null &&
                                        savedMessages.isNotEmpty()
                                    ) {
                                        var showLoadPopup by remember { mutableStateOf(false) }
                                        Box {
                                            TooltipIconButton(
                                                tooltip = "Load Message Template",
                                                onClick = { showLoadPopup = true },
                                                modifier = iconSize28,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.FolderOpen,
                                                    contentDescription = "Load",
                                                    modifier = iconSize18,
                                                    tint = AppTheme.Colors.textSecondary,
                                                )
                                            }

                                            if (showLoadPopup) {
                                                SavedMessagesBrowserPopup(
                                                    savedMessages = savedMessages,
                                                    connectionProfiles = connectionProfiles,
                                                    dictionary = dictionary,
                                                    currentProfileId = currentProfileId,
                                                    selectedEditorProfile = selectedEditorProfile,
                                                    onSelectMessage = { savedMessage ->
                                                        onLoadMessage(savedMessage)
                                                        showLoadPopup = false
                                                    },
                                                    onDeleteMessage = onDeleteMessage,
                                                    onToggleFavorite = onToggleFavorite,
                                                    onDismiss = { showLoadPopup = false },
                                                )
                                            }
                                        }
                                    }
                                    // Button 3: Save
                                    if (visibleButtonsCount <= 3 && onSaveMessage != null) {
                                        var showSaveDialog by remember { mutableStateOf(false) }
                                        TooltipIconButton(
                                            tooltip = "Save Message Template",
                                            onClick = { showSaveDialog = true },
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Save,
                                                contentDescription = "Save",
                                                modifier = iconSize18,
                                                tint = AppTheme.Colors.textSecondary,
                                            )
                                        }
                                    }
                                    // Button 4: Add
                                    if (visibleButtonsCount <= 4) {
                                        TooltipIconButton(
                                            tooltip = "Add Field",
                                            onClick = { onFieldAdd() },
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add",
                                                modifier = iconSize18,
                                                tint = AppTheme.Colors.textSecondary,
                                            )
                                        }
                                    }
                                    // Button 5: Delete
                                    if (visibleButtonsCount <= 5) {
                                        TooltipIconButton(
                                            tooltip = "Delete Field",
                                            onClick = { onFieldDelete(selectedFieldIndex) },
                                            enabled = fields.size > 1,
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Remove,
                                                contentDescription = "Delete",
                                                modifier = iconSize18,
                                                tint = if (fields.size > 1) AppTheme.Colors.textSecondary else disabledIconColor,
                                            )
                                        }
                                    }
                                    // Button 6: Move Up
                                    if (visibleButtonsCount <= 6) {
                                        TooltipIconButton(
                                            tooltip = "Move Up",
                                            onClick = { onFieldMoveUp(selectedFieldIndex) },
                                            enabled = selectedFieldIndex > 0,
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowUpward,
                                                contentDescription = "Move Up",
                                                modifier = iconSize18,
                                                tint =
                                                    if (selectedFieldIndex > 0) {
                                                        AppTheme.Colors.textSecondary
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
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowDownward,
                                                contentDescription = "Move Down",
                                                modifier = iconSize18,
                                                tint =
                                                    if (selectedFieldIndex <
                                                        fields.size - 1
                                                    ) {
                                                        AppTheme.Colors.textSecondary
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
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Clear",
                                                modifier = iconSize18,
                                                tint = AppTheme.Colors.textSecondary,
                                            )
                                        }
                                    }
                                    // Button 9: Toggle Indentation
                                    if (visibleButtonsCount <= 9) {
                                        TooltipIconButton(
                                            tooltip =
                                                if (!hasDataDictionary) {
                                                    "Requires FIX data dictionary"
                                                } else if (showIndentation) {
                                                    "Hide Group Indentation"
                                                } else {
                                                    "Show Group Indentation"
                                                },
                                            onClick = { showIndentation = !showIndentation },
                                            enabled = hasDataDictionary,
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FormatIndentIncrease,
                                                contentDescription = "Toggle Indentation",
                                                modifier = iconSize18,
                                                tint =
                                                    when {
                                                        !hasDataDictionary -> disabledIconColor
                                                        showIndentation -> AppTheme.Colors.primary
                                                        else -> AppTheme.Colors.textSecondary
                                                    },
                                            )
                                        }
                                    }
                                    // Button 10: Toggle Description
                                    if (visibleButtonsCount <= 10) {
                                        TooltipIconButton(
                                            tooltip =
                                                if (!hasDataDictionary) {
                                                    "Requires FIX data dictionary"
                                                } else if (showDescription) {
                                                    "Hide Description column"
                                                } else {
                                                    "Show Description column"
                                                },
                                            onClick = {
                                                showDescription =
                                                    !showDescription
                                                onDescriptionVisibilityChanged?.invoke(
                                                    showDescription,
                                                )
                                            },
                                            enabled = hasDataDictionary,
                                            modifier = iconSize28,
                                        ) {
                                            Icon(
                                                imageVector = if (showDescription) Icons.Default.ViewModule else Icons.Default.ViewList,
                                                contentDescription = "Toggle Description",
                                                modifier = iconSize18,
                                                tint = if (hasDataDictionary) AppTheme.Colors.textSecondary else disabledIconColor,
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

        // Search bar — the app's one field-grid search box, which this panel invented and now shares with
        // the scenario editor's Send grid and the reconcile diff. See [SlimSearchBar].
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            SlimSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                testTag = "editor-search",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // Validation error/warning display section
        if (validationErrors.isNotEmpty()) {
            // Check if these are warnings or errors
            val isWarning = validationErrors.any { it.startsWith("WARNING:") }
            val backgroundColor = if (isWarning) Color(0xFF3A2F1F) else Color(0xFF3A1F1F) // Amber-tinted vs red-tinted
            val textColor = if (isWarning) Color(0xFFFFA726) else AppTheme.Colors.error // Amber vs red
            val label = if (isWarning) "Validation Warnings" else "Validation Errors"

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
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
                            imageVector = if (isWarning) Icons.Default.Warning else Icons.Default.Error,
                            contentDescription = label,
                            tint = textColor,
                            modifier = iconSize16,
                        )
                        Text(
                            text = "$label (${validationErrors.size})",
                            color = textColor,
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                    TooltipIconButton(
                        tooltip = if (isWarning) "Dismiss Warnings" else "Dismiss Errors",
                        onClick = onClearValidationErrors,
                        modifier = iconSize20,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = AppTheme.Colors.textSecondary,
                            modifier = iconSize14,
                        )
                    }
                }

                // Display each error/warning
                validationErrors.forEach { error ->
                    Text(
                        text = "• $error",
                        color = textColor,
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
                    .background(AppTheme.Colors.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Spacer for eye icon column
            Spacer(modifier = Modifier.width(24.dp))

            Text(
                text = "Tag",
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.width(48.dp),
            )

            if (hasDataDictionary) {
                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "Field Name",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.width(120.dp),
                )

                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Spacer(modifier = Modifier.width(4.dp))
            }

            Text(
                text = "Value",
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.width(180.dp),
            )

            if (showDescription) {
                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "Description",
                    color = AppTheme.Colors.textSecondary,
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

                            // Does this field answer the query? The rule is shared with every other field
                            // grid in the app, deliberately — see [FieldSearch].
                            val isHighlighted =
                                com.knapsack.fixtool.service.FieldSearch.matches(
                                    query = searchQuery,
                                    tag = field.tag,
                                    name = field.tag.toIntOrNull()?.let { dictionary.getFieldName(it) },
                                    value = field.value,
                                )

                            FieldEditorRow(
                                field = field,
                                dictionary = dictionary,
                                isSelected = index in selectedFieldIndices,
                                isPrimarySelection = selectedFieldIndex == index,
                                isManaged = isManaged,
                                isHighlighted = isHighlighted,
                                onFieldChange = { newField ->
                                    onFieldUpdate(index, newField)
                                },
                                onClick = { isCtrl, isShift ->
                                    onFieldSelect(index, isCtrl, isShift)
                                },
                                showDescription = showDescription,
                                showFieldName = hasDataDictionary,
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
                            .background(AppTheme.Colors.border)
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
                                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(4.dp))
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
                                color = AppTheme.Colors.textSecondary,
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
                                    modifier = iconSize20,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = AppTheme.Colors.textSecondary,
                                        modifier = iconSize14,
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
                                    modifier = iconSize20,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste",
                                        tint = AppTheme.Colors.textSecondary,
                                        modifier = iconSize14,
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
                                    focusedTextColor = AppTheme.Colors.text,
                                    unfocusedTextColor = AppTheme.Colors.text,
                                    cursorColor = AppTheme.Colors.primary,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                            textStyle =
                                LocalTextStyle.current.copy(
                                    fontSize = 10.sp,
                                    color = AppTheme.Colors.text,
                                    fontFamily = FontFamily.Monospace,
                                ),
                        )
                    }
                }
            }
        }

        // Status bar at the bottom showing message state
        EditorStatusBar(
            editorState = editorState,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Indent level and instance number for every row, derived from [GroupOverlay] — the same
 * dictionary-driven structure the reconcile diff bands, so the editor and the diff cannot disagree
 * about where an entry begins. (This replaced a hand-rolled state machine that guessed each group's
 * shape from its first instance: a single-instance group never finished "learning" and swallowed
 * the rest of the body, an optional field present in only one instance truncated the group, and a
 * sibling group after a one-instance group rendered as a nested one.)
 *
 * A row's indent is the number of overlay entries containing it: a top-level field — and every
 * group's count row — sits at 0, a party's fields at 1, a party's sub-ids at 2. The first row of an
 * entry carries its 1-based instance number, which the row renders as `[n]`. Groups the dictionary
 * defines are bracketed exactly (delimiter to delimiter, nested via each group's own scoped
 * dictionary); groups it has never heard of fall back to the overlay's period-detection guess, flat.
 *
 * Rows the overlay cannot place — a blank row mid-edit, a non-numeric tag — inherit the indent above
 * them rather than snapping to 0 under the author's cursor, and are invisible to the walk, so a
 * half-typed row does not split the entry it sits in.
 */
internal fun calculateIndentLevels(fields: List<FixField>, dictionary: FixDictionary): Pair<List<Int>, List<Int?>> {
    val numbered = fields.withIndex().mapNotNull { (row, f) -> f.tag.toIntOrNull()?.let { tag -> row to (tag to f.value) } }
    val overlay =
        GroupOverlay.build(
            numbered.map { (_, field) -> field.first to field.second.takeIf { it.isNotBlank() } },
            numbered.firstOrNull { it.second.first == 35 }?.second?.second,
            dictionary,
        )
    val posOf = numbered.withIndex().associate { (pos, n) -> n.first to pos }

    val indentLevels = mutableListOf<Int>()
    val instanceNumbers = mutableListOf<Int?>()
    fields.indices.forEach { row ->
        val pos = posOf[row]
        if (pos == null) {
            indentLevels.add(indentLevels.lastOrNull() ?: 0)
            instanceNumbers.add(null)
        } else {
            indentLevels.add(overlay.depthAt(pos))
            instanceNumbers.add(overlay.entryOpenedAt(pos)?.let { it.entryIndex + 1 })
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
    isHighlighted: Boolean = false,
    onFieldChange: (FixField) -> Unit,
    onClick: (isCtrl: Boolean, isShift: Boolean) -> Unit,
    showDescription: Boolean,
    showFieldName: Boolean = true,
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
            tagInt.let {
                dictionary.getFieldEnumValues(it)
            }
        } else {
            emptyList()
        }

    // Determine background color based on selection and highlight state
    val backgroundColor =
        when {
            isPrimarySelection -> AppTheme.Colors.selectionPrimary // Primary selection - darker blue
            isSelected -> selectionSecondaryColor // Part of multi-selection - lighter blue
            isHighlighted -> AppTheme.Colors.searchMatch // Matches search query - the app's one gold
            else -> AppTheme.Colors.background // Not selected
        }

    Column {
        // Show instance number as a header if present (first field of each group instance)
        if (instanceNumber != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(AppTheme.Colors.surfaceHeader)
                        .padding(start = FixIndent.start(indentLevel, FixIndent.DETAIL_STEP, FixIndent.DETAIL_BASE), end = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    text = "[$instanceNumber]",
                    color = AppTheme.Colors.fieldValue,
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
                                if (event.type == PointerEventType.Press) {
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
                Spacer(modifier = Modifier.width(FixIndent.start(indentLevel, FixIndent.DETAIL_STEP)))
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
                modifier = iconSize20,
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
                            isManaged -> disabledIconColor
                            field.excluded -> AppTheme.Colors.textDisabled
                            isGroupTag -> AppTheme.Colors.groupTag // Orange for group tags
                            else -> AppTheme.Colors.primary // Green for regular fields
                        },
                    modifier = iconSize14,
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
                    backgroundColor =
                        if (field.tag.isBlank()) {
                            AppTheme.Colors.emptyFieldBackground
                        } else {
                            AppTheme.Colors.surface
                        },
                )

                if (showFieldName) {
                    Spacer(modifier = Modifier.width(4.dp))

                    // Field name (read-only)
                    Text(
                        text = fieldName,
                        color =
                            when {
                                isManaged -> AppTheme.Colors.textDisabled
                                isGroupTag -> AppTheme.Colors.groupTag // Orange for group tags
                                else -> AppTheme.Colors.primary // Green for regular fields
                            },
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp),
                    )

                    Spacer(modifier = Modifier.width(4.dp))
                } else {
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Value input
                SlimTextField(
                    value = field.value,
                    onValueChange = { onFieldChange(field.copy(value = it)) },
                    modifier = Modifier.width(180.dp).height(24.dp),
                    backgroundColor =
                        if (field.value.isBlank()) {
                            AppTheme.Colors.emptyFieldBackground
                        } else {
                            AppTheme.Colors.surface
                        },
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
                            color = if (isManaged) AppTheme.Colors.textDisabled else descriptionColor,
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
                            color = if (isManaged || field.excluded) AppTheme.Colors.textDisabled else AppTheme.Colors.text,
                            fontSize = 10.sp,
                            modifier =
                                Modifier
                                    .width(48.dp)
                                    .then(
                                        if (field.tag.isBlank()) {
                                            Modifier
                                                .background(AppTheme.Colors.emptyFieldBackground, inputShape)
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )

                        if (showFieldName) {
                            Spacer(modifier = Modifier.width(4.dp))

                            // Field name
                            Text(
                                text = fieldName,
                                color =
                                    when {
                                        isManaged || field.excluded -> AppTheme.Colors.textDisabled
                                        isGroupTag -> AppTheme.Colors.groupTag // Orange for group tags
                                        else -> AppTheme.Colors.primary // Green for regular fields
                                    },
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.width(120.dp),
                            )

                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Text(
                            text = field.value,
                            color = if (isManaged || field.excluded) AppTheme.Colors.textDisabled else AppTheme.Colors.text,
                            fontSize = 10.sp,
                            modifier =
                                Modifier
                                    .width(180.dp)
                                    .then(
                                        if (field.value.isBlank()) {
                                            Modifier
                                                .background(AppTheme.Colors.emptyFieldBackground, inputShape)
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )

                        if (showDescription) {
                            Spacer(modifier = Modifier.width(4.dp))

                            Text(
                                text = if (hasValueDescription) valueDescription!! else "",
                                color = if (isManaged || field.excluded) AppTheme.Colors.textDisabled else descriptionColor,
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

    // Detect Cucumber test template format (contains [FieldName] pattern)
    val isCucumberFormat = rawMessage.contains(Regex("""\[.*?\]\s*\d+\s*="""))
    if (isCucumberFormat) {
        return try {
            FixField.parseCucumberTemplateFormat(rawMessage)
        } catch (e: Exception) {
            null // Return null if parsing fails
        }
    }

    // Managed tags that QuickFIX/J handles automatically
    // 8=BeginString, 9=BodyLength, 10=CheckSum, 34=MsgSeqNum, 49=SenderCompID, 50=SenderSubID,
    // 52=SendingTime, 56=TargetCompID, 57=TargetSubID, 142=SenderLocationID, 143=TargetLocationID
    val managedTags = setOf("8", "9", "10", "34", "49", "50", "52", "56", "57", "142", "143")

    return try {
        // Normalize message format (handles both traditional and line-based formats)
        val normalizedMessage = rawMessage.normalizeFixMessage()

        // Parse the normalized traditional format
        val fields =
            normalizedMessage
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
                    color = if (enabled) containerColor else AppTheme.Colors.border,
                    shape = RoundedCornerShape(4.dp),
                ).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else AppTheme.Colors.textDisabled,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
    }
}

// Component-specific color constants (not in AppTheme)
private val deleteColor = Color(0xFFFF5555)
private val disabledIconColor = Color(0xFF4A4A4A)
private val selectionSecondaryColor = Color(0xFF1E4A6B)
private val placeholderColor = Color(0xFF888888)
private val descriptionColor = Color(0xFF9A9A9A)

// Common modifiers
private val iconSize28 = Modifier.size(28.dp)
private val iconSize20 = Modifier.size(20.dp)
private val iconSize18 = Modifier.size(18.dp)
private val iconSize16 = Modifier.size(16.dp)
private val iconSize14 = Modifier.size(14.dp)
private val iconSize24 = Modifier.size(24.dp)
private val inputShape = RoundedCornerShape(2.dp)
