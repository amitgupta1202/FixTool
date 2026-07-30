package com.knapsack.fixtool.model

/**
 * What the message editor is editing — and therefore what its terminal action does.
 *
 * Orthogonal to [MessageEditorState], which is about a *saved template's* identity (new, clean,
 * dirty). A message being composed for a session and a reply step being edited for a rule can each
 * be in any of those states; what separates them is where the result goes.
 */
sealed interface EditorTarget {
    /** The wire: the fields are a message, and the terminal action sends it to a session. */
    data object Wire : EditorTarget

    /**
     * One step of one acceptor rule's reply. The terminal action writes it back to the staged rule.
     *
     * Addressed by index because a rule's index is its identity — the list is ordered and
     * first-match-wins, so position is meaningful and there is nothing else to name a rule by. Which
     * is also why [snapshot] exists: an index is only an address for as long as the list holds still,
     * and the author can delete or reorder rules while a step of one is open in the editor. It is the
     * template as it was when the step was opened, and Apply refuses if what is at that address no
     * longer matches — writing anyway would silently edit a different step.
     */
    data class ReplyStep(
        val profileId: String,
        val ruleIndex: Int,
        val stepIndex: Int,
        val snapshot: String,
    ) : EditorTarget
}

/** A reply step written back: what to put where, and the address it was read from. */
data class ReplyStepApply(
    val profileId: String,
    val ruleIndex: Int,
    val stepIndex: Int,
    val snapshot: String,
    val template: String,
)
