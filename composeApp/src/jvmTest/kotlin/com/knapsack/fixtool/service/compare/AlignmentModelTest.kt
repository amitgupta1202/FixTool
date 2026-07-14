package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [AlignmentModel] is a *shape*, not a second opinion.
 *
 * Everything it says has to come off the engine unchanged, because the moment the diff surface can draw a
 * diff the engine did not produce, it can offer a fix for a row the engine was not asserting. So the first
 * test here is the one that matters: **the wrapper reproduces `ScenarioReconcile.rows` exactly**. The rest
 * pin the grouping the surface renders — and the one genuinely new fact, which is where a moved entry
 * lands on the other side.
 */
class AlignmentModelTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val at = Instant.parse("2026-07-14T09:35:44Z")

    private fun exp(mode: MatchMode, vararg f: FieldExpectation) = Expectation(f.toList(), mode = mode)

    private fun party(id: String, role: String) =
        listOf(
            FieldExpectation(448, Matcher.Exact(id)),
            FieldExpectation(447, Matcher.Exact("D")),
            FieldExpectation(452, Matcher.Exact(role)),
        )

    /** The fixture corpus: every failure kind this model can produce, one shape each. */
    private fun corpus(): List<Pair<Expectation, List<Pair<Int, String>>>> =
        listOf(
            // clean
            exp(MatchMode.STRICT, FieldExpectation(35, Matcher.Exact("8"))) to listOf(35 to "8"),
            // value mismatch, kind-preserving matcher
            exp(MatchMode.STRICT, FieldExpectation(151, Matcher.Numeric(0.0, 0.0))) to listOf(151 to "500000"),
            // missing tag
            exp(MatchMode.STRICT, FieldExpectation(58, Matcher.Exact("filled"))) to listOf(35 to "8"),
            // the reply carries a tag no row mentions — a failure in STRICT, the point of OPEN
            exp(MatchMode.STRICT, FieldExpectation(35, Matcher.Exact("8"))) to listOf(35 to "8", 2376 to "Y"),
            exp(MatchMode.OPEN, FieldExpectation(35, Matcher.Exact("8"))) to listOf(35 to "8", 2376 to "Y"),
            // two party entries that traded places
            exp(MatchMode.STRICT, *(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray()) to
                listOf(448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1"),
            // the role swap, which is NOT a move
            exp(MatchMode.STRICT, *(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray()) to
                listOf(448 to "FIRMA", 447 to "D", 452 to "4", 448 to "FIRMB", 447 to "D", 452 to "1"),
            // a row nothing here can judge, and one judged against the clock
            exp(
                MatchMode.STRICT,
                FieldExpectation(11, Matcher.Reference("\${id0}")),
                FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
            ) to listOf(11 to "ORD-7f3a", 60 to "20260714-09:35:44"),
            // an absent row the venue defied
            exp(MatchMode.STRICT, FieldExpectation(58, Matcher.Absent)) to listOf(58 to "rejected"),
        )

    /**
     * THE ANTI-DRIFT PIN. The alignment model is a regrouping of the engine's rows and nothing else — same
     * rows, same order, same verdicts. A wrapper that quietly re-judged anything would be a second decider,
     * and the whole point of routing the surface through the engine is that there is only one.
     */
    @Test
    fun `the model's rows are the engine's rows, verbatim`() {
        for ((expectation, wire) in corpus()) {
            val message = wireView(wire)
            val semantics = SemanticsRegistry.forMode(expectation.mode)

            val model = semantics.align(expectation, message, dictionary, at = at)
            val engine = ScenarioReconcile.rows(expectation, message, dictionary) { at }

            assertEquals(engine, model.rows, "the wrapper re-judged something: $expectation vs $wire")
        }
    }

    /** Every row of the diff belongs to exactly one chunk, and reading order survives the grouping. */
    @Test
    fun `chunks partition the diff, in order`() {
        for ((expectation, wire) in corpus()) {
            val model = SemanticsRegistry.forMode(expectation.mode).align(expectation, wireView(wire), dictionary, at = at)

            val rows = ScenarioReconcile.rows(expectation, wireView(wire), dictionary) { at }
            assertEquals(rows, model.chunks.flatMap { it.rows }, "chunks must cover the diff exactly once, in order")
            assertEquals(model.chunks.indices.toList(), model.chunks.map { it.id }, "chunk ids address chunks")
        }
    }

    // ----- the kinds ------------------------------------------------------------------------------------

    @Test
    fun `a value mismatch is one chunk, with both sides on it`() {
        val expectation = exp(MatchMode.STRICT, FieldExpectation(151, Matcher.Numeric(0.0, 0.0)))

        val model = SemanticsRegistry.forMode(MatchMode.STRICT).align(expectation, wireView(151 to "500000"), dictionary)

        val chunk = model.chunks.single()
        assertEquals(ChunkKind.VALUE, chunk.kind)
        assertEquals(1, chunk.left.size)
        assertEquals("500000", chunk.right.single().value)
        assertEquals("LeavesQty", chunk.right.single().name, "the right column names the field")
    }

    @Test
    fun `a tag the venue stopped sending has no right side at all`() {
        val expectation = exp(MatchMode.STRICT, FieldExpectation(58, Matcher.Exact("filled")))

        val model = SemanticsRegistry.forMode(MatchMode.STRICT).align(expectation, wireView(35 to "8"), dictionary)

        val missing = model.chunks.single { it.kind == ChunkKind.LEFT_ONLY }
        assertEquals(TagStatus.MISSING, missing.rows.single().status)
        assertTrue(missing.right.isEmpty(), "the venue sent nothing here — a gap is the honest rendering")
    }

    @Test
    fun `a tag no row mentions is right-only, and carries the wire index the gutter inserts against`() {
        val expectation = exp(MatchMode.STRICT, FieldExpectation(35, Matcher.Exact("8")))

        val model = SemanticsRegistry.forMode(MatchMode.STRICT).align(expectation, wireView(35 to "8", 2376 to "Y"), dictionary)

        val added = model.chunks.single { it.kind == ChunkKind.RIGHT_ONLY }
        assertTrue(added.left.isEmpty(), "the expectation says nothing here")
        assertEquals(1, added.right.single().wireIndex, "assert-it inserts against this field, not a guess at it")
        assertEquals(TagStatus.UNEXPECTED, added.rows.single().status)
    }

    /**
     * A row that cannot be judged offline is **paired**, not broken. Classifying it by its status would
     * make it a value mismatch — red, with an "Accept actual" under it that pins the assertion to this
     * run's id and deletes the cross-step binding the row exists to express.
     */
    @Test
    fun `an unjudgeable reference row is a pair, not a diff`() {
        val expectation = exp(MatchMode.STRICT, FieldExpectation(11, Matcher.Reference("\${id0}")))

        val model = SemanticsRegistry.forMode(MatchMode.STRICT).align(expectation, wireView(11 to "ORD-7f3a"), dictionary)

        val chunk = model.chunks.single()
        assertEquals(ChunkKind.SAME, chunk.kind, "there is no difference here to offer a fix for")
        assertTrue(chunk.rows.single().unknown, "and the row says it is unjudged, which is what renders amber")
    }

    /** Temporals are judged at the message's own instant. Two minutes of the engineer's time is not a regression. */
    @Test
    fun `a temporal row is judged at the reference's instant, not the reader's clock`() {
        val expectation = exp(MatchMode.STRICT, FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)))
        val message = wireView(60 to "20260714-09:35:44")

        val atArrival = SemanticsRegistry.forMode(MatchMode.STRICT).align(expectation, message, dictionary, at = at)
        assertEquals(ChunkKind.SAME, atArrival.chunks.single().kind)

        val anHourLater = at.plusSeconds(3600)
        val stale = SemanticsRegistry.forMode(MatchMode.STRICT).align(expectation, message, dictionary, at = anHourLater)
        assertEquals(ChunkKind.VALUE, stale.chunks.single().kind, "judged against the wrong clock, it is a false red")
    }

    // ----- moves ----------------------------------------------------------------------------------------

    /**
     * The crossing connector's data. A moved row pairs with nothing, so its own alignment has no wire
     * position — the right side of a MOVED chunk exists only because the engine's placement is published.
     */
    @Test
    fun `two entries that traded places point at each other, and know where they landed`() {
        val expectation = exp(MatchMode.STRICT, *(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())
        val message = wireView(448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1")

        val model = SemanticsRegistry.forMode(MatchMode.STRICT).align(expectation, message, dictionary, at = at)

        val moved = model.chunks.filter { it.kind == ChunkKind.MOVED }
        assertEquals(2, moved.size, "two entries moved, and each is one chunk")
        // FIRMA's rows are listed first and land second; FIRMB's are listed second and land first.
        assertEquals("FIRMA", (moved[0].left.first().matcher as Matcher.Exact).value)
        assertEquals(listOf(3, 4, 5), moved[0].right.map { it.wireIndex }, "FIRMA is now the second party on the wire")
        assertEquals(listOf(0, 1, 2), moved[1].right.map { it.wireIndex })
        // ...and each names the other, which is what the surface draws the crossing between.
        assertEquals(moved[1].id, moved[0].moveLink)
        assertEquals(moved[0].id, moved[1].moveLink)
    }

    /**
     * The role swap. Same rows red, and it is **not** a move: the entries did not go anywhere, the values
     * in them changed. A MOVED chunk here would offer "Accept new order" and delete "FIRMA holds role 1".
     */
    @Test
    fun `two firms swapping roles is not a move`() {
        val expectation = exp(MatchMode.STRICT, *(party("FIRMA", "1") + party("FIRMB", "4")).toTypedArray())
        val message = wireView(448 to "FIRMA", 447 to "D", 452 to "4", 448 to "FIRMB", 447 to "D", 452 to "1")

        val model = SemanticsRegistry.forMode(MatchMode.STRICT).align(expectation, message, dictionary, at = at)

        assertTrue(model.chunks.none { it.kind == ChunkKind.MOVED }, "the entries did not move; the values changed")
        assertTrue(model.chunks.any { it.kind == ChunkKind.VALUE })
    }

    // ----- the registry ---------------------------------------------------------------------------------

    @Test
    fun `the mode a scenario stores resolves to the semantics that judges it`() {
        assertEquals("strict", SemanticsRegistry.forMode(MatchMode.STRICT).id)
        assertEquals("open", SemanticsRegistry.forMode(MatchMode.OPEN).id)
        assertEquals(2, SemanticsRegistry.all().size)
        assertNotNull(SemanticsRegistry.byId("strict"))
        assertNull(SemanticsRegistry.byId("gumtree"), "a semantics that does not exist must not resolve to one that does")
    }

    /**
     * The seam's other half: a semantics re-modes the expectation it is given, so the editor can *preview*
     * a failure under a comparison the scenario was not authored under without rewriting the scenario.
     */
    @Test
    fun `a semantics judges by its own rule, not by the mode the expectation was stored with`() {
        val open = exp(MatchMode.OPEN, FieldExpectation(35, Matcher.Exact("8")))
        val message = wireView(35 to "8", 2376 to "Y")

        assertTrue(OpenSemantics.align(open, message, dictionary).rows.all { it.passed })
        assertTrue(
            StrictSemantics.align(open, message, dictionary).rows.any { !it.passed },
            "previewed under STRICT, the unmentioned tag is a failure — and the stored mode is untouched",
        )
        assertEquals(MatchMode.OPEN, open.mode)
    }
}
