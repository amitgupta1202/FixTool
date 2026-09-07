package com.knapsack.fixtool.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.load.LoadReportCodec
import com.knapsack.fixtool.service.load.Pacer
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * **The two pictures a load run owes, drawn on a Compose [Canvas] and adding no dependency.**
 *
 * Which one you get follows [LoadReport.Issue.spanMs] and not the shape, because shape is the wrong
 * discriminator: a ×300,000 burst on five lanes issues for about a minute and has a real per-second story,
 * while a 4,000 burst leaves in 813ms and would draw three bars that say nothing. So the per-second panel
 * appears when the issue span is more than a few seconds, and the outstanding curve appears always.
 *
 * Both are drawn from the incremental measurements, so both are correct while a run is still going.
 */
object LoadCharts {
    /** Below this the per-second panel would be a handful of bars, which is a picture of nothing. */
    val MIN_SPAN_MS = 4_000L

    fun hasPerSecond(r: LoadReport): Boolean = (r.issue.spanMs ?: 0) >= MIN_SPAN_MS && r.perSecond.size > 1
}

/**
 * **Throughput and latency, second by second.**
 *
 * One column per second, the issued schedule as a line over them, and p95 round trip below on its own
 * scale sharing the x axis. The schedule lands exactly on the bar tops for almost every second, which is
 * the point of drawing it, so it carries a dark halo to stay readable over them.
 *
 * A second is red when it falls under the pacer's own floor — the requested rate less [Pacer.TOLERANCE] —
 * so the picture and the rate verdict cannot disagree about what "behind" means. Each red second gets a
 * guide through both panels, because the eye should connect a throughput dip to its latency spike without
 * being told to.
 *
 * Past [MAX_COLUMNS] seconds the columns are ranges, min to max, never a single "worst second": min-of-N
 * on a run with ordinary jitter parks every column under the schedule line and makes a healthy run read
 * as a sustained shortfall.
 */
@Composable
@Suppress("LongMethod")
fun PerSecondPanel(report: LoadReport, modifier: Modifier = Modifier) {
    val columns = columnsOf(report.perSecond, MAX_COLUMNS)
    val schedule = (report.shape as? LoadShape.Rate)?.perSecond
    val floor = schedule?.let { floor(it * (1 - Pacer.TOLERANCE)).toInt() }
    val topMax = maxOf(columns.maxOf { maxOf(it.answeredHigh, it.issuedHigh) }, schedule ?: 0).coerceAtLeast(1)
    val p95Max = columns.mapNotNull { it.p95High }.maxOrNull()?.coerceAtLeast(1L) ?: 1L

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(BARS_H + GAP + P95_H).testTag("load-chart-seconds")) {
            val plotLeft = AXIS_W.toPx()
            val plotWidth = (size.width - plotLeft - RIGHT_PAD.toPx()).coerceAtLeast(1f)
            val step = plotWidth / columns.size
            val barW = (step * BAR_FILL).coerceIn(1f, MAX_BAR_W)
            val barsBase = BARS_H.toPx()
            val p95Top = barsBase + GAP.toPx()
            val p95Base = p95Top + P95_H.toPx()

            fun x(i: Int) = plotLeft + i * step + step / 2

            fun yTop(v: Number) = barsBase - (v.toFloat() / topMax) * BARS_H.toPx()

            fun yP95(v: Long) = p95Base - (v.toFloat() / p95Max) * P95_H.toPx()

            // Grid: three lines in the throughput panel, three in the latency panel, each on a round value.
            listOf(0f, HALF, 1f).forEach { f ->
                drawLine(
                    rule,
                    Offset(plotLeft, barsBase - f * BARS_H.toPx()),
                    Offset(size.width, barsBase - f * BARS_H.toPx()),
                    if (f ==
                        0f
                    ) {
                        1f
                    } else {
                        GRID_W
                    },
                )
                drawLine(
                    rule,
                    Offset(plotLeft, p95Base - f * P95_H.toPx()),
                    Offset(size.width, p95Base - f * P95_H.toPx()),
                    if (f ==
                        0f
                    ) {
                        1f
                    } else {
                        GRID_W
                    },
                )
            }

            // The stall guides go under the bars, so they show in the gap and across the latency panel.
            columns.forEachIndexed { i, c ->
                if (floor != null && c.answeredLow < floor) {
                    drawLine(guide, Offset(x(i), 0f), Offset(x(i), p95Base), GUIDE_W)
                }
            }

            columns.forEachIndexed { i, c ->
                val behind = floor != null && c.answeredLow < floor
                val top = yTop(c.answeredHigh)
                val bottom = if (c.answeredLow == c.answeredHigh) barsBase else yTop(c.answeredLow)
                drawLine(
                    if (behind) bad else bar,
                    Offset(x(i), bottom.coerceAtMost(barsBase)),
                    Offset(x(i), top),
                    barW,
                )
            }

            if (schedule != null) {
                val y = yTop(schedule)
                drawLine(halo, Offset(plotLeft, y), Offset(size.width, y), HALO_W)
                drawLine(
                    scheduleInk,
                    Offset(plotLeft, y),
                    Offset(size.width, y),
                    SCHEDULE_W,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF)),
                )
            }

            // p95: a polyline, and only where a second actually had a matched reply to measure.
            var previous: Offset? = null
            columns.forEachIndexed { i, c ->
                val v =
                    c.p95High ?: run {
                        previous = null
                        return@forEachIndexed
                    }
                val point = Offset(x(i), yP95(v))
                previous?.let { drawLine(warn, it, point, LINE_W, cap = StrokeCap.Round) }
                previous = point
            }
        }
        Axis(report, columns.size, topMax, p95Max, floor)
    }
}

