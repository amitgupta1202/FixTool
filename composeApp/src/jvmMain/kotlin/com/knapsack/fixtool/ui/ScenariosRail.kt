// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.ScenarioSort
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.SavedRunSet
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import java.awt.Desktop

/**
 * The Scenarios rail — a docked pane in the main window, where the workbench window used to be.
 *
 * It is the *test-runner tree*: every saved scenario, expandable to its steps, with the last run's verdict
 * on each. That is the whole argument for docking it. A separate window could never be the ambient answer to
 * "what do my regressions say right now", because it was never on screen when you were looking at the
 * traffic; a rail is cheap to leave open beside the grid it is judging.
 *
 * Everything that can be clicked here opens a **document tab**, never a window.
 */
@Composable
fun ScenariosRail(viewModel: FixMessageViewModel, modifier: Modifier = Modifier) {
    val scenarios by viewModel.scenarios.collectAsState()
    val running by viewModel.scenarioRunning.collectAsState()
    val busySessions by viewModel.busySessions.collectAsState()
    val result by viewModel.scenarioResult.collectAsState()
    val ran by viewModel.lastRunScenario.collectAsState()
    val viewState by viewModel.scenarioViewState.collectAsState()
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    // **Selection is transient, and deliberately not view state.** A star is a lasting opinion about a
    // scenario — persisted, and it moves the row into its own section. A pick is "these, now": it must
    // not survive a restart, must not reorder the rail, and is spent the moment the set is made.
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmingDeleteId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    val activeSet by viewModel.activeRunSet.collectAsState()
    // A scenario deleted while picked would otherwise stay in the count and run as nothing.
    LaunchedEffect(scenarios) {
        val alive = scenarios.mapTo(mutableSetOf()) { it.id }
        if (!alive.containsAll(selectedIds)) selectedIds = selectedIds intersect alive
    }
    // The Repeat dialog outlives the menu that opened it, like the remap dialog above.
    var repeating by remember { mutableStateOf(false) }
    var savingSet by remember { mutableStateOf(false) }
    var outlining by remember { mutableStateOf(false) }
    var fanningOut by remember { mutableStateOf(false) }
    // The scenario a "Save as scenario…" is being authored for — the dialog outlives the hover that opened it.
    var remapFor by remember { mutableStateOf<Scenario?>(null) }
    remapFor?.let { RemapScenarioDialog(scenario = it, viewModel = viewModel, onDismiss = { remapFor = null }) }

    // A failure the author cannot see is a failure they will not fix: open the tree on the scenario that
    // just failed, at the step it failed on.
    LaunchedEffect(result, ran) {
        val id = ran?.id
        if (id != null && result?.passed == false) expanded = expanded + id
    }

    // By name or by the sessions it drives — "QUOTE1" finds every scenario touching that session, which is
    // the other question an author actually asks of a list this long.
    val visible =
        remember(scenarios, filter) {
            val q = filter.trim()
            if (q.isEmpty()) {
                scenarios
            } else {
                scenarios.filter { sc ->
                    sc.name.contains(q, ignoreCase = true) ||
                        sc.steps.mapNotNull { it.sessionOrNull() }.any { it.contains(q, ignoreCase = true) }
                }
            }
        }
    if (repeating) {
        RepeatScenarioDialog(
            scenarios = scenarios,
            onDismiss = { repeating = false },
            onRun = { scenario, times, pauseMs ->
                repeating = false
                viewModel.startRepeat(scenario, times, pauseMs)
            },
        )
    }
    if (outlining) {
        RunExamplesDialog(
            scenarios = scenarios.filter { it.examples?.live?.isNotEmpty() == true },
            onDismiss = { outlining = false },
            onRun = { scenario ->
                outlining = false
                viewModel.startExamples(scenario)
            },
        )
    }
    if (fanningOut) {
        FanOutDialog(
            viewModel = viewModel,
            scenarios = scenarios,
            onDismiss = { fanningOut = false },
            onRun = { scenario, profileId, leg ->
                fanningOut = false
                viewModel.startFanOut(scenario, profileId, leg)
            },
        )
    }
    if (savingSet) {
        SaveRunSetDialog(
            // Starred wins, then whatever the list is showing — "save as set" has to mean something with
            // nothing starred and no filter typed, and what it means then is the list in front of you.
            scenarios =
                viewState.favouriteIds
                    .takeIf { it.isNotEmpty() }
                    ?.let { ids -> visible.filter { it.id in ids } }
                    ?: visible,
            onDismiss = { savingSet = false },
            onSave = { name, chosen ->
                savingSet = false
                viewModel.saveRunSet(name, chosen)
            },
        )
    }

    // File dates, read once per store change — the meta line must not touch the disk per frame.
    val modified = remember(scenarios) { scenarios.associate { it.id to viewModel.scenarioService.modifiedAt(it.id) } }

    // Slim interactive targets, as the workbench had: Material3's 48dp minimum is a touch-screen convention
    // that would leave this rail room for a name and nothing else.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 20.dp) {
        Column(modifier = modifier.fillMaxSize().background(AppTheme.Colors.background).testTag("scenarios-rail")) {
            RailHeader(
                running = running,
                filter = filter,
                onFilter = { filter = it },
                runMenu =
                    RunMenu(
                        savedSets = remember(scenarios, activeSet) { viewModel.runSetStore.list() },
                        favourites = viewState.favouriteIds.size,
                        filtered = if (filter.isBlank()) 0 else visible.size,
                        recent = remember(activeSet) { viewModel.runRecordStore.listSets().take(RECENT_RUNS) },
                        onRunSaved = { name -> viewModel.startSavedRunSet(name) },
                        onRunFavourites = {
                            viewModel.startRunSet(
                                viewModel.planSuite(
                                    scenarios.filter { it.id in viewState.favouriteIds },
                                    "★ favourites",
                                    RunSource.Favourites,
                                ),
                            )
                        },
                        onRunFiltered = {
                            viewModel.startRunSet(viewModel.planSuite(visible, "filtered: $filter", RunSource.Filtered(filter)))
                        },
                        selected = selectedIds.size,
                        onRunSelected = {
                            val picked = scenarios.filter { it.id in selectedIds }
                            viewModel.startRunSet(viewModel.planSuite(picked, "selected (${picked.size})"))
                            // Spent: the set has been made, and a tick left standing would quietly join
                            // the next one. The starred set is the durable list; this was "these, now".
                            selectedIds = emptySet()
                        },
                        outlines = scenarios.count { it.examples?.live?.isNotEmpty() == true },
                        onSaveAsSet = { savingSet = true },
                        onRepeat = { repeating = true },
                        laneProfiles =
                            remember(activeSet) {
                                viewModel.connectionProfiles.count {
                                    viewModel.fanOutLanes(it.id) is FixMessageViewModel.FanOutLanes.Available
                                }
                            },
                        onRunExamples = { outlining = true },
                        onFanOut = { fanningOut = true },
                        onOpenRecent = { id -> viewModel.focusRunSet(id) },
                    ),
                sort = viewState.sortMode,
                onSort = { viewModel.setScenarioSort(it) },
                anyExpanded = expanded.isNotEmpty(),
                onToggleExpandAll = {
                    // Collapse-all when anything is open, expand-all when nothing is — one button, two jobs.
                    expanded = if (expanded.isNotEmpty()) emptySet() else scenarios.map { it.id }.toSet()
                },
                onCapture = { viewModel.captureAllSessionsToEditor() },
                onPasteCapture = { viewModel.openPasteCapture() },
                onNew = { viewModel.openScenarioEditor(newScenario()) },
                onOpenFolder = { openScenarioFolder(viewModel) },
                onDiffMessages = { viewModel.openEmptyDiffViewer() },
                onClose = { viewModel.toggleScenariosRail() },
            )
            // Header block closed off from the list: the controls above, the run status and tree below.
            HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
            activeSet?.let { set ->
                RunSetLine(
                    set = set,
                    running = running,
                    onStop = { viewModel.requestScenarioStop() },
                    onFocus = { entry -> viewModel.openRunSetEntry(set.id, entry) },
                    onDismiss = { viewModel.clearActiveRunSet() },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    storedStats = { id -> viewModel.runRecordStore.readSetStats(id)?.let { RunSetStats.fromJson(it) } },
                )
            }
            RunStatusLine(
                running = running && activeSet == null,
                onStop = { viewModel.requestScenarioStop() },
                result = result,
                dictionary = viewModel.dictionary,
                // Keyed on the STORE as well as the result: the route consults the file on disk, so a save or
                // a delete changes the answer without changing the result — a route remembered on the result
                // alone kept offering a button into a scenario that was already gone.
                route = remember(result, scenarios) { result?.firstFailure()?.let { viewModel.reconcileRoute(it) } },
                onReconcile = { result?.firstFailure()?.let { viewModel.openReconcile(it) } },
                onDismiss = { viewModel.dismissRunResult() },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (scenarios.isEmpty()) {
                Text(
                    "No scenarios yet. Drive a flow in a session, then Capture from sessions.",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp),
                )
                return@Column
            }
            if (visible.isEmpty()) {
                // The filter said something, and so does the tool — an empty pane with no sentence reads as
                // "the scenarios are gone", which is a heart-stopper on a list the author curates by hand.
                Text(
                    "No scenario matches \"${filter.trim()}\" — by name or by session.",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp).testTag("rail-filter-empty"),
                )
                return@Column
            }
            // weight(1f), not a bare height: the list owns whatever the header and run report leave, and
            // scrolls *within* it. Without the weight it was measured against the full pane height, rendered
            // below the report, and ran off the bottom edge — the rows down there were unreachable, which is
            // exactly what a tall failure report made routine.
            // The scenario that just ran, lifted to the top so a failure is not buried behind the alphabet.
            // The report above says WHAT failed; this puts the scenario itself — its row, its steps, their
            // verdicts and per-step reconcile routes — where the eye already is, until the report is dismissed.
            val pinned = ran?.id?.let { id -> scenarios.firstOrNull { it.id == id } }?.takeIf { result != null }
            val pinnedId = pinned?.id
            val listVisible = if (pinnedId != null) visible.filterNot { it.id == pinnedId } else visible
            // Split into ★ favourites and the rest, each ordered by the author's chosen sort. A favourited
            // scenario shows only in its own section (never twice), and the pinned current run is out of both.
            val sections =
                remember(listVisible, viewState.favouriteIds, viewState.sortMode, modified) {
                    railSections(listVisible, viewState.favouriteIds, viewState.sortMode) { modified[it] }
                }
            val run = RunView(ran, result, running, busySessions)
            val onToggleExpand: (String) -> Unit = { id -> expanded = if (id in expanded) expanded - id else expanded + id }
            val onRequestDelete: (String) -> Unit = { confirmingDeleteId = it }
            val onDeleted = { confirmingDeleteId = null }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (pinned != null) {
                    item(key = "current-run-header") {
                        SectionHeader("CURRENT RUN", count = null, collapsed = false, tag = "current-run-header", onToggle = null)
                    }
                    scenarioTree(
                        viewModel = viewModel,
                        scenario = pinned,
                        run = run,
                        modifiedAt = modified[pinned.id],
                        expanded = pinned.id in expanded,
                        isFavourite = pinned.id in viewState.favouriteIds,
                        isSelected = pinned.id in selectedIds,
                        confirmingDelete = confirmingDeleteId == pinned.id,
                        onToggle = { onToggleExpand(pinned.id) },
                        onRequestDelete = { onRequestDelete(pinned.id) },
                        onDeleted = onDeleted,
                        onRemap = { remapFor = pinned },
                        onToggleFavourite = { viewModel.toggleScenarioFavourite(pinned.id) },
                        onToggleSelected = { selectedIds = selectedIds.toggle(pinned.id) },
                    )
                    item(key = "current-run-divider") {
                        HorizontalDivider(
                            color = AppTheme.Separators.color,
                            thickness = AppTheme.Separators.dividerThickness,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                if (sections.favourites.isNotEmpty()) {
                    // Sections only appear once something is starred: with no favourites the rail reads exactly
                    // as it did before, a single flat list under no header.
                    val favCollapsed = "favourites" in viewState.collapsedSections
                    item(key = "sec-favourites") {
                        SectionHeader("★ Favourites", sections.favourites.size, favCollapsed, "section-favourites") {
                            viewModel.toggleScenarioSection("favourites")
                        }
                    }
                    if (!favCollapsed) {
                        scenarioTrees(
                            sections.favourites, viewModel, run, modified, expanded, viewState.favouriteIds,
                            selectedIds, confirmingDeleteId, onToggleExpand, onRequestDelete, onDeleted,
                            onRemap = { remapFor = it }, onToggleFavourite = viewModel::toggleScenarioFavourite,
                            onToggleSelected = { selectedIds = selectedIds.toggle(it) },
                        )
                    }
                    val allCollapsed = "all" in viewState.collapsedSections
                    item(key = "sec-all") {
                        SectionHeader("All", sections.others.size, allCollapsed, "section-all") {
                            viewModel.toggleScenarioSection("all")
                        }
                    }
                    if (!allCollapsed) {
                        scenarioTrees(
                            sections.others, viewModel, run, modified, expanded, viewState.favouriteIds,
                            selectedIds, confirmingDeleteId, onToggleExpand, onRequestDelete, onDeleted,
                            onRemap = { remapFor = it }, onToggleFavourite = viewModel::toggleScenarioFavourite,
                            onToggleSelected = { selectedIds = selectedIds.toggle(it) },
                        )
                    }
                } else {
                    scenarioTrees(
                        sections.others, viewModel, run, modified, expanded, viewState.favouriteIds,
                        selectedIds, confirmingDeleteId, onToggleExpand, onRequestDelete, onDeleted,
                        onRemap = { remapFor = it }, onToggleFavourite = viewModel::toggleScenarioFavourite,
                        onToggleSelected = { selectedIds = selectedIds.toggle(it) },
                    )
                }
            }
        }
    }
}

