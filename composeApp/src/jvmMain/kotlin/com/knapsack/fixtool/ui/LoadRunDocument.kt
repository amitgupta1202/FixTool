package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.load.LoadPhase
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.load.LoadReportCodec
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **The load run document: the report as a screen.**
 *
 * Opens the moment Run is clicked and draws the live report as it changes, then draws the same record from
 * disk when Recent reopens it, so it says the same thing at both moments. The five tiles are the headline
 * the issue asks for. Below them: the issue line, the two timings, the distribution, the tool's own part,
 * the unanswered requests as a table, and the verdict with the three separate judgements named.
 */
@Composable
fun LoadRunDocument(viewModel: FixMessageViewModel, doc: ScenarioDoc.LoadRunView, modifier: Modifier = Modifier) {
    val live by viewModel.activeLoadRun.collectAsState()
    val report = if (live?.id == doc.loadId) live else remember(doc.loadId, live) { viewModel.loadRecordStore.read(doc.loadId) }
    if (report == null) {
        Text(
            "This load run is no longer on disk — the loads directory keeps the most recent runs, and this one has been pruned.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = modifier.padding(8.dp),
        )
        return
    }
    val wire = remember(report.id, report.status) { if (report.unmatched.isEmpty()) emptyList() else viewModel.loadRecordStore.unmatchedWire(report.id) }
    LoadReportView(
        report = report,
        unmatchedWire = wire,
        recordsPath = viewModel.loadRecordStore.directoryFor(report.id).path,
        onStop = { viewModel.stopLoadRun(report.id) },
        modifier = modifier,
    )
}

/** The report drawn, with nothing of the view model in it, so a test can hand it a running report directly. */
@Composable
fun LoadReportView(
    report: LoadReport,
    unmatchedWire: List<String>,
    recordsPath: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag("load-run-document")) {
        LoadHeader(report, onStop = onStop)
        ProgressBar(report)
        Tiles(report)
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Lines(report)
            if (report.perSecond.size > 1) PerSecondStrip(report)
            if (report.unmatched.isNotEmpty()) UnmatchedTable(report, unmatchedWire)
            Verdict(report, recordsPath)
        }
    }
}

@Composable
private fun LoadHeader(r: LoadReport, onStop: () -> Unit) {
    val (state, tint) =
        when {
            r.status == LoadStatus.RUNNING && r.phase == LoadPhase.SETTLING ->
                "settling · ${humanDuration(r.settleLeftMs ?: r.settleMs)} left" to AppTheme.Colors.warning
            r.status == LoadStatus.RUNNING -> r.phase.name.lowercase() to AppTheme.Colors.info
            r.status == LoadStatus.STOPPED -> "stopped" to AppTheme.Colors.textSecondary
            r.verdict.completeness == LoadReport.Completeness.UNMATCHED -> "unmatched ${LoadReportCodec.fmt(r.replies.unmatched)}" to AppTheme.Colors.error
            r.verdict.tool == LoadReport.ToolVerdict.LIMITED -> "tool limited" to AppTheme.Colors.error
            else -> "complete" to AppTheme.Colors.success
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text("⚡ ${r.label}", color = AppTheme.Colors.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(
            "${r.lanes} lane${if (r.lanes == 1) "" else "s"} · settle ${humanDuration(r.settleMs)}" +
                (if (r.seed.isNotEmpty()) " · " + r.seed.entries.joinToString(" ") { "${it.key}=${it.value}" } else "") +
                (r.storeAndLog?.let { " · ${it.describe()}" } ?: ""),
            color = AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f),
        )
        Text(state, color = tint, fontSize = 10.sp, modifier = Modifier.testTag("load-state"))
        if (r.status == LoadStatus.RUNNING) {
            SlimButton("■ Stop", onClick = onStop, color = AppTheme.Colors.error, modifier = Modifier.testTag("load-stop"))
        }
    }
}

