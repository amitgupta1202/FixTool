package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val state = rememberDialogState(width = 820.dp, height = 620.dp)
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
                            draft = draft.copy(phases = draft.phases.filterIndexed { i, _ -> i != index })
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
            saved.forEach { set ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(set) }
                            .background(if (set.name == selected) AppTheme.Colors.surfaceVariant else Color.Transparent)
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                            .testTag("load-sets-row-${set.name}"),
                ) {
                    Text("⚡", color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
                    Text(
                        set.label.ifBlank { set.name },
                        color = if (set.name == selected) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
                        style = AppTheme.Type.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${set.phases.size}", color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
                }
            }
        }
    }
}

@Composable
private fun SetAction(glyph: String, tag: String, onClick: () -> Unit) {
    Text(
        glyph,
        color = AppTheme.Colors.textSecondary,
        style = AppTheme.Type.body,
        modifier = Modifier.clickable(onClick = onClick).testTag(tag).padding(horizontal = 3.dp),
    )
}

/** The set band: what it is called, the seed, the store, the phases in order, and the one policy. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun SetEditor(
    draft: LoadSet,
    problems: List<LoadSet.Problem>,
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
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        SetFormRow("Name") {
            SlimField(
                draft.label,
                { onChange(draft.copy(label = it, name = LoadSet.slug(it))) },
                modifier = Modifier.fillMaxWidth().testTag("load-set-name"),
            )
            SetSub("load-sets/${LoadSet.slug(draft.label.ifBlank { draft.name })}.json")
        }

        SetGroupHead("Seed")
        SetFormRow("") {
            seedRows.forEachIndexed { index, (name, value) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SlimField(
                        name,
                        { seedRows = written(seedRows, index, it to value, draft, onChange) },
                        modifier = Modifier.width(96.dp).testTag("load-set-seed-name-$index"),
                    )
                    SetSub("=")
                    SlimField(
                        value,
                        { seedRows = written(seedRows, index, name to it, draft, onChange) },
                        modifier = Modifier.width(120.dp).testTag("load-set-seed-value-$index"),
                    )
                    if (index == seedRows.lastIndex) {
                        SetChip("+ add", "load-set-seed-add") { seedRows = seedRows + ("" to "") }
                        SetChip("mint a new one", "load-set-seed-mint") {
                            val row = name.ifBlank { "run" } to SEED_GENERATOR
                            seedRows = written(seedRows, index, row, draft, onChange)
                        }
                    }
                }
            }
            SetSub(
                "rendered once when the set starts and shared by every phase, so phase 2 addresses the " +
                    "\${run} ids phase 1 minted without anything being passed between them",
            )
            SetRefusals(problems.filter { it.phase == null && it.sentence.contains("seeds") }, draft)
        }

        SetGroupHead("Store and log")
        SetFormRow("") {
            SlimRadioGroup(
                options = listOf(true, false),
                selected = draft.storeAndLog != null,
                onSelect = { onChange(draft.copy(storeAndLog = if (it) StoreAndLogOverride.FOR_LOAD else null)) },
                optionTestTag = { if (it) "load-set-store-memory" else "load-set-store-profile" },
            ) { memory ->
                Text(
                    if (memory) "Memory store, no log" else "As each profile",
                    color = AppTheme.Colors.text,
                    style = AppTheme.Type.body,
                )
            }
            SetSub("once, for the whole set")
            SetRefusals(problems.filter { it.phase == null && it.sentence.contains("Reset on Logon") }, draft)
        }

        SetGroupHead("Phases, in order")
        draft.phases.forEachIndexed { index, spec ->
            PhaseRow(
                n = index + 1,
                spec = spec,
                problems = problems.filter { it.phase == index + 1 },
                onEdit = { onEditPhase(index) },
                onDuplicate = {
                    val copied = draft.phases.toMutableList()
                    copied.add(index + 1, spec.copy(label = spec.label + " copy"))
                    onChange(draft.copy(phases = copied))
                },
                onMove = { by ->
                    val to = (index + by).coerceIn(0, draft.phases.lastIndex)
                    if (to != index) {
                        val moved = draft.phases.toMutableList()
                        moved.add(to, moved.removeAt(index))
                        onChange(draft.copy(phases = moved))
                    }
                },
            )
        }
        SetChip("+ add a phase", "load-set-add-phase") {
            onChange(draft.copy(phases = draft.phases + newPhase(draft)))
        }
        SetRefusals(problems.filter { it.phase == null && it.sentence.startsWith("A set needs") }, draft)
        SetRefusals(problems.filter { it.phase == null && it.sentence.startsWith("Two phases") }, draft)

        SetGroupHead("Between phases")
        SetFormRow("If a phase fails") {
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
            SetSub(
                "a phase passes when it is complete and the tool block is clean, and held when it is " +
                    "strict about rate",
            )
        }
    }
}

/** One phase, as the row the dialog's own footer composes: everything it will do, on one line. */
@Composable
@Suppress("LongParameterList")
private fun PhaseRow(
    n: Int,
    spec: LoadPhaseSpec,
    problems: List<LoadSet.Problem>,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("load-set-phase-row-$n")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "$n",
                color = AppTheme.Colors.textDisabled,
                style = AppTheme.Type.meta,
                modifier = Modifier.width(14.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    spec.label,
                    color = AppTheme.Colors.text,
                    style = AppTheme.Type.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("load-set-phase-label-$n"),
                )
                Text(
                    spec.describe(),
                    color = AppTheme.Colors.textDisabled,
                    style = AppTheme.Type.meta,
                    modifier = Modifier.testTag("load-set-phase-plan-$n"),
                )
            }
            if (problems.isNotEmpty()) {
                Text(
                    "${problems.size} fix",
                    color = AppTheme.Colors.error,
                    style = AppTheme.Type.meta,
                    modifier = Modifier.testTag("load-set-phase-fixes-$n"),
                )
            }
            SetChip("↑", "load-set-phase-up-$n") { onMove(-1) }
            SetChip("↓", "load-set-phase-down-$n") { onMove(1) }
            SetChip("edit", "load-set-phase-edit-$n", onEdit)
            SetChip("duplicate", "load-set-phase-duplicate-$n", onDuplicate)
        }
        problems.forEach { SetNotice(it.describe(spec.label)) }
    }
}

