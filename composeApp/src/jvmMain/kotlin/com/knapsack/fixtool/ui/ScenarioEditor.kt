// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList", "TooManyFunctions")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchOp
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.StepOrigin
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.TrafficMode
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.ScenarioAnnotations
import com.knapsack.fixtool.service.mintName

/** Label used in session dropdowns for "no explicit session" (the runner uses the active one). */
private const val ACTIVE_SESSION = "(active session)"

/** The step kinds the editor can hold. */
enum class StepKind { SEND, WAIT, EXPECT, CLEAR, RESET }

/**
 * A step under edit. Unlike the old builder draft, this round-trips **everything** the model holds —
 * the Expect/Wait `match` predicate, the expectation's `golden`, and the step's [ScenarioStep.stepId]
 * survive load → edit → save.
 *
 * The id is here because it was not, and the omission put the identifier back where Phase 0 took it
 * from: `toStep()` built every step with a blank id, so Save handed `ScenarioService` a scenario with
 * none, and `withIds()` — finding nothing to claim — minted all of them from `(scenario, phase, index)`.
 * A step that had not moved got its own id back by luck of the determinism; a step that *had* moved took
 * the id of whatever now sat at its old index, and the run that failed on it no longer knew which step
 * it meant. A step the author adds still carries the blank, which is what `withIds()` mints for — and
 * only after every existing step has claimed its own.
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
    val stepId: String = "",
    /** See [ScenarioStep.origin]. Carried through the editor — a save must not launder a paste into LIVE. */
    val origin: StepOrigin = StepOrigin.LIVE,
    /** See [ScenarioStep.muted]. */
    val muted: Boolean = false,
)

fun ScenarioStep.toEditStep(): EditStep =
    when (this) {
        is ScenarioStep.Send ->
            EditStep(StepKind.SEND, session, fields = FixMessageHelper.parseFixMessage(raw), stepId = stepId, origin = origin, muted = muted)
        is ScenarioStep.Wait ->
            EditStep(StepKind.WAIT, session, state = state ?: "", match = match, timeoutMs = timeoutMs, stepId = stepId, origin = origin, muted = muted)
        is ScenarioStep.Expect ->
            EditStep(
                StepKind.EXPECT,
                session,
                match = match,
                direction = direction,
                timeoutMs = timeoutMs,
                expectation = expectation,
                stepId = stepId,
                origin = origin,
                muted = muted,
            )
        is ScenarioStep.ClearMessages -> EditStep(StepKind.CLEAR, session, stepId = stepId, origin = origin, muted = muted)
        is ScenarioStep.ResetSeqNum ->
            EditStep(StepKind.RESET, session, sender = sender, target = target, stepId = stepId, origin = origin, muted = muted)
    }

fun EditStep.toStep(): ScenarioStep =
    when (kind) {
        StepKind.SEND -> ScenarioStep.Send(FixMessageHelper.joinFields(fields), session, stepId, origin, muted)
        StepKind.WAIT -> ScenarioStep.Wait(session, state.ifBlank { null }, match, timeoutMs, stepId, origin, muted)
        StepKind.EXPECT -> ScenarioStep.Expect(session, direction, match, timeoutMs, expectation, stepId, origin, muted)
        StepKind.CLEAR -> ScenarioStep.ClearMessages(session, stepId, origin, muted)
        StepKind.RESET -> ScenarioStep.ResetSeqNum(session, sender, target, stepId, origin, muted)
    }

/**
 * The scenario as this editor would emit it having changed nothing — the seed a document's *dirty* flag is
 * measured against.
 *
 * It is not the file. `EditStep` re-writes a Send's raw on the way through (`parseFixMessage` → re-join with
 * `|`), so a scenario read from disk and not touched can already differ from itself as the editor holds it.
 * Compare a draft against the file and every tab opens dirty; compare it against this and a tab is dirty
 * exactly when the author has changed something. One function, so the editor and its host cannot come to
 * disagree about what "untouched" means.
 */
fun Scenario.asEditorSeed(): Scenario = copy(steps = steps.map { it.toEditStep().toStep() })

/**
 * The failing-run context handed from the session window by the failure → editor deep-link:
 * which tags failed (highlighted in the builder) and the raw message that failed them (a second
 * live preview target, so a matcher edit shows "would now pass" against the real failure).
 */