@Composable
private fun ProgressBar(r: LoadReport) {
    val total = r.issue.requested.coerceAtLeast(1).toFloat()
    val matched = (r.replies.matched / total).coerceIn(0f, 1f)
    val pending = if (r.status == LoadStatus.RUNNING) (r.replies.unmatched / total).coerceIn(0f, 1f - matched) else 0f
    val unmatched = if (r.status != LoadStatus.RUNNING) (r.replies.unmatched / total).coerceIn(0f, 1f - matched) else 0f
    Row(modifier = Modifier.fillMaxWidth().height(3.dp).background(AppTheme.Colors.surfaceVariant)) {
        if (matched > 0f) Box(Modifier.fillMaxHeight().weight(matched).background(AppTheme.Colors.success))
        if (pending > 0f) Box(Modifier.fillMaxHeight().weight(pending).background(AppTheme.Colors.info))
        if (unmatched > 0f) Box(Modifier.fillMaxHeight().weight(unmatched).background(AppTheme.Colors.error))
        val rest = 1f - matched - pending - unmatched
        if (rest > 0f) Spacer(Modifier.weight(rest))
    }
}

@Composable
private fun Tiles(r: LoadReport) {
    val running = r.status == LoadStatus.RUNNING
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant)) {
        Tile("issued", LoadReportCodec.fmt(r.issue.leftSocket), if (running) "of ${LoadReportCodec.fmt(r.issue.requested)}" else "engine ${LoadReportCodec.fmt(r.issue.handedToEngine)} · socket ${LoadReportCodec.fmt(r.issue.leftSocket)}", "load-issued")
        Tile("matched", LoadReportCodec.fmt(r.replies.matched), "first reply, any session", "load-matched")
        Tile(
            if (running) "pending" else "unmatched",
            LoadReportCodec.fmt(r.replies.unmatched),
            if (running) "awaiting a reply" else "no reply within ${humanDuration(r.settleMs)}",
            "load-unmatched",
            tint = if (!running && r.replies.unmatched > 0) AppTheme.Colors.error else AppTheme.Colors.text,
        )
        Tile("duplicates", LoadReportCodec.fmt(r.replies.duplicates), "reported, not judged", "load-duplicates")
        Tile("late", if (running) "—" else LoadReportCodec.fmt(r.replies.late), if (running) "settle not closed" else "after settle closed", "load-late")
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String, sub: String, tag: String, tint: Color = AppTheme.Colors.text) {
    Column(modifier = Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text(label, color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.testTag("$tag-label"))
        Text(value, color = tint, fontSize = 17.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(tag))
        Text(sub, color = AppTheme.Colors.textDisabled, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun Lines(r: LoadReport) {
    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val issue = r.issue
        Line(
            "issue",
            buildString {
                issue.firstSendAt?.let { append("first send ${clock(it)}") }
                issue.spanMs?.let { span ->
                    append(" · ${LoadReportCodec.fmt(issue.leftSocket)} left the socket in ${RunSetStats.humanMs(span)}")
                    issue.achievedPerSecond?.let { append(" · ${LoadReportCodec.fmt(it)}/s") }
                }
                append(" · prepared in ${issue.prepareMs}ms")
                r.template.perMessageTags.takeIf { it.isNotEmpty() }?.let { append(" · per message: ${it.joinToString(", ")}") }
                r.template.onceTags.takeIf { it.isNotEmpty() }?.let { append(" · once per lane: ${it.joinToString(", ")}") }
            }.trimStart(' ', '·'),
        )
        r.rate?.let { Line("rate", LoadReportCodec.rateSentence(it), tint = if (it.shortfalls.isEmpty()) AppTheme.Colors.textSecondary else AppTheme.Colors.warning) }
        r.timing?.let { Line("timing", "elapsed ${RunSetStats.humanMs(it.elapsedMs)} (first send → last matched) · drain ${RunSetStats.humanMs(it.drainMs)} (last send → last matched)") }
        r.roundTrip?.let { d ->
            Line(
                "round trip",
                "min ${LoadReportCodec.humanMicros(d.min)} · p50 ${LoadReportCodec.humanMicros(d.p50)} · p95 ${LoadReportCodec.humanMicros(d.p95)} · " +
                    "p99 ${LoadReportCodec.humanMicros(d.p99)} · max ${LoadReportCodec.humanMicros(d.max)} · mean ${LoadReportCodec.humanMicros(d.mean)} · " +
                    "${LoadReportCodec.fmt(d.samples.toLong())} round trips",
            )
        }
        Line(
            "tool",
            if (r.tool.limited) LoadReportCodec.toolSentence(r.tool) else "clean · ${r.tool.discarded} discarded · ${r.tool.neverLeftSocket} never left the socket · peak ${LoadReportCodec.fmt(r.tool.pendingPeak.toLong())} outstanding",
            tint = if (r.tool.limited) AppTheme.Colors.error else AppTheme.Colors.textSecondary,
        )
    }
}

@Composable
private fun Line(key: String, value: String, tint: Color = AppTheme.Colors.textSecondary) {
    Row {
        Text(key, color = AppTheme.Colors.textDisabled, fontSize = 11.sp, modifier = Modifier.width(72.dp))
        Text(value, color = tint, fontSize = 11.sp)
    }
}

/** One thin bar per second: matched replies, so a settle tail and a stall both read at a glance. */
@Composable
private fun PerSecondStrip(r: LoadReport) {
    val buckets = r.perSecond.take(MAX_STRIP_SECONDS)
    val peak = buckets.maxOf { maxOf(it.matched, it.issued) }.coerceAtLeast(1).toFloat()
    Column(modifier = Modifier.padding(horizontal = 9.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            buckets.forEach { s ->
                val h = (s.matched / peak).coerceIn(0.03f, 1f)
                Box(Modifier.weight(1f).fillMaxHeight(h).background(AppTheme.Colors.info))
            }
        }
        Text(
            "matched per second, ${buckets.size} second${if (buckets.size == 1) "" else "s"}" +
                (if (r.perSecond.size > MAX_STRIP_SECONDS) " of ${r.perSecond.size}" else ""),
            color = AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun UnmatchedTable(r: LoadReport, wire: List<String>) {
    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text("${LoadReportCodec.fmt(r.replies.unmatched)} unanswered within ${humanDuration(r.settleMs)}", color = AppTheme.Colors.error, fontSize = 11.sp)
        Row(modifier = Modifier.padding(top = 3.dp)) {
            Text("id", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.width(180.dp))
            Text("lane", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.width(60.dp))
            Text("sent", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.width(110.dp))
            Text("wire", color = AppTheme.Colors.textDisabled, fontSize = 10.sp)
        }
        r.unmatched.take(MAX_UNMATCHED_ROWS).forEachIndexed { i, u ->
            Row(modifier = Modifier.testTag("load-unmatched-$i")) {
                Text(u.id, color = AppTheme.Colors.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(180.dp))
                Text(u.lane.toString(), color = AppTheme.Colors.textSecondary, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                Text(clock(u.sentAt), color = AppTheme.Colors.textSecondary, fontSize = 11.sp, modifier = Modifier.width(110.dp))
                Text(wire.getOrNull(i) ?: "", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
        if (r.unmatchedTotal > MAX_UNMATCHED_ROWS) {
            Text("and ${LoadReportCodec.fmt((r.unmatchedTotal - MAX_UNMATCHED_ROWS).toLong())} more in unmatched.fix", color = AppTheme.Colors.textDisabled, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Verdict(r: LoadReport, records: String) {
    val (head, tint) =
        when {
            r.status == LoadStatus.RUNNING -> "RUNNING" to AppTheme.Colors.info
            r.status == LoadStatus.STOPPED -> "STOPPED  after ${LoadReportCodec.fmt(r.issue.leftSocket)} of ${LoadReportCodec.fmt(r.issue.requested)} issued" to AppTheme.Colors.textSecondary
            r.verdict.completeness == LoadReport.Completeness.UNMATCHED -> "UNMATCHED  ${LoadReportCodec.fmt(r.replies.unmatched)} of ${LoadReportCodec.fmt(r.issue.leftSocket)}" to AppTheme.Colors.error
            r.verdict.exitCode == LoadReport.EXIT_FAILED -> "FAILED  the tool limited the run" to AppTheme.Colors.error
            else -> "COMPLETE  ${LoadReportCodec.fmt(r.replies.matched)} of ${LoadReportCodec.fmt(r.issue.leftSocket)} answered" to AppTheme.Colors.success
        }
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant).padding(horizontal = 9.dp, vertical = 7.dp)) {
        Text(head, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("load-verdict"))
        Text(
            (r.verdict.exitCode?.let { "exit $it · " } ?: "") +
                "completeness ${r.verdict.completeness.name.lowercase()} · rate ${r.verdict.rate.name.lowercase().replace('_', ' ')} · tool ${r.verdict.tool.name.lowercase()}",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
        )
        Text("records: $records", color = AppTheme.Colors.textDisabled, fontSize = 10.sp)
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun clock(epochMillis: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private const val MAX_STRIP_SECONDS = 120
private const val MAX_UNMATCHED_ROWS = 200
