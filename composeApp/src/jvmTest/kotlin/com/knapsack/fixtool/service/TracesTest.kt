package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **One exchange followed across every pane** — the job a QA does today by reading ids off four grids
 * and typing `RFQ-A1|V-2291|Q-77` into a filter box.
 *
 * The flow under test is the one that motivates it: a venue that mints its own id on the way through, so
 * the client calls the exchange `RFQ-A1`, both LPs call it `V-2291`, and the only thing connecting them
 * is the `QuoteID` the winning LP echoed back. No rule anywhere names that path; the shared value is the
 * whole edge.
 */
class TracesTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val out = FixMessage.Direction.OUTGOING

    private val epoch = LocalDateTime.of(2026, 9, 2, 10, 0, 0)

    /** A message at [millis] past the epoch, so a test states the merged order it expects. */
    private fun at(
        millis: Long,
        raw: String,
        dir: FixMessage.Direction = FixMessage.Direction.INCOMING,
    ): FixMessage =
        FixMessage(
            timestamp = epoch.plusNanos(millis * 1_000_000L),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
            // The named constant, not a literal SOH: an invisible control character in source is a hazard.
            wireRaw = raw.replace('|', FixMessageHelper.SOH),
        )

    /**
     * The venue's shape: one RFQ, three panes, two ids, and a quote that bridges them.
     *
     * Session 0 is the client FixTool sends the request from. Sessions 1 and 2 are LPs receiving the
     * venue's forwarded copy under the venue's own `QuoteReqID`. Only LP-1 quotes, and its `QuoteID` is
     * echoed to the client — which is the single value that makes all three panes one exchange.
     */
    private fun venue(): List<List<FixMessage>> =
        listOf(
            listOf(
                at(0, "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|", out),
                at(40, "35=S|131=RFQ-A1|117=Q-77|"),
            ),
            listOf(
                at(10, "35=R|131=V-2291|55=EUR/USD|38=10000000|"),
                at(30, "35=S|131=V-2291|117=Q-77|", out),
            ),
            listOf(
                at(11, "35=R|131=V-2291|55=EUR/USD|38=10000000|"),
            ),
        )

    /**
     * **The join the regex in the search box was standing in for.** Nothing in the client's messages
     * mentions `V-2291` and nothing in LP-2's mentions `RFQ-A1`; the quote carrying `Q-77` on one pane and
     * `131=V-2291` on another is what puts all three in one trace.
     */
    @Test
    fun `a venue's own id joins the client's exchange to every LP that saw it`() {
        val snapshots = venue()
        val grouping = Traces.group(snapshots, dictionary)

        val trace = grouping.traces.single()
        assertEquals("RFQ-A1", trace.label, "named by the first id on the earliest message in merged order")
        assertEquals(131, trace.labelTag)
        assertEquals(setOf("RFQ-A1", "V-2291", "Q-77"), trace.ids)
        assertEquals(listOf(0, 1, 2), trace.sessions, "in the order the trace first appeared on each")
        assertEquals(
            listOf(Located(0, 0), Located(1, 0), Located(2, 0), Located(1, 1), Located(0, 1)),
            trace.members,
            "members are in merged time order, not per-session order",
        )
        assertEquals(grouping.order, trace.members, "every message here belongs to the one trace")
        assertEquals(trace.members, trace.positions.map { grouping.order[it] }, "positions address [order]")
        assertEquals(snapshots.sumOf { it.size }, grouping.total, "every message must land somewhere")
    }

    /** Two exchanges that share no value are two exchanges, however close together they ran. */
    @Test
    fun `flows that share no value stay separate traces`() {
        val grouping =
            Traces.group(
                listOf(
                    listOf(at(0, "35=R|131=RFQ-A1|", out), at(20, "35=S|131=RFQ-A1|117=Q-77|")),
                    listOf(at(1, "35=R|131=RFQ-B2|", out), at(21, "35=S|131=RFQ-B2|117=Q-88|")),
                ),
                dictionary,
            )

        assertEquals(listOf("RFQ-A1", "RFQ-B2"), grouping.traces.map { it.label }, "in the order they opened")
        grouping.traces.forEach { assertEquals(1, it.sessions.size, "${it.label} touched one session") }
    }

    // ---- the invariant: a pane's conversation is its trace's slice ---------------------------------

    /** One session's log, in arrival order, with a heartbeat and a second RFQ mixed through it. */
    private fun oneSession(): List<FixMessage> =
        listOf(
            at(0, "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|", out),
            at(1, "35=0|"),
            at(2, "35=R|131=RFQ-B2|55=GBP/USD|38=5000000|", out),
            at(3, "35=S|117=Q-77|131=RFQ-A1|"),
            at(4, "35=S|117=Q-78|131=RFQ-B2|"),
            at(5, "35=D|11=ORD-9|117=Q-77|", out),
            at(6, "35=8|37=V-551|11=ORD-9|39=0|"),
            at(7, "35=A|98=0|108=30|"),
            at(8, "35=8|37=V-551|11=ORD-9|39=2|"),
        )

    /**
     * **The invariant the whole feature rests on**: over one session, the trace grouping and the
     * conversation grouping are the same answer — same components, same labels, same members, same order,
     * same residue. They share [CorrelationComponents] precisely so this cannot come apart, and a reader
     * who saw a pane and the Trace panel disagree would have no way to tell which was lying.
     */
    @Test
    fun `over one session a trace is exactly that session's conversation`() {
        val session = oneSession()
        val traces = Traces.group(listOf(session), dictionary)
        val conversations = Conversations.group(session, dictionary)

        assertEquals(conversations.conversations.map { it.label }, traces.traces.map { it.label })
        assertEquals(conversations.conversations.map { it.labelTag }, traces.traces.map { it.labelTag })
        assertEquals(conversations.conversations.map { it.ids }, traces.traces.map { it.ids })
        assertEquals(
            conversations.conversations.map { it.indices },
            traces.traces.map { trace -> trace.members.map { it.index } },
            "same members, same order",
        )
        assertEquals(
            conversations.ungroupedIndices,
            traces.ungrouped.map { it.index },
            "and the same messages left over",
        )
        assertTrue(traces.traces.all { it.sessions == listOf(0) }, "one snapshot in, one session out")
        assertEquals(conversations.total, traces.total)
    }

    /**
     * The multi-session half of the same invariant: a pane keeps its own conversations, and each one sits
     * whole inside exactly one trace. A conversation split across two traces, or absorbed into none, would
     * mean the two groupings disagree about what shares a value.
     */
    @Test
    fun `every per-session conversation sits whole inside exactly one trace`() {
        val snapshots = venue() + listOf(oneSession())
        val grouping = Traces.group(snapshots, dictionary)

        snapshots.forEachIndexed { session, snapshot ->
            Conversations.group(snapshot, dictionary).conversations.forEach { conversation ->
                val slice = conversation.indices.map { Located(session, it) }.toSet()
                val containing = grouping.traces.filter { it.members.containsAll(slice) }
                assertEquals(
                    1,
                    containing.size,
                    "${conversation.label} on session $session must be inside exactly one trace",
                )
            }
        }
    }

    // ---- merged order ------------------------------------------------------------------------------

    /**
     * **Equal timestamps are the normal case across panes, not the edge**, because a venue forwards
     * within the same millisecond FixTool stamped the original. So the key carries on: `MsgSeqNum(34)`
     * decides next, then `SenderCompID(49)`.
     */
    @Test
    fun `messages sharing a timestamp are ordered by sequence number, then by sender`() {
        val grouping =
            Traces.group(
                listOf(
                    listOf(
                        at(0, "35=D|49=BETA|34=7|11=ORD-1|"),
                        at(0, "35=D|49=ALPHA|34=2|11=ORD-1|"),
                    ),
                    listOf(
                        at(0, "35=D|49=ALPHA|34=7|11=ORD-1|"),
                        at(0, "35=D|49=ALPHA|34=1|11=ORD-1|"),
                    ),
                ),
                dictionary,
            )

        assertEquals(
            listOf(Located(1, 1), Located(0, 1), Located(1, 0), Located(0, 0)),
            grouping.order,
            "seq 1, 2, then the two at seq 7 with ALPHA before BETA",
        )
    }

    /**
     * When the key cannot separate two messages, `(session, index)` does — and it always can, because no
     * two messages share one. A grid whose rows shuffle between two identical rebuilds is a bug report
     * nobody can reproduce.
     */
    @Test
    fun `messages alike in every sorted field still come out in one fixed order`() {
        val identical = "35=D|49=ALPHA|34=1|11=ORD-1|"
        val snapshots = listOf(listOf(at(0, identical), at(0, identical)), listOf(at(0, identical)))

        val once = Traces.group(snapshots, dictionary).order
        assertEquals(listOf(Located(0, 0), Located(0, 1), Located(1, 0)), once)
        assertEquals(once, Traces.group(snapshots, dictionary).order, "and the same order every time")
    }

    // ---- nothing is hidden -------------------------------------------------------------------------

    /**
     * **A logon and a heartbeat carry no correlation id, so they join nothing** — and they are still
     * there, in merged order, and counted. A view that quietly drops what it did not understand is the
     * silent-coverage-loss defect this codebase refuses everywhere else.
     */
    @Test
    fun `heartbeats and logons join nothing and are kept and counted`() {
        val snapshots =
            listOf(
                listOf(at(0, "35=A|98=0|108=30|", out), at(5, "35=R|131=RFQ-A1|", out), at(9, "35=0|")),
                listOf(at(1, "35=A|98=0|108=30|"), at(6, "35=R|131=V-9|117=Q-1|"), at(8, "35=0|", out)),
            )
        val grouping = Traces.group(snapshots, dictionary)

        assertEquals(2, grouping.traces.size, "the admin traffic must not have joined the two flows")
        assertEquals(
            listOf(Located(0, 0), Located(1, 0), Located(1, 2), Located(0, 2)),
            grouping.ungrouped,
            "in merged order, both sessions' admin messages",
        )
        assertEquals(6, grouping.total, "every message must land somewhere")
        assertEquals(2, grouping.sessionCount)
    }

    /** An empty snapshot is a pane nothing has arrived on yet, which is most panes for their first second. */
    @Test
    fun `an empty snapshot is not a special case`() {
        val grouping = Traces.group(listOf(emptyList(), listOf(at(0, "35=R|131=RFQ-A1|", out))), dictionary)

        assertEquals(listOf(1), grouping.traces.single().sessions, "session 0 contributed nothing")
        assertEquals(1, grouping.total)
        assertEquals(0, Traces.group(emptyList(), dictionary).total, "no sessions at all")
        assertEquals(0, Traces.group(listOf(emptyList(), emptyList()), dictionary).total)
    }

    // ---- the truncation flag -----------------------------------------------------------------------

    /**
     * **The flag says a message this trace contained is gone, and it may never say anything else.**
     *
     * A trace whose opener fell out of a session's ring looks, to a reader, exactly like a trace that
     * started later — so the header has to say `opened before the buffer` rather than let the first
     * surviving row impersonate the first message. What it must not do is cry wolf: a trace that lost
     * nothing is never flagged, whatever else the session threw away.
     */
    @Test
    fun `a trace is flagged only when a session lost a message that was in it`() {
        val snapshots =
            listOf(
                listOf(at(0, "35=S|131=RFQ-A1|117=Q-77|"), at(5, "35=D|11=ORD-9|117=Q-77|", out)),
                listOf(at(1, "35=R|131=RFQ-B2|", out)),
            )

        val lostTheOpener =
            Traces.group(snapshots, dictionary, lostIds = listOf(setOf("RFQ-A1"), emptySet()))
        val flagged = lostTheOpener.traces.single { it.label == "RFQ-A1" }
        assertTrue(flagged.truncatedAtHead)
        assertEquals(listOf(0), flagged.truncatedSessions, "session 0 is where the message fell out")
        assertFalse(
            lostTheOpener.traces.single { it.label == "RFQ-B2" }.truncatedAtHead,
            "the other flow lost nothing and must not be marked",
        )

        val lostSomethingElse =
            Traces.group(snapshots, dictionary, lostIds = listOf(setOf("MD-REQ-4"), setOf("ORD-404")))
        assertTrue(
            lostSomethingElse.traces.none { it.truncatedAtHead },
            "values from flows these traces never touched prove nothing about them",
        )

        assertTrue(
            Traces.group(snapshots, dictionary).traces.none { it.truncatedAtHead },
            "a caller with no record of what was lost gets no flags, not guesses",
        )
    }

    /**
     * **What the session actually records when the window overflows**, end to end.
     *
     * Both loss mechanisms drop the *oldest* thing they hold — the retained deque evicts its head at
     * `bufferSize`, and an overflowing ingest queue discards its head to make room — so what a trace
     * loses, it loses at the front. This is the evidence for that claim.
     */
    @Test
    fun `a session remembers the ids of the messages it evicted, and a trace reads them`() =
        runBlocking {
            val session = FixMessageSession(title = "LP-1", bufferSize = 2)
            session.addMessage(at(0, "35=R|131=RFQ-A1|", out))
            session.addMessage(at(10, "35=S|131=RFQ-A1|117=Q-77|"))
            delay(POLL_SETTLE_MS)
            assertTrue(session.lostCorrelationIds.isEmpty(), "a full window has still lost nothing")

            // One more arrival evicts the head — the message that opened the exchange.
            session.addMessage(at(20, "35=D|11=ORD-9|117=Q-77|", out))
            delay(POLL_SETTLE_MS)
            assertEquals(setOf("RFQ-A1"), session.lostCorrelationIds, "the opener's id, and only it")

            val quiet = FixMessageSession(title = "LP-2", bufferSize = 2)
            quiet.addMessage(at(30, "35=R|131=RFQ-B2|", out))
            delay(POLL_SETTLE_MS)

            val snapshots =
                listOf(quiet, session).map { pane -> pane.messages.value.filterIsInstance<FixMessage>() }
            val grouping =
                Traces.group(
                    snapshots,
                    dictionary,
                    lostIds = listOf(quiet.lostCorrelationIds, session.lostCorrelationIds),
                )

            val opened = grouping.traces.single { "Q-77" in it.ids }
            assertTrue(opened.truncatedAtHead, "the surviving quote still carries the evicted opener's id")
            assertEquals(listOf(1), opened.truncatedSessions)
            assertFalse(
                grouping.traces.single { it.label == "RFQ-B2" }.truncatedAtHead,
                "a pane that evicted nothing marks nothing",
            )
        }

    /** A clear is the user restarting the pane, not the tool losing history behind their back. */
    @Test
    fun `clearing a session forgets what it lost`() =
        runBlocking {
            val session = FixMessageSession(title = "T", bufferSize = 1)
            session.addMessage(at(0, "35=D|11=ORD-1|", out))
            session.addMessage(at(1, "35=D|11=ORD-2|", out))
            delay(POLL_SETTLE_MS)
            assertEquals(setOf("ORD-1"), session.lostCorrelationIds)

            session.clearMessages()
            assertTrue(session.lostCorrelationIds.isEmpty(), "a replayed scenario reusing ORD-1 is not truncated")
        }

    // ---- the header ---------------------------------------------------------------------------------

    /**
     * A trace summarises exactly as a conversation does — the same quotation-not-inference rule, the same
     * function — plus the one fact only a trace has: how many sessions it crossed.
     */
    @Test
    fun `a trace's header is the conversation header plus a session count`() {
        val snapshots = venue()
        val summary = Traces.summarize(Traces.group(snapshots, dictionary).traces.single(), snapshots, dictionary)

        assertEquals("RFQ-A1", summary.exchange.label)
        assertEquals(5, summary.exchange.messageCount)
        assertEquals(listOf("R" to 3, "S" to 2), summary.exchange.composition.map { it.messageType to it.count })
        assertEquals("EUR/USD", summary.exchange.instrument, "read off the opening message")
        assertEquals(40L, summary.exchange.elapsedMillis, "first to last across all three panes")
        assertEquals(3, summary.sessionCount)
    }

    private companion object {
        /** The session drains its queue on a 100ms poll; give it room to settle. */
        const val POLL_SETTLE_MS = 300L
    }
}
