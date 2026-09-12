package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.SetOutcome
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.load.LoadReportCodec
import java.io.File

/**
 * **A load set, as a thing to read**: the set's verdict, where its clock went, its phases down the left,
 * and the focused phase drawn as the single-run document it already is.
 *
 * Two things a set has and a run does not sit above the split. A **verdict that names the phase**, because
 * "FAILED" without "PHASE 2" makes a reader open all three. And a **timeline**, because the interesting
 * question about a set is almost always where the time went: 60 of the set's 63 seconds being phase 2's
 * settle window waiting for four replies that never came is the whole finding, and no per-phase chart says
 * it. The per-phase charts keep their own axes, because phases are mostly settle and a shared axis would
 * give the interesting 0.8s eight pixels.
 *
 * The pane is [LoadReportView] with its two ends replaced through [PhaseFrame], which is why nothing new
 * had to be designed for it.
 */
@Composable
@Suppress("LongParameterList")
fun LoadSetView(
    record: LoadRecord,
    /** 1-based. The live phase by default, and whichever was clicked once one has been. */
    focused: Int,
    onFocus: (Int) -> Unit,
    /** The focused phase's unanswered wire, read by the caller because only it has the store. */
    phaseWire: List<String>,
    records: File,
    onStop: () -> Unit,
    onReveal: (String) -> Boolean = { false },
    onCompare: (() -> Unit)? = null,
    /** "Run set again": null when this record came from no saved set, which disables the button. */
    onRerun: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val phase = record.phases.getOrElse(focused - 1) { record.only }
    Column(modifier = modifier.fillMaxSize().testTag("load-set-document")) {
        SetHeader(record, records, onStop, onCompare, onRerun)
        SetTimeline(record)
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(modifier = Modifier.fillMaxSize()) {
            PhaseRail(record, focused, onFocus, modifier = Modifier.fillMaxHeight().weight(RAIL_SHARE))
            VerticalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
            LoadReportView(
                report = phase,
                unmatchedWire = phaseWire,
                records = records,
                onStop = onStop,
                onReveal = onReveal,
                onCompare = null,
                frame =
                    PhaseFrame(
                        number = focused,
                        header = { narrow -> PhaseHeader(focused, phase, narrow) },
                        footer = { PhaseFooter(phase) },
                    ),
                modifier = Modifier.fillMaxHeight().weight(PANE_SHARE),
            )
        }
    }
}

/**
 * The set's verdict, its one meta line, and its actions.
 *
 * **Stop set**, not Stop: one button, and its consequence is written into the skipped rows below it.
 */
@Composable
@Suppress("LongParameterList")
private fun SetHeader(
    record: LoadRecord,
    records: File,
    onStop: () -> Unit,
    onCompare: (() -> Unit)?,
    onRerun: (() -> Unit)?,
) {
    val (headline, tint) = setHeadline(record)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        VerdictBadge(headline, tint, tag = "load-set-verdict")
        Column(modifier = Modifier.weight(1f)) {
            val profiles =
                record.phases
                    .map { it.profileName }
                    .distinct()
                    .joinToString(", ")
            Text(
                "${record.label} · ${record.phases.size} phases · $profiles",
                color = AppTheme.Colors.text,
                style = AppTheme.Type.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                setMetaLine(record),
                color = AppTheme.Colors.textDisabled,
                style = AppTheme.Type.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("load-set-meta"),
            )
        }
        if (record.status == LoadStatus.RUNNING) {
            SlimButton(
                "■ Stop set",
                onClick = onStop,
                color = AppTheme.Colors.error,
                modifier = Modifier.testTag("load-set-stop"),
            )
        } else {
            onCompare?.let { SlimButton("Compare…", onClick = it, modifier = Modifier.testTag("load-set-compare")) }
            // Off when the set came from no file, with the reason in the meta line above rather than in a
            // notification nobody asked for by hovering a button.
            SlimButton(
                "Run again",
                onClick = onRerun ?: {},
                enabled = onRerun != null,
                modifier = Modifier.testTag("load-set-rerun"),
            )
        }
        // Always, in every state. A record on disk can be read and revealed while its set is still going,
        // and withholding both for the length of a soak is the state a reader is most likely to be in.
        SlimButton("Copy JSON", onClick = { copyJson(records) }, modifier = Modifier.testTag("load-set-copy-json"))
        SlimButton("Reveal records", onClick = { reveal(records) }, modifier = Modifier.testTag("load-set-reveal"))
    }
}

/** "FAILED · PHASE 2", "ISSUING · PHASE 2 OF 3", "PASSED". Under CONTINUE it names the first that failed. */
private fun setHeadline(record: LoadRecord): Pair<String, Color> = setWord(record) to setTint(record.verdict.outcome)

