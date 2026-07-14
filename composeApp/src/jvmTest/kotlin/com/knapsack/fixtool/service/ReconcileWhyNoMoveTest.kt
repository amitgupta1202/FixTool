package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the engine withholds a move, it must **say why**.
 *
 * This is the defect that read as a missing feature. The two firms swap roles; the `448` rows go red; the
 * engine considers a re-order, works out that it would have to tear an entry in half, and correctly refuses —
 * and then says nothing at all. The author, looking at two red rows in a party group with no bracket and no
 * Accept-new-order anywhere, concludes that re-ordering was never built. It was. The tool simply kept its
 * reasoning to itself.
 *
 * A refusal that does not explain itself is indistinguishable from an absence.
 */
class ReconcileWhyNoMoveTest {
    private fun exp(vararg f: FieldExpectation) = Expectation(f.toList())

    private fun party(id: String, role: String) =
        listOf(
            FieldExpectation(448, Matcher.Exact(id)),
            FieldExpectation(447, Matcher.Exact("D")),
            FieldExpectation(452, Matcher.Exact(role)),
        )

    /**
     * DEMO B — the role swap. FIRMA and FIRMB exchange roles; the `452` rows still pass exactly where they
     * are. A move here would carry 448 and 447 across while 452 stayed behind, which is not an entry moving,
     * it is an entry being torn in half. No move is offered — and now the view can say so.
     */
    @Test
    fun `a role swap explains that the entries did not move`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())
        // Same positions, same roles — the FIRMS swapped. Nothing moved; the values changed in place.
        val reply =
            FixMessageView.ofFields(
                listOf(448 to "FIRMB", 447 to "D", 452 to "1", 448 to "FIRMA", 447 to "D", 452 to "4"),
            )

        assertNull(ScenarioReconcile.acceptNewOrder(draft, reply), "a move here would be the false green")

        val why = ScenarioReconcile.whyNoReorder(draft, reply)
        assertTrue(why != null, "the engine knows why it refused; it has to say it")
        assertTrue("did not move" in why, why)
        assertTrue("Accept actual" in why, "and it must leave the author somewhere to go: $why")
    }

    /**
     * The other refusal: nothing is out of position at all, the values at each row's own occurrence simply
     * changed. Still worth saying, because the author is staring at red rows in a group and wondering where
     * the re-order button is.
     */
    @Test
    fun `values changing inside a group explain that no re-order would help`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())
        // Neither entry moved and neither firm moved — one firm was renamed.
        val reply =
            FixMessageView.ofFields(
                listOf(448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMZ", 447 to "D", 452 to "4"),
            )

        val why = ScenarioReconcile.whyNoReorder(draft, reply)
        assertTrue(why != null, "a group with red rows and no move on offer must explain itself")
        assertTrue("did not move" in why, why)
    }

    /**
     * ...but it must not become noise. A lone `151` that went from 0 to 500000 has no entries, no
     * occurrences and nothing to re-order. Announcing "no move is offered" there would be a sentence about a
     * feature the author was never reaching for.
     */
    @Test
    fun `a plain value regression outside a group says nothing about moves`() {
        val draft =
            exp(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(151, Matcher.Numeric(0.0)),
            )
        val reply = FixMessageView.ofFields(listOf(35 to "8", 151 to "500000"))

        assertNull(
            ScenarioReconcile.whyNoReorder(draft, reply),
            "there was never a re-order to withhold here, so there is nothing to explain",
        )
    }

    /** And a step that passes says nothing either. */
    @Test
    fun `a passing step says nothing about moves`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())
        val reply =
            FixMessageView.ofFields(
                listOf(448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4"),
            )

        assertNull(ScenarioReconcile.whyNoReorder(draft, reply))
    }

    /**
     * The venue stops sending the third party. That is a SHAPE change — the commonest one there is — and the
     * refusal used to announce, over a verdict bar reading "3 tags missing · it is all shape", that the values
     * had changed in place and the venue was behaving differently. It then advised an Accept actual that a
     * missing row does not even offer. Nothing changed value; three rows simply did not arrive.
     */
    @Test
    fun `a dropped entry is not described as a value change`() {
        val draft =
            exp(*(party("FIRMA", "1") + party("FIRMB", "4") + party("FIRMC", "7")).toTypedArray())
        val reply =
            FixMessageView.ofFields(
                listOf(448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4"),
            )

        assertNull(
            ScenarioReconcile.whyNoReorder(draft, reply),
            "the venue dropped an entry; it did not change a value, and the tool must not say it did",
        )
    }

    /**
     * A row this view cannot judge offline is not a row that changed. A `~now ±60s` inside a group "fails"
     * against the wall clock an hour after the run — and the refusal would then print, under a diff the view
     * renders entirely green (it judges at the message's arrival instant), that the values changed in place
     * and the author should Accept actual. That click pins the scenario to a timestamp that will never recur.
     */
    @Test
    fun `a temporal row judged outside its run is not a value change`() {
        val draft =
            exp(
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
            )
        val longAgo = "20200101-00:00:00"
        val reply =
            FixMessageView.ofFields(
                listOf(448 to "FIRMA", 60 to longAgo, 448 to "FIRMB", 60 to longAgo),
            )

        assertNull(
            ScenarioReconcile.whyNoReorder(draft, reply),
            "a timestamp outside its window offline is not evidence that the venue changed anything",
        )
    }

    /**
     * A venue that swaps 447 and 452 *inside* each party is a pure shape change, and the occurrence rule —
     * which is structurally occurrence-preserving — is what covers it. The move can still be withheld here by
     * the conservative solid-span guard, but "the entries did not move, the values changed in place" is then
     * simply false, and it points the author at an Accept actual that would re-aim their assertion onto
     * another party's field. When the tool is not certain what happened, it says nothing.
     */
    @Test
    fun `a within-entry reshape is never described as a role swap`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())
        // The venue now emits 452 before 447 inside each party. Nothing about the parties changed.
        val reply =
            FixMessageView.ofFields(
                listOf(448 to "FIRMA", 452 to "1", 447 to "D", 448 to "FIRMB", 452 to "4", 447 to "D"),
            )

        val why = ScenarioReconcile.whyNoReorder(draft, reply)
        assertTrue(
            why == null || "did not move" !in why,
            "nothing swapped roles here — the fields were reshaped inside each entry: $why",
        )
    }

    /** A genuine reorder is offered, so there is no refusal to explain — the bracket speaks for itself. */
    @Test
    fun `a genuine entry reorder is offered, and explains nothing`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())
        // The entries swapped places. FIRMA still holds role 1 — this is shape, and it is one click.
        val reply =
            FixMessageView.ofFields(
                listOf(448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1"),
            )

        assertTrue(ScenarioReconcile.acceptNewOrder(draft, reply) != null, "this one really is a move")
        assertNull(ScenarioReconcile.whyNoReorder(draft, reply), "and a move on offer needs no apology")
    }
}
