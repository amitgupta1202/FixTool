package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.LaneRole
import com.knapsack.fixtool.service.ConversationRows
import com.knapsack.fixtool.service.Conversations
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.TraceLanes
import com.knapsack.fixtool.service.TraceRows
import com.knapsack.fixtool.service.Traces
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What the grid pays, ten times a second, to draw conversations it already drew.**
 *
 * `ConversationRows.build` is what turns a session log into grouped rows. It sits inside
 * `remember(messages, ...)` at three call sites, and `messages` is a fresh list on every 100ms drain
 * cycle — so during live traffic the memo never hits and the whole thing is rebuilt from scratch, ten
 * times a second, over the entire retained buffer.
 *
 * Rebuilding row structure is cheap. Rebuilding it by **re-parsing every raw message string three
 * times** is not, and that is what it did:
 *
 * ```
 * Conversations.group  → idsOf(m)   → fieldsForDisplay(m)   ← parse
 * build's idsByTag loop → idsOf(m)  → fieldsForDisplay(m)   ← parse again
 * Conversations.summarize          → fieldsForDisplay(m)   ← parse again
 * ```
 *
 * `fieldsForDisplay` had no cache: every call split the whole wire string on SOH and then split each
 * field on `=`, allocating a fresh `List<Pair<Int, String>>` and a couple of `String`s per field. The
 * message is immutable and its fields cannot change, so all of that was the same answer, recomputed.
 *
 * `Conversations`' own KDoc already said so — *"a caller regrouping on each arrival should memoize on
 * the log rather than call it per frame"* — and no caller did.
 */
class GroupingBenchmarkTest {
    private val dictionary = FixDictionaryAdapter.createDefault()

    /**
     * **The per-message unit: one parse of one message's fields.**
     *
     * Everything else in this file is this number multiplied by how many times the callers ask. Cached
     * on the message as a `lazy` body property, the second and every subsequent ask is a field read.
     */
    @Test
    fun `parsing one message's fields, uncached against cached`() {
        val message = Corpus.rfqFlow(1).first()

        val result =
            Bench.compare(
                "Reading the fields of one FIX message",
                ops = 10_000,
                // The uncached path, preserved verbatim: this is what fieldsForDisplay used to do on
                // every call, and what it will do again if anyone removes the lazy field.
                before =
                    "parse the wire string every time" to {
                        FixMessageHelper.parseFixMessage(message.wireRaw!!, FixMessageHelper.SOH)
                    },
                after =
                    "read the message's cached field list" to {
                        FixMessageHelper.fieldsForDisplay(message)
                    },
            )

        assertTrue(
            result.allocationFactor > 20.0,
            "a cached field list must allocate essentially nothing per read; got ${result.allocationFactor}×",
        )
    }

    /**
     * **The number the grid actually pays**: one full rebuild of the grouped row list over a realistic
     * buffer, which happens ten times a second while traffic flows.
     *
     * 1,000 messages is `FixMessageSession.DEFAULT_BUFFER_SIZE` — the retained window as shipped, not a
     * stress figure. Multiply the per-op result by 10 for the per-second cost of one pane, and again by
     * the number of open panes.
     */
    @Test
    fun `building grouped rows over a full retained buffer`() {
        val messages = Corpus.rfqFlow(1_000)

        val result =
            Bench.measure("ConversationRows.build over 1,000 messages", ops = 20) {
                ConversationRows.build(messages, dictionary, emptySet())
            }

        println("\n┌─ Grouped row rebuild (happens ~10x/second per pane during traffic)")
        println("│  " + result.render())
        println("│  → per second, one pane: %,d B and %,d ms".format(result.bytesPerOp * 10, result.nanosPerOp * 10 / 1_000_000))
        println("└─\n")

        // The rows themselves are the irreducible part; the parses were not. Pinned loosely because the
        // absolute figure depends on the machine, and the point of the pin is that it cannot go back to
        // re-parsing — which showed up here as roughly an order of magnitude more allocation.
        assertTrue(
            result.bytesPerOp < 3_000_000,
            "a grouped rebuild over 1,000 messages should not allocate megabytes; got ${result.bytesPerOp} B",
        )
    }

