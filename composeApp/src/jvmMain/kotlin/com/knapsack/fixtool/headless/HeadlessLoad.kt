package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhase
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.AppSettingsService
import com.knapsack.fixtool.service.ConnectionProfileService
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.service.SavedMessagesService
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.service.load.LoadRecordStore
import com.knapsack.fixtool.service.load.LoadRefused
import com.knapsack.fixtool.service.load.LoadReportCodec
import com.knapsack.fixtool.service.load.LoadRunner
import com.knapsack.fixtool.service.load.LoadTemplates
import java.io.File

/**
 * **`fixtool load`**: a load run with no window, and an exit code a build can gate on.
 *
 * ```
 * fixtool load "NOS EUR/USD 1M" --profile LOADGEN --count 4000 --settle 60s --json reports/load.json
 * echo $?      # 0 everything answered · 1 unmatched, tool-limited or a strict-rate shortfall · 2 could not run
 * ```
 *
 * The verdicts are separate on purpose. A run where the venue answered everything and the tool could not
 * hold the rate for nineteen seconds says both, and exits 0 unless `--strict-rate` asked otherwise.
 */
object HeadlessLoad {
    /** Parsed argv after the `load` verb. Unknown flags are refused rather than ignored. */
    data class Options(
        val template: String,
        val profile: String,
        val count: Int? = null,
        val perSecond: Int? = null,
        val forMs: Long? = null,
        val settleMs: Long = LoadPlan.DEFAULT_SETTLE_MS,
        val listen: List<String> = emptyList(),
        val match: LoadMatch? = null,
        val replyType: String? = null,
        val seed: Map<String, String> = emptyMap(),
        val store: FixConnectionConfig.MessageStoreKind? = null,
        val log: FixConnectionConfig.MessageLogKind? = null,
        val strictRate: Boolean = false,
        val jsonFile: String? = null,
        val junitFile: String? = null,
        val home: String? = null,
    ) {
        /** Burst or rate, or null when the arguments say neither or both. */
        val shape: LoadShape?
            get() =
                when {
                    count != null && perSecond == null && forMs == null -> LoadShape.Burst(count)
                    count == null && perSecond != null && forMs != null -> LoadShape.Rate(perSecond, forMs)
                    else -> null
                }

        companion object {
            @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
            fun parse(args: List<String>): Options? {
                var template = ""
                var profile = ""
                var count: Int? = null
                var perSecond: Int? = null
                var forMs: Long? = null
                var settleMs = LoadPlan.DEFAULT_SETTLE_MS
                val listen = mutableListOf<String>()
                var match: LoadMatch? = null
                var replyType: String? = null
                val seed = linkedMapOf<String, String>()
                var store: FixConnectionConfig.MessageStoreKind? = null
                var log: FixConnectionConfig.MessageLogKind? = null
                var strictRate = false
                var json: String? = null
                var junit: String? = null
                var home: String? = null
                var i = 0
                while (i < args.size) {
                    val arg = args[i]
                    when {
                        arg == "--profile" -> profile = args.getOrNull(++i) ?: return null
                        arg == "--count" -> count = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                        arg == "--rate" -> perSecond = parseRate(args.getOrNull(++i)) ?: return null
                        arg == "--for" -> forMs = HeadlessRun.parseDuration(args.getOrNull(++i))?.takeIf { it > 0 } ?: return null
                        arg == "--settle" -> settleMs = HeadlessRun.parseDuration(args.getOrNull(++i)) ?: return null
                        arg == "--listen" -> listen += args.getOrNull(++i) ?: return null
                        arg == "--match" -> match = parseMatch(args.getOrNull(++i)) ?: return null
                        arg == "--reply-type" -> replyType = args.getOrNull(++i)?.takeIf { it.isNotBlank() } ?: return null
                        arg == "--set" -> {
                            val pair = args.getOrNull(++i) ?: return null
                            val (k, v) = pair.split("=", limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() } ?: return null
                            seed[k.trim()] = v
                        }
                        arg == "--store" -> store = enumOrNull<FixConnectionConfig.MessageStoreKind>(args.getOrNull(++i)) ?: return null
                        arg == "--log" -> log = enumOrNull<FixConnectionConfig.MessageLogKind>(args.getOrNull(++i)) ?: return null
                        arg == "--strict-rate" -> strictRate = true
                        arg == "--json" -> json = args.getOrNull(++i) ?: return null
                        arg == "--junit" -> junit = args.getOrNull(++i) ?: return null
                        arg == "--home" -> home = args.getOrNull(++i) ?: return null
                        arg.startsWith("-") -> return null
                        template.isEmpty() -> template = arg
                        else -> return null
                    }
                    i++
                }
                return Options(template, profile, count, perSecond, forMs, settleMs, listen, match?.copy(replyType = replyType), replyType, seed, store, log, strictRate, json, junit, home)
            }

            /** `500/s` or a bare `500`. */
            private fun parseRate(raw: String?): Int? = raw?.trim()?.removeSuffix("/s")?.toIntOrNull()?.takeIf { it > 0 }

            /** `11=11`, or a bare `11` for the same tag both ways. */
            private fun parseMatch(raw: String?): LoadMatch? {
                val text = raw?.trim() ?: return null
                val parts = text.split("=", limit = 2).map { it.trim().toIntOrNull() ?: return null }
                return if (parts.size == 1) LoadMatch(parts[0]) else LoadMatch(parts[0], parts[1])
            }

            private inline fun <reified E : Enum<E>> enumOrNull(raw: String?): E? =
                raw?.let { r -> enumValues<E>().firstOrNull { it.name.equals(r.trim(), ignoreCase = true) } }
        }
    }

