package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pairing rule, attacked exhaustively rather than by example.
 *
 * [ExpectationEvaluator.align] is the load-bearing decision in the whole tool: it decides *which field an
 * assertion is about*. Every example-based test proves it works for the shape the author thought of, which
 * is exactly the failure mode this project keeps hitting — the fixtures dodge the hard case, the suite goes
 * green, and the model is wrong anyway.
 *
 * So this enumerates **every** message and **every** expectation over a small alphabet — all tag sequences
 * up to length 5 over two tags, against all row sequences up to length 4 — a few hundred thousand pairs, and
 * asserts the model's invariants on all of them. It cannot prove the engine right for FIX at large, but a
 * counterexample in this space is a counterexample everywhere, and the space is big enough to contain every
 * structural shape that matters: interleaved repeats, rows with no matching tag, expectations longer than
 * the message, duplicate values, unique values.
 */
class AlignmentPropertiesTest {
    private val tags = listOf(1, 2)
    private val values = listOf("a", "b")

    /** All (tag, value) sequences of length 0..[maxLen] over the alphabet. */
    private fun messages(maxLen: Int): List<List<Pair<Int, String>>> {
        var out = listOf<List<Pair<Int, String>>>(emptyList())
        val all = mutableListOf<List<Pair<Int, String>>>(emptyList())
        repeat(maxLen) {
            out = out.flatMap { prefix -> tags.flatMap { t -> values.map { v -> prefix + (t to v) } } }
            all += out
        }
        return all
    }

    /** All row sequences of length 0..[maxLen]; every matcher is Exact, so pass/fail is decidable by hand. */
    private fun expectations(maxLen: Int): List<List<FieldExpectation>> {
        var out = listOf<List<FieldExpectation>>(emptyList())
        val all = mutableListOf<List<FieldExpectation>>(emptyList())
        repeat(maxLen) {
            out =
                out.flatMap { prefix ->
                    tags.flatMap { t -> values.map { v -> prefix + FieldExpectation(t, Matcher.Exact(v)) } }
                }
            all += out
        }
        return all
    }

    /**
     * The invariants that make a false green impossible, checked on every pair.
     *
     * 1. **A row never pairs with a field of a different tag.** If it could, an assertion would be judged
     *    against a field it does not describe — the whole failure this model was built to eliminate.
     * 2. **No two rows claim the same field**, and claims strictly increase. That is what makes the rows a
     *    *subsequence* of the message rather than a bag of tags, and it is what stops a reordered group from
     *    being silently absorbed.
     * 3. **Pairing is blind to the matcher.** Rows differing only in their expected *value* must produce the
     *    identical pairing. A pairing that preferred the occurrence a matcher happens to satisfy would
     *    re-aim the assertion onto whichever field makes it green.
     */
    @Test
    fun `pairing is by tag and position, never by whether the matcher would pass`() {
        var checked = 0
        for (wire in messages(5)) {
            for (rows in expectations(4)) {
                val expectation = Expectation(rows, mode = MatchMode.OPEN)
                val alignment = ExpectationEvaluator.align(expectation, wire)

                val claims = mutableListOf<Int>()
                for (a in alignment) {
                    val row = a.row ?: continue
                    val at = a.wireIndex ?: continue
                    // 1. same tag, always
                    assertEquals(
                        row.tag,
                        wire[at].first,
                        "row ${a.index} (tag ${row.tag}) paired with wire[$at] (tag ${wire[at].first}) — " +
                            "wire=$wire rows=${rows.map { it.tag }}",
                    )
                    claims += at
                }
                // 2. strictly increasing, so no field is claimed twice and the rows are a subsequence
                assertEquals(
                    claims.sorted().distinct(),
                    claims,
                    "claims must strictly increase: $claims for wire=$wire rows=${rows.map { it.tag }}",
                )

                // 3. the same tag sequence with different expected values must pair identically
                val flipped = Expectation(rows.map { FieldExpectation(it.tag, Matcher.Exact("zzz")) }, mode = MatchMode.OPEN)
                val flippedClaims =
                    ExpectationEvaluator.align(flipped, wire).filter { it.row != null }.map { it.index to it.wireIndex }
                val originalClaims = alignment.filter { it.row != null }.map { it.index to it.wireIndex }
                assertEquals(
                    originalClaims,
                    flippedClaims,
                    "pairing changed when only the expected VALUES changed — the engine is matcher-aware, " +
                        "which re-aims assertions onto whichever field makes them green. wire=$wire",
                )
                checked++
            }
        }
        println("alignment invariants checked on $checked (message, expectation) pairs")
        assertTrue(checked > 100_000, "the search space should be large; got $checked")
    }

