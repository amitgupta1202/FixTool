package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunPolicy
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.service.AppSettingsService
import com.knapsack.fixtool.service.ConnectionProfileService
import com.knapsack.fixtool.service.EntryOutcome
import com.knapsack.fixtool.service.FanOutPlan
import com.knapsack.fixtool.service.RunRecord
import com.knapsack.fixtool.service.RunRecordStore
import com.knapsack.fixtool.service.RunRecorder
import com.knapsack.fixtool.service.RunSetHost
import com.knapsack.fixtool.service.RunSetRunner
import com.knapsack.fixtool.service.RunSetStore
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.service.ScenarioCodec
import com.knapsack.fixtool.service.ScenarioReport
import com.knapsack.fixtool.service.ScenarioRunner
import com.knapsack.fixtool.service.ScenarioService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a saved scenario with no window, and **exits with a status code**.
 *
 * The runner has always been pure and the report has always been able to render JUnit — what was
 * missing was a way in that is not a GUI. Running a scenario unattended meant booting Compose on a
 * virtual display, opening the control port, POSTing to it, and translating a JSON field into an
 * exit code by hand. Now:
 *
 * ```
 * fixtool run smoke-nos --junit reports/smoke.xml
 * echo $?      # 0 passed, 1 failed
 * ```
 *
 * The strongest driver is not CI but the **batch sweep** — as a saved-scenario library grows there
 * has to be a way to ask "run all of these and tell me what broke", and one process per scenario
 * with a real exit code is that way.
 */
object HeadlessRun {
    /** 0 = the scenario passed. 1 = it ran and failed. 2 = it could not be run at all. */
    const val EXIT_PASSED = 0
    const val EXIT_FAILED = 1
    const val EXIT_USAGE = 2

    /** True when [args] ask for a headless run, so `main` knows not to open a window. */
    fun handles(args: Array<String>): Boolean = args.firstOrNull() in setOf("run", "--help", "-h", "help")

    @Suppress("ReturnCount")
    fun execute(args: Array<String>, out: Appendable, err: Appendable): Int {
        if (args.firstOrNull() != "run") {
            out.appendLine(USAGE)
            return if (args.firstOrNull() in setOf("--help", "-h", "help")) EXIT_PASSED else EXIT_USAGE
        }
        val options =
            Options.parse(args.drop(1)) ?: run {
                err.appendLine("fixtool: could not read the arguments")
                err.appendLine(USAGE)
                return EXIT_USAGE
            }
        if (options.isSet) return executeSet(options, out, err)
        if (options.target.isBlank()) {
            err.appendLine("fixtool run: name a scenario (an id, a saved name, or a path to a .json file)")
            err.appendLine(USAGE)
            return EXIT_USAGE
        }

        val home = options.home
        val settings = AppSettingsService(customSettingsDir = home).loadSettings()
        val scenario = loadScenario(options.target, home, err) ?: return EXIT_USAGE
        val profiles = ConnectionProfileService(customPath = home?.let { "$it/connection_profiles.json" } ?: "").loadProfiles()
        val dictionary = dictionaryFor(settings, err)

        err.appendLine("fixtool: running '${scenario.name}'")
        val host = HeadlessScenarioHost(profiles, dictionary, settings) { err.appendLine("fixtool: $it") }
        val result =
            try {
                ScenarioRunner(host).run(scenario, options.sessionMap)
            } finally {
                // Always, including when a step threw: a run that leaves a venue holding an open
                // session is worse than one that fails, and the process is about to exit anyway.
                host.disconnectAll()
            }

        out.append(summary(result))
        options.junitFile?.let { write(it, ScenarioReport.toJUnitXml(result), err) }
        options.jsonFile?.let { write(it, ScenarioReport.toJson(result).toString(), err) }
        return if (result.passed) EXIT_PASSED else EXIT_FAILED
    }

