package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.RunSetStats
import quickfix.SessionID
import java.util.concurrent.atomic.AtomicLong

/**
 * **A load run, start to verdict.**
 *
 * Open the lanes and the listeners. Compile the template once and prepare one prototype per lane, timing
 * it. Put one [StampMatcher] on every participating session's socket stamps. Hand the [Pacer] a callback
 * that renders and sends. When the pacer returns, settle: wait for the pending set to empty, up to the
 * window, then close it. Release the sessions with the matcher still listening, so a reply that lands during
 * logout is counted as late rather than lost. Assemble the report, write the record, hand it back.
 *
 * Progress is the same [LoadReport] shape with `status = RUNNING`, emitted about four times a second while
 * issuing and once per poll while settling, and written to the store on each emission so a poller and a
 * reopened document always find the counts so far.
 *
 * Rendering happens on the pacer's thread, per message. A render is a clone and a `setString` or two, which
 * is far inside a two-millisecond budget. If a template ever makes it otherwise, the pacer's lag spans say
 * so, and a render-ahead producer is the follow-on the design note names.
 */
class LoadRunner(
    private val host: LoadHost,
    private val store: LoadRecordStore? = null,
    private val clock: Pacer.Clock = Pacer.Clock.SYSTEM,
    /**
     * **What this phase's own matches release**, which is the buffer of every phase reacting to it.
     *
     * A single run has none, because it has no later phase to fire. A set hands one over for a phase
     * something reacts to, and the matcher calls it from inside its own monitor so a fire and the close
     * behind it cannot cross. See [StampMatcher.Trigger].
     */
    private val fires: StampMatcher.Trigger? = null,
    /**
     * Where this phase's matcher goes to be fed stamps, and how it stops being fed.
     *
     * A single run puts a listener on every participating session, which is what [everySession] does and
     * what this always did. A set hands its own reply router instead, because a reply belongs to the phase
     * that asked for it however long after that phase ended it arrives, and the router is the only thing
     * that knows which phase that was.
     */
    private val listen: (StampMatcher, List<LoadLane>) -> AutoCloseable = ::everySession,
) {
    /** The finished report, and the evidence the record keeps beside it. */
    data class Outcome(
        val report: LoadReport,
        val unmatched: List<StampMatcher.Unmatched>,
        val specimens: List<StampMatcher.Specimen>,
        /** One line per index that carries a captured value: `index name=value …`. */
        val captured: List<Pair<Int, List<Pair<String, String>>>> = emptyList(),
    )

    /**
     * **What a phase of a set reads and what it writes**, in one argument rather than two.
     *
     * [lookups] are the names earlier phases captured, resolved per message index. [table] is where this
     * phase puts its own. A single run passes neither, because it has no later phase to read them.
     */
    data class Captures(
        val lookups: Map<String, (Int) -> String?> = emptyMap(),
        val table: StampMatcher.CaptureTable? = null,
    )

    /**
     * Runs [plan] to its verdict. Throws [LoadRefused] when it cannot start: a template without a message
     * type, a variable nothing seeds, no lane logged on.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
    fun run(
        plan: LoadPlan,
        /** Which phase of its set this is, 1-based. It names the evidence files, and nothing else. */
        phase: Int = 1,
        /**
         * Names an earlier phase captured, each a per-message lookup by index, and the table this phase
         * writes its own captures into. Both are the set's: a single run has neither.
         */
        captures: Captures = Captures(),
        /**
         * **What releases this phase's messages**, for a reactive one: the indices its trigger answered
         * for, in the order they were answered, and the word that no more are coming. Null for a paced
         * phase, which is released by a schedule of its own.
         */
        firedBy: TriggerBuffer? = null,
        cancelled: () -> Boolean = { false },
        onProgress: (LoadReport) -> Unit = {},
    ): Outcome {
        val startedAt = host.now()
        val evidence = LoadReport.Evidence.forPhase(phase, captured = plan.capture.isNotEmpty())
        // A reactive phase is released by an earlier phase's replies, and only a set has an earlier
        // phase. Said here rather than in the pacer, so it is a refusal before a lane opens rather than
        // a throw out of the middle of a run that has already logged on.
        if (plan.shape is LoadShape.Triggered && firedBy == null) {
            throw LoadRefused("this phase reacts to an earlier one, and only a phase of a set has one to react to")
        }
        val compiled =
            try {
                CompiledTemplate.compile(plan.template)
            } catch (e: IllegalArgumentException) {
                throw LoadRefused(e.message ?: "the template cannot be compiled")
            }
        // A name an earlier phase captured is covered, which is what makes a set work against a venue that
        // mints its own ids. A single run has no lookups, so its refusal is exactly what it was.
        val missing = compiled.missingVariables(plan.seed.keys + Lane.SEED_NAMES + captures.lookups.keys)
        if (missing.isNotEmpty()) {
            throw LoadRefused(
                "the template reads ${missing.joinToString(", ") { "\${$it}" }} and nothing seeds " +
                    "${if (missing.size == 1) "it" else "them"}: pass --seed ${missing.first()}=…",
            )
        }

        val progress = Progress(plan, compiled, startedAt, evidence, captures, onProgress)
        progress.emit(LoadStage.PREPARING)

        val lanes = host.openLanes(plan.profileId, plan.storeAndLog)
        if (lanes.isEmpty()) throw LoadRefused("no session of '${plan.profileName}' reached LOGGED_ON, so there is nothing to issue on")
        val listeners = host.openListeners(plan.listenProfileIds, plan.storeAndLog)
        // Distinct by identity, because in a set a phase can issue from a profile another phase listens on
        // and the set hands the same lane instances back through both doors. Counting a lane twice would
        // double `discardedBefore` and put two stamp listeners on the one session.
        val all = (lanes + listeners).distinct()
        progress.lanes = lanes.size
        // A delta over the sessions this phase takes part in, which is this phase's own number only while
        // it is the only phase on them. See LoadReport.Tool.discarded for what overlap does to it.
        val discardedBefore = all.sumOf { it.discarded() }

        val prepareStart = clock.nanoTime()
        val prototypes =
            lanes.map { lane ->
                compiled.prepare(lane.lane, plan.seed, host.dictionary(), captures.lookups) { template ->
                    host.resolveOnce(template, plan.seed + lane.lane.seed(), lane)
                }
            }
        progress.prepareMs = (clock.nanoTime() - prepareStart) / NANOS_PER_MILLI

        val bySession = lanes.mapNotNull { l -> l.sessionId?.let { it to l.lane.slot } }.toMap()
        val matcher =
            StampMatcher(
                match = plan.match,
                requestType = compiled.msgType,
                issuing = bySession.keys,
                laneOf = { id: SessionID -> bySession[id] ?: 0 },
                captures = plan.capture.entries.map { it.key to it.value },
                table = captures.table,
                trigger = fires,
            )
        progress.matcher = matcher
        val listening = listen(matcher, all)

        // Each lane renders ahead of its own sends, so a lane's message is not queued behind every other
        // lane's rendering on the pacer thread. See RenderAhead: this is what makes a per-lane number
        // worth showing rather than a picture of the round-robin.
        //
        // A reactive phase cannot look ahead at all, because its next index is whichever request its
        // trigger answered next. It renders on demand instead, which is the same one-renderer-per-lane
        // shape reading a queue rather than a counter. See RenderOnTrigger.
        val onTrigger =
            if (plan.shape !is LoadShape.Triggered) {
                null
            } else {
                val buffer = requireNotNull(firedBy)
                RenderOnTrigger(
                    prototypes = prototypes,
                    buffer = buffer,
                    indexFrom = plan.indexFrom,
                    requested = plan.requested,
                    missing = "reply to ${buffer.firedBy ?: "the phase this one reacts to"}",
                    cancelled = cancelled,
                    phase = phase,
                )
            }
        val producers =
            if (onTrigger != null) {
                emptyList()
            } else {
                RenderAhead.forLanes(prototypes, plan.requested, plan.indexFrom, phase)
            }

        try {
            progress.emit(LoadStage.ISSUING)
            val handed = AtomicLong()
            var lastEmit = clock.nanoTime()

            // The tail every shape shares: give the engine one rendered message, count what became of it,
            // and emit a progress tick when one is due. A paced phase reaches it with a message it took
            // off its own lane's look-ahead a line ago, and a reactive one with a message a lane finished
            // with while its trigger's reply was still landing. Both on this one issuing thread, which is
            // what lets Pacer.Tally, `handed` and `lastEmit` stay unsynchronised.
            fun handOver(laneIndex: Int, rendered: CompiledTemplate.Rendered): Pacer.Issued {
                val issued =
                    when (rendered) {
                        is CompiledTemplate.Rendered.Unaddressable -> {
                            progress.refuse(rendered)
                            Pacer.Issued.UNADDRESSABLE
                        }
                        is CompiledTemplate.Rendered.Message -> {
                            // The index goes to the matcher before the send, because the SEND stamp
                            // carries the wire and nothing else and a capture has to land somewhere.
                            val id = requestId(rendered.message, plan.match.requestTag)
                            if (id != null) matcher.issued(id, rendered.index)
                            val ok = lanes[laneIndex].send(rendered.message)
                            if (ok) {
                                handed.incrementAndGet()
                            } else if (id != null) {
                                // A refusal is the one path with no stamp behind it, so the index handed
                                // over a line ago has to be taken back by hand or nothing ever claims it.
                                matcher.refused(id)
                            }
                            if (ok) Pacer.Issued.HANDED else Pacer.Issued.REFUSED
                        }
                    }
                progress.handed = handed.get()
                val now = clock.nanoTime()
                if (now - lastEmit > PROGRESS_EVERY_NANOS) {
                    lastEmit = now
                    progress.emit(LoadStage.ISSUING)
                }
                return issued
            }

            val stats =
                Pacer(plan.shape, lanes.size, clock, plan.requested).run(
                    issue = { laneIndex, messageIndex ->
                        // The pacer counts 1..requested. `indexFrom` shifts that once, here, so a phase of a
                        // set can address the half another phase left: "pass the other 2,000" is index 2,001.
                        val index = plan.indexFrom - 1 + messageIndex
                        val rendered = producers[laneIndex].next(index) ?: prototypes[laneIndex].renderOrRefuse(index)
                        handOver(laneIndex, rendered)
                    },
                    cancelled = cancelled,
                    reactive =
                        onTrigger?.let { source ->
                            object : Pacer.Reactive {
                                override fun next(): Pacer.Ready? = source.next()

                                override fun hand(ready: Pacer.Ready): Pacer.Issued =
                                    handOver(ready.laneIndex, ready.rendered)
                            }
                        },
                )
            progress.handed = stats.handedToEngine
            progress.stats = stats
            // Nothing more will be sent, so nothing more needs rendering. Before the settle window rather
            // than after it, so the producers are not sitting on a queue for the length of it.
            producers.forEach { it.close() }
            onTrigger?.close()

            // Settle: the window closes early the moment nothing is outstanding, and it never ages a send out.
            // Outstanding is two things, not one. A request stamped out of the socket and unanswered is
            // pending; a message handed to the engine and not yet stamped has not left the socket at all, and
            // is not pending because the matcher has never seen it. Closing on `pendingNow` alone therefore
            // closes the window at the instant the pacer returns on any machine where the engine's writer
            // thread has not been scheduled yet — the run then reports every one of its own messages as
            // "handed to the engine, never left the socket" and blames itself for a send it never waited for.
            // The tool's own verdict is the one number a load report exists to keep honest, so the window
            // waits for the issue path to drain as well. A message that truly never leaves is still reported
            // as such, having cost the settle window to establish it, which is the only way to know.
            val settleStart = host.now()
            var stoppedInSettle = false
            while (host.now() - settleStart < plan.settleMs) {
                val counts = matcher.snapshot()
                val undrained = stats.handedToEngine - counts.leftSocket
                if (counts.pendingNow <= 0 && undrained <= 0) break
                if (cancelled()) {
                    stoppedInSettle = true
                    break
                }
                progress.settleLeftMs = (plan.settleMs - (host.now() - settleStart)).coerceAtLeast(0)
                progress.emit(LoadStage.SETTLING)
                host.sleep(SETTLE_POLL_MS)
            }
            val result = matcher.finish()

            // Release with the matcher still listening: a reply during logout is late, not lost.
            host.release()
            val lateCounts = matcher.snapshot()
            val discardedAfter = all.sumOf { it.discarded() }

            val stopped = stats.stopped || stoppedInSettle
            val report =
                progress.finalReport(
                    status = if (stopped) LoadStatus.STOPPED else LoadStatus.DONE,
                    result = result,
                    late = lateCounts.late,
                    discarded = (discardedAfter - discardedBefore).coerceAtLeast(0),
                    finishedAt = host.now(),
                )
            val capturedRows = captures.table?.rows().orEmpty()
            store?.write(report)
            store?.writeEvidence(plan.id, evidence, result.unmatched, result.specimens, capturedRows)
            onProgress(report)
            return Outcome(report, result.unmatched, result.specimens, capturedRows)
        } finally {
            producers.forEach { it.close() }
            onTrigger?.close()
            listening.close()
        }
    }

    /** The report so far, assembled from whatever is known at the moment it is asked for. */
    private inner class Progress(
        private val plan: LoadPlan,
        private val compiled: CompiledTemplate,
        private val startedAt: Long,
        private val evidence: LoadReport.Evidence,
        private val captures: Captures,
        private val onProgress: (LoadReport) -> Unit,
    ) {
        var lanes = 0
        var prepareMs = 0L
        var handed = 0L
        var settleLeftMs: Long? = null
        var matcher: StampMatcher? = null
        var stats: Pacer.IssueStats? = null

        /** Every index that could not be built, capped as `unmatched` is, with the count kept whole. */
        private val refused = java.util.concurrent.CopyOnWriteArrayList<LoadReport.Unaddressable>()

        @Volatile private var refusedCount = 0L

        fun refuse(rendered: CompiledTemplate.Rendered.Unaddressable) {
            refusedCount++
            if (refused.size < LoadReport.UNMATCHED_IN_JSON) {
                refused += LoadReport.Unaddressable(rendered.index, rendered.missing)
            }
        }

        fun emit(stage: LoadStage) {
            val report = build(stage, LoadStatus.RUNNING, finishedAt = null, result = null, late = null, discarded = 0)
            store?.write(report)
            onProgress(report)
        }

        fun finalReport(status: LoadStatus, result: StampMatcher.Result, late: Long, discarded: Long, finishedAt: Long): LoadReport =
            build(LoadStage.DONE, status, finishedAt, result, late, discarded)

        @Suppress("LongParameterList")
        private fun build(
            stage: LoadStage,
            status: LoadStatus,
            finishedAt: Long?,
            result: StampMatcher.Result?,
            late: Long?,
            discarded: Long,
        ): LoadReport {
            val counts = result?.counts ?: matcher?.snapshot()
            val issued = stats
            val issue =
                LoadReport.Issue(
                    requested = plan.requested,
                    handedToEngine = issued?.handedToEngine ?: handed,
                    leftSocket = counts?.leftSocket ?: 0,
                    firstSendAt = counts?.firstSendMicros?.let { it / MICROS_PER_MILLI },
                    lastSendAt = counts?.lastSendMicros?.let { it / MICROS_PER_MILLI },
                    prepareMs = prepareMs,
                    unaddressable = issued?.unaddressable ?: refusedCount,
                )
            val replies =
                LoadReport.Replies(
                    matched = counts?.matched ?: 0,
                    unmatched = if (result != null) result.unmatched.size.toLong() else (counts?.pendingNow ?: 0).toLong(),
                    duplicates = counts?.duplicates ?: 0,
                    late = late ?: counts?.late ?: 0,
                    strays = counts?.strays ?: 0,
                    lastMatchedAt = counts?.lastMatchedMicros?.let { it / MICROS_PER_MILLI },
                )
            val rate =
                issued?.let { stats ->
                    when (val shape = plan.shape) {
                        is LoadShape.Rate -> rateReport(shape, stats)
                        // A ceiling has a report and no ceiling has nothing to report: an uncapped
                        // reactive phase issues as fast as its triggers arrive, which is a burst's answer.
                        is LoadShape.Triggered -> shape.cap?.let { capReport(it, stats) }
                        is LoadShape.Burst -> null
                    }
                }
            val tool =
                LoadReport.Tool(
                    discarded = discarded,
                    neverLeftSocket = issue.neverLeftSocket,
                    issueFailures = issued?.issueFailures ?: 0,
                    // Live too. The document leads on "outstanding · peak N" while a run is going, and this
                    // read 0 for the whole of it because only the final report ever carried a Result.
                    pendingPeak = result?.pendingPeak ?: matcher?.pendingPeakSoFar() ?: 0,
                )
            val sorted = result?.roundTripsSorted ?: matcher?.roundTripsSoFar()
            val timing =
                if (issue.firstSendAt != null && issue.lastSendAt != null && replies.lastMatchedAt != null) {
                    LoadReport.Timing(
                        elapsedMs = (replies.lastMatchedAt - issue.firstSendAt).coerceAtLeast(0),
                        drainMs = (replies.lastMatchedAt - issue.lastSendAt).coerceAtLeast(0),
                    )
                } else {
                    null
                }
            return LoadReport(
                id = plan.id,
                label = plan.label,
                status = status,
                stage = stage,
                template = LoadReport.TemplateInfo(plan.template.name, compiled.msgType, compiled.perMessageTags, compiled.fixedTags, compiled.onceTags),
                profileName = plan.profileName,
                lanes = lanes,
                listen = plan.listenProfileIds,
                shape = plan.shape,
                indexFrom = plan.indexFrom,
                match = plan.match,
                settleMs = plan.settleMs,
                seed = plan.seed,
                storeAndLog = plan.storeAndLog,
                strictRate = plan.strictRate,
                startedAt = startedAt,
                finishedAt = finishedAt,
                settleLeftMs = if (stage == LoadStage.SETTLING) settleLeftMs else null,
                issue = issue,
                rate = rate,
                replies = replies,
                timing = timing,
                roundTrip = sorted?.let { RunSetStats.of(it) },
                // Live as well as final, which is the point of the matcher keeping these incrementally:
                // `perSecond` used to be emptyList() for the whole run, because `result` is non-null only
                // in the final report, so the live document had no data source at all.
                roundTripHistogram = result?.histogram ?: matcher?.histogramSoFar() ?: RoundTripHistogram.empty(),
                perLane =
                    (result?.perLane ?: matcher?.perLaneSoFar()).orEmpty().map {
                        LoadReport.LaneCounts(it.slot, it.matched, it.unanswered, it.duplicates, it.p50Micros, it.p95Micros)
                    },
                perSecond =
                    (result?.perSecond ?: matcher?.bucketsSoFar())
                        .orEmpty()
                        .map { LoadReport.Second(it.second, it.issued, it.matched, it.p95Micros) },
                tool = tool,
                unmatched =
                    result?.unmatched.orEmpty().take(LoadReport.UNMATCHED_IN_JSON).map {
                        LoadReport.UnmatchedRequest(it.id, it.laneSlot, it.sentMicros / MICROS_PER_MILLI)
                    },
                unmatchedTotal = result?.unmatched?.size ?: (counts?.pendingNow ?: 0),
                capture =
                    plan.capture.takeIf { it.isNotEmpty() }?.let { names ->
                        val kept = result?.captured ?: captures.table?.counts().orEmpty()
                        LoadReport.Capture(names = names, captured = kept)
                    },
                unaddressable = refused.toList(),
                evidence = evidence,
                verdict = LoadReport.verdict(status, replies, rate, tool, plan.strictRate, issue),
            )
        }

        private fun rateReport(shape: LoadShape.Rate, stats: Pacer.IssueStats): LoadReport.RateReport {
            val floor = Math.floor(shape.perSecond * (1 - Pacer.TOLERANCE)).toInt()
            val fullSeconds = stats.perSecondIssued.dropLast(1)
            return LoadReport.RateReport(
                requestedPerSecond = shape.perSecond,
                heldForMs = fullSeconds.count { it >= floor } * MILLIS_PER_SECOND,
                shortfalls = stats.shortfalls.map { LoadReport.Shortfall(it.fromSecond, it.toSecond, it.minPerSecond, it.behind) },
                maxLagMs = stats.maxLagNanos / NANOS_PER_MILLI,
                tolerance = Pacer.TOLERANCE,
            )
        }

        /**
         * **A ceiling's own report: seconds spent at the cap, and seconds spent waiting for a trigger.**
         *
         * The same arithmetic a schedule gets and a different meaning for every number in it. The count
         * of full seconds that reached the floor is, under a ceiling, "the cap was the binding
         * constraint". Its complement over the same seconds is the phase sitting under the cap because
         * its trigger had less for it than the cap allowed, which is waiting rather than failing. So
         * there are no shortfalls, which the pacer gives free by finishing a reactive phase with no
         * requested rate at all, and the flag is what stops the verdict scoring a ceiling as a schedule.
         *
         * The seconds are counted from this phase's first issue, so whatever it spent waiting for its
         * first trigger is neither held nor starved. It had not started.
         */
        private fun capReport(cap: Int, stats: Pacer.IssueStats): LoadReport.RateReport {
            val floor = Math.floor(cap * (1 - Pacer.TOLERANCE)).toInt()
            val fullSeconds = stats.perSecondIssued.dropLast(1)
            val held = fullSeconds.count { it >= floor } * MILLIS_PER_SECOND
            return LoadReport.RateReport(
                requestedPerSecond = cap,
                heldForMs = held,
                shortfalls = emptyList(),
                maxLagMs = 0,
                tolerance = Pacer.TOLERANCE,
                ceiling = true,
                starvedForMs = fullSeconds.size * MILLIS_PER_SECOND - held,
            )
        }
    }

    /**
     * The request id off a rendered message: header first, then body, which is the order
     * `AcceptorResponder.valueOf` reads a tag in and therefore the order the venue will.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun requestId(message: quickfix.Message, tag: Int): String? =
        try {
            when {
                message.header.isSetField(tag) -> message.header.getString(tag)
                message.isSetField(tag) -> message.getString(tag)
                else -> null
            }
        } catch (e: Exception) {
            null
        }

    companion object {
        /** One listener per participating session, closed together. What a single run has always done. */
        fun everySession(matcher: StampMatcher, lanes: List<LoadLane>): AutoCloseable {
            val handles = lanes.map { it.addStampListener(matcher::onStamp) }
            return AutoCloseable { handles.forEach { it.close() } }
        }

        const val SETTLE_POLL_MS = 100L
        private const val PROGRESS_EVERY_NANOS = 250_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MICROS_PER_MILLI = 1_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
