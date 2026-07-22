package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The conversation view across the control surface, with two sessions in play.**
 *
 * The unit tests ([com.knapsack.fixtool.service.ConversationsTest] and its Rows sibling) prove the
 * grouping algorithm. They cannot prove the thing that actually broke in use: grouping and collapse
 * were held app-globally, so in a both-sides test — the shape this tool's major client runs — folding
 * a conversation in one pane folded the identically-labelled conversation in the other, and the
 * toggle turned every pane on at once. Both flags live on [FixMessageSession] now, and these tests
 * hold them there.
 *
 * The `/panel conversations` door exists because the toolbar button has no HTTP hook: without it an
 * agent driving FixTool cannot reach grouped state at all, and verifying it meant editing a source
 * default and rebuilding. Its session-targeting contract is asserted here for the same reason the
 * state is per-session — one pane is the unit a user thinks in.
 */
class ConversationsIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val baseUrl get() = "http://127.0.0.1:$port"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-conversations-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        port = TestPorts.free()
        server = ControlServer(port, viewModel, windowProvider = { emptyList() }, token = null)
        server.start()
    }

    @After
    fun cleanup() {
        server.stop()
        testDir.deleteRecursively()
    }

    /**
     * Two panes carrying **the same RFQ label**, which is the whole point: a both-sides test shows one
     * exchange from both chairs, and `RFQ-X` names a conversation in each. Injected rather than wired
     * over a socket — the transport is not what these tests are about, and [FixMessageSession] is where
     * the state under test lives.
     */
    private fun twoSessionsSharingALabel() {
        val sessionsField = FixMessageViewModel::class.java.getDeclaredField("_sessions")
        sessionsField.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(viewModel) as MutableList<FixMessageSession>
        listOf("VENUE" to FixMessage.Direction.INCOMING, "CLIENT" to FixMessage.Direction.OUTGOING)
            .forEach { (title, requestDirection) ->
                val session = FixMessageSession(title = title)
                session.addMessage(fix("35=R|131=RFQ-X|55=EUR/USD|38=1000000|", requestDirection))
                session.addMessage(fix("35=S|117=QX-1|131=RFQ-X|55=EUR/USD|", requestDirection.opposite()))
                session.addMessage(fix("35=0|", requestDirection)) // heartbeat -> Ungrouped
                session.flushMessageQueue() // addMessage enqueues; the UI pump drains it. No pump here.
                sessions.add(session)
            }
    }

    private fun FixMessage.Direction.opposite(): FixMessage.Direction =
        if (this == FixMessage.Direction.INCOMING) FixMessage.Direction.OUTGOING else FixMessage.Direction.INCOMING

    private fun fix(raw: String, direction: FixMessage.Direction): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = direction,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', FixMessageHelper.SOH),
        )

    private fun sessionNamed(title: String): FixMessageSession = viewModel.sessions.first { it.title == title }

    // ------------------------------------------------------------------ the control surface contract

    @Test
    fun `targeting one session groups only that session`() {
        twoSessionsSharingALabel()

        val resp = post("/panel", """{"panel":"conversations","show":true,"session":"VENUE"}""")

        assertEquals("ok", obj(resp)["status"]!!.jsonPrimitive.content)
        assertEquals("VENUE", obj(resp)["session"]!!.jsonPrimitive.content)
        assertTrue(sessionNamed("VENUE").groupByConversation.value)
        assertFalse(sessionNamed("CLIENT").groupByConversation.value, "the other pane must be untouched")
    }

    @Test
    fun `omitting the session sets every session, which is what the toolbar button does`() {
        twoSessionsSharingALabel()

        post("/panel", """{"panel":"conversations","show":true}""")

        assertTrue(viewModel.sessions.all { it.groupByConversation.value })

        post("/panel", """{"panel":"conversations","show":false}""")

        assertTrue(viewModel.sessions.none { it.groupByConversation.value })
    }

    @Test
    fun `an unknown session is refused rather than silently applied somewhere`() {
        twoSessionsSharingALabel()

        val resp = post("/panel", """{"panel":"conversations","show":true,"session":"NOPE"}""")

        assertEquals("error", obj(resp)["status"]!!.jsonPrimitive.content)
        assertTrue(viewModel.sessions.none { it.groupByConversation.value }, "nothing may have been grouped")
    }

    // ------------------------------------------------------------------ the defect this exists for

    /**
     * The reported bug, in the shape it was reported: *"when I click on stream A all sessions
     * expanded"*. Collapse is keyed by the conversation's label, and both panes have an `RFQ-X`.
     */
    @Test
    fun `folding a conversation in one pane leaves the same label folded-open in the other`() {
        twoSessionsSharingALabel()
        post("/panel", """{"panel":"conversations","show":true}""")

        sessionNamed("VENUE").toggleConversationCollapsed("RFQ-X")

        assertEquals(setOf("RFQ-X"), sessionNamed("VENUE").collapsedConversations.value)
        assertEquals(emptySet(), sessionNamed("CLIENT").collapsedConversations.value, "the other pane's RFQ-X stays open")
    }

    @Test
    fun `each pane's toggle is its own`() {
        twoSessionsSharingALabel()

        sessionNamed("VENUE").toggleGroupByConversation()

        assertTrue(sessionNamed("VENUE").groupByConversation.value)
        assertFalse(sessionNamed("CLIENT").groupByConversation.value)
    }

    /** Off is off: the default, and what the "additive only" promise rests on. */
    @Test
    fun `sessions start ungrouped and uncollapsed`() {
        twoSessionsSharingALabel()

        assertTrue(viewModel.sessions.none { it.groupByConversation.value })
        assertTrue(viewModel.sessions.all { it.collapsedConversations.value.isEmpty() })
    }

    // ------------------------------------------------------------------ helpers

    private fun post(path: String, body: String): HttpResponse<String> {
        val req =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun obj(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonObject
}
