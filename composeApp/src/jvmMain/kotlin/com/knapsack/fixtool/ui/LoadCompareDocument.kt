package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.load.LoadComparison
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **Compare: two runs, one delta line, and a refusal when subtracting them would lie.**
 *
 * Its own tab rather than a second column in the document, because a document that sometimes has two
 * columns is two layouts pretending to be one.
 *
 * Both runs come off disk, so a run the CLI made overnight compares against one fired in the app an hour
 * ago and nothing is rerun. The picker is the same list Recent shows: choosing the other run here is what
 * "two runs selected in Recent" means in a menu that opens one document per click.
 */
@Composable
fun LoadCompareDocument(viewModel: FixMessageViewModel, doc: ScenarioDoc.LoadCompare, modifier: Modifier = Modifier) {
    val records = remember(doc.id) { viewModel.loadRecordStore.list() }
    val after = records.firstOrNull { it.id == doc.afterId }
    var beforeId by remember(doc.id) { mutableStateOf(doc.beforeId) }
    val before = records.firstOrNull { it.id == beforeId }

    Column(modifier = modifier.fillMaxSize().testTag("load-compare")) {
        if (after == null) {
            Empty("The run this comparison was opened from is no longer on disk.", "The loads directory keeps the most recent runs.")
            return@Column
        }
        CompareHeader(viewModel, after, before, records) { beforeId = it }
        if (before == null) {
            Empty("Pick the run to compare this one against.", "Any run in the loads directory, whichever fired it.")
            return@Column
        }
        val comparison = remember(before.id, after.id) { LoadComparison.of(before, after) }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 9.dp)) {
            if (!comparison.comparable) {
                comparison.blockers.forEach { Why(it) }
                // The three rows that decide are named first, so it is obvious which one to change if you
                // meant to compare something else.
                Table("What decides", comparison.deciding, before, after)
                Table("The rest, for context", comparison.context, before, after)
            } else {
                Table("What changed", comparison.deltas, before, after)
                Table("The rest, for context", comparison.deciding + comparison.context, before, after)
            }
        }
    }
}

@Composable
private fun CompareHeader(
    viewModel: FixMessageViewModel,
    after: LoadReport,
    before: LoadReport?,
    records: List<LoadReport>,
    onPick: (String) -> Unit,
) {
    val comparison = before?.let { remember(it.id, after.id) { LoadComparison.of(it, after) } }
    var note by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val (badge, tint) =
                when {
                    comparison == null -> "PICK A RUN" to AppTheme.Colors.textDisabled
                    !comparison.comparable -> "NOT COMPARABLE" to AppTheme.Colors.textSecondary
                    else -> "COMPARED" to AppTheme.Colors.info
                }
            Text(
                badge,
                color = tint,
                style = AppTheme.Type.head,
                modifier =
                    Modifier
                        .background(AppTheme.Colors.background, RoundedCornerShape(3.dp))
                        .border(1.dp, tint, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("compare-verdict"),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(after.label, color = AppTheme.Colors.text, style = AppTheme.Type.body, maxLines = 1)
                Text(
                    before?.let { "${stamp(it.startedAt)} → ${stamp(after.startedAt)}" } ?: "started ${stamp(after.startedAt)}",
                    color = AppTheme.Colors.textDisabled,
                    style = AppTheme.Type.meta,
                    maxLines = 1,
                )
            }
            OtherRunPicker(records.filter { it.id != after.id }, before, onPick)
            // Refuses rather than substituting: a run fired against a different template of the same name
            // is worse than not being able to fire it at all.
            SlimButton("Run this plan again", modifier = Modifier.testTag("compare-rerun"), onClick = {
                viewModel
                    .replanLoad(after)
                    .onSuccess {
                        note = ""
                        viewModel.startLoadRun(it)
                    }.onFailure { note = it.message.orEmpty() }
            })
        }
        if (note.isNotEmpty()) {
            Text(
                note,
                color = AppTheme.Colors.error,
                style = AppTheme.Type.meta,
                modifier = Modifier.padding(top = 3.dp).testTag("compare-rerun-refusal"),
            )
        }
    }
}

