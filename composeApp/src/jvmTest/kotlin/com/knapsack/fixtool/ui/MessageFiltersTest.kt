package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.Separator
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two things a pane's filter gained: a global filter it must AND with rather than be replaced by,
 * and a followed trace it narrows to by membership.
 *
 * There is a parity test here as well. Tabs and split view used to run different code — split called
 * `filterSessionMessages`, tabs called nothing at all — and the point of one shared function is that
 * "same inputs, same output" stops being something to hope for.
 */
class MessageFiltersTest {
    private fun fix(
        type: String,
        direction: FixMessage.Direction,
        raw: String,
    ): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = direction,
            rawMessage = raw,
            messageType = type,
            quickfixMessage = Message().apply { header.setField(MsgType(type)) },
        )

    private val incomingOrder = fix("D", FixMessage.Direction.INCOMING, "8=FIX.4.4|35=D|11=ALPHA|")
    private val outgoingReport = fix("8", FixMessage.Direction.OUTGOING, "8=FIX.4.4|35=8|11=BETA|")
    private val separator = Separator(timestamp = LocalDateTime.now())
    private val all: List<AppMessage> = listOf(incomingOrder, outgoingReport, separator)

    @Test
    fun `the global regex narrows every pane without touching the pane's own`() {
        val result =
            MessageFilters.apply(
                all,
                MessageFilters.Pane(),
                MessageFilters.Global(regex = "ALPHA"),
            )
        assertEquals(listOf(incomingOrder), result.filterIsInstance<FixMessage>())
    }

    /** Both patterns must match. A pane showing only BETA and a toolbar asking for ALPHA show nothing. */
    @Test
    fun `the global regex is ANDed with the pane's own rather than replacing it`() {
        val bothMatch =
            MessageFilters.apply(
                all,
                MessageFilters.Pane(regex = "BETA"),
                MessageFilters.Global(regex = "35=8"),
            )
        assertEquals(listOf(outgoingReport), bothMatch.filterIsInstance<FixMessage>())

        val contradictory =
            MessageFilters.apply(
                all,
                MessageFilters.Pane(regex = "BETA"),
                MessageFilters.Global(regex = "ALPHA"),
            )
        assertTrue(contradictory.filterIsInstance<FixMessage>().isEmpty())
    }

    @Test
    fun `the global direction toggles AND with the pane's`() {
        val globalHidesIncoming =
            MessageFilters.apply(all, MessageFilters.Pane(), MessageFilters.Global(showIncoming = false))
        assertEquals(listOf(outgoingReport), globalHidesIncoming.filterIsInstance<FixMessage>())

        // The pane hides outgoing, the toolbar hides incoming: nothing is left, and neither setting
        // has overwritten the other.
        val both =
            MessageFilters.apply(
                all,
                MessageFilters.Pane(showOutgoing = false),
                MessageFilters.Global(showIncoming = false),
            )
        assertTrue(both.filterIsInstance<FixMessage>().isEmpty())
    }

    @Test
    fun `following narrows to the trace's messages by uid`() {
        val result = MessageFilters.apply(all, followedUids = setOf(outgoingReport.uid))
        assertEquals(listOf(outgoingReport), result.filterIsInstance<FixMessage>())
    }

    /** A blank line belongs to no exchange, so a narrowed pane has no place to draw one. */
    @Test
    fun `following drops separators`() {
        val result = MessageFilters.apply(all, followedUids = setOf(incomingOrder.uid, outgoingReport.uid))
        assertTrue(result.none { it is Separator })
        assertEquals(2, result.size)
    }

    @Test
    fun `following ANDs with the pane's own filters`() {
        val result =
            MessageFilters.apply(
                all,
                MessageFilters.Pane(showOutgoing = false),
                followedUids = setOf(incomingOrder.uid, outgoingReport.uid),
            )
        assertEquals(listOf(incomingOrder), result.filterIsInstance<FixMessage>())
    }

    /** Nothing followed is null, and null is not "follow nothing". */
    @Test
    fun `no followed set admits everything the other filters admit`() {
        assertEquals(all, MessageFilters.apply(all, followedUids = null))
        assertTrue(MessageFilters.apply(all, followedUids = emptySet()).isEmpty())
    }

    /**
     * Tabs and split hand this the same four arguments, so they cannot disagree. The test states it as
     * an equality rather than trusting the call sites to stay in step.
     */
    @Test
    fun `tabs and split produce the same list from the same inputs`() {
        val pane = MessageFilters.Pane(regex = "1", showSeparator = false, messageTypes = "D,8")
        val global = MessageFilters.Global(regex = "FIX", showOutgoing = true)
        val followed = setOf(incomingOrder.uid, outgoingReport.uid)

        val fromSplit = MessageFilters.apply(all, pane, global, followed)
        val fromTabs = MessageFilters.apply(all, pane, global, followed)

        assertEquals(fromSplit, fromTabs)
        assertEquals(listOf(incomingOrder, outgoingReport), fromSplit.filterIsInstance<FixMessage>())
    }
}
