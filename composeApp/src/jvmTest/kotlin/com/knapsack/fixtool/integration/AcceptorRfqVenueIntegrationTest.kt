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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The RFQ venue over a socket**, the claim `RfqVenuePresetTest` deliberately stops short of.
 *
 * Two things only bytes can prove for this bundle. The `62=${utcnow+1min}` shorthand is expanded on the
 * dispatch thread of a loaded acceptor against a request that arrived over TCP, and a shorthand that
 * throws there is a venue that goes silent. And the QuoteStatusReport and the booked trade are shapes
 * chosen from the dictionary; whether the client's engine agrees is read back through its own parse and
 * its own validation, which is the only reader whose opinion counts.
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
    fun `a quote request is answered with a firm quote whose QuoteID follows from the QuoteReqID`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage("35=R|131=RFQ-1|146=1|55=EUR/USD|54=1|38=1000000", viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { incoming(client, "S").isNotEmpty() },
            "the RFQ venue should quote EUR/USD; got ${incoming(client).map { field(it, 35) }}",
        )
        val quote = incoming(client, "S").single()
        assertEquals("RFQ-1", field(quote, 131))
        assertEquals("Q-RFQ-1", field(quote, 117), "the QuoteID is Q- plus the QuoteReqID")
        assertEquals("1.08990", field(quote, 132))
        assertEquals("1.09010", field(quote, 133))
        assertEquals("1000000", field(quote, 135), "the quote is for the size asked")
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

        client.sendFixMessage("35=R|131=RFQ-2|146=1|55=USD/JPY|54=1|38=2000000", viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { incoming(client, "S").isNotEmpty() }, "no quote came back")
        val quote = incoming(client, "S").single()
        val offer = assertNotNull(field(quote, 133))

        client.sendFixMessage(
            "35=AJ|693=RESP-2|694=1|117=${field(quote, 117)}|11=TRADE-2|55=USD/JPY|54=1|38=2000000|44=$offer",
            viewModel.dictionary,
        )

        assertTrue(
            awaitCondition(15_000) { incoming(client, "8").isNotEmpty() },
            "the hit should be booked; got ${incoming(client).map { field(it, 35) }}",
        )
        val trade = incoming(client, "8").single()
        assertEquals("F", field(trade, 150))
        assertEquals("2", field(trade, 39))
        assertEquals(offer, field(trade, 31), "the trade is at the quoted offer")
        assertEquals(offer, field(trade, 6))
        assertEquals("2000000", field(trade, 32))
        assertEquals("TRADE-2", field(trade, 11), "the report names the trade the client named")
        assertEquals("RESP-2", field(trade, 693))
        assertNotNull(field(trade, 37))
    }

    @Test
    fun `a pass, a counter and a hit at the wrong price are each answered with a QuoteStatusReport`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage("35=AJ|693=RESP-P|694=6|117=Q-RFQ-3|55=GBP/USD", viewModel.dictionary)
        client.sendFixMessage(
            "35=AJ|693=RESP-C|694=2|117=Q-RFQ-3|11=TRADE-C|55=GBP/USD|54=1|38=1000000|44=1.27000",
            viewModel.dictionary,
        )
        client.sendFixMessage(
            "35=AJ|693=RESP-W|694=1|117=Q-RFQ-3|11=TRADE-W|55=GBP/USD|54=1|38=1000000|44=1.27000",
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
        byResponse.values.forEach { assertEquals("Q-RFQ-3", field(it, 117), "every status report names the quote") }
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

        listOf(
            "35=R|131=RFQ-1|146=1|55=EUR/USD|54=1|38=1000000",
            "35=R|131=RFQ-2|146=1|55=EUR/USD|54=1",
            "35=R|131=RFQ-3|146=1|55=XXX/YYY|54=1|38=1000000",
            "35=AJ|693=RESP-1|694=1|117=Q-RFQ-1|11=TRADE-1|55=EUR/USD|54=1|38=1000000|44=1.09010",
            "35=AJ|693=RESP-2|694=1|117=Q-RFQ-1|11=TRADE-2|55=EUR/USD|54=2|38=1000000|44=1.09010",
            "35=AJ|693=RESP-3|694=1|117=Q-RFQ-1|55=EUR/USD|54=1|44=1.09010",
            "35=AJ|693=RESP-4|694=6|117=Q-RFQ-1|55=EUR/USD",
            "35=AJ|693=RESP-5|694=2|117=Q-RFQ-1|11=TRADE-5|55=EUR/USD|54=1|38=1000000|44=1.09000",
            "35=AJ|693=RESP-6|694=4|117=Q-RFQ-1|55=EUR/USD",
            "35=AJ|693=RESP-7|694=1|117=Q-RFQ-7|11=TRADE-7|55=XXX/YYY|54=1|38=1000000|44=1.0",
            "35=AJ|693=RESP-8|694=1|11=TRADE-8|55=EUR/USD|54=1|38=1000000|44=1.09010",
        ).forEach { client.sendFixMessage(it, viewModel.dictionary) }

        assertTrue(
            awaitCondition(15_000) { incoming(client).size >= 11 },
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
    }

    // ---------------------------------------------------------------- helpers

    private fun startVenue() {
        val rules = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(RfqVenuePreset.ID)!!).rules
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