    /**
     * **The batch sweep this file's own header called the strongest driver**, finally supported.
     *
     * One process, many scenarios, one exit code — and the records on disk that a build can attach. The
     * scheduler is the same one the app uses, over a headless host: the same isolation, the same
     * stop-on-failure, the same `set.json`, so a suite behaves identically whether it was started by a
     * click or by a build step.
     */
    @Suppress("ReturnCount", "LongMethod")
    private fun executeSet(options: Options, out: Appendable, err: Appendable): Int {
        val home = options.home
        val settings = AppSettingsService(customSettingsDir = home).loadSettings()
        val scenarioService = ScenarioService(customDir = home?.let { "$it/scenarios" } ?: "")
        val saved = scenarioService.list()
        val now = System.currentTimeMillis()

        // The host comes up before the set is planned, because a fan-out set cannot be planned without
        // it: its entries are one per lane that actually logged on, which is a fact about live sessions
        // rather than about the scenario file. Nothing is dialled by merely constructing it.
        val profiles = ConnectionProfileService(customPath = home?.let { "$it/connection_profiles.json" } ?: "").loadProfiles()
        val host = HeadlessScenarioHost(profiles, dictionaryFor(settings, err), settings) { err.appendLine("fixtool: $it") }

        val set =
            when {
                options.set != null -> {
                    val file =
                        RunSetStore(customDir = home?.let { "$it/sets" } ?: "").load(options.set)
                            ?: run {
                                err.appendLine("fixtool: no saved run set '${options.set}'")
                                return EXIT_USAGE
                            }
                    val planned = file.copy(policy = options.policy(file.policy)).plan(saved, now)
                    planned.missing.forEach { err.appendLine("fixtool: set '${options.set}' names '$it', which is not a saved scenario") }
                    planned.set
                }
                options.all -> {
                    if (saved.isEmpty()) {
                        err.appendLine("fixtool: ${scenarioService.directory} holds no scenarios to run")
                        return EXIT_USAGE
                    }
                    val entries = saved.flatMap { sc -> (1..options.repeat).map { RunEntry(sc.id, sc.name, iteration = it) } }
                    RunSet(
                        id = RunSets.id(now, "all"),
                        label = "all scenarios (${saved.size})",
                        source = RunSource.Selected(saved.map { it.id }),
                        entries = entries,
                        policy = options.policy(RunPolicy()),
                    )
                }
                options.fanOut != null -> {
                    val scenario = loadScenario(options.target, home, err) ?: return EXIT_USAGE
                    val matches = profiles.filter { it.id == options.fanOut || it.name == options.fanOut }.distinctBy { it.id }
                    val profile =
                        when (matches.size) {
                            0 -> {
                                err.appendLine("fixtool: no saved connection profile named '${options.fanOut}'")
                                return EXIT_USAGE
                            }
                            1 -> matches.single()
                            // Refused rather than guessed, as connectSession does: fanning fifty lanes at
                            // the wrong venue is worse than stopping.
                            else -> {
                                err.appendLine("fixtool: ${matches.size} saved profiles answer to '${options.fanOut}' — rename one")
                                return EXIT_USAGE
                            }
                        }
                    if (profile.config.connectionType != FixConnectionConfig.ConnectionType.INITIATOR) {
                        err.appendLine(
                            "fixtool: '${profile.name}' is an acceptor, and an acceptor has one session by " +
                                "construction — fan out from the client profile instead",
                        )
                        return EXIT_USAGE
                    }
                    err.appendLine("fixtool: opening ${profile.config.sessionCount.coerceAtLeast(1)} lanes of '${profile.name}'")
                    val lanes = host.openLanes(profile)
                    val shortfall = profile.config.sessionCount.coerceAtLeast(1) - lanes.size
                    // Reported, not refused: 38 lanes of 50 is still a load test.
                    if (shortfall > 0) {
                        err.appendLine("fixtool: $shortfall of '${profile.name}'s sessions did not reach LOGGED_ON")
                    }
                    val policy = options.policy(RunPolicy(concurrency = lanes.size.coerceAtLeast(1)))
                    when (val plan = RunSets.fanOut(scenario, profile.id, lanes, options.over, now, policy)) {
                        is FanOutPlan.Refused -> {
                            err.appendLine("fixtool: ${plan.why}")
                            return EXIT_USAGE
                        }
                        is FanOutPlan.Ready -> plan.set
                    }
                }
                options.rows != null -> {
                    val scenario = loadScenario(options.target, home, err) ?: return EXIT_USAGE
                    val only = options.rows.takeIf { it.isNotEmpty() }
                    only?.filterNot { name -> scenario.examples?.live.orEmpty().any { it.name == name } }
                        ?.forEach { err.appendLine("fixtool: '${scenario.name}' has no live row named '$it'") }
                    RunSets.examples(scenario, now, options.policy(RunPolicy()), only)
                        ?: run {
                            err.appendLine(
                                "fixtool: '${scenario.name}' has no rows to run — " +
                                    "${scenario.examples?.rows?.size ?: 0} row(s), " +
                                    "${scenario.examples?.live?.size ?: 0} live",
                            )
                            return EXIT_USAGE
                        }
                }
                else -> {
                    val scenario = loadScenario(options.target, home, err) ?: return EXIT_USAGE
                    RunSets.repeat(scenario, options.repeat, now, options.policy(RunPolicy()))
                }
            }
        if (set.entries.isEmpty()) {
            err.appendLine("fixtool: that run set has no entries to run")
            return EXIT_USAGE
        }

        val byId = (saved + listOfNotNull(loadTargetIfFile(options, home))).associateBy { it.id }
        val store = RunRecordStore(customDir = home?.let { "$it/runs" } ?: "")
        err.appendLine("fixtool: running ${set.entries.size} entries — ${set.label}")

        val done =
            try {
                store.begin(set)
                RunSetRunner(HeadlessSetHost(byId, host, store, settings)).run(set) { progress ->
                    val last = progress.entries.lastOrNull { it.state.finished }
                    if (last != null && progress.done > 0) {
                        err.appendLine("fixtool: ${progress.done}/${progress.total} ${last.scenarioName} ${last.state.name.lowercase()}")
                    }
                }
            } finally {
                host.disconnectAll()
            }
        store.prune(settings.runRecordsKept)

        out.append(setSummary(done, store))
        writeSetReports(options, done, store, err)
        err.appendLine("fixtool: records in ${store.directoryFor(done.id)}")
        return if (done.status == RunSetStatus.PASSED) EXIT_PASSED else EXIT_FAILED
    }

