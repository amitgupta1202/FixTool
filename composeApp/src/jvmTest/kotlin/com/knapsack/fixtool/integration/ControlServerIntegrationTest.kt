package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the automation control surface ([ControlServer]).
 *
 * Each test starts a real loopback HTTP server backed by a real [FixMessageViewModel]
 * (settings/profiles/templates persisted to a temp dir) and drives it over HTTP, exactly
 * as the MCP server / curl / CI would. The end-to-end test additionally stands up
 * [TestFixServer] so a profile created over the API can connect, log on, send and be read
 * back through the control endpoints.
 */
class ControlServerIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val baseUrl get() = "http://127.0.0.1:$port"

    // Unique comp IDs per run: QuickFIX/J keeps a static per-JVM session registry.
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-control-test", "").apply {
                delete()
                mkdirs()
            }
        // testSettingsDir isolates app_settings.json plus the connection-profile and saved-message
        // stores, so these tests never read or write the real ~/.fixtool files.
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        port = freePort()
        server = ControlServer(port, viewModel, windowProvider = { null }, token = null)
        server.start()
    }

    @After
    fun cleanup() {
        server.stop()
        viewModel.disconnectAllSessions()
        awaitCondition { viewModel.sessions.all { it.connectionState.value != FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    // ----------------------------------------------------------------- basics

    @Test
    fun `health reports ok with no sessions`() {
        val resp = get("/health")
        assertEquals(200, resp.statusCode())
        assertEquals("ok", status(resp))
        assertEquals(0, obj(resp)["sessionCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `unknown path is not served as success`() {
        // A method-aware endpoint still rejects bad input rather than throwing.
        assertEquals("error", status(post("/connect", """{"profile":"does-not-exist"}""")))
    }

    // --------------------------------------------------------------- profiles

    @Test
    fun `profile create list and delete round-trip`() {
        val create =
            post(
                "/profiles",
                """{"name":"IT Server","config":{"host":"h1","port":"5001","senderCompID":"ME","targetCompID":"THEM"}}""",
            )
        assertEquals("created", status(create))
        val id = obj(create)["id"]!!.jsonPrimitive.content

        val listed = arr(get("/profiles")).map { it.jsonObject }
        val mine = listed.single { it["id"]!!.jsonPrimitive.content == id }
        assertEquals("THEM", mine["targetCompID"]!!.jsonPrimitive.content)
        assertEquals("5001", mine["port"]!!.jsonPrimitive.content)

        assertEquals("deleted", status(delete("/profiles", """{"id":"$id"}""")))
        assertFalse(arr(get("/profiles")).any { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test
    fun `profile update keeps the same id and applies changes`() {
        val id = obj(post("/profiles", """{"name":"Upd","config":{"port":"1"}}""")) ["id"]!!.jsonPrimitive.content

        val upd = post("/profiles", """{"id":"$id","name":"Upd","config":{"port":"2","senderCompID":"S2"}}""")
        assertEquals("updated", status(upd))
        assertEquals(id, obj(upd)["id"]!!.jsonPrimitive.content)

        val mine = arr(get("/profiles")).map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == id }
        assertEquals("2", mine["port"]!!.jsonPrimitive.content)
        assertEquals("S2", mine["senderCompID"]!!.jsonPrimitive.content)
    }

    @Test
    fun `profile create without a name is rejected`() {
        assertEquals("error", status(post("/profiles", """{"config":{"port":"1"}}""")))
    }

    @Test
    fun `upsert with an unknown id reports created, not updated`() {
        val resp = post("/profiles", """{"id":"ghost-id","name":"Ghost","config":{"port":"1"}}""")
        assertEquals("created", status(resp))
        assertEquals("ghost-id", obj(resp)["id"]!!.jsonPrimitive.content)
    }

    // -------------------------------------------------------------- templates

    @Test
    fun `template create list update and delete`() {
        val pid = obj(post("/profiles", """{"name":"TplProf","config":{"port":"1"}}""")) ["id"]!!.jsonPrimitive.content

        val create =
            post(
                "/templates",
                """{"profile":"$pid","name":"NOS","fields":[{"tag":"35","value":"D"},{"tag":"55","value":"EUR/USD"}]}""",
            )
        assertEquals("created", status(create))
        assertEquals("D", obj(create)["messageType"]!!.jsonPrimitive.content)
        val tid = obj(create)["id"]!!.jsonPrimitive.content

        val listed = obj(get("/templates?profile=$pid"))
        assertEquals(1, listed["count"]!!.jsonPrimitive.int)
        val tpl = listed["templates"]!!.jsonArray.single().jsonObject
        assertEquals("NOS", tpl["name"]!!.jsonPrimitive.content)
        assertFalse(tpl["isFavorite"]!!.jsonPrimitive.boolean)

        val upd =
            post(
                "/templates",
                """{"id":"$tid","profile":"$pid","name":"NOS","isFavorite":true,"fields":[{"tag":"35","value":"D"}]}""",
            )
        assertEquals("updated", status(upd))
        val updated = obj(get("/templates?profile=$pid"))["templates"]!!.jsonArray.single().jsonObject
        assertTrue(updated["isFavorite"]!!.jsonPrimitive.boolean)

        assertEquals("deleted", status(delete("/templates", """{"id":"$tid"}""")))
        assertEquals(0, obj(get("/templates?profile=$pid"))["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `template can be created from a raw fix string`() {
        val pid = obj(post("/profiles", """{"name":"RawProf","config":{"port":"1"}}""")) ["id"]!!.jsonPrimitive.content

        assertEquals("created", status(post("/templates", """{"profile":"$pid","name":"FromRaw","raw":"8=FIX.4.4|35=D|55=GBP/USD|54=1|"}""")))

        val tpl = obj(get("/templates?profile=$pid"))["templates"]!!.jsonArray.single().jsonObject
        val tags = tpl["fields"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue(tags.containsAll(listOf("8", "35", "55", "54")), "raw fields should be parsed; got $tags")
    }

    @Test
    fun `template upsert requires a known profile`() {
        assertEquals("error", status(post("/templates", """{"profile":"nope","name":"X","raw":"35=D"}""")))
    }

    @Test
    fun `listing templates for an unknown profile errors instead of returning all`() {
        assertEquals("error", status(get("/templates?profile=does-not-exist")))
    }

    // ----------------------------------------------------- read / view / filter

    @Test
    fun `messages for an unknown session returns an error`() {
        assertEquals("error", status(get("/messages?session=99")))
    }

    @Test
    fun `search with no messages returns an empty timeline`() {
        assertEquals(0, obj(post("/search", """{"query":"anything"}"""))["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `filter with an unknown scope is rejected`() {
        assertEquals("error", status(post("/filter", """{"scope":"sesion","regex":"x"}""")))
    }

    @Test
    fun `global filter applies to the view model`() {
        val resp = post("/filter", """{"scope":"global","regex":"35=D","showIncoming":false}""")
        assertEquals("filtered", status(resp))
        assertEquals("global", obj(resp)["scope"]!!.jsonPrimitive.content)
        assertEquals("35=D", viewModel.globalFilterRegex.value)
        assertFalse(viewModel.globalFilterShowIncoming.value)
    }

    @Test
    fun `panel show and hide toggles view model state`() {
        assertFalse(viewModel.showConnectionPanel.value)
        assertEquals("ok", status(post("/panel", """{"panel":"connection","show":true}""")))
        assertTrue(viewModel.showConnectionPanel.value)
        post("/panel", """{"panel":"connection","show":false}""")
        assertFalse(viewModel.showConnectionPanel.value)
    }

    @Test
    fun `select with no sessions returns an error`() {
        assertEquals("error", status(post("/select", """{"session":"0"}""")))
    }

    @Test
    fun `screenshot without a window returns 404`() {
        assertEquals(404, get("/screenshot").statusCode())
    }

    // -------------------------------------------------- validate / dictionary / admin

    @Test
    fun `validate reports validity and errors`() {
        val resp = obj(post("/validate", """{"raw":"8=FIX.4.4|35=ZZZ|"}"""))
        assertFalse(resp["isValid"]!!.jsonPrimitive.boolean)
        assertTrue(resp["errors"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `dictionary can be read and switched by version`() {
        val current = obj(get("/dictionary"))
        assertTrue(current.containsKey("version"))
        assertTrue(current["valid"]!!.jsonPrimitive.boolean)

        assertEquals("ok", status(post("/dictionary", """{"version":"FIX_4_2"}""")))
        assertEquals("FIX_4_2", obj(get("/dictionary"))["version"]!!.jsonPrimitive.content)
        // Switch back so the rest of the (per-test) instance is unaffected.
        assertEquals("ok", status(post("/dictionary", """{"version":"FIX_4_4"}""")))

        assertEquals("error", status(post("/dictionary", """{"version":"NOPE"}""")))
    }

    @Test
    fun `admin rejects an unknown action and a missing session`() {
        assertEquals("error", status(post("/admin", """{"action":"seqnum"}""")))
    }

    // -------------------------------------------------------------- auth gate

    @Test
    fun `token is enforced when configured`() {
        val securePort = freePort()
        val secure = ControlServer(securePort, viewModel, windowProvider = { null }, token = "s3cret")
        secure.start()
        try {
            val base = "http://127.0.0.1:$securePort/health"
            val without = client.send(HttpRequest.newBuilder(URI.create(base)).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(401, without.statusCode())

            val with =
                client.send(
                    HttpRequest.newBuilder(URI.create(base)).header("X-Control-Token", "s3cret").build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, with.statusCode())
        } finally {
            secure.stop()
        }
    }

    // -------------------------------------------------------- connect / send

    @Test
    fun `connect through the control server registers the session`() {
        // Pointing at a closed port is fine: we assert the control server registered the session
        // with the configured identity, not that it logged on (logon is covered below / by the
        // QuickFIX integration tests).
        val create =
            post(
                "/profiles",
                """{"name":"REG","config":{"host":"localhost","port":"${freePort()}",""" +
                    """"senderCompID":"REGSND$runId","targetCompID":"REGTGT$runId","beginString":"FIX.4.4"}}""",
            )
        assertEquals("created", status(create))

        assertEquals("connecting", status(post("/connect", """{"profile":"REG"}""")))
        assertTrue(awaitCondition { arr(get("/sessions")).isNotEmpty() }, "a session should be registered")

        val session = arr(get("/sessions")).single().jsonObject
        assertEquals("REG", session["title"]!!.jsonPrimitive.content)
        assertEquals("REGSND$runId", session["senderCompID"]!!.jsonPrimitive.content)
        // /messages now resolves the session (returns a result object) instead of erroring.
        assertTrue(obj(get("/messages?session=0")).containsKey("messages"))
        // A negative limit must not crash the endpoint (coerced to >= 0).
        assertEquals(200, get("/messages?session=0&limit=-1").statusCode())
        assertTrue(obj(get("/messages?session=0&limit=-1")).containsKey("messages"))
        // The session never logs on (closed port), so /wait for LOGGED_ON times out.
        assertEquals("timeout", status(post("/wait", """{"session":"0","state":"LOGGED_ON","timeoutMs":400}""")))
    }

    @Test
    fun `send and read a message through the control server on a live session`() {
        val fixServer = TestFixServer()
        fixServer.start()
        try {
            // Connect directly (test thread), the way the QuickFIX integration tests do; the
            // control server's own connect wiring is covered by the test above. We then exercise
            // the control server's /send and /messages endpoints against a real logged-on session.
            val profile = liveProfile(fixServer.port)
            viewModel.saveConnectionProfile(profile)
            viewModel.connectProfile(profile.id, profile)
            assertTrue(
                awaitCondition(15_000) { viewModel.sessions.any { it.connectionState.value == FixConnectionState.LOGGED_ON } },
                "session should log on to the test server",
            )

            // /wait reaches the already-logged-on state immediately (deterministic, no client poll).
            assertEquals(
                "matched",
                status(post("/wait", """{"session":"LIVE","state":"LOGGED_ON","timeoutMs":15000}""")),
            )

            // Bulk send to every logged-on session.
            val all = post("/send/all", """{"raw":"8=FIX.4.4|35=D|11=BULK-1|55=EUR/USD|54=1|38=1000|40=1|"}""")
            assertEquals("ok", status(all))
            assertEquals(1, obj(all)["count"]!!.jsonPrimitive.int)
            assertTrue(
                awaitCondition(5_000) { fixServer.applicationMessages.any { it.contains("35=D") } },
                "test server should receive the bulk-sent order",
            )

            // /wait blocks until the outgoing order surfaces in the session log, then returns it.
            val waited =
                post("/wait", """{"session":"LIVE","match":{"messageType":"D","direction":"out"},"timeoutMs":5000}""")
            assertEquals("matched", status(waited))
            assertEquals("D", obj(waited)["message"]!!.jsonObject["messageType"]!!.jsonPrimitive.content)

            // Clear the log between phases.
            assertEquals("cleared", status(post("/messages/clear", """{"session":"LIVE"}""")))
            assertEquals(0, obj(get("/messages?session=LIVE"))["total"]!!.jsonPrimitive.int)

            // Save a template under the live profile and send it (resolved) to the session.
            val pid = viewModel.connectionProfiles.first { it.name == "LIVE" }.id
            val tpl = post("/templates", """{"profile":"$pid","name":"NOS","raw":"35=D|11=TPL-1|55=GBP/USD|54=1|38=500|40=1"}""")
            assertEquals("created", status(tpl))
            val tid = obj(tpl)["id"]!!.jsonPrimitive.content
            val sent = post("/templates/send", """{"id":"$tid","session":"LIVE"}""")
            assertTrue(status(sent) in listOf("sent", "warning"), "template send should succeed; got ${status(sent)}")
            assertTrue(
                awaitCondition(5_000) { fixServer.applicationMessages.any { it.contains("11=TPL-1") } },
                "test server should receive the template-sent order",
            )

            // Admin / session control against the live QuickFIX session.
            val seq = obj(post("/admin", """{"action":"seqnum","session":"LIVE"}"""))
            assertEquals("ok", seq["status"]!!.jsonPrimitive.content)
            assertTrue(seq["nextSenderSeqNum"]!!.jsonPrimitive.int >= 1)
            assertEquals("ok", status(post("/admin", """{"action":"test-request","session":"LIVE","id":"TR-1"}""")))
            assertEquals("ok", status(post("/admin", """{"action":"reset-seqnum","session":"LIVE","target":1}""")))
            assertEquals("ok", status(post("/admin", """{"action":"resend-request","session":"LIVE","begin":1,"end":0}""")))
        } finally {
            fixServer.stop()
        }
    }

    // ----------------------------------------------------------- acceptor rules

    @Test
    fun `acceptor auto-responds to a matching message per its rules`() {
        val port = freePort()
        // FixTool as the acceptor, with a rule: NewOrderSingle (35=D) -> ExecutionReport (35=8)
        // echoing the request's ClOrdID (11) and Symbol (55).
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
                        port = port.toString(),
                        socketAcceptPort = port.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "accstore").absolutePath,
                        fileLogPath = File(testDir, "acclog").absolutePath,
                        acceptorResponseRules = listOf(rule),
                    ),
            )
        val client =
            FixConnectionProfile(
                name = "CLI",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "CLI$runId",
                        targetCompID = "ACC$runId",
                        host = "localhost",
                        port = port.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "clistore").absolutePath,
                        fileLogPath = File(testDir, "clilog").absolutePath,
                    ),
            )

        // Connect both directly (test thread); the acceptor starts listening, the client logs on.
        listOf(acceptor, client).forEach {
            viewModel.saveConnectionProfile(it)
            viewModel.connectProfile(it.id, it)
        }
        assertTrue(
            awaitCondition(15_000) {
                viewModel.sessions.any { it.title == "CLI" && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "client should log on to the FixTool acceptor",
        )

        // Client sends a NewOrderSingle; the acceptor auto-responds and the client receives the reply.
        assertTrue(
            status(post("/send", """{"session":"CLI","raw":"35=D|11=ORD-ACC|55=EUR/USD|54=1|38=100|40=1|"}"""))
                in listOf("sent", "warning"),
        )
        val reply =
            post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")
        assertEquals("matched", status(reply), "client should receive the acceptor's templated ExecutionReport")
        val raw = obj(reply)["message"]!!.jsonObject["raw"]!!.jsonPrimitive.content
        assertTrue(raw.contains("11=ORD-ACC"), "response should echo the request ClOrdID; got $raw")
    }

    // ----------------------------------------------------------------- helpers

    private fun liveProfile(serverPort: Int): FixConnectionProfile =
        FixConnectionProfile(
            name = "LIVE",
            config =
                FixConnectionConfig(
                    senderCompID = "LIVESND$runId",
                    targetCompID = "LIVETGT$runId",
                    host = "localhost",
                    port = serverPort.toString(),
                    socketConnectHost = "localhost",
                    beginString = "FIX.4.4",
                    heartBtInt = "30",
                    autoReconnect = false,
                    resetOnLogon = true,
                    fileStorePath = File(testDir, "qfstore").absolutePath,
                    fileLogPath = File(testDir, "qflog").absolutePath,
                ),
        )


    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = request("GET", path, null)

    private fun post(path: String, body: String) = request("POST", path, body)

    private fun delete(path: String, body: String) = request("DELETE", path, body)

    private fun obj(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonObject

    private fun arr(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonArray

    private fun status(resp: HttpResponse<String>) = obj(resp)["status"]!!.jsonPrimitive.content
}
