package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.BindScope
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunPolicy
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scheduler, which is the whole of "multi-run" once the model is agreed: it walks the entries, and
 * everything that makes a set more than a `for` loop is here and is small — the isolation copy, the
 * record written as each entry lands, stop-on-first-failure, the pause, and the stop.
 */
class RunSetRunnerTest {
    @Test
    fun `a repeat runs every iteration and each one leaves a record`() {
        val host = FakeSetHost(green = true)
        val set = repeatOf(host, "book-a-trade", times = 3)

        val done = RunSetRunner(host).run(set)

        assertEquals(RunSetStatus.PASSED, done.status)
        assertEquals(3, done.passed)
        assertEquals(listOf(1, 2, 3), done.entries.map { it.iteration })
        assertEquals(
            listOf("01-book-a-trade.json", "02-book-a-trade.json", "03-book-a-trade.json"),
            done.entries.map { it.record },
            "every entry's evidence is on disk, named for where it sat in the set",
        )
        assertEquals(3, host.written.size)
        assertEquals(listOf(1, 2, 3), host.written.map { it.entry })
    }

    /**
     * Under the default binding an expect may bind a message that arrived before the run started, so
     * iteration 2 can bind iteration 1's reply and report that the venue answered when it has not. On a
     * twenty-times repeat that is a false green by construction — in the one feature whose whole purpose
     * is catching flakiness.
     */
    @Test
    fun `entries run isolated, and the isolation is a copy rather than a file write`() {
        val host = FakeSetHost(green = true)
        val saved = scenario("book-a-trade")
        host.scenarios[saved.id] = saved

        RunSetRunner(host).run(RunSets.repeat(saved, times = 2, now = 0L))

        assertTrue(host.ran.all { it.binding == BindScope.THIS_RUN }, "each entry runs isolated")
        assertEquals(BindScope.ANY, host.scenarios.getValue(saved.id).binding, "and the saved scenario is untouched")
    }

    /** A suite exists to produce the whole morning's picture, so a failure does not end it by default. */
    @Test
    fun `a failed entry does not stop the set, and the set says it failed`() {
        val host = FakeSetHost(green = true).apply { failEntries += 2 }
        val set = suiteOf(host, "smoke", "book-a-trade", "cancel-replace")

        val done = RunSetRunner(host).run(set)

        assertEquals(RunSetStatus.FAILED, done.status)
        assertEquals(listOf(RunState.PASSED, RunState.FAILED, RunState.PASSED), done.entries.map { it.state })
        assertEquals(3, host.ran.size, "the third entry still ran — '3 of 20 failed' is the answer a flake hunt wants")
    }

    /** CI gates want the opposite, and say so. The entries that never ran say why they did not. */
    @Test
    fun `stop-on-first-failure skips the rest, by name`() {
        val host = FakeSetHost(green = true).apply { failEntries += 2 }
        val set = suiteOf(host, "smoke", "book-a-trade", "cancel-replace").copy(policy = RunPolicy(stopOnFirstFailure = true))

        val done = RunSetRunner(host).run(set)

        assertEquals(RunSetStatus.FAILED, done.status)
        assertEquals(listOf(RunState.PASSED, RunState.FAILED, RunState.SKIPPED), done.entries.map { it.state })
        assertEquals("an earlier entry failed", done.entries[2].note)
        assertEquals(2, host.ran.size)
    }

    /**
     * A set is planned up front so its queue is visible before it runs, which means a scenario can be
     * deleted between the planning and the running. Skipped by name: nothing about the venue is known,
     * and reporting a failure would say something the run cannot support.
     */
    @Test
    fun `a scenario deleted since the set was planned is skipped, not failed`() {
        val host = FakeSetHost(green = true)
        val set =
            RunSet(
                id = "s",
                label = "suite",
                source = RunSource.Selected(listOf("gone")),
                entries = listOf(RunEntry("gone", "deleted-scenario")),
            )

        val done = RunSetRunner(host).run(set)

        assertEquals(RunState.SKIPPED, done.entries.single().state)
        assertTrue(done.entries.single().note!!.contains("no longer on disk"), done.entries.single().note!!)
        assertEquals(RunSetStatus.PASSED, done.status, "a set that could not run an entry has still not failed one")
    }

    @Test
    fun `a stopped set stops where it is and marks the rest`() {
        val host = FakeSetHost(green = true)
        host.stopAfter = 1
        val set = suiteOf(host, "smoke", "book-a-trade", "cancel-replace")

        val done = RunSetRunner(host).run(set)

        assertEquals(RunSetStatus.STOPPED, done.status)
        assertEquals(listOf(RunState.PASSED, RunState.SKIPPED, RunState.SKIPPED), done.entries.map { it.state })
        assertEquals("the set was stopped", done.entries[1].note)
        assertEquals(1, host.ran.size)
    }

