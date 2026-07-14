// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.RawMessageView
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * The Scenarios workbench — a real, resizable, **non-modal** window, so the live session view in the
 * main window stays visible and usable while capturing or editing (live previews and the
 * verify-generalizes check feed off live session traffic). Three panes:
 * list → capture-review (curate what was recorded *before* it becomes a scenario) → editor.
 */
@Composable
fun ScenarioWorkbenchWindow(viewModel: FixMessageViewModel, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = "Scenarios — FixTool",
        state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
    ) {
        FixToolWindowChrome {
            Box(modifier = Modifier.fillMaxSize()) {
                ScenarioWorkbench(viewModel)
                // Errors raised while this window has focus (failed save, failed run) must surface
                // HERE — the main window's popups are invisible behind a focused second window.
                NotificationPopupContainer(
                    notifications = viewModel.notifications,
                    onDismiss = { id -> viewModel.dismissNotification(id) },
                )
            }
        }
    }
}

private sealed interface Mode {
    object List : Mode

    object Capture : Mode

    data class Edit(
        val scenario: Scenario,
        /** Index into `scenario.steps` to open on, from the failure → editor deep-link. */
        val focusStep: Int? = null,
        /** The last run's failed tags for the focused step — highlighted in the builder. */
        val failedTags: kotlin.collections.List<com.knapsack.fixtool.model.scenario.TagResult> = emptyList(),
        /** Raw message that failed the assertions — feeds the builder's would-now-pass preview. */
        val actualRaw: String? = null,
        val actualAt: java.time.Instant? = null,
    ) : Mode
}

@Composable
fun ScenarioWorkbench(viewModel: FixMessageViewModel, modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    // Failure → editor deep-link: a one-shot request from the session window (Edit assertion… on a
    // failed message) lands the editor directly on the failing step — no list → edit → hunt.
    val editRequest by viewModel.workbenchEditRequest.collectAsState()
    androidx.compose.runtime.LaunchedEffect(editRequest) {
        editRequest?.let { req ->
            mode = Mode.Edit(req.scenario, req.focusStep, req.failedTags, req.actualRaw, req.actualAt)
            viewModel.consumeWorkbenchEditRequest()
        }
    }
    // Slim interactive targets (checkboxes, icon buttons) to match the app's dense-desktop feel —
    // Material3's default 48dp minimum is a touch-screen convention that fattens every row.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 24.dp,
    ) {
        ScenarioWorkbenchBody(viewModel, mode, onMode = { mode = it }, modifier = modifier)
    }
}

@Composable
private fun ScenarioWorkbenchBody(viewModel: FixMessageViewModel, mode: Mode, onMode: (Mode) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(AppTheme.Colors.background).padding(12.dp)) {
        when (mode) {
            is Mode.List ->
                ScenarioListPane(
                    viewModel = viewModel,
                    onCapture = { onMode(Mode.Capture) },
                    onEdit = { onMode(Mode.Edit(it)) },
                )
            is Mode.Capture -> {
                val scan = remember { viewModel.captureScan() }
                ScenarioCaptureReview(
                    candidates = scan.candidates,
                    unreadable = scan.unreadable,
                    dictionary = viewModel.dictionary,
                    onSave = { name, selection ->
                        val saved = viewModel.saveCapturedSelection(name, selection) != null
                        if (saved) onMode(Mode.List)
                        saved
                    },
                    onBack = { onMode(Mode.List) },
                )
            }
            is Mode.Edit ->
                ScenarioEditor(
                    initial = mode.scenario,
                    dictionary = viewModel.dictionary,
                    sessionOptions = (mode.scenario.sessionsInvolved() + viewModel.sessions.map { it.title }).distinct(),
                    secondInstance = { session, messageType, golden -> viewModel.liveSecondInstance(session, messageType, golden) },
                    focusStep = mode.focusStep,
                    runFailure =
                        if (mode.focusStep != null) {
                            RunFailureContext(mode.failedTags, mode.actualRaw, mode.actualAt)
                        } else {
                            null
                        },
                    onSave = { edited ->
                        viewModel.scenarioService.save(edited)
                        onMode(Mode.List)
                    },
                    onBack = { onMode(Mode.List) },
                )
        }
    }
}

