package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.LegOutcome
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.RfqLife
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MatcherCodec
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **Two parties, one venue, on real sockets**: a buy side asks, the venue relays the request to the dealers,
 * each dealer's quote is relayed back under the venue's own id, and a lift is confirmed to both sides.
 *
 * Every assertion is about a message being where it belongs, **with the ids that side is allowed to see**,
 * and nowhere else. See `docs/rfq-relay-proposal.md`, the slice A acceptance criteria, and the implementation
 * plan's decisions R2 and R3, which several of these exist to hold.
 */
class RfqRelayIntegrationTest {
    private lateinit var venue: VenueHarness

    @Before
    fun setup() {
        venue = VenueHarness("fixtool-rfq-relay")
    }

    @After
    fun cleanup() {
        venue.close()
    }

    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private val fromRequester = condition(49, Matcher.CounterpartyRole("requester"))
    private val fromResponder = condition(49, Matcher.CounterpartyRole("responder"))
    private val rfqOpenBy131 = condition(131, Matcher.RfqState("open"))
    private val rfqOpenBy117 = condition(117, Matcher.RfqState("open"))
    private val rfqDoneBy117 = condition(117, Matcher.RfqState("done"))

    /** A platform's rules, in the order they are read: relay and entitlement, and nothing priced by the venue. */
    private fun platformRules(): List<AcceptorResponseRule> =
        listOf(
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(fromResponder),
                steps = listOf(ResponseStep("35=AG|131=\${req.131}|658=6|146=1|55=\${req.55}|")),
            ),
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(fromRequester),
                steps =
                    listOf(
                        ResponseStep("35=R|131=\${req.uuid}|146=1|55=\${req.55}|54=\${req.54}|38=\${req.38}|", to = "responders"),
                    ),
            ),
            AcceptorResponseRule(
                whenMsgType = "S",
                conditions = listOf(fromResponder, rfqOpenBy131),
                steps =
                    listOf(
                        ResponseStep("35=S|131=\${to.131}|117=\${uuid:10}|55=\${req.55}|133=\${req.133}|", to = "requester"),
                    ),
            ),
            AcceptorResponseRule(
                whenMsgType = "S",
                steps = listOf(ResponseStep("35=AI|117=\${req.117}|131=\${req.131}|297=5|55=\${req.55}|")),
            ),
            AcceptorResponseRule(
                whenMsgType = "AJ",
                conditions = listOf(fromRequester, rfqOpenBy117, condition(694, Matcher.Exact("1")), condition(54, Matcher.Exact("1"))),
                steps =
                    listOf(
                        ResponseStep(
                            "35=8|37=\${req.uuid}|11=\${req.11}|693=\${req.693}|17=\${uuid}|150=F|39=2|54=1|55=\${req.55}|" +
                                "31=\${req.44}|32=\${req.38}|14=\${req.38}|151=0|6=\${req.44}|",
                        ),
                        ResponseStep(
                            "35=AJ|693=\${uuid:10}|694=1|117=\${to.117}|11=\${uuid:10}|54=1|55=\${req.55}|38=\${req.38}|44=\${req.44}|",
                            to = "quoter",
                        ),
                        ResponseStep(
                            "35=8|37=\${req.uuid}|11=\${to.11}|17=\${uuid}|150=F|39=2|54=2|55=\${req.55}|" +
                                "31=\${req.44}|32=\${req.38}|14=\${req.38}|151=0|6=\${req.44}|",
                            to = "quoter",
                        ),
                        ResponseStep("35=AJ|693=\${uuid:10}|694=4|117=\${to.117}|55=\${req.55}|", to = "cover"),
                        ResponseStep("35=AJ|693=\${uuid:10}|694=5|117=\${to.117}|55=\${req.55}|", to = "others"),
                    ),
            ),
            AcceptorResponseRule(
                whenMsgType = "AJ",
                conditions = listOf(fromRequester, rfqOpenBy117, condition(694, Matcher.Exact("6"))),
                steps =
                    listOf(
                        ResponseStep("35=AJ|693=\${uuid:10}|694=6|117=\${to.117}|55=\${req.55}|", to = "quoted"),
                        ResponseStep("35=AI|117=\${req.117}|693=\${req.693}|297=11|55=\${req.55}|"),
                    ),
            ),
            AcceptorResponseRule(
                whenMsgType = "AJ",
                conditions = listOf(fromRequester, rfqDoneBy117),
                steps = listOf(ResponseStep("35=AI|117=\${req.117}|693=\${req.693}|297=5|55=\${req.55}|")),
            ),
        )

    private fun declared(vararg buyers: String, dealers: List<String>) =
        buyers.map { Counterparty(venue.comp(it), "requester") } + dealers.map { Counterparty(venue.comp(it), "responder") }

    private fun request(buyer: FixMessageSession, reqId: String) =
        venue.send(buyer, "35=R|131=$reqId|146=1|55=T 4.25 11/15/36|54=1|38=10000000")

    /** Waits for [dealer] to see its [n]th QuoteRequest and returns the QuoteReqID the venue gave it. */
    private fun venueRfqId(dealer: FixMessageSession, n: Int = 1): String {
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(dealer, "R").size >= n }, "the dealer should be asked")
        return venue.field(venue.incoming(dealer, "R")[n - 1], 131)!!
    }

    private fun quote(dealer: FixMessageSession, rfqId: String, quoteId: String, offer: String) =
        venue.send(dealer, "35=S|131=$rfqId|117=$quoteId|55=T 4.25 11/15/36|133=$offer")

    private fun awaitQuotes(buyer: FixMessageSession, n: Int) =
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "S").size >= n }, "the buy side should see $n quote(s)")

    private fun lift(buyer: FixMessageSession, venueQuoteId: String, clOrdId: String, price: String) =
        venue.send(buyer, "35=AJ|693=RESP-$clOrdId|694=1|117=$venueQuoteId|11=$clOrdId|54=1|55=T 4.25 11/15/36|38=10000000|44=$price")

    @Test
    fun `a request reaches every dealer re-keyed and nobody else, and a declared dealer who is not there is counted`() {
        venue.connectVenue(platformRules(), declared("BUY1", "BUY2", dealers = listOf("DLR1", "DLR2", "DLR3")))
        val buyer = venue.connectClient("BUY1")
        val otherBuyer = venue.connectClient("BUY2")
        val dealer1 = venue.connectClient("DLR1")
        val dealer2 = venue.connectClient("DLR2")

        request(buyer, "BUY-RFQ-7")

        val id1 = venueRfqId(dealer1)
        val id2 = venueRfqId(dealer2)
        assertNotEquals("BUY-RFQ-7", id1, "the dealer must never see the buy side's own id")
        assertEquals(id1, id2, "one venue id for every dealer asked, from \${req.uuid}")
        assertEquals("T 4.25 11/15/36", venue.field(venue.incoming(dealer1, "R").single(), 55))
        assertTrue(venue.incoming(otherBuyer, "R").isEmpty(), "the other buy side is not a responder")
        assertTrue(
            venue.awaitCondition(5_000) { (venue.venuePane().acceptorStatus()?.notDelivered ?: 0) >= 1 },
            "DLR3 is declared and never connected, so its request is counted, not lost",
        )
    }

    /** Ids are unique per counterparty: the venue must keep two buy sides' identical QuoteReqIDs apart. */
    @Test
    fun `two buy sides with the same QuoteReqID each get their own quote back under their own id`() {
        venue.connectVenue(platformRules(), declared("BUY1", "BUY2", dealers = listOf("DLR1")))
        val buyer = venue.connectClient("BUY1")
        val otherBuyer = venue.connectClient("BUY2")
        val dealer = venue.connectClient("DLR1")

        request(buyer, "RFQ-1")
        val forBuyer = venueRfqId(dealer, 1)
        request(otherBuyer, "RFQ-1")
        val forOtherBuyer = venueRfqId(dealer, 2)
        assertNotEquals(forBuyer, forOtherBuyer, "two RFQs, two venue ids")

        quote(dealer, forOtherBuyer, "D-Q-2", "98.531250")
        quote(dealer, forBuyer, "D-Q-1", "98.515625")
        awaitQuotes(buyer, 1)
        awaitQuotes(otherBuyer, 1)

        assertEquals("98.515625", venue.field(venue.incoming(buyer, "S").single(), 133))
        assertEquals("RFQ-1", venue.field(venue.incoming(buyer, "S").single(), 131))
        assertEquals("98.531250", venue.field(venue.incoming(otherBuyer, "S").single(), 133))
    }

    @Test
    fun `a lift confirms both sides with one OrderID, tells the cover, refuses a second lift and a late quote`() {
        val venuePane = venue.connectVenue(platformRules(), declared("BUY1", dealers = listOf("DLR1", "DLR2")))
        val buyer = venue.connectClient("BUY1")
        val dealer1 = venue.connectClient("DLR1")
        val dealer2 = venue.connectClient("DLR2")

        request(buyer, "BUY-RFQ-7")
        val rfqId = venueRfqId(dealer1)
        venueRfqId(dealer2)
        quote(dealer1, rfqId, "D1-Q-88", "98.515625")
        awaitQuotes(buyer, 1)
        quote(dealer2, rfqId, "D2-551", "98.531250")
        awaitQuotes(buyer, 2)
        val shown = venue.incoming(buyer, "S").associate { venue.field(it, 133) to venue.field(it, 117)!! }
        assertTrue(shown.values.none { it.startsWith("D1") || it.startsWith("D2") }, "the buy side sees venue quote ids only: $shown")

        lift(buyer, shown.getValue("98.515625"), "BUY-TRD-7", "98.515625")
        // Straight after, the other quote: decision R3 says this reads the trade that was already decided.
        lift(buyer, shown.getValue("98.531250"), "BUY-TRD-8", "98.531250")

        assertTrue(venue.awaitCondition(10_000) { venue.incoming(dealer1, "8").isNotEmpty() && venue.incoming(buyer, "8").isNotEmpty() })
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "AI").isNotEmpty() }, "the second lift is answered")

        val buyerFill = venue.incoming(buyer, "8").single()
        val dealerFill = venue.incoming(dealer1, "8").single()
        assertEquals(venue.field(buyerFill, 37), venue.field(dealerFill, 37), "one OrderID, the id both sides reconcile on")
        assertEquals("1", venue.field(buyerFill, 54))
        assertEquals("2", venue.field(dealerFill, 54), "the dealer's fill is on its own side")
        assertEquals("BUY-TRD-7", venue.field(buyerFill, 11))
        assertEquals("D1-Q-88", venue.field(venue.incoming(dealer1, "AJ").single(), 117), "the dealer is told of its own quote")
        assertEquals(venue.field(venue.incoming(dealer1, "AJ").single(), 11), venue.field(dealerFill, 11), "\${to.11} read step 2's id")
        assertEquals("5", venue.field(venue.incoming(buyer, "AI").single(), 297), "the second lift is refused")
        assertTrue(venue.awaitCondition(5_000) { venue.incoming(dealer2, "AJ").isNotEmpty() })
        val toCover = venue.incoming(dealer2, "AJ").single()
        assertEquals("4", venue.field(toCover, 694))
        assertEquals("D2-551", venue.field(toCover, 117))
        assertTrue(venue.incoming(dealer2, "8").isEmpty(), "the cover did not trade")

        quote(dealer2, rfqId, "D2-552", "98.500000")
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(dealer2, "AI").isNotEmpty() }, "a late quote is refused to its dealer")
        assertEquals(2, venue.incoming(buyer, "S").size, "and never relayed")

        // Probe P3: the relay's reason is recorded by the recipient's own toApp, on the dealer's pane, which is what
        // Trace will join the two legs through.
        val relayedFill =
            venue.awaitPane("DLR1").messages.value
                .filterIsInstance<com.knapsack.fixtool.model.FixMessage>()
                .single { it.direction == com.knapsack.fixtool.model.FixMessage.Direction.OUTGOING && venue.field(it, 35) == "8" }
        assertEquals("quoter", relayedFill.sendReason?.relay?.address)
        assertEquals(venue.comp("BUY1"), relayedFill.sendReason?.relay?.triggerCompId)

        // One negotiation across every pane: the buy side's own request and the dealer's fill share no id, and the
        // relay edges the venue recorded are what put them in one trace.
        val panes = venue.viewModel.sessions.toList()
        val snapshots = panes.map { pane -> pane.messages.value.filterIsInstance<com.knapsack.fixtool.model.FixMessage>() }
        val traces = com.knapsack.fixtool.service.Traces.group(snapshots, null).traces
        val requestAt = snapshots[panes.indexOf(buyer)].indexOfFirst { venue.field(it, 35) == "R" }
        val fillAt = snapshots[panes.indexOf(dealer1)].indexOfFirst { venue.field(it, 35) == "8" }
        val negotiation = traces.single { com.knapsack.fixtool.service.Located(panes.indexOf(buyer), requestAt) in it.members }
        assertTrue(
            com.knapsack.fixtool.service.Located(panes.indexOf(dealer1), fillAt) in negotiation.members,
            "the dealer's fill is in the buy side's trace",
        )

        val book = venuePane.venueService()!!.rfqBookView().rfqs.single()
        assertEquals(RfqLife.DONE, book.life)
        assertEquals(LegOutcome.LIFTED, book.legs.single { it.compId == venue.comp("DLR1") }.outcome)
        assertEquals(LegOutcome.COVER, book.legs.single { it.compId == venue.comp("DLR2") }.outcome)
        listOf("BUY1", "DLR1", "DLR2").forEach { name ->
            assertEquals(0L, venue.awaitPane(name).orderBook()?.unattributedCount ?: 0L, "nothing unattributed on $name's pane")
        }
    }

    @Test
    fun `a lift of a level its dealer has since replaced is refused`() {
        venue.connectVenue(platformRules(), declared("BUY1", dealers = listOf("DLR1")))
        val buyer = venue.connectClient("BUY1")
        val dealer = venue.connectClient("DLR1")

        request(buyer, "BUY-RFQ-9")
        val rfqId = venueRfqId(dealer)
        quote(dealer, rfqId, "D1-Q-1", "98.515625")
        awaitQuotes(buyer, 1)
        val oldLevel = venue.field(venue.incoming(buyer, "S").single(), 117)!!
        quote(dealer, rfqId, "D1-Q-2", "98.531250")
        awaitQuotes(buyer, 2)

        lift(buyer, oldLevel, "BUY-TRD-9", "98.515625")

        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "AI").isNotEmpty() })
        assertEquals("5", venue.field(venue.incoming(buyer, "AI").single(), 297))
        assertTrue(venue.incoming(dealer, "8").isEmpty(), "nothing traded on a level that no longer stood")
    }

    @Test
    fun `a pass tells the dealers who quoted and nobody else`() {
        venue.connectVenue(platformRules(), declared("BUY1", dealers = listOf("DLR1", "DLR2")))
        val buyer = venue.connectClient("BUY1")
        val dealer1 = venue.connectClient("DLR1")
        val dealer2 = venue.connectClient("DLR2")

        request(buyer, "BUY-RFQ-5")
        val rfqId = venueRfqId(dealer1)
        venueRfqId(dealer2)
        quote(dealer1, rfqId, "D1-Q-5", "98.515625")
        awaitQuotes(buyer, 1)

        venue.send(buyer, "35=AJ|693=RESP-P|694=6|117=${venue.field(venue.incoming(buyer, "S").single(), 117)}|55=T 4.25 11/15/36")

        assertTrue(venue.awaitCondition(10_000) { venue.incoming(dealer1, "AJ").isNotEmpty() })
        assertEquals("6", venue.field(venue.incoming(dealer1, "AJ").single(), 694))
        assertEquals("D1-Q-5", venue.field(venue.incoming(dealer1, "AJ").single(), 117))
        assertTrue(venue.awaitCondition(5_000) { venue.incoming(buyer, "AI").isNotEmpty() })
        Thread.sleep(300)
        assertTrue(venue.incoming(dealer2, "AJ").isEmpty(), "a dealer who never quoted is not told of a pass")
    }

    @Test
    fun `a dealer that asks for a quote is refused, and no buy side hears of it`() {
        venue.connectVenue(platformRules(), declared("BUY1", dealers = listOf("DLR1")))
        val buyer = venue.connectClient("BUY1")
        val dealer = venue.connectClient("DLR1")

        venue.send(dealer, "35=R|131=D-RFQ-1|146=1|55=T 4.25 11/15/36|54=1|38=10000000")

        assertTrue(venue.awaitCondition(10_000) { venue.incoming(dealer, "AG").isNotEmpty() })
        assertEquals("6", venue.field(venue.incoming(dealer, "AG").single(), 658))
        assertTrue(venue.incoming(buyer, "R").isEmpty())
    }
}
