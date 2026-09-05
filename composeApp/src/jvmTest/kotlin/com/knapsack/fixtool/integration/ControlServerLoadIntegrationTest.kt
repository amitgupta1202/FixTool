package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
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
import kotlin.test.assertTrue

/**
 * **The load run's job routes**, over a real loopback control server: what a caller gets when the plan is
 * wrong, when nothing can supply lanes, and when a finished record is read back.
 */
class ControlServerLoadIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0
    private val client: HttpClient = HttpClient.newHttpClient()

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-control-load", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        port = ServerSocket(0).use { it.localPort }
        server = ControlServer(port, viewModel, windowProvider = { emptyList() }, token = null)
        server.start()
    }

    @After
    fun cleanup() {
        server.stop()
        testDir.deleteRecursively()
    }

    private fun profile(resetOnLogon: Boolean) =
        FixConnectionProfile(
            id = "lg",
            name = "LOADGEN",
            config = FixConnectionConfig(senderCompID = "LG{n}", targetCompID = "VENUE", host = "localhost", port = "9", sessionCount = 3, resetOnLogon = resetOnLogon),
        )

    @Test
    fun `a plan with no lane logged on is a 409 that names the reason`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        val resp = post("/load", """{"profile":"LOADGEN","raw":"35=D|11=ORD-${'$'}{messageIndex}|55=EUR/USD|","count":100}""")

        assertEquals(409, resp.statusCode(), resp.body())
        assertTrue(resp.body().contains("logged on"), resp.body())
    }

    @Test
    fun `a wrong plan is an error object in the config's own words`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = false))

        val store = obj(post("/load", """{"profile":"LOADGEN","raw":"35=D|11=A|","count":10,"store":"memory"}"""))
        assertTrue(store["error"]!!.jsonPrimitive.content.contains("Reset on Logon"), store.toString())

        val shape = obj(post("/load", """{"profile":"LOADGEN","raw":"35=D|11=A|"}"""))
        assertTrue(shape["error"]!!.jsonPrimitive.content.contains("say either"), shape.toString())

        val match = obj(post("/load", """{"profile":"LOADGEN","raw":"35=0|","count":10}"""))
        assertTrue(match["error"]!!.jsonPrimitive.content.contains("matched on"), match.toString())

        val missing = post("/load", """{"profile":"NOPE","raw":"35=D|11=A|","count":10}""")
        assertEquals(404, missing.statusCode())
    }

    @Test
    fun `a finished record lists, reads back as the same report, and refuses a stop`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.loadRecordStore.write(report)

        val list = obj(get("/loads"))
        assertEquals(1, list["count"]!!.jsonPrimitive.int)
        val row = list["loads"]!!.jsonArray.single().jsonObject
        assertEquals(report.id, row["id"]!!.jsonPrimitive.content)
        assertEquals("done", row["status"]!!.jsonPrimitive.content)
        assertEquals(1, row["exitCode"]!!.jsonPrimitive.int)

        val one = get("/loads/${report.id}?wait=100")
        assertEquals(200, one.statusCode())
        val json = obj(one)
        assertEquals(report.label, json["label"]!!.jsonPrimitive.content)
        assertEquals(4, json["replies"]!!.jsonObject["unmatched"]!!.jsonPrimitive.int)
        assertEquals("UNMATCHED", json["verdict"]!!.jsonObject["completeness"]!!.jsonPrimitive.content)

        assertEquals(409, post("/loads/${report.id}/stop", "{}").statusCode())
        assertEquals(404, get("/loads/nothing-here").statusCode())
    }

    @Test
    fun `the MCP tools are listed and the status tool lists recent runs`() {
        viewModel.loadRecordStore.write(LoadFixtures.burstReport(unmatched = 0))

        val tools = obj(post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""))
        val names = tools["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("fixtool_load" in names && "fixtool_load_status" in names, names.toString())

        val text = mcpCall("fixtool_load_status", "{}")
        assertEquals(1, Json.parseToJsonElement(text).jsonObject["count"]!!.jsonPrimitive.int, text)
    }

    private fun request(method: String, path: String, body: String?): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val req =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = request("GET", path, null)

    private fun post(path: String, body: String) = request("POST", path, body)

    private fun obj(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonObject

    private fun mcpCall(name: String, args: String): String {
        val body = """{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        return obj(post("/mcp", body))["result"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    }
}
