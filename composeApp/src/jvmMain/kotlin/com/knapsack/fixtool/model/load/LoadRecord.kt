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
     */
    val exitCode: Int?
        get() {
            val judged = phases.filterNot { it.status == LoadStatus.SKIPPED || it.status == LoadStatus.PENDING }
            if (judged.isEmpty() || judged.any { it.verdict.exitCode == null }) return null
            return judged.maxOf { it.verdict.exitCode ?: 0 }
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
            val outcome =
                when {
                    stoppedAt != null -> SetOutcome.STOPPED
                    status == LoadStatus.RUNNING -> SetOutcome.RUNNING
                    firstBad != null -> SetOutcome.FAILED
                    else -> SetOutcome.PASSED
                }
            val phase =
                when (outcome) {
                    SetOutcome.STOPPED -> stoppedAt
                    SetOutcome.RUNNING -> currentPhase
                    SetOutcome.FAILED -> firstBad
                    SetOutcome.PASSED -> null
                }
            return SetVerdict(outcome, phase, passed = passed, failed = failed, stopped = stopped, skipped = skipped)
        }

    companion object {
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
