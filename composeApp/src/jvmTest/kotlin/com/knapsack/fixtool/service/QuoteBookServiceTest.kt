package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.QuoteState
import org.junit.Test
import quickfix.field.MsgType
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quote book as a *thing that holds state*: whose quote is whose, when it stops being available,
 * and which messages it takes off the order book's hands.
 *
 * The rules that read it are `AcceptorResponderTest`'s question, and what a venue *says* when a quote
 * is expired is `AcceptorRfqVenueIntegrationTest`'s.
 */
class QuoteBookServiceTest {
    /** Epoch millis, moved by hand: a quote's expiry is a clock comparison and nothing else. */
    private var now = LocalDateTime.of(2026, 7, 31, 9, 14, 22).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val book = QuoteBookService(clock = { now })

    private fun QuoteBookService.send(vararg fields: Pair<Int, String>) =
        record("CLIENT-A", sent = true, fields = fields.toMap())

    private fun QuoteBookService.receive(vararg fields: Pair<Int, String>) =
        record("CLIENT-A", sent = false, fields = fields.toMap())

    private fun reading(vararg fields: Pair<Int, String>) = book.reading("CLIENT-A", fields.toMap())

    /** A quote the venue sends, valid for thirty seconds on the test's own clock. */
    private fun quote(quoteId: String, validitySeconds: Long = 30) =
        arrayOf(
            35 to "S",
            117 to quoteId,
            131 to "REQ-1",
            55 to "EUR/USD",
            132 to "1.09010",
            133 to "1.09030",
            134 to "1000000",
            135 to "1000000",
            62 to fixTime(now / 1000 + validitySeconds),
        )

