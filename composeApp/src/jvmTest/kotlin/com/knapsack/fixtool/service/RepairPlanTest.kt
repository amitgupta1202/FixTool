package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ScenarioReconcile.FixClass
import com.knapsack.fixtool.service.compare.ReferenceMessage
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The plan's three new classes — and the gates that keep each one honest.**
 *
 * Exactly as with the bands ([FixPlanTest]), the property that carries the feature is the *guard list*:
 * an over-wide `oneOf` or an anchor-free regex is the `452 ± 3` trap in different clothes. So this file
 * pins where each class may fire (the seeder's classification, D1's regex-over-presence precedence, the
 * literal-anchor rule) and that what fires actually repairs — plus D2's contract that the one class
 * asserting *less* than the author had arrives unchecked.
 */
class RepairPlanTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val arrival = Instant.parse("2025-01-01T00:00:00Z")

    private fun ref(view: MessageView) =
        ReferenceMessage.live(view, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival)

    private fun open(vararg fields: FieldExpectation) =
        Expectation(fields.toList(), messageType = "8", mode = MatchMode.OPEN)

    private fun plan(draft: Expectation, reference: ReferenceMessage) =
        ScenarioReconcile.fixPlan(draft, reference, dictionary)

    private fun applied(draft: Expectation, fixes: List<ScenarioReconcile.PlannedFix>) =
        fixes.fold(draft) { d, f -> ScenarioReconcile.loosen(d, f.index, f.proposed) }

    // ----- ∈ oneOf: a vocabulary widens by one member, named ---------------------------------------------

    /** The S2 case: OrdStatus one rung along its enum. The set admits both, and the reason decodes them. */
    @Test
    fun `an enum drift widens to oneOf with the meanings decoded, and applying repairs the row`() {
        val draft = open(FieldExpectation(39, Matcher.Exact("1")))
        val reference = ref(wireView(35 to "8", 39 to "2"))

        val fixes = plan(draft, reference)

        val fix = fixes.single()
        assertEquals(FixClass.ONE_OF, fix.klass)
        assertEquals(Matcher.OneOf(listOf("1", "2")), fix.proposed, "the author's value first, the actual appended")
        assertTrue(fix.repairs)
        assertTrue(fix.defaultChecked, "a set still constrains the value — D2 says it arrives checked")
        val decoded = dictionary.getFieldValueDescription(39, "2")
        assertTrue(decoded != null && decoded in fix.reason, "the author admits a meaning, not a digit: ${fix.reason}")

        val after = ScenarioReconcile.rows(applied(draft, fixes), reference, dictionary)
        assertTrue(after.all { it.passed })
    }

    /** An author-declared oneOf has had its kind decided — it widens on any field, preserving its order. */
    @Test
    fun `an authored oneOf widens by the actual, keeping the listed order`() {
        val draft = open(FieldExpectation(150, Matcher.OneOf(listOf("0", "1"))))
        val reference = ref(wireView(35 to "8", 150 to "2"))

        val fix = plan(draft, reference).single()

        assertEquals(FixClass.ONE_OF, fix.klass)
        assertEquals(Matcher.OneOf(listOf("0", "1", "2")), fix.proposed)
    }

    /** Past three members the reason must say what the set has become — presence in disguise, named. */
    @Test
    fun `a set past three members carries the presence warning in its reason`() {
        val draft = open(FieldExpectation(39, Matcher.OneOf(listOf("0", "1", "2"))))
        val reference = ref(wireView(35 to "8", 39 to "8"))

        val fix = plan(draft, reference).single()

        assertEquals(Matcher.OneOf(listOf("0", "1", "2", "8")), fix.proposed)
        assertTrue("presence is the honest assertion" in fix.reason, fix.reason)
    }

    /** A STRING field whose values are a vocabulary is an enum, not a shape — `∈`, never `≈`. */
    @Test
    fun `an enum-coded string widens to oneOf, not to a pattern`() {
        val draft = open(FieldExpectation(22, Matcher.Exact("4")))
        val reference = ref(wireView(35 to "8", 22 to "5"))

        assertEquals(FixClass.ONE_OF, plan(draft, reference).single().klass)
    }

    // ----- ≈ regex: the shape both runs share ------------------------------------------------------------

    /** The S3 case: an id that kept its scheme and changed its counter. */
    @Test
    fun `pattern drift keeps the scheme - the literal anchor plus the narrowest class`() {
        val draft = open(FieldExpectation(526, Matcher.Exact("ORD-2026-0117")))
        val reference = ref(wireView(35 to "8", 526 to "ORD-2026-0245"))

        val fixes = plan(draft, reference)

        val fix = fixes.single()
        assertEquals(FixClass.REGEX, fix.klass)
        assertEquals(Matcher.Regex("""ORD-2026-\d+"""), fix.proposed)
        assertTrue(fix.repairs)
        assertTrue(
            ExpectationEvaluator.satisfies(fix.proposed, "ORD-2026-0117"),
            "the pattern must keep matching the golden it was inferred from",
        )
        val after = ScenarioReconcile.rows(applied(draft, fixes), reference, dictionary)
        assertTrue(after.all { it.passed })
    }

    /** A `.` in the shared literal is a character, not a wildcard — escaped, and still matching both sides. */
    @Test
    fun `regex metacharacters in the shared literal are escaped`() {
        val draft = open(FieldExpectation(526, Matcher.Exact("A.B-001")))
        val reference = ref(wireView(35 to "8", 526 to "A.B-042"))

        val fix = plan(draft, reference).single()

        assertEquals(Matcher.Regex("""A\.B-\d+"""), fix.proposed)
        assertTrue(ExpectationEvaluator.satisfies(fix.proposed, "A.B-001"))
        assertFalse(ExpectationEvaluator.satisfies(fix.proposed, "AXB-042"), "the dot is literal, not any-character")
    }

    /** The boundary digit of a shared prefix belongs to the counter, not the scheme. */
    @Test
    fun `a class character on the literal boundary joins the varying run`() {
        assertEquals("""ORD-\d+""", ScenarioReconcile.inferPattern("ORD-1", "ORD-12"))
    }

    /** Narrowest first: hex-ish uppercase ids get `[A-Z0-9]+`, digits get `\d+` — never the widest fit. */
    @Test
    fun `the narrowest class covering both middles wins`() {
        assertEquals("""BT-[A-Z0-9]+""", ScenarioReconcile.inferPattern("BT-9F3A21", "BT-77C04D"))
        assertEquals("""BT-\d+""", ScenarioReconcile.inferPattern("BT-917", "BT-8244"))
    }

    /** Tight means a literal survives: a pattern that is all class is an assertion about nothing. */
    @Test
    fun `no literal anchor - no pattern`() {
        assertNull(ScenarioReconcile.inferPattern("A7QK2", "9ZP41"), "nothing shared")
        assertNull(ScenarioReconcile.inferPattern("ABC", "ABD"), "the shared prefix dissolves into the class")
    }

    /** A typed non-string field never generalises: `2026072\d+` full-matches two settle dates while asserting nothing a date means. */
    @Test
    fun `a business date is outside every class - no pattern, no presence, no band`() {
        val draft = open(FieldExpectation(64, Matcher.Exact("20260722")))
        val reference = ref(wireView(35 to "8", 64 to "20260723"))

        assertTrue(plan(draft, reference).isEmpty(), "S6 is a matcher-vocabulary gap, not a repair this plan may fake")
    }

    // ----- ∃ presence: the last resort, opted into deliberately ------------------------------------------

    /** The S1 case: an id-named field with a fresh value and no shape to keep. */
    @Test
    fun `an identifier with no shared shape demotes to presence - unchecked by default`() {
        val draft = open(FieldExpectation(583, Matcher.Exact("A7QK2")))
        val reference = ref(wireView(35 to "8", 583 to "9ZP41"))

        val fixes = plan(draft, reference)

        val fix = fixes.single()
        assertEquals(FixClass.PRESENCE, fix.klass)
        assertEquals(Matcher.Presence, fix.proposed)
        assertTrue(fix.repairs)
        assertFalse(fix.defaultChecked, "presence asserts less than the author had — D2 says it arrives unchecked")
        assertTrue("Accept actual instead" in fix.reason, "the reason carries the stable-id warning: ${fix.reason}")

        val after = ScenarioReconcile.rows(applied(draft, fixes), reference, dictionary)
        assertTrue(after.all { it.passed })
    }

    /** D1: where a tight pattern exists, it wins the row — presence never shadows a shape. */
    @Test
    fun `a tight pattern beats presence on the same identifier row`() {
        val draft = open(FieldExpectation(583, Matcher.Exact("L-001")))
        val reference = ref(wireView(35 to "8", 583 to "L-042"))

        assertEquals(FixClass.REGEX, plan(draft, reference).single().klass)
    }

    /** A custom tag the dictionary has never heard of is exactly what S1 is about — presence-eligible. */
    @Test
    fun `a dictionary-unknown custom tag is presence-eligible`() {
        val draft = open(FieldExpectation(5002, Matcher.Exact("8c1f43aa")))
        val reference = ref(wireView(35 to "8", 5002 to "e02d971b"))

        assertEquals(FixClass.PRESENCE, plan(draft, reference).single().klass)
    }

    /** A field named like prose is not an identifier: no class reaches it, and the row stays for per-row repair. */
    @Test
    fun `a free-text field with no shape gets no proposal at all`() {
        val draft = open(FieldExpectation(58, Matcher.Exact("filled")))
        val reference = ref(wireView(35 to "8", 58 to "partial"))

        assertTrue(plan(draft, reference).isEmpty())
    }

    // ----- the composite properties ----------------------------------------------------------------------

    /**
     * One row per class, one plan: every class fires beside the others, every row gets at most one
     * proposal, and every default-checked proposal repairs by construction — the CoverBoth promise,
     * extended to the new classes. Presence repairs too; it is unchecked for D2's reason, not because it
     * would not turn the row green.
     */
    @Test
    fun `one row per class - disjoint, at most one proposal each, and every proposal repairs`() {
        val draft =
            open(
                FieldExpectation(31, Matcher.Exact("100.25")),
                FieldExpectation(39, Matcher.Exact("1")),
                FieldExpectation(526, Matcher.Exact("ORD-2026-0117")),
                FieldExpectation(583, Matcher.Exact("A7QK2")),
            )
        val reference =
            ref(
                wireView(
                    35 to "8",
                    31 to "100.27",
                    39 to "2",
                    526 to "ORD-2026-0245",
                    583 to "9ZP41",
                ),
            )

        val fixes = plan(draft, reference)

        assertEquals(
            listOf(FixClass.NUMERIC, FixClass.ONE_OF, FixClass.REGEX, FixClass.PRESENCE),
            fixes.map { it.klass },
            "each class fired exactly once, in row order",
        )
        assertEquals(fixes.map { it.index }.distinct().size, fixes.size, "at most one proposal per row")
        assertTrue(fixes.all { it.repairs }, "CoverBoth proposals repair by construction — all classes")

        val after = ScenarioReconcile.rows(applied(draft, fixes), reference, dictionary)
        assertTrue(after.all { it.passed })
    }
}
