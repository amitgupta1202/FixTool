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
import androidx.compose.runtime.collectAsState
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
import com.knapsack.fixtool.model.load.LoadRecord
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
    // **The records are the ViewModel's state, not a read remembered here.** Keyed on `doc.id`, the list was
    // whatever was on disk the moment the tab opened: a run that finished afterwards never joined it, so the
    // picker could not offer the run you had just fired, and a workspace opened behind an open tab left the
    // previous box's runs in it.
    val configurations by viewModel.runConfigurations.collectAsState()
    val records = configurations.loadRecords
    val after = records.firstOrNull { it.id == doc.afterId }
    var beforeId by remember(doc.id) { mutableStateOf(doc.beforeId) }
    var pair by remember(doc.id, beforeId) { mutableStateOf(0) }
    val before = records.firstOrNull { it.id == beforeId }

    Column(modifier = modifier.fillMaxSize().testTag("load-compare")) {
        if (after == null) {
            Empty("The run this comparison was opened from is no longer on disk.", "The loads directory keeps the most recent runs.")
            return@Column
        }
        // Phases pair by position, and one phase each is today's Compare exactly. Comparing a set against a
        // single run pairs the run with phase 1, which is what somebody who tuned one burst and then built
        // the set around it wants to see. Hoisted above the header because the header's badge judges the
        // pairs, not the last phase.
        val pairs = remember(before?.id, after.id) { before?.let { pairsOf(it, after) }.orEmpty() }
        CompareHeader(viewModel, after, before, pairs, records) { beforeId = it }
        if (before == null) {
            Empty("Pick the run to compare this one against.", "Any run in the loads directory, whichever fired it.")
            return@Column
        }
        if (pairs.size > 1) PairRail(pairs, pair) { pair = it }
        val chosen = pairs.getOrElse(pair) { pairs.first() }
        val b = chosen.before
        val a = chosen.after
        if (b == null || a == null) {
            Empty(
                "This phase has no counterpart.",
                "One of the two sets has " + (if (a == null) "fewer" else "more") +
                    " phases, or it stopped before this one ran.",
            )
            return@Column
        }
        val comparison = remember(before.id, after.id, pair) { LoadComparison.of(b, a) }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 9.dp)) {
            if (!comparison.comparable) {
                comparison.blockers.forEach { Why(it) }
                // The three rows that decide are named first, so it is obvious which one to change if you
                // meant to compare something else.
                Table("What decides", comparison.deciding, b, a)
                Table("The rest, for context", comparison.context, b, a)
            } else {
                Table("What changed", comparison.deltas, b, a)
                Table("The rest, for context", comparison.deciding + comparison.context, b, a)
            }
        }
    }
}

/** One phase pair: the two reports, and the chip the rail carries for them. */
private data class Pair(
    val n: Int,
    val label: String,
    val before: LoadReport?,
    val after: LoadReport?,
)

/**
 * The pairs, by position.
 *
 * By position and not by label, because sets from the same file always pair right, two different files
 * pair by position, and the three deciding rows catch a mismatch, which is the refusal Compare already
 * has. Comparing a set against a single run therefore pairs the run with phase 1.
 */
private fun pairsOf(before: LoadRecord, after: LoadRecord): List<Pair> =
    (0 until maxOf(before.phases.size, after.phases.size)).map { i ->
        val b = before.phases.getOrNull(i)
        val a = after.phases.getOrNull(i)
        Pair(i + 1, a?.label ?: b?.label ?: "phase ${i + 1}", b, a)
    }

/** The rail: one chip per pair, carrying the headline delta or why there is not one. */
@Composable
private fun PairRail(pairs: List<Pair>, focused: Int, onFocus: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.background)
                .padding(horizontal = 9.dp, vertical = 5.dp)
                .testTag("compare-pair-rail"),
    ) {
        pairs.forEachIndexed { index, p ->
            val (text, tint) = chipOf(p)
            Column(
                modifier =
                    Modifier
                        .clickable { onFocus(index) }
                        .background(
                            if (index == focused) AppTheme.Colors.surfaceVariant else AppTheme.Colors.surface,
                            RoundedCornerShape(3.dp),
                        ).border(1.dp, if (index == focused) tint else AppTheme.Colors.border, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .testTag("compare-pair-${p.n}"),
            ) {
                Text("${p.n} · ${p.label}", color = AppTheme.Colors.text, style = AppTheme.Type.meta, maxLines = 1)
                Text(text, color = tint, style = AppTheme.Type.meta, maxLines = 1)
            }
        }
    }
}