/**
 * The headline, and the one place a set says how much of itself is going at once.
 *
 * **Every phase that is running, and not the earliest of them.** The verdict names one phase because a
 * verdict is about one phase, but "ISSUING" is about the whole set, and a set whose phase 3 is hitting
 * quotes while phase 2 makes them and phase 1 asks for them is not issuing phase 1. Naming one of the
 * three would report a third of the set as all of it.
 */
private fun setWord(record: LoadRecord): String {
    val v = record.verdict
    return when (v.outcome) {
        SetOutcome.RUNNING -> {
            val live = record.livePhases
            when {
                live.isEmpty() -> "PREPARING"
                live.size == 1 -> "ISSUING · PHASE ${live.single()} OF ${record.phases.size}"
                else -> "ISSUING · PHASES ${andList(live)} OF ${record.phases.size}"
            }
        }
        SetOutcome.PASSED -> "PASSED"
        SetOutcome.FAILED -> v.phase?.let { "FAILED · PHASE $it" } ?: "FAILED"
        SetOutcome.STOPPED -> v.phase?.let { "STOPPED · PHASE $it" } ?: "STOPPED"
    }
}

/** "2 AND 3", "2, 3 AND 4": a list of phase numbers as a headline says them out loud. */
private fun andList(numbers: List<Int>): String =
    when (numbers.size) {
        0 -> ""
        1 -> numbers.single().toString()
        else -> numbers.dropLast(1).joinToString(", ") + " AND " + numbers.last()
    }

private fun setTint(outcome: SetOutcome): Color =
    when (outcome) {
        SetOutcome.RUNNING -> AppTheme.Colors.info
        SetOutcome.PASSED -> AppTheme.Colors.success
        SetOutcome.FAILED -> AppTheme.Colors.error
        SetOutcome.STOPPED -> AppTheme.Colors.textSecondary
    }

/** "run=b7f2 · desk=LDN · 5 lanes · memory store, no log · 1 passed, 1 failed, 1 skipped · 63.1s · exit 1" */
private fun setMetaLine(record: LoadRecord): String {
    val lanes = record.phases.maxOfOrNull { it.lanes } ?: 0
    val elapsed = record.finishedAt?.let { RunSetStats.humanMs((it - record.startedAt).coerceAtLeast(0)) }
    val seed = record.seed.entries.joinToString(" · ") { "${it.key}=${it.value}" }
    val storeAndLog = record.phases.firstOrNull()?.storeAndLog
    val parts =
        listOfNotNull(
            seed.ifBlank { null },
            "$lanes lane${if (lanes == 1) "" else "s"}",
            storeAndLog?.describe(),
            record.verdict.counts().ifBlank { null },
            // The set's own, because the sessions are the set's and no phase has a number of its own. "At
            // least", because the counter is written without a lock by every thread that touches it.
            record.discarded.takeIf { it > 0 }?.let { "${LoadReportCodec.fmt(it)} discarded, at least" },
            elapsed,
            record.exitCode?.let { "exit $it" },
            // Why "Run set again" is off, beside everything else this line says about the set, rather than
            // on a hover a reader has to go looking for.
            if (record.status != LoadStatus.RUNNING && record.set?.name == null) "no saved set to run again" else null,
        )
    return parts.joinToString(" · ")
}

/**
 * **Where the set's time went**: one track per phase, its issuing span solid and its settle window
 * hatched, on the set's own clock.
 *
 * The one sentence under it names the largest span, which is where tuning starts.
 */
@Composable
private fun SetTimeline(record: LoadRecord) {
    val spans = record.phases.map { span(record, it) }
    val total = spans.sumOf { it.issuingMs + it.settleMs }.coerceAtLeast(1)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.background)
                .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            "Where the set's time " + (if (record.status == LoadStatus.RUNNING) "is going" else "went") +
                " · issuing solid, settle window shaded",
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
        )
        record.phases.forEachIndexed { index, phase ->
            TimelineRow(index + 1, phase, spans[index], total)
        }
        Text(
            timelineSentence(record, spans),
            color = AppTheme.Colors.textSecondary,
            style = AppTheme.Type.meta,
            modifier = Modifier.testTag("load-set-timeline-note"),
        )
    }
}

/** One phase's issuing and settle spans, in milliseconds, from what its report already carries. */
private data class Span(
    val issuingMs: Long,
    val settleMs: Long,
) {
    val totalMs: Long get() = issuingMs + settleMs
}