/** Everything the tree needs to know about the last run, in one value so a row is not handed four. */
private data class RunView(
    val ran: Scenario?,
    val result: ScenarioResult?,
    val running: Boolean,
    /** Sessions a run currently holds. A scenario that touches none of them can still be run. */
    val busySessions: Set<String> = emptySet(),
) {
    /**
     * **Can this scenario run right now?** Not "is anything running" — the run slot is a claim over
     * sessions, so a flow on UAT is free to start while a fifty-lane load test holds LOADGEN.
     *
     * A scenario naming no session runs on whichever session is first, so it waits for everything: the
     * one case the claim cannot reason about is the one the button must not encourage.
     */
    fun canRun(scenario: Scenario): Boolean {
        val steps = (scenario.setup + scenario.steps + scenario.teardown).filterNot { it.muted }
        if (steps.any { it.sessionOrNull() == null }) return busySessions.isEmpty()
        return steps.mapNotNull { it.sessionOrNull() }.none { it in busySessions }
    }
}

/**
 * Renders each of [items] as a [scenarioTree], sharing the rail's expand/delete/favourite wiring. Kept
 * apart so the favourites section, the "all" section, and the flat no-favourites list all draw their rows
 * the one way — differing only in which scenarios they are handed.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.scenarioTrees(
    items: List<Scenario>,
    viewModel: FixMessageViewModel,
    run: RunView,
    modified: Map<String, Long?>,
    expanded: Set<String>,
    favouriteIds: Set<String>,
    selectedIds: Set<String>,
    confirmingDeleteId: String?,
    onToggleExpand: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onDeleted: () -> Unit,
    onRemap: (Scenario) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
) {
    items.forEach { scenario ->
        scenarioTree(
            viewModel = viewModel,
            scenario = scenario,
            run = run,
            modifiedAt = modified[scenario.id],
            expanded = scenario.id in expanded,
            isFavourite = scenario.id in favouriteIds,
            isSelected = scenario.id in selectedIds,
            confirmingDelete = confirmingDeleteId == scenario.id,
            onToggle = { onToggleExpand(scenario.id) },
            onRequestDelete = { onRequestDelete(scenario.id) },
            onDeleted = onDeleted,
            onRemap = { onRemap(scenario) },
            onToggleFavourite = { onToggleFavourite(scenario.id) },
            onToggleSelected = { onToggleSelected(scenario.id) },
        )
    }
}

/**
 * A section label in the rail — "★ Favourites", "All", "CURRENT RUN". Clickable to fold the section shut
 * when [onToggle] is given; the pinned current-run label passes null, because it is never collapsible.
 */
