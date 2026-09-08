package com.knapsack.fixtool.model.load

import com.knapsack.fixtool.service.RunSetStats

/**
 * **Everything a load run has to say, live and afterwards, in one shape.**
 *
 * The same object is the progress line while the run issues and settles, the `load.json` on disk when it
 * is done, the document's source in the app, and the body `GET /loads/<id>` returns. One shape for all
 * four is what makes a run started from the terminal overnight the same document in Recent the next
 * morning. While [status] is RUNNING the counts are so far and [replies].`unmatched` means pending now.
 */
data class LoadReport(
    /** Also the record directory: `loads/<id>/`. */
    val id: String,
    /** "NOS EUR/USD 1M ×4,000 on LOADGEN". */
    val label: String,
    val status: LoadStatus,
    /** Where the phase is in its own lifecycle: preparing, issuing, settling, done. */
    val stage: LoadStage,
    val template: TemplateInfo,
    val profileName: String,
    val lanes: Int,
    val listen: List<String>,
    val shape: LoadShape,
    /** Where this phase's `${'$'}{messageIndex}` started. 1 for a single run and for a set's first phase. */
    val indexFrom: Int = 1,
    val match: LoadMatch,
    val settleMs: Long,
    val seed: Map<String, String>,
    val storeAndLog: StoreAndLogOverride?,
    /** Whether the plan asked a rate shortfall to fail the build. Carried so the JUnit case can agree with the exit code. */
    val strictRate: Boolean,
    /** Epoch millis. */
    val startedAt: Long,
    val finishedAt: Long?,
    /** Milliseconds of settle window left while settling, null otherwise. */
    val settleLeftMs: Long?,
    val issue: Issue,
    val rate: RateReport?,
    val replies: Replies,
    val timing: Timing?,
    val roundTrip: RunSetStats.Distribution?,
    /**
     * The matched round trips as [RoundTripHistogram]: thirty counts, log-spaced, whatever the run's size.
     *
     * [roundTrip] is seven numbers and cannot be drawn — no sample used to reach this object at all, so
     * neither a bucket histogram nor a share-still-outstanding curve could be recovered from a record.
     * Thirty integers can be, they bound the memory, and because they are incremented as replies land they
     * are correct while the run is still going. Nothing judges on them.
     */
    val roundTripHistogram: List<Int> = RoundTripHistogram.empty(),
    val perSecond: List<Second>,
    /**
     * Completeness per lane: matched, unanswered, duplicates. Empty for a run recorded before it existed.
     *
     * Completeness and not latency, on purpose: one pacer loop renders and sends every lane round-robin,
     * so per-lane latency would partly measure FixTool's own ordering. See [LoadRunner]'s render-ahead work.
     */
    val perLane: List<LaneCounts> = emptyList(),
    val tool: Tool,
    /** The first [UNMATCHED_IN_JSON] unanswered requests. The whole set is in `unmatched.fix`. */
    val unmatched: List<UnmatchedRequest>,
    val unmatchedTotal: Int,
    /**
     * What this phase kept off each matched reply, and how many replies carried each name. Null when the
     * phase captures nothing, which is every single run.
     */
    val capture: Capture? = null,
    /** The first [UNMATCHED_IN_JSON] messages that could not be addressed. The whole count is in [Issue]. */
    val unaddressable: List<Unaddressable> = emptyList(),
    /**
     * The evidence files this phase wrote, by their names in the record directory. Null for a record
     * written before the names were in the JSON, which is the same thing as "the bare names".
     */
    val evidence: Evidence? = null,
    /**
     * Why this phase is what it is, when the numbers cannot say: "phase 2 did not pass and the set stops
     * on failure". Null for a phase that ran.
     */
    val note: String? = null,
    val verdict: Verdict,
) {
    /** What the template was, and which of its tags were rendered per message. */
    data class TemplateInfo(
        val name: String,
        val msgType: String,
        val perMessageTags: List<Int>,
        val fixedTags: List<Int>,
        /** The fixed tags that reached the script engine once per lane. */
        val onceTags: List<Int>,
    )

    /**
     * **The names of this phase's evidence files**, so nothing has to guess one.
     *
     * A set writes several phases into one record directory, so the names carry the phase number:
     * `02-unmatched.fix`. A record written before schema 3 has no names at all and its reader falls back
     * to the bare `unmatched.fix` the one-phase writer used.
     */
    data class Evidence(
        val unmatched: String,
        val specimens: String,
        /** The captured values, one line per index. Absent when the phase captures nothing. */
        val captured: String? = null,
    ) {
        companion object {
            /** `01-unmatched.fix`, `01-specimens.fix`: the names phase [n] writes, 1-based. */
            fun forPhase(n: Int, captured: Boolean = false): Evidence =
                Evidence(
                    unmatched = "%02d-unmatched.fix".format(n),
                    specimens = "%02d-specimens.fix".format(n),
                    captured = if (captured) "%02d-captured.tsv".format(n) else null,
                )
        }
    }

    /**
     * **"Issued" is three numbers.** Requested is what the plan asked for, handed to the engine is what
     * `sendToTarget` accepted, left the socket is what the SEND stamps counted. Completeness is judged
     * over the last, and the gap between the second and the third is the tool's own.
     */
    data class Issue(
        val requested: Long,
        val handedToEngine: Long,
        val leftSocket: Long,
        val firstSendAt: Long?,
        val lastSendAt: Long?,
        val prepareMs: Long,
        /**
         * Messages the plan asked for that were never rendered, because a capture an earlier phase should
         * have filled was not there.
         *
         * Counted, not hidden: the bar is "every requested message answered", so a message that was never
         * sent is a hole in the proof and not a smaller proof.
         */
        val unaddressable: Long = 0,
    ) {
        val spanMs: Long? get() = if (firstSendAt != null && lastSendAt != null) lastSendAt - firstSendAt else null

        /** Messages per second over the issue span. Null for a single message or none. */
        val achievedPerSecond: Long?
            get() = spanMs?.takeIf { it > 0 }?.let { leftSocket * MILLIS_PER_SECOND / it }

        val neverLeftSocket: Long get() = (handedToEngine - leftSocket).coerceAtLeast(0)
    }

    /** A rate run's schedule against what happened. Null for a burst, which has no schedule. */
    data class RateReport(
        val requestedPerSecond: Int,
        /** Full seconds in which the achieved rate met the requested one within [tolerance]. */
        val heldForMs: Long,
        val shortfalls: List<Shortfall>,
        val maxLagMs: Long,
        val tolerance: Double,
    )

    data class Shortfall(
        val fromSecond: Int,
        val toSecond: Int,
        val minPerSecond: Int,
        val behind: Long,
    )

    data class Replies(
        val matched: Long,
        /** Unanswered within the settle window once done. Pending now while running. */
        val unmatched: Long,
        val duplicates: Long,
        val late: Long,
        val strays: Long,
        val lastMatchedAt: Long?,
    )

    /** Elapsed is first send to last matched reply. Drain is last send to last matched reply. */
    data class Timing(
        val elapsedMs: Long,
        val drainMs: Long,
    )

    /**
     * What one lane matched, what it never got an answer to, what came back twice, and how long it took.
     *
     * The percentiles are the lane's own log-histogram buckets, and they are only worth showing because
     * each lane renders ahead of its own sends. Before that, one pacer loop rendered and sent every lane
     * round-robin and lane N was issued systematically later than lane 1.
     */
    data class LaneCounts(
        val slot: Int,
        val matched: Long,
        val unanswered: Long,
        val duplicates: Long,
        val p50Us: Long? = null,
        val p95Us: Long? = null,
    )

    /** One second of the run, counted from the first send. */
    data class Second(
        val second: Int,
        val issued: Int,
        val matched: Int,
        val p95Us: Long?,
    )

    /** **FixTool's own contribution**, which the report shows rather than hides. */
    data class Tool(
        /** Messages the panes' queues threw away during the run, summed over the participating sessions. */
        val discarded: Long,
        /** Messages the engine accepted that never produced a SEND stamp. */
        val neverLeftSocket: Long,
        /** Messages the engine refused. */
        val issueFailures: Long,
        /** The most requests outstanding at once. */
        val pendingPeak: Int,
    ) {
        val limited: Boolean get() = discarded > 0 || neverLeftSocket > 0 || issueFailures > 0
    }

    data class UnmatchedRequest(
        val id: String,
        val lane: Int,
        val sentAt: Long,
    )

    /**
     * **What this phase kept, and how much of it there was.**
     *
     * [names] is what was asked for, [captured] is how many replies actually carried each one. The two
     * differ whenever the venue answered without the tag, which is worth seeing before a later phase
     * cannot address 4 of 2,000.
     */
    data class Capture(
        val names: Map<String, Int>,
        val captured: Map<String, Int>,
    ) {
        /**
         * "quoteId, offer on 3,996 replies", or "quoteId on 3,995 · offer on 3,996" when they differ.
         *
         * The counts differing is the interesting case, because it is the venue answering without a tag,
         * and one number for both would hide exactly the thing a later phase is about to trip over.
         */
        fun describe(): String {
            val counts = names.keys.map { captured[it] ?: 0 }
            val same = counts.distinct().size <= 1
            return if (same) {
                "${names.keys.joinToString(", ")} on ${"%,d".format(counts.firstOrNull() ?: 0)} replies"
            } else {
                names.keys.joinToString(" · ") { "$it on ${"%,d".format(captured[it] ?: 0)}" }
            }
        }

        /** The highest count any name reached, for a figure that has to be one number. */
        fun most(): Int = captured.values.maxOrNull() ?: 0
    }

    /** One message the plan asked for that was never rendered, and the name that was missing. */
    data class Unaddressable(
        /** The 1-based message index, after `indexFrom`. */
        val index: Int,
        val missing: String,
    )

    /** Three separate judgements, and the exit code they add up to. */
    data class Verdict(
        val completeness: Completeness,
        val rate: RateVerdict,
        val tool: ToolVerdict,
        /** 0 or 1 once the run is over. Null while it runs. */
        val exitCode: Int?,
    )

    enum class Completeness {
        /** Every message that left the socket was answered within the settle window. */
        COMPLETE,
        UNMATCHED,

        /**
         * A message the plan asked for was never sent, because a capture it needed was not there.
         *
         * Its own word rather than UNMATCHED, because "unanswered" says the venue did not reply and this
         * says the tool never asked. Both fail, and a reader has to be able to tell them apart.
         */
        INCOMPLETE,

        /** Still running. */
        PENDING,
    }

    enum class RateVerdict {
        /** A burst has no schedule to hold. */
        NOT_APPLICABLE,
        HELD,
        SHORTFALL,
    }

    enum class ToolVerdict {
        CLEAN,
        LIMITED,
    }

    companion object {
        /**
         * **A phase that has not run: its plan, no measurements, and nothing to judge.**
         *
         * A skipped phase carries what would have run, so a reader can still say what the set was for, and
         * a pending one is drawn by the live document as a phase that has not started. Both are different
         * pictures from "a phase with no replies", which is why they are statuses and not empty reports.
         */
        @Suppress("LongParameterList")
        fun stub(
            plan: LoadPlan,
            status: LoadStatus,
            lanes: Int,
            template: TemplateInfo,
            startedAt: Long,
            note: String? = null,
        ): LoadReport =
            LoadReport(
                id = plan.id,
                label = plan.label,
                status = status,
                stage = LoadStage.PREPARING,
                template = template,
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
                finishedAt = null,
                settleLeftMs = null,
                issue = Issue(plan.requested, 0, 0, null, null, 0),
                rate = null,
                replies = Replies(0, 0, 0, 0, 0, null),
                timing = null,
                roundTrip = null,
                perSecond = emptyList(),
                tool = Tool(0, 0, 0, 0),
                unmatched = emptyList(),
                unmatchedTotal = 0,
                note = note,
                verdict = Verdict(Completeness.PENDING, RateVerdict.NOT_APPLICABLE, ToolVerdict.CLEAN, exitCode = null),
            )

        const val UNMATCHED_IN_JSON = 1_000
        const val EXIT_PASSED = 0
        const val EXIT_FAILED = 1
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * **The verdict, from the numbers.** Exit 1 when anything was unmatched, when the tool limited the
         * run, when the run was stopped before it finished, or on a rate shortfall the plan asked to fail on.
         * A shortfall without `strictRate` is reported and exits 0, because the venue answered everything and
         * a build that wants to gate on the tool's own pacing has to say so.
         */
        @Suppress("LongParameterList")
        fun verdict(
            status: LoadStatus,
            replies: Replies,
            rate: RateReport?,
            tool: Tool,
            strictRate: Boolean,
            /** What the plan asked for and the tool never sent. Fails the phase, like anything unanswered. */
            issue: Issue = Issue(0, 0, 0, null, null, 0),
        ): Verdict {
            val completeness =
                when {
                    status == LoadStatus.RUNNING -> Completeness.PENDING
                    // Before unanswered: a phase that could not address 4 of its 2,000 has a hole the
                    // venue is not responsible for, and that is the thing to say first.
                    issue.unaddressable > 0 -> Completeness.INCOMPLETE
                    replies.unmatched > 0 -> Completeness.UNMATCHED
                    else -> Completeness.COMPLETE
                }
            val rateVerdict =
                when {
                    rate == null -> RateVerdict.NOT_APPLICABLE
                    rate.shortfalls.isEmpty() -> RateVerdict.HELD
                    else -> RateVerdict.SHORTFALL
                }
            val toolVerdict = if (tool.limited) ToolVerdict.LIMITED else ToolVerdict.CLEAN
            val exit =
                when {
                    status == LoadStatus.RUNNING -> null
                    status == LoadStatus.STOPPED -> EXIT_FAILED
                    completeness == Completeness.INCOMPLETE -> EXIT_FAILED
                    completeness == Completeness.UNMATCHED -> EXIT_FAILED
                    toolVerdict == ToolVerdict.LIMITED -> EXIT_FAILED
                    strictRate && rateVerdict == RateVerdict.SHORTFALL -> EXIT_FAILED
                    else -> EXIT_PASSED
                }
            return Verdict(completeness, rateVerdict, toolVerdict, exit)
        }
    }
}

enum class LoadStatus {
    /**
     * A phase of a set that has not started. Only a set has one, and the live document has to draw it,
     * which is why it is a status and not the absence of a report.
     */
    PENDING,

    RUNNING,

    /** Ran to the end of its settle window, whatever the verdict. */
    DONE,

    /** Stopped by the author, or found stopped by a process that exited under it. Exit 1: it proved nothing whole. */
    STOPPED,

    /**
     * A phase of a set that will never run: an earlier one did not pass and the set stops on failure, or
     * the set was stopped by hand. It carries its plan and no measurements, so a reader can still say what
     * the set was for, and it is not judged.
     */
    SKIPPED,
    ;

    /** Still to come, or going now. What a set has left to do. */
    val isLive: Boolean get() = this == PENDING || this == RUNNING
}

/**
 * **Where one phase is in its own lifecycle.** Not to be confused with a phase of a set, which is what
 * `LoadRecord.phases` holds: this is the stage that phase has reached.
 */
enum class LoadStage {
    PREPARING,
    ISSUING,
    SETTLING,
    DONE,
}
