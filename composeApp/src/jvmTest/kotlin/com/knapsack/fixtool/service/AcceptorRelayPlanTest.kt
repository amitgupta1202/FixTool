package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.RelayRef
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.RfqReading
import com.knapsack.fixtool.model.SendReason
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The relay engine against a venue made of a table**: who a step reaches, what each recipient is told, and
 * what the engine refuses to put on the wire.
 *
 * The venue here is [TableVenue], so every answer is one the test wrote down and nothing depends on sockets. The
 * same questions against real sessions are `RfqRelayIntegrationTest`'s.
 */
class AcceptorRelayPlanTest {
    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private val fromRequester = condition(49, Matcher.CounterpartyRole("requester"))
    private val fromResponder = condition(49, Matcher.CounterpartyRole("responder"))

    private fun recipient(compId: String, address: StepAddress, quoteId: String? = null) =
        Recipient(null, "key-$compId", compId, address, quoteId)

    /** A venue whose every answer is a row the test wrote. */
    private class TableVenue(
        val reach: Map<StepAddress, Resolution>,
        val ids: Map<Pair<String, Int>, String> = emptyMap(),
        val fields: Map<String, String> = emptyMap(),
    ) : RelayVenue {
        override fun resolve(address: StepAddress, trigger: RelayTrigger) = reach[address] ?: Resolution()

        override fun toValue(recipient: Recipient, trigger: RelayTrigger, tag: Int): String? =
            if (tag == 117 && recipient.quoteId != null) recipient.quoteId else ids[recipient.compId to tag]

        override fun rfqField(trigger: RelayTrigger, name: String): String? = fields[name]
    }

    private fun trigger(raw: String) =
        RelayTrigger(
            sessionId = null,
            sessionKey = "key-FIBUY1",
            compId = "FIBUY1",
            msgType = Regex("35=([^|]+)").find(raw)?.groupValues?.get(1),
            fields = emptyMap(),
            rfqId = "RFQ-1",
        )

    private fun plan(rule: AcceptorResponseRule, raw: String, venue: RelayVenue): RelayPlan =
        AcceptorResponder.planRelay(rule, AcceptorResponder.buildMessage(raw), null, null, venue, trigger(raw))

    private fun field(raw: String, tag: Int) = raw.split('|').firstOrNull { it.startsWith("$tag=") }?.substringAfter('=')

    // ------------------------------------------------------------------ fan-out

