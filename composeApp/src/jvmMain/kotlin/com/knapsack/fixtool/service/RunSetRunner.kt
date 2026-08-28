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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What one entry produced: the verdict, and the evidence to write beside it. */
data class EntryOutcome(
    val result: ScenarioResult,
    val evidence: RunRecorder.Evidence,
)

/**
 * What a set needs from the world. The ViewModel and a headless process implement it differently and
 * the scheduler cannot tell them apart — which is the point: the same set runs from a click, from the
 * control surface and from a build box, and leaves the same record.
 */
interface RunSetHost {
    fun scenario(id: String): Scenario?

    /**
     * Runs one scenario **with the run slot already held by the set** and collects its evidence.
     *
     * Null means the run could not start at all; a scenario that ran and failed comes back as a result
     * that did not pass, which is a different thing and reads differently in the report.
     */
    fun runOne(scenario: Scenario, sessionMap: Map<String, String>, seed: Map<String, String>): EntryOutcome?

    /** Writes one entry's record; returns the file name to record on the entry. */
    fun write(record: RunRecord): String?

    /** Rewrites `set.json`, so a reader on another process sees the progress so far. */
    fun writeSet(set: RunSet)

    fun sleep(ms: Long)

    fun now(): Long

    /** Has somebody asked the set to stop? Asked between entries; the runner asks it inside one. */
    fun cancelled(): Boolean
}

/**
 * **The scheduler: it walks the entries and nothing else.**
 *
 * Everything that makes a set more than a `for` loop lives here and is small — the isolation copy, the
 * record written as each entry lands, stop-on-first-failure, the pause, and the stop. What it deliberately
 * does *not* do is publish: a set of twenty would re-aim the author's open reconcile window twenty times
 * while they were reading it, so entries run through the host's slot-free path and **focusing** an entry
 * is what publishes it.
 */
class RunSetRunner(
    private val host: RunSetHost,
) {
    fun run(set: RunSet, onProgress: (RunSet) -> Unit = {}): RunSet {
        var current = set.copy(startedAt = host.now(), status = RunSetStatus.RUNNING)
        host.writeSet(current)
        onProgress(current)

        for (index in current.entries.indices) {
            if (host.cancelled()) return finish(current, stopped = true, from = index, onProgress = onProgress)

            current = current.withEntry(index) { it.copy(state = RunState.RUNNING) }
            onProgress(current)

            val entry = current.entries[index]
            val scenario = host.scenario(entry.scenarioId)
            if (scenario == null) {
                // A set is planned up front, and a scenario can be deleted between the planning and the
                // running. Skipped by name rather than failing the set: nothing about the venue is known.
                current =
                    current.withEntry(index) {
                        it.copy(state = RunState.SKIPPED, note = "scenario '${entry.scenarioName}' is no longer on disk")
                    }
                host.writeSet(current)
                onProgress(current)
                continue
            }

            val startedAt = host.now()
            val asRun = isolate(scenario, current.policy)
            // The row's cells are the scope this entry starts with. An entry with no row seeds nothing,
            // which is every suite and every repeat — one path, and the outline is not a second runner.
            val outcome = host.runOne(asRun, entry.sessionMap, entry.row?.values.orEmpty())
            val elapsed = host.now() - startedAt

            current =
                if (outcome == null) {
                    current.withEntry(index) {
                        it.copy(state = RunState.SKIPPED, durationMs = elapsed, note = "the run slot was not free")
                    }
                } else {
                    val record =
                        RunRecord(
                            setId = current.id,
                            entry = index + 1,
                            iteration = entry.iteration,
                            row = entry.row,
                            scenarioId = scenario.id,
                            scenarioName = scenario.name,
                            // As it ran, isolation and all — the record is evidence, and what was asserted
                            // is half of it.
                            scenario = asRun,
                            startedAt = startedAt,
                            durationMs = outcome.result.durationMs ?: elapsed,
                            result = outcome.result,
                            messages = outcome.evidence.messages,
                            bound = outcome.evidence.bound,
                            dropped = outcome.evidence.dropped,
                        )
                    val file = host.write(record)
                    current.withEntry(index) {
                        it.copy(
                            state = stateOf(outcome.result),
                            result = outcome.result,
                            durationMs = outcome.result.durationMs ?: elapsed,
                            record = file,
                        )
                    }
                }
            host.writeSet(current)
            onProgress(current)

            val failed = current.entries[index].state != RunState.PASSED
            if (failed && current.policy.stopOnFirstFailure) {
                return finish(current, stopped = false, from = index + 1, onProgress = onProgress)
            }
            if (host.cancelled()) return finish(current, stopped = true, from = index + 1, onProgress = onProgress)
            if (index < current.entries.lastIndex && current.policy.pauseBetweenMs > 0) {
                host.sleep(current.policy.pauseBetweenMs)
            }
        }
        return finish(current, stopped = false, from = current.entries.size, onProgress = onProgress)
    }

    /**
     * **Isolation is an in-memory copy, never a file write.**
     *
     * Under the default binding an expect may bind a message that arrived before the run started, so
     * iteration 2 can bind iteration 1's reply and report that the venue answered when it has not — a
     * false green *by construction* in the one feature whose purpose is catching flakiness.
     */
    private fun isolate(scenario: Scenario, policy: RunPolicy): Scenario =
        if (policy.isolateIterations && scenario.binding != BindScope.THIS_RUN) {
            scenario.copy(binding = BindScope.THIS_RUN)
        } else {
            scenario
        }

    /** A run that stopped part-way is neither passed nor failed: it stopped checking. */
    private fun stateOf(result: ScenarioResult): RunState =
        when {
            result.steps.any { it.kind == "stopped" } -> RunState.STOPPED
            result.passed -> RunState.PASSED
            else -> RunState.FAILED
        }

    private fun finish(set: RunSet, stopped: Boolean, from: Int, onProgress: (RunSet) -> Unit): RunSet {
        var done = set
        for (i in from until done.entries.size) {
            if (done.entries[i].state != RunState.PENDING) continue
            done =
                done.withEntry(i) {
                    it.copy(
                        state = RunState.SKIPPED,
                        note = if (stopped) "the set was stopped" else "an earlier entry failed",
                    )
                }
        }
        val status =
            when {
                stopped || done.entries.any { it.state == RunState.STOPPED } -> RunSetStatus.STOPPED
                done.entries.any { it.state == RunState.FAILED } -> RunSetStatus.FAILED
                else -> RunSetStatus.PASSED
            }
        done = done.copy(status = status, finishedAt = host.now())
        host.writeSet(done)
        onProgress(done)
        return done
    }
}