    /** An epoch second as tag 62 is written: a UTCTimestamp, which is how the venue quotes validity. */
    private fun fixTime(epochSecond: Long) =
        LocalDateTime
            .ofEpochSecond(epochSecond, 0, ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss"))

    // ------------------------------------------------------------------ born by a sent quote

    @Test
    fun `a sent quote is born open, with the prices the venue quoted`() {
        book.send(*quote("Q-1"))

        val read = reading(117 to "Q-1")
        assertEquals(QuoteConstraint.OPEN.word, read.word)
        assertEquals("1.09030", read.field("offer"))
        assertEquals("1.09010", read.field("bid"))
        assertEquals("EUR/USD", read.field("symbol"))
        assertEquals("REQ-1", read.field("quoteReqId"))
    }

    @Test
    fun `a received quote is not this venue's quote`() {
        book.receive(*quote("Q-1"))

        assertEquals(QuoteConstraint.UNKNOWN.word, reading(117 to "Q-1").word, "a client cannot mint our quote")
    }

    @Test
    fun `a hit naming a quote this venue never sent reads unknown`() {
        book.send(*quote("Q-1"))

        assertEquals(QuoteConstraint.UNKNOWN.word, reading(117 to "Q-9").word)
        assertNull(reading(117 to "Q-9").entry)
    }

    @Test
    fun `a message naming no quote at all reads unknown, and names none back`() {
        book.send(*quote("Q-1"))

        val read = reading(35 to "AJ", 693 to "R-1")
        assertEquals(QuoteConstraint.UNKNOWN.word, read.word)
        assertNull(read.quoteId, "there was no quote id to report")
    }

    // ------------------------------------------------------------------ done, by a hit and by a pass

    @Test
    fun `a hit is done once the venue's own report goes out, resolved through 693`() {
        book.send(*quote("Q-1"))
        book.receive(35 to "AJ", 693 to "R-1", 117 to "Q-1", 11 to "C-1", 38 to "1000000")

        assertEquals(
            QuoteConstraint.OPEN.word,
            reading(117 to "Q-1").word,
            "the client asking is not the venue agreeing",
        )

        book.send(35 to "8", 693 to "R-1", 11 to "C-1", 31 to "1.09030")

        val read = reading(117 to "Q-1")
        assertEquals(QuoteConstraint.DONE.word, read.word)
        assertEquals("8", read.entry?.doneBy)
        assertEquals(QuoteState.DONE, read.entry?.state)
    }

    @Test
    fun `a pass closes the quote, and any other status leaves it open`() {
        book.send(*quote("Q-1"))
        book.send(*quote("Q-2"))
        book.receive(35 to "AJ", 693 to "R-1", 117 to "Q-1", 694 to "6")
        book.receive(35 to "AJ", 693 to "R-2", 117 to "Q-2", 694 to "1")

        book.send(35 to "AI", 693 to "R-1", 297 to "11")
        book.send(35 to "AI", 693 to "R-2", 297 to "5", 58 to "Price is not the quoted price")

        assertEquals(QuoteConstraint.DONE.word, reading(117 to "Q-1").word, "a pass is an answer")
        assertEquals("AI", reading(117 to "Q-1").entry?.doneBy)
        assertEquals(
            QuoteConstraint.OPEN.word,
            reading(117 to "Q-2").word,
            "a quote the venue refused to trade is still the quote it sent",
        )
    }

    @Test
    fun `a report the book cannot attribute moves nothing`() {
        book.send(*quote("Q-1"))

        assertFalse(book.send(35 to "8", 693 to "R-UNSEEN", 11 to "C-1"), "nothing to claim")
        assertFalse(book.send(35 to "8", 11 to "C-1"), "an ordinary order report carries no 693")
        assertEquals(QuoteConstraint.OPEN.word, reading(117 to "Q-1").word)
    }

    // ------------------------------------------------------------------ the clock

    @Test
    fun `a quote expires by the clock, without anybody sending anything`() {
        book.send(*quote("Q-1", validitySeconds = 30))
        assertEquals(QuoteConstraint.OPEN.word, reading(117 to "Q-1").word)

        now += 31_000

        assertEquals(QuoteConstraint.EXPIRED.word, reading(117 to "Q-1").word)
        assertEquals(QuoteState.OPEN, reading(117 to "Q-1").entry?.state, "expired is open plus a clock")
    }

    @Test
    fun `a quote already done stays done after its validity passes`() {
        book.send(*quote("Q-1", validitySeconds = 30))
        book.receive(35 to "AJ", 693 to "R-1", 117 to "Q-1")
        book.send(35 to "8", 693 to "R-1")

        now += 600_000

        assertEquals(QuoteConstraint.DONE.word, reading(117 to "Q-1").word, "done outranks expired")
    }

    @Test
    fun `a quote with no validity the venue could read never expires`() {
        book.send(35 to "S", 117 to "Q-1", 62 to "next Tuesday")

        now += 86_400_000

        assertEquals(QuoteConstraint.OPEN.word, reading(117 to "Q-1").word)
        assertNull(reading(117 to "Q-1").entry?.validUntil, "an unreadable validity is no validity")
    }

    // ------------------------------------------------------------------ whose quote is whose

    @Test
    fun `one client's quote is not another's`() {
        book.record("CLIENT-A", sent = true, fields = quote("Q-1").toMap())

        assertEquals(QuoteConstraint.OPEN.word, book.reading("CLIENT-A", mapOf(117 to "Q-1")).word)
        assertEquals(
            QuoteConstraint.UNKNOWN.word,
            book.reading("CLIENT-B", mapOf(117 to "Q-1")).word,
            "QuoteID is unique per client, not per venue",
        )
    }

    @Test
    fun `two clients answering with the same response id do not cross`() {
        book.record("CLIENT-A", sent = true, fields = quote("Q-A").toMap())
        book.record("CLIENT-B", sent = true, fields = quote("Q-B").toMap())
        book.record("CLIENT-A", sent = false, fields = mapOf(35 to "AJ", 693 to "R-1", 117 to "Q-A"))
        book.record("CLIENT-B", sent = false, fields = mapOf(35 to "AJ", 693 to "R-1", 117 to "Q-B"))

        book.record("CLIENT-A", sent = true, fields = mapOf(35 to "8", 693 to "R-1"))

        assertEquals(QuoteConstraint.DONE.word, book.reading("CLIENT-A", mapOf(117 to "Q-A")).word)
        assertEquals(QuoteConstraint.OPEN.word, book.reading("CLIENT-B", mapOf(117 to "Q-B")).word)
    }

    // ------------------------------------------------------------------ claimed, so the order book is spared

    @Test
    fun `a booked hit is claimed, and everything else is left for the order book`() {
        book.send(*quote("Q-1"))
        book.receive(35 to "AJ", 693 to "R-1", 117 to "Q-1")

        assertTrue(
            book.send(35 to "8", 693 to "R-1", 11 to "C-1", 31 to "1.09030"),
            "a quote hit's report has a ClOrdID the venue never saw on an order",
        )
        assertFalse(book.send(35 to "AI", 693 to "R-1", 297 to "11"), "a status report was never an order event")
        assertFalse(book.send(*quote("Q-2")), "the quote itself is nobody else's business either")
        assertFalse(book.receive(35 to "D", 11 to "C-2"), "an order is an order")
        assertFalse(book.send(35 to "8", 11 to "C-2", 39 to "0"), "so is its report")
    }

    // ------------------------------------------------------------------ cleared and capped

    @Test
    fun `clearing forgets the quotes and the responses they were answering`() {
        book.send(*quote("Q-1"))
        book.receive(35 to "AJ", 693 to "R-1", 117 to "Q-1")

        book.clear("CLIENT-A")

        assertEquals(0, book.size("CLIENT-A"))
        assertEquals(QuoteConstraint.UNKNOWN.word, reading(117 to "Q-1").word)
        assertFalse(book.send(35 to "8", 693 to "R-1"), "the response it was answering is forgotten too")
    }

    @Test
    fun `clearing a book this venue never opened is not an error`() {
        book.clear("CLIENT-NOBODY")

        assertEquals(0, book.size("CLIENT-NOBODY"))
        assertEquals(0L, book.evicted("CLIENT-NOBODY"))
    }

    @Test
    fun `a full book drops the finished quotes first`() {
        val small = QuoteBookService(clock = { now }, initialCap = 3)
        (1..3).forEach { small.record("CLIENT-A", sent = true, fields = quote("Q-$it").toMap()) }
        small.record("CLIENT-A", sent = false, fields = mapOf(35 to "AJ", 693 to "R-2", 117 to "Q-2"))
        small.record("CLIENT-A", sent = true, fields = mapOf(35 to "8", 693 to "R-2"))

        small.record("CLIENT-A", sent = true, fields = quote("Q-4").toMap())

        assertEquals(3, small.size("CLIENT-A"))
        assertEquals(1L, small.evicted("CLIENT-A"))
        assertEquals(
            QuoteConstraint.UNKNOWN.word,
            small.reading("CLIENT-A", mapOf(117 to "Q-2")).word,
            "the answered quote is the one nobody needs",
        )
        assertEquals(QuoteConstraint.OPEN.word, small.reading("CLIENT-A", mapOf(117 to "Q-1")).word)
        assertEquals(QuoteConstraint.OPEN.word, small.reading("CLIENT-A", mapOf(117 to "Q-4")).word)
    }

    @Test
    fun `lowering the cap while the books are open brings them within it`() {
        val small = QuoteBookService(clock = { now }, initialCap = 10)
        (1..6).forEach { small.record("CLIENT-A", sent = true, fields = quote("Q-$it").toMap()) }

        small.setCap(2)

        assertEquals(2, small.size("CLIENT-A"))
        assertEquals(4L, small.evicted("CLIENT-A"), "a book reporting a cap it does not keep is a book that lies")
    }

    // ------------------------------------------------------------------ off a real message

    @Test
    fun `the tags come off a real message, header and body alike`() {
        val message = quickfix.Message()
        message.header.setField(MsgType("AJ"))
        message.setString(117, "Q-1")
        message.setString(693, "R-1")
        message.setString(297, "11")
        message.setString(11, "C-1")

        val fields = QuoteBookService.fieldsOf(message)

        assertEquals("AJ", fields[35], "MsgType lives in the header")
        assertEquals("Q-1", fields[117])
        assertEquals("R-1", fields[693])
        assertEquals("11", fields[297])
        assertNull(fields[11], "ClOrdID is the order book's business")
    }
}
