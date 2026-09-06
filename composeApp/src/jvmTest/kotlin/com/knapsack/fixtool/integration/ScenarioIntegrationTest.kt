package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.SavedRunEntry
import com.knapsack.fixtool.service.SavedRunSet
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
        testDir =
            File.createTempFile("fixtool-scenario-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        port = freePort()
        server = ControlServer(port, viewModel, windowProvider = { emptyList() }, token = null)
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
    private fun scenarioJson(name: String, execTypeExpected: String, id: String? = null): String =
        """{
          ${id?.let { "\"id\": \"$it\"," } ?: ""}
          "name": "$name",
          "setup": [ {"type":"clearMessages","session":"CLI"} ],
          "steps": [
            {"type":"send","session":"CLI","raw":"35=D|11=ORD-${'$'}{uuidShort}|55=EUR/USD|54=1|38=100|40=1|"},
            {"type":"expect","session":"CLI","direction":"in","timeoutMs":8000,"expectation":{
              "messageType":"8","mode":"open","fields":[
                {"tag":35,"matcher":{"type":"exact","value":"8"}},
                {"tag":11,"matcher":{"type":"reference","expression":"${'$'}{out.D.11}"}},
                {"tag":37,"matcher":{"type":"presence"}},
                {"tag":150,"matcher":{"type":"exact","value":"$execTypeExpected"}}
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

    /**
     * The read → edit → save-back loop: GET /scenarios?id= returns the full definition in exactly
     * the shape POST /scenarios accepts, and a fetch → save → fetch round-trip is lossless. Also
     * exposed over MCP as fixtool_get_scenario.
     */
    @Test
    fun `get by id round-trips losslessly through save`() {
        val id = obj(post("/scenarios", scenarioJson("roundtrip", "0")))["id"]!!.jsonPrimitive.content

        val fetched = obj(get("/scenarios?id=$id"))
        assertEquals(id, fetched["id"]!!.jsonPrimitive.content)
        assertEquals(2, fetched["steps"]!!.jsonArray.size, "full definition, not a summary: $fetched")

        // Save the fetched JSON back verbatim, fetch again — byte-for-byte identical definition.
        assertEquals("updated", obj(post("/scenarios", fetched.toString()))["status"]!!.jsonPrimitive.content)
        assertEquals(fetched, obj(get("/scenarios?id=$id")))

        // And it still runs green after the round-trip.
        assertTrue(obj(post("/scenarios/run", """{"id":"$id"}"""))["passed"]!!.jsonPrimitive.boolean)

        // MCP surface returns the same definition; unknown id is a named error.
        assertTrue(mcpCall("fixtool_get_scenario", """{"id":"$id"}""").contains("\"steps\""))
        assertTrue(obj(get("/scenarios?id=nope"))["error"]!!.jsonPrimitive.content.contains("not found"))
        delete("/scenarios", """{"id":"$id"}""")
    }

    /**
     * The fail → fix loop end to end over real sessions: a saved scenario fails (wrong ExecType), the
     * failure deep-links to the expect step that produced it, the assertion is rebaselined, and the
     * re-run of the rewritten scenario is green.
     *
     * The rebaseline goes through the scenario itself rather than the message viewer. The viewer's
     * quick-fix chips are gone: they could only ever repair a *value mismatch*, because that panel
     * renders the message that arrived — a tag the venue stopped sending has no row to click, and a
     * reordered group entry looks fine tag by tag while the step still fails. Authoring lives in the
     * reconcile view now, which sees both sides. What this test guards is the loop, not the surface:
     * a failure must be attributable to one step, and correcting that step must actually make it pass.
     */
    @Test
    fun `a failing run is attributed to its step, and correcting that step re-runs green`() {
        // Expect ExecType=8 (Rejected); the acceptor answers ExecType=0 (New) -> the run fails.
        val created = obj(post("/scenarios", scenarioJson("fixloop", "8")))
        val id = created["id"]!!.jsonPrimitive.content
        val firstRun = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertFalse(firstRun["passed"]!!.jsonPrimitive.boolean)

        // The run left per-message attribution behind: exactly one message carries the failed step.
        val (failedMessage, failedStep) = viewModel.assertionResults.entries.single { !it.value.passed }
        val failed150 = failedStep.tags.single { it.tag == 150 && !it.passed }
        assertEquals("0", failed150.actual, "acceptor answers ExecType=0")
        assertEquals(0, failed150.occurrence, "ExecType appears once — the first and only occurrence")

        // Deep-link opens the diff WINDOW on the failing expect step (steps index 1: send=0, expect=1).
        viewModel.openScenarioEditorForFailure(failedMessage)
        val window = viewModel.openDiffWindows.value.single()
        assertEquals(id, window.scenarioId)
        assertEquals(
            viewModel
                .scenarioDraft(id)!!
                .draft.steps[1]
                .stepId,
            window.stepId,
            "the diff opened on the step that failed, by its identity",
        )
        assertNotNull(window.session, "and it is bound to this run's bytes, so there is a diff to look at")
        viewModel.closeDiffWindow(window.id)

        // Rebaseline the step to what the venue actually sends, save it back, and re-run for real.
        assertEquals("updated", obj(post("/scenarios", scenarioJson("fixloop", "0", id = id)))["status"]!!.jsonPrimitive.content)
        val secondRun = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertTrue(secondRun["passed"]!!.jsonPrimitive.boolean, "rebaselined scenario should pass: $secondRun")
        delete("/scenarios", """{"id":"$id"}""")
    }

    /**
     * An agent-driven run must leave the workbench in the same state the Run button does — verdict published,
     * failure attributed, reconcile route open.
     *
     * It did not. The control endpoint kept its own copy of the run choreography and had quietly dropped the
     * last step of it: it published the per-message assertion results (so the session grid went red) but never
     * the verdict, so the run report — and the only route to the reconcile view, which hangs off it — stayed
     * blank. The scenarios are staged by MCP and curl in practice, so this was the path a human actually used.
     */
    @Test
    fun `a control-surface run publishes the same verdict the Run button does`() {
        val created = obj(post("/scenarios", scenarioJson("published", "8"))) // expects Rejected, gets New
        val id = created["id"]!!.jsonPrimitive.content

        val ran = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertFalse(ran["passed"]!!.jsonPrimitive.boolean)

        val published = viewModel.scenarioResult.value
        assertNotNull(published, "the run report reads scenarioResult — an unpublished verdict is a blank report")
        assertFalse(published.passed)
        assertEquals("published", published.scenario)

        // And the report's route to the diff is open on the failing step, exactly as after a UI run.
        val failure = published.steps.first { !it.passed }
        val route = viewModel.reconcileRoute(failure)
        assertTrue(
            route is FixMessageViewModel.ReconcileRoute.Open,
            "an agent-driven failure must be reconcilable from the report, got: $route",
        )
        assertEquals(1, route.request.focusStep)
        delete("/scenarios", """{"id":"$id"}""")
    }


    /**
     * The excluded-field invariant, end to end over a real session and asserted on the **counterparty's
     * own received bytes** — not on what the runner reports it sent, which is the tool marking its own
     * homework. An excluded field must survive authoring and the store, and reach nobody.
     *
     * Deliberately an OUTCOME test, and it does not pin any one mechanism: delete the strip in
     * `ScenarioRunner` and this still passes, because `#18` is unreadable to `parseFixMessage` and gets
     * dropped on the way to QuickFIX/J anyway. That is the `#` marker earning its keep — the invariant is
     * defended twice over — but it does mean this test cannot tell the two layers apart. The mint test
     * below is the one that pins the strip specifically; it goes red the moment the strip is removed.
     */
    @Test
    fun `an excluded field survives the store and never reaches the counterparty`() {
        val scenario =
            """{
              "name": "exclude-$runId",
              "setup": [ {"type":"clearMessages","session":"CLI"} ],
              "steps": [
                {"type":"send","session":"CLI","raw":"35=D|11=EX-$runId|55=EUR/USD|54=1|38=100|40=1|#18=A|"}
              ]
            }"""
        val id = obj(post("/scenarios", scenario))["id"]!!.jsonPrimitive.content

        // The marker is still on disk after a save -> fetch round trip.
        val fetched = obj(get("/scenarios?id=$id"))
        assertTrue(fetched.toString().contains("#18=A"), "excluded field must survive save/fetch: $fetched")

        assertTrue(obj(post("/scenarios/run", """{"id":"$id"}"""))["passed"]!!.jsonPrimitive.boolean)

        val tags = wireTagsOf(awaitOrder("EX-$runId"))
        assertFalse(18 in tags, "excluded tag 18 must not reach the venue; received $tags")
        assertTrue(40 in tags, "its neighbour tag 40 must still be there; received $tags")

        delete("/scenarios", """{"id":"$id"}""")
    }

    /**
     * The subtle half: `resolve` is a whole-string regex that never parses fields, so a mint sitting in
     * an excluded row would evaluate and bind a variable nothing sends — and the next step would
     * correlate against a value the venue never saw. Here the second order must carry the reference
     * LITERALLY, which is the engine's deliberate behaviour for a name nothing minted.
     */
    @Test
    fun `a mint inside an excluded field binds nothing, so a later reference stays literal`() {
        val scenario =
            """{
              "name": "excluded-mint-$runId",
              "setup": [ {"type":"clearMessages","session":"CLI"} ],
              "steps": [
                {"type":"send","session":"CLI","raw":"35=D|11=M1-$runId|55=EUR/USD|54=1|38=100|40=1|#58=${'$'}{note = \"HELLO\"}|"},
                {"type":"send","session":"CLI","raw":"35=D|11=M2-$runId|55=EUR/USD|54=1|38=100|40=1|58=${'$'}{note}|"}
              ]
            }"""
        assertTrue(obj(post("/scenarios/run", """{"scenario": $scenario}"""))["passed"]!!.jsonPrimitive.boolean)

        // The first order carried no 58 at all — the row it lived on was excluded.
        assertFalse(58 in wireTagsOf(awaitOrder("M1-$runId")), "the excluded row must not be sent")

        // And the second's 58 is the unresolved reference, proving nothing ever bound `note`.
        val second = FixMessageHelper.wireFields(awaitOrder("M2-$runId"))!!
        assertEquals("${'$'}{note}", second.single { it.first == 58 }.second)
    }

    /**
     * **A run in progress can be abandoned.** An expect holds the single run slot for its whole timeout,
     * so a scenario waiting on a reply that is not coming took the tool away from its author for thirty
     * seconds — and from every other run, since the slot is one. The stop is polled, not thrown: the
     * runner's waits are sleep loops on the thread that owns the slot and is halfway through a report.
     */
    @Test
    fun `a run in progress can be stopped, and reports as stopped rather than passed`() {
        val body =
            """{"scenario": {
              "name": "never-answers-$runId",
              "steps": [
                {"type":"expect","session":"CLI","direction":"in","timeoutMs":30000,"expectation":{
                  "messageType":"9","mode":"open","fields":[{"tag":35,"matcher":{"type":"exact","value":"9"}}]}}
              ]}}"""
        // The route is synchronous, so the run has to be in flight on another thread to be stopped at all.
        val inFlight = CompletableFuture.supplyAsync { post("/scenarios/run", body) }
        assertTrue(awaitCondition(10_000) { viewModel.scenarioRunning.value }, "the run should have claimed the slot")

        val askedAt = System.currentTimeMillis()
        viewModel.requestScenarioStop()
        val ran = obj(inFlight.get(20, TimeUnit.SECONDS))

        assertTrue(
            System.currentTimeMillis() - askedAt < 10_000,
            "the stop must land within a poll, not at the end of the 30s timeout",
        )
        assertFalse(ran["passed"]!!.jsonPrimitive.boolean, "a run that stopped checking has not checked: $ran")
        val stopped =
            ran["steps"]!!.jsonArray.map { it.jsonObject }.single { it["kind"]!!.jsonPrimitive.content == "stopped" }
        assertTrue(stopped["detail"]!!.jsonPrimitive.content.contains("stopped by request"), "$stopped")

        // And the slot is free again: a stopped run releases what it held, or nothing could run after it.
        assertTrue(awaitCondition(5_000) { !viewModel.scenarioRunning.value }, "the run slot must be released")
        assertTrue(obj(post("/scenarios/run", """{"scenario": ${scenarioJson("after-stop", "0")}}"""))["passed"]!!.jsonPrimitive.boolean)
    }

    /**
     * **A set is a job.** The route that runs one scenario inline cannot carry twelve: it runs on one of
     * four HTTP threads with no timeout, and the MCP shim gives up at fifteen seconds. So a set is
     * started, polled, and read — while the bare `id` call stays exactly what it was.
     */
    @Test
    fun `a run set starts, polls, and its entries can be read back`() {
        val id = obj(post("/scenarios", scenarioJson("suite-member", "0")))["id"]!!.jsonPrimitive.content

        val started = post("/scenarios/run", """{"id":"$id","repeat":2}""")
        assertEquals(202, started.statusCode(), "a set answers 202 and an id, not a report: ${started.body()}")
        val runSet = obj(started)["runSet"]!!.jsonPrimitive.content
        assertEquals(2, obj(started)["entries"]!!.jsonPrimitive.int)

        val status = obj(get("/scenarios/runs/$runSet?wait=10000"))
        assertEquals("passed", status["status"]!!.jsonPrimitive.content, "$status")
        val summary = status["summary"]!!.jsonObject
        assertEquals(2, summary["total"]!!.jsonPrimitive.int)
        assertEquals(2, summary["passed"]!!.jsonPrimitive.int)
        assertEquals(listOf(1, 2), status["entries"]!!.jsonArray.map { it.jsonObject["iteration"]!!.jsonPrimitive.int })

        // The record: the same report, plus the bytes — which is what a failed entry is diagnosed from
        // once the grid holds a later entry's traffic.
        val record = obj(get("/scenarios/runs/$runSet/entries/1"))
        assertTrue(record["result"]!!.jsonObject["passed"]!!.jsonPrimitive.boolean)
        assertTrue(record["messages"]!!.jsonArray.isNotEmpty(), "the evidence is in the record: $record")
        assertTrue(record["bound"]!!.jsonObject.isNotEmpty(), "and it says which message each step judged")

        assertTrue(
            obj(get("/scenarios/runs"))["sets"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == runSet },
            "the set is listed among the recent runs",
        )

        // Unchanged: given an id alone, the route is still synchronous and still returns the report.
        val single = post("/scenarios/run", """{"id":"$id"}""")
        assertEquals(200, single.statusCode())
        assertTrue(obj(single)["passed"]!!.jsonPrimitive.boolean)
    }

    /**
     * **The outline, over a real wire.** A scenario is already parameterized — every step resolves `${…}`
     * against one scope — so a table needed the table and one runner parameter. Each row is one entry of a
     * run set, and everything past that point is the machinery that already existed.
     */
    @Test
    fun `an examples table runs one entry per row, each seeded with its own cells`() {
        val outline =
            """{
              "name": "outline-$runId",
              "examples": {
                "columns": ["symbol", "qty"],
                "rows": [
                  {"name": "EUR/USD small", "values": {"symbol": "EUR/USD", "qty": "100"}},
                  {"name": "GBP/USD large", "values": {"symbol": "GBP/USD", "qty": "9000"}},
                  {"name": "parked", "values": {"symbol": "USD/JPY", "qty": "1"}, "muted": true}
                ]
              },
              "setup": [ {"type":"clearMessages","session":"CLI"} ],
              "steps": [
                {"type":"send","session":"CLI","raw":"35=D|11=ROW-${'$'}{uuid}|55=${'$'}{symbol}|54=1|38=${'$'}{qty}|40=1|"},
                {"type":"expect","session":"CLI","direction":"in","timeoutMs":8000,"expectation":{
                  "messageType":"8","mode":"open","fields":[
                    {"tag":35,"matcher":{"type":"exact","value":"8"}},
                    {"tag":55,"matcher":{"type":"reference","expression":"${'$'}{symbol}"}}]}}
              ]}"""
        val id = obj(post("/scenarios", outline))["id"]!!.jsonPrimitive.content

        val started = post("/scenarios/run", """{"id":"$id","rows":true}""")
        assertEquals(202, started.statusCode(), started.body())
        val runSet = obj(started)["runSet"]!!.jsonPrimitive.content
        assertEquals(2, obj(started)["entries"]!!.jsonPrimitive.int, "the parked row is kept and skipped")

        val status = obj(get("/scenarios/runs/$runSet?wait=10000"))
        assertEquals("passed", status["status"]!!.jsonPrimitive.content, "$status")

        // Each entry ran its own row: the cells reached the wire, and the reply was asserted against them.
        val first = obj(get("/scenarios/runs/$runSet/entries/1"))
        assertEquals("EUR/USD small", first["row"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(
            first["messages"]!!.jsonArray.any { m ->
                val raw = m.jsonObject["raw"]!!.jsonPrimitive.content
                raw.contains("55=EUR/USD") && raw.contains("38=100")
            },
            "the row's cells are on the bytes it sent: ${first["messages"]}",
        )
        // And provenance says the row supplied them, not whichever step happened to run first.
        val symbol =
            first["result"]!!.jsonObject["variables"]!!.jsonArray
                .map { it.jsonObject }
                .single { it["name"]!!.jsonPrimitive.content == "symbol" }
        assertEquals("EUR/USD", symbol["value"]!!.jsonPrimitive.content)
        assertEquals("row", symbol["source"]!!.jsonPrimitive.content)
        assertTrue(symbol["mintedAtStepId"] == null, "no step wrote it — the run was handed it: $symbol")

        val second = obj(get("/scenarios/runs/$runSet/entries/2"))
        assertEquals("GBP/USD large", second["row"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(second["messages"]!!.jsonArray.any { it.jsonObject["raw"]!!.jsonPrimitive.content.contains("38=9000") })
    }

    /** One row of eight is how an author debugs the row that failed without re-running the other seven. */
    @Test
    fun `a single row can be run by name, and a name nothing answers to is reported`() {
        val outline =
            """{
              "name": "one-row-$runId",
              "examples": {"columns": ["symbol"], "rows": [
                {"name": "alpha", "values": {"symbol": "EUR/USD"}},
                {"name": "beta", "values": {"symbol": "GBP/USD"}}]},
              "setup": [ {"type":"clearMessages","session":"CLI"} ],
              "steps": [
                {"type":"send","session":"CLI","raw":"35=D|11=ONE-${'$'}{uuid}|55=${'$'}{symbol}|54=1|38=1|40=1|"}
              ]}"""
        val id = obj(post("/scenarios", outline))["id"]!!.jsonPrimitive.content

        val started = post("/scenarios/run", """{"id":"$id","rows":["beta","no-such-row"]}""")

        assertEquals(202, started.statusCode(), started.body())
        assertEquals(1, obj(started)["entries"]!!.jsonPrimitive.int)
        assertEquals(
            listOf("no-such-row"),
            obj(started)["unresolved"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the row it could not find is named, and the other one still ran",
        )
        val runSet = obj(started)["runSet"]!!.jsonPrimitive.content
        val status = obj(get("/scenarios/runs/$runSet?wait=10000"))
        assertEquals("passed", status["status"]!!.jsonPrimitive.content, "$status")
        assertEquals("beta", obj(get("/scenarios/runs/$runSet/entries/1"))["row"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    /**
     * **The CI path: a set selected by name.** A build box has a checkout, not a local star file, so the
     * thing it names is a saved set — and a set whose file mentions a scenario nothing answers to runs the
     * rest and says which name it could not resolve.
     */
    @Test
    fun `a saved set is run by name, and an unresolved entry is reported rather than fatal`() {
        obj(post("/scenarios", scenarioJson("nightly-member", "0")))
        viewModel.runSetStore.save(
            SavedRunSet(
                name = "nightly-$runId",
                entries = listOf(SavedRunEntry("nightly-member"), SavedRunEntry("scenario-that-was-deleted")),
            ),
        )

        val started = post("/scenarios/run", """{"set":"nightly-$runId"}""")

        assertEquals(202, started.statusCode(), started.body())
        val body = obj(started)
        assertEquals(1, body["entries"]!!.jsonPrimitive.int)
        assertEquals(
            listOf("scenario-that-was-deleted"),
            body["unresolved"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the one it could not find is named, and the other one still ran",
        )
        val status = obj(get("/scenarios/runs/${body["runSet"]!!.jsonPrimitive.content}?wait=10000"))
        assertEquals("passed", status["status"]!!.jsonPrimitive.content, "$status")
    }

    /**
     * The slot is the set's for the whole batch, and the caller is told so in the word HTTP already has.
     * Then the set is stopped, which is the other half of a job model: a twelve-scenario suite nobody can
     * call off is a tool holding its user hostage.
     */
    @Test
    fun `a run in progress refuses another with 409, and the set can be stopped`() {
        val id = obj(post("/scenarios", scenarioJson("long-set", "0")))["id"]!!.jsonPrimitive.content

        // A pause between entries gives the test a window to act inside — the venue answers in microseconds.
        val started = post("/scenarios/run", """{"id":"$id","repeat":3,"pauseMs":4000}""")
        assertEquals(202, started.statusCode())
        val runSet = obj(started)["runSet"]!!.jsonPrimitive.content
        assertTrue(awaitCondition(10_000) { viewModel.scenarioRunning.value }, "the set should hold the run slot")

        val refused = post("/scenarios/run", """{"id":"$id"}""")
        assertEquals(409, refused.statusCode(), "a bare run during a set is refused, not interleaved: ${refused.body()}")

        val stop = post("/scenarios/runs/$runSet/stop", "")
        assertEquals(202, stop.statusCode(), stop.body())

        val finished = obj(get("/scenarios/runs/$runSet?wait=10000"))
        assertEquals("stopped", finished["status"]!!.jsonPrimitive.content, "$finished")
        assertTrue(
            finished["entries"]!!.jsonArray.any { it.jsonObject["state"]!!.jsonPrimitive.content == "skipped" },
            "the entries that never ran say so: $finished",
        )
        assertTrue(awaitCondition(10_000) { !viewModel.scenarioRunning.value }, "and the slot comes back")
    }

    /**
     * **Timing, measured on a real socket** — where the numbers come from two messages' capture stamps
     * rather than a fake's clock, and where they have to survive the whole path out to the report a CI
     * job reads. The venue here answers in microseconds, so the assertion is about the *shape* of the
     * measurement (present, sane, seconds in the XML) rather than a value no machine can promise.
     */
    @Test
    fun `a run reports its own duration and its expect's latency`() {
        val ran = obj(post("/scenarios/run", """{"scenario": ${scenarioJson("timed", "0")}}"""))
        assertTrue(ran["passed"]!!.jsonPrimitive.boolean, "$ran")

        val duration = ran["durationMs"]!!.jsonPrimitive.long
        assertTrue(duration > 0, "a run that sent and waited took time: $duration")

        val expect = ran["steps"]!!.jsonArray.map { it.jsonObject }.single { it["kind"]!!.jsonPrimitive.content == "expect" }
        val latency = expect["latencyMs"]!!.jsonPrimitive.long
        assertTrue(latency >= 0, "the venue answered, so the gap was measured: $latency")
        assertTrue(latency <= duration, "the reply cannot have taken longer than the run that waited for it")

        // And the same numbers reach CI, in JUnit's seconds — the other rendering of the same run.
        val asJUnit =
            obj(post("/scenarios/run", """{"format":"junit","scenario": ${scenarioJson("timed-junit", "0")}}"""))
        val junit = asJUnit["junit"]!!.jsonPrimitive.content
        assertTrue(junit.contains("<testsuite") && junit.contains(" time=\""), "the report a build step reads: $junit")
    }

    /**
     * **The repeat, over a real wire.** The same saved scenario run twice against the same session, with
     * nothing clearing the log in between — which is what a run set does twenty times over, and what a
     * tester does by clicking Run again.
     *
     * The strict verdict scans the session's whole log, and a log is not emptied between runs, so before
     * the run's own watermark reached that check the second run was red on the first run's ExecutionReport
     * every time. Under `this_run` it was red by construction: the older reply is not bindable, so it could
     * not be anything but a stray. Both runs are green, and the second says how many messages it set aside.
     */
    @Test
    fun `the same strict scenario run twice in a row is green both times`() {
        val scenario =
            """{
              "name": "strict-repeat-$runId",
              "traffic": "strict",
              "binding": "this_run",
              "steps": [
                {"type":"send","session":"CLI","raw":"35=D|11=RPT-${'$'}{uuid}|55=EUR/USD|54=1|38=100|40=1|"},
                {"type":"expect","session":"CLI","direction":"in","timeoutMs":8000,"expectation":{
                  "messageType":"8","mode":"open","fields":[
                    {"tag":35,"matcher":{"type":"exact","value":"8"}},
                    {"tag":11,"matcher":{"type":"reference","expression":"${'$'}{out.D.11}"}}
                  ]}}
              ]
            }"""
        // Saved and run by id, because that is the path a repeat takes — not a fresh inline scenario each
        // time, which would prove nothing about a log the previous iteration left behind.
        val id = obj(post("/scenarios", scenario))["id"]!!.jsonPrimitive.content

        val first = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertTrue(first["passed"]!!.jsonPrimitive.boolean, "the first run is the easy one: $first")
        assertTrue(trafficRow(first)["passed"]!!.jsonPrimitive.boolean)

        val second = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertTrue(second["passed"]!!.jsonPrimitive.boolean, "iteration 2 is judged on its own traffic: $second")
        val verdict = trafficRow(second)
        assertTrue(verdict["passed"]!!.jsonPrimitive.boolean, "the first run's reply is not the second run's surplus: $verdict")
        assertTrue(
            verdict["detail"]!!.jsonPrimitive.content.contains("already in the log when the run began were not judged"),
            "and it says what it set aside: ${verdict["detail"]}",
        )

        // The two runs are genuinely two: each minted its own ClOrdID and bound its own reply.
        assertNotEquals(boundClOrdId(first), boundClOrdId(second), "each run must judge its own ExecutionReport")
    }

    /** The strict stream verdict from a run report — the run-level row, which wears no stepId. */
    private fun trafficRow(report: kotlinx.serialization.json.JsonObject) =
        report["steps"]!!.jsonArray.map { it.jsonObject }.single { it["kind"]!!.jsonPrimitive.content == "traffic" }

    /** What tag 11 held on the ExecutionReport the run's expect actually bound. */
    private fun boundClOrdId(report: kotlinx.serialization.json.JsonObject): String =
        report["steps"]!!
            .jsonArray
            .map { it.jsonObject }
            .single { it["kind"]!!.jsonPrimitive.content == "expect" }["tags"]!!
            .jsonArray
            .map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.int == 11 }["actual"]!!
            .jsonPrimitive.content

    // ----------------------------------------------------------------- helpers

    /** The order the ACCEPTOR actually received, identified by its ClOrdID — waits for it to arrive. */
    private fun awaitOrder(clOrdId: String): FixMessage {
        assertTrue(awaitCondition(10_000) { findOrder(clOrdId) != null }, "acceptor never received order $clOrdId")
        return findOrder(clOrdId)!!
    }

    private fun findOrder(clOrdId: String): FixMessage? =
        viewModel.sessions
            .firstOrNull { it.title == "ACC" }
            ?.messages
            ?.value
            ?.filterIsInstance<FixMessage>()
            ?.firstOrNull { m ->
                m.direction == FixMessage.Direction.INCOMING && m.messageType == "D" &&
                    FixMessageHelper.wireFields(m)?.any { it.first == 11 && it.second == clOrdId } == true
            }

    /** The tags the counterparty actually received, read from ITS bytes — null wire order is a failure. */
    private fun wireTagsOf(message: FixMessage): List<Int> {
        val fields = FixMessageHelper.wireFields(message)
        assertNotNull(fields, "the acceptor must have the wire bytes it received")
        return fields.map { it.first }
    }


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

    /** See [TestPorts]: an ephemeral port closed before it is bound is a race this suite kept losing. */
    private fun freePort(): Int = TestPorts.free()

    private fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        // A torn read is 'not yet', not 'no' -- see [settled].
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(100)
        }
    }

    private fun request(method: String, path: String, body: String?): HttpResponse<String> {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val req =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
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
