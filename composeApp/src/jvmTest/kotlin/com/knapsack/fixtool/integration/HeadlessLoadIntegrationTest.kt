package com.knapsack.fixtool.integration

import com.knapsack.fixtool.headless.HeadlessRun
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.service.load.LoadRecordStore
import com.knapsack.fixtool.service.load.LoadReportCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **`fixtool load` against a loopback venue**, end to end: five lanes log on, the burst leaves, the venue
 * answers or does not, the record is written, the exit code says what happened.
 *
 * These are the acceptance criteria of #43 with a socket under them. The matcher's own criteria are
 * exact in `StampMatcherTest`; here what is tested is that a real QuickFIX/J session's stamps reach it.
 */
class HeadlessLoadIntegrationTest {
    private lateinit var server: TestFixServer
    private lateinit var home: File
    private val runId = System.nanoTime().toString().takeLast(5)

    @Before
    fun setUp() {
        server = TestFixServer()
        server.start()
        home = File(System.getProperty("java.io.tmpdir"), "fixtool-load-$runId").apply { mkdirs() }
        writeProfiles(resetOnLogon = true)
        File(home, "nos.fix").writeText("8=FIX.4.4|35=D|11=ORD-\${run}-\${messageIndex}|55=EUR/USD|54=1|38=1000000|40=2|44=1.0842|59=0|60=\${utcnow}|\n")
        File(home, "quotes.fix").writeText("35=R|131=Q-\${run}-\${messageIndex}|146=1|55=EUR/USD|54=1|38=1000000|\n")
        File(
            home,
            "hits.fix",
        ).writeText(
            "35=AJ|693=R-\${run}-\${messageIndex}|694=1|117=Q-\${run}-\${messageIndex}|" +
                "11=H-\${run}-\${messageIndex}|55=EUR/USD|54=1|38=1000000|44=1.09010|\n",
        )
    }

    /** A hit that names the quote by the id the venue minted, which no seed can derive. */
    private fun writeCaptureTemplate() {
        File(
            home,
            "hits-by-capture.fix",
        ).writeText(
            "35=AJ|693=R-\${run}-\${messageIndex}|694=1|117=\${quoteId}|" +
                "11=H-\${run}-\${messageIndex}|55=EUR/USD|54=1|38=1000000|44=\${offer}|\n",
        )
    }

    /**
     * The same two phases, but phase 2 addresses what phase 1 was given rather than what it chose.
     *
     * [reactive] makes phase 2 fire as phase 1's quotes land rather than after phase 1's whole settle
     * window, which is the same file the dialog writes and the only thing `--set` needs to run one.
     */
    private fun writeCapturingSet(name: String = "round-trip", count: Int = 20, reactive: Boolean = false) {
        writeCaptureTemplate()
        val second =
            if (reactive) {
                """"shape": { "kind": "triggered" }, "after": 1"""
            } else {
                """"shape": { "kind": "burst", "count": $count }"""
            }
        File(home, "load-sets").mkdirs()
        File(home, "load-sets/$name.json").writeText(
            """
            {
              "schema": 2,
              "name": "$name",
              "label": "Round trip",
              "seed": $seedGeneratorJson,
              "storeAndLog": { "store": "MEMORY", "log": "NONE" },
              "onFailure": "CONTINUE",
              "phases": [
                { "label": "Ask for a quote", "template": "${File(home, "quotes.fix").absolutePath}",
                  "profile": "LOADGEN",
                  "match": { "requestTag": 131, "replyTag": 131, "replyType": "S" },
                  "shape": { "kind": "burst", "count": $count }, "settleMs": 10000,
                  "capture": { "quoteId": 117, "offer": 133 } },
                { "label": "Hit them", "template": "${File(home, "hits-by-capture.fix").absolutePath}",
                  "profile": "LOADGEN",
                  "match": { "requestTag": 11, "replyTag": 11, "replyType": "8" },
                  $second, "settleMs": 10000 }
              ]
            }
            """.trimIndent(),
        )
    }

