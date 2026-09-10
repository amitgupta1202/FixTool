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
     * a build cannot pass on it. See [verdict], which says the same in words.
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
     *
     * **With several phases running at once, the lowest-numbered of them.** See [livePhases]: it is the
     * phase the set would still be on if nothing reacted to anything, and it is the one that does not
     * change under a reader as the phases behind it start beside it. A row is one phase wide whatever the
     * set is doing, and a row that jumped to phase 3 the instant phase 3 began answering phase 2 would
     * report the deepest phase's counts as the set's.
     */
    val lead: LoadReport
        get() {
            phases.firstOrNull { it.status == LoadStatus.RUNNING }?.let { return it }
            val named = verdict.phase?.let { phases.getOrNull(it - 1) }
            if (named != null && !named.status.isLive && named.status != LoadStatus.SKIPPED) return named
            return phases.lastOrNull { it.status == LoadStatus.DONE || it.status == LoadStatus.STOPPED }
                ?: phases.first()
        }

    /**
     * **Every phase running now, 1-based, in the order they sit in the set.**
     *
     * More than one once a phase can react to another: a three-phase RFQ set has all three going at once
     * for most of its length, because phase 2 answers phase 1's replies while they land and phase 3
     * answers phase 2's. Empty before the first phase starts and after the last has ended.
     *
     * Everything that has to name one phase reads [currentPhase] and everything that can name them all
     * reads this, so the two never disagree about what "running" means.
     */
    val livePhases: List<Int>
        get() = phases.mapIndexedNotNull { index, phase -> (index + 1).takeIf { phase.status == LoadStatus.RUNNING } }

    /**
     * The phase running now, 1-based, or null when none is. The **lowest-numbered** when several are, for
     * the reason [lead] gives: it is the phase the set is on, and it does not move as a chain deepens.
     */
    val currentPhase: Int? get() = livePhases.firstOrNull()

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
     *
     * **A muted phase is looked past three times**: it is counted apart from the phases a failure or a
     * stop skipped, it is not the phase a STOPPED or FAILED set names, and the note that says a stop ended
     * the set is read off the first phase the set meant to run. A muted stub is written at set start, so
     * without all three a set stopped before phase one dialled would name a phase it was never going to
     * run and read its note.
     */
    val verdict: SetVerdict
        get() {
            val passed = phases.count { it.verdict.exitCode == LoadReport.EXIT_PASSED && it.status == LoadStatus.DONE }
            val muted = phases.count { it.isMuted }
            // Counted apart, so a set that passed on the two phases that ran never reads "1 skipped": a
            // skip is what a failure or a stop does to the phases after it, and a mute is neither.
            val skipped = phases.count { it.status == LoadStatus.SKIPPED } - muted
            val failed = phases.count { it.status == LoadStatus.DONE && it.verdict.exitCode != LoadReport.EXIT_PASSED }
            val stopped = phases.count { it.status == LoadStatus.STOPPED }
            val stoppedAt = phases.indexOfFirst { it.status == LoadStatus.STOPPED }.takeIf { it >= 0 }?.plus(1)
            val firstBad =
                phases
                    .indexOfFirst { it.status == LoadStatus.DONE && it.verdict.exitCode != LoadReport.EXIT_PASSED }
                    .takeIf { it >= 0 }
                    ?.plus(1)
            // Past the muted stubs, which are written at set start: the phase a STOPPED or FAILED set names
            // has to be one the set was going to run, or a set stopped before phase one dialled would name
            // whichever phase happened to be parked.
            val firstSkipped =
                phases.indexOfFirst { it.status == LoadStatus.SKIPPED && !it.isMuted }.takeIf { it >= 0 }?.plus(1)
            val nothingJudged = passed == 0 && failed == 0 && stopped == 0
            val outcome =
                when {
                    stoppedAt != null -> SetOutcome.STOPPED
                    status == LoadStatus.RUNNING -> SetOutcome.RUNNING
                    firstBad != null -> SetOutcome.FAILED
                    nothingJudged && phases.firstOrNull { !it.isMuted }?.note == STOPPED_NOTE -> SetOutcome.STOPPED
                    nothingJudged -> SetOutcome.FAILED
                    else -> SetOutcome.PASSED
                }
            val phase =
                when (outcome) {
                    SetOutcome.STOPPED -> stoppedAt ?: firstSkipped
                    // The lowest-numbered of the phases running, which is the phase the set is on. The
                    // verdict names one phase by construction, and every word it has to put beside a
                    // number is a word about a phase that is still going.
                    SetOutcome.RUNNING -> currentPhase
                    SetOutcome.FAILED -> firstBad ?: firstSkipped
                    SetOutcome.PASSED -> null
                }
            return SetVerdict(
                outcome,
                phase,
                passed = passed,
                failed = failed,
                stopped = stopped,
                skipped = skipped,
                muted = muted,
            )
        }

    companion object {
        /**
         * The note a phase carries when the stop, and not a failure, is why it never ran.
         *
         * Here rather than in the runner because the verdict reads it: a set skipped whole has no stopped
         * phase to name, so the note is the only thing that says whether somebody ended it.
         */
        const val STOPPED_NOTE = "the set was stopped"

        /**
         * The note a phase carries when it was muted in the set, and so was never going to run.
         *
         * Here for the same reason [STOPPED_NOTE] is: the verdict reads it. A muted phase is SKIPPED, and
         * the note is the only thing that tells it from the phases a failure or a stop took out, which the
         * counts, the first-skipped phase and every door's one word all depend on. See [LoadReport.isMuted].
         */
        const val MUTED_NOTE = "muted in the set"

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
    /** The phases a failure or a stop took out. A phase muted in the file is [muted], not one of these. */
    val skipped: Int = 0,
    /** The phases parked in the set. Last in [counts], so a clean set reads "2 passed, 1 muted". */
    val muted: Int = 0,
) {
    /** "1 passed, 1 failed, 1 skipped". Only the non-zero counts, so a clean set reads "3 passed". */
    fun counts(): String =
        listOfNotNull(
            passed.takeIf { it > 0 }?.let { "$it passed" },
            failed.takeIf { it > 0 }?.let { "$it failed" },
            stopped.takeIf { it > 0 }?.let { "$it stopped" },
            skipped.takeIf { it > 0 }?.let { "$it skipped" },
            muted.takeIf { it > 0 }?.let { "$it muted" },
        ).joinToString(", ")
}
