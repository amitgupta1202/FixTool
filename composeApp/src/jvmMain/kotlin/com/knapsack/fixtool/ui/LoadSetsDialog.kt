package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **Load sets: the run-configurations dialog.**
 *
 * The sketch drew a bare editor, which leaves the question of where the saved sets are chosen, duplicated
 * and deleted. IntelliJ answers that with one dialog: configurations down the left, the selected one's
 * editor on the right, Run in the footer. A load set is a run configuration in every sense that matters,
 * so that is the idiom.
 *
 * The right-hand editor is the set band (name, seed, store and log, the phases in order, and the one
 * policy between them). A phase opens over it through a breadcrumb, drawn by [LoadRunDialogContent] in
 * its phase mode, because a phase *is* a load plan minus the two things the set owns, and the thing you
 * already know how to fill in should not be a second form.
 *
 * Which is also why the editor is drawn with the run dialog's own chrome, from [LoadDialogChrome.kt]:
 * sections with a rule and a purpose, one right-aligned label column, sentence hints, the same refusal
 * inset and the same footer row. This dialog had its own smaller copy of each, so the two halves of one
 * window disagreed the moment a phase opened.
 */
@Composable
fun LoadSetsDialog(
    viewModel: FixMessageViewModel,
    onDismiss: () -> Unit,
    onRun: (LoadSet.Planned) -> Unit,
    /** The set to open on, unsaved: what "Make this a set…" in Load run… hands over. */
    initial: LoadSet? = null,
    /**
     * The **saved** set to open on, by name: what a refused `Load set ▸` hands over.
     *
     * By name rather than as a [initial] value, because it is on disk already and opening it as an unsaved
     * draft would put "unsaved" in the footer of a set nobody has touched.
     */
    initialName: String? = null,
) {
    // Resizable, as every Compose dialog is by default, and remembered beside the run dialog's size in the
    // view-state store, never in AppSettings: a window size is not a setting anybody edits on a settings
    // page. Its own pair, because this window is two panes wide and the run dialog is one column.
    val (width, height) = remember { viewModel.loadSetsDialogSize() }
    val state = rememberDialogState(width = width.dp, height = height.dp)
    DisposableEffect(Unit) {
        onDispose { viewModel.rememberLoadSetsDialogSize(state.size.width.value, state.size.height.value) }
    }
    Dialog(onCloseRequest = onDismiss, title = "Load sets", state = state) {
        LoadSetsDialogContent(viewModel, onDismiss, onRun, initial, initialName)
    }
}

