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

/**
 * **The nouns the two view toggles carry, in one place.**
 *
 * The editor draws its toolbar twice — the row, and the progressive overflow Popup that re-renders the same
 * eleven buttons — so every one of these strings existed twice and the two copies had already drifted: the
 * row's Validate said "Validate message against the data dictionary" where the Popup's said "Validate", and one
 * said "Requires FIX data dictionary" where the other said "Validation disabled". One constant each until
 * the Popup itself goes.
 *
 * The word and not the gesture: "Group indentation", never "Hide Group Indentation" / "Show Group
 * Indentation". The pressed look is what says which of those a click would do.
 */
private const val INDENT_LABEL = "Group indentation"

private const val DESCRIPTION_LABEL = "Description column"

private const val NEEDS_DICTIONARY = "Requires a FIX data dictionary"

/**
 * **Saving a message as a template**, lifted out of the button that used to contain it.
 *
 * Three hundred lines of dialog nested inside an `onClick`, which meant the dialog only existed while the
 * button was drawn — and the button is now one of the first things the bar folds. A folded Save that could
 * not open its own dialog would be an action that is reachable at one width and inert at another.
 *
 * The three buttons are the same three it always had, and the rule behind them is unchanged: a new
 * template saves as new, a renamed one saves as new under the new name, and an existing one with its name
 * untouched updates in place.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun SaveTemplateDialog(
    editorState: com.knapsack.fixtool.model.MessageEditorState,
    fields: List<FixField>,
    savedMessages: List<com.knapsack.fixtool.model.SavedFixMessage>,
    connectionProfiles: List<com.knapsack.fixtool.model.FixConnectionProfile>,
    currentProfileId: String?,
    onSaveMessage: (String, List<FixField>, String, Set<String>) -> Unit,
    onSaveMessageAs: ((String, List<FixField>, String, Set<String>) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var messageName by remember { mutableStateOf(editorState.messageNameOrNull() ?: "") }
    var selectedUserTags by remember {
        mutableStateOf(editorState.allUserTags().ifEmpty { currentProfileId?.let { setOf(it) } ?: emptySet() })
    }
    // "Save as new" checks every template; "Update" checks the others, so a template does not clash with
    // itself.
    val clashesAsNew =
        savedMessages.any { it.name.trim().equals(messageName.trim(), ignoreCase = true) && messageName.isNotBlank() }
    val clashesOnUpdate =
        savedMessages.any {
            it.name.trim().equals(messageName.trim(), ignoreCase = true) &&
                messageName.isNotBlank() &&
                it.id != editorState.messageIdOrNull()
        }
    val originalName = editorState.messageNameOrNull() ?: ""
    val renamed = messageName.trim() != originalName.trim() && originalName.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    val nameFieldInteractionSource = remember { MutableInteractionSource() }
    val nameFocused by nameFieldInteractionSource.collectIsFocusedAsState()
    val savable = messageName.isNotBlank() && selectedUserTags.isNotEmpty()
    val toSave = fields.filter { !it.excluded && it.tag.isNotBlank() }
    val primaryProfileId = currentProfileId ?: selectedUserTags.firstOrNull()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .background(AppTheme.Colors.surface, RoundedCornerShape(8.dp))
                    .padding(16.dp)
                    .width(400.dp)
                    .testTag("editor-save-template-dialog"),
        ) {
            Text(
                "Save message template",
                color = AppTheme.Colors.text,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                "Template name",
                color = AppTheme.Colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            BasicTextField(
                value = messageName,
                onValueChange = { messageName = it },
                singleLine = true,
                textStyle =
                    TextStyle(color = AppTheme.Colors.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(AppTheme.Colors.primary),
                interactionSource = nameFieldInteractionSource,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(AppTheme.Colors.surface, RoundedCornerShape(2.dp))
                        .border(
                            width = 1.dp,
                            color =
                                when {
                                    clashesOnUpdate -> deleteColor
                                    nameFocused -> AppTheme.Colors.primary
                                    else -> AppTheme.Colors.borderDark
                                },
                            shape = RoundedCornerShape(2.dp),
                        ).padding(horizontal = 4.dp, vertical = 8.dp)
                        .focusRequester(focusRequester),
            )

            if (connectionProfiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Share with users (select one or more)",
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
                            .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(4.dp))
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
                                        if (checked) selectedUserTags + profile.id else selectedUserTags - profile.id
                                },
                                colors =
                                    CheckboxDefaults.colors(
                                        checkedColor = AppTheme.Colors.primary,
                                        uncheckedColor = AppTheme.Colors.border,
                                        checkmarkColor = AppTheme.Colors.background,
                                    ),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(profile.name, color = AppTheme.Colors.text, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // The reason it cannot be saved, where the dialog footer keeps it — never a disabled button
            // with nothing to say.
            when {
                clashesOnUpdate ->
                    Text(
                        "A template with this name already exists",
                        color = deleteColor,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                selectedUserTags.isEmpty() ->
                    Text(
                        "Please select at least one user to share with",
                        color = AppTheme.Colors.warning,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                else -> Spacer(modifier = Modifier.height(16.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                SlimButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    containerColor = AppTheme.Colors.border,
                    contentColor = AppTheme.Colors.textSecondary,
                    modifier = Modifier.width(90.dp),
                )
                if (!editorState.isNew() && onSaveMessageAs != null) {
                    SlimButton(
                        text = if (renamed) "Rename & update" else "Save as new",
                        onClick = {
                            if (savable && !(if (renamed) clashesOnUpdate else clashesAsNew) && primaryProfileId != null) {
                                if (renamed) {
                                    onSaveMessage(messageName, toSave, primaryProfileId, selectedUserTags)
                                } else {
                                    onSaveMessageAs(messageName, toSave, primaryProfileId, selectedUserTags)
                                }
                                onDismiss()
                            }
                        },
                        enabled = savable && !(if (renamed) clashesOnUpdate else clashesAsNew),
                        containerColor = AppTheme.Colors.border,
                        contentColor = AppTheme.Colors.text,
                        modifier = Modifier.width(130.dp),
                    )
                }
                val savesAsNew = editorState.isNew() || renamed
                SlimButton(
                    text = if (savesAsNew) "Save as new" else "Update existing",
                    onClick = {
                        if (savable && !(if (savesAsNew) clashesAsNew else clashesOnUpdate) && primaryProfileId != null) {
                            if (savesAsNew && onSaveMessageAs != null && renamed) {
                                onSaveMessageAs(messageName, toSave, primaryProfileId, selectedUserTags)
                            } else {
                                onSaveMessage(messageName, toSave, primaryProfileId, selectedUserTags)
                            }
                            onDismiss()
                        }
                    },
                    enabled = savable && !(if (savesAsNew) clashesAsNew else clashesOnUpdate),
                    containerColor = AppTheme.Colors.primary,
                    contentColor = AppTheme.Colors.background,
                    modifier = Modifier.width(130.dp),
                )
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * **Send, with the three refusals it has always had, said out loud.**
 *
 * Nothing to send, no session to send on, and no MsgType. Each one used to be a branch inside the button's
 * own `onClick`; they are here so the button is a name and a lambda like every other action on the bar.
 */
