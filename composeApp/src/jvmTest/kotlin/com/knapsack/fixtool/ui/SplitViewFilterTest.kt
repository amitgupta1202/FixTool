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
 * Behaviour of a pane's own message filter.
 *
 * The filter was reshaped so the regex is compiled once and the message-type list split once, per
 * filter change, instead of once per message. These tests exist to show that reshaping did not move
 * the behaviour — in particular the two permissive cases, where a blank *or invalid* pattern must
 * admit every message rather than blanking the view mid-keystroke.
 */
class SplitViewFilterTest {
    // These pin the pane half of MessageFilters, which is where the split view's filter moved to when
    // the TABS layout started using it too. See MessageFiltersTest for the global and follow halves.

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

    private fun filter(
        regex: String = "",
        incoming: Boolean = true,
        outgoing: Boolean = true,
        separators: Boolean = true,
        types: String = "",
    ) = MessageFilters.apply(
        all,
        MessageFilters.Pane(
            regex = regex,
            showIncoming = incoming,
            showOutgoing = outgoing,
            showSeparator = separators,
            messageTypes = types,
        ),
    )

    @Test
    fun `no filters admits everything`() {
        assertEquals(all, filter())
    }

    @Test
    fun `blank regex admits every message`() {
        assertEquals(all, filter(regex = "   "))
    }

    /** A half-typed pattern like "ORD[" must not blank the view. */
    @Test
    fun `invalid regex admits every message rather than blanking the view`() {
        assertEquals(all, filter(regex = "ORD["))
    }

    @Test
    fun `regex matches against the display string`() {
        val result = filter(regex = "ALPHA")
        assertEquals(listOf(incomingOrder), result.filterIsInstance<FixMessage>())
    }

    @Test
    fun `regex is case insensitive`() {
        assertEquals(listOf(incomingOrder), filter(regex = "alpha").filterIsInstance<FixMessage>())
    }

    @Test
    fun `direction filters exclude the unwanted side`() {
        assertEquals(listOf(outgoingReport), filter(incoming = false).filterIsInstance<FixMessage>())
        assertEquals(listOf(incomingOrder), filter(outgoing = false).filterIsInstance<FixMessage>())
    }

    /** Separators answer only to their own toggle, whatever the other filters say. */
    @Test
    fun `separator visibility is independent of the other filters`() {
        assertTrue(filter(separators = false).none { it is Separator })
        assertTrue(
            filter(regex = "NOTHINGMATCHES", separators = true).any { it is Separator },
            "a separator should survive a regex that matches no message",
        )
    }

    @Test
    fun `message type filter accepts a comma separated list with padding`() {
        val result = filter(types = " D , 8 ").filterIsInstance<FixMessage>()
        assertEquals(listOf(incomingOrder, outgoingReport), result)
    }

    @Test
    fun `message type filter selects a single type`() {
        assertEquals(listOf(incomingOrder), filter(types = "D").filterIsInstance<FixMessage>())
    }

    @Test
    fun `message type filter is case insensitive`() {
        assertEquals(listOf(incomingOrder), filter(types = "d").filterIsInstance<FixMessage>())
    }

    /** Commas and whitespace alone leave nothing to match on, so nothing is excluded. */
    @Test
    fun `message type filter of only separators admits every message`() {
        assertEquals(all, filter(types = " , , "))
    }

    @Test
    fun `filters compose`() {
        val result = filter(regex = "BETA", types = "8", separators = false)
        assertEquals(listOf(outgoingReport), result)
    }
}
