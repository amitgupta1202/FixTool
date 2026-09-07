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
) {
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
                phases.any { it.status == LoadStatus.RUNNING } -> LoadStatus.RUNNING
                phases.any { it.status == LoadStatus.STOPPED } -> LoadStatus.STOPPED
                else -> LoadStatus.DONE
            }

    /** The worst exit code any phase reached, or null while one is still running. */
    val exitCode: Int?
        get() = if (phases.any { it.verdict.exitCode == null }) null else phases.maxOf { it.verdict.exitCode ?: 0 }

    companion object {
        /** The one-phase set a single run makes. */
        fun of(report: LoadReport): LoadRecord =
            LoadRecord(
                id = report.id,
                label = report.label,
                startedAt = report.startedAt,
                finishedAt = report.finishedAt,
                phases = listOf(report),
            )
    }
}
