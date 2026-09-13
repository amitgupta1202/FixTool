package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.RfqLife
import com.knapsack.fixtool.model.WHEN_RFQ_EXPIRES
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MatcherCodec
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **An RFQ nobody trades still ends**, told to both sides by a venue that nobody sent anything to.
 *
 * Each RFQ here names its own ExpireTime a moment ahead, so a test waits a second or two for the clock instead of a
 * venue's default. What is checked is what the parties receive, on real sessions: the requester and every dealer
 * quoting told, each by the id they know; the requester told when nobody quoted; nothing told for an RFQ that traded
 * first; and the dealers still told when the requester has gone. See `docs/rfq-relay-proposal.md`, decision 10.
 */
class RfqExpiryIntegrationTest {
    private lateinit var venue: VenueHarness

    @Before
    fun setup() {
        venue = VenueHarness("fixtool-rfq-expiry")
    }

    @After
    fun cleanup() {
        venue.close()
    }

    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private val fromRequester = condition(49, Matcher.CounterpartyRole("requester"))
    private val fromResponder = condition(49, Matcher.CounterpartyRole("responder"))

    private fun rules(): List<AcceptorResponseRule> =
        listOf(
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(fromRequester),
                steps = listOf(ResponseStep("35=R|131=\${req.uuid}|146=1|55=\${req.55}|38=\${req.38}|", to = "responders")),
            ),
            AcceptorResponseRule(
                whenMsgType = "S",
                conditions = listOf(fromResponder, condition(131, Matcher.RfqState("open"))),
                steps = listOf(ResponseStep("35=S|131=\${to.131}|117=\${uuid:10}|55=\${req.55}|133=\${req.133}|", to = "requester")),
            ),
            AcceptorResponseRule(
                whenMsgType = "AJ",
                conditions = listOf(fromRequester, condition(117, Matcher.RfqState("open")), condition(694, Matcher.Exact("1"))),
                steps =
                    listOf(
                        ResponseStep("35=8|37=\${req.uuid}|11=\${req.11}|17=\${uuid}|150=F|39=2|54=1|55=\${req.55}|"),
                        ResponseStep("35=8|37=\${req.uuid}|11=\${uuid:10}|17=\${uuid}|150=F|39=2|54=2|55=\${req.55}|", to = "quoter"),
                    ),
            ),
            AcceptorResponseRule(
                whenMsgType = WHEN_RFQ_EXPIRES,
                whenQuotes = "some",
                steps =
                    listOf(
                        ResponseStep("35=AI|117=\${to.117}|131=\${to.131}|55=\${req.55}|297=7|", to = "quotes"),
                        ResponseStep("35=AI|117=\${to.117}|131=\${to.131}|55=\${req.55}|297=7|", to = "quoted"),
                    ),
            ),
            AcceptorResponseRule(
                whenMsgType = WHEN_RFQ_EXPIRES,
                whenQuotes = "none",
                steps = listOf(ResponseStep("35=AG|131=\${to.131}|658=99|146=1|55=\${req.55}|58=Expired unquoted|", to = "requester")),
            ),
        )

    private fun connect(vararg dealers: String) =
        venue.connectVenue(
            rules(),
            listOf(Counterparty(venue.comp("BUY1"), "requester")) + dealers.map { Counterparty(venue.comp(it), "responder") },
        )

    private fun expiresIn(millis: Long): String =
        LocalDateTime.now(ZoneOffset.UTC).plusNanos(millis * 1_000_000).format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"))

    private fun request(buyer: com.knapsack.fixtool.model.FixMessageSession, reqId: String, expiresInMillis: Long) =
        venue.send(buyer, "35=R|131=$reqId|146=1|55=T 4.25 11/15/36|38=10000000|126=${expiresIn(expiresInMillis)}")

    private fun askedAs(dealer: com.knapsack.fixtool.model.FixMessageSession): String {
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(dealer, "R").isNotEmpty() }, "the dealer should be asked")
        return venue.field(venue.incoming(dealer, "R").last(), 131)!!
    }

    private fun book() =
        venue
            .venuePane()
            .venueService()!!
            .rfqBook
            .view()
            .rfqs

    @Test
    fun `an RFQ that expires with quotes ends each one by name, to the requester and to every dealer quoting`() {
        connect("DLR1", "DLR2")
        val buyer = venue.connectClient("BUY1")
        val dealer1 = venue.connectClient("DLR1")
        val dealer2 = venue.connectClient("DLR2")

        request(buyer, "EXP-1", expiresInMillis = 2_500)
        venue.send(dealer1, "35=S|131=${askedAs(dealer1)}|117=D1-Q|55=T 4.25 11/15/36|133=98.515625")
        venue.send(dealer2, "35=S|131=${askedAs(dealer2)}|117=D2-Q|55=T 4.25 11/15/36|133=98.531250")
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "S").size == 2 }, "both quotes are shown")
        val shown = venue.incoming(buyer, "S").map { venue.field(it, 117)!! }.toSet()

        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "AI").size == 2 }, "the buy side is told of each quote")
        assertTrue(venue.awaitCondition(5_000) { venue.incoming(dealer1, "AI").isNotEmpty() && venue.incoming(dealer2, "AI").isNotEmpty() })

        val toBuyer = venue.incoming(buyer, "AI")
        assertEquals(shown, toBuyer.map { venue.field(it, 117)!! }.toSet(), "each notice names a quote as the buy side was shown it")
        assertTrue(toBuyer.all { venue.field(it, 297) == "7" && venue.field(it, 131) == "EXP-1" })
        assertEquals("D1-Q", venue.field(venue.incoming(dealer1, "AI").single(), 117), "a dealer is told of its own quote")
        assertEquals("D2-Q", venue.field(venue.incoming(dealer2, "AI").single(), 117))
        assertTrue(venue.incoming(buyer, "AG").isEmpty(), "quoted is not unquoted")
        assertEquals(RfqLife.EXPIRED, book().single().life)
    }

    @Test
    fun `an RFQ that expires unquoted is refused to its requester, under the requester's own id`() {
        connect("DLR1")
        val buyer = venue.connectClient("BUY1")
        val dealer = venue.connectClient("DLR1")

        request(buyer, "EXP-2", expiresInMillis = 1_500)
        askedAs(dealer)

        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "AG").isNotEmpty() }, "the buy side is told")
        val refused = venue.incoming(buyer, "AG").single()
        assertEquals("EXP-2", venue.field(refused, 131))
        assertEquals("99", venue.field(refused, 658))
        assertTrue(venue.incoming(dealer, "AI").isEmpty(), "a dealer that never quoted has no quote to end")
    }

    /** The timer is disarmed when the RFQ trades, and a timer that fired anyway would find nothing to end. */
    @Test
    fun `an RFQ that traded before its time is not told it expired`() {
        connect("DLR1")
        val buyer = venue.connectClient("BUY1")
        val dealer = venue.connectClient("DLR1")

        request(buyer, "EXP-3", expiresInMillis = 2_000)
        venue.send(dealer, "35=S|131=${askedAs(dealer)}|117=D1-Q3|55=T 4.25 11/15/36|133=98.515625")
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "S").isNotEmpty() })
        val quoteId = venue.field(venue.incoming(buyer, "S").single(), 117)
        venue.send(buyer, "35=AJ|693=R-3|694=1|117=$quoteId|11=T-3|54=1|55=T 4.25 11/15/36|38=10000000|44=98.515625")
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "8").isNotEmpty() && venue.incoming(dealer, "8").isNotEmpty() })

        Thread.sleep(3_000)
        assertTrue(venue.incoming(buyer, "AI").isEmpty() && venue.incoming(buyer, "AG").isEmpty(), "the trade was the end")
        assertTrue(venue.incoming(dealer, "AI").isEmpty())
        assertEquals(RfqLife.DONE, book().single().life)
    }

    /** A requester logging out does not end an RFQ its dealers are still quoting on: they are owed the end. */
    @Test
    fun `the dealers are still told when the requester has gone before its RFQ expires`() {
        connect("DLR1")
        val buyer = venue.connectClient("BUY1")
        val dealer = venue.connectClient("DLR1")

        request(buyer, "EXP-4", expiresInMillis = 3_000)
        venue.send(dealer, "35=S|131=${askedAs(dealer)}|117=D1-Q4|55=T 4.25 11/15/36|133=98.515625")
        assertTrue(venue.awaitCondition(10_000) { venue.incoming(buyer, "S").isNotEmpty() })
        buyer.disconnect()
        assertTrue(venue.awaitCondition(10_000) { buyer.connectionState.value != FixConnectionState.LOGGED_ON })

        assertTrue(venue.awaitCondition(10_000) { venue.incoming(dealer, "AI").isNotEmpty() }, "the dealer is told its quote ended")
        assertEquals("D1-Q4", venue.field(venue.incoming(dealer, "AI").single(), 117))
    }
}
