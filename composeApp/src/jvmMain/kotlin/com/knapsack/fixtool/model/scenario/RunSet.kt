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

    /**
     * What one entry is called, wherever a name is needed: `book-a-trade`, `book-a-trade #3`, or
     * `book-a-trade [EUR/USD partial fill]` — the way a parameterized test has always named itself.
     */
    fun nameOf(index: Int): String {
        val entry = entries.getOrNull(index) ?: return ""
        val repeated = entries.count { it.scenarioId == entry.scenarioId && it.row == null } > 1
        return entry.scenarioName +
            when {
                entry.lane != null -> " [lane ${entry.lane.slot}]"
                entry.row != null -> " [${entry.row.name}]"
                repeated || entry.iteration > 1 -> " #${entry.iteration}"
                else -> ""
            }
    }

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
    data class Saved(
        val setName: String,
    ) : RunSource

    object Favourites : RunSource

    data class Filtered(
        val text: String,
    ) : RunSource

    data class Selected(
        val ids: List<String>,
    ) : RunSource

    data class Repeat(
        val scenarioId: String,
        val times: Int,
    ) : RunSource

    /** One entry per row of the scenario's own table — the outline, run. */
    data class Examples(
        val scenarioId: String,
    ) : RunSource

    /** One entry per session of a multi-session profile — the flow, run by many clients at once. */
    data class FanOut(
        val scenarioId: String,
        val profileId: String,
    ) : RunSource
}

/**
 * **A lane is a session slot, named by the number the profile gave it — not by its position in a list.**
 *
 * `getProfileSessions` answers in append order, and a slot that dropped and was refilled goes to the end;
 * numbering lanes by that order would make lane 7 a different client on the next run, and the whole point
 * of a lane is that it is *the same client* every time — `LOADGEN07`, run after run and record after
 * record.
 */
data class Lane(
    val slot: Int,
    val sessionTitle: String,
    val senderCompID: String,
    val qualifier: String,
) {
    /**
     * The four names a lane puts in the run's scope — **exactly the four Bulk Send already seeds**, so a
     * scenario written for one works in the other without ceremony: `11=ORD-${sessionIndex}` gives every
     * lane its own ClOrdID, and `262=MD-${sessionIndex}` its own MDReqID.
     *
     * `sessionIndex` is the **profile slot**, where Bulk Send uses the target's position in the logged-on
     * list. Bulk Send is a one-shot over whoever is up; a lane is an identity that has to mean the same
     * thing on every run and in every record.
     */
    fun seed(): Map<String, String> =
        mapOf(
            "sessionIndex" to slot.toString(),
            "sessionQualifier" to qualifier,
            "sessionTitle" to sessionTitle,
            "sessionSenderCompID" to senderCompID,
        )
}

/** One request in a set: which scenario, which iteration of it, and what became of it. */
data class RunEntry(
    val scenarioId: String,
    val scenarioName: String,
    /** 1-based; always 1 for a plain suite entry. */
    val iteration: Int = 1,
    /**
     * The table row this entry runs, when it has one — its cells are the scope the run starts with.
     *
     * Which row an entry ran is the *entry's* business, exactly as which iteration it was is. That is why
     * the report, the record, the reconcile route and the JUnit renderer all keep working: nothing below
     * this line had to learn what an outline is.
     */
    val row: ExampleRow? = null,
    /** The fan-out lane this entry runs on, when it has one — its session, and its identity in the scope. */
    val lane: Lane? = null,
    val sessionMap: Map<String, String> = emptyMap(),
    val state: RunState = RunState.PENDING,
    val result: ScenarioResult? = null,
    val durationMs: Long? = null,
    /** The entry's record file, once written — the artifact this whole design is about. */
    val record: String? = null,
    /** Why an entry was skipped, when it was: the scenario was deleted, or an earlier one failed. */
    val note: String? = null,
) {
    /**
     * **The scope this entry starts with** — its row's cells, its lane's identity, or neither.
     *
     * One mechanism, and that is where the four readings of "multi-run" stop being separate features: a
     * lane is a run whose scope carries its session's identity, a row is a run whose scope carries the
     * table's values, an iteration is a run whose scope carries neither, and a scenario saying
     * `11=ORD-${sessionIndex}-${clOrdSuffix}` draws on both without knowing they came from different
     * places.
     */
    val seed: Map<String, String> get() = row?.values.orEmpty() + lane?.seed().orEmpty()

    /** Who supplied a seeded name — a reader deciding what a value means needs the difference. */
    fun sourceOf(name: String): VariableSource =
        if (lane != null && name in lane.seed()) VariableSource.LANE else VariableSource.ROW

    /** The session a step that names none runs on: this lane's, so fifty lanes do not share session 0. */
    val defaultSession: String? get() = lane?.sessionTitle
}

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
    /**
     * **How many entries may run at once.** One, unless the entries are lanes.
     *
     * The run slot exists because two runners would race each other's consumed-message cursors — cursors
     * are per-run over per-session logs, so two runs whose sessions are **disjoint** cannot interfere, and
     * that is the whole licence for this being greater than one. Disjointness is decided when the set is
     * planned, after every step's session is resolved; the scheduler only honours the number.
     */
    val concurrency: Int = 1,
)

enum class RunSetStatus { RUNNING, PASSED, FAILED, STOPPED }