    @Test
    fun `a step to the responders renders once for each, with one request id and a fresh id each`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(fromRequester),
                steps = listOf(ResponseStep("35=R|131=\${req.uuid}|37=\${uuid}|55=\${req.55}|", to = "responders")),
            )
        val venue =
            TableVenue(
                mapOf(
                    StepAddress.Responders to
                        Resolution(listOf(recipient("FIDLR1", StepAddress.Responders), recipient("FIDLR2", StepAddress.Responders))),
                ),
            )

        val sends = plan(rule, "35=R|131=BUY-RFQ-7|55=T 4.25 11/15/36|", venue).sends
        val rendered = sends.map { it.render() }

        assertEquals(listOf("FIDLR1", "FIDLR2"), sends.map { it.to?.compId })
        assertEquals(1, rendered.map { field(it, 131) }.distinct().size, "one RFQ id for every dealer")
        assertEquals(2, rendered.map { field(it, 37) }.distinct().size, "a fresh id per recipient")
        assertTrue(rendered.all { field(it, 55) == "T 4.25 11/15/36" })
        assertTrue(sends.all { it.authoredStep == 0 }, "a fan-out is one step, however many it reaches")
    }

    // ------------------------------------------------------------------ what each side knows

    @Test
    fun `each recipient reads the ids it knows, and the quoter the quote the trigger named`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "AJ",
                conditions = listOf(fromRequester, condition(117, Matcher.RfqState("open"))),
                steps =
                    listOf(
                        ResponseStep("35=8|11=\${req.11}|375=\${rfq.quoter}|"),
                        ResponseStep("35=AJ|694=1|117=\${to.117}|131=\${to.131}|", to = "quoter"),
                        ResponseStep("35=AJ|694=4|117=\${to.117}|", to = "cover"),
                    ),
            )
        val venue =
            TableVenue(
                reach =
                    mapOf(
                        StepAddress.Quoter to Resolution(listOf(recipient("FIDLR1", StepAddress.Quoter, quoteId = "D1-Q-88"))),
                        StepAddress.Cover to Resolution(listOf(recipient("FIDLR2", StepAddress.Cover, quoteId = "D2-551"))),
                    ),
                ids = mapOf(("FIDLR1" to 131) to "V-RFQ-1042"),
                fields = mapOf("quoter" to "FIDLR1"),
            )

        val sends = plan(rule, "35=AJ|694=1|117=V-Q-1042-1|11=BUY-TRD-7|", venue).sends.map { it.to?.compId to it.render() }

        assertEquals("BUY-TRD-7", field(sends[0].second, 11))
        assertEquals("FIDLR1", field(sends[0].second, 375))
        assertEquals("D1-Q-88", field(sends[1].second, 117), "the dealer's own quote id, never the one the buy side saw")
        assertEquals("V-RFQ-1042", field(sends[1].second, 131))
        assertEquals("D2-551", field(sends[2].second, 117))
    }

    @Test
    fun `a reference a recipient cannot answer stops the step rather than sending an empty field`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "S",
                conditions = listOf(fromResponder, condition(131, Matcher.RfqState("open"))),
                steps = listOf(ResponseStep("35=S|131=\${to.131}|", to = "requester")),
            )
        val venue = TableVenue(mapOf(StepAddress.Requester to Resolution(listOf(recipient("FIBUY1", StepAddress.Requester)))))

        val send = plan(rule, "35=S|131=V-RFQ-1042|117=D1-Q-88|", venue).sends.single()

        val refusal = assertFailsWith<IllegalStateException> { send.render() }
        assertTrue(refusal.message!!.contains("FIBUY1 has no 131"), refusal.message)
    }

    // ------------------------------------------------------------------ nobody, and not delivered

    @Test
    fun `an address that reaches nobody sends nothing and says which step, and one that cannot be reached is owed`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "AJ",
                conditions = listOf(fromRequester, condition(117, Matcher.RfqState("open"))),
                steps =
                    listOf(
                        ResponseStep("35=AJ|694=1|117=\${to.117}|", to = "quoter"),
                        ResponseStep("35=AJ|694=5|117=\${to.117}|", to = "others"),
                    ),
            )
        val venue =
            TableVenue(
                mapOf(
                    StepAddress.Quoter to Resolution(notDelivered = listOf(recipient("FIDLR1", StepAddress.Quoter, "D1-Q-88"))),
                ),
            )

        val relayPlan = plan(rule, "35=AJ|694=1|117=V-Q-1|", venue)

        assertTrue(relayPlan.sends.isEmpty())
        assertEquals(listOf(0 to "FIDLR1"), relayPlan.notDelivered.map { (step, r) -> step to r.compId })
        assertEquals(listOf(1), relayPlan.nobody, "the others step reached nobody: a single-dealer RFQ has no others")
    }

    // ------------------------------------------------------------------ triggers that ask the venue

    private fun venueReading(
        role: PartyRole?,
        by131: String? = null,
        by117: String? = null,
        online: Boolean = true,
    ) = VenueReading(
        senderRole = role,
        rfqBy131 = by131?.let { RfqReading("RFQ-1", null, it) },
        rfqBy117 = by117?.let { RfqReading("RFQ-1", null, it) },
        respondersOnline = online,
    )

    @Test
    fun `a role condition and an RFQ condition are answered by the venue, and false with no venue`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "AJ",
                conditions = listOf(fromRequester, condition(117, Matcher.RfqState("open"))),
                steps = listOf(ResponseStep("35=AI|297=5|")),
            )
        val compiled = AcceptorResponder.compile(listOf(rule))
        val lift = AcceptorResponder.buildMessage("35=AJ|694=1|117=V-Q-1|49=FIBUY1|")

        assertNotNull(AcceptorResponder.firstMatch(compiled, lift, venue = venueReading(PartyRole.REQUESTER, by117 = "open")))
        assertNull(AcceptorResponder.firstMatch(compiled, lift, venue = venueReading(PartyRole.RESPONDER, by117 = "open")))
        assertNull(AcceptorResponder.firstMatch(compiled, lift, venue = venueReading(PartyRole.REQUESTER, by117 = "done")))
        assertNull(AcceptorResponder.firstMatch(compiled, lift), "with no venue to ask, a rule that asks does not fire")
    }

    @Test
    fun `whether responders are online is judged, and explain says what it read`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(fromRequester),
                whenResponders = "none",
                steps = listOf(ResponseStep("35=AG|131=\${req.131}|658=99|")),
            )
        val request = AcceptorResponder.buildMessage("35=R|131=Q-1|")

        val nobodyThere = AcceptorResponder.explain(listOf(rule), request, venue = venueReading(PartyRole.REQUESTER, online = false))
        val someoneThere = AcceptorResponder.explain(listOf(rule), request, venue = venueReading(PartyRole.REQUESTER, online = true))

        assertTrue(nobodyThere.single().selected)
        assertFalse(someoneThere.single().selected)
        assertEquals(true, someoneThere.single().responders?.actual)
        assertEquals(
            nobodyThere.single().selected,
            AcceptorResponder.firstMatch(
                AcceptorResponder.compile(listOf(rule)),
                request,
                venue = venueReading(PartyRole.REQUESTER, online = false),
            ) != null,
            "explain and firstMatch must agree",
        )
    }

    @Test
    fun `a rule that sends the quoter a fill is a trade, and one that only tells it is not`() {
        val books =
            AcceptorResponseRule(
                whenMsgType = "AJ",
                steps = listOf(ResponseStep("35=AJ|694=1|", to = "quoter"), ResponseStep("35=8|39=2|", to = "quoter")),
            )
        val tells = books.copy(steps = listOf(ResponseStep("35=AJ|694=1|", to = "quoter")))

        assertTrue(books.booksATrade())
        assertFalse(tells.booksATrade())
        assertFalse(books.copy(steps = listOf(ResponseStep("35=8|39=2|"))).booksATrade(), "a fill to the sender alone books nothing")
    }

    @Test
    fun `a relayed step's reason says where it came from and where it went`() {
        val reason =
            SendReason(
                source = SendReason.Source.RULE,
                at = LocalDateTime.of(2026, 9, 13, 9, 14, 21, 402_000_000),
                ruleIndex = 3,
                whenMsgType = "R",
                relay = RelayRef(1, "k", "FIBUY1", "R", "responders", "FIDLR1", "RFQ-1"),
            )

        assertEquals("sent by rule 4 — 35=R matched at 09:14:21.402 → responders FIDLR1, relayed from FIBUY1's 35=R", reason.line())
        assertEquals(RfqConstraint.OPEN.word, "open")
    }
}