    /** Runs the command. [args] are everything after `load`. */
    @Suppress("ReturnCount", "LongMethod", "TooGenericExceptionCaught")
    fun execute(args: List<String>, out: Appendable, err: Appendable): Int {
        if (args.firstOrNull() in setOf("--help", "-h", "help")) {
            out.appendLine(USAGE)
            return HeadlessRun.EXIT_PASSED
        }
        val options =
            Options.parse(args) ?: run {
                err.appendLine("fixtool load: could not read the arguments")
                err.appendLine(USAGE)
                return HeadlessRun.EXIT_USAGE
            }
        WorkspacePaths.use(options.home)
        if (options.template.isBlank() || options.profile.isBlank()) {
            err.appendLine("fixtool load: name a template and a --profile")
            err.appendLine(USAGE)
            return HeadlessRun.EXIT_USAGE
        }
        val shape =
            options.shape ?: run {
                err.appendLine("fixtool load: say either --count <n> for a burst, or --rate <r>/s --for <d> for a sustained run")
                return HeadlessRun.EXIT_USAGE
            }

        val settings = AppSettingsService().loadSettings()
        val profiles = ConnectionProfileService().loadProfiles()
        val profile = pickProfile(options.profile, profiles, err) ?: return HeadlessRun.EXIT_USAGE
        val savedMessages = SavedMessagesService()
        val template =
            LoadTemplates.resolve(options.template, profile.id, savedMessages, profiles) ?: run {
                err.appendLine("fixtool load: no template '${options.template}' — not a file, and no saved message of that id or name")
                val names = savedMessages.loadMessagesForProfile(profile.id).map { it.name }
                if (names.isNotEmpty()) err.appendLine("fixtool load: saved under '${profile.name}': ${names.joinToString(", ")}")
                return HeadlessRun.EXIT_USAGE
            }
        val match =
            options.match ?: template.inferMatch()?.copy(replyType = options.replyType) ?: run {
                err.appendLine(
                    "fixtool load: '${template.name}' carries none of the tags a reply is matched on " +
                        "(${LoadTemplate.CORRELATION_ORDER.joinToString(", ")}) — pass --match <request tag>=<reply tag>",
                )
                return HeadlessRun.EXIT_USAGE
            }
        val override =
            if (options.store != null || options.log != null) {
                StoreAndLogOverride(options.store ?: profile.config.messageStore, options.log ?: profile.config.messageLog)
            } else {
                null
            }
        (override?.applyTo(profile.config) ?: profile.config).storeProblem()?.let {
            err.appendLine("fixtool load: $it")
            return HeadlessRun.EXIT_USAGE
        }

        val store = LoadRecordStore()
        val label = LoadPlan.label(template, shape, profile.name)
        val plan =
            LoadPlan(
                id = store.reserve(RunSets.id(System.currentTimeMillis(), label)),
                label = label,
                template = template,
                profileId = profile.id,
                profileName = profile.name,
                listenProfileIds = options.listen,
                shape = shape,
                match = match,
                settleMs = options.settleMs,
                seed = options.seed,
                storeAndLog = override,
                strictRate = options.strictRate,
            )
        val dictionary = HeadlessRun.dictionaryFor(settings, err)
        val host = HeadlessLoadHost(profiles, dictionary, settings) { err.appendLine("fixtool: $it") }
        val narrator = Narrator(err)
        val report =
            try {
                LoadRunner(host, store).run(plan, onProgress = narrator::tell).report
            } catch (e: LoadRefused) {
                err.appendLine("fixtool load: ${e.message}")
                return HeadlessRun.EXIT_USAGE
            } finally {
                host.release()
            }
        store.prune(settings.runRecordsKept)

        out.append(summary(report, store.directoryFor(report.id)))
        options.jsonFile?.let { write(it, LoadReportCodec.toJson(report).toString(), err) }
        options.junitFile?.let { write(it, LoadReportCodec.toJUnitXml(report), err) }
        return report.verdict.exitCode ?: HeadlessRun.EXIT_FAILED
    }

