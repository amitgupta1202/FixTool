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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.load.LoadReportCodec
import com.knapsack.fixtool.service.load.Pacer
import kotlin.math.ceil
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
 * **A reactive phase's cap is drawn and is not a schedule.** It gets its own line, its own key and no
 * floor at all. Fed in as a schedule it would have been a line every starved second falls under, and every
 * one of those seconds would have gone red on a phase that was waiting on its trigger and failing at
 * nothing. A ceiling is a line to sit under, so nothing under it is behind anything.
 *
 * Past [MAX_COLUMNS] seconds the columns are ranges, min to max, never a single "worst second": min-of-N
 * on a run with ordinary jitter parks every column under the schedule line and makes a healthy run read
 * as a sustained shortfall.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun PerSecondPanel(report: LoadReport, modifier: Modifier = Modifier) {
    val columns = columnsOf(report.perSecond, MAX_COLUMNS)
    val schedule = (report.shape as? LoadShape.Rate)?.perSecond
    val ceiling = (report.shape as? LoadShape.Triggered)?.cap
    // The schedule's and never the ceiling's: this is what paints a second red, and a second under a
    // ceiling is a second its trigger had less for it than the cap allowed.
    val floor = schedule?.let { floor(it * (1 - Pacer.TOLERANCE)).toInt() }
    val tallest = columns.maxOf { maxOf(it.answeredHigh, it.issuedHigh) }
    // **Headroom.** The top of the plot used to be whichever was taller of the tallest bar and the
    // schedule, so a run that held its rate welded the schedule line to the frame and drew it as the
    // chart's own edge. A tenth above the tallest thing gives the line somewhere to be.
    val topMax = ceil(maxOf(tallest, schedule ?: 0, ceiling ?: 0).coerceAtLeast(1) * HEADROOM).toInt()
    val p95Max = columns.mapNotNull { it.p95High }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val secondsPerColumn = ceilDiv(report.perSecond.size.coerceAtLeast(1), columns.size)
    val measurer = rememberTextMeasurer()
    val axis = AppTheme.Type.meta.copy(color = AppTheme.Colors.textDisabled, fontSize = AXIS_FONT)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(BARS_H + GAP + P95_H + LABEL_H)
                    .testTag("load-chart-seconds"),
        ) {
            val plotLeft = GUTTER_W.toPx()
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

            // Both scales named at their own gridlines: two stacked panels on different scales, neither
            // of which said what any height meant.
            listOf(0f, HALF, 1f).forEach { f ->
                val rate = (topMax * f).roundToInt()
                val rateLabel = measurer.measure(AnnotatedString(rate.toString()), axis)
                drawText(
                    rateLabel,
                    topLeft =
                        Offset(
                            plotLeft - TICK_GAP.toPx() - rateLabel.size.width,
                            barsBase - f * BARS_H.toPx() - rateLabel.size.height / 2f,
                        ),
                )
                val us = if (f == 0f) "0" else LoadReportCodec.humanMicros((p95Max * f).toLong())
                val usLabel = measurer.measure(AnnotatedString(us), axis)
                drawText(
                    usLabel,
                    topLeft =
                        Offset(
                            plotLeft - TICK_GAP.toPx() - usLabel.size.width,
                            p95Base - f * P95_H.toPx() - usLabel.size.height / 2f,
                        ),
                )
            }

            // **The time axis.** A red patch says nothing until the reader can say when it happened, and
            // "41 columns over 41s" in the key gives the span and never a position. Every fifth column or
            // so, labelled with the second that column starts at — which is also the only thing that shows
            // a column has stopped being one second and become a range of them.
            val every = niceStep(ceilDiv(columns.size, TIME_TICKS).coerceAtLeast(1))
            var lastRight = 0f
            columns.indices.step(every).forEach { i ->
                val at = humanDuration(i * secondsPerColumn * MILLIS_PER_SECOND)
                val label = measurer.measure(AnnotatedString(at), axis)
                val left = x(i) - label.size.width / 2f
                if (left > lastRight && left + label.size.width < size.width) {
                    drawText(label, topLeft = Offset(left, p95Base + TICK_GAP.toPx()))
                    lastRight = left + label.size.width + TICK_GAP.toPx()
                }
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
                // Named where it is drawn. The key says the same thing, and the key is not where the
                // reader is looking when they wonder what the dashed line is.
                val label = measurer.measure(AnnotatedString("$schedule/s schedule"), axis.copy(color = scheduleInk))
                drawText(
                    label,
                    topLeft = Offset(size.width - label.size.width, y - label.size.height - TICK_GAP.toPx() / 2),
                )
            }
            // The floor, which was only ever a colour on a bar and a sentence in the key. Drawn faintly,
            // because it is the line the bars are allowed to sit on rather than one they aim for — and
            // left unlabelled on purpose: it sits within the pacer's tolerance of the schedule, so any
            // two labels up there land on top of each other. The key carries its number instead.
            if (floor != null && schedule != null) {
                val y = yTop(floor)
                drawLine(
                    guide,
                    Offset(plotLeft, y),
                    Offset(size.width, y),
                    GRID_W,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(FLOOR_DASH, FLOOR_DASH)),
                )
            }

            // The ceiling: thinner than a schedule, in its own ink and on a longer dash, because it is a
            // limit and not a target and the bars are meant to sit anywhere beneath it.
            if (ceiling != null) {
                val y = yTop(ceiling)
                drawLine(halo, Offset(plotLeft, y), Offset(size.width, y), HALO_W)
                drawLine(
                    ceilingInk,
                    Offset(plotLeft, y),
                    Offset(size.width, y),
                    CEILING_W,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(CEILING_DASH_ON, CEILING_DASH_OFF)),
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
        Axis(report, columns.size, topMax, p95Max, floor, ceiling)
    }
}