/**
 * A second live occurrence of the same response type (different bytes than the golden), for the
 * editor's verify-generalizes check: a matcher that only passes against its own capture — e.g. an
 * `exact` timestamp — goes red against a genuine second instance.
 */
private fun FixMessageViewModel.liveSecondInstance(session: String?, messageType: String?, golden: String?): MessageView? {
    if (messageType.isNullOrBlank()) return null
    return sessions
        .filter { session == null || it.title == session }
        .flatMap { it.messages.value.filterIsInstance<FixMessage>() }
        // Compared and read as wire bytes, because that is what the golden now stores. Comparing the
        // display string against an SOH golden never matches, so the golden message itself would come back
        // as its own "second instance" — and "Verify generalizes", whose entire job is to re-check the
        // expectation against a *different* message, would have been checking it against the same one and
        // reporting that everything generalizes.
        .lastOrNull { it.direction == FixMessage.Direction.INCOMING && it.messageType == messageType && it.wireRaw != golden }
        ?.let { msg -> msg.wireRaw?.let { RawMessageView(it) } }
}

@Composable
private fun ScenarioListPane(viewModel: FixMessageViewModel, onCapture: () -> Unit, onEdit: (Scenario) -> Unit) {
    var scenarios by remember { mutableStateOf(viewModel.scenarioService.list()) }
    var confirmingDeleteId by remember { mutableStateOf<String?>(null) }
    val running by viewModel.scenarioRunning.collectAsState()

    fun refresh() {
        scenarios = viewModel.scenarioService.list()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Scenarios", color = AppTheme.Colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            // The diffable/shareable story needs a handle: scenarios are plain JSON files.
            SlimButton("Open folder", onClick = { runCatching { java.awt.Desktop.getDesktop().open(viewModel.scenarioService.directory) } })
            SlimButton("Capture from sessions…", onClick = onCapture, color = AppTheme.Colors.success)
        }
        Text(
            text = "Capture records the flow currently in your session windows — you review and trim it before anything is saved. " +
                "Run replays a scenario against the live sessions; results tint the session rows green/red.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        RunReport(viewModel, running)
        if (scenarios.isEmpty()) {
            Text(
                "No scenarios yet. Drive a flow in the main window (manually or via the MCP tools), then Capture from sessions.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                itemsIndexed(scenarios) { _, scenario ->
                    ScenarioRow(
                        scenario = scenario,
                        runEnabled = !running,
                        confirmingDelete = confirmingDeleteId == scenario.id,
                        onRun = { viewModel.runScenario(scenario) },
                        onEdit = { onEdit(scenario) },
                        onDuplicate = {
                            viewModel.scenarioService.save(
                                scenario.copy(id = java.util.UUID.randomUUID().toString(), name = "${scenario.name} (copy)"),
                            )
                            refresh()
                        },
                        onRequestDelete = { confirmingDeleteId = scenario.id },
                        onConfirmDelete = {
                            viewModel.scenarioService.delete(scenario.id)
                            confirmingDeleteId = null
                            refresh()
                        },
                        onCancelDelete = { confirmingDeleteId = null },
                    )
                }
            }
        }
    }
}

/**
 * The last run's verdict, and the route from its failure to the reconcile view. The workbench is already
 * open and already observing `workbenchEditRequest`, so the click lands the editor on the failing step's
 * diff *in place* — no second window, no hunting for the message in the session grid.
 */
@Composable
private fun RunReport(viewModel: FixMessageViewModel, running: Boolean) {
    val lastResult by viewModel.scenarioResult.collectAsState()
    val failure = lastResult?.firstFailure()
    // Recomputed only when the verdict changes: the route reads the saved scenario off disk.
    val route = remember(lastResult) { failure?.let { viewModel.reconcileRoute(it) } }
    RunStatusLine(
        running = running,
        result = lastResult,
        dictionary = viewModel.dictionary,
        route = route,
        onReconcile = { failure?.let { viewModel.openReconcile(it) } },
    )
}

