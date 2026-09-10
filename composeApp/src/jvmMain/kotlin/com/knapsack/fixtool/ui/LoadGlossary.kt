package com.knapsack.fixtool.ui

/**
 * **What every figure in a load report means, where the figure is.**
 *
 * The document had no tooltips at all: a figure was a label and a number, and `peak outstanding` or
 * `strays` was defined nowhere in the product. A reader looking at a finished report had to go and find
 * the guide, or ask somebody — which is a poor trade for a number whose whole job is to be read once, in
 * passing, by somebody deciding whether a run proved anything.
 *
 * **Keyed by test tag, not by label**, because a label moves and a tag does not: the same figure reads
 * "unanswered" when a run is over and "outstanding" while it is going, and "late" becomes "late to phase
 * 2" inside a set. The tag is the figure's identity and is already threaded through every one of these
 * composables for the tests.
 *
 * **Definitions go on labels and column headers, never on cells.** That is the whole of the performance
 * story: a lane table is one header row and up to fifty rows of six, so putting a tooltip on the cells
 * would be three hundred hover targets that grow with the lane count, while putting them on the headers
 * is six that do not. The count of tooltip areas in a report is fixed at about twenty whatever the run
 * did — see `LoadRunDocumentTest`, which renders the same report at two lanes and at fifty and holds
 * the two counts of explained nodes equal.
 *
 * Each definition is the short form of what the user guide's *What the report says* chapter states at
 * length; `HelpDocTest` holds the two together so a figure cannot be explained here and nowhere else.
 */
object LoadGlossary {
    /** What a figure is called on screen, and what it means. */
    data class Entry(
        val term: String,
        val meaning: String,
    )

    /**
     * Tag to entry. A figure with no entry simply gets no tooltip, which is the right default for one
     * whose label already says everything it means — "lane" is a lane.
     */
    private val byTag =
        mapOf(
            "load-matched" to
                Entry(
                    "answered",
                    "Requests whose first reply arrived before the settle window closed. " +
                        "That first reply is also what the round trip is measured to.",
                ),
            "load-unmatched" to
                Entry(
                    "unanswered",
                    "Requests that left the socket and never drew a reply carrying their id. " +
                        "This is the bar: anything above zero fails the run.",
                ),
            "load-issued" to
                Entry(
                    "issued",
                    "Three numbers. Requested is what the plan asked for, engine is what QuickFIX/J accepted, " +
                        "socket is what the SEND stamps counted. Completeness is judged over the last of the three.",
                ),
            "load-duplicates" to
                Entry(
                    "duplicates",
                    "A reply carrying an id that had already been matched. Reported and never judged: one order " +
                        "legitimately draws several ExecutionReports, and a reply seen on a listening session looks " +
                        "the same as one the venue sent twice.",
                ),
            "load-late" to
                Entry(
                    "late",
                    "A reply that did arrive, after the settle window had closed. It answers nothing — its request " +
                        "stays unanswered.",
                ),
            "load-strays" to
                Entry(
                    "strays",
                    "A reply of the right shape carrying an id nothing in this run issued: another client's traffic, " +
                        "seen because a listening session sees everything on it.",
                ),
            "load-peak" to
                Entry(
                    "peak outstanding",
                    "The most requests waiting for an answer at any one moment — what the venue was actually asked " +
                        "to hold at once, which a count and a rate together cannot tell you.",
                ),
            "load-achieved" to
                Entry(
                    "achieved",
                    "Messages a second over the issue span, first send to last send. What the run did, against what " +
                        "a rate asked for.",
                ),
            "load-captured" to
                Entry(
                    "captured",
                    "How many replies carried each name this phase keeps. Fewer than were matched means the venue " +
                        "answered without the tag, which is what a later phase is about to trip over.",
                ),
            "load-tool-discarded" to
                Entry(
                    "discarded",
                    "Messages the panes' display queues threw away during the run. Above zero fails the run: raise " +
                        "the session buffer, or run with the panes minimized.",
                ),
            "load-tool-never-left" to
                Entry(
                    "never left socket",
                    "Messages the engine accepted that never produced a SEND stamp. Above zero fails the run.",
                ),
            "load-tool-refused" to
                Entry(
                    "issue failures",
                    "Messages the engine refused outright. Above zero fails the run.",
                ),
            "load-tool-rate" to
                Entry(
                    "rate",
                    "A rate run's schedule against what happened. A shortfall fails the run only with " +
                        "--strict-rate, and a reactive phase's ceiling is never a failure: under it is the " +
                        "trigger having less to give.",
                ),
        )

    /** Definitions for the per-lane table's column headers, which have no tag of their own. */
    private val byLaneHeader =
        mapOf(
            "answered" to "Requests this lane issued that were answered.",
            "unanswered" to "Requests this lane issued that were never answered. Anything above zero fails the run.",
            "duplicates" to "Replies to this lane carrying an id it had already been answered for.",
            "p50" to "This lane's own median round trip, socket send stamp to socket receive stamp.",
            "p95" to
                "This lane's own 95th-percentile round trip. The table is sorted by it, because the question it " +
                "answers is whether one lane is much worse than the rest.",
        )

    /** The definition for a figure, or null when its label already says everything it means. */
    fun of(tag: String): String? = byTag[tag]?.meaning

    fun ofLaneHeader(header: String): String? = byLaneHeader[header]

    /**
     * **What every figure is called**, for the test that holds these definitions and the user guide
     * together: a figure explained on hover and nowhere else leaves the reader who is looking at a saved
     * record with nothing, and one explained in the guide and not here sends them off to find it.
     */
    val terms: Set<String> get() = byTag.values.map { it.term }.toSet()
}
