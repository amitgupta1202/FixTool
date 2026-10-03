package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.StepAddress
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **What a counterparty sends is data, never template.**
 *
 * A reply echoes the client's own values back: `11=${req.11}` in every order preset, `${order.clOrdId}` in the
 * book-driven ones, `${to.117}` in a relay. Those values used to be pasted into the template as text, and the
 * passes after that (the Kotlin expression pass and the `|` split) read the pasted text as if the author had
 * written it. So a client whose ClOrdID was `${...}` had it run as Kotlin on the tester's machine, and one whose
 * ClOrdID carried a `|` added fields to FixTool's own reply.
 *
 * The canary is a system property: a payload that calls into the JVM sets it, so a test that finds it unset has
 * proved the code never ran, not merely that its result was thrown away.
 */
class AcceptorEchoInjectionTest {
    private val canary = "fixtool.echo.canary"

    /** Sets [canary] when evaluated, and is otherwise an ordinary FIX string value. */
    private val runsCode = "\${System.setProperty(\"$canary\", \"ran\")}"

    /** The same call shaped to sit inside an author's arithmetic, so it evaluates to a number. */
    private val runsCodeAsNumber = "(System.setProperty(\"$canary\", \"ran\") ?: \"\").length + 2"

    @Before
    fun clearCanary() {
        System.clearProperty(canary)
    }

    @After
    fun clearCanaryAfter() {
        System.clearProperty(canary)
    }

