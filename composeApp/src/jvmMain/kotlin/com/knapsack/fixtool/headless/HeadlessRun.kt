package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.service.AppSettingsService
import com.knapsack.fixtool.service.ConnectionProfileService
import com.knapsack.fixtool.service.ScenarioCodec
import com.knapsack.fixtool.service.ScenarioReport
import com.knapsack.fixtool.service.ScenarioRunner
import com.knapsack.fixtool.service.ScenarioService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

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
    ) {
        companion object {
            @Suppress("ReturnCount")
            fun parse(args: List<String>): Options? {
                var target = ""
                var junit: String? = null
                var json: String? = null
                var home: String? = null
                val sessions = mutableMapOf<String, String>()
                var i = 0
                while (i < args.size) {
                    val arg = args[i]
                    when {
                        arg == "--junit" -> junit = args.getOrNull(++i) ?: return null
                        arg == "--json" -> json = args.getOrNull(++i) ?: return null
                        arg == "--home" -> home = args.getOrNull(++i) ?: return null
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
                return Options(target, junit, json, sessions, home)
            }
        }
    }

    private val USAGE =
        """
        fixtool run <scenario> [options]

          <scenario>          a saved scenario's id or name, or a path to a scenario .json file

          --junit <file>      write the JUnit XML report to <file>
          --json  <file>      write the full JSON report to <file>
          --session <a>=<b>   run the steps that name session <a> against session <b> instead
                              (a throwaway remap for this run; nothing is saved)
          --home <dir>        read profiles, settings and saved scenarios from <dir> instead of
                              ~/.fixtool — for running against config checked in beside the code

        Exits 0 when the scenario passes, 1 when it runs and fails, 2 when it cannot be run.
        Sessions the scenario names are connected from the saved profiles of the same name.
        """.trimIndent()
}
