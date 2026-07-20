package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.ScenarioReconcile.FixTolerance
import com.knapsack.fixtool.service.compare.ReferenceMessage
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The fix plan: widen the bands until the reply fits — and touch nothing else.**
 *
 * The property that carries the whole feature is the *guard list*, not the arithmetic: a bulk loosen that
 * reaches one row too many is a regression-swallowing machine wearing a convenience. So most of this file
 * pins what the plan must NOT propose — the enum-coded int, the moved entry, the reference, the shape
 * change — and the rest pins that what it does propose actually repairs, under the evaluator's own
 * arithmetic, judged at the reference's own moment.
 */
class FixPlanTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val arrival = Instant.parse("2025-01-01T00:00:00Z")

    private fun ref(view: MessageView) =
        ReferenceMessage.live(view, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival)

    private fun open(vararg fields: FieldExpectation) =
        Expectation(fields.toList(), messageType = "8", mode = MatchMode.OPEN)

    private fun plan(
        draft: Expectation,
        reference: ReferenceMessage,
        tolerance: FixTolerance = FixTolerance.CoverBoth,
    ) = ScenarioReconcile.fixPlan(draft, reference, dictionary, tolerance)

    private fun applied(draft: Expectation, fixes: List<ScenarioReconcile.PlannedFix>) =
        fixes.fold(draft) { d, f -> ScenarioReconcile.loosen(d, f.index, f.proposed) }

    // ----- what the plan proposes, and that it really repairs --------------------------------------------

    /** The daily case: a fill price a pip away on re-run. The band covers it, and the row is green after. */
    @Test
    fun `a drifted price gets the smallest covering band, and applying it repairs the row`() {
        val draft = open(
            FieldExpectation(35, Matcher.Exact("8")),
            FieldExpectation(31, Matcher.Numeric(1.09087)),
        )
        val reference = ref(wireView(35 to "8", 31 to "1.09152"))

        val fixes = plan(draft, reference)

        assertEquals(listOf(31), fixes.map { it.tag })
        assertTrue(fixes.single().repairs)
        val proposed = fixes.single().proposed as Matcher.Numeric
        assertEquals(1.09087, proposed.expected, "the band widens around the author's baseline — it never rebases")

        val after = ScenarioReconcile.rows(applied(draft, fixes), reference, dictionary)
        assertTrue(after.all { it.passed }, "a plan that leaves a row red is a broken button in bulk")
    }

    /**
     * The ulp trap, through the plan: `2.0` vs `1.9` differ by `0.1` in decimal, and `2.0d - 1.9d` exceeds
     * `0.1d` by ~6 ulps. The plan shares [ScenarioReconcile.coveringBand] with the per-row `±` precisely so
     * this case cannot regress in one of them while the other's test stays green.
     */
    @Test
    fun `the plan's band passes the evaluator's own double arithmetic`() {
        val draft = open(FieldExpectation(44, Matcher.Exact("2.0")))
        val reference = ref(wireView(44 to "1.9"))

        val fixes = plan(draft, reference)

        val after = ScenarioReconcile.rows(applied(draft, fixes), reference, dictionary)
        assertTrue(after.single { it.tag == 44 }.passed, "loosened to cover both sides — not red by one ulp")
    }

    /** `1000000` against `1000000.0` is the same number in different clothes: numeric ±0, not a band. */
    @Test
    fun `an exact row failing over formatting alone becomes numeric zero, not a band`() {
        val draft = open(FieldExpectation(38, Matcher.Exact("1000000")))
        val reference = ref(wireView(38 to "1000000.0"))

        val fixes = plan(draft, reference)

        assertEquals(Matcher.Numeric(1000000.0, 0.0), fixes.single().proposed)
        assertTrue(fixes.single().repairs)
        assertTrue("formatted differently" in fixes.single().reason, fixes.single().reason)
    }

    /** An author who declared a row numeric has already decided its kind — the plan may widen it anywhere. */
    @Test
    fun `a row the author made numeric is widenable even off the numeric families`() {
        // 58 is Text — a STRING field the seeder would never seed numeric. The author did, deliberately.
        val draft = open(FieldExpectation(58, Matcher.Numeric(5.0)))
        val reference = ref(wireView(58 to "7"))

        val fixes = plan(draft, reference)

        assertEquals(listOf(58), fixes.map { it.tag })
        assertTrue(fixes.single().repairs)
    }

    /** The knob: one tolerance for every numeric row — and the row it cannot reach is marked, not hidden. */
    @Test
    fun `uniform tolerance marks the rows it does not reach as staying red`() {
        val draft = open(
            FieldExpectation(31, Matcher.Numeric(1.0)),
            FieldExpectation(44, Matcher.Numeric(2.0)),
        )
        val reference = ref(wireView(31 to "1.001", 44 to "7.0"))

        val fixes = plan(draft, reference, FixTolerance.Uniform(0.01))

        assertEquals(2, fixes.size, "a policy that quietly skips what it cannot fix lies about what apply does")
        val price = fixes.single { it.tag == 31 }
        val stuck = fixes.single { it.tag == 44 }
        assertTrue(price.repairs)
        assertEquals(Matcher.Numeric(1.0, 0.01), price.proposed)
        assertFalse(stuck.repairs)
        assertTrue("stays red" in stuck.reason, stuck.reason)
    }

    /** A `~now` outside its window widens to the next friendly rung that covers the observed skew. */
    @Test
    fun `a drifted temporal widens up the ladder, judged at the reference's own moment`() {
        val draft = open(FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)))
        // Five minutes after the arrival instant the row is judged at: outside ±60s, inside ±300s.
        val reference = ref(wireView(60 to "20250101-00:05:00"))

        val fixes = plan(draft, reference)

        assertEquals(Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 300), fixes.single().proposed)
        val after = ScenarioReconcile.rows(applied(draft, fixes), reference, dictionary)
        assertTrue(after.single { it.tag == 60 }.passed)
    }

    /** A timestamp a day from its anchor is not clock drift, and widening over it would assert nothing. */
    @Test
    fun `a temporal beyond the ladder gets no proposal at all`() {
        val draft = open(FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)))
        val reference = ref(wireView(60 to "20250103-00:00:00")) // two days out

        assertTrue(plan(draft, reference).isEmpty())
    }

    // ----- what the plan must never touch -----------------------------------------------------------------

    /**
     * **The guard the whole feature stands on.** `PartyRole(452)` and `OrdStatus(39)` parse as numbers, and
     * `4 ± 3` over a role is a matcher that accepts seven different meanings while reading like a tolerance.
     * The plan asks [ExpectationSeeder.numericFamily] — the same decider that seeded the matcher kind — so
     * it cannot call numeric what the seed called a code. What an enum row gets instead is `∈` — the
     * failing value joining the admitted set by name (see [RepairPlanTest]) — which is the guard's other
     * half: never a band over a code, and never a code left with no honest repair at all.
     */
    @Test
    fun `enum-coded fields never get a numeric band, however numeric their values look`() {
        val draft = open(
            FieldExpectation(452, Matcher.Exact("1")),
            FieldExpectation(39, Matcher.Exact("2")),
            FieldExpectation(151, Matcher.Numeric(0.0)),
        )
        // The role changed, the status changed, the quantity drifted. Only one of those is drift.
        val reference = ref(wireView(452 to "4", 39 to "8", 151 to "500000"))

        val fixes = plan(draft, reference)

        assertEquals(
            listOf(ScenarioReconcile.FixClass.NUMERIC),
            fixes.filter { it.proposed is Matcher.Numeric }.map { it.klass },
            "one band, on the quantity — 452 failing as a different role is a behaviour change wearing digits",
        )
        assertEquals(151, fixes.single { it.klass == ScenarioReconcile.FixClass.NUMERIC }.tag)
        assertTrue(
            fixes.filter { it.tag in setOf(452, 39) }.all { it.klass == ScenarioReconcile.FixClass.ONE_OF },
            "an enum row's only repair is membership, named — never a tolerance",
        )
    }

    /** Shape is not drift: a missing tag, an extra tag and an unjudgeable reference get no proposal. */
    @Test
    fun `missing tags, extras and reference rows are outside the plan`() {
        val draft = open(
            FieldExpectation(11, Matcher.Reference("\${id0}")),
            FieldExpectation(44, Matcher.Numeric(2.0)),
            FieldExpectation(58, Matcher.Exact("filled")),
        )
        // 58 vanished, 2376 appeared, 11 cannot be judged here — and 44 genuinely drifted.
        val reference = ref(wireView(11 to "ORD-1", 44 to "2.5", 2376 to "Y"))

        val fixes = plan(draft, reference)

        assertEquals(listOf(44), fixes.map { it.tag })
    }

    /**
     * A row the engine proved moved is repaired by Accept-new-order as a unit — never per-row (D1). Its
     * status is VALUE, which is exactly why the plan must ask the reorder rather than the status.
     */
    @Test
    fun `rows inside a proven move are never proposed`() {
        val draft = open(
            FieldExpectation(453, Matcher.Exact("2")),
            FieldExpectation(448, Matcher.Exact("FIRMA")),
            FieldExpectation(447, Matcher.Exact("D")),
            FieldExpectation(452, Matcher.Exact("1")),
            FieldExpectation(448, Matcher.Exact("FIRMB")),
            FieldExpectation(447, Matcher.Exact("D")),
            FieldExpectation(452, Matcher.Exact("4")),
        )
        val swapped = ref(
            wireView(
                453 to "2",
                448 to "FIRMB", 447 to "D", 452 to "4",
                448 to "FIRMA", 447 to "D", 452 to "1",
            ),
        )

        assertTrue(
            plan(draft, swapped).isEmpty(),
            "the entries swapped places; a per-row widen here would paper over the very thing the move explains",
        )
    }

    /** A temporal against a reference with no moment of its own is unjudged — and unjudged is untouchable. */
    @Test
    fun `an unanchored temporal is not proposed, because it was never judged`() {
        val draft = open(FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)))
        val pastedWithoutMoment = ReferenceMessage.golden(wireView(60 to "20200101-00:00:00"))

        assertTrue(plan(draft, pastedWithoutMoment).isEmpty())
    }
}