    /**
     * **Where the remaining cost sits**, so the next person to look does not have to guess.
     *
     * Kept after the fix rather than deleted with it. The first round of this work cut allocation by 8×
     * and wall time by only 1.5×, which is the signature of "you fixed one thing and something else is
     * now dominant" — and it was: `group` was still re-deriving indices the caller then threw away, and
     * `build` was hashing every message into a `HashMap<FixMessage, Int>` to get them back. Neither
     * showed up in the headline number. This breakdown is what found them.
     */
    @Test
    fun `where the cost sits inside a grouped rebuild`() {
        val messages = Corpus.rfqFlow(1_000)
        val fix = messages.filterIsInstance<com.knapsack.fixtool.model.FixMessage>()

        println("\n┌─ Decomposition of one grouped rebuild over 1,000 messages")
        listOf<Pair<String, () -> Any?>>(
            "splitting FIX from non-FIX entries" to { messages.filterIsInstance<com.knapsack.fixtool.model.FixMessage>() },
            "Conversations.group" to { Conversations.group(fix, dictionary) },
            "group + summarize every conversation" to {
                Conversations.group(fix, dictionary).conversations.map { Conversations.summarize(it, dictionary) }
            },
            "full ConversationRows.build" to { ConversationRows.build(messages, dictionary, emptySet()) },
        ).forEach { (name, block) -> println("│  " + Bench.measure(name, ops = 20, block = block).render()) }
        println("└─\n")
    }

    /**
     * **What a cross-session trace costs on the same tick** — eight full panes regrouped at once.
     *
     * `Traces.group` is `Conversations.group` over every session's snapshot instead of one, and it is
     * driven by the same 100ms drain that rebuilds the panes. So the budget is not "fast enough to feel
     * instant" but "finishes inside a tick with room for eight panes' own rebuilds beside it".
     *
     * Eight sessions of 1,000 is the retained window at `FixMessageSession.DEFAULT_BUFFER_SIZE`, times the
     * number of panes a both-sides RFQ test opens. The ids repeat across sessions on purpose: that is what
     * a venue test looks like, and it is the worst case for the union — every exchange becomes one
     * component spanning all eight sessions rather than eight small ones — and the worst case for the sort,
     * since every session stamps the same timestamps and the same `SenderCompID`, so the whole merge falls
     * through to the `(session, index)` tiebreak.
     */
    @Test
    fun `tracing eight sessions of a thousand messages under the drain tick`() {
        val snapshots = List(8) { Corpus.rfqFlow(1_000) }

        val result =
            Bench.measure("Traces.group over 8 x 1,000 messages", ops = 5) {
                Traces.group(snapshots, dictionary)
            }

        println("\n┌─ Cross-session trace rebuild over 8 x 1,000 messages")
        println("│  " + result.render())
        println("│  → %.1f ms per rebuild, against a 100 ms drain tick".format(result.nanosPerOp / 1_000_000.0))
        println("└─\n")

        val grouping = Traces.group(snapshots, dictionary)
        assertEquals(8_000, grouping.total, "nothing may be dropped or double-counted across sessions")
        assertEquals(200, grouping.traces.size, "1,000 messages of a 5-message flow is 200 exchanges, shared by all 8")
        grouping.traces.forEach { trace ->
            assertEquals(40, trace.members.size, "each exchange's five messages, on every one of the eight sessions")
            assertEquals((0..7).toList(), trace.sessions)
        }

        // Allocation, not the stopwatch, for the reason Bench's own KDoc gives. The figure to protect is
        // that this stays proportional to the messages merged — roughly what one pane's grouped rebuild
        // costs, times eight — rather than to the traces times the sessions. Measured at ~5.6 MB and
        // ~8 ms, comfortably inside a 100 ms tick with the eight pane rebuilds beside it.
        assertTrue(
            result.bytesPerOp < 9_000_000,
            "tracing 8,000 messages should cost about eight panes' worth; got ${result.bytesPerOp} B",
        )
    }

