package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.service.RunRecordMessages
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **The run set document: a viewer over one record directory.**
 *
 * By the time a twelve-scenario suite lands, the session grid holds the last entry's traffic and nothing
 * else — entry 2's setup cleared it, and even without a clear the session is a ring buffer. So eleven of
 * the twelve reports would point at messages that are not there. This tab reads them back off disk: the
 * entries down the left, and for the focused one its verdict, its variables, and **its own message grid**,
 * re-parsed from the bytes the record kept and tinted by what that entry decided.
 *
 * Two surfaces, two jobs, neither lying. The session grid shows *now*, tinted by the last entry that ran on
 * it. This shows *one entry*, from its record, and says so in its header — because a record's grid that
 * looked identical to the live one would be worse than none.
 */
@Composable
fun RunSetDocument(viewModel: FixMessageViewModel, doc: ScenarioDoc.RunSetView, modifier: Modifier = Modifier) {
    // Re-read per focused entry rather than held: the directory is the state, and a tab that cached it
    // would go stale the moment the set it is watching wrote its next entry.
    val active by viewModel.activeRunSet.collectAsState()
    val set = remember(doc.setId, active) { viewModel.runRecordStore.readSet(doc.setId) }
    if (set == null) {
        Text(
            "This run set is no longer on disk — the runs directory keeps the most recent sets, and this one " +
                "has been pruned.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = modifier.padding(8.dp),
        )
        return
    }
    Column(modifier = modifier.fillMaxSize().testTag("run-set-document")) {
        SetHeader(set)
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(modifier = Modifier.fillMaxSize()) {
            EntryList(
                set = set,
                focused = doc.entry,
                onFocus = { n ->
                    viewModel.updateDocument(doc.copy(entry = n))
                    viewModel.focusRunEntry(set.id, n)
                },
                modifier = Modifier.fillMaxHeight().weight(0.28f),
            )
            VerticalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
            EntryDetail(viewModel = viewModel, set = set, entry = doc.entry, modifier = Modifier.fillMaxHeight().weight(0.72f))
        }
    }
}

@Composable
private fun SetHeader(set: RunSet) {
    val colour =
        when (set.status) {
            RunSetStatus.PASSED -> AppTheme.Colors.success
            RunSetStatus.RUNNING -> AppTheme.Colors.info
            RunSetStatus.STOPPED -> AppTheme.Colors.textSecondary
            RunSetStatus.FAILED -> AppTheme.Colors.error
        }
    val elapsed = ((set.finishedAt ?: System.currentTimeMillis()) - set.startedAt).coerceAtLeast(0) / 1000
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(set.label, color = AppTheme.Colors.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(
            "${set.status.name.lowercase()} · ${set.passed}/${set.total} passed" +
                (if (set.failed > 0) " · ${set.failed} failed" else "") + " · ${elapsed}s",
            color = colour,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun EntryList(set: RunSet, focused: Int, onFocus: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
        set.entries.forEachIndexed { i, entry ->
            val n = i + 1
            val mark =
                when (entry.state) {
                    RunState.PASSED -> "✓"
                    RunState.FAILED -> "✗"
                    RunState.RUNNING -> "⟳"
                    RunState.STOPPED -> "⏹"
                    RunState.SKIPPED -> "–"
                    RunState.PENDING -> "·"
                }
            val tint =
                when (entry.state) {
                    RunState.PASSED -> AppTheme.Colors.success
                    RunState.FAILED -> AppTheme.Colors.error
                    RunState.RUNNING -> AppTheme.Colors.info
                    else -> AppTheme.Colors.textDisabled
                }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(if (n == focused) AppTheme.Colors.surfaceVariant else AppTheme.Colors.background)
                        .clickable(enabled = entry.record != null) { onFocus(n) }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .testTag("run-entry-$n"),
            ) {
                Text("$mark ", color = tint, fontSize = 11.sp)
                Text(
                    entry.scenarioName + if (entry.iteration > 1) " #${entry.iteration}" else "",
                    color = if (n == focused) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                entry.durationMs?.let { Text("${it}ms", color = AppTheme.Colors.textDisabled, fontSize = 10.sp) }
            }
        }
    }
}

/**
 * One entry, from its record: what it was, what it decided, what it held, and the bytes it decided on.
 *
 * The header names the entry **and its file**, so a reader is never in doubt about which run they are
 * looking at — the one confusion a second grid could cause, and the whole reason it says so.
 */
@Composable
private fun EntryDetail(viewModel: FixMessageViewModel, set: RunSet, entry: Int, modifier: Modifier = Modifier) {
    val record = remember(set.id, entry, set.entries.getOrNull(entry - 1)?.record) { viewModel.runRecordStore.readEntry(set.id, entry) }
    if (record == null) {
        Text(
            set.entries.getOrNull(entry - 1)?.note
                ?: "This entry has no record — it never ran, or its file could not be read.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = modifier.padding(8.dp),
        )
        return
    }
    val parsed = remember(record) { RunRecordMessages.of(record, viewModel.dictionary) }
    val appSettings = viewModel.appSettings
    Column(modifier = modifier) {
        Text(
            "${record.scenarioName} · entry ${record.entry} of ${set.total} · ${parsed.messages.size} messages · " +
                (set.entries.getOrNull(entry - 1)?.record ?: "") +
                (if (record.dropped > 0) " · ${record.dropped} dropped by the cap" else ""),
            color = AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("run-entry-header"),
        )
        Column(modifier = Modifier.fillMaxWidth().weight(0.45f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            val verdict = if (record.result.passed) "PASSED" else "FAILED"
            Text(
                "$verdict — ${record.result.steps.count { it.passed }}/${record.result.steps.size} steps" +
                    (record.durationMs?.let { " · ${it}ms" } ?: ""),
                color = if (record.result.passed) AppTheme.Colors.success else AppTheme.Colors.error,
                fontSize = 11.sp,
                modifier = Modifier.testTag("run-entry-verdict"),
            )
            record.result.steps.forEach { step -> StepLine(step) }
            if (record.result.variables.isNotEmpty()) {
                Text(
                    record.result.variables.joinToString("   ") { "${it.name}=${it.value}" },
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Box(modifier = Modifier.fillMaxWidth().weight(0.55f)) {
            if (parsed.messages.isEmpty()) {
                Text(
                    "This entry's record kept no messages" +
                        (if (record.dropped > 0) " — the cap dropped ${record.dropped}." else "."),
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                HierarchicalGridView(
                    messages = parsed.messages,
                    dictionary = viewModel.dictionary,
                    hideProtocolTags = appSettings.hideProtocolTags,
                    gridViewColumns = appSettings.gridViewColumns,
                    assertionResults = parsed.judged,
                    appSettings = appSettings,
                    modifier = Modifier.fillMaxSize().testTag("run-entry-grid"),
                )
            }
        }
    }
}

@Composable
private fun StepLine(step: StepResult) {
    val mark = if (step.passed) "ok" else "FAIL"
    val where = if (step.stepIndex < 0) step.kind else "step ${step.stepIndex + 1} ${step.kind}"
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            mark,
            color = if (step.passed) AppTheme.Colors.success else AppTheme.Colors.error,
            fontSize = 10.sp,
        )
        Text(
            "$where (${step.phase})" + (step.latencyMs?.let { " · ${it}ms" } ?: "") +
                (step.detail?.let { " — ${it.take(STEP_DETAIL)}" } ?: ""),
            color = AppTheme.Colors.textSecondary,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How much of a step's detail the entry's report shows before the grid takes over the explaining. */
private const val STEP_DETAIL = 160
