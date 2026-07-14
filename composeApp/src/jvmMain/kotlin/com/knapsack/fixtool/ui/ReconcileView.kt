// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioReconcile
import java.time.Instant

/**
 * The reconcile view: a failed Expect step as **a diff you can fix**.
 *
 * A failed step is two lists that disagree — what we captured, and what actually arrived. Aligned the way
 * `git diff` aligns two files, every kind of failure becomes one row with one obvious fix: a changed value,
 * a tag the venue added, a tag it dropped, an entry it reordered.
 *
 * This is the only surface in the app that authors an assertion from a failure. The message viewer
 * diagnoses; this authors. The viewer renders the message that *arrived*, so it can never repair a failure:
 * a tag the venue stopped sending has no row to click, and a moved entry looks perfectly fine tag by tag —
 * every value matches, nothing is red, and the step still failed. Only this view sees both sides.
 *
 * The organising idea is the verdict line: **shape versus behaviour**. Three red rows may all be the venue
 * reshaping its message and one of them the venue behaving differently, and only the last is a regression.
 * A reader should never have to work that out for themselves.
 */
@Composable
fun ReconcileView(
    expectation: Expectation,
    actual: MessageView,
    dictionary: FixDictionary?,
    onChange: (Expectation) -> Unit,
    modifier: Modifier = Modifier,
    /** "Step 2 · Expect · ExecutionReport (8) · session CLI" — the breadcrumb over the diff. */
    crumb: String = "",
    /** When the message arrived. Temporal rows are judged against THIS, never against "now" — see below. */
    actualAt: Instant? = null,
    /**
     * A **stable identity for the step** — the thing the staging state is keyed on.
     *
     * Not `expectation`: every fix replaces it, so keying on it destroyed history, draft and original on every
     * click. Not `crumb` either: it is built from the step's messageType and session, and BOTH are editable in
     * the editor while the reconcile view is open — so changing the session would have silently thrown away a
     * session's worth of repairs, which is the same bug wearing a different hat.
     */
    stepKey: Any = crumb,
) {
    // Keyed on the STEP — see stepKey. Keying on `expectation` destroyed the staging state on every click,
    // because every fix replaces it; keying on `crumb` destroyed it whenever the author edited the step's
    // session or message type, because the crumb is built from them.
    val original = remember(stepKey) { expectation }
    // Every fix is staged: the scenario file is not touched until the workbench saves, and Undo walks back
    // one fix at a time. The draft is pushed to the step as it changes so that navigating away — to look at
    // what the step before it sent — cannot silently destroy a session's worth of repairs.
    val history = remember(stepKey) { mutableStateListOf<StagedFix>() }
    var draft by remember(stepKey) { mutableStateOf(expectation) }
    var loosening by remember(stepKey) { mutableStateOf<Int?>(null) }

    fun stage(label: String, next: Expectation?) {
        if (next == null || next == draft) return
        history.add(StagedFix(label, draft))
        draft = next
        onChange(next)
    }

    fun undo() {
        val last = history.removeLastOrNull() ?: return
        draft = last.before
        onChange(last.before)
    }

    fun discard() {
        history.clear()
        draft = original
        onChange(original)
    }

    // Judged at the instant the message ARRIVED, not the instant this view was opened.
    //
    // A capture seeds TransactTime as "~now ±60s". The run passes. Two minutes later the engineer clicks
    // "Reconcile assertions…", the view re-evaluates that row against Instant.now(), and a timestamp that was
    // fine during the run is now hours outside its window — so the view reports a value change the venue never
    // made, tells the engineer it is a behaviour regression, and offers only Loosen and Drop. The scenario
    // permanently stops checking TransactTime. That is exactly the "a red that leads to a deleted assertion is
    // a green by a longer route" failure canAcceptActual's own doc warns about, manufactured by the view.
    val judgedAt = actualAt ?: Instant.now()
    val rows = ScenarioReconcile.rows(draft, actual, dictionary, now = { judgedAt })
    val reorder = ScenarioReconcile.acceptNewOrder(draft, actual)
    // A row is "moved" exactly when Accept-new-order would put it somewhere else — defined by the fix
    // itself, so there is no second opinion about it to disagree with the first. That is what lets a party
    // arriving out of order read as *one* entry that moved, rather than as six unrelated value mismatches.
    val movedRows = ScenarioReconcile.movedRows(draft, actual)
    val blocks = contiguousBlocks(rows, movedRows)

    Column(modifier = modifier.fillMaxWidth().border(1.dp, AppTheme.Colors.border).testTag("reconcile-view")) {
        StepHeader(
            crumb = crumb,
            strict = draft.mode == MatchMode.STRICT,
            failing = rows.any { !it.passed },
            staged = history.size,
            // Only offer the bulk button when there is a shape FAILURE to accept. Counting every unasserted
            // field as "a tag the venue added" made it live on a fully-passing OPEN step — where an unmentioned
            // tag is not a failure, it is the whole point of OPEN — and clicking it then asserted every field
            // the author had deliberately left alone.
            canAcceptShape =
                rows.any { ScenarioReconcile.isShapeChange(it) } ||
                    (draft.mode == MatchMode.STRICT && rows.any { it.unasserted && it.wireIndex != null }),
            onAcceptShape = { stage("Accepted every shape change", ScenarioReconcile.acceptEveryShapeChange(draft, actual, dictionary)) },
            onReseed = {
                stage("Re-seeded the step", ScenarioReconcile.reseed(draft, actual, dictionary))
            },
            onDiscard = ::discard,
            onStrictChange = { stage(if (it) "Switched to STRICT" else "Switched to OPEN", draft.copy(mode = if (it) MatchMode.STRICT else MatchMode.OPEN)) },
        )
        VerdictBar(rows, movedRows, blocks.size)
        DiffHeaderRow()

        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, row ->
                blocks.firstOrNull { it.first == i }?.let { block ->
                    MovedBlockHeader(
                        rows = block.map { rows[it] },
                        dictionary = dictionary,
                        onAcceptOrder = { stage("Accepted the new order", reorder) },
                    )
                }
                DiffRowView(
                    row = row,
                    moved = row.index in movedRows,
                    edited = isEdited(row, original, actual, dictionary),
                    loosening = loosening == i,
                    onLoosen = { loosening = if (loosening == i) null else i },
                    onMatcherChange = { stage("Loosened ${row.tag} to ${ExpectationDescribe.of(it)}", ScenarioReconcile.loosen(draft, row.index!!, it)) },
                    onAcceptActual = { stage("Accepted ${row.tag} = ${row.actual}", ScenarioReconcile.acceptActual(draft, row.index!!, row.actual)) },
                    onAssertAbsent = { stage("Asserting ${row.tag} absent", ScenarioReconcile.assertAbsent(draft, row.index!!)) },
                    canAssertAbsent = row.index?.let { ScenarioReconcile.canAssertAbsent(draft, actual, it) } == true,
                    onDrop = { stage("Dropped ${row.tag}", ScenarioReconcile.drop(draft, row.index!!)) },
                    dropTakesWholeTag = row.index?.let { ScenarioReconcile.dropTakesWholeTag(draft, it) } == true,
                    onAssertIt = { stage("Now asserted: ${row.tag} = ${row.actual}", ScenarioReconcile.addAssertion(draft, actual, row.wireIndex!!, dictionary)) },
                )
                if (blocks.any { it.last == i }) BlockEnd()
            }
        }
        Footer(staged = history.size, canUndo = history.isNotEmpty(), onUndo = ::undo)
    }
}

