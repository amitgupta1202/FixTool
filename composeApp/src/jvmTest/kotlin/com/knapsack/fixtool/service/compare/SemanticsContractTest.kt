package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The gate.** Every semantics in [SemanticsRegistry] is run against the pairing contract here, over every
 * (message, expectation) pair the alphabet can produce. A semantics that is not in the registry cannot be
 * selected; a semantics that is in it and fails this cannot be built. That is the registration gate — a
 * test, not a startup assertion, because nothing runs a property harness of this size at app start and a
 * check that pretends to would either lie or never run.
 *
 * The contract is deliberately narrow, and it is the whole safety argument of the seam:
 *
 * 1. **A row never pairs with a field of a different tag.** If it could, an assertion would be judged
 *    against a field it does not describe.
 * 2. **The rows are a subsequence.** No two rows claim the same field, and claims strictly increase —
 *    which is what stops a reordered group from being silently absorbed.
 * 3. **Pairing is blind to the matcher.** Expectations differing only in their expected *values* must pair
 *    identically. A pairing that preferred the occurrence a matcher happens to satisfy would re-aim the
 *    assertion onto whichever field makes it green: the venue reorders two party entries, every row finds
 *    the entry that still matches, and the step passes while asserting something nobody wrote. This is the
 *    forbidden false green, and it is the property a future tree/GumTree semantics is most likely to break,
 *    because "match what looks alike" is exactly what those algorithms are good at.
 * 4. **An expectation seeded from a message aligns clean against it.** A semantics that cannot recognise a
 *    message as itself cannot be trusted to say anything about one that differs.
 * 5. **The model is a partition.** Every row of the diff appears in exactly one chunk, in reading order —
 *    so the surface cannot silently drop a failure it has no chunk for.
 *
 * `AlignmentPropertiesTest` stays exactly as it is: it pins the *current* aligner against an independently
 * written oracle, and it is the regression net this harness is not.
 */
class SemanticsContractTest {
    private val tags = listOf(1, 2)
    private val values = listOf("a", "b")

    private fun sequences(maxLen: Int): List<List<Pair<Int, String>>> {
        var out = listOf<List<Pair<Int, String>>>(emptyList())
        val all = mutableListOf<List<Pair<Int, String>>>(emptyList())
        repeat(maxLen) {
            out = out.flatMap { prefix -> tags.flatMap { t -> values.map { v -> prefix + (t to v) } } }
            all += out
        }
        return all
    }

    private fun expectationOf(fields: List<Pair<Int, String>>) =
        Expectation(fields.map { (t, v) -> FieldExpectation(t, Matcher.Exact(v)) })

    private val messages = sequences(maxLen = 4)
    private val expectations = sequences(maxLen = 3).map(::expectationOf)

    /** How the semantics paired this expectation: row index → the field of the message it claims. */
    private fun pairing(model: AlignmentModel): List<Pair<Int, Int?>> =
        model.rows.filter { it.matcher != null }.map { it.index!! to it.wireIndex }

    private fun eachSemantics(check: (ComparisonSemantics) -> Unit) {
        val registered = SemanticsRegistry.all()
        assertTrue(registered.isNotEmpty(), "an empty registry would make this whole suite vacuously green")
        registered.forEach(check)
    }

    @Test
    fun `a row only ever pairs with a field of its own tag`() {
        eachSemantics { semantics ->
            for (wire in messages) {
                val message = wireView(wire)
                for (expectation in expectations) {
                    val model = semantics.align(expectation, message, dictionary = null)
                    for (row in model.rows) {
                        val at = row.wireIndex ?: continue
                        assertEquals(
                            row.tag,
                            wire[at].first,
                            "${semantics.id}: a row for tag ${row.tag} claimed field ${wire[at]} — $expectation vs $wire",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the rows are a subsequence of the message - no field claimed twice, claims strictly increase`() {
        eachSemantics { semantics ->
            for (wire in messages) {
                val message = wireView(wire)
                for (expectation in expectations) {
                    val model = semantics.align(expectation, message, dictionary = null)
                    val claims = pairing(model).mapNotNull { it.second }

                    assertEquals(
                        claims.size,
                        claims.distinct().size,
                        "${semantics.id}: two rows claimed the same field — $expectation vs $wire",
                    )
                    assertEquals(
                        claims.sorted(),
                        claims,
                        "${semantics.id}: the rows are not in the order the message has them — $expectation vs $wire",
                    )
                }
            }
        }
    }

    /**
     * THE ONE THE MODEL EXISTS FOR. Two expectations with identical tags and different expected values must
     * pair identically — the pairing may look at the tag and the position, and never at whether the matcher
     * would pass.
     */
    @Test
    fun `pairing is blind to whether the matcher would pass`() {
        eachSemantics { semantics ->
            for (wire in messages) {
                val message = wireView(wire)
                for (expectation in expectations) {
                    val paired = pairing(semantics.align(expectation, message, dictionary = null))

                    // Same rows, same tags, every matcher rewritten to something that cannot hold.
                    val cannotMatch =
                        expectation.copy(fields = expectation.fields.map { it.copy(matcher = Matcher.Exact("zzz")) })
                    val alsoPaired = pairing(semantics.align(cannotMatch, message, dictionary = null))

                    assertEquals(
                        paired,
                        alsoPaired,
                        "${semantics.id}: the pairing moved when only the expected values changed — $expectation vs $wire",
                    )
                }
            }
        }
    }

    /** A semantics that cannot recognise a message as itself cannot be trusted to judge one that differs. */
    @Test
    fun `an expectation seeded from a message aligns clean against that message`() {
        eachSemantics { semantics ->
            for (wire in messages.filter { it.isNotEmpty() }) {
                val message = wireView(wire)
                val seeded = ExpectationSeeder.seed(wire, null)

                val model = semantics.align(seeded, message, dictionary = null)

                assertTrue(
                    model.rows.all { it.passed },
                    "${semantics.id}: a seeded expectation failed against its own message — $wire",
                )
                assertTrue(
                    model.chunks.all { it.kind == ChunkKind.SAME },
                    "${semantics.id}: a seeded expectation diffed against its own message — " +
                        "${model.chunks.map { it.kind }} — $wire",
                )
            }
        }
    }

    /** Every row of the diff is in exactly one chunk, in reading order: the surface can drop nothing. */
    @Test
    fun `the chunks partition the diff`() {
        eachSemantics { semantics ->
            for (wire in messages) {
                val message = wireView(wire)
                for (expectation in expectations) {
                    val model = semantics.align(expectation, message, dictionary = null)

                    assertEquals(
                        model.rows,
                        model.chunks.flatMap { it.rows },
                        "${semantics.id}: the chunks are not the rows — $expectation vs $wire",
                    )
                    val asserted = model.rows.mapNotNull { it.index }
                    assertEquals(
                        expectation.fields.indices.toList(),
                        asserted,
                        "${semantics.id}: a row of the expectation is missing from the diff — $expectation vs $wire",
                    )
                }
            }
        }
    }
}