private fun sendGuarded(
    sendable: List<FixField>,
    selectedSession: FixMessageSession?,
    unconnectedProfileName: String?,
    onSetValidationErrors: (List<String>) -> Unit,
    onSend: (List<FixField>) -> Unit,
) {
    try {
        when {
            sendable.isEmpty() ->
                onSetValidationErrors(listOf("No fields to send. Add at least one field with tag and value."))
            selectedSession == null ->
                onSetValidationErrors(
                    listOf(
                        unconnectedProfileName?.let { "$it is not connected. Connect it to send message." }
                            ?: "No session selected. Select a session to send message.",
                    ),
                )
            sendable.none { it.tag == "35" } ->
                onSetValidationErrors(listOf("Missing required field: Tag 35 (MsgType/Message Type)"))
            else -> onSend(sendable)
        }
    } catch (e: Exception) {
        onSetValidationErrors(listOf("Send error: ${e.message ?: e.toString()}"))
    }
}

/**
 * The same, for every logged-on session at once.
 *
 * Templates are re-resolved per session, so a `${'$'}{UUID.randomUUID()}` in MDReqID yields a different value on
 * each one — which is the whole reason this is not a loop over Send at the call site.
 */
private fun sendAllGuarded(
    sendable: List<FixField>,
    onSetValidationErrors: (List<String>) -> Unit,
    onSendToAll: (List<FixField>) -> Unit,
) {
    try {
        when {
            sendable.isEmpty() ->
                onSetValidationErrors(listOf("No fields to send. Add at least one field with tag and value."))
            sendable.none { it.tag == "35" } ->
                onSetValidationErrors(listOf("Missing required field: Tag 35 (MsgType/Message Type)"))
            else -> onSendToAll(sendable)
        }
    } catch (e: Exception) {
        onSetValidationErrors(listOf("Send error: ${e.message ?: e.toString()}"))
    }
}

