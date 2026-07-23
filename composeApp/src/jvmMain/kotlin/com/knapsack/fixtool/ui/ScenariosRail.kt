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
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.ScenarioSort
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
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
    val result by viewModel.scenarioResult.collectAsState()
    val ran by viewModel.lastRunScenario.collectAsState()
    val viewState by viewModel.scenarioViewState.collectAsState()
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var confirmingDeleteId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
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
            RunStatusLine(
                running = running,
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
            val run = RunView(ran, result, running)
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
                        confirmingDelete = confirmingDeleteId == pinned.id,
                        onToggle = { onToggleExpand(pinned.id) },
                        onRequestDelete = { onRequestDelete(pinned.id) },
                        onDeleted = onDeleted,
                        onRemap = { remapFor = pinned },
                        onToggleFavourite = { viewModel.toggleScenarioFavourite(pinned.id) },
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
                            confirmingDeleteId, onToggleExpand, onRequestDelete, onDeleted,
                            onRemap = { remapFor = it }, onToggleFavourite = viewModel::toggleScenarioFavourite,
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
                            confirmingDeleteId, onToggleExpand, onRequestDelete, onDeleted,
                            onRemap = { remapFor = it }, onToggleFavourite = viewModel::toggleScenarioFavourite,
                        )
                    }
                } else {
                    scenarioTrees(
                        sections.others, viewModel, run, modified, expanded, viewState.favouriteIds,
                        confirmingDeleteId, onToggleExpand, onRequestDelete, onDeleted,
                        onRemap = { remapFor = it }, onToggleFavourite = viewModel::toggleScenarioFavourite,
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
)

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
    confirmingDeleteId: String?,
    onToggleExpand: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onDeleted: () -> Unit,
    onRemap: (Scenario) -> Unit,
    onToggleFavourite: (String) -> Unit,
) {
    items.forEach { scenario ->
        scenarioTree(
            viewModel = viewModel,
            scenario = scenario,
            run = run,
            modifiedAt = modified[scenario.id],
            expanded = scenario.id in expanded,
            isFavourite = scenario.id in favouriteIds,
            confirmingDelete = confirmingDeleteId == scenario.id,
            onToggle = { onToggleExpand(scenario.id) },
            onRequestDelete = { onRequestDelete(scenario.id) },
            onDeleted = onDeleted,
            onRemap = { onRemap(scenario) },
            onToggleFavourite = { onToggleFavourite(scenario.id) },
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
    confirmingDelete: Boolean,
    onToggle: () -> Unit,
    onRequestDelete: () -> Unit,
    onDeleted: () -> Unit,
    onRemap: () -> Unit,
    onToggleFavourite: () -> Unit,
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
            runEnabled = !run.running,
            confirmingDelete = confirmingDelete,
            onToggle = onToggle,
            onToggleFavourite = onToggleFavourite,
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
    runEnabled: Boolean,
    confirmingDelete: Boolean,
    onToggle: () -> Unit,
    onToggleFavourite: () -> Unit,
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
    result: ScenarioResult?,
    dictionary: FixDictionary?,
    route: FixMessageViewModel.ReconcileRoute?,
    onReconcile: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        running -> Text("Running…", color = AppTheme.Colors.info, fontSize = 11.sp, modifier = modifier)
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
