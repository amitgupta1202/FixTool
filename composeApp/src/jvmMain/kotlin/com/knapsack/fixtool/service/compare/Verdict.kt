package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.ScenarioReconcile

/**
 * **The line that separates shape from behaviour** — the one sentence a reader must never have to work out
 * for themselves, and the most bug-prone arithmetic in the application.
 *
 * Four red rows say nothing about whether the venue *regressed* or merely *reshaped* its message. A venue
 * that reorders its party entries, adds an optional tag, or stops sending one has changed the message's
 * shape; a venue that fills 500,000 where it used to fill nothing has changed its behaviour. Only the second
 * is a regression. This says which just happened:
 *
 * > *"9 rows need attention │ 1 value changed · 1 tag added · 1 tag missing · 2 entries moved │ only the
 * > value change alters what this scenario checks"*
 *
 * **Why this is a pure function in the engine's package and not a composable.**
 *
 * It lived inside `ReconcileView.VerdictBar`, and every counter below carries the scar of a defect that
 * shipped in it — a bar that announced success over a step that fails, a bar that announced success over an
 * expectation asserting that a ClOrdID equals an OrdStatus. The diff surface that replaces that view must
 * render *this* verdict rather than count its own rows, because a second implementation would arrive at the
 * same mistakes by the same route, and its tests would be written from the same misunderstanding that made
 * them. One verdict, one set of tests, two surfaces — until the old one is deleted and there is one of each.
 */
