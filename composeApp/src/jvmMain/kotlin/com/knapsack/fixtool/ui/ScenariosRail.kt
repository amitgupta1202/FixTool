// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
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
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
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
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var confirmingDeleteId by remember { mutableStateOf<String?>(null) }

    // A failure the author cannot see is a failure they will not fix: open the tree on the scenario that
    // just failed, at the step it failed on.
    LaunchedEffect(result, ran) {
        val id = ran?.id
        if (id != null && result?.passed == false) expanded = expanded + id
    }

    // Slim interactive targets, as the workbench had: Material3's 48dp minimum is a touch-screen convention
    // that would leave this rail room for a name and nothing else.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 20.dp) {
        Column(modifier = modifier.fillMaxSize().background(AppTheme.Colors.surface).testTag("scenarios-rail")) {
            RailHeader(
                running = running,
                onCapture = { viewModel.captureAllSessionsToEditor() },
                onPasteCapture = { viewModel.openPasteCapture() },
                onNew = { viewModel.openScenarioEditor(newScenario()) },
                onOpenFolder = { openScenarioFolder(viewModel) },
                onDiffMessages = { viewModel.openEmptyDiffViewer() },
                onClose = { viewModel.toggleScenariosRail() },
            )
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
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                scenarios.forEach { scenario ->
                    scenarioTree(
                        viewModel = viewModel,
                        scenario = scenario,
                        run = RunView(ran, result, running),
                        expanded = scenario.id in expanded,
                        confirmingDelete = confirmingDeleteId == scenario.id,
                        onToggle = { expanded = if (scenario.id in expanded) expanded - scenario.id else expanded + scenario.id },
                        onRequestDelete = { confirmingDeleteId = scenario.id },
                        onDeleted = { confirmingDeleteId = null },
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

/** One scenario: its row, and — when expanded — its steps, each carrying its own verdict and route. */
private fun androidx.compose.foundation.lazy.LazyListScope.scenarioTree(
    viewModel: FixMessageViewModel,
    scenario: Scenario,
    run: RunView,
    expanded: Boolean,
    confirmingDelete: Boolean,
    onToggle: () -> Unit,
    onRequestDelete: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ranThis = run.ran?.id == scenario.id
    item(key = scenario.id) {
        ScenarioRailRow(
            scenario = scenario,
            verdict = scenarioVerdict(scenario, run.ran, run.result, run.running),
            // The mockup's "1/3": how many of this scenario's steps passed, once it has run. Counted over the
            // `steps` phase only — setup and teardown are not what the author is asking about.
            stepCount =
                if (ranThis && run.result != null) {
                    "${run.result.steps.count { it.passed && it.phase == "steps" }}/${scenario.steps.size}"
                } else {
                    "${scenario.steps.size}"
                },
            expanded = expanded,
            runEnabled = !run.running,
            confirmingDelete = confirmingDelete,
            onToggle = onToggle,
            onRun = { viewModel.runScenario(scenario) },
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
                // judged at all.
                unreached = ranThis && !run.running && run.result != null && stepResult == null,
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
 * The result for the step now sitting at [index] — **by id first**, because that is what an id is for. The
 * index fallback is for a result minted against a file that had none, and it is checked against the same
 * phase, since a run's `stepIndex` is an index within its phase and not into the scenario.
 */
internal fun resultFor(result: ScenarioResult?, scenario: Scenario, index: Int): StepResult? {
    val steps = result?.steps ?: return null
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RailHeader(
    running: Boolean,
    onCapture: () -> Unit,
    onPasteCapture: () -> Unit,
    onNew: () -> Unit,
    onOpenFolder: () -> Unit,
    onDiffMessages: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "SCENARIOS",
                color = AppTheme.Colors.textSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            TooltipIconButton(tooltip = "Hide the Scenarios rail", onClick = onClose, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Hide Scenarios",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        // A FLOW row: the rail is narrow and this used to be a Row that ran out of width and clipped its
        // last button to "Fo". Buttons that don't fit wrap to the next line instead of being amputated.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        ) {
            SlimButton(
                "Capture from sessions…",
                onClick = onCapture,
                enabled = !running,
                color = AppTheme.Colors.success,
                modifier = Modifier.testTag("rail-capture"),
            )
            // W2's front door: a server log fragment, pasted, becomes a scenario without a live session at all.
            SlimButton("Paste wire…", onClick = onPasteCapture, modifier = Modifier.testTag("rail-paste"))
            SlimButton("New", onClick = onNew, modifier = Modifier.testTag("rail-new"))
            SlimButton("Folder", onClick = onOpenFolder)
            // The plain diff viewer's own front door: two empty slots, each a session pick or a paste. Distinct
            // from a scenario capture — it diffs two messages and asserts nothing (Phase 7 entry point).
            SlimButton(
                "⇄ Diff messages…",
                onClick = onDiffMessages,
                color = AppTheme.Colors.textSecondary,
                modifier = Modifier.testTag("rail-diff-messages"),
            )
        }
    }
}

@Composable
private fun ScenarioRailRow(
    scenario: Scenario,
    verdict: RailVerdict,
    stepCount: String,
    expanded: Boolean,
    runEnabled: Boolean,
    confirmingDelete: Boolean,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    val bg = if (verdict == RailVerdict.FAILED) AppTheme.Colors.error.copy(alpha = 0.10f) else AppTheme.Colors.surfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    bg,
                ).clickable(onClick = onToggle)
                .padding(start = 6.dp, end = 2.dp, top = 1.dp, bottom = 1.dp)
                .testTag("scenario-row-${scenario.id}"),
    ) {
        // Not Modifier.size(): a 10dp box clips a 9sp glyph away to nothing, and the tree then looks like a
        // flat list — the one affordance that says these rows open, invisible. Found by looking at it.
        Text(if (expanded) "▾" else "▸", color = AppTheme.Colors.textDisabled, fontSize = 9.sp, modifier = Modifier.width(10.dp))
        VerdictGlyph(verdict, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            text = scenario.name,
            color = AppTheme.Colors.text,
            fontSize = 12.sp,
            maxLines = 1,
            // The rail is narrow and scenario names are sentences. Truncating without saying so reads as a
            // name that simply ends there.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (confirmingDelete) {
            SlimButton("Delete", onClick = onConfirmDelete, color = AppTheme.Colors.error, modifier = Modifier.testTag("confirm-delete"))
            SlimButton("Cancel", onClick = onCancelDelete, color = AppTheme.Colors.textSecondary)
        } else {
            // Start padding too: the name column ends flush against this, and a name long enough to fill it
            // read straight into the count ("…regression1/2").
            Text(stepCount, color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp, end = 2.dp))
            RailIcon(
                Icons.Default.PlayArrow,
                "Run",
                if (runEnabled) AppTheme.Colors.success else AppTheme.Colors.textDisabled,
                onRun,
                enabled = runEnabled,
                tag = "run-${scenario.id}",
            )
            RailIcon(Icons.Default.Edit, "Edit", AppTheme.Colors.textSecondary, onEdit, tag = "edit-${scenario.id}")
            RailIcon(Icons.Default.ContentCopy, "Duplicate", AppTheme.Colors.textSecondary, onDuplicate)
            // A breath before the destructive one: Delete sat flush against Duplicate at 18dp targets, and
            // the inline confirm is a net, not a licence to invite the misclick.
            Spacer(Modifier.width(4.dp))
            RailIcon(Icons.Default.Delete, "Delete", AppTheme.Colors.error, onRequestDelete, tag = "delete-${scenario.id}")
        }
    }
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
            VerdictGlyph(verdict, modifier = Modifier.padding(end = 4.dp))
            Text("${index + 1}", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
            Text(
                text = stepLabel(step, dictionary) + if (unreached) " — not reached" else "",
                color = if (unreached) AppTheme.Colors.textDisabled else AppTheme.Colors.textSecondary,
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
                val stepResults = result.steps.filter { it.phase == "steps" }
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
                    val tagText =
                        failedTags.take(4).joinToString(", ") { t ->
                            // "(?)" and not silence: "failed tags: 6, 31 LastPx" reads as if 6 were a count
                            // or a typo. The dictionary not knowing a tag is a fact worth one glyph.
                            val name = dictionary?.getFieldName(t.tag) ?: "(?)"
                            "${t.tag} $name"
                        } + (if (failedTags.size > 4) " +${failedTags.size - 4} more" else "")
                    // A failure outside the main phase says WHICH phase — "step 1 clear" for a setup step
                    // pointed the author at the wrong list entirely.
                    val where =
                        when {
                            firstFailure.stepIndex < 0 -> firstFailure.kind
                            firstFailure.phase != "steps" -> "${firstFailure.phase} ${firstFailure.kind}"
                            else -> "step ${firstFailure.stepIndex + 1} ${firstFailure.kind}"
                        }
                    val detail = firstFailure.detail?.let { " ($it)" } ?: ""
                    Text(
                        text = "First failure: $where$detail" + (if (failedTags.isNotEmpty()) " — failed tags: $tagText" else ""),
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag("run-failure-line"),
                    )
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
                }
            }
        else -> Unit
    }
}
