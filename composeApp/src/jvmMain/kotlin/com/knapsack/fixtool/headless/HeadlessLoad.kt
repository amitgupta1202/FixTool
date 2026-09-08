package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.OnFailure
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
import com.knapsack.fixtool.service.load.LoadSetRunner
import com.knapsack.fixtool.service.load.LoadSetStore
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
@Suppress("TooManyFunctions")
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
        /** `--set k=v` was used to seed a value. Tolerated for one release, with a note on stderr. */
        val seededWithSet: Boolean = false,
        /** `--set <name>`: the saved load set to run, instead of one template. */
        val set: String? = null,
        /** `--on-failure`, which overrides the set file's own policy. */
        val onFailure: OnFailure? = null,
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
                var seededWithSet = false
                var setName: String? = null
                var onFailure: OnFailure? = null
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
                        arg == "--seed" -> {
                            val pair = args.getOrNull(++i) ?: return null
                            val (k, v) = pair.split("=", limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() } ?: return null
                            seed[k.trim()] = v
                        }
                        // `--set k=v` seeded a value until #45 needed `--set <name>` to mean a saved load
                        // set, as it already does on `fixtool run`. Read as a seed for one release, with a
                        // note on stderr. A set name is a slug and cannot carry an `=`, so the two never
                        // collide.
                        arg == "--set" -> {
                            val value = args.getOrNull(++i) ?: return null
                            if (!value.contains("=")) {
                                setName = value.trim().takeIf { it.isNotBlank() } ?: return null
                            } else {
                                val (k, v) = value.split("=", limit = 2).takeIf { it[0].isNotBlank() } ?: return null
                                seed[k.trim()] = v
                                seededWithSet = true
                            }
                        }
                        arg == "--on-failure" -> onFailure = enumOrNull<OnFailure>(args.getOrNull(++i)) ?: return null
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
                return Options(
                    template, profile, count, perSecond, forMs, settleMs, listen, match?.copy(replyType = replyType),
                    replyType, seed, store, log, strictRate, json, junit, home, seededWithSet, setName, onFailure,
                )
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
        if (options.seededWithSet) {
            err.appendLine(
                "fixtool load: --set <k>=<v> now seeds through --seed <k>=<v>. --set <name> runs a saved load set.",
            )
        }
        if (options.set != null) {
            if (options.template.isNotBlank()) {
                err.appendLine("fixtool load: name a template or a --set, not both")
                return HeadlessRun.EXIT_USAGE
            }
            return executeSet(options, options.set, out, err)
        }
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
        // One list, three surfaces: the dialog refused a template reading a name nothing seeds and this
        // command did not, so a CLI run got the same refusal from LoadRunner after opening every lane.
        val problems = LoadPlan.problems(template, options.seed, profile.name, profile.config, override, LoadPlan.Surface.CLI)
        if (problems.isNotEmpty()) {
            problems.forEach { err.appendLine("fixtool load: $it") }
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
        // The record, not the bare report: the file on disk, this flag and GET /loads/<id> are one shape,
        // so a set and a run read the same at every door. A reader of the old top-level `.verdict` reads
        // `.phases[0].verdict` or the set-level `.exitCode` now, and the changelog says so.
        options.jsonFile?.let { write(it, LoadReportCodec.recordToJson(LoadRecord.of(report)).toString(), err) }
        options.junitFile?.let { write(it, LoadReportCodec.toJUnitXml(report), err) }
        return report.verdict.exitCode ?: HeadlessRun.EXIT_FAILED
    }

    /**
     * **`fixtool load --set <name>`**: a saved set, run in order, under one seed, with one report.
     *
     * Nothing dials until every phase is fine, which is why the refusals come out before the record
     * directory is even reserved: a set of six must never fail on phase five for something that could have
     * been said before phase one.
     */
    @Suppress("ReturnCount", "LongMethod")
    private fun executeSet(options: Options, name: String, out: Appendable, err: Appendable): Int {
        val settings = AppSettingsService().loadSettings()
        val profiles = ConnectionProfileService().loadProfiles()
        val savedMessages = SavedMessagesService()
        val sets = LoadSetStore()
        val set =
            sets.load(name) ?: run {
                err.appendLine("fixtool load: no load set '$name' under ${sets.directory}")
                val saved = sets.list().map { it.name }
                if (saved.isNotEmpty()) err.appendLine("fixtool load: saved sets: ${saved.joinToString(", ")}")
                return HeadlessRun.EXIT_USAGE
            }
        val resolve = WorkspaceResolver(profiles, savedMessages)
        val problems = set.problems(resolve, LoadPlan.Surface.CLI)
        if (problems.isNotEmpty()) {
            problems.forEach { problem ->
                val label = problem.phase?.let { set.phases.getOrNull(it - 1)?.label }
                err.appendLine("fixtool load: " + problem.describe(label))
            }
            return HeadlessRun.EXIT_USAGE
        }

        val store = LoadRecordStore()
        val id = store.reserve(RunSets.id(System.currentTimeMillis(), set.name))
        val planned =
            set
                .copy(onFailure = options.onFailure ?: set.onFailure)
                .plan(resolve, options.seed, id)
                .let { p ->
                    // --strict-rate on the command line applies to every phase. A per-phase strictRate
                    // stays in the file for the case where only the soak phase should gate the build.
                    if (options.strictRate) p.copy(phases = p.phases.map { it.copy(strictRate = true) }) else p
                }
        val dictionary = HeadlessRun.dictionaryFor(settings, err)
        val host = HeadlessLoadHost(profiles, dictionary, settings) { err.appendLine("fixtool: $it") }
        val narrator = SetNarrator(err)
        val record =
            try {
                LoadSetRunner(host, store).run(planned, onProgress = narrator::tell)
            } catch (e: LoadRefused) {
                err.appendLine("fixtool load: ${e.message}")
                return HeadlessRun.EXIT_USAGE
            } finally {
                host.release()
            }
        store.prune(settings.runRecordsKept)

        out.append(setSummary(record, planned, store.directoryFor(record.id), dictionary))
        options.jsonFile?.let { write(it, LoadReportCodec.recordToJson(record).toString(), err) }
        options.junitFile?.let { write(it, LoadReportCodec.toJUnitXml(record), err) }
        return record.exitCode ?: HeadlessRun.EXIT_FAILED
    }

    /** The workspace as a set's [LoadSet.Resolver]: profiles by id or name, templates by path or name. */
    private class WorkspaceResolver(
        private val profiles: List<FixConnectionProfile>,
        private val savedMessages: SavedMessagesService,
    ) : LoadSet.Resolver {
        override fun profile(key: String): LoadSet.Profile? =
            profiles
                .filter { it.id == key || it.name == key }
                .distinctBy { it.id }
                .singleOrNull()
                ?.let { LoadSet.Profile(it.id, it.name, it.config) }

        override fun template(key: String, profileId: String?): LoadTemplate? =
            LoadTemplates.resolve(key, profileId, savedMessages, profiles)
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
        private var lastStage: LoadStage? = null
        private var lastSettleLine = 0L

        fun tell(r: LoadReport) {
            val stage = r.stage
            if (stage != lastStage) {
                lastStage = stage
                when (stage) {
                    LoadStage.PREPARING -> Unit
                    LoadStage.ISSUING -> {
                        err.appendLine("fixtool: prepared ${r.lanes} lane${if (r.lanes == 1) "" else "s"} in ${r.issue.prepareMs}ms (per message: ${r.template.perMessageTags.joinToString(", ").ifEmpty { "none" }})")
                        err.appendLine("fixtool: issuing ${LoadReportCodec.fmt(r.issue.requested)} ${r.shape.describe().removePrefix("×")}")
                    }
                    LoadStage.SETTLING -> {
                        r.issue.spanMs?.let { span ->
                            err.appendLine("fixtool: ${LoadReportCodec.fmt(r.issue.leftSocket)} left the socket in ${RunSetStats.humanMs(span)}" + (r.issue.achievedPerSecond?.let { " (${LoadReportCodec.fmt(it)}/s)" } ?: ""))
                        }
                        settleLine(r)
                    }
                    LoadStage.DONE -> err.appendLine("fixtool: settle closed with ${LoadReportCodec.fmt(r.replies.unmatched)} pending")
                }
            } else if (stage == LoadStage.SETTLING && System.currentTimeMillis() - lastSettleLine > SETTLE_LINE_EVERY_MS) {
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

    /**
     * The progress lines for a set: which phase, then that phase's own lines through [Narrator].
     *
     * A fresh narrator per phase, so each phase says "prepared", "issuing" and "settling" once, exactly as
     * a single run does. Everything goes to stderr, so `> report.txt` keeps the report clean.
     */
    private class SetNarrator(
        private val err: Appendable,
    ) {
        private var phase = 0
        private var narrator: Narrator? = null

        fun tell(record: LoadRecord) {
            val running = record.phases.indexOfFirst { it.status == LoadStatus.RUNNING }.takeIf { it >= 0 } ?: return
            if (running + 1 != phase) {
                phase = running + 1
                narrator = Narrator(err)
                err.appendLine("fixtool: phase $phase of ${record.phases.size} · ${record.phases[running].label}")
            }
            narrator?.tell(record.phases[running])
        }
    }

    /**
     * **The set as a build log reads it**: three header lines, a block per phase under its heading, and the
     * set's own line last. The record path is printed once.
     */
    fun setSummary(record: LoadRecord, planned: LoadSet.Planned, records: File, dictionary: FixDictionaryAdapter?): String =
        buildString {
            val profiles = record.phases.map { it.profileName }.distinct().joinToString(", ")
            val lanes = record.phases.maxOfOrNull { it.lanes } ?: 0
            appendLine(
                "set".padEnd(COL) +
                    listOfNotNull(
                        record.label,
                        "${record.phases.size} phases",
                        profiles,
                        "$lanes lane${if (lanes == 1) "" else "s"}",
                        planned.storeAndLog?.describe(),
                    ).joinToString(" · "),
            )
            if (record.seed.isNotEmpty()) {
                appendLine("seed".padEnd(COL) + record.seed.entries.joinToString(" · ") { "${it.key}=${it.value}" })
            }
            appendLine("policy".padEnd(COL) + policySentence(planned.onFailure))
            record.phases.forEachIndexed { index, phase ->
                appendLine()
                appendLine(phaseHeading(index + 1, phase, dictionary))
                if (phase.status == LoadStatus.SKIPPED || phase.status == LoadStatus.PENDING) {
                    appendLine("SKIPPED".padEnd(COL) + (phase.note ?: "this phase did not run"))
                } else {
                    append(phaseBlock(phase))
                }
            }
            appendLine()
            appendLine(setVerdictLine(record))
            appendLine("".padEnd(COL) + "records: $records")
        }

    private fun policySentence(onFailure: OnFailure): String =
        when (onFailure) {
            OnFailure.STOP -> "stop when a phase does not pass"
            OnFailure.CONTINUE -> "carry on, and report every phase"
        }

    /** "1 · Ask for a quote       RFQ Load QuoteRequest ×4,000 · 35=R → S · 131 QuoteReqID · settle 1m" */
    private fun phaseHeading(n: Int, r: LoadReport, dictionary: FixDictionaryAdapter?): String {
        val from = if (r.indexFrom > 1) " from ${LoadReportCodec.fmt(r.indexFrom.toLong())}" else ""
        val tagName = dictionary?.getFieldName(r.match.requestTag)?.let { " $it" }.orEmpty()
        val plan =
            listOf(
                "${r.template.name} ${r.shape.describe()}$from",
                "35=${r.template.msgType} → ${r.match.replyType ?: "any"}",
                "${r.match.requestTag}$tagName",
                "settle ${humanDuration(r.settleMs)}",
            ).joinToString(" · ")
        return "$n · ${r.label}".padEnd(HEAD).let { if (it.length > HEAD) "$it  " else it } + plan
    }

    /** "FAILED       phase 2 · 1 passed, 1 failed, 1 skipped · 63.1s · exit 1" */
    private fun setVerdictLine(record: LoadRecord): String {
        val v = record.verdict
        val elapsed = record.finishedAt?.let { RunSetStats.humanMs(it - record.startedAt) }
        return v.outcome.name.padEnd(COL) +
            listOfNotNull(
                v.phase?.let { "phase $it" },
                v.counts().ifBlank { null },
                elapsed,
                record.exitCode?.let { "exit $it" },
            ).joinToString(" · ")
    }

    /** The summary block a build log is read from: the counts, the timings, the tool's own part, the verdict. */
    fun summary(r: LoadReport, records: File): String = phaseBlock(r) + "".padEnd(COL) + "records: $records\n"

    /** The same block without the record path, which a set prints once at the end rather than per phase. */
    fun phaseBlock(r: LoadReport): String =
        buildString {
            appendLine(
                "issued".padEnd(COL) + LoadReportCodec.fmt(r.issue.leftSocket).padStart(NUM) +
                    "   requested ${LoadReportCodec.fmt(r.issue.requested)} · handed to engine ${LoadReportCodec.fmt(r.issue.handedToEngine)} · left socket ${LoadReportCodec.fmt(r.issue.leftSocket)}",
            )
            r.capture?.let { c ->
                val most = LoadReportCodec.fmt(c.most().toLong())
                appendLine("captured".padEnd(COL) + most.padStart(NUM) + "   " + c.describe())
            }
            if (r.issue.unaddressable > 0) {
                val holes =
                    r.unaddressable
                        .take(UNMATCHED_NAMED)
                        .joinToString(" · ") { "no ${it.missing} for ${it.index}" }
                val count = LoadReportCodec.fmt(r.issue.unaddressable)
                appendLine("not sent".padEnd(COL) + count.padStart(NUM) + "   " + holes)
            }
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
        }

    private fun verdictLine(r: LoadReport): String {
        val head =
            when {
                r.status == LoadStatus.STOPPED -> "STOPPED".padEnd(COL) + "after ${LoadReportCodec.fmt(r.issue.leftSocket)} of ${LoadReportCodec.fmt(r.issue.requested)} issued"
                // Before unanswered, and its own word: "the tool never asked" and "the venue never
                // replied" are different findings and a reader has to be able to tell them apart.
                r.verdict.completeness == LoadReport.Completeness.INCOMPLETE ->
                    "INCOMPLETE".padEnd(COL) + LoadReportCodec.unaddressableSentence(r)
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
    private const val HEAD = 26
    private const val NUM = 9
    private const val UNMATCHED_NAMED = 6

    val USAGE =
        """
        fixtool load <template> --profile <name> (--count <n> | --rate <r>/s --for <d>) [options]
        fixtool load --set <name> [--seed <k>=<v>]… [--on-failure stop|continue] [options]

          <template>             a saved message's name or id, or a path to a .fix file holding one message
          --profile <name>       the multi-session initiator profile whose lanes issue
          --count <n>            burst: issue n messages as fast as the lanes carry them
          --rate <r>/s --for <d>  sustained: issue r per second for d (90s, 10m, 1h)
          --settle <d>           wait this long for replies after the last send (default 60s); the window
                                 closes early when nothing is pending
          --listen <profile>     also match replies landing on this profile's sessions (repeatable)
          --match <req>=<rep>    request tag to reply tag (default: the template's first correlation tag, both sides)
          --reply-type <35>      count only replies of this MsgType as answers
          --set <name>           run a saved load set (<home>/load-sets/<name>.json): several phases in
                                 order, under one seed, with one report
          --on-failure stop|continue   after a phase that did not pass (default: the file's, then stop)
          --seed <k>=<v>         seed a value into every message's scope as ${'$'}{k} (repeatable); with
                                 --set it overrides the file's value, so a build can pass its own run id
          --set <k>=<v>          still read as a seed this release, with a note on stderr to write --seed
          --store file|memory    message store for this run's sessions (default: the profile's)
          --log file|none        message log for this run's sessions (default: the profile's)
          --strict-rate          exit 1 on a rate shortfall, not only on unmatched replies
          --json <file>          write the record: the same JSON as loads/<id>/load.json and GET /loads/<id>
          --junit <file>         write one <testsuite> with three cases: completeness, rate, tool. A set of
                                 several phases writes one <testsuites> with a <testsuite> per phase
          --home <dir>           read profiles and templates from <dir> instead of ~/.fixtool

        A set exits 0 when every phase passed, 1 when any did not or it was stopped, 2 when it could not
        start. Under the default policy the phases after a failure are reported as skipped.

        Exits 0 when every message that left the socket was answered and the tool stayed out of the way.
        Exits 1 when anything was unmatched, when the tool limited the run, when the run was stopped, or on a
        shortfall under --strict-rate. Exits 2 when it could not be run: no lane logged on, a memory store
        without Reset on Logon, a template or profile not found.
        The record is written under <home>/loads/<id>/ whether or not --json is given.
        """.trimIndent()
}