/** One staged fix: what it was called, and the expectation to go back to. */
private data class StagedFix(val label: String, val before: Expectation)

// ---------------------------------------------------------------------------------- header

@Composable
private fun StepHeader(
    crumb: String,
    strict: Boolean,
    failing: Boolean,
    staged: Int,
    canAcceptShape: Boolean,
    onAcceptShape: () -> Unit,
    onReseed: () -> Unit,
    onDiscard: () -> Unit,
    onStrictChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (crumb.isNotBlank()) {
            Text(crumb, color = AppTheme.Colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Chip(if (strict) "strict" else "open", AppTheme.Colors.warning, onClick = { onStrictChange(!strict) })
        if (failing) Chip("failed", AppTheme.Colors.error, tinted = true)
        Row(modifier = Modifier.weight(1f)) {}

        // Shape churn is the common case and it is tedious, not interesting. This takes the reorder, the
        // tags the venue added and the ones it stopped sending — and deliberately leaves every value
        // mismatch alone, because those are the rows that mean something.
        if (canAcceptShape) {
            SlimButton("Accept every shape change", onClick = onAcceptShape, color = AppTheme.Colors.textSecondary, modifier = Modifier.padding(end = 6.dp))
        }
        SlimButton("Re-seed step from this message", onClick = onReseed, color = AppTheme.Colors.textSecondary, modifier = Modifier.padding(end = 6.dp))
        SlimButton("Discard", onClick = onDiscard, color = AppTheme.Colors.textSecondary, enabled = staged > 0)
    }
}

@Composable
private fun Chip(label: String, color: Color, tinted: Boolean = false, onClick: (() -> Unit)? = null) {
    val box = Modifier
        .padding(end = 6.dp)
        .border(1.dp, color)
        .background(if (tinted) AppTheme.Colors.notificationErrorBackground else Color.Transparent)
        .padding(horizontal = 6.dp, vertical = 1.dp)
    Box(modifier = if (onClick != null) box.clickable(onClick = onClick) else box) {
        Text(label.uppercase(), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The line that separates **shape** from **behaviour**.
 *
 * Four red rows say nothing about whether the venue regressed or merely reshaped its message. This says it:
 * "1 value changed · 1 tag added · 1 tag missing · 1 entry moved — only the value change alters what this
 * scenario checks."
 */

/** What "Loosen" may turn a row into: weaker checks, never a different assertion. See MatcherEditor.types. */
private val LOOSENINGS = listOf("presence", "oneOf", "regex", "numeric", "temporal", "absent")

@Composable
private fun VerdictBar(rows: List<ScenarioReconcile.Row>, movedRows: Set<Int?>, movedEntries: Int) {
    fun isMoved(r: ScenarioReconcile.Row) = r.index != null && r.index in movedRows
    val judged = rows.count { it.judged }
    // A moved row is the message's SHAPE changing, not the venue's behaviour — that is the whole split.
    val values = rows.count { !it.passed && !it.unknown && !isMoved(it) && (it.status == TagStatus.VALUE || it.status == TagStatus.INVALID) }
    val behaviour = values
    val added = rows.count { it.unasserted && it.wireIndex != null }
    val missing = rows.count { !it.passed && !isMoved(it) && it.status == TagStatus.MISSING }
    val moved = movedEntries

    // Rows that cannot be judged here at all — a reference resolves against a live run's scope, and there is
    // none offline. They are not failures, but a headline that ignores them is a lie: one click of
    // "Loosen → reference" made a red row unjudgeable, dropped it out of every count, and the bar announced
    // "✓ every assertion would now pass" over an expectation that asserts a ClOrdID equals an OrdStatus.
    val unknown = rows.count { it.unknown }

    // ...and any failing row none of the buckets above caught. A row reported MOVED for which there is no
    // safe re-ordering has no block bracket, no Accept-new-order and no per-row fix — it fell out of the
    // count entirely, and the bar declared success over a step that fails.
    val bucketed = setOf(TagStatus.VALUE, TagStatus.INVALID, TagStatus.MISSING)
    val unresolved = rows.count { !it.passed && !it.unasserted && !it.unknown && !isMoved(it) && it.status !in bucketed }
    val attention = values + added + missing + moved + unresolved

    val parts = buildList {
        if (values > 0) add("$values value${plural(values)} changed")
        if (added > 0) add("$added tag${plural(added)} added")
        if (missing > 0) add("$missing tag${plural(missing)} missing")
        if (moved > 0) add("$moved entr${if (moved == 1) "y" else "ies"} moved")
        if (unresolved > 0) add("$unresolved out of order")
        if (unknown > 0) add("$unknown not checkable here")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(if (attention > 0) AppTheme.Colors.notificationErrorBackground else AppTheme.Colors.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = headline(judged, attention, added, unknown),
            color = if (attention > 0 || added > 0) AppTheme.Colors.error else if (judged == 0) AppTheme.Colors.warning else AppTheme.Colors.success,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.testTag("reconcile-summary"),
        )
        if (parts.isNotEmpty()) {
            Text(" │ ", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
            Text(parts.joinToString(" · "), color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
            Text(" │ ", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
            Text(
                text = shapeVersusBehaviour(behaviour),
                color = if (behaviour > 0) AppTheme.Colors.error else AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.testTag("reconcile-shape-or-behaviour"),
            )
        }
    }
}

private fun headline(judged: Int, attention: Int, added: Int, unknown: Int): String {
    // A step that asserts nothing passes every run for ever while checking nothing. It is one Drop away from
    // any failing expectation, and it is never allowed to read as a success.
    if (judged == 0) return "⚠ nothing is asserted — this step would pass every run without checking anything"
    val needing = attention + added
    if (needing > 0) return "$needing of $judged rows need attention"
    // Green, but only about what was actually checked. A row this view cannot judge is not a row that passed.
    if (unknown > 0) {
        return "✓ every checked assertion would now pass ($judged checked · $unknown only verifiable on a run)"
    }
    return "✓ every assertion would now pass ($judged checked)"
}

private fun shapeVersusBehaviour(behaviour: Int): String =
    when (behaviour) {
        0 -> "nothing here changes what this scenario checks — it is all shape"
        1 -> "only the value change alters what this scenario checks"
        else -> "$behaviour value changes alter what this scenario checks"
    }

private fun plural(n: Int) = if (n == 1) "" else "s"

// ---------------------------------------------------------------------------------- the table

private val GUT = 20.dp
private val TAG = 150.dp
private val EXPECTED = 210.dp
private val ACTUAL = 180.dp
private val STATUS = 84.dp

@Composable
private fun DiffHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        HeadCell("", GUT)
        HeadCell("tag", TAG)
        HeadCell("expected (captured)", EXPECTED)
        HeadCell("actual (this run)", ACTUAL)
        HeadCell("status", STATUS)
        Text("fix", color = AppTheme.Colors.textDisabled, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HeadCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(text, color = AppTheme.Colors.textDisabled, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(width))
}

/**
 * The runs of rows that travelled together — one bracket per entry that moved.
 *
 * A party's fields do not all *move*: when the venue swaps two parties, the PartyIDSource of each is `D`
 * either way, so those rows pass where they stand and only the PartyID and PartyRole change position. Left
 * as-is that reads as three separate brackets with a passing row wedged between them — three decisions
 * offered for what is one fact. So a run is carried across the passing rows riding along inside it.
 *
 * Only across *passing* rows, and only a couple of them: a row that fails is a fact of its own and must never
 * be swallowed into somebody else's bracket.
 */
private const val MAX_RIDERS = 2

private fun contiguousBlocks(rows: List<ScenarioReconcile.Row>, moved: Set<Int?>): List<IntRange> {
    fun isMoved(i: Int) = rows[i].index != null && rows[i].index in moved
    val blocks = mutableListOf<IntRange>()
    var i = 0
    while (i < rows.size) {
        if (!isMoved(i)) { i++; continue }
        var end = i
        var scan = i + 1
        var riders = 0
        while (scan < rows.size) {
            when {
                isMoved(scan) -> { end = scan; riders = 0 }
                rows[scan].passed && riders < MAX_RIDERS -> riders++
                else -> break
            }
            scan++
        }
        blocks += i..end
        i = end + 1
    }
    return blocks
}

@Composable
private fun DiffRowView(
    row: ScenarioReconcile.Row,
    moved: Boolean,
    edited: Boolean,
    loosening: Boolean,
    onLoosen: () -> Unit,
    onMatcherChange: (Matcher) -> Unit,
    onAcceptActual: () -> Unit,
    onAssertAbsent: () -> Unit,
    canAssertAbsent: Boolean,
    onDrop: () -> Unit,
    dropTakesWholeTag: Boolean,
    onAssertIt: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(rowBackground(row, moved, edited)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(glyph(row, moved), color = statusColor(row, moved), fontSize = 11.sp, modifier = Modifier.width(GUT))

            Row(modifier = Modifier.width(TAG)) {
                Text("${row.tag}${occurrenceSuffix(row)}", color = AppTheme.Colors.info, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text(" ${row.name.take(18)}", color = AppTheme.Colors.textDisabled, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
            }
            Text(
                text = if (row.unasserted) "not asserted" else row.expected,
                color = if (row.unasserted) AppTheme.Colors.textDisabled else AppTheme.Colors.textSecondary,
                fontStyle = if (row.unasserted) FontStyle.Italic else FontStyle.Normal,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.width(EXPECTED),
            )
            Text(
                text = row.actual ?: "not sent",
                color = actualColor(row),
                fontStyle = if (row.actual == null) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (row.status == TagStatus.VALUE && !row.passed && !moved) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.width(ACTUAL),
            )
            Text(
                text = statusWord(row, moved, edited),
                color = if (edited) AppTheme.Colors.success else statusColor(row, moved),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(STATUS),
            )
            RowFixes(row, moved, loosening, onLoosen, onAcceptActual, onAssertAbsent, canAssertAbsent, onDrop, dropTakesWholeTag, onAssertIt)
        }
        if (loosening && row.matcher != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = GUT + 4.dp, top = 3.dp, bottom = 3.dp)) {
                // Loosen offers loosenings. Not `exact` — that is what "Accept actual" is, and it re-seeds
                // rather than flattening. Not `reference` — see MatcherEditor.types.
                MatcherEditor(
                    matcher = row.matcher,
                    capturedValue = row.actual ?: "",
                    onChange = onMatcherChange,
                    types = LOOSENINGS,
                )
            }
        }
    }
}

/** Only the fixes that fit this row's failure. An action that cannot help it is not offered. */
@Composable
private fun RowFixes(
    row: ScenarioReconcile.Row,
    moved: Boolean,
    loosening: Boolean,
    onLoosen: () -> Unit,
    onAcceptActual: () -> Unit,
    onAssertAbsent: () -> Unit,
    canAssertAbsent: Boolean,
    onDrop: () -> Unit,
    dropTakesWholeTag: Boolean,
    onAssertIt: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            // A moved row is fixed by its block's one Accept-new-order, never per row: a per-row arrow could
            // swap which occurrence two same-tag rows refer to, which is the failure this model exists to
            // prevent. It is one decision, not three.
            moved -> Unit

            // A tag the venue sent that we do not assert. In OPEN it is harmless; in STRICT it fails the
            // step. Either way the author's two answers are: start checking it, or say you meant not to.
            row.unasserted -> {
                SlimButton("Assert it", onClick = onAssertIt, color = AppTheme.Colors.info)
                SlimButton("Ignore", onClick = {}, color = AppTheme.Colors.textDisabled, enabled = false)
            }

            // A reference resolves against a live run's scope; there is none here. It is not failing, and it
            // must never be offered "Accept actual" — one click would pin the assertion to this run's ClOrdID
            // and destroy the cross-step binding it exists to express.
            row.unknown -> SlimButton("Loosen ▾", onClick = onLoosen, color = AppTheme.Colors.textSecondary)

            row.passed -> Unit

            row.status == TagStatus.VALUE || row.status == TagStatus.INVALID -> {
                // Not every failing row has something to *accept*. A temporal row's failure is about a moment,
                // not a value, so accepting it pins the scenario to a timestamp that will never come again —
                // red on every run, until the author deletes the assertion to make it stop. ScenarioReconcile
                // owns that rule (canAcceptActual) so the button and the engine cannot come to differ about it.
                val matcher = row.matcher
                if (row.actual != null && (matcher == null || ScenarioReconcile.canAcceptActual(matcher))) {
                    SlimButton("Accept actual", onClick = onAcceptActual, color = AppTheme.Colors.success)
                }
                SlimButton(if (loosening) "Loosen ▴" else "Loosen ▾", onClick = onLoosen, color = AppTheme.Colors.textSecondary)
                DropButton(onDrop, dropTakesWholeTag)
            }

            row.status == TagStatus.MISSING -> {
                if (canAssertAbsent) SlimButton("Assert absent", onClick = onAssertAbsent, color = AppTheme.Colors.warning)
                DropButton(onDrop, dropTakesWholeTag)
            }

            // A moved row is fixed by its block's Accept-new-order, never per row: a per-row arrow could swap
            // which occurrence two same-tag rows refer to, which is the failure this model exists to prevent.
            row.status == TagStatus.MOVED -> Unit

            else -> DropButton(onDrop, dropTakesWholeTag)
        }
    }
}

/**
 * Dropping one row of a repeated tag would promote its siblings — the second `452` becomes the first and
 * silently starts checking the executing firm's entry. So it takes the tag's rows with it, and says so.
 */
@Composable
private fun DropButton(onDrop: () -> Unit, takesWholeTag: Boolean) {
    SlimButton(
        text = if (takesWholeTag) "Drop tag" else "Drop",
        onClick = onDrop,
        color = AppTheme.Colors.error,
    )
}

/**
 * A moved entry, bracketed as one thing with one fix.
 *
 * A venue does not move a PartyRole; it moves a *party* — the delimiter and everything under it, three to
 * six tags travelling together. Fixing that row by row would be several clicks to express one fact, and each
 * click would leave the expectation in a state that is momentarily wrong.
 */
@Composable
private fun MovedBlockHeader(rows: List<ScenarioReconcile.Row>, dictionary: FixDictionary?, onAcceptOrder: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.Colors.selectionSecondary)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text("⇅", color = AppTheme.Colors.warning, fontSize = 11.sp, modifier = Modifier.width(GUT))
        val travelled = rows.filter { it.status == TagStatus.MOVED || !it.passed }.ifEmpty { rows }
        Text(
            text = if (travelled.size == 1) "Field moved" else "Entry moved",
            color = AppTheme.Colors.warning,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        val names = travelled.map { "${it.tag}${dictionary?.getFieldName(it.tag)?.let { n -> " $n" } ?: ""}" }.distinct()
        Text(
            text = "${names.joinToString(", ")} — same tags, same values, different position",
            color = AppTheme.Colors.textSecondary,
            fontSize = 10.sp,
            maxLines = 1,
        )
        Row(modifier = Modifier.weight(1f)) {}
        SlimButton("Accept new order", onClick = onAcceptOrder, color = AppTheme.Colors.info)
    }
}

/** Closes the bracket under a moved block, so the eye reads it as one unit. */
@Composable
private fun BlockEnd() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppTheme.Colors.warning))
}

