package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reconcile actions — the only surface that authors an assertion from a failure.
 *
 * Every test here is really the same test: **an action may not re-aim an assertion onto a field the
 * author did not choose.** The reconcile view is where a tired engineer clicks things to make a red
 * build go green, which makes it the likeliest place in the whole tool to manufacture a false green.
 */
class ScenarioReconcileTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun expectation(vararg fields: FieldExpectation, mode: MatchMode = MatchMode.OPEN) =
        Expectation(fields.toList(), messageType = "8", mode = mode)

    private fun rows(draft: Expectation, message: MessageView) = ScenarioReconcile.rows(draft, message, dictionary)

    // ---------------------------------------------------------------- what the view is looking at

    @Test
    fun `the diff shows the reply's unasserted fields, which OPEN's results do not`() {
        val reply = wireView(35 to "8", 39 to "2", 58 to "note")
        val draft = expectation(FieldExpectation(35, Matcher.Exact("8")))

        val rows = rows(draft, reply)

        assertEquals(listOf(35, 39, 58), rows.map { it.tag }, "the author must see what they are not asserting")
        assertTrue(rows.single { it.tag == 39 }.unasserted)
        // ...but they are not failures, and the run must not report them as such.
        assertTrue(ExpectationEvaluator.evaluate(reply, draft).all { it.passed })
    }

    // ------------------------------------------------------------------------- accept new order

    /**
     * The case the subsequence rule makes an author hit: the venue sends 37 before 11, the expectation
     * lists 11 before 37. Nothing is wrong with either. One click puts the rows in the venue's order.
     */
    @Test
    fun `accept new order re-sorts the rows into the order the venue sent them`() {
        val reply = wireView(35 to "8", 37 to "EXEC-1", 11 to "ORD-1")
        val draft =
            expectation(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(37, Matcher.Presence),
            )
        assertTrue(rows(draft, reply).any { it.status == TagStatus.MOVED }, "precondition: it is red")

        val fixed = ScenarioReconcile.acceptNewOrder(draft, reply) ?: error("this is exactly what it is for")

        assertEquals(listOf(35, 37, 11), fixed.fields.map { it.tag })
        assertTrue(ExpectationEvaluator.evaluate(reply, fixed).all { it.passed }, "one click, and it passes")
    }

    /**
     * A venue that emits a group entry's delimiter second sends the same data in a different order. Every row
     * still describes a field that is in the reply — it is just somewhere else — so this is exactly what
     * Accept-new-order is for, and it must not touch what any row asserts.
     */
    @Test
    fun `accept new order handles a whole group entry being reshaped`() {
        val reply =
            wireView(
                447 to "D",
                448 to "A",
                452 to "1",
                447 to "E",
                448 to "B",
                452 to "4",
            )
        val draft =
            expectation(
                FieldExpectation(448, Matcher.Exact("A")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("B")),
                FieldExpectation(447, Matcher.Exact("E")),
                FieldExpectation(452, Matcher.Exact("4")),
            )

        val fixed = ScenarioReconcile.acceptNewOrder(draft, reply) ?: error("every row's field is in the reply")

        assertEquals(listOf(447, 448, 452, 447, 448, 452), fixed.fields.map { it.tag })
        val results = ExpectationEvaluator.evaluate(reply, fixed)
        assertTrue(results.all { it.passed }, "the entries are intact; only the field order changed: $results")
        // Each row still asserts exactly what it always asserted, and now lands on the field that has it.
        assertEquals(listOf("D", "A", "1", "E", "B", "4"), results.map { it.actual })
    }

    /**
     * THE ONE THAT MATTERS, AND THE ONE THAT WAS BROKEN.
     *
     * A lone `452` row can legitimately be checking the *second* `452` — an earlier row having pushed the
     * engine's cursor past the first. The first version of acceptNewOrder asked a different question (the
     * k-th row for a tag → the k-th occurrence of it), which is a second pairing rule, and it disagreed
     * with the engine's. So it moved that green row onto the first `452`, turning it red; the author, now
     * offered "Accept actual" on it, would rebase their assertion onto a party entry they never chose while
     * the one they *did* choose silently stopped being asserted. Green build, coverage gone, two clicks.
     */
    @Test
    fun `accept new order never moves a row that is already paired with a field`() {
        val reply = wireView(452 to "1", 55 to "EUR/USD", 452 to "4", 44 to "100", 38 to "5")
        // The 452 row pairs with the SECOND 452 (value 4) because the 55 row pushed the cursor past the
        // first. It passes. Only tag 44 is out of place.
        val draft =
            expectation(
                FieldExpectation(55, Matcher.Exact("EUR/USD")),
                FieldExpectation(452, Matcher.Exact("4")),
                FieldExpectation(38, Matcher.Exact("5")),
                FieldExpectation(44, Matcher.Exact("100")),
            )
        val before = rows(draft, reply)
        assertTrue(
            before.single { it.tag == 452 && !it.unasserted }.passed,
            "precondition: the 452 row is green, checking the 2nd entry",
        )

        val fixed = ScenarioReconcile.acceptNewOrder(draft, reply) ?: error("the 44 row can be re-ordered")

        val after = ScenarioReconcile.rows(fixed, reply, dictionary)
        assertTrue(after.filter { it.judged }.all { it.passed }, "the re-order must fix 44: $after")
        assertEquals(
            "4",
            after.single { it.tag == 452 && !it.unasserted }.actual,
            "the 452 row must STILL be checking the second party entry — re-ordering may not re-aim it",
        )
    }

    /**
     * The venue swapped its two party entries. That is the message's **shape** changing, not its behaviour,
     * and the spec is explicit: a venue that legitimately reorders is a one-click acknowledgement, not a
     * redesign of the scenario.
     *
     * The re-order is safe because each row carries its own matcher with it — the row asserting the clearing
     * firm's role lands on the clearing firm's entry. Both roles remain asserted; nothing is re-aimed and no
     * coverage is lost. (What must never happen is a *passing* row being moved, which is the test above.)
     */
    @Test
    fun `two entries that swapped are re-ordered, and every assertion survives`() {
        val reply =
            wireView(
                448 to "FIRMA",
                452 to "4",
                448 to "FIRMA",
                452 to "1",
            )
        val draft =
            expectation(
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(452, Matcher.Exact("4")),
            )

        val fixed = ScenarioReconcile.acceptNewOrder(draft, reply) ?: error("the entries merely swapped")

        assertEquals(
            listOf(Matcher.Exact("FIRMA"), Matcher.Exact("4"), Matcher.Exact("FIRMA"), Matcher.Exact("1")),
            fixed.fields.map { it.matcher },
            "both roles are still asserted — the clearing firm is simply expected first now",
        )
        assertTrue(ExpectationEvaluator.evaluate(reply, fixed).all { it.passed })
    }

    /**
     * The venue sent FEWER party entries than were captured. The surplus rows have nowhere to go, so there
     * is nothing to re-order — and offering the button anyway gave the author one that did nothing at all.
     */
    @Test
    fun `a venue sending fewer entries is missing rows, not moved ones — and no re-order is offered`() {
        val reply = wireView(35 to "8", 448 to "FIRMA", 452 to "1")
        val draft =
            expectation(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(452, Matcher.Presence),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(452, Matcher.Presence),
            )

        val rows = rows(draft, reply)
        val surplus = rows.filter { !it.passed }

        assertEquals(listOf(448, 452), surplus.map { it.tag })
        assertTrue(
            surplus.all { it.status == TagStatus.MISSING },
            "the surviving entry satisfies them, but no row is free to check — that is missing, not moved: " +
                surplus.map { it.status },
        )
        assertEquals(emptyList(), ScenarioReconcile.movedBlocks(rows))
        assertNull(ScenarioReconcile.acceptNewOrder(draft, reply), "a button that cannot help must not appear")
    }

    // -------------------------------------------------------------------------------- absent

    @Test
    fun `a failing absent row shows what the reply actually carries, and can be accepted`() {
        val reply = wireView(35 to "8", 58 to "the note")
        val draft = expectation(FieldExpectation(35, Matcher.Exact("8")), FieldExpectation(58, Matcher.Absent))

        val row = rows(draft, reply).single { it.tag == 58 }
        assertFalse(row.passed)
        assertEquals("the note", row.actual, "the whole failure is that the reply HAS 58 — the view must show it")

        // ...and Accept-actual is therefore a real repair, not `absent` rewritten to `absent`.
        val fixed = ScenarioReconcile.acceptActual(draft, row.index!!, row.actual)
        assertEquals(Matcher.Exact("the note"), fixed.fields[1].matcher)
        assertTrue(ExpectationEvaluator.evaluate(reply, fixed).all { it.passed })
    }

    @Test
    fun `assert-absent is not offered while the reply still carries the tag`() {
        // Three parties captured, two arrive. The third row is unrepairable by `absent`: it asserts the tag
        // appears NOWHERE, and two of them are right there.
        val reply = wireView(452 to "1", 452 to "4")
        val draft =
            expectation(
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(452, Matcher.Exact("4")),
                FieldExpectation(452, Matcher.Exact("7")),
            )

        assertFalse(ScenarioReconcile.canAssertAbsent(draft, reply, 2), "452 is still in the reply, twice")
        assertTrue(ScenarioReconcile.canAssertAbsent(expectation(FieldExpectation(58, Matcher.Exact("x"))), reply, 0))
    }

    // ----------------------------------------------------------------------------- references

    @Test
    fun `a reference matcher is unknown here, not failing`() {
        // It resolves against a live run's scope. Rendering it red would put "Accept actual" under a row
        // whose reference was working perfectly — one click pins it to this run's id and the cross-step
        // binding is gone.
        val reply = wireView(35 to "8", 11 to "ORD-1")
        val draft =
            expectation(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Reference("\${'$'}{out.D.11}")),
            )

        val row = rows(draft, reply).single { it.tag == 11 }
        assertTrue(row.unknown, "no resolver here means we cannot judge it — which is not the same as failing")
        assertFalse(row.judged, "and it must not count towards the verdict either way")
    }

    // --------------------------------------------------------------------------- add assertion

    @Test
    fun `add assertion inserts the row where it pairs, not at the end`() {
        val reply = wireView(35 to "8", 150 to "0", 39 to "2")
        val draft = expectation(FieldExpectation(35, Matcher.Exact("8")), FieldExpectation(39, Matcher.Exact("2")))

        // Assert the 150 the reply carries — it sits between the two rows we already have.
        val wireIndex = reply.fields().indexOfFirst { it.first == 150 }
        val fixed = ScenarioReconcile.addAssertion(draft, reply, wireIndex, dictionary)

        assertEquals(listOf(35, 150, 39), fixed.fields.map { it.tag }, "appended at the end it would pair with nothing")
        assertTrue(
            ExpectationEvaluator.evaluate(reply, fixed).all { it.passed },
            "a row added from the message must not make the expectation stop matching that message",
        )
    }

    @Test
    fun `add assertion seeds the matcher from the dictionary, not from a blind exact`() {
        val reply = wireView(35 to "8", 37 to "EXEC-9")
        val draft = expectation(FieldExpectation(35, Matcher.Exact("8")))

        val fixed = ScenarioReconcile.addAssertion(draft, reply, 1, dictionary)

        // OrderID is venue-assigned and random — seeding it exact would go red on the very next run.
        assertEquals(Matcher.Presence, fixed.fields.single { it.tag == 37 }.matcher)
    }

    // ---------------------------------------------------------------------------------- drop

    /**
     * Dropping one row of a repeated tag promotes its siblings — the second `452` becomes the first, and
     * silently starts checking the executing firm's entry while still claiming to check the clearing
     * firm's. So a drop takes the tag's rows with it.
     */
    @Test
    fun `dropping one occurrence of a repeated tag drops them all, and says so`() {
        val draft =
            expectation(
                FieldExpectation(448, Matcher.Exact("A")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("B")),
                FieldExpectation(452, Matcher.Exact("4")),
            )

        assertTrue(ScenarioReconcile.dropTakesWholeTag(draft, 1), "the view must warn before this happens")

        val fixed = ScenarioReconcile.drop(draft, 1) // the first 452
        assertEquals(listOf(448, 448), fixed.fields.map { it.tag }, "no lone 452 row left to be promoted")
    }

    @Test
    fun `dropping a tag that occurs once drops only that row`() {
        val draft = expectation(FieldExpectation(35, Matcher.Exact("8")), FieldExpectation(58, Matcher.Exact("x")))

        assertFalse(ScenarioReconcile.dropTakesWholeTag(draft, 1))
        assertEquals(listOf(35), ScenarioReconcile.drop(draft, 1).fields.map { it.tag })
    }

    // ------------------------------------------------------------------------- the simple verbs

    @Test
    fun `accept actual, loosen, and assert absent each touch exactly their own row`() {
        val reply = wireView(452 to "1", 452 to "9")
        val draft = expectation(FieldExpectation(452, Matcher.Exact("1")), FieldExpectation(452, Matcher.Exact("4")))

        // The second row is the one that failed (actual 9). Accepting it must not disturb the first.
        val accepted = ScenarioReconcile.acceptActual(draft, 1, "9")
        assertEquals(listOf(Matcher.Exact("1"), Matcher.Exact("9")), accepted.fields.map { it.matcher })
        assertTrue(ExpectationEvaluator.evaluate(reply, accepted).all { it.passed })

        assertEquals(Matcher.Presence, ScenarioReconcile.loosen(draft, 1, Matcher.Presence).fields[1].matcher)
        assertEquals(Matcher.Exact("1"), ScenarioReconcile.loosen(draft, 1, Matcher.Presence).fields[0].matcher)
        assertEquals(Matcher.Absent, ScenarioReconcile.assertAbsent(draft, 1).fields[1].matcher)
    }

    @Test
    fun `accept actual on a tag the venue stopped sending becomes an absent assertion`() {
        val draft = expectation(FieldExpectation(58, Matcher.Exact("note")))
        val accepted = ScenarioReconcile.acceptActual(draft, 0, null)
        assertEquals(Matcher.Absent, accepted.fields.single().matcher)
    }

    // --------------------------------------------------------------------------- moved blocks

    @Test
    fun `a moved party is bracketed as one block, not as three separate broken rows`() {
        // Captured with Symbol ahead of the party entry; the venue now sends Symbol at the end. The whole
        // party — delimiter and all — is what dislocated, and the author should be offered one fix, not
        // three: three clicks would be three ways to express one fact, each leaving the expectation in a
        // state that is momentarily wrong.
        val reply = wireView(35 to "8", 448 to "A", 447 to "D", 452 to "1", 55 to "EUR/USD")
        val draft =
            expectation(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(55, Matcher.Exact("EUR/USD")),
                FieldExpectation(448, Matcher.Exact("A")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
            )

        val rows = rows(draft, reply)
        val blocks = ScenarioReconcile.movedBlocks(rows)

        assertEquals(1, blocks.size, "one dislocation: ${rows.map { it.tag to it.status }}")
        assertEquals(listOf(448, 447, 452), blocks.single().map { rows[it].tag })

        // And the one click fixes the lot, without touching what any row refers to.
        val fixed = ScenarioReconcile.acceptNewOrder(draft, reply) ?: error("a whole party moved; that is re-orderable")
        assertEquals(listOf(35, 448, 447, 452, 55), fixed.fields.map { it.tag })
        assertTrue(ExpectationEvaluator.evaluate(reply, fixed).all { it.passed })
    }

    // --------------------------------------------------------------------------------- re-seed

    @Test
    fun `re-seed replaces the step from the message that actually arrived, keeping the mode`() {
        val reply = wireView(35 to "8", 11 to "ORD-9", 39 to "2")

        val fresh = ScenarioReconcile.reseed(reply, dictionary, MatchMode.STRICT)

        assertEquals(listOf(35, 11, 39), fresh.fields.map { it.tag })
        assertEquals(MatchMode.STRICT, fresh.mode)
        assertTrue(ExpectationEvaluator.evaluate(reply, fresh).all { it.passed })
    }
}
