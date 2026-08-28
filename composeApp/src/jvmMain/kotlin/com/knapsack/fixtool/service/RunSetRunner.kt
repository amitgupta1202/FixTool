package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.BindScope
import com.knapsack.fixtool.model.scenario.Lane
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

/**
 * A fan-out, planned — or the sentence saying why it cannot be.
 *
 * Every refusal here is a thing the author can act on, which is why none of them is a silent no-op: a
 * fan-out that quietly ran one lane, or ran fifty that trampled each other, would be worse than one that
 * did not run.
 */
sealed interface FanOutPlan {
    data class Ready(
        val set: RunSet,
    ) : FanOutPlan

    data class Refused(
        val why: String,
    ) : FanOutPlan
}

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
    fun runOne(scenario: Scenario, entry: RunEntry): EntryOutcome?

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
        val current = set.copy(startedAt = host.now(), status = RunSetStatus.RUNNING)
        host.writeSet(current)
        onProgress(current)
        return if (current.policy.concurrency > 1) inParallel(current, onProgress) else inOrder(current, onProgress)
    }

    /**
     * **Lanes, at once.** The licence is the run slot's own reasoning: consumed-message cursors are
     * per-run over per-session logs, so two runs whose sessions are disjoint cannot interfere. Disjointness
     * is settled when the set is planned — after every step's session is resolved, including the ones that
     * name none — so all this has to do is honour the number.
     *
     * A failure under `stopOnFirstFailure` stops *starting* lanes; the ones already in flight finish,
     * because killing a client mid-order tells you less than letting it land.
     */
    private fun inParallel(start: RunSet, onProgress: (RunSet) -> Unit): RunSet {
        val lock = Any()
        var current = start
        val lanes = minOf(start.policy.concurrency, start.entries.size)
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(lanes)
        val stop =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        try {
            val running =
                start.entries.indices.map { index ->
                    pool.submit {
                        if (stop.get() || host.cancelled()) return@submit
                        synchronized(lock) {
                            current = current.withEntry(index) { it.copy(state = RunState.RUNNING) }
                            onProgress(current)
                        }
                        val finished = executeEntry(start, index)
                        synchronized(lock) {
                            current = current.withEntry(index) { finished }
                            host.writeSet(current)
                            onProgress(current)
                        }
                        if (finished.state != RunState.PASSED && start.policy.stopOnFirstFailure) stop.set(true)
                    }
                }
            running.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        // Whatever never started is still PENDING, and `finish` says why — stopped, or an earlier failure.
        return finish(current, stopped = host.cancelled(), from = 0, onProgress = onProgress)
    }

    private fun inOrder(set: RunSet, onProgress: (RunSet) -> Unit): RunSet {
        var current = set
        for (index in current.entries.indices) {
            if (host.cancelled()) return finish(current, stopped = true, from = index, onProgress = onProgress)

            current = current.withEntry(index) { it.copy(state = RunState.RUNNING) }
            onProgress(current)

            current = current.withEntry(index) { executeEntry(current, index) }
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
     * **One entry, start to finish** — the work both paths share, so a lane and a suite entry cannot come
     * to differ in what they record or what they call themselves.
     *
     * Reads the set only for its id and its policy, and neither moves while it runs, so this is safe to
     * call from several threads at once against the same snapshot.
     */
    private fun executeEntry(set: RunSet, index: Int): RunEntry {
        val entry = set.entries[index]
        // A set is planned up front, and a scenario can be deleted between the planning and the running.
        // Skipped by name rather than failing the set: nothing about the venue is known.
        val scenario =
            host.scenario(entry.scenarioId)
                ?: return entry.copy(
                    state = RunState.SKIPPED,
                    note = "scenario '${entry.scenarioName}' is no longer on disk",
                )
        val startedAt = host.now()
        val asRun = isolate(scenario, set.policy)
        // The entry carries everything the run needs that the scenario does not: its row's cells, its
        // lane's identity, the session remap, and the session a step that names none runs on. One path,
        // and neither the outline nor the fan-out is a second runner.
        val outcome = host.runOne(asRun, entry)
        val elapsed = host.now() - startedAt
        if (outcome == null) {
            return entry.copy(state = RunState.SKIPPED, durationMs = elapsed, note = "the run slot was not free")
        }
        val record =
            RunRecord(
                setId = set.id,
                entry = index + 1,
                iteration = entry.iteration,
                row = entry.row,
                lane = entry.lane,
                scenarioId = scenario.id,
                scenarioName = scenario.name,
                // As it ran, isolation and all — the record is evidence, and what was asserted is half of it.
                scenario = asRun,
                startedAt = startedAt,
                durationMs = outcome.result.durationMs ?: elapsed,
                result = outcome.result,
                messages = outcome.evidence.messages,
                bound = outcome.evidence.bound,
                dropped = outcome.evidence.dropped,
            )
        val file = host.write(record)
        return entry.copy(
            state = stateOf(outcome.result),
            result = outcome.result,
            durationMs = outcome.result.durationMs ?: elapsed,
            record = file,
        )
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

    /**
     * **One scenario, one entry per lane, concurrent** — the flow, run by many clients at once.
     *
     * [over] is the scenario session being spread across the lanes; null means the scenario names none and
     * every lane runs on its own session. Refusals are named rather than silent, because each one is a
     * thing the author can fix.
     */
    fun fanOut(
        scenario: Scenario,
        profileId: String,
        lanes: List<Lane>,
        over: String?,
        now: Long,
        policy: RunPolicy = RunPolicy(),
    ): FanOutPlan {
        if (lanes.isEmpty()) return FanOutPlan.Refused("no lanes are logged on, so there is nothing to fan out over")
        val named = (scenario.setup + scenario.steps + scenario.teardown).filterNot { it.muted }.mapNotNull { it.session }.distinct()
        val spread = over ?: named.singleOrNull()
        if (named.size > 1 && spread == null) {
            return FanOutPlan.Refused(
                "'${scenario.name}' drives ${named.size} sessions (${named.joinToString()}) — name the one to " +
                    "fan out over, and the rest stay as they are.",
            )
        }
        if (spread != null && spread !in named) {
            return FanOutPlan.Refused("'${scenario.name}' has no step on session '$spread'")
        }
        // **The pinned leg.** Fifty lanes sharing one back-office session share its message log and its
        // consumed cursor, so lane 12 could bind the reply to lane 30's order and the report would be
        // indistinguishable from a venue bug. That is the disjointness the concurrency licence rests on,
        // checked here — after the sessions a step will *actually* touch are known, the ones that name none
        // included, since those resolve to each lane's own session.
        val pinned = named.filterNot { it == spread }
        if (pinned.isNotEmpty() && policy.concurrency > 1) {
            return FanOutPlan.Refused(
                "${pinned.joinToString()} ${if (pinned.size == 1) "is" else "are"} pinned to one session, and " +
                    "${lanes.size} lanes would share its message log — lane 1 could bind lane ${lanes.size}'s " +
                    "reply. Give it a multi-session profile of its own, fan out only the other leg, or set " +
                    "concurrency to 1.",
            )
        }
        return FanOutPlan.Ready(
            RunSet(
                id = id(now, "${scenario.name}-fanout"),
                label = "${scenario.name} ×${lanes.size} lanes",
                source = RunSource.FanOut(scenario.id, profileId),
                entries =
                    lanes.map { lane ->
                        RunEntry(
                            scenarioId = scenario.id,
                            scenarioName = scenario.name,
                            lane = lane,
                            sessionMap = spread?.let { mapOf(it to lane.sessionTitle) }.orEmpty(),
                        )
                    },
                policy = policy,
            ),
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