    /** An inbound order whose fields are set one by one, so a value may carry a `|` the way it can on the wire. */
    private fun order(vararg fields: Pair<Int, String>, msgType: String = "D"): Pair<Message, FixMessage> {
        val message =
            Message().apply {
                header.setString(35, msgType)
                fields.forEach { (tag, value) -> setString(tag, value) }
            }
        val wire = (listOf(35 to msgType) + fields).joinToString("") { (tag, value) -> "$tag=$value\u0001" }
        val request =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = wire,
                messageType = msgType,
                quickfixMessage = message,
            )
        return message to request
    }

    private fun rule(template: String, msgType: String = "D") =
        AcceptorResponseRule(whenMsgType = msgType, steps = listOf(ResponseStep(template)))

    private fun planned(
        template: String,
        inbound: Pair<Message, FixMessage>,
        book: Map<String, String>? = null,
    ): PlannedSend =
        AcceptorResponder
            .plan(rule(template), inbound.first, inbound.second, null, quote = { null }) { book }
            .single()

    private fun ackFor(clOrdId: String): PlannedSend =
        planned(AcceptorPresets.ACK, order(11 to clOrdId, 55 to "IBM", 54 to "1", 38 to "100", 40 to "2", 44 to "1"))

    // ------------------------------------------------------------------ standalone echoes

    @Test
    fun `a ClOrdID shaped like an expression is echoed, not evaluated`() {
        val built = ackFor("\${1+1}").build()

        assertEquals("\${1+1}", built.getString(11), "the client's own ClOrdID, character for character")
    }

    @Test
    fun `a ClOrdID that calls into the JVM never runs`() {
        val built = ackFor(runsCode).build()

        assertNull(System.getProperty(canary), "the client's ClOrdID ran as Kotlin on the tester's machine")
        assertEquals(runsCode, built.getString(11))
    }

    @Test
    fun `a ClOrdID carrying a pipe stays one field and adds none`() {
        val send = ackFor("X|39=2")
        val built = send.build()
        val rendered = FixMessageHelper.parseFixMessage(send.render())

        assertEquals("X|39=2", built.getString(11), "a | is an ordinary character inside a FIX value")
        assertEquals("0", built.getString(39), "an ack says New, whatever the client's ClOrdID says")
        assertEquals(1, rendered.count { it.first == 39 }, "the rendered reply carries the preset's 39 and no other")
        assertEquals("X|39=2", rendered.single { it.first == 11 }.second)
    }

    @Test
    fun `a value carrying SOH refuses the step rather than splitting the reply`() {
        val send = planned("35=8|150=0|39=0|58=\${req.58}|", order(11 to "ORD-1", 58 to "a\u000139=2"))

        assertFailsWith<IllegalStateException> { send.build() }
    }

    @Test
    fun `a book value is echoed, not evaluated`() {
        val send =
            planned(
                "35=8|150=0|39=0|11=\${order.clOrdId}|",
                order(11 to "ORD-1", 38 to "100"),
                book = mapOf("clOrdId" to runsCode),
            )

        val built = send.build()

        assertNull(System.getProperty(canary), "a ClOrdID the book holds ran as Kotlin")
        assertEquals(runsCode, built.getString(11))
    }

    // ------------------------------------------------------------------ inside an author's expression

    @Test
    fun `a request value inside an expression must be a number, and anything else refuses the step`() {
        val send = planned("35=8|150=F|39=1|14=\${req.38 / 2}|", order(11 to "ORD-1", 38 to runsCodeAsNumber))

        val refused = assertFailsWith<IllegalStateException> { send.build() }

        assertNull(System.getProperty(canary), "an OrderQty spliced into the author's arithmetic ran as Kotlin")
        assertTrue("38" in refused.message.orEmpty(), "the refusal names the tag: ${refused.message}")
    }

    @Test
    fun `a book value inside an expression must be a number too`() {
        val send =
            planned(
                "35=8|150=F|39=1|32=\${order.orderQty / 2}|",
                order(11 to "ORD-1", 38 to "100"),
                book = mapOf("orderQty" to runsCodeAsNumber),
            )

        val refused = assertFailsWith<IllegalStateException> { send.build() }

        assertNull(System.getProperty(canary), "an OrderQty the book holds ran as Kotlin")
        assertTrue("orderQty" in refused.message.orEmpty(), "the refusal names the field: ${refused.message}")
    }

    @Test
    fun `a number inside an expression still computes, signed and decimal alike`() {
        val inbound = order(11 to "ORD-1", 38 to "1000", 44 to "-1.25")

        val built = planned("35=8|150=F|39=1|14=\${req.38 / 2}|31=\${req.44 * 2}|", inbound).build()

        assertEquals("500", built.getString(14))
        assertEquals("-2.5", built.getString(31))
    }

    // ------------------------------------------------------------------ what an expression answers

    @Test
    fun `an expression reading the request cannot split its answer into fields`() {
        val send = planned("35=8|150=8|39=8|58=\${in.D.58}|", order(11 to "ORD-1", 58 to "see|39=2"))

        val built = send.build()
        val rendered = FixMessageHelper.parseFixMessage(send.render())

        assertEquals("see|39=2", built.getString(58), "the client's Text, read back whole")
        assertEquals(1, rendered.count { it.first == 39 }, "the reply's own 39 and no other")
    }

    // ------------------------------------------------------------------ relay

    /** A venue whose every answer is a row the test wrote, and every row is something a counterparty sent. */
    private class TableVenue(
        val reach: Map<StepAddress, Resolution>,
        val ids: Map<Int, String>,
        val fields: Map<String, String>,
    ) : RelayVenue {
        override fun resolve(address: StepAddress, trigger: RelayTrigger) = reach[address] ?: Resolution()

        override fun toValue(recipient: Recipient, trigger: RelayTrigger, tag: Int): String? = ids[tag]

        override fun rfqField(trigger: RelayTrigger, name: String): String? = fields[name]
    }

    @Test
    fun `what a relay recipient and the RFQ book hold is echoed, not evaluated`() {
        // Read through the vocabulary: naming `StepAddress.Quoter` first, before anything else has touched
        // StepAddress, initialises the list of words while Quoter is still being built, and the list holds a null.
        val quoter = StepAddress.parse("quoter") ?: error("quoter is an address")
        val dealer = Recipient(null, "key-FIDLR1", "FIDLR1", quoter)
        val venue =
            TableVenue(
                reach = mapOf(quoter to Resolution(listOf(dealer))),
                ids = mapOf(117 to runsCode),
                fields = mapOf("requester" to "BUY|39=2"),
            )
        val inbound = order(117 to "Q-1", 131 to "RFQ-1", msgType = "AJ")
        val trigger =
            RelayTrigger(
                sessionId = null,
                sessionKey = "key-FIBUY1",
                compId = "FIBUY1",
                msgType = "AJ",
                fields = emptyMap(),
                rfqId = "RFQ-1",
            )
        val rule =
            AcceptorResponseRule(
                whenMsgType = "AJ",
                steps = listOf(ResponseStep("35=AJ|694=1|117=\${to.117}|448=\${rfq.requester}|", to = "quoter")),
            )

        val send =
            AcceptorResponder
                .planRelay(rule, inbound.first, inbound.second, null, venue, trigger)
                .sends
                .single()
        val built = send.build()

        assertNull(System.getProperty(canary), "a QuoteID a dealer chose ran as Kotlin")
        assertEquals(runsCode, built.getString(117))
        assertEquals("BUY|39=2", built.getString(448))
        assertFalse(built.isSetField(39), "a requester's CompID added no field to the dealer's message")
    }
}