@Composable
@Suppress("LongParameterList")
private fun SetFooter(
    why: String?,
    runnable: Boolean,
    dirty: Boolean,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface)) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                why?.let { "Run set is off: $it" } ?: if (dirty) "unsaved" else "",
                color = if (why != null) AppTheme.Colors.error else AppTheme.Colors.textDisabled,
                style = AppTheme.Type.meta,
                maxLines = 2,
                modifier = Modifier.weight(1f).testTag("load-set-why"),
            )
            SlimButton("Copy as fixtool load --set", onClick = onCopy, modifier = Modifier.testTag("load-set-copy-cli"))
            SlimButton("Save", onClick = onSave, modifier = Modifier.testTag("load-set-save"))
            SlimButton("Cancel", onClick = onDismiss)
            SlimButton(
                "Run set  $SET_RUN_KEYS",
                color = AppTheme.Colors.success,
                enabled = runnable,
                onClick = onRun,
                modifier = Modifier.testTag("load-set-run"),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// The small pieces, kept beside the dialog rather than shared: the run dialog's own are private to it
// ------------------------------------------------------------------------------------------------

/** Ctrl or Cmd with Enter, which is what runs the set from anywhere in the dialog. */
private fun enter(event: KeyEvent): Boolean =
    (event.key == Key.Enter || event.key == Key.NumPadEnter) && (event.isMetaPressed || event.isCtrlPressed)

/** The label of a phase a refusal names, so its sentence can carry it. Null for the set's own refusals. */
private fun labelOf(draft: LoadSet, phase: Int?): String? = phase?.let { draft.phases.getOrNull(it - 1)?.label }

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

@Composable
private fun SetGroupHead(title: String) {
    Text(
        title,
        color = AppTheme.Colors.textDisabled,
        style = AppTheme.Type.meta,
        modifier = Modifier.padding(top = 8.dp, bottom = 1.dp),
    )
}

@Composable
private fun SetFormRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        if (label.isNotEmpty()) {
            Text(
                label,
                color = AppTheme.Colors.textSecondary,
                style = AppTheme.Type.body,
                modifier = Modifier.width(104.dp).padding(top = 5.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun SetSub(text: String) {
    if (text.isNotEmpty()) Text(text, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
}

@Composable
private fun SetChip(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = AppTheme.Colors.textSecondary,
        style = AppTheme.Type.meta,
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .testTag(tag)
                .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun SetRefusals(problems: List<LoadSet.Problem>, draft: LoadSet) {
    problems.forEach { SetNotice(it.describe(it.phase?.let { n -> draft.phases.getOrNull(n - 1)?.label })) }
}

@Composable
private fun SetNotice(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text("fix", color = AppTheme.Colors.error, style = AppTheme.Type.meta, modifier = Modifier.width(22.dp))
        Text(
            text,
            color = AppTheme.Colors.error,
            style = AppTheme.Type.body,
            modifier = Modifier.testTag("load-set-refusal"),
        )
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

private val SET_RUN_KEYS: String =
    if (System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
    ) {
        "⌘↵"
    } else {
        "Ctrl+↵"
    }
