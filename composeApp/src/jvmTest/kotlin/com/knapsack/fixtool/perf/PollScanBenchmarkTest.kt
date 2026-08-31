package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.FixMessageView
import org.junit.Test
import kotlin.test.assertTrue

/**
 * **What one tick of a wait loop costs, over the whole retained buffer.**
 *
 * Three loops poll for a message on a 100ms tick — `ScenarioRunner`'s reply wait, and two in
 * `ControlServer` — and each one rescans the *entire* retained window from index zero every time. A
 * step waiting five seconds for a reply scans the buffer fifty times, and the buffer is growing while
 * it waits.
 *
 * The expensive half of that used to be the field parse: a step with a field predicate asked
 * `host.view(msg)?.fields()` per message per tick, which went through `FixMessageView.of` to a fresh
 * `parseFixMessage` of the raw wire string, uncached. That is gone — `FixMessage.wireFields` caches it
 * now (see `GroupingBenchmarkTest`), so the same scan reads a field list instead of building one.
 *
 * What remains is the scan itself: a `filterIsInstance` and a `filter`, each materialising a list the
 * size of what survives. These numbers say how much that is still worth, so the decision about whether
 * to carry a watermark is made against a measurement rather than against the shape of the code.
 */
class PollScanBenchmarkTest {
    /** The predicate shape a bound step uses: type, direction, and a field constraint. */
    private fun pollOnce(messages: List<AppMessage>, wanted: String): FixMessage? =
        messages
            .filterIsInstance<FixMessage>()
            .filter { it.messageType == "8" }
            .firstOrNull { message ->
                val wire = FixMessageView.of(message)?.fields() ?: return@firstOrNull false
                wire.any { it.first == 11 && it.second == wanted }
            }

    /**
     * One poll tick against buffers an order of magnitude apart.
     *
     * Reported rather than pinned to an absolute: the figure is a laptop's, the shape is the point. A
     * scan that is linear in the buffer shows a proportional climb across the row; the question this
     * answers is how big that climb still is now the parse behind it is cached.
     */
    @Test
    fun `one poll tick, by retained buffer size`() {
        println("\n┌─ One wait-loop tick, scanning the retained buffer (~10 of these per second, per waiting step)")
        val measured =
            listOf(100, 1_000).map { size ->
                val messages: List<AppMessage> = Corpus.rfqFlow(size)
                // The worst case a wait loop has, and the only one that matters: the message is not
                // there yet, so the scan runs to the end rather than short-circuiting.
                val result =
                    Bench.measure("buffer of %,d messages".format(size), ops = 200) {
                        pollOnce(messages, wanted = "ORD-NEVER-ARRIVES")
                    }
                println("│  " + result.render())
                size to result
            }
        val perSecond = measured.last().second
        println("│  → a step waiting 5s over a full buffer: %,d B and %,d ms of scanning".format(
            perSecond.bytesPerOp * 50,
            perSecond.nanosPerOp * 50 / 1_000_000,
        ))
        println("└─\n")

        // The parse behind the scan is cached now, so a tick must not be allocating a field list per
        // message any more. Anything above this is the old uncached path coming back.
        val large = measured.last().second
        assertTrue(
            large.bytesPerOp < 200_000,
            "a poll tick over 1,000 messages should not re-parse them; got ${large.bytesPerOp} B/op",
        )
    }

    /**
     * The parse cache, seen from the wait loop rather than from the grid.
     *
     * `FixMessageView.of` is what the runner's `matches()` calls per message per tick. It used to build
     * the field list every time; it now wraps one the message already holds.
     */
    @Test
    fun `building a message view no longer parses the message`() {
        val message = Corpus.rfqFlow(1).first()

        val result =
            Bench.measure("FixMessageView.of(message).fields()", ops = 20_000) {
                FixMessageView.of(message)?.fields()
            }
        println("\n┌─ What the runner's matches() pays per message per tick\n│  " + result.render() + "\n└─\n")

        assertTrue(
            result.bytesPerOp <= 64,
            "a view over a cached field list should allocate only the wrapper; got ${result.bytesPerOp} B/op",
        )
    }
}
