package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.service.ScenarioCapture
import com.knapsack.fixtool.ui.diff.ReconcileSession

/**
 * **One scenario's unsaved state — and there is exactly one of these per scenario, ever.**
 *
 * The draft used to live in the editor document, which was fine while the editor was the only document that
 * could touch a scenario. It is not: the reconcile diff is a document too, and it edits an expectation *of the
 * same scenario*. Give each of them a draft and the two diverge — save from the diff tab and the editor's next
 * Save writes the old expectation straight back over the repair; save from the editor and a rename the author
 * never meant to commit goes with it. That is the two-editing-surfaces defect from the assertion model doc,
 * re-created between two tabs.
 *
 * So the draft belongs to the *scenario*, and every document of that scenario is a **view** onto it.
 */
data class ScenarioDraft(
    /** The scenario as it stands across every open document of it. Nothing reaches disk until Save. */
    val draft: Scenario,
    /**
     * What an untouched scenario looks like once it has been through the editor's row model, and the *only*
     * thing [dirty] is measured against. Not the file: [EditStep] normalizes a Send's raw (parse → re-join),
     * so a scenario that has been opened and not touched can differ from its own file over a difference the
     * author never made — and every tab would then open dirty and every close would stop to ask.
     */
    val seed: Scenario,
) {
    val dirty: Boolean get() = draft != seed

    companion object {
        /**
         * **Identified on the way in.** A document addresses a step by its `stepId` — the diff tab *is* a step —
         * so a draft whose steps have blank ids is a draft whose steps cannot be found. `withIds` is the
         * normalizer that runs at every door a scenario comes through (load, capture, save, run); the workspace
         * is a new door, and it needs it too. Deterministic, so opening the same file twice identifies its steps
         * the same way both times — which is the whole of D3.
         */
        fun of(scenario: Scenario): ScenarioDraft =
            scenario
                .withIds()
                .asEditorSeed()
                .let { ScenarioDraft(it, it) }

        /**
         * A draft that is **not on disk yet** — a capture the author has not chosen to keep. Its seed is the
         * scenario with no steps, so it is dirty from the first frame: Save writes it (and re-seeds from
         * disk, going clean), and closing its last view asks first instead of silently discarding a capture
         * the author may have spent a whole flow producing.
         */
        fun ofUnsaved(scenario: Scenario): ScenarioDraft =
            of(scenario).let { it.copy(seed = it.seed.copy(steps = emptyList(), setup = emptyList(), teardown = emptyList())) }
    }
}

/**
 * A scenario document — a closable tab in the centre pane, beside the session tabs.
 *
 * **A document owns whatever state is its own, and that is not a matter of taste.** The workbench *window*
 * kept `ScenarioEditor`'s remembered state alive while the author looked at the session grid, because the
 * window stayed composed. A tab does not: only the active document is composed, so anything left inside the
 * composable is destroyed by a glance at the session tab. What belongs to the *scenario* lives in
 * [ScenarioDraft]; what belongs to the *view* — the cursor, the curation, the undo stack — lives here.
 *
 * The alternative — keeping every document composed but unplaced — preserves the state for free and puts
 * every open document's nodes in the semantics tree, where `onNodeWithTag` finds a *hidden* tab's button and
 * passes. A UI test that cannot tell which document it is looking at is worse than no test.
 */
sealed interface ScenarioDoc {
    /**
     * Stable across re-opens, and the reason a deep-link *focuses* rather than duplicates: a second failure
     * in a scenario already open lands on the tab that is already there, carrying its unsaved edits.
     */
    val id: String

    /** The tab's glyph — the mockup's, so a document reads as a document at a glance. */
    val glyph: String

    /** The scenario this document is a view onto. Null for capture review, which is not one yet. */
    val scenarioId: String?

    /** The scenario flow editor: the steps, their order, and each one's session and bind predicate. */
    data class Editor(
        override val scenarioId: String,
        /** The step a deep-link landed on: the one the run failed at. This is not the selection. */
        val focusStep: Int? = null,
        /** The failing run's context for [focusStep]. Null outside a deep-link. */
        val failure: RunFailureContext? = null,
        /** Where the author's cursor is — kept out here so that switching tabs does not move it. */
        val selectedStep: Int? = null,
        /**
         * The step-list/detail divider, as the author dragged it — kept out here for the same reason as
         * the cursor: a split that lived in the composable's `remember` snapped back to the default on
         * every glance at the session grid. 55/45 to start: the step list is a column of sentences and
         * earns the room, but the detail form wants enough width to edit a matcher; the divider is still
         * draggable either way.
         */
        val split: Float = 0.55f,
        /**
         * Bumped by every deep-link into this document, and the composable is keyed on it.
         *
         * A tab that is already open has already seeded its cursor, and a prop cannot move it — so a second
         * failure in the same scenario would focus the tab and land on the wrong step, silently. Re-keying
         * rebuilds the editor from the draft, which is current, so the author's unsaved edits survive the
         * re-aim; only the cursor moves.
         */
        val focusEpoch: Int = 0,
    ) : ScenarioDoc {
        override val id: String get() = editorId(scenarioId)
        override val glyph: String get() = "⚙"
    }