@Composable
private fun SectionHeader(title: String, count: Int?, collapsed: Boolean, tag: String, onToggle: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
                .padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 1.dp)
                .testTag(tag),
    ) {
        if (onToggle != null) {
            Text(
                if (collapsed) "▸" else "▾",
                color = AppTheme.Colors.textDisabled,
                fontSize = 8.sp,
                modifier = Modifier.width(10.dp),
            )
        }
        Text(
            title + (if (count != null) "  ($count)" else ""),
            color = AppTheme.Colors.textDisabled,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
    }
}

/** One scenario: its row, and — when expanded — its steps, each carrying its own verdict and route. */
private fun androidx.compose.foundation.lazy.LazyListScope.scenarioTree(
    viewModel: FixMessageViewModel,
    scenario: Scenario,
    run: RunView,
    modifiedAt: Long?,
    expanded: Boolean,
    isFavourite: Boolean,
    isSelected: Boolean,
    confirmingDelete: Boolean,
    onToggle: () -> Unit,
    onRequestDelete: () -> Unit,
    onDeleted: () -> Unit,
    onRemap: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    val ranThis = run.ran?.id == scenario.id
    item(key = scenario.id) {
        // The second line is what tells four scenarios named "Captured scenario" apart: the sessions each
        // drives, the step count, and the day its file last changed.
        val sessions = scenario.steps.mapNotNull { it.sessionOrNull() }.distinct()
        val mutedCount = scenario.steps.count { it.muted }
        val meta =
            buildList {
                add(
                    "${scenario.steps.size} ${if (scenario.steps.size == 1) "step" else "steps"}" +
                        if (mutedCount > 0) " ($mutedCount muted)" else "",
                )
                if (sessions.isNotEmpty()) add(sessions.joinToString(" → "))
                modifiedLabel(modifiedAt)?.let { add(it) }
            }.joinToString(" · ")
        // The failure's route, inline on the row that failed — where the failure is announced, not only in
        // a banner the eye must correlate with the list below it. Remembered: the route consults the disk,
        // and a hover flicker is not a reason to read a file.
        val failure = if (ranThis) run.result?.firstFailure() else null
        val inlineRoute = remember(run.result, scenario) { failure?.let { viewModel.reconcileRoute(it) } }
        ScenarioRailRow(
            scenario = scenario,
            verdict = scenarioVerdict(scenario, run.ran, run.result, run.running),
            // How many of this scenario's steps passed, once it has run — counted over the `steps` phase
            // only; setup and teardown are not what the author is asking about. The denominator counts the
            // steps that RUN: two muted steps out of five must read "3/3", not a 3/5 that looks like a miss.
            fraction =
                if (ranThis && run.result != null) {
                    // `isStepVerdict`: a diagnosis row is a note about a message, not a step that ran, and
                    // counting one would inflate the numerator past what the run actually did.
                    "${run.result.steps.count { it.passed && it.isStepVerdict() }}/${scenario.steps.count { !it.muted }}"
                } else {
                    null
                },
            meta = meta,
            reconcileLabel =
                when {
                    inlineRoute !is FixMessageViewModel.ReconcileRoute.Open -> null
                    failure?.phase == "steps" && failure.stepIndex >= 0 -> "Reconcile step ${failure.stepIndex + 1} →"
                    else -> "Reconcile →"
                },
            onReconcile = { failure?.let { viewModel.openReconcile(it) } },
            expanded = expanded,
            isFavourite = isFavourite,
            isSelected = isSelected,
            runEnabled = run.canRun(scenario),
            confirmingDelete = confirmingDelete,
            onToggle = onToggle,
            onToggleFavourite = onToggleFavourite,
            onToggleSelected = onToggleSelected,
            onRun = { viewModel.runScenario(scenario) },
            onRemap = onRemap,
            onEdit = { viewModel.openScenarioEditor(scenario) },
            onDuplicate = { viewModel.duplicateScenario(scenario) },
            onRequestDelete = onRequestDelete,
            onConfirmDelete = {
                viewModel.deleteScenario(scenario.id)
                onDeleted()
            },
            onCancelDelete = onDeleted,
        )
    }
    if (!expanded) return
    scenario.steps.forEachIndexed { index, step ->
        item(key = "${scenario.id}#${step.stepId.ifBlank { index.toString() }}") {
            val stepResult = if (ranThis) resultFor(run.result, scenario, index) else null
            StepRailRow(
                index = index,
                step = step,
                dictionary = viewModel.dictionary,
                result = stepResult,
                // Not reached, and it must SAY so: a bare "–" reads as "it ran, and there was nothing to
                // report". The runner stops at the first failure, so every step below one has not been
                // judged at all. A muted step also has no result, but "not reached" would be the wrong
                // sentence about it — the runner never meant to reach it.
                unreached = ranThis && !run.running && run.result != null && stepResult == null && !step.muted,
                route =
                    remember(run.result, scenario, stepResult) {
                        stepResult?.takeIf { !it.passed }?.let { viewModel.reconcileRoute(it) }
                    },
                onReconcile = { stepResult?.let { viewModel.openReconcile(it) } },
                onOpen = { viewModel.openScenarioEditor(scenario, focusStep = index) },
            )
        }
    }
}

/** The diffable/shareable story needs a handle: scenarios are plain JSON files on disk. */
private fun openScenarioFolder(viewModel: FixMessageViewModel) {
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    runCatching { desktop.open(viewModel.scenarioService.directory) }
}

/** A scenario the author is about to write: it exists as a tab, and reaches disk only when they Save it. */
private fun newScenario(): Scenario =
    Scenario(
        id =
            java.util.UUID
                .randomUUID()
                .toString(),
        name = "new scenario",
        steps = emptyList(),
    )

/** What the rail says about a scenario as a whole. */
enum class RailVerdict { PASSED, FAILED, RUNNING, NOT_RUN }

/**
 * **A run is attributed by scenario *id*, never by [ScenarioResult.scenario] — which is a display name.**
 * Two scenarios may share one, and a green tick on the wrong row is a lie about what passed.
 */
internal fun scenarioVerdict(scenario: Scenario, ran: Scenario?, result: ScenarioResult?, running: Boolean): RailVerdict =
    when {
        ran?.id != scenario.id -> RailVerdict.NOT_RUN
        running -> RailVerdict.RUNNING
        result == null -> RailVerdict.NOT_RUN
        result.passed -> RailVerdict.PASSED
        else -> RailVerdict.FAILED
    }

/**
 * The file's date as an author reads it: `today 14:06` for today, `16 Jul` inside the year, `16 Jul 2025`
 * once the year turns — the words people actually use for "the one from this morning" and "the old one".
 */
internal fun modifiedLabel(epochMs: Long?, clock: () -> java.time.Instant = { java.time.Instant.now() }): String? {
    if (epochMs == null) return null
    val zone = java.time.ZoneId.systemDefault()
    val then = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = java.time.LocalDate.ofInstant(clock(), zone)
    return when {
        then.toLocalDate() == today -> "today " + then.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        then.year == today.year -> then.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        else -> then.format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu"))
    }
}

/**
 * The result for the step now sitting at [index] — **by id first**, because that is what an id is for. The
 * index fallback is for a result minted against a file that had none, and it is checked against the same
 * phase, since a run's `stepIndex` is an index within its phase and not into the scenario.
 */
