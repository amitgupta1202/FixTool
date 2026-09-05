package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadPhase
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
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
) {
    /** The finished report, and the evidence the record keeps beside it. */
    data class Outcome(
        val report: LoadReport,
        val unmatched: List<StampMatcher.Unmatched>,
        val specimens: List<StampMatcher.Specimen>,
    )

    /**
     * Runs [plan] to its verdict. Throws [LoadRefused] when it cannot start: a template without a message
     * type, a variable nothing seeds, no lane logged on.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run(plan: LoadPlan, cancelled: () -> Boolean = { false }, onProgress: (LoadReport) -> Unit = {}): Outcome {
        val startedAt = host.now()
        val compiled =
            try {
                CompiledTemplate.compile(plan.template)
            } catch (e: IllegalArgumentException) {
                throw LoadRefused(e.message ?: "the template cannot be compiled")
            }
        val missing = compiled.missingVariables(plan.seed.keys + Lane.SEED_NAMES)
        if (missing.isNotEmpty()) {
            throw LoadRefused(
                "the template reads ${missing.joinToString(", ") { "\${$it}" }} and nothing seeds " +
                    "${if (missing.size == 1) "it" else "them"}: pass --set ${missing.first()}=…",
            )
        }

        val progress = Progress(plan, compiled, startedAt, onProgress)
        progress.emit(LoadPhase.PREPARING)

        val lanes = host.openLanes(plan.profileId, plan.storeAndLog)
        if (lanes.isEmpty()) throw LoadRefused("no session of '${plan.profileName}' reached LOGGED_ON, so there is nothing to issue on")
        val listeners = host.openListeners(plan.listenProfileIds, plan.storeAndLog)
        val all = lanes + listeners
        progress.lanes = lanes.size
        val discardedBefore = all.sumOf { it.discarded() }

        val prepareStart = clock.nanoTime()
        val prototypes =
            lanes.map { lane ->
                compiled.prepare(lane.lane, plan.seed, host.dictionary()) { template ->
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
            )
        progress.matcher = matcher
        val handles = all.map { it.addStampListener(matcher::onStamp) }

        try {
            progress.emit(LoadPhase.ISSUING)
            val handed = AtomicLong()
            var lastEmit = clock.nanoTime()
            val stats =
                Pacer(plan.shape, lanes.size, clock).run(
                    issue = { laneIndex, messageIndex ->
                        val ok = lanes[laneIndex].send(prototypes[laneIndex].render(messageIndex))
                        if (ok) handed.incrementAndGet()
                        progress.handed = handed.get()
                        val now = clock.nanoTime()
                        if (now - lastEmit > PROGRESS_EVERY_NANOS) {
                            lastEmit = now
                            progress.emit(LoadPhase.ISSUING)
                        }
                        ok
                    },
                    cancelled = cancelled,
                )
            progress.handed = stats.handedToEngine
            progress.stats = stats

            // Settle: the window closes early the moment nothing is pending, and it never ages a send out.
            val settleStart = host.now()
            var stoppedInSettle = false
            while (matcher.snapshot().pendingNow > 0 && host.now() - settleStart < plan.settleMs) {
                if (cancelled()) {
                    stoppedInSettle = true
                    break
                }
                progress.settleLeftMs = (plan.settleMs - (host.now() - settleStart)).coerceAtLeast(0)
                progress.emit(LoadPhase.SETTLING)
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
            store?.write(report)
            store?.writeEvidence(plan.id, result.unmatched, result.specimens)
            onProgress(report)
            return Outcome(report, result.unmatched, result.specimens)
        } finally {
            handles.forEach { it.close() }
        }
    }

    /** The report so far, assembled from whatever is known at the moment it is asked for. */
    private inner class Progress(
        private val plan: LoadPlan,
        private val compiled: CompiledTemplate,
        private val startedAt: Long,
        private val onProgress: (LoadReport) -> Unit,
    ) {
        var lanes = 0
        var prepareMs = 0L
        var handed = 0L
        var settleLeftMs: Long? = null
        var matcher: StampMatcher? = null
        var stats: Pacer.IssueStats? = null

        fun emit(phase: LoadPhase) {
            val report = build(phase, LoadStatus.RUNNING, finishedAt = null, result = null, late = null, discarded = 0)
            store?.write(report)
            onProgress(report)
        }

        fun finalReport(status: LoadStatus, result: StampMatcher.Result, late: Long, discarded: Long, finishedAt: Long): LoadReport =
            build(LoadPhase.DONE, status, finishedAt, result, late, discarded)

        @Suppress("LongParameterList")
        private fun build(
            phase: LoadPhase,
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
            val rate = (plan.shape as? LoadShape.Rate)?.let { shape -> issued?.let { rateReport(shape, it) } }
            val tool =
                LoadReport.Tool(
                    discarded = discarded,
                    neverLeftSocket = issue.neverLeftSocket,
                    issueFailures = issued?.issueFailures ?: 0,
                    pendingPeak = result?.pendingPeak ?: 0,
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
                phase = phase,
                template = LoadReport.TemplateInfo(plan.template.name, compiled.msgType, compiled.perMessageTags, compiled.fixedTags, compiled.onceTags),
                profileName = plan.profileName,
                lanes = lanes,
                listen = plan.listenProfileIds,
                shape = plan.shape,
                match = plan.match,
                settleMs = plan.settleMs,
                seed = plan.seed,
                storeAndLog = plan.storeAndLog,
                strictRate = plan.strictRate,
                startedAt = startedAt,
                finishedAt = finishedAt,
                settleLeftMs = if (phase == LoadPhase.SETTLING) settleLeftMs else null,
                issue = issue,
                rate = rate,
                replies = replies,
                timing = timing,
                roundTrip = sorted?.let { RunSetStats.of(it) },
                perSecond = result?.perSecond?.map { LoadReport.Second(it.second, it.issued, it.matched, it.p95Micros) } ?: emptyList(),
                tool = tool,
                unmatched =
                    result?.unmatched.orEmpty().take(LoadReport.UNMATCHED_IN_JSON).map {
                        LoadReport.UnmatchedRequest(it.id, it.laneSlot, it.sentMicros / MICROS_PER_MILLI)
                    },
                unmatchedTotal = result?.unmatched?.size ?: (counts?.pendingNow ?: 0),
                verdict = LoadReport.verdict(status, replies, rate, tool, plan.strictRate),
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
    }

    companion object {
        const val SETTLE_POLL_MS = 100L
        private const val PROGRESS_EVERY_NANOS = 250_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MICROS_PER_MILLI = 1_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