    /**
     * OPEN's verdict must be exactly the model's rule, checked against an independently written oracle.
     *
     * The oracle is deliberately a *second* implementation — normally the thing this project forbids, but in
     * a test it is the point: if the engine and a from-the-spec reading of the rule ever disagree on any
     * input in the space, one of them is wrong and I want to know which.
     *
     * The rule: greedily pair the k-th row for a tag with the next unclaimed occurrence of that tag at or
     * after the previous row's field. OPEN passes iff every row paired AND every matcher passed on the value
     * it paired with.
     */
    @Test
    fun `OPEN passes exactly when the rows are a subsequence whose matchers all hold`() {
        var passes = 0
        var fails = 0
        for (wire in messages(5)) {
            for (rows in expectations(4)) {
                val expectation = Expectation(rows, mode = MatchMode.OPEN)
                val engine = ExpectationEvaluator.evaluate(FixMessageView.ofFields(wire), expectation).all { it.passed }

                // The oracle, written straight from the spec.
                var cursor = 0
                var oracle = true
                for (row in rows) {
                    val at = (cursor until wire.size).firstOrNull { wire[it].first == row.tag }
                    if (at == null) {
                        oracle = false
                        break
                    }
                    val expected = (row.matcher as Matcher.Exact).value
                    if (wire[at].second != expected) oracle = false // paired, but the value differs
                    cursor = at + 1
                }

                assertEquals(
                    oracle,
                    engine,
                    "engine and spec disagree. wire=$wire rows=${rows.map { it.tag to (it.matcher as Matcher.Exact).value }}",
                )
                if (engine) passes++ else fails++
            }
        }
        println("OPEN oracle agreement: $passes passing, $fails failing")
        // A space with no passes (or no fails) would mean the test proves nothing.
        assertTrue(passes > 1000 && fails > 1000, "the space must contain both outcomes: $passes / $fails")
    }

    /**
     * The property capture depends on: **an expectation seeded from a message always passes that message.**
     *
     * If this can ever be false, then a freshly captured scenario can go red against the very reply it was
     * captured from — which is not a hypothetical, it is the bug that shipped twice (the golden stored in a
     * lossy form, and the routing tags seeded Exact). Here it is asserted for every message in the space.
     */
    @Test
    fun `an expectation seeded from a message always passes that message`() {
        for (wire in messages(5)) {
            if (wire.isEmpty()) continue
            val rows = wire.map { (tag, value) -> FieldExpectation(tag, Matcher.Exact(value)) }
            for (mode in listOf(MatchMode.OPEN, MatchMode.STRICT)) {
                val results = ExpectationEvaluator.evaluate(FixMessageView.ofFields(wire), Expectation(rows, mode = mode))
                val failed = results.filterNot { it.passed }
                assertTrue(
                    failed.isEmpty(),
                    "a capture must pass its own golden in $mode: wire=$wire failed=$failed",
                )
            }
        }
    }

    /**
     * STRICT must reject any message the expectation does not describe exactly — and accept the one it does.
     *
     * The asymmetry worth pinning: STRICT is a claim about the message's *shape*, so an expectation seeded
     * from message A must pass A and fail every other message in the space that differs from it in tags,
     * counts or order. If STRICT ever passes a message it was not seeded from, it is not asserting shape.
     */
    @Test
    fun `STRICT passes exactly the message it was seeded from`() {
        val all = messages(4).filter { it.isNotEmpty() }
        for (golden in all) {
            val rows = golden.map { (tag, value) -> FieldExpectation(tag, Matcher.Exact(value)) }
            val expectation = Expectation(rows, mode = MatchMode.STRICT)
            for (candidate in all) {
                val passed =
                    ExpectationEvaluator.evaluate(FixMessageView.ofFields(candidate), expectation).all { it.passed }
                assertEquals(
                    golden == candidate,
                    passed,
                    "STRICT must pass exactly its golden. golden=$golden candidate=$candidate passed=$passed",
                )
            }
        }
    }

