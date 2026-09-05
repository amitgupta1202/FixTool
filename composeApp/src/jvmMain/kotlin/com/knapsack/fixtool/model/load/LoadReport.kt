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
    val phase: LoadPhase,
    val template: TemplateInfo,
    val profileName: String,
    val lanes: Int,
    val listen: List<String>,
    val shape: LoadShape,
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
    val perSecond: List<Second>,
    val tool: Tool,
    /** The first [UNMATCHED_IN_JSON] unanswered requests. The whole set is in `unmatched.fix`. */
    val unmatched: List<UnmatchedRequest>,
    val unmatchedTotal: Int,
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
        fun verdict(
            status: LoadStatus,
            replies: Replies,
            rate: RateReport?,
            tool: Tool,
            strictRate: Boolean,
        ): Verdict {
            val completeness =
                when {
                    status == LoadStatus.RUNNING -> Completeness.PENDING
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
    RUNNING,

    /** Ran to the end of its settle window, whatever the verdict. */
    DONE,

    /** Stopped by the author, or found stopped by a process that exited under it. Exit 1: it proved nothing whole. */
    STOPPED,
}

enum class LoadPhase {
    PREPARING,
    ISSUING,
    SETTLING,
    DONE,
}
