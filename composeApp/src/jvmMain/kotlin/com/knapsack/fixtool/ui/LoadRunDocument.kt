package com.knapsack.fixtool.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.load.LoadPhase
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.load.LoadReportCodec
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **The load run document: the report as a screen.**
 *
 * Opens the moment Run is clicked and draws the live report as it changes, then draws the same record from
 * disk when Recent reopens it, so it says the same thing at both moments.
 *
 * The rank is the whole redraw. The verdict is a badge in the header, because "did it pass" is the first
 * question and used to be answered at the bottom of a scroll region. The two counts that decide the verdict
 * are figures; the five that do not — issued, duplicates, late, strays, peak outstanding — are one quiet
 * strip, where before all five tiles were the same weight as the number that failed the build.
 *
 * Four states the happy path skips are drawn here too: a run stopped by hand is reported and not judged, a
 * run that matched nothing spends its empty chart slot on the diagnosis it can already prove, a pruned
 * record says so as an empty state rather than one grey sentence in a corner, and a pane narrow enough to
 * be a split drops the figures a rank and gives its wide rows their own horizontal scroll.
 */
@Composable
fun LoadRunDocument(viewModel: FixMessageViewModel, doc: ScenarioDoc.LoadRunView, modifier: Modifier = Modifier) {
    val live by viewModel.activeLoadRun.collectAsState()
    val report = if (live?.id == doc.loadId) live else remember(doc.loadId, live) { viewModel.loadRecordStore.read(doc.loadId) }
    if (report == null) {
        PrunedRecord(viewModel.loadRecordStore.directory, modifier)
        return
    }
    val wire =
        remember(report.id, report.status) { if (report.unmatched.isEmpty()) emptyList() else viewModel.loadRecordStore.unmatchedWire(report.id) }
    LoadReportView(
        report = report,
        unmatchedWire = wire,
        records = viewModel.loadRecordStore.directoryFor(report.id),
        onStop = { viewModel.stopLoadRun(report.id) },
        onReveal = { id -> viewModel.revealLoadRequest(id) },
        onCompare = { viewModel.openLoadCompare(report.id) },
        modifier = modifier,
    )
}

