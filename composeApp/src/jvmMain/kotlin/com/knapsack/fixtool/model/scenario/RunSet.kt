package com.knapsack.fixtool.model.scenario

/**
 * **A run set is an ordered list of run requests, and it is the whole of multi-run.**
 *
 * "Can we multi-run a scenario?" is four questions — is this flow flaky (one scenario, N iterations),
 * what broke overnight (N scenarios, one each), does it hold for every instrument (one scenario, N
 * parameter rows) and does it hold with fifty clients (one scenario, N lanes) — and they are the same
 * feature seen from four sides. Each is a list of `(scenario, iteration)` requests producing a list of
 * the [ScenarioResult] that already exists. **A single run is a set of one**, which is the property
 * that makes this cheap: nothing downstream of the runner had to learn a second shape of report.
 *
 * The entries are planned **up front** so the queue is visible before it runs — a suite that discovers
 * its own length as it goes cannot show progress, and progress is most of what a twelve-scenario run
 * has to say while it is running.
 */
data class RunSet(
    /** Also the record directory: `~/.fixtool/runs/<id>/`. */
    val id: String,
    /** "nightly — 12 scenarios" / "book-a-trade ×20". What the rail and the report call it. */
    val label: String,
    val source: RunSource,
    val entries: List<RunEntry>,
    val policy: RunPolicy = RunPolicy(),
    val startedAt: Long = 0L,
    val finishedAt: Long? = null,
    /**
     * Set by the scheduler rather than derived. STOPPED is not a shape the entry states can be read
     * back out of — a stopped set and a set still queuing both have entries that never ran.
     */
    val status: RunSetStatus = RunSetStatus.RUNNING,
) {
    val total: Int get() = entries.size
    val done: Int get() = entries.count { it.state.finished }
    val passed: Int get() = entries.count { it.state == RunState.PASSED }
    val failed: Int get() = entries.count { it.state == RunState.FAILED }

    /** The same entries with the one at [index] replaced — the scheduler's only mutation. */
    fun withEntry(index: Int, edit: (RunEntry) -> RunEntry): RunSet =
        copy(entries = entries.mapIndexed { i, e -> if (i == index) edit(e) else e })
}

/** Where a set's entries came from. A [Saved] set is the only one that persists; the rest describe a click. */
sealed interface RunSource {
    /** A named set on disk — what CI selects, because CI selects by a name in a checkout. */
    data class Saved(val setName: String) : RunSource

    object Favourites : RunSource

    data class Filtered(val text: String) : RunSource

    data class Selected(val ids: List<String>) : RunSource

    data class Repeat(val scenarioId: String, val times: Int) : RunSource
}

/** One request in a set: which scenario, which iteration of it, and what became of it. */
data class RunEntry(
    val scenarioId: String,
    val scenarioName: String,
    /** 1-based; always 1 for a plain suite entry. */
    val iteration: Int = 1,
    val sessionMap: Map<String, String> = emptyMap(),
    val state: RunState = RunState.PENDING,
    val result: ScenarioResult? = null,
    val durationMs: Long? = null,
    /** The entry's record file, once written — the artifact this whole design is about. */
    val record: String? = null,
    /** Why an entry was skipped, when it was: the scenario was deleted, or an earlier one failed. */
    val note: String? = null,
)

enum class RunState {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,

    /** Never run: stop-on-first-failure got there first, the set was stopped, or the scenario is gone. */
    SKIPPED,

    /** Ran, and was stopped part-way. Not passed — it stopped checking. */
    STOPPED,
    ;

    val finished: Boolean get() = this != PENDING && this != RUNNING
}

/** How a set runs. Concurrency is fan-out's, and is deliberately absent until it exists. */
data class RunPolicy(
    /**
     * Off by default, and the default is the argument: a suite exists to produce the whole morning's
     * picture, and a flake hunt wants "3 of 20 failed", not "failed at 4". CI gates want the opposite
     * and can say so.
     */
    val stopOnFirstFailure: Boolean = false,
    val pauseBetweenMs: Long = 0,
    /**
     * **Each entry runs under `BindScope.THIS_RUN`** — an in-memory copy, never a file write.
     *
     * Under the default `ANY` an expect may bind a message that arrived before the run started, so
     * iteration 2 can bind iteration 1's ExecutionReport and report that the venue answered when it has
     * not. On a single run that is a caveat the report prints. On a twenty-times repeat it is a false
     * green *by construction*, and a feature whose purpose is catching flakiness must not manufacture
     * passes.
     *
     * It is not a reset of everything: it does not clear a session's messages, and it does not clear a
     * venue's order book. Those are the scenario's own setup steps — `ClearMessages` and
     * `ClearOrderBook` — and a capture writes both.
     */
    val isolateIterations: Boolean = true,
)

enum class RunSetStatus { RUNNING, PASSED, FAILED, STOPPED }
