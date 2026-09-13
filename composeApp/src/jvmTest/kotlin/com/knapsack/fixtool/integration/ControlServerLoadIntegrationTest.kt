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
import com.knapsack.fixtool.model.load.NBSP
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /** The loopback venue, started only by the tests that actually run a set. */
    private var venue: TestFixServer? = null

    /** QuickFIX/J keeps a static session registry per JVM, so every CompID here is this run's own. */
    private val runId = System.nanoTime().toString().takeLast(6)

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
        viewModel.disconnectAllSessions()
        venue?.stop()
        testDir.deleteRecursively()
    }

    private fun profile(resetOnLogon: Boolean = true) =
        FixConnectionProfile(
            id = "lg",
            name = "LOADGEN",
            config = FixConnectionConfig(senderCompID = "LG{n}", targetCompID = "VENUE", host = "localhost", port = "9", sessionCount = 3, resetOnLogon = resetOnLogon),
        )

    /**
     * **A profile that is merely down is work to do, not a refusal.** The route used to answer 409 with
     * "no session of 'LOADGEN' is logged on", which made every caller connect first and, for a set, know
     * which profiles to connect. The run dials them itself now, exactly as `fixtool load --set` does, and
     * the 202 is the same 202 a run over live lanes gets.
     */
    @Test
    fun `a plan whose profile is down is accepted, and the run brings it up`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.loadLogonWaitMs = 300

        val resp = post("/load", """{"profile":"LOADGEN","raw":"35=D|11=ORD-${'$'}{messageIndex}|55=EUR/USD|","count":100}""")

        assertEquals(202, resp.statusCode(), resp.body())
        assertTrue(
            awaitTrue { settled { viewModel.getProfileSessions("lg").size == 3 } },
            "the run opens every lane of the profile it names: ${viewModel.sessions.map { it.title }}",
        )
    }

    /**
     * **The refusals a connect could not answer are still refusals**, and still 409: an acceptor is the
     * far end of lanes, never their source, and no amount of dialling makes it one.
     */
    @Test
    fun `a plan on a profile that could never issue is still a 409 that names the reason`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "acc",
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "VENUE$runId",
                        targetCompID = "LG$runId",
                        socketAcceptPort = "9",
                    ),
            ),
        )

        val resp = post("/load", """{"profile":"VENUE","raw":"35=D|11=ORD-${'$'}{messageIndex}|","count":10}""")

        assertEquals(409, resp.statusCode(), resp.body())
        assertTrue(resp.body().contains("acceptor"), resp.body())
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

        assertNull(row["muted"], "a set with nothing parked reads exactly as it did")
        assertEquals(
            2,
            row["plans"]!!.jsonArray.size,
            "the row says what each phase will do, because a count says nothing about the shape of one",
        )

        val whole = obj(get("/load-sets/round-trip"))
        assertEquals("Round trip", whole["label"]!!.jsonPrimitive.content)
        assertEquals(2, whole["phases"]!!.jsonArray.size)

        assertEquals(404, get("/load-sets/nowhere").statusCode())
    }

    /**
     * **A reactive set is a chain, and a phase count cannot say so.**
     *
     * `GET /load-sets` had a name, a label, a phase count and the profiles, all of which a three-block set
     * and a three-link chain answer identically. The plan line is the one thing that tells them apart, and
     * it is the same line the dialog's phase row and the set file's own reader print.
     */
    @Test
    fun `a load set row says what each phase will do, so a reactive one reads as a chain`() {
        viewModel.loadSetStore.save(
            LoadSet(
                name = "reactive",
                label = "Reactive",
                storeAndLog = StoreAndLogOverride.FOR_LOAD,
                phases =
                    listOf(
                        LoadPhaseSpec("Ask for a quote", "Quotes", "LOADGEN", match = LoadMatch(131, 131, "S"), shape = LoadShape.Burst(200)),
                        LoadPhaseSpec(
                            "Quote it",
                            "Hits",
                            "LOADGEN",
                            match = LoadMatch(117, 117, "AI"),
                            shape = LoadShape.Triggered(200),
                            after = 1,
                        ),
                    ),
            ),
        )

        val row = obj(get("/load-sets"))["sets"]!!.jsonArray.single().jsonObject
        val plans = row["plans"]!!.jsonArray

        assertTrue(plans[0].jsonPrimitive.content.contains("×200"), plans.toString())
        assertTrue(plans[1].jsonPrimitive.content.contains("reactive, capped 200/s"), plans.toString())
        // The no-break space the row is written with reaches the API too: the plan an agent reads is the
        // sentence a person reads, and one of them wrapping badly is not a reason to print two.
        assertTrue(plans[1].jsonPrimitive.content.contains("after${NBSP}phase${NBSP}1"), plans.toString())
    }

    /** So an agent reading the list knows the set will run two of its three before it starts one. */
    @Test
    fun `a load set row says how many of its phases are muted`() {
        viewModel.loadSetStore.save(
            LoadSet(
                name = "round-trip",
                label = "Round trip",
                storeAndLog = StoreAndLogOverride.FOR_LOAD,
                phases =
                    listOf(
                        LoadPhaseSpec("Ask for a quote", "Quotes", "LOADGEN", match = LoadMatch(131, 131, "S"), shape = LoadShape.Burst(10)),
                        LoadPhaseSpec(
                            "Hit them",
                            "Hits",
                            "LOADGEN",
                            match = LoadMatch(11, 11, "8"),
                            shape = LoadShape.Burst(10),
                            muted = true,
                        ),
                    ),
            ),
        )

        val row = obj(get("/load-sets"))["sets"]!!.jsonArray.single().jsonObject

        assertEquals(2, row["phases"]!!.jsonPrimitive.int)
        assertEquals(1, row["muted"]!!.jsonPrimitive.int)
        val phases = obj(get("/load-sets/round-trip"))["phases"]!!.jsonArray
        assertNull(phases[0].jsonObject["muted"], "and the file keeps the key off the phase that is not")
        assertEquals("true", phases[1].jsonObject["muted"]!!.jsonPrimitive.content)
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

    /**
     * **`/run` is the widget's ▶ from outside**: what the window is pointed at, aiming it, and running it.
     *
     * Running goes through `/load {"set"}`, so what comes back is that door's answer — here the set's own
     * refusal, in its phase's voice — plus `configuration`, which says what the window was pointed at. That is
     * the claim worth a test: the door adds a name and no second set of rules.
     */
    @Test
    fun `the run door reads the selection, aims it, refuses what is not saved, and runs through the load door`() {
        val empty = obj(get("/run"))
        assertTrue(empty["selected"] is kotlinx.serialization.json.JsonNull, empty.toString())
        assertEquals("Nothing saved to run", empty["refusal"]!!.jsonPrimitive.content)
        assertEquals(404, post("/run", "{}").statusCode())

        listOf("alpha", "beta").forEach { name ->
            viewModel.loadSetStore.save(
                LoadSet(
                    name = name,
                    label = name.replaceFirstChar { it.uppercase() },
                    phases = listOf(LoadPhaseSpec("Ask", "nowhere", "LOADGEN", shape = LoadShape.Burst(10))),
                ),
            )
        }

        val aimed = post("/run", """{"loadSet":"beta","start":false}""")
        assertEquals(200, aimed.statusCode(), aimed.body())
        assertEquals("beta", obj(aimed)["selected"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("LOADSET:beta", viewModel.layoutState.value.selectedRunConfiguration, "the choice is remembered")

        assertEquals(404, post("/run", """{"loadSet":"nowhere"}""").statusCode())
        assertEquals(409, post("/run", """{"stop":true}""").statusCode(), "nothing of beta's is running")

        val ran = obj(post("/run", "{}"))
        assertEquals("beta", ran["configuration"]!!.jsonObject["name"]!!.jsonPrimitive.content, ran.toString())
        assertTrue(ran["error"]!!.jsonPrimitive.content.startsWith("Phase 1 · Ask:"), ran.toString())
    }

    @Test
    fun `the MCP tools are listed and the status tool lists recent runs`() {
        viewModel.loadRecordStore.write(LoadFixtures.burstReport(unmatched = 0))

        val tools = obj(post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""))
        val names = tools["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(
            "fixtool_load" in names && "fixtool_load_status" in names && "fixtool_load_sets" in names,
            names.toString(),
        )

        val text = mcpCall("fixtool_load_status", "{}")
        assertEquals(1, Json.parseToJsonElement(text).jsonObject["count"]!!.jsonPrimitive.int, text)
    }

    /**
     * **The sets tool reads what the two routes read**, so an agent can find a set and then read its phases
     * without having been told the name.
     *
     * MCP has no status codes, so the route's 404 is the tool's body, and the test says so: an agent that
     * guessed a name gets the same sentence a caller of `GET /load-sets/nowhere` does.
     */
    @Test
    fun `the sets tool lists the saved sets, reads one whole, and names one nothing answers to`() {
        viewModel.loadSetStore.save(
            LoadSet(
                name = "round-trip",
                label = "Round trip",
                seed = mapOf("run" to "\${uuid:4}"),
                storeAndLog = StoreAndLogOverride.FOR_LOAD,
                phases =
                    listOf(
                        LoadPhaseSpec(
                            "Ask for a quote",
                            "Quotes",
                            "LOADGEN",
                            match = LoadMatch(131, 131, "S"),
                            shape = LoadShape.Burst(10),
                            capture = mapOf("quoteId" to 117),
                        ),
                        LoadPhaseSpec("Hit them", "Hits", "LOADGEN", match = LoadMatch(11, 11, "8"), shape = LoadShape.Burst(10)),
                    ),
            ),
        )

        val list = mcpObj("fixtool_load_sets", "{}")
        assertEquals(1, list["count"]!!.jsonPrimitive.int, list.toString())
        val row = list["sets"]!!.jsonArray.single().jsonObject
        assertEquals("round-trip", row["name"]!!.jsonPrimitive.content)
        assertEquals("Round trip", row["label"]!!.jsonPrimitive.content)
        assertEquals(2, row["phases"]!!.jsonPrimitive.int)
        assertEquals(listOf("LOADGEN"), row["profiles"]!!.jsonArray.map { it.jsonPrimitive.content })

        val whole = mcpObj("fixtool_load_sets", """{"name":"round-trip"}""")
        assertEquals("Round trip", whole["label"]!!.jsonPrimitive.content)
        val phases = whole["phases"]!!.jsonArray
        assertEquals(2, phases.size, whole.toString())
        val quotes = phases.first().jsonObject
        assertEquals("Ask for a quote", quotes["label"]!!.jsonPrimitive.content)
        assertEquals("Quotes", quotes["template"]!!.jsonPrimitive.content)
        val captured = quotes["capture"]!!.jsonObject
        assertEquals(117, captured["quoteId"]!!.jsonPrimitive.int, whole.toString())

        val missing = mcpObj("fixtool_load_sets", """{"name":"nowhere"}""")
        assertEquals("no saved load set 'nowhere'", missing["error"]!!.jsonPrimitive.content, missing.toString())
    }

    /**
     * **An inline set with a generator seed runs, and the ids on the wire carry what it rendered.**
     *
     * `seed` on an inline body **is** the set's seed, read by the codec like every other set-level field.
     * It was also passed as the override that wins over the file, so `${uuid:4}` was written back over the
     * four hex characters the set start had just rendered: the record kept the generator's own text, every
     * ClOrdID read `ORD-${uuid:4}-1` literally, and nothing matched.
     *
     * The body carries no `storeAndLog`: an override reconnects every lane to apply it, and a lane that
     * does not come back inside ten seconds is left out of the run, which has nothing to do with the seed.
     */
    @Test
    fun `an inline set with a generator seed runs, and its ids carry the rendered value`() {
        val venue = TestFixServer().also { it.start() }
        this.venue = venue
        venue.answer = { request -> listOf(TestFixServer.executionReportFor(request)) }
        val template =
            File(testDir, "nos.fix").apply {
                writeText("8=FIX.4.4|35=D|11=ORD-\${run}-\${messageIndex}|55=EUR/USD|54=1|38=1000000|40=1|")
            }
        connectLanes(venue)

        val body =
            """
            {"phases":[
              {"label":"Send some orders","template":"${template.absolutePath}","profile":"LOADGEN",
               "match":{"requestTag":11,"replyTag":11,"replyType":"8"},
               "shape":{"kind":"burst","count":6},"settleMs":3000}],
             "seed":{"run":"${'$'}{uuid:4}"}}
            """.trimIndent()
        val accepted = obj(post("/load", body))
        val id = assertNotNull(accepted["load"]?.jsonPrimitive?.contentOrNull, "the set was refused: $accepted")
        assertEquals(1, accepted["phases"]!!.jsonPrimitive.int)

        val record = awaitFinished(id)
        val run = record["seed"]!!.jsonObject["run"]!!.jsonPrimitive.content
        assertTrue(Regex("^[0-9a-f]{4}$").matches(run), "the rendered seed, not the generator: '$run'")
        val ids = venue.applicationMessages.mapNotNull { TestFixServer.fieldValue(it, 11) }
        assertEquals(6, ids.size, "six orders on the wire, and they were: $ids")
        assertTrue(ids.all { it.startsWith("ORD-$run-") }, "every id carries the seed the record kept: $ids")
        assertEquals(6, ids.toSet().size, "each its own ClOrdID")
        assertEquals(0, record["exitCode"]!!.jsonPrimitive.int, "and every one of them was answered")
        val phase = record["phases"]!!.jsonArray.single().jsonObject
        assertEquals(6, phase["replies"]!!.jsonObject["matched"]!!.jsonPrimitive.int)
    }

    /**
     * **A set runs from a box with nothing connected**, which is the whole of it.
     *
     * Which sessions a saved set needs is the one thing its name does not say, so running one meant
     * opening the file, connecting the profiles by hand, and finding out one refusal at a time which had
     * been missed. `fixtool load --set` never had that problem — its host opens every lane the set names —
     * and this is the same contract in the window and over HTTP: not a session is up when the set is
     * posted, and it dials, logs on, issues, matches and reports.
     *
     * The lanes are **left up** afterwards, as an auto-connected scenario session is: the run borrowed
     * them, it did not own them. Close all is how a box goes back to nothing.
     */
    @Test
    fun `a set with nothing connected brings up the profile it names, and runs`() {
        val venue = TestFixServer().also { it.start() }
        this.venue = venue
        venue.answer = { request -> listOf(TestFixServer.executionReportFor(request)) }
        val template =
            File(testDir, "nos-auto.fix").apply {
                writeText("8=FIX.4.4|35=D|11=ORD-\${messageIndex}|55=EUR/USD|54=1|38=1000000|40=1|")
            }
        saveLanes(venue)
        assertEquals(0, viewModel.sessions.size, "nothing is connected, which is the point")

        val body =
            """
            {"phases":[
              {"label":"Send some orders","template":"${template.absolutePath}","profile":"LOADGEN",
               "match":{"requestTag":11,"replyTag":11,"replyType":"8"},
               "shape":{"kind":"burst","count":4},"settleMs":3000}]}
            """.trimIndent()
        val accepted = obj(post("/load", body))
        val id = assertNotNull(accepted["load"]?.jsonPrimitive?.contentOrNull, "the set was refused: $accepted")

        val record = awaitFinished(id)
        assertEquals(0, record["exitCode"]!!.jsonPrimitive.int, "every order was answered: $record")
        val phase = record["phases"]!!.jsonArray.single().jsonObject
        assertEquals(4, phase["replies"]!!.jsonObject["matched"]!!.jsonPrimitive.int)
        assertEquals(2, phase["lanes"]!!.jsonPrimitive.int, "both lanes of the profile, not the first one to answer")
        assertEquals(
            2,
            viewModel.sessions.count { it.connectionState.value == FixConnectionState.LOGGED_ON },
            "and they are still up afterwards: the run borrowed them, it did not own them",
        )
    }

    /**
     * **A set names its template the way the command line reads one**: a path, then a saved message by id
     * or by name.
     *
     * The API resolved by name only, so a set file that named a template by its id (which is what a saved
     * set holds after the editor picked one) ran under `fixtool load` and was refused over HTTP.
     */
    @Test
    fun `an inline set names its template by saved id, as the command line does`() {
        viewModel.saveConnectionProfile(profile())
        val saved =
            assertNotNull(
                viewModel.saveTemplateDirect(
                    profileId = "lg",
                    name = "NOS EUR/USD",
                    fields = listOf(SavedFixField("35", "D"), SavedFixField("11", "ORD-\${messageIndex}"), SavedFixField("55", "EUR/USD")),
                ),
            ).message

        // Nothing is logged on, so the set dials LOADGEN, finds nobody home, and ends there — which is
        // enough to prove the template resolved, and is why each is awaited before the next is posted:
        // two sets cannot hold the same lanes at once.
        viewModel.loadLogonWaitMs = 200
        val byId = post("/load", setBody(saved.id))
        assertEquals(202, byId.statusCode(), byId.body())
        awaitReleased(obj(byId)["load"]!!.jsonPrimitive.content)

        val byName = post("/load", setBody(saved.name))
        assertEquals(202, byName.statusCode(), "the name still works, as it always did: ${byName.body()}")
        awaitReleased(obj(byName)["load"]!!.jsonPrimitive.content)

        val byNothing = obj(post("/load", setBody("no-such-template")))
        assertTrue(byNothing["error"]!!.jsonPrimitive.content.contains("no template 'no-such-template'"), byNothing.toString())
    }

    private fun setBody(template: String) =
        """
        {"phases":[
          {"label":"Send some orders","template":"$template","profile":"LOADGEN",
           "match":{"requestTag":11,"replyTag":11,"replyType":"8"},
           "shape":{"kind":"burst","count":4}}]}
        """.trimIndent()

    /**
     * **Every refusal, not only the first**, and a body that could not be read at all says why.
     *
     * A set of six phases refused on four of them cost six round trips to fix one at a time, and the whole
     * reason the set is validated before anything dials is that the caller can be told everything at once.
     * A body whose `store` read "memory" rather than "MEMORY" got the one fixed sentence about labels and
     * shapes, which named nothing that was wrong with it.
     */
    @Test
    fun `a refused set lists every problem, and an unreadable body says what could not be read`() {
        viewModel.saveConnectionProfile(profile())

        val body =
            """
            {"phases":[
              {"label":"One","template":"nowhere","profile":"LOADGEN",
               "match":{"requestTag":131,"replyTag":131,"replyType":"S"},
               "shape":{"kind":"burst","count":10}},
              {"label":"Two","template":"nowhere either","profile":"NOBODY",
               "match":{"requestTag":11,"replyTag":11,"replyType":"8"},
               "shape":{"kind":"burst","count":10}}]}
            """.trimIndent()
        val refused = obj(post("/load", body))
        val problems = refused["problems"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(refused["error"]!!.jsonPrimitive.content, problems.first(), "the sentence is the first of the list")
        assertTrue(problems.size >= 2, "both phases are named at once: $problems")
        assertTrue(problems.any { it.startsWith("Phase 1 · One:") }, problems.toString())
        assertTrue(problems.any { it.startsWith("Phase 2 · Two:") }, problems.toString())

        // A body the codec cannot read at all: the store kind is an enum, and "memory" is not one of its names.
        val unreadable =
            obj(
                post(
                    "/load",
                    """{"phases":[{"label":"One","template":"x","profile":"LOADGEN","shape":{"kind":"burst","count":1}}],"storeAndLog":{"store":"memory","log":"none"}}""",
                ),
            )
        val sentence = unreadable["error"]!!.jsonPrimitive.content
        assertTrue(sentence.startsWith("could not read the set: "), sentence)
        assertTrue(sentence.contains("memory"), "and it names what it choked on: $sentence")
        assertEquals(listOf(sentence), unreadable["problems"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /**
     * **`POST /disconnect {"all": true}` drops every session and says how many, over how many profiles.**
     *
     * The toolbar's Disconnect all and this are the same action, and this is the door a script uses to put
     * a box back to nothing connected between jobs.
     */
    @Test
    fun `disconnect all drops every session and reports what it dropped`() {
        val venue = TestFixServer().also { it.start() }
        this.venue = venue
        connectLanes(venue)

        val answer = post("/disconnect", """{"all":true}""")

        assertEquals(200, answer.statusCode(), answer.body())
        val json = obj(answer)
        assertEquals("disconnecting", json["status"]!!.jsonPrimitive.content)
        assertEquals(2, json["sessions"]!!.jsonPrimitive.int, "both lanes were up: $json")
        assertEquals(1, json["profiles"]!!.jsonPrimitive.int, "and they are one profile's")

        val start = System.currentTimeMillis()

        fun stillUp() = viewModel.sessions.count { it.connectionState.value != FixConnectionState.DISCONNECTED }

        while (stillUp() > 0 && System.currentTimeMillis() - start < 20_000) Thread.sleep(100)
        assertEquals(0, stillUp(), "nothing should be left up: ${viewModel.sessions.joinToString { "${it.title}=${it.connectionState.value}" }}")
    }

    /**
     * **Nothing connected is a 200 with `sessions: 0`, not a refusal.**
     *
     * "Make sure nothing is up" is a reasonable thing for a script to say before it starts, and an error
     * there would make every such script branch on a state it does not care about.
     */
    @Test
    fun `disconnect all with nothing connected is a zero, not an error`() {
        viewModel.saveConnectionProfile(profile())

        val answer = post("/disconnect", """{"all":true}""")

        assertEquals(200, answer.statusCode(), answer.body())
        val json = obj(answer)
        assertEquals("disconnecting", json["status"]!!.jsonPrimitive.content)
        assertEquals(0, json["sessions"]!!.jsonPrimitive.int)
        assertEquals(0, json["profiles"]!!.jsonPrimitive.int)
    }

    /**
     * **A live load run refuses Disconnect all, in the sentence the toolbar's tooltip carries.**
     *
     * The one thing a disconnect would lose is a run's own measurements: the books, the records and the
     * panes all survive one, which is why nothing else about this button asks first. The venue answers
     * nothing here, so the run sits in its settle window for the length of the refusal.
     */
    @Test
    fun `disconnect all is refused while a load run is live, and works once it is stopped`() {
        val venue = TestFixServer().also { it.start() }
        this.venue = venue
        connectLanes(venue)

        val accepted = obj(post("/load", """{"profile":"LOADGEN","raw":"35=D|11=ORD-${'$'}{messageIndex}|55=EUR/USD|","count":4,"settleMs":20000}"""))
        val id = assertNotNull(accepted["load"]?.jsonPrimitive?.contentOrNull, "the run was refused: $accepted")
        awaitLive(id)

        val refused = post("/disconnect", """{"all":true}""")
        assertEquals(409, refused.statusCode(), refused.body())
        val error = obj(refused)
        assertEquals("A load run is running. Stop it first.", error["error"]!!.jsonPrimitive.content)
        assertEquals("true", error["busy"]!!.jsonPrimitive.content, "carried as a flag, so nothing has to match on the sentence")
        assertEquals(2, viewModel.sessions.count { it.connectionState.value == FixConnectionState.LOGGED_ON }, "and it dropped nothing")

        assertEquals(202, post("/loads/$id/stop", "{}").statusCode())
        awaitFinished(id)

        val answer = post("/disconnect", """{"all":true}""")
        assertEquals(200, answer.statusCode(), answer.body())
        assertEquals(2, obj(answer)["sessions"]!!.jsonPrimitive.int, "the run is over, so the lanes go down")
    }

    /**
     * **Close all, the other half of it.** A disconnect leaves the panes where they are — on purpose, the
     * logs are still readable — and this is how a box goes back to nothing after a set has left a pane per
     * lane. It disconnects on the way, so the far end gets a Logout rather than a socket that stopped
     * answering, and the counts are what the call did rather than what is left.
     */
    @Test
    fun `close all closes every pane and reports what it closed`() {
        val venue = TestFixServer().also { it.start() }
        this.venue = venue
        connectLanes(venue)

        val answer = post("/sessions/close", "{}")

        assertEquals(200, answer.statusCode(), answer.body())
        val json = obj(answer)
        assertEquals("closed", json["status"]!!.jsonPrimitive.content)
        assertEquals(2, json["sessions"]!!.jsonPrimitive.int, "both panes were open: $json")
        assertEquals(1, json["profiles"]!!.jsonPrimitive.int, "and they are one profile's")
        assertEquals(0, viewModel.sessions.size, "nothing is left: ${viewModel.sessions.map { it.title }}")

        // Nothing open is a zero rather than an error, for the reason `disconnect all` is: "make sure the
        // box is empty" is a reasonable thing for a script to say before it starts.
        assertEquals(0, obj(post("/sessions/close", "{}"))["sessions"]!!.jsonPrimitive.int)
    }

    /** A live run refuses it, in the words Disconnect all is refused with — it would lose the same and more. */
    @Test
    fun `close all is refused while a load run is live`() {
        val venue = TestFixServer().also { it.start() }
        this.venue = venue
        connectLanes(venue)

        val accepted = obj(post("/load", """{"profile":"LOADGEN","raw":"35=D|11=ORD-${'$'}{messageIndex}|55=EUR/USD|","count":4,"settleMs":20000}"""))
        val id = assertNotNull(accepted["load"]?.jsonPrimitive?.contentOrNull, "the run was refused: $accepted")
        awaitLive(id)

        val refused = post("/sessions/close", "{}")

        assertEquals(409, refused.statusCode(), refused.body())
        assertEquals("A load run is running. Stop it first.", obj(refused)["error"]!!.jsonPrimitive.content)
        assertEquals(2, viewModel.sessions.size, "and it closed nothing")

        assertEquals(202, post("/loads/$id/stop", "{}").statusCode())
        awaitFinished(id)
    }

    /**
     * **The set has let go of its lanes.** A set that ends by refusing writes no record, so waiting on the
     * claim rather than on a status is the only wait that ends for both outcomes.
     */
    private fun awaitReleased(id: String) {
        assertTrue(awaitTrue { !viewModel.isLoadRunning(id) }, "the set never released its lanes")
    }

    /** A polled predicate with the timeout every wait here uses. */
    private fun awaitTrue(timeoutMs: Long = 20_000, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    /**
     * The state Disconnect all reads: a live record whose id the claim still holds. Both are set on the
     * runner's first tick, and the claim is what outlives the record, so waiting on the pair is waiting
     * on exactly the condition the route asks about.
     */
    private fun awaitLive(id: String) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 20_000) {
            if (viewModel.activeLoadRun.value?.id == id && viewModel.isLoadRunning(id)) return
            Thread.sleep(50)
        }
        throw AssertionError("the run never went live: active=${viewModel.activeLoadRun.value?.id}, claimed=${viewModel.isLoadRunning(id)}")
    }

    /** Two lanes of LOADGEN, dialling [venue] and logged on, which is what a load set needs to start. */
    private fun connectLanes(venue: TestFixServer) {
        val live = saveLanes(venue)
        viewModel.connectProfile(live.id, live)
        val start = System.currentTimeMillis()

        fun loggedOn() = viewModel.getProfileSessions(live.id).count { it.connectionState.value == FixConnectionState.LOGGED_ON }

        while (loggedOn() < 2 && System.currentTimeMillis() - start < 25_000) Thread.sleep(100)
        val states = viewModel.sessions.joinToString { "${it.title}=${it.connectionState.value}" }
        assertEquals(2, loggedOn(), "two lanes should log on: $states")
    }

    /** The same profile, saved and left down: what a box looks like the morning after. */
    private fun saveLanes(venue: TestFixServer): FixConnectionProfile {
        val live =
            FixConnectionProfile(
                id = "lg",
                name = "LOADGEN",
                config =
                    FixConnectionConfig(
                        senderCompID = "LG{nn}$runId",
                        targetCompID = "VENUE$runId",
                        host = "localhost",
                        socketConnectHost = "localhost",
                        port = venue.port.toString(),
                        beginString = "FIX.4.4",
                        sessionCount = 2,
                        heartBtInt = "30",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "store").absolutePath,
                        fileLogPath = File(testDir, "log").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(live)
        return live
    }

    /**
     * The record once the set is over, polled the way an agent polls it.
     *
     * A record the runner has not written its first tick for yet answers 404, so "not there" is "not yet"
     * and only a status ends the wait.
     */
    private fun awaitFinished(id: String): JsonObject {
        val start = System.currentTimeMillis()
        var last = "never answered"
        while (System.currentTimeMillis() - start < 40_000) {
            val record = obj(get("/loads/$id"))
            // The record says DONE; only the one-line row on `GET /loads` lowercases its status.
            when (record["status"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "done", "stopped" -> return record
                else -> last = record.toString()
            }
            Thread.sleep(200)
        }
        throw AssertionError("the set did not finish within 40s, last answer: $last")
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

    /** One tool call whose answer is a JSON object, which every tool read here returns. */
    private fun mcpObj(name: String, args: String) = Json.parseToJsonElement(mcpCall(name, args)).jsonObject

    private fun mcpCall(name: String, args: String): String {
        val body = """{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        return obj(post("/mcp", body))["result"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    }
}