private fun span(record: LoadRecord, phase: LoadReport): Span {
    if (phase.status == LoadStatus.SKIPPED || phase.status == LoadStatus.PENDING) return Span(0, 0)
    val end = phase.finishedAt ?: record.finishedAt ?: System.currentTimeMillis()
    val issuing = phase.issue.spanMs ?: 0
    val settle = (end - (phase.issue.lastSendAt ?: phase.startedAt)).coerceAtLeast(0)
    return Span(issuing, settle)
}

@Composable
private fun TimelineRow(n: Int, phase: LoadReport, span: Span, total: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$n · ${phase.label}",
            color = AppTheme.Colors.textSecondary,
            style = AppTheme.Type.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(TIMELINE_NAME_COL),
        )
        Row(modifier = Modifier.weight(1f).height(9.dp).background(AppTheme.Colors.surfaceVariant)) {
            if (span.totalMs <= 0) {
                Spacer(Modifier.weight(1f))
            } else {
                val issuing = (span.issuingMs.toFloat() / total).coerceIn(0f, 1f)
                val settle = (span.settleMs.toFloat() / total).coerceIn(0f, 1f - issuing)
                // Never thinner than a hair: a settle window of 40ms beside a 60s one is otherwise the
                // same picture as none.
                if (issuing > 0f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .weight(issuing)
                            .widthIn(min = TIMELINE_MIN)
                            .background(AppTheme.Colors.info),
                    )
                }
                if (settle > 0f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .weight(settle)
                            .widthIn(min = TIMELINE_MIN)
                            .background(AppTheme.Colors.border),
                    )
                }
                val rest = 1f - issuing - settle
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
        }
        Text(
            when {
                phase.isMuted -> "muted"
                phase.status == LoadStatus.SKIPPED -> "skipped"
                phase.status == LoadStatus.PENDING -> "queued"
                else -> RunSetStats.humanMs(span.totalMs)
            },
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
            maxLines = 1,
            modifier = Modifier.width(TIMELINE_TIME_COL),
        )
    }
}

/** The sentence that names the largest span, which is where tuning starts. */
private fun timelineSentence(record: LoadRecord, spans: List<Span>): String {
    val wall = record.finishedAt?.let { (it - record.startedAt).coerceAtLeast(0) } ?: spans.sumOf { it.totalMs }
    val worst = spans.withIndex().maxByOrNull { it.value.totalMs }
    if (worst == null || worst.value.totalMs <= 0) return "nothing has been measured yet"
    val phase = record.phases[worst.index]
    val settleDominates = worst.value.settleMs > worst.value.issuingMs
    val head =
        "${RunSetStats.humanMs(worst.value.totalMs)} of the set's ${RunSetStats.humanMs(wall)} " +
            "was phase ${worst.index + 1}"
    return if (settleDominates && phase.replies.unmatched > 0) {
        "$head's settle window, waiting for ${LoadReportCodec.fmt(phase.replies.unmatched)} " +
            "repl${if (phase.replies.unmatched == 1L) "y" else "ies"} that never came"
    } else if (settleDominates) {
        "$head's settle window"
    } else {
        "$head, issuing"
    }
}

/**
 * The phases down the left, with a count column: answered for a finished phase, issued for the live one,
 * and the word for the rest.
 */
@Composable
private fun PhaseRail(record: LoadRecord, focused: Int, onFocus: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(4.dp).testTag("load-set-rail")) {
        record.phases.forEachIndexed { index, phase ->
            val n = index + 1
            val (mark, tint) = phaseMark(phase)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onFocus(n) }
                        .background(if (n == focused) AppTheme.Colors.surfaceVariant else Color.Transparent)
                        .padding(horizontal = 5.dp, vertical = 4.dp)
                        .testTag("load-set-phase-$n"),
            ) {
                Text(mark, color = tint, style = AppTheme.Type.meta, maxLines = 1)
                Text(
                    phase.label,
                    color = if (n == focused) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
                    style = AppTheme.Type.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(phaseCount(phase), color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta, maxLines = 1)
            }
        }
    }
}

/** The three marks a scenario set's rail uses, plus the three a set's phases add. */
private fun phaseMark(phase: LoadReport): Pair<String, Color> =
    when {
        // ⊘, the glyph the scenarios rail draws for a muted step, because a muted phase has no verdict and
        // never will. That is what muted means.
        phase.isMuted -> "⊘" to AppTheme.Colors.textDisabled
        phase.status == LoadStatus.PENDING -> "○" to AppTheme.Colors.textDisabled
        phase.status == LoadStatus.SKIPPED -> "⏭" to AppTheme.Colors.textDisabled
        phase.status == LoadStatus.RUNNING -> "●" to AppTheme.Colors.info
        phase.status == LoadStatus.STOPPED -> "■" to AppTheme.Colors.textSecondary
        phase.verdict.exitCode == LoadReport.EXIT_PASSED -> "✓" to AppTheme.Colors.success
        else -> "✗" to AppTheme.Colors.error
    }

