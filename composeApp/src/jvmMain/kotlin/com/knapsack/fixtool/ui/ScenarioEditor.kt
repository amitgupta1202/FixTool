// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList", "TooManyFunctions")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.RawMessageView
import com.knapsack.fixtool.service.ScenarioAnnotations

/** Label used in session dropdowns for "no explicit session" (the runner uses the active one). */
private const val ACTIVE_SESSION = "(active session)"

/** The step kinds the editor can hold. */
enum class StepKind { SEND, WAIT, EXPECT, CLEAR, RESET }

/**
 * A step under edit. Unlike the old builder draft, this round-trips **everything** the model holds —
 * the Expect/Wait `match` predicate and the expectation's `golden` survive load → edit → save.
 */
data class EditStep(
    val kind: StepKind,
    val session: String? = null,
    val fields: List<Pair<Int, String>> = emptyList(),
    val state: String = "",
    val match: MatchPredicate? = null,
    val direction: String = "in",
    val timeoutMs: Long = 10_000,
    val expectation: Expectation = Expectation(emptyList()),
    val sender: Int? = null,
    val target: Int? = null,
)

fun ScenarioStep.toEditStep(): EditStep =
    when (this) {
        is ScenarioStep.Send -> EditStep(StepKind.SEND, session, fields = FixMessageHelper.parseFixMessage(raw))
        is ScenarioStep.Wait -> EditStep(StepKind.WAIT, session, state = state ?: "", match = match, timeoutMs = timeoutMs)
        is ScenarioStep.Expect ->
            EditStep(StepKind.EXPECT, session, match = match, direction = direction, timeoutMs = timeoutMs, expectation = expectation)
        is ScenarioStep.ClearMessages -> EditStep(StepKind.CLEAR, session)
        is ScenarioStep.ResetSeqNum -> EditStep(StepKind.RESET, session, sender = sender, target = target)
    }

fun EditStep.toStep(): ScenarioStep =
    when (kind) {
        StepKind.SEND -> ScenarioStep.Send(fields.joinToString("|", postfix = "|") { "${it.first}=${it.second}" }, session)
        StepKind.WAIT -> ScenarioStep.Wait(session, state.ifBlank { null }, match, timeoutMs)
        StepKind.EXPECT -> ScenarioStep.Expect(session, direction, match, timeoutMs, expectation)
        StepKind.CLEAR -> ScenarioStep.ClearMessages(session)
        StepKind.RESET -> ScenarioStep.ResetSeqNum(session, sender, target)
    }

/**
 * The failing-run context handed from the session window by the failure → editor deep-link:
 * which tags failed (highlighted in the builder) and the raw message that failed them (a second
 * live preview target, so a matcher edit shows "would now pass" against the real failure).
 */
data class RunFailureContext(
    val failedTags: List<com.knapsack.fixtool.model.scenario.TagResult>,
    val actualRaw: String?,
)

/**
 * Where the selection lands after the step at [removed] is deleted: on the *same* step it was on.
 * Deleting a step above the selection shifts it down by one; deleting the selected step (or the last
 * one) falls back to the step now occupying that slot.
 */
internal fun selectionAfterRemoval(removed: Int, selected: Int, remaining: Int): Int =
    when {
        remaining == 0 -> -1
        removed < selected -> selected - 1
        else -> selected.coerceAtMost(remaining - 1)
    }

/**
 * The scenario editor: the same chronological, session-badged flow list as capture-review on the
 * left; the selected step's detail on the right (Send = a message-editor-style field grid with
 * dictionary names + enum dropdowns, Expect = bind-predicate + the matcher-chip expectation builder
 * with live preview and verify-generalizes). Inputs follow the app's slim-field convention.
 */