    private fun pickProfile(key: String, profiles: List<FixConnectionProfile>, err: Appendable): FixConnectionProfile? {
        val matches = profiles.filter { it.id == key || it.name == key }.distinctBy { it.id }
        return when {
            matches.isEmpty() -> {
                err.appendLine("fixtool load: no saved connection profile named '$key'")
                null
            }
            matches.size > 1 -> {
                err.appendLine("fixtool load: ${matches.size} saved profiles answer to '$key' — rename one")
                null
            }
            matches.single().config.connectionType != FixConnectionConfig.ConnectionType.INITIATOR -> {
                err.appendLine("fixtool load: '${matches.single().name}' is an acceptor, and an acceptor has one session by construction — issue from the client profile")
                null
            }
            else -> matches.single()
        }
    }

    /** The progress lines, one per phase change and one every few seconds while settling. */
    private class Narrator(
        private val err: Appendable,
    ) {
        private var lastPhase: LoadPhase? = null
        private var lastSettleLine = 0L

        fun tell(r: LoadReport) {
            val phase = r.phase
            if (phase != lastPhase) {
                lastPhase = phase
                when (phase) {
                    LoadPhase.PREPARING -> Unit
                    LoadPhase.ISSUING -> {
                        err.appendLine("fixtool: prepared ${r.lanes} lane${if (r.lanes == 1) "" else "s"} in ${r.issue.prepareMs}ms (per message: ${r.template.perMessageTags.joinToString(", ").ifEmpty { "none" }})")
                        err.appendLine("fixtool: issuing ${LoadReportCodec.fmt(r.issue.requested)} ${r.shape.describe().removePrefix("×")}")
                    }
                    LoadPhase.SETTLING -> {
                        r.issue.spanMs?.let { span ->
                            err.appendLine("fixtool: ${LoadReportCodec.fmt(r.issue.leftSocket)} left the socket in ${RunSetStats.humanMs(span)}" + (r.issue.achievedPerSecond?.let { " (${LoadReportCodec.fmt(it)}/s)" } ?: ""))
                        }
                        settleLine(r)
                    }
                    LoadPhase.DONE -> err.appendLine("fixtool: settle closed with ${LoadReportCodec.fmt(r.replies.unmatched)} pending")
                }
            } else if (phase == LoadPhase.SETTLING && System.currentTimeMillis() - lastSettleLine > SETTLE_LINE_EVERY_MS) {
                settleLine(r)
            }
        }

        private fun settleLine(r: LoadReport) {
            lastSettleLine = System.currentTimeMillis()
            err.appendLine("fixtool: settling, ${LoadReportCodec.fmt(r.replies.unmatched)} pending, ${humanDuration(r.settleLeftMs ?: r.settleMs)} left")
        }

        private companion object {
            const val SETTLE_LINE_EVERY_MS = 2_000L
        }
    }

    /** The summary block a build log is read from: the counts, the timings, the tool's own part, the verdict. */
    fun summary(r: LoadReport, records: File): String =
        buildString {
            appendLine(
                "issued".padEnd(COL) + LoadReportCodec.fmt(r.issue.leftSocket).padStart(NUM) +
                    "   requested ${LoadReportCodec.fmt(r.issue.requested)} · handed to engine ${LoadReportCodec.fmt(r.issue.handedToEngine)} · left socket ${LoadReportCodec.fmt(r.issue.leftSocket)}",
            )
            appendLine("matched".padEnd(COL) + LoadReportCodec.fmt(r.replies.matched).padStart(NUM))
            val named = r.unmatched.take(UNMATCHED_NAMED).joinToString(" · ") { "${it.id} (lane ${it.lane})" }
            appendLine("unmatched".padEnd(COL) + LoadReportCodec.fmt(r.replies.unmatched).padStart(NUM) + (if (named.isNotEmpty()) "   $named" else ""))
            appendLine("duplicates".padEnd(COL) + LoadReportCodec.fmt(r.replies.duplicates).padStart(NUM))
            appendLine("late".padEnd(COL) + LoadReportCodec.fmt(r.replies.late).padStart(NUM))
            if (r.replies.strays > 0) appendLine("strays".padEnd(COL) + LoadReportCodec.fmt(r.replies.strays).padStart(NUM) + "   replies to nothing this run issued")
            r.rate?.let { appendLine("rate".padEnd(COL) + LoadReportCodec.rateSentence(it)) }
            r.timing?.let { appendLine("timing".padEnd(COL) + "elapsed ${RunSetStats.humanMs(it.elapsedMs)} · drain ${RunSetStats.humanMs(it.drainMs)}") }
            r.roundTrip?.let { d ->
                appendLine(
                    "round trip".padEnd(COL) +
                        "min ${LoadReportCodec.humanMicros(d.min)} · p50 ${LoadReportCodec.humanMicros(d.p50)} · p95 ${LoadReportCodec.humanMicros(d.p95)} · " +
                        "p99 ${LoadReportCodec.humanMicros(d.p99)} · max ${LoadReportCodec.humanMicros(d.max)} · mean ${LoadReportCodec.humanMicros(d.mean)}  (${LoadReportCodec.fmt(d.samples.toLong())})",
                )
            }
            appendLine(
                "tool".padEnd(COL) +
                    if (r.tool.limited) {
                        LoadReportCodec.toolSentence(r.tool)
                    } else {
                        "clean · ${r.tool.discarded} discarded on ${r.lanes + r.listen.size} sessions · ${r.tool.neverLeftSocket} never left the socket"
                    },
            )
            appendLine(verdictLine(r))
            appendLine("".padEnd(COL) + "records: $records")
        }