internal fun resultFor(result: ScenarioResult?, scenario: Scenario, index: Int): StepResult? {
    // Verdicts only. A diagnosis row carries the id of the step a message would have bound to — a step the
    // run never reached — and the strip this feeds says what each step DID. Tinting a step that never ran
    // would be the strip claiming a verdict nobody reached.
    val steps = result?.steps?.filter { it.isStepVerdict() } ?: return null
    val id =
        scenario.steps
            .getOrNull(index)
            ?.stepId
            .orEmpty()
    if (id.isNotBlank()) {
        steps.firstOrNull { it.stepId == id }?.let { return it }
    }
    return steps.firstOrNull { it.phase == "steps" && it.stepIndex == index && it.stepId.isNullOrBlank() }
}

/**
 * A result that says what a step *did*, as opposed to what the run's post-mortem noticed afterwards.
 *
 * Both live in [ScenarioResult.steps] — deliberately, so CI and the control surface get the diagnosis for
 * free — and every surface that counts steps or tints one has to tell them apart. See `ScenarioRunner`'s
 * PostMortem: a diagnosis has no vote.
 */
internal fun StepResult.isStepVerdict(): Boolean = kind != "diagnosis"

/**
 * `297 QuoteStatus (0 → 5)` — the tags of a failure, named and capped at four.
 *
 * "(?)" and not silence for a tag the dictionary does not know: "failed tags: 6, 31 LastPx" reads as if 6
 * were a count or a typo. The dictionary not knowing a tag is a fact worth one glyph.
 */
private fun namedTags(
    tags: List<TagResult>,
    dictionary: FixDictionary?,
    /**
     * Show `(expected → actual)` too. On for a diagnosis, whose entire job is to say what differs; off for
     * the first-failure line, which sits above a "Reconcile assertions →" button that shows the drift in
     * full — and which reads the same as it always has.
     */
    drift: Boolean = false,
    max: Int = 4,
): String =
    tags.take(max).joinToString(", ") { t ->
        val name = dictionary?.getFieldName(t.tag) ?: "(?)"
        val moved = if (drift && t.actual != null) " (${t.expected} → ${t.actual})" else ""
        "${t.tag} $name$moved"
    } + (if (tags.size > max) " +${tags.size - max} more" else "")

@Composable
private fun RailHeader(
    running: Boolean,
    filter: String,
    onFilter: (String) -> Unit,
    runMenu: RunMenu,
    sort: ScenarioSort,
    onSort: (ScenarioSort) -> Unit,
    anyExpanded: Boolean,
    onToggleExpandAll: () -> Unit,
    onCapture: () -> Unit,
    onPasteCapture: () -> Unit,
    onNew: () -> Unit,
    onOpenFolder: () -> Unit,
    onDiffMessages: () -> Unit,
    onClose: () -> Unit,
) {
    // The docked-pane header every other pane speaks (Message Editor, Connection, Message Details): a top
    // rule, the title in a surface-filled bar, a bottom rule — then the controls below. The rail skipped the
    // fill and the rules and painted its whole body surface, so it read looser and flatter than its neighbours.
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface).padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            // The register every docked pane speaks ("Message Details", "Connection"): title case, 11sp, plain.
            Text("Scenarios", color = AppTheme.Colors.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TooltipIconButton(tooltip = "Hide the Scenarios rail", onClick = onClose, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Hide Scenarios",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        // The controls, below the header bar as the Message Editor's toolbar sits below its title: the filter
        // (which a list this size had earned), one way in to the three ways of creating a scenario, and ⋯.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // Run ▾ first, and the per-row Run button is unchanged — it is a set of one.
            Box {
                var runOpen by remember { mutableStateOf(false) }
                SlimButton(
                    "Run ▾",
                    onClick = { runOpen = true },
                    color = if (running) AppTheme.Colors.textDisabled else AppTheme.Colors.success,
                    modifier = Modifier.testTag("rail-run-menu"),
                )
                DropdownMenu(expanded = runOpen, onDismissRequest = { runOpen = false }) {
                    RunMenuContents(menu = runMenu, running = running, onChose = { runOpen = false })
                }
            }
            SlimField(
                value = filter,
                onValueChange = onFilter,
                placeholder = "filter…",
                modifier = Modifier.weight(1f).testTag("rail-filter"),
            )
            // Sort — how the list orders itself within each section.
            Box {
                var sortOpen by remember { mutableStateOf(false) }
                SlimButton("⇅", onClick = { sortOpen = true }, color = AppTheme.Colors.textSecondary, modifier = Modifier.testTag("rail-sort"))
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortItem("Name (A–Z)", ScenarioSort.NAME, sort) { onSort(it); sortOpen = false }
                    SortItem("Recently modified", ScenarioSort.RECENTLY_MODIFIED, sort) { onSort(it); sortOpen = false }
                    SortItem("Creation order", ScenarioSort.CREATED, sort) { onSort(it); sortOpen = false }
                }
            }
            // Collapse-all / expand-all — one button that folds every open scenario shut, or opens them all.
            SlimButton(
                if (anyExpanded) "⊟" else "⊞",
                onClick = onToggleExpandAll,
                color = AppTheme.Colors.textSecondary,
                modifier = Modifier.testTag("rail-collapse-all"),
            )
            Box {
                var open by remember { mutableStateOf(false) }
                SlimButton(
                    "+ New ▾",
                    onClick = { open = true },
                    color = AppTheme.Colors.success,
                    modifier = Modifier.testTag("rail-new-menu"),
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    RailMenuItem("Capture from sessions…", enabled = !running, tag = "rail-capture") {
                        open = false
                        onCapture()
                    }
                    // W2's front door: a server log fragment, pasted, becomes a scenario without a live session.
                    RailMenuItem("Paste wire…", tag = "rail-paste") {
                        open = false
                        onPasteCapture()
                    }
                    RailMenuItem("New blank scenario", tag = "rail-new") {
                        open = false
                        onNew()
                    }
                }
            }
            Box {
                var open by remember { mutableStateOf(false) }
                SlimButton("⋯", onClick = { open = true }, color = AppTheme.Colors.textSecondary, modifier = Modifier.testTag("rail-more"))
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    RailMenuItem("Open scenarios folder", tag = "rail-folder") {
                        open = false
                        onOpenFolder()
                    }
                    // The plain diff viewer's own front door: two empty slots, each a session pick or a paste.
                    // Distinct from a scenario capture — it diffs two messages and asserts nothing.
                    RailMenuItem("⇄ Diff messages…", tag = "rail-diff-messages") {
                        open = false
                        onDiffMessages()
                    }
                }
            }
        }
    }
}

/**
 * **What the Run menu can do, and how many of each there are.** Passed as one value rather than eight
 * parameters, because every item is the same shape of thing — a way to make a set — and the header should
 * not have to know which of them the rail's state happens to support today.
 */
private data class RunMenu(
    val savedSets: List<SavedRunSet>,
    val favourites: Int,
    val filtered: Int,
    /** How many rows the author has ticked — an ad-hoc set, gone once it runs. */
    val selected: Int,
    val recent: List<RunSet>,
    /** How many saved scenarios carry a table — the count on the outline item. */
    val outlines: Int,
    /** How many saved profiles could supply lanes — a multi-session initiator with sessions logged on. */
    val laneProfiles: Int,
    val onRunSaved: (String) -> Unit,
    val onRunFavourites: () -> Unit,
    val onRunFiltered: () -> Unit,
    val onRunSelected: () -> Unit,
    val onSaveAsSet: () -> Unit,
    val onRepeat: () -> Unit,
    val onRunExamples: () -> Unit,
    val onFanOut: () -> Unit,
    val onOpenRecent: (String) -> Unit,
)

/**
 * The four ways to build a set and the one way to look at an old one.
 *
 * Every item that cannot be used stays **visible and disabled with its count showing**, because an author
 * cannot tell "there is nothing starred" from "this feature does not exist" if the item is withheld.
 */
