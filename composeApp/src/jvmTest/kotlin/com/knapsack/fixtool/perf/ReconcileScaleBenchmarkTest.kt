package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.FixMessageView
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioReconcile
import org.junit.Test
import kotlin.test.assertTrue

/**
 * **How the reconcile view behaves as the message it is reconciling gets big.**
 *
 * Both of the superlinear findings in the audit live on this path, and both only bite on a shape this
 * tool's users produce constantly: a market-data snapshot. An order has forty fields and nothing here
 * is measurable on it; a 200-entry `MarketDataSnapshotFullRefresh` has eight hundred, and the two paths
 * below grow much faster than that.
 *
 * - `ScenarioReconcile.placeByMovedEntry` enumerates **every contiguous sub-run** of the draft — n(n+1)/2
 *   of them, each eagerly materialised as a list — and hands each to `verbatimWindow`, which scans the
 *   wire position by position. That is a product of two superlinear things.
 * - `DiffModelBuilder.itemsOf` used to scan the whole line list once per overlay entry to find each
 *   entry's first and last row: O(entries × lines) for something one sweep answers. Fixed, and pinned
 *   here by the whole-path number rather than in isolation.
 *
 * **Growth is the measurement.** Reconcile is authoring-time, not per-message, so an absolute figure is
 * not interesting — nobody minds five milliseconds when they click a button. What matters is whether
 * doubling the message quadruples the wait, because that is the difference between a big snapshot being
 * slow and being a hang.
 */
class ReconcileScaleBenchmarkTest {
    private val dictionary = FixDictionaryAdapter.createDefault()

    /**
     * A draft that asserts every field of an [entries]-entry snapshot, and the reply it is checked
     * against — the shape an author gets from capturing a snapshot and seeding assertions off it.
     */
    private fun draftAndReply(entries: Int): Pair<Expectation, MessageView> {
        val message = Corpus.marketDataSnapshot(entries)
        val wire = FixMessageView.of(message)!!
        val fields =
            wire.fields().map { (tag, value) ->
                // Presence on the volatile ones, exact on the rest: an ordinary seeded expectation.
                if (tag in VOLATILE) FieldExpectation(tag, Matcher.Presence) else FieldExpectation(tag, Matcher.Exact(value))
            }
        return Expectation(fields, messageType = "W", mode = MatchMode.OPEN) to wire
    }

    /** Tags a seeder would not pin to a literal. */
    private val VOLATILE = setOf(52, 270, 271)

    /**
     * The whole authoring path — rows plus the move analysis — over snapshots an order of magnitude
     * apart in size.
     *
     * `canAcceptNewOrder` is what the view calls before it offers the button, so it runs on every
     * reconcile whether or not anything moved. It is the entry point to `placeByMovedEntry`.
     */
    @Test
    fun `reconciling a market-data snapshot grows with the message, not with its square`() {
        println("\n┌─ Reconciling a MarketDataSnapshot, by entry count")
        val measured =
            listOf(25, 50, 100).map { entries ->
                val (draft, reply) = draftAndReply(entries)
                val result =
                    Bench.measure(
                        "%,d entries (%,d fields)".format(entries, draft.fields.size),
                        ops = 1,
                        warmupRounds = 1,
                        rounds = 3,
                    ) {
                        ScenarioReconcile.rows(draft, reply, dictionary)
                        ScenarioReconcile.canAcceptNewOrder(draft, reply)
                    }
                println("│  " + result.render())
                entries to result
            }
        println("└─\n")

        val small = measured.first().second
        val large = measured.last().second
        val sizeRatio = measured.last().first.toDouble() / measured.first().first
        val timeRatio = large.nanosPerOp.toDouble() / small.nanosPerOp

        println("   ${sizeRatio.toInt()}x the entries cost %.1fx the time\n".format(timeRatio))

        // Four times the message must not cost anything like sixteen times the work. The bound is loose
        // — there is real per-field work here and some superlinearity is inherent in comparing two
        // sequences — but a runaway enumeration lands far above it, and that is what this catches.
        assertTrue(
            timeRatio < sizeRatio * sizeRatio,
            "reconciling ${measured.last().first} entries took ${large.nanosPerOp}ns against " +
                "${small.nanosPerOp}ns for ${measured.first().first} — ${timeRatio}x for ${sizeRatio}x the " +
                "message, which is quadratic or worse and turns a big snapshot into a hang",
        )
    }

    /**
     * The reconcile view has to keep working on a message this size, not merely finish. A snapshot with
     * hundreds of entries is the case the whole feature exists for, and a row per field is what the
     * author is going to read.
     */
    @Test
    fun `a large snapshot still reconciles to one row per asserted field`() {
        val (draft, reply) = draftAndReply(entries = 100)
        val rows = ScenarioReconcile.rows(draft, reply, dictionary)

        assertTrue(rows.isNotEmpty(), "a reconcile over a real snapshot must produce rows")
        assertTrue(
            rows.size >= draft.fields.size,
            "every asserted field needs a row of its own; got ${rows.size} for ${draft.fields.size} fields",
        )
    }
}
