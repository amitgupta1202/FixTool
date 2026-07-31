package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.BookedOrder
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.OrderBook
import com.knapsack.fixtool.model.OrderEvent
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reply shapes behind "Reply With…" — the manual half of the acceptor.
 *
 * Same stance as `AcceptorPresetsTest`: nothing here reads a template as a string, because the wire
 * does not read it that way either. What is asked of each shape is what the engine does with it
 * against a real message.
 *
 * The one thing these tests hold that the preset tests cannot is that the two halves are *the same
 * venue*. A fill typed twice would drift, and the drift would show up as a venue that behaves one way
 * when it answers by itself and another way when a tester answers for it — which is the single most
 * confusing thing this feature could do.
 */
class AcceptorReplyShapesTest {
    private val limitOrder = "35=D|11=ORD-1|55=ACME|54=1|38=1001|40=2|44=185.25|60=20260731-09:14:22.000"
    private val marketOrder = "35=D|11=ORD-2|55=ACME|54=1|38=1000|40=1|60=20260731-09:14:22.000"
    private val cancel = "35=F|41=ORD-1|11=CXL-1|55=ACME|54=1|60=20260731-09:14:22.000"
    private val replace = "35=G|41=ORD-1|11=RPL-1|55=ACME|54=1|38=500|40=2|44=186.00|60=20260731-09:14:22.000"
    private val statusRequest = "35=H|37=EX-1|11=ORD-1|55=ACME|54=1"
    private val executionReport = "35=8|37=EX-1|17=E-1|150=0|39=0|11=ORD-1|55=ACME|54=1|38=1000"