    /**
     * A venue that mints an id the client cannot derive, and books a hit at the price it quoted.
     *
     * [withoutQuoteIdFor] answers one QuoteRequest without a 117 at all, which is how a capture goes
     * missing and a later phase finds it has nothing to address.
     */
    private fun opaqueVenue(withoutQuoteIdFor: String? = null): (String) -> List<TestFixServer.Reply> =
        { request ->
            when (TestFixServer.fieldValue(request, 35)) {
                "R" -> {
                    val reqId = TestFixServer.fieldValue(request, 131) ?: "?"
                    val quoteId = "OPAQUE-" + java.util.UUID.randomUUID().toString().take(8)
                    listOf(
                        TestFixServer.Reply(
                            "S",
                            listOfNotNull(
                                if (reqId == withoutQuoteIdFor) null else "117=$quoteId",
                                "131=$reqId",
                                "55=EUR/USD",
                                "132=1.09000",
                                "133=1.09010",
                                "134=1000000",
                                "135=1000000",
                                "15=EUR",
                            ),
                        ),
                    )
                }
                "AJ" -> listOf(TestFixServer.executionReportFor(request, "2"))
                else -> emptyList()
            }
        }

    /** A two-phase set on disk, exactly as `load-sets/<name>.json` holds one. */
    private fun writeSet(name: String = "round-trip", onFailure: String = "STOP", seed: String = seedGeneratorJson) {
        File(home, "load-sets").mkdirs()
        File(home, "load-sets/$name.json").writeText(
            """
            {
              "schema": 1,
              "name": "$name",
              "label": "Round trip",
              "seed": $seed,
              "storeAndLog": { "store": "MEMORY", "log": "NONE" },
              "onFailure": "$onFailure",
              "phases": [
                { "label": "Ask for a quote", "template": "${File(home, "quotes.fix").absolutePath}",
                  "profile": "LOADGEN",
                  "match": { "requestTag": 131, "replyTag": 131, "replyType": "S" },
                  "shape": { "kind": "burst", "count": 20 }, "settleMs": 10000 },
                { "label": "Hit them", "template": "${File(home, "hits.fix").absolutePath}",
                  "profile": "LOADGEN",
                  "match": { "requestTag": 11, "replyTag": 11, "replyType": "8" },
                  "shape": { "kind": "burst", "count": 20 }, "settleMs": 10000 }
              ]
            }
            """.trimIndent(),
        )
    }

    /** A Quote for a QuoteRequest and an ExecutionReport for a QuoteResponse, which is the RFQ round trip. */
    private fun quoteThenBook(swallowQuoteFor: String? = null): (String) -> List<TestFixServer.Reply> =
        { request ->
            when (TestFixServer.fieldValue(request, 35)) {
                "R" -> {
                    val reqId = TestFixServer.fieldValue(request, 131) ?: "?"
                    if (reqId.endsWith(swallowQuoteFor ?: "\u0000")) {
                        emptyList()
                    } else {
                        listOf(
                            TestFixServer.Reply(
                                "S",
                                listOf(
                                    "117=QID-$reqId", "131=$reqId", "55=EUR/USD", "132=1.09000", "133=1.09010",
                                    "134=1000000", "135=1000000", "15=EUR",
                                ),
                            ),
                        )
                    }
                }
                "AJ" -> listOf(TestFixServer.executionReportFor(request, "2"))
                else -> emptyList()
            }
        }

    @After
    fun tearDown() {
        server.stop()
        home.deleteRecursively()
    }

    /** A set's seed value may be a generator, rendered once when the set starts and then frozen. */
    private val seedGeneratorJson = "{ \"run\": \"\${uuid:4}\" }"

    private fun writeProfiles(resetOnLogon: Boolean) {
        File(home, "connection_profiles.json").writeText(
            """
            {"profiles":[{
              "id":"lg-$runId","name":"LOADGEN",
              "config":{
                "senderCompID":"LG{n}$runId","targetCompID":"VENUE$runId",
                "host":"localhost","port":"${server.port}","socketConnectHost":"localhost",
                "beginString":"FIX.4.4","autoReconnect":false,"resetOnLogon":$resetOnLogon,
                "sessionCount":5,"heartBtInt":"30",
                "fileStorePath":"${File(home, "store").absolutePath}",
                "fileLogPath":"${File(home, "log").absolutePath}"
              }},{
              "id":"dc-$runId","name":"DROPCOPY",
              "config":{
                "senderCompID":"DC$runId","targetCompID":"VENUE$runId",
                "host":"localhost","port":"${server.port}","socketConnectHost":"localhost",
                "beginString":"FIX.4.4","autoReconnect":false,"resetOnLogon":true,"heartBtInt":"30",
                "fileStorePath":"${File(home, "store").absolutePath}",
                "fileLogPath":"${File(home, "log").absolutePath}"
              }}]}
            """.trimIndent(),
        )
    }