@Composable
fun ScenarioEditor(
    initial: Scenario,
    dictionary: FixDictionary?,
    sessionOptions: List<String>,
    onSave: (Scenario) -> Unit,
    onBack: () -> Unit,
    secondInstance: (String?, String?, String?) -> MessageView? = { _, _, _ -> null },
    /** Step index to open on (failure → editor deep-link); null opens on the first step. */
    focusStep: Int? = null,
    /** Failed-run context for [focusStep]'s builder; null outside a deep-link. */
    runFailure: RunFailureContext? = null,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial.name) }
    val steps = remember { mutableStateListOf<EditStep>().apply { addAll(initial.steps.map { it.toEditStep() }) } }
    // A stable id per step. The detail editor seeds its drafts once per step and must not re-seed on
    // every keystroke, so it is keyed — but an index is not an identity: delete a step above the
    // selection and a *different* step slides under the same index, and the stale drafts would then
    // be written onto it, silently overwriting assertions the user never opened.
    val stepIds = remember { mutableStateListOf<Long>().apply { addAll(initial.steps.indices.map { it.toLong() }) } }
    var nextStepId by remember { mutableStateOf(initial.steps.size.toLong()) }
    var selectedIdx by remember { mutableStateOf(focusStep ?: if (initial.steps.isEmpty()) -1 else 0) }

    val builtSteps = steps.map { it.toStep() }
    val stepVars = ScenarioAnnotations.annotate(builtSteps)
    val varColors = varColorMap(stepVars.flatMap { it.minted })
    val sessionColors = sessionColorMap(steps.mapNotNull { it.session } + sessionOptions)

    fun insertStep(kind: StepKind) {
        val newStep = if (kind == StepKind.SEND) EditStep(kind, fields = listOf(35 to "")) else EditStep(kind)
        val at = if (selectedIdx in steps.indices) selectedIdx + 1 else steps.size
        steps.add(at, newStep)
        stepIds.add(at, nextStepId++)
        selectedIdx = at
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AppTheme.Colors.textSecondary)
            }
            Text("Edit scenario", color = AppTheme.Colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            SlimLabeled("Name", modifier = Modifier.padding(start = 16.dp)) {
                SlimField(name, { name = it }, modifier = Modifier.width(240.dp).testTag("scenario-name"))
            }
            Row(modifier = Modifier.weight(1f)) {}
            SlimButton(
                text = "Save scenario",
                onClick = { onSave(initial.copy(name = name, steps = steps.map { it.toStep() })) },
                enabled = name.isNotBlank() && steps.isNotEmpty(),
                color = AppTheme.Colors.success,
                modifier = Modifier.testTag("editor-save"),
            )
        }
        if (initial.setup.isNotEmpty()) {
            Text(
                "Setup (runs first): " + initial.setup.joinToString("; ") { stepLabel(it, dictionary) + (it.sessionOrNull()?.let { s -> " [$s]" } ?: "") },
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp),
            )
        }
        // Deep-link orientation: say why the editor opened here and which tags to look at.
        if (focusStep != null && runFailure != null && runFailure.failedTags.isNotEmpty()) {
            val tagText = runFailure.failedTags.take(4).joinToString(", ") { t ->
                val n = dictionary?.getFieldName(t.tag)?.let { " $it" } ?: ""
                "${t.tag}$n"
            } + (if (runFailure.failedTags.size > 4) " +${runFailure.failedTags.size - 4} more" else "")
            Text(
                "Opened at step ${focusStep + 1} — the last run failed here on: $tagText. Failed rows are tinted below; the ▶ dot previews against that run's actual message.",
                color = AppTheme.Colors.error,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp),
            )
        }
        val stepListState = androidx.compose.foundation.lazy.rememberLazyListState(
            initialFirstVisibleItemIndex = (focusStep ?: 0).coerceAtLeast(0),
        )
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.weight(0.46f).fillMaxHeight()) {
                LazyColumn(state = stepListState, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    itemsIndexed(steps) { i, step ->
                        StepRow(
                            index = i,
                            step = step,
                            built = builtSteps.getOrNull(i),
                            dictionary = dictionary,
                            selected = i == selectedIdx,
                            sessionColor = sessionColors[step.session] ?: AppTheme.Colors.textDisabled,
                            vars = stepVars.getOrNull(i),
                            varColors = varColors,
                            canMoveUp = i > 0,
                            canMoveDown = i < steps.size - 1,
                            onSelect = { selectedIdx = i },
                            onMove = { delta ->
                                val to = i + delta
                                if (to in steps.indices) {
                                    val tmp = steps[i]
                                    steps[i] = steps[to]
                                    steps[to] = tmp
                                    val tmpId = stepIds[i]
                                    stepIds[i] = stepIds[to]
                                    stepIds[to] = tmpId
                                    if (selectedIdx == i) selectedIdx = to else if (selectedIdx == to) selectedIdx = i
                                }
                            },
                            onRemove = {
                                steps.removeAt(i)
                                stepIds.removeAt(i)
                                selectedIdx = selectionAfterRemoval(removed = i, selected = selectedIdx, remaining = steps.size)
                            },
                        )
                    }
                }
                AddStepBar(onAdd = ::insertStep)
            }
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(AppTheme.Colors.border))
            Column(modifier = Modifier.weight(0.54f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp)) {
                if (selectedIdx in steps.indices) {
                    // Keyed on the step's identity, not its index, so the detail editors re-seed when
                    // a different step comes under the selection — and only then.
                    key(stepIds[selectedIdx]) {
                        StepDetail(
                            index = selectedIdx,
                            step = steps[selectedIdx],
                            dictionary = dictionary,
                            sessionOptions = sessionOptions,
                            secondInstance = secondInstance,
                            // The failed-run context belongs to the focused step only — other steps
                            // matched different messages (or none).
                            runFailure = if (selectedIdx == focusStep) runFailure else null,
                            onChange = { steps[selectedIdx] = it },
                        )
                    }
                } else {
                    Text("Select a step to edit it.", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: EditStep,
    built: ScenarioStep?,
    dictionary: FixDictionary?,
    selected: Boolean,
    sessionColor: androidx.compose.ui.graphics.Color,
    vars: ScenarioAnnotations.StepVars?,
    varColors: Map<String, androidx.compose.ui.graphics.Color>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val bg = if (selected) AppTheme.Colors.selectionPrimary else AppTheme.Colors.surfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(bg).clickable(onClick = onSelect).padding(start = 8.dp, top = 2.dp, bottom = 2.dp).testTag("step-row-$index"),
    ) {
        RowIndex(index)
        SessionBadge(step.session, sessionColor, modifier = Modifier.width(120.dp))
        when (step.kind) {
            StepKind.SEND -> DirectionGlyph(outgoing = true, modifier = Modifier.padding(end = 6.dp))
            StepKind.EXPECT -> DirectionGlyph(outgoing = false, modifier = Modifier.padding(end = 6.dp))
            else -> Text("⚙", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
        }
        Text(
            text = built?.let { stepLabel(it, dictionary) } ?: step.kind.name.lowercase(),
            color = AppTheme.Colors.text,
            fontSize = 11.sp,
            maxLines = 1,
        )
        if (vars != null) VarBadges(vars.minted, vars.referenced, varColors, modifier = Modifier.padding(start = 8.dp))
        Row(modifier = Modifier.weight(1f)) {}
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(12.dp))
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Default.ArrowDownward, contentDescription = "Down", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(12.dp))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = AppTheme.Colors.error, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun AddStepBar(onAdd: (StepKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text("Insert:", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
        StepKind.values().forEach { kind ->
            SlimButton(kind.name.lowercase(), onClick = { onAdd(kind) }, modifier = Modifier.testTag("add-${kind.name.lowercase()}"))
        }
    }
}

@Composable
private fun StepDetail(
    index: Int,
    step: EditStep,
    dictionary: FixDictionary?,
    sessionOptions: List<String>,
    secondInstance: (String?, String?, String?) -> MessageView?,
    onChange: (EditStep) -> Unit,
    runFailure: RunFailureContext? = null,
) {
    Text("Step ${index + 1} — ${step.kind.name.lowercase()}", color = AppTheme.Colors.text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)) {
        SlimLabeled("Session") {
            SlimDropdown(
                value = step.session ?: ACTIVE_SESSION,
                options = listOf(ACTIVE_SESSION) + sessionOptions,
                onValueChange = { picked -> onChange(step.copy(session = picked?.takeIf { it != ACTIVE_SESSION })) },
                displayText = { it },
                modifier = Modifier.width(180.dp).testTag("session-dropdown"),
            )
        }
    }
    when (step.kind) {
        StepKind.SEND -> SendDetail(step, dictionary, onChange)
        StepKind.EXPECT -> ExpectDetail(step, dictionary, secondInstance, onChange, runFailure)
        StepKind.WAIT ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlimLabeled("State") {
                    SlimField(step.state, { onChange(step.copy(state = it)) }, monospace = true, modifier = Modifier.width(160.dp))
                }
                SlimLabeled("Timeout ms") {
                    SlimField(step.timeoutMs.toString(), { onChange(step.copy(timeoutMs = it.toLongOrNull() ?: step.timeoutMs)) }, monospace = true, modifier = Modifier.width(80.dp))
                }
                Text("e.g. LOGGED_ON", color = AppTheme.Colors.textDisabled, fontSize = 10.sp)
            }
        StepKind.CLEAR ->
            Text("Clears the session's message log (deterministic starting point).", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
        StepKind.RESET ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlimLabeled("Sender seq") {
                    SlimField(step.sender?.toString() ?: "", { onChange(step.copy(sender = it.toIntOrNull())) }, monospace = true, modifier = Modifier.width(70.dp))
                }
                SlimLabeled("Target seq") {
                    SlimField(step.target?.toString() ?: "", { onChange(step.copy(target = it.toIntOrNull())) }, monospace = true, modifier = Modifier.width(70.dp))
                }
            }
    }
}

/**
 * Send steps edit as a message-editor-style field grid: tag, dictionary field name, value, and —
 * for dictionary enum fields — a value dropdown with descriptions, so a user new to FIX picks
 * "1 (BUY)" instead of memorizing codes.
 */
@Composable
private fun SendDetail(step: EditStep, dictionary: FixDictionary?, onChange: (EditStep) -> Unit) {
    Text(
        "Fields — values may use \${...} expressions: \${id = UUID.randomUUID()} mints an id, \${id} reuses it, \${LocalDateTime.now()...} stamps send time.",
        color = AppTheme.Colors.textSecondary,
        fontSize = 10.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    val unknownTags = com.knapsack.fixtool.service.DictionaryLint.unknownTags(step.fields, dictionary)
    if (unknownTags.isNotEmpty()) {
        Text(
            "⚠ " + com.knapsack.fixtool.service.DictionaryLint.describe(unknownTags, step.fields, dictionary),
            color = AppTheme.Colors.warning,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    val allFields = remember(dictionary) { dictionary?.getAllFields() ?: emptyList() }
    step.fields.forEachIndexed { i, (tag, value) ->
        fun update(newTag: Int, newValue: String) {
            onChange(step.copy(fields = step.fields.toMutableList().apply { this[i] = newTag to newValue }))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            // Tag with dictionary autocomplete: type "ClOrd" or "11" and pick — no code memorizing.
            SlimTagPicker(
                tag = tag,
                fields = allFields,
                onPick = { picked -> update(picked, value) },
                modifier = Modifier.width(64.dp),
                fieldTestTag = "send-tag-$i",
            )
            FieldNameCell(tag, dictionary)
            SlimField(
                value = value,
                onValueChange = { text -> update(tag, text) },
                monospace = true,
                textColor = AppTheme.Colors.fieldValue,
                tintBlank = true,
                modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("send-value-$i"),
            )
            ValueHelpCell(tag, value, dictionary, onPick = { picked -> update(tag, picked) })
            IconButton(
                onClick = { onChange(step.copy(fields = step.fields.toMutableList().apply { removeAt(i) })) },
                modifier = Modifier.size(22.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove field", tint = AppTheme.Colors.error, modifier = Modifier.size(12.dp))
            }
        }
    }
    SlimButton("+ field", onClick = { onChange(step.copy(fields = step.fields + (0 to ""))) }, modifier = Modifier.padding(top = 4.dp))
}

/** Dictionary field name, colored like the message editor (orange for repeating-group tags). */
@Composable
private fun FieldNameCell(tag: Int, dictionary: FixDictionary?) {
    val isGroup = dictionary?.isGroupTag(tag) == true
    Text(
        text = dictionary?.getFieldName(tag) ?: "",
        color = if (isGroup) AppTheme.Colors.groupTag else AppTheme.Colors.fieldName,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier.width(120.dp).padding(start = 4.dp),
    )
}

/**
 * The per-field help column (message-editor pattern): an enum dropdown ("1 (BUY)") when the
 * dictionary defines values for the tag, the value's description for a plain enum value, or a hint
 * that the value is a runtime expression.
 */
@Composable
private fun ValueHelpCell(tag: Int, value: String, dictionary: FixDictionary?, onPick: (String) -> Unit) {
    val enumValues = if (dictionary?.hasFieldValues(tag) == true) dictionary.getFieldEnumValues(tag) else emptyList()
    when {
        value.contains("\${") ->
            Text("expression", color = AppTheme.Colors.info, fontSize = 10.sp, modifier = Modifier.width(150.dp).padding(start = 4.dp))
        enumValues.isNotEmpty() ->
            SlimDropdown(
                value = value.ifBlank { null },
                options = enumValues.map { it.first },
                onValueChange = { picked -> picked?.let(onPick) },
                displayText = { v -> enumValues.firstOrNull { it.first == v }?.second?.let { "$v ($it)" } ?: v },
                placeholder = "pick…",
                modifier = Modifier.width(150.dp).padding(start = 4.dp),
            )
        else -> {
            val description = dictionary?.getFieldValueDescription(tag, value)?.takeIf { it != value } ?: ""
            Text(description, color = AppTheme.Colors.textSecondary, fontSize = 10.sp, maxLines = 1, modifier = Modifier.width(150.dp).padding(start = 4.dp))
        }
    }
}

@Composable
private fun ExpectDetail(
    step: EditStep,
    dictionary: FixDictionary?,
    secondInstance: (String?, String?, String?) -> MessageView?,
    onChange: (EditStep) -> Unit,
    runFailure: RunFailureContext? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SlimLabeled("Direction") {
            SlimDropdown(
                value = step.direction,
                options = listOf("in", "out"),
                onValueChange = { picked -> picked?.let { onChange(step.copy(direction = it)) } },
                displayText = { if (it == "out") "outgoing" else "incoming" },
                modifier = Modifier.width(100.dp),
            )
        }
        SlimLabeled("Timeout ms") {
            SlimField(step.timeoutMs.toString(), { onChange(step.copy(timeoutMs = it.toLongOrNull() ?: step.timeoutMs)) }, monospace = true, modifier = Modifier.width(80.dp))
        }
    }
    MatchEditor(step.match, dictionary, onChange = { onChange(step.copy(match = it)) })
    val messageType = step.expectation.messageType ?: step.match?.messageType ?: ""
    if (step.expectation.fields.isEmpty() && step.expectation.golden == null) {
        Text(
            "No asserted tags yet. Assertions are normally captured from a live response; this step will only check that a matching message arrives.",
            color = AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        val drafts = remember { ExpectationDrafts.fromExpectation(step.expectation, dictionary) }
        val goldenView = remember { step.expectation.golden?.let { RawMessageView(it, dictionary) } }
        val second = remember { secondInstance(step.session, messageType.ifBlank { null }, step.expectation.golden) }
        val golden = step.expectation.golden
        val failedView = remember { runFailure?.actualRaw?.let { RawMessageView(it, dictionary) } }
        val failedKeys = remember { runFailure?.failedTags?.map { it.tag to it.path }?.toSet() ?: emptySet() }
        ExpectationBuilder(
            messageType = messageType,
            initialFields = drafts,
            goldenView = goldenView,
            secondView = second,
            initialMode = step.expectation.mode,
            failedView = failedView,
            failedKeys = failedKeys,
            onChange = { updated -> onChange(step.copy(expectation = updated.copy(golden = golden))) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Edits the Expect bind predicate: which arriving message this step consumes — by message type plus
 * tag=value constraints (AND), e.g. ExecType 150=F and OrdStatus 39=1 to pick the first partial.
 * With a dictionary, the message type and enum constraint values are picked from named dropdowns.
 */
@Composable
private fun MatchEditor(match: MatchPredicate?, dictionary: FixDictionary?, onChange: (MatchPredicate?) -> Unit) {
    fun push(messageType: String?, fields: List<TagValue>) {
        val normalized = MatchPredicate(messageType?.ifBlank { null }, match?.direction, fields)
        onChange(if (normalized.messageType == null && normalized.fields.isEmpty()) null else normalized)
    }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "Binds to — which arriving message this step asserts (consumed in order; add tag=value constraints for e.g. partial fills):",
            color = AppTheme.Colors.textSecondary,
            fontSize = 10.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            SlimLabeled("Msg type") {
                MsgTypePicker(match?.messageType, dictionary) { picked -> push(picked, match?.fields ?: emptyList()) }
            }
            SlimButton("+ constraint", onClick = { push(match?.messageType, (match?.fields ?: emptyList()) + TagValue(0, "")) })
        }
        val allFields = remember(dictionary) { dictionary?.getAllFields() ?: emptyList() }
        (match?.fields ?: emptyList()).forEachIndexed { i, tv ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                SlimTagPicker(
                    tag = tv.tag,
                    fields = allFields,
                    onPick = { picked -> push(match?.messageType, match!!.fields.toMutableList().apply { this[i] = TagValue(picked, tv.value) }) },
                    modifier = Modifier.width(64.dp),
                )
                FieldNameCell(tv.tag, dictionary)
                ConstraintValueCell(tv, dictionary) { newValue ->
                    push(match?.messageType, match!!.fields.toMutableList().apply { this[i] = TagValue(tv.tag, newValue) })
                }
                IconButton(onClick = { push(match?.messageType, match!!.fields.toMutableList().apply { removeAt(i) }) }, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove constraint", tint = AppTheme.Colors.error, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/** Enum-aware constraint value: a named dropdown when the dictionary knows the values. */
@Composable
private fun ConstraintValueCell(tv: TagValue, dictionary: FixDictionary?, onPick: (String) -> Unit) {
    val enumValues = if (dictionary?.hasFieldValues(tv.tag) == true) dictionary.getFieldEnumValues(tv.tag) else emptyList()
    if (enumValues.isNotEmpty()) {
        SlimDropdown(
            value = tv.value.ifBlank { null },
            options = enumValues.map { it.first },
            onValueChange = { picked -> picked?.let(onPick) },
            displayText = { v -> enumValues.firstOrNull { it.first == v }?.second?.let { "$v ($it)" } ?: v },
            placeholder = "pick…",
            modifier = Modifier.width(170.dp).padding(start = 4.dp),
        )
    } else {
        SlimField(tv.value, onPick, monospace = true, tintBlank = true, modifier = Modifier.width(170.dp).padding(start = 4.dp))
    }
}

/**
 * Message-type picker: with a dictionary it is a dropdown of tag-35 enum values with their names
 * ("8 (EXECUTION_REPORT)"); without one it falls back to a plain field.
 */
@Composable
private fun MsgTypePicker(current: String?, dictionary: FixDictionary?, onPick: (String?) -> Unit) {
    val types = dictionary?.getFieldEnumValues(35) ?: emptyList()
    if (types.isEmpty()) {
        SlimField(current ?: "", { onPick(it) }, monospace = true, modifier = Modifier.width(110.dp).testTag("match-type"))
    } else {
        // Keep an unknown/custom current value pickable so it still displays.
        val options = if (current != null && types.none { it.first == current }) listOf(current) + types.map { it.first } else types.map { it.first }
        SlimDropdown(
            value = current,
            options = options,
            onValueChange = onPick,
            displayText = { v -> types.firstOrNull { it.first == v }?.second?.let { "$v ($it)" } ?: v },
            placeholder = "any message type",
            allowUnselect = true,
            modifier = Modifier.width(220.dp).testTag("match-type"),
        )
    }
}
