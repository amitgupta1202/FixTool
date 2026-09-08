package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.service.RfqVenuePreset
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The RFQ venue over a socket**, the claim `RfqVenuePresetTest` deliberately stops short of.
 *
 * Three things only bytes can prove for this bundle.
 *
 * The shorthands — `${random:…}` for each side and `${utcnow+30s}` for the validity — are rendered on
 * the dispatch thread of a loaded acceptor against a request that arrived over TCP, and a shorthand
 * that throws there is a venue that goes silent.
 *
 * **The quote book is fed from the wire**, which is the whole reason the venue can refuse a stale or a
 * spent quote. Nothing below tells it what state a quote is in: it sends real messages and the venue's
 * own memory of what it sent decides the answer. `AcceptorQuoteRulesTest` asks the rules that question
 * with a stated reading; this asks the venue.
 *
 * And the QuoteStatusReport and the booked trade are shapes chosen from the dictionary, so whether a
 * client's engine agrees is read back through its own parse and its own validation, which is the only
 * reader whose opinion counts.
 */
class AcceptorRfqVenueIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0

    // QuickFIX/J's session registry is static per JVM, so CompIDs reused across tests collide with a
    // session a previous test has not finished tearing down.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val venueCompId get() = "RFQVENUE$runId"
    private val clientCompId get() = "RFQCLIENT$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-rfq-venue", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        venuePort = TestPorts.free()
    }

    @After
    fun cleanup() {
        viewModel.disconnectAllSessions()
        awaitCondition(5_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    @Test
    fun `a quote request is answered with a priced quote whose id is the venue's own`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage("35=R|131=RFQ-1|146=1|55=EUR/USD|54=1|38=1000000", viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { incoming(client, "S").isNotEmpty() },
            "the RFQ venue should quote EUR/USD; got ${incoming(client).map { field(it, 35) }}",
        )
        val quote = incoming(client, "S").single()
        val quoted = RfqVenuePreset.QUOTED.first { it.symbol == "EUR/USD" }

        assertEquals("RFQ-1", field(quote, 131))
        val quoteId = assertNotNull(field(quote, 117), "a quote with no QuoteID cannot be answered")
        assertTrue("RFQ-1" !in quoteId, "the QuoteID must not be derivable from the request: $quoteId")
        assertEquals("1000000", field(quote, 135), "the quote is for the size asked")

        val bid = BigDecimal(assertNotNull(field(quote, 132)))
        val offer = BigDecimal(assertNotNull(field(quote, 133)))
        assertTrue(bid >= BigDecimal(quoted.bid.low) && bid <= BigDecimal(quoted.bid.high), "bid off band: $bid")
        assertTrue(
            offer >= BigDecimal(quoted.offer.low) && offer <= BigDecimal(quoted.offer.high),
            "offer off band: $offer",
        )
        assertTrue(bid < offer, "an inverted quote reached the wire: ${quote.rawMessage}")

        val validUntil = assertNotNull(field(quote, 62), "no ValidUntilTime in ${quote.rawMessage}")
        assertTrue(
            Regex("""^\d{8}-\d{2}:\d{2}:\d{2}\.\d{3}$""").matches(validUntil),
            "62 is a UTC timestamp: $validUntil",
        )
    }

    @Test
    fun `a QuoteResponse at the quoted price books a trade at that price`() {
        startVenue()
        val client = connectClient()

        val quote = quoteFor(client, "35=R|131=RFQ-2|146=1|55=USD/JPY|54=1|38=2000000")
        val offer = assertNotNull(field(quote, 133))

        client.sendFixMessage(
            "35=AJ|693=RESP-2|694=1|117=${field(quote, 117)}|11=TRADE-2|55=USD/JPY|54=1|38=2000000|44=$offer",
            viewModel.dictionary,
        )

        assertTrue(
            awaitCondition(15_000) { incoming(client, "8").isNotEmpty() },
            "the hit should be booked; got ${incoming(client).map { "${field(it, 35)}/${field(it, 297)}" }}",
        )
        val trade = incoming(client, "8").single()
        assertEquals("F", field(trade, 150))
        assertEquals("2", field(trade, 39))
        assertEquals(offer, field(trade, 31), "the trade is at the price the quote carried")
        assertEquals(offer, field(trade, 6))
        assertEquals("2000000", field(trade, 32))
        assertEquals("TRADE-2", field(trade, 11), "the report names the trade the client named")
        assertEquals("RESP-2", field(trade, 693))
        assertNotNull(field(trade, 37))
    }

    /**
     * **The refusal that needed a memory.** Nothing distinguishes these two messages but the fact that
     * the venue already answered the first one, and the venue is the only party that knows it.
     */
    @Test
    fun `a second hit on a quote the venue already booked is refused, not booked twice`() {
        startVenue()
        val client = connectClient()

        val quote = quoteFor(client, "35=R|131=RFQ-3|146=1|55=EUR/USD|54=1|38=1000000")
        val quoteId = assertNotNull(field(quote, 117))
        val offer = assertNotNull(field(quote, 133))
        val hit = { id: String -> "35=AJ|693=$id|694=1|117=$quoteId|11=T-$id|55=EUR/USD|54=1|38=1000000|44=$offer" }

        client.sendFixMessage(hit("RESP-A"), viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { incoming(client, "8").isNotEmpty() }, "the first hit was not booked")

        client.sendFixMessage(hit("RESP-B"), viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { incoming(client, "AI").isNotEmpty() },
            "the second hit should be refused; got ${incoming(client).map { field(it, 35) }}",
        )
        val refusal = incoming(client, "AI").single()
        assertEquals("RESP-B", field(refusal, 693))
        assertEquals("5", field(refusal, 297))
        assertEquals("Quote already answered", field(refusal, 58))
        assertEquals(1, incoming(client, "8").size, "the venue booked the same quote twice")
    }

    /**
     * **A quote goes stale on its own**, with nobody sending anything, which is why `expired` is a clock
     * comparison and not a state anything writes. The venue is started with a one-second validity so
     * the test can wait it out.
     */
    @Test
    fun `a hit that arrives after the quote's validity is refused as expired`() {
        startVenue(validitySeconds = 1)
        val client = connectClient()

        val quote = quoteFor(client, "35=R|131=RFQ-4|146=1|55=EUR/USD|54=1|38=1000000")
        val quoteId = assertNotNull(field(quote, 117))
        val offer = assertNotNull(field(quote, 133))

        Thread.sleep(1_500)
        client.sendFixMessage(
            "35=AJ|693=RESP-LATE|694=1|117=$quoteId|11=TRADE-LATE|55=EUR/USD|54=1|38=1000000|44=$offer",
            viewModel.dictionary,
        )

        assertTrue(
            awaitCondition(15_000) { incoming(client, "AI").isNotEmpty() },
            "a late hit should be refused; got ${incoming(client).map { field(it, 35) }}",
        )
        val refusal = incoming(client, "AI").single()
        assertEquals("7", field(refusal, 297), "297=7 is Expired")
        assertEquals(quoteId, field(refusal, 117))
        assertTrue(field(refusal, 58)!!.contains("expired"), refusal.rawMessage)
        assertTrue(incoming(client, "8").isEmpty(), "the venue booked a trade on an expired quote")
    }

    @Test
    fun `a hit on a quote this venue never sent is disowned rather than answered`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage(
            "35=AJ|693=RESP-X|694=1|117=not-a-quote-we-sent|11=TRADE-X|55=EUR/USD|54=1|38=1000000|44=1.09010",
            viewModel.dictionary,
        )

        assertTrue(
            awaitCondition(15_000) { incoming(client, "AI").isNotEmpty() },
            "got ${incoming(client).map { field(it, 35) }}",
        )
        val refusal = incoming(client, "AI").single()
        assertEquals("9", field(refusal, 297), "297=9 is Quote not found")
        assertTrue(field(refusal, 58)!!.contains("did not send"), refusal.rawMessage)
    }

    @Test
    fun `a pass, a counter and a hit at the wrong price are each answered with a QuoteStatusReport`() {
        startVenue()
        val client = connectClient()

        // Three quotes, because each of these responses is about a live quote and a pass closes the one
        // it answers. Before the venue had a memory this could be one quote and the order did not matter.
        val quotes =
            (1..3).map { n ->
                quoteFor(client, "35=R|131=RFQ-5$n|146=1|55=GBP/USD|54=1|38=1000000", expected = n)
            }

        fun id(n: Int) = field(quotes[n - 1], 117)

        client.sendFixMessage("35=AJ|693=RESP-P|694=6|117=${id(1)}|55=GBP/USD", viewModel.dictionary)
        client.sendFixMessage(
            "35=AJ|693=RESP-C|694=2|117=${id(2)}|11=TRADE-C|55=GBP/USD|54=1|38=1000000|44=1.27000",
            viewModel.dictionary,
        )
        client.sendFixMessage(
            "35=AJ|693=RESP-W|694=1|117=${id(3)}|11=TRADE-W|55=GBP/USD|54=1|38=1000000|44=1.20000",
            viewModel.dictionary,
        )

        assertTrue(
            awaitCondition(15_000) { incoming(client, "AI").size >= 3 },
            "three status reports expected; got ${incoming(client).map { "${field(it, 35)}/${field(it, 693)}" }}",
        )
        val byResponse = incoming(client, "AI").associateBy { field(it, 693) }
        assertEquals("11", field(byResponse.getValue("RESP-P"), 297), "a pass is 297=11")
        assertEquals("5", field(byResponse.getValue("RESP-C"), 297))
        assertTrue(field(byResponse.getValue("RESP-C"), 58)!!.contains("firm"))
        assertEquals("5", field(byResponse.getValue("RESP-W"), 297))
        assertTrue(field(byResponse.getValue("RESP-W"), 58)!!.contains("quoted price"))
        byResponse.forEach { (resp, report) ->
            val answered = quotes[listOf("RESP-P", "RESP-C", "RESP-W").indexOf(resp)]
            assertEquals(field(answered, 117), field(report, 117), "$resp names the wrong quote")
            assertEquals("GBP/USD", field(report, 55), "the symbol comes from the venue's own record")
        }
    }

    /**
     * **Nothing the RFQ venue sends is malformed**, judged by the client's own engine: every message it
     * received is validated against the loaded dictionary, body and all, and no session-level Reject
     * went back. The shapes were chosen from the dictionary, and this is the wire agreeing.
     */
    @Test
    fun `nothing the RFQ venue sends is malformed against the dictionary`() {
        startVenue()
        val client = connectClient()

        // A real quote first, so the responses that need one are about a quote the venue actually sent.
        val quote = quoteFor(client, "35=R|131=RFQ-1|146=1|55=EUR/USD|54=1|38=1000000")
        val quoteId = assertNotNull(field(quote, 117))
        val offer = assertNotNull(field(quote, 133))

        listOf(
            // Every kind of answer the venue has: a quote, both quote-request refusals, a booked hit,
            // a status report of each sort, and the reject a response nothing can answer earns.
            "35=R|131=RFQ-2|146=1|55=EUR/USD|54=1",
            "35=R|131=RFQ-3|146=1|55=XXX/YYY|54=1|38=1000000",
            "35=AJ|693=RESP-1|694=1|117=$quoteId|55=EUR/USD|54=1|44=$offer",
            "35=AJ|693=RESP-2|694=2|117=$quoteId|11=TRADE-2|55=EUR/USD|54=1|38=1000000|44=1.00000",
            "35=AJ|693=RESP-3|694=4|117=$quoteId|55=EUR/USD",
            "35=AJ|693=RESP-4|694=1|117=$quoteId|11=TRADE-4|55=EUR/USD|54=1|38=1000000|44=1.00000",
            "35=AJ|693=RESP-5|694=1|117=never-sent|11=TRADE-5|55=EUR/USD|54=1|38=1000000|44=1.00000",
            "35=AJ|693=RESP-6|694=1|11=TRADE-6|55=EUR/USD|54=1|38=1000000|44=$offer",
            // Last, because it books the quote and closes it for everything above.
            "35=AJ|693=RESP-7|694=1|117=$quoteId|11=TRADE-7|55=EUR/USD|54=1|38=1000000|44=$offer",
        ).forEach { client.sendFixMessage(it, viewModel.dictionary) }

        assertTrue(
            awaitCondition(15_000) { incoming(client).size >= 10 },
            "the venue went quiet: ${incoming(client).size} replies",
        )
        Thread.sleep(1_000)

        val empty =
            incoming(client).flatMap { m ->
                m.rawMessage.split('|').filter { it.isNotBlank() && it.endsWith("=") }
            }
        assertTrue(empty.isEmpty(), "the venue put ${empty.joinToString()} on the wire")

        val unevaluated = incoming(client).filter { it.rawMessage.contains("\${") }
        assertTrue(
            unevaluated.isEmpty(),
            "unevaluated expressions reached the wire: ${unevaluated.map { it.rawMessage }}",
        )

        val rejects = incoming(client).filter { field(it, 35) == "3" }
        assertTrue(rejects.isEmpty(), "the client rejected something the venue sent: ${rejects.map { it.rawMessage }}")

        val dd = assertNotNull(viewModel.dictionary.getDataDictionary(), "the test needs the bundled dictionary")
        incoming(client).forEach { m ->
            val parsed = assertNotNull(m.quickfixMessage, "the client could not parse ${m.rawMessage}")
            runCatching { dd.validate(parsed, true) }
                .onFailure { throw AssertionError("malformed against FIX 4.4: ${it.message} in ${m.rawMessage}", it) }
        }
        val kinds = incoming(client).map { field(it, 35) }.toSet()
        assertTrue(
            kinds.containsAll(setOf("S", "AG", "8", "AI", "j")),
            "every kind of answer the venue has was exercised, got $kinds",
        )
        val statuses = incoming(client, "AI").mapNotNull { field(it, 297) }.toSet()
        assertTrue(statuses.containsAll(setOf("5", "9")), "the refusals the book decides were exercised: $statuses")
    }

    // ---------------------------------------------------------------- helpers

    /** Asks for a quote and returns the one that came back. [expected] is how many should exist by then. */
    private fun quoteFor(client: FixMessageSession, request: String, expected: Int = 1): FixMessage {
        client.sendFixMessage(request, viewModel.dictionary)
        assertTrue(
            awaitCondition(15_000) { incoming(client, "S").size >= expected },
            "no quote came back for $request; got ${incoming(client).map { field(it, 35) }}",
        )
        return incoming(client, "S")[expected - 1]
    }

    private fun startVenue(validitySeconds: Int = RfqVenuePreset.VALIDITY_SECONDS) {
        val preset = AcceptorPresets.byId(RfqVenuePreset.ID)!!.copy(rules = RfqVenuePreset.rules(validitySeconds))
        val rules = AcceptorPresets.insert(emptyList(), preset).rules
        val profile =
            FixConnectionProfile(
                name = "RFQ VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = venueCompId,
                        targetCompID = clientCompId,
                        port = venuePort.toString(),
                        socketAcceptPort = venuePort.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "venuestore").absolutePath,
                        fileLogPath = File(testDir, "venuelog").absolutePath,
                        acceptorResponseRules = rules,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "RFQ VENUE" } },
            "the venue should be listening on $venuePort",
        )
    }

    private fun connectClient(): FixMessageSession {
        val profile =
            FixConnectionProfile(
                name = "RFQ CLIENT",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = clientCompId,
                        targetCompID = venueCompId,
                        host = "localhost",
                        port = venuePort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "clientstore").absolutePath,
                        fileLogPath = File(testDir, "clientlog").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        val session = viewModel.sessions.first { it.title == "RFQ CLIENT" }
        assertTrue(
            awaitCondition(15_000) { session.connectionState.value == FixConnectionState.LOGGED_ON },
            "the client should log on to the RFQ venue",
        )
        return session
    }

    /** What the client received, oldest first, optionally only one MsgType. */
    private fun incoming(session: FixMessageSession, msgType: String? = null): List<FixMessage> =
        session.messages.value
            .filterIsInstance<FixMessage>()
            .filter { it.direction == FixMessage.Direction.INCOMING }
            .filter { msgType == null || field(it, 35) == msgType }

    private fun field(message: FixMessage, tag: Int): String? =
        message.rawMessage
            .split('|')
            .firstOrNull { it.startsWith("$tag=") }
            ?.substringAfter('=')

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        // A torn read is 'not yet', not 'no' -- see [settled].
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
    }
}
