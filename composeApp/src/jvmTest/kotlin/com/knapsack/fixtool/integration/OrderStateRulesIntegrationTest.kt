package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.model.SendReason
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
 * **Rules that read the book, over a real wire.**
 *
 * `AcceptorResponderExplainTest` asks the engine whether a conditioned rule fires and
 * `AcceptorPresetsTest` asks whether the shipped ones do. Neither can say that the state the engine
 * was handed is the state the venue was actually in — that claim needs the reading to be taken at the
 * right moment on the callback thread, against a book filled by messages that genuinely arrived, with
 * replies that genuinely left.
 *
 * Two things here exist nowhere else:
 *
 * - **One rule list, two answers.** A cancel for an order that was never placed comes back `35=9` and
 *   a cancel for a live one comes back canceled, without anybody switching a rule off in between.
 *   That is the sentence the issue opens with, and until this slice it was not true.
 * - **A recorded reason outlives the state that produced it.** The reply rejected at second one still
 *   says the order was unknown, read back at second three when the order is working. This is the
 *   claim that fails the moment reasons are re-derived instead of recorded (decision 6a), and it is
 *   the only test in the repo that can fail that way.
 */
class OrderStateRulesIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0

    // QuickFIX/J's session registry is static per JVM, so CompIDs reused across tests collide with a
    // session a previous test has not finished tearing down.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val venueCompId get() = "SVENUE$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-order-state", "").apply {
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

    private val order = "35=D|11=ORD-1|55=VOD.L|54=1|38=1000|40=2|44=185.25|60=20260731-09:14:22.000"

    private fun cancelOf(clOrdId: String, cancelId: String) =
        "35=F|11=$cancelId|41=$clOrdId|55=VOD.L|54=1|38=1000|60=20260731-09:14:23.000"

    /** The venue from decision 1: it answers a cancel two ways, and the book chooses which. */
    private fun cancelVenue(): List<AcceptorResponseRule> {
        var rules = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId("order-ack")!!).rules
        rules = AcceptorPresets.insert(rules, AcceptorPresets.byId("cancel-rejected-unknown")!!).rules
        return AcceptorPresets.insert(rules, AcceptorPresets.byId("cancel-accepted-working")!!).rules
    }

    // ---------------------------------------------------------------- one rule list, two answers

    @Test
    fun `a cancel for an order nobody sent is rejected, and one for a live order is canceled`() {
        connectVenue(cancelVenue())
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(cancelOf("ORD-1", "CXL-1"), viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { received(client, "9").isNotEmpty() },
            "a cancel for an order the venue never had must come back as a cancel reject; got " +
                received(client).map { field(it, 35) },
        )
        assertTrue(field(received(client, "9").single(), 102) == "1", "and it must say why: 102=1, unknown order")

        // The same venue, the same rule list, nothing switched off — only the book has changed.
        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(
            awaitCondition(15_000) { booked(pane, "ORD-1")?.state == OrderState.WORKING },
            "the order should have been acknowledged and be working",
        )

        client.sendFixMessage(cancelOf("ORD-1", "CXL-2"), viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { received(client, "8").any { field(it, 150) == "4" } },
            "a cancel for a live order must be accepted; got " +
                received(client).map { "35=${field(it, 35)} 150=${field(it, 150)}" },
        )
        assertEquals(
            1,
            received(client, "9").size,
            "and only the first cancel was rejected — the second must not have reached the unknown-order rule",
        )
    }

    /**
     * The read is taken **before** the message is recorded, which is the only reason a duplicate can
     * be told from a first arrival: by the time any rule is asked, the order's own arrival has already
     * booked it. Reverse those two lines and this test fails while every unit test still passes.
     */
    @Test
    fun `a repeated ClOrdID is rejected as a duplicate and the first one still gets its acknowledgement`() {
        val rules =
            AcceptorPresets
                .insert(
                    AcceptorPresets.insert(emptyList(), AcceptorPresets.byId("order-ack")!!).rules,
                    AcceptorPresets.byId("duplicate-clordid")!!,
                ).rules
        connectVenue(rules)
        val client = connectClient("ALPHA")
        awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(
            awaitCondition(15_000) { received(client, "8").any { field(it, 150) == "0" } },
            "the first order is a new order and must be acknowledged",
        )

        client.sendFixMessage(order, viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { received(client, "8").any { field(it, 103) == "6" } },
            "the second use of ORD-1 is a duplicate and must be rejected; got " +
                received(client, "8").map { "150=${field(it, 150)} 103=${field(it, 103)}" },
        )
    }

    // ---------------------------------------------------------------- the reason, recorded

    /**
     * **The one claim that fails the moment a reason is re-derived.**
     *
     * The reject was sent when ORD-1 was unknown. Re-deriving its reason afterwards — from the same
     * rules and the same message, which is all `/acceptor/test` needs — now answers *accepted*,
     * because by then the order exists. Nothing is wrong with either answer; they were asked at
     * different times, and a tool that states the second one about the first one's reply is
     * confidently wrong.
     */
    @Test
    fun `a reply's recorded reason still names the state that produced it after the order has moved on`() {
        connectVenue(cancelVenue())
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(cancelOf("ORD-1", "CXL-1"), viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { sent(pane, "9").isNotEmpty() }, "the venue should have rejected the cancel")

        val reject = sent(pane, "9").single()
        val reason = assertNotNull(reject.sendReason, "an auto-reply must record why it was sent")
        assertEquals(SendReason.Source.RULE, reason.source)
        assertEquals("F", reason.whenMsgType)
        assertEquals("unknown", assertNotNull(reason.reading).word)
        assertEquals("ORD-1", reason.reading?.key, "the order it was asked about, not the cancel's own id")

        // Now the order arrives, is acknowledged, and is canceled — the book moves right past the
        // state that reject was sent in.
        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "ORD-1")?.state == OrderState.WORKING })
        client.sendFixMessage(cancelOf("ORD-1", "CXL-2"), viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "ORD-1")?.state == OrderState.DONE })

        val stillSaid = assertNotNull(sent(pane, "9").single().sendReason)
        assertEquals("unknown", stillSaid.reading?.word, "the reject was sent for an order that did not exist yet")
        assertTrue(
            stillSaid.line().contains("the book said ORD-1 was unknown"),
            "and it has to say so in words: ${stillSaid.line()}",
        )

        // The cancel that was accepted records the other answer, from the same rule list.
        val accepted = sent(pane, "8").first { field(it, 150) == "6" || field(it, 150) == "4" }
        assertEquals("working", assertNotNull(accepted.sendReason?.reading).word)
    }

    @Test
    fun `each step of a sequence carries the one decision that produced the whole reply`() {
        connectVenue(cancelVenue())
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "ORD-1")?.state == OrderState.WORKING })
        client.sendFixMessage(cancelOf("ORD-1", "CXL-1"), viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { sent(pane, "8").count { field(it, 11) == "CXL-1" } == 2 },
            "the accepted cancel is a two-step reply: pending cancel, then canceled",
        )
        val steps = sent(pane, "8").filter { field(it, 11) == "CXL-1" }.mapNotNull { it.sendReason }
        assertEquals(listOf(1, 2), steps.map { it.step }, "each step says which of them it is")
        assertTrue(steps.all { it.steps == 2 }, "and how many there were")
        assertEquals(
            1,
            steps.map { it.reading?.word }.distinct().size,
            "one decision produced both, so both quote the same reading — a second read would describe a book that had moved",
        )
        assertTrue(steps.first().line().contains("step 1 of 2"), "got: ${steps.first().line()}")
    }

    /**
     * A person's reply is recorded too. The client cannot tell which of them sent it and neither
     * should the record — a log where the improvised replies are the silent ones is hardest to read
     * exactly when somebody was improvising.
     */
    @Test
    fun `a reply sent by hand records the shape that was picked and what the venue was holding`() {
        connectVenue(AcceptorPresets.byId("order-ack")!!.rules)
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "ORD-1")?.state == OrderState.WORKING })

        replyByHand(pane, "ORD-1", "partial-fill")

        assertTrue(
            awaitCondition(10_000) { sent(pane, "8").any { it.sendReason?.source == SendReason.Source.HAND } },
            "a hand-sent reply must record its reason as surely as a rule's does",
        )
        val byHand = sent(pane, "8").first { it.sendReason?.source == SendReason.Source.HAND }
        val reason = assertNotNull(byHand.sendReason)
        assertEquals("Partial fill", reason.shapeName)
        assertEquals("ORD-1", reason.reading?.key)
        assertEquals("working", reason.reading?.word, "which is what it was when the author reached for the reply")
        assertTrue(reason.line().startsWith("sent by hand — \"Partial fill\" against ORD-1, working"), reason.line())
    }

    @Test
    fun `a message nobody claimed carries no reason, rather than an invented one`() {
        connectVenue(AcceptorPresets.byId("order-ack")!!.rules)
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { sent(pane, "8").isNotEmpty() })

        assertNull(
            client.messages.value
                .filterIsInstance<FixMessage>()
                .firstOrNull()
                ?.sendReason,
            "a received message was not sent by this app at all, and has no reason to carry",
        )
        // And the reply the rule sent does have one, so the assertion above is about the direction
        // rather than about nothing being recorded anywhere.
        assertNotNull(sent(pane, "8").first().sendReason)
    }

    // ---------------------------------------------------------------- helpers

    /** The Send button's tail, as `ReplyWithIntegrationTest` establishes it. */
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
        viewModel.setActiveSession(viewModel.sessions.indexOf(pane))
        assertTrue(viewModel.replyWith(received, shape), "'${shape.name}' should have opened for $clOrdId")
        viewModel.sendMessage(viewModel.editorFields.joinToString("|") { "${it.tag}=${it.value}" } + "|")
    }

    private fun booked(pane: FixMessageSession, clOrdId: String) =
        pane.orderBook()?.orders?.firstOrNull { it.key == clOrdId }

    private fun fixMessages(session: FixMessageSession): List<FixMessage> =
        session.messages.value.filterIsInstance<FixMessage>()

    /** What this counterparty received, by MsgType — the client's own view, which is the one under test. */
    private fun received(session: FixMessageSession, msgType: String? = null): List<FixMessage> =
        fixMessages(session).filter {
            it.direction == FixMessage.Direction.INCOMING && (msgType == null || field(it, 35) == msgType)
        }

    /** What the venue put on the wire for this counterparty. */
    private fun sent(session: FixMessageSession, msgType: String? = null): List<FixMessage> =
        fixMessages(session).filter {
            it.direction == FixMessage.Direction.OUTGOING && (msgType == null || field(it, 35) == msgType)
        }

    private fun field(message: FixMessage, tag: Int): String? =
        message.rawMessage
            .split('|')
            .firstOrNull { it.startsWith("$tag=") }
            ?.substringAfter('=')

    private fun connectVenue(rules: List<AcceptorResponseRule>) {
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

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(50)
        }
        return predicate()
    }
}
