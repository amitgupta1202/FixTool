package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.compare.StrictSemantics
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A moment is judged against the moment the message arrived, everywhere — including inside the code that
 * decides whether an entry moved.**
 *
 * The diff already judged temporal rows at `actualAt`. What it did *not* do was tell
 * `ScenarioReconcile.reorder` about it: `verbatimWindow` asked `ExpectationEvaluator.satisfies`, which
 * hard-coded `Instant.now()` and a null resolver. So an entry carrying a `~now ±60s` row — an `MDEntryTime`
 * on any market-data snapshot, and every venue sends those — could never be recognised as having moved
 * *verbatim*, because one row of it "failed" against the reader's wall clock. Accept-new-order was then
 * silently withheld from exactly the messages that need it, and the author was left with a screenful of red
 * rows, no button, and no reason. A reference row inside an entry did the same thing, for the same reason.
 */
class ReconcileAnchorTest {
    /** 1 Jan 2025, which is not now and will never be now again. */
    private val arrival = Instant.parse("2025-01-01T00:00:00Z")
    private val arrivalOnTheWire = "20250101-00:00:00"

    /** Two market-data entries, each carrying a timestamp — the shape the defect hid in. */
    private fun snapshot() =
        Expectation(
            fields =
                listOf(
                    FieldExpectation(268, Matcher.Exact("2")),
                    FieldExpectation(269, Matcher.Exact("0")),
                    FieldExpectation(270, Matcher.Exact("1.10")),
                    FieldExpectation(273, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                    FieldExpectation(269, Matcher.Exact("1")),
                    FieldExpectation(270, Matcher.Exact("1.20")),
                    FieldExpectation(273, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                ),
            messageType = "W",
        )

    /** The same two entries, in the other order. Nothing regressed; the venue reshuffled. */
    private fun swapped() =
        wireView(
            268 to "2",
            269 to "1",
            270 to "1.20",
            273 to arrivalOnTheWire,
            269 to "0",
            270 to "1.10",
            273 to arrivalOnTheWire,
        )

    /**
     * THE DEFECT, REPRODUCED. The entries moved, whole and verbatim, and every row of them would pass at the
     * instant the message arrived — which is the instant the diff itself judges them at. The re-order is
     * withheld anyway, because the one row that cannot be judged against a wall clock is judged against one.
     */
    @Test
    fun `an entry carrying a timestamp is still recognised as having moved`() {
        val draft = snapshot()
        val reply = swapped()

        // The caller says which moment to judge at — and `reorder` has always accepted it. It was the
        // placement underneath that went to the wall clock behind its back.
        val reorder = ScenarioReconcile.reorder(draft, reply, now = { arrival })

        assertTrue(
            reorder is ScenarioReconcile.Reorder.Possible,
            "the entries moved verbatim — withholding the re-order here leaves the author with red rows, no " +
                "button and no reason: $reorder",
        )
        assertTrue(
            ExpectationEvaluator.evaluate(reply, reorder.reordered, now = { arrival }).all { it.passed },
            "and accepting it must actually repair the step",
        )
    }

    /** Two parties, one of them carrying an echoed value the reconcile view has no way to resolve. */
    private fun partiesWithAnEcho() =
        Expectation(
            fields =
                listOf(
                    FieldExpectation(453, Matcher.Exact("2")),
                    FieldExpectation(448, Matcher.Exact("FIRMA")),
                    FieldExpectation(447, Matcher.Reference("\${src}")),
                    FieldExpectation(452, Matcher.Exact("1")),
                    FieldExpectation(448, Matcher.Exact("FIRMB")),
                    FieldExpectation(447, Matcher.Reference("\${src}")),
                    FieldExpectation(452, Matcher.Exact("4")),
                ),
            messageType = "8",
        )

    private fun partiesSwapped() =
        wireView(453 to "2", 448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1")

    private fun asWritten(e: Expectation) =
        e.fields.map { row ->
            when (val m = row.matcher) {
                is Matcher.Exact -> m.value
                is Matcher.Reference -> m.expression
                else -> "?"
            }
        }

    /**
     * THE SAME DEFECT, FOR AN ECHO — **and this is the fixture that must not be dodged.** A reference resolves
     * against a live run's variable scope, and a reconcile view has none: no caller has a resolver to pass,
     * because there is nothing to pass. So the row is judged with the resolver the app really uses, which is
     * *none at all*, and `verbatimWindow` has to place it without asking whether its value holds — exactly as
     * `placeByOccurrence` has always done.
     *
     * A test that handed in a working resolver would pass over the code that produced the bug. It passed, and
     * the app went on telling the author "these entries did not move; the values changed in place" about a
     * message whose entries had plainly swapped.
     */
    @Test
    fun `an entry carrying a reference is still recognised as having moved, with no resolver at all`() {
        val reordered = ScenarioReconcile.acceptNewOrder(partiesWithAnEcho(), partiesSwapped())

        assertNotNull(reordered, "an echo inside a party entry must not hide the fact that the entry moved")
        assertEquals(
            listOf("2", "FIRMB", "\${src}", "4", "FIRMA", "\${src}", "1"),
            asWritten(reordered),
            "and FIRMA must still be the party that holds role 1",
        )
    }

    /** And it must reach the same answer when the scope IS available — a replay, or a future live re-judge. */
    @Test
    fun `and the same entry move is found when the reference does resolve`() {
        val reordered =
            ScenarioReconcile.acceptNewOrder(
                partiesWithAnEcho(),
                partiesSwapped(),
                referenceResolver = { if (it == "\${src}") "D" else null },
            )

        assertNotNull(reordered)
        assertEquals(listOf("2", "FIRMB", "\${src}", "4", "FIRMA", "\${src}", "1"), asWritten(reordered))
    }

    /**
     * **THE LIMIT OF THE LICENCE, AND WHY IT IS NOT OPTIONAL.**
     *
     * Placing an unjudgeable row without checking its value is safe only because the *other* rows of the block
     * still pin the window, value for value. A block with **no** judgeable row in it pins nothing: it is a tag
     * sequence matching a tag sequence, and tag sequences repeat. That is the rotation trap with the values
     * taken away.
     *
     * Here both party fields are scenario variables, and the row above them fails, so the occurrence rule
     * stands down and the block rule is all that is left. The engine has the two echo rows checking the
     * *second* party (its greedy cursor was pushed past the first). Let a block of two unreadable rows "match"
     * the first party on the strength of its tags — `448, 447`, which is every party ever sent — and the two
     * rows are silently re-aimed onto a firm the author never chose, and reported as an entry that moved. No
     * value was compared to reach that conclusion, because there was no value anybody here could read.
     */
    @Test
    fun `a block of nothing but unjudgeable rows is never called a move`() {
        val draft =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(452, Matcher.Exact("9")),
                        FieldExpectation(448, Matcher.Reference("\${firm}")),
                        FieldExpectation(447, Matcher.Reference("\${src}")),
                    ),
                messageType = "8",
            )
        // Two parties. The echo rows pair with the SECOND one; the first has the same tags and different values.
        val reply = wireView(448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "E")

        val reorder = ScenarioReconcile.reorder(draft, reply)

        assertTrue(
            reorder !is ScenarioReconcile.Reorder.Possible,
            "two rows nobody can read, 'matching' a party by its tag shape alone, is not evidence of a move — " +
                "and accepting it would re-aim both onto a firm the author never chose: $reorder",
        )
    }

    /**
     * And the rule the anchor exists for, stated on its own: a row that passed during the run does not become
     * a venue regression because the engineer took two minutes to click Reconcile.
     */
    @Test
    fun `a temporal row judged at the arrival instant passes, and judged at the clock does not`() {
        val draft =
            Expectation(
                fields = listOf(FieldExpectation(273, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60))),
                messageType = "W",
            )
        val reply = wireView(273 to arrivalOnTheWire)

        assertTrue(
            ScenarioReconcile.rows(draft, reply, dictionary = null, now = { arrival }).single().passed,
        )
        assertTrue(
            !ScenarioReconcile.rows(draft, reply, dictionary = null, now = { Instant.now() }).single().passed,
            "if this passes, the fixture's timestamp is close enough to now that the test proves nothing",
        )
    }