    private fun request(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw),
        )

    /**
     * ORD-1 as this venue would be holding it: received, acknowledged, half of 1001 already traded.
     *
     * Read through [OrderBook.fields] from a real [BookedOrder] rather than hand-written, so a shape
     * naming something the fold does not produce fails here rather than in front of a client.
     */
    private val booked: Map<String, String> =
        OrderBook.fields(
            BookedOrder(
                key = "ORD-1",
                events =
                    listOf(
                        OrderEvent(
                            at = LocalDateTime.now(),
                            sent = false,
                            msgType = "D",
                            fields = mapOf(11 to "ORD-1", 55 to "ACME", 54 to "1", 38 to "1001", 44 to "185.25"),
                        ),
                        OrderEvent(
                            at = LocalDateTime.now(),
                            sent = true,
                            msgType = "8",
                            fields =
                                mapOf(
                                    11 to "ORD-1",
                                    37 to "EX-1",
                                    150 to "F",
                                    39 to "1",
                                    14 to "500",
                                    151 to "501",
                                ),
                        ),
                    ),
            ),
        )

    private fun offers(raw: String, order: Map<String, String>? = null) =
        AcceptorResponder.offersFor(AcceptorResponder.buildMessage(raw), order = order)

    private fun reply(shapeId: String, raw: String, order: Map<String, String>? = null): String =
        AcceptorResponder.replyTo(
            AcceptorPresets.shapeById(shapeId)!!,
            AcceptorResponder.buildMessage(raw),
            request(raw),
            order = order,
        )

    private fun fieldsOf(raw: String): Map<String, String> =
        raw.split('|').filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') }

    // ------------------------------------------------------------------ the catalogue

    @Test
    fun `every shape is a template a preset already plays`() {
        val shipped =
            AcceptorPresets.all
                .flatMap { it.rules }
                .flatMap { it.sequence() }
                .map { it.template }
                .toSet()

        AcceptorPresets.replyShapes.forEach { shape ->
            assertTrue(
                shape.template in shipped,
                "'${shape.name}' sends something no rule sends — the manual venue has drifted from the automatic one",
            )
        }
    }

    @Test
    fun `shape ids and names are unique, since the id is how one is asked for`() {
        val ids = AcceptorPresets.replyShapes.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate shape id")
        val names = AcceptorPresets.replyShapes.map { it.name }
        assertEquals(names.size, names.distinct().size, "two shapes with one name are one entry in the menu")
    }

    @Test
    fun `every shape answers a message type some preset triggers on`() {
        val answered =
            AcceptorPresets.all
                .flatMap { it.rules }
                .map { it.whenMsgType }
                .toSet()

        AcceptorPresets.replyShapes.forEach { shape ->
            assertTrue(shape.answers in answered, "'${shape.name}' answers 35=${shape.answers}, which no rule receives")
        }
    }

    // ------------------------------------------------------------------ what is offered, and to what

    @Test
    fun `the menu is filtered by what the message is`() {
        assertEquals(
            listOf("ack", "fill", "partial-fill", "fill-remainder", "partial-of-remainder", "fill-what-is-left", "order-reject"),
            offers(limitOrder).map { it.shape.id },
        )
        assertEquals(listOf("pending-cancel", "canceled", "cancel-reject"), offers(cancel).map { it.shape.id })
        assertEquals(listOf("replaced"), offers(replace).map { it.shape.id })
        assertEquals(listOf("business-reject", "order-status"), offers(statusRequest).map { it.shape.id })
    }

    @Test
    fun `an ExecutionReport is not something a venue replies to`() {
        assertTrue(offers(executionReport).isEmpty(), "a venue answering its own reports would be talking to itself")
    }

    /**
     * The counterpart of the presets' `40 = 2` condition. A rule cannot see the order, so it excludes
     * market orders from the fill up front; this can see it, so it offers the fill and says why not.
     */
    @Test
    fun `a market order is offered the fills, refused, and told why`() {
        val offered = offers(marketOrder).associateBy { it.shape.id }

        assertTrue(offered.getValue("ack").available, "an acknowledgement reads no price and must stand")
        listOf("fill", "partial-fill", "fill-remainder").forEach { id ->
            val refusal = offered.getValue(id).refusal
            assertTrue(refusal != null && "44" in refusal, "$id must be refused, by name: $refusal")
        }
    }

    @Test
    fun `a limit order with a book behind it refuses nothing`() {
        offers(limitOrder, booked).forEach {
            assertNull(it.refusal, "'${it.shape.name}' was refused a message that has everything")
        }
    }

    /**
     * The refusal one level up from a missing tag: the message is complete and the *venue* has
     * nothing. A shape that reads the book against a venue holding no such order must be greyed out
     * with the reason, not offered and then discovered to build `37=`.
     */
    @Test
    fun `a shape that reads the book is refused when there is no order to read`() {
        val offered = offers(limitOrder).associateBy { it.shape.id }

        listOf("partial-of-remainder", "fill-what-is-left").forEach { id ->
            val refusal = offered.getValue(id).refusal
            assertTrue(refusal != null && "no order here" in refusal, "$id must be refused, and say why: $refusal")
        }
        assertNull(offered.getValue("fill").refusal, "the stateless fill needs no book and must still stand")
    }

    /** A name outside the vocabulary is a typo in the template, not a venue that has not said it yet. */
    @Test
    fun `a name the book does not have is refused as a name, not as a missing value`() {
        val refusal = AcceptorResponder.orderRefusal("35=8|37=\${order.leaves}|", booked)

        assertTrue(refusal != null && "is not a name" in refusal, "got: $refusal")
        assertTrue(refusal!!.contains("leavesQty"), "and it lists what the names are: $refusal")
    }

    // ------------------------------------------------------------------ what the book makes truthful

    /**
     * **The identity defect from the top of the proposal, fixed.** Acknowledging an order and then
     * filling it by hand drew two OrderIDs for one order, because `${req.uuid}` is one draw per
     * *triggering message* and a hand-sent reply is its own draw. Read from the book, the fill carries
     * the id the client already has.
     */
    @Test
    fun `a fill read from the book carries the OrderID the client was already given`() {
        val stateless = fieldsOf(reply("fill", limitOrder))
        val fromBook = fieldsOf(reply("fill-what-is-left", limitOrder, booked))

        assertNotEquals("EX-1", stateless["37"], "the stateless fill mints its own id — which is the defect")
        assertEquals("EX-1", fromBook["37"], "the book's fill is about the order the ack acknowledged")
    }

    @Test
    fun `a fill read from the book fills what is left rather than what a template guessed`() {
        val fields = fieldsOf(reply("fill-what-is-left", limitOrder, booked))

        assertEquals("1001", fields["14"], "the whole order is now done")
        assertEquals("0", fields["151"])
        assertEquals("501", fields["32"], "and this fill is the 501 that were still open, not half of 1001")
    }

    @Test
    fun `a partial of the remainder accumulates instead of restating the same quantity`() {
        val fields = fieldsOf(reply("partial-of-remainder", limitOrder, booked))

        // 500 already done, 501 left: half of what is left is 250, so CumQty is 750 and 251 remain.
        assertEquals("750", fields["14"], "CumQty has to move forward from what the venue already reported")
        assertEquals("251", fields["151"])
        assertEquals("250", fields["32"])
        assertEquals(
            fields["14"]!!.toInt() + fields["151"]!!.toInt(),
            1001,
            "CumQty and LeavesQty still make the order, which is what a client checks",
        )
    }

    @Test
    fun `the tags a reply reads are found inside expressions too, not only standing alone`() {
        // The partial fill reads OrderQty only as `${req.38 / 2}`. A scan that missed it would call the
        // shape available against a message with no 38 and then send `14=`.
        val partial = AcceptorPresets.shapeById("partial-fill")!!

        assertTrue(38 in AcceptorResponder.requestTags(partial.template), "req.38 inside an expression was not seen")

        val noQuantity = "35=D|11=ORD-4|55=ACME|54=1|40=2|44=185.25"
        val refusal = offers(noQuantity).first { it.shape.id == "partial-fill" }.refusal
        assertTrue(refusal != null && "38" in refusal, "a partial fill with no quantity to halve: $refusal")
    }

    // ------------------------------------------------------------------ what lands in the editor

    @Test
    fun `the reply is about the order it was opened from`() {
        val fields = fieldsOf(reply("ack", limitOrder))

        assertEquals("ORD-1", fields["11"], "the reply must name the order it answers")
        assertEquals("ACME", fields["55"])
        assertEquals("1", fields["54"])
        assertEquals("1001", fields["38"])
        assertEquals("8", fields["35"])
        assertEquals("0", fields["150"])
    }

    @Test
    fun `a second order gets its own ids`() {
        assertNotEquals(
            fieldsOf(reply("ack", limitOrder))["37"],
            fieldsOf(reply("ack", limitOrder.replace("ORD-1", "ORD-9")))["37"],
            "one OrderID per order, not one per shape",
        )
    }

    /**
     * `${req.38 / 2}` is an expression, and the expression pass is the half of the resolution
     * `resolve()` does not do — which is why [AcceptorResponder.replyTo] goes the long way round
     * through `plan`. Odd quantities are the case that shows arithmetic happened at all: 1001 halves
     * to 500 and 501, and both halves have to be integers a venue would send.
     */
    @Test
    fun `a partial fill halves the quantity, and the halves add up`() {
        val fields = fieldsOf(reply("partial-fill", limitOrder))

        assertEquals("500", fields["14"], "CumQty is half of 1001, rounded down")
        assertEquals("501", fields["151"], "LeavesQty is the rest, or the two do not add to the order")
        assertEquals(1001, fields.getValue("14").toInt() + fields.getValue("151").toInt())
    }

    @Test
    fun `nothing offered would put an empty field on the wire`() {
        listOf(limitOrder, marketOrder, cancel, replace, statusRequest).forEach { raw ->
            offers(raw).filter { it.available }.forEach { offer ->
                val sent = reply(offer.shape.id, raw)
                val empty = sent.split('|').filter { it.isNotBlank() }.filter { it.endsWith("=") }
                assertTrue(
                    empty.isEmpty(),
                    "'${offer.shape.name}' would open with ${empty.joinToString()} — a tag with no value is malformed",
                )
                assertTrue("\${" !in sent, "'${offer.shape.name}' left a substitution unresolved: $sent")
            }
        }
    }

    @Test
    fun `the reply builds as the message type it advertises`() {
        listOf(limitOrder, cancel, replace, statusRequest).forEach { raw ->
            offers(raw).filter { it.available }.forEach { offer ->
                val sent = reply(offer.shape.id, raw)
                assertEquals(
                    fieldsOf(sent)["35"],
                    AcceptorResponder.buildMessage(sent).header.getString(35),
                    "'${offer.shape.name}' builds a different message type than its template says",
                )
            }
        }
    }
}