    /** A `--repeat` target that is a file rather than a saved scenario still has to be findable by id. */
    private fun loadTargetIfFile(options: Options, home: String?): Scenario? =
        if (options.set == null && !options.all && options.target.isNotBlank()) {
            loadScenario(options.target, home, StringBuilder())
        } else {
            null
        }

    /**
     * `--junit <file.xml>` writes the whole set as one `<testsuites>`; `--junit <dir>` writes one file
     * per entry. Both, because the two consumers are different: a CI step that ingests one report, and a
     * build that publishes an artifact per test.
     */
    private fun writeSetReports(options: Options, set: RunSet, store: RunRecordStore, err: Appendable) {
        val junit = options.junitFile ?: return
        val named =
            set.entries.indices.mapNotNull { i ->
                val record = store.readEntry(set.id, i + 1) ?: return@mapNotNull null
                // One place decides what an entry is called — `#3` for an iteration, `[row name]` for an
                // outline — so the XML, the log and the rail cannot come to disagree about it.
                set.nameOf(i) to record.result
            }
        if (junit.endsWith(".xml")) {
            write(junit, ScenarioReport.toJUnitXml(named), err)
        } else {
            named.forEachIndexed { i, (name, result) ->
                write("$junit/%02d-%s.xml".format(i + 1, name.replace(Regex("[^A-Za-z0-9_-]"), "-")), ScenarioReport.toJUnitXml(result), err)
            }
        }
        options.jsonFile?.let { path ->
            write(path, ScenarioReport.toJson(named.last().second).toString(), err)
        }
    }