/** The report drawn, with nothing of the view model in it, so a test can hand it a running report directly. */
@Composable
fun LoadReportView(
    report: LoadReport,
    unmatchedWire: List<String>,
    records: File,
    onStop: () -> Unit,
    onReveal: (String) -> Boolean = { false },
    onCompare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("load-run-document")) {
        // 460px is the width this pane reaches when it shares a split with the rail. Specified, not hoped
        // for: below it the figures drop a rank, the stat row goes to three columns, and the header's
        // actions collapse to an overflow rather than pushing the badge off the left.
        val narrow = maxWidth < NARROW
        Column(modifier = Modifier.fillMaxSize()) {
            LoadHeader(report, records, narrow, onStop, onCompare)
            ProgressBar(report)
            BarLine(report)
            // The scrollbar is the affordance, not a nicety: with the tool block and the three judgements
            // at the bottom of a document that can run past a screen, a reader who cannot see that there
            // is more below stops at whatever the fold happens to cut.
            val body = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(body)) {
                    Leads(report, narrow)
                    QuietStrip(report)
                    Throughput(report, narrow)
                    RoundTrip(report, narrow)
                    if (report.unmatched.isNotEmpty()) UnmatchedTable(report, unmatchedWire, narrow, onReveal)
                    Lanes(report, narrow)
                    ToolPart(report)
                    Judgements(report, records)
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(body),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Header, progress, figures
// ---------------------------------------------------------------------------------------------------

/**
 * The verdict, as the first thing on the screen. [Badge] is what used to be a 12sp line at the bottom of
 * a scroll region, which is the last place anyone looks for the answer to "did it pass".
 */
@Composable
private fun LoadHeader(r: LoadReport, records: File, narrow: Boolean, onStop: () -> Unit, onCompare: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val (headline, tint) = verdictHeadline(r)
        // The long headline is what shoulders the title out of the header at 460px, so narrow gets the
        // word and the count and leaves the arithmetic to the bar line under it.
        Badge(if (narrow) headline.substringBefore("  ") else headline, tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(r.label, color = AppTheme.Colors.text, style = AppTheme.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${r.lanes} lane${if (r.lanes == 1) "" else "s"} · settle ${humanDuration(r.settleMs)}" +
                        (if (r.seed.isNotEmpty()) " · " + r.seed.entries.joinToString(" ") { "${it.key}=${it.value}" } else "") +
                        (r.storeAndLog?.let { " · ${it.describe()}" } ?: "") +
                        // Reported and not judged: a fraction of a plan measured against that plan's
                        // thresholds would invent the one number this report exists to keep honest.
                        (if (r.status == LoadStatus.STOPPED) "" else r.verdict.exitCode?.let { " · exit $it" } ?: ""),
                    color = AppTheme.Colors.textDisabled,
                    style = AppTheme.Type.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(stateWord(r), color = tint, style = AppTheme.Type.meta, maxLines = 1, modifier = Modifier.testTag("load-state"))
            }
        }
        if (r.status == LoadStatus.RUNNING) {
            SlimButton("■ Stop", onClick = onStop, color = AppTheme.Colors.error, modifier = Modifier.testTag("load-stop"))
        } else if (narrow) {
            Overflow(records, onCompare)
        } else {
            // Load work is comparative by nature — the question is almost never "how fast is this" but
            // "did the fix work" — and Recent already lists every run this one could be measured against.
            onCompare?.let { SlimButton("Compare…", onClick = it, modifier = Modifier.testTag("load-compare")) }
            SlimButton("Copy JSON", onClick = { copyJson(records) }, modifier = Modifier.testTag("load-copy-json"))
            SlimButton("Reveal records", onClick = { reveal(records) }, modifier = Modifier.testTag("load-reveal"))
        }
    }
}

/** The header's actions at 460px, where three buttons would push the badge off the left edge. */
@Composable
private fun Overflow(records: File, onCompare: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        SlimButton("⋯", onClick = { open = true }, modifier = Modifier.testTag("load-overflow"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onCompare?.let { compare ->
                DropdownMenuItem(text = { Text("Compare…", style = AppTheme.Type.body) }, onClick = {
                    compare()
                    open = false
                })
            }
            DropdownMenuItem(text = { Text("Copy JSON", style = AppTheme.Type.body) }, onClick = {
                copyJson(records)
                open = false
            })
            DropdownMenuItem(text = { Text("Reveal records", style = AppTheme.Type.body) }, onClick = {
                reveal(records)
                open = false
            })
        }
    }
}

@Composable
private fun Badge(text: String, tint: Color) {
    Text(
        text,
        color = tint,
        style = AppTheme.Type.head,
        maxLines = 1,
        modifier =
            Modifier
                .background(AppTheme.Colors.background, RoundedCornerShape(3.dp))
                .border(1.dp, tint, RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("load-verdict"),
    )
}

/**
 * 8dp, not the 3dp hairline it was, and a failing segment is never thinner than [MIN_SEGMENT]: four
 * unanswered out of four thousand is a tenth of a pixel of red, which is the same picture as none.
 */
@Composable
private fun ProgressBar(r: LoadReport) {
    val total =
        r.issue.requested
            .coerceAtLeast(1)
            .toFloat()
    val matched = (r.replies.matched / total).coerceIn(0f, 1f)
    val running = r.status == LoadStatus.RUNNING
    val outstanding = if (running) (r.replies.unmatched / total).coerceIn(0f, 1f - matched) else 0f
    val unanswered = if (running) 0f else (r.replies.unmatched / total).coerceIn(0f, 1f - matched)
    Row(modifier = Modifier.fillMaxWidth().height(8.dp).background(AppTheme.Colors.surfaceVariant)) {
        if (matched > 0f) Box(Modifier.fillMaxHeight().weight(matched).background(AppTheme.Colors.success))
        if (outstanding > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(outstanding)
                    .widthIn(min = MIN_SEGMENT)
                    .background(AppTheme.Colors.info),
            )
        }
        if (unanswered > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(unanswered)
                    .widthIn(min = MIN_SEGMENT)
                    .background(AppTheme.Colors.error),
            )
        }
        val rest = 1f - matched - outstanding - unanswered
        if (rest > 0f) Spacer(Modifier.weight(rest))
    }
}

/** The counts beside the bar, and how much longer it has to go. Today's bar carried neither. */
@Composable
private fun BarLine(r: LoadReport) {
    val running = r.status == LoadStatus.RUNNING
    val left =
        if (running) {
            "${LoadReportCodec.fmt(r.replies.matched)} answered · ${LoadReportCodec.fmt(r.replies.unmatched)} outstanding · " +
                "${LoadReportCodec.fmt((r.issue.requested - r.issue.leftSocket).coerceAtLeast(0))} to go"
        } else {
            "${LoadReportCodec.fmt(r.replies.matched)} of ${LoadReportCodec.fmt(r.issue.leftSocket)} answered" +
                (if (r.replies.unmatched > 0) " · ${LoadReportCodec.fmt(r.replies.unmatched)} never came back" else "")
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant).padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            left,
            color = if (!running && r.replies.unmatched > 0) AppTheme.Colors.error else AppTheme.Colors.textSecondary,
            style = AppTheme.Type.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            clockLine(r),
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
            maxLines = 1,
            modifier = Modifier.testTag("load-clock"),
        )
    }
}

