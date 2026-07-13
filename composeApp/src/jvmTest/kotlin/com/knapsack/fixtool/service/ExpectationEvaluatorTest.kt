package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure assertion engine. Each matcher type is exercised on a small in-memory
 * [MessageView] so the rules are pinned without a live FIX session.
 */
class ExpectationEvaluatorTest {
    // A fixed "now" so temporal matchers are deterministic: 2026-06-30 09:15:02 UTC.
    private val fixedNow = { Instant.parse("2026-06-30T09:15:02Z") }

    private fun view(vararg tags: Pair<Int, String>, groups: Map<Int, List<Map<Int, String>>> = emptyMap()) =
        MapMessageView(tags.toMap(), groups)

    private fun eval(
        view: MessageView,
        vararg fields: FieldExpectation,
        mode: MatchMode = MatchMode.OPEN,
        resolver: (String) -> String? = { null },
    ) = ExpectationEvaluator.evaluate(view, Expectation(fields.toList(), mode = mode), resolver, fixedNow)

    private fun fe(tag: Int, matcher: Matcher, path: GroupPath? = null) = FieldExpectation(tag, matcher, path)

    @Test
    fun `exact matches and mismatches`() {
        val v = view(35 to "8", 150 to "F")
        assertTrue(eval(v, fe(35, Matcher.Exact("8"))).single().passed)
        val miss = eval(v, fe(150, Matcher.Exact("0"))).single()
        assertFalse(miss.passed)
        assertEquals("F", miss.actual)
        assertEquals("0", miss.expected)
    }

    @Test
    fun `presence and absent`() {
        val v = view(37 to "OID-9")
        assertTrue(eval(v, fe(37, Matcher.Presence)).single().passed)
        assertFalse(eval(v, fe(99, Matcher.Presence)).single().passed)
        assertTrue(eval(v, fe(58, Matcher.Absent)).single().passed)
        val present = eval(v, fe(37, Matcher.Absent)).single()
        assertFalse(present.passed)
        assertEquals("OID-9", present.actual)
    }

    @Test
    fun `oneOf and regex`() {
        val v = view(39 to "2", 11 to "ORD-abc123")
        assertTrue(eval(v, fe(39, Matcher.OneOf(listOf("1", "2")))).single().passed)
        assertFalse(eval(v, fe(39, Matcher.OneOf(listOf("0", "8")))).single().passed)
        assertTrue(eval(v, fe(11, Matcher.Regex("ORD-[a-z0-9]+"))).single().passed)
        assertFalse(eval(v, fe(11, Matcher.Regex("REJ-.*"))).single().passed)
    }

    @Test
    fun `numeric ignores formatting and honours tolerance`() {
        // Trailing-zero formatting must not fail an exact (tolerance 0) numeric compare.
        assertTrue(eval(view(31 to "1.23450"), fe(31, Matcher.Numeric(1.2345, 0.0))).single().passed)
        // Within tolerance passes; outside fails.
        assertTrue(eval(view(31 to "1.23451"), fe(31, Matcher.Numeric(1.2345, 0.00005))).single().passed)
        assertFalse(eval(view(31 to "1.2346"), fe(31, Matcher.Numeric(1.2345, 0.00005))).single().passed)
        // Non-numeric actual fails rather than throwing.
        assertFalse(eval(view(31 to "abc"), fe(31, Matcher.Numeric(1.0))).single().passed)
    }