private fun phaseCount(phase: LoadReport): String =
    when {
        phase.isMuted -> "muted"
        phase.status == LoadStatus.PENDING -> "queued"
        phase.status == LoadStatus.SKIPPED -> "skipped"
        phase.status == LoadStatus.RUNNING -> LoadReportCodec.fmt(phase.issue.leftSocket)
        else -> LoadReportCodec.fmt(phase.replies.matched)
    }

/** The focused phase's own header, in the pane: which phase, and what it was asked to do. */
@Composable
private fun PhaseHeader(n: Int, phase: LoadReport, narrow: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val (mark, tint) = phaseMark(phase)
        VerdictBadge(if (narrow) mark else "$mark  " + phaseWord(phase), tint, tag = "load-set-phase-verdict")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$n · ${phase.label}",
                color = AppTheme.Colors.text,
                style = AppTheme.Type.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("load-set-phase-title"),
            )
            Text(
                "${phase.template.name} ${phase.shape.describe()}" +
                    (if (phase.indexFrom > 1) " from ${LoadReportCodec.fmt(phase.indexFrom.toLong())}" else "") +
                    " · 35=${phase.template.msgType} → ${phase.match.replyType ?: "any"}" +
                    " · ${phase.match.requestTag} → ${phase.match.replyTag}" +
                    " · settle ${humanDuration(phase.settleMs)}",
                color = AppTheme.Colors.textDisabled,
                style = AppTheme.Type.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("load-set-phase-plan"),
            )
        }
    }
}

/** The phase's state in a word, for its badge. */
private fun phaseWord(phase: LoadReport): String =
    when {
        phase.isMuted -> "MUTED"
        phase.status == LoadStatus.PENDING -> "QUEUED"
        phase.status == LoadStatus.SKIPPED -> "SKIPPED"
        phase.status == LoadStatus.STOPPED -> "STOPPED"
        phase.status == LoadStatus.RUNNING -> phase.stage.name
        phase.verdict.completeness == LoadReport.Completeness.UNMATCHED -> "UNANSWERED"
        phase.verdict.exitCode != LoadReport.EXIT_PASSED -> "FAILED"
        else -> "COMPLETE"
    }

/**
 * The focused phase's three judgements, with no records path: the set prints one, at the bottom of its own
 * header rather than once per phase.
 */
@Composable
private fun PhaseFooter(phase: LoadReport) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceVariant)
                .padding(horizontal = 9.dp, vertical = 7.dp)
                .testTag("load-set-phase-judgements"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            when {
                phase.status == LoadStatus.SKIPPED || phase.status == LoadStatus.PENDING ->
                    JudgementPill(
                        "not run · " + (phase.note ?: "this phase did not run"),
                        AppTheme.Colors.textDisabled,
                    )
                phase.status == LoadStatus.STOPPED ->
                    JudgementPill("not judged · the phase did not finish", AppTheme.Colors.textDisabled)
                phase.status == LoadStatus.RUNNING -> JudgementPill("running · no verdict yet", AppTheme.Colors.info)
                else -> {
                    val complete = phase.verdict.completeness == LoadReport.Completeness.COMPLETE
                    JudgementPill(
                        "completeness · " + if (complete) "complete" else "unanswered",
                        if (complete) AppTheme.Colors.success else AppTheme.Colors.error,
                    )
                    JudgementPill(
                        // The report's own words: "not applicable" means something different for a
                        // burst, a reactive phase and a run that stopped. See LoadReport.rateWord.
                        "rate · " + phase.rateWord,
                        when (phase.verdict.rate) {
                            LoadReport.RateVerdict.HELD -> AppTheme.Colors.success
                            LoadReport.RateVerdict.SHORTFALL -> AppTheme.Colors.warning
                            // Grey with the other unscored one: a ceiling is a line the phase sat under,
                            // which is neither a pass nor a fail. Only the word differs.
                            LoadReport.RateVerdict.CAPPED -> AppTheme.Colors.textDisabled
                            LoadReport.RateVerdict.NOT_APPLICABLE -> AppTheme.Colors.textDisabled
                        },
                    )
                    JudgementPill(
                        "tool · ${phase.verdict.tool.name.lowercase()}",
                        if (phase.tool.limited) AppTheme.Colors.error else AppTheme.Colors.success,
                    )
                }
            }
        }
    }
}

private const val RAIL_SHARE = 0.28f
private const val PANE_SHARE = 0.72f
private val TIMELINE_NAME_COL = 150.dp
private val TIMELINE_TIME_COL = 56.dp
private val TIMELINE_MIN = 3.dp
