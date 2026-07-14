package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * 6. **The reference overload pairs exactly as the message overload does.** Pairing is blind to provenance and
 *    to the anchor, as it is blind to the matcher.
 * 7. **A row nobody can read is `unknown`, never `false`** — given what a *reconcile view* actually has, which
 *    is no resolver and, for a paste with no `SendingTime(52)`, no moment either.
 *
 * **Properties 6 and 7 exist because of R2, and R2 is what happens without them.** Until Phase 5 this harness
 * only ever called the `MessageView` overload — the one that does **not** carry the anchoring rule — and the
 * example-based test that covered the other one handed the engine a working `referenceResolver` *the app can
 * never supply*: a reconcile view has no run scope, so there is nothing to pass. It passed. Meanwhile a
 * `reference` row, asked to judge a value it could not read, answered **false**, the block it sat in never
 * fitted, and the tool told the author "these entries did not move; it is the values there that changed" about
 * a message whose party entries had plainly swapped. A row nobody could read was enough to hide an entry that
 * had moved. The fixture dodged the hard case by giving the engine something the caller does not have.
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

    // ---------------------------------------------------------------- the reference overload (S7)

    /**
     * The overload the diff actually calls, over the same bytes, **must pair identically**. A semantics that
     * paired differently because of *what the slot says about the message* would make the diff and the runner
     * disagree about what faces what — and the slot is exactly the thing the author swaps to ask "and against
     * this one?".
     */
    @Test
    fun `the reference overload pairs exactly as the message overload does`() {
        eachSemantics { semantics ->
            for (wire in messages) {
                val message = wireView(wire)
                for (expectation in expectations) {
                    val plain = pairing(semantics.align(expectation, message, dictionary = null))
                    ReferenceMessage.Provenance.entries.forEach { provenance ->
                        val reference = ReferenceMessage(message, provenance, provenance.name, ANCHOR)
                        val viaSlot = pairing(semantics.align(expectation, reference, dictionary = null))
                        assertEquals(
                            plain,
                            viaSlot,
                            "${semantics.id}: pairing moved when the slot said $provenance — $expectation vs $wire",
                        )
                    }
                }
            }
        }
    }

    /**
     * **The row the app cannot read, judged the way the app judges it.**
     *
     * No resolver — because a reconcile view has none — and no moment, because a paste need not carry one. Both
     * rows come back `unknown`: not passed, not failed, and out of every count. Answering `false` is R2, and
     * answering `true` is the false green the model exists to make impossible.
     */
    @Test
    fun `a row that cannot be read is unknown, and never false`() {
        val wire = listOf(11 to "ORD-1", 60 to "20200101-00:00:00")
        val message = wireView(wire)
        val expectation =
            Expectation(
                listOf(
                    // A reference resolves against a live run's variable scope. A diff has no scope to offer it.
                    FieldExpectation(11, Matcher.Reference("\${id0}")),
                    FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                ),
            )

        eachSemantics { semantics ->
            // A paste with no SendingTime(52): there is no moment to judge `~now` against, and the clock is not
            // one — it would manufacture a red describing nothing but how long ago the message was pasted.
            val unanchored = ReferenceMessage(message, ReferenceMessage.Provenance.PASTED, "pasted", null)
            val rows = semantics.align(expectation, unanchored, dictionary = null).rows

            rows.forEach { row ->
                assertTrue(
                    row.unknown,
                    "${semantics.id}: row ${row.tag} was judged when nothing here could judge it — ${row.status}",
                )
                assertFalse(row.passed, "${semantics.id}: an unreadable row is not a pass either")
            }
            val verdict = Verdict.of(rows, emptySet(), 0, expectation.mode)
            assertEquals(0, verdict.judged, "${semantics.id}: and they are counted as judged by nothing")
            assertEquals(0, verdict.attention, "${semantics.id}: nor as failures")
        }
    }

    /** An anchored reference judges its temporal at **the message's own moment**, and not at the wall clock. */
    @Test
    fun `an anchored reference judges a temporal row at the reference's instant`() {
        val sent = "20200101-00:00:05"
        val message = wireView(listOf(52 to sent, 60 to sent))
        val expectation = Expectation(listOf(FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60))))
        val anchored = ReferenceMessage.pasted(message)

        eachSemantics { semantics ->
            val row = semantics.align(expectation, anchored, dictionary = null).rows.single { it.tag == 60 }
            assertTrue(
                row.passed,
                "${semantics.id}: `~now ±60s` held when the message was sent, and the reader being years late " +
                    "is not the venue's doing — ${row.status}",
            )
        }
    }

    private companion object {
        val ANCHOR: java.time.Instant = java.time.Instant.parse("2026-07-14T08:12:31Z")
    }
}
