package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.MatchContextMode
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
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
import kotlin.test.assertNull
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

    // ----------------------------------------------------- the diff, opened without a hand

    /**
     * **The one thing the control surface could not do, and the reason the diff had never been seen live.**
     *
     * Every repair in the reconcile surface is a click, and this server cannot click — so the one surface in
     * the app that authors an assertion was the one surface no automated run had ever opened against a real
     * venue's bytes. `/scenarios/reconcile` is that click, and it is not a new door: it calls `openReconcile`
     * on the same `StepResult`, through the same `reconcileRoute` decider, as the rail's **Reconcile →**.
     *
     * With no run behind it there is nothing to reconcile, and it says so rather than opening an empty tab.
     */
    @Test
    fun `reconcile with no run behind it refuses, and says why`() {
        val resp = post("/scenarios/reconcile", "{}")
        assertEquals("error", status(resp))
        assertTrue(
            "no scenario has been run" in obj(resp)["error"]!!.jsonPrimitive.content,
            obj(resp)["error"]!!.jsonPrimitive.content,
        )
    }

    /**
     * **The whole loop, with no hand on the mouse:** a live acceptor, a scenario that fails against it, and
     * the diff opened on the step that failed — bound to the bytes that failed it.
     *
     * This is the thing the surface could never prove about itself. The route decides (a scenario the author
     * has edited since it ran is *refused*, in `reconcileRoute`'s own words); when it opens, a diff **window**
     * stands up on the right `stepId`, carrying a session whose reference is the failing message and whose
     * verdict is the engine's — not a tab that merely looks right.
     */
    @Test
    fun `reconcile opens the diff on the failing step, bound to the bytes that failed it`() {
        val fixServer = TestFixServer()
        fixServer.start()
        try {
            val profile = liveProfile(fixServer.port)
            viewModel.saveConnectionProfile(profile)
            viewModel.connectProfile(profile.id, profile)
            assertTrue(
                awaitCondition(15_000) { viewModel.sessions.any { it.connectionState.value == FixConnectionState.LOGGED_ON } },
                "session should log on to the test server",
            )

            // A step that MATCHES a message and FAILS an assertion on it — which is the only kind of failure
            // the diff can be opened on, and the kind this endpoint exists to reach. (The order goes out; the
            // step asserts a quantity it does not carry.)
            val scenario =
                """
                {"name":"LOOP","steps":[
                  {"type":"send","session":"LIVE","raw":"35=D|11=${'$'}{id0 = \"LOOP-1\"}|55=EUR/USD|54=1|38=1000|40=1|"},
                  {"type":"expect","session":"LIVE","direction":"out","timeoutMs":5000,
                   "match":{"messageType":"D"},
                   "expectation":{"messageType":"D","mode":"open",
                     "fields":[{"tag":38,"matcher":{"type":"exact","value":"9999"}}]}}
                ]}
                """.trimIndent()
            val saved = obj(post("/scenarios", scenario))
            assertEquals("created", saved["status"]!!.jsonPrimitive.content, saved.toString())
            val id = saved["id"]!!.jsonPrimitive.content
            val run = obj(post("/scenarios/run", """{"id":"$id"}"""))
            assertFalse(run["passed"]!!.jsonPrimitive.boolean, "the order carries 38=1000, not 9999: this must fail")

            // The run's scope comes out on the report: what `${id0}` held, and which step wrote it.
            val runVars = run["variables"]!!.jsonArray.map { it.jsonObject }
            assertEquals("id0", runVars.single()["name"]!!.jsonPrimitive.content)
            assertEquals("LOOP-1", runVars.single()["value"]!!.jsonPrimitive.content)

            val open = obj(post("/scenarios/reconcile", "{}"))
            assertEquals("open", open["status"]!!.jsonPrimitive.content, open.toString())
            assertEquals(2, open["step"]!!.jsonPrimitive.int, "the Expect is step 2, and it is the one that failed")
            assertEquals(
                "LOOP-1",
                open["variables"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content,
                "the reconcile response says what the agent's \${id0} held — the strip's data, over the wire",
            )

            // ...and what opened is the real thing: the diff WINDOW, on that step, judging the message that
            // actually arrived. `openReconcile` runs on the EDT, so let it land.
            assertTrue(
                awaitCondition(5_000) { viewModel.openDiffWindows.value.isNotEmpty() },
                "the control surface opened the one surface that can repair the assertion — in its own window",
            )
            val window = viewModel.openDiffWindows.value.single()
            assertEquals(open["stepId"]!!.jsonPrimitive.content, window.stepId)
            val session = window.session ?: error("the diff must be bound to the failing message, not to a prompt")
            assertTrue(session.model.verdict.needsAttention, "the surface agrees with the engine that this failed")
            assertEquals(38, session.model.lines.first { !it.row.passed }.row.tag, "and it is the row that failed")
            assertEquals(
                "LOOP-1",
                session.reference.variables.single().value,
                "the THIS_RUN reference carries the run's scope — the strip and any reference row read it here",
            )
        } finally {
            fixServer.stop()
        }
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

    /**
     * `fixtool_panel "scenarios"` used to open a **window**. It opens the docked rail now, and the endpoint is
     * unchanged — that is the whole contract: every agent and script driving this tool over MCP keeps working,
     * and simply gets a pane where it used to get a window (which the `/screenshot` of the main window could
     * never see into anyway).
     */
    @Test
    fun `panel scenarios toggles the rail, not a window`() {
        assertFalse(viewModel.showScenariosRail.value)
        assertEquals("ok", status(post("/panel", """{"panel":"scenarios","show":true}""")))
        assertTrue(viewModel.showScenariosRail.value)
        post("/panel", """{"panel":"scenarios","show":false}""")
        assertFalse(viewModel.showScenariosRail.value)
    }

    @Test
    fun `select with no sessions returns an error`() {
        assertEquals("error", status(post("/select", """{"session":"0"}""")))
    }

    @Test
    fun `detail search sets the detail panel query and mode on the view model`() {
        val resp = post("/detail", """{"query":"PartyRole","mode":"identity","show":true}""")
        assertEquals("ok", status(resp))
        assertEquals("PartyRole", obj(resp)["query"]!!.jsonPrimitive.content)
        assertEquals("identity", obj(resp)["mode"]!!.jsonPrimitive.content)
        assertEquals("PartyRole", viewModel.detailSearchQuery.value)
        assertEquals(MatchContextMode.IDENTITY, viewModel.detailMatchContextMode.value)
        assertTrue(viewModel.showDetailPanel.value, "show:true should reveal the detail panel")

        // mode-only leaves the query unchanged
        assertEquals("ok", status(post("/detail", """{"mode":"full"}""")))
        assertEquals("PartyRole", viewModel.detailSearchQuery.value)
        assertEquals(MatchContextMode.FULL, viewModel.detailMatchContextMode.value)

        // query-only leaves the mode unchanged
        assertEquals("ok", status(post("/detail", """{"query":"448"}""")))
        assertEquals("448", viewModel.detailSearchQuery.value)
        assertEquals(MatchContextMode.FULL, viewModel.detailMatchContextMode.value)
    }

    @Test
    fun `detail search rejects an unknown mode and an empty request`() {
        assertEquals("error", status(post("/detail", """{"mode":"sideways"}""")))
        assertEquals("error", status(post("/detail", """{}""")))
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

    // -------------------------------------------------------- syntax reference

    @Test
    fun `syntax reference is served over HTTP and MCP`() {
        // It ships as a bundled resource, so a packaging slip would otherwise surface as an agent
        // quietly being told the grammar is "unavailable".
        val http = get("/syntax")
        assertEquals(200, http.statusCode())
        assertTrue(http.headers().firstValue("content-type").orElse("").startsWith("text/markdown"))

        val mcp = mcpCall("fixtool_syntax", "{}")
        assertEquals(http.body(), mcp, "both surfaces serve the same reference")
        // The things an agent came here to find: both grammars, and the resolve gotcha.
        listOf("\${uuid}", "\${clOrdId = uuid}", "\${out.D.11}", "reference", "occurrence", "resolve")
            .forEach { assertTrue(mcp.contains(it), "syntax reference should document '$it'") }
    }

    // -------------------------------------------------------- embedded MCP server

    @Test
    fun `mcp initialize and tools list expose all tools`() {
        val init =
            obj(post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""))
        val result = init["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))

        val tools =
            obj(post("/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))["result"]!!
                .jsonObject["tools"]!!
                .jsonArray
        assertEquals(44, tools.size)
        assertTrue(
            tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "fixtool_reconcile" },
            "the diff is reachable without a hand on the mouse, or an agent can never open the surface that repairs",
        )
        assertTrue(
            tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "fixtool_acceptor_orders" },
            "the venue's own memory is readable from outside, or slice A of #35 is a panel and nothing else",
        )
        assertTrue(
            tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "fixtool_capture_paste" },
            "and the paste box too, or W2 could never be driven without a hand",
        )
        assertTrue(
            tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "fixtool_diff" },
            "the plain diff viewer's door (G8) exists on this transport too, not only on HTTP and the Node server",
        )
        tools.forEach {
            val t = it.jsonObject
            assertTrue(t.containsKey("name") && t.containsKey("inputSchema"), "each tool needs a name and schema")
        }
    }

    /**
     * **Advertised means callable.** tools/list is this transport's contract, and a listed name that
     * tools/call answers "unknown tool" is registry rot — fixtool_capture_paste and fixtool_reconcile
     * shipped a release advertised-but-uncallable on exactly this transport while working over plain
     * HTTP and the Node server. Probed with inert arguments: any answer but "unknown tool" is the
     * handler speaking (a named validation error is fine — that IS the wiring), so this cannot be
     * satisfied by a count pin that never dials a number.
     */
    @Test
    fun `every advertised mcp tool dispatches — advertised means callable`() {
        val tools =
            obj(post("/mcp", """{"jsonrpc":"2.0","id":11,"method":"tools/list"}"""))["result"]!!
                .jsonObject["tools"]!!
                .jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(tools.isNotEmpty())
        // Inert per-tool arguments where {} would act rather than answer (demo {} means start).
        val inert = mapOf("fixtool_demo" to """{"action":"stop"}""")
        tools.forEach { name ->
            val text = mcpCall(name, inert[name] ?: "{}")
            assertFalse(
                text.startsWith("unknown tool"),
                "$name is advertised on /mcp but tools/call cannot reach it: $text",
            )
        }
    }

    @Test
    fun `mcp tools call dispatches to the same control logic`() {
        // no-arg tool
        assertTrue(mcpCall("fixtool_health", "{}").contains("\"status\":\"ok\""))
        // body-arg tool
        assertTrue(mcpCall("fixtool_validate", """{"raw":"8=FIX.4.4|35=ZZZ|"}""").contains("\"isValid\""))
        // object-arg tool round-trips through upsertProfile (adapter body)
        assertTrue(mcpCall("fixtool_save_profile", """{"name":"MCP-P","config":{"port":"1"}}""").contains("\"created\""))
        // query-arg tool reaches the handler (adapter query) -> session not found
        assertTrue(mcpCall("fixtool_get_messages", """{"session":"99"}""").contains("session not found"))
        // detail-search tool drives the detail-panel state through the same control logic
        assertTrue(mcpCall("fixtool_detail_search", """{"query":"452","mode":"full"}""").contains("\"mode\":\"full\""))
        // unknown tool -> isError
        val unknown =
            obj(post("/mcp", """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"nope","arguments":{}}}"""))["result"]!!
                .jsonObject
        assertEquals(true, unknown["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `mcp notification gets a 204 with no body`() {
        assertEquals(204, post("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())
    }

    // -------------------------------------------------------------- auth gate

    @Test
    fun `token is enforced when configured`() {
        val securePort = freePort()
        val secure = ControlServer(securePort, viewModel, windowProvider = { emptyList() }, token = "s3cret")
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

    // ------------------------------------------------- authoring a profile without losing it

    /**
     * **The regression this suite most needed and did not have.**
     *
     * A `/profiles` POST used to replace the config wholesale. Nothing could read a profile's SSL
     * settings, logon fields or existing rules — the list gave eight fields — so the only way to add
     * a rule was to post a config carrying that rule and nothing else, which silently deleted
     * everything the caller could not see and then answered `{"status":"updated"}`.
     */
    @Test
    fun `updating a profile keeps the settings the caller did not mention`() {
        val created =
            post(
                "/profiles",
                """{"name":"Venue","config":{"connectionType":"ACCEPTOR","senderCompID":"ME","targetCompID":"THEM",
                   "socketAcceptPort":"9100","useSSL":true,"keyStorePath":"/certs/venue.jks","heartBtInt":"45",
                   "logonFields":{"553":"user"}}}""",
            )
        val id = obj(created)["id"]!!.jsonPrimitive.content

        // The edit an agent actually makes: one key, nothing else known.
        val updated =
            post(
                "/profiles",
                """{"id":"$id","name":"Venue","config":{"acceptorResponseRules":[
                   {"whenMsgType":"D","steps":[{"template":"35=8|39=0|11=${'$'}{req.11}|"}]}]}}""",
            )
        assertEquals("updated", status(updated))
        assertEquals("merge", obj(updated)["mode"]!!.jsonPrimitive.content)

        val config = obj(get("/profiles?profile=$id"))["config"]!!.jsonObject
        assertTrue(config["useSSL"]!!.jsonPrimitive.boolean, "SSL must survive an edit that never mentioned it")
        assertEquals("/certs/venue.jks", config["keyStorePath"]!!.jsonPrimitive.content)
        assertEquals("45", config["heartBtInt"]!!.jsonPrimitive.content)
        assertEquals("9100", config["socketAcceptPort"]!!.jsonPrimitive.content)
        assertEquals("user", config["logonFields"]!!.jsonObject["553"]!!.jsonPrimitive.content)
        assertEquals(1, config["acceptorResponseRules"]!!.jsonArray.size, "and the edit itself must have landed")
    }

    @Test
    fun `an explicitly empty value still clears, and replace still replaces`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Venue","config":{"connectionType":"ACCEPTOR","heartBtInt":"45","acceptorResponseRules":[
                       {"whenMsgType":"D","steps":[{"template":"35=8|"}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        // Present in the JSON, therefore applied: merging must not make clearing inexpressible.
        post("/profiles", """{"id":"$id","name":"Venue","config":{"acceptorResponseRules":[]}}""")
        assertEquals(0, obj(get("/profiles?profile=$id"))["config"]!!.jsonObject["acceptorResponseRules"]!!.jsonArray.size)

        val replaced = post("/profiles", """{"id":"$id","name":"Venue","config":{"connectionType":"ACCEPTOR"},"replace":true}""")
        assertEquals("replace", obj(replaced)["mode"]!!.jsonPrimitive.content)
        assertEquals(
            "30",
            obj(get("/profiles?profile=$id"))["config"]!!.jsonObject["heartBtInt"]!!.jsonPrimitive.content,
            "replace:true must still mean 'the profile is exactly what I sent' — heartBtInt back to its default",
        )
    }

    /**
     * A read that hands out passwords puts them in the caller's transcript for good; a read that
     * omits them cannot be posted back. The sentinel is both: shown by the read, ignored by the write.
     */
    @Test
    fun `secrets are redacted on read and unharmed by a round trip`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Venue","config":{"keyStorePassword":"hunter2","password":"s3cret","heartBtInt":"45"}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        val detail = obj(get("/profiles?profile=$id"))
        val config = detail["config"]!!.jsonObject
        assertEquals("[REDACTED]", config["keyStorePassword"]!!.jsonPrimitive.content)
        assertEquals("[REDACTED]", config["password"]!!.jsonPrimitive.content)
        assertTrue(detail["redacted"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("keyStorePassword"))

        // Post the whole config back, marker and all — the naive read-modify-write.
        post("/profiles", """{"id":"$id","name":"Venue","config":$config}""")
        assertEquals(
            "hunter2",
            viewModel.connectionProfiles.first { it.id == id }.config.keyStorePassword,
            "a round trip must leave the password alone, never set it to the literal marker",
        )
    }

    @Test
    fun `saving a rule that cannot fire says so instead of reporting plain success`() {
        val saved =
            post(
                "/profiles",
                """{"name":"Venue","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                   {"whenMsgType":"D"}]}}""",
            )
        assertEquals("created", status(saved))
        val warnings = obj(saved)["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(warnings.any { it.contains("nothing to reply with") }, "got: $warnings")
    }

    // ------------------------------------------------------- editing one rule at a time

    @Test
    fun `a rule can be appended, replaced, toggled and deleted without touching the profile`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Venue","config":{"connectionType":"ACCEPTOR","useSSL":true}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        val appended =
            post("/acceptor/rules", """{"profile":"$id","rule":{"whenMsgType":"D","steps":[{"template":"35=8|39=0|"}]}}""")
        assertEquals("appended", status(appended))
        assertEquals(0, obj(appended)["index"]!!.jsonPrimitive.int)

        assertEquals(
            "appended",
            status(post("/acceptor/rules", """{"profile":"$id","rule":{"whenMsgType":"F","steps":[{"template":"35=9|"}]}}""")),
        )

        val replaced =
            post("/acceptor/rules", """{"profile":"$id","index":0,"rule":{"whenMsgType":"D","steps":[{"template":"35=8|39=2|"}]}}""")
        assertEquals("replaced", status(replaced))

        val disabled = post("/acceptor/rules", """{"profile":"$id","index":1,"enabled":false}""")
        assertEquals("disabled", status(disabled))

        val rules = obj(get("/acceptor/rules?profile=$id"))["rules"]!!.jsonArray
        assertEquals(2, rules.size)
        assertEquals(0, rules[0].jsonObject["index"]!!.jsonPrimitive.int, "the index is the rule's identity, so it must be reported")
        assertEquals("35=8|39=2|", rules[0].jsonObject["sequence"]!!.jsonArray[0].jsonObject["template"]!!.jsonPrimitive.content)
        assertFalse(rules[1].jsonObject["enabled"]!!.jsonPrimitive.boolean)

        val deleted = delete("/acceptor/rules", """{"profile":"$id","index":0}""")
        assertEquals("deleted", status(deleted))
        assertEquals(1, obj(deleted)["ruleCount"]!!.jsonPrimitive.int)

        assertTrue(
            viewModel.connectionProfiles.first { it.id == id }.config.useSSL,
            "editing a rule must not disturb the rest of the profile",
        )
    }

    // ------------------------------------------------------- presets

    @Test
    fun `a preset is inserted by name, and lands where it can fire`() {
        val id = obj(post("/profiles", """{"name":"Venue","config":{"connectionType":"ACCEPTOR"}}"""))["id"]!!.jsonPrimitive.content

        val listed = Json.parseToJsonElement(get("/acceptor/presets").body()).jsonArray
        assertTrue(listed.any { it.jsonObject["id"]!!.jsonPrimitive.content == "starter-venue" })
        assertTrue(
            listed.all { it.jsonObject["rules"]!!.jsonArray.isNotEmpty() },
            "a preset that inserts nothing is not a preset",
        )
        // A preset that reads the venue's memory has to say so here, since this listing is the whole of
        // how one is discovered — and it is the only thing that distinguishes it from the stateless
        // preset sitting beside it under a nearly identical name.
        val conditioned =
            listed.first { it.jsonObject["id"]!!.jsonPrimitive.content == "cancel-rejected-unknown" }
        assertEquals(
            "unknown",
            conditioned.jsonObject["rules"]!!
                .jsonArray[0]
                .jsonObject["whenOrder"]!!
                .jsonPrimitive.content,
        )

        val venue = post("/acceptor/rules", """{"profile":"$id","preset":"starter-venue"}""")
        assertEquals("added", status(venue))
        assertEquals(7, obj(venue)["rulesAdded"]!!.jsonPrimitive.int)
        assertEquals(7, viewModel.connectionProfiles.first { it.id == id }.config.acceptorResponseRules.size)

        // The starter venue ends with an unconditioned 35=D rule in it, so a conditioned D preset
        // appended after it could never fire. The response has to say where it went instead.
        val reject = post("/acceptor/rules", """{"profile":"$id","preset":"order-reject-size"}""")
        assertEquals("added", status(reject))
        val placedAbove = obj(reject)["placedAbove"]!!.jsonPrimitive.int
        assertEquals(obj(reject)["index"]!!.jsonPrimitive.int, placedAbove, "it went above the rule it names")
        assertTrue(obj(reject)["placedBecause"]!!.jsonPrimitive.content.contains("35=D"))
        assertNull(obj(reject)["shadowedBy"], "the placement exists precisely so this is not shadowed")
    }

    @Test
    fun `an unknown preset is refused by name, and a preset cannot be given an index`() {
        val id = obj(post("/profiles", """{"name":"Venue","config":{"connectionType":"ACCEPTOR"}}"""))["id"]!!.jsonPrimitive.content

        val unknown = post("/acceptor/rules", """{"profile":"$id","preset":"full-fil"}""")
        assertEquals("error", status(unknown))
        assertTrue(
            obj(unknown)["error"]!!.jsonPrimitive.content.contains("ack-then-fill"),
            "a refusal that does not say what the known ids are leaves the caller guessing",
        )

        assertEquals(
            "error",
            status(post("/acceptor/rules", """{"profile":"$id","preset":"order-ack","index":0}""")),
            "a preset chooses its own position; honouring one of the two silently would be worse",
        )
        assertEquals(0, viewModel.connectionProfiles.first { it.id == id }.config.acceptorResponseRules.size)
    }

    @Test
    fun `a rule an earlier one already answers is reported as unreachable when the list is read`() {
        val id = obj(post("/profiles", """{"name":"Venue","config":{"connectionType":"ACCEPTOR"}}"""))["id"]!!.jsonPrimitive.content

        post("/acceptor/rules", """{"profile":"$id","rule":{"whenMsgType":"D","steps":[{"template":"35=8|39=0|"}]}}""")
        val second =
            post("/acceptor/rules", """{"profile":"$id","rule":{"whenMsgType":"D","steps":[{"template":"35=8|39=8|"}]}}""")

        assertEquals(0, obj(second)["shadowedBy"]!!.jsonPrimitive.int, "said when the rule is written, not only when read")

        val rules = obj(get("/acceptor/rules?profile=$id"))["rules"]!!.jsonArray
        assertNull(rules[0].jsonObject["shadowedBy"], "the rule that answers everything is not itself shadowed")
        assertEquals(0, rules[1].jsonObject["shadowedBy"]!!.jsonPrimitive.int)
    }

    /**
     * The step-in-the-editor hand-off, which is otherwise reachable only with a mouse — so without
     * this it could neither be driven nor checked without a hand on one.
     */
    @Test
    fun `a reply step can be opened in the editor, applied, and left unsaved`() {
        val id = obj(post("/profiles", """{"name":"Venue","config":{"connectionType":"ACCEPTOR"}}"""))["id"]!!.jsonPrimitive.content
        post("/acceptor/rules", """{"profile":"$id","preset":"ack-then-fill"}""")
        val original =
            viewModel.connectionProfiles.first { it.id == id }.config.acceptorResponseRules.first().sequence()[1].template

        val opened = post("/panel", """{"panel":"editor","profile":"$id","rule":0,"step":1}""")
        assertEquals("editing", status(opened))
        assertEquals(original, obj(opened)["template"]!!.jsonPrimitive.content, "the editor gets the step as written")

        val applied = post("/panel", """{"panel":"editor","action":"apply"}""")
        assertEquals("applied", status(applied))
        assertEquals(
            original,
            obj(applied)["template"]!!.jsonPrimitive.content,
            "a step that was not edited must come back exactly as it went in",
        )
        assertFalse(
            obj(applied)["saved"]!!.jsonPrimitive.boolean,
            "applying stages the step; a venue that has not changed must not read as one that has",
        )
        assertEquals(
            original,
            viewModel.connectionProfiles.first { it.id == id }.config.acceptorResponseRules.first().sequence()[1].template,
            "and the saved profile is the proof of it",
        )
    }

    @Test
    fun `the editor hand-off refuses what it cannot address, by name`() {
        val id = obj(post("/profiles", """{"name":"Venue","config":{"connectionType":"ACCEPTOR"}}"""))["id"]!!.jsonPrimitive.content
        post("/acceptor/rules", """{"profile":"$id","preset":"order-ack"}""")

        assertEquals("error", status(post("/panel", """{"panel":"editor","profile":"nope","rule":0}""")))
        assertEquals("error", status(post("/panel", """{"panel":"editor","profile":"$id","rule":4}""")))
        val badStep = post("/panel", """{"panel":"editor","profile":"$id","rule":0,"step":3}""")
        assertEquals("error", status(badStep))
        assertTrue(obj(badStep)["error"]!!.jsonPrimitive.content.contains("1 step"), "the refusal says what is there")

        // Finishing something that was never started is a different mistake from finishing it wrongly.
        assertEquals("error", status(post("/panel", """{"panel":"editor","action":"apply"}""")))
        assertEquals("error", status(post("/panel", """{"panel":"editor","action":"cancel"}""")))

        post("/panel", """{"panel":"editor","profile":"$id","rule":0}""")
        assertEquals("error", status(post("/panel", """{"panel":"editor","action":"applyy"}""")))
        assertEquals("cancelled", status(post("/panel", """{"panel":"editor","action":"cancel"}""")))
    }

    @Test
    fun `an out of range rule index is refused rather than silently appending`() {
        val id = obj(post("/profiles", """{"name":"Venue","config":{"connectionType":"ACCEPTOR"}}"""))["id"]!!.jsonPrimitive.content

        val resp = post("/acceptor/rules", """{"profile":"$id","index":3,"rule":{"whenMsgType":"D","steps":[{"template":"35=8|"}]}}""")
        assertEquals("error", status(resp))
        assertEquals(0, viewModel.connectionProfiles.first { it.id == id }.config.acceptorResponseRules.size)
    }

    // ------------------------------------------------------------- the dry run

    /**
     * The whole point of `/acceptor/test`: every way a rule can fail to fire looks identical from
     * outside (nothing comes back), and each one has a different fix. So each is asserted to be
     * *distinguishable*, not merely detected.
     */
    @Test
    fun `the dry run says which rule wins and why the others did not`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Venue","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                       {"whenMsgType":"F","steps":[{"template":"35=9|"}]},
                       {"whenMsgType":"D","enabled":false,"steps":[{"template":"35=8|39=8|"}]},
                       {"whenMsgType":"D","conditions":[{"tag":44,"matcher":{"type":"exact","value":"1.25"}}],
                        "steps":[{"template":"35=8|39=1|"}]},
                       {"whenMsgType":"D","conditions":[{"tag":38,"matcher":{"type":"range","min":500}}],
                        "steps":[{"template":"35=8|39=0|11=${'$'}{req.11}|38=${'$'}{req.38}|"}]},
                       {"whenMsgType":"D","steps":[{"template":"35=8|39=4|"}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        val resp = post("/acceptor/test", """{"profile":"$id","raw":"35=D|11=ORD-1|55=EUR/USD|54=1|38=1000|"}""")
        val body = obj(resp)
        assertTrue(body["matched"]!!.jsonPrimitive.boolean)
        val rules = body["rules"]!!.jsonArray

        // 0: wrong MsgType — named, so a typo'd trigger is not mistaken for a failed condition.
        assertFalse(rules[0].jsonObject["matched"]!!.jsonPrimitive.boolean)
        assertTrue(rules[0].jsonObject["mismatch"]!!.jsonPrimitive.content.contains("MsgType is D"))

        // 1: switched off — kept and skipped, so the message falls through rather than stopping here.
        assertTrue(rules[1].jsonObject["skipped"]!!.jsonPrimitive.content.contains("disabled"))

        // 2: a condition on a tag the message does not carry — the commonest silent failure.
        val absent = rules[2].jsonObject["conditions"]!!.jsonArray.single().jsonObject
        assertEquals(44, absent["tag"]!!.jsonPrimitive.int)
        assertTrue(absent["absent"]!!.jsonPrimitive.boolean, "tag 44 is missing, which is not the same as blank")
        assertFalse(absent["satisfied"]!!.jsonPrimitive.boolean)

        // 3: the winner, with the value its condition read.
        assertTrue(rules[3].jsonObject["selected"]!!.jsonPrimitive.boolean)
        assertEquals("1000", rules[3].jsonObject["conditions"]!!.jsonArray.single().jsonObject["actual"]!!.jsonPrimitive.content)

        // 4: would have matched, but a rule above it won first.
        assertTrue(rules[4].jsonObject["matched"]!!.jsonPrimitive.boolean)
        assertFalse(rules[4].jsonObject["selected"]!!.jsonPrimitive.boolean)
        assertEquals(3, rules[4].jsonObject["shadowedBy"]!!.jsonPrimitive.int)

        // The reply, rendered as the wire would carry it.
        val response = body["response"]!!.jsonArray
        assertEquals(1, response.size)
        val rendered = response[0].jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(rendered.contains("11=ORD-1"), "\${req.11} must be substituted from the tested message: $rendered")
        assertTrue(rendered.contains("38=1000"), "got: $rendered")
    }

    @Test
    fun `the dry run reports a sequence's offsets and changes nothing`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Venue","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                       {"whenMsgType":"D","steps":[
                          {"template":"35=8|150=0|39=0|"},
                          {"template":"35=8|150=F|39=1|","delayMillis":400},
                          {"template":"35=8|150=F|39=2|","delayMillis":400}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        val response = obj(post("/acceptor/test", """{"profile":"$id","raw":"35=D|11=ORD-1|"}"""))["response"]!!.jsonArray
        assertEquals(
            listOf(0, 400, 800),
            response.map { it.jsonObject["offsetMillis"]!!.jsonPrimitive.int },
            "a step's delay is measured from the step before it; the caller must not have to accumulate",
        )
        assertEquals(0, viewModel.sessions.size, "a dry run must not connect anything")
    }

    @Test
    fun `the dry run says when the profile is not an acceptor at all`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Client","config":{"connectionType":"INITIATOR","acceptorResponseRules":[
                       {"whenMsgType":"D","steps":[{"template":"35=8|"}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        val body = obj(post("/acceptor/test", """{"profile":"$id","raw":"35=D|11=ORD-1|"}"""))
        assertTrue(body["matched"]!!.jsonPrimitive.boolean, "the rule itself does match")
        assertTrue(
            body["inactive"]!!.jsonPrimitive.content.contains("ACCEPTOR"),
            "but it would never run, and that is invisible from the rule",
        )
        assertTrue(obj(get("/acceptor/rules?profile=$id"))["inactive"] != null)
    }

    // ------------------------------------------------------------- a dry run of a stateful trigger

    /** The first message of a dry run's rendered reply, as the wire would carry it. */
    private fun replyOf(body: JsonObject): String =
        body["response"]!!
            .jsonArray[0]
            .jsonObject["message"]!!
            .jsonPrimitive
            .content

    /** The venue whose cancel rules ask what it is holding — the pair from decision 1, over HTTP. */
    private fun statefulVenue(): String =
        obj(
            post(
                "/profiles",
                """{"name":"Stateful","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                   {"whenMsgType":"F","whenOrder":"unknown","steps":[{"template":"35=9|102=1|58=Unknown order|"}]},
                   {"whenMsgType":"F","whenOrder":"working","steps":[{"template":"35=8|150=4|39=4|"}]}]}}""",
            ),
        )["id"]!!.jsonPrimitive.content

    /**
     * **A dry run of a stateful trigger has to name the state it assumed, and take one.**
     *
     * Without the argument the question "what would this rule do" has no single answer any more, and
     * without the report the caller cannot tell which of the answers they were given. Both halves are
     * asserted, because either alone is a tool that quietly picks for you.
     */
    @Test
    fun `the dry run takes an order state and answers differently for each one`() {
        val id = statefulVenue()
        val cancel = "35=F|11=CXL-4|41=ORD-1|55=EUR/USD|54=1|"

        val assumedUnknown = obj(post("/acceptor/test", """{"profile":"$id","raw":"$cancel","orderState":"unknown"}"""))
        assertEquals(0, assumedUnknown["rules"]!!.jsonArray.indexOfFirst { it.jsonObject["selected"]!!.jsonPrimitive.boolean })
        assertTrue(
            replyOf(assumedUnknown).contains("35=9"),
            "a cancel for an order nobody sent comes back as a cancel reject",
        )

        val assumedWorking = obj(post("/acceptor/test", """{"profile":"$id","raw":"$cancel","orderState":"working"}"""))
        assertEquals(1, assumedWorking["rules"]!!.jsonArray.indexOfFirst { it.jsonObject["selected"]!!.jsonPrimitive.boolean })
        assertTrue(
            replyOf(assumedWorking).contains("150=4"),
            "and one for a live order is canceled — same rule list, same message, different venue state",
        )

        // "what would this rule do if the order were already filled", answered without arranging for
        // an order to be already filled — which is the whole reason the argument exists.
        val assumedDone = obj(post("/acceptor/test", """{"profile":"$id","raw":"$cancel","orderState":"done"}"""))
        assertFalse(assumedDone["matched"]!!.jsonPrimitive.boolean, "neither rule claims a finished order")
    }

    @Test
    fun `the dry run names the state it assumed, including the one it assumed for you`() {
        val id = statefulVenue()
        val cancel = "35=F|11=CXL-4|41=ORD-1|"

        val defaulted = obj(post("/acceptor/test", """{"profile":"$id","raw":"$cancel"}"""))["assumedOrderState"]!!.jsonObject
        assertEquals("unknown", defaulted["state"]!!.jsonPrimitive.content)
        assertFalse(defaulted["given"]!!.jsonPrimitive.boolean)
        assertEquals("ORD-1", defaulted["order"]!!.jsonPrimitive.content, "41 is the order the cancel is about")
        assertTrue(defaulted["note"]!!.jsonPrimitive.content.contains("orderState"), "and it says how to ask for another")

        val asked =
            obj(post("/acceptor/test", """{"profile":"$id","raw":"$cancel","orderState":"working"}"""))["assumedOrderState"]!!
                .jsonObject
        assertEquals("working", asked["state"]!!.jsonPrimitive.content)
        assertTrue(asked["given"]!!.jsonPrimitive.boolean)
        assertNull(asked["note"], "a caller who named the state does not need telling that one was assumed")
    }

    @Test
    fun `each conditioned rule reports what it asked the book and what it read`() {
        val id = statefulVenue()

        val rules =
            obj(post("/acceptor/test", """{"profile":"$id","raw":"35=F|11=CXL-4|41=ORD-1|","orderState":"working"}"""))["rules"]!!
                .jsonArray

        val rejected = rules[0].jsonObject["whenOrder"]!!.jsonObject
        assertEquals("unknown", rejected["constraint"]!!.jsonPrimitive.content)
        assertEquals("working", rejected["actual"]!!.jsonPrimitive.content, "what the book said, not what the rule wanted")
        assertEquals("ORD-1", rejected["order"]!!.jsonPrimitive.content)
        assertFalse(rejected["satisfied"]!!.jsonPrimitive.boolean)

        val accepted = rules[1].jsonObject["whenOrder"]!!.jsonObject
        assertTrue(accepted["satisfied"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a state outside the vocabulary is refused by name rather than guessed at`() {
        val id = statefulVenue()

        val error = obj(post("/acceptor/test", """{"profile":"$id","raw":"35=F|41=ORD-1|","orderState":"filled"}"""))

        val message = error["error"]!!.jsonPrimitive.content
        assertTrue(message.contains("filled"), "the refusal names what was sent: $message")
        assertTrue(message.contains("working"), "and what it could have been: $message")
    }

    /**
     * The other half of the dry run's assumption. `orderState` says what a *trigger* reads; `order`
     * says what a *reply* substitutes — and a template reading `${order.…}` cannot be rendered
     * without one, so the dry run says which step it could not build rather than showing a message
     * the wire would never carry.
     */
    @Test
    fun `a dry run of a reply that reads the book renders it when given an order, and says so when not`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Reader","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                       {"whenMsgType":"H","whenOrder":"working","steps":[{"template":
                        "35=8|150=I|37=${'$'}{order.orderId}|14=${'$'}{order.cumQty}|151=${'$'}{order.leavesQty}|"}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content
        val request = """{"profile":"$id","raw":"35=H|11=ORD-1|","orderState":"working""""

        val unrendered = obj(post("/acceptor/test", "$request}"))
        val step = unrendered["response"]!!.jsonArray[0].jsonObject
        assertNull(step["message"], "there was no order to read, so there is no message to show")
        assertTrue(step["unrendered"]!!.jsonPrimitive.content.contains("no order here"), "and it says why")
        assertTrue(step["template"]!!.jsonPrimitive.content.contains("\${order."), "with the template that needed one")
        assertTrue(unrendered["orderNote"]!!.jsonPrimitive.content.contains("order:"), "and how to supply it")

        val rendered =
            obj(post("/acceptor/test", """$request,"order":{"orderId":"EX-7","cumQty":"400","leavesQty":"600"}}"""))
        val message = replyOf(rendered)
        assertTrue(message.contains("37=EX-7"), "got: $message")
        assertTrue(message.contains("14=400") && message.contains("151=600"), "got: $message")
        assertNull(rendered["orderNote"], "a caller who supplied an order does not need telling to")
    }

    @Test
    fun `the rules endpoint reports the book constraint, and only when there is one`() {
        val id = statefulVenue()

        val rules = obj(get("/acceptor/rules?profile=$id"))["rules"]!!.jsonArray

        assertEquals("unknown", rules[0].jsonObject["whenOrder"]!!.jsonPrimitive.content)
        assertEquals("working", rules[1].jsonObject["whenOrder"]!!.jsonPrimitive.content)

        val stateless =
            obj(
                post(
                    "/profiles",
                    """{"name":"Plain","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                       {"whenMsgType":"F","steps":[{"template":"35=9|"}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content
        val plain = obj(get("/acceptor/rules?profile=$stateless"))["rules"]!!.jsonArray
        assertNull(
            plain[0].jsonObject["whenOrder"],
            "an absent key is how 'this rule does not read the book' is said; 'unknown' would be a rule that asks",
        )
    }

    /** A rule written over the wire has to arrive as the rule the engine will run. */
    @Test
    fun `a rule posted with a book constraint keeps it, and is placed where it can fire`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Written","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                       {"whenMsgType":"F","steps":[{"template":"35=8|150=4|"}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        val written =
            obj(
                post(
                    "/acceptor/rules",
                    """{"profile":"$id","rule":{"whenMsgType":"F","whenOrder":"unknown",
                       "steps":[{"template":"35=9|102=1|"}]}}""",
                ),
            )
        assertNull(written["validationError"], "a well-formed conditioned rule has nothing wrong with it")

        val rules = obj(get("/acceptor/rules?profile=$id"))["rules"]!!.jsonArray
        assertEquals(
            "unknown",
            rules[written["index"]!!.jsonPrimitive.int].jsonObject["whenOrder"]!!.jsonPrimitive.content,
        )
    }

    // ------------------------------------------------------------- simulated latency

    @Test
    fun `latency is reported beside the rules it delays`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Venue","config":{"connectionType":"ACCEPTOR","acceptorLatency":{
                       "mode":"RANDOM_RANGE","minMillis":20,"maxMillis":80,"spikeProbability":0.05,
                       "spikeMinMillis":2000,"spikeMaxMillis":5000}}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        val latency = obj(get("/acceptor/rules?profile=$id"))["latency"]!!.jsonObject
        assertTrue(latency["active"]!!.jsonPrimitive.boolean)
        assertEquals("RANDOM_RANGE", latency["mode"]!!.jsonPrimitive.content)
        assertEquals(20, latency["addedMillis"]!!.jsonObject["min"]!!.jsonPrimitive.int)
        // A spike replaces the ordinary sample rather than adding to it, so it widens the range.
        assertEquals(5000, latency["addedMillis"]!!.jsonObject["max"]!!.jsonPrimitive.int)
        assertTrue(latency["spike"]!!.jsonPrimitive.content.contains("5%"))
    }

    @Test
    fun `a latency setting that would do nothing is reported as such`() {
        val saved =
            post(
                "/profiles",
                """{"name":"Venue","config":{"connectionType":"ACCEPTOR","acceptorLatency":{
                   "mode":"RANDOM_RANGE","minMillis":500,"maxMillis":100}}}""",
            )
        val warnings = obj(saved)["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(warnings.any { it.contains("acceptorLatency") }, "got: $warnings")
    }

    // ------------------------------------------- the edges of the authoring surface

    /**
     * A message nothing answers. Every other dry-run test here asserts a rule *won*, which leaves the
     * commonest real outcome — "I sent it a message and got nothing" — unpinned. The absence of
     * `response` is the assertion that matters: an empty array would read as "it replies with nothing",
     * which is a different and much more alarming claim than "no rule applies".
     */
    @Test
    fun `the dry run reports no match, and offers no reply to read`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Venue","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                       {"whenMsgType":"D","conditions":[{"tag":38,"matcher":{"type":"range","min":500}}],
                        "steps":[{"template":"35=8|39=0|"}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        val body = obj(post("/acceptor/test", """{"profile":"$id","raw":"35=D|11=SMALL|38=10|"}"""))
        assertFalse(body["matched"]!!.jsonPrimitive.boolean)
        assertTrue(body["response"] == null, "no rule fired, so there is no reply to render: $body")
        assertFalse(body["rules"]!!.jsonArray.single().jsonObject["selected"]!!.jsonPrimitive.boolean)
        assertEquals("10", body["rules"]!!.jsonArray.single().jsonObject["conditions"]!!.jsonArray.single().jsonObject["actual"]!!.jsonPrimitive.content)
    }

    /**
     * The dry run is handed raw text by whoever is authoring, so it is handed typos. It must answer
     * them — a 500 from a debugging tool costs the author the one thing they came for, which is to be
     * told what is wrong.
     */
    @Test
    fun `the dry run answers bad input instead of throwing`() {
        val id =
            obj(post("/profiles", """{"name":"Venue","config":{"connectionType":"ACCEPTOR"}}"""))["id"]!!.jsonPrimitive.content

        assertEquals("error", status(post("/acceptor/test", """{"profile":"$id","raw":""}""")), "an empty message is refused by name")
        assertEquals("error", status(post("/acceptor/test", """{"profile":"$id"}""")), "a missing message is refused by name")
        assertEquals("error", status(post("/acceptor/test", """{"raw":"35=D|"}""")), "a missing profile is refused by name")
        assertEquals("error", status(post("/acceptor/test", """{"profile":"nope","raw":"35=D|"}""")))

        // Not FIX at all: there are no tags to read, so nothing can match — but it is an answer, with a
        // 200 and a readable body, not a stack trace.
        val garbage = post("/acceptor/test", """{"profile":"$id","raw":"this is not a fix message"}""")
        assertEquals(200, garbage.statusCode())
        assertFalse(obj(garbage)["matched"]!!.jsonPrimitive.boolean)
    }

    /**
     * A refused edit must leave the rule list exactly as it was. An out-of-range *delete* that removed
     * the wrong rule, or a toggle that silently hit index 0, would be worse than the error it replaced.
     */
    @Test
    fun `an out of range index is refused on delete and on toggle, and changes nothing`() {
        val id =
            obj(
                post(
                    "/profiles",
                    """{"name":"Venue","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                       {"whenMsgType":"D","steps":[{"template":"35=8|39=0|"}]}]}}""",
                ),
            )["id"]!!.jsonPrimitive.content

        assertEquals("error", status(delete("/acceptor/rules", """{"profile":"$id","index":5}""")))
        assertEquals("error", status(post("/acceptor/rules", """{"profile":"$id","index":5,"enabled":false}""")))
        assertEquals("error", status(delete("/acceptor/rules", """{"profile":"$id","index":-1}""")))

        val rules = obj(get("/acceptor/rules?profile=$id"))["rules"]!!.jsonArray
        assertEquals(1, rules.size, "the rule list must be untouched by a refusal")
        assertTrue(rules.single().jsonObject["enabled"]!!.jsonPrimitive.boolean, "and the surviving rule still enabled")
    }

    /**
     * `appliedToLiveSessions` is **absent**, not zero, when a save had nothing running to reach. Same
     * convention as the acceptor block and `discarded`: a field that reads 0 on every quiet save
     * teaches a reader to ignore it, and this one exists precisely to be noticed.
     */
    @Test
    fun `appliedToLiveSessions is absent when there is nothing live to apply to`() {
        val acceptor =
            post(
                "/profiles",
                """{"name":"Venue","config":{"connectionType":"ACCEPTOR","acceptorResponseRules":[
                   {"whenMsgType":"D","steps":[{"template":"35=8|39=0|"}]}]}}""",
            )
        assertEquals("created", status(acceptor))
        assertTrue(obj(acceptor)["appliedToLiveSessions"] == null, "nothing is connected: ${obj(acceptor)}")

        // An initiator carries rules inertly, so the question never arises for it at all.
        val initiator =
            post(
                "/profiles",
                """{"name":"Client","config":{"connectionType":"INITIATOR","acceptorResponseRules":[
                   {"whenMsgType":"D","steps":[{"template":"35=8|39=0|"}]}]}}""",
            )
        assertTrue(obj(initiator)["appliedToLiveSessions"] == null, "an initiator has no live acceptor to apply to")
    }

    /**
     * **Both dispatch-level branches, over the transport that has them.** `fixtool_profiles` and
     * `fixtool_acceptor_rule` each choose between two handlers inside the MCP dispatch map, and the
     * suite's "advertised means callable" probe calls every tool with `{}` — which reaches the
     * missing-argument error and neither branch. So the branches were wired and never dialled.
     */
    @Test
    fun `the acceptor tools reach both of their branches over mcp`() {
        val created = mcpCall("fixtool_save_profile", """{"name":"MCP-ACC","config":{"connectionType":"ACCEPTOR"}}""")
        val id = Json.parseToJsonElement(created).jsonObject["id"]!!.jsonPrimitive.content

        // fixtool_profiles: the list branch is a summary, the profile branch is the whole config.
        assertFalse(mcpCall("fixtool_profiles", "{}").contains("\"config\""), "the list branch stays a summary")
        val detail = mcpCall("fixtool_profiles", """{"profile":"$id"}""")
        assertTrue(detail.contains("\"config\""), "the profile branch returns the whole config: $detail")
        assertTrue(detail.contains("acceptorLatency"), "including the fields only the detail view carries")

        // fixtool_acceptor_rule: the upsert branch, then the delete branch behind delete:true.
        val appended =
            mcpCall("fixtool_acceptor_rule", """{"profile":"$id","rule":{"whenMsgType":"D","steps":[{"template":"35=8|39=0|"}]}}""")
        assertTrue(appended.contains("\"appended\""), "got: $appended")
        val deleted = mcpCall("fixtool_acceptor_rule", """{"profile":"$id","index":0,"delete":true}""")
        assertTrue(deleted.contains("\"deleted\""), "delete:true must reach the delete handler, not the upsert: $deleted")
        assertEquals(0, obj(get("/acceptor/rules?profile=$id"))["rules"]!!.jsonArray.size)

        // fixtool_acceptor_test over the same transport, since only HTTP has exercised it.
        val dry = mcpCall("fixtool_acceptor_test", """{"profile":"$id","raw":"35=D|11=X|"}""")
        assertTrue(dry.contains("\"matched\""), "got: $dry")
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

    /**
     * **`/acceptor/orders` — the book, read from outside the app.**
     *
     * This endpoint is in the *first* slice of #35 rather than a later one, and this is what that
     * buys: the venue's memory can be asserted on by something that never touches a mouse. "Reply
     * With…" shipped without it and could only ever be checked by hand.
     *
     * The roll-up and the full book are separate shapes on purpose — a venue with four clients
     * holding a thousand orders each is not a useful default response — so both are asked for here.
     */
    @Test
    fun `the order book is readable over the control surface, with the trail that proves it`() {
        val port = freePort()
        val ackThenFill =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps =
                    listOf(
                        ResponseStep(template = "35=8|150=0|39=0|37=EX-BOOK|17=\${uuid}|11=\${req.11}|14=0|151=100|"),
                        ResponseStep(
                            template = "35=8|150=F|39=2|37=EX-BOOK|17=\${uuid}|11=\${req.11}|14=100|151=0|32=100|",
                            delayMillis = 100,
                        ),
                    ),
            )
        val acceptor =
            FixConnectionProfile(
                name = "BOOKACC",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "BACC$runId",
                        targetCompID = "BCLI$runId",
                        port = port.toString(),
                        socketAcceptPort = port.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "bookaccstore").absolutePath,
                        fileLogPath = File(testDir, "bookacclog").absolutePath,
                        acceptorResponseRules = listOf(ackThenFill),
                    ),
            )
        val client =
            FixConnectionProfile(
                name = "BOOKCLI",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "BCLI$runId",
                        targetCompID = "BACC$runId",
                        host = "localhost",
                        port = port.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "bookclistore").absolutePath,
                        fileLogPath = File(testDir, "bookclilog").absolutePath,
                    ),
            )
        listOf(acceptor, client).forEach {
            viewModel.saveConnectionProfile(it)
            viewModel.connectProfile(it.id, it)
        }
        assertTrue(
            awaitCondition(15_000) {
                viewModel.sessions.any { it.title == "BOOKCLI" && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "client should log on to the acceptor",
        )

        post("/send", """{"session":"BOOKCLI","raw":"35=D|11=ORD-BOOK|55=EUR/USD|54=1|38=100|40=1|"}""")
        assertEquals(
            "matched",
            status(post("/wait", """{"session":"BOOKCLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")),
        )
        // The fill is 100ms behind the ack, and the book is only interesting once both have landed.
        assertTrue(
            awaitCondition(8_000) {
                val orders = obj(get("/acceptor/orders?session=BOOKACC"))["orders"]?.jsonArray
                orders
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("state")
                    ?.jsonPrimitive
                    ?.content == "done"
            },
            "the venue's book should show the order filled; got " + get("/acceptor/orders?session=BOOKACC").body(),
        )

        val book = obj(get("/acceptor/orders?session=BOOKACC"))
        val order = book["orders"]!!.jsonArray.single().jsonObject
        assertEquals("ORD-BOOK", order["clOrdId"]!!.jsonPrimitive.content)
        assertEquals("EX-BOOK", order["orderId"]!!.jsonPrimitive.content, "the id the client was actually given")
        assertEquals("100", order["cumQty"]!!.jsonPrimitive.content)
        assertEquals("0", order["leavesQty"]!!.jsonPrimitive.content)
        assertEquals(0, book["working"]!!.jsonPrimitive.int)

        // The trail is what makes those numbers checkable rather than merely trustworthy.
        val trail = order["trail"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("order received", "ack", "fill 100"), trail.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf("received", "sent", "sent"), trail.map { it["direction"]!!.jsonPrimitive.content })
        assertEquals("0", trail[1]["cumQty"]!!.jsonPrimitive.content, "nothing had traded at the ack")
        assertEquals("100", trail[2]["cumQty"]!!.jsonPrimitive.content)

        // The health fields report whether or not anything is wrong — a book with nothing to say still
        // has to say it, or a reader cannot tell a clean book from one that is not reporting.
        assertEquals(0, book["unattributed"]!!.jsonPrimitive.int)
        assertEquals(0, book["evicted"]!!.jsonPrimitive.int)
        assertNull(book["clearedAt"], "nobody has cleared this book")

        // The roll-up: every venue, no orders.
        val rollup = obj(get("/acceptor/orders"))["books"]!!.jsonArray.map { it.jsonObject }
        val venueLine = rollup.single { it["session"]!!.jsonPrimitive.content == "BOOKACC" }
        assertEquals(1, venueLine["orders"]!!.jsonPrimitive.int)
        assertEquals(0, venueLine["working"]!!.jsonPrimitive.int)
        assertTrue(rollup.none { it["session"]!!.jsonPrimitive.content == "BOOKCLI" }, "an initiator holds no book")

        // Cleared says so, because an empty book otherwise reads as one nothing ever happened to.
        assertEquals("cleared", status(post("/acceptor/orders", """{"session":"BOOKACC"}""")))
        val afterClear = obj(get("/acceptor/orders?session=BOOKACC"))
        assertTrue(afterClear["orders"]!!.jsonArray.isEmpty())
        assertEquals("the control surface", afterClear["clearedBy"]!!.jsonPrimitive.content)
    }

    /**
     * The order lifecycle a real venue sends — ack, partial fill, fill — over a real socket, from one
     * inbound order.
     *
     * The elapsed-time assertion is the point of doing this end to end. Everything above it would pass
     * against a rule that fired all three replies at once; only the clock catches an acceptor that
     * ignores the delays it was given, and only the clock shows the reply is no longer racing the
     * client's own `send()` back down the wire.
     */
    @Test
    fun `acceptor plays a sequence in order, taking the time the author asked for`() {
        val port = freePort()
        val ackThenFill =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps =
                    listOf(
                        ResponseStep(template = "35=8|150=0|39=0|37=\${uuid}|17=\${uuid}|11=\${req.11}|14=0|"),
                        ResponseStep(
                            template = "35=8|150=F|39=1|37=\${uuid}|17=\${uuid}|11=\${req.11}|14=50|",
                            delayMillis = 400,
                        ),
                        ResponseStep(
                            template = "35=8|150=F|39=2|37=\${uuid}|17=\${uuid}|11=\${req.11}|14=100|",
                            delayMillis = 400,
                        ),
                    ),
            )
        val acceptor =
            FixConnectionProfile(
                name = "ACC",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "SEQA$runId",
                        targetCompID = "SEQC$runId",
                        port = port.toString(),
                        socketAcceptPort = port.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "seqaccstore").absolutePath,
                        fileLogPath = File(testDir, "seqacclog").absolutePath,
                        acceptorResponseRules = listOf(ackThenFill),
                    ),
            )
        val client =
            FixConnectionProfile(
                name = "CLI",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "SEQC$runId",
                        targetCompID = "SEQA$runId",
                        host = "localhost",
                        port = port.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "seqclistore").absolutePath,
                        fileLogPath = File(testDir, "seqclilog").absolutePath,
                    ),
            )

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

        fun executionReports(): List<String> =
            viewModel.sessions
                .first { it.title == "CLI" }
                .messages.value
                .filterIsInstance<FixMessage>()
                .filter { it.direction == FixMessage.Direction.INCOMING && it.messageType == "8" }
                .map { it.rawMessage }

        val startedAt = System.nanoTime()
        assertTrue(
            status(post("/send", """{"session":"CLI","raw":"35=D|11=ORD-SEQ|55=EUR/USD|54=1|38=100|40=1|"}"""))
                in listOf("sent", "warning"),
        )
        assertTrue(
            awaitCondition(15_000) { executionReports().size >= 3 },
            "the client should receive all three steps; got ${executionReports().size}",
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        val reports = executionReports()
        assertEquals(3, reports.size, "the sequence is three steps, and it plays once: $reports")
        assertEquals(
            listOf("39=0", "39=1", "39=2"),
            reports.map { report -> listOf("39=0", "39=1", "39=2").first { report.contains("|$it|") } },
            "the lifecycle must arrive in the order it was written: $reports",
        )
        assertTrue(
            elapsedMillis >= 700,
            "0/400/400ms of delay cannot complete in ${elapsedMillis}ms — the acceptor is ignoring them",
        )

        val execIds = reports.mapNotNull { Regex("\\|17=([^|]+)\\|").find(it)?.groupValues?.get(1) }
        assertEquals(3, execIds.toSet().size, "each step mints its own ExecID; got $execIds")
        assertTrue(reports.all { it.contains("|11=ORD-SEQ|") }, "every step echoes the request ClOrdID: $reports")
    }

    /**
     * **A sequence mid-flight is a state, and it must be visible as one.**
     *
     * Between the trigger and the last step, an acceptor has matched a rule and sent only part of its
     * reply. From the message log that is indistinguishable from a rule that never matched — both are
     * "the thing I expected is not there yet" — so a test driving an acceptor cannot tell "wait longer"
     * from "your rule is wrong". The counters separate them: `triggersMatched` ahead of
     * `responsesSent`, with `pendingResponses` non-zero, is the sequence still playing out.
     */
    @Test
    fun `a running acceptor reports what it is mid-way through`() {
        val port = freePort()
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
                        acceptorResponseRules =
                            listOf(
                                AcceptorResponseRule(
                                    whenMsgType = "D",
                                    steps =
                                        listOf(
                                            ResponseStep(template = "35=8|150=0|39=0|11=\${req.11}|"),
                                            ResponseStep(template = "35=8|150=F|39=2|11=\${req.11}|", delayMillis = 2_000),
                                        ),
                                ),
                                // Disabled, so it is compiled away — rulesLive must say 1, not 2.
                                AcceptorResponseRule(
                                    whenMsgType = "F",
                                    enabled = false,
                                    steps = listOf(ResponseStep(template = "35=9|")),
                                ),
                            ),
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

        fun acceptorBlock(): JsonObject? =
            arr(get("/sessions"))
                .map { it.jsonObject }
                .firstOrNull { it["title"]!!.jsonPrimitive.content == "ACC" }
                ?.get("acceptor")
                ?.jsonObject

        val idle = acceptorBlock()
        assertTrue(idle != null, "an acceptor session must carry the block at all")
        assertEquals(port.toString(), idle!!["acceptPort"]!!.jsonPrimitive.content)
        assertEquals(1, idle["rulesLive"]!!.jsonPrimitive.int, "the disabled rule is compiled away, so only one is in force")
        assertEquals(0, idle["triggersMatched"]!!.jsonPrimitive.int)

        // An initiator has nothing to say here, and says nothing rather than a row of zeroes.
        val clientRow = arr(get("/sessions")).map { it.jsonObject }.first { it["title"]!!.jsonPrimitive.content == "CLI" }
        assertTrue(clientRow["acceptor"] == null, "an initiator must not carry an acceptor block")

        post("/send", """{"session":"CLI","raw":"35=D|11=ORD-MID|55=EUR/USD|54=1|38=100|40=1|"}""")
        // The ack lands immediately; the fill is 2s behind it. Between them the counters disagree.
        assertEquals(
            "matched",
            status(post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")),
        )
        val midFlight = acceptorBlock()!!
        assertEquals(1, midFlight["triggersMatched"]!!.jsonPrimitive.int, "one order matched one rule")
        assertTrue(
            midFlight["pendingResponses"]!!.jsonPrimitive.int > 0,
            "the second step is still waiting out its 2s, and that must be visible: $midFlight",
        )
        assertTrue(
            midFlight["responsesSent"]!!.jsonPrimitive.int < 2,
            "not everything has gone out yet, which is the whole point of the gap: $midFlight",
        )

        // Once it has all played out the two agree and nothing is queued.
        assertTrue(
            awaitCondition(10_000) {
                val done = acceptorBlock()!!
                done["responsesSent"]!!.jsonPrimitive.int == 2 && done["pendingResponses"]!!.jsonPrimitive.int == 0
            },
            "both steps should eventually be sent and the queue drain; got ${acceptorBlock()}",
        )
    }

    /**
     * **Saving a rule changes what the acceptor does, without dropping the session.**
     *
     * Rules were compiled once, when the session connected, and nothing re-read them: editing one
     * under a logged-on acceptor wrote the file and changed nothing on the wire. Nothing said so
     * either, so the author watched the *old* rule keep firing — indistinguishable from a new rule
     * that does not work — and the natural response is to go and rewrite a rule that was already
     * correct. The reconnect that would have picked it up is exactly what someone mid-test does not
     * want to do.
     *
     * Asserted over a real socket, because that is the only place the distinction exists: the saved
     * profile was always right, and a test reading it back would have passed throughout the bug.
     */
    @Test
    fun `a rule edited under a live session takes effect without reconnecting`() {
        val port = freePort()
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
                        acceptorResponseRules =
                            listOf(
                                AcceptorResponseRule(
                                    whenMsgType = "D",
                                    steps = listOf(ResponseStep(template = "35=8|150=0|39=0|11=\${req.11}|58=first|")),
                                ),
                            ),
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

        post("/send", """{"session":"CLI","raw":"35=D|11=ORD-A|55=EUR/USD|54=1|38=100|40=1|"}""")
        val before =
            post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")
        assertEquals("matched", status(before))
        assertTrue(
            obj(before)["message"]!!.jsonObject["raw"]!!.jsonPrimitive.content.contains("58=first"),
            "the original rule should be what fires first",
        )

        // The edit, mid-session. No disconnect, no reconnect.
        val edited =
            post(
                "/acceptor/rules",
                """{"profile":"${acceptor.id}","index":0,"rule":{"whenMsgType":"D",
                   "steps":[{"template":"35=8|150=0|39=0|11=${'$'}{req.11}|58=second|"}]}}""",
            )
        assertEquals("replaced", status(edited))
        assertEquals(
            1,
            obj(edited)["appliedToLiveSessions"]!!.jsonPrimitive.int,
            "the caller must be told the edit reached the running session, not just the file",
        )

        post("/messages/clear", """{"session":"CLI"}""")
        post("/send", """{"session":"CLI","raw":"35=D|11=ORD-B|55=EUR/USD|54=1|38=100|40=1|"}""")
        val after =
            post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")
        assertEquals("matched", status(after))
        val raw = obj(after)["message"]!!.jsonObject["raw"]!!.jsonPrimitive.content
        assertTrue(raw.contains("58=second"), "the saved rule must be the one that fires; got $raw")
        assertTrue(raw.contains("11=ORD-B"), "and it must still be answering the new order; got $raw")
    }

    /**
     * **The other half of a live reload.** `reloadAcceptorRules` swaps the rules *and* the latency, and
     * only the rules half was pinned — the latency half was shipped on the strength of one manual run,
     * which protects nothing.
     *
     * The delay is asserted by the clock, because there is no other way to catch it. Everything else
     * about a latency change is invisible: the reply is byte-identical whether it left immediately or a
     * second late, so a test reading the message can pass against a latency that was never applied. The
     * `latencyActive` flag is asserted too, but only the elapsed time distinguishes "the config was
     * stored" from "the config governs the wire".
     */
    @Test
    fun `latency edited under a live session takes effect without reconnecting`() {
        val port = freePort()
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
                        acceptorResponseRules =
                            listOf(
                                AcceptorResponseRule(
                                    whenMsgType = "D",
                                    steps = listOf(ResponseStep(template = "35=8|150=0|39=0|11=\${req.11}|")),
                                ),
                            ),
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

        fun acceptorBlock(): JsonObject =
            arr(get("/sessions"))
                .map { it.jsonObject }
                .first { it["title"]!!.jsonPrimitive.content == "ACC" }["acceptor"]!!
                .jsonObject

        assertFalse(acceptorBlock()["latencyActive"]!!.jsonPrimitive.boolean, "the acceptor starts with no simulated latency")

        // The edit, mid-session: latency only, nothing else mentioned.
        val edited =
            post("/profiles", """{"id":"${acceptor.id}","name":"ACC","config":{"acceptorLatency":{"mode":"FIXED","fixedMillis":$LIVE_LATENCY_MS}}}""")
        assertEquals("updated", status(edited))
        assertEquals(
            1,
            obj(edited)["appliedToLiveSessions"]!!.jsonPrimitive.int,
            "a latency edit reaches the running session just as a rule edit does",
        )
        assertTrue(acceptorBlock()["latencyActive"]!!.jsonPrimitive.boolean, "and the session says so")

        post("/messages/clear", """{"session":"CLI"}""")
        val startedAt = System.currentTimeMillis()
        post("/send", """{"session":"CLI","raw":"35=D|11=ORD-SLOW|55=EUR/USD|54=1|38=100|40=1|"}""")
        val reply =
            post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":15000}""")
        val elapsedMillis = System.currentTimeMillis() - startedAt
        assertEquals("matched", status(reply), "the reply must still arrive, only later")
        // One-sided on purpose: a slow machine can only push this up, so the test cannot flake by being
        // late — only by the latency not being applied at all, which is the thing under test.
        assertTrue(
            elapsedMillis >= LIVE_LATENCY_MS - LATENCY_TOLERANCE_MS,
            "a ${LIVE_LATENCY_MS}ms venue latency cannot be answered in ${elapsedMillis}ms — the live session is ignoring it",
        )

        // And it can be taken away again while the session stays up.
        post("/profiles", """{"id":"${acceptor.id}","name":"ACC","config":{"acceptorLatency":{"mode":"NONE"}}}""")
        assertFalse(acceptorBlock()["latencyActive"]!!.jsonPrimitive.boolean, "turning it off must be live too")
    }

    /**
     * **W2, without a hand on the mouse.** The paste box is click-only, so the control surface grew a door to
     * it (as it grew one to reconcile in Phase 4) — and this drives it: a fake-venue log fragment, pasted, read
     * by the same reader the sheet uses, becomes a saved, badged, runnable scenario.
     *
     * The pipe is present on purpose: `58=filled|in full`, in the SOH bytes, comes through whole.
     */
    @Test
    fun `capture-paste turns a pasted log into a saved, badged scenario`() {
        val soh = "\u0001"
        fun frame(vararg body: String): String {
            val fields = body.joinToString(soh, postfix = soh)
            val head = "8=FIX.4.4${soh}9=${fields.length}$soh"
            val checksum = (head + fields).toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
            return head + fields + "10=%03d".format(checksum) + soh
        }
        val order =
            frame("35=D", "49=CLIENT", "56=VENUE", "11=ORD-1", "55=EUR/USD", "54=1", "38=1000000", "40=1", "60=20260714-08:12:31")
        val execution =
            frame("35=8", "49=VENUE", "56=CLIENT", "37=V-9", "11=ORD-1", "17=E-1", "150=2", "39=2", "58=filled|in full", "60=20260714-08:12:31")
        val wire = "08:12:31.500 OUT $order" + "\n" + "08:12:31.517 IN $execution"

        val body =
            buildJsonObject {
                put("name", "PASTED")
                put("wire", wire)
                put("senderCompId", "CLIENT")
                put("targetCompId", "VENUE")
            }
        val resp = obj(post("/scenarios/capture-paste", Json.encodeToString(JsonObject.serializer(), body)))

        assertEquals("created", resp["status"]!!.jsonPrimitive.content, resp.toString())
        assertEquals(2, resp["steps"]!!.jsonPrimitive.int, "the send and the reply")
        assertTrue(resp["pasted"]!!.jsonPrimitive.boolean, "and every step of it is badged pasted")
        assertTrue(resp["refused"]!!.jsonArray.isEmpty(), "both lines read")

        // The scenario is real, and the pipe survived into the golden it will re-judge against.
        val scenario = resp["scenario"]!!.jsonObject
        val steps = scenario["steps"]!!.jsonArray
        assertEquals("send", steps[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("expect", steps[1].jsonObject["type"]!!.jsonPrimitive.content)
        val golden = steps[1].jsonObject["expectation"]!!.jsonObject["golden"]!!.jsonPrimitive.content
        assertTrue(golden.contains("58=filled|in full"), "the trap survived: 58 is one field, pipe and all")
    }

    /**
     * **The false green by omission, refused at the door.** With no CompIDs to settle direction, a reply saved
     * as a Send would assert nothing — so the endpoint refuses the save and names the undirected messages
     * rather than handing an agent a scenario that checks less than it says.
     */
    @Test
    fun `capture-paste refuses a save when a message's direction cannot be settled`() {
        val soh = "\u0001"
        fun frame(vararg body: String): String {
            val fields = body.joinToString(soh, postfix = soh)
            val head = "8=FIX.4.4${soh}9=${fields.length}$soh"
            val checksum = (head + fields).toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
            return head + fields + "10=%03d".format(checksum) + soh
        }
        val reply = frame("35=8", "49=SOMEONE", "56=ELSE", "150=2", "39=2")
        val body = buildJsonObject { put("name", "NODIR"); put("wire", reply) }
        val resp = obj(post("/scenarios/capture-paste", Json.encodeToString(JsonObject.serializer(), body)))

        assertEquals("refused", resp["status"]!!.jsonPrimitive.content, resp.toString())
        assertTrue(resp["undirected"]!!.jsonArray.isNotEmpty(), "the direction nobody could settle is named")
        assertTrue(
            "asserts nothing" in resp["reason"]!!.jsonPrimitive.content,
            "and it says WHY a guess is refused: ${resp["reason"]!!.jsonPrimitive.content}",
        )
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

    /** Invokes an MCP tool over /mcp and returns the first text content block. */
    private fun mcpCall(name: String, args: String): String {
        val body = """{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        return obj(post("/mcp", body))["result"]!!
            .jsonObject["content"]!!
            .jsonArray
            .first()
            .jsonObject["text"]!!
            .jsonPrimitive.content
    }

    private companion object {
        /**
         * The simulated venue latency applied mid-session. Large enough that no scheduling jitter can
         * account for it, small enough not to pad the suite.
         */
        const val LIVE_LATENCY_MS = 800L

        /** Slack for timer granularity. Only the *lower* bound is asserted, so this cannot mask a miss. */
        const val LATENCY_TOLERANCE_MS = 100L
    }
}