@Composable
private fun RunMenuContents(menu: RunMenu, running: Boolean, onChose: () -> Unit) {
    if (menu.savedSets.isEmpty()) {
        RailMenuItem("Run set ▸  (none saved)", enabled = false, tag = "rail-run-set-none") {}
    } else {
        menu.savedSets.forEach { set ->
            val runs = set.entries.sumOf { it.repeat.coerceAtLeast(1) }
            RailMenuItem(
                "Run set ▸  ${set.name}  ($runs)",
                enabled = !running,
                tag = "rail-run-set-${set.name}",
            ) {
                onChose()
                menu.onRunSaved(set.name)
            }
        }
    }
    RailMenuItem("Run ★ favourites  (${menu.favourites})", enabled = !running && menu.favourites > 0, tag = "rail-run-favourites") {
        onChose()
        menu.onRunFavourites()
    }
    RailMenuItem("Run filtered  (${menu.filtered})", enabled = !running && menu.filtered > 0, tag = "rail-run-filtered") {
        onChose()
        menu.onRunFiltered()
    }
    // Visible with its count at zero, like every other item here: "nothing is ticked" and "this cannot be
    // done" are different answers, and withholding the row gives the author only the second one.
    RailMenuItem("Run selected…  (${menu.selected})", enabled = !running && menu.selected > 0, tag = "rail-run-selected") {
        onChose()
        menu.onRunSelected()
    }
    RailMenuItem("Repeat a scenario ×N…", enabled = !running, tag = "rail-run-repeat") {
        onChose()
        menu.onRepeat()
    }
    // Disabled with its count showing, like the rest: "no scenario here has a table" and "this feature
    // does not exist" are different sentences, and only one of them is true.
    RailMenuItem(
        "Fan out over sessions…  (${menu.laneProfiles})",
        enabled = !running && menu.laneProfiles > 0,
        tag = "rail-run-fanout",
    ) {
        onChose()
        menu.onFanOut()
    }
    RailMenuItem(
        "Run examples table…  (${menu.outlines})",
        enabled = !running && menu.outlines > 0,
        tag = "rail-run-examples",
    ) {
        onChose()
        menu.onRunExamples()
    }
    RailMenuItem("Save as set…", tag = "rail-save-set") {
        onChose()
        menu.onSaveAsSet()
    }
    if (menu.recent.isNotEmpty()) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        menu.recent.forEach { set ->
            val mark = if (set.status == RunSetStatus.PASSED) "✓" else "✗"
            RailMenuItem("Recent ▸  $mark ${set.label}  (${set.passed}/${set.total})", tag = "rail-recent-${set.id}") {
                onChose()
                menu.onOpenRecent(set.id)
            }
        }
    }
}

/**
 * **The set report, where the single run's report sits** — progress while it runs, the whole picture when
 * it is done, and one row per entry that is click-to-focus.
 *
 * Bounded height with its own scroll, like everything else that has ever been allowed into this rail: the
 * set's *detail* is a document tab, which is exactly why this does not have to grow to hold it.
 */