    /**
     * **What the Ledger costs on top of the grouping it draws** — eight panes' worth of rows, rebuilt.
     *
     * `TraceRows.build` sits behind a `remember` keyed on the index and the fold set, so it runs when
     * the grouping changes, which during live traffic is every drain tick. The budget is the same one
     * `Traces.group` has: finish inside the 100 ms tick with room for eight pane rebuilds beside it.
     *
     * The two cases below are the two ends of the panel's own range, and the gap between them is the
     * point of collapsing by default. **Collapsed** is what a reader actually opens the panel to — 200
     * header rows and the summary behind each — and it is what the fold buys: no member rows at all.
     * **Everything expanded** is what "Expand all" costs, 8,000 rows of it, and is the worst case a
     * reader can ask for. Summarising is the shared floor: `Conversations.summarize` walks every message
     * of every trace either way, which is why the two are much closer than 200 rows against 8,200.
     */
    @Test
    fun `building ledger rows over eight sessions of a thousand messages`() {
        val snapshots = List(8) { Corpus.rfqFlow(1_000) }
        val titles = List(8) { "SESSION-$it" }
        val grouping = Traces.group(snapshots, dictionary)
        val allKeys = grouping.traces.map { TraceRows.keyOf(it, titles) }.toSet()

        val collapsed =
            Bench.measure("TraceRows.build, all collapsed (the panel as it opens)", ops = 5) {
                TraceRows.build(snapshots, titles, grouping, dictionary)
            }
        val expanded =
            Bench.measure("TraceRows.build, everything expanded (Expand all)", ops = 5) {
                TraceRows.build(snapshots, titles, grouping, dictionary, expanded = allKeys, ungroupedExpanded = true)
            }

        println("\n┌─ Ledger row rebuild over 8 x 1,000 messages")
        println("│  " + collapsed.render())
        println("│  " + expanded.render())
        val collapsedMs = collapsed.nanosPerOp / 1_000_000.0
        val expandedMs = expanded.nanosPerOp / 1_000_000.0
        println("│  → %.1f ms collapsed / %.1f ms expanded, against a 100 ms drain tick".format(collapsedMs, expandedMs))
        println("└─\n")

        assertEquals(200, TraceRows.build(snapshots, titles, grouping, dictionary).size, "one header per exchange")
        assertEquals(
            8_200,
            TraceRows.build(snapshots, titles, grouping, dictionary, expanded = allKeys, ungroupedExpanded = true).size,
            "200 headers and every one of the 8,000 messages — nothing dropped when everything is open",
        )

        // Allocation, not the stopwatch, for the reason Bench's own KDoc gives. The figure to protect is
        // that a fully expanded Ledger stays proportional to the messages it draws — one small row object
        // each — rather than to traces times sessions, and that it never goes back to re-parsing the
        // messages it summarises. Measured at ~0.55 MB and ~0.7 ms, an order of magnitude under the
        // grouping it renders (~5.6 MB, ~6 ms), because it reads the fields that pass already cached.
        assertTrue(
            expanded.bytesPerOp < 2_500_000,
            "8,000 rows should cost a small object each, not a re-parse; got ${expanded.bytesPerOp} B",
        )
    }

