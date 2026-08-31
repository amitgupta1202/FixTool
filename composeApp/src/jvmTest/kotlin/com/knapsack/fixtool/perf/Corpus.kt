package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.FixMessageHelper.SOH
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import java.time.LocalDateTime

/**
 * **The traffic the benchmarks run against** — shaped like the sessions this tool is actually pointed at,
 * because a benchmark over toy messages measures the wrong thing.
 *
 * Two properties matter and neither is size for its own sake:
 *
 * - **Correlation ids that chain.** The conversation grouping under test joins messages into business
 *   exchanges by shared id *values*, so a corpus of unrelated messages would put every message in its
 *   own conversation and never exercise the union-find, the summaries, or the repeated field reads that
 *   the audit found. [rfqFlow] mints an RFQ per exchange and echoes its ids forward exactly as a venue
 *   does: QuoteReqID → QuoteID → ClOrdID → ExecID.
 *
 * - **Repeating groups.** Half the superlinear findings only bite on messages with many entries, which on
 *   a real desk means market data, not orders. [marketDataSnapshot] carries a configurable entry count so
 *   a test can ask for the 500-entry snapshot that turns an O(n³) path from theoretical into a freeze.
 *
 * Messages are built SOH-delimited and carry [FixMessage.wireRaw], because that is what the venue path
 * produces and what the assertion engine reads. A corpus built with `|` would quietly route every test
 * through the display-string fallback and measure a path production never takes.
 */
object Corpus {
    private val EPOCH: LocalDateTime = LocalDateTime.of(2026, 8, 30, 9, 30, 0)

    /**
     * A full RFQ lifecycle for one exchange: request, quote, order, two fills.
     *
     * Five messages that all belong to one conversation, chained the way [com.knapsack.fixtool.service.Conversations]
     * discovers them — the Quote carries both the QuoteReqID and its own QuoteID, which is the edge that
     * makes the chain transitive.
     */
    fun rfqFlow(n: Int): List<FixMessage> {
        val out = ArrayList<FixMessage>(n * 5)
        var seq = 1
        var exchange = 0
        while (out.size < n) {
            val rfq = "RFQ-$exchange"
            val quote = "Q-$exchange"
            val order = "ORD-$exchange"
            val exec = "EX-$exchange"
            val sym = SYMBOLS[exchange % SYMBOLS.size]

            out += msg(seq++, "R", inbound = false, fields = listOf(131 to rfq, 55 to sym, 38 to "100000", 54 to "1"))
            out += msg(seq++, "S", inbound = true, fields = listOf(117 to quote, 131 to rfq, 55 to sym, 132 to "101.25", 133 to "101.75"))
            out += msg(seq++, "D", inbound = false, fields = listOf(11 to order, 117 to quote, 55 to sym, 38 to "100000", 44 to "101.75"))
            out +=
                msg(
                    seq++,
                    "8",
                    inbound = true,
                    fields =
                        listOf(
                            37 to exec,
                            11 to order,
                            17 to "$exec-1",
                            150 to "1",
                            39 to "1",
                            55 to sym,
                            32 to "40000",
                            31 to "101.75",
                            14 to "40000",
                        ),
                )
            out +=
                msg(
                    seq++,
                    "8",
                    inbound = true,
                    fields =
                        listOf(
                            37 to exec,
                            11 to order,
                            17 to "$exec-2",
                            150 to "2",
                            39 to "2",
                            55 to sym,
                            32 to "60000",
                            31 to "101.75",
                            14 to "100000",
                        ),
                )
            exchange++
        }
        return out.take(n)
    }

    /**
     * One MarketDataSnapshotFullRefresh carrying [entries] repeating-group entries.
     *
     * The shape that makes the reconcile and diff findings reachable: a 500-entry snapshot is a message
     * with a couple of thousand fields, and the paths that enumerate contiguous runs over it are the ones
     * that go from milliseconds to minutes.
     */
    fun marketDataSnapshot(entries: Int, reqId: String = "MD-1"): FixMessage {
        val body = ArrayList<Pair<Int, String>>(entries * 4 + 4)
        body += 262 to reqId
        body += 55 to "EUR/USD"
        body += 268 to entries.toString()
        repeat(entries) { i ->
            body += 269 to if (i % 2 == 0) "0" else "1"
            body += 270 to "%.5f".format(1.08000 + i * 0.00001)
            body += 271 to ((i + 1) * 1_000_000).toString()
            body += 272 to "20260830"
        }
        return msg(1, "W", inbound = true, fields = body)
    }

    /**
     * [count] execution reports against one order — the trail that makes an append quadratic.
     *
     * A single working order taking many partial fills is the ordinary shape of an algo or a
     * market-making session, not a stress case invented for the test.
     */
    fun fillStream(count: Int, order: String = "ORD-1"): List<FixMessage> =
        (1..count).map { i ->
            msg(
                i,
                "8",
                inbound = true,
                fields =
                    listOf(
                        37 to "EXEC-1",
                        11 to order,
                        17 to "FILL-$i",
                        150 to "1",
                        39 to "1",
                        55 to "EUR/USD",
                        32 to "100",
                        31 to "1.0850",
                        14 to (i * 100).toString(),
                    ),
            )
        }

    private val SYMBOLS = listOf("EUR/USD", "GBP/USD", "USD/JPY", "AUD/USD", "USD/CHF")

    private fun msg(
        seq: Int,
        type: String,
        inbound: Boolean,
        fields: List<Pair<Int, String>>,
    ): FixMessage {
        val header =
            listOf(
                35 to type,
                49 to if (inbound) "VENUE" else "CLIENT",
                56 to if (inbound) "CLIENT" else "VENUE",
                34 to seq.toString(),
                52 to "20260830-09:30:00.000",
            )
        val wire = (header + fields).joinToString("") { (t, v) -> "$t=$v$SOH" }
        val full = "8=FIX.4.4${SOH}9=${wire.length}$SOH$wire" + "10=000$SOH"
        return FixMessage(
            timestamp = EPOCH.plusNanos(seq * 1_000_000L),
            direction = if (inbound) FixMessage.Direction.INCOMING else FixMessage.Direction.OUTGOING,
            rawMessage = full.replace(SOH, '|'),
            messageType = type,
            quickfixMessage = runCatching { full.toQuickFixMessage() }.getOrElse { quickfix.Message() },
            wireRaw = full,
        )
    }
}