@Composable
private fun RunSetLine(
    set: RunSet,
    running: Boolean,
    onStop: () -> Unit,
    onFocus: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The set's stored `stats`, for a reopened set that no longer carries its entries' reports. */
    storedStats: (String) -> RunSetStats.Stats? = { null },
) {
    val colour =
        when (set.status) {
            RunSetStatus.PASSED -> AppTheme.Colors.success
            RunSetStatus.RUNNING -> AppTheme.Colors.info
            RunSetStatus.STOPPED -> AppTheme.Colors.textSecondary
            RunSetStatus.FAILED -> AppTheme.Colors.error
        }
    Column(modifier = modifier.testTag("run-set-report")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val elapsed = ((set.finishedAt ?: System.currentTimeMillis()) - set.startedAt).coerceAtLeast(0) / 1000
            Text(
                "${set.label} — ${set.done}/${set.total}" +
                    (if (set.failed > 0) " · ${set.failed} failed" else "") +
                    " · ${elapsed}s",
                color = colour,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
        }
        // **Fifty lanes are a distribution, not fifty rows.** The step latency is the venue's number; the
        // wall clock is the flow's, and they are never offered as the same thing.
        if (set.source is RunSource.FanOut) {
            // Computed from a live set, read back for one that has been reopened: `set.json` keeps entries
            // but not their reports, so a set from Recent runs can recompute none of this. Without the
            // fallback the distribution was visible only while the run that produced it was still on screen.
            val stats = remember(set) { RunSetStats.merge(RunSetStats.of(set), storedStats(set.id)) }
            stats?.replyLatency?.let { steps ->
                Text(
                    "reply latency  ${RunSetStats.describe(steps)}  (${steps.samples} steps)" +
                        stats.failedLanes.takeIf { it.isNotEmpty() }
                            ?.let { "   failures: " + it.joinToString(", ") { slot -> "lane $slot" } }.orEmpty(),
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("run-set-latency"),
                )
            }
            stats?.wallClock?.let { wall ->
                Text(
                    "lane wall-clock  ${RunSetStats.describe(wall)}",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 10.sp,
                )
            }
            if (running) {
                TooltipIconButton(
                    tooltip = "Stop this set — the entry running stops where it is, and the rest are skipped",
                    onClick = onStop,
                    modifier = Modifier.size(16.dp).testTag("stop-run-set"),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop set",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            } else {
                TooltipIconButton(
                    tooltip = "Put this set report down (the records stay on disk)",
                    onClick = onDismiss,
                    modifier = Modifier.size(16.dp).testTag("dismiss-run-set"),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss set report",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            set.entries.forEachIndexed { i, entry ->
                val mark =
                    when (entry.state) {
                        RunState.PASSED -> "✓"
                        RunState.FAILED -> "✗"
                        RunState.RUNNING -> "⟳"
                        RunState.STOPPED -> "⏹"
                        RunState.SKIPPED -> "–"
                        RunState.PENDING -> "·"
                    }
                val tint =
                    when (entry.state) {
                        RunState.PASSED -> AppTheme.Colors.success
                        RunState.FAILED -> AppTheme.Colors.error
                        RunState.RUNNING -> AppTheme.Colors.info
                        else -> AppTheme.Colors.textDisabled
                    }
                val name = set.nameOf(i)
                val timing = entry.durationMs?.let { " ${it}ms" }.orEmpty()
                Text(
                    "  $mark $name$timing" + (entry.note?.let { " — $it" } ?: ""),
                    color = tint,
                    fontSize = 10.sp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.record != null) { onFocus(i + 1) }
                            .testTag("run-set-entry-${i + 1}"),
                )
            }
        }
    }
}

/** Pick a scenario and a count. The flake hunt's whole dialog. */
@Composable
private fun RepeatScenarioDialog(scenarios: List<Scenario>, onDismiss: () -> Unit, onRun: (Scenario, Int, Long) -> Unit) {
    var chosen by remember { mutableStateOf(scenarios.firstOrNull()) }
    var times by remember { mutableStateOf("20") }
    var pause by remember { mutableStateOf("0") }
    Dialog(onCloseRequest = onDismiss, title = "Repeat a scenario", state = rememberDialogState(width = 420.dp, height = 300.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().background(AppTheme.Colors.background).padding(12.dp),
        ) {
            Text("Run one scenario N times and report every iteration.", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
            Box {
                var open by remember { mutableStateOf(false) }
                SlimButton(chosen?.name ?: "pick a scenario", onClick = { open = true }, modifier = Modifier.testTag("repeat-pick"))
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    scenarios.forEach { sc ->
                        RailMenuItem(sc.name, tag = "repeat-pick-${sc.id}") {
                            chosen = sc
                            open = false
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("times", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
                SlimField(times, { times = it }, modifier = Modifier.width(60.dp).testTag("repeat-times"))
                Text("pause ms", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
                SlimField(pause, { pause = it }, modifier = Modifier.width(70.dp).testTag("repeat-pause"))
            }
            // Isolation is on and stated rather than offered: a repeat that could bind the previous
            // iteration's reply is a false green, which is the one thing a flake hunt must not produce.
            Text(
                "Each iteration binds only its own traffic. It does not clear a session's messages or a " +
                    "venue's order book — those are the scenario's own setup steps.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimButton("Cancel", onClick = onDismiss)
                SlimButton(
                    "Run",
                    color = AppTheme.Colors.success,
                    onClick = {
                        val sc = chosen ?: return@SlimButton
                        onRun(sc, times.toIntOrNull()?.coerceAtLeast(1) ?: 1, pause.toLongOrNull()?.coerceAtLeast(0) ?: 0)
                    },
                    modifier = Modifier.testTag("repeat-run"),
                )
            }
        }
    }
}

/**
 * **Fan a flow out over a profile's sessions.** Pick the scenario, the profile that opens the lanes, and —
 * when the scenario drives more than one session — which leg is the one being spread.
 *
 * Every profile is listed, including the ones that cannot supply lanes: an author cannot tell "this cannot
 * be done, and here is why" from "this feature does not exist" if the entry is withheld, so the reason is
 * on screen and the Run button is what refuses.
 */
@Composable
private fun FanOutDialog(
    viewModel: FixMessageViewModel,
    scenarios: List<Scenario>,
    onDismiss: () -> Unit,
    onRun: (Scenario, String, String?) -> Unit,
) {
    var scenario by remember { mutableStateOf(scenarios.firstOrNull()) }
    var profileId by remember { mutableStateOf<String?>(null) }
    var leg by remember { mutableStateOf<String?>(null) }
    val legs = remember(scenario) { scenario?.sessionsInvolved().orEmpty() }
    val lanes = profileId?.let { viewModel.fanOutLanes(it) }
    Dialog(onCloseRequest = onDismiss, title = "Fan out over sessions", state = rememberDialogState(width = 520.dp, height = 380.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().background(AppTheme.Colors.background).padding(12.dp),
        ) {
            Text(
                "Runs the flow once per session of a multi-session profile, all at once. Each lane knows " +
                    "which client it is: \${sessionIndex}, \${sessionSenderCompID} and two more are in its scope.",
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    SlimButton(scenario?.name ?: "pick a scenario", onClick = { open = true }, modifier = Modifier.testTag("fanout-scenario"))
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        scenarios.forEach { sc ->
                            RailMenuItem(sc.name, tag = "fanout-scenario-${sc.id}") {
                                scenario = sc
                                leg = null
                                open = false
                            }
                        }
                    }
                }
                Box {
                    var open by remember { mutableStateOf(false) }
                    val profile = viewModel.connectionProfiles.firstOrNull { it.id == profileId }
                    SlimButton(profile?.name ?: "pick a profile", onClick = { open = true }, modifier = Modifier.testTag("fanout-profile"))
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        viewModel.connectionProfiles.forEach { p ->
                            val available = viewModel.fanOutLanes(p.id)
                            val count = (available as? FixMessageViewModel.FanOutLanes.Available)?.lanes?.size
                            RailMenuItem(
                                p.name + (count?.let { "  ($it lanes)" } ?: "  (no lanes)"),
                                tag = "fanout-profile-${p.id}",
                            ) {
                                profileId = p.id
                                open = false
                            }
                        }
                    }
                }
                if (legs.size > 1) {
                    Box {
                        var open by remember { mutableStateOf(false) }
                        SlimButton(leg ?: "which leg?", onClick = { open = true }, modifier = Modifier.testTag("fanout-leg"))
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            legs.forEach { session ->
                                RailMenuItem(session, tag = "fanout-leg-$session") {
                                    leg = session
                                    open = false
                                }
                            }
                        }
                    }
                }
            }
            when (lanes) {
                is FixMessageViewModel.FanOutLanes.Unavailable ->
                    Text(lanes.why, color = AppTheme.Colors.warning, fontSize = 10.sp, modifier = Modifier.testTag("fanout-unavailable"))
                is FixMessageViewModel.FanOutLanes.Available -> {
                    Text(
                        "${lanes.lanes.size} lanes: " + lanes.lanes.joinToString(", ") { "lane ${it.slot} (${it.senderCompID})" },
                        color = AppTheme.Colors.textDisabled,
                        fontSize = 10.sp,
                    )
                    lanes.shortfall?.let { Text(it, color = AppTheme.Colors.warning, fontSize = 10.sp) }
                }
                null -> Unit
            }
            profileId?.let { id ->
                viewModel.fanOutFarEndNotice(id)?.let {
                    Text(it, color = AppTheme.Colors.warning, fontSize = 10.sp, modifier = Modifier.testTag("fanout-far-end"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimButton("Cancel", onClick = onDismiss)
                SlimButton(
                    "Run",
                    color = AppTheme.Colors.success,
                    enabled = scenario != null && lanes is FixMessageViewModel.FanOutLanes.Available && (legs.size <= 1 || leg != null),
                    onClick = { onRun(scenario ?: return@SlimButton, profileId ?: return@SlimButton, leg) },
                    modifier = Modifier.testTag("fanout-run"),
                )
            }
        }
    }
}

/** Pick the outline to run. Its rows are shown, because "8 rows" is not the same claim as naming them. */
@Composable
private fun RunExamplesDialog(scenarios: List<Scenario>, onDismiss: () -> Unit, onRun: (Scenario) -> Unit) {
    var chosen by remember { mutableStateOf(scenarios.firstOrNull()) }
    Dialog(onCloseRequest = onDismiss, title = "Run examples table", state = rememberDialogState(width = 460.dp, height = 300.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().background(AppTheme.Colors.background).padding(12.dp),
        ) {
            Text(
                "Runs the scenario once per live row of its table, each row seeded into the run's own scope.",
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
            )
            Box {
                var open by remember { mutableStateOf(false) }
                SlimButton(chosen?.name ?: "pick a scenario", onClick = { open = true }, modifier = Modifier.testTag("examples-pick"))
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    scenarios.forEach { sc ->
                        RailMenuItem("${sc.name}  (${sc.examples?.live?.size ?: 0} rows)", tag = "examples-pick-${sc.id}") {
                            chosen = sc
                            open = false
                        }
                    }
                }
            }
            chosen?.examples?.let { table ->
                Text(
                    table.columns.joinToString("  ") + "\n" +
                        table.live.joinToString("\n") { row -> row.name + ":  " + table.columns.joinToString("  ") { row.values[it].orEmpty() } },
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 10.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimButton("Cancel", onClick = onDismiss)
                SlimButton(
                    "Run",
                    color = AppTheme.Colors.success,
                    onClick = { chosen?.let(onRun) },
                    modifier = Modifier.testTag("examples-run"),
                )
            }
        }
    }
}

/** Name what is on screen, and it becomes the thing CI can run by name. */
@Composable
private fun SaveRunSetDialog(scenarios: List<Scenario>, onDismiss: () -> Unit, onSave: (String, List<Scenario>) -> Unit) {
    var name by remember { mutableStateOf("nightly") }
    Dialog(onCloseRequest = onDismiss, title = "Save as run set", state = rememberDialogState(width = 420.dp, height = 260.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().background(AppTheme.Colors.background).padding(12.dp),
        ) {
            Text(
                "Saves ${scenarios.size} scenario${if (scenarios.size == 1) "" else "s"} as a named set — a file " +
                    "beside your scenarios, so CI can run it by name.",
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
            )
            SlimField(name, { name = it }, modifier = Modifier.fillMaxWidth().testTag("save-set-name"))
            Text(scenarios.joinToString(", ") { it.name }, color = AppTheme.Colors.textDisabled, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimButton("Cancel", onClick = onDismiss)
                SlimButton(
                    "Save",
                    color = AppTheme.Colors.success,
                    enabled = name.isNotBlank() && scenarios.isNotEmpty(),
                    onClick = { onSave(name.trim(), scenarios) },
                    modifier = Modifier.testTag("save-set-confirm"),
                )
            }
        }
    }
}

@Composable
private fun RailMenuItem(text: String, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = if (enabled) AppTheme.Colors.text else AppTheme.Colors.textDisabled, fontSize = 11.sp) },
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.height(26.dp).testTag(tag),
    )
}

/** One row of the sort menu, its current choice marked with a tick. */
@Composable
private fun SortItem(label: String, value: ScenarioSort, current: ScenarioSort, onPick: (ScenarioSort) -> Unit) {
    DropdownMenuItem(
        text = { Text((if (value == current) "✓ " else "     ") + label, color = AppTheme.Colors.text, fontSize = 11.sp) },
        onClick = { onPick(value) },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.height(26.dp).testTag("sort-${value.name.lowercase()}"),
    )
}

@Composable
private fun ScenarioRailRow(
    scenario: Scenario,
    verdict: RailVerdict,
    /** "passed/total" once this scenario has run — what the FAILED chip shows. Null before any run. */
    fraction: String?,
    /** The identity line: "2 steps · QUOTE1 → TRADE1 · 15 Jul". */
    meta: String,
    /** "Reconcile step 2 →", when the failure has an open route — inline, where the failure is announced. */
    reconcileLabel: String?,
    onReconcile: () -> Unit,
    expanded: Boolean,
    isFavourite: Boolean,
    /** Picked for "Run selected…" — transient, unlike a star, and gone once the set is made. */
    isSelected: Boolean,
    runEnabled: Boolean,
    confirmingDelete: Boolean,
    onToggle: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleSelected: () -> Unit,
    onRun: () -> Unit,
    /** Opens the "Save as scenario for other sessions" dialog — the environment-copy door. */
    onRemap: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    // The actions live where the pointer is. Ninety-six standing icon targets — four per row, delete
    // included — were the rail's single loudest element, and all but four of them were about rows the
    // author was not looking at. Hover (or an in-flight delete confirm) brings them back, on that row only.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg =
        when {
            verdict == RailVerdict.FAILED -> AppTheme.Colors.error.copy(alpha = 0.10f)
            hovered || confirmingDelete -> AppTheme.Colors.surfaceVariant
            else -> androidx.compose.ui.graphics.Color.Transparent
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                .background(bg)
                .clickable(onClick = onToggle)
                .padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 3.dp)
                .testTag("scenario-row-${scenario.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Not Modifier.size(): a 10dp box clips a 9sp glyph away to nothing, and the tree then looks like
            // a flat list — the one affordance that says these rows open, invisible. Found by looking at it.
            Text(if (expanded) "▾" else "▸", color = AppTheme.Colors.textDisabled, fontSize = 9.sp, modifier = Modifier.width(10.dp))
            // The star: filled and always shown once starred (that is what a favourite looks like), an outline
            // that appears on hover for the rest — click to move a scenario into, or out of, ★ Favourites.
            Box(modifier = Modifier.width(15.dp), contentAlignment = Alignment.Center) {
                when {
                    isFavourite ->
                        Text(
                            "★",
                            color = AppTheme.Colors.warning,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable(onClick = onToggleFavourite).testTag("fav-${scenario.id}"),
                        )
                    hovered ->
                        Text(
                            "☆",
                            color = AppTheme.Colors.textDisabled,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable(onClick = onToggleFavourite).testTag("fav-${scenario.id}"),
                        )
                }
            }
            // The tick, built like the star beside it: shown once picked (that is what picked looks like),
            // an empty box on hover for the rest. Nothing standing in the rail when nothing is selected —
            // ninety-six idle checkboxes is the chrome the hover rule was written to avoid.
            Box(modifier = Modifier.width(15.dp), contentAlignment = Alignment.Center) {
                when {
                    isSelected ->
                        Text(
                            "☑",
                            color = AppTheme.Colors.info,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable(onClick = onToggleSelected).testTag("pick-${scenario.id}"),
                        )
                    hovered ->
                        Text(
                            "☐",
                            color = AppTheme.Colors.textDisabled,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable(onClick = onToggleSelected).testTag("pick-${scenario.id}"),
                        )
                }
            }
            Text(
                text = scenario.name,
                color = AppTheme.Colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                // The rail is narrow and scenario names are sentences. Truncating without saying so reads as
                // a name that simply ends there.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                confirmingDelete -> {
                    SlimButton("Delete", onClick = onConfirmDelete, color = AppTheme.Colors.error, modifier = Modifier.testTag("confirm-delete"))
                    SlimButton("Cancel", onClick = onCancelDelete, color = AppTheme.Colors.textSecondary)
                }
                hovered -> {
                    RailIcon(
                        Icons.Default.PlayArrow,
                        "Run",
                        if (runEnabled) AppTheme.Colors.success else AppTheme.Colors.textDisabled,
                        onRun,
                        enabled = runEnabled,
                        tag = "run-${scenario.id}",
                    )
                    // The environment door, beside Run because that is where the need arises ("run this on
                    // QA"): duplicate this scenario with its sessions re-aimed. A dialog, not a menu — the
                    // copies it creates live in the rail like any other scenario, so there is no second
                    // list of environments to manage here.
                    RailIcon(
                        Icons.Default.ArrowDropDown,
                        "Save as scenario for other sessions…",
                        if (runEnabled) AppTheme.Colors.success else AppTheme.Colors.textDisabled,
                        onRemap,
                        enabled = runEnabled,
                        tag = "remap-${scenario.id}",
                    )
                    RailIcon(Icons.Default.Edit, "Edit", AppTheme.Colors.textSecondary, onEdit, tag = "edit-${scenario.id}")
                    RailIcon(Icons.Default.ContentCopy, "Duplicate", AppTheme.Colors.textSecondary, onDuplicate)
                    // A breath before the destructive one: Delete sat flush against Duplicate at 18dp
                    // targets, and the inline confirm is a net, not a licence to invite the misclick.
                    Spacer(Modifier.width(4.dp))
                    RailIcon(Icons.Default.Delete, "Delete", AppTheme.Colors.error, onRequestDelete, tag = "delete-${scenario.id}")
                }
                // The verdict is a chip on the one row that owns the run slot — not a dash column on the
                // twenty-three that do not. Silence means not run.
                else -> VerdictChip(verdict, fraction)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 1.dp)) {
            Text(
                meta,
                color = AppTheme.Colors.textDisabled,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (reconcileLabel != null) {
                Text(" · ", color = AppTheme.Colors.textDisabled, fontSize = 9.5.sp)
                Text(
                    reconcileLabel,
                    color = AppTheme.Colors.error,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable(onClick = onReconcile).testTag("rail-row-reconcile-${scenario.id}"),
                )
            }
        }
    }
}

/** The last run's verdict, worn only by the scenario that ran: ✓ passed, ✗ 1/2, or running…. */
@Composable
private fun VerdictChip(verdict: RailVerdict, fraction: String?) {
    val (label, colour) =
        when (verdict) {
            RailVerdict.PASSED -> "✓ passed" to AppTheme.Colors.success
            RailVerdict.FAILED -> "✗ ${fraction ?: "failed"}" to AppTheme.Colors.error
            RailVerdict.RUNNING -> "running…" to AppTheme.Colors.info
            RailVerdict.NOT_RUN -> return
        }
    Text(
        label,
        color = colour,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        modifier =
            Modifier
                .padding(start = 6.dp)
                .border(1.dp, colour.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp),
    )
}

@Composable
private fun RailIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(18.dp).let { if (tag != null) it.testTag(tag) else it }) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(11.dp))
    }
}

