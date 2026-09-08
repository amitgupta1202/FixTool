package com.knapsack.fixtool.model.load

/**
 * **What a load run leaves on disk: a set with one phase.**
 *
 * A single run and a load set are the same record, because the alternative is two readers, two JSON
 * schemas and two Compares for what is one question — "how did this go" — asked of one phase or of
 * several. "Load run…" stays exactly as it is and saves as a one-phase set behind the scenes; nobody
 * has to build a set to fire one burst.
 *
 * This lands before Compare rather than with the load-set work, because Compare reads this schema and a
 * Compare written against the old one would have to be written twice.
 *
 * A record written before this shape existed is a bare report object with no `phases`, and reads back as
 * a one-phase set. Nothing on disk needs migrating.
 */
data class LoadRecord(
    /** Also the record directory: `loads/<id>/`. */
    val id: String,
    val label: String,
    val startedAt: Long,
    val finishedAt: Long?,
    /** One for a run, several for a set, in the order they were asked for. Never empty. */
    val phases: List<LoadReport>,
    /** The set this came from, or null for a "Load run…" record, which is a set of one with no file. */
    val set: SetInfo? = null,
    /** The seed as rendered, once, and shared by every phase. */
    val seed: Map<String, String> = emptyMap(),
) {
    /** Which saved set produced this, and what it did between phases. */
    data class SetInfo(
        val name: String,
        val onFailure: OnFailure,
    )

    /** The phase a single run is. What the document draws and what every surface but Compare reads. */
    val only: LoadReport get() = phases.last()

    /**
     * The set's status: still going while any phase is, stopped if any was, done otherwise.
     *
     * A set that stopped in phase two is a stopped set however cleanly phase one ran, for the same reason
     * a stopped run is not judged: what it proved is a fraction of what was asked.
     */
    val status: LoadStatus
        get() =
            when {
                phases.any { it.status == LoadStatus.RUNNING || it.status == LoadStatus.PENDING } -> LoadStatus.RUNNING
                phases.any { it.status == LoadStatus.STOPPED } -> LoadStatus.STOPPED
                else -> LoadStatus.DONE
            }

    /**
     * The worst exit code any phase that ran reached, or null while one is still going.
     *
     * A skipped phase is not judged and contributes nothing, which is the whole reason the set can exit 1
     * on the phase that failed while the two after it never dialled.
     *
     * **Null while any phase is still to come**, and not the worst of the phases that have landed so far.
     * A row on `GET /loads` carries the exit code only when it has one, so a poller reads a number here as
     * "the set is over": a set whose phase 1 passed and whose phase 2 has not started would otherwise
     * report 0 and be read as finished and green.
     *
     * A finished set where nothing was judged at all, every phase skipped, exits 1. It proved nothing, and
     * a build cannot pass on it — see [verdict], which says the same in words.
     */
    val exitCode: Int?
        get() {
            if (phases.any { it.status.isLive }) return null
            val judged = phases.filterNot { it.status == LoadStatus.SKIPPED }
            if (judged.any { it.verdict.exitCode == null }) return null
            return judged.maxOfOrNull { it.verdict.exitCode ?: 0 } ?: LoadReport.EXIT_FAILED
        }

    /**
     * **The phase a one-line row leads on**: the one running now, else the phase the verdict names, else
     * the last phase that was judged.
     *
     * Never a skipped or pending stub while any phase has numbers, because a stub carries its plan and no
     * measurements: leading on the last phase of a set that failed at phase two under STOP put "issued 0,
     * matched 0, stage preparing" on the row of a set that issued four thousand messages, which is a
     * poller's whole picture of it.
     */
    val lead: LoadReport
        get() {
            phases.firstOrNull { it.status == LoadStatus.RUNNING }?.let { return it }
            val named = verdict.phase?.let { phases.getOrNull(it - 1) }
            if (named != null && !named.status.isLive && named.status != LoadStatus.SKIPPED) return named
            return phases.lastOrNull { it.status == LoadStatus.DONE || it.status == LoadStatus.STOPPED }
                ?: phases.first()
        }

    /** The phase running now, 1-based, or null when none is. */
    val currentPhase: Int? get() = phases.indexOfFirst { it.status == LoadStatus.RUNNING }.takeIf { it >= 0 }?.plus(1)

    /** How many phases have run to a verdict, whatever it was. */
    val donePhases: Int get() = phases.count { it.status == LoadStatus.DONE || it.status == LoadStatus.STOPPED }

    /**
     * **The set's own verdict**, which is the thing the badge, the terminal's last line and the JUnit
     * `<testsuites>` all read.
     *
     * A set passes when every phase did. It fails naming the **first** phase that did not, which is the
     * one worth looking at whether the policy stopped there or carried on. Stopped by hand is its own
     * outcome, because a build cannot pass on a run somebody ended.
     *
     * **A set where no phase was judged does not pass.** Stop the set the moment after it is accepted and
     * every phase is skipped before it dials, with nothing stopped and nothing failed: on the count of
     * failures alone that reads as PASSED with nothing passed, which is the one verdict that could put a
     * green tick on a set that never sent a message. It is STOPPED when the skip note says the stop ended
     * it, and FAILED otherwise, because something else refused it before phase one.
     */
    val verdict: SetVerdict
        get() {
            val passed = phases.count { it.verdict.exitCode == LoadReport.EXIT_PASSED && it.status == LoadStatus.DONE }
            val skipped = phases.count { it.status == LoadStatus.SKIPPED }
            val failed = phases.count { it.status == LoadStatus.DONE && it.verdict.exitCode != LoadReport.EXIT_PASSED }
            val stopped = phases.count { it.status == LoadStatus.STOPPED }
            val stoppedAt = phases.indexOfFirst { it.status == LoadStatus.STOPPED }.takeIf { it >= 0 }?.plus(1)
            val firstBad =
                phases
                    .indexOfFirst { it.status == LoadStatus.DONE && it.verdict.exitCode != LoadReport.EXIT_PASSED }
                    .takeIf { it >= 0 }
                    ?.plus(1)
            val firstSkipped = phases.indexOfFirst { it.status == LoadStatus.SKIPPED }.takeIf { it >= 0 }?.plus(1)
            val nothingJudged = passed == 0 && failed == 0 && stopped == 0
            val outcome =
                when {
                    stoppedAt != null -> SetOutcome.STOPPED
                    status == LoadStatus.RUNNING -> SetOutcome.RUNNING
                    firstBad != null -> SetOutcome.FAILED
                    nothingJudged && phases.first().note == STOPPED_NOTE -> SetOutcome.STOPPED
                    nothingJudged -> SetOutcome.FAILED
                    else -> SetOutcome.PASSED
                }
            val phase =
                when (outcome) {
                    SetOutcome.STOPPED -> stoppedAt ?: firstSkipped
                    SetOutcome.RUNNING -> currentPhase
                    SetOutcome.FAILED -> firstBad ?: firstSkipped
                    SetOutcome.PASSED -> null
                }
            return SetVerdict(outcome, phase, passed = passed, failed = failed, stopped = stopped, skipped = skipped)
        }

    companion object {
        /**
         * The note a phase carries when the stop, and not a failure, is why it never ran.
         *
         * Here rather than in the runner because the verdict reads it: a set skipped whole has no stopped
         * phase to name, so the note is the only thing that says whether somebody ended it.
         */
        const val STOPPED_NOTE = "the set was stopped"

        /** The one-phase set a single run makes. */
        fun of(report: LoadReport): LoadRecord =
            LoadRecord(
                id = report.id,
                label = report.label,
                startedAt = report.startedAt,
                finishedAt = report.finishedAt,
                phases = listOf(report),
                seed = report.seed,
            )
    }
}

/** What became of a set, in one word. Named on the badge and in the terminal's last line. */
enum class SetOutcome {
    RUNNING,
    PASSED,
    FAILED,

    /** Ended by hand. Exit 1, because what it proved is a fraction of what was asked. */
    STOPPED,
}

/** The set's verdict: the word, the phase it names, and the counts under it. */
data class SetVerdict(
    val outcome: SetOutcome,
    /** The phase the outcome is about, 1-based. Null when every phase passed. */
    val phase: Int?,
    val passed: Int,
    val failed: Int,
    val stopped: Int = 0,
    val skipped: Int = 0,
) {
    /** "1 passed, 1 failed, 1 skipped". Only the non-zero counts, so a clean set reads "3 passed". */
    fun counts(): String =
        listOfNotNull(
            passed.takeIf { it > 0 }?.let { "$it passed" },
            failed.takeIf { it > 0 }?.let { "$it failed" },
            stopped.takeIf { it > 0 }?.let { "$it stopped" },
            skipped.takeIf { it > 0 }?.let { "$it skipped" },
        ).joinToString(", ")
}