data class RunFailureContext(
    val failedTags: List<com.knapsack.fixtool.model.scenario.TagResult>,
    val actualRaw: String?,
    /** When the message arrived — the instant temporal rows are judged against (the reference's anchor in the diff). */
    val actualAt: java.time.Instant? = null,
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
    /** Step index the deep-link landed on (the step a run failed at); null outside one. Not the selection. */
    focusStep: Int? = null,
    /**
     * Opens the diff for a step, by its id — the one surface that authors or repairs an assertion. The
     * tag, when given, is the row the author clicked: the diff opens scrolled to it.
     */
    onOpenDiff: ((String, Int?) -> Unit)? = null,
    /**
     * The scenario as it now stands, emitted on every change.
     *
     * The host is a *tab*, and only the active tab is composed — so an edit that lived only in this
     * composable's `remember`s would be destroyed by a glance at the session grid. The host holds the draft;
     * this is how it gets it. See [ScenarioDoc].
     */
    onChange: (Scenario) -> Unit = {},
    /** Where the cursor sits, hoisted for the same reason. Seeded from here; reported through [onSelectStep]. */
    selectedStep: Int? = null,
    onSelectStep: (Int) -> Unit = {},
    /** The list/detail divider, hoisted for the same reason again. See [ScenarioDoc.Editor.split]. */
    split: Float = 0.60f,
    onSplitChange: (Float) -> Unit = {},
    /** The last run's scope, when the run report stands for THIS scenario — the strip shows the values. */
    runVariables: List<ScenarioVariable> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial.name) }
    var traffic by remember { mutableStateOf(initial.traffic) }
    val steps = remember { mutableStateListOf<EditStep>().apply { addAll(initial.steps.map { it.toEditStep() }) } }
    // A stable id per step. The detail editor seeds its drafts once per step and must not re-seed on
    // every keystroke, so it is keyed — but an index is not an identity: delete a step above the
    // selection and a *different* step slides under the same index, and the stale drafts would then
    // be written onto it, silently overwriting assertions the user never opened.
    val stepIds = remember { mutableStateListOf<Long>().apply { addAll(initial.steps.indices.map { it.toLong() }) } }
    var nextStepId by remember { mutableStateOf(initial.steps.size.toLong()) }
    var selectedIdx by remember {
        mutableStateOf(selectedStep ?: focusStep ?: if (initial.steps.isEmpty()) -1 else 0)
    }

    val builtSteps = steps.map { it.toStep() }
    val built = initial.copy(name = name, steps = builtSteps, traffic = traffic)
    // By value, not by every recomposition: an untouched editor emits its seed once and then stays quiet.
    LaunchedEffect(built) { onChange(built) }

    val stepVars = ScenarioAnnotations.annotate(builtSteps)
    val varColors = varColorMap(stepVars.flatMap { it.minted })
    val sessionColors = sessionColorMap(steps.mapNotNull { it.session } + sessionOptions)

    fun select(index: Int) {
        selectedIdx = index
        onSelectStep(index)
    }

    fun insertStep(kind: StepKind) {
        val newStep = if (kind == StepKind.SEND) EditStep(kind, fields = listOf(35 to "")) else EditStep(kind)
        val at = if (selectedIdx in steps.indices) selectedIdx + 1 else steps.size
        steps.add(at, newStep)
        stepIds.add(at, nextStepId++)
        select(at)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // The register every pane header speaks (Message Details: 11sp, plain weight) — this row wore the
        // app's one 14sp SemiBold, a heading from some other tool. Padding gives the row the same breathing
        // room as the header strips it now matches.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
        ) {
            Text("Edit scenario", color = AppTheme.Colors.text, fontSize = 11.sp)
            SlimLabeled("Name", modifier = Modifier.padding(start = 16.dp)) {
                SlimField(name, { name = it }, modifier = Modifier.width(280.dp).testTag("scenario-name"))
            }
            TrafficChip(traffic, onToggle = { traffic = if (traffic == TrafficMode.OPEN) TrafficMode.STRICT else TrafficMode.OPEN })
            Row(modifier = Modifier.weight(1f)) {}
            // Setup used to stand on its own row below the header, spending a whole line on one quiet
            // sentence. It rides in the header's empty span now — a reclaimed line — beside Save, where
            // the wipe warning sits next to the button that launches the run it warns about.
            if (initial.setup.isNotEmpty()) {
                SetupSummary(initial.setup, dictionary, modifier = Modifier.padding(end = 12.dp))
            }
            SlimButton(
                text = "Save scenario",
                onClick = { onSave(built) },
                enabled = name.isNotBlank() && steps.isNotEmpty(),
                color = AppTheme.Colors.success,
                modifier = Modifier.testTag("editor-save"),
            )
        }
        // The rule every other pane header wears (Message Details, Connection, Settings). Without it the
        // toolbar bled straight into the variables strip and the step list — five stacked rows, no seam.
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        // The scenario's variables, on one line: every minted name (with the value the last run left, when
        // there is one), and — in warning colors — every name referenced but never minted, which the engine
        // would leave literal on the wire. The per-step badges say who touches a name; this says what it IS.
        // Judged over the steps that will RUN: a muted Send's mint does not happen, so an active reference
        // to it is exactly the leaves-a-literal problem this strip warns about.
        val activeSteps = builtSteps.filterNot { it.muted }
        val mintedNames = builtSteps.zip(stepVars).filterNot { (s, _) -> s.muted }.flatMap { (_, v) -> v.minted }.distinct()
        // Muted mints stay RESERVED even while they do not run: a fresh mint that took a parked step's
        // name would collide with it the moment the step is unmuted.
        val allMintedNames = stepVars.flatMap { it.minted }.distinct()
        val unminted = ScenarioAnnotations.unminted(activeSteps)
        val runValues = runVariables.associate { it.name to it.value }
        VariablesStrip(
            chips =
                mintedNames.map { name ->
                    VariableChipData(
                        name = name,
                        value = runValues[name],
                        tooltip =
                            runValues[name]?.let { "\${$name} = $it (last run)" }
                                ?: "\${$name} — values appear here after a run",
                    )
                } +
                    unminted.map { name ->
                        VariableChipData(
                            name = name,
                            value = null,
                            warning = true,
                            tooltip =
                                "\${$name} is referenced but no step mints it — the engine leaves an unknown " +
                                    "\${...} literal on the wire, so this is almost certainly a typo. Mint it in " +
                                    "a Send (\${$name = ...}) or fix the reference.",
                        )
                    },
            colors = varColors,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
        )
        // Deep-link orientation: say why the editor opened where it did. The failure itself is repaired in the
        // diff, which is its own tab — this list is the scenario's *shape*, not its assertions.
        if (focusStep != null) {
            Text(
                "Opened at step ${focusStep + 1} — the last run failed here. Its assertions are repaired in the diff.",
                color = AppTheme.Colors.error,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp),
            )
        }
        val stepListState =
            androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = (focusStep ?: 0).coerceAtLeast(0),
            )
        // Draggable, seeded from the hoisted value so the author's drag survives a tab switch. 60/40 by
        // default: the step list is a column of sentences (labels, session badges, var chips) and was
        // truncating them all at the old 34%; the detail pane reflows, and the divider gives room back.
        var splitState by remember { mutableStateOf(split.coerceIn(0.18f, 0.80f)) }
        var paneWidth by remember { mutableStateOf(0) }
        Row(modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { paneWidth = it.width }) {
            Column(modifier = Modifier.weight(splitState).fillMaxHeight()) {
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
                            onSelect = { select(i) },
                            onMove = { delta ->
                                val to = i + delta
                                if (to in steps.indices) {
                                    val tmp = steps[i]
                                    steps[i] = steps[to]
                                    steps[to] = tmp
                                    val tmpId = stepIds[i]
                                    stepIds[i] = stepIds[to]
                                    stepIds[to] = tmpId
                                    if (selectedIdx == i) {
                                        select(to)
                                    } else if (selectedIdx == to) {
                                        select(i)
                                    }
                                }
                            },
                            onToggleMute = { steps[i] = steps[i].copy(muted = !steps[i].muted) },
                            onRemove = {
                                steps.removeAt(i)
                                stepIds.removeAt(i)
                                select(selectionAfterRemoval(removed = i, selected = selectedIdx, remaining = steps.size))
                            },
                        )
                    }
                }
                AddStepBar(onAdd = ::insertStep)
            }
            PaneDivider(onDrag = { dx ->
                if (paneWidth > 0) {
                    splitState = (splitState + dx / paneWidth).coerceIn(0.18f, 0.80f)
                    onSplitChange(splitState)
                }
            })
            Column(
                modifier =
                    Modifier
                        .weight(1f - splitState)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp),
            ) {
                if (selectedIdx in steps.indices) {
                    // Keyed on the step's identity, not its index, so the detail editors re-seed when
                    // a different step comes under the selection — and only then.
                    key(stepIds[selectedIdx]) {
                        StepDetail(
                            index = selectedIdx,
                            step = steps[selectedIdx],
                            dictionary = dictionary,
                            sessionOptions = sessionOptions,
                            sessionColor = sessionColors[steps[selectedIdx].session] ?: AppTheme.Colors.textDisabled,
                            onChange = { steps[selectedIdx] = it },
                            onOpenDiff = onOpenDiff?.let { open -> { tag -> open(steps[selectedIdx].stepId, tag) } },
                            takenNames = allMintedNames.toSet(),
                        )
                    }
                } else {
                    Text("Select a step to edit it.", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * The setup's one-line summary, worn in the header row. What setup *does* is a lesson paid once, so the
 * step enumeration folds behind the ⓘ (the app's one idiom for that); the wipe warning is the load-bearing
 * part and stays on the line, at a whisper. Extracted so the header row reads as a row, not a paragraph.
 */
@Composable
private fun SetupSummary(setup: List<ScenarioStep>, dictionary: FixDictionary?, modifier: Modifier = Modifier) {
    val clears = setup.any { it is ScenarioStep.ClearMessages }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            "setup: ${setup.size} ${if (setup.size == 1) "step" else "steps"} before the flow" +
                (if (clears) " · wipes the session log each run" else ""),
            color = AppTheme.Colors.textDisabled,
            fontSize = 9.5.sp,
            modifier = Modifier.testTag("setup-summary"),
        )
        HintIcon(
            "Setup runs before the steps on every run: " +
                setup.joinToString("; ") { stepLabel(it, dictionary) + (it.sessionOrNull()?.let { s -> " [$s]" } ?: "") } +
                (if (clears) ". Clearing gives each run a deterministic starting point — and erases the session's message log." else "."),
            modifier = Modifier.padding(start = 5.dp).testTag("setup-help"),
        )
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
    onToggleMute: () -> Unit,
    onRemove: () -> Unit,
) {
    val bg = if (selected) AppTheme.Colors.selectionPrimary else AppTheme.Colors.surfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    bg,
                ).clickable(onClick = onSelect)
                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                .testTag("step-row-$index"),
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
            // A muted step keeps its row and dims its voice: the shape stays readable, the state is plain.
            color = if (step.muted) AppTheme.Colors.textDisabled else AppTheme.Colors.text,
            fontSize = 11.sp,
            maxLines = 1,
        )
        if (step.muted) MutedChip()
        if (vars != null) VarBadges(vars.minted, vars.referenced, varColors, modifier = Modifier.padding(start = 8.dp))
        Row(modifier = Modifier.weight(1f)) {}
        TooltipIconButton(
            tooltip = if (step.muted) "Unmute — the runner executes this step again" else "Mute — keep the step, but skip it on every run",
            onClick = onToggleMute,
            modifier = Modifier.size(22.dp).testTag("mute-step-$index"),
        ) {
            Icon(
                if (step.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = if (step.muted) "Unmute step" else "Mute step",
                tint = if (step.muted) AppTheme.Colors.warning else AppTheme.Colors.textSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
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

/**
 * The parked state, worn where the mode chip pattern already lives. The tooltip is the lesson: muting is
 * skip-without-delete, and the un-park is one click away in the row.
 */
@Composable
private fun MutedChip() {
    AppTooltip("The runner skips this step — nothing is sent, nothing is asserted. Its place, session and assertions are kept; unmute with the speaker icon.") {
        Text(
            "MUTED",
            color = AppTheme.Colors.warning,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier =
                Modifier
                    .padding(start = 8.dp)
                    .border(1.dp, AppTheme.Colors.warning.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
        )
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

/**
 * **One bounded concern of a step form** — the paste sheet's frame, extracted: a surfaceVariant sheet, a
 * 9sp uppercase header (the register the diff surface already speaks — "EXPECTATION (EDITABLE)",
 * "FIX PLAN — …"), and one left edge for everything inside. The header names the concern so the fields
 * do not have to: inside a sheet titled RECEIVES, a dropdown reading "incoming" needs no "Direction" label,
 * which is what lets every control start at the same x instead of wherever its label happened to end.
 */
@Composable
private fun DetailSection(
    header: String,
    dim: String? = null,
    headerTrailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(4.dp))
                .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 7.dp)) {
            Text(header, color = AppTheme.Colors.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            if (dim != null) {
                Text(" $dim", color = AppTheme.Colors.textDisabled, fontSize = 9.sp, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            headerTrailing()
        }
        content()
    }
}

/** The quiet in-row words that replaced the field labels: "on", "waits up to", "position". */
@Composable
private fun QuietWord(text: String) {
    Text(text, color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
}

/** The step's session, as the same colored dot the step list badges it with. */
@Composable
private fun SessionDot(color: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
}

/**
 * The scenario's stream-level mode, worn (and toggled) in the editor header. The same STRICT/OPEN
 * dress as the per-step [ModeChipMini] — deliberately, they are the same idea at two levels — with a
 * "TRAFFIC" prefix so the header chip cannot be misread as some step's expectation mode.
 */
@Composable
private fun TrafficChip(mode: TrafficMode, onToggle: () -> Unit) {
    val strict = mode == TrafficMode.STRICT
    val colour = if (strict) AppTheme.Colors.warning else AppTheme.Colors.info
    AppTooltip(
        if (strict) {
            "Traffic is STRICT: after the last step (plus a 1s settle window) any incoming application " +
                "message no expect bound fails the run. Session admin (heartbeats, test requests, logon) " +
                "is exempt. Click for OPEN."
        } else {
            "Traffic is OPEN: incoming messages no expect binds are ignored — the venue may say more than " +
                "the scenario checks. Click for STRICT, which also asserts the venue sent nothing else."
        },
    ) {
        Text(
            if (strict) "TRAFFIC STRICT" else "TRAFFIC OPEN",
            color = colour,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier =
                Modifier
                    .padding(start = 12.dp)
                    .border(1.dp, colour.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .testTag("traffic-mode-chip"),
        )
    }
}

/** The expectation's mode, worn as a chip beside the title — displayed here, edited in the diff. */
@Composable
private fun ModeChipMini(mode: MatchMode) {
    val strict = mode == MatchMode.STRICT
    val colour = if (strict) AppTheme.Colors.warning else AppTheme.Colors.info
    Text(
        if (strict) "STRICT" else "OPEN",
        color = colour,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier =
            Modifier
                .padding(start = 8.dp)
                .border(1.dp, colour.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// One column spec for every tag·name grid on this screen: the bind constraints and the assertions preview
// show the same shape of data, and two grids that disagree about their columns refuse to read as kin.
private val TAG_COL = 64.dp
private val NAME_COL = 128.dp

// The Send field grid mirrors the Message Editor's field grid (MessageEditorPanel) so the two editors read
// as one tool: a fixed tag, a fixed name (FieldNameCell's 120dp default), a fixed-width value, and one
// flexible help column beside the value — never a flexible value.
private val SEND_TAG_COL = 48.dp
private val SEND_VALUE_COL = 180.dp

// The mint button's reserved slot in a Send field row. Kept at a constant width whether or not the button
// shows, so a row whose value already mints (and therefore has no button) does not stretch its value input
// wider than its neighbours by the width of the missing control. See [SendDetail].
private val MINT_SLOT_WIDTH = 28.dp

/** The step's timeout as the seconds a human reads, while the model keeps milliseconds. */
private fun secondsText(ms: Long): String =
    if (ms % 1000L == 0L) {
        (ms / 1000L).toString()
    } else {
        java.math.BigDecimal(ms).movePointLeft(3).stripTrailingZeros().toPlainString()
    }

@Composable
private fun StepDetail(
    index: Int,
    step: EditStep,
    dictionary: FixDictionary?,
    sessionOptions: List<String>,
    sessionColor: androidx.compose.ui.graphics.Color,
    onChange: (EditStep) -> Unit,
    /** Opens this step's diff, when it is an Expect — at the clicked row's tag when there is one. */
    onOpenDiff: ((Int?) -> Unit)? = null,
    /** Names the scenario already mints anywhere — a fresh mint must not silently re-assign one. */
    takenNames: Set<String> = emptySet(),
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            // The Expect title says what the step expects, not merely that it is one — the same label the
            // step list shows, so the detail pane and the list cannot describe one step two ways.
            if (step.kind == StepKind.EXPECT) {
                "Step ${index + 1} — ${stepLabel(step.toStep(), dictionary)}"
            } else {
                "Step ${index + 1} — ${step.kind.name.lowercase()}"
            },
            color = AppTheme.Colors.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (step.kind == StepKind.EXPECT) ModeChipMini(step.expectation.mode)
        if (step.muted) MutedChip()
    }
    // The Expect form carries its session inside RECEIVES — the transport facts, together. Every other kind
    // keeps the plain labeled row until it adopts the same sections, or the editor speaks two dialects.
    if (step.kind != StepKind.EXPECT) {
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
    }
    when (step.kind) {
        StepKind.SEND -> SendDetail(step, dictionary, onChange, takenNames)
        StepKind.EXPECT -> ExpectDetail(step, dictionary, sessionOptions, sessionColor, onChange, onOpenDiff)
        StepKind.WAIT ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlimLabeled("State") {
                    // The example lives inside the empty field, where an example belongs.
                    SlimField(
                        step.state,
                        { onChange(step.copy(state = it)) },
                        monospace = true,
                        placeholder = "e.g. LOGGED_ON",
                        modifier = Modifier.width(160.dp),
                    )
                }
                SlimLabeled("Timeout ms") {
                    SlimField(step.timeoutMs.toString(), {
                        onChange(step.copy(timeoutMs = it.toLongOrNull() ?: step.timeoutMs))
                    }, monospace = true, modifier = Modifier.width(80.dp))
                }
            }
        StepKind.CLEAR ->
            Text("Clears the session's message log (deterministic starting point).", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
        StepKind.RESET ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlimLabeled("Sender seq") {
                    SlimField(
                        step.sender?.toString() ?: "",
                        { onChange(step.copy(sender = it.toIntOrNull())) },
                        monospace = true,
                        modifier = Modifier.width(70.dp),
                    )
                }
                SlimLabeled("Target seq") {
                    SlimField(
                        step.target?.toString() ?: "",
                        { onChange(step.copy(target = it.toIntOrNull())) },
                        monospace = true,
                        modifier = Modifier.width(70.dp),
                    )
                }
            }
    }
}

/**
 * Send steps edit as a message-editor-style field grid: tag, dictionary field name, value, and —
 * for dictionary enum fields — a value dropdown with descriptions, so a user new to FIX picks
 * "1 (BUY)" instead of memorizing codes.
 */
/** A value that already mints (`${x = …}`) — the mint button would wrap a mint in a mint. */
private val ALREADY_MINTS = Regex("""\$\{\s*\w+\s*=""")

/** A value that is entirely ONE `${…}` expression — minting wraps the expression, not the text. */
private val WHOLE_EXPRESSION = Regex("""^\$\{([^}]+)}$""")

/**
 * The same bytes on the wire, now with a name: `EURUSD` → `${sym = "EURUSD"}`, and a value that is
 * already an expression wraps as an assignment — `${LocalDateTime.now()}` → `${ts = LocalDateTime.now()}`.
 * The assignment evaluates to exactly what the value evaluated to before, so minting a send field is
 * never a change to what the venue receives — only to what later steps can say about it.
 */
internal fun mintFieldValue(value: String, name: String): String {
    val inner = WHOLE_EXPRESSION.matchEntire(value.trim())?.groupValues?.get(1)?.trim()
    return if (inner != null) {
        "\${$name = $inner}"
    } else {
        "\${$name = \"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"
    }
}

@Composable
private fun SendDetail(
    step: EditStep,
    dictionary: FixDictionary?,
    onChange: (EditStep) -> Unit,
    takenNames: Set<String> = emptySet(),
) {
    // The expressions lesson costs one glyph, not a standing paragraph (same fold as capture's ⓘ).
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Text("Fields", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
        HintIcon(
            "Values may use \${...} expressions: \${id = uuid:20} mints a 20-char id, \${id} reuses it, " +
                "\${now} stamps send time (\${now:yyyyMMdd} for a custom format). Full Kotlin expressions " +
                "like \${UUID.randomUUID()} work too.",
            modifier = Modifier.testTag("send-fields-help"),
        )
    }
    val unknownTags =
        com.knapsack.fixtool.service.DictionaryLint
            .unknownTags(step.fields, dictionary)
    if (unknownTags.isNotEmpty()) {
        Text(
            "⚠ " +
                com.knapsack.fixtool.service.DictionaryLint
                    .describe(unknownTags, step.fields, dictionary),
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
            // Same column rhythm as the Message Editor's field grid (MessageEditorPanel): tag, name, a
            // fixed-width value, then one flexible help column — so the two field editors read as one tool.
            // Tag with dictionary autocomplete: type "ClOrd" or "11" and pick — no code memorizing.
            SlimTagPicker(
                tag = tag,
                fields = allFields,
                onPick = { picked -> update(picked, value) },
                modifier = Modifier.width(SEND_TAG_COL),
                fieldTestTag = "send-tag-$i",
            )
            FieldNameCell(tag, dictionary)
            // Fixed width, like the Message Editor's value column — the column that flexes is the help beside
            // it, not the value. As weight(1f) the value stretched into a box the width of the whole pane.
            SlimField(
                value = value,
                onValueChange = { text -> update(tag, text) },
                monospace = true,
                textColor = AppTheme.Colors.fieldValue,
                tintBlank = true,
                modifier = Modifier.width(SEND_VALUE_COL).padding(start = 4.dp).testTag("send-value-$i"),
            )
            ValueHelpCell(tag, value, dictionary, modifier = Modifier.weight(1f).padding(start = 4.dp), onPick = { picked -> update(tag, picked) })
            // Mint-at-send: give this value a name — same bytes on the wire, but later expects can
            // reference-check the echo and later sends can reuse it. Hidden once the value mints — but the
            // slot is kept at a constant width, so a row whose value already mints (${id = uuid:20}) does
            // not stretch its value field wider than its neighbours by the width of the missing button.
            Box(modifier = Modifier.width(MINT_SLOT_WIDTH), contentAlignment = Alignment.Center) {
                if (value.isNotBlank() && !ALREADY_MINTS.containsMatchIn(value)) {
                    val name = mintName(tag, dictionary?.getFieldName(tag), takenNames)
                    AppTooltip(
                        "Mint as \${$name} — the wire bytes do not change, but the value now has a name: an " +
                            "expect can assert the echo (reference \${$name}) and a later send can reuse it.",
                    ) {
                        SlimButton(
                            "●",
                            onClick = { update(tag, mintFieldValue(value, name)) },
                            color = AppTheme.Colors.info,
                            modifier = Modifier.testTag("send-mint-$i"),
                        )
                    }
                }
            }
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
private fun FieldNameCell(tag: Int, dictionary: FixDictionary?, width: androidx.compose.ui.unit.Dp = 120.dp) {
    val isGroup = dictionary?.isGroupTag(tag) == true
    Text(
        text = dictionary?.getFieldName(tag) ?: "",
        color = if (isGroup) AppTheme.Colors.groupTag else AppTheme.Colors.fieldName,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier.width(width).padding(start = 4.dp),
    )
}

/**
 * The per-field help column (message-editor pattern): an enum dropdown ("1 (BUY)") when the
 * dictionary defines values for the tag, the value's description for a plain enum value, or a hint
 * that the value is a runtime expression.
 */
@Composable
private fun ValueHelpCell(tag: Int, value: String, dictionary: FixDictionary?, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val enumValues = if (dictionary?.hasFieldValues(tag) == true) dictionary.getFieldEnumValues(tag) else emptyList()
    // The flexible column: the caller passes weight(1f), matching the Message Editor's description/enum column.
    when {
        value.contains("\${") ->
            Text("expression", color = AppTheme.Colors.info, fontSize = 10.sp, maxLines = 1, modifier = modifier)
        enumValues.isNotEmpty() ->
            SlimDropdown(
                value = value.ifBlank { null },
                options = enumValues.map { it.first },
                onValueChange = { picked -> picked?.let(onPick) },
                displayText = { v -> enumValues.firstOrNull { it.first == v }?.second?.let { "$v ($it)" } ?: v },
                placeholder = "pick…",
                modifier = modifier,
            )
        else -> {
            val description = dictionary?.getFieldValueDescription(tag, value)?.takeIf { it != value } ?: ""
            Text(description, color = AppTheme.Colors.textSecondary, fontSize = 10.sp, maxLines = 1, modifier = modifier)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpectDetail(
    step: EditStep,
    dictionary: FixDictionary?,
    sessionOptions: List<String>,
    sessionColor: androidx.compose.ui.graphics.Color,
    onChange: (EditStep) -> Unit,
    /** Opens this step's diff — the one surface that can author or repair an assertion. */
    onOpenDiff: ((Int?) -> Unit)? = null,
) {
    DetailSection("RECEIVES") {
        // One sentence, one left edge: "incoming on <session> · waits up to <10> s". The field labels this
        // row used to wear are the reason its controls started at five different x positions. A FlowRow, not
        // a Row: the step list defaults to 60% of the editor now, and a sentence that cannot wrap responds
        // to a narrow detail pane by pushing its own timeout out of view.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimDropdown(
                    value = step.direction,
                    options = listOf("in", "out"),
                    onValueChange = { picked -> picked?.let { onChange(step.copy(direction = it)) } },
                    displayText = { if (it == "out") "outgoing" else "incoming" },
                    modifier = Modifier.width(100.dp),
                )
                QuietWord("on")
                SessionDot(sessionColor)
                SlimDropdown(
                    value = step.session ?: ACTIVE_SESSION,
                    options = listOf(ACTIVE_SESSION) + sessionOptions,
                    onValueChange = { picked -> onChange(step.copy(session = picked?.takeIf { it != ACTIVE_SESSION })) },
                    displayText = { it },
                    modifier = Modifier.width(180.dp).testTag("session-dropdown"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietWord("· waits up to")
                // Seconds on screen, milliseconds in the model — "Timeout ms 10000" made the reader do the
                // arithmetic the field can do. Local text so a half-typed "1.5" is not reformatted mid-keystroke.
                var seconds by remember { mutableStateOf(secondsText(step.timeoutMs)) }
                SlimField(
                    seconds,
                    { typed ->
                        seconds = typed
                        typed.trim().toDoubleOrNull()?.takeIf { it >= 0 }?.let {
                            onChange(step.copy(timeoutMs = (it * 1000).toLong()))
                        }
                    },
                    monospace = true,
                    modifier = Modifier.width(52.dp).testTag("expect-timeout"),
                )
                QuietWord("s")
            }
        }
    }
    MatchEditor(step.match, dictionary, onChange = { onChange(step.copy(match = it)) })
    AssertionsDoor(step, dictionary, onOpenDiff)
}

/** How many asserted rows the door lists before it defers to the diff. */
private const val DOOR_PREVIEW_ROWS = 8

/**
 * **The step editor does not edit assertions. It says where they are edited — and now also what they are.**
 *
 * There is exactly one surface in the app that can author or repair an assertion — the diff — and exactly one
 * host that composes it: its own document tab. Two hosts would be two sets of props, two lifetimes and two
 * answers to *"which reference is bound"*, and two chances to rewrite the wrong assertion. That is the defect
 * the assertion model doc names, and it has been paid for once already.
 *
 * So this is the door. *Reconcile* when the step has failed a run; *Edit assertions* otherwise — the same
 * surface, a different message in the slot, which is the whole argument of the reference slot made where it
 * costs nothing. The **⧉** glyph says the diff opens *elsewhere* — its own window (Phase 6), beside the grid
 * it is about — not in a tab that replaces the step editor. The read-only row summary is not a second editing
 * surface: it shows the first [DOOR_PREVIEW_ROWS] assertions so the count is not a number the author must
 * open another window to interpret.
 */
@Composable
private fun AssertionsDoor(step: EditStep, dictionary: FixDictionary?, onOpenDiff: ((Int?) -> Unit)?) {
    val rows = step.expectation.fields
    DetailSection(
        "ASSERTS",
        // "EDITED IN THE DIFF" alone read as a fact about elsewhere, not an instruction — the tool's most
        // informed user concluded editing did not exist. Say what the click does, on the thing to click.
        dim =
            if (rows.isNotEmpty()) {
                "— ${rows.size} ${if (rows.size == 1) "ROW" else "ROWS"}" +
                    if (onOpenDiff != null) " · CLICK A ROW TO EDIT IT" else ""
            } else {
                null
            },
    ) {
        if (rows.isEmpty()) {
            Text(
                text =
                    if (step.expectation.golden != null) {
                        "No asserted rows — this step checks only that a matching message arrives."
                    } else {
                        "No asserted rows, and no captured message. This step checks only that a matching " +
                            "message arrives; run the scenario, or bind a message, to start asserting on it."
                    },
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
            )
        }
        // The rows themselves, read-only — "16 asserted rows." was a number the author had to open another
        // window to see the meaning of. The diff stays the ONE surface that edits them (the door below).
        // Same columns as the constraint grid above: the two show the same shape of data.
        rows.take(DOOR_PREVIEW_ROWS).forEach { fe ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .padding(top = 2.dp)
                        // The row IS the door: clicking it opens the diff scrolled to this tag, which is
                        // the gesture "I want to change this value" actually is.
                        .let { m -> if (onOpenDiff != null) m.clickable { onOpenDiff(fe.tag) }.testTag("assert-row-${fe.tag}") else m },
            ) {
                Text("${fe.tag}", color = AppTheme.Colors.tagNumber, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(TAG_COL))
                Text(
                    dictionary?.getFieldName(fe.tag) ?: "",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.width(NAME_COL).padding(start = 4.dp),
                )
                Text(
                    matcherSummary(fe.matcher, dictionary, fe.tag),
                    color = AppTheme.Colors.fieldName,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (rows.size > DOOR_PREVIEW_ROWS) {
            Text(
                "+${rows.size - DOOR_PREVIEW_ROWS} more — the diff shows all of them",
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (onOpenDiff != null) {
            SlimButton(
                text = "Edit assertions ⧉",
                onClick = { onOpenDiff(null) },
                color = AppTheme.Colors.primary,
                modifier = Modifier.padding(top = 8.dp).testTag("open-diff"),
            )
        }
    }
}

/**
 * Edits the Expect bind predicate: which arriving message this step consumes — by message type plus
 * tag=value constraints (AND), e.g. ExecType 150=F and OrdStatus 39=1 to pick the first partial.
 * With a dictionary, the message type and enum constraint values are picked from named dropdowns.
 */
@Composable
private fun MatchEditor(match: MatchPredicate?, dictionary: FixDictionary?, onChange: (MatchPredicate?) -> Unit) {
    fun push(messageType: String?, fields: List<TagValue>, occurrence: Int? = match?.occurrence) {
        val normalized = MatchPredicate(messageType?.ifBlank { null }, match?.direction, fields, occurrence)
        // "Empty" must count every constraint the predicate carries — including the direction this form
        // does not edit but does carry through. A direction-only predicate (match={direction:'out'}, an
        // assertion on a message this side sent) collapsed to null here, and the step silently flipped to
        // the default 'in' — consuming the venue's replies instead of the outgoing message.
        val empty =
            normalized.messageType == null &&
                normalized.direction == null &&
                normalized.fields.isEmpty() &&
                normalized.occurrence == null
        onChange(if (empty) null else normalized)
    }
    // The lesson folded behind the ⓘ: it used to be a two-line standing paragraph that wrapped
    // mid-sentence and ended in a dangling colon, paid on every visit to every Expect step.
    DetailSection(
        "BINDS TO",
        dim = "— WHICH ARRIVING MESSAGE THIS STEP CONSUMES",
        headerTrailing = {
            HintIcon(
                "Which arriving message this step asserts. Walked in order by default; pin a position, or add " +
                    "tag constraints (equals / present / absent) to pick a specific one — e.g. the terminal fill.",
                modifier = Modifier.padding(start = 5.dp).testTag("binds-to-help"),
            )
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MsgTypePicker(match?.messageType, dictionary) { picked -> push(picked, match?.fields ?: emptyList()) }
            QuietWord("position")
            OccurrencePicker(match?.occurrence) { picked -> push(match?.messageType, match?.fields ?: emptyList(), picked) }
        }
        val allFields = remember(dictionary) { dictionary?.getAllFields() ?: emptyList() }
        // Vertical rhythm, and gaps between the cells. These rows were flush against each other and against
        // their own contents — tag, name and value ran together into one grey smear.
        (match?.fields ?: emptyList()).forEachIndexed { i, tv ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                SlimTagPicker(
                    tag = tv.tag,
                    fields = allFields,
                    onPick = { picked -> push(match?.messageType, match!!.fields.toMutableList().apply { this[i] = tv.copy(tag = picked) }) },
                    modifier = Modifier.width(TAG_COL),
                )
                FieldNameCell(tv.tag, dictionary, width = NAME_COL)
                ConstraintOpCell(tv.op) { newOp ->
                    push(match?.messageType, match!!.fields.toMutableList().apply { this[i] = tv.copy(op = newOp) })
                }
                if (tv.op == MatchOp.EQ) {
                    ConstraintValueCell(tv, dictionary) { newValue ->
                        push(match?.messageType, match!!.fields.toMutableList().apply { this[i] = tv.copy(value = newValue) })
                    }
                } else {
                    // Present/absent test existence only; there is no value to compare, so the cell would lie.
                    Text(
                        "(any value)",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.width(170.dp).padding(start = 4.dp),
                    )
                }
                IconButton(
                    onClick = { push(match?.messageType, match!!.fields.toMutableList().apply { removeAt(i) }) },
                    modifier = Modifier.size(22.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove constraint",
                        tint = AppTheme.Colors.error,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        // Below the constraints, because that is where the row it inserts appears. It used to sit up in the
        // header row, a screen-width away from what it did.
        SlimButton(
            "+ constraint",
            onClick = { push(match?.messageType, (match?.fields ?: emptyList()) + TagValue(0, "")) },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Ordinal label for an [occurrence]: 1 -> "1st", 2 -> "2nd", 11 -> "11th". */
private fun ordinalLabel(n: Int): String {
    val suffix = if (n % 100 in 11..13) {
        "th"
    } else {
        when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
    return "$n$suffix"
}

/**
 * Pins which same-type message this step binds: "any (in order)" leaves the walk-in-order default, "2nd of
 * type" binds the 2nd match absolutely — the escape hatch for two replies a tag constraint cannot separate.
 */
@Composable
private fun OccurrencePicker(current: Int?, onPick: (Int?) -> Unit) {
    val anyLabel = "any (in order)"
    val options = listOf(anyLabel) + (1..9).map { it.toString() }
    SlimDropdown(
        value = current?.toString() ?: anyLabel,
        options = options,
        onValueChange = { picked -> onPick(picked?.toIntOrNull()) },
        displayText = { v -> if (v == anyLabel) v else "${ordinalLabel(v.toInt())} of type" },
        modifier = Modifier.width(140.dp).testTag("match-occurrence"),
    )
}

/** The comparison a bind constraint applies: equals a value, or the tag is merely present / absent. */
@Composable
private fun ConstraintOpCell(op: MatchOp, onPick: (MatchOp) -> Unit) {
    SlimDropdown(
        value = op.name.lowercase(),
        options = listOf("eq", "present", "absent"),
        onValueChange = { picked -> picked?.let { onPick(MatchOp.valueOf(it.uppercase())) } },
        displayText = { v -> if (v == "eq") "=" else v },
        modifier = Modifier.width(96.dp).testTag("match-op"),
    )
}

/**
 * The draggable split between a flow list and its detail pane. Same grab-and-drag divider the session view
 * uses, so it feels like the rest of the app rather than like a second, stranger app inside it. Shared by the
 * editor and the capture review, which is why it takes its [testTag].
 */
@Composable
internal fun PaneDivider(onDrag: (Float) -> Unit, testTag: String = "editor-pane-divider") {
    Box(
        modifier =
            Modifier
                // A single hairline, the width and colour of every other panel separator — not the 5dp
                // border-coloured bar it used to be, which read as a different, heavier thing.
                .width(AppTheme.Separators.panelSeparatorWidth)
                .fillMaxHeight()
                .background(AppTheme.Separators.color)
                .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x)
                    }
                }.testTag(testTag),
    )
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