/** The two counts the verdict is made of, and nothing else at this size. */
@Composable
private fun Leads(r: LoadReport, narrow: Boolean) {
    val running = r.status == LoadStatus.RUNNING
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp)) {
        Lead(
            "answered",
            LoadReportCodec.fmt(r.replies.matched),
            if (running) "of ${LoadReportCodec.fmt(r.issue.leftSocket)} issued so far" else "first reply, on any listening session",
            "load-matched",
            if (r.replies.matched > 0) AppTheme.Colors.success else AppTheme.Colors.error,
            narrow,
        )
        Lead(
            if (running) "outstanding" else "unanswered",
            LoadReportCodec.fmt(r.replies.unmatched),
            when {
                running -> "awaiting a reply now · peak ${LoadReportCodec.fmt(r.tool.pendingPeak.toLong())}"
                r.replies.unmatched == 0L -> "none outstanding when settle closed"
                else -> "no reply within ${humanDuration(r.settleMs)} of the send"
            },
            "load-unmatched",
            when {
                running -> AppTheme.Colors.info
                r.replies.unmatched > 0 -> AppTheme.Colors.error
                else -> AppTheme.Colors.text
            },
            narrow,
        )
    }
}

@Composable
private fun RowScope.Lead(label: String, value: String, sub: String, tag: String, tint: Color, narrow: Boolean) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta, modifier = Modifier.testTag("$tag-label"))
        Text(
            value,
            color = tint,
            style = if (narrow) AppTheme.Type.figure.copy(fontSize = NARROW_FIGURE) else AppTheme.Type.figure,
            modifier = Modifier.testTag(tag),
        )
        Text(sub, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta, maxLines = 2)
    }
}

/**
 * The five counts that do not decide the verdict, on one line with separators.
 *
 * They were five tiles of `weight(1f)` at 17sp beside the two that do, so "duplicates 0" read as loudly
 * as the number that failed the build. Reported, never hidden — only ranked.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun QuietStrip(r: LoadReport) {
    val running = r.status == LoadStatus.RUNNING
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        StripItem("issued", if (running) LoadReportCodec.fmt(r.issue.leftSocket) else issuedSentence(r), "load-issued", first = true)
        StripItem("duplicates", LoadReportCodec.fmt(r.replies.duplicates), "load-duplicates")
        StripItem("late", if (running) "— settle not closed" else LoadReportCodec.fmt(r.replies.late), "load-late")
        StripItem("strays", LoadReportCodec.fmt(r.replies.strays), "load-strays")
        StripItem("peak outstanding", LoadReportCodec.fmt(r.tool.pendingPeak.toLong()), "load-peak")
        r.issue.achievedPerSecond?.let { StripItem("achieved", "${LoadReportCodec.fmt(it)}/s", "load-achieved") }
    }
}

/** Issued is three numbers, and stays three numbers. It moves rank, not meaning. */
private fun issuedSentence(r: LoadReport): String =
    "${LoadReportCodec.fmt(r.issue.requested)} requested, engine ${LoadReportCodec.fmt(r.issue.handedToEngine)}, " +
        "socket ${LoadReportCodec.fmt(r.issue.leftSocket)}"

@Composable
private fun StripItem(label: String, value: String, tag: String, first: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!first) Text("·", color = AppTheme.Colors.border, style = AppTheme.Type.meta, modifier = Modifier.padding(horizontal = 6.dp))
        Text(label, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta, modifier = Modifier.testTag("$tag-label"))
        Text(" $value", color = AppTheme.Colors.textSecondary, style = AppTheme.Type.meta, modifier = Modifier.testTag(tag))
    }
}

// ---------------------------------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------------------------------

/**
 * The per-second panel, when there was a per-second story to tell.
 *
 * Gated on [LoadReport.Issue.spanMs] rather than on the shape: a 4,000 burst leaves in 813ms and would
 * draw three bars that say nothing, while a ×300,000 burst issues for a minute and has as much to say as
 * a rate run does. At 460px the chart keeps its own horizontal scroll, so its columns stay a pixel each
 * and the pane never scrolls sideways.
 */