    /** Capture review: the session scan, curated, before any of it becomes a scenario. */
    data class Capture(
        /** A snapshot, not a live feed — the review curates a stable list (see [ScenarioCapture.scan]). */
        val scan: ScenarioCapture.Scan,
        val state: CaptureReviewState,
        /**
         * **Capture's second source.** Null = the live sessions. Non-null = the author is pasting wire, one
         * message per line, and [scan] is what that paste read to — recomputed by the ViewModel whenever the
         * text or the assigned session changes, because the direction is read off the *session's* CompIDs.
         */
        val paste: Paste? = null,
    ) : ScenarioDoc {
        override val id: String get() = CAPTURE_ID
        override val glyph: String get() = "⧉"
        override val scenarioId: String? get() = null

        // Never "capture: capture": a blank name falls back to the source, not to the tab's own kind.
        val title: String get() =
            state.name.takeIf { it.isNotBlank() }?.let { "capture: $it" }
                ?: if (paste != null) "paste capture" else "capture"
        val dirty: Boolean
            get() = state != CaptureReviewState.of(scan.candidates.size) || paste?.text?.isNotBlank() == true

        /** The pasted text, the session it is assigned to, and what could not be read out of it. */
        data class Paste(
            val text: String = "",
            val session: String = "",
            /** The lines the reader refused, in its own words. Reported — never dropped on the floor. */
            val refused: List<String> = emptyList(),
        )
    }

    companion object {
        /** There is one session scan at a time, so there is one capture review at a time. */
        const val CAPTURE_ID = "capture"

        fun editorId(scenarioId: String): String = "editor:$scenarioId"
    }
}

/**
 * **One step's own state inside a scenario's diff window.**
 *
 * Everything a [ReconcileSession] owns is a *step's*: the undo stack, the reference slot, the staged count.
 * The window is the scenario's, so the per-step state has to live somewhere below it — here, one slot per
 * step the author has actually visited.
 *
 * **Created on first visit, and never rebuilt.** Rebuilding a slot when the author steps away and back is
 * the defect [ReconcileSession]'s own kdoc exists to prevent, relocated from the composable to the ViewModel:
 * it would throw away that step's undo stack and staged repairs on every click of the strip. Holding ten
 * steps' sessions at once is what "repair a pass without closing ten windows" *means*.
 */
data class DiffStepSlot(
    /** By id, never by index: the author can reorder the steps under this window while it is open. */
    val stepId: String,
    /**
     * Null until a reference is bound — see the prompt in `DiffWindow`. A step that never ran and was never
     * captured has **nothing on the right**, and diffing it against an empty message would mark every asserted
     * row `missing`: a wall of red, and a gutter offering to drop every row of a step whose only crime is not
     * having run yet.
     */
    val session: ReconcileSession? = null,
    /** This run's wire bytes, kept because the golden is re-pointed at them — and at nothing else (V4). */
    val thisRunWire: String? = null,
    /** The row the author clicked in the message viewer. The body scrolls to it. */
    val focusTag: Int? = null,
)

/**
 * **A window and one of its steps** — what every per-step helper on the ViewModel actually addresses, now that
 * a window id no longer names a step. Mirrors [FixMessageViewModel.ArmedViewerSlot], which had this shape first.
 */
data class DiffStepRef(
    val scenarioId: String,
    val stepId: String,
) {
    val windowId: String get() = DiffWindowState.diffWindowId(scenarioId)
}

/** One step repaired during a reconcile pass, and the edits that repaired it. Named for the completion state. */
data class RepairedStep(
    val stepId: String,
    val label: String,
    val edits: List<String>,
)

/** A pass that ended green: what it took to get there. Set by `continueReconcilePass`, cleared by a new failure. */
data class ReconcileCompletion(
    val repaired: List<RepairedStep>,
)

