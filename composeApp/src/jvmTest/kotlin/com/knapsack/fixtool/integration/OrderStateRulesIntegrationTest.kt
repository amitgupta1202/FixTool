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

    /**
     * **The starter venue, on a wire, doing the thing the issue was filed about.**
     *
     * Not a hand-assembled rule list — the bundle a new user gets from an empty rules editor, which is
     * the venue almost everyone actually runs and the only place most people will read a rule. For
     * three slices it still answered a cancel for an order nobody placed with "canceled": the fix was
     * in the menu, and the default was not it.
     *
     * Both ends asserted from one venue: the order that was never placed, and the one this bundle has
     * already filled — because filling limit orders 250ms after acking them is what makes `done` the
     * state a cancel most often finds here.
     */
    @Test
    fun `the starter venue rejects a cancel for an order nobody placed, and one that already filled`() {
        connectVenue(AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)!!).rules)
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(cancelOf("GHOST-1", "CXL-1"), viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { received(client, "9").isNotEmpty() },
            "the starter venue answered a cancel for an order it never had with " +
                received(client, "8").map { "150=${field(it, 150)}" },
        )
        assertEquals("1", field(received(client, "9").single(), 102), "unknown order, by reason code")

        // Now an order this venue fills of its own accord, cancelled after the fact.
        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(
            awaitCondition(20_000) { booked(pane, "ORD-1")?.state == OrderState.DONE },
            "the starter venue should have acked and filled it",
        )
        client.sendFixMessage(cancelOf("ORD-1", "CXL-2"), viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { received(client, "9").size == 2 },
            "a cancel for a filled order must be answered, not ignored",
        )
        val tooLate = received(client, "9").last()
        assertEquals("0", field(tooLate, 102), "too late to cancel — not 'unknown order' about an order it just filled")
        assertNull(
            received(client, "8").firstOrNull { field(it, 150) == "4" },
            "and it must not report the filled order canceled",
        )
    }

    // ---------------------------------------------------------------- templates that read the book

    /**
     * **Fills that accumulate, and the claim decision 2 made as a comment.**
     *
     * "Step one's `toApp` runs inside its own `send()`, which returns before step two is built 250ms
     * later, so step two's `${order.*}` sees step one's effect. That is a claim worth a test rather
     * than a comment." This is the test. It cannot be made below the wire, because the effect being
     * seen *is* the outgoing message reaching the book.
     *
     * 1000 shares: half of what is left, then half of what is left, then the rest — 500, 250, 250.
     * A stateless partial repeated three times reports the same CumQty three times, and a client
     * tracking it watches each fill undo the last.
     */
    @Test
    fun `each fill of a sequence reads what the fill before it left`() {
        connectVenue(AcceptorPresets.byId("ack-accumulating-fills")!!.rules)
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)

        // Waited on the *client's* grid, not the venue's book. The book leads the grid — it is written
        // on the callback thread as each message goes out, while a pane drains its queue on a poll —
        // so an order can read DONE a tick before the last report the client is being asked about
        // has a row.
        assertTrue(
            awaitCondition(20_000) { received(client, "8").count { field(it, 150) == "F" } == 3 },
            "the whole sequence should have reached the client; the venue's own trail says " +
                booked(pane, "ORD-1")?.events?.map { it.label },
        )
        val fills = received(client, "8").filter { field(it, 150) == "F" }

        assertEquals(
            listOf("500", "750", "1000"),
            fills.map { field(it, 14) },
            "CumQty has to move forward — the same number three times is the defect this replaces",
        )
        assertEquals(listOf("500", "250", "0"), fills.map { field(it, 151) }, "and LeavesQty has to come down")
        assertEquals(listOf("500", "250", "250"), fills.map { field(it, 32) }, "each report says how much *it* traded")

        fills.forEach { fill ->
            assertEquals(
                1000,
                field(fill, 14)!!.toInt() + field(fill, 151)!!.toInt(),
                "CumQty and LeavesQty must still make the order at every step, which is what a client checks",
            )
        }
        assertEquals(
            1,
            fills.mapNotNull { field(it, 37) }.distinct().size,
            "and all three are about one order, so they carry one OrderID",
        )
    }

    /**
     * "Where is my order?" answered from the book. Every field of the reply is a fact the venue
     * computed rather than one the author typed, which is what makes it true a minute later.
     */
    @Test
    fun `a status request is answered with where the order actually is`() {
        var rules = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId("order-ack")!!).rules
        rules = AcceptorPresets.insert(rules, AcceptorPresets.byId("status-request-working")!!).rules
        rules = AcceptorPresets.insert(rules, AcceptorPresets.byId("status-request-unknown")!!).rules
        connectVenue(rules)
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        // Asked before the order exists: the venue has never heard of it.
        client.sendFixMessage("35=H|11=ORD-1|55=VOD.L|54=1|", viewModel.dictionary)
        assertTrue(
            awaitCondition(15_000) { received(client, "j").isNotEmpty() },
            "a status request for an order nobody sent is a business reject",
        )
        assertEquals("1", field(received(client, "j").single(), 380), "380=1, unknown id")

        // And once the venue is holding it, the same request is answered from the book.
        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "ORD-1")?.state == OrderState.WORKING })
        client.sendFixMessage("35=H|11=ORD-1|55=VOD.L|54=1|", viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { received(client, "8").any { field(it, 150) == "I" } },
            "the venue holds this order and should say where it is; got " +
                received(client, "8").map { "150=${field(it, 150)}" },
        )
        val status = received(client, "8").first { field(it, 150) == "I" }
        val ack = received(client, "8").first { field(it, 150) == "0" }
        assertEquals(field(ack, 37), field(status, 37), "the status is about the order the client already knows")
        assertEquals("0", field(status, 14), "nothing has traded")
        assertEquals("1000", field(status, 151), "and the whole order is still open")
        assertEquals("VOD.L", field(status, 55))
        assertEquals(
            1,
            received(client, "j").size,
            "and the second request must not have reached the unknown-order rule",
        )
    }

    /**
     * **A venue must not disown an order it is holding.** Found by live verification: the
     * status-request preset shipped conditioned `working` alone, so asking after a *filled* order fell
     * past it to the unknown-order reject — the venue answering "never heard of it" about an order
     * whose fills it had just sent. `done` is as answerable from the book as `working` is; `pending`
     * is the one that genuinely is not, because the venue has issued no OrderID to quote.
     */
    @Test
    fun `a status request for a filled order is answered from the book, not disowned`() {
        var rules = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId("ack-accumulating-fills")!!).rules
        rules = AcceptorPresets.insert(rules, AcceptorPresets.byId("status-request-working")!!).rules
        rules = AcceptorPresets.insert(rules, AcceptorPresets.byId("status-request-unknown")!!).rules
        connectVenue(rules)
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(
            awaitCondition(20_000) { booked(pane, "ORD-1")?.state == OrderState.DONE },
            "the order should have filled in full",
        )

        client.sendFixMessage("35=H|11=ORD-1|55=VOD.L|54=1|", viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { received(client, "8").any { field(it, 150) == "I" } },
            "the venue holds this order, filled, and must say so; got " +
                received(client).map { "35=${field(it, 35)} 150=${field(it, 150)}" },
        )
        val status = received(client, "8").first { field(it, 150) == "I" }
        assertEquals("1000", field(status, 14), "it traded in full, and the status has to say the same")
        assertEquals("0", field(status, 151))
        assertTrue(
            received(client, "j").isEmpty(),
            "a venue that answers 'unknown order' about an order it just filled is the failure this covers",
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

    // ---------------------------------------------------------------- the card's mark

    /**
     * **The mark on the rule card and the reason on the reply have to name the same rule.**
     *
     * They are the same fact approached from opposite ends — "which rule answered this message" read
     * off the message, and "which rule just answered" read off the list — and they are computed once,
     * from `authoredRules`, precisely so they cannot drift. If they ever disagree then one of the two
     * is pointing at the wrong card, and there is no way to tell from the screen which one: both look
     * like confident answers. So the assertion is not that the index is 1 or 2, it is that the two
     * surfaces agree, which stays true when the venue's rule list is edited.
     */
    @Test
    fun `the venue records which rule answered, and it is the rule the reply says sent it`() {
        connectVenue(cancelVenue())
        val venue = viewModel.sessions.first { it.isVenue }
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        assertNull(venue.lastRuleFired.value, "nothing has been asked of the venue yet")

        client.sendFixMessage(cancelOf("ORD-1", "CXL-1"), viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { sent(pane, "9").isNotEmpty() }, "the venue should have rejected the cancel")

        val fired = assertNotNull(venue.lastRuleFired.value, "the venue must record the rule that answered")
        val reason = assertNotNull(sent(pane, "9").single().sendReason)
        assertEquals(
            reason.ruleIndex,
            fired.ruleIndex,
            "the card to mark and the rule the reply names must be the same one",
        )
        assertEquals("F", fired.whenMsgType, "it was a cancel that tripped it")
        assertEquals(1, fired.steps, "and a cancel reject is a one-step reply")

        // Reported once per triggering message, not once per step — so the two-step accepted cancel
        // leaves one record and not two.
        client.sendFixMessage(order, viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { booked(pane, "ORD-1")?.state == OrderState.WORKING })
        client.sendFixMessage(cancelOf("ORD-1", "CXL-2"), viewModel.dictionary)
        assertTrue(
            awaitCondition(15_000) { sent(pane, "8").count { field(it, 11) == "CXL-2" } == 2 },
            "the accepted cancel is a two-step reply",
        )
        val accepted = assertNotNull(venue.lastRuleFired.value)
        assertEquals(2, accepted.steps, "the record describes the whole reply, not the step that landed last")
        assertTrue(
            accepted.ruleIndex != fired.ruleIndex,
            "a different rule answered this one — same list, and the book chose",
        )
    }

    /**
     * A saved edit is the one thing that can make the recorded number name a different card, since it
     * is a position in a list that has just been replaced. Nothing is the right answer then.
     */
    @Test
    fun `a rules reload drops the record, rather than leaving it pointing at a renumbered card`() {
        val rules = cancelVenue()
        connectVenue(rules)
        val venue = viewModel.sessions.first { it.isVenue }
        val client = connectClient("ALPHA")
        val pane = awaitPane("ALPHA")

        client.sendFixMessage(cancelOf("ORD-1", "CXL-1"), viewModel.dictionary)
        assertTrue(awaitCondition(15_000) { sent(pane, "9").isNotEmpty() })
        assertNotNull(venue.lastRuleFired.value)

        // The same rules with one more in front of them: every index below it now names its neighbour.
        val shifted = listOf(AcceptorPresets.byId("order-ack")!!.rules.single()) + rules
        assertNotNull(venue.reloadAcceptorRules(shifted, venue.currentConfig!!.acceptorLatency))

        assertNull(
            venue.lastRuleFired.value,
            "the number was a position in the ruleset that has just been replaced",
        )
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