@Composable
private fun Throughput(r: LoadReport, narrow: Boolean) {
    if (!LoadCharts.hasPerSecond(r)) return
    val note =
        (r.shape as? LoadShape.Rate)
            ?.let { "red is any second under the pacer's own floor, ${it.perSecond}/s less Pacer.TOLERANCE" }
            ?: "a burst has no schedule, so no second is behind one"
    Section("Throughput and latency, second by second", note) {
        if (narrow) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                PerSecondPanel(r, Modifier.width(WIDE_CHART))
            }
        } else {
            PerSecondPanel(r)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Section(title: String, note: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp)) {
        HorizontalDivider(
            color = AppTheme.Separators.color,
            thickness = AppTheme.Separators.dividerThickness,
            modifier = Modifier.padding(top = 8.dp),
        )
        FlowRow(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            Text(title, color = AppTheme.Colors.text, style = AppTheme.Type.head)
            if (note.isNotEmpty()) {
                Text("  $note", color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta, modifier = Modifier.padding(top = 2.dp))
            }
        }
        content()
    }
}

/**
 * The distribution as a card in the idiom [LatencyPanel]'s `StatisticsCard` established — a new card in
 * that idiom rather than a reuse: that one is private, takes a `LatencyStatistics`, and shows a p90 and a
 * stddev the load run's seven-number `Distribution` does not carry.
 *
 * When nothing was answered there is no distribution to draw, and the slot spends itself on the diagnosis
 * instead of an empty axis. See [nothingMatchedDiagnosis].
 */
@Composable
private fun RoundTrip(r: LoadReport, narrow: Boolean) {
    val d = r.roundTrip
    if (d == null || d.samples == 0) {
        Section("Round trip", "no samples") {
            Empty("Nothing was answered, so there is no distribution.", nothingMatchedDiagnosis(r))
        }
        return
    }
    Section("Round trip", "${LoadReportCodec.fmt(d.samples.toLong())} samples · socket send stamp to socket receive stamp") {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(6.dp))
                    .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(6.dp))
                    .padding(10.dp)
                    .testTag("load-round-trip"),
        ) {
            val stats =
                listOf(
                    "min" to LoadReportCodec.humanMicros(d.min),
                    "p50" to LoadReportCodec.humanMicros(d.p50),
                    "p95" to LoadReportCodec.humanMicros(d.p95),
                    "p99" to LoadReportCodec.humanMicros(d.p99),
                    "max" to LoadReportCodec.humanMicros(d.max),
                    "mean" to LoadReportCodec.humanMicros(d.mean),
                )
            // Six columns, or two rows of three at 460px — never six columns squeezed until the figures wrap.
            stats.chunked(if (narrow) NARROW_STATS else stats.size).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) { row.forEach { (k, v) -> Stat(k, v) } }
            }
            OutstandingCurve(r)
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
        Text(
            value,
            color = AppTheme.Colors.text,
            style = AppTheme.Type.body.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.testTag("load-rt-$label"),
        )
    }
}

/**
 * **The most useful sentence the load run can add, and it costs a comparison.**
 *
 * When nothing matched and the strays are about as many as the requests, replies *are* arriving and none
 * carries the reply tag: that is a match misconfiguration, and the tool already counted everything needed
 * to say so. `Replies.strays` has always been counted and never surfaced.
 */
private fun nothingMatchedDiagnosis(r: LoadReport): String {
    val issued = r.issue.leftSocket
    return if (issued > 0 && r.replies.strays >= issued - issued / STRAY_SLACK) {
        "${LoadReportCodec.fmt(r.replies.strays)} replies arrived and none carried tag ${r.match.replyTag}. A reply on a " +
            "listening session that matches nothing issued is counted as a stray, and there are about as many strays as " +
            "requests. Check the reply tag."
    } else {
        "The percentiles need at least one matched reply. The unanswered requests are listed below."
    }
}

@Composable
private fun Empty(headline: String, hint: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(10.dp)
                .testTag("load-empty"),
    ) {
        Text(headline, color = AppTheme.Colors.text, style = AppTheme.Type.body)
        Text(hint, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
    }
}

/**
 * The unanswered requests, as rows that do something: a click opens the request in whichever session pane
 * still holds it, Ctrl/Cmd+C copies the selected row's wire, and one action hands the whole set to a
 * colleague. They were inert text with the wire clipped to one line.
 */