@Composable
private fun OtherRunPicker(options: List<LoadReport>, current: LoadReport?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier =
                Modifier
                    .height(24.dp)
                    .background(AppTheme.Colors.surface, RoundedCornerShape(2.dp))
                    .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(2.dp))
                    .clickable { open = true }
                    .testTag("compare-pick-other")
                    .padding(horizontal = 6.dp),
        ) {
            Text(
                current?.let {
                    "against ${stamp(it.startedAt)}"
                } ?: "pick the other run",
                color = AppTheme.Colors.text,
                style = AppTheme.Type.body,
                maxLines = 1,
            )
            Text("▾", color = AppTheme.Colors.textSecondary, style = AppTheme.Type.meta)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(text = { Text("no other run on disk", style = AppTheme.Type.meta) }, onClick = { open = false })
            }
            options.forEach { r ->
                DropdownMenuItem(
                    text = { Text("${stamp(r.startedAt)}  ${r.label}", style = AppTheme.Type.body) },
                    onClick = {
                        onPick(r.id)
                        open = false
                    },
                    modifier = Modifier.testTag("compare-other-${r.id}"),
                )
            }
        }
    }
}

/** Why these two cannot be subtracted, said before anything that looks like a number. */
@Composable
private fun Why(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(2.dp))
                .padding(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
    ) {
        Box(Modifier.width(2.dp).height(14.dp).background(AppTheme.Colors.error))
        Text("why", color = AppTheme.Colors.error, style = AppTheme.Type.meta, modifier = Modifier.width(22.dp))
        Text(text, color = AppTheme.Colors.error, style = AppTheme.Type.body, modifier = Modifier.testTag("compare-why"))
    }
}

@Composable
private fun Table(title: String, rows: List<LoadComparison.Row>, before: LoadReport, after: LoadReport) {
    if (rows.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Text(title, color = AppTheme.Colors.text, style = AppTheme.Type.head, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        Row {
            Head("")
            Head(stamp(before.startedAt))
            Head(stamp(after.startedAt))
            Head("delta")
        }
        rows.forEach { row ->
            Row(modifier = Modifier.testTag("compare-row-${row.label}")) {
                Cell(row.label, AppTheme.Colors.textSecondary, mono = false)
                Cell(row.before, AppTheme.Colors.text, mono = true)
                Cell(row.after, AppTheme.Colors.text, mono = true)
                Cell(row.delta, tintOf(row.direction), mono = false)
            }
        }
    }
}

/** Completeness up, latency down, tool counts down. Anything the tool cannot rank gets no colour. */
private fun tintOf(direction: LoadComparison.Direction): Color =
    when (direction) {
        LoadComparison.Direction.BETTER -> AppTheme.Colors.success
        LoadComparison.Direction.WORSE -> AppTheme.Colors.error
        LoadComparison.Direction.DIFFERS -> AppTheme.Colors.warning
        LoadComparison.Direction.SAME, LoadComparison.Direction.UNRANKED -> AppTheme.Colors.textDisabled
    }

@Composable
private fun Head(text: String) {
    Text(text, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta, maxLines = 1, modifier = Modifier.width(COL))
}

@Composable
private fun Cell(text: String, tint: Color, mono: Boolean) {
    Text(
        text,
        color = tint,
        style = if (mono) AppTheme.Type.body.copy(fontFamily = FontFamily.Monospace) else AppTheme.Type.body,
        maxLines = 1,
        modifier = Modifier.width(COL),
    )
}

@Composable
private fun Empty(headline: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("compare-empty")) {
        Text(headline, color = AppTheme.Colors.text, style = AppTheme.Type.body)
        Text(hint, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta)
    }
}

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun stamp(epochMillis: Long): String = STAMP.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private val COL = 210.dp
