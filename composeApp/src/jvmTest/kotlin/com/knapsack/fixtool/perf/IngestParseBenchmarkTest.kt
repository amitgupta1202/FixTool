package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import org.junit.Test
import kotlin.test.assertTrue

/**
 * **What `fromApp` pays to re-parse every incoming message — the cost the audit missed.**
 *
 * `QuickFixService.fromApp` does not use the message QuickFIX/J already parsed. It re-parses the wire
 * bytes itself through [FixMessageHelper.toQuickFixMessageManual], on the callback thread, for every
 * message. There are documented reasons — QFJ's parser mishandles non-standard delimiter ordering, and
 * repeating groups the dictionary does not define collapse to their last instance unless they are
 * salvaged — so this is deliberate, not an oversight.
 *
 * What was an oversight is that nobody had costed it. The audit noted the re-parse in passing and
 * called it minor without measuring. It is not minor: after the logging fix took the per-message log
 * line to 35ns and the order book to ~800ns, **this is now the dominant per-message cost in the
 * application by roughly three orders of magnitude**, and it scales with message size.
 *
 * Two things inside it are worth separating before anyone optimises:
 *
 * - `salvageableGroups` calls `GroupOverlay.build` over the whole field list on every message, running
 *   period-detection heuristics to find undefined repeating groups. On a session whose dictionary
 *   defines everything, that work always finds nothing.
 * - The assembly loop then does `salvaged.keys.filter { it > i }.minOrNull()` per iteration, which is
 *   O(groups²) — small, but it is inside the per-message path too.
 *
 * This test asserts nothing about the absolute figures, which are a laptop's. It exists to record the
 * cost so a fix can be measured against it, and to fail loudly if the shape ever goes superlinear.
 */
class IngestParseBenchmarkTest {
    // The bundled dictionary, not createDefault(): the manual parser refuses without a DataDictionary,
    // and the salvage heuristics only mean anything against a real one.
    private val dictionary = FixDictionaryAdapter.fromResource()

    @Test
    fun `the per-message manual re-parse, by message size`() {
        val cases =
            listOf(
                "ExecutionReport (~14 fields)" to Corpus.rfqFlow(5).first { it.messageType == "8" }.wireRaw!!,
                "MD snapshot,  25 entries" to Corpus.marketDataSnapshot(25).wireRaw!!,
                "MD snapshot, 100 entries" to Corpus.marketDataSnapshot(100).wireRaw!!,
                "MD snapshot, 250 entries" to Corpus.marketDataSnapshot(250).wireRaw!!,
            )

        println("\n┌─ What fromApp pays to re-parse ONE incoming message, on the callback thread")
        val measured =
            cases.map { (name, raw) ->
                val result =
                    Bench.measure(name, ops = 50, warmupRounds = 2, rounds = 5) {
                        with(FixMessageHelper) { raw.toQuickFixMessageManual(dictionary) }
                    }
                println("│  " + result.render())
                result
            }
        val ordinary = measured.first()
        println(
            "└─ an ordinary message caps ingest near %,d msg/s from this path alone\n"
                .format(1_000_000_000L / ordinary.nanosPerOp),
        )

        // 10x the entries must not cost 100x. The absolute figure is free to move — the machine decides
        // it — but the parse must stay linear in the message, which is what the salvage heuristics
        // running over the whole field list per message could quietly stop being.
        val small = measured[1] // 25 entries
        val large = measured[3] // 250 entries
        val ratio = large.nanosPerOp.toDouble() / small.nanosPerOp
        assertTrue(
            ratio < 25.0,
            "10x the entries cost ${ratio}x the parse time — the manual re-parse has gone superlinear",
        )
    }
}