    /** The set form has no template positional and no --profile: the file names both, per phase. */
    private fun loadSet(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = HeadlessRun.execute(arrayOf("load", "--home", home.absolutePath, *args), out, err)
        return Triple(code, out.toString(), err.toString())
    }

    private fun load(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = HeadlessRun.execute(arrayOf("load", File(home, "nos.fix").absolutePath, "--profile", "LOADGEN", "--home", home.absolutePath, *args), out, err)
        return Triple(code, out.toString(), err.toString())
    }

    /**
     * The record's one phase, and the assertion that it *is* one phase.
     *
     * `load.json` is a set with one phase, so that a load set is the same file, the same reader and the
     * same Compare rather than a second of each — see [com.knapsack.fixtool.model.load.LoadRecord].
     */
    private fun onlyRecord(): Pair<String, kotlinx.serialization.json.JsonObject> {
        val dirs = File(home, "loads").listFiles { f -> f.isDirectory }.orEmpty()
        assertEquals(1, dirs.size, "one record for one run: ${dirs.map { it.name }}")
        val json = Json.parseToJsonElement(File(dirs.single(), LoadRecordStore.REPORT_FILE).readText()).jsonObject
        assertEquals(LoadReportCodec.SCHEMA, json["schema"]!!.jsonPrimitive.int)
        val phases = json["phases"]!!.jsonArray
        assertEquals(1, phases.size, "a run is a set with one phase")
        return dirs.single().name to phases.single().jsonObject
    }

