package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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

    private fun profile(resetOnLogon: Boolean = true) =
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

        assertEquals("done", row["stage"]!!.jsonPrimitive.content, "the run's own lifecycle, on the row")

        // The record, not the bare report: one shape at the file, the flag and this route.
        val one = get("/loads/${report.id}?wait=100")
        assertEquals(200, one.statusCode())
        val json = obj(one)
        assertEquals(report.label, json["label"]!!.jsonPrimitive.content)
        assertEquals(1, json["exitCode"]!!.jsonPrimitive.int)
        val phase = json["phases"]!!.jsonArray.single().jsonObject
        assertEquals(4, phase["replies"]!!.jsonObject["unmatched"]!!.jsonPrimitive.int)
        assertEquals("UNMATCHED", phase["verdict"]!!.jsonObject["completeness"]!!.jsonPrimitive.content)

        assertEquals(409, post("/loads/${report.id}/stop", "{}").statusCode())
        assertEquals(404, get("/loads/nothing-here").statusCode())
    }

    /**
     * A row says how many phases there are and leads on the one worth reading, which a poller acts on.
     *
     * **Not on the last phase.** A set that failed at phase 2 under STOP ends on a skipped stub that
     * carries its plan and no measurements, and a row that led on it reported "issued 0, matched 0, stage
     * preparing" for a set that had issued four thousand messages and failed.
     */
    @Test
    fun `a set's record lists with its phase counts, and reads back whole`() {
        val one = LoadFixtures.burstReport(unmatched = 0).copy(label = "Ask for a quote")
        val two = LoadFixtures.burstReport(unmatched = 4).copy(label = "Hit them")
        val three =
            one.copy(
                label = "Pass the rest",
                status = LoadStatus.SKIPPED,
                note = "phase 2 did not pass and the set stops on failure",
                stage = com.knapsack.fixtool.model.load.LoadStage.PREPARING,
                issue = one.issue.copy(handedToEngine = 0, leftSocket = 0),
                replies = one.replies.copy(matched = 0, unmatched = 0),
                verdict = one.verdict.copy(exitCode = null),
            )
        viewModel.loadRecordStore.write(
            LoadRecord(
                id = "set-1",
                label = "Round trip",
                startedAt = 1_000,
                finishedAt = 2_000,
                phases = listOf(one, two, three),
                set = LoadRecord.SetInfo("round-trip", OnFailure.STOP),
                seed = mapOf("run" to "b7f2"),
            ),
        )

        val row = obj(get("/loads"))["loads"]!!.jsonArray.single().jsonObject
        assertEquals("set-1", row["id"]!!.jsonPrimitive.content)
        assertEquals(3, row["phases"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        assertEquals(2, row["phases"]!!.jsonObject["done"]!!.jsonPrimitive.int)
        assertEquals(1, row["exitCode"]!!.jsonPrimitive.int)
        assertEquals("done", row["stage"]!!.jsonPrimitive.content, "the stage of the phase that failed, not the stub's")
        assertEquals(two.issue.leftSocket, row["issued"]!!.jsonPrimitive.content.toLong())
        assertEquals(two.replies.matched, row["matched"]!!.jsonPrimitive.content.toLong())
        assertEquals(two.replies.unmatched, row["unmatched"]!!.jsonPrimitive.content.toLong())

        val whole = obj(get("/loads/set-1"))
        assertEquals(3, whole["phases"]!!.jsonArray.size)
        assertEquals("round-trip", whole["set"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("FAILED", whole["verdict"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        assertEquals(2, whole["verdict"]!!.jsonObject["phase"]!!.jsonPrimitive.int)
    }

    /**
     * **A row never says running for a set nothing is running**, and it leads on the phase that was going
     * rather than on the stub after it.
     *
     * The store heals a record left behind by a process that died: the live phase to stopped, the phases
     * still to come to skipped. A poller that saw "running" for ever would wait for ever.
     */
    @Test
    fun `a row for a set whose process died is stopped, and leads on the phase that was going`() {
        val done = LoadFixtures.burstReport(unmatched = 0).copy(label = "Ask for a quote")
        val going =
            LoadFixtures
                .burstReport(unmatched = 0, status = LoadStatus.RUNNING)
                .copy(label = "Hit them", finishedAt = null, verdict = done.verdict.copy(exitCode = null))
        val pending =
            done.copy(label = "Pass the rest", status = LoadStatus.PENDING, finishedAt = null, verdict = done.verdict.copy(exitCode = null))
        viewModel.loadRecordStore.write(
            LoadRecord("set-live", "Round trip", 1_000, null, listOf(done, going, pending), LoadRecord.SetInfo("round-trip", OnFailure.STOP)),
        )

        val row = obj(get("/loads"))["loads"]!!.jsonArray.single().jsonObject

        assertEquals("stopped", row["status"]!!.jsonPrimitive.content)
        assertEquals(1, row["exitCode"]!!.jsonPrimitive.int, "the set is over, and a stop proves nothing whole")
        assertEquals(going.issue.leftSocket, row["issued"]!!.jsonPrimitive.content.toLong(), "the counts of the phase that was going")
        assertEquals("done", row["stage"]!!.jsonPrimitive.content, "healed, so not the skipped stub's 'preparing'")
    }

    @Test
    fun `the saved sets list, one reads back whole, and a name nothing answers to is a 404`() {
        viewModel.loadSetStore.save(
            LoadSet(
                name = "round-trip",
                label = "Round trip",
                seed = mapOf("run" to "\${uuid:4}"),
                storeAndLog = StoreAndLogOverride.FOR_LOAD,
                phases =
                    listOf(
                        LoadPhaseSpec("Ask for a quote", "Quotes", "LOADGEN", match = LoadMatch(131, 131, "S"), shape = LoadShape.Burst(10)),
                        LoadPhaseSpec("Hit them", "Hits", "LOADGEN", match = LoadMatch(11, 11, "8"), shape = LoadShape.Burst(10)),
                    ),
            ),
        )

        val list = obj(get("/load-sets"))
        assertEquals(1, list["count"]!!.jsonPrimitive.int)
        val row = list["sets"]!!.jsonArray.single().jsonObject
        assertEquals("round-trip", row["name"]!!.jsonPrimitive.content)
        assertEquals(2, row["phases"]!!.jsonPrimitive.int)
        assertEquals(listOf("LOADGEN"), row["profiles"]!!.jsonArray.map { it.jsonPrimitive.content })

        val whole = obj(get("/load-sets/round-trip"))
        assertEquals("Round trip", whole["label"]!!.jsonPrimitive.content)
        assertEquals(2, whole["phases"]!!.jsonArray.size)

        assertEquals(404, get("/load-sets/nowhere").statusCode())
    }

    @Test
    fun `a set whose phase names nothing is refused in that phase's voice, and none of it dials`() {
        viewModel.saveConnectionProfile(profile())

        val body =
            """
            {"phases":[
              {"label":"Ask for a quote","template":"nowhere","profile":"LOADGEN",
               "match":{"requestTag":131,"replyTag":131,"replyType":"S"},
               "shape":{"kind":"burst","count":10}}]}
            """.trimIndent()
        val refused = obj(post("/load", body))
        assertTrue(refused["error"]!!.jsonPrimitive.content.startsWith("Phase 1 · Ask for a quote:"), refused.toString())
        assertTrue(refused["error"]!!.jsonPrimitive.content.contains("no template 'nowhere'"), refused.toString())

        assertEquals(404, post("/load", """{"set":"nowhere"}""").statusCode())
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