    /**
     * **What Lanes costs to lay out** — one followed trace of a thousand messages, over eight panes.
     *
     * `TraceLanes.build` sits behind a `remember` keyed on the index, so during live traffic it runs on
     * the same 100 ms drain tick the Ledger's rows do, and the budget is the same: finish inside the
     * tick with eight pane rebuilds beside it.
     *
     * The corpus is the shape that makes pairing work hardest, which is also the shape Lanes exists for.
     * Every pane holds the *same* fill stream against one order — so all 1,000 messages are one trace
     * across all eight sessions, and every message on an OUT pane has a byte-identical twin on each of
     * the four IN panes. That is the worst case for the same-bytes index: one bucket per distinct
     * message body, every candidate in it live, and half the trace looking for a partner.
     *
     * The figure to protect is that this stays proportional to the messages laid out — one small row
     * object each, plus the index — rather than to messages times candidates, which is what a linear
     * scan per arrival would have cost.
     */
    @Test
    fun `laying out lanes over a thousand-message trace on eight sessions`() {
        // Half the panes send it and half receive it: a both-sides test at eight panes, which is what
        // makes every message a pairing candidate rather than a row on its own.
        val snapshots =
            List(8) { session ->
                Corpus.fillStream(125, order = "ORD-1").map { message ->
                    if (session % 2 == 0) message.copy(direction = FixMessage.Direction.OUTGOING) else message
                }
            }
        val titles = List(8) { "SESSION-$it" }
        val roles = List(8) { if (it % 2 == 0) LaneRole.INITIATOR else LaneRole.ACCEPTOR }
        val trace = Traces.group(snapshots, dictionary).traces.single()
        assertEquals(1_000, trace.members.size, "one order's fills on eight panes is one trace of a thousand")

        val result =
            Bench.measure("TraceLanes.build over a 1,000-message 8-session trace", ops = 5) {
                TraceLanes.build(trace, snapshots, titles, roles)
            }

        val lanes = TraceLanes.build(trace, snapshots, titles, roles)
        println("\n┌─ Lanes layout over a 1,000-message trace on 8 sessions")
        println("│  " + result.render())
        println("│  → %.1f ms per layout, against a 100 ms drain tick".format(result.nanosPerOp / 1_000_000.0))
        println("│  → %d lanes, %d rows, %d of them same-bytes hops".format(lanes.lanes.size, lanes.rows.size, lanes.rows.count { it.paired }))
        println("└─\n")

        assertEquals(8, lanes.lanes.size, "one column per pane the trace touched")
        assertEquals(
            trace.members.size,
            lanes.rows.sumOf { if (it.paired) 2 else 1 },
            "every message drawn exactly once — a hop is one row with two ends, never a dropped message",
        )

        // Allocation, not the stopwatch, for the reason Bench's own KDoc gives. Measured at ~0.14 MB and
        // ~0.4 ms — an order of magnitude under the grouping it draws from, because it reads only each
        // message's timestamp, direction and bytes and never its fields. The pin is what stops a return
        // to scanning every pending candidate per arrival, which showed up here as the allocation of the
        // index times the messages rather than beside them.
        assertTrue(
            result.bytesPerOp < 600_000,
            "laying out 1,000 messages should cost a small object each; got ${result.bytesPerOp} B",
        )
    }

    /**
     * The correctness half. A cache that returns a different answer than the parse it replaced would
     * turn every grouped view into a quiet lie, so the two are asserted to agree field for field —
     * including on a message with repeating groups, where wire order is the whole point.
     */
    @Test
    fun `the cached field list is identical to a fresh parse, order included`() {
        val messages = Corpus.rfqFlow(20) + Corpus.marketDataSnapshot(entries = 50)

        messages.forEach { message ->
            val fresh = FixMessageHelper.parseFixMessage(message.wireRaw!!, FixMessageHelper.SOH)
            val cached = FixMessageHelper.fieldsForDisplay(message)
            assertEquals(fresh, cached, "cached fields must equal a fresh parse, in wire order")
            // And repeated reads must keep agreeing — a lazy that memoised a mutable list could not.
            assertEquals(cached, FixMessageHelper.fieldsForDisplay(message), "repeat reads must be stable")
        }
    }

    /**
     * The grouping's own output must not change: same conversations, same labels, same membership. The
     * cache is an implementation detail of how the fields are read, and nothing above it may notice.
     */
    @Test
    fun `grouping still finds the same conversations`() {
        val messages = Corpus.rfqFlow(50)
        val grouping = Conversations.group(messages, dictionary)

        assertEquals(10, grouping.conversations.size, "50 messages of a 5-message RFQ flow is 10 exchanges")
        assertTrue(grouping.ungrouped.isEmpty(), "every message in the corpus carries a correlation id")
        grouping.conversations.forEach { conversation ->
            assertEquals(5, conversation.messages.size, "each RFQ exchange has request, quote, order, 2 fills")
            assertTrue(conversation.label.startsWith("RFQ-"), "the opener's id labels the exchange")
        }
        assertEquals(messages.size, grouping.total, "nothing may be dropped or double-counted")
    }
}
