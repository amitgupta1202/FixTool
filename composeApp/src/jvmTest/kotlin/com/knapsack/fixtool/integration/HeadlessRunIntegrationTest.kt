package com.knapsack.fixtool.integration

import com.knapsack.fixtool.headless.HeadlessRun
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
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
