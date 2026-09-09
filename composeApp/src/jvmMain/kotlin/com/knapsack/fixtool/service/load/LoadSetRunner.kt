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
     * **The reply router: one listener per session for the whole set, and every stamp to the phase that
     * issued it.**
     *
     * A phase owns a stamp when it can name it: a reply carrying an id it has outstanding or has already
     * matched, a send carrying an id it handed over on its way to the socket. The router asks each
     * registered phase in turn, newest first, and offers the stamp only to the one that says yes. Asking
     * rather than reading is the point of it. Two phases of one set can wait on different tags of
     * different message types, so there is no single id the router could pull off a stamp and hold up to
     * everybody: only a matcher knows how a matcher reads an id. See [StampMatcher.owns].
     *
     * A phase stays registered after its run ends, because a reply to something it issued is still its own
     * however long after it ended it arrives. A fill drawn by one of phase 1's orders and landing while
     * phase 2 cancels is phase 1's late reply, not a phase 2 stray.
     *
     * **A send goes to its issuer or nowhere.** Two phases can legitimately issue the same MsgType on the
     * same lanes, and a phase offered a send it never made would take it into its own `pending` and its
     * own per-second buckets, quietly corrupting numbers it had already reported.
     *
     * **A reply nobody issued is a stray**, charged once to the newest phase waiting for a reply of that
     * shape, which is the phase whose "nothing matched" diagnosis has to explain it. Once and not once per
     * phase: the offers stop at the first phase that recognises the shape.
     */
    private class Router {
        /** One phase's matcher and the number the whole set knows that phase by. */
        private class Registered(
            val phase: Int,
            val matcher: StampMatcher,
        )

        /**
         * Newest phase first, which is the order a reply is most likely to belong to and the phase a stray
         * belongs to. Ordered by phase number rather than by arrival, because a phase's place in the set is
         * what "newest" means and phases will not always start in that order.
         */
        private val registered = CopyOnWriteArrayList<Registered>()

        fun onStamp(stamp: SocketStamp) {
            val owner = registered.firstOrNull { it.matcher.owns(stamp) }
            if (owner != null) {
                owner.matcher.offer(stamp)
                return
            }
            // Nobody issued it. A send stops here: only its issuer may count it. A reply is somebody's
            // stray, and whose is decided by the first phase that recognises the shape it arrived in.
            if (stamp.direction == WireDirection.SEND) return
            registered.firstOrNull { it.matcher.offer(stamp) == StampMatcher.Claim.UNKNOWN }?.matcher?.countStray()
        }

        /**
         * Puts [matcher] in as phase [phase]. Closing stops nothing: the phase keeps its stamps for the
         * length of the set, which is what a late reply needs and what the record re-reads for it.
         */
        fun register(phase: Int, matcher: StampMatcher): AutoCloseable {
            val at = registered.indexOfFirst { it.phase < phase }
            registered.add(if (at < 0) registered.size else at, Registered(phase, matcher))
            return AutoCloseable { }
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

        /**
         * A phase listening on a profile that also issues somewhere gets that profile's whole lane list,
         * because the set holds one set of sessions per profile and there is no second one to hand back.
         */
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
        // The phases that will actually run. A muted phase's lanes, captures and indices are none of the
        // set's business, and it keeps its position in every list that is indexed by phase number.
        val live = planned.phases.filterNot { it.muted }

        val router = Router()
        val held = HeldLanes(host, byProfile)
        // One table for the whole set, sized by the highest index any phase will reach, because a phase
        // that counts from 2,001 reads what phase 1 filled at 2,001. Absent when nothing is captured.
        val names = live.flatMap { it.capture.keys }
        val table =
            names.takeIf { it.isNotEmpty() }?.let {
                StampMatcher.CaptureTable(it, (live.maxOf { p -> p.indexTo } + 1).toInt())
            }
        val sessions = byProfile.values.flatten().distinct()
        val handles = sessions.map { it.addStampListener(router::onStamp) }

        val reports =
            planned.phases
                .mapIndexed { index, plan ->
                    // A muted phase is skipped from the FIRST record rather than when the loop reaches it,
                    // so the live document draws it as parked from the first tick instead of as queued.
                    if (plan.muted) {
                        LoadReport
                            .stub(
                                plan,
                                LoadStatus.SKIPPED,
                                lanes = 0,
                                template = compiled[index],
                                startedAt = startedAt,
                                note = LoadRecord.MUTED_NOTE,
                            ).copy(finishedAt = startedAt)
                    } else {
                        val lanes = byProfile[plan.profileId]?.size ?: 0
                        LoadReport.stub(plan, LoadStatus.PENDING, lanes, compiled[index], startedAt)
                    }
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
                // Before the policy and before the stop, so neither overwrites the muted note: a set
                // stopped by hand must still say a parked phase was parked and not that the stop got it.
                if (plan.muted) return@forEachIndexed
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
                        router.register(n, matcher)
                    }
                // Only what EARLIER LIVE phases captured: a phase reading its own capture, a later one's,
                // or a muted one's has been refused by LoadSet.problems() before anything dialled.
                val earlier =
                    planned.phases
                        .take(index)
                        .filterNot { it.muted }
                        .flatMap { it.capture.keys }
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
     * **Every lane the set will ever need, open before phase 1 dials, with the store override applied once.**
     *
     * **Issuers first.** A profile that issues in *any* phase is opened as lanes, so it gets **all** of
     * them, with the 1-based slots every other door hands out. Walking the phases in order and opening
     * whichever door that phase happened to name put a profile that listens in phase 1 and issues in phase
     * 2 into the map as a listener, which is one session wrapped as slot 0. Phase 2 then issued from that
     * single lane whatever `sessionCount` said, and `${sessionIndex}` rendered 0 on every message of it.
     *
     * **Listeners second, and only for a profile that never issues.** One session is all matching needs
     * from a profile nothing is sent on. A profile that does both listens on its issuing lanes, which is
     * right because [Router] puts a stamp listener on every session the set holds for the whole set,
     * rather than on the lanes of the phase that happens to be live.
     *
     * A phase whose issuing profile has no lane logged on is refused here, before phase 1 dials, with the
     * sentence a single run gives. Anything already open is released on the way out, so a set refused on
     * phase four does not leave phase one's lanes up.
     *
     * **A muted phase's profile is neither opened nor judged**, which is what "the venue leg this phase
     * issues on is down" needs from muting it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun openEveryLane(planned: LoadSet.Planned): Map<String, List<LoadLane>> {
        // Indexed, because the refusal names the phase by its place in the whole set and a muted phase
        // keeps its number: phase 3 of a set whose phase 2 is parked is still phase 3.
        val live = planned.phases.withIndex().filterNot { it.value.muted }
        val byProfile = linkedMapOf<String, List<LoadLane>>()
        try {
            live.forEach { (_, plan) ->
                byProfile.getOrPut(plan.profileId) { host.openLanes(plan.profileId, planned.storeAndLog) }
            }
            live.forEach { (_, plan) ->
                plan.listenProfileIds.forEach { id ->
                    byProfile.getOrPut(id) { host.openListeners(listOf(id), planned.storeAndLog) }
                }
            }
            live.forEach { (index, plan) ->
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
            stopped -> LoadRecord.STOPPED_NOTE
            firstFailure != null && onFailure == OnFailure.STOP ->
                "phase $firstFailure did not pass and the set stops on failure"
            else -> null
        }

    /**
     * The template's shape for the record, or a refusal for the whole set when it will not compile.
     *
     * **Except for a muted phase**, whose template is never rendered: its stub carries the name and the
     * MsgType and moves on, because a phase parked while its message is half written must not be the
     * reason the two phases either side of it cannot run.
     */
    @Suppress("SwallowedException")
    private fun compile(plan: LoadPlan): LoadReport.TemplateInfo {
        val compiled =
            try {
                CompiledTemplate.compile(plan.template)
            } catch (e: IllegalArgumentException) {
                if (plan.muted) {
                    return LoadReport.TemplateInfo(
                        plan.template.name,
                        plan.template.msgType ?: "",
                        emptyList(),
                        emptyList(),
                        emptyList(),
                    )
                }
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