@Composable
private fun UnmatchedTable(r: LoadReport, wire: List<String>, narrow: Boolean, onReveal: (String) -> Boolean) {
    var selected by remember(r.id) { mutableStateOf(-1) }
    var note by remember(r.id) { mutableStateOf("") }
    val shown = minOf(r.unmatched.size, MAX_UNMATCHED_ROWS)
    Section(
        "The ${LoadReportCodec.fmt(r.replies.unmatched)} that never came back",
        "click a row to open it in its session · $COPY_KEYS copies the wire",
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    val copy = event.key == Key.C && (event.isMetaPressed || event.isCtrlPressed)
                    if (event.type == KeyEventType.KeyDown && copy && selected >= 0) {
                        copyText(wire.getOrNull(selected) ?: r.unmatched[selected].id)
                        note = "wire copied"
                        true
                    } else {
                        false
                    }
                },
        ) {
            // The wide row scrolls inside itself. The pane never scrolls sideways, which is what makes a
            // 460px split usable instead of merely possible.
            Column(modifier = Modifier.fillMaxWidth().let { if (narrow) it.horizontalScroll(rememberScrollState()) else it }) {
                Row {
                    Head("id (${r.match.requestTag})", ID_COL)
                    Head("lane", LANE_COL)
                    Head("sent", SENT_COL)
                    Head("wire", Dp.Unspecified)
                }
                r.unmatched.take(MAX_UNMATCHED_ROWS).forEachIndexed { i, u ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .background(if (i == selected) AppTheme.Colors.selectionSecondary else Color.Transparent)
                                .clickable {
                                    selected = i
                                    note = if (onReveal(u.id)) "" else "no session pane still holds this request"
                                }.testTag("load-unmatched-$i"),
                    ) {
                        Cell(u.id, ID_COL, AppTheme.Colors.text)
                        Cell(u.lane.toString(), LANE_COL, AppTheme.Colors.textSecondary)
                        Cell(clock(u.sentAt), SENT_COL, AppTheme.Colors.textSecondary)
                        Cell(wire.getOrNull(i) ?: "", Dp.Unspecified, AppTheme.Colors.textDisabled)
                        Text(" open ›", color = AppTheme.Colors.info, style = AppTheme.Type.meta)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    // The count of rows actually on screen, not of rows the cap allows: a record keeps at
                    // most UNMATCHED_IN_JSON of them, so "200 of 590" over four rows was a third number.
                    if (shown < r.unmatchedTotal) {
                        "${LoadReportCodec.fmt(shown.toLong())} of ${LoadReportCodec.fmt(r.unmatchedTotal.toLong())} " +
                            "shown · the rest are in unmatched.fix"
                    } else {
                        "all ${LoadReportCodec.fmt(r.unmatchedTotal.toLong())} shown"
                    },
                    color = AppTheme.Colors.textDisabled,
                    style = AppTheme.Type.meta,
                    modifier = Modifier.weight(1f),
                )
                if (note.isNotEmpty()) {
                    Text(
                        note,
                        color = AppTheme.Colors.warning,
                        style = AppTheme.Type.meta,
                        modifier = Modifier.testTag("load-unmatched-note"),
                    )
                }
                SlimButton("copy all as FIX", onClick = { copyText(wire.joinToString("\n")) }, modifier = Modifier.testTag("load-copy-unmatched"))
            }
        }
    }
}

@Composable
private fun Head(text: String, width: Dp) {
    Text(
        text,
        color = AppTheme.Colors.textDisabled,
        style = AppTheme.Type.meta,
        maxLines = 1,
        modifier = if (width == Dp.Unspecified) Modifier else Modifier.width(width),
    )
}

@Composable
private fun Cell(text: String, width: Dp, tint: Color) {
    Text(
        text,
        color = tint,
        style = AppTheme.Type.body.copy(fontFamily = FontFamily.Monospace),
        maxLines = 1,
        modifier = if (width == Dp.Unspecified) Modifier else Modifier.width(width),
    )
}

/**
 * **The lanes, and the one sentence a lane table exists to produce.**
 *
 * The real question is never "what were lane 37's six numbers" — a 50 × 6 matrix answers nothing anyone
 * asks — it is "is any lane much worse than the rest". So the table is sorted by p95, worst first, and
 * the note above it says whether the spread is worth looking at.
 *
 * The latency columns are only here because each lane renders ahead of its own sends. While one pacer
 * loop rendered and sent every lane round-robin, lane N left later than lane 1 by construction, and this
 * table would have reported that ordering as the venue's behaviour.
 */
