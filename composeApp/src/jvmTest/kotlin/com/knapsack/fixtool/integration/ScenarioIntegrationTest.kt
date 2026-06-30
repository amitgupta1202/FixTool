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
 * End-to-end test for the repeatable-scenarios runner and store: a saved scenario (send a
 * NewOrderSingle, assert the auto-responded ExecutionReport) is run deterministically over the
 * control surface and produces a per-tag report. Covers run-inline, save/list/run-by-id/delete,
 * JUnit output, a failing expectation, and the MCP transport.
 */
class ScenarioIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val baseUrl get() = "http://127.0.0.1:$port"
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-scenario-test", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        port = freePort()
        server = ControlServer(port, viewModel, windowProvider = { null }, token = null)
        server.start()
        connectAcceptorAndClient()
    }

    @After
    fun cleanup() {
        server.stop()
        viewModel.disconnectAllSessions()
        awaitCondition { viewModel.sessions.all { it.connectionState.value != FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    /** A book-a-trade scenario: clear, send the order, assert the ExecutionReport. */
    private fun scenarioJson(name: String, execTypeExpected: String): String =
        """{
          "name": "$name",
          "setup": [ {"type":"clearMessages","session":"CLI"} ],
          "steps": [
            {"type":"send","session":"CLI","raw":"35=D|11=ORD-${'$'}{uuidShort}|55=EUR/USD|54=1|38=100|40=1|"},
            {"type":"expect","session":"CLI","direction":"in","timeoutMs":8000,"expectation":{
              "messageType":"8","mode":"open","fields":[
                {"tag":35,"matcher":{"type":"exact","value":"8"}},
                {"tag":150,"matcher":{"type":"exact","value":"$execTypeExpected"}},
                {"tag":11,"matcher":{"type":"reference","expression":"${'$'}{out.D.11}"}},
                {"tag":37,"matcher":{"type":"presence"}}
              ]}}
          ]
        }""".replace("\${uuidShort}", "S$runId")

    @Test
    fun `run an inline scenario end to end`() {
        val resp = obj(post("/scenarios/run", """{"scenario": ${scenarioJson("book", "0")}}"""))
        assertTrue(resp["passed"]!!.jsonPrimitive.boolean, "scenario should pass: $resp")
        val expectStep = resp["steps"]!!.jsonArray.map { it.jsonObject }.single { it["kind"]!!.jsonPrimitive.content == "expect" }
        val tag11 = expectStep["tags"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.int == 11 }
        assertEquals("ORD-S$runId", tag11["actual"]!!.jsonPrimitive.content, "reference matcher should resolve out.D.11")
    }

    @Test
    fun `a wrong expectation fails the scenario`() {
        // Expect ExecType=8 (Rejected) but the acceptor sends ExecType=0 (New).
        val resp = obj(post("/scenarios/run", """{"scenario": ${scenarioJson("bad", "8")}}"""))
        assertFalse(resp["passed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `save list run-by-id and delete`() {
        val created = obj(post("/scenarios", scenarioJson("saved-trade", "0")))
        assertEquals("created", created["status"]!!.jsonPrimitive.content)
        val id = created["id"]!!.jsonPrimitive.content

        val listed = obj(get("/scenarios"))
        assertTrue(listed["count"]!!.jsonPrimitive.int >= 1)
        assertTrue(listed["scenarios"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == id })

        val ran = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertTrue(ran["passed"]!!.jsonPrimitive.boolean, "saved scenario should run green: $ran")

        assertEquals("deleted", obj(delete("/scenarios", """{"id":"$id"}"""))["status"]!!.jsonPrimitive.content)
        assertFalse(obj(get("/scenarios"))["scenarios"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test
    fun `junit format is returned for CI`() {
        val resp = obj(post("/scenarios/run", """{"format":"junit","scenario": ${scenarioJson("ci", "0")}}"""))
        assertTrue(resp["passed"]!!.jsonPrimitive.boolean)
        assertTrue(resp["junit"]!!.jsonPrimitive.content.contains("<testsuite"), "should return JUnit XML")
    }

    @Test
    fun `run scenario is reachable over MCP`() {
        val text = mcpCall("fixtool_run_scenario", """{"scenario": ${scenarioJson("mcp", "0")}}""")
        assertTrue(text.contains("\"passed\":true"), "MCP run_scenario should pass; got $text")
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
                config = FixConnectionConfig(
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
                config = FixConnectionConfig(
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
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = request("GET", path, null)

    private fun post(path: String, body: String) = request("POST", path, body)

    private fun delete(path: String, body: String) = request("DELETE", path, body)

    private fun obj(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonObject

    private fun mcpCall(name: String, args: String): String {
        val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        return obj(post("/mcp", body))["result"]!!
            .jsonObject["content"]!!
            .jsonArray
            .first()
            .jsonObject["text"]!!
            .jsonPrimitive.content
    }
}