/** The two panels' scales and the x axis, said in words rather than drawn as tick labels on a canvas. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Axis(report: LoadReport, columns: Int, topMax: Int, p95Max: Long, floor: Int?) {
    val span = report.issue.spanMs ?: 0
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().testTag("load-chart-legend")) {
        Key(bar, "answered per second, 0 to ${LoadReportCodec.fmt(topMax.toLong())}")
        floor?.let { Key(bad, "a second under $it/s, the pacer's floor") }
        report.shape.let { it as? LoadShape.Rate }?.let { Key(scheduleInk, "${it.perSecond}/s issued, the schedule") }
        Key(warn, "p95 round trip, 0 to ${LoadReportCodec.humanMicros(p95Max)}")
        Text(
            "$columns column${if (columns == 1) "" else "s"} over ${humanDuration(span)}" +
                if (report.perSecond.size > columns) " · ${report.perSecond.size} seconds, drawn as min-to-max ranges" else "",
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
        )
    }
}

/**
 * **The share of the burst still outstanding, against round trip, on log-log axes.**
 *
 * Drawn the other way up on purpose. A plain cumulative curve puts four unanswered out of four thousand at
 * 0.1% of the plot height, which is a tenth of a pixel. Complementary and logarithmic, the same four are a
 * plateau the curve never falls off — which is the fact the verdict states.
 *
 * It self-checks: p95 crosses the curve at the 5% gridline and p99 at the 1% gridline, because that is
 * what those words mean. Source is the 30-bucket histogram, so it draws live too.
 */