/** How a set is named and planned. The id is also the record directory, so it has to be sortable and safe. */
object RunSets {
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)

    /** `2026-08-28T09-36-02-nightly` — sorts chronologically in a directory listing, and says what it was. */
    fun id(now: Long, label: String): String {
        val slug =
            buildString { for (c in label.lowercase()) append(if (c in 'a'..'z' || c in '0'..'9') c else '-') }
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(40)
        return stamp.format(Instant.ofEpochMilli(now)) + (if (slug.isBlank()) "" else "-$slug")
    }

    /** One scenario, N times — the flake hunt. */
    fun repeat(scenario: Scenario, times: Int, now: Long, policy: RunPolicy = RunPolicy()): RunSet {
        val label = if (times == 1) scenario.name else "${scenario.name} ×$times"
        return RunSet(
            id = id(now, scenario.name),
            label = label,
            source = RunSource.Repeat(scenario.id, times),
            entries = (1..times).map { RunEntry(scenario.id, scenario.name, iteration = it) },
            policy = policy,
        )
    }

    /**
     * One scenario, once per live row of its own table — the outline, run.
     *
     * Null when the scenario has no rows to run: an outline whose rows are all parked is not a set of zero
     * entries that passes, it is a request that cannot be honoured, and the caller says so.
     */
    fun examples(
        scenario: Scenario,
        now: Long,
        policy: RunPolicy = RunPolicy(),
        /** Row names to run, or null for every live row — a debug run of one row of eight. */
        only: List<String>? = null,
    ): RunSet? {
        val live = scenario.examples?.live.orEmpty()
        val rows = if (only == null) live else live.filter { it.name in only }
        if (rows.isEmpty()) return null
        return RunSet(
            id = id(now, scenario.name),
            label = "${scenario.name} — ${rows.size} rows",
            source = RunSource.Examples(scenario.id),
            entries = rows.map { RunEntry(scenario.id, scenario.name, row = it) },
            policy = policy,
        )
    }

    /** N scenarios, once each — the overnight suite. */
    fun suite(scenarios: List<Scenario>, source: RunSource, label: String, now: Long, policy: RunPolicy = RunPolicy()): RunSet =
        RunSet(
            id = id(now, label),
            label = label,
            source = source,
            entries = scenarios.map { RunEntry(it.id, it.name) },
            policy = policy,
        )
}
