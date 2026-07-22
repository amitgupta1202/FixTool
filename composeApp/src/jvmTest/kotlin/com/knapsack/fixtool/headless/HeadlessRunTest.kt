package com.knapsack.fixtool.headless

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The command line, and the promise it makes: **the exit code is the verdict**.
 *
 * Everything a build step does with this tool hangs off that one number, so the cases that must never
 * be confused with each other — passed, ran-and-failed, could-not-run — are pinned here rather than
 * left to the shell to discover.
 */
class HeadlessRunTest {
    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = HeadlessRun.execute(arrayOf(*args), out, err)
        return Triple(code, out.toString(), err.toString())
    }

    // ------------------------------------------------------- which invocations are ours

    @Test
    fun `only a run or a help asks for a headless process`() {
        assertTrue(HeadlessRun.handles(arrayOf("run", "smoke")))
        assertTrue(HeadlessRun.handles(arrayOf("--help")))
        assertTrue(HeadlessRun.handles(arrayOf("-h")))
        // No args is the GUI — double-clicking the app must never land in the CLI.
        assertTrue(!HeadlessRun.handles(emptyArray()))
        assertTrue(!HeadlessRun.handles(arrayOf("--some-jvm-flag")))
    }

    @Test
    fun `help is a success, and a bare unknown invocation is a usage error`() {
        val (helpCode, helpOut, _) = run("--help")
        assertEquals(HeadlessRun.EXIT_PASSED, helpCode)
        assertTrue(helpOut.contains("fixtool run <scenario>"), helpOut)

        assertEquals(HeadlessRun.EXIT_USAGE, run("nonsense").first)
    }

    // ------------------------------------------------------- could-not-run is never a pass

    @Test
    fun `naming no scenario is a usage error, not a green run`() {
        val (code, _, err) = run("run")

        assertEquals(HeadlessRun.EXIT_USAGE, code)
        assertTrue(err.contains("name a scenario"), err)
    }

    @Test
    fun `a scenario that does not exist exits 2, and says where it looked`() {
        val (code, _, err) = run("run", "no-such-scenario-${System.nanoTime()}")

        assertEquals(HeadlessRun.EXIT_USAGE, code, "a missing scenario must never be reported as passed")
        assertTrue(err.contains("no scenario"), err)
    }

    @Test
    fun `a file that is not a scenario exits 2 rather than throwing`() {
        val junk =
            File.createTempFile("not-a-scenario", ".json").apply {
                writeText("{ this is not json")
                deleteOnExit()
            }

        val (code, _, err) = run("run", junk.absolutePath)

        assertEquals(HeadlessRun.EXIT_USAGE, code)
        assertTrue(err.contains("not a readable scenario"), err)
    }

    @Test
    fun `a mistyped flag is refused rather than quietly ignored`() {
        // The hazard this guards: `--junitt out.xml` running the scenario, reporting a pass, and
        // writing nothing — a build that goes green while producing no evidence.
        assertEquals(HeadlessRun.EXIT_USAGE, run("run", "smoke", "--junitt", "out.xml").first)
        assertEquals(HeadlessRun.EXIT_USAGE, run("run", "smoke", "--junit").first, "a flag with no value is refused")
    }

    // ------------------------------------------------------- argument parsing

    @Test
    fun `the scenario, the report paths and the session remap are all read`() {
        val options =
            HeadlessRun.Options.parse(
                listOf("smoke", "--junit", "r/j.xml", "--json", "r/r.json", "--session", "DEV=QA", "--session", "A=B"),
            )

        assertEquals("smoke", options?.target)
        assertEquals("r/j.xml", options?.junitFile)
        assertEquals("r/r.json", options?.jsonFile)
        assertEquals(mapOf("DEV" to "QA", "A" to "B"), options?.sessionMap)
    }

    @Test
    fun `a session remap without an equals sign is refused`() {
        assertNull(HeadlessRun.Options.parse(listOf("smoke", "--session", "DEV")))
    }

    @Test
    fun `a second bare argument is refused, since only one scenario runs per process`() {
        assertNull(HeadlessRun.Options.parse(listOf("smoke", "also-smoke")))
    }

    @Test
    fun `a scenario path is taken verbatim, not treated as a flag`() {
        val options = HeadlessRun.Options.parse(listOf("scenarios/some-file.json"))

        assertEquals("scenarios/some-file.json", options?.target)
        assertNull(options?.junitFile)
    }
}
