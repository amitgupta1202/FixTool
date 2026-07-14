package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.service.ScenarioCapture

/**
 * A scenario document — a closable tab in the centre pane, beside the session tabs.
 *
 * **A document owns its editable state, and that is not a matter of taste.** The workbench *window* kept
 * `ScenarioEditor`'s remembered state alive while the author looked at the session grid, because the window
 * stayed composed. A tab does not: only the active document is composed, so a draft left inside the
 * composable is destroyed by a glance at the session tab — the author's unsaved edits, and capture review's
 * whole include/exclude curation, with it. So everything the author has typed or ticked lives here, and the
 * composables mirror it back out as they change it.
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

    /** What the tab says. */
    val title: String

    /** The tab's glyph — the mockup's, so a document reads as a document at a glance. */
    val glyph: String

    /** Closing this would throw away work, so the × asks first. */
    val dirty: Boolean

    /** The scenario flow editor. */
    data class Editor(
        /** The scenario as it stands in this tab. Nothing is written to disk until Save. */
        val draft: Scenario,
        /**
         * What the editor emits from an untouched scenario, and the *only* thing [dirty] is measured
         * against. Not the file: [EditStep] normalizes a Send's raw (parse → re-join), so a document that
         * has been opened and not touched can differ from its own file over a difference the author never
         * made — and every tab would then open dirty and every close would stop to ask.
         */
        val seed: Scenario,
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
         * rebuilds the editor from [draft], which is current, so the author's unsaved edits survive the
         * re-aim; only the cursor moves.
         */
        val focusEpoch: Int = 0,
    ) : ScenarioDoc {
        override val id: String get() = editorId(draft.id)
        override val title: String get() = draft.name.ifBlank { "untitled scenario" }
        override val glyph: String get() = "⚙"
        override val dirty: Boolean get() = draft != seed
    }

    /** Capture review: the session scan, curated, before any of it becomes a scenario. */
    data class Capture(
        /** A snapshot, not a live feed — the review curates a stable list (see [ScenarioCapture.scan]). */
        val scan: ScenarioCapture.Scan,
        val state: CaptureReviewState,
    ) : ScenarioDoc {
        override val id: String get() = CAPTURE_ID
        override val title: String get() = state.name.ifBlank { "capture" }.let { "capture: $it" }
        override val glyph: String get() = "⧉"
        override val dirty: Boolean get() = state != CaptureReviewState.of(scan.candidates.size)
    }

    companion object {
        /** There is one session scan at a time, so there is one capture review at a time. */
        const val CAPTURE_ID = "capture"

        fun editorId(scenarioId: String): String = "editor:$scenarioId"
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