    private fun verdictLine(r: LoadReport): String {
        val head =
            when {
                r.status == LoadStatus.STOPPED -> "STOPPED".padEnd(COL) + "after ${LoadReportCodec.fmt(r.issue.leftSocket)} of ${LoadReportCodec.fmt(r.issue.requested)} issued"
                r.verdict.completeness == LoadReport.Completeness.UNMATCHED -> "UNMATCHED".padEnd(COL) + LoadReportCodec.unmatchedSentence(r).substringBefore(":")
                else -> "COMPLETE".padEnd(COL) + "${LoadReportCodec.fmt(r.replies.matched)} of ${LoadReportCodec.fmt(r.issue.leftSocket)} answered"
            }
        val rate = if (r.verdict.rate == LoadReport.RateVerdict.SHORTFALL) " · RATE SHORTFALL" + (if (r.strictRate) "" else " (reported, exit unaffected; --strict-rate would exit 1)") else ""
        val tool = if (r.verdict.tool == LoadReport.ToolVerdict.LIMITED) " · TOOL LIMITED" else ""
        return "$head$rate$tool · exit ${r.verdict.exitCode}"
    }

    @Suppress("TooGenericExceptionCaught")
    private fun write(path: String, content: String, err: Appendable) {
        try {
            val file = File(path)
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(content)
            err.appendLine("fixtool: wrote $path")
        } catch (e: Exception) {
            err.appendLine("fixtool: could not write $path — ${e.message}")
        }
    }

    private const val COL = 13
    private const val NUM = 9
    private const val UNMATCHED_NAMED = 6

    val USAGE =
        """
        fixtool load <template> --profile <name> (--count <n> | --rate <r>/s --for <d>) [options]

          <template>             a saved message's name or id, or a path to a .fix file holding one message
          --profile <name>       the multi-session initiator profile whose lanes issue
          --count <n>            burst: issue n messages as fast as the lanes carry them
          --rate <r>/s --for <d>  sustained: issue r per second for d (90s, 10m, 1h)
          --settle <d>           wait this long for replies after the last send (default 60s); the window
                                 closes early when nothing is pending
          --listen <profile>     also match replies landing on this profile's sessions (repeatable)
          --match <req>=<rep>    request tag to reply tag (default: the template's first correlation tag, both sides)
          --reply-type <35>      count only replies of this MsgType as answers
          --set <k>=<v>          seed a value into every message's scope as ${'$'}{k} (repeatable)
          --store file|memory    message store for this run's sessions (default: the profile's)
          --log file|none        message log for this run's sessions (default: the profile's)
          --strict-rate          exit 1 on a rate shortfall, not only on unmatched replies
          --json <file>          write the load report
          --junit <file>         write one <testsuite> with three cases: completeness, rate, tool
          --home <dir>           read profiles and templates from <dir> instead of ~/.fixtool

        Exits 0 when every message that left the socket was answered and the tool stayed out of the way.
        Exits 1 when anything was unmatched, when the tool limited the run, when the run was stopped, or on a
        shortfall under --strict-rate. Exits 2 when it could not be run: no lane logged on, a memory store
        without Reset on Logon, a template or profile not found.
        The record is written under <home>/loads/<id>/ whether or not --json is given.
        """.trimIndent()
}
