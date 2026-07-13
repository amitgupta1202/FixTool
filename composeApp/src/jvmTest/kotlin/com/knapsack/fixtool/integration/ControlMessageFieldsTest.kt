package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /messages` must report the fields the **venue** sent.
 *
 * This one was found by driving the real app against a venue that sends
 * `58=filled|in full` — a perfectly ordinary FIX rejection, and legal, because `|` is just a character
 * inside a value. FixTool reported `58 = "filled"`. The tail was gone.
 *
 * The endpoint built its `fields` array by splitting `rawMessage` — the *display* string, where SOH has
 * been replaced by `|` precisely so a human can read it — so a `|` inside a value was indistinguishable
 * from a field boundary. And this array is the one an agent is told to trust: the tool description for
 * `fixtool_get_messages` says, in as many words, *"assert against these rather than screenshots"*.
 *
 * So the read surface and the assert surface disagreed about what had arrived. An agent reading
 * `58 = "filled"` would compose `{"tag": 58, "matcher": {"type": "exact", "value": "filled"}}`, and
 * `fixtool_assert` — which reads the venue's real bytes — would fail it. The tool would have handed the
 * agent a broken assertion and then blamed it for being broken.
 *
 * No unit test caught it because every fixture in the suite used values without a pipe in them.
 */
class ControlMessageFieldsTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val soh = ''

    /** The venue's bytes. Note tag 58: one field, whose value happens to contain a pipe. */
    private val wire =
        "8=FIX.4.4${soh}35=8${soh}34=2${soh}49=VENUE${soh}56=US${soh}11=ORD-1${soh}39=8$soh" +
            "58=filled|in full${soh}10=004$soh"

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-fields-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        val sessionsField = FixMessageViewModel::class.java.getDeclaredField("_sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(viewModel) as MutableList<FixMessageSession>
        val session = FixMessageSession(title = "VENUE")
        session.addMessage(
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                // Exactly how the transport builds the pair: a lossy display string, and the real bytes.
                rawMessage = wire.replace(soh, '|'),
                messageType = "8",
                quickfixMessage = Message(),
                wireRaw = wire,
            ),
        )
        session.flushMessageQueue() // addMessage enqueues; the UI pump drains it. No pump in a test.
        sessions.add(session)

        port = freePort()
        server = ControlServer(port, viewModel, windowProvider = { null }, token = null)
        server.start()
    }

    @After
    fun cleanup() {
        server.stop()
        testDir.deleteRecursively()
    }

    @Test
    fun `a value containing a pipe is one field, not a truncated one`() {
        val resp =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/messages?session=VENUE")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, resp.statusCode())

        val body = Json.parseToJsonElement(resp.body()).jsonObject
        val message = body["messages"]!!.jsonArray.single().jsonObject
        val fields = message["fields"]!!.jsonArray.map { it.jsonObject }
        val byTag = fields.associate { it["tag"]!!.jsonPrimitive.int to it["value"]!!.jsonPrimitive.content }

        assertEquals(
            "filled|in full",
            byTag[58],
            "the venue sent one Text field; splitting the display string on '|' truncates it to \"filled\"",
        )

        // And it does not invent one either: "in full" carries no '=', so a bad split silently dropped it
        // rather than producing a visible phantom — which is why this went unnoticed.
        assertEquals(
            listOf(8, 35, 34, 49, 56, 11, 39, 58, 10),
            fields.map { it["tag"]!!.jsonPrimitive.int },
            "the fields, in the venue's order, with nothing lost and nothing invented",
        )
    }

    /** The same array must also carry every occurrence — it is what an agent seeds its rows from. */
    @Test
    fun `every occurrence of a repeated tag is reported, in wire order`() {
        val sessionsField = FixMessageViewModel::class.java.getDeclaredField("_sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(viewModel) as MutableList<FixMessageSession>
        val parties =
            "8=FIX.4.4${soh}35=8${soh}34=3${soh}49=VENUE${soh}56=US$soh" +
                "453=2${soh}448=FIRMA${soh}447=D${soh}452=1${soh}448=FIRMA${soh}447=D${soh}452=4${soh}10=005$soh"
        sessions[0].addMessage(
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = parties.replace(soh, '|'),
                messageType = "8",
                quickfixMessage = Message(),
                wireRaw = parties,
            ),
        )
        sessions[0].flushMessageQueue()

        val resp =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/messages?session=VENUE")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val body = Json.parseToJsonElement(resp.body()).jsonObject
        val messages = body["messages"]!!.jsonArray
        val fields = (messages.last() as JsonObject)["fields"]!!.jsonArray.map { it.jsonObject }

        val roleRows = fields.filter { it["tag"]!!.jsonPrimitive.int == 452 }
        val roles = roleRows.map { it["value"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("1", "4"),
            roles,
            "both party entries must be reported: an agent that sees one 452 seeds one assertion and " +
                "leaves the clearing firm unchecked",
        )
        assertTrue(fields.count { it["tag"]!!.jsonPrimitive.int == 448 } == 2)
    }
}
