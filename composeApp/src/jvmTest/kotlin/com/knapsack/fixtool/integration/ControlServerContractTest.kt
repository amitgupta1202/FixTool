package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The control surface does what its tool descriptions and AUTOMATION.md say it does.**
 *
 * Each test here is a door that answered something other than its contract: a search that returned the
 * previous query's matches, a tool failure that reached the MCP client as a transport error, a session
 * index the send routes refused, a documented route that did not exist but answered 200. An agent driving
 * FixTool cannot see the code. It has the contract, and a door that quietly departs from it is a wrong
 * answer the agent will act on.
 */
class ControlServerContractTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val soh = '\u0001'

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-contract-test", "").apply {
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

    // ------------------------------------------------------------------ search

    @Test
    fun `a search answers with the matches for its own query, not the one before it`() {
        addSession("VENUE").receive(order("ORD-1"))

        assertEquals(1, count(search("ORD-1")), "the first search for ORD-1 finds the one message")
        assertEquals(0, count(search("NOPE-XYZ")), "a search that matches nothing finds nothing")
    }

    @Test
    fun `the same query posted twice scans twice`() {
        val venue = addSession("VENUE")
        venue.receive(order("ORD-1"))
        search("ORD-1")

        venue.receive(order("ORD-1"))

        assertEquals(2, count(search("ORD-1")), "the second message arrived after the first search")
    }

    @Test
    fun `a pinned search shows the pane what it answered, under its own query`() {
        addSession("VENUE").receive(order("ORD-1"))

        val answered = search("ORD-1")["results"]!!.jsonArray.map { it.jsonObject["raw"]!!.jsonPrimitive.content }

        assertEquals(answered, viewModel.pinnedSearchResults.value.map { it.message.rawMessage })
        assertEquals(listOf(order("ORD-1")), answered)
        assertEquals("ORD-1", viewModel.globalSearchQuery.value, "the search box says what the pane shows")
    }

    @Test
    fun `an unpinned search leaves the pane and the search box alone`() {
        addSession("VENUE").receive(order("ORD-1"))

        assertEquals(1, count(search("ORD-1", pin = false)))

        assertEquals(emptyList(), viewModel.pinnedSearchResults.value)
        assertEquals("", viewModel.globalSearchQuery.value)
    }

    // ------------------------------------------------------------------ failures

    /**
     * An LLM caller often passes an array as a JSON string. The handler's cast throws, and that used to come
     * back as a JSON-RPC error under `id: null`, which the MCP SDK's schema rejects (an id is a string or a
     * number), so the client reported a broken transport instead of the reason. MCP's word for a tool that
     * failed is a result with `isError`.
     */
    @Test
    fun `a tool that throws answers its own call with isError and the reason`() {
        addSession("VENUE")
        val call =
            """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"fixtool_assert",""" +
                """"arguments":{"messageType":"8","fields":"[{\"tag\":150}]"}}}"""

        val reply = obj(post("/mcp", call))

        assertEquals(JsonPrimitive(7), reply["id"], "the reply answers the call it was given: $reply")
        val result = assertNotNull(reply["result"]?.jsonObject, "a tool failure is a result, not an error: $reply")
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        val text =
            result["content"]!!
                .jsonArray
                .single()
                .jsonObject["text"]!!
                .jsonPrimitive.content
        assertTrue("JsonArray" in text, "the reason the tool failed reaches the caller: $text")
    }

    @Test
    fun `any other method that fails answers under the request's own id`() {
        val reply = obj(post("/mcp", """{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":"x"}"""))

        assertEquals(JsonPrimitive("init-1"), reply["id"], "$reply")
        assertNotNull(reply["error"]?.jsonObject, "$reply")
    }

    @Test
    fun `a body that is not JSON is a parse error with no id, because none could be read`() {
        val reply = obj(post("/mcp", "{"))

        assertEquals(JsonNull, reply["id"])
        assertEquals(-32700, reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.int, "$reply")
    }

    @Test
    fun `a request the caller got wrong is a 400 with the reason, not a 500`() {
        addSession("VENUE")

        val wrongShape = post("/assert", """{"fields":"x"}""")
        assertEquals(400, wrongShape.statusCode(), wrongShape.body())
        assertTrue("JsonArray" in obj(wrongShape)["error"]!!.jsonPrimitive.content, wrongShape.body())

        val notJson = post("/assert", "{")
        assertEquals(400, notJson.statusCode(), notJson.body())
        assertEquals("error", obj(notJson)["status"]!!.jsonPrimitive.content)

        // handleCoded's routes too: the job API reads its body through the same parser.
        val coded = post("/load", "{")
        assertEquals(400, coded.statusCode(), coded.body())
    }

    // ------------------------------------------------------------------ sessions by index

    /** Every tool says `session id/title/index`, and `GET /messages?session=1` always took one. */
    @Test
    fun `send takes a session by its index, as every other route does`() {
        addSession("FIRST")
        addSession("SECOND")

        val reply = obj(post("/send", """{"session":"1","raw":"35=D|11=ORD-1|55=EUR/USD|"}"""))

        assertEquals(1, viewModel.activeSessionIndex, "the send went from session 1: $reply")
        assertEquals(
            "session not found: 5",
            obj(post("/send", """{"session":"5","raw":"35=D|"}"""))["error"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a template is sent from a session named by its index`() {
        addSession("FIRST")
        addSession("SECOND")
        val profile =
            obj(post("/profiles", """{"name":"TplProf","config":{"port":"1"}}"""))["id"]!!.jsonPrimitive.content
        val template =
            obj(post("/templates", """{"profile":"$profile","name":"NOS","raw":"35=D|55=EUR/USD|"}"""))["id"]!!
                .jsonPrimitive.content

        val reply = obj(post("/templates/send", """{"id":"$template","session":"1"}"""))

        assertEquals("NOS", reply["template"]?.jsonPrimitive?.content, "session 1 was found: $reply")
        assertEquals(
            "session not found: 5",
            obj(post("/templates/send", """{"id":"$template","session":"5"}"""))["error"]?.jsonPrimitive?.content,
        )
    }

    // ------------------------------------------------------------------ routes

    /**
     * AUTOMATION.md documented `POST /sessions/{i}/clear-order-book`, which was never a route. The `/sessions`
     * context answered every path under it with the sessions list, so the call read as a 200 and cleared nothing.
     */
    @Test
    fun `a path under sessions that is no route is a 404 naming it, not the sessions list`() {
        addSession("VENUE")

        val unknown = post("/sessions/0/clear-order-book", "{}")

        assertEquals(404, unknown.statusCode(), unknown.body())
        assertTrue("/sessions/0/clear-order-book" in obj(unknown)["error"]!!.jsonPrimitive.content, unknown.body())
    }

    @Test
    fun `the sessions list and close all still answer at their own paths`() {
        addSession("VENUE")

        val listed = get("/sessions")
        assertEquals(200, listed.statusCode())
        val sessions = Json.parseToJsonElement(listed.body()).jsonArray
        assertEquals(listOf("VENUE"), sessions.map { it.jsonObject["title"]!!.jsonPrimitive.content })

        val closed = post("/sessions/close", "{}")
        assertEquals(200, closed.statusCode(), closed.body())
        assertEquals("closed", obj(closed)["status"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ wait

    /** `while (now < deadline)` ran no times at all for a zero timeout, so it answered timeout unlooked. */
    @Test
    fun `a wait with no time to wait still looks once`() {
        addSession("VENUE").receive(order("ORD-1"))

        fun wait(body: String) = obj(post("/wait", body))["status"]!!.jsonPrimitive.content

        assertEquals("matched", wait("""{"session":"VENUE","match":{"messageType":"D"},"timeoutMs":0}"""))
        assertEquals("matched", wait("""{"session":"VENUE","state":"DISCONNECTED","timeoutMs":0}"""))
        assertEquals("matched", wait("""{"session":"VENUE","state":"DISCONNECTED","timeoutMs":-5}"""), "coerced to 0")
        assertEquals("timeout", wait("""{"session":"VENUE","match":{"messageType":"8"},"timeoutMs":0}"""))
    }

    // ------------------------------------------------------------------ helpers

    private fun order(clOrdId: String) = "8=FIX.4.4|35=D|49=CLI|56=VENUE|11=$clOrdId|55=EUR/USD|54=1|38=100|40=1|"

    /** A pane in the view model, as the transport would add one. */
    private fun addSession(title: String): FixMessageSession {
        val sessionsField = FixMessageViewModel::class.java.getDeclaredField("_sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(viewModel) as MutableList<FixMessageSession>
        return FixMessageSession(title = title).also { sessions.add(it) }
    }

    private fun FixMessageSession.receive(raw: String) {
        addMessage(
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = raw,
                messageType = "D",
                quickfixMessage = Message(),
                wireRaw = raw.replace('|', soh),
            ),
        )
        flushMessageQueue() // addMessage enqueues and the UI pump drains, and a test has no pump.
    }

    private fun search(query: String, pin: Boolean = true): JsonObject =
        obj(post("/search", """{"query":"$query","pin":$pin}"""))

    private fun count(body: JsonObject): Int = body["count"]!!.jsonPrimitive.int

    private fun request(method: String, path: String, body: String?): HttpResponse<String> {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val req =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = request("GET", path, null)

    private fun post(path: String, body: String) = request("POST", path, body)

    private fun obj(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonObject
}