    /** The set as a build log wants it: one line per entry, then the verdict, then where the evidence is. */
    private fun setSummary(set: RunSet, store: RunRecordStore): String =
        buildString {
            set.entries.forEachIndexed { i, entry ->
                val mark =
                    when (entry.state) {
                        RunState.PASSED -> "  ok  "
                        RunState.FAILED -> "FAIL  "
                        RunState.STOPPED -> "STOP  "
                        else -> "skip  "
                    }
                val where = entry.durationMs?.let { " ${it}ms" }.orEmpty()
                appendLine("$mark${i + 1}/${set.total} ${set.nameOf(i)}$where")
                entry.note?.let { appendLine("        $it") }
                // The first failure, named, because a build log has no report to click into.
                entry.result?.steps?.firstOrNull { !it.passed && it.phase != "teardown" }?.let { step ->
                    appendLine("        ${step.phase}[${step.stepIndex}] ${step.kind}: ${step.detail.orEmpty().take(200)}")
                }
            }
            appendLine(
                when (set.status) {
                    RunSetStatus.PASSED -> "PASSED  ${set.label} (${set.passed}/${set.total})"
                    RunSetStatus.STOPPED -> "STOPPED ${set.label} (${set.done}/${set.total} ran)"
                    else -> "FAILED  ${set.label} (${set.failed} of ${set.total} failed)"
                },
            )
            store.directoryFor(set.id).let { appendLine("        records: $it") }
        }

    /** A scenario by saved id or name, or a path to a JSON file — a CI checkout has files, not a store. */
    private fun loadScenario(target: String, home: String?, err: Appendable): Scenario? {
        val file = File(target)
        if (file.isFile) {
            return try {
                ScenarioCodec.fromJson(Json.parseToJsonElement(file.readText()).jsonObject)
            } catch (e: Exception) {
                err.appendLine("fixtool: '$target' is not a readable scenario — ${e.message}")
                null
            }
        }
        val service = ScenarioService(customDir = home?.let { "$it/scenarios" } ?: "")
        val found = service.load(target) ?: service.list().firstOrNull { it.name == target }
        if (found == null) {
            err.appendLine("fixtool: no scenario '$target' — not a file, and no saved scenario of that id or name")
            val saved = service.list()
            if (saved.isEmpty()) {
                err.appendLine("fixtool: (${service.directory} holds no scenarios)")
            } else {
                err.appendLine("fixtool: saved scenarios: " + saved.joinToString(", ") { it.name })
            }
        }
        return found
    }

    /** The same dictionary the app would have loaded, from the same settings — a headless run must judge the same. */
    @Suppress("TooGenericExceptionCaught")
    private fun dictionaryFor(settings: AppSettings, err: Appendable): FixDictionaryAdapter =
        try {
            if (settings.useBundledDictionary || settings.defaultDataDictionary.isBlank()) {
                FixDictionaryAdapter.forVersion(settings.defaultFixVersion)
            } else {
                val configured = settings.defaultTransportDictionary
                val transport = if (configured.isBlank()) null else File(configured).takeIf { it.exists() }
                FixDictionaryAdapter.fromFiles(File(settings.defaultDataDictionary), transport)
            }
        } catch (e: Exception) {
            err.appendLine("fixtool: could not load the configured dictionary (${e.message}); falling back to the default")
            FixDictionaryAdapter.createDefault()
        }

    /**
     * One line per step, then the verdict. Written for a build log, where the reader has no diff view
     * to open — so a failing step prints its detail and its failing tags, and a passing one stays quiet.
     */
    private fun summary(result: ScenarioResult): String =
        buildString {
            result.steps.forEach { step ->
                appendLine("${if (step.passed) "  ok  " else "FAIL  "}${step.phase}[${step.stepIndex}] ${step.kind}")
                if (!step.passed) {
                    step.detail?.let { appendLine("        $it") }
                    step.tags.filterNot { it.passed }.forEach {
                        appendLine("        tag ${it.tag}: expected ${it.expected}, got ${it.actual ?: "<absent>"}")
                    }
                }
            }
            val failed = result.steps.count { !it.passed }
            appendLine(
                if (result.passed) {
                    "PASSED  ${result.scenario} (${result.steps.size} steps)"
                } else {
                    "FAILED  ${result.scenario} ($failed of ${result.steps.size} steps)"
                },
            )
        }

    @Suppress("TooGenericExceptionCaught")
    private fun write(path: String, content: String, err: Appendable) {
        try {
            val file = File(path)
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(content)
            err.appendLine("fixtool: wrote $path")
        } catch (e: Exception) {
            // Reported, never fatal: the run's verdict is the exit code, and losing the report file
            // must not turn a genuine pass into a failure.
            err.appendLine("fixtool: could not write $path — ${e.message}")
        }
    }