    @Test
    fun `temporal now within tolerance`() {
        val ts = "20260630-09:15:20.000" // 18s after fixedNow
        assertTrue(eval(view(60 to ts), fe(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 30))).single().passed)
        assertFalse(eval(view(60 to ts), fe(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 5))).single().passed)
    }

    @Test
    fun `temporal today`() {
        assertTrue(eval(view(60 to "20260630-00:00:01.000"), fe(60, Matcher.Temporal(TemporalKind.TODAY))).single().passed)
        assertFalse(eval(view(60 to "20260629-23:59:59.000"), fe(60, Matcher.Temporal(TemporalKind.TODAY))).single().passed)
    }

    @Test
    fun `reference resolves against scope`() {
        val resolver = { expr: String -> if (expr == "\${out.D.11}") "ORD-1" else null }
        assertTrue(eval(view(11 to "ORD-1"), fe(11, Matcher.Reference("\${out.D.11}")), resolver = resolver).single().passed)
        assertFalse(eval(view(11 to "ORD-2"), fe(11, Matcher.Reference("\${out.D.11}")), resolver = resolver).single().passed)
        // Unresolvable reference is a failure, not a crash.
        assertFalse(eval(view(11 to "ORD-1"), fe(11, Matcher.Reference("\${out.X.99}")), resolver = resolver).single().passed)
    }

    @Test
    fun `strict mode flags unexpected tags but ignores header trailer volatiles`() {
        val v = view(35 to "8", 9 to "100", 10 to "077", 34 to "5", 52 to "20260630-09:15:02", 58 to "extra")
        val results = eval(v, fe(35, Matcher.Exact("8")), mode = MatchMode.STRICT)
        // 35 passes; 58 is an unexpected business tag -> synthetic failure; 8/9/10/34/52 ignored.
        assertEquals(setOf(35, 58), results.map { it.tag }.toSet())
        assertTrue(results.single { it.tag == 35 }.passed)
        assertFalse(results.single { it.tag == 58 }.passed)
    }

    @Test
    fun `group entry located by identity not position`() {
        val groups = mapOf(453 to listOf(mapOf(452 to "1", 448 to "BROKER-A"), mapOf(452 to "2", 448 to "CLIENT-X")))
        val v = view(35 to "8", groups = groups)
        // PartyID(448) of the entry whose PartyRole(452)=1 must be BROKER-A.
        assertTrue(eval(v, fe(448, Matcher.Exact("BROKER-A"), GroupPath(453, 452, "1"))).single().passed)
        assertTrue(eval(v, fe(448, Matcher.Exact("CLIENT-X"), GroupPath(453, 452, "2"))).single().passed)
        // No entry with the requested identity -> fail with an explanatory actual.
        val missing = eval(v, fe(448, Matcher.Exact("BROKER-A"), GroupPath(453, 452, "9"))).single()
        assertFalse(missing.passed)
        assertEquals("<no entry>", missing.actual)
    }

    @Test
    fun `results carry the group path so same-tag entries stay distinguishable`() {
        val groups = mapOf(453 to listOf(mapOf(452 to "1", 448 to "BROKER-A"), mapOf(452 to "2", 448 to "CLIENT-X")))
        val v = view(35 to "8", groups = groups)
        val results =
            eval(
                v,
                fe(448, Matcher.Exact("BROKER-A"), GroupPath(453, 452, "1")),
                fe(448, Matcher.Exact("WRONG"), GroupPath(453, 452, "2")),
            )
        // Two results for the same tag, told apart by path — one per group entry.
        assertEquals(2, results.size)
        val broker = results.single { it.path == GroupPath(453, 452, "1") }
        val client = results.single { it.path == GroupPath(453, 452, "2") }
        assertTrue(broker.passed)
        assertFalse(client.passed)
        assertEquals("CLIENT-X", client.actual)
        // A missing entry keeps its path too, so the failure is still attributable.
        val noEntry = eval(v, fe(448, Matcher.Presence, GroupPath(453, 452, "9"))).single()
        assertEquals(GroupPath(453, 452, "9"), noEntry.path)
        // Top-level results carry no path.
        assertEquals(null, eval(v, fe(35, Matcher.Exact("8"))).single().path)
    }

    /**
     * The expectation builder re-evaluates on every keystroke, so an unguarded compile took the
     * whole workbench down (with the author's unsaved edits) the moment they typed a lone `[`. An
     * unusable pattern is a failed assertion that says so, not an exception.
     */
    @Test
    fun `an invalid regex fails the row instead of throwing`() {
        val v = MapMessageView(mapOf(35 to "8", 17 to "EXEC-1"))

        val result = eval(v, fe(17, Matcher.Regex("EXEC-["))).single()

        assertFalse(result.passed)
        assertTrue(result.expected.contains("invalid regex"), "the row should say why: ${result.expected}")
    }

    @Test
    fun `a valid regex still matches`() {
        val v = MapMessageView(mapOf(35 to "8", 17 to "EXEC-1"))

        assertTrue(eval(v, fe(17, Matcher.Regex("EXEC-\\d+"))).single().passed)
        assertFalse(eval(v, fe(17, Matcher.Regex("ORD-\\d+"))).single().passed)
    }

    /** In-memory [MessageView] for tests: flat tags plus optional single-level repeating groups. */
    private class MapMessageView(
        private val tags: Map<Int, String>,
        private val groups: Map<Int, List<Map<Int, String>>> = emptyMap(),
    ) : MessageView {
        override fun valueOfTag(tag: Int): String? = tags[tag]

        override fun presentTags(): Set<Int> = tags.keys

        override fun groupEntries(groupTag: Int): List<MessageView> =
            groups[groupTag]?.map { MapMessageView(it) } ?: emptyList()
    }
}