/** The dialog's body without its window, so a test can drive it in a plain composition. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
fun LoadSetsDialogContent(
    viewModel: FixMessageViewModel,
    onDismiss: () -> Unit,
    onRun: (LoadSet.Planned) -> Unit,
    initial: LoadSet? = null,
    initialName: String? = null,
) {
    var saved by remember { mutableStateOf(viewModel.loadSets()) }
    var draft by
        remember {
            mutableStateOf(
                initial
                    ?: initialName?.let { viewModel.loadSet(it) }
                    ?: viewModel.lastLoadSet()
                    ?: saved.firstOrNull()
                    ?: blankSet(viewModel),
            )
        }
    var editing by remember { mutableStateOf<Int?>(null) }
    var dirty by remember { mutableStateOf(initial != null) }

    val resolve = remember(draft) { viewModel.loadSetResolver() }
    val problems = remember(draft) { draft.problems(resolve, LoadPlan.Surface.DIALOG) }

    /**
     * What the phases **before** [index] keep, to the number of the first phase that keeps it.
     *
     * Kept apart from the seed, which the phase editor takes separately: the template's sub-line says where
     * each name comes from, and "from phase 1" is the half its reader does not already know.
     */
    fun capturedBefore(index: Int): Map<String, Int> =
        buildMap {
            draft.phases.take(index).forEachIndexed { i, spec ->
                spec.capture.keys.forEach { name -> putIfAbsent(name, i + 1) }
            }
        }

    fun select(set: LoadSet) {
        draft = set
        editing = null
        dirty = false
        if (set.name.isNotBlank()) viewModel.rememberLastLoadSet(set.name)
    }

    fun save(): Boolean {
        if (draft.name.isBlank() || !viewModel.saveLoadSet(draft)) return false
        saved = viewModel.loadSets()
        viewModel.rememberLastLoadSet(draft.name)
        dirty = false
        return true
    }

    fun run() {
        if (problems.isNotEmpty()) return
        if (dirty && !save()) return
        onRun(draft.plan(resolve, seedOverride = emptyMap(), id = viewModel.reserveLoadId(draft.name)))
    }

    // A phase over the set band, reached by the breadcrumb, with Esc as the way back.
    editing?.let { index ->
        draft.phases.getOrNull(index)?.let { spec ->
            LoadRunDialogContent(
                viewModel = viewModel,
                fixedTemplate = null,
                onDismiss = onDismiss,
                onRun = {},
                phase =
                    PhaseEdit(
                        n = index + 1,
                        setLabel = draft.label.ifBlank { draft.name },
                        spec = spec,
                        seeded = draft.seed.keys,
                        captured = capturedBefore(index),
                        onBack = { editing = null },
                        onDone = { updated ->
                            draft = draft.copy(phases = draft.phases.toMutableList().also { it[index] = updated })
                            dirty = true
                            editing = null
                        },
                        onRemove = {
                            draft = draft.removePhase(index)
                            dirty = true
                            editing = null
                        },
                    ),
            )
            return
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AppTheme.Colors.background)
                .testTag("load-sets-dialog")
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Escape -> {
                            onDismiss()
                            true
                        }
                        enter(event) -> {
                            run()
                            true
                        }
                        else -> false
                    }
                },
    ) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SetList(
                saved = saved,
                selected = draft.name,
                onSelect = ::select,
                onNew = {
                    select(blankSet(viewModel))
                    dirty = true
                },
                onDuplicate = {
                    select(draft.copy(name = freeName(draft.name, saved), label = draft.label + " copy"))
                    dirty = true
                },
                onDelete = {
                    viewModel.deleteLoadSet(draft.name)
                    saved = viewModel.loadSets()
                    select(saved.firstOrNull() ?: blankSet(viewModel))
                },
                modifier = Modifier.fillMaxHeight().width(SET_LIST_WIDTH),
            )
            VerticalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
            SetEditor(
                draft = draft,
                problems = problems,
                resolve = resolve,
                onChange = {
                    draft = it
                    dirty = true
                },
                onEditPhase = { editing = it },
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
        }
        SetFooter(
            why = problems.firstOrNull()?.let { first -> first.describe(labelOf(draft, first.phase)) },
            ready = readySentence(draft),
            runnable = problems.isEmpty() && draft.phases.isNotEmpty(),
            dirty = dirty,
            // It copies the *saved* name, so it saves first if it has to. A renamed or brand-new set
            // otherwise put a name on the clipboard that no file on disk answers to, and the build that
            // pasted it failed on a set nobody could find.
            onCopy = { if (!dirty || save()) copyToClipboard("fixtool load --set ${draft.name}") },
            onSave = { save() },
            onDismiss = onDismiss,
            onRun = ::run,
        )
    }
}

/** The set store, down the left: what is saved, and the three things you can do to the list. */
@Composable
@Suppress("LongParameterList")
private fun SetList(
    saved: List<LoadSet>,
    selected: String,
    onSelect: (LoadSet) -> Unit,
    onNew: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(AppTheme.Colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            SetAction("+", "load-sets-new", onNew)
            SetAction("⧉", "load-sets-duplicate", onDuplicate)
            SetAction("−", "load-sets-delete", onDelete)
            Text(
                "saved",
                color = AppTheme.Colors.textDisabled,
                style = AppTheme.Type.meta,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (saved.isEmpty()) {
                Text(
                    "No set saved yet. + starts one.",
                    color = AppTheme.Colors.textDisabled,
                    style = AppTheme.Type.meta,
                    modifier = Modifier.padding(8.dp).testTag("load-sets-none"),
                )
            }
            saved.forEach { set -> SetRow(set, set.name == selected) { onSelect(set) } }
        }
    }
}

/**
 * One saved set, drawn as the app's rail rows are drawn.
 *
 * The rail's own row composable takes eighteen parameters about scenarios, so what is reused is the idiom
 * rather than the code: the same row height, and the same selected treatment of a lifted background with a
 * 2.dp stripe down the leading edge. A list that read as a plain highlighted table beside a rail that reads
 * like this is the same window disagreeing with itself in a smaller way.
 */