    /**
     * The set's world without a window: scenarios from the checkout, one long-lived session host, and the
     * same runs directory the app writes.
     *
     * The sessions stay up **across** entries. Bringing them down between scenarios would make a suite
     * mostly logon traffic and would change what it is testing — the venue sees one client for the whole
     * run, which is what a suite is meant to look like from the far side.
     */
    private class HeadlessSetHost(
        private val scenarios: Map<String, Scenario>,
        private val host: HeadlessScenarioHost,
        private val store: RunRecordStore,
        private val settings: AppSettings,
    ) : RunSetHost {
        override fun scenario(id: String): Scenario? = scenarios[id]

        override fun runOne(scenario: Scenario, entry: RunEntry): EntryOutcome {
            val recorder = RunRecorder()
            val judged = linkedMapOf<FixMessage, StepResult>()
            // Sampled while the entry runs rather than snapshotted after it: a session evicts inside a
            // single run, and the record is the only place that traffic will exist afterwards. A plain
            // daemon thread, because there is no scope here to launch into and nothing else needs one.
            val running = AtomicBoolean(true)
            val sampler =
                Thread {
                    while (running.get()) {
                        host.opened.forEach { session ->
                            recorder.observe(session.title, session.messages.value.filterIsInstance<FixMessage>())
                        }
                        Thread.sleep(SAMPLE_MS)
                    }
                }
            sampler.isDaemon = true
            sampler.start()
            try {
                val result =
                    ScenarioRunner(host, onExpectMatched = { message, step -> judged[message] = step })
                        .run(scenario, entry.sessionMap, entry.seed, entry::sourceOf)
                host.opened.forEach { session ->
                    recorder.observe(session.title, session.messages.value.filterIsInstance<FixMessage>())
                }
                return EntryOutcome(result, recorder.build(judged, settings.runRecordCap))
            } finally {
                running.set(false)
            }
        }

        override fun write(record: RunRecord): String? = store.write(record)

        override fun writeSet(set: RunSet) {
            store.writeSet(set)
        }

        override fun sleep(ms: Long) = Thread.sleep(ms)

        override fun now(): Long = System.currentTimeMillis()

        /** Nothing to stop a headless set with yet — the process is the job, and killing it is the stop. */
        override fun cancelled(): Boolean = false

        private companion object {
            const val SAMPLE_MS = 50L
        }
    }

