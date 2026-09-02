package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import org.junit.Test
import quickfix.Message
import quickfix.field.ClOrdID
import quickfix.field.MsgSeqNum
import quickfix.field.MsgType
import quickfix.field.OrderID
import quickfix.field.QuoteReqID
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Follow is: one app-level set of messages, spanning every pane, that grows as the exchange runs.
 *
 * These drive [TraceFollow] directly rather than through the ViewModel's 100 ms ticker, because the
 * questions here are about the *set* — which messages, from which panes, after which arrivals — and a
 * test that answers them by sleeping is a test that answers them differently on a loaded machine.
 */
class TraceFollowTest {
    private var seq = 0

    private fun message(
        type: String,
        vararg ids: Pair<Int, String>,
    ): FixMessage {
        seq++
        val quickfix =
            Message().apply {
                header.setField(MsgType(type))
                header.setField(MsgSeqNum(seq))
                ids.forEach { (tag, value) ->
                    when (tag) {
                        11 -> setField(ClOrdID(value))
                        37 -> setField(OrderID(value))
                        131 -> setField(QuoteReqID(value))
                        else -> error("unmapped tag $tag")
                    }
                }
            }
        return FixMessage(
            // Distinct, ascending timestamps so the merged order is the order they were built in.
            timestamp = LocalDateTime.of(2026, 9, 2, 10, 0, 0).plusSeconds(seq.toLong()),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = "8=FIX.4.4|35=$type|" + ids.joinToString("") { "${it.first}=${it.second}|" },
            messageType = type,
            quickfixMessage = quickfix,
        )
    }

    private fun input(
        title: String,
        vararg messages: AppMessage,
    ) = TraceFollow.Input(title, messages.toList())

    private val dictionary: FixDictionaryAdapter = FixDictionaryAdapter.createDefault()

    @Test
    fun `the followed set spans two sessions joined by a bridging id`() {
        // The client asked with RFQ-A1; the venue's copy on LP-1 carries RFQ-A1 and mints Q-77.
        val clientRequest = message("R", 131 to "RFQ-A1")
        val lpRequest = message("R", 131 to "RFQ-A1", 11 to "Q-77")
        val unrelated = message("R", 131 to "RFQ-B9")

        val follow = TraceFollow()
        follow.follow("RFQ-A1")
        follow.refresh(listOf(input("client", clientRequest, unrelated), input("lp1", lpRequest)), dictionary)

        val followed = requireNotNull(follow.followedTrace.value)
        assertEquals(setOf(clientRequest.uid, lpRequest.uid), followed.uids)
        assertEquals(2, followed.sessionCount)
        assertEquals(2, followed.messageCount)
        assertEquals("RFQ-A1", followed.label)
        assertFalse(unrelated.uid in followed.uids, "a different exchange is not in the set")
    }

    @Test
    fun `a message arriving later on a third session joins the followed set`() {
        val clientRequest = message("R", 131 to "RFQ-A1")
        val lpRequest = message("R", 131 to "RFQ-A1", 11 to "Q-77")

        val follow = TraceFollow()
        follow.follow("RFQ-A1")
        follow.refresh(listOf(input("client", clientRequest), input("lp1", lpRequest), input("lp2")), dictionary)
        assertEquals(2, requireNotNull(follow.followedTrace.value).messageCount)

        // LP-2 quotes back citing Q-77, which the client never saw. The relation joins it anyway,
        // because Q-77 is a shared value — which is the whole reason the regex was the wrong tool.
        val lpQuote = message("S", 11 to "Q-77", 37 to "V-ORD-8813")
        follow.refresh(
            listOf(input("client", clientRequest), input("lp1", lpRequest), input("lp2", lpQuote)),
            dictionary,
        )

        val followed = requireNotNull(follow.followedTrace.value)
        assertEquals(setOf(clientRequest.uid, lpRequest.uid, lpQuote.uid), followed.uids)
        assertEquals(3, followed.sessionCount)
        assertEquals(3, followed.messageCount)
    }

    @Test
    fun `unfollow clears the followed trace`() {
        val request = message("R", 131 to "RFQ-A1")
        val follow = TraceFollow()
        follow.follow("RFQ-A1")
        follow.refresh(listOf(input("client", request)), dictionary)
        assertTrue(follow.followedTrace.value != null)

        follow.unfollow()
        assertNull(follow.followedTrace.value)
    }

    @Test
    fun `following a second id replaces the first`() {
        val a = message("R", 131 to "RFQ-A1")
        val b = message("R", 131 to "RFQ-B9")
        val follow = TraceFollow()
        val sessions = listOf(input("client", a, b))

        follow.follow("RFQ-A1")
        follow.refresh(sessions, dictionary)
        assertEquals(setOf(a.uid), requireNotNull(follow.followedTrace.value).uids)

        follow.follow("RFQ-B9")
        follow.refresh(sessions, dictionary)
        assertEquals(setOf(b.uid), requireNotNull(follow.followedTrace.value).uids)
    }

