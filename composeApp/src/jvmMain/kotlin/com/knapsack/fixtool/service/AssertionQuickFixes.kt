package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.Matcher

/**
 * Relax-at-point-of-failure edits: the three per-tag verbs offered on a failed assertion row in the
 * message detail panel. Deliberately narrow — anything richer (regex, tolerance, group paths) goes
 * through the full expectation builder via the failure → editor deep-link. Edits are batched in the
 * ViewModel and applied here in one pass on explicit Save; there is no bulk "make it all pass".
 */
object AssertionQuickFixes {
    enum class Kind {
        /** Re-baseline: the run's actual value becomes the expected one (absent actual → [Matcher.Absent]). */
        ACCEPT_ACTUAL,

        /** The tag must exist but its value is volatile — loosen to [Matcher.Presence]. */
        LOOSEN_TO_PRESENCE,

        /** Stop checking this tag entirely. */
        DROP,
    }

    /** One pending edit: which assertion — (tag, path) — and what to do with it. */
    data class Edit(
        val tag: Int,
        val path: GroupPath?,
        val kind: Kind,
        /** The failed run's actual value; the new expected for [Kind.ACCEPT_ACTUAL]. */
        val actual: String?,
    )

    /**
     * Applies [edits] to [expectation]. Each edit locates its field by exact (tag, path), falling
     * back to a path-less same-tag field (scenarios saved before group-aware capture). An edit whose
     * field no longer exists is skipped — the assertion is already gone, which is what DROP wanted
     * and the closest safe outcome for the others.
     */
    fun apply(expectation: Expectation, edits: List<Edit>): Expectation {
        val fields = expectation.fields.toMutableList()
        for (edit in edits) {
            var idx = fields.indexOfFirst { it.tag == edit.tag && it.path == edit.path }
            if (idx < 0) idx = fields.indexOfFirst { it.tag == edit.tag && it.path == null }
            if (idx < 0) continue
            when (edit.kind) {
                Kind.DROP -> fields.removeAt(idx)
                Kind.ACCEPT_ACTUAL ->
                    fields[idx] = fields[idx].copy(matcher = edit.actual?.let { Matcher.Exact(it) } ?: Matcher.Absent)
                Kind.LOOSEN_TO_PRESENCE -> fields[idx] = fields[idx].copy(matcher = Matcher.Presence)
            }
        }
        return expectation.copy(fields = fields)
    }
}
