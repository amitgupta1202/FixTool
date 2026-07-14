package com.knapsack.fixtool.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.MatchContextMode
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.MessageEditorState
import com.knapsack.fixtool.model.Notification
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.service.AppSettingsService
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.RawMessageView
import com.knapsack.fixtool.service.ConnectionProfileService
import com.knapsack.fixtool.service.FixMessageHelper.normalizeFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.FixMessageValidator
import com.knapsack.fixtool.service.SavedMessagesService
import com.knapsack.fixtool.service.ScenarioCapture
import com.knapsack.fixtool.service.ScenarioCodec
import com.knapsack.fixtool.service.ScenarioRunner
import com.knapsack.fixtool.service.ScenarioService
import com.knapsack.fixtool.service.SessionIdentityResolver
import com.knapsack.fixtool.service.demo.DemoServerManager
import com.knapsack.fixtool.ui.CaptureReviewState
import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.ui.FixField.Companion.resolveTemplates
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import com.knapsack.fixtool.ui.RunFailureContext
import com.knapsack.fixtool.ui.ScenarioDoc
import com.knapsack.fixtool.ui.asEditorSeed
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

class FixMessageViewModel(
    private val testSettingsDir: String? = null,
) : ViewModel() {
    private val logger =
        NotifyingLogger(
            FixMessageViewModel::class.java,
            onNotify = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
        )

    // Coroutine scope for this ViewModel
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessions = mutableStateListOf<FixMessageSession>()
    val sessions: List<FixMessageSession> = _sessions

    private val _activeSessionIndex = mutableStateOf(-1) // -1 means no session selected
    val activeSessionIndex: Int
        get() = _activeSessionIndex.value
    val activeSessionIndexState: State<Int> = _activeSessionIndex

    val activeSession: FixMessageSession?
        get() = if (_activeSessionIndex.value >= 0) _sessions.getOrNull(_activeSessionIndex.value) else null

    private val _activeSessionState = mutableStateOf<FixMessageSession?>(null)
    val activeSessionState: State<FixMessageSession?> = _activeSessionState

    // Selected profile for message editor (can be set even if profile is disconnected)
    private val _selectedEditorProfile = mutableStateOf<FixConnectionProfile?>(null)
    val selectedEditorProfile: State<FixConnectionProfile?> = _selectedEditorProfile

    private val _dictionary = mutableStateOf(FixDictionaryAdapter.createDefault())
    val dictionary: FixDictionary
        get() = _dictionary.value

    // Current FIX version based on loaded dictionary
    val currentFixVersion: FixVersion
        get() = (_dictionary.value as? FixDictionaryAdapter)?.fixVersion ?: FixVersion.DEFAULT

    // Global message selection state (shared across all panes/sessions)
    private val _selectedMessage = MutableStateFlow<FixMessage?>(null)
    val selectedMessage: StateFlow<FixMessage?> = _selectedMessage.asStateFlow()

    // Global detail panel visibility (shared across all panes/sessions)
    private val _showDetailPanel = MutableStateFlow(false)
    val showDetailPanel: StateFlow<Boolean> = _showDetailPanel.asStateFlow()

    // Detail-panel search state — driven by the in-panel search box and by the automation
    // control surface (/detail), so a nested-tag search and its context mode can be set by an agent.
    private val _detailSearchQuery = MutableStateFlow("")
    val detailSearchQuery: StateFlow<String> = _detailSearchQuery.asStateFlow()

    private val _detailMatchContextMode = MutableStateFlow(MatchContextMode.IDENTITY)
    val detailMatchContextMode: StateFlow<MatchContextMode> = _detailMatchContextMode.asStateFlow()

    // Message editor dialog visibility
    private val _showMessageEditor = MutableStateFlow(false)
    val showMessageEditor: StateFlow<Boolean> = _showMessageEditor.asStateFlow()

    // Connection panel visibility
    private val _showConnectionPanel = MutableStateFlow(false)
    val showConnectionPanel: StateFlow<Boolean> = _showConnectionPanel.asStateFlow()

    // Settings dialog visibility
    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    // Help dialog visibility
    private val _showHelpDialog = MutableStateFlow(false)
    val showHelpDialog: StateFlow<Boolean> = _showHelpDialog.asStateFlow()

    // The Scenarios rail — a docked pane in the main window, not a dialog and no longer a window.
    private val _showScenariosRail = MutableStateFlow(false)
    val showScenariosRail: StateFlow<Boolean> = _showScenariosRail.asStateFlow()

    /**
     * The scenarios on disk. The rail cannot own this list any more.
     *
     * `ScenarioListPane` held it in a `remember` and refreshed itself after every write, which worked only
     * because every write happened inside it. The writes now happen in a *document tab* — Save in the editor,
     * Save in capture review — and a list that only refreshes itself would never hear about them: the author
     * saves, and the rail goes on showing the old step count. One owner, refreshed at every door.
     */
    private val _scenarios = MutableStateFlow<List<Scenario>>(emptyList())
    val scenarios: StateFlow<List<Scenario>> = _scenarios.asStateFlow()

    fun refreshScenarios() {
        _scenarios.value = scenarioService.list()
    }

    // Last scenario run result (drives the in-app red/green report) and the running flag
    private val _scenarioResult = MutableStateFlow<ScenarioResult?>(null)
    val scenarioResult: StateFlow<ScenarioResult?> = _scenarioResult.asStateFlow()
    private val _scenarioRunning = MutableStateFlow(false)
    val scenarioRunning: StateFlow<Boolean> = _scenarioRunning.asStateFlow()

    // Per-message assertion results from the last run — keyed by the matched message — so the session
    // grid can tint rows green/red and the detail panel can show per-tag expected-vs-actual.
    private val _assertionResults = mutableStateOf<Map<FixMessage, StepResult>>(emptyMap())
    val assertionResults: Map<FixMessage, StepResult> get() = _assertionResults.value

    /** Publish per-message assertion results (also used by control-surface runs so they light up the UI). */
    fun setAssertionResults(results: Map<FixMessage, StepResult>) {
        _assertionResults.value = results
    }

    // The scenario snapshot behind the current assertionResults — attribution for the
    // failure → editor deep-link. Results are cleared at run start, so one slot is enough.
    private val _lastRunScenario = MutableStateFlow<Scenario?>(null)
    val lastRunScenario: StateFlow<Scenario?> = _lastRunScenario.asStateFlow()

    /**
     * Record which scenario the upcoming run's results belong to (UI and control-surface runs).
     *
     * Identified on the way in, with the same deterministic assignment the runner and the codec use, so
     * the snapshot of what ran can be compared step-for-step against what is on disk. An un-identified
     * snapshot would have no way to say *which* step failed once the author moved it.
     */
    fun noteScenarioRun(scenario: Scenario) {
        _lastRunScenario.value = scenario.withIds()
    }

    /**
     * Where a failed step is to be opened: the editor on [scenario] at [focusStep] (an index into its
     * `steps`, null = unfocused), with the last run's [failedTags] highlighted and [actualRaw] (the message
     * that failed the assertions) wired into the live preview.
     *
     * It used to be a one-shot flow, because a second *window* had to observe it and could not be told
     * anything directly. There is no second window: the ViewModel opens the document itself.
     */
    data class ScenarioEditRequest(
        val scenario: Scenario,
        val focusStep: Int?,
        val failedTags: List<TagResult> = emptyList(),
        val actualRaw: String? = null,
        /**
         * When the message arrived. Temporal rows are judged against THIS in the reconcile view, never
         * against "now" — a `~now ±60s` row that passed during the run must not read as a venue regression
         * because the engineer took two minutes to click Reconcile.
         */
        val actualAt: java.time.Instant? = null,
    )

    // ---- Scenario documents: the centre pane's tabs, beside the session tabs -------------------------

    private val _openDocuments = MutableStateFlow<List<ScenarioDoc>>(emptyList())
    val openDocuments: StateFlow<List<ScenarioDoc>> = _openDocuments.asStateFlow()

    /**
     * Which document the centre pane is showing — and **null *is* the session view.**
     *
     * The centre's selection is not [activeSessionIndex] and must never become it. A document tab is not a
     * session: if focusing one moved the active session, the message editor's target, `fixtool_send`, the
     * grid's tint and every control-surface call that means "the active session" would follow the author
     * into a scenario document.
     */
    private val _activeDocumentId = MutableStateFlow<String?>(null)
    val activeDocumentId: StateFlow<String?> = _activeDocumentId.asStateFlow()

    val activeDocument: ScenarioDoc?
        get() = _openDocuments.value.firstOrNull { it.id == _activeDocumentId.value }

    /** Back to the sessions — what clicking a session tab means. Leaves the documents open. */
    fun showSessions() {
        _activeDocumentId.value = null
    }

    fun focusDocument(id: String) {
        if (_openDocuments.value.any { it.id == id }) _activeDocumentId.value = id
    }

    /** The document's own state, coming back from the composable that is editing it. See [ScenarioDoc]. */
    fun updateDocument(doc: ScenarioDoc) {
        _openDocuments.value = _openDocuments.value.map { if (it.id == doc.id) doc else it }
    }

    /**
     * Both mirror-backs go through a *transform*, never through a `doc.copy()` taken in the composable.
     *
     * The draft arrives from a `LaunchedEffect` and the cursor from a click handler, so the two can be
     * carrying different snapshots of the same document in the same frame — and a `copy()` off the stale one
     * silently puts the other field back the way it was. A transform reads whatever is there when it lands.
     */
    fun updateEditorDocument(id: String, transform: (ScenarioDoc.Editor) -> ScenarioDoc.Editor) {
        _openDocuments.value =
            _openDocuments.value.map { if (it.id == id && it is ScenarioDoc.Editor) transform(it) else it }
    }

    fun updateCaptureDocument(transform: (ScenarioDoc.Capture) -> ScenarioDoc.Capture) {
        _openDocuments.value = _openDocuments.value.map { if (it is ScenarioDoc.Capture) transform(it) else it }
    }

    /**
     * A second live occurrence of the same response type (different bytes than the golden), for the editor's
     * verify-generalizes check: a matcher that only passes against its own capture — an `exact` timestamp,
     * say — goes red against a genuine second instance.
     */
    fun liveSecondInstance(session: String?, messageType: String?, golden: String?): MessageView? {
        if (messageType.isNullOrBlank()) return null
        return sessions
            .filter { session == null || it.title == session }
            .flatMap { it.messages.value.filterIsInstance<FixMessage>() }
            // Compared and read as wire bytes, because that is what the golden now stores. Comparing the
            // display string against an SOH golden never matches, so the golden message itself would come back
            // as its own "second instance" — and "Verify generalizes", whose entire job is to re-check the
            // expectation against a *different* message, would have been checking it against the same one and
            // reporting that everything generalizes.
            .lastOrNull { candidate ->
                candidate.direction == FixMessage.Direction.INCOMING &&
                    candidate.messageType == messageType &&
                    candidate.wireRaw != golden
            }?.wireRaw
            ?.let { RawMessageView(it) }
    }

    fun closeDocument(id: String) {
        val remaining = _openDocuments.value.filterNot { it.id == id }
        _openDocuments.value = remaining
        if (_activeDocumentId.value == id) _activeDocumentId.value = remaining.lastOrNull()?.id
        if (_confirmingCloseId.value == id) _confirmingCloseId.value = null
    }

    /**
     * The document whose close is waiting on a *"discard edits?"*, and the reason that state is here rather
     * than in the tab strip: `esc` closes the focused document too, and a confirmation only the strip knew
     * about would mean two answers to "is it safe to close this" — one of which never asks.
     */
    private val _confirmingCloseId = MutableStateFlow<String?>(null)
    val confirmingCloseId: StateFlow<String?> = _confirmingCloseId.asStateFlow()

    fun requestCloseDocument(id: String) {
        val doc = _openDocuments.value.firstOrNull { it.id == id } ?: return
        if (doc.dirty) _confirmingCloseId.value = id else closeDocument(id)
    }

    fun cancelCloseDocument() {
        _confirmingCloseId.value = null
    }

    private fun openDocument(doc: ScenarioDoc) {
        val existing = _openDocuments.value.any { it.id == doc.id }
        _openDocuments.value =
            if (existing) {
                _openDocuments.value.map { if (it.id == doc.id) doc else it }
            } else {
                _openDocuments.value + doc
            }
        _activeDocumentId.value = doc.id
    }

    /**
     * Open the flow editor on [scenario] — or **focus the tab already holding it**, re-aimed at [focusStep].
     *
     * Never re-seeded: that tab may be carrying edits the author has not saved, and a second failure in the
     * same scenario is not a reason to throw them away. Only the aim moves — which is what [focusEpoch] is
     * for, since the composable's cursor is seeded once and would otherwise ignore a new one.
     */
    fun openScenarioEditor(scenario: Scenario, focusStep: Int? = null, failure: RunFailureContext? = null) {
        val id = ScenarioDoc.editorId(scenario.id)
        val open = _openDocuments.value.firstOrNull { it.id == id } as? ScenarioDoc.Editor
        val doc =
            if (open != null) {
                open.copy(
                    focusStep = focusStep ?: open.focusStep,
                    failure = failure ?: open.failure,
                    selectedStep = focusStep ?: open.selectedStep,
                    focusEpoch = if (focusStep != null) open.focusEpoch + 1 else open.focusEpoch,
                )
            } else {
                val seed = scenario.asEditorSeed()
                ScenarioDoc.Editor(
                    draft = seed,
                    seed = seed,
                    focusStep = focusStep,
                    failure = failure,
                    selectedStep = focusStep,
                )
            }
        openDocument(doc)
    }

    /** Open capture review on a fresh scan of the sessions — or focus the review already open. */
    fun openCaptureReview() {
        if (_openDocuments.value.any { it.id == ScenarioDoc.CAPTURE_ID }) {
            focusDocument(ScenarioDoc.CAPTURE_ID)
            return
        }
        val scan = captureScan()
        openDocument(ScenarioDoc.Capture(scan, CaptureReviewState.of(scan.candidates.size)))
    }

    /**
     * Write the editor's draft to disk, and leave the tab open on it.
     *
     * A tab is a document, not a modal: Save writes and the document becomes clean. It is re-seeded from what
     * is now *on disk*, so the draft carries the ids [Scenario.withIds] minted for any step the author added
     * — and the tab's dirty flag is measured against the file it actually wrote.
     */
    fun saveScenarioDocument(edited: Scenario): Boolean {
        if (!scenarioService.save(edited)) return false
        val onDisk = (scenarioService.load(edited.id) ?: edited).asEditorSeed()
        (_openDocuments.value.firstOrNull { it.id == ScenarioDoc.editorId(edited.id) } as? ScenarioDoc.Editor)
            ?.let { updateDocument(it.copy(draft = onDisk, seed = onDisk)) }
        return true
    }

    /** Capture review's Save: its document has done its job, so it closes — and the rail has a new scenario. */
    fun saveCaptureDocument(name: String, selection: List<ScenarioCapture.Candidate>): Boolean {
        val id = saveCapturedSelection(name, selection) ?: return false
        closeDocument(ScenarioDoc.CAPTURE_ID)
        scenarioService.load(id)?.let { openScenarioEditor(it) }
        return true
    }

    /** Delete a scenario, and close the tab that was editing it — a document with no file is a trap. */
    fun deleteScenario(id: String) {
        scenarioService.delete(id)
        closeDocument(ScenarioDoc.editorId(id))
    }

    fun duplicateScenario(scenario: Scenario) {
        val copyId = UUID.randomUUID().toString()
        scenarioService.save(scenario.copy(id = copyId, name = "${scenario.name} (copy)"))
    }

    /**
     * Where a failed step can be taken, and — when it cannot be taken to the reconcile view — *why not*,
     * in words meant for the author. A refusal that does not say why is how the run report became a dead
     * end in the first place: the tool knew exactly what it was withholding and said nothing.
     */
    sealed interface ReconcileRoute {
        /** The failure can be reconciled: [request] opens the workbench on its diff. */
        data class Open(
            val request: ScenarioEditRequest,
        ) : ReconcileRoute

        /** The failure cannot be reconciled, and [why] is the sentence the author gets. */
        data class Refused(
            val why: String,
        ) : ReconcileRoute
    }

    /**
     * Can the editor open *on* this run result's step at all? The editor edits `steps` only (run indices
     * are per-phase), and only an Expect carries assertions to reconcile.
     */
    private fun StepResult.isEditableExpect(): Boolean = phase == "steps" && kind == "expect"

    /**
     * **The one decider behind both doors into the reconcile view** — the run report's button and the
     * session window's "Reconcile assertions…". Two deciders would eventually disagree, and the way they
     * disagree here is that one of them offers a route into a diff the other would have refused.
     *
     * A failure is reconcilable only if *all* of this holds:
     *
     * - it is an **Expect in the `steps` phase** — the only thing the editor can focus;
     * - the run **matched a message**: an Expect that timed out has no actual to diff against;
     * - FixTool has that message's **wire bytes**. The venue's field order is otherwise unknown, and the
     *   expectation's row order is half of what it asserts. The engine refuses to judge such a step
     *   ([ScenarioRunner]) and capture refuses to seed from one ([ScenarioCapture.scan]); reconciling one
     *   would diff against `toString()`'s re-sorted body — showing entries the venue never moved, offering
     *   "Accept new order" on them, and saving an order nobody sent;
     * - the scenario is **still saved** (a run of an inline, never-persisted scenario has nothing to edit);
     * - the step that failed is **still in the scenario** — found by [ScenarioStep.stepId], not by index;
     * - and **that step has not itself been edited** since it ran. This last one is not pedantry: the run
     *   result used to address its step by *index*, and nothing invalidates a run when the scenario is
     *   edited. So delete a step above the failure, save, and index 1 now holds a *different* Expect — one
     *   whose assertions would be diffed against the failing step's message, and which "Accept actual"
     *   would then overwrite with bytes the venue never sent *for it*.
     *
     * The old guard caught that by refusing whenever `saved.steps[i] != ran.steps[i]` — correct, and far
     * too wide: it withdrew the fix for the step that failed because the author had renamed a *different*
     * step. Now the step is looked up by identity, so an edit elsewhere in the scenario is what it always
     * was — irrelevant — and the failure still routes, to wherever that step now sits.
     */
    @Suppress("ReturnCount") // One guard per reason, each with its own sentence. Nesting them reads far worse.
    fun reconcileRoute(step: StepResult): ReconcileRoute {
        if (!step.isEditableExpect()) {
            return ReconcileRoute.Refused(
                "Only an Expect in the scenario's main steps can be reconciled — this failure is a " +
                    "${step.kind} in ${step.phase}. Open the message in the session window for expected-vs-actual.",
            )
        }
        val message =
            assertionResults.entries
                .firstOrNull { (_, result) -> result.phase == step.phase && result.stepIndex == step.stepIndex }
                ?.key
                ?: return ReconcileRoute.Refused(
                    "No message matched this step, so there is nothing to diff its expectation against. " +
                        "Fix the bind predicate, or the venue, and run it again.",
                )
        val wire =
            message.wireRaw
                ?: return ReconcileRoute.Refused(
                    "FixTool has no wire bytes for this ${message.messageType}, so the venue's field order is " +
                        "unknown and its assertions cannot be reconciled against it. This is a FixTool " +
                        "limitation, not a venue failure.",
                )
        val ranScenario =
            _lastRunScenario.value
                ?: return ReconcileRoute.Refused("This run is no longer attributed to a scenario — run it again.")
        val saved =
            scenarioService.load(ranScenario.id)
                ?: return ReconcileRoute.Refused(
                    "Scenario '${ranScenario.name}' is not saved — there are no assertions on disk to reconcile.",
                )
        // Which step ran here — by identity. The result's own id where it has one; otherwise the id of the
        // step that sat at its index in the scenario that ran, which is the same thing by construction
        // (both sides go through the same deterministic assignment).
        val ranStep =
            ranScenario.steps.getOrNull(step.stepIndex)
                ?: return ReconcileRoute.Refused(
                    "This run's step ${step.stepIndex + 1} is not in the scenario it was attributed to — " +
                        "run it again and reconcile the new failure.",
                )
        val stepId = step.stepId?.takeIf { it.isNotBlank() } ?: ranStep.stepId

        // The step that failed must still exist...
        val savedIndex = saved.steps.indexOfFirst { it.stepId == stepId }
        if (savedIndex < 0) {
            return ReconcileRoute.Refused(
                "The step that failed is no longer in scenario '${saved.name}' — it has been deleted since " +
                    "this run, so there is nothing to reconcile. Run it again and reconcile the new failure.",
            )
        }
        // ...and it must be the step that ran, not an edited descendant of it. An edit to any OTHER step is
        // none of this decision's business — that is the whole point of asking by id.
        if (saved.steps[savedIndex] != ranStep) {
            return ReconcileRoute.Refused(
                "Step ${savedIndex + 1} of '${saved.name}' has changed since this run, so it is no longer " +
                    "the step that failed. Run it again and reconcile the new failure.",
            )
        }
        return ReconcileRoute.Open(
            ScenarioEditRequest(
                scenario = saved,
                // Where the step is NOW, which is not where it ran if the author has been rearranging.
                focusStep = savedIndex,
                failedTags = step.tags.filterNot { it.passed },
                actualRaw = wire,
                actualAt = message.timestamp.atZone(java.time.ZoneId.systemDefault()).toInstant(),
            ),
        )
    }

    /**
     * Opens the reconcile view for [step], or says why it will not. See [reconcileRoute].
     *
     * The destination is a **document tab** — open-or-focus, scrolled to the failing step. Three doors (the
     * rail, the run line, the message viewer's "Reconcile assertions…") and one destination, because two
     * destinations would eventually be two answers to "which step failed".
     */
    fun openReconcile(step: StepResult) {
        when (val route = reconcileRoute(step)) {
            is ReconcileRoute.Open ->
                openScenarioEditor(
                    scenario = route.request.scenario,
                    focusStep = route.request.focusStep,
                    failure =
                        RunFailureContext(
                            route.request.failedTags,
                            route.request.actualRaw,
                            route.request.actualAt,
                        ),
                )
            is ReconcileRoute.Refused -> showNotification(route.why, NotificationType.WARNING)
        }
    }

    /**
     * The session window's door into the reconcile view: "Reconcile assertions…" on a message whose
     * assertions failed. Routes through [reconcileRoute], so it can never offer what the run report's
     * button refuses, nor refuse what it offers.
     */
    fun openScenarioEditorForFailure(message: FixMessage) {
        val step = assertionResults[message] ?: return
        openReconcile(step)
    }

    // The quick-fix chips that used to live here are gone, and with them the map that backed them.
    //
    // They could only ever fix a *value mismatch*, because the detail panel renders the message that
    // arrived: a tag the venue stopped sending has no row to click, and a reordered group entry looks
    // perfectly fine tag by tag — every value matches, nothing is red, and the step still failed. So it
    // was permanently the incomplete surface, and it taught users that fixing lives in two places.
    //
    // Two editing surfaces are also two chances to rewrite the wrong assertion, which is not
    // hypothetical: this map was keyed globally by (tag, path) and wrote one message's edit into
    // another message's step, and the entry matching underneath it rewrote the second party's
    // assertion when the first party's row was clicked. Fixing now happens in the reconcile view, the
    // only surface that can see the whole failure. The viewer diagnoses; the diff view authors.

    // Latency panel visibility
    private val _showLatencyPanel = MutableStateFlow(false)
    val showLatencyPanel: StateFlow<Boolean> = _showLatencyPanel.asStateFlow()

    // Global search across all sessions
    private val _showGlobalSearchDialog = MutableStateFlow(false)
    val showGlobalSearchDialog: StateFlow<Boolean> = _showGlobalSearchDialog.asStateFlow()

    private val _globalSearchQuery = MutableStateFlow("")
    val globalSearchQuery: StateFlow<String> = _globalSearchQuery.asStateFlow()

    data class SearchResult(
        val session: FixMessageSession,
        val message: FixMessage,
        val matchedText: String,
        val messageTypeDescription: String,
        val msgSeqNum: Int?,
        val senderCompId: String?,
        val sessionUsername: String,
    )

    private val _globalSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val globalSearchResults: StateFlow<List<SearchResult>> = _globalSearchResults.asStateFlow()

    // Search results pane (persistent search results at bottom of screen)
    private val _showSearchResultsPane = MutableStateFlow(false)
    val showSearchResultsPane: StateFlow<Boolean> = _showSearchResultsPane.asStateFlow()

    private val _pinnedSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val pinnedSearchResults: StateFlow<List<SearchResult>> = _pinnedSearchResults.asStateFlow()

    // Global filter across all sessions
    private val _globalFilterRegex = MutableStateFlow("")
    val globalFilterRegex: StateFlow<String> = _globalFilterRegex.asStateFlow()

    private val _globalFilterShowIncoming = MutableStateFlow(true)
    val globalFilterShowIncoming: StateFlow<Boolean> = _globalFilterShowIncoming.asStateFlow()

    private val _globalFilterShowOutgoing = MutableStateFlow(true)
    val globalFilterShowOutgoing: StateFlow<Boolean> = _globalFilterShowOutgoing.asStateFlow()

    // Global view mode (applies to all sessions)
    private val _viewMode = MutableStateFlow(FixMessageSession.ViewMode.PARSED) // Will be initialized from settings

    // Message maps for template expressions - stores latest message of each type
    // These can be referenced in template expressions like: ${incoming["D"].valueOfTag(11)}
    private val _incomingMessagesByType = mutableMapOf<String, FixMessage>()
    val incomingMessagesByType: Map<String, FixMessage>
        get() = _incomingMessagesByType.toMap()

    private val _outgoingMessagesByType = mutableMapOf<String, FixMessage>()
    val outgoingMessagesByType: Map<String, FixMessage>
        get() = _outgoingMessagesByType.toMap()
    val viewMode: StateFlow<FixMessageSession.ViewMode> = _viewMode.asStateFlow()

    // App settings (loaded first before other services)
    private val settingsService =
        AppSettingsService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            customSettingsDir = testSettingsDir,
        )
    private val _appSettings = mutableStateOf(AppSettings.default())
    val appSettings: AppSettings
        get() = _appSettings.value

    // Connection profiles (lazy-initialized to use appSettings paths)
    private val profileService by lazy {
        ConnectionProfileService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            customPath = resolveStoragePath(_appSettings.value.connectionProfilesPath, "connection_profiles.json"),
        )
    }
    private val _connectionProfiles = mutableStateListOf<FixConnectionProfile>()
    val connectionProfiles: List<FixConnectionProfile> = _connectionProfiles

    // Saved messages (lazy-initialized to use appSettings paths)
    private val savedMessagesService by lazy {
        SavedMessagesService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            customPath = resolveStoragePath(_appSettings.value.savedMessagesPath, "saved_messages.json"),
        )
    }
    private val _savedMessages = mutableStateListOf<SavedFixMessage>()
    val savedMessages: List<SavedFixMessage> = _savedMessages

    // Repeatable scenarios — directory store (lazy-initialized to use appSettings paths)
    val scenarioService by lazy {
        ScenarioService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            customDir = resolveStoragePath(_appSettings.value.scenariosPath, "scenarios"),
        )
    }

    /**
     * Resolves where a JSON store (connection profiles, saved messages) is kept. An explicit
     * setting always wins. Otherwise, when constructed with a [testSettingsDir] the store is kept
     * beside that dir's app_settings.json, so tests stay isolated and never read or write the real
     * ~/.fixtool files; in normal use this returns blank and the service applies its own default.
     */
    private fun resolveStoragePath(configured: String, fileName: String): String =
        when {
            configured.isNotBlank() -> configured
            testSettingsDir != null -> java.io.File(testSettingsDir, fileName).absolutePath
            else -> ""
        }

    // Track message editor state (new, clean, dirty)
    private val _editorState =
        MutableStateFlow<MessageEditorState>(
            MessageEditorState.New,
        )
    val editorState: StateFlow<MessageEditorState> = _editorState

    // Backwards compatibility: expose message name from editor state
    val currentLoadedMessageName: StateFlow<String?> =
        _editorState
            .map { it.messageNameOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Track which sessions belong to which profile (a profile owns multiple sessions when sessionCount > 1)
    private val profileToSessionMap = mutableMapOf<String, MutableList<Int>>()

    private fun profileIdForSessionIndex(index: Int): String? = profileToSessionMap.entries.find { index in it.value }?.key

    // Demo server state
    val demoServerRunning: StateFlow<Boolean> = DemoServerManager.isRunning

    // Message editor state (persisted when opening/closing)
    private val _editorFields = mutableStateListOf<FixField>()
    val editorFields: List<FixField> = _editorFields

    private val _editorSelectedFieldIndex = mutableStateOf(0)
    val editorSelectedFieldIndex: Int
        get() = _editorSelectedFieldIndex.value

    // Multi-selection support - stores selected indices as a sorted set
    private val _editorSelectedIndices = mutableStateListOf<Int>()
    val editorSelectedIndices: List<Int> = _editorSelectedIndices

    // Message validation state
    private val _editorValidationErrors = mutableStateListOf<String>()
    val editorValidationErrors: List<String> = _editorValidationErrors

    // Notification state
    private val _notifications = mutableStateListOf<Notification>()
    val notifications: List<Notification> = _notifications

    // Data dictionary validation state
    private val _isDictionaryValid = MutableStateFlow(true)
    val isDictionaryValid: StateFlow<Boolean> = _isDictionaryValid.asStateFlow()

    private val _dictionaryErrorMessage = MutableStateFlow<String?>(null)
    val dictionaryErrorMessage: StateFlow<String?> = _dictionaryErrorMessage.asStateFlow()

    init {
        // Load app settings first (this also loads the data dictionary)
        loadAppSettings()

        // The rail renders this list; nothing else may hold a copy of it — and it is refreshed by the service,
        // not by each caller, because two of the four doors that write a scenario (the control surface and
        // fixtool_save_scenario) do not come through here at all.
        scenarioService.onChanged = { refreshScenarios() }
        refreshScenarios()

        // Initialize global view mode from settings
        _viewMode.value =
            if (appSettings.defaultViewMode.lowercase() == "grid") {
                FixMessageSession.ViewMode.PARSED
            } else {
                FixMessageSession.ViewMode.RAW
            }

        // Validate dictionary on startup
        validateDataDictionary()

        // Load saved connection profiles
        loadConnectionProfiles()

        // Load saved messages once so the in-memory list is populated from startup (the control
        // surface reads it without forcing a per-request disk reload; writes refresh it).
        loadSavedMessagesForActiveSession()

        // Initialize editor with one blank field
        if (_editorFields.isEmpty()) {
            _editorFields.add(FixField())
            _editorSelectedIndices.add(0)
        }

        // Set up demo profile management
        DemoServerManager.onDemoProfilesChanged = { demoProfiles ->
            handleDemoProfilesChanged(demoProfiles)
        }

        // Set up demo template management
        DemoServerManager.onDemoTemplatesChanged = { _ ->
            // Reload saved messages to reflect template changes
            loadSavedMessagesForActiveSession()
        }
    }

    private fun loadAppSettings() {
        _appSettings.value = settingsService.loadSettings()
        // Load data dictionary from app settings after loading settings
        loadDictionaryFromSettings()
    }

    private fun loadDictionaryFromSettings() {
        try {
            val settings = _appSettings.value
            if (settings.useBundledDictionary) {
                // Use bundled dictionary for the configured FIX version
                loadBundledDictionaryForVersion(settings.defaultFixVersion)
            } else {
                // Use custom dictionary path
                val dictionaryPath = settings.defaultDataDictionary
                val transportDictionaryPath = settings.defaultTransportDictionary
                if (dictionaryPath.isNotBlank()) {
                    val dictionaryFile = File(dictionaryPath)
                    if (dictionaryFile.exists()) {
                        // Check if transport dictionary is configured for FIX 5.0+
                        val transportFile =
                            if (transportDictionaryPath.isNotBlank()) {
                                File(transportDictionaryPath).takeIf { it.exists() }
                            } else {
                                null
                            }

                        _dictionary.value = FixDictionaryAdapter.fromFiles(dictionaryFile, transportFile)
                        val loadedVersion = (_dictionary.value as? FixDictionaryAdapter)?.fixVersion
                        logger.info(
                            "Loaded data dictionary for UI from: {} (detected version: {}, transport: {})",
                            dictionaryPath,
                            loadedVersion?.displayName,
                            transportFile?.absolutePath ?: "none",
                        )

                        // Warn if FIX 5.0+ but no transport dictionary
                        if (loadedVersion?.isFix50Plus == true && transportFile == null) {
                            showNotification(
                                "FIX 5.0+ requires a transport dictionary (FIXT11.xml). Please configure it in Settings.",
                                NotificationType.WARNING,
                            )
                        }

                        _isDictionaryValid.value = true
                        _dictionaryErrorMessage.value = null
                    } else {
                        logger.warn(
                            "Data dictionary file not found: {}, falling back to bundled {}",
                            dictionaryPath,
                            settings.defaultFixVersion.displayName,
                        )
                        showNotification(
                            "Custom dictionary not found at $dictionaryPath, using bundled ${settings.defaultFixVersion.displayName}",
                            NotificationType.WARNING,
                        )
                        loadBundledDictionaryForVersion(settings.defaultFixVersion)
                    }
                } else {
                    // No custom dictionary configured - use bundled dictionary for default version
                    logger.info("No custom data dictionary configured, using bundled {}", settings.defaultFixVersion.displayName)
                    loadBundledDictionaryForVersion(settings.defaultFixVersion)
                }
            }
        } catch (e: Exception) {
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Failed to load data dictionary: ${e.message}"
            logger.error("Failed to load data dictionary: ${e.message}", e, notifyUser = true)
            // Try bundled dictionary as last resort
            showNotification(
                "Failed to load custom dictionary, using bundled ${FixVersion.DEFAULT.displayName}",
                NotificationType.WARNING,
            )
            loadBundledDictionaryForVersion(FixVersion.DEFAULT)
        }
    }

    /**
     * Loads the bundled FIX dictionary for the default version.
     * This is used as the default when no custom dictionary is configured.
     */
    private fun loadBundledDictionary() {
        loadBundledDictionaryForVersion(FixVersion.DEFAULT)
    }

    /**
     * Loads the bundled FIX dictionary for a specific version.
     * For FIX 5.0+, this also loads the FIXT.1.1 transport dictionary.
     *
     * @param version The FIX version to load
     */
    fun loadBundledDictionaryForVersion(version: FixVersion) {
        try {
            _dictionary.value = FixDictionaryAdapter.forVersion(version)
            if (_dictionary.value.isLoaded()) {
                logger.info("Loaded bundled ${version.displayName} dictionary")
                _isDictionaryValid.value = true
                _dictionaryErrorMessage.value = null
            } else {
                logger.error("Failed to load bundled ${version.displayName} dictionary")
                _isDictionaryValid.value = false
                _dictionaryErrorMessage.value = "Failed to load bundled ${version.displayName} dictionary"
            }
        } catch (e: Exception) {
            logger.error("Failed to load bundled dictionary for ${version.displayName}: ${e.message}", e)
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Failed to load bundled ${version.displayName} dictionary: ${e.message}"
        }
    }

    /** Switches the active data dictionary to a bundled FIX version (for automation/control). */
    fun switchDictionaryToVersion(version: FixVersion) = loadBundledDictionaryForVersion(version)

    /** Switches the active data dictionary to a custom file (plus optional FIXT transport file). */
    fun switchDictionaryToFile(path: String, transportPath: String? = null) {
        val file = java.io.File(path)
        if (!file.exists()) {
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Dictionary file not found: $path"
            return
        }
        try {
            val transportFile = transportPath?.let { java.io.File(it).takeIf(java.io.File::exists) }
            _dictionary.value = FixDictionaryAdapter.fromFiles(file, transportFile)
            _isDictionaryValid.value = _dictionary.value.isLoaded()
            _dictionaryErrorMessage.value = if (_isDictionaryValid.value) null else "Failed to load dictionary: $path"
        } catch (e: Exception) {
            logger.error("Failed to load custom dictionary $path: ${e.message}", e)
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Failed to load dictionary: ${e.message}"
        }
    }

    /**
     * Validates the data dictionary configuration and shows an error notification if invalid
     */
    private fun validateDataDictionary() {
        if (!_isDictionaryValid.value) {
            val errorMsg = _dictionaryErrorMessage.value ?: "Data dictionary is not configured"
            showNotification(errorMsg, NotificationType.ERROR)
        }
    }

    private fun createNewSession(
        title: String = "Session",
        sessionQualifier: String = "",
        profileSlot: Int = 0,
    ): FixMessageSession {
        val session =
            FixMessageSession(
                title = title,
                sessionQualifier = sessionQualifier,
                profileSlot = profileSlot,
                bufferSize = _appSettings.value.sessionBufferSize,
                onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
                onWarning = { warning -> showNotification(warning, NotificationType.WARNING) },
            )
        _sessions.add(session)
        // Do NOT auto-select session on connect - let user or template loading do it
        return session
    }

    fun closeSession(index: Int) {
        if (index in _sessions.indices) {
            // Remove this session from its profile's group mapping
            profileToSessionMap.values.forEach { indices -> indices.removeAll { it == index } }
            profileToSessionMap.entries.removeIf { it.value.isEmpty() }

            _sessions[index].destroy()
            _sessions.removeAt(index)

            // Adjust active index if needed
            if (_sessions.isEmpty()) {
                _activeSessionIndex.value = -1 // No sessions, so no selection
                _activeSessionState.value = null
            } else if (_activeSessionIndex.value >= _sessions.size) {
                _activeSessionIndex.value = _sessions.size - 1
                _activeSessionState.value = _sessions.getOrNull(_activeSessionIndex.value)
            } else if (_activeSessionIndex.value > index) {
                _activeSessionIndex.value--
                _activeSessionState.value = _sessions.getOrNull(_activeSessionIndex.value)
            } else if (_activeSessionIndex.value == index) {
                // If closing the active session, select the first available session
                _activeSessionIndex.value = 0
                _activeSessionState.value = _sessions.getOrNull(0)
            }

            // Adjust all session indices in the map that are greater than the closed index
            profileToSessionMap.values.forEach { indices ->
                for (i in indices.indices) {
                    if (indices[i] > index) indices[i] = indices[i] - 1
                }
            }
        }
    }

    fun setActiveSession(index: Int) {
        if (index == -1 || index in _sessions.indices) {
            val session = if (index >= 0) _sessions.getOrNull(index) else null
            logger.info("setActiveSession(index=$index): Switching to session: ${session?.title} (ID: ${session?.id})")
            _activeSessionIndex.value = index
            _activeSessionState.value = session

            // Sync selectedEditorProfile to match the selected session (if enabled)
            if (_appSettings.value.autoSyncSessionToEditor) {
                val profileId = profileIdForSessionIndex(index)
                val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
                _selectedEditorProfile.value = profile
                logger.info("setActiveSession: Updated selectedEditorProfile to: ${profile?.name} (ID: ${profile?.id})")
            } else {
                logger.info("setActiveSession: Auto-sync to editor disabled, skipping profile update")
            }

            // Reload messages when session selection changes
            loadSavedMessagesForActiveSession()
        }
    }

    fun setActiveSessionByObject(session: FixMessageSession?) {
        logger.info("setActiveSessionByObject: Switching to session: ${session?.title} (ID: ${session?.id})")
        if (session == null) {
            _activeSessionIndex.value = -1
            _activeSessionState.value = null
            if (_appSettings.value.autoSyncSessionToEditor) {
                _selectedEditorProfile.value = null
                logger.info("setActiveSessionByObject: Cleared selectedEditorProfile")
            }
        } else {
            val index = _sessions.indexOf(session)
            if (index >= 0) {
                logger.info("setActiveSessionByObject: Found session at index $index")
                _activeSessionIndex.value = index
                _activeSessionState.value = session

                // Sync selectedEditorProfile to match the selected session (if enabled)
                if (_appSettings.value.autoSyncSessionToEditor) {
                    val profileId = profileIdForSessionIndex(index)
                    val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
                    _selectedEditorProfile.value = profile
                    logger.info("setActiveSessionByObject: Updated selectedEditorProfile to: ${profile?.name} (ID: ${profile?.id})")
                } else {
                    logger.info("setActiveSessionByObject: Auto-sync to editor disabled, skipping profile update")
                }
            } else {
                logger.warn("setActiveSessionByObject: Session not found in sessions list!")
            }
        }
        loadSavedMessagesForActiveSession()
    }

    /**
     * Sets the selected profile for the message editor.
     * This can be a connected or disconnected profile.
     * If the profile has a session, that session will also be made active.
     * A profile that has never been connected owns no session: nothing can be sent, so no
     * session is made active, but the editor keeps naming the profile that was chosen.
     */
    fun setSelectedEditorProfile(profile: FixConnectionProfile?) {
        logger.info("setSelectedEditorProfile: ${profile?.name} (ID: ${profile?.id})")
        _selectedEditorProfile.value = profile

        if (profile == null) {
            // No profile selected, clear active session
            setActiveSessionByObject(null)
            return
        }

        val session = getProfileSession(profile.id)
        setActiveSessionByObject(session)
        if (session == null) {
            // setActiveSessionByObject(null) cleared the editor profile via auto-sync; the
            // dropdown must still name the profile that was picked, or the choice vanishes.
            _selectedEditorProfile.value = profile
            showNotification("${profile.name} has no session - connect it to send", NotificationType.WARNING)
        }
    }

    fun selectMessage(message: FixMessage?) {
        _selectedMessage.value = message
        // Auto-show detail panel when a message is selected
        if (message != null && !_showDetailPanel.value) {
            _showDetailPanel.value = true
        }
        // Auto-select the tab/session that contains this message
        if (message != null) {
            val sessionIndex =
                _sessions.indexOfFirst { session ->
                    session.messages.value.contains(message)
                }
            if (sessionIndex >= 0 && sessionIndex != _activeSessionIndex.value) {
                _activeSessionIndex.value = sessionIndex
                _activeSessionState.value = _sessions.getOrNull(sessionIndex)

                // Sync selectedEditorProfile to match the selected session (if enabled)
                if (_appSettings.value.autoSyncSessionToEditor) {
                    val profileId = profileIdForSessionIndex(sessionIndex)
                    val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
                    _selectedEditorProfile.value = profile
                }
            }
        }
    }

    fun pasteAndDisplayMessage(rawMessage: String) {
        try {
            // Normalize the message format (supports both traditional and line-based formats)
            val normalizedMessage = rawMessage.normalizeFixMessage()

            // Parse the raw message using the loaded data dictionary
            val dataDictionary = _dictionary.value.getDataDictionary()

            // Parse the message
            val quickfixMessage =
                if (dataDictionary != null) {
                    normalizedMessage.toQuickFixMessage(dataDictionary)
                } else {
                    // Parse without validation if no data dictionary
                    normalizedMessage.toQuickFixMessage()
                }

            // Create a FixMessage object for display (not connected to any session)
            val fixMessage =
                FixMessage(
                    timestamp = java.time.LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING, // Default to incoming for pasted messages
                    rawMessage = normalizedMessage,
                    quickfixMessage = quickfixMessage,
                )

            // Select and display the pasted message
            selectMessage(fixMessage)
        } catch (e: Exception) {
            logger.error("Failed to parse message: ${e.message}", e, notifyUser = true)
        }
    }

    fun toggleDetailPanel() {
        _showDetailPanel.value = !_showDetailPanel.value
    }

    /**
     * Sets the message-detail-panel search query and/or match-context mode. A null argument leaves
     * that value unchanged. Backs both the in-panel search box and the control surface's /detail
     * endpoint.
     */
    fun setDetailSearch(query: String? = null, mode: MatchContextMode? = null) {
        if (query != null) _detailSearchQuery.value = query
        if (mode != null) _detailMatchContextMode.value = mode
    }

    fun toggleMessageEditor() {
        _showMessageEditor.value = !_showMessageEditor.value
    }

    fun toggleConnectionPanel() {
        _showConnectionPanel.value = !_showConnectionPanel.value
    }

    fun toggleHideProtocolTags() {
        val updatedSettings = appSettings.copy(hideProtocolTags = !appSettings.hideProtocolTags)
        saveAppSettings(updatedSettings)
    }

    fun toggleSettingsDialog() {
        _showSettingsDialog.value = !_showSettingsDialog.value
    }

    fun toggleHelpDialog() {
        _showHelpDialog.value = !_showHelpDialog.value
    }

    fun toggleScenariosRail() {
        _showScenariosRail.value = !_showScenariosRail.value
    }

    /**
     * Runs a saved scenario deterministically off the UI thread (the runner blocks on polling) and
     * publishes the per-step / per-tag [ScenarioResult] to [scenarioResult] for the red/green overlay.
     */
    /**
     * Claims the single run slot (shared by the UI and the control surface, whose runners would
     * otherwise race each other's consumed-message cursors). Pair with [endScenarioRun].
     */
    fun beginScenarioRun(): Boolean = _scenarioRunning.compareAndSet(expect = false, update = true)

    fun endScenarioRun() {
        _scenarioRunning.value = false
    }

    /**
     * Publish the last run's verdict. The workbench's run report — and the route from a failed step to the
     * reconcile view, which hangs off it — reads this. Production code publishes only through
     * [runScenarioBlocking]; it is open for tests that stage a run's aftermath without running one.
     */
    fun publishScenarioResult(result: ScenarioResult?) {
        _scenarioResult.value = result
    }

    /**
     * **The** scenario run: claims the single run slot, clears the last verdict, runs [scenario] on the
     * calling thread, and publishes everything the UI hangs off a run (per-message assertion results, then
     * the verdict) **before** releasing the slot. Returns null if a run is already in progress.
     *
     * Both callers use this — the UI's [runScenario] and the control surface's `/scenarios/run`. They used
     * to keep their own copies of this sequence, and the copies drifted: the control surface published the
     * per-message results but never the verdict, so an agent- or curl-driven run tinted the session grid red
     * and left the run report — and with it the only route to the reconcile view — completely blank. A
     * sequence that must happen identically in two places will eventually happen differently in two places.
     *
     * A throw is re-raised (the control surface answers HTTP 500, the UI logs) but is *notified* first,
     * because the alternative is a wiped report with no explanation next to a grid full of red rows.
     */
    @Suppress("TooGenericExceptionCaught")
    fun runScenarioBlocking(scenario: Scenario): ScenarioResult? {
        if (!beginScenarioRun()) return null
        noteScenarioRun(scenario)
        publishScenarioResult(null)
        setAssertionResults(emptyMap())
        val matched = linkedMapOf<FixMessage, StepResult>()
        try {
            val result =
                ScenarioRunner(
                    ViewModelScenarioHost(this),
                    onExpectMatched = { message, stepResult ->
                        matched[message] = stepResult
                        _assertionResults.value = matched.toMap()
                    },
                ).run(scenario)
            // Published while the run slot is still held: a verdict that lands after the slot is free can
            // land on top of the *next* run's freshly-cleared state, and the report would then name one
            // run while the assertion results underneath it belong to another.
            publishScenarioResult(result)
            return result
        } catch (e: Exception) {
            showNotification("Scenario run failed: ${e.message}", NotificationType.ERROR)
            throw e
        } finally {
            endScenarioRun()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun runScenario(scenario: Scenario) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runScenarioBlocking(scenario)
            } catch (e: Exception) {
                logger.error("Scenario run failed: ${e.message}", e, notifyUser = false) // already notified
            }
        }
    }

    /**
     * Capture the current session message flow into a saved scenario (capture-driven authoring).
     * Captures all sessions by default, or only [sessionTitles]. Returns the new scenario id, or null.
     */
    fun captureScenarioFromSessions(name: String, sessionTitles: List<String>? = null): String? {
        val chosen = if (sessionTitles.isNullOrEmpty()) _sessions.toList() else _sessions.filter { it.title in sessionTitles }
        if (chosen.isEmpty()) {
            showNotification("No sessions to capture", NotificationType.ERROR)
            return null
        }
        val captured = chosen.map { ScenarioCapture.CapturedSession(it.title, it.messages.value.filterIsInstance<FixMessage>()) }
        val scenario =
            ScenarioCapture.capture(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                profile = null,
                sessions = captured,
                dictionary = dictionary,
            )
        return if (scenarioService.save(scenario)) scenario.id else null
    }

    /**
     * The capture-review rows: every business message across all sessions, oldest first, **plus whatever
     * had to be left out**. A snapshot — the review screen curates a stable list, not a live feed.
     *
     * The omissions come back with the rows rather than being dropped here, because a capture is a claim
     * about coverage. A message FixTool cannot read is a message the scenario will not check, and an author
     * who is not told has been handed a test that looks complete and is not.
     */
    fun captureScan(): ScenarioCapture.Scan =
        ScenarioCapture.scan(
            _sessions.map { ScenarioCapture.CapturedSession(it.title, it.messages.value.filterIsInstance<FixMessage>()) },
        )

    /** Persist a curated capture selection as a scenario (the review screen's Save); id or null. */
    fun saveCapturedSelection(name: String, selection: List<ScenarioCapture.Candidate>): String? {
        if (selection.isEmpty()) {
            showNotification("No messages selected to capture", NotificationType.ERROR)
            return null
        }
        val scenario =
            ScenarioCapture.captureFrom(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                profile = null,
                selection = selection,
                dictionary = dictionary,
            )
        return if (scenarioService.save(scenario)) scenario.id else null
    }

    /** Persist a scenario (used by the Scenarios dialog's "save from JSON"); returns its id or null. */
    @Suppress("TooGenericExceptionCaught")
    fun saveScenarioJson(json: String): String? =
        try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val withId =
                if (obj["id"] != null) {
                    obj
                } else {
                    JsonObject(obj + ("id" to JsonPrimitive(java.util.UUID.randomUUID().toString())))
                }
            val scenario = ScenarioCodec.fromJson(withId)
            if (scenarioService.save(scenario)) scenario.id else null
        } catch (e: Exception) {
            showNotification("Invalid scenario JSON: ${e.message}", NotificationType.ERROR)
            null
        }

    fun toggleLatencyPanel() {
        _showLatencyPanel.value = !_showLatencyPanel.value
    }

    fun toggleGlobalSearchDialog() {
        _showGlobalSearchDialog.value = !_showGlobalSearchDialog.value
        // Clear results when closing
        if (!_showGlobalSearchDialog.value) {
            _globalSearchResults.value = emptyList()
            _globalSearchQuery.value = ""
        }
    }

    fun pinSearchResults() {
        // Pin current search results to the pane and show it
        _pinnedSearchResults.value = _globalSearchResults.value
        _showSearchResultsPane.value = true
    }

    fun closeSearchResultsPane() {
        _showSearchResultsPane.value = false
        _pinnedSearchResults.value = emptyList()
    }

    fun setGlobalSearchQuery(query: String) {
        _globalSearchQuery.value = query
        performGlobalSearch(query)
    }

    private fun performGlobalSearch(query: String) {
        if (query.isBlank()) {
            _globalSearchResults.value = emptyList()
            return
        }

        val results = mutableListOf<SearchResult>()
        val regex =
            try {
                Regex(query, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                // Invalid regex, use literal string matching
                null
            }

        _sessions.forEach { session ->
            session.messages.value.forEach { appMessage ->
                if (appMessage is FixMessage) {
                    val displayText = appMessage.toDisplayString()
                    val matchedText =
                        if (regex != null) {
                            regex.find(displayText)?.value
                        } else {
                            if (displayText.contains(query, ignoreCase = true)) query else null
                        }

                    if (matchedText != null) {
                        // Extract message type description
                        val messageTypeDescription =
                            _dictionary.value.getFieldValueDescription(35, appMessage.messageType)
                                ?: appMessage.messageType

                        // Extract MsgSeqNum (tag 34) from header
                        val msgSeqNum =
                            try {
                                if (appMessage.quickfixMessage.header.isSetField(34)) {
                                    appMessage.quickfixMessage.header.getInt(34)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }

                        // Extract SenderCompID (tag 49) from header
                        val senderCompId =
                            try {
                                if (appMessage.quickfixMessage.header.isSetField(49)) {
                                    appMessage.quickfixMessage.header.getString(49)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }

                        results.add(
                            SearchResult(
                                session = session,
                                message = appMessage,
                                matchedText = matchedText,
                                messageTypeDescription = messageTypeDescription,
                                msgSeqNum = msgSeqNum,
                                senderCompId = senderCompId,
                                sessionUsername = session.title,
                            ),
                        )
                    }
                }
            }
        }

        // Sort by timestamp, then MsgSeqNum, then SenderCompID
        val sortedResults =
            results.sortedWith(
                compareBy<SearchResult> { it.message.timestamp }
                    .thenBy(nullsLast()) { it.msgSeqNum }
                    .thenBy(nullsLast()) { it.senderCompId },
            )

        _globalSearchResults.value = sortedResults
    }

    fun navigateToSearchResult(result: SearchResult) {
        // Switch to the session containing the result
        val sessionIndex = _sessions.indexOf(result.session)
        if (sessionIndex >= 0) {
            setActiveSession(sessionIndex)
            // Select the message
            selectMessage(result.message)
        }
    }

    fun toggleViewMode() {
        // Toggle global view mode (applies to all sessions)
        _viewMode.value =
            when (_viewMode.value) {
                FixMessageSession.ViewMode.RAW -> FixMessageSession.ViewMode.PARSED
                FixMessageSession.ViewMode.PARSED -> FixMessageSession.ViewMode.RAW
            }
    }

    /**
     * Set by the app entry point ([main]) to start/stop the automation control server when the
     * Automation Control setting changes (the ViewModel doesn't own the window the server needs).
     */
    var automationControlHook: ((enabled: Boolean, port: Int) -> Unit)? = null

    fun saveAppSettings(settings: AppSettings) {
        _appSettings.value = settings
        if (!settingsService.saveSettings(settings)) {
            logger.error("Failed to save application settings")
        }
        // Reload dictionary when settings change
        loadDictionaryFromSettings()
        // Validate the new dictionary
        validateDataDictionary()
        // Start/stop the automation control server to match the new setting
        automationControlHook?.invoke(settings.automationControlEnabled, settings.automationControlPort)
    }

    /**
     * Updates the message maps with the latest messages from all sessions.
     * This is called before template evaluation to ensure templates can reference recent messages.
     */
    fun updateMessageMaps() {
        _incomingMessagesByType.clear()
        _outgoingMessagesByType.clear()

        // Use per-session caches to avoid rescanning full message histories
        _sessions.forEach { session ->
            _incomingMessagesByType.putAll(session.snapshotLatestIncomingByType())
            _outgoingMessagesByType.putAll(session.snapshotLatestOutgoingByType())
        }
    }

    data class SessionSendOutcome(
        val session: FixMessageSession,
        val result: com.knapsack.fixtool.service.SendResult,
    )

    /**
     * Template variables describing one session, available to message template expressions
     * at send time - e.g. 262=MD-${sessionIndex} for a unique MDReqID per session.
     */
    fun sessionTemplateVariables(session: FixMessageSession, index: Int): Map<String, String> =
        mapOf(
            "sessionIndex" to index.toString(),
            "sessionQualifier" to session.sessionQualifier,
            "sessionTitle" to session.title,
            "sessionSenderCompID" to (session.currentConfig?.senderCompID ?: ""),
        )

    /**
     * Sends one message to every logged-on session. Template expressions are re-resolved
     * per session, so dynamic values (UUIDs, timestamps) are unique per session and the
     * per-session variables from [sessionTemplateVariables] are available.
     */
    fun sendMessageToAllConnectedSessions(fields: List<FixField>): List<SessionSendOutcome> {
        updateMessageMaps()
        val targets = _sessions.filter { it.connectionState.value == FixConnectionState.LOGGED_ON }
        if (targets.isEmpty()) {
            showNotification("No logged-on session to send to", NotificationType.WARNING)
            return emptyList()
        }

        val outcomes =
            targets.mapIndexed { index, session ->
                val resolvedFields =
                    fields.resolveTemplates(
                        incomingMessages = incomingMessagesByType,
                        outgoingMessages = outgoingMessagesByType,
                        dictionary = getDictionaryAdapter(),
                        seedVariables = sessionTemplateVariables(session, index + 1),
                    )
                val result = session.sendFixMessage(resolvedFields.toRawMessage(), _dictionary.value)
                logger.info("sendMessageToAllConnectedSessions: sent to '${session.title}', result: $result")
                SessionSendOutcome(session, result)
            }

        val failed = outcomes.filter { it.result is com.knapsack.fixtool.service.SendResult.Failed }
        if (failed.isEmpty()) {
            showNotification("Message sent to ${outcomes.size} session(s)", NotificationType.SUCCESS)
        } else {
            showNotification(
                "Sent to ${outcomes.size - failed.size}/${outcomes.size} sessions - " +
                    "failed: ${failed.joinToString { it.session.title }}",
                NotificationType.WARNING,
            )
        }
        return outcomes
    }

    fun sendMessage(rawMessage: String): com.knapsack.fixtool.service.SendResult? {
        // Use the currently active session to send message
        logger.info("sendMessage called. Active session index: ${_activeSessionIndex.value}")
        logger.info("sendMessage: _activeSessionState.value = ${_activeSessionState.value?.title} (ID: ${_activeSessionState.value?.id})")
        logger.info("sendMessage: activeSession computed = ${activeSession?.title} (ID: ${activeSession?.id})")

        // Use _activeSessionState directly instead of computed activeSession
        val session = _activeSessionState.value
        if (session == null) {
            logger.error(
                "sendMessage: No active session found! activeSessionIndex=${_activeSessionIndex.value}, sessions.size=${_sessions.size}",
                notifyUser = true,
            )
            return null
        } else {
            logger.info("sendMessage: Sending to session: '${session.title}' (ID: ${session.id})")
            val result = session.sendFixMessage(rawMessage, _dictionary.value)
            logger.info("sendMessage: Message sent to ${session.title}, result: $result")
            return result
        }
    }

    /** Parses a raw FIX string into editor fields (so template expressions can be resolved). */
    private fun rawToFields(raw: String): List<FixField> =
        com.knapsack.fixtool.service.FixMessageHelper
            .parseFixMessage(raw)
            .map { (tag, value) -> FixField(tag = tag.toString(), value = value) }

    /**
     * Resolves template expressions in [raw] against the session at [sessionIndex] (per-session
     * variables, latest in/out messages) and sends the resolved message — the same path the editor
     * "Send" button uses, exposed for automation. Returns null if the session index is invalid.
     */
    fun sendResolvedToSession(raw: String, sessionIndex: Int): com.knapsack.fixtool.service.SendResult? {
        val session = _sessions.getOrNull(sessionIndex) ?: return null
        updateMessageMaps()
        val resolved =
            rawToFields(raw).resolveTemplates(
                incomingMessages = incomingMessagesByType,
                outgoingMessages = outgoingMessagesByType,
                dictionary = getDictionaryAdapter(),
                seedVariables = sessionTemplateVariables(session, sessionIndex + 1),
            )
        return session.sendFixMessage(resolved.toRawMessage(), _dictionary.value)
    }

    /** Raw-string overload of [sendMessageToAllConnectedSessions]; resolves per session. */
    fun sendMessageToAllConnectedSessions(raw: String): List<SessionSendOutcome> =
        sendMessageToAllConnectedSessions(rawToFields(raw))

    // Connection management methods
    fun connectProfile(profileId: String, profile: FixConnectionProfile) {
        // Acceptors bind a single listen port, so they always run as one session
        val targetCount =
            if (profile.config.connectionType == FixConnectionConfig.ConnectionType.INITIATOR) {
                profile.config.sessionCount.coerceAtLeast(1)
            } else {
                1
            }

        val identityErrors = SessionIdentityResolver.validate(profile.config, targetCount)
        if (identityErrors.isNotEmpty()) {
            identityErrors.forEach { showNotification(it, NotificationType.ERROR) }
            return
        }

        val existingIndices = profileToSessionMap[profileId]?.filter { it in _sessions.indices }.orEmpty()
        val reconnected = reconnectExistingSessions(existingIndices, profile, targetCount)
        val created = createMissingSessions(profileId, existingIndices, profile, targetCount)

        // Auto-select profile and activate session if none is currently selected
        if ((reconnected || created) && _selectedEditorProfile.value == null) {
            logger.info("Auto-selecting profile '{}' in message editor", profile.name)
            setSelectedEditorProfile(profile)
        }
    }

    /**
     * Reconnects a profile's existing sessions that are down; sessions already connecting or
     * connected are left alone. Each session re-resolves its slot's identity from the profile
     * so config edits take effect on reconnect.
     * @return true if at least one session was reconnected
     */
    private fun reconnectExistingSessions(existingIndices: List<Int>, profile: FixConnectionProfile, targetCount: Int): Boolean {
        var reconnected = false
        existingIndices.forEach { index ->
            val session = _sessions[index]
            val currentState = session.connectionState.value
            if (currentState == FixConnectionState.CONNECTING ||
                currentState == FixConnectionState.CONNECTED ||
                currentState == FixConnectionState.LOGGED_ON
            ) {
                logger.info("Session already connecting/connected: {}", session.title)
            } else {
                logger.info("Reconnecting session: {}", session.title)
                val config =
                    if (session.profileSlot > 0) {
                        SessionIdentityResolver.resolve(profile.config, session.profileSlot, targetCount.coerceAtLeast(session.profileSlot))
                    } else {
                        profile.config
                    }
                enableLatencyTrackingIfConfigured(session)
                session.connect(config, _appSettings.value, _dictionary.value)
                reconnected = true
            }
        }
        return reconnected
    }

    /**
     * Creates sessions for any slots of the profile's group not yet occupied, up to [targetCount].
     * @return true if at least one session was created
     */
    private fun createMissingSessions(
        profileId: String,
        existingIndices: List<Int>,
        profile: FixConnectionProfile,
        targetCount: Int,
    ): Boolean {
        if (existingIndices.size >= targetCount) return false

        val usedSlots = existingIndices.mapTo(mutableSetOf()) { _sessions[it].profileSlot }
        val freeSlots = (1..targetCount).filter { it !in usedSlots }

        freeSlots.take(targetCount - existingIndices.size).forEach { slot ->
            val isMultiSession = targetCount > 1
            val config =
                if (isMultiSession) SessionIdentityResolver.resolve(profile.config, slot, targetCount) else profile.config
            val title = if (isMultiSession) "${profile.name} [$slot]" else profile.name

            logger.info(
                "Creating new session '{}' for profile: {} (SenderCompID: {}, qualifier: '{}')",
                title,
                profile.name,
                config.senderCompID,
                config.sessionQualifier,
            )
            val session = createNewSession(title, config.sessionQualifier, profileSlot = if (isMultiSession) slot else 0)
            profileToSessionMap.getOrPut(profileId) { mutableListOf() }.add(_sessions.size - 1)

            enableLatencyTrackingIfConfigured(session)
            session.connect(config, _appSettings.value, _dictionary.value)
        }
        return true
    }

    private fun enableLatencyTrackingIfConfigured(session: FixMessageSession) {
        if (_appSettings.value.enableLatencyTracking) {
            session.enableLatencyTracking(
                correlationTags = _appSettings.value.latencyCorrelationTags,
                historySize = _appSettings.value.latencyHistorySize,
                warningThresholdMicros = _appSettings.value.latencyWarningThresholdMicros,
                criticalThresholdMicros = _appSettings.value.latencyCriticalThresholdMicros,
                networkInterface = _appSettings.value.captureNetworkInterface.ifBlank { null },
            )
        }
    }

    fun disconnectProfile(profileId: String) {
        getProfileSessions(profileId).forEach { it.disconnect() }
    }

    /**
     * Disconnects all active sessions
     * Called during app shutdown to gracefully logout from all servers
     */
    fun disconnectAllSessions() {
        logger.info("Disconnecting all sessions (${_sessions.size})")
        _sessions.forEach { session ->
            try {
                session.disconnect()
            } catch (e: Exception) {
                logger.error("Error disconnecting session ${session.title}: ${e.message}", e, notifyUser = false)
            }
        }
    }

    fun getProfileConnectionState(profileId: String): FixConnectionState {
        // For multi-session profiles, report the most-connected state across the group
        val states = getProfileSessions(profileId).map { it.connectionState.value }
        return states.minByOrNull { connectionStateRank(it) } ?: FixConnectionState.DISCONNECTED
    }

    private fun connectionStateRank(state: FixConnectionState): Int =
        when (state) {
            FixConnectionState.LOGGED_ON -> 0
            FixConnectionState.CONNECTED -> 1
            FixConnectionState.CONNECTING -> 2
            FixConnectionState.ERROR -> 3
            FixConnectionState.DISCONNECTED -> 4
        }

    /**
     * Returns priority for connection state sorting.
     * Lower value = higher priority.
     */
    private fun getConnectionPriority(state: FixConnectionState): Int =
        when (state) {
            FixConnectionState.CONNECTED, FixConnectionState.LOGGED_ON -> 0 // Highest priority
            FixConnectionState.CONNECTING -> 1 // Medium priority
            else -> 2 // Lowest priority (DISCONNECTED, ERROR)
        }

    fun getProfileSession(profileId: String): FixMessageSession? = getProfileSessions(profileId).firstOrNull()

    /**
     * Returns all sessions belonging to a profile, in creation order.
     * A profile owns multiple sessions when its sessionCount is greater than 1.
     */
    fun getProfileSessions(profileId: String): List<FixMessageSession> =
        profileToSessionMap[profileId]
            ?.filter { it in _sessions.indices }
            ?.map { _sessions[it] }
            .orEmpty()

    // Profile management methods
    private fun loadConnectionProfiles() {
        _connectionProfiles.clear()
        _connectionProfiles.addAll(profileService.loadProfiles().sortedBy { it.name.lowercase() })
    }

    /** @return true if the profile was persisted; false if the underlying save failed. */
    fun saveConnectionProfile(profile: FixConnectionProfile): Boolean {
        var persisted = false
        profileService
            .saveProfile(profile)
            .onSuccess {
                loadConnectionProfiles()
                persisted = true
            }.onFailure { error ->
                logger.error("Failed to save connection profile: ${error.message}", error)
            }
        return persisted
    }

    fun deleteConnectionProfile(profileId: String) {
        // Don't delete demo profiles - they're managed by the demo server
        if (DemoServerManager.isDemoProfile(profileId)) {
            logger.warn("Cannot delete demo profile: {}", profileId)
            return
        }

        profileService
            .deleteProfile(profileId)
            .onSuccess {
                loadConnectionProfiles()
            }.onFailure { error ->
                logger.error("Failed to delete connection profile: ${error.message}", error)
            }
    }

    fun cloneConnectionProfile(profile: FixConnectionProfile): FixConnectionProfile {
        val clonedProfile =
            profile.copy(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                name = "${profile.name} (Copy)",
                createdAt = System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis(),
            )
        profileService
            .saveProfile(clonedProfile)
            .onSuccess {
                loadConnectionProfiles()
            }.onFailure { error ->
                logger.error("Failed to clone connection profile: ${error.message}", error)
            }
        return clonedProfile
    }

    // Message editor field management
    fun updateEditorField(index: Int, field: FixField) {
        if (index in _editorFields.indices) {
            _editorFields[index] = field
            markEditorDirty()
        }
    }

    fun addEditorField() {
        // If there's a selected field, insert after it; otherwise add at the end
        val insertIndex =
            if (_editorSelectedFieldIndex.value in _editorFields.indices) {
                _editorSelectedFieldIndex.value + 1
            } else {
                _editorFields.size
            }
        _editorFields.add(insertIndex, FixField())
        _editorSelectedFieldIndex.value = insertIndex
        markEditorDirty()
    }

    fun deleteEditorField(index: Int) {
        // If multi-selection exists, delete all selected fields (even if not contiguous)
        if (_editorSelectedIndices.size > 1 && _editorFields.size > _editorSelectedIndices.size) {
            val sorted = _editorSelectedIndices.sorted()
            // Remove from bottom to top to maintain indices
            sorted.reversed().forEach { _editorFields.removeAt(it) }
            // Update selection to the position where deleted items were
            val newIndex = (sorted.first()).coerceIn(0, _editorFields.size - 1)
            _editorSelectedIndices.clear()
            _editorSelectedIndices.add(newIndex)
            _editorSelectedFieldIndex.value = newIndex
            markEditorDirty()
        } else {
            // Single selection mode
            if (_editorFields.size > 1 && index in _editorFields.indices) {
                _editorFields.removeAt(index)
                val newIndex = if (index >= _editorFields.size) _editorFields.size - 1 else index
                _editorSelectedFieldIndex.value = newIndex
                _editorSelectedIndices.clear()
                _editorSelectedIndices.add(newIndex)
                markEditorDirty()
            }
        }
    }

    fun moveEditorFieldUp(index: Int) {
        // If multi-selection exists, move all selected fields up
        if (_editorSelectedIndices.size > 1) {
            val sorted = _editorSelectedIndices.sorted()
            val minIndex = sorted.first()

            if (minIndex > 0) {
                if (isSelectionContiguous()) {
                    // Contiguous: move as a group
                    val fieldsToMove = sorted.map { _editorFields[it] }
                    sorted.reversed().forEach { _editorFields.removeAt(it) }
                    fieldsToMove.forEachIndexed { offset, field ->
                        _editorFields.add(minIndex - 1 + offset, field)
                    }
                    _editorSelectedIndices.clear()
                    _editorSelectedIndices.addAll(sorted.map { it - 1 })
                    _editorSelectedFieldIndex.value = _editorSelectedFieldIndex.value - 1
                } else {
                    // Non-contiguous: swap each selected item with the one above it
                    val newIndices = mutableListOf<Int>()
                    sorted.forEach { currentIndex ->
                        val targetIndex = currentIndex - 1
                        if (currentIndex > 0 && targetIndex !in sorted) {
                            // Swap with the item above (which is not selected)
                            val temp = _editorFields[currentIndex]
                            _editorFields[currentIndex] = _editorFields[targetIndex]
                            _editorFields[targetIndex] = temp
                            newIndices.add(targetIndex)
                        } else {
                            // Can't move up, keep at current position
                            newIndices.add(currentIndex)
                        }
                    }
                    _editorSelectedIndices.clear()
                    _editorSelectedIndices.addAll(newIndices.sorted())
                    if (_editorSelectedFieldIndex.value in sorted &&
                        _editorSelectedFieldIndex.value - 1 !in sorted &&
                        _editorSelectedFieldIndex.value > 0
                    ) {
                        _editorSelectedFieldIndex.value = _editorSelectedFieldIndex.value - 1
                    }
                }
                markEditorDirty()
            }
        } else {
            // Single selection mode
            if (index > 0 && index in _editorFields.indices) {
                val field = _editorFields.removeAt(index)
                _editorFields.add(index - 1, field)
                _editorSelectedFieldIndex.value = index - 1
                if (index in _editorSelectedIndices) {
                    _editorSelectedIndices.remove(index)
                    _editorSelectedIndices.add(index - 1)
                }
                markEditorDirty()
            }
        }
    }

    fun moveEditorFieldDown(index: Int) {
        // If multi-selection exists, move all selected fields down
        if (_editorSelectedIndices.size > 1) {
            val sorted = _editorSelectedIndices.sorted()
            val maxIndex = sorted.last()

            if (maxIndex < _editorFields.size - 1) {
                if (isSelectionContiguous()) {
                    // Contiguous: move as a group
                    val fieldsToMove = sorted.map { _editorFields[it] }
                    sorted.reversed().forEach { _editorFields.removeAt(it) }
                    fieldsToMove.forEachIndexed { offset, field ->
                        _editorFields.add(sorted.first() + 1 + offset, field)
                    }
                    _editorSelectedIndices.clear()
                    _editorSelectedIndices.addAll(sorted.map { it + 1 })
                    _editorSelectedFieldIndex.value = _editorSelectedFieldIndex.value + 1
                } else {
                    // Non-contiguous: swap each selected item with the one below it (process from bottom to top)
                    val newIndices = mutableListOf<Int>()
                    sorted.reversed().forEach { currentIndex ->
                        val targetIndex = currentIndex + 1
                        if (currentIndex < _editorFields.size - 1 && targetIndex !in sorted) {
                            // Swap with the item below (which is not selected)
                            val temp = _editorFields[currentIndex]
                            _editorFields[currentIndex] = _editorFields[targetIndex]
                            _editorFields[targetIndex] = temp
                            newIndices.add(targetIndex)
                        } else {
                            // Can't move down, keep at current position
                            newIndices.add(currentIndex)
                        }
                    }
                    _editorSelectedIndices.clear()
                    _editorSelectedIndices.addAll(newIndices.sorted())
                    if (_editorSelectedFieldIndex.value in sorted &&
                        _editorSelectedFieldIndex.value + 1 !in sorted &&
                        _editorSelectedFieldIndex.value < _editorFields.size - 1
                    ) {
                        _editorSelectedFieldIndex.value = _editorSelectedFieldIndex.value + 1
                    }
                }
                markEditorDirty()
            }
        } else {
            // Single selection mode
            if (index < _editorFields.size - 1 && index in _editorFields.indices) {
                val field = _editorFields.removeAt(index)
                _editorFields.add(index + 1, field)
                _editorSelectedFieldIndex.value = index + 1
                if (index in _editorSelectedIndices) {
                    _editorSelectedIndices.remove(index)
                    _editorSelectedIndices.add(index + 1)
                }
                markEditorDirty()
            }
        }
    }

    fun selectEditorField(index: Int, isCtrlPressed: Boolean = false, isShiftPressed: Boolean = false) {
        if (index !in _editorFields.indices) return

        when {
            isShiftPressed && _editorSelectedIndices.isNotEmpty() -> {
                // Range selection: select all fields between anchor and current index
                val anchor = _editorSelectedIndices.minOrNull() ?: index
                val range = if (index >= anchor) anchor..index else index..anchor
                _editorSelectedIndices.clear()
                _editorSelectedIndices.addAll(range.toList())
                _editorSelectedFieldIndex.value = index
            }

            isCtrlPressed -> {
                // Toggle selection: add/remove from multi-selection
                if (index in _editorSelectedIndices) {
                    _editorSelectedIndices.remove(index)
                    if (_editorSelectedIndices.isNotEmpty()) {
                        _editorSelectedFieldIndex.value = _editorSelectedIndices.last()
                    } else {
                        _editorSelectedFieldIndex.value = index
                    }
                } else {
                    _editorSelectedIndices.add(index)
                    _editorSelectedFieldIndex.value = index
                }
            }

            else -> {
                // Single selection: clear previous and select only this one
                _editorSelectedIndices.clear()
                _editorSelectedIndices.add(index)
                _editorSelectedFieldIndex.value = index
            }
        }
    }

    fun clearEditorSelection() {
        _editorSelectedIndices.clear()
        if (_editorFields.isNotEmpty()) {
            _editorSelectedIndices.add(0)
            _editorSelectedFieldIndex.value = 0
        }
    }

    /**
     * Returns true if the selected indices form a contiguous range
     */
    private fun isSelectionContiguous(): Boolean {
        if (_editorSelectedIndices.size <= 1) return true
        val sorted = _editorSelectedIndices.sorted()
        for (i in 0 until sorted.size - 1) {
            if (sorted[i + 1] - sorted[i] != 1) return false
        }
        return true
    }

    /**
     * Marks the editor as dirty (modified) if it's currently in Clean state
     * Call this whenever the editor content is modified by the user
     */
    fun markEditorDirty() {
        val currentState = _editorState.value
        if (currentState is MessageEditorState.Clean) {
            _editorState.value =
                MessageEditorState.Dirty(
                    messageId = currentState.messageId,
                    messageName = currentState.messageName,
                    userTags = currentState.userTags,
                )
        }
        // If already Dirty or New, no change needed
    }

    fun clearEditorFields(resetSelection: Boolean = true) {
        _editorFields.clear()
        _editorFields.add(FixField())
        if (resetSelection) {
            _editorSelectedFieldIndex.value = 0
            _editorSelectedIndices.clear()
            _editorSelectedIndices.add(0)
        }
        // Reset editor state to New when fields are cleared
        _editorState.value = MessageEditorState.New
    }

    /**
     * Validates template expressions in fields with incoming/outgoing message context
     * Used before sending to ensure all expressions can be resolved
     */
    fun validateTemplateExpressions(
        fields: List<FixField>,
        incomingMessages: Map<String, FixMessage>,
        outgoingMessages: Map<String, FixMessage>,
        seedVariables: Map<String, String> = emptyMap(),
    ): List<String> {
        val errors = mutableListOf<String>()
        // Use shared variables map so variables defined in earlier fields are available to later fields
        val sharedVariables = seedVariables.toMutableMap()

        fields.forEach { field ->
            if (FixMessageTemplate.hasTemplateExpressions(field.value)) {
                val templateErrors =
                    FixMessageTemplate.validateExpressions(
                        field.value,
                        incomingMessages = incomingMessages,
                        outgoingMessages = outgoingMessages,
                        variables = sharedVariables,
                        dictionary = _dictionary.value,
                    )
                templateErrors.forEach { error ->
                    errors.add("Field ${field.tag}: $error")
                }
            }
        }

        return errors
    }

    /**
     * Returns the current data dictionary adapter for template expression evaluation.
     */
    fun getDictionaryAdapter(): FixDictionaryAdapter = _dictionary.value

    fun validateEditorMessage(fields: List<FixField>): List<String> {
        _editorValidationErrors.clear()

        if (!_dictionary.value.isLoaded()) {
            _editorValidationErrors.add(
                "No data dictionary configured. Please configure a data dictionary in settings.",
            )
            return _editorValidationErrors
        }

        // Validate template expressions in field values (without incoming/outgoing context for Validate button)
        // Use shared variables map so variables defined in earlier fields are available to later fields
        val sharedVariables = mutableMapOf<String, String>()
        fields.forEach { field ->
            if (FixMessageTemplate.hasTemplateExpressions(field.value)) {
                val templateErrors =
                    FixMessageTemplate.validateExpressions(
                        field.value,
                        // No incoming/outgoing context available in editor, but can still validate syntax
                        incomingMessages = emptyMap(),
                        outgoingMessages = emptyMap(),
                        variables = sharedVariables,
                    )
                templateErrors.forEach { error ->
                    _editorValidationErrors.add("Field ${field.tag}: $error")
                }
            }
        }

        // Also validate FIX message structure
        val result =
            FixMessageValidator.validate(
                fields.toRawMessage(),
                _dictionary.value,
            )
        _editorValidationErrors.addAll(result.errors)

        return _editorValidationErrors
    }

    fun clearEditorValidationErrors() {
        _editorValidationErrors.clear()
    }

    fun setEditorValidationErrors(errors: List<String>) {
        _editorValidationErrors.clear()
        _editorValidationErrors.addAll(errors)
    }

    fun moveSession(fromIndex: Int, toIndex: Int) {
        if (fromIndex in _sessions.indices && toIndex in _sessions.indices) {
            val session = _sessions.removeAt(fromIndex)
            _sessions.add(toIndex, session)

            // Adjust active index if needed
            when {
                _activeSessionIndex.value == fromIndex -> _activeSessionIndex.value = toIndex
                fromIndex < _activeSessionIndex.value && toIndex >= _activeSessionIndex.value -> _activeSessionIndex.value--
                fromIndex > _activeSessionIndex.value && toIndex <= _activeSessionIndex.value -> _activeSessionIndex.value++
            }

            // Adjust profileToSessionMap indices to reflect the move
            profileToSessionMap.values.forEach { indices ->
                for (i in indices.indices) {
                    val sessionIndex = indices[i]
                    indices[i] =
                        when {
                            sessionIndex == fromIndex -> toIndex
                            fromIndex < toIndex && sessionIndex > fromIndex && sessionIndex <= toIndex -> sessionIndex - 1
                            fromIndex > toIndex && sessionIndex >= toIndex && sessionIndex < fromIndex -> sessionIndex + 1
                            else -> sessionIndex
                        }
                }
            }
        }
    }

    // Demo Server Management
    val demoServerFixVersion: StateFlow<FixVersion?> = DemoServerManager.currentFixVersion

    fun startDemoServer(fixVersion: FixVersion = FixVersion.FIX_4_4) {
        try {
            DemoServerManager.start(fixVersion)
        } catch (e: Exception) {
            // Error already logged by manager
        }
    }

    fun stopDemoServer() {
        DemoServerManager.stop()
    }

    /**
     * Handles demo profile creation/deletion when demo server starts/stops
     */
    private fun handleDemoProfilesChanged(demoProfiles: List<FixConnectionProfile>) {
        // Remove existing demo profiles
        val nonDemoProfiles = _connectionProfiles.filter { !DemoServerManager.isDemoProfile(it.id) }
        _connectionProfiles.clear()
        _connectionProfiles.addAll(nonDemoProfiles)

        // Add new demo profiles
        if (demoProfiles.isNotEmpty()) {
            _connectionProfiles.addAll(demoProfiles)
            // Re-sort profiles
            val sortedProfiles = _connectionProfiles.sortedBy { it.name.lowercase() }
            _connectionProfiles.clear()
            _connectionProfiles.addAll(sortedProfiles)
        }
    }

    // Global session operations
    fun addSeparatorToAllSessions() {
        _sessions.forEach { it.addSeparator() }
    }

    fun clearAllSessions() {
        _sessions.forEach { it.clearMessages() }
    }

    fun setGlobalFilterRegex(regex: String) {
        _globalFilterRegex.value = regex
        // Apply to all sessions
        _sessions.forEach { it.setFilterRegex(regex) }
    }

    fun setGlobalFilterShowIncoming(show: Boolean) {
        _globalFilterShowIncoming.value = show
        // Apply to all sessions
        _sessions.forEach { it.setFilterShowIncoming(show) }
    }

    fun setGlobalFilterShowOutgoing(show: Boolean) {
        _globalFilterShowOutgoing.value = show
        // Apply to all sessions
        _sessions.forEach { it.setFilterShowOutgoing(show) }
    }

    // Saved Messages Operations
    fun saveEditorMessage(
        name: String,
        fields: List<FixField>,
        profileId: String,
        userTags: Set<String> = setOf(profileId),
    ) {
        val savedFields = fields.map { SavedFixField(tag = it.tag, value = it.value, excluded = it.excluded) }

        // Determine if this is an update to existing message or a new save
        val currentState = _editorState.value
        val savedMessage =
            when (currentState) {
                is MessageEditorState.Clean,
                is MessageEditorState.Dirty,
                -> {
                    // Update existing message - preserve ID and createdAt
                    val existingId = currentState.messageIdOrNull()!!
                    val existing = _savedMessages.find { it.id == existingId }
                    SavedFixMessage(
                        id = existingId,
                        name = name,
                        userTags = userTags,
                        fields = savedFields,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        modifiedAt = System.currentTimeMillis(),
                        version = (existing?.version ?: 0) + 1,
                    )
                }
                is MessageEditorState.New -> {
                    // Create new message with new ID
                    SavedFixMessage(
                        name = name,
                        userTags = userTags,
                        fields = savedFields,
                    )
                }
            }

        savedMessagesService
            .saveMessage(profileId, savedMessage)
            .onSuccess { updatedMessages ->
                _savedMessages.clear()
                _savedMessages.addAll(updatedMessages)

                // Update editor state to Clean after successful save
                _editorState.value =
                    MessageEditorState.Clean(
                        messageId = savedMessage.id,
                        messageName = savedMessage.name,
                        userTags = savedMessage.getAllUserTags(),
                    )
            }.onFailure { error ->
                logger.error("Failed to save message: ${error.message}", error)
            }
    }

    /** Result of [saveTemplateDirect]: the persisted message plus whether it was newly created. */
    data class TemplateSaveResult(val message: SavedFixMessage, val created: Boolean)

    /**
     * Saves a template directly, independent of the message editor's current state. Creates a new
     * template, or updates the existing one when [id] matches a saved message under [profileId].
     * Intended for automation/control callers; refreshes the in-memory list so the UI reflects the
     * change.
     * @return the [TemplateSaveResult], or null if the underlying persistence failed.
     */
    @Suppress("LongParameterList") // distinct template attributes; a DTO would only add ceremony for one caller
    fun saveTemplateDirect(
        profileId: String,
        name: String,
        fields: List<SavedFixField>,
        userTags: Set<String> = setOf(profileId),
        isFavorite: Boolean = false,
        id: String? = null,
    ): TemplateSaveResult? {
        // Look the existing record up from the store (not the in-memory list, which a headless
        // control caller may not have populated) so an update preserves createdAt and version.
        val existing = id?.let { mid -> savedMessagesService.loadMessagesForProfile(profileId).find { it.id == mid } }
        val message =
            SavedFixMessage(
                id = id ?: java.util.UUID.randomUUID().toString(),
                name = name,
                userTags = userTags,
                fields = fields,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis(),
                version = (existing?.version ?: 0) + 1,
                isFavorite = isFavorite,
            )
        var persisted = false
        savedMessagesService
            .saveMessage(profileId, message)
            .onSuccess { loadSavedMessagesForActiveSession(); persisted = true }
            .onFailure { error -> logger.error("Failed to save template: ${error.message}", error) }
        return if (persisted) TemplateSaveResult(message, created = existing == null) else null
    }

    /**
     * Save As: Always creates a new message with a new ID
     * Useful for creating copies or variants of existing messages
     */
    fun saveEditorMessageAs(
        name: String,
        fields: List<FixField>,
        profileId: String,
        userTags: Set<String> = setOf(profileId),
    ) {
        val savedFields = fields.map { SavedFixField(tag = it.tag, value = it.value, excluded = it.excluded) }

        // Always create a new message (never update existing)
        val savedMessage =
            SavedFixMessage(
                // id will be generated automatically
                name = name,
                userTags = userTags,
                fields = savedFields,
            )

        savedMessagesService
            .saveMessage(profileId, savedMessage)
            .onSuccess { updatedMessages ->
                _savedMessages.clear()
                _savedMessages.addAll(updatedMessages)

                // Update editor state to Clean with the new message ID
                _editorState.value =
                    MessageEditorState.Clean(
                        messageId = savedMessage.id,
                        messageName = savedMessage.name,
                        userTags = savedMessage.getAllUserTags(),
                    )
            }.onFailure { error ->
                logger.error("Failed to save message: ${error.message}", error)
            }
    }

    fun getCurrentProfileId(): String? =
        activeSession?.let {
            profileIdForSessionIndex(_activeSessionIndex.value)
        }

    fun loadEditorMessage(savedMessage: SavedFixMessage) {
        // Clear current fields
        _editorFields.clear()

        // Load fields from saved message
        val fieldsToLoad = savedMessage.fields.map { FixField(tag = it.tag, value = it.value, excluded = it.excluded) }
        if (fieldsToLoad.isEmpty()) {
            _editorFields.add(FixField())
        } else {
            _editorFields.addAll(fieldsToLoad)
        }

        // Reset selection to first field
        _editorSelectedIndices.clear()
        _editorSelectedIndices.add(0)
        _editorSelectedFieldIndex.value = 0

        // Set editor state to Clean (message loaded, unmodified)
        _editorState.value =
            MessageEditorState.Clean(
                messageId = savedMessage.id,
                messageName = savedMessage.name,
                userTags = savedMessage.getAllUserTags(),
            )

        // Auto-select appropriate profile/session based on template's associated profiles
        autoSelectProfileForMessage(savedMessage)

        // Mark message as used
        activeSession?.let { session ->
            val currentProfileId = profileIdForSessionIndex(_activeSessionIndex.value)
            if (currentProfileId != null) {
                savedMessagesService
                    .markMessageAsUsed(currentProfileId, savedMessage.id)
                    .onFailure { error ->
                        logger.error("Failed to mark message as used: ${error.message}", error)
                    }
            }
        }
    }

    /**
     * Auto-selects the appropriate profile/session when a template is loaded.
     *
     * A template's userTags are the ids of the profiles it belongs to. The rules are:
     * 1. A template that belongs to no known profile leaves the session selection alone.
     * 2. If the active session already belongs to one of those profiles, it stays active -
     *    a template that fits where you are working never moves you somewhere else.
     * 3. Otherwise the best candidate wins: connected first, then profiles that own a
     *    session, then alphabetically.
     */
    private fun autoSelectProfileForMessage(savedMessage: SavedFixMessage) {
        val associatedProfileIds = savedMessage.getAllUserTags()

        if (associatedProfileIds.isEmpty()) {
            // No associated profiles - keep current session selection
            logger.info("loadEditorMessage: No associated profiles for message '${savedMessage.name}', keeping current session")
            return
        }

        // Filter connection profiles to only those associated with the message
        val associatedProfiles = connectionProfiles.filter { it.id in associatedProfileIds }

        if (associatedProfiles.isEmpty()) {
            // Associated profiles not found - keep current session selection
            logger.info("loadEditorMessage: Associated profiles not found for message '${savedMessage.name}', keeping current session")
            return
        }

        val activeProfileId = profileIdForSessionIndex(_activeSessionIndex.value)
        if (activeProfileId in associatedProfileIds) {
            logger.info(
                "loadEditorMessage: Active session already belongs to message '${savedMessage.name}', keeping current session",
            )
            return
        }

        // Connected profiles first, then ones that own a session (a profile that has never
        // been connected has no session to switch to), then alphabetically.
        val sortedProfiles =
            associatedProfiles.sortedWith(
                compareBy(
                    { profile -> getConnectionPriority(getProfileConnectionState(profile.id)) },
                    { profile -> if (getProfileSessions(profile.id).isEmpty()) 1 else 0 },
                    { profile -> profile.name.lowercase() },
                ),
            )

        // Select the first profile
        val selectedProfile = sortedProfiles.first()

        logger.info(
            "loadEditorMessage: Auto-selecting profile '${selectedProfile.name}' for message '${savedMessage.name}' (${associatedProfiles.size} associated profiles)",
        )

        // Set the selected editor profile (this will also set active session if connected)
        setSelectedEditorProfile(selectedProfile)
    }

    fun loadSavedMessagesForActiveSession() {
        // Always show all messages from all profiles (no filtering by active session)
        // The user can filter in the popup UI if needed
        val allMessages = mutableListOf<SavedFixMessage>()
        connectionProfiles.forEach { profile ->
            allMessages.addAll(savedMessagesService.loadMessagesForProfile(profile.id))
        }
        // Deduplicate messages by ID (messages can be shared across multiple profiles)
        _savedMessages.clear()
        _savedMessages.addAll(allMessages.distinctBy { it.id })
    }

    fun deleteSavedMessage(messageId: String, profileId: String) {
        // Delete the message using the profileId from the message itself
        savedMessagesService
            .deleteMessage(profileId, messageId)
            .onSuccess {
                // Reload all saved messages to reflect the deletion
                loadSavedMessagesForActiveSession()

                // Clear editor state if we just deleted the currently loaded message
                val currentMessageId = _editorState.value.messageIdOrNull()
                if (currentMessageId == messageId) {
                    _editorState.value = MessageEditorState.New
                }
            }.onFailure { error ->
                logger.error("Failed to delete message: ${error.message}", error)
            }
    }

    fun toggleMessageFavorite(messageId: String) {
        // Find the message and toggle its favorite status
        val message = _savedMessages.find { it.id == messageId } ?: return
        val updatedMessage = message.copy(isFavorite = !message.isFavorite)

        // Get the profileId from the message's user tags
        val profileId = message.getAllUserTags().firstOrNull() ?: return

        // Update local state immediately for responsive UI
        val index = _savedMessages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            _savedMessages[index] = updatedMessage
        }

        // Persist to storage
        savedMessagesService
            .saveMessage(profileId, updatedMessage)
            .onFailure { error ->
                logger.error("Failed to toggle favorite: ${error.message}", error)
                // Revert local state on failure
                if (index >= 0) {
                    _savedMessages[index] = message
                }
            }
    }

    // ========================================
    // Notification Management
    // ========================================

    /**
     * Shows a notification to the user
     */
    fun showNotification(
        message: String,
        type: NotificationType = NotificationType.ERROR,
    ) {
        logger.info("Showing notification: [$type] $message")
        val notification =
            Notification(
                message = message,
                type = type,
            )
        _notifications.add(notification)
    }

    /**
     * Dismisses a notification by its ID
     */
    fun dismissNotification(notificationId: String) {
        _notifications.removeAll { it.id == notificationId }
    }

    /**
     * Clears all notifications
     */
    fun clearAllNotifications() {
        _notifications.clear()
    }

    // ========================================
    // Test Helper Methods
    // ========================================

    /**
     * Creates a new session for testing purposes.
     * This is a public wrapper around createNewSession for use in tests.
     */
    fun createSessionForTest(title: String = "Test Session"): FixMessageSession = createNewSession(title)

    /**
     * Creates a session with an associated profile for testing purposes.
     * This properly sets up the profile-to-session mapping that's needed for
     * testing the selectedEditorProfile sync behavior.
     *
     * @param profileName Name for the profile
     * @return Pair of the created profile and session
     */
    fun createSessionWithProfileForTest(profileName: String): Pair<FixConnectionProfile, FixMessageSession> {
        val profile = createProfileWithoutSessionForTest(profileName)

        val session = createNewSession(profileName)
        val sessionIndex = _sessions.size - 1
        profileToSessionMap[profile.id] = mutableListOf(sessionIndex)

        return Pair(profile, session)
    }

    /**
     * Adds another session to an existing profile for testing purposes, as a profile with a
     * sessionCount above one owns after connecting.
     *
     * @param profile Profile that owns the session
     * @param title Title for the session
     * @return The created session
     */
    fun addSessionToProfileForTest(profile: FixConnectionProfile, title: String): FixMessageSession {
        val session = createNewSession(title)
        profileToSessionMap.getOrPut(profile.id) { mutableListOf() }.add(_sessions.size - 1)
        return session
    }

    /**
     * Creates a profile with no session for testing purposes, as if it had never been connected.
     *
     * Registers the profile in memory only: saveConnectionProfile() would reload the profile list
     * from disk and drop the profiles its sibling helper added.
     *
     * @param profileName Name for the profile
     * @return The created profile
     */
    fun createProfileWithoutSessionForTest(profileName: String): FixConnectionProfile {
        val profile =
            FixConnectionProfile(
                name = profileName,
                config =
                    FixConnectionConfig(
                        host = "localhost",
                        port = "9876",
                        senderCompID = "TEST_SENDER",
                        targetCompID = "TEST_TARGET",
                        beginString = "FIX.4.4",
                    ),
            )
        _connectionProfiles.add(profile)
        return profile
    }

    override fun onCleared() {
        super.onCleared()
        _sessions.forEach { it.destroy() }
        DemoServerManager.stop()
    }
}