/**
 * **The diff's state — a window now, not a tab, and one per *scenario*, not per step.**
 *
 * The reconcile diff opens in a dedicated, task-scoped window (Phase 6), the way an IDE opens a diff: it is
 * consulted *against* the grid it is about, not instead of it. So the diff left the `ScenarioDoc` document
 * family — it is no longer a tab in the centre pane — but everything it *held* is unchanged. The draft is
 * still the scenario's ([ScenarioDraft], one per scenario however many views it); this owns only the
 * per-step [DiffStepSlot]s — the state that has no meaning outside this one diff.
 *
 * **Why the scenario and not the step.** Reconciling is one continuous act: repair a failing step, Save &
 * re-run, meet the next failure, repeat until green. The runner stops at the first failure, so a scenario
 * that diverges in five places surfaces one step per run. Keyed on the step, that pass opened a *new window*
 * every time the re-run got further than the last one — repair ten steps, close ten windows, nine of them
 * showing green rows for work already finished. The window is the pass; the step in view moves inside it.
 *
 * And it stays here on the ViewModel, not in the window's `remember`, even though a window is not disposed
 * when you look away (unlike the tab it replaces). Three reasons survive the window: **Save & re-run** (V9)
 * rebases every open diff's session from the *rail*, which is in the main window; **cross-window arming**
 * (S8) binds a reference from the main window's *grid* into a slot; and **reopen** must find these sessions
 * rather than build fresh ones over a draft three edits from disk. See Phase 6 decision F5.
 */
data class DiffWindowState(
    val scenarioId: String,
    /** The step in view. Always a key of [slots]. */
    val stepId: String,
    /** One per step the author has visited. Lazily grown, never pruned except when the step leaves the draft. */
    val slots: Map<String, DiffStepSlot> = emptyMap(),
    /**
     * Bumped by every deep-link into an already-open window. A tab deep-link just set the active id; a window
     * that already exists may be *behind* the one that sent the author there, so the window raises itself
     * (`toFront`) on every bump. See Phase 6 decision F6.
     *
     * Moving between steps *inside* this window deliberately does not bump it — see
     * `FixMessageViewModel.showStepInDiffWindow`.
     */
    val focusEpoch: Int = 0,
    /** What this pass has repaired so far, accumulated across every Save & re-run. Dies with the window. */
    val repairs: List<RepairedStep> = emptyList(),
    /** Non-null once a re-run of this scenario came back green: the body swaps to the completion state. */
    val completion: ReconcileCompletion? = null,
) {
    /** One window per scenario. The step it is showing is [stepId], and that moves. */
    val id: String get() = diffWindowId(scenarioId)

    val slot: DiffStepSlot? get() = slots[stepId]

    // The window's current step, named once so the surface and the helpers driven from the header — which all
    // mean "the step the author is looking at" — do not each have to spell out the lookup.
    val session: ReconcileSession? get() = slot?.session
    val thisRunWire: String? get() = slot?.thisRunWire
    val focusTag: Int? get() = slot?.focusTag

    fun withSlot(stepId: String, transform: (DiffStepSlot?) -> DiffStepSlot): DiffWindowState =
        copy(slots = slots + (stepId to transform(slots[stepId])))

    companion object {
        fun diffWindowId(scenarioId: String): String = "diff:$scenarioId"
    }
}

/**
 * What the tab strip draws. Derived from the documents **and the workspace**, never stored: a document of a
 * scenario cannot say on its own what that scenario is called or whether it is dirty, because it no longer
 * holds it — and a denormalized copy of either would be the second source of truth this whole change exists
 * to remove.
 */
data class DocumentTab(
    val id: String,
    val title: String,
    val glyph: String,
    val dirty: Boolean,
)

/** Where a step stands in the pass. Ordered by what the author most needs to see, not by severity. */
enum class StepStatus {
    /** The last run said no, and the author has not touched it since. */
    FAILING,

    /** The draft differs from disk here — repaired, but not yet re-run, so nothing has confirmed it. */
    REPAIRED,

    /** The last run said yes. */
    PASSING,

    /** A run happened, but it stopped before reaching this step. */
    NOT_REACHED,

    /** No run to speak of. */
    NOT_RUN,
}

/** One chip of the step strip. Derived from the draft and the run report; never stored. */
data class StepChip(
    val stepId: String,
    /** Position among **all** steps, so it agrees with the crumb and the window title. */
    val index: Int,
    val label: String,
    val status: StepStatus,
    val current: Boolean,
    /** This step's reference slot is armed and waiting for a grid click — even though it is not in view. */
    val armed: Boolean,
    val tooltip: String,
)

/**
 * **The step strip, derived.** One chip per Expect step of the draft, in draft order.
 *
 * Takes the whole [ScenarioDraft] rather than a draft and a seed, because REPAIRED is the difference between
 * them — and handing those in separately invites a caller to pass two scenarios that were never a pair.
 *
 * [results] must be empty unless the standing run report is *this scenario's* — another scenario's verdict
 * colouring these chips would be a lie the author has no way to see through.
 *
 * **[StepStatus.REPAIRED] is measured draft-against-seed, not against the step's session**, and that is
 * load-bearing: a repair that travelled in from a sibling step (C2) is written straight into the draft and
 * never touches that step's session at all. Ask the session and a travelled repair reads as untouched.
 * Draft-vs-seed catches both routes with one rule, including for steps that have no slot in the window yet.
 *
 * REPAIRED outranks FAILING on purpose. A chip's job is to answer "what is left to do", and a step the author
 * has already fixed is not left to do — it is waiting for the re-run that will confirm it.
 */
