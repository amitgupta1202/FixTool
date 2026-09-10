package com.knapsack.fixtool.model.load

import com.knapsack.fixtool.service.load.LoadReportCodec

/**
 * **Two runs, subtracted — and refused when subtracting them would produce a confident wrong number.**
 *
 * Load work is comparative by nature: the question is almost never "how fast is this" but "did the fix
 * work". Both runs are already on disk, so this reads two records and takes the difference. Nothing is
 * rerun, which is what lets a run the CLI made overnight compare against one fired in the app an hour ago.
 *
 * **What makes two runs comparable is what a round trip *is* in each**: the request MsgType, the match
 * tags and the reply type. A run matching `131 QuoteReqID` on a `35=S` and one matching `11 ClOrdID` on a
 * `35=8` measured two different exchanges, and a tool that will cheerfully subtract their percentiles is a
 * tool that produces confident wrong numbers. So those three are checked first, shown first, and refused
 * on. Everything else — lanes, shape, rate, settle window, store — may differ and is context.
 */
data class LoadComparison(
    val before: LoadReport,
    val after: LoadReport,
    /** Seconds whose p95 crossed this count as stalls. Fixed, and applied identically to both runs. */
    val thresholdUs: Long,
    /** The three rows that decide, whether they agree or not, always first. */
    val deciding: List<Row>,
    /** Why these two cannot be subtracted. Empty when they can. */
    val blockers: List<String>,
    val deltas: List<Row>,
    /** May differ, and says nothing about whether the comparison is sound. */
    val context: List<Row>,
) {
    val comparable: Boolean get() = blockers.isEmpty()

    /**
     * **The one delta a pair's chip carries**, when there is one worth carrying.
     *
     * A set's Compare rail has one chip per phase pair and no room for a table, so it says the thing
     * somebody opened Compare to find out. Unanswered first, because that is the verdict. Then p99, which
     * is where a regression shows before a mean does. Then answered. Null when nothing moved, which the
     * caller reads as "same" rather than inventing a number for it.
     */
    fun headline(): Row? {
        if (!comparable) return null
        val order = listOf("unanswered", "p99 round trip", "answered")
        return order.firstNotNullOfOrNull { label ->
            deltas.firstOrNull { it.label == label && it.direction != Direction.SAME }
        }
    }

    data class Row(
        val label: String,
        val before: String,
        val after: String,
        val delta: String,
        val direction: Direction,
    )

    /**
     * Which way a row moved, when the tool can say.
     *
     * [UNRANKED] is not a gap: duplicates, peak outstanding and the store choice are reported and not
     * judged, and an arrow on one of them would invent a preference the report does not hold.
     */
    enum class Direction {
        BETTER,
        WORSE,
        SAME,
        UNRANKED,
        DIFFERS,
    }

    companion object {
        /** 100ms. Fixed on purpose: 10× each run's own p50 would ask the two runs different questions. */
        const val DEFAULT_THRESHOLD_US = 100_000L

        @Suppress("LongMethod")
        fun of(before: LoadReport, after: LoadReport, thresholdUs: Long = DEFAULT_THRESHOLD_US): LoadComparison {
            val deciding =
                listOf(
                    same("request type", "35=${before.template.msgType}", "35=${after.template.msgType}"),
                    same("match tags", tags(before.match), tags(after.match)),
                    same("reply type", replyType(before.match), replyType(after.match)),
                )
            // One sentence, not one per differing row: three near-identical paragraphs above a table that
            // already shows the three differences is the same fact said four times.
            val blockers =
                if (deciding.none { it.direction == Direction.DIFFERS }) {
                    emptyList()
                } else {
                    listOf(
                        "These two runs measured different exchanges. One issued ${describe(before)}. " +
                            "The other issued ${describe(after)}. A round trip means a different thing in each, so " +
                            "subtracting their percentiles would produce a number that looks like an answer and is not one.",
                    )
                }
            val deltas =
                if (blockers.isNotEmpty()) {
                    emptyList()
                } else {
                    counts(before, after) + percentiles(before, after) + rate(before, after) + tool(before, after, thresholdUs)
                }
            return LoadComparison(before, after, thresholdUs, deciding, blockers, deltas, context(before, after))
        }

        private fun counts(b: LoadReport, a: LoadReport): List<Row> =
            listOf(
                count("answered", b.replies.matched, a.replies.matched, lowerIsBetter = false),
                count("unanswered", b.replies.unmatched, a.replies.unmatched, lowerIsBetter = true),
                unranked("duplicates", b.replies.duplicates, a.replies.duplicates),
                unranked("late", b.replies.late, a.replies.late),
                unranked("strays", b.replies.strays, a.replies.strays),
            )

        private fun percentiles(b: LoadReport, a: LoadReport): List<Row> {
            val bd = b.roundTrip
            val ad = a.roundTrip
            if (bd == null || ad == null) return emptyList()
            return listOf(
                "min" to (bd.min to ad.min),
                "p50" to (bd.p50 to ad.p50),
                "p95" to (bd.p95 to ad.p95),
                "p99" to (bd.p99 to ad.p99),
                "max" to (bd.max to ad.max),
                "mean" to (bd.mean to ad.mean),
            ).map { (name, pair) -> micros("$name round trip", pair.first, pair.second) }
        }

        private fun rate(b: LoadReport, a: LoadReport): List<Row> {
            val br = b.rate
            val ar = a.rate
            if (br == null && ar == null) return emptyList()
            val verdict =
                Row(
                    "rate verdict",
                    verdictWord(b),
                    verdictWord(a),
                    when {
                        verdictWord(b) == verdictWord(a) -> "same"
                        a.verdict.rate == LoadReport.RateVerdict.HELD -> "held"
                        else -> "changed"
                    },
                    when {
                        b.verdict.rate == a.verdict.rate -> Direction.SAME
                        a.verdict.rate == LoadReport.RateVerdict.HELD -> Direction.BETTER
                        b.verdict.rate == LoadReport.RateVerdict.HELD -> Direction.WORSE
                        else -> Direction.UNRANKED
                    },
                )
            val lag = if (br != null && ar != null) listOf(millis("max lag", br.maxLagMs, ar.maxLagMs)) else emptyList()
            return listOf(verdict) + lag
        }

        private fun tool(b: LoadReport, a: LoadReport, thresholdUs: Long): List<Row> =
            listOf(
                count("discarded by the panes", b.tool.discarded, a.tool.discarded, lowerIsBetter = true),
                count("accepted, never left the socket", b.tool.neverLeftSocket, a.tool.neverLeftSocket, lowerIsBetter = true),
                count("refused", b.tool.issueFailures, a.tool.issueFailures, lowerIsBetter = true),
                unranked("peak outstanding", b.tool.pendingPeak.toLong(), a.tool.pendingPeak.toLong()),
                count(
                    "seconds over ${LoadReportCodec.humanMicros(thresholdUs)} p95",
                    stalls(b, thresholdUs),
                    stalls(a, thresholdUs),
                    lowerIsBetter = true,
                ),
            )

        /** Seconds whose own p95 crossed the threshold. The final report carries these, so this row is free. */
        fun stalls(r: LoadReport, thresholdUs: Long): Long = r.perSecond.count { (it.p95Us ?: 0) > thresholdUs }.toLong()

        private fun context(b: LoadReport, a: LoadReport): List<Row> =
            listOf(
                same("lanes", b.lanes.toString(), a.lanes.toString()),
                same("shape", b.shape.describe(), a.shape.describe()),
                same("settle", humanDuration(b.settleMs), humanDuration(a.settleMs)),
                same("store and log", b.storeAndLog?.describe() ?: "the profile's", a.storeAndLog?.describe() ?: "the profile's"),
                same("profile", b.profileName, a.profileName),
            )

        /** "35=R and matched 131 → 131 on a reply of type S" — the three things that decide, in one clause. */
        private fun describe(r: LoadReport): String =
            "35=${r.template.msgType} and matched ${tags(r.match)} on a reply of type ${r.match.replyType ?: "any"}"

        private fun tags(m: LoadMatch): String = "${m.requestTag} → ${m.replyTag}"

        private fun replyType(m: LoadMatch): String = m.replyType?.let { "35=$it" } ?: "any"

        /**
         * The report's own words, because Compare said "n/a, burst" about a reactive phase and about a
         * run that stopped as readily as about a burst. See [LoadReport.rateWord].
         */
        private fun verdictWord(r: LoadReport): String = r.rateWord

        /** A row that is only ever "same" or "differs" — the shape of every comparability and context row. */
        private fun same(label: String, before: String, after: String): Row =
            Row(label, before, after, if (before == after) "same" else "differs", if (before == after) Direction.SAME else Direction.DIFFERS)

        private fun count(label: String, b: Long, a: Long, lowerIsBetter: Boolean): Row =
            Row(
                label,
                LoadReportCodec.fmt(b),
                LoadReportCodec.fmt(a),
                delta(b, a, lowerIsBetter) { LoadReportCodec.fmt(it) },
                direction(b, a, lowerIsBetter),
            )

        private fun unranked(label: String, b: Long, a: Long): Row =
            Row(
                label,
                LoadReportCodec.fmt(b),
                LoadReportCodec.fmt(a),
                if (b ==
                    a
                ) {
                    "same"
                } else {
                    "changed"
                },
                if (b == a) Direction.SAME else Direction.UNRANKED,
            )

        private fun micros(label: String, b: Long, a: Long): Row =
            Row(
                label,
                LoadReportCodec.humanMicros(b),
                LoadReportCodec.humanMicros(a),
                delta(b, a, lowerIsBetter = true) { LoadReportCodec.humanMicros(it) },
                direction(b, a, lowerIsBetter = true),
            )

        private fun millis(label: String, b: Long, a: Long): Row =
            Row(label, "${b}ms", "${a}ms", delta(b, a, lowerIsBetter = true) { "${it}ms" }, direction(b, a, lowerIsBetter = true))

        private fun direction(b: Long, a: Long, lowerIsBetter: Boolean): Direction =
            when {
                a == b -> Direction.SAME
                (a < b) == lowerIsBetter -> Direction.BETTER
                else -> Direction.WORSE
            }

        /**
         * The change, as a percentage only where the earlier value is non-zero.
         *
         * `77 → 0` reads "cleared" rather than "−100%", and `0 → 77` reads "+77" rather than a division by
         * nothing. A percentage of zero is not a big improvement, it is an arithmetic error with a sign.
         */
        private fun delta(b: Long, a: Long, lowerIsBetter: Boolean, human: (Long) -> String): String {
            val changed = a - b
            val sign = if (changed > 0) "+" else "−"
            val magnitude = kotlin.math.abs(changed)
            val pct = if (b == 0L) 0.0 else kotlin.math.abs(changed.toDouble() / b) * PERCENT
            return when {
                changed == 0L -> "same"
                a == 0L && lowerIsBetter -> "cleared"
                // A change worth naming that rounds to 0% is worse than no figure at all: "+0%" beside
                // 299,923 → 300,000 reads as "nothing happened", and 77 requests did. Below one percent
                // the count is the informative number, so say the count.
                b == 0L || pct < ONE_PERCENT -> "$sign${human(magnitude)}"
                else -> "$sign${"%.0f".format(pct)}%"
            }
        }

        private const val PERCENT = 100.0
        private const val ONE_PERCENT = 1.0
    }
}
