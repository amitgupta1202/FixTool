package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.LegOutcome
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.RelayRef
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.RfqLife
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The RFQ book as a thing that holds state across sessions**: which dealer's quote the buy side is lifting,
 * what a second lift reads, and what a re-quote does to the level it replaced.
 *
 * Scripted wire, no sockets. Every `send` here stands for a message the venue relayed, carrying the relay
 * reference the engine will attach in step 3; every `receive` for one that arrived. What a venue *does* with
 * these readings is the relay engine's test, not this one.
 */
class RfqBookServiceTest {
    private var now = LocalDateTime.of(2026, 9, 13, 9, 14, 21).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val book = RfqBookService(clock = { now })

    private val buyer = "FIX.4.4:FIRFQ_VENUE->FIBUY1"
    private val otherBuyer = "FIX.4.4:FIRFQ_VENUE->FIBUY2"
    private val dealer1 = "FIX.4.4:FIRFQ_VENUE->FIDLR1"
    private val dealer2 = "FIX.4.4:FIRFQ_VENUE->FIDLR2"

    private fun compOf(key: String) = key.substringAfter("->")

    private fun receive(key: String, role: PartyRole?, vararg fields: Pair<Int, String>) =
        book.record(key, compOf(key), role, sent = false, fields = fields.toMap(), messageUid = 1)

    private fun relay(key: String, rfqId: String, triggerSession: String, address: String, vararg fields: Pair<Int, String>) =
        book.record(
            key,
            compOf(key),
            null,
            sent = true,
            fields = fields.toMap(),
            relay = RelayRef(1, triggerSession, compOf(triggerSession), null, address, compOf(key), rfqId),
        )

