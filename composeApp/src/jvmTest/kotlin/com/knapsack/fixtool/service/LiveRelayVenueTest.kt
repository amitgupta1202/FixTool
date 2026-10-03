package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.RelayRef
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.RfqLife
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import quickfix.SessionID
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The running venue's half of a relay**: what the RFQ book records when a rule's plan is made, with the sessions
 * the venue has created and who among them is logged on.
 *
 * A scripted book and a table of who is on, no sockets. Every `receive` stands for a message that arrived and every
 * `relay` for one the venue sent on, as in `RfqBookServiceTest`. The planner against a venue made of a table is
 * `AcceptorRelayPlanTest`'s, and the whole negotiation over real sessions is `RfqRelayIntegrationTest`'s.
 */
class LiveRelayVenueTest {
    private var now = LocalDateTime.of(2026, 9, 13, 9, 14, 21).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val book = RfqBookService(clock = { now })

    private val online = mutableSetOf<String>()

    private val venue =
        LiveRelayVenue(
            book,
            counterparties = { FiRfqPlatformPreset.COUNTERPARTIES },
            isLoggedOn = { it.targetCompID in online },
            clock = { now },
        )

    /** A counterparty session the venue has created, logged on. */
    private fun session(compId: String): SessionID =
        SessionID("FIX.4.4", "FIRFQ_VENUE", compId).also {
            venue.created(it)
            online += compId
        }

    private val buyer = session("FIBUY1")
    private val dealer1 = session("FIDLR1")
    private val dealer2 = session("FIDLR2")

    private fun receive(from: SessionID, role: PartyRole, vararg fields: Pair<Int, String>) =
        book.record(from.toString(), from.targetCompID, role, sent = false, fields = fields.toMap(), messageUid = 1)

    private fun relay(to: SessionID, rfqId: String, triggerSession: SessionID, address: String, vararg fields: Pair<Int, String>) =
        book.record(
            to.toString(),
            to.targetCompID,
            null,
            sent = true,
            fields = fields.toMap(),
            relay = RelayRef(1, triggerSession.toString(), triggerSession.targetCompID, null, address, to.targetCompID, rfqId),
        )

    private fun utc(secondsFromNow: Long) =
        LocalDateTime
            .ofEpochSecond(now / 1000 + secondsFromNow, 0, ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss"))

    /** The negotiation up to both dealers' quotes being shown to the buy side, with a minute to run. Returns the RFQ. */
    private fun twoDealersQuoted(): String {
        receive(buyer, PartyRole.REQUESTER, 35 to "R", 131 to "BUY-RFQ-7", 55 to "T 4.25 08/15/36", 54 to "1", 126 to utc(60))
        val rfqId = book.entryFor(buyer.toString(), mapOf(131 to "BUY-RFQ-7"))!!.rfqId
        relay(dealer1, rfqId, buyer, "responders", 35 to "R", 131 to "V-RFQ-1042")
        relay(dealer2, rfqId, buyer, "responders", 35 to "R", 131 to "V-RFQ-1042")
        receive(dealer1, PartyRole.RESPONDER, 35 to "S", 131 to "V-RFQ-1042", 117 to "D1-Q-88", 133 to "98.515625", 62 to utc(30))
        relay(buyer, rfqId, dealer1, "requester", 35 to "S", 131 to "BUY-RFQ-7", 117 to "V-Q-1042-1", 133 to "98.515625")
        receive(dealer2, PartyRole.RESPONDER, 35 to "S", 131 to "V-RFQ-1042", 117 to "D2-551", 133 to "98.531250", 62 to utc(30))
        relay(buyer, rfqId, dealer2, "requester", 35 to "S", 131 to "BUY-RFQ-7", 117 to "V-Q-1042-2", 133 to "98.531250")
        return rfqId
    }

    private fun request(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw),
        )

    /** The platform's rule for a buy side lifting an offer: the one that books a trade. */
    private val liftAtTheOffer =
        FiRfqPlatformPreset.rules.first { rule ->
            rule.whenMsgType == "AJ" && rule.booksATrade() && rule.trigger().any { it.tag == 54 && it.parsed() == Matcher.Exact("1") }
        }

    // ------------------------------------------------------------------ the trade a plan books

    /**
     * The buy side is filled the moment the lift is planned, whether or not the dealer it traded with is there to be
     * told. Decided only from the sends, a lift of a departed dealer's quote booked nothing: the RFQ stayed open, the
     * same lift could fill a second time, and at expiry the buy side was told its RFQ expired.
     */
    @Test
    fun `lifting a quote whose dealer has logged off still books the trade`() {
        val rfqId = twoDealersQuoted()
        online -= "FIDLR1"
        val lift = "35=AJ|693=BUY-RESP-1|694=1|117=V-Q-1042-1|11=BUY-ORD-1|55=T 4.25 08/15/36|54=1|38=10000000|44=98.515625|"
        receive(buyer, PartyRole.REQUESTER, 35 to "AJ", 694 to "1", 117 to "V-Q-1042-1", 54 to "1")

        val (_, plan) = venue.plan(liftAtTheOffer, AcceptorResponder.buildMessage(lift), request(lift), null, buyer, { null }, { null })

        assertTrue(plan.notDelivered.any { (_, owed) -> owed.compId == "FIDLR1" }, "the dealer is owed its fill and is not there")
        assertTrue(plan.sends.any { it.to?.compId == "FIBUY1" }, "while the buy side is filled")
        assertEquals(RfqLife.DONE, book.entry(rfqId)!!.life, "so the RFQ traded")
        assertEquals(
            RfqConstraint.DONE.word,
            book.reading(buyer.toString(), 117, mapOf(117 to "V-Q-1042-1")).word,
            "and the same lift sent again reads done",
        )
        now += 120_000
        assertNull(book.expire(rfqId), "a traded RFQ never expires, so the buy side is never told it did")
    }
}