@Composable
@Suppress("LongMethod")
fun OutstandingCurve(report: LoadReport, modifier: Modifier = Modifier) {
    val issued = report.issue.leftSocket.coerceAtLeast(1)
    val curve = outstandingCurve(report.roundTripHistogram, issued)
    val floorShare = (report.replies.unmatched.toDouble() / issued).coerceAtLeast(0.0)
    val marks =
        report.roundTrip
            ?.let {
                listOf("p50" to it.p50, "p95" to it.p95, "p99" to it.p99).filter { (_, us) -> us > 0 }
            }.orEmpty()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(CURVE_H).testTag("load-chart-outstanding")) {
            val plotLeft = AXIS_W.toPx()
            val plotWidth = (size.width - plotLeft - RIGHT_PAD.toPx()).coerceAtLeast(1f)
            val plotHeight = size.height

            fun x(micros: Long): Float {
                val t = (log10(micros.coerceAtLeast(X_LO).toDouble()) - log10(X_LO.toDouble())) / (log10(X_HI.toDouble()) - log10(X_LO.toDouble()))
                return plotLeft + t.coerceIn(0.0, 1.0).toFloat() * plotWidth
            }

            fun y(share: Double): Float {
                val t = (log10(share.coerceAtLeast(Y_LO)) - log10(Y_LO)) / (0.0 - log10(Y_LO))
                return plotHeight - t.coerceIn(0.0, 1.0).toFloat() * plotHeight
            }

            // Decades, labelled by the legend below as the percentages they are.
            var decade = 1.0
            while (decade >= Y_LO) {
                drawLine(rule, Offset(plotLeft, y(decade)), Offset(size.width, y(decade)), GRID_W)
                decade /= DECADE
            }
            var t = X_LO
            while (t <= X_HI) {
                drawLine(rule, Offset(x(t), 0f), Offset(x(t), plotHeight), GRID_W)
                t *= DECADE.toLong()
            }

            marks.forEach { (_, micros) ->
                drawLine(warn, Offset(x(micros), 0f), Offset(x(micros), plotHeight), GUIDE_W)
            }

            var previous: Offset? = null
            curve.forEach { (micros, share) ->
                val point = Offset(x(micros), y(share))
                previous?.let { drawLine(bar, it, point, CURVE_W, cap = StrokeCap.Round) }
                previous = point
            }
            // The plateau, drawn as what it is: a floor nothing gets under.
            if (floorShare > 0) {
                val fy = y(floorShare)
                previous?.let { drawLine(bad, Offset(it.x, fy), Offset(size.width, fy), CURVE_W) }
                    ?: drawLine(bad, Offset(plotLeft, fy), Offset(size.width, fy), CURVE_W)
            }
        }
        CurveLegend(marks, floorShare, report.replies.unmatched)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CurveLegend(marks: List<Pair<String, Long>>, floorShare: Double, unanswered: Long) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().testTag("load-curve-legend")) {
        Key(
            bar,
            "share still outstanding · 100% down to 0.01%, ${LoadReportCodec.humanMicros(X_LO)} to ${LoadReportCodec.humanMicros(X_HI)}, both log",
        )
        marks.forEach { (name, us) -> Key(warn, "$name ${LoadReportCodec.humanMicros(us)}") }
        if (floorShare > 0) {
            Key(bad, "${percent(floorShare)} · ${LoadReportCodec.fmt(unanswered)} never came back")
        }
    }
}

@Composable
private fun Key(tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(KEY_W, KEY_H)) { drawLine(tint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), KEY_STROKE) }
        Text(text, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
    }
}

// ---------------------------------------------------------------------------------------------------
// The arithmetic, kept out of the drawing so a test can read it
// ---------------------------------------------------------------------------------------------------

/** One drawn column: one second, or a min-to-max range over several once a run is longer than the plot. */
data class SecondColumn(
    val answeredLow: Int,
    val answeredHigh: Int,
    val issuedHigh: Int,
    val p95High: Long?,
)

/**
 * The seconds as columns, at most [max] of them.
 *
 * Past that, a column is the range its seconds covered, low and high, never one representative value. An
 * earlier draft kept the "worst second", which is both impossible to reconcile with a catch-up second
 * above the schedule and wrong on real data: min-of-N over ordinary jitter parks every column under the
 * line and makes a healthy run read as a sustained shortfall.
 */
