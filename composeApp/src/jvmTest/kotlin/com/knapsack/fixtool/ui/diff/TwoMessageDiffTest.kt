package com.knapsack.fixtool.ui.diff

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.ReferenceMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The plain diff viewer, at the engine seam.**
 *
 * The viewer's left side is message A seeded all-exact, and the diff is the runner's own `align` over that
 * expectation and message B (Phase 7, G1). That path *is* `align(expectation, reference)`, which
 * `SemanticsContractTest` and `AlignmentModelTest` exercise — but never with an expectation seeded all-exact
 * from **one real message against a different one**, which is the case the viewer introduces. That is the blind
 * spot the model doc's §"Testing this model" names, and the shape of R2 and S7: *the fixture handed the engine
 * something the surface can never supply.* So the corpus here carries the hard cases — a **pipe inside a value**,
 * **two different firms**, a group with **≥3 entries and ≥3 tags each** (the rotation trap), **nested groups** —
 * and the fake venue's own shapes: `golden` vs `shape` (a value change **and** an added tag **and** a dropped
 * tag **and** a moved entry, in one message) and the role swap.
 *
 * The bytes are built with the six-character `` escape, never a literal SOH — a de-delimited fixture
 * parses as one field and looks exactly like an alignment defect (ground-rule trap 7).
 */
class TwoMessageDiffTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val soh = ""

    private fun wire(vararg f: Pair<Int, String>): String =
        f.joinToString("") { (tag, value) -> "$tag=$value$soh" }

    private fun side(w: String) = DiffSide(wire = w, label = "x", provenance = ReferenceMessage.Provenance.PICKED)

    private fun model(a: String, b: String, mode: MatchMode = MatchMode.OPEN) =
        DiffViewerSession(side(a), side(b), dictionary, mode).model

    private fun kindOfTag(model: com.knapsack.fixtool.ui.diff.DiffModel, tag: Int): ChunkKind? =
        model.lines.firstOrNull { it.row.tag == tag && !it.row.unasserted }?.kind

    // ---- the anti-drift pin: the viewer path IS the engine path -------------------------------------------

    /**
     * The viewer's rows are `ScenarioReconcile.rows` of the all-exact expectation against B, verbatim. If the
     * two ever diverged, the viewer would draw a diff the runner would not — the seam the whole design closes.
     */
    @Test
    fun `the viewer's rows are the engine's rows over the all-exact expectation`() {
        val a = wire(35 to "8", 150 to "2", 31 to "1.0851", 58 to "filled|in full")
        val b = wire(35 to "8", 150 to "2", 31 to "1.0849")
        val session = DiffViewerSession(side(a), side(b), dictionary, MatchMode.OPEN)
        val engine =
            ScenarioReconcile.rows(exactExpectation(side(a), MatchMode.OPEN), referenceOf(side(b)), dictionary) { null }
        assertEquals(engine, session.model.lines.map { it.row }, "the viewer re-judged something the engine did not")
    }

    // ---- A vs A is all-same, and the pipe survives both readings -----------------------------------------

    @Test
    fun `a message diffed against itself shows no difference, pipe and all`() {
        val a = wire(35 to "8", 150 to "2", 31 to "1.0851", 58 to "filled|in full")
        val model = model(a, a)
        assertTrue(model.lines.all { it.kind == ChunkKind.SAME }, "identical messages must show no difference")
        assertTrue(
            model.lines.any { it.row.tag == 58 && it.right?.value == "filled|in full" },
            "the pipe inside 58 must survive both readings — a mis-split would show a phantom diff",
        )
    }

    @Test
    fun `a pipe inside a value is compared whole, not split`() {
        val a = wire(35 to "8", 58 to "filled|in full")
        val b = wire(35 to "8", 58 to "partial")
        val model = model(a, b)
        val row = model.lines.single { it.row.tag == 58 }
        assertEquals(ChunkKind.VALUE, row.kind)
        assertEquals("filled|in full", row.row.matcher?.let { (it as com.knapsack.fixtool.model.scenario.Matcher.Exact).value })
        assertEquals("partial", row.right?.value)
    }

    // ---- the four classifications ------------------------------------------------------------------------

    @Test
    fun `a changed value is a value chunk`() {
        val model = model(wire(35 to "8", 31 to "1.0851"), wire(35 to "8", 31 to "1.0849"))
        assertEquals(ChunkKind.VALUE, kindOfTag(model, 31))
        assertEquals("1.0849", model.lines.single { it.row.tag == 31 }.right?.value)
    }

    @Test
    fun `a tag only in A is left-only, and one only in B is right-only`() {
        val model = model(wire(35 to "8", 2376 to "Y"), wire(35 to "8", 58 to "manual fill"))
        assertEquals(ChunkKind.LEFT_ONLY, kindOfTag(model, 2376), "a tag only in A faces a gap on the right (+A)")
        assertTrue(model.lines.single { it.row.tag == 2376 }.rightIsGap)
        val onlyB = model.lines.single { it.kind == ChunkKind.RIGHT_ONLY }
        assertEquals(58, onlyB.right?.tag, "a tag only in B faces a gap on the left (+B)")
        assertTrue(onlyB.leftIsGap)
    }

    /** A `+B` difference must show in OPEN too — the viewer is symmetric; the mode changes classification, not visibility. */
    @Test
    fun `a right-only tag shows in both modes`() {
        for (mode in MatchMode.entries) {
            val model = model(wire(35 to "8"), wire(35 to "8", 58 to "x"), mode)
            assertTrue(
                model.lines.any { it.kind == ChunkKind.RIGHT_ONLY && it.right?.tag == 58 },
                "the viewer shows every difference in $mode; a +B must not be hidden",
            )
        }
    }

    // ---- two firms, moved entries, and the role swap that is NOT a move ----------------------------------

    @Test
    fun `two party entries that traded places are a move, not six value mismatches`() {
        val a = wire(453 to "2", 448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4")
        val b = wire(453 to "2", 448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1")
        val model = model(a, b, MatchMode.STRICT)
        assertTrue(model.chunks.any { it.kind == ChunkKind.MOVED }, "the entry swap must be detected as a move")
    }

    @Test
    fun `the role swap is not a move, it is two value changes in place`() {
        // Same tag sequence, the roles exchanged where they sit: FIRMA 1->4, FIRMB 4->1. A move would be a lie.
        val a = wire(453 to "2", 448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4")
        val b = wire(453 to "2", 448 to "FIRMA", 447 to "D", 452 to "4", 448 to "FIRMB", 447 to "D", 452 to "1")
        val model = model(a, b, MatchMode.STRICT)
        assertTrue(model.chunks.none { it.kind == ChunkKind.MOVED }, "a role swap is not a move")
        assertEquals(2, model.lines.count { it.row.tag == 452 && it.kind == ChunkKind.VALUE }, "both 452 rows differ")
    }

    // ---- golden vs shape: a value change AND an added tag AND a dropped tag AND a moved entry ------------

    @Test
    fun `the golden-vs-shape shape shows all four differences at once`() {
        val golden =
            wire(
                35 to "8", 151 to "0", 453 to "2",
                448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4",
                58 to "filled|in full",
            )
        val shape =
            wire(
                35 to "8", 151 to "500000", 2376 to "Y", 453 to "2",
                448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1",
            )
        val model = model(golden, shape, MatchMode.STRICT)
        assertEquals(ChunkKind.VALUE, kindOfTag(model, 151), "151 regressed 0 -> 500000")
        assertTrue(model.lines.any { it.kind == ChunkKind.RIGHT_ONLY && it.right?.tag == 2376 }, "2376 added (+B)")
        assertEquals(ChunkKind.LEFT_ONLY, kindOfTag(model, 58), "58 dropped (+A)")
        assertTrue(model.chunks.any { it.kind == ChunkKind.MOVED }, "the party entries moved")
    }

    // ---- ≥3 entries, ≥3 tags: the rotation trap must not fabricate a move --------------------------------

    @Test
    fun `a three-party group with one value changed shows one value, no spurious move`() {
        fun parties(vararg roles: String) =
            listOf(453 to roles.size.toString()) +
                roles.flatMapIndexed { i, r ->
                    listOf(448 to "FIRM${'A' + i}", 447 to "D", 452 to r)
                }
        val a = wire(*(listOf(35 to "8") + parties("1", "4", "7")).toTypedArray())
        val b = wire(*(listOf(35 to "8") + parties("1", "4", "8")).toTypedArray()) // third party's role 7 -> 8
        val model = model(a, b, MatchMode.STRICT)
        assertTrue(model.chunks.none { it.kind == ChunkKind.MOVED }, "one changed value is not a rotation")
        assertEquals(1, model.lines.count { it.kind == ChunkKind.VALUE }, "exactly the one changed 452 differs")
    }

    // ---- nested groups: NoPartySubIDs inside NoPartyIDs --------------------------------------------------

    @Test
    fun `a nested subgroup value change is one value chunk`() {
        // 453 -> 448/447/452, then 802 -> 523/803 (NoPartySubIDs), one sub value changed.
        val a = wire(35 to "8", 453 to "1", 448 to "FIRMA", 447 to "D", 452 to "1", 802 to "1", 523 to "DESK", 803 to "3")
        val b = wire(35 to "8", 453 to "1", 448 to "FIRMA", 447 to "D", 452 to "1", 802 to "1", 523 to "RISK", 803 to "3")
        val model = model(a, b, MatchMode.STRICT)
        assertEquals(ChunkKind.VALUE, kindOfTag(model, 523), "the nested 523 changed DESK -> RISK")
        assertEquals(1, model.lines.count { it.kind == ChunkKind.VALUE }, "nothing else differs")
    }

    // ---- the envelope is moment/environment noise, and it is dropped from both sides ---------------------

    @Test
    fun `envelope differences are not shown`() {
        val a = wire(8 to "FIX.4.4", 9 to "100", 34 to "5", 52 to "20260714-09:35:44", 35 to "8", 150 to "2", 10 to "001")
        val b = wire(8 to "FIX.4.4", 9 to "212", 34 to "9", 52 to "20260714-10:00:00", 35 to "8", 150 to "2", 10 to "222")
        val model = model(a, b)
        assertTrue(model.lines.all { it.kind == ChunkKind.SAME }, "the envelope differs by construction; it must not drown the diff")
        assertTrue(
            model.lines.none { it.row.tag in setOf(8, 9, 34, 52, 10) },
            "the envelope is dropped from both sides — not one side, or it resurfaces as +B",
        )
    }

    // ---- swap sides is symmetric, and setMode re-judges without a stale memo ------------------------------

    @Test
    fun `swap sides turns a plus-A into a plus-B`() {
        val session = DiffViewerSession(side(wire(35 to "8", 2376 to "Y")), side(wire(35 to "8")), dictionary, MatchMode.OPEN)
        assertEquals(ChunkKind.LEFT_ONLY, kindOfTag(session.model, 2376), "2376 starts only in A")
        session.swapSides()
        assertTrue(
            session.model.lines.any { it.kind == ChunkKind.RIGHT_ONLY && it.right?.tag == 2376 },
            "after the swap, 2376 is only in B",
        )
    }

    @Test
    fun `the model memoizes across reads and rebuilds on a mode change`() {
        val session = DiffViewerSession(side(wire(35 to "8", 58 to "x")), side(wire(35 to "8")), dictionary, MatchMode.OPEN)
        session.model
        session.model
        assertEquals(1, session.rebuilds, "an unchanged viewer must not re-run the aligner on every read")
        session.selectMode(MatchMode.STRICT)
        session.model
        assertEquals(2, session.rebuilds, "a mode change must re-judge")
    }
}