    /** Parsed argv. Unknown flags are refused rather than ignored — a mistyped `--junit` must not run silently. */
    data class Options(
        val target: String,
        val junitFile: String? = null,
        val jsonFile: String? = null,
        val sessionMap: Map<String, String> = emptyMap(),
        /**
         * Where the profiles, settings and saved scenarios live — `~/.fixtool` unless given.
         *
         * A build box has no `~/.fixtool` and should not be made to grow one: the point of running from
         * a checkout is that the scenarios and the venue config under test are the ones in the checkout,
         * versioned beside the code they are testing.
         */
        val home: String? = null,
        /** A saved run set by name — what a build box selects, because it selects by a name in a checkout. */
        val set: String? = null,
        /** Every saved scenario, in name order. */
        val all: Boolean = false,
        val repeat: Int = 1,
        val pauseMs: Long = 0,
        val stopOnFailure: Boolean = false,
        /**
         * The Examples rows to run: empty = every live row, a list = only those, null = not an outline run.
         *
         * Three states rather than two, because "run the table" and "run this one row of it" are different
         * asks and `--row X` must not read as "and nothing else about the table matters".
         */
        val rows: List<String>? = null,
        /**
         * The multi-session initiator profile to spread the flow across — one entry per logged-on lane,
         * all at once. Null = not a fan-out run.
         */
        val fanOut: String? = null,
        /**
         * Which of the scenario's sessions the lanes replace, for a flow that drives more than one. Null
         * means the scenario names exactly one and that is the one, which is the common case.
         */
        val over: String? = null,
    ) {
        /** True when the arguments describe a batch rather than one run. */
        val isSet: Boolean get() = set != null || all || repeat > 1 || rows != null || fanOut != null

        /** The file's policy, with whatever the command line said about it applied over the top. */
        fun policy(base: RunPolicy): RunPolicy =
            base.copy(
                stopOnFirstFailure = stopOnFailure || base.stopOnFirstFailure,
                pauseBetweenMs = if (pauseMs > 0) pauseMs else base.pauseBetweenMs,
            )

        companion object {
            @Suppress("ReturnCount", "CyclomaticComplexMethod")
            fun parse(args: List<String>): Options? {
                var target = ""
                var junit: String? = null
                var json: String? = null
                var home: String? = null
                var set: String? = null
                var all = false
                var repeat = 1
                var pauseMs = 0L
                var stopOnFailure = false
                var rows: MutableList<String>? = null
                var fanOut: String? = null
                var over: String? = null
                val sessions = mutableMapOf<String, String>()
                var i = 0
                while (i < args.size) {
                    val arg = args[i]
                    when {
                        arg == "--junit" -> junit = args.getOrNull(++i) ?: return null
                        arg == "--json" -> json = args.getOrNull(++i) ?: return null
                        arg == "--home" -> home = args.getOrNull(++i) ?: return null
                        arg == "--set" -> set = args.getOrNull(++i) ?: return null
                        arg == "--all" -> all = true
                        arg == "--stop-on-failure" -> stopOnFailure = true
                        arg == "--rows" -> rows = rows ?: mutableListOf()
                        arg == "--row" -> {
                            val name = args.getOrNull(++i) ?: return null
                            rows = (rows ?: mutableListOf()).also { it += name }
                        }
                        arg == "--fan-out" -> fanOut = args.getOrNull(++i) ?: return null
                        arg == "--over" -> over = args.getOrNull(++i) ?: return null
                        arg == "--repeat" -> repeat = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                        arg == "--pause" -> pauseMs = parseDuration(args.getOrNull(++i)) ?: return null
                        arg == "--session" -> {
                            val pair = args.getOrNull(++i) ?: return null
                            val (from, to) = pair.split("=", limit = 2).takeIf { it.size == 2 } ?: return null
                            sessions[from] = to
                        }
                        arg.startsWith("-") -> return null
                        target.isEmpty() -> target = arg
                        else -> return null
                    }
                    i++
                }
                return Options(target, junit, json, sessions, home, set, all, repeat, pauseMs, stopOnFailure, rows, fanOut, over)
            }

            /** `500ms`, `2s`, or a bare number of milliseconds — the three ways somebody writes a pause. */
            private fun parseDuration(raw: String?): Long? {
                val text = raw?.trim()?.lowercase() ?: return null
                return when {
                    text.endsWith("ms") -> text.dropLast(2).toLongOrNull()
                    text.endsWith("s") -> text.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
                    else -> text.toLongOrNull()
                }?.takeIf { it >= 0 }
            }
        }
    }

    private val USAGE =
        """
        fixtool run <scenario> [options]
        fixtool run --set <name> [options]
        fixtool run --all [options]

          <scenario>          a saved scenario's id or name, or a path to a scenario .json file

          --set <name>        run a saved run set (~/.fixtool/sets/<name>.json)
          --all               run every saved scenario, in name order
          --repeat <n>        run each scenario n times (a flake hunt)
          --rows              run the scenario once per live row of its Examples table
          --row <name>        run only that row of the table (repeatable)
          --fan-out <profile> run the scenario once per logged-on session of a multi-session initiator
                              profile, all at once — a load run against the venue under test
          --over <session>    which of the scenario's sessions the lanes replace (only needed when it
                              drives more than one)
          --pause <500ms|2s>  wait between entries
          --stop-on-failure   end the batch at the first failing entry (CI); the default runs them all,
                              because "3 of 20 failed" is what a flake hunt needs

          --junit <file>      write the JUnit XML report to <file>
          --json  <file>      write the full JSON report to <file>
          --session <a>=<b>   run the steps that name session <a> against session <b> instead
                              (a throwaway remap for this run; nothing is saved)
          --home <dir>        read profiles, settings and saved scenarios from <dir> instead of
                              ~/.fixtool — for running against config checked in beside the code

        Exits 0 when everything passes, 1 when something runs and fails, 2 when it cannot be run.
        Sessions the scenario names are connected from the saved profiles of the same name.
        A batch writes a record per entry under <home>/runs/<set>/ — the report and the bytes — and
        `--junit <dir>` writes one XML per entry while `--junit <file>.xml` writes one <testsuites>.
        """.trimIndent()
}