@Composable
private fun SetRow(set: LoadSet, selected: Boolean, onSelect: () -> Unit) {
    val stripe = AppTheme.Colors.info
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .background(if (selected) AppTheme.Colors.background else Color.Transparent)
                .drawBehind { if (selected) drawRect(stripe, size = Size(RAIL_STRIPE.toPx(), size.height)) }
                .padding(horizontal = 9.dp, vertical = 6.dp)
                .testTag("load-sets-row-${set.name}"),
    ) {
        Text("⚡", color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
        Text(
            set.label.ifBlank { set.name },
            color = if (selected) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
            style = AppTheme.Type.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("${set.phases.size}", color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
    }
}

/** One of the list's three verbs, as a chip with a target you can actually hit. */
@Composable
private fun SetAction(glyph: String, tag: String, onClick: () -> Unit) {
    Chip(
        glyph,
        on = false,
        tag = tag,
        modifier = Modifier.defaultMinSize(minWidth = ACTION_TARGET, minHeight = ACTION_TARGET),
        onClick = onClick,
    )
}

/**
 * The set band: what it is called, the seed, the store, the phases in order, and the one policy.
 *
 * Four sections with a rule and a purpose each, in the order somebody fills them in. The name row is first
 * and carries no rule, because the top of the pane is its own top edge, exactly as the run dialog's lead
 * sentence is the top edge of its first section.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
private fun SetEditor(
    draft: LoadSet,
    problems: List<LoadSet.Problem>,
    resolve: LoadSet.Resolver,
    onChange: (LoadSet) -> Unit,
    onEditPhase: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var seedRows by remember(draft.name) {
        mutableStateOf(
            draft.seed.entries
                .map { it.key to it.value }
                .ifEmpty { listOf("run" to "") },
        )
    }
    // No padding and no spacing of its own: every rule is full bleed and each section owns its gutter,
    // which is what stops four headings reading as one continuous list.
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Section(rule = null) {
            FormRow("Name") {
                SlimField(
                    draft.label,
                    { onChange(draft.copy(label = it, name = LoadSet.slug(it))) },
                    modifier = Modifier.fillMaxWidth().testTag("load-set-name"),
                )
                Hint("load-sets/${LoadSet.slug(draft.label.ifBlank { draft.name })}.json")
            }
        }

        Section(
            rule = "set-rule-identity",
            title = "Identity and store",
            purpose = "the seed and the store every phase shares",
        ) {
            FormRow("Seed") {
                seedRows.forEachIndexed { index, (name, value) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SlimField(
                            name,
                            { seedRows = written(seedRows, index, it to value, draft, onChange) },
                            modifier = Modifier.width(SEED_FIELD).testTag("load-set-seed-name-$index"),
                        )
                        Sub("=")
                        SlimField(
                            value,
                            { seedRows = written(seedRows, index, name to it, draft, onChange) },
                            modifier = Modifier.width(SEED_FIELD).testTag("load-set-seed-value-$index"),
                        )
                        // Mint and remove belong to the row they act on, which is a row with a name: on the
                        // empty placeholder "mint a new one" names a seed out of thin air, and a row once
                        // added could never be taken away again.
                        if (name.isNotBlank()) {
                            Chip("mint a new one", on = false, tag = mintTag(index)) {
                                seedRows = written(seedRows, index, name to SEED_GENERATOR, draft, onChange)
                            }
                            Chip("−", on = false, tag = "load-set-seed-remove-$index") {
                                val left = seedRowsWithout(seedRows, index)
                                onChange(draft.copy(seed = seedMap(left)))
                                seedRows = left
                            }
                        }
                        // On the last row, because a new row can only follow the last one.
                        if (index == seedRows.lastIndex) {
                            Chip("+ add", on = false, tag = "load-set-seed-add") { seedRows = seedRows + ("" to "") }
                        }
                    }
                }
                Hint(
                    "rendered once when the set starts and shared by every phase, so phase 2 addresses the " +
                        "**\${run}** ids phase 1 minted without anything being passed between them",
                )
                SetRefusals(problems.filter { it.phase == null && it.sentence.contains("seeds") }, draft)
            }
            FormRow("Store and log") {
                // Two radios that mean the same thing are a decision nobody has to make, which is the run
                // dialog's rule. A set has no single profile, so the sentence stands in only when every
                // phase's profile already runs a memory store with no log.
                if (alreadyMemoryStores(draft, resolve)) {
                    Hint(
                        "Lanes run on a **memory store with no message log**, as every phase's profile " +
                            "already does.",
                        tag = "load-set-store-same",
                    )
                } else {
                    SlimRadioGroup(
                        options = listOf(true, false),
                        selected = draft.storeAndLog != null,
                        onSelect = {
                            onChange(draft.copy(storeAndLog = if (it) StoreAndLogOverride.FOR_LOAD else null))
                        },
                        optionTestTag = { if (it) "load-set-store-memory" else "load-set-store-profile" },
                    ) { memory ->
                        Text(
                            if (memory) "Memory store, no log" else "As each profile",
                            color = AppTheme.Colors.text,
                            style = AppTheme.Type.body,
                        )
                    }
                    Hint("once, for the whole set")
                }
                SetRefusals(problems.filter { it.phase == null && it.sentence.contains("Reset on Logon") }, draft)
            }
        }

        Section(
            rule = "set-rule-phases",
            title = "Phases, in order",
            purpose = "one saved message per phase, in the order they run",
        ) {
            draft.phases.forEachIndexed { index, spec ->
                PhaseRow(
                    n = index + 1,
                    spec = spec,
                    problems = problems.filter { it.phase == index + 1 },
                    onEdit = { onEditPhase(index) },
                    // The set owns the arithmetic, because a phase's trigger is an ordinal and every one of
                    // these moves what an ordinal points at. See LoadSet.movePhase.
                    onDuplicate = { onChange(draft.duplicatePhase(index)) },
                    onMove = { by -> onChange(draft.movePhase(index, index + by)) },
                    onToggleMute = {
                        val parked = draft.phases.toMutableList()
                        parked[index] = spec.copy(muted = !spec.muted)
                        onChange(draft.copy(phases = parked))
                    },
                )
            }
            Chip("+ add a phase", on = false, tag = "load-set-add-phase") {
                onChange(draft.copy(phases = draft.phases + newPhase(draft)))
            }
            SetRefusals(problems.filter { it.phase == null && it.sentence.startsWith("A set needs") }, draft)
            SetRefusals(problems.filter { it.phase == null && it.sentence.startsWith("Every phase is muted") }, draft)
            SetRefusals(problems.filter { it.phase == null && it.sentence.startsWith("Two phases") }, draft)
        }

        Section(
            rule = "set-rule-policy",
            title = "Between phases",
            purpose = "what happens when a phase does not pass",
        ) {
            FormRow("If a phase fails") {
                SlimRadioGroup(
                    options = listOf(OnFailure.STOP, OnFailure.CONTINUE),
                    selected = draft.onFailure,
                    onSelect = { onChange(draft.copy(onFailure = it)) },
                    optionTestTag = { "load-set-on-failure-${it.name.lowercase()}" },
                ) { policy ->
                    Text(
                        when (policy) {
                            OnFailure.STOP -> "Stop, and report the rest as skipped"
                            OnFailure.CONTINUE -> "Carry on, and report every phase"
                        },
                        color = AppTheme.Colors.text,
                        style = AppTheme.Type.body,
                    )
                }
                Hint(
                    "a phase passes when it is **complete** and the tool block is clean, and held when it " +
                        "is **strict about rate**",
                )
            }
        }
    }
}

/**
 * One phase, as the row the dialog's own footer composes: everything it will do, on one line.
 *
 * A **muted** row keeps its number, its plan line and every chip, dims its label and wears the step
 * editor's own MUTED tag. It shows no "N fix" count, because a muted phase is not judged: the count would
 * be a promise that unmuting it is all that stands between the set and a clean run.
 */
@Composable
@Suppress("LongParameterList")
private fun PhaseRow(
    n: Int,
    spec: LoadPhaseSpec,
    problems: List<LoadSet.Problem>,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
    onToggleMute: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("load-set-phase-row-$n")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "$n",
                color = AppTheme.Colors.textDisabled,
                style = AppTheme.Type.meta,
                modifier = Modifier.width(14.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        spec.label,
                        color = if (spec.muted) AppTheme.Colors.textDisabled else AppTheme.Colors.text,
                        style = AppTheme.Type.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("load-set-phase-label-$n"),
                    )
                    if (spec.muted) MutedTag(n)
                }
                Hint(spec.describe(), tag = "load-set-phase-plan-$n")
            }
            if (problems.isNotEmpty() && !spec.muted) {
                Text(
                    "${problems.size} fix",
                    color = AppTheme.Colors.error,
                    style = AppTheme.Type.meta,
                    modifier = Modifier.testTag("load-set-phase-fixes-$n"),
                )
            }
            Chip("↑", on = false, tag = "load-set-phase-up-$n") { onMove(-1) }
            Chip("↓", on = false, tag = "load-set-phase-down-$n") { onMove(1) }
            Chip("edit", on = false, tag = "load-set-phase-edit-$n", onClick = onEdit)
            Chip("duplicate", on = false, tag = "load-set-phase-duplicate-$n", onClick = onDuplicate)
            Chip(
                if (spec.muted) "muted" else "mute",
                on = spec.muted,
                tag = "load-set-phase-mute-$n",
                onClick = onToggleMute,
            )
        }
        // Under the row that caused it, which is the whole placement rule: the phase this names is the one
        // whose edit button is one line above the sentence.
        problems.forEach { RefusalNotice(it.describe(spec.label), "load-set-refusal") }
    }
}