/**
 * The last run's verdict, and — when the failing step can be reconciled — the route to the diff that fixes
 * it. That route is the whole point of the feature, so it belongs *on the failure*, where the failure is
 * announced. Without it the report was a dead end: it named the failed tags and then left the author to go
 * back to the main window and hunt for the message themselves.
 *
 * When there is **no** route, the report says why rather than simply omitting the button. Silently
 * withholding what the tool has already decided is the same mistake in a smaller costume: the author cannot
 * tell "this cannot be reconciled, and here is the reason" from "this feature does not exist".
 */
@Composable
private fun RunStatusLine(
    running: Boolean,
    result: com.knapsack.fixtool.model.scenario.ScenarioResult?,
    dictionary: com.knapsack.fixtool.model.FixDictionary?,
    route: FixMessageViewModel.ReconcileRoute?,
    onReconcile: () -> Unit,
) {
    when {
        running -> Text("Running…", color = AppTheme.Colors.info, fontSize = 12.sp)
        result != null -> {
            val passedSteps = result.steps.count { it.passed }
            val color = if (result.passed) AppTheme.Colors.success else AppTheme.Colors.error
            val verdict = if (result.passed) "PASSED" else "FAILED"
            Text(
                "Last run — ${result.scenario}: $verdict ($passedSteps/${result.steps.size} steps).",
                color = color,
                fontSize = 12.sp,
            )
            // Say WHAT failed, not just that something did — and then offer the way to fix it.
            val firstFailure = result.firstFailure()
            if (firstFailure != null) {
                val failedTags = firstFailure.tags.filterNot { it.passed }
                val tagText = failedTags.take(4).joinToString(", ") { t ->
                    val name = dictionary?.getFieldName(t.tag)?.let { " $it" } ?: ""
                    "${t.tag}$name"
                } + (if (failedTags.size > 4) " +${failedTags.size - 4} more" else "")
                val where = if (firstFailure.stepIndex < 0) firstFailure.kind else "step ${firstFailure.stepIndex + 1} ${firstFailure.kind}"
                val detail = firstFailure.detail?.let { " ($it)" } ?: ""
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(
                        text = "First failure: $where$detail" +
                            (if (failedTags.isNotEmpty()) " — failed tags: $tagText" else ""),
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).testTag("run-failure-line"),
                    )
                    if (route is FixMessageViewModel.ReconcileRoute.Open) {
                        SlimButton(
                            text = "Reconcile assertions →",
                            onClick = onReconcile,
                            color = AppTheme.Colors.error,
                            modifier = Modifier.padding(start = 8.dp).testTag("reconcile-failure"),
                        )
                    }
                }
                if (route is FixMessageViewModel.ReconcileRoute.Refused) {
                    Text(
                        text = route.why,
                        color = AppTheme.Colors.textDisabled,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp).testTag("reconcile-refused"),
                    )
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun ScenarioRow(
    scenario: Scenario,
    runEnabled: Boolean,
    confirmingDelete: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant).padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(scenario.name, color = AppTheme.Colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            val sessions = scenario.sessionsInvolved()
            val detail = buildString {
                append("${scenario.steps.size} steps")
                if (sessions.isNotEmpty()) append(" · ${sessions.joinToString(", ")}")
                scenario.profile?.let { append(" · $it") }
            }
            Text(detail, color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
        if (confirmingDelete) {
            Text("Delete '${scenario.name}'?", color = AppTheme.Colors.error, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            SlimButton("Delete", onClick = onConfirmDelete, color = AppTheme.Colors.error, modifier = Modifier.padding(end = 6.dp).testTag("confirm-delete"))
            SlimButton("Cancel", onClick = onCancelDelete, color = AppTheme.Colors.textSecondary)
        } else {
            IconButton(onClick = onRun, enabled = runEnabled) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = if (runEnabled) AppTheme.Colors.success else AppTheme.Colors.textDisabled)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AppTheme.Colors.textSecondary)
            }
            IconButton(onClick = onDuplicate) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", tint = AppTheme.Colors.textSecondary)
            }
            IconButton(onClick = onRequestDelete, modifier = Modifier.testTag("delete-${scenario.id}")) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AppTheme.Colors.error)
            }
        }
    }
}
