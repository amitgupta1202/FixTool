package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.scenario.RunPolicy
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

    // ------------------------------------------------------- the batch

    @Test
    fun `the batch flags are read, and only they make it a set`() {
        assertEquals("nightly", HeadlessRun.Options.parse(listOf("--set", "nightly"))?.set)
        assertTrue(HeadlessRun.Options.parse(listOf("--set", "nightly"))!!.isSet)
        assertTrue(HeadlessRun.Options.parse(listOf("--all"))!!.isSet)
        assertTrue(HeadlessRun.Options.parse(listOf("smoke", "--repeat", "20"))!!.isSet)
        // One run is a set of one, but it does not have to take the batch path to prove it.
        assertTrue(!HeadlessRun.Options.parse(listOf("smoke"))!!.isSet)
        assertTrue(!HeadlessRun.Options.parse(listOf("smoke", "--repeat", "1"))!!.isSet)
    }

    /** `500ms`, `2s` or a bare number — the three ways somebody writes a pause on a command line. */
    @Test
    fun `a pause is read in any of the three ways it is written`() {
        assertEquals(500L, HeadlessRun.Options.parse(listOf("smoke", "--pause", "500ms"))?.pauseMs)
        assertEquals(2_000L, HeadlessRun.Options.parse(listOf("smoke", "--pause", "2s"))?.pauseMs)
        assertEquals(250L, HeadlessRun.Options.parse(listOf("smoke", "--pause", "250"))?.pauseMs)
        assertNull(HeadlessRun.Options.parse(listOf("smoke", "--pause", "soon")), "a pause nobody can parse is not zero")
    }

    @Test
    fun `a repeat that is not a positive number is refused rather than silently ignored`() {
        assertNull(HeadlessRun.Options.parse(listOf("smoke", "--repeat", "0")))
        assertNull(HeadlessRun.Options.parse(listOf("smoke", "--repeat", "twice")))
    }

    /** The command line says how the batch runs; a saved set's own policy is the default underneath it. */
    @Test
    fun `stop-on-failure is applied over a saved set's policy, never taken away from it`() {
        val asked = HeadlessRun.Options.parse(listOf("--set", "nightly", "--stop-on-failure", "--pause", "1s"))!!
        val fromFile = RunPolicy(stopOnFirstFailure = false, pauseBetweenMs = 250)

        val effective = asked.policy(fromFile)

        assertTrue(effective.stopOnFirstFailure)
        assertEquals(1_000L, effective.pauseBetweenMs)
        // And a command line that says nothing leaves the file's own choices alone.
        val quiet =
            HeadlessRun.Options
                .parse(listOf("--set", "nightly"))!!
                .policy(fromFile.copy(stopOnFirstFailure = true))
        assertTrue(quiet.stopOnFirstFailure)
        assertEquals(250L, quiet.pauseBetweenMs)
    }

    @Test
    fun `naming no scenario is fine when the batch names itself`() {
        // "run --all" has no bare argument, and must not be told to name a scenario.
        val (code, _, err) = run("run", "--all", "--home", File(System.getProperty("java.io.tmpdir"), "fixtool-empty-set").absolutePath)

        assertEquals(HeadlessRun.EXIT_USAGE, code, "an empty store is a usage error, not a pass")
        assertTrue(err.contains("no scenarios"), err)
    }
}