    /** An id the venue has not echoed yet is still followed; it just has nothing in it. */
    @Test
    fun `an anchor no trace carries keeps following with an empty set`() {
        val unrelated = message("R", 131 to "RFQ-B9")
        val follow = TraceFollow()
        follow.follow("V-ORD-8813")
        follow.refresh(listOf(input("client", unrelated)), dictionary)

        val followed = requireNotNull(follow.followedTrace.value)
        assertTrue(followed.uids.isEmpty())
        assertEquals(0, followed.sessionCount)
        assertEquals(0, followed.messageCount)
        assertEquals("V-ORD-8813", followed.label, "It names what was asked for, not a trace it has not found")
        assertTrue(followed.pending)
    }

    @Test
    fun `unchanged snapshots do not regroup`() {
        val request = message("R", 131 to "RFQ-A1")
        val sessions = listOf(input("client", request))
        val follow = TraceFollow()
        follow.follow("RFQ-A1")

        follow.refresh(sessions, dictionary)
        val first = follow.traceIndex.value
        val afterFirst = follow.regroupCount

        // Same snapshot objects, ten more ticks: a quiet app must cost nothing.
        repeat(10) { follow.refresh(listOf(TraceFollow.Input("client", sessions[0].messages)), dictionary) }

        assertEquals(afterFirst, follow.regroupCount, "identical snapshots regrouped again")
        assertTrue(first === follow.traceIndex.value, "the index object itself should be untouched")
    }

    @Test
    fun `a changed snapshot regroups`() {
        val request = message("R", 131 to "RFQ-A1")
        val follow = TraceFollow()
        follow.follow("RFQ-A1")
        follow.refresh(listOf(input("client", request)), dictionary)
        val before = follow.regroupCount

        val reply = message("S", 131 to "RFQ-A1", 11 to "Q-77")
        follow.refresh(listOf(input("client", request, reply)), dictionary)

        assertEquals(before + 1, follow.regroupCount)
        assertEquals(2, requireNotNull(follow.followedTrace.value).messageCount)
    }

    /** Nothing followed and the panel shut: the index is not maintained at all. */
    @Test
    fun `nothing wanted means nothing is grouped`() {
        val request = message("R", 131 to "RFQ-A1")
        val follow = TraceFollow()
        follow.refresh(listOf(input("client", request)), dictionary)

        assertEquals(0L, follow.regroupCount)
        assertNull(follow.traceIndex.value)

        // Opening the panel alone is enough to want it, with nothing followed.
        follow.openTracePanel()
        follow.refresh(listOf(input("client", request)), dictionary)
        assertEquals(1L, follow.regroupCount)
        assertNull(follow.followedTrace.value, "the panel being open follows nothing by itself")
    }

    @Test
    fun `closing the panel does not unfollow`() {
        val request = message("R", 131 to "RFQ-A1")
        val follow = TraceFollow()
        follow.follow("RFQ-A1")
        follow.openTracePanel()
        follow.refresh(listOf(input("client", request)), dictionary)

        follow.closeTracePanel()
        follow.refresh(listOf(input("client", request)), dictionary)

        assertFalse(follow.tracePanelOpen.value)
        assertEquals(setOf(request.uid), requireNotNull(follow.followedTrace.value).uids)
    }

    /** A session closed between ticks is simply a shorter list; no position from before is consulted. */
    @Test
    fun `a session closing while following recomputes against the sessions that remain`() {
        val clientRequest = message("R", 131 to "RFQ-A1")
        val lpRequest = message("R", 131 to "RFQ-A1", 11 to "Q-77")
        val follow = TraceFollow()
        follow.follow("RFQ-A1")
        follow.refresh(listOf(input("client", clientRequest), input("lp1", lpRequest)), dictionary)
        assertEquals(2, requireNotNull(follow.followedTrace.value).sessionCount)

        follow.refresh(listOf(input("lp1", lpRequest)), dictionary)

        val followed = requireNotNull(follow.followedTrace.value)
        assertEquals(setOf(lpRequest.uid), followed.uids)
        assertEquals(1, followed.sessionCount)
    }

    /** The chip has to name the pane, so the titles are resolved where the indices were computed. */
    @Test
    fun `a trace that lost history names the sessions it lost it on`() {
        val request = message("R", 131 to "RFQ-A1", 11 to "Q-77")
        val follow = TraceFollow()
        follow.follow("RFQ-A1")
        follow.refresh(
            listOf(
                TraceFollow.Input("client", listOf(request), lostIds = setOf("RFQ-A1")),
                TraceFollow.Input("lp1", emptyList(), lostIds = setOf("SOMETHING-ELSE")),
            ),
            dictionary,
        )

        val followed = requireNotNull(follow.followedTrace.value)
        assertEquals(listOf(0), followed.truncatedSessions)
        assertEquals(listOf("client"), followed.truncatedSessionTitles)
    }
}