@Composable
private fun VerdictGlyph(verdict: RailVerdict, modifier: Modifier = Modifier) {
    val (glyph, color) =
        when (verdict) {
            RailVerdict.PASSED -> "✓" to AppTheme.Colors.success
            RailVerdict.FAILED -> "✗" to AppTheme.Colors.error
            RailVerdict.RUNNING -> "▸" to AppTheme.Colors.info
            RailVerdict.NOT_RUN -> "–" to AppTheme.Colors.textDisabled
        }
    Text(glyph, color = color, fontSize = 11.sp, modifier = modifier)
}

/**
 * A step of the tree. A failing step carries **Reconcile →** — or the refusal, verbatim from
 * [FixMessageViewModel.reconcileRoute], because a withheld route with no reason beside it is how the author
 * concludes the feature does not exist.
 */
@Composable
private fun StepRailRow(
    index: Int,
    step: ScenarioStep,
    dictionary: FixDictionary?,
    result: StepResult?,
    unreached: Boolean,
    route: FixMessageViewModel.ReconcileRoute?,
    onReconcile: () -> Unit,
    onOpen: () -> Unit,
) {
    val verdict =
        when {
            result == null -> RailVerdict.NOT_RUN
            result.passed -> RailVerdict.PASSED
            else -> RailVerdict.FAILED
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 18.dp, end = 4.dp)
                .testTag("rail-step-$index"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // ⊘, not a verdict glyph: a muted step has no verdict and never will — that is what muted means.
            if (step.muted) {
                Text("⊘", color = AppTheme.Colors.textDisabled, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            } else {
                VerdictGlyph(verdict, modifier = Modifier.padding(end = 4.dp))
            }
            Text("${index + 1}", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
            Text(
                text =
                    stepLabel(step, dictionary) +
                        if (step.muted) " — muted" else if (unreached) " — not reached" else "",
                color = if (unreached || step.muted) AppTheme.Colors.textDisabled else AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                // Ellipsis, not the hard clip that cut "· 17 tags" to "· 17" against the Reconcile button.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (route is FixMessageViewModel.ReconcileRoute.Open) {
                SlimButton("Reconcile →", onClick = onReconcile, color = AppTheme.Colors.error, modifier = Modifier.testTag("rail-reconcile-$index"))
            }
        }
        if (route is FixMessageViewModel.ReconcileRoute.Refused) {
            Text(
                route.why,
                color = AppTheme.Colors.textDisabled,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 14.dp, bottom = 2.dp).testTag("rail-refused-$index"),
            )
        }
    }
}