/**
 * **What the plot cannot say about itself.**
 *
 * The scales are tick labels on the canvas now, so this no longer repeats them. What is left is the
 * things a mark cannot carry: what red means, what the two dashed lines are for a phase that has them,
 * and — the one that matters — whether a column is still one second or has become a range of them.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList")
private fun Axis(report: LoadReport, columns: Int, topMax: Int, p95Max: Long, floor: Int?, ceiling: Int?) {
    val span = report.issue.spanMs ?: 0
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().testTag("load-chart-legend")) {
        Key(bar, "answered per second")
        floor?.let { Key(bad, "a second under $it/s, the pacer's floor") }
        // Its own words, because the schedule's would be a claim: nothing asked this phase for a rate, and
        // a second under the line is a second its trigger had less for it than the cap allowed.
        ceiling?.let { Key(ceilingInk, "$it/s, the ceiling nothing was released above") }
        Key(warn, "p95 round trip")
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
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun OutstandingCurve(report: LoadReport, modifier: Modifier = Modifier) {
    val issued = report.issue.leftSocket.coerceAtLeast(1)
    val curve = outstandingCurve(report.roundTripHistogram, issued)
    val floorShare = (report.replies.unmatched.toDouble() / issued).coerceAtLeast(0.0)
    val marks =
        report.roundTrip
            ?.let {
                listOf("p50" to it.p50, "p95" to it.p95, "p99" to it.p99).filter { (_, us) -> us > 0 }
            }.orEmpty()

    val measurer = rememberTextMeasurer()
    val ink = AppTheme.Colors.textDisabled
    val axis = AppTheme.Type.meta.copy(color = ink, fontSize = AXIS_FONT)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(CURVE_H + MARK_H + LABEL_H)
                    .testTag("load-chart-outstanding"),
        ) {
            val plotLeft = GUTTER_W.toPx()
            val plotWidth = (size.width - plotLeft - RIGHT_PAD.toPx()).coerceAtLeast(1f)
            val plotTop = MARK_H.toPx()
            val plotHeight = (size.height - plotTop - LABEL_H.toPx()).coerceAtLeast(1f)
            val plotBottom = plotTop + plotHeight
            val roomForWords = plotWidth >= WORDS_NEED.toPx()

            fun x(micros: Long): Float {
                val t = (log10(micros.coerceAtLeast(X_LO).toDouble()) - log10(X_LO.toDouble())) / (log10(X_HI.toDouble()) - log10(X_LO.toDouble()))
                return plotLeft + t.coerceIn(0.0, 1.0).toFloat() * plotWidth
            }

            fun y(share: Double): Float {
                val t = (log10(share.coerceAtLeast(Y_LO)) - log10(Y_LO)) / (0.0 - log10(Y_LO))
                return plotBottom - t.coerceIn(0.0, 1.0).toFloat() * plotHeight
            }

            // Every decade named, because a log scale cannot be read by eye between its lines: the
            // midpoint of a decade is 3.16, not 5, so an unlabelled log grid says less than no grid.
            var decade = 1.0
            while (decade >= Y_LO) {
                drawLine(rule, Offset(plotLeft, y(decade)), Offset(size.width, y(decade)), GRID_W)
                val label = measurer.measure(AnnotatedString(decadePercent(decade)), axis)
                drawText(
                    label,
                    topLeft = Offset(plotLeft - TICK_GAP.toPx() - label.size.width, y(decade) - label.size.height / 2f),
                )
                decade /= DECADE
            }
            // **On round numbers, not on the floor times ten.** The verticals used to start at X_LO and
            // multiply, which put them on 500µs, 5ms, 50ms — values no reader thinks in, and the reason
            // labelling them had to wait for this.
            var t = X_LO.roundedUpToDecade()
            var lastLabelRight = 0f
            while (t <= X_HI) {
                drawLine(rule, Offset(x(t), plotTop), Offset(x(t), plotBottom), GRID_W)
                val label = measurer.measure(AnnotatedString(decadeLabel(t)), axis)
                val left = x(t) - label.size.width / 2f
                if (left > lastLabelRight && left + label.size.width < size.width) {
                    drawText(label, topLeft = Offset(left, plotBottom + TICK_GAP.toPx()))
                    lastLabelRight = left + label.size.width + TICK_GAP.toPx()
                }
                t *= DECADE.toLong()
            }

            // The guides carry the name and the legend carries the value: three identical lines told
            // apart by matching left-to-right order against a key is not telling them apart.
            var lastMarkRight = 0f
            marks.forEach { (name, micros) ->
                drawLine(warn, Offset(x(micros), plotTop), Offset(x(micros), plotBottom), GUIDE_W)
                val label = measurer.measure(AnnotatedString(name), axis.copy(color = warn))
                val left = x(micros) - label.size.width / 2f
                if (left > lastMarkRight && left + label.size.width < size.width) {
                    drawText(label, topLeft = Offset(left, plotTop - label.size.height - TICK_GAP.toPx() / 2))
                    lastMarkRight = left + label.size.width + TICK_GAP.toPx()
                }
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

            // What the axes are, said once each. Dropped on a narrow pane, where the plot needs the room
            // more than the reader needs telling twice — the legend underneath says it either way.
            if (roomForWords) {
                val across = measurer.measure(AnnotatedString(X_AXIS_WORDS), axis)
                drawText(
                    across,
                    topLeft = Offset(size.width - across.size.width, plotBottom + LABEL_H.toPx() / 2 + TICK_GAP.toPx()),
                )
            }
        }
        CurveLegend(marks, floorShare, report.replies.unmatched)
    }
}

/**
 * "100%", "1%", "0.01%" — a decade tick reads as the round number it is.
 *
 * [percent] is for a share that lands anywhere and keeps a decimal so 0.1% and 0.14% are different
 * numbers; these are exact powers of ten, and "1.0%" beside "100%" reads as a measurement rather than
 * as the gridline it is.
 */