data class Verdict(
    /** Rows this diff can actually judge: not an unasserted extra, not a row it cannot read. */
    val judged: Int,
    /** The venue is *behaving* differently. The only bucket that is a regression. */
    val values: Int,
    /** Tags the reply carries that no row mentions. */
    val added: Int,
    /**
     * **Are those added tags a failure?** Only in STRICT. In OPEN an unmentioned tag is ignored — that is the
     * entire promise of OPEN — so counting them into [attention] painted a fully passing step FAILED and told
     * the author to go and fix a step that is not broken. `canAcceptShape` has always known this; the verdict,
     * which is the sentence read *first*, did not, and the engine and the diff then disagreed about whether the
     * step passes.
     */
    val addedAreFailures: Boolean,
    /** Tags the expectation lists that the reply did not send. */
    val missing: Int,
    /** Rows the engine proved moved — counted as **rows**, which is not the same number as [movedEntries]. */
    val movedRows: Int,
    /** Entries the engine proved moved — counted as **entries**. See the note on [attention]. */
    val movedEntries: Int,
    /** Failing rows none of the buckets above caught — a row reported MOVED that no re-order covers. */
    val unresolved: Int,
    /** Rows that cannot be judged here at all: a `reference` resolves against a live run's scope. */
    val unknown: Int,
) {
    /**
     * **Rows needing attention.** Two different units live in this class and they must not be confused:
     * this is a count of ROWS, while [movedEntries] counts ENTRIES.
     *
     * Feeding the entry count in here made the headline read *"2 of 21 rows need attention"* over six rows
     * that had all moved; and on a moved field belonging to no repeating group it counted zero, so the bar
     * announced that every assertion would now pass.
     *
     * [added] is in this sum **exactly once**, and — see [addedAreFailures] — only when the mode makes it a
     * failure at all. The headline used to add it a second time on top of this total, so every tag the venue
     * added inflated the number the author reads first: the canonical four-failures ExecutionReport — one
     * value, one added tag, one dropped tag, two swapped parties — announced *"10 of 17 rows need attention"*
     * over nine rows that need it.
     */
    val attention: Int get() = values + (if (addedAreFailures) added else 0) + missing + movedRows + unresolved

    /** A step that asserts nothing passes every run for ever while checking nothing. It is never a success. */
    val assertsNothing: Boolean get() = judged == 0

    /** Anything at all to do here — what the header's FAILED chip and the error colouring key off. */
    val needsAttention: Boolean get() = attention > 0

    /**
     * The headline. Read first, and trusted; so every clause of it has to be true of the rows below it.
     */
    val headline: String get() =
        when {
            assertsNothing -> "⚠ nothing is asserted — this step would pass every run without checking anything"
            // No denominator, and there never honestly was one: `attention` counts rows from BOTH sides —
            // a tag the venue added is a row the expectation does not have — while `judged` counts only the
            // rows the expectation *does*. On a two-row step against a reply with three extra tags the bar
            // read "4 of 2 rows need attention". The split below says what the four are; the ratio never could.
            attention == 1 -> "1 row needs attention"
            attention > 1 -> "$attention rows need attention"
            // Green, but only about what was actually checked. A row this view cannot judge is not a row that
            // passed. One click of "Loosen → reference" made a red row unjudgeable, dropped it out of every
            // count, and the bar announced "✓ every assertion would now pass" over an expectation asserting
            // that a ClOrdID equals an OrdStatus.
            unknown > 0 ->
                "✓ every checked assertion would now pass ($judged checked · $unknown only verifiable on a run)"
            else -> "✓ every assertion would now pass ($judged checked)"
        }

    /** `1 value changed · 1 tag added · 1 tag missing · 2 entries moved` — what happened, in its own units. */
    val parts: List<String> get() =
        buildList {
            if (values > 0) add("$values value${plural(values)} changed")
            if (added > 0) add("$added tag${plural(added)} added")
            if (missing > 0) add("$missing tag${plural(missing)} missing")
            if (movedEntries > 0) {
                add("$movedEntries entr${if (movedEntries == 1) "y" else "ies"} moved")
            } else if (movedRows > 0) {
                add("$movedRows field${plural(movedRows)} moved")
            }
            if (unresolved > 0) add("$unresolved out of order")
            if (unknown > 0) add("$unknown not checkable here")
        }

    /** The whole point of the bar: is this a regression, or is the venue merely re-arranging its message? */
    val shapeVersusBehaviour: String get() =
        when (values) {
            0 -> "nothing here changes what this scenario checks — it is all shape"
            1 -> "only the value change alters what this scenario checks"
            else -> "$values value changes alter what this scenario checks"
        }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    companion object {
        /**
         * Count the rows. [movedRows] and [movedEntries] come from the engine — a row is moved exactly when
         * Accept-new-order would put it somewhere else, so there is no second opinion here to disagree with
         * the first.
         */
        fun of(
            rows: List<ScenarioReconcile.Row>,
            movedRows: Set<Int>,
            movedEntries: Int,
            /**
             * STRICT counts a tag the venue added as a failure. OPEN, by definition, does not — and there is no
             * default here on purpose: a caller that forgets is a caller that calls a passing OPEN step failed.
             */
            mode: MatchMode,
        ): Verdict {
            fun moved(row: ScenarioReconcile.Row) = row.index != null && row.index in movedRows

            // A moved row is the message's SHAPE changing, not the venue's behaviour — that is the whole split.
            val bucketed = setOf(TagStatus.VALUE, TagStatus.INVALID, TagStatus.MISSING)
            return Verdict(
                judged = rows.count { it.judged },
                values =
                    rows.count {
                        !it.passed &&
                            !it.unknown &&
                            !moved(it) &&
                            (it.status == TagStatus.VALUE || it.status == TagStatus.INVALID)
                    },
                added = rows.count { it.unasserted && it.wireIndex != null },
                addedAreFailures = mode == MatchMode.STRICT,
                missing = rows.count { !it.passed && !moved(it) && it.status == TagStatus.MISSING },
                movedRows = rows.count { moved(it) },
                movedEntries = movedEntries,
                // ...and any failing row none of the buckets above caught. A row reported MOVED for which
                // there is no safe re-ordering has no block bracket, no Accept-new-order and no per-row fix —
                // it fell out of the count entirely, and the bar declared success over a step that fails.
                unresolved =
                    rows.count {
                        !it.passed && !it.unasserted && !it.unknown && !moved(it) && it.status !in bucketed
                    },
                unknown = rows.count { it.unknown },
            )
        }

        /**
         * Is there a *shape* change to accept in bulk?
         *
         * **Only in STRICT may an unasserted tag count.** In OPEN an unmentioned tag is not a failure — it is
         * the entire point of OPEN — so counting every unasserted field as "a tag the venue added" put the
         * bulk button on a fully-passing OPEN step, and clicking it asserted every field the author had
         * deliberately left alone.
         */
        fun canAcceptShape(rows: List<ScenarioReconcile.Row>, mode: MatchMode): Boolean =
            rows.any { ScenarioReconcile.isShapeChange(it) } ||
                (mode == MatchMode.STRICT && rows.any { it.unasserted && it.wireIndex != null })
    }
}