/**
 * The last run's verdict, and — when the failing step can be reconciled — the route to the diff that fixes
 * it. That route is the whole point of the feature, so it belongs *on the failure*, where the failure is
 * announced. Without it the report was a dead end: it named the failed tags and then left the author to go
 * back to the session grid and hunt for the message themselves.
 *
 * When there is **no** route, it says why rather than simply omitting the button. Silently withholding what
 * the tool has already decided is the same mistake in a smaller costume: the author cannot tell "this cannot
 * be reconciled, and here is the reason" from "this feature does not exist".
 */
@Composable
private fun RunStatusLine(
    running: Boolean,
    onStop: () -> Unit,
    result: ScenarioResult?,
    dictionary: FixDictionary?,
    route: FixMessageViewModel.ReconcileRoute?,
    onReconcile: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        // A run that cannot be stopped is a UI that has taken the tool away from its author: an expect
        // holds the run slot for its whole timeout, and until now the only way out was to wait it out.
        running ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
                Text("Running…", color = AppTheme.Colors.info, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TooltipIconButton(
                    tooltip = "Stop this run — it stops where it is, and reports as stopped rather than passed",
                    onClick = onStop,
                    modifier = Modifier.size(16.dp).testTag("stop-run"),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop run",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        result != null ->
            Column(modifier = modifier) {
                // The `steps` phase only — the same count the scenario's own row shows. This used to count
                // setup/teardown too, so the two said "4/5" and "3/4" about the same run, one above the other.
                val stepResults = result.steps.filter { it.phase == "steps" && it.isStepVerdict() }
                val passedSteps = stepResults.count { it.passed }
                val color = if (result.passed) AppTheme.Colors.success else AppTheme.Colors.error
                val verdict = if (result.passed) "PASSED" else "FAILED"
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Last run — ${result.scenario}: $verdict ($passedSteps/${stepResults.size} steps).",
                        color = color,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    // The report can always be put down. Without this, a red banner nobody can act on any
                    // more (or one that has simply been read) sat at the head of the rail until the next run.
                    TooltipIconButton(
                        tooltip = "Dismiss this run report (clears the grid's red/green rows too)",
                        onClick = onDismiss,
                        modifier = Modifier.size(16.dp).testTag("dismiss-run-report"),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss run report",
                            tint = AppTheme.Colors.textSecondary,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
                val firstFailure = result.firstFailure()
                if (firstFailure != null) {
                    val failedTags = firstFailure.tags.filterNot { it.passed }
                    val tagText = namedTags(failedTags, dictionary)
                    // A failure outside the main phase says WHICH phase — "step 1 clear" for a setup step
                    // pointed the author at the wrong list entirely.
                    val where =
                        when {
                            firstFailure.stepIndex < 0 -> firstFailure.kind
                            firstFailure.phase != "steps" -> "${firstFailure.phase} ${firstFailure.kind}"
                            else -> "step ${firstFailure.stepIndex + 1} ${firstFailure.kind}"
                        }
                    val detail = firstFailure.detail?.let { " ($it)" } ?: ""
                    val failureText =
                        "First failure: $where$detail" + (if (failedTags.isNotEmpty()) " — failed tags: $tagText" else "")
                    // **What else arrived** — the reject on the other leg, the reply a bind predicate turned
                    // down. Kept out of the default view: it is context for a failure the essentials already
                    // name, and on a busy run it is what turned the report into a wall.
                    val diagnosis = result.steps.filterNot { it.isStepVerdict() }
                    // Default to the essentials — verdict, the one failure line, the route. A long detail or a
                    // stack of diagnosis lines is exactly what buried the scenario list, so it waits behind an
                    // explicit "Show full error". Reset per run: a fresh failure opens compact, as its own thing.
                    var showFull by remember(result) { mutableStateOf(false) }
                    val canExpand = diagnosis.isNotEmpty() || (firstFailure.detail?.length ?: 0) > 140
                    if (showFull) {
                        // Even expanded it is bounded and scrolls, so "see the whole error" never costs the
                        // author the list underneath it — the one regression this whole area is about.
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState())
                                    .testTag("run-report-detail"),
                        ) {
                            Text(
                                text = failureText,
                                color = AppTheme.Colors.textSecondary,
                                fontSize = 10.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag("run-failure-line"),
                            )
                            diagnosis.forEach { d ->
                                val diverging = d.tags.filterNot { it.passed }
                                val tags = if (diverging.isEmpty()) "" else " — ${namedTags(diverging, dictionary, drift = true)}"
                                Text(
                                    text = "· ${d.detail.orEmpty()}$tags",
                                    color = AppTheme.Colors.textDisabled,
                                    fontSize = 9.sp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag("run-diagnosis-line"),
                                )
                            }
                        }
                    } else {
                        Text(
                            text = failureText,
                            color = AppTheme.Colors.textSecondary,
                            fontSize = 10.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag("run-failure-line"),
                        )
                    }
                    if (route is FixMessageViewModel.ReconcileRoute.Open) {
                        SlimButton(
                            text = "Reconcile assertions →",
                            onClick = onReconcile,
                            color = AppTheme.Colors.error,
                            modifier = Modifier.padding(top = 2.dp).testTag("reconcile-failure"),
                        )
                    }
                    if (route is FixMessageViewModel.ReconcileRoute.Refused) {
                        Text(
                            route.why,
                            color = AppTheme.Colors.textDisabled,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(top = 2.dp).testTag("reconcile-refused"),
                        )
                    }
                    if (canExpand) {
                        Text(
                            text =
                                if (showFull) {
                                    "Show less ▴"
                                } else {
                                    "Show full error ▾" + (if (diagnosis.isNotEmpty()) " (${diagnosis.size})" else "")
                                },
                            color = AppTheme.Colors.info,
                            fontSize = 9.5.sp,
                            modifier =
                                Modifier
                                    .padding(top = 2.dp)
                                    .clickable { showFull = !showFull }
                                    .testTag("report-expand"),
                        )
                    }
                }
            }
        else -> Unit
    }
}

/** How many past sets the Run menu offers — enough to find last night's, short of a scrolling list. */
private const val RECENT_RUNS = 5

/** Pick or unpick — the one operation a transient selection needs. */
private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
