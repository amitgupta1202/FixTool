package com.knapsack.fixtool.integration

import com.knapsack.fixtool.headless.HeadlessRun
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole point of the headless entrypoint, over a real socket: **no window, and the exit code is
 * the verdict**.
 *
 * Nothing here touches Compose, the control server or a display. A scenario and a connection profile
 * are written to a directory, `fixtool run` is handed that directory, and what comes back is a status
 * code and a JUnit file — which is exactly the contract a build step depends on and the one thing a
 * unit test of the argument parser cannot prove.
 */
class HeadlessRunIntegrationTest {
    private lateinit var server: TestFixServer
    private lateinit var home: File
    private val runId = System.nanoTime().toString().takeLast(5)

    @Before
    fun setUp() {
        server = TestFixServer()
        server.start()
        home = File(System.getProperty("java.io.tmpdir"), "fixtool-headless-$runId").apply { mkdirs() }
        File(home, "scenarios").mkdirs()
        writeProfile()
    }

    @After
    fun tearDown() {
        server.stop()
        home.deleteRecursively()
    }

    private fun writeProfile() {
        File(home, "connection_profiles.json").writeText(
            """
            {"profiles":[{
              "id":"p-$runId","name":"HL$runId",
              "config":{
                "senderCompID":"HLCLI$runId","targetCompID":"HLSRV$runId",
                "host":"localhost","port":"${server.port}","socketConnectHost":"localhost",
                "beginString":"FIX.4.4","autoReconnect":false,"resetOnLogon":true,
                "fileStorePath":"${File(home, "store").absolutePath}",
                "fileLogPath":"${File(home, "log").absolutePath}"
              }}]}
            """.trimIndent(),
        )
    }

    /**
     * A second profile beside the first, opening [count] sessions off one `{n}` pattern — the shape a
     * fan-out needs and the one thing the headless host used to flatten to a single session.
     */
    private fun writeFanOutProfile(count: Int) {
        File(home, "connection_profiles.json").writeText(
            """
            {"profiles":[{
              "id":"p-$runId","name":"HL$runId",
              "config":{
                "senderCompID":"HLCLI$runId","targetCompID":"HLSRV$runId",
                "host":"localhost","port":"${server.port}","socketConnectHost":"localhost",
                "beginString":"FIX.4.4","autoReconnect":false,"resetOnLogon":true,
                "fileStorePath":"${File(home, "store").absolutePath}",
                "fileLogPath":"${File(home, "log").absolutePath}"
              }},{
              "id":"pf-$runId","name":"FAN$runId",
              "config":{
                "senderCompID":"HLF{n}$runId","targetCompID":"HLSRV$runId",
                "host":"localhost","port":"${server.port}","socketConnectHost":"localhost",
                "beginString":"FIX.4.4","autoReconnect":false,"resetOnLogon":true,
                "sessionCount":$count,
                "fileStorePath":"${File(home, "store").absolutePath}",
                "fileLogPath":"${File(home, "log").absolutePath}"
              }}]}
            """.trimIndent(),
        )
    }

