package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MatcherCodec
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A dealer that answers by itself**: rules on an initiator, which is how a client plays the other side of a venue
 * that relays. See `docs/rfq-relay-impl-plan.md`, step 7.
 *
 * The venue relays; the dealer is a FixTool client with one quote rule; the buy side is a plain client. Every
 * assertion is on what the buy side receives, because that is the only evidence a dealer answered at all.
 */
class DealerRulesIntegrationTest {
    private lateinit var venue: VenueHarness

    @Before
    fun setup() {
        venue = VenueHarness("fixtool-dealer-rules")
    }

    @After
    fun cleanup() {
        venue.close()
    }

    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    /** Just enough platform to carry one RFQ: relay the request to the dealers, relay each quote to the buy side. */
    private val platform =
        listOf(
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(condition(49, Matcher.CounterpartyRole("requester"))),
                steps = listOf(ResponseStep("35=R|131=\${req.uuid}|146=1|55=\${req.55}|", to = "responders")),
            ),
            AcceptorResponseRule(
                whenMsgType = "S",
                conditions = listOf(condition(49, Matcher.CounterpartyRole("responder")), condition(131, Matcher.RfqState("open"))),
                steps = listOf(ResponseStep("35=S|131=\${to.131}|117=\${uuid:10}|55=\${req.55}|133=\${req.133}|", to = "requester")),
            ),
        )

    /** The dealer's whole brain: quote every request it is asked, at one level, under an id of its own. */
    private val quoteEverything =
        listOf(
            AcceptorResponseRule(
                whenMsgType = "R",
                steps = listOf(ResponseStep("35=S|131=\${req.131}|117=\${uuid:10}|55=\${req.55}|133=99.5|")),
            ),
        )

    private fun request(buyer: FixMessageSession, reqId: String) =
        venue.send(buyer, "35=R|131=$reqId|146=1|55=T 4.25 11/15/36|54=1|38=10000000")

    private fun quotesFor(buyer: FixMessageSession, n: Int): Boolean = venue.awaitCondition(15_000) { venue.incoming(buyer, "S").size >= n }

    @Test
    fun `a dealer with a quote rule answers the request a venue relays to it`() {
        venue.connectVenue(
            platform,
            listOf(Counterparty(venue.comp("BUY1"), "requester"), Counterparty(venue.comp("DLR1"), "responder")),
        )
        val buyer = venue.connectClient("BUY1")
        val dealer = venue.connectClient("DLR1", rules = quoteEverything)

        request(buyer, "BUY-RFQ-1")

        assertTrue(quotesFor(buyer, 1), "the dealer's rule should have quoted, and the venue relayed it")
        val quote = venue.incoming(buyer, "S").single()
        assertEquals("99.5", venue.field(quote, 133))
        assertEquals("BUY-RFQ-1", venue.field(quote, 131), "the buy side sees its own id, whatever the dealer answered with")
        // A pane draws what it received on its own schedule, so the buy side can hold the quote before the dealer's
        // pane has drawn the request that caused it.
        assertTrue(venue.awaitCondition(5_000) { venue.incoming(dealer, "R").isNotEmpty() })
        assertTrue(
            venue.awaitCondition(5_000) { (dealer.acceptorStatus()?.responsesSent ?: 0) >= 1 },
            "the dealer reports what its rules sent, as an acceptor does",
        )
    }

    @Test
    fun `an initiator connected with no rules starts answering when Save gives it one`() {
        venue.connectVenue(
            platform,
            listOf(Counterparty(venue.comp("BUY1"), "requester"), Counterparty(venue.comp("DLR1"), "responder")),
        )
        val buyer = venue.connectClient("BUY1")
        val dealer = venue.connectClient("DLR1")

        request(buyer, "BUY-RFQ-1")
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(dealer, "R").isNotEmpty() })
        Thread.sleep(500)
        assertTrue(venue.incoming(buyer, "S").isEmpty(), "with no rules the dealer is the plain client it always was")

        val saved = venue.viewModel.connectionProfiles.first { it.name == "DLR1" }
        venue.viewModel.saveConnectionProfile(saved.copy(config = saved.config.copy(acceptorResponseRules = quoteEverything)))

        request(buyer, "BUY-RFQ-2")
        assertTrue(quotesFor(buyer, 1), "the saved rule reaches the live session and answers the next request")
        assertEquals("BUY-RFQ-2", venue.field(venue.incoming(buyer, "S").single(), 131))
    }

    @Test
    fun `every lane of a dealer load client answers, and the venue reaches them through one family`() {
        venue.connectVenue(
            platform,
            listOf(Counterparty(venue.comp("BUY1"), "requester"), Counterparty("${venue.comp("DLRLG")}_*", "responder")),
        )
        val buyer = venue.connectClient("BUY1")
        val lanes = venue.connectLanes("DLRLG", lanes = 5, rules = quoteEverything)

        request(buyer, "BUY-RFQ-1")

        assertTrue(quotesFor(buyer, 5), "five lanes, five quotes: saw ${venue.incoming(buyer, "S").size}")
        assertTrue(venue.awaitCondition(5_000) { lanes.all { venue.incoming(it, "R").size == 1 } }, "each lane was asked once")
        val ids = venue.incoming(buyer, "S").map { venue.field(it, 131) }
        assertEquals(listOf("BUY-RFQ-1"), ids.distinct(), "all on the buy side's own id")
    }
}