@Composable
private fun Lanes(r: LoadReport, narrow: Boolean) {
    if (r.perLane.size < 2) return
    val sorted = r.perLane.sortedByDescending { it.p95Us ?: -1 }
    var all by remember(r.id) { mutableStateOf(false) }
    val rows = if (all) sorted else worstOf(sorted, LANE_ROWS)
    Section("Per lane", laneSentence(sorted)) {
        Column(modifier = Modifier.fillMaxWidth().let { if (narrow) it.horizontalScroll(rememberScrollState()) else it }) {
            Row {
                Head("lane", LANE_NAME_COL)
                Head("answered", LANE_COUNT_COL)
                Head("unanswered", LANE_COUNT_COL)
                Head("duplicates", LANE_COUNT_COL)
                Head("p50", LANE_COUNT_COL)
                Head("p95", LANE_COUNT_COL)
            }
            rows.forEach { l ->
                Row(modifier = Modifier.testTag("load-lane-${l.slot}")) {
                    Cell(l.slot.toString(), LANE_NAME_COL, AppTheme.Colors.textSecondary)
                    Cell(LoadReportCodec.fmt(l.matched), LANE_COUNT_COL, AppTheme.Colors.text)
                    Cell(
                        LoadReportCodec.fmt(l.unanswered),
                        LANE_COUNT_COL,
                        if (l.unanswered > 0) AppTheme.Colors.error else AppTheme.Colors.textSecondary,
                    )
                    Cell(LoadReportCodec.fmt(l.duplicates), LANE_COUNT_COL, AppTheme.Colors.textSecondary)
                    Cell(l.p50Us?.let { LoadReportCodec.humanMicros(it) } ?: "—", LANE_COUNT_COL, AppTheme.Colors.textSecondary)
                    Cell(l.p95Us?.let { LoadReportCodec.humanMicros(it) } ?: "—", LANE_COUNT_COL, AppTheme.Colors.text)
                }
            }
        }
        // Fifty rows of near-identical numbers is the matrix that answers nothing, and on a fifty-lane run
        // it pushed the tool block and the three judgements off the bottom of the document. So: the lanes
        // worth looking at, and an explicit ask for the rest.
        if (sorted.size > rows.size || all) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                Text(
                    if (all) {
                        "all ${sorted.size} lanes"
                    } else {
                        "${rows.size} of ${sorted.size} lanes · every lane with something unanswered, then the worst by p95"
                    },
                    color = AppTheme.Colors.textDisabled,
                    style = AppTheme.Type.meta,
                    modifier = Modifier.weight(1f).testTag("load-lane-count"),
                )
                SlimButton(
                    if (all) "show the worst only" else "show all ${sorted.size}",
                    onClick = { all = !all },
                    modifier = Modifier.testTag("load-lane-all"),
                )
            }
        }
    }
}

/**
 * The lanes worth a row: every lane with something unanswered, then the slowest, up to [limit].
 *
 * Unanswered first because it is the finding that does not need latency at all — four unanswered
 * requests all on one lane is the answer, whatever that lane's p95 was.
 */
internal fun worstOf(sorted: List<LoadReport.LaneCounts>, limit: Int): List<LoadReport.LaneCounts> {
    if (sorted.size <= limit) return sorted
    val missing = sorted.filter { it.unanswered > 0 }
    val slowest = sorted.filter { it.unanswered == 0L }
    return (missing + slowest).take(limit)
}

/** "no lane is out of line", or the lane that is — which is the whole answer a lane table owes. */
internal fun laneSentence(sorted: List<LoadReport.LaneCounts>): String {
    val missing = sorted.filter { it.unanswered > 0 }
    if (missing.size == 1) return "every unanswered request was issued on lane ${missing.single().slot}"
    val p95s = sorted.mapNotNull { it.p95Us }
    if (p95s.size < 2) return "completeness per lane · no round trips to compare"
    val median = p95s.sorted()[p95s.size / 2].coerceAtLeast(1)
    val worst = sorted.first()
    val ratio = (worst.p95Us ?: 0).toDouble() / median
    return if (ratio >= LANE_OUTLIER) {
        "lane ${worst.slot}'s p95 is ${"%.1f".format(ratio)}× the median lane's — worth a look"
    } else {
        "sorted by p95, worst first · no lane is more than ${LANE_OUTLIER.toInt()}× the median lane's p95"
    }
}

