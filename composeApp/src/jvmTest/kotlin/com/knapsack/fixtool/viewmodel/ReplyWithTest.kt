package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.service.AcceptorResponder
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Replying by hand answers the order in front of you, on the session it arrived on.**
 *
 * Both halves of that sentence are the test. The editor is a single surface shared by every session,
 * so "the message that is selected" and "the most recent message" are the same thing right up until
 * a second order arrives while the tester is reading the first — which, on a venue with several
 * clients logged on, is the normal case rather than the edge one. And the reply has to go back to the
 * client that sent the order: the editor sends to the active session, so pointing it there is part of
 * opening the reply, not something the tester should have to remember.
 *
 * What is *not* here is anything about which shapes exist or what they resolve to — that is
 * `AcceptorReplyShapesTest`, against the engine. This is about the gates and the wiring.
 */
class ReplyWithTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-reply", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun message(raw: String, direction: FixMessage.Direction = FixMessage.Direction.INCOMING) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = direction,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw),
        )

    /** A venue's session with [raw] sitting in it, as if a client had just sent it. */
    private fun venueWith(
        raw: String,
        name: String = "Venue",
        direction: FixMessage.Direction = FixMessage.Direction.INCOMING,
        type: FixConnectionConfig.ConnectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
    ): Pair<FixMessageSession, FixMessage> {
        val (_, session) = viewModel.createSessionWithProfileForTest(name, type)
        val msg = message(raw, direction)
        session.addMessage(msg)
        session.flushMessageQueue()
        return session to msg
    }

    private val order = "35=D|11=ORD-1|55=ACME|54=1|38=1000|40=2|44=185.25|60=20260731-09:14:22.000"
    private val ack = AcceptorPresets.shapeById("ack")!!

    private fun editorField(tag: String): String? =
        viewModel.editorFields.firstOrNull { it.tag == tag }?.value

    // ------------------------------------------------------------------ the gates

    @Test
    fun `an order on a venue's session can be replied to`() {
        val (_, msg) = venueWith(order)

        assertEquals(
            listOf("ack", "fill", "partial-fill", "fill-remainder", "order-reject"),
            viewModel.replyOffersFor(msg).map { it.shape.id },
        )
    }

    @Test
    fun `this venue's own reply is not something to reply to`() {
        val (_, msg) = venueWith(order, direction = FixMessage.Direction.OUTGOING)

        assertTrue(viewModel.replyOffersFor(msg).isEmpty(), "an outgoing message is the venue talking to itself")
    }

    @Test
    fun `an order seen on an initiator session is somebody else's to answer`() {
        val (_, msg) = venueWith(order, name = "Client", type = FixConnectionConfig.ConnectionType.INITIATOR)

        assertTrue(viewModel.replyOffersFor(msg).isEmpty(), "a client does not reply to orders")
    }

    @Test
    fun `a message in no session at all is offered nothing`() {
        assertTrue(viewModel.replyOffersFor(message(order)).isEmpty(), "nothing owns this message, so nothing can send for it")
    }

    // ------------------------------------------------------------------ what opening a reply does

    @Test
    fun `the reply lands in the editor, about that order, and nothing is sent`() {
        val (_, msg) = venueWith(order)

        assertTrue(viewModel.replyWith(msg, ack))

        assertTrue(viewModel.showMessageEditor.value, "the reply has to be visible to be edited")
        assertEquals("8", editorField("35"))
        assertEquals("ORD-1", editorField("11"), "the reply must name the order it answers")
        assertEquals("ACME", editorField("55"))
        assertEquals("0", editorField("150"))
        assertTrue(
            viewModel.sessions.all { session ->
                session.messages.value
                    .filterIsInstance<FixMessage>()
                    .none { it.direction == FixMessage.Direction.OUTGOING }
            },
            "opening a reply must not put anything on the wire",
        )
    }

    /**
     * The acceptance criterion this feature exists to satisfy: *that* message, not the latest one. Two
     * orders arrive, the tester picks the first, and the reply must be about the first.
     */
    @Test
    fun `the reply is about the message it was opened from, not the newest one`() {
        val (session, first) = venueWith(order)
        val second = message(order.replace("ORD-1", "ORD-2"))
        session.addMessage(second)
        session.flushMessageQueue()

        viewModel.replyWith(first, ack)

        assertEquals("ORD-1", editorField("11"), "the reply followed the selection, not the clock")
    }

    /**
     * A venue with two clients logged on has two sessions, and the editor sends to the active one. An
     * order answered on the wrong session is a reply the client that placed it never sees.
     */
    @Test
    fun `replying makes the session the order arrived on the active one`() {
        val (_, _) = venueWith(order, name = "Venue A")
        val (sessionB, msgB) = venueWith(order.replace("ORD-1", "ORD-B"), name = "Venue B")
        viewModel.setActiveSession(0)

        viewModel.replyWith(msgB, ack)

        assertEquals(sessionB, viewModel.activeSession, "the reply would have gone to the other client")
    }

    @Test
    fun `a shape this message cannot support is refused rather than half built`() {
        val marketOrder = "35=D|11=ORD-M|55=ACME|54=1|38=1000|40=1|60=20260731-09:14:22.000"
        val (_, msg) = venueWith(marketOrder)
        val fill = AcceptorPresets.shapeById("fill")!!

        assertFalse(viewModel.replyWith(msg, fill), "a fill with no price to fill at must not open")
        assertFalse(viewModel.showMessageEditor.value, "nothing was built, so nothing should have opened")
    }

    /**
     * The editor is one surface with two jobs (see `EditorTarget`), and a reply is for the wire. Opened
     * while a rule's step is borrowing it, Apply would otherwise write this message into that rule.
     */
    @Test
    fun `a reply takes the editor back from a rule's step`() {
        val (_, msg) = venueWith(order)
        viewModel.openReplyStep("profile-1", 0, 0, "35=8|150=0|39=0")

        viewModel.replyWith(msg, ack)

        assertTrue(
            viewModel.editorTarget is com.knapsack.fixtool.model.EditorTarget.Wire,
            "the editor must be composing for the wire, or Apply writes the reply into a rule",
        )
        assertEquals("ORD-1", editorField("11"))
    }
}