    /**
     * `absent` rows take **no part in pairing**, and that interaction is where a subtle false green would
     * hide: an absent row that silently consumed the cursor would shift every row after it onto the wrong
     * occurrence, so a scenario would assert a different field than the one written on the screen.
     *
     * The alphabet here mixes `absent` rows in among `exact` ones, in every position, and checks the engine
     * against an oracle that models the rule directly: an absent row claims nothing and passes iff its tag
     * appears **nowhere** in the message; the exact rows pair greedily among themselves as if the absent rows
     * were not there.
     */
    @Test
    fun `absent rows take no part in pairing and do not shift the rows after them`() {
        // Rows are Exact(a) / Exact(b) / Absent, over both tags.
        fun rowAlphabet(): List<FieldExpectation> =
            tags.flatMap { t -> values.map { v -> FieldExpectation(t, Matcher.Exact(v)) } + FieldExpectation(t, Matcher.Absent) }

        var out = listOf<List<FieldExpectation>>(emptyList())
        val allRows = mutableListOf<List<FieldExpectation>>()
        repeat(3) {
            out = out.flatMap { prefix -> rowAlphabet().map { prefix + it } }
            allRows += out
        }

        var withAbsent = 0
        for (wire in messages(4)) {
            for (rows in allRows) {
                if (rows.none { it.matcher is Matcher.Absent }) continue
                withAbsent++
                val expectation = Expectation(rows, mode = MatchMode.OPEN)
                val engine =
                    ExpectationEvaluator.evaluate(FixMessageView.ofFields(wire), expectation).all { it.passed }

                // Oracle: absent rows claim nothing; the rest pair greedily as if the absent rows were absent.
                var cursor = 0
                var oracle = true
                for (row in rows) {
                    if (row.matcher is Matcher.Absent) {
                        if (wire.any { it.first == row.tag }) oracle = false // the tag is there; it must not be
                        continue // and it does NOT move the cursor
                    }
                    val at = (cursor until wire.size).firstOrNull { wire[it].first == row.tag }
                    if (at == null) {
                        oracle = false
                        break
                    }
                    if (wire[at].second != (row.matcher as Matcher.Exact).value) oracle = false
                    cursor = at + 1
                }

                assertEquals(
                    oracle,
                    engine,
                    "absent-row handling diverges from the rule. wire=$wire rows=" +
                        rows.joinToString { "${it.tag}:${ExpectationEvaluator.describe(it.matcher)}" },
                )
            }
        }
        println("absent-row interaction checked on $withAbsent (message, expectation) pairs")
        assertTrue(withAbsent > 10_000, "the absent-row space should be large; got $withAbsent")
    }

    /**
     * A row an author writes **explicitly** on a session-envelope tag is evaluated like any other.
     *
     * The envelope (8, 9, 10, 34, 49, 52, 56, 369) is never *seeded* and never counted as an unexpected extra
     * in STRICT — but filtering it out of the wire before pairing made `{"tag":34,"matcher":{"exact":"5"}}` —
     * a legitimate check on a gap-fill test — permanently red with "the reply has no such tag", while the raw
     * pane sat there showing `34=5`. The distinction is invisible until someone writes that row.
     */
    @Test
    fun `an explicit row on an envelope tag is evaluated, and never reported as an extra`() {
        val wire = listOf(8 to "FIX.4.4", 34 to "5", 35 to "8", 39 to "2", 10 to "123")

        // Explicitly asserted: it is judged, and it passes on the right value.
        val onSeqNum = Expectation(listOf(FieldExpectation(34, Matcher.Exact("5"))), mode = MatchMode.OPEN)
        val hit = ExpectationEvaluator.evaluate(FixMessageView.ofFields(wire), onSeqNum).single()
        assertTrue(hit.passed, "an explicit row on MsgSeqNum must be evaluated, not silently dropped: $hit")
        assertEquals("5", hit.actual)

        // And it fails on the wrong value — i.e. it is a real assertion, not a no-op that always passes.
        val wrong = Expectation(listOf(FieldExpectation(34, Matcher.Exact("6"))), mode = MatchMode.OPEN)
        assertTrue(!ExpectationEvaluator.evaluate(FixMessageView.ofFields(wire), wrong).single().passed)

        // Unmentioned, STRICT does not call the envelope an unexpected extra — or a captured scenario could
        // never pass anywhere, since every message carries one.
        val body = Expectation(listOf(FieldExpectation(35, Matcher.Exact("8")), FieldExpectation(39, Matcher.Exact("2"))), mode = MatchMode.STRICT)
        val strict = ExpectationEvaluator.evaluate(FixMessageView.ofFields(wire), body)
        assertTrue(strict.all { it.passed }, "STRICT must not flag the session envelope as unexpected: ${strict.filterNot { it.passed }}")
    }
}
