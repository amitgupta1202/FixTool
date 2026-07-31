package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The book, filled by a wire rather than by a test's own hand.**
 *
 * `OrderBookTest` asks the fold what a fill does to a quantity and `OrderBookServiceTest` asks the
 * book whose order is whose; both feed themselves. What only this can say is that the messages a real
 * venue and a real client exchange arrive at the book at all — that `fromApp` and `toApp` are the
 * right two places, that a multi-client venue's per-session keying survives contact with QuickFIX's
 * own `SessionID`, and that the OrderID in the book is the one the counterparty actually received.
 *
 * The venue runs with **no rules** except where a test says otherwise, so a book that filled up
 * anyway would be a book filling itself.
 */
class OrderBookIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0

    // QuickFIX/J's session registry is static per JVM, so CompIDs reused across tests collide with a
    // session a previous test has not finished tearing down.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val venueCompId get() = "BVENUE$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-order-book", "").apply {
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

    private val order = "35=D|11=BOOK-1|55=VOD.L|54=1|38=1000|40=2|44=185.25|60=20260731-09:14:22.000"

    // ---------------------------------------------------------------- the venue's own memory

    @Test
    fun `an order the venue has not answered is pending, and the reply it sends moves it`() {
        connectVenue()
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)

        assertTrue(awaitCondition(15_000) { booked(pane, "BOOK-1") != null }, "the order never reached the book")
        val pending = booked(pane, "BOOK-1")!!
        assertEquals(OrderState.PENDING, pending.state, "nothing has been sent back about it yet")
        assertNull(pending.current.orderId, "and the venue has not named it")
        assertEquals("VOD.L", pending.current.symbol)

        // Answered by hand, which is the path that would silently bypass a book fed from the rules
        // engine — see decision 2. This is the same tail of the Send button as ReplyWithIntegrationTest.
        replyByHand(pane, "BOOK-1", "ack")

        assertTrue(
            awaitCondition(10_000) { booked(pane, "BOOK-1")?.state == OrderState.WORKING },
            "a reply typed by a person has to move the book exactly as a rule's does",
        )
        val working = booked(pane, "BOOK-1")!!
        assertEquals("1000", working.current.leavesQty)
        assertEquals(2, working.events.size, "the order and the answer, in the trail")
    }

    /**
     * **The book records the id the counterparty was given.** Not one the fold derived, not one the
     * test computed — the OrderID on the bytes that arrived at the client. Decision 3a lives or dies
     * here, one layer below the unit test that pins the same rule.
     */
    @Test
    fun `the OrderID in the book is the one the client received`() {
        connectVenue()
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "BOOK-1") != null })
        replyByHand(pane, "BOOK-1", "ack")

        assertTrue(awaitCondition(10_000) { executionReports(client).isNotEmpty() })
        val onTheWire = field(executionReports(client).single(), 37)
        assertNotNull(onTheWire, "the venue's reply carried no OrderID at all")
        assertEquals(
            onTheWire,
            booked(pane, "BOOK-1")!!.current.orderId,
            "the book has to agree with the client about which order this is",
        )
    }

    @Test
    fun `a rule's whole sequence lands in the book, and the trail is what it did`() {
        connectVenue(rules = AcceptorPresets.byId("ack-then-fill")!!.rules)
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { booked(pane, "BOOK-1")?.state == OrderState.DONE },
            "the ack and the fill should both have reached the book; got " +
                booked(pane, "BOOK-1")?.events?.map { it.label },
        )
        val filled = booked(pane, "BOOK-1")!!
        assertEquals("1000", filled.current.cumQty, "filled in full")
        assertEquals("0", filled.current.leavesQty)
        assertEquals(listOf("order received", "ack", "fill 1000"), filled.events.map { it.label })
        assertEquals(
            filled.events.size,
            filled.snapshots().size,
            "one snapshot per event, or the trail cannot be drawn beside it",
        )
    }

    /**
     * ClOrdID is unique per client and not per venue. Two clients sending `BOOK-1` are two orders,
     * and a book that collapsed them would answer one client's cancel with the other's state — the
     * same separation claim the multi-client venue itself rests on.
     */
    @Test
    fun `two clients sending the same ClOrdID get two books`() {
        connectVenue()
        val alpha = connectClient("ALPHA")
        val beta = connectClient("BETA")
        val alphaPane = awaitPane("ALPHA")
        val betaPane = awaitPane("BETA")

        alpha.sendFixMessage(order, viewModel.dictionary)
        beta.sendFixMessage(order.replace("38=1000", "38=9999"), viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { booked(alphaPane, "BOOK-1") != null && booked(betaPane, "BOOK-1") != null },
            "each client's order should be in its own book",
        )
        assertEquals("1000", booked(alphaPane, "BOOK-1")!!.current.orderQty)
        assertEquals("9999", booked(betaPane, "BOOK-1")!!.current.orderQty)

        replyByHand(alphaPane, "BOOK-1", "ack")

        assertTrue(awaitCondition(10_000) { booked(alphaPane, "BOOK-1")!!.state == OrderState.WORKING })
        assertEquals(
            OrderState.PENDING,
            booked(betaPane, "BOOK-1")!!.state,
            "beta's order was never answered, and alpha's answer is not beta's",
        )
    }

    @Test
    fun `a report about an order nobody sent is counted and kept, not booked`() {
        connectVenue()
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")
        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "BOOK-1") != null })

        // A venue answering an order it never received: the tester's own typo, or a rule reading the
        // wrong tag. Either way the book must not invent an order to hold it.
        viewModel.setActiveSession(viewModel.sessions.indexOf(pane))
        viewModel.sendMessage("35=8|11=GHOST-9|37=EX-9|17=E-9|150=F|39=2|14=10|151=0|55=VOD.L|54=1|38=10|60=20260731-09:14:25.000|")

        assertTrue(
            awaitCondition(10_000) { pane.orderBook()!!.unattributedCount > 0 },
            "a report the book cannot attribute has to be reported, not dropped",
        )
        val book = pane.orderBook()!!
        assertEquals(1, book.orders.size, "and nothing invented to hold it")
        assertTrue("GHOST-9" in book.unattributed.single().why, "the reason names what could not be attributed")
    }

    @Test
    fun `clearing says it was cleared, so an empty book is never ambiguous`() {
        connectVenue()
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")
        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "BOOK-1") != null })

        pane.clearOrderBook(by = "the test")

        val book = pane.orderBook()!!
        assertTrue(book.orders.isEmpty())
        assertNotNull(book.clearedAt, "an empty book that cannot say why reads as one nothing ever happened to")
        assertEquals("the test", book.clearedBy)
    }

    @Test
    fun `an initiator holds no book at all`() {
        connectVenue()
        val client = connectClient("ALPHA")
        awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)

        assertNull(client.orderBook(), "a client's view of the orders it sent is a different feature, deliberately not this one")
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The Send button's tail, as `ReplyWithIntegrationTest` establishes it.
     *
     * The wait is not ceremony: **the book leads the grid**. It is written on the callback thread as
     * the message arrives, while a pane drains its queue on a poll — so an order can be booked a tick
     * before the row a tester would click on exists.
     */
    private fun replyByHand(pane: FixMessageSession, clOrdId: String, shapeId: String) {
        assertTrue(
            awaitCondition(10_000) {
                fixMessages(pane).any { field(it, 11) == clOrdId && it.direction == FixMessage.Direction.INCOMING }
            },
            "$clOrdId should have reached the pane's grid, not only its book",
        )
        val received =
            fixMessages(pane).first { field(it, 11) == clOrdId && it.direction == FixMessage.Direction.INCOMING }
        val shape = AcceptorPresets.shapeById(shapeId)!!
        assertTrue(viewModel.replyWith(received, shape), "'${shape.name}' should have opened for $clOrdId")
        viewModel.sendMessage(viewModel.editorFields.joinToString("|") { "${it.tag}=${it.value}" } + "|")
    }

    private fun booked(pane: FixMessageSession, clOrdId: String) =
        pane.orderBook()?.orders?.firstOrNull { it.key == clOrdId }

    private fun connectVenue(rules: List<AcceptorResponseRule> = emptyList()) {
        val profile =
            FixConnectionProfile(
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = venueCompId,
                        targetCompID = FixConnectionConfig.ANY_CLIENT,
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
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "VENUE" && it.isVenue } },
            "the venue should be listening on $venuePort",
        )
    }

    private fun connectClient(name: String): FixMessageSession {
        val profile =
            FixConnectionProfile(
                name = name,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "$name$runId",
                        targetCompID = venueCompId,
                        host = "localhost",
                        port = venuePort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "${name}store").absolutePath,
                        fileLogPath = File(testDir, "${name}log").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        val session = viewModel.sessions.first { it.title == name }
        assertTrue(
            awaitCondition(15_000) { session.connectionState.value == FixConnectionState.LOGGED_ON },
            "$name should log on to the venue",
        )
        return session
    }

    private fun awaitPane(client: String): FixMessageSession {
        val expected = "VENUE ← $client$runId"
        assertTrue(
            awaitCondition(15_000) {
                viewModel.sessions.any { it.title == expected && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "the venue should open a pane for $client",
        )
        return viewModel.sessions.first { it.title == expected }
    }

    private fun fixMessages(session: FixMessageSession): List<FixMessage> =
        session.messages.value.filterIsInstance<FixMessage>()

    private fun executionReports(session: FixMessageSession): List<FixMessage> =
        fixMessages(session).filter { field(it, 35) == "8" && it.direction == FixMessage.Direction.INCOMING }

    private fun field(message: FixMessage, tag: Int): String? =
        message.rawMessage
            .split('|')
            .firstOrNull { it.startsWith("$tag=") }
            ?.substringAfter('=')

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(50)
        }
        return predicate()
    }
}