    private fun utc(secondsFromNow: Long) =
        LocalDateTime.ofEpochSecond(now / 1000 + secondsFromNow, 0, ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss"))

    /** The mockup's negotiation up to both quotes being shown to the buy side. Returns the RFQ id. */
    private fun twoDealersQuoted(): String {
        receive(buyer, PartyRole.REQUESTER, 35 to "R", 131 to "BUY-RFQ-7", 55 to "T 4.25 08/15/36", 54 to "1", 38 to "10000000")
        val rfqId = book.entryFor(buyer, mapOf(131 to "BUY-RFQ-7"))!!.rfqId
        relay(dealer1, rfqId, buyer, "responders", 35 to "R", 131 to "V-RFQ-1042")
        relay(dealer2, rfqId, buyer, "responders", 35 to "R", 131 to "V-RFQ-1042")
        receive(dealer1, PartyRole.RESPONDER, 35 to "S", 131 to "V-RFQ-1042", 117 to "D1-Q-88", 133 to "98.515625", 62 to utc(30))
        relay(buyer, rfqId, dealer1, "requester", 35 to "S", 131 to "BUY-RFQ-7", 117 to "V-Q-1042-1", 133 to "98.515625")
        receive(dealer2, PartyRole.RESPONDER, 35 to "S", 131 to "V-RFQ-1042", 117 to "D2-551", 133 to "98.531250", 62 to utc(30))
        relay(buyer, rfqId, dealer2, "requester", 35 to "S", 131 to "BUY-RFQ-7", 117 to "V-Q-1042-2", 133 to "98.531250")
        return rfqId
    }

    @Test
    fun `a requester's QuoteRequest is requested, and relaying it opens it with a leg per dealer`() {
        receive(buyer, PartyRole.REQUESTER, 35 to "R", 131 to "BUY-RFQ-7")
        val entry = assertNotNull(book.entryFor(buyer, mapOf(131 to "BUY-RFQ-7")))
        assertEquals(RfqLife.REQUESTED, entry.life)
        assertEquals(RfqConstraint.OPEN.word, book.reading(buyer, 131, mapOf(131 to "BUY-RFQ-7")).word, "requested reads open")

        relay(dealer1, entry.rfqId, buyer, "responders", 35 to "R", 131 to "V-RFQ-1042")

        val opened = book.entry(entry.rfqId)!!
        assertEquals(RfqLife.OPEN, opened.life)
        assertEquals(listOf("FIDLR1"), opened.legs.map { it.compId })
        assertEquals(entry.rfqId, book.reading(dealer1, 131, mapOf(131 to "V-RFQ-1042")).rfqId, "the dealer's id finds the RFQ")
    }

    @Test
    fun `a QuoteRequest from anyone but a requester opens nothing`() {
        receive(dealer1, PartyRole.RESPONDER, 35 to "R", 131 to "D-RFQ-1")
        receive(buyer, null, 35 to "R", 131 to "U-RFQ-1")
        assertEquals(0, book.size())
    }

    /** Ids are unique per counterparty, not per venue, which is the whole reason the book keys by both. */
    @Test
    fun `two buy sides using the same QuoteReqID are two RFQs`() {
        receive(buyer, PartyRole.REQUESTER, 35 to "R", 131 to "RFQ-1")
        receive(otherBuyer, PartyRole.REQUESTER, 35 to "R", 131 to "RFQ-1")

        val first = book.reading(buyer, 131, mapOf(131 to "RFQ-1")).rfqId
        val second = book.reading(otherBuyer, 131, mapOf(131 to "RFQ-1")).rfqId
        assertEquals(2, book.size())
        assertTrue(first != null && second != null && first != second, "$first vs $second")
    }

    @Test
    fun `the id the buy side was shown leads back to the dealer's own quote`() {
        twoDealersQuoted()

        val reading = book.reading(buyer, 117, mapOf(117 to "V-Q-1042-1"))

        assertEquals(RfqConstraint.OPEN.word, reading.word)
        assertEquals("FIDLR1", reading.leg?.compId)
        assertEquals("D1-Q-88", reading.quote?.dealerQuoteId)
        assertEquals("V-Q-1042-1", reading.quote?.venueQuoteId)
    }

    /** Decision R3: recorded when decided, so the lift queued behind the first reads the trade that already happened. */
    @Test
    fun `once a trade is decided a second lift on the same RFQ reads done`() {
        val rfqId = twoDealersQuoted()

        book.decideTrade(rfqId, dealer1)

        assertEquals(RfqConstraint.DONE.word, book.reading(buyer, 117, mapOf(117 to "V-Q-1042-2")).word)
        assertEquals(RfqConstraint.DONE.word, book.reading(dealer2, 131, mapOf(131 to "V-RFQ-1042")).word, "a late quote is refused")
        val entry = book.entry(rfqId)!!
        assertEquals(RfqLife.DONE, entry.life)
        assertEquals(LegOutcome.LIFTED, entry.leg(dealer1)?.outcome)
    }

    /** A buy side that sells at a dealer's bid hit it; lifting is buying at the offer. A trader reads the book in those words. */
    @Test
    fun `a trade at the bid is recorded as a hit, and at the offer as a lift`() {
        val sold = twoDealersQuoted()
        book.decideTrade(sold, dealer1, side = "2")
        assertEquals(LegOutcome.HIT, book.entry(sold)!!.leg(dealer1)?.outcome)

        book.clear()
        val bought = twoDealersQuoted()
        book.decideTrade(bought, dealer1, side = "1")
        assertEquals(LegOutcome.LIFTED, book.entry(bought)!!.leg(dealer1)?.outcome)
    }

    @Test
    fun `a dealer that quotes again replaces its level, and the old one can no longer be lifted`() {
        val rfqId = twoDealersQuoted()

        receive(dealer1, PartyRole.RESPONDER, 35 to "S", 131 to "V-RFQ-1042", 117 to "D1-Q-89", 133 to "98.531250", 62 to utc(30))
        relay(buyer, rfqId, dealer1, "requester", 35 to "S", 131 to "BUY-RFQ-7", 117 to "V-Q-1042-3", 133 to "98.531250")

        assertEquals(RfqConstraint.DONE.word, book.reading(buyer, 117, mapOf(117 to "V-Q-1042-1")).word, "the replaced level")
        val current = book.reading(buyer, 117, mapOf(117 to "V-Q-1042-3"))
        assertEquals(RfqConstraint.OPEN.word, current.word)
        assertEquals("D1-Q-89", current.quote?.dealerQuoteId)
    }

    @Test
    fun `a quote that has lapsed reads done on its own id while the RFQ is still open`() {
        twoDealersQuoted()
        now += 31_000

        assertEquals(RfqConstraint.DONE.word, book.reading(buyer, 117, mapOf(117 to "V-Q-1042-1")).word)
        assertEquals(RfqConstraint.OPEN.word, book.reading(buyer, 131, mapOf(131 to "BUY-RFQ-7")).word)
        assertNull(book.entry(book.entryFor(buyer, mapOf(131 to "BUY-RFQ-7"))!!.rfqId)!!.leg(dealer1)!!.currentQuote(now))
    }

    @Test
    fun `a pass by the requester ends it, and so does a refusal the venue sends it`() {
        val rfqId = twoDealersQuoted()
        receive(buyer, PartyRole.REQUESTER, 35 to "AJ", 693 to "BUY-RESP-7", 117 to "V-Q-1042-1", 694 to "6")
        assertEquals(RfqLife.PASSED, book.entry(rfqId)!!.life)

        receive(otherBuyer, PartyRole.REQUESTER, 35 to "R", 131 to "BAD-1")
        val refusedId = book.entryFor(otherBuyer, mapOf(131 to "BAD-1"))!!.rfqId
        book.record(otherBuyer, "FIBUY2", PartyRole.REQUESTER, sent = true, fields = mapOf(35 to "AG", 131 to "BAD-1", 658 to "1"))
        assertEquals(RfqLife.REFUSED, book.entry(refusedId)!!.life)
        assertEquals(RfqConstraint.DONE.word, book.reading(otherBuyer, 131, mapOf(131 to "BAD-1")).word)
    }

    @Test
    fun `a dealer's pass marks its leg, and the RFQ stays open for the others`() {
        val rfqId = twoDealersQuoted()
        receive(dealer2, PartyRole.RESPONDER, 35 to "AG", 131 to "V-RFQ-1042", 658 to "10")

        assertEquals(LegOutcome.PASSED, book.entry(rfqId)!!.leg(dealer2)?.outcome)
        assertEquals(RfqLife.OPEN, book.entry(rfqId)!!.life)
    }

    @Test
    fun `cover and done away sent to dealers are recorded on their legs`() {
        val rfqId = twoDealersQuoted()
        book.decideTrade(rfqId, dealer1)
        relay(dealer2, rfqId, buyer, "cover", 35 to "AJ", 693 to "V-RESP-9", 694 to "4", 117 to "D2-551")

        assertEquals(LegOutcome.COVER, book.entry(rfqId)!!.leg(dealer2)?.outcome)
    }

    @Test
    fun `a relayed fill is claimed from the order book, and a fill to the sender is not`() {
        val rfqId = twoDealersQuoted()

        assertTrue(relay(dealer1, rfqId, buyer, "quoter", 35 to "8", 37 to "V-T-1042", 11 to "V-ORD-1042", 39 to "2"))
        assertFalse(book.record(buyer, "FIBUY1", PartyRole.REQUESTER, sent = true, fields = mapOf(35 to "8", 11 to "BUY-TRD-7")))
    }

    @Test
    fun `what was last exchanged with each counterparty is what that counterparty knows`() {
        val rfqId = twoDealersQuoted()
        relay(dealer1, rfqId, buyer, "quoter", 35 to "AJ", 693 to "V-RESP-1", 694 to "1", 117 to "D1-Q-88", 11 to "V-ORD-1042")

        assertEquals("V-RFQ-1042", book.toValue(rfqId, dealer1, 131))
        assertEquals("V-ORD-1042", book.toValue(rfqId, dealer1, 11))
        assertEquals("BUY-RFQ-7", book.toValue(rfqId, buyer, 131))
        assertEquals("V-Q-1042-2", book.toValue(rfqId, buyer, 117), "the last quote id shown to the buy side")
    }

    @Test
    fun `an RFQ with an ExpireTime reads expired once it passes, and one without never does`() {
        receive(buyer, PartyRole.REQUESTER, 35 to "R", 131 to "E-1", 126 to utc(60))
        receive(otherBuyer, PartyRole.REQUESTER, 35 to "R", 131 to "E-2")
        now += 61_000

        assertEquals(RfqConstraint.EXPIRED.word, book.reading(buyer, 131, mapOf(131 to "E-1")).word)
        assertEquals(RfqConstraint.OPEN.word, book.reading(otherBuyer, 131, mapOf(131 to "E-2")).word)
    }

    @Test
    fun `the venue's default expiry applies when the request names none`() {
        val defaulted = RfqBookService(clock = { now }, defaultExpiryMillis = { 30_000 })
        defaulted.record(buyer, "FIBUY1", PartyRole.REQUESTER, sent = false, fields = mapOf(35 to "R", 131 to "D-1"))
        now += 31_000
        assertEquals(RfqConstraint.EXPIRED.word, defaulted.reading(buyer, 131, mapOf(131 to "D-1")).word)
    }

    // ------------------------------------------------------------------ the expiry timer's hooks

    private val armed = mutableListOf<Pair<String, Long>>()
    private val disarmed = mutableListOf<String>()

    private fun timed(defaultExpiry: Long? = 30_000) =
        RfqBookService(
            clock = { now },
            defaultExpiryMillis = { defaultExpiry },
            onOpened = { rfqId, expireAt -> armed += rfqId to expireAt },
            onEnded = { disarmed += it },
        )

    private fun RfqBookService.open(reqId: String, vararg extra: Pair<Int, String>): String {
        record(buyer, "FIBUY1", PartyRole.REQUESTER, false, mapOf(35 to "R", 131 to reqId) + extra)
        return entryFor(buyer, mapOf(131 to reqId))!!.rfqId
    }

    private fun RfqBookService.ask(rfqId: String, dealer: String, venueReqId: String) =
        record(
            dealer,
            compOf(dealer),
            null,
            true,
            mapOf(35 to "R", 131 to venueReqId),
            RelayRef(1, buyer, "FIBUY1", "R", "responders", compOf(dealer), rfqId),
        )

    /** Armed when somebody is first asked, and once: a second dealer asked is the same RFQ, not a second expiry. */
    @Test
    fun `an RFQ's expiry is armed when it is first relayed, from its ExpireTime or the venue's default`() {
        val book = timed()
        val withTime = book.open("T-1", 126 to utc(10))
        val defaulted = book.open("T-2")
        assertTrue(armed.isEmpty(), "a request nobody has been asked about arms nothing")

        book.ask(withTime, dealer1, "V-1")
        book.ask(withTime, dealer2, "V-1")
        book.ask(defaulted, dealer1, "V-2")

        assertEquals(listOf(withTime to now / 1000 * 1000 + 10_000, defaulted to now + 30_000), armed)
    }

    @Test
    fun `nothing is armed for an RFQ that has no expiry at all`() {
        val book = timed(defaultExpiry = null)
        book.ask(book.open("N-1"), dealer1, "V-1")
        assertTrue(armed.isEmpty())
    }

    /** Refused, passed, traded, cleared, evicted: every way an RFQ stops being live, except its own expiry, disarms it. */
    @Test
    fun `an RFQ that ends any other way disarms its expiry`() {
        val book = timed()
        val refused = book.open("E-R")
        book.record(buyer, "FIBUY1", PartyRole.REQUESTER, true, mapOf(35 to "AG", 131 to "E-R"))
        val traded = book.open("E-T")
        book.ask(traded, dealer1, "V-T")
        book.decideTrade(traded, dealer1)
        val passed = book.open("E-P")
        book.ask(passed, dealer1, "V-P")
        book.record(dealer1, "FIDLR1", PartyRole.RESPONDER, false, mapOf(35 to "S", 131 to "V-P", 117 to "D-P", 133 to "98.5"))
        book.record(
            buyer,
            "FIBUY1",
            null,
            true,
            mapOf(35 to "S", 131 to "E-P", 117 to "S-P"),
            RelayRef(1, dealer1, "FIDLR1", "S", "requester", "FIBUY1", passed),
        )
        book.record(buyer, "FIBUY1", PartyRole.REQUESTER, false, mapOf(35 to "AJ", 117 to "S-P", 694 to "6"))
        val cleared = book.open("E-C")

        assertEquals(listOf(refused, traded, passed), disarmed)
        book.clear()
        assertTrue(cleared in disarmed, "clearing the book disarms what it held: $disarmed")
    }

    /** What the timer does when it fires: the book says whether there is anything left to end. */
    @Test
    fun `expiring ends a live RFQ whose time has come, and nothing else`() {
        val book = timed()
        val rfqId = book.open("X-1")
        book.ask(rfqId, dealer1, "V-X")
        assertNull(book.expire(rfqId), "its time has not come")

        now += 30_001
        val expired = assertNotNull(book.expire(rfqId))
        assertEquals(RfqLife.EXPIRED, expired.life)
        assertEquals(RfqLife.EXPIRED, book.entry(rfqId)!!.life, "recorded, not only read off the clock")
        assertNull(book.expire(rfqId), "an RFQ expires once")

        val traded = book.open("X-2")
        book.ask(traded, dealer1, "V-X2")
        book.decideTrade(traded, dealer1)
        now += 60_000
        assertNull(book.expire(traded), "a timer that fires after the trade finds nothing to end")
    }

    @Test
    fun `an RFQ relayed to a requester who has gone does not grow a leg named after the requester`() {
        val rfqId = twoDealersQuoted()
        book.notDelivered(rfqId, buyer, "FIBUY1")
        assertTrue(book.entry(rfqId)!!.legs.none { it.responderKey == buyer })
    }

    @Test
    fun `an evicted RFQ takes its links with it, and ended ones go first`() {
        val small = RfqBookService(clock = { now }, initialCap = 2)
        small.record(buyer, "FIBUY1", PartyRole.REQUESTER, false, mapOf(35 to "R", 131 to "A"))
        small.record(buyer, "FIBUY1", PartyRole.REQUESTER, false, mapOf(35 to "R", 131 to "B"))
        small.record(buyer, "FIBUY1", PartyRole.REQUESTER, true, mapOf(35 to "AG", 131 to "A"))
        small.record(buyer, "FIBUY1", PartyRole.REQUESTER, false, mapOf(35 to "R", 131 to "C"))

        assertEquals(2, small.size())
        assertEquals(1, small.evicted())
        assertEquals(RfqConstraint.UNKNOWN.word, small.reading(buyer, 131, mapOf(131 to "A")).word, "the refused one went first")
        assertEquals(RfqConstraint.OPEN.word, small.reading(buyer, 131, mapOf(131 to "B")).word)
    }

    @Test
    fun `clearing forgets everything`() {
        twoDealersQuoted()
        book.clear()
        assertEquals(0, book.size())
        assertEquals(RfqConstraint.UNKNOWN.word, book.reading(buyer, 131, mapOf(131 to "BUY-RFQ-7")).word)
    }

    /** A conformant FIX 4.4 QuoteRequest carries its instrument, side and size inside NoRelatedSym. */
    @Test
    fun `the book reads a conformant QuoteRequest through its group`() {
        val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        val message =
            AcceptorResponder.buildMessage(
                "35=R|131=G-1|146=1|55=T 4.25 08/15/36|48=91282CMF5|22=1|54=1|38=10000000|",
                dictionary,
            )

        val fields = RfqBookService.fieldsOf(message)

        assertEquals("T 4.25 08/15/36", fields[55])
        assertEquals("1", fields[54])
        assertEquals("10000000", fields[38])
    }
}