/** The MUTED tag the step editor wears, on the phase that is: same warning colour, same small caps. */
@Composable
private fun MutedTag(n: Int) {
    AppTooltip("The set skips this phase. Its template, profile, shape and place in the order are kept.") {
        Text(
            "MUTED",
            color = AppTheme.Colors.warning,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier =
                Modifier
                    .padding(start = 8.dp)
                    .border(1.dp, AppTheme.Colors.warning.copy(alpha = MUTED_TAG_ALPHA), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .testTag("load-set-phase-muted-$n"),
        )
    }
}

/** The same footer row as the run dialog's, with this dialog's own words and its own four buttons. */
@Composable
@Suppress("LongParameterList")
private fun SetFooter(
    why: String?,
    ready: String,
    runnable: Boolean,
    dirty: Boolean,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
) {
    DialogFooter(
        left = {
            Text(
                why?.let { "Run set is off: $it" } ?: if (dirty) "unsaved" else ready,
                color = if (why != null) AppTheme.Colors.error else AppTheme.Colors.textDisabled,
                style = AppTheme.Type.meta,
                maxLines = 2,
                modifier = Modifier.testTag("load-set-why"),
            )
        },
    ) {
        SlimButton("Copy as fixtool load --set", onClick = onCopy, modifier = Modifier.testTag("load-set-copy-cli"))
        SlimButton("Save", onClick = onSave, modifier = Modifier.testTag("load-set-save"))
        SlimButton("Cancel", onClick = onDismiss)
        SlimButton(
            "Run set  $RUN_KEYS",
            color = AppTheme.Colors.success,
            enabled = runnable,
            onClick = onRun,
            modifier = Modifier.testTag("load-set-run"),
        )
    }
}

// ------------------------------------------------------------------------------------------------
// The plain functions. The pieces they draw with are shared, in LoadDialogChrome.kt
// ------------------------------------------------------------------------------------------------

/** Ctrl or Cmd with Enter, which is what runs the set from anywhere in the dialog. */
private fun enter(event: KeyEvent): Boolean =
    (event.key == Key.Enter || event.key == Key.NumPadEnter) && (event.isMetaPressed || event.isCtrlPressed)

/** The label of a phase a refusal names, so its sentence can carry it. Null for the set's own refusals. */
private fun labelOf(draft: LoadSet, phase: Int?): String? = phase?.let { draft.phases.getOrNull(it - 1)?.label }

/**
 * "2 phases · run = ${uuid:4} · memory store, no log": what Run set will do, when nothing is wrong.
 *
 * The same three clauses in the same order as the run dialog's Identity summary, because it is the same
 * question asked of a set rather than of one burst.
 */
private fun readySentence(draft: LoadSet): String {
    val muted = draft.phases.count { it.muted }
    // Three phases in the file, one of them skipped: the count of parked phases belongs where the footer
    // says what Run set will do, because "3 phases" alone would promise three runs.
    val phases =
        (if (draft.phases.size == 1) "1 phase" else "${draft.phases.size} phases") +
            if (muted > 0) ", $muted muted" else ""
    val named = draft.seed.entries.joinToString(", ") { (name, value) -> "$name = $value" }
    val seeded = named.ifEmpty { "nothing seeded" }
    val store = draft.storeAndLog?.describe() ?: "each profile's own store"
    return "$phases · $seeded · $store"
}

/**
 * **Every phase already runs the store a load run wants.**
 *
 * The radios then say the same thing twice, so the row is one sentence instead, as the run dialog's Store
 * row is. A phase whose profile does not resolve counts as unknown rather than as agreeing: a set half
 * written cannot be told what its lanes will do.
 *
 * Live phases only, because the override is applied to the lanes the set opens and a muted phase opens
 * none. A parked phase on a file-store profile would otherwise keep the radios on screen for a set every
 * one of whose lanes already runs a memory store.
 */
private fun alreadyMemoryStores(draft: LoadSet, resolve: LoadSet.Resolver): Boolean {
    val live = draft.phases.filterNot { it.muted }
    if (live.isEmpty()) return false
    val resolved = live.mapNotNull { resolve.profile(it.profile) }
    if (resolved.size != live.size) return false
    return resolved.all { storeOf(it.config) == StoreAndLogOverride.FOR_LOAD }
}

/** One seed row written back, and the set told about it. */
private fun written(
    rows: List<Pair<String, String>>,
    index: Int,
    row: Pair<String, String>,
    draft: LoadSet,
    onChange: (LoadSet) -> Unit,
): List<Pair<String, String>> {
    val updated = rows.toMutableList().also { it[index] = row }
    onChange(draft.copy(seed = seedMap(updated)))
    return updated
}

/**
 * The mint chip's tag: unindexed on the first row, indexed after it.
 *
 * Mint moved onto every named row, and the first row's tag is the one the surface has always answered to.
 * Renaming it would have been a silent break for anything driving this dialog by tag.
 */
private fun mintTag(index: Int): String = if (index == 0) "load-set-seed-mint" else "load-set-seed-mint-$index"

@Composable
private fun SetRefusals(problems: List<LoadSet.Problem>, draft: LoadSet) {
    problems.forEach {
        RefusalNotice(it.describe(it.phase?.let { n -> draft.phases.getOrNull(n - 1)?.label }), "load-set-refusal")
    }
}

/** A new set, named so it does not collide, with one phase to fill in. */
private fun blankSet(viewModel: FixMessageViewModel): LoadSet {
    val existing = viewModel.loadSets().map { it.name }
    val name = freeName("new set", existing.map { LoadSet(it, it) })
    val set =
        LoadSet(
            name = name,
            label = "New set",
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            seed = mapOf("run" to SEED_GENERATOR),
        )
    return set.copy(phases = listOf(newPhase(set)))
}

/** A phase to fill in: the first template and profile to hand, and the shape the dialog defaults to. */
private fun newPhase(set: LoadSet): LoadPhaseSpec =
    LoadPhaseSpec(
        label = "Phase ${set.phases.size + 1}",
        template = "",
        profile = "",
        shape = LoadShape.Burst(DEFAULT_COUNT),
    )

/** `nightly`, `nightly-2`, `nightly-3`: a name the store does not already hold. */
private fun freeName(base: String, saved: List<LoadSet>): String {
    val slug = LoadSet.slug(base)
    if (saved.none { it.name == slug }) return slug
    var n = 2
    while (saved.any { it.name == "$slug-$n" }) n++
    return "$slug-$n"
}

/**
 * What the mint chip writes: the generator, not four hex characters.
 *
 * A saved set with `"run": "b7f2"` would give every execution the same ids, and the venue would see
 * duplicate ClOrdIDs on the second night.
 */
private const val SEED_GENERATOR = "\${uuid:4}"
private const val DEFAULT_COUNT = 4_000
private val SET_LIST_WIDTH = 190.dp

/** The name and value fields, at the width the run dialog's Seed row gives them. */
private val SEED_FIELD = 88.dp

/** The rail's selected stripe, down the leading edge of the row. */
private val RAIL_STRIPE = 2.dp

/** A one-character glyph is not a hit target, so the three list verbs get one. */
private val ACTION_TARGET = 22.dp

/** The MUTED tag's border, at the weight the step editor's own draws it. */
private const val MUTED_TAG_ALPHA = 0.45f