/** FixTool's own contribution, shown and never hidden. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ToolPart(r: LoadReport) {
    Section("The tool's own part", "shown, never hidden") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
            Pill(if (r.tool.limited) "limited" else "clean", if (r.tool.limited) AppTheme.Colors.error else AppTheme.Colors.success)
            StripItem("discarded by the panes", LoadReportCodec.fmt(r.tool.discarded), "load-tool-discarded")
            StripItem("accepted that never left the socket", LoadReportCodec.fmt(r.tool.neverLeftSocket), "load-tool-never-left")
            StripItem("refused", LoadReportCodec.fmt(r.tool.issueFailures), "load-tool-refused")
            // Read off the shape, not off the absence of a rate report. A rate run that ended before its
            // schedule was judged has no report either, and calling that one "burst" is a plain lie about
            // what was asked for — which is exactly what a 500/s run interrupted mid-flight showed.
            val rate = r.rate
            when {
                rate != null -> StripItem("rate", LoadReportCodec.rateSentence(rate), "load-tool-rate")
                r.shape is LoadShape.Burst -> StripItem("rate", "burst, so no schedule to lag", "load-tool-rate")
                else -> StripItem("rate", "the run did not finish, so its schedule was never judged", "load-tool-rate")
            }
        }
    }
}

/**
 * The three separate judgements, as pills. The model is right and only its presentation moves — except
 * for a stopped run, which gets one pill saying it was not judged. Judging a fraction of a plan against
 * that plan's thresholds invents the one number this report exists to keep honest.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Judgements(r: LoadReport, records: File) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant).padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.testTag("load-judgements"),
        ) {
            if (r.status == LoadStatus.STOPPED) {
                Pill("not judged · the run did not finish", AppTheme.Colors.textDisabled)
            } else if (r.status == LoadStatus.RUNNING) {
                Pill("running · no verdict yet", AppTheme.Colors.info)
            } else {
                // The enum stays UNMATCHED, because that is what the codec writes and what a reader of an
                // older load.json has to keep finding. The screen says "unanswered", like the rest of it.
                val complete = r.verdict.completeness == LoadReport.Completeness.COMPLETE
                Pill(
                    "completeness · " + if (complete) "complete" else "unanswered",
                    if (complete) AppTheme.Colors.success else AppTheme.Colors.error,
                )
                Pill(
                    "rate · " +
                        if (r.verdict.rate == LoadReport.RateVerdict.NOT_APPLICABLE) {
                            "n/a, burst"
                        } else {
                            r.verdict.rate.name
                                .lowercase()
                        },
                    when (r.verdict.rate) {
                        LoadReport.RateVerdict.HELD -> AppTheme.Colors.success
                        LoadReport.RateVerdict.SHORTFALL -> AppTheme.Colors.warning
                        LoadReport.RateVerdict.NOT_APPLICABLE -> AppTheme.Colors.textDisabled
                    },
                )
                Pill("tool · ${r.verdict.tool.name.lowercase()}", if (r.tool.limited) AppTheme.Colors.error else AppTheme.Colors.success)
            }
        }
        Text(
            if (r.status == LoadStatus.STOPPED) {
                "A stopped run keeps its records and its numbers. It gets no verdict, because a fraction of a plan " +
                    "measured against that plan's thresholds is a number nobody asked for."
            } else {
                records.path
            },
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
        )
    }
}

@Composable
private fun Pill(text: String, tint: Color) {
    Text(
        text,
        color = tint,
        style = AppTheme.Type.meta,
        modifier =
            Modifier
                .border(1.dp, tint, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * **A pruned record is not an error**, and it is not one grey sentence in a corner either.
 *
 * The design note wanted a "Run this plan again" button here on the grounds that the plan outlives the
 * record. It does not: the plan is only ever written inside `load.json`, so the prune that took the
 * record took the plan with it. What the pane can honestly offer is the folder that still has the runs
 * that were kept.
 */
@Composable
private fun PrunedRecord(loads: File, modifier: Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxSize().padding(12.dp).testTag("load-run-document"),
    ) {
        Badge("NO RECORD", AppTheme.Colors.textDisabled)
        Text("This load run is no longer on disk.", color = AppTheme.Colors.text, style = AppTheme.Type.head)
        Text(
            "The loads directory keeps the most recent runs, and this one has been pruned. Its plan went with it — " +
                "a plan is only ever written inside its own load.json — so there is nothing here to run again.",
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.body,
        )
        SlimButton("Reveal the loads folder", onClick = { reveal(loads) }, modifier = Modifier.testTag("load-reveal"))
    }
}

