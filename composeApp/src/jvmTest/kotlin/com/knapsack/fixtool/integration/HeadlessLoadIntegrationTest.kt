package com.knapsack.fixtool.integration

import com.knapsack.fixtool.headless.HeadlessRun
import com.knapsack.fixtool.service.load.LoadRecordStore
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
    }

    @After
    fun tearDown() {
        server.stop()
        home.deleteRecursively()
    }

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

    private fun load(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = HeadlessRun.execute(arrayOf("load", File(home, "nos.fix").absolutePath, "--profile", "LOADGEN", "--home", home.absolutePath, *args), out, err)
        return Triple(code, out.toString(), err.toString())
    }

    private fun onlyRecord(): Pair<String, kotlinx.serialization.json.JsonObject> {
        val dirs = File(home, "loads").listFiles { f -> f.isDirectory }.orEmpty()
        assertEquals(1, dirs.size, "one record for one run: ${dirs.map { it.name }}")
        val json = Json.parseToJsonElement(File(dirs.single(), LoadRecordStore.REPORT_FILE).readText()).jsonObject
        return dirs.single().name to json
    }

    @Test
    fun `a burst answered in full exits zero and leaves a small record`() {
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request)) }

        val (code, out, err) = load("--count", "200", "--set", "run=a1", "--store", "memory", "--log", "none", "--settle", "20s")

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
        assertEquals(100, File(dir, LoadRecordStore.SPECIMENS_FILE).readLines().size, "fifty pairs, request then reply")
        assertEquals("", File(dir, LoadRecordStore.UNMATCHED_FILE).readText())
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

        val (code, out, err) = load("--count", "30", "--set", "run=b2", "--settle", "2s", "--junit", junit.absolutePath, "--json", jsonFile.absolutePath)

        assertEquals(1, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("unmatched            2   ORD-b2-7 (lane 2) · ORD-b2-13 (lane 3)"), out)
        assertTrue(out.contains("UNMATCHED    2 of 30 unanswered within 2s"), out)
        assertTrue(err.contains("settle closed with 2 pending"), err)
        val json = Json.parseToJsonElement(jsonFile.readText()).jsonObject
        assertEquals(listOf("ORD-b2-7", "ORD-b2-13"), json["unmatched"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val xml = junit.readText()
        assertTrue(xml.contains("failures=\"1\""), xml)
        assertTrue(xml.contains("2 of 30 unanswered within 2s: ORD-b2-7 (lane 2), ORD-b2-13 (lane 3)"), xml)
        val (id, _) = onlyRecord()
        val wire = File(File(home, "loads/$id"), LoadRecordStore.UNMATCHED_FILE).readLines()
        assertEquals(2, wire.size)
        assertTrue(wire[0].contains("11=ORD-b2-7"), wire[0])
    }

    @Test
    fun `a venue that answers twice reports duplicates and stays complete`() {
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request, "0"), TestFixServer.executionReportFor(request, "2")) }

        val (code, out, err) = load("--count", "40", "--set", "run=c3", "--settle", "5s")

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("matched             40"), out)
        assertTrue(out.contains("duplicates          40"), out)
    }

    @Test
    fun `a rate run issues what the schedule asked and reports it held`() {
        server.answer = { request -> listOf(TestFixServer.executionReportFor(request)) }

        val (code, out, err) = load("--rate", "100/s", "--for", "3s", "--set", "run=d4", "--settle", "5s")

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

        val (code, out, err) = load("--count", "25", "--set", "run=e5", "--listen", "DROPCOPY", "--settle", "5s")

        assertEquals(0, code, "stdout:\n$out\nstderr:\n$err")
        assertTrue(out.contains("matched             25"), out)
        assertTrue(!out.contains("strays"), "nothing was a stray: $out")
        assertTrue(server.logons.any { it.first == "DC$runId" }, "the drop-copy session logged on: ${server.logons}")
    }

    @Test
    fun `a memory store without Reset on Logon exits two with the config's sentence`() {
        writeProfiles(resetOnLogon = false)

        val (code, _, err) = load("--count", "10", "--set", "run=f6", "--store", "memory")

        assertEquals(HeadlessRun.EXIT_USAGE, code, err)
        assertTrue(err.contains("Reset on Logon"), err)
        assertEquals(0, server.logonCount.get(), "refused before any lane dialled")
    }

    @Test
    fun `a template that reads a name nothing seeds exits two before a lane opens`() {
        val (code, _, err) = load("--count", "10")

        assertEquals(HeadlessRun.EXIT_USAGE, code, err)
        assertTrue(err.contains("\${run}"), err)
        assertTrue(err.contains("--set run="), err)
        assertEquals(0, server.logonCount.get(), err)
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
