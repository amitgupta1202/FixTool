package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end test for `fixtool_assert` / `fixtool_capture_expectation`: a FixTool acceptor
 * auto-responds to a NewOrderSingle with an ExecutionReport (echoing ClOrdID + Symbol), and the
 * control surface then machine-checks that report tag-by-tag — the exact "author the flow, assert
 * the response" loop the repeatable-scenarios design enables, with no eyeballing.
 */
class AssertIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val baseUrl get() = "http://127.0.0.1:$port"
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-assert-test", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        port = freePort()
        server = ControlServer(port, viewModel, windowProvider = { emptyList() }, token = null)
        server.start()
    }

    @After
    fun cleanup() {
        server.stop()
        viewModel.disconnectAllSessions()
        awaitCondition { viewModel.sessions.all { it.connectionState.value != FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    /**
     * Send used to judge the editor's body as a wire frame, which it never is — no BodyLength(9), no
     * CheckSum(10), the session supplies the sequencing header. So *every* send came back
     * "warning: validation bypassed", contradicting a green Validate and leaving an agent unable to
     * tell a real problem from the permanent noise. Send now asks the same linter Validate does.
     */
    @Test
    fun `a well-formed message sends clean, and a bad one says what is wrong`() {
        connectAcceptorAndClient()

        val wellFormed =
            """{"session":"CLI","raw":"35=D|11=ORD-OK|55=EUR/USD|54=1|38=100|40=1|60=20260713-10:00:00.000|"}"""
        assertEquals("sent", status(post("/send", wellFormed)), "a valid body must not warn")

        // ExecType(150) belongs to an ExecutionReport, not a NewOrderSingle: sent, but say so.
        val badTag =
            """{"session":"CLI","raw":"35=D|11=ORD-BAD|55=EUR/USD|54=1|38=100|40=1|60=20260713-10:00:00.000|150=2|"}"""
        val warned = obj(post("/send", badTag))
        assertEquals("warning", warned["status"]!!.jsonPrimitive.content)
        assertTrue(
            warned.toString().contains("150"),
            "the warning should name the offending tag, not blame construction: $warned",
        )
    }

    @Test
    fun `assert machine-checks an execution report tag by tag`() {
        connectAcceptorAndClient()

        // Author step: send the order. The acceptor auto-responds with an ExecutionReport.
        assertTrue(
            status(post("/send", """{"session":"CLI","raw":"35=D|11=ORD-ACC|55=EUR/USD|54=1|38=100|40=1|"}"""))
                in listOf("sent", "warning"),
        )
        assertEquals(
            "matched",
            status(post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")),
            "client should receive the acceptor's ExecutionReport",
        )

        // Assert step: every matcher should pass — including a reference matcher proving the
        // ExecutionReport's ClOrdID (11) echoes the order we sent (out.D.11), a presence matcher for
        // the venue-assigned OrderID (37), and a oneOf for OrdStatus (39).
        val fields =
            """[
              {"tag":35,"matcher":{"type":"exact","value":"8"}},
              {"tag":11,"matcher":{"type":"reference","expression":"${'$'}{out.D.11}"}},
              {"tag":37,"matcher":{"type":"presence"}},
              {"tag":39,"matcher":{"type":"oneOf","values":["0","1","2"]}},
              {"tag":55,"matcher":{"type":"exact","value":"EUR/USD"}},
              {"tag":150,"matcher":{"type":"exact","value":"0"}}
            ]"""
        val pass = obj(post("/assert", """{"session":"CLI","messageType":"8","direction":"in","timeoutMs":5000,"fields":$fields}"""))
        assertTrue(pass["passed"]!!.jsonPrimitive.boolean, "all matchers should pass; got $pass")
        // The reference matcher actually resolved and matched ORD-ACC.
        assertEquals("ORD-ACC", tagResult(pass, 11)["actual"]!!.jsonPrimitive.content)
        assertTrue(tagResult(pass, 11)["passed"]!!.jsonPrimitive.boolean, "reference matcher should resolve out.D.11")
        assertTrue(tagResult(pass, 37)["passed"]!!.jsonPrimitive.boolean, "OrderID should be present")

        // A wrong expectation fails on exactly the offending tag, and the report explains why.
        val fail =
            obj(post("/assert", """{"session":"CLI","messageType":"8","direction":"in","fields":[{"tag":39,"matcher":{"type":"exact","value":"2"}}]}"""))
        assertFalse(fail["passed"]!!.jsonPrimitive.boolean)
        val badTag = tagResult(fail, 39)
        assertFalse(badTag["passed"]!!.jsonPrimitive.boolean)
        assertEquals("0", badTag["actual"]!!.jsonPrimitive.content)
        assertEquals("2", badTag["expected"]!!.jsonPrimitive.content)
    }

    @Test
    fun `capture expectation auto-seeds matchers from the response`() {
        connectAcceptorAndClient()
        assertTrue(
            status(post("/send", """{"session":"CLI","raw":"35=D|11=ORD-CAP|55=EUR/USD|54=1|38=100|40=1|"}"""))
                in listOf("sent", "warning"),
        )
        assertEquals(
            "matched",
            status(post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")),
        )

        val captured = obj(post("/expectation/capture", """{"session":"CLI","messageType":"8","direction":"in"}"""))
        assertEquals("8", captured["messageType"]!!.jsonPrimitive.content)
        val byTag =
            captured["fields"]!!.jsonArray.associate {
                it.jsonObject["tag"]!!.jsonPrimitive.int to it.jsonObject["matcher"]!!.jsonObject["type"]!!.jsonPrimitive.content
            }
        assertEquals("exact", byTag[35], "MsgType should seed exact")
        assertEquals("presence", byTag[37], "OrderID should seed presence")
        assertFalse(byTag.containsKey(10), "CheckSum (volatile) should be omitted")
    }

    /**
     * The matcher table marks `toleranceSeconds` optional. Omitted, it parsed as ±0s — a matcher that
     * passes only if the venue's stamp equals the judging instant to the exact second, so the row went
     * red on a perfectly fresh reply with "~now ±0s" pointing at the venue rather than at the omitted
     * default. Omitted means the seeder's default (60s), the answer every other door already gives.
     */
    @Test
    fun `an omitted temporal tolerance means the seeder's default, not zero`() {
        connectAcceptorAndClient()
        post("/send", """{"session":"CLI","raw":"35=D|11=ORD-TOL|55=EUR/USD|54=1|38=100|40=1|"}""")
        assertEquals(
            "matched",
            status(post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")),
        )
        // Let the reply age past a second, so ±0s cannot sneak a same-second pass.
        Thread.sleep(1_500)
        val resp =
            obj(
                post(
                    "/assert",
                    """{"session":"CLI","messageType":"8","direction":"in","fields":[{"tag":52,"matcher":{"type":"temporal"}}]}""",
                ),
            )
        assertTrue(
            resp["passed"]!!.jsonPrimitive.boolean,
            "a seconds-old SendingTime must satisfy an omitted tolerance: $resp",
        )
    }

    /**
     * POST /validate is handed captured frames, not just editor drafts. The verdict stays a content
     * verdict (a stale frame is recomputed at send) — but a frame that disagrees with its own bytes
     * must be *said*, or an operator concludes a garbled capture is well-formed from the one surface
     * whose whole job is that verdict.
     */
    @Test
    fun `validate says when a frame disagrees with its own bytes`() {
        val garbled =
            """{"raw":"8=FIX.4.4|9=44|35=0|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|10=007|"}"""
        val resp = obj(post("/validate", garbled))
        val warnings = resp["warnings"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertTrue(
            warnings.any { it.contains("CheckSum(10)") || it.contains("BodyLength(9)") },
            "a frame its own arithmetic disproves must be named to the caller: $resp",
        )
    }

    @Test
    fun `assert is reachable over the MCP transport`() {
        connectAcceptorAndClient()
        post("/send", """{"session":"CLI","raw":"35=D|11=ORD-MCP|55=EUR/USD|54=1|38=100|40=1|"}""")
        post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")
        val text =
            mcpCall(
                "fixtool_assert",
                """{"session":"CLI","messageType":"8","direction":"in","fields":[{"tag":35,"matcher":{"type":"exact","value":"8"}}]}""",
            )
        assertTrue(text.contains("\"passed\":true"), "MCP fixtool_assert should pass on 35=8; got $text")
    }

    // ----------------------------------------------------------------- helpers

    private fun connectAcceptorAndClient() {
        val fixPort = freePort()
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                responseTemplate = "35=8|150=0|39=0|37=\${uuid}|11=\${req.11}|55=\${req.55}|",
            )
        val acceptor =
            FixConnectionProfile(
                name = "ACC",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "ACC$runId",
                        targetCompID = "CLI$runId",
                        port = fixPort.toString(),
                        socketAcceptPort = fixPort.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "accstore").absolutePath,
                        fileLogPath = File(testDir, "acclog").absolutePath,
                        acceptorResponseRules = listOf(rule),
                    ),
            )
        val cli =
            FixConnectionProfile(
                name = "CLI",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "CLI$runId",
                        targetCompID = "ACC$runId",
                        host = "localhost",
                        port = fixPort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "clistore").absolutePath,
                        fileLogPath = File(testDir, "clilog").absolutePath,
                    ),
            )
        listOf(acceptor, cli).forEach {
            viewModel.saveConnectionProfile(it)
            viewModel.connectProfile(it.id, it)
        }
        assertTrue(
            awaitCondition(15_000) {
                viewModel.sessions.any { it.title == "CLI" && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "client should log on to the FixTool acceptor",
        )
    }

    private fun tagResult(response: JsonObject, tag: Int): JsonObject =
        response["tags"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.int == tag }

    /** See [TestPorts]: an ephemeral port closed before it is bound is a race this suite kept losing. */
    private fun freePort(): Int = TestPorts.free()

    private fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(100)
        }
        return predicate()
    }

    private fun request(method: String, path: String, body: String?): HttpResponse<String> {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val req =
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String) = request("POST", path, body)

    private fun obj(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonObject

    private fun status(resp: HttpResponse<String>) = obj(resp)["status"]!!.jsonPrimitive.content

    private fun mcpCall(name: String, args: String): String {
        val body = """{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        return obj(post("/mcp", body))["result"]!!
            .jsonObject["content"]!!
            .jsonArray
            .first()
            .jsonObject["text"]!!
            .jsonPrimitive.content
    }
}