// ---------------------------------------------------------------------------------------------------
// The plain functions
// ---------------------------------------------------------------------------------------------------

/** The verdict as one loud line, in the badge. */
private fun verdictHeadline(r: LoadReport): Pair<String, Color> =
    when {
        r.status == LoadStatus.RUNNING && r.phase == LoadPhase.SETTLING -> "SETTLING" to AppTheme.Colors.warning
        r.status == LoadStatus.RUNNING && r.phase == LoadPhase.PREPARING -> "PREPARING" to AppTheme.Colors.info
        r.status == LoadStatus.RUNNING -> "ISSUING" to AppTheme.Colors.info
        r.status == LoadStatus.STOPPED ->
            "STOPPED  after ${LoadReportCodec.fmt(r.issue.leftSocket)} of ${LoadReportCodec.fmt(r.issue.requested)} issued" to
                AppTheme.Colors.textSecondary
        r.verdict.completeness == LoadReport.Completeness.UNMATCHED ->
            "UNANSWERED  ${LoadReportCodec.fmt(r.replies.unmatched)} of ${LoadReportCodec.fmt(r.issue.leftSocket)}" to AppTheme.Colors.error
        r.verdict.exitCode == LoadReport.EXIT_FAILED -> "FAILED  the tool limited the run" to AppTheme.Colors.error
        else ->
            "COMPLETE  ${LoadReportCodec.fmt(r.replies.matched)} of ${LoadReportCodec.fmt(r.issue.leftSocket)} answered" to
                AppTheme.Colors.success
    }

/** The same state in one word, for the header's meta line. */
private fun stateWord(r: LoadReport): String =
    when {
        r.status == LoadStatus.RUNNING && r.phase == LoadPhase.SETTLING -> "settling · ${humanDuration(r.settleLeftMs ?: r.settleMs)} left"
        r.status == LoadStatus.RUNNING -> r.phase.name.lowercase()
        r.status == LoadStatus.STOPPED -> "stopped"
        r.verdict.completeness == LoadReport.Completeness.UNMATCHED -> "unanswered ${LoadReportCodec.fmt(r.replies.unmatched)}"
        r.verdict.tool == LoadReport.ToolVerdict.LIMITED -> "tool limited"
        else -> "complete"
    }

/** How long it has run, and how much longer it has to go. */
private fun clockLine(r: LoadReport): String =
    if (r.status == LoadStatus.RUNNING) {
        val elapsed = System.currentTimeMillis() - (r.issue.firstSendAt ?: r.startedAt)
        val perMs = r.issue.achievedPerSecond?.takeIf { it > 0 }
        val left = perMs?.let { (r.issue.requested - r.issue.leftSocket).coerceAtLeast(0) * MILLIS_PER_SECOND / it }
        "t+${RunSetStats.humanMs(elapsed.coerceAtLeast(0))}" + (left?.let { " · about ${RunSetStats.humanMs(it)} left" } ?: "")
    } else {
        listOfNotNull(
            r.issue.spanMs?.let { "${RunSetStats.humanMs(it)} issuing" },
            r.timing?.let { "${RunSetStats.humanMs(it.drainMs)} drain" },
        ).joinToString(" · ")
    }

private fun copyText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

private fun copyJson(records: File) {
    runCatching { copyText(File(records, "load.json").readText()) }
}

private fun reveal(dir: File) {
    runCatching { Desktop.getDesktop() }.getOrNull()?.let { desktop -> runCatching { desktop.open(dir) } }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun clock(epochMillis: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private val COPY_KEYS: String =
    if (System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
    ) {
        "⌘C"
    } else {
        "Ctrl+C"
    }

private val NARROW = 460.dp
private val NARROW_FIGURE = 20.sp
private val MIN_SEGMENT = 4.dp
private val WIDE_CHART = 720.dp
private val LANE_NAME_COL = 52.dp
private val LANE_COUNT_COL = 92.dp
private val ID_COL = 168.dp
private val LANE_COL = 44.dp
private val SENT_COL = 100.dp
private const val NARROW_STATS = 3
private const val MAX_UNMATCHED_ROWS = 200
private const val MILLIS_PER_SECOND = 1_000L

/** How far the stray count may fall short of the issued count and still read as "every reply was a stray". */
private const val STRAY_SLACK = 20

/** How many times the median lane's p95 a lane has to reach before the sentence names it. */
private const val LANE_OUTLIER = 2.0

/** Rows the lane table shows before it asks. Enough to see a spread, few enough to keep the verdict on screen. */
private const val LANE_ROWS = 8
