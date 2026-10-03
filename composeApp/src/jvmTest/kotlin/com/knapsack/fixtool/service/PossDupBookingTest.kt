package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.model.QuoteConstraint
import org.junit.After
import org.junit.Test
import quickfix.Message
import quickfix.SessionID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A message the venue resends is shown, and booked into nothing.**
 *
 * QuickFIX/J answers a ResendRequest by passing every application message it replays through `toApp` again,
 * with PossDupFlag(43)=Y. The books are fed from `toApp`, so a replayed ack used to rewind a filled order to
 * working with nothing filled, and a replayed quote re-opened a quote the client had already hit. Each of
 * those is a fact the venue reported once, and its replay says nothing new.
 */
class PossDupBookingTest {
    private val sessionId = SessionID("FIX.4.4", "VENUE", "CLIENT")
    private val shown = mutableListOf<FixMessage>()

    private val venue =
        QuickFixService(
            config =
                FixConnectionConfig(
                    connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                    senderCompID = "VENUE",
                    targetCompID = "CLIENT",
                ),
            dictionary = FixDictionaryAdapter.createDefault(),
            onMessageReceived = { shown += it },
            onStateChanged = {},
        ).apply { onCreate(sessionId) }

    @After
    fun tearDown() {
        venue.shutdown()
    }

    private fun message(raw: String): Message = AcceptorResponder.buildMessage(raw)

    /** [raw] as QuickFIX/J replays it: PossDupFlag and OrigSendingTime set on the header. */
    private fun resent(raw: String): Message =
        message(raw).apply {
            header.setBoolean(POSS_DUP_FLAG, true)
            header.setString(ORIG_SENDING_TIME, "20261002-09:00:00.000")
        }

    @Test
    fun `a resent ack does not rewind an order the venue has filled`() {
        venue.fromApp(message("35=D|11=ORD-1|55=ACME|54=1|38=100|40=2|44=185.25"), sessionId)
        venue.toApp(message("35=8|37=EX-1|17=E-1|150=0|39=0|11=ORD-1|14=0|151=100|6=0"), sessionId)
        venue.toApp(message("35=8|37=EX-1|17=E-2|150=F|39=2|11=ORD-1|14=100|151=0|32=100|31=185.25|6=185.25"), sessionId)

        venue.toApp(resent("35=8|37=EX-1|17=E-1|150=0|39=0|11=ORD-1|14=0|151=100|6=0"), sessionId)

        val cancel = message("35=F|41=ORD-1|11=CXL-1|55=ACME|54=1")
        assertEquals(OrderState.DONE, venue.orderReading(sessionId, cancel).state, "a cancel must still find the order filled")
        assertEquals("100", venue.orderFields(sessionId, cancel)?.get("cumQty"), "and everything it filled still filled")
        assertTrue(
            shown.any { it.direction == FixMessage.Direction.OUTGOING && it.rawMessage.contains("43=Y") },
            "the resent ack went on the wire, so it is still shown",
        )
    }

    @Test
    fun `a resent quote does not re-open a quote the client has already hit`() {
        venue.toApp(message("35=S|117=Q-1|131=RFQ-1|55=ACME|132=185.20|133=185.30|134=100|135=100"), sessionId)
        venue.fromApp(message("35=AJ|693=QR-1|117=Q-1|694=1|55=ACME|54=1|38=100"), sessionId)
        venue.toApp(message("35=8|37=EX-1|17=E-1|150=F|39=2|11=QR-1|693=QR-1|55=ACME|54=1|14=100|151=0|6=185.30"), sessionId)

        venue.toApp(resent("35=S|117=Q-1|131=RFQ-1|55=ACME|132=185.20|133=185.30|134=100|135=100"), sessionId)

        val secondHit = message("35=AJ|693=QR-2|117=Q-1|694=1|55=ACME|54=1|38=100")
        assertEquals(QuoteConstraint.DONE.word, venue.quoteReading(sessionId, secondHit).word, "a quote that traded cannot trade twice")
    }

    private companion object {
        const val POSS_DUP_FLAG = 43
        const val ORIG_SENDING_TIME = 122
    }
}
