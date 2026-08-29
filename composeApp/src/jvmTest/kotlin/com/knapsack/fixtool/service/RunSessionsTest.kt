package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The run slot is a claim over sessions, and this is the part that decides what it claims.**
 *
 * It used to be one global boolean: any run excluded any other, whichever venues they drove, so a bare
 * check on UAT waited behind a fifty-lane load test on LOADGEN. The licence to overlap is the one fan-out
 * already rests on for its lanes — cursors are per-run over per-session logs, so runs on disjoint sessions
 * cannot see each other's traffic — and the whole of it turns on resolving *which* sessions, before the
 * run starts rather than inside preflight where it was resolved too late to be checked.
 */
class RunSessionsTest {
    @Test
    fun `a scenario claims the sessions its unmuted steps name`() {
        val touched = RunSessions.of(scenario("CLI" to false, "VENUE" to false, "OLD" to true))

        assertEquals(setOf("CLI", "VENUE"), touched.sessions, "the muted step does not run, so it is not claimed")
        assertFalse(touched.exclusive)
    }

    /** The remap is a run input, so the claim must be over the sessions actually driven. */
    @Test
    fun `the claim follows the remap, not the names in the file`() {
        val touched = RunSessions.of(scenario("CLI" to false), sessionMap = mapOf("CLI" to "UAT [2]"))

        assertEquals(setOf("UAT [2]"), touched.sessions)
    }

    /** A fan-out lane's steps name no session; each lane resolves to its own, which is what makes lanes disjoint. */
    @Test
    fun `a step naming no session takes the lane's, and lanes do not collide`() {
        val flow = scenario(null to false)

        val laneOne = RunSessions.of(flow, defaultSession = "LoadGen [1]")
        val laneTwo = RunSessions.of(flow, defaultSession = "LoadGen [2]")

        assertEquals(setOf("LoadGen [1]"), laneOne.sessions)
        assertFalse(RunSessions.conflict(laneOne, laneTwo), "two lanes are the case this rule exists to permit")
    }

    /** Outside a lane it is whichever session is first — exactly what the host picks. */
    @Test
    fun `a step naming no session falls to the first open one`() {
        val touched = RunSessions.of(scenario(null to false), firstOpen = "Demo User 1")

        assertEquals(setOf("Demo User 1"), touched.sessions)
        assertFalse(touched.exclusive)
    }

    /**
     * **Unknown is not empty.** A step that names no session, with nothing open to fall back to, will run
     * against whatever appears first — so its claim cannot be reasoned about. Two such runs must not both
     * conclude they are disjoint and drive the same session; the honest answer is to take everything.
     */
    @Test
    fun `a run that cannot name what it touches conflicts with everything`() {
        val unknown = RunSessions.of(scenario(null to false))

        assertTrue(unknown.exclusive)
        assertTrue(unknown.sessions.isEmpty())
        assertTrue(RunSessions.conflict(unknown, RunSessions.Touched(setOf("CLI"))), "it blocks a named run")
        assertTrue(RunSessions.conflict(RunSessions.Touched(setOf("CLI")), unknown), "and a named run blocks it")
    }

    /** The point of the whole change: disjoint runs proceed, overlapping ones do not. */
    @Test
    fun `disjoint runs may overlap and shared ones may not`() {
        val loadgen = RunSessions.Touched(setOf("LoadGen [1]", "LoadGen [2]"))
        val uat = RunSessions.Touched(setOf("UAT"))
        val alsoLoadgen = RunSessions.Touched(setOf("LoadGen [2]", "UAT"))

        assertFalse(RunSessions.conflict(loadgen, uat), "different venues, no shared log, no reason to wait")
        assertTrue(RunSessions.conflict(loadgen, alsoLoadgen), "one shared session is enough to refuse")
    }

    /** A set claims every session any of its entries would drive, before the first one starts. */
    @Test
    fun `a set claims the union of its entries, and one unknown entry taints the whole claim`() {
        val union = RunSessions.ofAll(listOf(RunSessions.Touched(setOf("A")), RunSessions.Touched(setOf("B"))))
        assertEquals(setOf("A", "B"), union.sessions)
        assertFalse(union.exclusive)

        val tainted = RunSessions.ofAll(listOf(RunSessions.Touched(setOf("A")), RunSessions.Touched(emptySet(), exclusive = true)))
        assertTrue(tainted.exclusive, "an entry whose scenario cannot be loaded must not slip past a collision")
    }

    private fun scenario(vararg steps: Pair<String?, Boolean>) =
        Scenario(
            id = "sc",
            name = "flow",
            steps =
                steps.map { (session, muted) ->
                    ScenarioStep.Expect(expectation = Expectation(fields = emptyList(), messageType = "8"), session = session, muted = muted)
                },
        )
}