    // ----- the reference slot ---------------------------------------------------------------------------

    private fun temporalRow() =
        Expectation(
            fields = listOf(FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60))),
            messageType = "8",
        )

    /**
     * **A pasted message is judged at its own SendingTime, not at the clock.** Paste a message an hour old
     * into the slot and a `~now ±60s` row must not go red for that reason: it passed when the message was
     * sent, which is the only claim it ever made. The author's repairs on a temporal row are to loosen it or
     * drop it, so a phantom red here does not stay a red — it becomes a deleted assertion.
     */
    @Test
    fun `a pasted message anchors its temporals to its own SendingTime`() {
        val anHourAgo = "20250101-00:00:00"
        val paste = wireView(35 to "8", 52 to anHourAgo, 60 to anHourAgo)

        val reference = ReferenceMessage.pasted(paste)

        assertEquals(arrival, reference.anchorInstant, "the anchor is tag 52, read by the evaluator's own parser")
        assertEquals(ReferenceMessage.Provenance.PASTED, reference.provenance)
        val row = ScenarioReconcile.rows(temporalRow(), reference, dictionary = null).first { it.tag == 60 }
        assertTrue(row.passed, "judged at the moment it was sent, this row holds — and it is not a venue regression")
    }

    /**
     * And where a paste carries no moment at all, the row is **unjudged** — not passed, not failed. The tool
     * does not know when that message was sent, and inventing an answer costs coverage either way: a red
     * gets the assertion deleted, a green asserts nothing.
     */
    @Test
    fun `a paste with no SendingTime leaves its temporal rows unjudged, and says why`() {
        val reference = ReferenceMessage.pasted(wireView(35 to "8", 60 to "20250101-00:00:00"))

        assertTrue(reference.unanchored)
        val row = ScenarioReconcile.rows(temporalRow(), reference, dictionary = null).first { it.tag == 60 }

        assertTrue(row.unknown, "an unanchored temporal is a third state, and it is excluded from the counts")
        assertTrue(!row.judged)
        assertTrue("no SendingTime(52)" in row.reason, row.reason)
        assertTrue("left unjudged" in row.reason, row.reason)
    }

    /** The slot swaps, and the whole diff re-judges. That is all "compare against something else" is. */
    @Test
    fun `swapping the reference re-judges every row`() {
        val draft = Expectation(listOf(FieldExpectation(39, Matcher.Exact("2"))), messageType = "8")
        val golden = ReferenceMessage.golden(wireView(35 to "8", 52 to "20250101-00:00:00", 39 to "2"))
        val thisRun =
            ReferenceMessage.live(
                wireView(35 to "8", 39 to "8"),
                ReferenceMessage.Provenance.THIS_RUN,
                "this run · 09:35:44",
                arrival,
            )

        val againstGolden = StrictSemantics.align(draft, golden, null).rows
        val againstThisRun = StrictSemantics.align(draft, thisRun, null).rows

        assertTrue(againstGolden.first { it.tag == 39 }.passed)
        assertTrue(
            !againstThisRun.first { it.tag == 39 }.passed,
            "the same expectation, a different reference, and no edit in between",
        )
    }
}
