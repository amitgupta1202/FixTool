package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.service.FxVenuePreset
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The FX venue over a socket** — the claim no unit test can make.
 *
 * `FxVenuePresetTest` asks the engine what the bundle renders. That deliberately stops short of the
 * wire, and for this bundle the gap is wider than usual, because two of the things it does are things
 * only bytes can prove:
 *
 * - a **repeating group** in a reply. A rendered template is a string with `146=1|55=…` in it; whether
 *   the counterparty parses that as a group depends on how the message was *built*, and on the field
 *   order QuickFIX/J puts on the wire.
 * - a **live expression**. Every price here is evaluated as the step is sent, on the dispatch thread of
 *   a loaded acceptor, against a request that arrived over TCP. A template that renders in a test and
 *   throws on that thread is a venue that goes silent, and the silence is all anyone would see.
 */
class AcceptorFxVenueIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0

    // QuickFIX/J's session registry is static per JVM, so CompIDs reused across tests collide with a
    // session a previous test has not finished tearing down.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val venueCompId get() = "FXVENUE$runId"
    private val clientCompId get() = "FXCLIENT$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-fx-venue", "").apply {
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

    /**
     * A quote request answered with a two-sided quote, priced.
     *
     * The bid/ask relationship is asserted rather than the prices themselves: they jitter by design, and
     * a test that pinned them would be testing the draw rather than the venue. What must hold every time
     * is that the bid is below the ask and the spread is the pair's.
     */
    @Test
    fun `a quote request is answered with a priced two-sided quote`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage("35=R|131=RFQ-1|55=EUR/USD|54=1", viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { incoming(client, "S").isNotEmpty() },
            "the FX venue should quote EUR/USD; got ${incoming(client).map { field(it, 35) }}",
        )
        val quote = incoming(client, "S").single()
        assertEquals("RFQ-1", field(quote, 131), "the quote answers the request that asked for it")
        assertEquals("EUR/USD", field(quote, 55))
        assertEquals("USD", field(quote, 15))

        val bid = field(quote, 132)?.toDoubleOrNull()
        val ask = field(quote, 133)?.toDoubleOrNull()
        assertNotNull(bid, "no BidPx in ${quote.rawMessage}")
        assertNotNull(ask, "no OfferPx in ${quote.rawMessage}")
        assertTrue(bid < ask, "a bid above its ask is not a quote: ${quote.rawMessage}")
        assertEquals(2.0E-4, ask - bid, 1.0E-9, "the spread must be the pair's: ${quote.rawMessage}")
        assertTrue(
            Regex("""^\d+\.\d{5}$""").matches(field(quote, 132)!!),
            "EUR/USD is quoted to five decimals: ${quote.rawMessage}",
        )
    }

    /**
     * **The expression actually ran**, on the dispatch thread, against a request off the wire.
     *
     * Two requests, two different prices. A venue whose expressions silently failed would answer both
     * with the identical literal `${…}` and every other assertion above would still pass.
     */
    @Test
    fun `two quote requests are priced differently`() {
        startVenue()
        val client = connectClient()

        repeat(6) { i -> client.sendFixMessage("35=R|131=RFQ-$i|55=EUR/USD|54=1", viewModel.dictionary) }

        assertTrue(
            awaitCondition(15_000) { incoming(client, "S").size >= 6 },
            "expected six quotes, got ${incoming(client, "S").size}",
        )
        val prices = incoming(client, "S").mapNotNull { field(it, 132) }
        assertTrue(
            prices.none { it.contains("$") },
            "a price that is still an expression means the engine never evaluated it: $prices",
        )
        assertTrue(prices.distinct().size > 1, "every quote came back at the same price: $prices")
    }

    /**
     * **The reply that carries a repeating group**, asked of the bytes the client parsed.
     *
     * NoRelatedSym is required on a QuoteRequestReject, and until this bundle the acceptor could not
     * build one: the reply's fields sorted by tag number, so `55` arrived before the `146` meant to
     * contain it and the group was empty. Read back through the client's own parse, which is the only
     * reader whose opinion counts.
     */
    @Test
    fun `an unpriced symbol is refused with a QuoteRequestReject naming the symbol`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage("35=R|131=RFQ-X|55=XXX/YYY|54=1", viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { incoming(client, "AG").isNotEmpty() },
            "an unpriced symbol should be refused; got ${incoming(client).map { field(it, 35) }}",
        )
        val reject = incoming(client, "AG").single()
        assertEquals("1", field(reject, 658), "658=1 is Unknown symbol")

        val parsed = reject.quickfixMessage
        assertNotNull(parsed, "the client could not parse the reject at all: ${reject.rawMessage}")
        assertEquals(
            "XXX/YYY",
            parsed.getGroup(1, 146).getString(55),
            "the symbol must be inside NoRelatedSym, not beside it: ${reject.rawMessage}",
        )
    }

    /**
     * A market order acknowledged and then filled at the venue's own price — the case the fill rules are
     * per-pair for. A market order carries no `44`, so the price can only come from the rule's content.
     */
    @Test
    fun `a market order is acknowledged and filled at the venue's own price`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage(
            "35=D|11=FX-1|55=USD/JPY|54=1|38=1000000|40=1|60=20260730-09:14:22.000",
            viewModel.dictionary,
        )

        assertTrue(
            awaitCondition(15_000) { incoming(client, "8").size >= 2 },
            "expected an ack and a fill; got ${incoming(client, "8").map { field(it, 150) }}",
        )
        val (ack, fill) = incoming(client, "8")
        assertEquals("0", field(ack, 150), "the first report is the acknowledgement")
        assertEquals("F", field(fill, 150), "the second is the trade")
        assertEquals(field(ack, 37), field(fill, 37), "both reports must be about one order")

        val price = field(fill, 31)
        assertNotNull(price, "no LastPx in ${fill.rawMessage}")
        assertTrue(
            Regex("""^\d+\.\d{3}$""").matches(price),
            "USD/JPY is quoted to three decimals, not five: '$price'",
        )
        assertEquals(price, field(fill, 6), "one fill has one price, so AvgPx must equal LastPx")
    }

    /** The guard the whole preset discipline exists for, re-asked of this bundle's bytes. */
    @Test
    fun `nothing the FX venue sends is malformed`() {
        startVenue()
        val client = connectClient()

        listOf(
            "35=R|131=RFQ-1|55=EUR/USD|54=1",
            "35=R|131=RFQ-2|55=XXX/YYY|54=1",
            "35=D|11=FX-2|55=GBP/USD|54=1|38=1000000|40=1|60=20260730-09:14:22.000",
            "35=D|11=FX-3|55=EUR/USD|54=2|38=1000000|40=2|44=1.08950|60=20260730-09:14:22.000",
            "35=D|11=FX-4|55=ZZZ/ZZZ|54=1|38=1000000|40=1|60=20260730-09:14:22.000",
        ).forEach { client.sendFixMessage(it, viewModel.dictionary) }

        assertTrue(awaitCondition(15_000) { incoming(client).size >= 7 }, "the venue went quiet")
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
    }

    // ---------------------------------------------------------------- helpers

    private fun startVenue() {
        val rules = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(FxVenuePreset.ID)!!).rules
        val profile =
            FixConnectionProfile(
                name = "FX VENUE",
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
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "FX VENUE" } },
            "the venue should be listening on $venuePort",
        )
    }

    private fun connectClient(): FixMessageSession {
        val profile =
            FixConnectionProfile(
                name = "FX CLIENT",
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
        val session = viewModel.sessions.first { it.title == "FX CLIENT" }
        assertTrue(
            awaitCondition(15_000) { session.connectionState.value == FixConnectionState.LOGGED_ON },
            "the client should log on to the FX venue",
        )
        return session
    }

    /** What the client received, oldest first — optionally only one MsgType. */
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
