package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Accept new order" may only move an entry that **actually moved**.
 *
 * The view's own label states the contract — *"Party entry moved — same tags, same values, different
 * position"* — and the engine did not enforce the middle clause. `plan()` re-aimed each failing row
 * *individually* onto whichever field its matcher happened to satisfy, which is the matcher-driven pairing
 * `align()` is explicitly forbidden from using, and it cannot tell these two apart:
 *
 * - the venue **reordered its party entries** — a shape change, and a legitimate one-click accept; and
 * - the two firms **swapped roles** — a behaviour regression, and the single most consequential thing a
 *   party group can tell you.
 *
 * Both present as "the 448 rows fail". Re-aiming by matcher greens them both.
 *
 * The rule that separates them: a moved block must appear **verbatim** — same tags, same values,
 * contiguously, in the same order — somewhere in the reply. An entry that really moved does. A role swap
 * does not, because the (firm, role) pairing is exactly what changed.
 */
class ReconcileMovedBlockTest {
    private fun exp(vararg f: FieldExpectation) = Expectation(f.toList())

    private fun party(id: String, role: String) =
        listOf(
            FieldExpectation(448, Matcher.Exact(id)),
            FieldExpectation(452, Matcher.Exact(role)),
        )

    /**
     * The legitimate case, which must keep working: the venue sends the same two parties, in the other order.
     * Each entry moved as a unit, so each appears verbatim in the reply.
     */
    @Test
    fun `a genuinely reordered entry is offered as a move, and accepting it keeps every assertion intact`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "2")).toTypedArray())
        // The entries swap places. FIRMA still has role 1; FIRMB still has role 2. Nothing regressed.
        val reply = FixMessageView.ofFields(listOf(448 to "FIRMB", 452 to "2", 448 to "FIRMA", 452 to "1"))

        val reordered = ScenarioReconcile.acceptNewOrder(draft, reply)

        assertTrue(reordered != null, "a real entry reorder must be a one-click accept")
        assertTrue(
            ExpectationEvaluator.evaluate(reply, reordered!!).all { it.passed },
            "and it must actually repair the step",
        )
        // The assertions themselves are unchanged — only their order. FIRMA still asserts role 1.
        assertEquals(
            listOf(448 to "FIRMB", 452 to "2", 448 to "FIRMA", 452 to "1"),
            reordered.fields.map { it.tag to (it.matcher as Matcher.Exact).value },
        )
    }

    /**
     * The false green. Two firms swap roles — FIRMA was the executing firm, it is now the clearing firm —
     * and nothing about the message's shape changed at all.
     *
     * The old plan() re-aimed the FIRMA row onto the FIRMB position and vice versa, verified the permutation
     * green, bracketed it as "entry moved — same values, different position", and the verdict line told the
     * author it was "all shape". One click and the assertion "FIRMA has role 1" was gone, silently.
     */
    @Test
    fun `two firms swapping roles is a behaviour change and must never be offered as a reorder`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "2")).toTypedArray())
        // Same positions, same roles — the FIRMS moved. FIRMB now holds role 1. That is a regression.
        val reply = FixMessageView.ofFields(listOf(448 to "FIRMB", 452 to "1", 448 to "FIRMA", 452 to "2"))

        assertNull(
            ScenarioReconcile.acceptNewOrder(draft, reply),
            "a role swap is not a reorder: accepting it deletes the assertion that FIRMA holds role 1",
        )
        assertTrue(
            ScenarioReconcile.movedRows(draft, reply).isEmpty(),
            "and no row may be bracketed as moved, or the view will call a regression 'all shape'",
        )

        // It presents as what it is: the 448 rows failed on their values.
        val rows = ScenarioReconcile.rows(draft, reply, null)
        val behaviour = rows.filter { ScenarioReconcile.isBehaviourChange(it) }
        assertEquals(2, behaviour.size, "both PartyID rows changed value: $rows")
        assertTrue(behaviour.all { it.tag == 448 })
    }

    /**
     * The narrower version of the same trap: a bare pair of roles swapping, with nothing to distinguish the
     * entries. There is no reading of this that is a reorder, and refusing is the only safe answer.
     */
    @Test
    fun `two bare occurrences of a tag swapping values is not a reorder`() {
        val draft = exp(FieldExpectation(452, Matcher.Exact("1")), FieldExpectation(452, Matcher.Exact("4")))
        val reply = FixMessageView.ofFields(listOf(452 to "4", 452 to "1"))

        assertNull(
            ScenarioReconcile.acceptNewOrder(draft, reply),
            "with nothing but the roles to go on, a swap is indistinguishable from a regression — refuse",
        )
    }

    /** A single top-level field the venue moved later in the message is a legitimate one-row block. */
    @Test
    fun `a single field the venue relocated is still a move`() {
        val draft =
            exp(
                FieldExpectation(58, Matcher.Exact("ok")),
                FieldExpectation(39, Matcher.Exact("2")),
                FieldExpectation(60, Matcher.Exact("T")),
            )
        // The venue now sends 58 last.
        val reply = FixMessageView.ofFields(listOf(39 to "2", 60 to "T", 58 to "ok"))

        val reordered = ScenarioReconcile.acceptNewOrder(draft, reply)
        assertTrue(reordered != null, "a relocated field is a move")
        assertTrue(ExpectationEvaluator.evaluate(reply, reordered!!).all { it.passed })
    }

    /**
     * "Assert it" must assert the field the author clicked.
     *
     * A two-party group where nothing is asserted yet. The author clicks "Assert it" on the **second** 448 —
     * the clearing firm. The old insertion point was computed from the *claimed* wire positions, so with the
     * first 448 unclaimed the engine's greedy cursor handed the new row THAT one instead. Both entries carry
     * `448=FIRMA`, so the row went green immediately while the entry the author actually clicked stayed
     * unasserted for ever, no matter how many times they clicked it. A false green and a silent coverage hole,
     * from one click, on the surface that exists to close them.
     */
    @Test
    fun `assert-it asserts the occurrence that was clicked, not an earlier one`() {
        val reply =
            FixMessageView.ofFields(
                listOf(35 to "8", 448 to "FIRMA", 452 to "1", 448 to "FIRMA", 452 to "4"),
            )
        val draft = exp(FieldExpectation(35, Matcher.Exact("8")))

        val secondPartyId = 3 // the wire index of the clearing firm's 448
        val fixed = ScenarioReconcile.addAssertion(draft, reply, secondPartyId, null)

        // The clicked field is now asserted by *some* row — and that row checks THAT field.
        val aligned = ExpectationEvaluator.align(fixed, reply.fields())
        val landed = aligned.filter { it.row != null }.map { it.wireIndex }
        assertTrue(
            secondPartyId in landed,
            "the field the author clicked must end up asserted; rows landed on $landed",
        )
        // And the expectation still passes the message it was built from.
        assertTrue(
            ExpectationEvaluator.evaluate(reply, fixed).all { it.passed },
            "a row added from the message must not stop the expectation matching that message",
        )
    }

    /**
     * The real thing, at full size: a captured party block of `448/447/452` twice, and the two venues the
     * reconcile view has to tell apart. This is the shape the live demo stages.
     */
    @Test
    fun `the full party block — entries reordered is a move, roles swapped is not`() {
        val draft =
            exp(
                FieldExpectation(453, Matcher.Exact("2")),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("4")),
            )

        // (a) The ENTRIES swap places. FIRMA still holds role 1. Shape — one click.
        val reordered =
            FixMessageView.ofFields(
                listOf(
                    453 to "2",
                    448 to "FIRMB",
                    447 to "D",
                    452 to "4",
                    448 to "FIRMA",
                    447 to "D",
                    452 to "1",
                ),
            )
        val fixed = ScenarioReconcile.acceptNewOrder(draft, reordered)
        assertTrue(fixed != null, "a genuine entry reorder must still be one click")
        assertTrue(
            ExpectationEvaluator.evaluate(reordered, fixed!!).all { it.passed },
            "and it must repair the step",
        )
        assertEquals(
            listOf("2", "FIRMB", "D", "4", "FIRMA", "D", "1"),
            fixed.fields.map { (it.matcher as Matcher.Exact).value },
            "every assertion survives — FIRMA still holds role 1, it is just listed second now",
        )

        // (b) The FIRMS swap ROLES. Nothing moved; FIRMB now holds role 1. Behaviour — never a move.
        val swapped =
            FixMessageView.ofFields(
                listOf(
                    453 to "2",
                    448 to "FIRMB",
                    447 to "D",
                    452 to "1",
                    448 to "FIRMA",
                    447 to "D",
                    452 to "4",
                ),
            )
        assertNull(
            ScenarioReconcile.acceptNewOrder(draft, swapped),
            "a role swap must never be offered as a reorder — accepting it deletes 'FIRMA holds role 1'",
        )
        assertTrue(ScenarioReconcile.movedRows(draft, swapped).isEmpty(), "and no row may be bracketed as moved")

        val behaviour = ScenarioReconcile.rows(draft, swapped, null).filter { ScenarioReconcile.isBehaviourChange(it) }
        assertEquals(2, behaviour.size, "it reads as what it is: the two PartyIDs changed")
        assertTrue(behaviour.all { it.tag == 448 })
    }

    /**
     * The reviewer's variant, which is NOT the one I found myself — and that is the point of the review.
     *
     * Here the FIRMS stay put (FIRMA first, FIRMB second) and only the ROLES swap. So the two `448` rows
     * still pass, and the failing rows are the two `452`s. A block rule that may move any contiguous sub-run
     * finds `(447=D, 452=1)` matching the OTHER entry's `(447=D, 452=1)` verbatim — because `447=D` is
     * identical in both entries, a two-row fragment can span two different parties. The `448` rows stay put,
     * the fragments cross over, and the regression is greened.
     *
     * The span rule kills it: the moved rows would be {1,2,4,5}, and row 3 — the `448=FIRMB` sitting between
     * them — does not move. An entry that really moved takes all of itself.
     */
    @Test
    fun `a fragment that spans two entries is not an entry, and may not move`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())
        // Same firms, same positions. The ROLES swapped. Pure behaviour regression.
        val reply =
            FixMessageView.ofFields(
                listOf(448 to "FIRMA", 452 to "4", 448 to "FIRMB", 452 to "1"),
            )

        assertNull(
            ScenarioReconcile.acceptNewOrder(draft, reply),
            "the roles swapped; nothing moved. Accepting this deletes 'FIRMA holds role 1'",
        )
    }

    /**
     * The same, with the 447 delimiter that makes the fragment possible — the reviewer's exact shape.
     */
    @Test
    fun `a fragment that spans two entries is not an entry, with the delimiter that enables it`() {
        val draft =
            exp(
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("4")),
            )
        // 447=D is identical in both entries, so (447=D, 452=1) matches the other party's tail verbatim.
        val reply =
            FixMessageView.ofFields(
                listOf(
                    448 to "FIRMA",
                    447 to "D",
                    452 to "4",
                    448 to "FIRMB",
                    447 to "D",
                    452 to "1",
                ),
            )

        assertNull(
            ScenarioReconcile.acceptNewOrder(draft, reply),
            "a two-row fragment spanning two parties is not a party; the roles swapped and it is a regression",
        )
        assertTrue(ScenarioReconcile.movedRows(draft, reply).isEmpty())
    }

    /**
     * A row that is ALREADY PASSING must keep the field the engine has it checking. Even under the
     * occurrence rule.
     *
     * The engine's greedy cursor and the k-th-row/k-th-occurrence rule can disagree, and where they do, the
     * occurrence rule silently re-aims. Here an earlier row (`55`) pushes the cursor past the first `452`, so
     * the engine has the `452 presence` row checking the SECOND party entry — and it passes there.
     * `placeByOccurrence` says the one `452` row is the *first* occurrence, moves it onto the first entry,
     * and `presence` passes there too. Both green, and the assertion now describes a party the author never
     * chose while the one they did choose stops being asserted. Two deciders for "which field does this row
     * assert", exactly the class of defect this model exists to remove.
     */
    @Test
    fun `an already-passing row is never re-aimed onto a different occurrence`() {
        val reply = FixMessageView.ofFields(listOf(452 to "1", 55 to "EUR/USD", 452 to "4"))
        // The 55 row pushes the cursor past the first 452, so the 452 row checks the SECOND entry, and passes.
        val draft =
            exp(
                FieldExpectation(55, Matcher.Exact("EUR/USD")),
                FieldExpectation(452, Matcher.Presence),
            )
        val before = ScenarioReconcile.rows(draft, reply, null).single { it.tag == 452 && !it.unasserted }
        assertTrue(before.passed, "precondition: it is green, checking the second entry")
        assertEquals("4", before.actual, "precondition: on the SECOND 452")

        val plan = ScenarioReconcile.acceptNewOrder(draft, reply)

        if (plan != null) {
            val after = ScenarioReconcile.rows(plan, reply, null).single { it.tag == 452 && !it.unasserted }
            assertEquals(
                "4",
                after.actual,
                "re-ordering may not move a green row onto a different party entry — it was checking the " +
                    "second, and it must still be checking the second",
            )
        }
    }
}
