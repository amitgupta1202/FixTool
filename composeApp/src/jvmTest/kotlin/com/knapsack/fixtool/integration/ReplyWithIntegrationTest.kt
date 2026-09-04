package com.knapsack.fixtool.integration

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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A hand-written reply on a socket** — "Reply With…" as far as the client that receives it.
 *
 * The layers below stop short of the wire and each for a different reason. `AcceptorReplyShapesTest`
 * asks the engine what a shape renders, which is a string, not a message anyone accepted.
 * `ReplyWithTest` asks the ViewModel which session it pointed at, which is an intent, not a delivery.
 * `ReplyWithMenuTest` clicks the menu, which ends at a callback. Everything after that — the fields
 * becoming a raw message, QuickFIX/J stamping a header on it, the venue holding a session per client
 * and putting the bytes on the right one — exists only here.
 *
 * The venue runs with **no auto-response rules on purpose**. A rule answering the order would produce
 * an ExecutionReport whatever this feature did, and the test would pass on a reply nobody sent. Every
 * report a client receives here was composed by hand.
 *
 * **The seam this does not close:** the editor's Send button is a lambda in `App.kt`, not a ViewModel
 * call, so what runs below is its tail — `toRawMessage()` then `sendMessage()`. The steps in between
 * are template validation and resolution, and they are no-ops for a reply, because `replyWith` hands
 * the editor fields with every `${…}` already resolved (asserted in `AcceptorReplyShapesTest`). A
 * reply that arrived here still carrying a template would be a different bug, in a different place.
 */
class ReplyWithIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0

    // QuickFIX/J's session registry is static per JVM, so CompIDs reused across tests collide with a
    // session a previous test has not finished tearing down.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val venueCompId get() = "RVENUE$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-reply-with", "").apply {
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

    private val order = "35=D|11=RW-1|55=VOD.L|54=1|38=1000|40=2|44=185.25|60=20260731-09:14:22.000"

    /**
     * The whole path, once: a client's order arrives at a venue that has no rules, a tester answers
     * it by hand, and the client receives the acknowledgement — about its own order, with a header
     * QuickFIX/J accepted, and no tag left empty.
     */
    @Test
    fun `an order replied to by hand reaches the client that sent it`() {
        connectVenue()
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        val received = awaitOrder(pane, "RW-1")

        val offers = viewModel.replyOffersFor(received)
        assertEquals(
            listOf("ack", "fill", "partial-fill", "fill-remainder", "partial-of-remainder", "fill-what-is-left", "order-reject"),
            offers.map { it.shape.id },
            "a limit order on a venue's pane is answerable, and this is the menu that says so",
        )
        // **The menu answers for the venue as well as for the message.** The order is booked but
        // unacknowledged, so the venue has not yet said its OrderID or what is left of it — and the
        // shapes that read those are refused *by name* rather than offered and then found to build
        // `37=`. The stateless ones read only the message and are unaffected.
        assertTrue(
            offers.filter { it.shape.id !in bookAware }.all { it.available },
            "this order carries everything the stateless shapes read",
        )
        offers.filter { it.shape.id in bookAware }.forEach {
            assertTrue(
                it.refusal?.contains("orderId") == true,
                "'${it.shape.name}' reads an id the venue has not issued yet; got: ${it.refusal}",
            )
        }

        sendReply(received, "ack")

        assertTrue(
            awaitCondition(10_000) { executionReports(client).isNotEmpty() },
            "the acknowledgement never reached the client that placed the order",
        )
        val ack = executionReports(client).single()
        assertEquals("RW-1", field(ack, 11), "the reply must name the order it answers")
        assertEquals("VOD.L", field(ack, 55))
        assertEquals("0", field(ack, 150), "an acknowledgement, as picked")
        assertEquals("0", field(ack, 39))
        assertEquals("1000", field(ack, 151), "nothing is done yet, so the whole order is still open")
        assertNotNull(field(ack, 37), "the venue owes the order an OrderID")
        assertNotNull(field(ack, 17))

        val empty = ack.rawMessage.split('|').filter { it.isNotBlank() && it.endsWith("=") }
        assertTrue(empty.isEmpty(), "the venue put ${empty.joinToString()} on the wire")
        assertTrue(
            fixMessages(client).none { field(it, 35) == "3" || field(it, 35) == "j" },
            "a reject means the client could not read what the venue sent: " +
                fixMessages(client).filter { field(it, 35) == "3" || field(it, 35) == "j" }.map { it.rawMessage },
        )

        // **And now the same menu offers what it refused a moment ago.** The venue has answered, so
        // the book holds an OrderID and a LeavesQty, and the shapes that read them become available —
        // the offer tracking the state of the venue rather than a property of the message.
        assertTrue(
            awaitCondition(10_000) {
                viewModel.replyOffersFor(received).filter { it.shape.id in bookAware }.all { it.available }
            },
            "after the acknowledgement the book can feed the fills; got: " +
                viewModel.replyOffersFor(received).filter { it.shape.id in bookAware }.map { it.refusal },
        )
    }

    /**
     * **The identity defect from the top of the proposal, on a real wire.**
     *
     * Acknowledging by hand and then filling by hand sent the client two different OrderIDs for one
     * order, because `${req.uuid}` is one draw per triggering message and each hand-sent reply is its
     * own. Read from the book, the fill carries the id the ack already gave. Nothing below this can
     * make the claim: it needs two separate sends, a book fed by the first, and a client to receive
     * both.
     */
    @Test
    fun `acknowledging then filling by hand sends one OrderID, not two`() {
        connectVenue()
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        val received = awaitOrder(pane, "RW-1")

        sendReply(received, "ack")
        assertTrue(awaitCondition(10_000) { executionReports(client).size == 1 })
        val ackId = field(executionReports(client).single(), 37)
        assertNotNull(ackId, "the venue owes the order an OrderID")

        // A separate send, a separate moment, a separate draw of everything not read from the book.
        assertTrue(
            awaitCondition(10_000) {
                viewModel.replyOffersFor(received).first { it.shape.id == "fill-what-is-left" }.available
            },
            "the acknowledged order should now be readable",
        )
        sendReply(received, "fill-what-is-left")

        assertTrue(
            awaitCondition(10_000) { executionReports(client).size == 2 },
            "the fill never reached the client",
        )
        val fill = executionReports(client).last()
        assertEquals(ackId, field(fill, 37), "the fill has to be about the order the acknowledgement acknowledged")
        assertEquals("1000", field(fill, 14), "and it fills what the book says was still open")
        assertEquals("0", field(fill, 151))
        assertEquals(
            OrderState.DONE,
            pane
                .orderBook()!!
                .orders
                .single { it.key == "RW-1" }
                .state,
            "the venue's own view has to agree with what it just told the client",
        )
    }

    /** The shapes that read the book — refused until the venue has said enough for them to read. */
    private val bookAware = setOf("partial-of-remainder", "fill-what-is-left")

    /**
     * **The assertion that cannot be made anywhere else.** Two clients are logged on to one venue and
     * the second one sends the order. Replying to it has to put the reply on *that* client's session
     * — and a ViewModel field saying which session is active is an intent, not a delivery. If the
     * reply went to the wrong client, ALPHA would receive a report about an order it never placed and
     * BETA would sit waiting for one that already went out.
     */
    @Test
    fun `with two clients logged on, the reply goes to the one whose order it is`() {
        connectVenue()
        val alpha = connectClient("ALPHA")
        val beta = connectClient("BETA")
        awaitPane("ALPHA")
        val betaPane = awaitPane("BETA")

        beta.sendFixMessage(order.replace("RW-1", "RW-BETA"), viewModel.dictionary)
        val received = awaitOrder(betaPane, "RW-BETA")

        // The tester was looking at ALPHA's pane when BETA's order came in — which is the case the
        // reply has to survive, and the reason opening one re-points the editor at all.
        viewModel.setActiveSession(viewModel.sessions.indexOfFirst { it.title == "VENUE ← ALPHA$runId" })

        sendReply(received, "ack")

        assertTrue(
            awaitCondition(10_000) { executionReports(beta).isNotEmpty() },
            "the reply never reached the client whose order it answered",
        )
        assertEquals("RW-BETA", field(executionReports(beta).single(), 11))

        // Longer than the round trip that has already completed, so a misdirected report would have
        // landed by now.
        Thread.sleep(1_000)
        assertTrue(
            executionReports(alpha).isEmpty(),
            "ALPHA was told about an order it never placed: " + executionReports(alpha).map { it.rawMessage },
        )
    }

    /**
     * A market order has no price, and the fill prices at `${req.44}`. The menu refuses it rather
     * than building `31=`, and the acknowledgement it *is* offered still goes out clean — the same
     * pair of claims the preset conditions make, made here about the manual path.
     */
    @Test
    fun `a market order is refused the fill and still answerable`() {
        connectVenue()
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage("35=D|11=RW-MKT|55=VOD.L|54=1|38=1000|40=1|60=20260731-09:14:22.000", viewModel.dictionary)
        val received = awaitOrder(pane, "RW-MKT")

        val offers = viewModel.replyOffersFor(received).associateBy { it.shape.id }
        assertTrue(offers.getValue("fill").refusal?.contains("44") == true, "the fill must be refused, by name")
        assertTrue(offers.getValue("ack").available)

        sendReply(received, "ack")

        assertTrue(awaitCondition(10_000) { executionReports(client).isNotEmpty() })
        val ack = executionReports(client).single()
        assertEquals("RW-MKT", field(ack, 11))
        val empty = ack.rawMessage.split('|').filter { it.isNotBlank() && it.endsWith("=") }
        assertTrue(empty.isEmpty(), "the venue put ${empty.joinToString()} on the wire")
    }

    @Test
    fun `two orders answered in turn get an OrderID each`() {
        connectVenue()
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        sendReply(awaitOrder(pane, "RW-1"), "ack")
        client.sendFixMessage(order.replace("RW-1", "RW-2"), viewModel.dictionary)
        sendReply(awaitOrder(pane, "RW-2"), "ack")

        assertTrue(awaitCondition(10_000) { executionReports(client).size >= 2 }, "both orders should have been answered")
        val (first, second) = executionReports(client)
        assertEquals("RW-1", field(first, 11))
        assertEquals("RW-2", field(second, 11))
        assertNotEquals(field(first, 37), field(second, 37), "two orders sharing one OrderID are one order to the client")
    }

    // ---------------------------------------------------------------- helpers

    /**
     * What the Send button does with what "Reply With…" left in the editor.
     *
     * The button's own lambda lives in `App.kt` and cannot be called from here; this is its tail. The
     * template validation and resolution it does in between are no-ops for a reply — see the class
     * comment — so what goes on the wire is what a click would have put there.
     */
    private fun sendReply(received: FixMessage, shapeId: String) {
        val shape = AcceptorPresets.shapeById(shapeId)!!
        assertTrue(viewModel.replyWith(received, shape), "'${shape.name}' should have opened for this message")
        val raw = viewModel.editorFields.joinToString("|") { "${it.tag}=${it.value}" } + "|"
        viewModel.sendMessage(raw)
    }

    private fun connectVenue() {
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
                        // No rules: every report a client receives here was composed by hand.
                        acceptorResponseRules = emptyList(),
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

    /** The venue's pane for [client], once the engine has announced it and the pane has been built. */
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

    /** The order as the venue's pane holds it — which is the message the tester would have selected. */
    private fun awaitOrder(pane: FixMessageSession, clOrdId: String): FixMessage {
        assertTrue(
            awaitCondition(15_000) { orderIn(pane, clOrdId) != null },
            "$clOrdId should have arrived on the venue's pane",
        )
        return orderIn(pane, clOrdId)!!
    }

    private fun orderIn(pane: FixMessageSession, clOrdId: String): FixMessage? =
        fixMessages(pane).firstOrNull {
            field(it, 35) == "D" && field(it, 11) == clOrdId && it.direction == FixMessage.Direction.INCOMING
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
        // A torn read is 'not yet', not 'no' -- see [settled].
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
    }
}
