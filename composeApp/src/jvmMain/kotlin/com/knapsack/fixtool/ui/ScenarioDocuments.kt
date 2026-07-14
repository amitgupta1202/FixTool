package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.service.ScenarioCapture

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
        fun of(scenario: Scenario): ScenarioDraft = scenario.asEditorSeed().let { ScenarioDraft(it, it) }
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
    ) : ScenarioDoc {
        override val id: String get() = CAPTURE_ID
        override val glyph: String get() = "⧉"
        override val scenarioId: String? get() = null

        val title: String get() = state.name.ifBlank { "capture" }.let { "capture: $it" }
        val dirty: Boolean get() = state != CaptureReviewState.of(scan.candidates.size)
    }

    companion object {
        /** There is one session scan at a time, so there is one capture review at a time. */
        const val CAPTURE_ID = "capture"

        fun editorId(scenarioId: String): String = "editor:$scenarioId"
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