    /** The pause is between entries, not after the set — a trailing sleep is time nobody asked for. */
    @Test
    fun `the pause falls between entries only`() {
        val host = FakeSetHost(green = true)
        val set = repeatOf(host, "book-a-trade", times = 3, policy = RunPolicy(pauseBetweenMs = 250))

        RunSetRunner(host).run(set)

        assertEquals(listOf(250L, 250L), host.slept)
    }

    /** `set.json` is rewritten as it goes, so a reader in another process sees the progress, not the end. */
    @Test
    fun `the set file is rewritten after every entry`() {
        val host = FakeSetHost(green = true)
        val progress = mutableListOf<Int>()

        RunSetRunner(host).run(repeatOf(host, "book-a-trade", times = 2)) { progress += it.done }

        assertTrue(host.setWrites >= 4, "start, each entry, and the finish: ${host.setWrites}")
        assertEquals(listOf(0, 0, 1, 1, 2, 2), progress, "the queue is visible while it drains")
    }

    /** An entry that ran and was stopped part-way is neither passed nor failed: it stopped checking. */
    @Test
    fun `an entry the runner stopped is reported as stopped`() {
        val host = FakeSetHost(green = true).apply { stoppedEntries += 1 }

        val done = RunSetRunner(host).run(repeatOf(host, "book-a-trade", times = 1))

        assertEquals(RunState.STOPPED, done.entries.single().state)
        assertEquals(RunSetStatus.STOPPED, done.status)
    }

    // ----------------------------------------------------------------- helpers

    private fun scenario(name: String) =
        Scenario(id = "id-$name", name = name, steps = listOf(ScenarioStep.Send("35=D|", session = "s")))

    /** A repeat whose scenario the host can actually find — a set names its scenarios by id. */
    private fun repeatOf(host: FakeSetHost, name: String, times: Int, policy: RunPolicy = RunPolicy()): RunSet {
        val scenario = scenario(name)
        host.scenarios[scenario.id] = scenario
        return RunSets.repeat(scenario, times = times, now = 0L, policy = policy)
    }

    private fun suiteOf(host: FakeSetHost, vararg names: String): RunSet {
        val scenarios = names.map { scenario(it) }
        scenarios.forEach { host.scenarios[it.id] = it }
        return RunSets.suite(scenarios, RunSource.Selected(scenarios.map { it.id }), "suite", now = 0L)
    }

    /** An in-memory world: scenarios by id, runs recorded, records and sleeps counted. */
    private class FakeSetHost(private val green: Boolean) : RunSetHost {
        val scenarios = mutableMapOf<String, Scenario>()
        val ran = mutableListOf<Scenario>()
        val written = mutableListOf<RunRecord>()
        val slept = mutableListOf<Long>()
        var setWrites = 0
        var clock = 0L

        /** 1-based entry numbers that should come back red, or stopped. */
        val failEntries = mutableSetOf<Int>()
        val stoppedEntries = mutableSetOf<Int>()

        /** Stop the set after this many entries have run. */
        var stopAfter: Int? = null

        override fun scenario(id: String): Scenario? = scenarios[id] ?: scenarios.values.firstOrNull { it.id == id }

        override fun runOne(scenario: Scenario, sessionMap: Map<String, String>): EntryOutcome? {
            ran += scenario
            val n = ran.size
            val steps =
                when {
                    n in stoppedEntries -> listOf(StepResult(-1, "stopped", "steps", passed = false))
                    n in failEntries -> listOf(StepResult(0, "expect", "steps", passed = false))
                    else -> listOf(StepResult(0, "send", "steps", passed = true))
                }
            val result = ScenarioResult(scenario.name, green && steps.all { it.passed }, steps, durationMs = 7)
            return EntryOutcome(result, RunRecorder.Evidence(emptyList(), emptyMap(), 0))
        }

        override fun write(record: RunRecord): String? {
            written += record
            return "%02d-%s.json".format(record.entry, record.scenarioName)
        }

        override fun writeSet(set: RunSet) {
            setWrites++
        }

        override fun sleep(ms: Long) {
            slept += ms
            clock += ms
        }

        override fun now(): Long = clock++

        override fun cancelled(): Boolean = stopAfter?.let { ran.size >= it } ?: false
    }
}
