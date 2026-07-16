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

    /**
     * **The headline, read against the message that is actually in the slot.**
     *
     * A red row does not always mean the same thing, and the sentence has to say which. Against this run's
     * failure — or the golden — it means *the venue did something new*. Against a **second instance**, a live
     * message of the same shape, it means the opposite: the expectation only passes against its own capture, so
     * it is **over-specified**, and that is the author's doing and the author's to loosen. "Verify generalizes"
     * has always answered `✓ generalizes` / `⚠ 2 over-specified`, and swapping the reference is what that check
     * *is* now — so those are the words it still answers in. Telling an author their venue has regressed when
     * what they have actually done is pin a timestamp would send them hunting a bug that does not exist.
     */
    fun headlineAgainst(provenance: ReferenceMessage.Provenance): String =
        when {
            // A step that asserts nothing passes every run for ever while checking nothing. That is true of it
            // against ANY message, and it is the one sentence the slot may not soften.
            assertsNothing -> headline
            // THIS_RUN and GOLDEN are the message the step is *about*. A red row there is the venue.
            !provenance.chosenByTheAuthor -> headline
            provenance == ReferenceMessage.Provenance.SECOND_INSTANCE -> overSpecified()
            else -> doesNotHoldAgainst(provenance)
        }

    private fun overSpecified(): String =
        when (attention) {
            0 -> "✓ generalizes — every assertion holds against a different message of the same shape"
            1 -> "⚠ 1 row is over-specified — it only passes against the message it was captured from"
            else -> "⚠ $attention rows are over-specified — they only pass against the message they were captured from"
        }

    /**
     * **PICKED and PASTED: the tool reports, and does not diagnose.**
     *
     * FixTool did not choose this message and cannot know why the author did. It may be UAT's reply to the same
     * order, a rejection where a fill was captured, or another venue entirely — so *"N rows need attention"*,
     * which in this surface has always meant **the venue did something new**, would accuse a venue of a
     * regression nobody has evidence of and send an engineer hunting a bug that does not exist. What is true is
     * that the rows do not hold against the message on the right. The split (`1 value changed · 1 tag missing`)
     * says the rest, and the author, who chose the message, is the one who knows what that means.
     */
    private fun doesNotHoldAgainst(provenance: ReferenceMessage.Provenance): String {
        val noun = if (provenance == ReferenceMessage.Provenance.PICKED) "the picked message" else "the pasted message"
        return when {
            attention == 1 -> "1 row does not hold against $noun"
            attention > 1 -> "$attention rows do not hold against $noun"
            // Green — and, exactly as in `headline`, green only about what could actually be checked here.
            unknown > 0 ->
                "✓ every checked assertion holds against $noun ($judged checked · $unknown only verifiable on a run)"
            else -> "✓ every assertion holds against $noun ($judged checked)"
        }
    }

    /**
     * **The header's chip — which is the headline in two words, and is read before it.**
     *
     * It used to be the literal string `failed`, drawn by the surface on [needsAttention] alone, with no idea
     * what was in the reference slot. So a step being *authored* against a message picked out of a grid — a step
     * that has **never run**, which is precisely why the pick prompt was showing — was painted **FAILED**, in
     * red, beside a verdict saying something else. And verify-generalizes, whose whole answer is *"over-specified"*,
     * was painted FAILED too. A step that has not run cannot have failed.
     *
     * Same rule as [headlineAgainst], and the same function is the reason they cannot come to disagree: the word
     * is licensed by what is on the right. Null when there is nothing to say — a green diff carries no chip.
     */
    fun chipAgainst(provenance: ReferenceMessage.Provenance): String? =
        when {
            !needsAttention -> null
            provenance == ReferenceMessage.Provenance.THIS_RUN -> "failed"
            // It has not run. This is a prediction about a run that has not happened, and it says so.
            provenance == ReferenceMessage.Provenance.GOLDEN -> "would fail"
            provenance == ReferenceMessage.Provenance.SECOND_INSTANCE -> "over-specified"
            else -> "does not hold"
        }

    /**
     * Is a red row here an accusation about the **venue**, or a fact about a message the author chose? It is
     * what the chip and the verdict line are coloured from: error is a claim, and it may only be made about the
     * message the step is actually about.
     */
    fun accusesTheVenue(provenance: ReferenceMessage.Provenance): Boolean =
        needsAttention && !provenance.chosenByTheAuthor

    /** `1 value changed · 1 tag added · 1 tag missing · 2 entries moved` — what happened, in its own units. */
    val parts: List<String> get() = partsUnder(open = false)

    /**
     * [parts], phrased for the semantics judging the diff. Under OPEN, a tag the reply *added* is ignored
     * by definition — counting "12 tags added" beside a green "every assertion would pass" read as twelve
     * problems the verdict was somehow overlooking. Same counts, honest words.
     */
    fun partsUnder(open: Boolean): List<String> =
        buildList {
            if (values > 0) add("$values value${plural(values)} changed")
            if (added > 0) {
                add(if (open) "$added extra tag${plural(added)} ignored (OPEN)" else "$added tag${plural(added)} added")
            }
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
                // Never a ghost: it is the other end of a move, and the moved row is already in the count.
                // Adding it here counts one divergence twice — the exact inflation this class exists to stop.
                added = rows.count { it.unasserted && !it.ghost && it.wireIndex != null },
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
                // A ghost is not a tag the venue added — the moved row asserting it is the shape change,
                // and it is already counted by the first clause.
                (mode == MatchMode.STRICT && rows.any { it.unasserted && !it.ghost && it.wireIndex != null })
    }
}
