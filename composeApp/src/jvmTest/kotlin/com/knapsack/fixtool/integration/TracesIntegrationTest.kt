package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The two read routes an agent follows one exchange with**, over two panes that only a venue's own
 * id connects.
 *
 * [com.knapsack.fixtool.service.TracesTest] proves the relation. What it cannot prove is the thing the
 * control surface is for: that an agent asking *what happened to this exchange* gets one answer with
 * every session in it, in one order, at the same fidelity `/messages` gives — instead of reading ids
 * off four panes and posting a regex to `/search`, where `ORD-9` also matches `ORD-91` and a missed id
 * is silent.
 *
 * The fixture is the venue shape in miniature: the client calls it `RFQ-A1`, the LP calls it `V-2291`,
 * and the only thing joining them is the `QuoteID` both quotes carry. No rule anywhere names that
 * path — the shared value is the whole edge — so a test that passes here is a test that the join
 * survives the trip through HTTP with its sessions and its order intact.
 */
class TracesIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val baseUrl get() = "http://127.0.0.1:$port"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-traces-test", "").apply {
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

    // ------------------------------------------------------------------ the fixture

    private val epoch = LocalDateTime.of(2026, 9, 2, 10, 0, 0)

    private val out = FixMessage.Direction.OUTGOING

    /** A message at [millis] past the epoch, so the test states the merged order it expects. */
    private fun at(
        millis: Long,
        raw: String,
        direction: FixMessage.Direction = FixMessage.Direction.INCOMING,
    ): FixMessage =
        FixMessage(
            timestamp = epoch.plusNanos(millis * 1_000_000L),
            direction = direction,
            rawMessage = raw,
            quickfixMessage = Message(),
            // The named constant, not a literal SOH: an invisible control character in source is a hazard.
            wireRaw = raw.replace('|', FixMessageHelper.SOH),
        )

    /**
     * Two panes of one exchange, bridged by a value neither pane mentions twice.
     *
     * `CLIENT` sends `RFQ-A1` and is quoted back; `LP-1` receives the venue's forwarded copy under the
     * venue's own `V-2291` and quotes it. Nothing on `CLIENT` says `V-2291` and nothing on `LP-1` says
     * `RFQ-A1`: `Q-77` on both quotes is the single edge. The heartbeat carries no correlation id and
     * must therefore be counted as ungrouped rather than tidied away.
     *
     * Injected rather than wired over a socket — the transport is not what this is about, and
     * [FixMessageSession] is what the routes read.
     */
    private fun clientAndLiquidityProvider() {
        val sessionsField = FixMessageViewModel::class.java.getDeclaredField("_sessions")
        sessionsField.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(viewModel) as MutableList<FixMessageSession>

        val clientPane = FixMessageSession(title = "CLIENT")
        clientPane.addMessage(at(0, "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|", out))
        clientPane.addMessage(at(5, "35=0|")) // heartbeat -> ungrouped
        clientPane.addMessage(at(40, "35=S|131=RFQ-A1|117=Q-77|"))

        val lpPane = FixMessageSession(title = "LP-1")
        lpPane.addMessage(at(10, "35=R|131=V-2291|55=EUR/USD|38=10000000|"))
        lpPane.addMessage(at(30, "35=S|131=V-2291|117=Q-77|", out))

        // addMessage enqueues; the UI pump drains it. There is no pump here.
        listOf(clientPane, lpPane).forEach {
            it.flushMessageQueue()
            sessions.add(it)
        }
    }

    // ------------------------------------------------------------------ GET /traces

    @Test
    fun `traces reports one trace over two sessions, named by the id that opened it`() {
        clientAndLiquidityProvider()

        val body = obj(get("/traces"))
        val trace = body["traces"]!!.jsonArray.single().jsonObject

        assertEquals("RFQ-A1", trace["label"]!!.jsonPrimitive.content, "the first id on the earliest message")
        assertEquals(131, trace["labelTag"]!!.jsonPrimitive.int)
        assertEquals(
            setOf("RFQ-A1", "V-2291", "Q-77"),
            trace["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "every value in the component, so a header can explain the grouping",
        )
        assertEquals(
            listOf(0 to "CLIENT", 1 to "LP-1"),
            trace["sessions"]!!.jsonArray.map { it.jsonObject.asSessionRef() },
            "both panes, in the order the trace first appeared on each",
        )
        assertEquals(4, trace["messageCount"]!!.jsonPrimitive.int, "the heartbeat is not part of the exchange")
        assertEquals(
            listOf("R" to 2, "S" to 2),
            trace["composition"]!!.jsonArray.map { part ->
                part.jsonObject["messageType"]!!.jsonPrimitive.content to part.jsonObject["count"]!!.jsonPrimitive.int
            },
        )
        assertEquals("EUR/USD", trace["instrument"]!!.jsonPrimitive.content)
        assertEquals("10000000", trace["quantity"]!!.jsonPrimitive.content)
        assertEquals(40L, trace["elapsedMillis"]!!.jsonPrimitive.long, "first to last across both panes")
        // Nothing in this flow stated an OrdStatus, and the header says so rather than deciding one.
        assertEquals(JsonNull, trace["status"], "a status is quoted or it is null; it is never inferred")
        assertEquals(emptyList(), trace["truncatedSessions"]!!.jsonArray.toList(), "nothing was evicted")
    }

    /**
     * The 8% a view is tempted to drop. A heartbeat carries no correlation id, so it belongs to no
     * trace — and is counted rather than quietly missing from the totals.
     */
    @Test
    fun `a heartbeat is counted under ungrouped, and the totals add up`() {
        clientAndLiquidityProvider()

        val body = obj(get("/traces"))

        assertEquals(1, body["ungrouped"]!!.jsonPrimitive.int)
        assertEquals(5, body["total"]!!.jsonPrimitive.int, "four in the trace plus the heartbeat")
    }

    @Test
    fun `no sessions is an empty answer rather than a failure`() {
        val body = obj(get("/traces"))

        assertEquals(0, body["traces"]!!.jsonArray.size)
        assertEquals(0, body["ungrouped"]!!.jsonPrimitive.int)
        assertEquals(0, body["total"]!!.jsonPrimitive.int)
    }

    // ------------------------------------------------------------------ GET /trace?id=

    /**
     * **The venue's id returns the client's messages too**, which is the whole point: `V-2291` appears
     * nowhere on the CLIENT pane, and asking for it still answers with what the client sent.
     */
    @Test
    fun `trace returns every message merged across sessions, with its pane and its gap`() {
        clientAndLiquidityProvider()

        val resp = get("/trace?id=V-2291")
        assertEquals(200, resp.statusCode())
        val body = obj(resp)

        assertEquals("RFQ-A1", body["label"]!!.jsonPrimitive.content)
        assertEquals(4, body["messageCount"]!!.jsonPrimitive.int)
        assertEquals(40L, body["elapsedMillis"]!!.jsonPrimitive.long)

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(0 to "CLIENT", 1 to "LP-1", 1 to "LP-1", 0 to "CLIENT"),
            messages.map { it["session"]!!.jsonObject.asSessionRef() },
            "merged time order across panes, not one pane after the other",
        )
        assertEquals(listOf("R", "R", "S", "S"), messages.map { it["messageType"]!!.jsonPrimitive.content })

        assertEquals(JsonNull, messages.first()["elapsedMillis"], "the first message has nothing to be measured from")
        assertEquals(
            listOf(10L, 20L, 10L),
            messages.drop(1).map { it["elapsedMillis"]!!.jsonPrimitive.long },
            "each gap is from the previous message in the trace, on whichever pane it landed",
        )

        // The same per-message shape /messages emits — one serialiser, so the read surface cannot
        // disagree with itself about what arrived.
        val first = messages.first()
        assertTrue(first["wireOrderKnown"]!!.jsonPrimitive.boolean)
        assertNotNull(first["timestamp"])
        assertEquals("OUTGOING", first["direction"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(35 to "R", 131 to "RFQ-A1", 55 to "EUR/USD", 38 to "10000000"),
            first["fields"]!!.jsonArray.map { field ->
                field.jsonObject["tag"]!!.jsonPrimitive.int to field.jsonObject["value"]!!.jsonPrimitive.content
            },
            "the ordered fields array, in the venue's order",
        )
    }

    /** Three names for one exchange: any id the trace carries answers with the same trace. */
    @Test
    fun `any id in the trace finds it`() {
        clientAndLiquidityProvider()

        val byClientId = obj(get("/trace?id=RFQ-A1"))
        val byQuoteId = obj(get("/trace?id=Q-77"))

        assertEquals(byClientId, byQuoteId)
        assertEquals(4, byQuoteId["messages"]!!.jsonArray.size)
    }

    /**
     * **The defect this route exists to remove.** `/search` would happily match `RFQ-A` inside
     * `RFQ-A1` and hand back rows; here it is not the id, so there is no trace, and the caller is told
     * so rather than shown a plausible answer to a question they did not ask.
     */
    @Test
    fun `a substring of an id is not a match`() {
        clientAndLiquidityProvider()

        val resp = get("/trace?id=RFQ-A")

        assertEquals(404, resp.statusCode())
        assertEquals("error", obj(resp)["status"]!!.jsonPrimitive.content)
        assertTrue(obj(resp)["error"]!!.jsonPrimitive.content.contains("RFQ-A"), "the body names the id it could not find")
    }

    @Test
    fun `an id nobody minted is a 404 naming it, not an empty trace`() {
        clientAndLiquidityProvider()

        val resp = get("/trace?id=ORD-NEVER-SENT")

        assertEquals(404, resp.statusCode())
        assertTrue(obj(resp)["error"]!!.jsonPrimitive.content.contains("ORD-NEVER-SENT"))
    }

    @Test
    fun `asking for no id at all is refused`() {
        clientAndLiquidityProvider()

        assertEquals(400, get("/trace").statusCode())
        assertEquals(400, get("/trace?id=").statusCode(), "blank is missing")
        assertEquals("error", obj(get("/trace"))["status"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ POST /panel {"panel":"trace"}

    /**
     * The write half of the same feature. `/traces` and `/trace` tell an agent what happened; this puts
     * it on screen and narrows every pane to it, so a verification screenshot shows the exchange being
     * asserted on rather than four panes of everything.
     */
    @Test
    fun `panel trace opens the ledger without following anything`() {
        clientAndLiquidityProvider()

        val body = obj(post("/panel", """{"panel":"trace"}"""))

        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals("trace", body["panel"]!!.jsonPrimitive.content)
        assertTrue(body["show"]!!.jsonPrimitive.boolean)
        assertTrue(viewModel.tracePanelOpen.value)
        assertEquals(JsonNull, body["following"], "the Ledger lists every trace; it follows none of them")
    }

    /** Any id the exchange carries follows it, and following is what opens the panel. */
    @Test
    fun `follow narrows to one exchange, by the venue's own id, and opens the panel`() {
        clientAndLiquidityProvider()

        val body = obj(post("/panel", """{"panel":"trace","follow":"V-2291"}"""))

        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals("RFQ-A1", body["following"]!!.jsonPrimitive.content, "the label, resolved from the anchor")
        assertEquals("V-2291", body["followingAnchor"]!!.jsonPrimitive.content, "and the id that was asked for")
        assertEquals(2, body["sessionCount"]!!.jsonPrimitive.int)
        assertEquals(4, body["messageCount"]!!.jsonPrimitive.int)
        assertEquals(false, body["pending"]!!.jsonPrimitive.boolean)
        assertTrue(body["show"]!!.jsonPrimitive.boolean, "following IS the gesture that opens the Ledger")
        assertEquals(setOf("RFQ-A1", "V-2291", "Q-77"), followedIds(), "the whole component, not the one id")
    }

    /**
     * An id nobody has minted **yet** is followed with nothing in it rather than refused. A venue that
     * mints its handle three hops in makes that the normal case, and un-following on the caller's behalf
     * would lose the instruction. `pending` is how that is told apart from a typo, which `/trace`
     * answers with a 404.
     */
    @Test
    fun `an id that has not arrived is followed as pending, not refused`() {
        clientAndLiquidityProvider()

        val body = obj(post("/panel", """{"panel":"trace","follow":"V-ORD-8813"}"""))

        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals("V-ORD-8813", body["following"]!!.jsonPrimitive.content)
        assertTrue(body["pending"]!!.jsonPrimitive.boolean)
        assertEquals(0, body["messageCount"]!!.jsonPrimitive.int)
        assertEquals(404, get("/trace?id=V-ORD-8813").statusCode(), "the read route still says it does not exist")
    }

    @Test
    fun `follow null stops following`() {
        clientAndLiquidityProvider()
        post("/panel", """{"panel":"trace","follow":"RFQ-A1"}""")

        val body = obj(post("/panel", """{"panel":"trace","follow":null}"""))

        assertEquals(JsonNull, body["following"])
        assertNull(viewModel.followedTrace.value)
    }

    /**
     * Closing the panel is closing the panel. The panes stay narrowed and the toolbar chip goes on
     * naming what to — the same asymmetry the ✕ on the panel header has.
     */
    @Test
    fun `show false closes the panel and leaves the follow alone`() {
        clientAndLiquidityProvider()
        post("/panel", """{"panel":"trace","follow":"RFQ-A1"}""")

        val body = obj(post("/panel", """{"panel":"trace","show":false}"""))

        assertEquals(false, body["show"]!!.jsonPrimitive.boolean)
        assertEquals("RFQ-A1", body["following"]!!.jsonPrimitive.content)
        assertTrue(viewModel.followedTrace.value != null)
    }

    /** One call to say one thing: follow this exchange, and do not put the panel on screen for it. */
    @Test
    fun `follow and show combine`() {
        clientAndLiquidityProvider()

        val body = obj(post("/panel", """{"panel":"trace","follow":"Q-77","show":false}"""))

        assertEquals("RFQ-A1", body["following"]!!.jsonPrimitive.content)
        assertEquals(false, body["show"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a blank follow is refused rather than read as null`() {
        clientAndLiquidityProvider()

        val body = obj(post("/panel", """{"panel":"trace","follow":""}"""))

        assertEquals("error", body["status"]!!.jsonPrimitive.content)
        assertNull(viewModel.followedTrace.value)
    }

    // ------------------------------------------------------------------ helpers

    /** The ids of the trace the app is following, read back off the index the panes narrow by. */
    private fun followedIds(): Set<String> {
        val anchor = viewModel.followedTrace.value?.anchorId
        return viewModel.traceIndex.value
            ?.grouping
            ?.traces
            ?.firstOrNull { anchor != null && anchor in it.ids }
            ?.ids
            .orEmpty()
    }

    private fun post(
        path: String,
        body: String,
    ): HttpResponse<String> {
        val req =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String): HttpResponse<String> {
        val req =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun obj(resp: HttpResponse<String>): JsonObject = Json.parseToJsonElement(resp.body()).jsonObject

    /** A `{index,title}` session reference as the pair a test can state in one line. */
    private fun JsonObject.asSessionRef(): Pair<Int, String> =
        this["index"]!!.jsonPrimitive.int to this["title"]!!.jsonPrimitive.content
}