    @Test
    fun `a burst answered in full exits zero and leaves a small record`() {
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request)) }

        val (code, out, err) = load("--count", "200", "--seed", "run=a1", "--store", "memory", "--log", "none", "--settle", "20s")

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("issued             200   requested 200 · handed to engine 200 · left socket 200"), out)
        assertTrue(out.contains("matched            200"), out)
        assertTrue(out.contains("COMPLETE     200 of 200 answered"), out)
        assertTrue(err.contains("opening 5 lanes of 'LOADGEN' (memory store, no log)"), err)
        assertEquals(200, server.applicationMessages.size)
        assertTrue(server.applicationMessages.all { TestFixServer.fieldValue(it, 11)!!.startsWith("ORD-a1-") }, "the seed and the index reached the wire")
        assertEquals(200, server.applicationMessages.map { TestFixServer.fieldValue(it, 11) }.toSet().size, "every message its own ClOrdID")
        assertTrue(server.logons.map { it.first }.toSet().size == 5, "five distinct lanes logged on: ${server.logons}")

        val (id, json) = onlyRecord()
        assertEquals("DONE", json["status"]!!.jsonPrimitive.content)
        assertEquals(200L, json["replies"]!!.jsonObject["matched"]!!.jsonPrimitive.long)
        assertEquals(0, json["verdict"]!!.jsonObject["exitCode"]!!.jsonPrimitive.int)
        assertEquals(listOf(11, 60), json["template"]!!.jsonObject["perMessageTags"]!!.jsonArray.map { it.jsonPrimitive.int })
        val dir = File(home, "loads/$id")
        assertEquals(100, File(dir, LoadReport.Evidence.forPhase(1).specimens).readLines().size, "fifty pairs, request then reply")
        assertEquals("", File(dir, LoadReport.Evidence.forPhase(1).unmatched).readText())
        assertTrue(!File(home, "store").exists(), "a memory store writes nothing under store/")
    }

    @Test
    fun `two swallowed orders exit one and are named in the summary, the JSON and the JUnit`() {
        server.answer = { request ->
            val id = TestFixServer.fieldValue(request, 11)
            if (id == "ORD-b2-7" || id == "ORD-b2-13") emptyList() else listOf(TestFixServer.executionReportFor(request))
        }
        val junit = File(home, "reports/load.xml")
        val jsonFile = File(home, "reports/load.json")

        val (code, out, err) = load("--count", "30", "--seed", "run=b2", "--settle", "2s", "--junit", junit.absolutePath, "--json", jsonFile.absolutePath)

        assertEquals(1, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("unmatched            2   ORD-b2-7 (lane 2) · ORD-b2-13 (lane 3)"), out)
        assertTrue(out.contains("UNMATCHED    2 of 30 unanswered within 2s"), out)
        assertTrue(err.contains("settle closed with 2 pending"), err)
        // --json writes the record, the same shape as the file on disk and GET /loads/<id>, so the
        // unanswered ids live under the phase rather than at the top level.
        val json = Json.parseToJsonElement(jsonFile.readText()).jsonObject
        assertEquals(LoadReportCodec.SCHEMA, json["schema"]!!.jsonPrimitive.int)
        val phase = json["phases"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("ORD-b2-7", "ORD-b2-13"), phase["unmatched"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(1, json["exitCode"]!!.jsonPrimitive.int, "the set-level exit code is what a build reads now")
        val xml = junit.readText()
        assertTrue(xml.contains("failures=\"1\""), xml)
        assertTrue(xml.contains("2 of 30 unanswered within 2s: ORD-b2-7 (lane 2), ORD-b2-13 (lane 3)"), xml)
        val (id, _) = onlyRecord()
        val wire = File(File(home, "loads/$id"), LoadReport.Evidence.forPhase(1).unmatched).readLines()
        assertEquals(2, wire.size)
        assertTrue(wire[0].contains("11=ORD-b2-7"), wire[0])
    }

    @Test
    fun `a venue that answers twice reports duplicates and stays complete`() {
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request, "0"), TestFixServer.executionReportFor(request, "2")) }

        val (code, out, err) = load("--count", "40", "--seed", "run=c3", "--settle", "5s")

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("matched             40"), out)
        assertTrue(out.contains("duplicates          40"), out)
    }

    @Test
    fun `a rate run issues what the schedule asked and reports it held`() {
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request)) }

        val (code, out, err) = load("--rate", "100/s", "--for", "3s", "--seed", "run=d4", "--settle", "5s")

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("issued             300   requested 300"), out)
        assertTrue(out.contains("rate         100/s requested · held"), out)
        val (_, json) = onlyRecord()
        assertEquals("HELD", json["verdict"]!!.jsonObject["rate"]!!.jsonPrimitive.content)
        assertEquals("rate", json["shape"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a reply routed to a listen-only session is matched, not a stray plus an unmatched`() {
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request, toClient = "DC$runId")) }

        val (code, out, err) = load("--count", "25", "--seed", "run=e5", "--listen", "DROPCOPY", "--settle", "5s")

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("matched             25"), out)
        assertTrue(!out.contains("strays"), "nothing was a stray: $out")
        assertTrue(server.logons.any { it.first == "DC$runId" }, "the drop-copy session logged on: ${server.logons}")
    }

    @Test
    fun `a memory store without Reset on Logon exits two with the config's sentence`() {
        writeProfiles(resetOnLogon = false)

        val (code, _, err) = load("--count", "10", "--seed", "run=f6", "--store", "memory")

        assertEquals(HeadlessRun.EXIT_USAGE, code, err)
        assertTrue(err.contains("Reset on Logon"), err)
        assertEquals(0, server.logonCount.get(), "refused before any lane dialled")
    }

    @Test
    fun `a template that reads a name nothing seeds exits two before a lane opens`() {
        val (code, _, err) = load("--count", "10")

        assertEquals(HeadlessRun.EXIT_USAGE, code, err)
        assertTrue(err.contains("\${run}"), err)
        assertTrue(err.contains("--seed run="), err)
        assertEquals(0, server.logonCount.get(), err)
    }

    /**
     * The set's exit criteria with a socket under them: two phases in order, one record, one `<testsuites>`.
     */
    @Test
    fun `a two-phase set runs both phases under one seed and writes one record`() {
        server.answer = quoteThenBook()
        writeSet()
        val junit = File(home, "reports/set.xml")
        val jsonFile = File(home, "reports/set.json")

        val (code, out, err) =
            loadSet("--set", "round-trip", "--seed", "run=cli1", "--junit", junit.absolutePath, "--json", jsonFile.absolutePath)

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("set          Round trip · 2 phases · LOADGEN · 5 lanes · memory store, no log"), out)
        assertTrue(out.contains("seed         run=cli1"), out)
        assertTrue(out.contains("policy       stop when a phase does not pass"), out)
        assertTrue(out.contains("1 · Ask for a quote"), out)
        assertTrue(out.contains("2 · Hit them"), out)
        assertTrue(out.contains("PASSED       2 passed"), out)
        assertEquals(1, out.split("records: ").size - 1, "the record path is printed once")
        assertTrue(err.contains("fixtool: phase 1 of 2 · Ask for a quote"), err)
        assertTrue(err.contains("fixtool: phase 2 of 2 · Hit them"), err)

        assertEquals(20, server.applicationMessages.count { TestFixServer.fieldValue(it, 35) == "R" })
        val hits = server.applicationMessages.filter { TestFixServer.fieldValue(it, 35) == "AJ" }
        assertEquals(20, hits.size)
        assertTrue(hits.all { TestFixServer.fieldValue(it, 117) == "Q-cli1-" + TestFixServer.fieldValue(it, 11)!!.removePrefix("H-cli1-") })

        val record = Json.parseToJsonElement(jsonFile.readText()).jsonObject
        assertEquals(LoadReportCodec.SCHEMA, record["schema"]!!.jsonPrimitive.int)
        assertEquals("round-trip", record["set"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(mapOf("run" to "cli1"), record["seed"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals("PASSED", record["verdict"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        val phases = record["phases"]!!.jsonArray
        assertEquals(2, phases.size)
        assertEquals(listOf(20L, 20L), phases.map { it.jsonObject["replies"]!!.jsonObject["matched"]!!.jsonPrimitive.long })

        val xml = junit.readText()
        assertTrue(xml.contains("<testsuites name=\"load set: Round trip\""), xml)
        assertTrue(xml.contains("<testsuite name=\"load: 1 · Ask for a quote\""), xml)
        assertTrue(xml.contains("<testsuite name=\"load: 2 · Hit them\""), xml)
        assertTrue(xml.contains("classname=\"load.round-trip.2\""), xml)

        val dirs = File(home, "loads").listFiles { f -> f.isDirectory }.orEmpty()
        assertEquals(1, dirs.size, "one record for the whole set: ${dirs.map { it.name }}")
        assertTrue(File(dirs.single(), LoadReport.Evidence.forPhase(1).specimens).isFile)
        assertTrue(File(dirs.single(), LoadReport.Evidence.forPhase(2).specimens).isFile)
    }

    @Test
    fun `a phase that did not pass skips the rest, exits one, and phase two never dials`() {
        server.answer = quoteThenBook(swallowQuoteFor = "-7")
        writeSet()
        val junit = File(home, "reports/set.xml")

        val (code, out, err) = loadSet("--set", "round-trip", "--seed", "run=cli2", "--junit", junit.absolutePath)

        assertEquals(1, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("UNMATCHED    1 of 20 unanswered"), out)
        assertTrue(out.contains("SKIPPED      phase 1 did not pass and the set stops on failure"), out)
        assertTrue(out.contains("FAILED       phase 1 · 1 failed, 1 skipped"), out)
        assertEquals(0, server.applicationMessages.count { TestFixServer.fieldValue(it, 35) == "AJ" }, "phase 2 never dialled")
        val xml = junit.readText()
        assertTrue(xml.contains("<skipped message=\"phase 1 did not pass and the set stops on failure\"/>"), xml)
    }

    @Test
    fun `under CONTINUE the set runs every phase and still exits one`() {
        server.answer = quoteThenBook(swallowQuoteFor = "-7")
        writeSet(onFailure = "CONTINUE")

        val (code, out, err) = loadSet("--set", "round-trip", "--seed", "run=cli3")

        assertEquals(1, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("policy       carry on, and report every phase"), out)
        assertTrue(out.contains("FAILED       phase 1 · 1 passed, 1 failed"), out)
        assertEquals(20, server.applicationMessages.count { TestFixServer.fieldValue(it, 35) == "AJ" }, "phase 2 ran anyway")
    }

    @Test
    fun `a set nothing answers to, and one whose phase reads a name nothing seeds, exit two`() {
        val missing = loadSet("--set", "nowhere")
        assertEquals(HeadlessRun.EXIT_USAGE, missing.first, missing.third)
        assertTrue(missing.third.contains("no load set 'nowhere'"), missing.third)

        writeSet(seed = "{}")
        val unseeded = loadSet("--set", "round-trip")
        assertEquals(HeadlessRun.EXIT_USAGE, unseeded.first, unseeded.third)
        assertTrue(unseeded.third.contains("Phase 1 · Ask for a quote:"), unseeded.third)
        assertTrue(unseeded.third.contains("Pass --seed run=…"), unseeded.third)
        assertEquals(0, server.logonCount.get(), "nothing dialled")
    }

    /**
     * **Part 2's exit criterion with a socket under it**: phase 2 addresses ids it could not have derived,
     * because phase 1 kept them at the index phase 2 counts by.
     */
    @Test
    fun `a phase addresses the opaque ids an earlier phase was given`() {
        server.answer = opaqueVenue()
        writeCapturingSet()
        val jsonFile = File(home, "reports/capture.json")

        val (code, out, err) = loadSet("--set", "round-trip", "--seed", "run=cap1", "--json", jsonFile.absolutePath)

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("captured            20   quoteId, offer on 20 replies"), out)
        assertTrue(out.contains("PASSED       2 passed"), out)

        val hits = server.applicationMessages.filter { TestFixServer.fieldValue(it, 35) == "AJ" }
        assertEquals(20, hits.size)
        assertTrue(hits.all { TestFixServer.fieldValue(it, 117)!!.startsWith("OPAQUE-") }, "the id came off the reply")
        assertEquals(20, hits.mapNotNull { TestFixServer.fieldValue(it, 117) }.toSet().size, "every hit its own quote")
        assertTrue(hits.all { TestFixServer.fieldValue(it, 44) == "1.09010" }, "and at the price the quote carried")

        val record = Json.parseToJsonElement(jsonFile.readText()).jsonObject
        val phaseOne = record["phases"]!!.jsonArray.first().jsonObject
        val capture = phaseOne["capture"]!!.jsonObject
        assertEquals(117, capture["names"]!!.jsonObject["quoteId"]!!.jsonPrimitive.int)
        assertEquals(20, capture["captured"]!!.jsonObject["quoteId"]!!.jsonPrimitive.int)
        val dirs = File(home, "loads").listFiles { f -> f.isDirectory }.orEmpty()
        val captured = File(dirs.single(), LoadReport.Evidence.forPhase(1, captured = true).captured!!).readLines()
        assertEquals(20, captured.filter { it.isNotBlank() }.size, "one line per index that carries a value")
        assertTrue(captured.first().contains("quoteId=OPAQUE-"), captured.first())
    }

    /**
     * **A reactive set runs from the command line with no flag of its own.**
     *
     * The shape is in the file, so `--set` is the whole grammar: there is no `--reactive` to pass and no
     * `--count` that would mean anything, because a reactive phase's count belongs to the phase it reacts
     * to. What the run has to show for it is the chain, which is the figure a staged set cannot produce.
     */
    @Test
    fun `a reactive phase in a set file runs from the command line, and reports its chain`() {
        server.answer = opaqueVenue()
        writeCapturingSet(reactive = true)
        val jsonFile = File(home, "reports/reactive.json")

        val (code, out, err) = loadSet("--set", "round-trip", "--seed", "run=rx1", "--json", jsonFile.absolutePath)

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("PASSED       2 passed"), out)
        val hits = server.applicationMessages.filter { TestFixServer.fieldValue(it, 35) == "AJ" }
        assertEquals(20, hits.size, "one hit per quote the venue answered")
        assertTrue(hits.all { TestFixServer.fieldValue(it, 117)!!.startsWith("OPAQUE-") }, "the id came off the reply")

        val phases = Json.parseToJsonElement(jsonFile.readText()).jsonObject["phases"]!!.jsonArray
        val reacting = phases[1].jsonObject
        assertEquals("triggered", reacting["shape"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        val issue = reacting["issue"]!!.jsonObject
        assertEquals(20, issue["requested"]!!.jsonPrimitive.int, "the trigger's count")
        assertNotNull(reacting["chain"], "the phase that ends a chain reports one")
    }

    /**
     * A requested message that was not sent fails the phase. The bar is "every requested message
     * answered", so a hole the tool could not fill is not a smaller proof.
     */
    @Test
    fun `a capture the venue never sent is a message never sent, named, and exits one`() {
        server.answer = opaqueVenue(withoutQuoteIdFor = "Q-cap2-7")
        writeCapturingSet()

        val (code, out, err) = loadSet("--set", "round-trip", "--seed", "run=cap2")

        assertEquals(1, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("captured            20   quoteId on 19 · offer on 20"), out)
        assertTrue(out.contains("not sent             1   no quoteId for 7"), out)
        assertTrue(out.contains("INCOMPLETE   1 of 20 not sent: no quoteId for index 7"), out)
        assertTrue(out.contains("FAILED       phase 2"), out)
        assertEquals(19, server.applicationMessages.count { TestFixServer.fieldValue(it, 35) == "AJ" }, "the other nineteen went")
    }

    @Test
    fun `a set whose phase reads a capture no earlier phase keeps is refused before a lane dials`() {
        writeCapturingSet()
        File(home, "load-sets/round-trip.json").writeText(
            File(home, "load-sets/round-trip.json").readText().replace(
                "\"capture\": { \"quoteId\": 117, \"offer\": 133 } }",
                "\"capture\": { \"offer\": 133 } }",
            ),
        )

        val (code, _, err) = loadSet("--set", "round-trip")

        assertEquals(HeadlessRun.EXIT_USAGE, code, err)
        assertTrue(err.contains("Phase 2 · Hit them:"), err)
        assertTrue(err.contains("\${quoteId}"), err)
        assertEquals(0, server.logonCount.get(), "nothing dialled")
    }

    @Test
    fun `the old --set spelling still seeds, and says to write --seed`() {
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request)) }

        val (code, out, err) = load("--count", "10", "--set", "run=old1", "--store", "memory", "--log", "none", "--settle", "10s")

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(err.contains("--set <k>=<v> now seeds through --seed <k>=<v>"), err)
        assertTrue(server.applicationMessages.all { TestFixServer.fieldValue(it, 11)!!.startsWith("ORD-old1-") }, "it still seeded")
    }

    @Test
    fun `a saved message is found by name under the issuing profile`() {
        File(home, "saved_messages.json").writeText(
            """
            {"messages":[{"name":"NOS by name","userTags":["lg-$runId"],
              "fields":[{"tag":"35","value":"D"},{"tag":"11","value":"BYNAME-${'$'}{messageIndex}"},{"tag":"55","value":"EUR/USD"},
                        {"tag":"58","value":"dropped","excluded":true}]}]}
            """.trimIndent(),
        )
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request)) }
        val out = StringBuilder()
        val err = StringBuilder()

        val code = HeadlessRun.execute(arrayOf("load", "NOS by name", "--profile", "LOADGEN", "--home", home.absolutePath, "--count", "12", "--settle", "5s"), out, err)

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(server.applicationMessages.all { TestFixServer.fieldValue(it, 11)!!.startsWith("BYNAME-") }, server.applicationMessages.take(2).toString())
        assertTrue(server.applicationMessages.none { it.contains("58=dropped") }, "an excluded row stays out of the wire")
    }

    @Test
    fun `the help and a bad argument exit as fixtool run does`() {
        val out = StringBuilder()
        val err = StringBuilder()
        assertEquals(0, HeadlessRun.execute(arrayOf("load", "--help"), out, err))
        assertTrue(out.contains("fixtool load <template> --profile <name>"), out.toString())
        assertEquals(HeadlessRun.EXIT_USAGE, HeadlessRun.execute(arrayOf("load", "x", "--profile", "p", "--count", "nope"), StringBuilder(), StringBuilder()))
        assertNotNull(HeadlessRun.handles(arrayOf("load")))
        assertTrue(HeadlessRun.handles(arrayOf("load")))
    }
}