    private fun writeScenario(name: String, steps: String): File =
        File(home, "scenarios/$name.json").apply {
            writeText("""{"id":"$name","name":"$name","steps":[$steps]}""")
        }

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = HeadlessRun.execute(arrayOf(*args), out, err)
        return Triple(code, out.toString(), err.toString())
    }

    /**
     * The passing path: a profile that was never connected by hand is dialled by the run itself
     * (preflight auto-connect), the order reaches the venue, and the process exits 0.
     */
    @Test
    fun `a passing scenario connects its own session, sends, and exits zero`() {
        writeScenario(
            "smoke",
            """{"type":"wait","session":"HL$runId","state":"LOGGED_ON","timeoutMs":15000},
               {"type":"send","session":"HL$runId","raw":"35=D|11=HDLS-$runId|55=EUR/USD|54=1|38=100|40=1|"}""",
        )
        val junit = File(home, "reports/smoke.xml")

        val (code, out, err) = run("run", "smoke", "--home", home.absolutePath, "--junit", junit.absolutePath)

        assertEquals(HeadlessRun.EXIT_PASSED, code, "expected a pass\nout:\n$out\nerr:\n$err")
        assertTrue(out.contains("PASSED"), out)
        assertTrue(
            server.applicationMessages.any { it.contains("11=HDLS-$runId") },
            "the venue should have received the order: ${server.applicationMessages}",
        )
        assertTrue(junit.isFile, "the JUnit report should have been written")
        assertTrue(junit.readText().contains("<testsuite"), junit.readText().take(200))
    }

    /**
     * The failing path, and the one that matters most: a scenario that runs and fails must exit **1**,
     * not 0 and not 2. A build step that cannot tell "the venue answered wrongly" from "the report file
     * was missing" is a build step that goes green on a broken venue.
     */
    @Test
    fun `a scenario that runs and fails exits one`() {
        // The test venue records application messages and never replies, so this expect times out.
        writeScenario(
            "expects-a-reply",
            """{"type":"wait","session":"HL$runId","state":"LOGGED_ON","timeoutMs":15000},
               {"type":"send","session":"HL$runId","raw":"35=D|11=NOREPLY-$runId|55=EUR/USD|54=1|38=100|40=1|"},
               {"type":"expect","session":"HL$runId","direction":"in","timeoutMs":1500,
                "expectation":{"messageType":"8","mode":"open","fields":[
                  {"tag":35,"matcher":{"type":"exact","value":"8"}}]}}""",
        )

        val (code, out, _) = run("run", "expects-a-reply", "--home", home.absolutePath)

        assertEquals(HeadlessRun.EXIT_FAILED, code, "a real failure is exit 1: $out")
        assertTrue(out.contains("FAILED"), out)
        assertTrue(out.contains("FAIL  "), "the failing step should be named in the summary: $out")
    }

    /**
     * **The exit code and the report file must say the same thing.** They are the two things a build
     * step reads, and teardown is where they disagreed: the verdict exempts best-effort cleanup, the
     * JUnit renderer counted it, so this run exited 0 while the file it had just written said
     * `failures="1"` — and a build gated on the report went red on a run the tool called green.
     */
    @Test
    fun `a failing teardown leaves the exit code and the report agreeing`() {
        // The venue records and never replies, so a teardown expect times out — a cleanup problem, not
        // a verdict. Written by hand because [writeScenario] has no teardown.
        File(home, "scenarios/cleanup-fails.json").writeText(
            """{"id":"cleanup-fails","name":"cleanup-fails",
                "steps":[{"type":"wait","session":"HL$runId","state":"LOGGED_ON","timeoutMs":15000}],
                "teardown":[{"type":"expect","session":"HL$runId","direction":"in","timeoutMs":800,
                  "expectation":{"messageType":"8","mode":"open","fields":[
                    {"tag":35,"matcher":{"type":"exact","value":"8"}}]}}]}""",
        )
        val junit = File(home, "reports/cleanup-fails.xml")

        val (code, out, _) = run("run", "cleanup-fails", "--home", home.absolutePath, "--junit", junit.absolutePath)

        assertEquals(HeadlessRun.EXIT_PASSED, code, "teardown does not decide the verdict: $out")
        val xml = junit.readText()
        assertTrue(xml.contains("failures=\"0\""), "and the report it writes must not say otherwise: $xml")
        assertTrue(xml.contains("<system-out>"), "the cleanup problem is still reported, just not as a gate: $xml")
        assertTrue(xml.contains("teardown"), xml)
    }

    /** A scenario naming a profile that does not exist stops rather than reporting a pass. */
    @Test
    fun `a scenario naming an unknown session cannot pass`() {
        writeScenario(
            "unknown-session",
            """{"type":"send","session":"NOSUCH","raw":"35=D|11=X|55=EUR/USD|54=1|38=1|40=1|"}""",
        )

        val (code, out, _) = run("run", "unknown-session", "--home", home.absolutePath)

        assertTrue(code != HeadlessRun.EXIT_PASSED, "an unconnectable session must never report a pass: $out")
    }

    // ------------------------------------------------------------------ the batch sweep

    /**
     * **What this file's own header called the strongest driver**: one process, many scenarios, one exit
     * code — and the evidence on disk that a build can attach. `--all` takes the store in name order;
     * `--junit <dir>` writes one report per entry, because a build publishes an artifact per test.
     */
    @Test
    fun `--all runs every saved scenario, exits zero, and leaves a record for each`() {
        writeScenario("aaa-first", sendStep("BATCH1-$runId"))
        writeScenario("bbb-second", sendStep("BATCH2-$runId"))
        val reports = File(home, "reports")

        val (code, out, err) = run("run", "--all", "--home", home.absolutePath, "--junit", reports.absolutePath)

        assertEquals(HeadlessRun.EXIT_PASSED, code, "out:\n$out\nerr:\n$err")
        assertTrue(out.contains("PASSED"), out)
        assertTrue(out.contains("1/2") && out.contains("2/2"), "every entry is named in the log: $out")
        assertTrue(
            server.applicationMessages.any { it.contains("BATCH1-$runId") } &&
                server.applicationMessages.any { it.contains("BATCH2-$runId") },
            "both scenarios reached the venue: ${server.applicationMessages}",
        )

        // One JUnit file per entry, and a record directory holding the whole set.
        val xml =
            reports
                .listFiles()
                ?.map { it.name }
                .orEmpty()
                .sorted()
        assertEquals(listOf("01-aaa-first.xml", "02-bbb-second.xml"), xml, "one report per entry: $xml")
        val runs = File(home, "runs").listFiles()?.singleOrNull()
        assertNotNull(runs, "the set's records should be under <home>/runs")
        val entries =
            runs
                .listFiles()
                ?.map { it.name }
                .orEmpty()
                .sorted()
        assertEquals(listOf("01-aaa-first.json", "02-bbb-second.json", "set.json"), entries, "$entries")
        assertTrue(
            File(runs, "01-aaa-first.json").readText().contains("BATCH1-$runId"),
            "the record carries the bytes, which is the whole reason it is written",
        )
    }

    /** A named set in the checkout — what a build box selects, since it has no local star file. */
    @Test
    fun `--set runs a saved set by name, in the order the file gives`() {
        writeScenario("one", sendStep("SET1-$runId"))
        writeScenario("two", sendStep("SET2-$runId"))
        File(home, "sets").mkdirs()
        File(home, "sets/nightly.json").writeText(
            """{"name":"nightly","entries":[{"scenario":"two"},{"scenario":"one","repeat":2}]}""",
        )
        val junit = File(home, "reports/nightly.xml")

        val (code, out, err) = run("run", "--set", "nightly", "--home", home.absolutePath, "--junit", junit.absolutePath)

        assertEquals(HeadlessRun.EXIT_PASSED, code, "out:\n$out\nerr:\n$err")
        assertTrue(out.contains("PASSED  nightly"), out)
        // The file's order, and its repeat expanded into numbered iterations.
        assertTrue(out.indexOf("two") < out.indexOf("one"), "the set's order is the file's: $out")
        assertTrue(out.contains("one #2"), "a repeat is iterations, and they are named: $out")

        // --junit <file>.xml is the whole set as one document.
        val xml = junit.readText()
        assertTrue(xml.contains("<testsuites"), xml.take(200))
        assertEquals(3, Regex("<testsuite ").findAll(xml).count(), "one suite per entry: $xml")
    }

    /**
     * **`--rows` in CI**, where the outline pays for itself: one scenario, one process, one testcase per
     * row, each named the way a parameterized test always has been.
     */
    @Test
    fun `--rows runs the table and names each suite for its row`() {
        File(home, "scenarios/outline.json").writeText(
            """{"id":"outline","name":"outline",
                "examples":{"columns":["symbol"],"rows":[
                  {"name":"EUR USD","values":{"symbol":"EUR/USD"}},
                  {"name":"GBP USD","values":{"symbol":"GBP/USD"}}]},
                "steps":[{"type":"wait","session":"HL$runId","state":"LOGGED_ON","timeoutMs":15000},
                         {"type":"send","session":"HL$runId","raw":"35=D|11=ROW-$runId|55=${'$'}{symbol}|54=1|38=100|40=1|"}]}""",
        )
        val junit = File(home, "reports/outline.xml")

        val (code, out, err) = run("run", "outline", "--rows", "--home", home.absolutePath, "--junit", junit.absolutePath)

        assertEquals(HeadlessRun.EXIT_PASSED, code, "out:\n$out\nerr:\n$err")
        // Each row's cells reached the venue — the whole point of the table.
        assertTrue(
            server.applicationMessages.any { it.contains("55=EUR/USD") } &&
                server.applicationMessages.any { it.contains("55=GBP/USD") },
            "both rows drove the flow: ${server.applicationMessages}",
        )
        val xml = junit.readText()
        assertTrue(xml.contains("""name="outline [EUR USD]""""), "a row names its own suite: $xml")
        assertTrue(xml.contains("""name="outline [GBP USD]""""), xml)
        assertTrue(out.contains("outline [EUR USD]"), "and the build log says the same: $out")
    }

    /** One row of eight, for the row that failed. */
    @Test
    fun `--row runs only the row it names`() {
        File(home, "scenarios/one-row.json").writeText(
            """{"id":"one-row","name":"one-row",
                "examples":{"columns":["symbol"],"rows":[
                  {"name":"alpha","values":{"symbol":"EUR/USD"}},
                  {"name":"beta","values":{"symbol":"GBP/USD"}}]},
                "steps":[{"type":"wait","session":"HL$runId","state":"LOGGED_ON","timeoutMs":15000},
                         {"type":"send","session":"HL$runId","raw":"35=D|11=ONE-$runId|55=${'$'}{symbol}|54=1|38=100|40=1|"}]}""",
        )

        val (code, out, _) = run("run", "one-row", "--row", "beta", "--home", home.absolutePath)

        assertEquals(HeadlessRun.EXIT_PASSED, code, out)
        assertTrue(out.contains("one-row [beta]"), out)
        assertTrue(
            server.applicationMessages.any { it.contains("ONE-$runId") && it.contains("55=GBP/USD") },
            "beta's cells went out: ${server.applicationMessages}",
        )
        assertTrue(
            server.applicationMessages.none { it.contains("ONE-$runId") && it.contains("55=EUR/USD") },
            "and alpha's did not",
        )
    }

    /**
     * A CI gate wants the opposite of a flake hunt: stop at the first red. The entries that never ran say
     * why, so a log reader is not left wondering whether they passed.
     */
    @Test
    fun `--stop-on-failure ends the batch at the first failure and exits one`() {
        // The venue records and never replies, so this expect times out.
        writeScenario(
            "aaa-fails",
            """{"type":"wait","session":"HL$runId","state":"LOGGED_ON","timeoutMs":15000},
               {"type":"expect","session":"HL$runId","direction":"in","timeoutMs":1000,
                "expectation":{"messageType":"8","mode":"open","fields":[
                  {"tag":35,"matcher":{"type":"exact","value":"8"}}]}}""",
        )
        writeScenario("bbb-never-runs", sendStep("SKIPPED-$runId"))

        val (code, out, _) = run("run", "--all", "--stop-on-failure", "--home", home.absolutePath)

        assertEquals(HeadlessRun.EXIT_FAILED, code, out)
        assertTrue(out.contains("FAIL  1/2 aaa-fails"), out)
        assertTrue(out.contains("skip  2/2 bbb-never-runs"), out)
        assertTrue(out.contains("an earlier entry failed"), "and it says why it did not run: $out")
        assertTrue(
            server.applicationMessages.none { it.contains("SKIPPED-$runId") },
            "the skipped entry must not have sent anything",
        )
    }

    private fun sendStep(clOrdId: String) =
        """{"type":"wait","session":"HL$runId","state":"LOGGED_ON","timeoutMs":15000},
           {"type":"send","session":"HL$runId","raw":"35=D|11=$clOrdId|55=EUR/USD|54=1|38=100|40=1|"}"""

    /**
     * **The gap `--fan-out` closes, over real sockets.** The headless host used to create one session per
     * profile *name*, which meant a three-session profile logged on once and a "fan-out" was one client
     * wearing three labels — the run would have passed while measuring nothing.
     *
     * Three lanes, three logons under three CompIDs, three orders each stamped with its own
     * `${'$'}{sessionIndex}`. That last part is what proves the lanes are separate scopes and not one
     * scope run three times.
     */
    @Test
    fun `fan-out opens one session per profile slot, and each lane sends as itself`() {
        writeFanOutProfile(count = 3)
        writeScenario(
            "fan",
            """{"type":"send","session":"CLI","raw":"35=D|11=FAN-${'$'}{sessionIndex}-$runId|55=EUR/USD|54=1|38=100|40=1|"}""",
        )

        val (code, out, err) = run("run", "fan", "--fan-out", "FAN$runId", "--home", home.absolutePath)

        assertEquals(HeadlessRun.EXIT_PASSED, code, "out=$out err=$err")
        // One logon per slot, each as its own identity — the {n} pattern actually resolved.
        val fanned =
            server.logons
                .map { it.first }
                .filter { it.startsWith("HLF") }
                .toSet()
        assertEquals(setOf("HLF1$runId", "HLF2$runId", "HLF3$runId"), fanned, "one logon per slot: ${server.logons}")
        // And each lane sent under its own seeded index, so the three scopes never collapsed into one.
        (1..3).forEach { slot ->
            assertTrue(
                server.applicationMessages.any { it.contains("11=FAN-$slot-$runId") },
                "lane $slot sent nothing of its own: ${server.applicationMessages}",
            )
        }
    }

    /**
     * **`--json` on a batch wrote nothing unless `--junit` came with it**, and when it did write, it wrote
     * the last entry's report as though it were the run's. A CI step that asked for its report got silence
     * and exit 0; one that asked for both got iteration 20 of 20 labelled as the whole thing.
     */
    @Test
    fun `--json describes the whole set, and does not need --junit to be written at all`() {
        writeScenario("jsonset", sendStep("JSONSET-$runId"))
        val json = File(home, "reports/set.json")

        val (code, _, err) = run("run", "jsonset", "--repeat", "3", "--json", json.absolutePath, "--home", home.absolutePath)

        assertEquals(HeadlessRun.EXIT_PASSED, code, err)
        assertTrue(json.exists(), "--json alone must write the file: $err")
        val doc = Json.parseToJsonElement(json.readText()).jsonObject
        assertEquals("passed", doc["status"]!!.jsonPrimitive.content)
        val entries = doc["entries"]!!.jsonArray
        assertEquals(3, entries.size, "the set, not one entry of it")
        // Each entry carries its own report, so the file answers "which step failed" on its own.
        entries.forEach { entry ->
            val report = entry.jsonObject["report"]!!.jsonObject
            assertEquals("jsonset", report["scenario"]!!.jsonPrimitive.content)
            assertTrue(report["steps"]!!.jsonArray.isNotEmpty(), "a report with no steps is not a report")
        }
        // And the set's own distribution, which used to exist only on the Compose rail. This flow has no
        // Expect, so there is no round trip to report — the wall clock is the number it does have.
        val stats = assertNotNull(doc["stats"], "the distribution must reach a build box")
        assertTrue(stats.jsonObject.containsKey("wallClock"), "$stats")
    }

    /** An acceptor is the far end of lanes, never their source — and it says so rather than trying. */
    @Test
    fun `fanning out over a profile that opens one session is refused with the reason`() {
        writeFanOutProfile(count = 3)
        writeScenario("fan1", """{"type":"send","session":"CLI","raw":"35=D|11=X-$runId|55=EUR/USD|54=1|38=1|40=1|"}""")

        val (code, _, err) = run("run", "fan1", "--fan-out", "no-such-profile", "--home", home.absolutePath)

        assertEquals(HeadlessRun.EXIT_USAGE, code)
        assertTrue(err.contains("no saved connection profile named 'no-such-profile'"), err)
    }

    /** `--home` is the whole CI story: the config under test is the one in the checkout. */
    @Test
    fun `the home directory decides which scenarios and profiles are visible`() {
        writeScenario("only-here", """{"type":"wait","session":"HL$runId","state":"LOGGED_ON","timeoutMs":15000}""")

        val elsewhere = File(System.getProperty("java.io.tmpdir"), "fixtool-empty-$runId").apply { mkdirs() }
        try {
            val (code, _, err) = run("run", "only-here", "--home", elsewhere.absolutePath)
            assertEquals(HeadlessRun.EXIT_USAGE, code, "a scenario outside the given home must not be found")
            assertTrue(err.contains("no scenario"), err)
        } finally {
            elsewhere.deleteRecursively()
        }

        assertEquals(HeadlessRun.EXIT_PASSED, run("run", "only-here", "--home", home.absolutePath).first)
    }
}