// ---------------------------------------------------------------------------------------------------
// The editor's toolbar: one list of actions, one badge, and the two dialogs its buttons open.
// ---------------------------------------------------------------------------------------------------

/** Room the profile picker keeps for itself, which the fold may not spend. */
private val PROFILE_PICKER_WIDTH = 140.dp

/** Room the validation badge keeps beside Send, whether or not there is a verdict to print. */
private val VALIDATION_BADGE_SLOT = 60.dp

/**
 * **Every action the editor offers, in one list, in fold order.**
 *
 * The ranks are the note's fold table, as code: the two view toggles go first, then the template Open and
 * Save, then the five field buttons, then Load run and Send to all — those two keep their word until the
 * very end, because they are two of the three things a person came here to do. **Send never folds.**
 *
 * Send, Send to all and Load run are three different sizes of the same intent: one message on one session,
 * one message on every session once, and thousands with the accounting afterwards.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun editorActions(
    replyStep: ReplyStepEditing?,
    sendable: List<FixField>,
    canSend: Boolean,
    unconnectedProfileName: String?,
    connectionStateText: String,
    onSend: () -> Unit,
    onSendToAll: (() -> Unit)?,
    canSendToAll: Boolean,
    loggedOnSessionCount: Int,
    onLoadRun: (() -> Unit)?,
    canLoad: Boolean,
    onValidate: () -> Unit,
    hasDataDictionary: Boolean,
    validatable: Boolean,
    onOpenTemplate: (() -> Unit)?,
    onSaveTemplate: (() -> Unit)?,
    onFieldAdd: () -> Unit,
    onFieldDelete: () -> Unit,
    canDeleteField: Boolean,
    onFieldMoveUp: () -> Unit,
    canMoveFieldUp: Boolean,
    onFieldMoveDown: () -> Unit,
    canMoveFieldDown: Boolean,
    onClearFields: () -> Unit,
    showIndentation: Boolean,
    onToggleIndentation: () -> Unit,
    showDescription: Boolean,
    onToggleDescription: () -> Unit,
): List<BarAction> {
    // Withheld while a rule's step is loaded: a template has no session to go to, and the action that
    // finishes it is Apply, in the bar above.
    val sending =
        if (replyStep != null) {
            emptyList()
        } else {
            listOfNotNull(
                BarAction(
                    label = "Send",
                    icon = Icons.Default.Send,
                    onClick = onSend,
                    tag = "editor-send",
                    enabled = canSend && sendable.isNotEmpty(),
                    labelled = true,
                    neverFolds = true,
                    disabledReason =
                        when {
                            sendable.isEmpty() -> "Nothing to send — add a field with a tag and a value"
                            unconnectedProfileName != null -> "$unconnectedProfileName is not connected"
                            else -> "The session is not logged on ($connectionStateText)"
                        },
                ),
                onSendToAll?.let {
                    BarAction(
                        label = "Send to all $loggedOnSessionCount",
                        icon = Icons.Default.Campaign,
                        onClick = it,
                        tag = "editor-send-all",
                        enabled = canSendToAll,
                        labelled = true,
                        foldRank = 10,
                        disabledReason = "No session is logged on",
                    )
                },
                onLoadRun?.let {
                    BarAction(
                        label = "Load run",
                        icon = Icons.Default.Bolt,
                        onClick = it,
                        tag = "editor-load",
                        enabled = canLoad,
                        labelled = true,
                        foldRank = 9,
                        hint = "issue this message across a profile's sessions and account for every reply",
                        disabledReason = "The message needs a MsgType (35) and a correlation tag such as ClOrdID (11)",
                    )
                },
                BarAction(
                    label = "Validate",
                    icon = Icons.Default.CheckCircle,
                    onClick = onValidate,
                    tag = "editor-validate",
                    // Refused with nothing to validate, rather than answering the click with silence. The
                    // old button had the same guard inside its handler, where nobody could see it: press
                    // Validate on an empty editor and precisely nothing happened.
                    enabled = hasDataDictionary && validatable,
                    foldRank = 8,
                    hint = "against the data dictionary",
                    disabledReason = if (!hasDataDictionary) NEEDS_DICTIONARY else "Nothing to validate yet",
                ),
            )
        }

    return sending +
        listOfNotNull(
            onOpenTemplate?.let {
                BarAction("Load message template", Icons.Default.FolderOpen, it, "editor-open-template", foldRank = 2)
            },
            onSaveTemplate?.let {
                BarAction("Save message template", Icons.Default.Save, it, "editor-save-template", foldRank = 3)
            },
            BarAction("Add field", Icons.Default.Add, onFieldAdd, "editor-add-field", foldRank = 7),
            BarAction(
                "Delete field",
                Icons.Default.Remove,
                onFieldDelete,
                "editor-delete-field",
                enabled = canDeleteField,
                foldRank = 6,
                disabledReason = "Delete field — the message needs at least one",
            ),
            BarAction(
                "Move up",
                Icons.Default.ArrowUpward,
                onFieldMoveUp,
                "editor-move-up",
                enabled = canMoveFieldUp,
                foldRank = 5,
                disabledReason = "Move up — this field is already first",
            ),
            BarAction(
                "Move down",
                Icons.Default.ArrowDownward,
                onFieldMoveDown,
                "editor-move-down",
                enabled = canMoveFieldDown,
                foldRank = 4,
                disabledReason = "Move down — this field is already last",
            ),
            BarAction("Clear fields", Icons.Default.Delete, onClearFields, "editor-clear-fields", foldRank = 4),
            // The two view toggles fold first, and keep their state as a tick in the ⋯ menu.
            BarAction(
                INDENT_LABEL,
                Icons.Default.FormatIndentIncrease,
                onToggleIndentation,
                "editor-indent",
                enabled = hasDataDictionary,
                pressed = showIndentation,
                foldRank = 1,
                disabledReason = NEEDS_DICTIONARY,
            ),
            BarAction(
                DESCRIPTION_LABEL,
                Icons.Default.ViewModule,
                onToggleDescription,
                "editor-description",
                enabled = hasDataDictionary,
                pressed = showDescription,
                foldRank = 1,
                disabledReason = NEEDS_DICTIONARY,
            ),
        )
}

/**
 * **What the last Validate found, beside Send.**
 *
 * Validate was a button that answered by tinting its own glyph — green for passed, a dull orange for
 * anything else — which is a control pretending to be a toggle, and a count that a colour cannot carry.
 * The result is a word now, where the eye already is: "Valid" in green, or "3 errors" in warn.
 *
 * Nothing at all until Validate is pressed, and nothing again the moment the fields change: a verdict about
 * a message that no longer exists is worse than no verdict.
 */
