package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manual `move entry ↑ ↓` on a bracketed block — the override for when the diff's own alignment is not
 * the one the author knows to be true.
 *
 * **Arrows on the block, never on the row.** A per-row arrow does two things this model exists to make
 * impossible: it lets the author move the second `452` above the first, which silently swaps which
 * occurrence each row checks — the row still reads `452 exact 4` while it has quietly stopped meaning the
 * clearing firm's role and started meaning the executing firm's — and it lets them build an order no real
 * message has (`448, 447, 448, 452, 452`).
 *
 * [ScenarioReconcile.moveBlock] can do neither, and these tests are what hold that shut: it swaps the block
 * with the adjacent run carrying the **same tags in the same order**, so the expectation's tag sequence comes
 * out identical and every row keeps the neighbours that give it its meaning.
 */
class ReconcileMoveBlockTest {
    private fun exp(vararg f: FieldExpectation) = Expectation(f.toList())

    /** A three-tag party entry, as the seeder produces one. */
    private fun party(id: String, role: String) =
        listOf(
            FieldExpectation(448, Matcher.Exact(id)),
            FieldExpectation(447, Matcher.Exact("D")),
            FieldExpectation(452, Matcher.Exact(role)),
        )

    private fun Expectation.shape() = fields.map { it.tag to (it.matcher as Matcher.Exact).value }

    private fun Expectation.tags() = fields.map { it.tag }

    // ----- what it does ------------------------------------------------------------------------------