@Composable
private fun Footer(staged: Int, canUndo: Boolean, onUndo: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("$staged", color = AppTheme.Colors.text, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.testTag("reconcile-staged"))
        Text(if (staged == 1) " fix staged" else " fixes staged", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        Text(" · nothing is written to the scenario until you save", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
        Row(modifier = Modifier.weight(1f)) {}
        SlimButton("Undo last", onClick = onUndo, color = AppTheme.Colors.textSecondary, enabled = canUndo)
    }
}

// ------------------------------------------------------------------------------- row rendering

/** True when this row's assertion is not what the author started with — the staged-fix tint. */
private fun isEdited(
    row: ScenarioReconcile.Row,
    original: Expectation,
    actual: MessageView,
    dictionary: FixDictionary?,
): Boolean {
    if (row.matcher == null) return false
    val before = ScenarioReconcile.rows(original, actual, dictionary)
        .firstOrNull { it.tag == row.tag && it.occurrence == row.occurrence && !it.unasserted }
    return before == null || before.matcher != row.matcher
}

private fun rowBackground(row: ScenarioReconcile.Row, moved: Boolean, edited: Boolean): Color =
    when {
        edited -> AppTheme.Colors.notificationSuccessBackground
        moved -> AppTheme.Colors.highlightOther
        row.unknown -> AppTheme.Colors.surface
        row.unasserted -> AppTheme.Colors.selectionSecondary
        row.passed -> AppTheme.Colors.surface
        row.status == TagStatus.MISSING || row.status == TagStatus.MOVED -> AppTheme.Colors.highlightOther
        else -> AppTheme.Colors.notificationErrorBackground
    }

private fun statusWord(row: ScenarioReconcile.Row, moved: Boolean, edited: Boolean): String =
    when {
        edited -> "staged"
        moved -> "moved"
        row.unknown -> "needs a run"
        row.unasserted -> "added"
        row.passed -> "ok"
        row.status == TagStatus.MISSING -> "missing"
        row.status == TagStatus.MOVED -> "moved"
        row.status == TagStatus.INVALID -> "invalid"
        else -> "value"
    }

private fun glyph(row: ScenarioReconcile.Row, moved: Boolean): String =
    when {
        moved -> "⇅"
        row.unknown -> "·"
        row.unasserted -> "+"
        row.passed -> "✓"
        row.status == TagStatus.MISSING -> "−"
        row.status == TagStatus.MOVED -> "⇅"
        else -> "✗"
    }

private fun statusColor(row: ScenarioReconcile.Row, moved: Boolean): Color =
    when {
        moved -> AppTheme.Colors.warning
        row.unknown -> AppTheme.Colors.textDisabled
        row.unasserted -> AppTheme.Colors.info
        row.passed -> AppTheme.Colors.success
        row.status == TagStatus.MISSING || row.status == TagStatus.MOVED -> AppTheme.Colors.warning
        else -> AppTheme.Colors.error
    }

private fun actualColor(row: ScenarioReconcile.Row): Color =
    when {
        row.actual == null -> AppTheme.Colors.textDisabled
        row.status == TagStatus.VALUE && !row.passed -> AppTheme.Colors.error
        else -> AppTheme.Colors.text
    }

private fun occurrenceSuffix(row: ScenarioReconcile.Row): String = if (row.occurrence > 0) "#${row.occurrence + 1}" else ""

/** Matcher description for a staged-fix label, without dragging the evaluator into the UI's imports. */
private object ExpectationDescribe {
    fun of(matcher: Matcher): String = com.knapsack.fixtool.service.ExpectationEvaluator.describe(matcher)
}