@Composable
private fun ValidationBadge(errors: Int?) {
    if (errors == null) return
    val passed = errors == 0
    Text(
        text = if (passed) "Valid" else "$errors error${if (errors == 1) "" else "s"}",
        color = if (passed) AppTheme.Colors.success else AppTheme.Colors.warning,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier.testTag("editor-validation-badge"),
    )
}

/** Connected first, then connecting, then everything else — so the list opens on what can be sent to. */
private fun profileConnectionPriority(state: FixConnectionState?): Int =
    when (state) {
        FixConnectionState.CONNECTED, FixConnectionState.LOGGED_ON -> 0
        FixConnectionState.CONNECTING -> 1
        else -> 2
    }

/** A filled dot for a live profile, a hollow one for the rest. */
private fun profileStatusIndicator(state: FixConnectionState?): String =
    when (state) {
        FixConnectionState.CONNECTED, FixConnectionState.LOGGED_ON, FixConnectionState.CONNECTING -> "\u25CF"
        else -> "\u25CB"
    }

private fun profileStatusColor(state: FixConnectionState?): Color =
    when (state) {
        FixConnectionState.CONNECTED, FixConnectionState.LOGGED_ON -> Color(0xFF4CAF50)
        FixConnectionState.CONNECTING -> Color(0xFFFFA726)
        else -> Color(0xFF9E9E9E)
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

    /**
     * **How the last validation went, or null for "not validated since the fields changed".**
     *
     * A count rather than a boolean, because the result is a badge beside Send now — "Valid" in green or
     * "3 errors" in warn — and a boolean can only say the first of those. Null is the honest third state:
     * Validate has not been pressed since the message last changed, so the badge says nothing rather than
     * carrying a verdict about a message that no longer exists.
     */
    var validationVerdict by remember { mutableStateOf<Int?>(null) }

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
        // The verdict was about the message as it was, so it goes when the message changes.
        validationVerdict = null
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppTheme.Colors.background),
    ) {
        // The shared dock header: "Editor" from the stripe tab rather than "Message Editor", and a Hide that
        // finally has a tooltip — this was the one dock whose close was a bare IconButton, so the only way
        // to learn what it did was to press it. The editor's own button row is below, untouched here.
        DockHeader(window = ToolWindow.EDITOR, onHide = onClose)

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

        // **The editor's toolbar, on the shared fold.**
        //
        // It used to draw its eleven buttons twice: once in the row, and once again inside a `Popup` that
        // opened when a hand-rolled `visibleButtonsCount` decided the row had run out of width. Two copies
        // of every tooltip, and they had already drifted — the row's Validate said "Validate Message
        // against Data Dictionary" where the Popup's said "Validate", and one said "Requires FIX data
        // dictionary" where the other said "Validation disabled". One list of actions now, folded by the
        // one rule every bar in the app uses.
        //
        // **The three things a person came to the editor to do are words**, the way Run and Quick Connect
        // are words on the toolbar: Send, Send to all and Load run keep their labels until the bar runs
        // short. Field editing stays glyphs — those five act on the selected row and get pressed in bursts
        // while the eye is on the field list rather than on the button.
        var showLoadPopup by remember { mutableStateOf(false) }
        var showSaveDialog by remember { mutableStateOf(false) }

        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            val available = maxWidth

            // Read all session connection states to trigger recomposition when they change
            val sessionStates = sessions.map { session -> session.connectionState.collectAsState().value }
            val loggedOnSessionCount = sessionStates.count { it == FixConnectionState.LOGGED_ON }
            val sortedProfiles =
                remember(connectionProfiles, sessionStates) {
                    connectionProfiles.sortedWith(
                        compareBy<com.knapsack.fixtool.model.FixConnectionProfile> {
                            profileConnectionPriority(onGetProfileConnectionState?.invoke(it.id))
                        }.thenBy { it.name.lowercase() },
                    )
                }

            // The fields worth sending: what the author typed, minus the ones QuickFIX/J manages itself.
            val sendable =
                fields.filter { !it.excluded && it.tag.isNotBlank() && it.value.isNotBlank() && it.tag !in managedTags }
            val asTemplate =
                LoadTemplate("message editor", sendable.mapNotNull { f -> f.tag.toIntOrNull()?.let { it to f.value } })
            val canLoad = asTemplate.msgType != null && asTemplate.inferMatch() != null
            val canSendToAll = loggedOnSessionCount > 0
            // A profile that has never been connected owns no session, so name it rather than reporting
            // the state of a session that does not exist.
            val unconnectedProfileName = if (selectedSession == null) selectedEditorProfile?.name else null

            val actions =
                editorActions(
                    replyStep = replyStep,
                    sendable = sendable,
                    canSend = canSend,
                    unconnectedProfileName = unconnectedProfileName,
                    connectionStateText = connectionState.getDisplayText(),
                    onSend = {
                        onClearValidationErrors()
                        sendGuarded(sendable, selectedSession, unconnectedProfileName, onSetValidationErrors, onSend)
                    },
                    onSendToAll =
                        onSendToAll?.let { all ->
                            {
                                onClearValidationErrors()
                                sendAllGuarded(sendable, onSetValidationErrors, all)
                            }
                        },
                    canSendToAll = canSendToAll,
                    loggedOnSessionCount = loggedOnSessionCount,
                    onLoadRun =
                        onLoad?.let { load ->
                            {
                                onClearValidationErrors()
                                load(sendable)
                            }
                        },
                    canLoad = canLoad,
                    onValidate = {
                        onClearValidationErrors()
                        val toValidate = fields.filter { !it.excluded && it.tag.isNotBlank() }
                        if (toValidate.isNotEmpty()) validationVerdict = onValidate(toValidate).size
                    },
                    hasDataDictionary = hasDataDictionary,
                    validatable = fields.any { !it.excluded && it.tag.isNotBlank() },
                    onOpenTemplate = if (onLoadMessage != null && savedMessages.isNotEmpty()) ({ showLoadPopup = true }) else null,
                    onSaveTemplate = if (onSaveMessage != null) ({ showSaveDialog = true }) else null,
                    onFieldAdd = onFieldAdd,
                    onFieldDelete = { onFieldDelete(selectedFieldIndex) },
                    canDeleteField = fields.size > 1,
                    onFieldMoveUp = { onFieldMoveUp(selectedFieldIndex) },
                    canMoveFieldUp = selectedFieldIndex > 0,
                    onFieldMoveDown = { onFieldMoveDown(selectedFieldIndex) },
                    canMoveFieldDown = selectedFieldIndex < fields.size - 1,
                    onClearFields = {
                        previewText = ""
                        onClearFields()
                    },
                    showIndentation = showIndentation,
                    onToggleIndentation = { showIndentation = !showIndentation },
                    showDescription = showDescription,
                    onToggleDescription = {
                        showDescription = !showDescription
                        onDescriptionVisibilityChanged?.invoke(showDescription)
                    },
                )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Profile dropdown - every profile, with its connection status, connected ones first.
                SlimDropdownWithColor(
                    value = selectedEditorProfile,
                    options = sortedProfiles,
                    onValueChange = { profile: com.knapsack.fixtool.model.FixConnectionProfile? ->
                        logger.info("MessageEditorPanel profile dropdown changed to: ${profile?.name} (ID: ${profile?.id})")
                        onEditorProfileChange?.invoke(profile)
                    },
                    displayText = { profile: com.knapsack.fixtool.model.FixConnectionProfile ->
                        "${profileStatusIndicator(onGetProfileConnectionState?.invoke(profile.id))} ${profile.name}"
                    },
                    textColor = { profile: com.knapsack.fixtool.model.FixConnectionProfile ->
                        profileStatusColor(onGetProfileConnectionState?.invoke(profile.id))
                    },
                    placeholder = "Profile",
                    allowUnselect = true,
                    modifier = Modifier.widthIn(max = PROFILE_PICKER_WIDTH),
                )

                Spacer(modifier = Modifier.width(6.dp))
                ValidationBadge(validationVerdict)
                Spacer(modifier = Modifier.weight(1f))

                FoldingActions(
                    actions = actions,
                    available = available,
                    reserved = PROFILE_PICKER_WIDTH + if (validationVerdict == null) 0.dp else VALIDATION_BADGE_SLOT,
                    overflowTag = "editor-overflow",
                )
            }
        }

        // The template browser and the save dialog are composed outside the bar, so a folded Open or Save
        // still opens them: a dialog nested inside a button that is no longer drawn is a dialog that cannot
        // be reached at the width where the button folded.
        if (showLoadPopup && onLoadMessage != null) {
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
        if (showSaveDialog && onSaveMessage != null) {
            SaveTemplateDialog(
                editorState = editorState,
                fields = fields,
                savedMessages = savedMessages,
                connectionProfiles = connectionProfiles,
                currentProfileId = currentProfileId,
                onSaveMessage = onSaveMessage,
                onSaveMessageAs = onSaveMessageAs,
                onDismiss = { showSaveDialog = false },
            )
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
            val label = if (isWarning) "Validation warnings" else "Validation errors"

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
                        tooltip = if (isWarning) "Dismiss warnings" else "Dismiss errors",
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
                    text = "Field name",
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
                                    tooltip = "Copy to clipboard",
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
                                    tooltip = "Paste from clipboard",
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