fun stepStripOf(
    workspace: ScenarioDraft,
    currentStepId: String,
    results: Map<String, StepResult>,
    armedStepId: String?,
    typeName: (String) -> String? = { null },
): List<StepChip> =
    workspace.draft.steps.mapIndexedNotNull { index, step ->
        if (step !is ScenarioStep.Expect) return@mapIndexedNotNull null
        val status = stepStatusOf(step, workspace.seed.steps.firstOrNull { it.stepId == step.stepId }, results)
        val type = step.expectation.messageType ?: step.match?.messageType ?: ""
        // **The number, and only the number.** A chip's job is which step, what state, and click to go there;
        // it is not the place to name the message type. Scenarios run to twelve and fourteen Expects, and a
        // type name on each pushed the strip into horizontal scroll — at which point the chip the author is
        // ON can sit off-screen and the strip stops answering the one question it exists for. The type is a
        // hover away, and the crumb directly beneath already spells out the current step's in full.
        val armed = step.stepId == armedStepId
        StepChip(
            stepId = step.stepId,
            index = index,
            label = "${index + 1}",
            status = status,
            current = step.stepId == currentStepId,
            armed = armed,
            tooltip =
                "Step ${index + 1}${if (type.isBlank()) "" else " · Expect ${typeName(type) ?: type}"} — " +
                    status.describe() +
                    if (armed) " · waiting for a grid click" else "",
        )
    }


private fun stepStatusOf(
    step: ScenarioStep.Expect,
    onDisk: ScenarioStep?,
    results: Map<String, StepResult>,
): StepStatus {
    val result = results[step.stepId]
    return when {
        onDisk != null && onDisk != step -> StepStatus.REPAIRED
        result != null && !result.passed -> StepStatus.FAILING
        result != null -> StepStatus.PASSING
        results.isNotEmpty() -> StepStatus.NOT_REACHED
        else -> StepStatus.NOT_RUN
    }
}

private fun StepStatus.describe(): String =
    when (this) {
        StepStatus.FAILING -> "failing"
        StepStatus.REPAIRED -> "repaired, not saved"
        StepStatus.PASSING -> "passing"
        StepStatus.NOT_REACHED -> "the run stopped before this step"
        StepStatus.NOT_RUN -> "not run"
    }

/**
 * `2 of 12 failing · 1 unsaved` — the pass in one line, or silence when there is nothing to say.
 *
 * **Terse on purpose.** It is pinned to the right of the step strip, so every character it spends is a
 * character of chips the reader cannot see. The count is the part that is always worth its width; the
 * unsaved-work warning is appended only when there *is* unsaved work, which is the one moment it earns the
 * room it takes.
 */
fun stepStripSummary(chips: List<StepChip>): String {
    if (chips.isEmpty()) return ""
    val failing = chips.count { it.status == StepStatus.FAILING }
    val repaired = chips.count { it.status == StepStatus.REPAIRED }
    val parts = mutableListOf<String>()
    if (failing > 0) parts += "$failing of ${chips.size} failing"
    if (repaired > 0) parts += "$repaired unsaved"
    if (parts.isEmpty()) {
        val allGreen = chips.all { it.status == StepStatus.PASSING }
        parts += if (allGreen) "all ${chips.size} passing" else "${chips.size} steps"
    }
    return parts.joinToString(" · ")
}

fun documentTabsOf(documents: List<ScenarioDoc>, workspace: Map<String, ScenarioDraft>): List<DocumentTab> =
    documents.map { doc ->
        when (doc) {
            is ScenarioDoc.Capture -> DocumentTab(doc.id, doc.title, doc.glyph, doc.dirty)
            is ScenarioDoc.Editor -> {
                val scenario = workspace[doc.scenarioId]
                DocumentTab(
                    id = doc.id,
                    title = scenario?.draft?.name?.ifBlank { null } ?: "untitled scenario",
                    glyph = doc.glyph,
                    dirty = scenario?.dirty == true,
                )
            }
        }
    }

/**
 * Capture review's curation, hoisted out of the composable: the name, the cursor, and which candidates are
 * in. See [ScenarioDoc] for why it cannot stay where it was.
 */
data class CaptureReviewState(
    val name: String = "",
    val selectedIdx: Int = -1,
    val included: List<Boolean> = emptyList(),
) {
    fun includes(index: Int): Boolean = included.getOrElse(index) { false }

    companion object {
        /** The state a fresh review opens in — everything in, the first row selected, unnamed. */
        fun of(candidates: Int): CaptureReviewState =
            CaptureReviewState(
                selectedIdx = if (candidates == 0) -1 else 0,
                included = List(candidates) { true },
            )
    }
}