fun columnsOf(seconds: List<LoadReport.Second>, max: Int): List<SecondColumn> {
    if (seconds.isEmpty()) return listOf(SecondColumn(0, 0, 0, null))
    if (seconds.size <= max) {
        return seconds.map { SecondColumn(it.matched, it.matched, it.issued, it.p95Us) }
    }
    val per = ceilDiv(seconds.size, max)
    return seconds.chunked(per).map { chunk ->
        SecondColumn(
            answeredLow = chunk.minOf { it.matched },
            answeredHigh = chunk.maxOf { it.matched },
            issuedHigh = chunk.maxOf { it.issued },
            p95High = chunk.mapNotNull { it.p95Us }.maxOrNull(),
        )
    }
}

/**
 * The complementary cumulative curve: at each bucket's upper edge, the share of everything issued that has
 * not been answered yet.
 *
 * The denominator is what left the socket, not what was matched, which is what makes the curve flatten at
 * the unanswered share instead of reaching zero. A request that never came back is outstanding forever.
 */
fun outstandingCurve(histogram: List<Int>, issued: Long): List<Pair<Long, Double>> {
    if (issued <= 0) return emptyList()
    var answered = 0L
    val points = ArrayList<Pair<Long, Double>>(histogram.size + 1)
    points += RoundTripHistogram.lowerMicros(0) to 1.0
    histogram.forEachIndexed { i, n ->
        answered += n
        if (n > 0) {
            val edge = if (i >= RoundTripHistogram.BUCKETS - 1) X_HI else RoundTripHistogram.upperMicros(i)
            points += edge to ((issued - answered).toDouble() / issued).coerceAtLeast(0.0)
        }
    }
    return points
}

/** "0.1%", "12%", "0.03%" — enough figures to tell a plateau from the axis it sits near. */
fun percent(share: Double): String {
    val pct = share * PERCENT
    return when {
        pct >= TEN -> "${pct.roundToInt()}%"
        pct >= ONE -> "%.1f%%".format(pct)
        else -> "%.2f%%".format(pct)
    }
}

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

private fun DrawScope.drawLine(color: Color, from: Offset, to: Offset, width: Float, cap: StrokeCap = Stroke.DefaultCap, pathEffect: PathEffect? = null) =
    drawLine(color = color, start = from, end = to, strokeWidth = width, cap = cap, pathEffect = pathEffect)

private val bar = Color(0xFF4A7BA7)
private val bad = AppTheme.Colors.error
private val warn = AppTheme.Colors.warning
private val rule = AppTheme.Colors.border
private val guide = AppTheme.Colors.error.copy(alpha = 0.4f)
private val halo = Color(0xFF141414)
private val scheduleInk = Color(0xFFE8E8E8)

private val BARS_H = 96.dp
private val P95_H = 46.dp
private val GAP = 22.dp
private val CURVE_H = 132.dp
private val AXIS_W = 2.dp
private val RIGHT_PAD = 2.dp
private val KEY_W = 12.dp
private val KEY_H = 8.dp

private const val MAX_COLUMNS = 600
private const val BAR_FILL = 0.86f
private const val MAX_BAR_W = 7f
private const val GRID_W = 0.5f
private const val GUIDE_W = 1f
private const val LINE_W = 1.4f
private const val CURVE_W = 1.8f
private const val SCHEDULE_W = 1.4f
private const val HALO_W = 4f
private const val KEY_STROKE = 2f
private const val DASH_ON = 5f
private const val DASH_OFF = 3f
private const val HALF = 0.5f
private const val DECADE = 10.0
private const val PERCENT = 100.0
private const val TEN = 10.0
private const val ONE = 1.0

/** 0.5ms to 100s: the round-trip axis, wide enough for a venue's tail without wasting a decade. */
private const val X_LO = 500L
private const val X_HI = 100_000_000L

/** 0.01%: one unanswered in ten thousand is still a plateau you can see. */
private const val Y_LO = 0.0001
