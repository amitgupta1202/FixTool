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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.assertEquals

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