internal fun decadePercent(share: Double): String {
    val pct = share * PERCENT
    // Formatted and trimmed, never `BigDecimal(pct)`: that constructor takes the double's exact binary
    // value, and a tenth of a percent came out as 0.1000000000000000055511151231257827021181583404541015625.
    return if (pct >= 1.0) "${pct.roundToInt()}%" else "%.4f".format(pct).trimEnd('0').trimEnd('.') + "%"
}

/** "1ms", "100ms", "1s" — a tick reads as the number it is, where `humanMicros` would say "1.00s". */
internal fun decadeLabel(micros: Long): String =
    when {
        micros < 1_000L -> "${micros}µs"
        micros < 1_000_000L -> "${micros / 1_000L}ms"
        else -> "${micros / 1_000_000L}s"
    }

/** The first true decade at or above the plot's floor: 500µs starts the scale, 1ms starts the grid. */
private fun Long.roundedUpToDecade(): Long {
    var d = 1L
    while (d < this) d *= DECADE.toLong()
    return d
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CurveLegend(marks: List<Pair<String, Long>>, floorShare: Double, unanswered: Long) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().testTag("load-curve-legend")) {
        // The corners used to be spelled out here because nothing on the plot said them. Both axes are
        // labelled now, so what is left to say is that they are logarithmic — which no tick can show.
        Key(bar, "share still outstanding · both axes log")
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

/** Cooler and dimmer than the schedule's, because a ceiling is a limit and the bars belong under it. */
private val ceilingInk = Color(0xFF9AA7B0)

/** The gutter the y tick labels live in, on both charts, so the two plots start at the same x. */
private val GUTTER_W = 40.dp

/** Under the plot, for the x tick labels and the one line of words under them. */
private val LABEL_H = 26.dp

/** Above the plot, for the names on the p50/p95/p99 guides. */
private val MARK_H = 12.dp

private val TICK_GAP = 4.dp
private val AXIS_FONT = 9.sp

/** Below this the words under the x axis are dropped: the plot needs the room more than the reader. */
private val WORDS_NEED = 320.dp

/** How far above the tallest thing the plot's top sits, so a held schedule is not drawn as the frame. */
private const val HEADROOM = 1.1

/** Roughly how many time labels to put under the columns. */
private const val TIME_TICKS = 5

/**
 * The nearest step a reader counts in, at or above [least].
 *
 * A 41-second run divided five ways is 8.2, and columns every 9 gave 0s, 9s, 18s, 27s, 36s — five
 * correct labels nobody reads a clock in. The same run on this scale is labelled every ten.
 */
internal fun niceStep(least: Int): Int =
    NICE_STEPS.firstOrNull { it >= least } ?: (ceilDiv(least, MINUTE_SECONDS) * MINUTE_SECONDS)

private val NICE_STEPS = listOf(1, 2, 5, 10, 15, 20, 30, 60, 120, 300, 600)
private const val MINUTE_SECONDS = 60

private const val MILLIS_PER_SECOND = 1_000L
private const val FLOOR_DASH = 2f

private const val X_AXIS_WORDS = "time since the request left the socket →"

private val BARS_H = 96.dp
private val P95_H = 46.dp
private val GAP = 22.dp
private val CURVE_H = 132.dp
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
private const val CEILING_W = 1f
private const val CEILING_DASH_ON = 2f
private const val CEILING_DASH_OFF = 5f
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