/** "p99 −32%", "4 → 0 cleared", "not comparable", "no counterpart", or "same". */
@Suppress("ReturnCount")
private fun chipOf(p: Pair): kotlin.Pair<String, Color> {
    val b = p.before
    val a = p.after
    if (b == null || a == null) return "no counterpart" to AppTheme.Colors.textDisabled
    val comparison = LoadComparison.of(b, a)
    if (!comparison.comparable) return "not comparable" to AppTheme.Colors.warning
    val row = comparison.headline() ?: return "same" to AppTheme.Colors.textDisabled
    val text =
        if (row.label == "unanswered") {
            "${row.before} → ${row.after} ${row.delta}"
        } else {
            row.label.substringBefore(' ') + " " + row.delta
        }
    return text to tintOf(row.direction)
}

@Composable
@Suppress("LongParameterList")
private fun CompareHeader(
    viewModel: FixMessageViewModel,
    after: LoadRecord,
    before: LoadRecord?,
    pairs: List<Pair>,
    records: List<LoadRecord>,
    onPick: (String) -> Unit,
) {
    // **The badge reads the pairs**, because that is what the document below it draws. Reading
    // `before.only` against `after.only` judged the last phase of each and called a three-phase set
    // comparable or not on the strength of one of them.
    val counterparts = remember(before?.id, after.id) { pairs.filter { it.before != null && it.after != null } }
    val comparable =
        remember(before?.id, after.id) {
            counterparts.isNotEmpty() &&
                counterparts.all { LoadComparison.of(it.before!!, it.after!!).comparable }
        }
    var note by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val (badge, tint) =
                when {
                    before == null -> "PICK A RUN" to AppTheme.Colors.textDisabled
                    !comparable -> "NOT COMPARABLE" to AppTheme.Colors.textSecondary
                    pairs.size > 1 -> "COMPARED · ${counterparts.size} PHASE PAIRS" to AppTheme.Colors.info
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
            // is worse than not being able to fire it at all. A **set** goes back through its saved file,
            // because replanning one phase of it would rerun a third of the thing that regressed.
            SlimButton(
                "Run again",
                modifier = Modifier.testTag("compare-rerun"),
                onClick = { note = rerun(viewModel, after) },
            )
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

/**
 * **Fires this record again**, and returns the sentence to print when it could not. Empty on success.
 *
 * A set of several phases goes through its saved file by name, which is the only thing that can reproduce
 * all of it: the record's phases are reports, and replanning one of them would rerun a third of the run
 * that regressed. A set that never came from a file says so rather than running a fraction of itself.
 */
private fun rerun(viewModel: FixMessageViewModel, record: LoadRecord): String {
    if (record.phases.size <= 1) {
        return viewModel
            .replanLoad(record.only)
            .fold(
                onSuccess = {
                    viewModel.startLoadRun(it)
                    ""
                },
                onFailure = { it.message.orEmpty() },
            )
    }
    val name =
        record.set?.name
            ?: return "This set was not run from a saved set, so there is no file to run again. " +
                "Build it under Load sets… and run it from there."
    return when (val outcome = viewModel.startSavedLoadSet(name)) {
        is FixMessageViewModel.SavedLoadSetRun.Started -> ""
        is FixMessageViewModel.SavedLoadSetRun.Refused -> outcome.why
        is FixMessageViewModel.SavedLoadSetRun.CannotRunNow -> outcome.why
    }
}

@Composable
private fun OtherRunPicker(options: List<LoadRecord>, current: LoadRecord?, onPick: (String) -> Unit) {
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
                    text = {
                        val phases = if (r.phases.size > 1) " (${r.phases.size})" else ""
                        Text("${stamp(r.startedAt)}  ${r.label}$phases", style = AppTheme.Type.body)
                    },
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