    @Test
    fun `moving a party entry down swaps it with its sibling, whole`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())

        val moved = ScenarioReconcile.moveBlock(draft, 0..2, up = false)

        assertNotNull(moved)
        assertEquals(
            listOf(448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1"),
            moved.shape(),
            "the whole entry travels: FIRMA is still the firm that holds role 1",
        )
    }

    @Test
    fun `moving the second entry up is the same swap from the other side`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())

        val up = ScenarioReconcile.moveBlock(draft, 3..5, up = true)
        val down = ScenarioReconcile.moveBlock(draft, 0..2, up = false)

        assertEquals(down?.shape(), up?.shape(), "one swap, reachable from either entry")
    }

    /**
     * THE INVARIANT. Swapping two entries cannot change the expectation's tag sequence — so the order
     * `448, 447, 448, 452, 452`, which a per-row arrow can build and no venue can send, is not reachable.
     *
     * Note the span it sweeps: **every** window of the expectation, not merely the entry-aligned ones. The
     * first version of this test only ever moved 0..2, 3..5 and 6..8 — the spans that are already entries —
     * and it therefore certified an invariant the code did preserve while missing entirely the one it broke.
     */
    @Test
    fun `no window of any size can be moved into an order the expectation did not have`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4") + party("FIRMC", "7")).toTypedArray())

        for (start in draft.fields.indices) {
            for (end in start until draft.fields.size) {
                for (up in listOf(true, false)) {
                    val moved = ScenarioReconcile.moveBlock(draft, start..end, up) ?: continue
                    assertEquals(draft.tags(), moved.tags(), "tag order must survive moving $start..$end (up=$up)")
                    assertEquals(
                        draft.fields.toSet(),
                        moved.fields.toSet(),
                        "and the assertions themselves are only re-ordered, never rewritten",
                    )
                    // ...and every party must still be one party: each entry's firm still sits with its role.
                    val entriesAfter = moved.fields.chunked(3).map { e -> e.map { (it.matcher as Matcher.Exact).value } }
                    assertEquals(
                        setOf(listOf("FIRMA", "D", "1"), listOf("FIRMB", "D", "4"), listOf("FIRMC", "D", "7")),
                        entriesAfter.toSet(),
                        "moving $start..$end (up=$up) tore a party apart: $entriesAfter",
                    )
                }
            }
        }
    }

    /**
     * THE FALSE GREEN THE REVIEW FOUND, pinned. In a group of three parties the window `447, 452, 448` — rows
     * 1..3 — has a neighbour carrying exactly those tags in exactly that order (rows 4..6). Tag-sequence
     * equality alone therefore *accepts* the swap, and it welds FIRMA's PartyID to FIRMC's role while every
     * row still reads `452 exact 4` on screen. The expectation's tag order even survives, which is why an
     * invariant test that only checks tag order cannot see it.
     *
     * A window is not an entry. Only an entry may move.
     */
    @Test
    fun `a phase-shifted window with an identically tagged neighbour is refused`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4") + party("FIRMC", "7")).toTypedArray())

        // tags 1..3 = (447, 452, 448); tags 4..6 = (447, 452, 448). Identical — and not an entry.
        assertEquals(listOf(447, 452, 448), (1..3).map { draft.fields[it].tag })
        assertEquals(listOf(447, 452, 448), (4..6).map { draft.fields[it].tag })

        assertNull(
            ScenarioReconcile.moveBlock(draft, 1..3, up = false),
            "this swap would assert FIRMA alongside FIRMC's role, with every row still reading the same",
        )
        assertNull(ScenarioReconcile.moveBlock(draft, 4..6, up = true))
        assertNull(ScenarioReconcile.moveBlock(draft, 2..4, up = false), "and any other rotation of the entry")
    }

    /** The entries are where the expectation says they are, and nowhere else. */
    @Test
    fun `entries are the repeating runs of the expectation, anchored at their start`() {
        val draft =
            exp(
                FieldExpectation(35, Matcher.Exact("8")),
                *(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray(),
                FieldExpectation(58, Matcher.Exact("filled")),
            )

        assertEquals(listOf(1..3, 4..6), ScenarioReconcile.entries(draft), "35 and 58 are not entries of anything")
    }

    /** Each entry keeps its own rows adjacent: the firm and the role it holds stay in the same entry. */
    @Test
    fun `a moved entry keeps its rows together, so no row changes what it describes`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())

        val moved = ScenarioReconcile.moveBlock(draft, 0..2, up = false)!!

        val entries = moved.fields.chunked(3).map { entry -> entry.map { (it.matcher as Matcher.Exact).value } }
        assertEquals(
            listOf(listOf("FIRMB", "D", "4"), listOf("FIRMA", "D", "1")),
            entries,
            "FIRMA is still asserted alongside role 1 — the pairing is what must not come apart",
        )
    }

    // ----- what it refuses ---------------------------------------------------------------------------

    /**
     * THE ONE THAT MUST NOT BE BROKEN. A single row of a repeated tag is not an entry — moving it past its
     * sibling is exactly the per-row arrow, and it re-aims both rows while both still read the same on screen.
     */
    @Test
    fun `a lone row of a repeated tag cannot be moved - that is a per-row arrow in a block's clothes`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())

        // The second 452 (index 5) alone, and the first (index 2) alone.
        assertNull(ScenarioReconcile.moveBlock(draft, 5..5, up = true), "moving one 452 past the other re-aims both")
        assertNull(ScenarioReconcile.moveBlock(draft, 2..2, up = false))
        assertNull(ScenarioReconcile.moveBlock(draft, 0..0, up = false), "and the same for a lone 448")
    }

    /** A lone row of a tag that occurs once has no sibling to be confused with — but also nothing to swap with. */
    @Test
    fun `a lone row of a unique tag has no identically shaped sibling to swap with`() {
        val draft =
            exp(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(39, Matcher.Exact("2")),
            )

        assertNull(ScenarioReconcile.moveBlock(draft, 1..1, up = true), "35 and 150 are not the same shape")
        assertNull(ScenarioReconcile.moveBlock(draft, 1..1, up = false))
    }

    /**
     * A *fragment* of an entry cannot be moved, and it is the tag-sequence rule that stops it — not a special
     * case. `447, 452` sits above `448, 447`, which is a different shape, so there is nothing to swap with.
     * Were it allowed, the source's PartyIDSource and PartyRole would cross to the other party while its
     * PartyID stayed behind: "FIRMA, D, role 4" — the exact regression the solid-span rule exists to refuse.
     */
    @Test
    fun `a fragment of an entry has no identically shaped neighbour, so it cannot be torn off`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())

        // Rows 1..2 are (447, 452); the run below is (448, 447) and the run above is out of bounds.
        assertNull(ScenarioReconcile.moveBlock(draft, 1..2, up = false), "447,452 cannot swap with 448,447")
        assertNull(ScenarioReconcile.moveBlock(draft, 1..2, up = true))
        // Rows 0..1 are (448, 447); the run below is (452, 448).
        assertNull(ScenarioReconcile.moveBlock(draft, 0..1, up = false), "448,447 cannot swap with 452,448")
    }

    @Test
    fun `an entry at the end of the group has nowhere further to go`() {
        val draft = exp(*(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())

        assertNull(ScenarioReconcile.moveBlock(draft, 0..2, up = true), "nothing above the first entry")
        assertNull(ScenarioReconcile.moveBlock(draft, 3..5, up = false), "nothing below the last")
        assertTrue(ScenarioReconcile.canMoveBlock(draft, 0..2, up = false))
        assertTrue(ScenarioReconcile.canMoveBlock(draft, 3..5, up = true))
    }

    /** An entry may not swap with the message body around it: `35, 150` is not another party. */
    @Test
    fun `an entry will not swap with unrelated fields that merely happen to be adjacent`() {
        val draft =
            exp(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(39, Matcher.Exact("2")),
                *party("FIRMA", "1").toTypedArray(),
            )

        assertNull(
            ScenarioReconcile.moveBlock(draft, 3..5, up = true),
            "448,447,452 must not swap with 35,150,39 — different tags entirely",
        )
    }

    /**
     * The pathological shape the guard is really for: a group whose entries are a single repeated tag. There
     * is no "entry" to move here that is not one row, and one row of a repeated tag is forbidden — so the
     * arrows do nothing at all, which is the correct answer.
     */
    @Test
    fun `a group of bare repeated tags offers no move at all`() {
        val draft =
            exp(
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
            )

        assertNull(ScenarioReconcile.moveBlock(draft, 0..0, up = false))
        assertNull(ScenarioReconcile.moveBlock(draft, 1..1, up = true))
    }
}
