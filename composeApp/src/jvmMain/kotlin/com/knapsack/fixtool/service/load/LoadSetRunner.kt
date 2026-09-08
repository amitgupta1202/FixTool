package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.SocketStamp
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **A load set, start to verdict: several runs in order, on lanes that outlive any one of them.**
 *
 * Everything inside a phase is [LoadRunner] exactly as it is. The set owns the three things one run never
 * had to:
 *
 * - **Lanes that outlive a phase.** Opened once, before phase 1, for every profile any phase names, with
 *   the store override applied once. Opening lazily per phase would put a logon inside the set's clock and
 *   draw it on the timeline as phase time.
 * - **A seed that outlives a phase.** Rendered before this by [LoadSet.plan], and carried by every phase's
 *   plan, so phase 2 addresses the ids phase 1 minted without anything being passed between them.
 * - **The routing of a reply to the phase that asked for it.** See [Router]. Against a matching venue this
 *   is not a corner case: phase 1's orders keep drawing fills while phase 2 cancels them.
 *
 * The record is written after every phase and on every progress tick inside one, so a set killed at phase
 * three leaves phases one and two complete on disk, and the morning after an overnight set the answer is
 * in `loads/<id>/load.json` whether or not the app is still open.
 */
class LoadSetRunner(
    private val host: LoadHost,
    private val store: LoadRecordStore? = null,
    private val clock: Pacer.Clock = Pacer.Clock.SYSTEM,
) {
    /**
     * **The reply router: one listener per session for the whole set.**
     *
     * Every reply is offered to the live phase's matcher first. If it names something that phase issued, it
     * is that phase's match or duplicate. If not, each finished phase's matcher is offered it in turn,
     * newest first, so a reply to something phase 1 issued is phase 1's late reply or duplicate however
     * long after phase 1 ended it arrives. A reply nobody issued is a stray of the phase that was running
     * when it came, which is the count the "nothing matched" diagnosis reads, so it has to be honest.
     *
     * A **send** goes only to the live phase. Two phases can legitimately issue the same MsgType on the
     * same lanes, and a finished phase offered a send it never made would take it into its own `pending`
     * and its own per-second buckets, quietly corrupting numbers it had already reported.
     */
    private class Router {
        @Volatile
        var live: StampMatcher? = null

        /** Newest first, which is the order a late reply is most likely to belong to. */
        private val finished = CopyOnWriteArrayList<StampMatcher>()

        fun onStamp(stamp: SocketStamp) {
            val current = live
            if (stamp.direction == WireDirection.SEND) {
                current?.offer(stamp)
                return
            }
            var recognised = false
            if (current != null) {
                when (current.offer(stamp)) {
                    StampMatcher.Claim.MINE -> return
                    StampMatcher.Claim.UNKNOWN -> recognised = true
                    StampMatcher.Claim.NOT_A_REPLY -> Unit
                }
            }
            for (matcher in finished) {
                when (matcher.offer(stamp)) {
                    StampMatcher.Claim.MINE -> return
                    StampMatcher.Claim.UNKNOWN -> recognised = true
                    StampMatcher.Claim.NOT_A_REPLY -> Unit
                }
            }
            if (recognised) current?.countStray()
        }

        /** Makes [matcher] the live phase, and on close moves it to the finished list rather than dropping it. */
        fun register(matcher: StampMatcher): AutoCloseable {
            live = matcher
            return AutoCloseable {
                finished.add(0, matcher)
                if (live === matcher) live = null
            }
        }
    }

    /**
     * The set's own [LoadHost], handed to each phase: the lanes are already open and `release()` does
     * nothing, because the set releases after the last phase.
     */
    private class HeldLanes(
        private val delegate: LoadHost,
        private val byProfile: Map<String, List<LoadLane>>,
    ) : LoadHost {
        override fun openLanes(profileId: String, override: StoreAndLogOverride?): List<LoadLane> =
            byProfile[profileId].orEmpty()

        override fun openListeners(profileIds: List<String>, override: StoreAndLogOverride?): List<LoadLane> =
            profileIds.flatMap { byProfile[it].orEmpty() }

        override fun resolveOnce(template: String, scope: Map<String, String>, lane: LoadLane): String =
            delegate.resolveOnce(template, scope, lane)

        override fun dictionary(): FixDictionaryAdapter = delegate.dictionary()

        /** The set releases, once, after the last phase. A phase releasing would log the lanes out mid-set. */
        override fun release() = Unit

        override fun now(): Long = delegate.now()

        override fun sleep(ms: Long) = delegate.sleep(ms)
    }

    /**
     * Runs [planned] to its verdict, writing the record as it goes.
     *
     * Throws [LoadRefused] when the set cannot start: a template that will not compile, a name nothing
     * seeds, or a phase whose issuing profile has no lane logged on. Nothing dials for phase 1 until every
     * phase has been looked at, so a set of six never fails on phase five for something that could have
     * been said before phase one.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run(
        planned: LoadSet.Planned,
        cancelled: () -> Boolean = { false },
        onProgress: (LoadRecord) -> Unit = {},
    ): LoadRecord {
        val startedAt = host.now()
        val compiled = planned.phases.map { plan -> compile(plan) }
        val byProfile = openEveryLane(planned)

        val router = Router()
        val held = HeldLanes(host, byProfile)
        // One table for the whole set, sized by the highest index any phase will reach, because a phase
        // that counts from 2,001 reads what phase 1 filled at 2,001. Absent when nothing is captured.
        val names = planned.phases.flatMap { it.capture.keys }
        val table =
            names.takeIf { it.isNotEmpty() }?.let {
                StampMatcher.CaptureTable(it, (planned.phases.maxOf { p -> p.indexTo } + 1).toInt())
            }
        val sessions = byProfile.values.flatten().distinct()
        val handles = sessions.map { it.addStampListener(router::onStamp) }

        val reports =
            planned.phases
                .mapIndexed { index, plan ->
                    val lanes = byProfile[plan.profileId]?.size ?: 0
                    LoadReport.stub(plan, LoadStatus.PENDING, lanes, compiled[index], startedAt)
                }.toMutableList()
        // Kept for the whole set: a finished phase's late count keeps growing while a later phase runs, and
        // the record has to say so rather than freezing the number the phase happened to end on.
        val matchers = arrayOfNulls<StampMatcher>(planned.phases.size)

        fun record(): LoadRecord =
            LoadRecord(
                id = planned.id,
                label = planned.label,
                startedAt = startedAt,
                finishedAt = if (reports.any { it.status.isLive }) null else host.now(),
                phases = reports.mapIndexed { i, r -> withLate(r, matchers[i]) },
                set = LoadRecord.SetInfo(planned.name, planned.onFailure),
                seed = planned.seed,
            )

        fun publish() {
            val current = record()
            store?.write(current)
            onProgress(current)
        }

        try {
            publish()
            var firstFailure: Int? = null
            planned.phases.forEachIndexed { index, plan ->
                val n = index + 1
                val skip = skipNote(firstFailure, planned.onFailure, cancelled())
                if (skip != null) {
                    reports[index] =
                        reports[index].copy(status = LoadStatus.SKIPPED, note = skip, finishedAt = host.now())
                    publish()
                    return@forEachIndexed
                }
                val runner =
                    LoadRunner(held, store = null, clock = clock) { matcher, _ ->
                        matchers[index] = matcher
                        router.register(matcher)
                    }
                // Only what EARLIER phases captured: a phase reading its own capture, or a later one's, has
                // been refused by LoadSet.problems() before anything dialled.
                val earlier = planned.phases.take(index).flatMap { it.capture.keys }
                val captures =
                    LoadRunner.Captures(
                        lookups = table?.let { t -> earlier.associateWith { t.lookup(it) } }.orEmpty(),
                        table = table.takeIf { plan.capture.isNotEmpty() },
                    )
                val outcome =
                    runner.run(plan, phase = n, captures = captures, cancelled = cancelled) { progress ->
                        reports[index] = progress
                        publish()
                    }
                reports[index] = outcome.report
                outcome.report.evidence?.let { files ->
                    store?.writeEvidence(planned.id, files, outcome.unmatched, outcome.specimens, outcome.captured)
                }
                publish()
                if (firstFailure == null && outcome.report.verdict.exitCode != LoadReport.EXIT_PASSED) firstFailure = n
            }
            return record().also {
                store?.write(it)
                onProgress(it)
            }
        } finally {
            handles.forEach { it.close() }
            host.release()
        }
    }

    /**
     * Every profile any phase names, opened once, with the store override applied once.
     *
     * A phase whose issuing profile has no lane logged on is refused here, before phase 1 dials, with the
     * sentence a single run gives. Anything already open is released on the way out, so a set refused on
     * phase four does not leave phase one's lanes up.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun openEveryLane(planned: LoadSet.Planned): Map<String, List<LoadLane>> {
        val byProfile = linkedMapOf<String, List<LoadLane>>()
        try {
            planned.phases.forEach { plan ->
                byProfile.getOrPut(plan.profileId) { host.openLanes(plan.profileId, planned.storeAndLog) }
                plan.listenProfileIds.forEach { id ->
                    byProfile.getOrPut(id) { host.openListeners(listOf(id), planned.storeAndLog) }
                }
            }
            planned.phases.forEachIndexed { index, plan ->
                if (byProfile[plan.profileId].isNullOrEmpty()) {
                    throw LoadRefused(
                        "phase ${index + 1}: no session of '${plan.profileName}' reached LOGGED_ON, " +
                            "so there is nothing to issue on",
                    )
                }
            }
        } catch (e: Exception) {
            host.release()
            throw e
        }
        return byProfile
    }

    /** A finished phase's late count, re-read from its matcher, because a reply to it can still arrive. */
    private fun withLate(report: LoadReport, matcher: StampMatcher?): LoadReport {
        if (matcher == null || report.status != LoadStatus.DONE) return report
        val late = matcher.snapshot().late
        return if (late == report.replies.late) report else report.copy(replies = report.replies.copy(late = late))
    }

    /** Why this phase will not run, or null when it will. */
    private fun skipNote(firstFailure: Int?, onFailure: OnFailure, stopped: Boolean): String? =
        when {
            stopped -> "the set was stopped"
            firstFailure != null && onFailure == OnFailure.STOP ->
                "phase $firstFailure did not pass and the set stops on failure"
            else -> null
        }

    @Suppress("SwallowedException")
    private fun compile(plan: LoadPlan): LoadReport.TemplateInfo {
        val compiled =
            try {
                CompiledTemplate.compile(plan.template)
            } catch (e: IllegalArgumentException) {
                throw LoadRefused(e.message ?: "the template '${plan.template.name}' cannot be compiled")
            }
        return LoadReport.TemplateInfo(
            plan.template.name,
            compiled.msgType,
            compiled.perMessageTags,
            compiled.fixedTags,
            compiled.onceTags,
        )
    }
}
