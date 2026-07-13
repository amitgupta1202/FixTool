// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
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

/**
 * The reconcile view: the expectation against the message that actually arrived, side by side, with the
 * fix that fits each row — and the **only** surface in the app that authors an assertion from a failure.
 *
 * The message viewer diagnoses; this authors. The viewer renders the message that *arrived*, so it can
 * never be the place a failure is repaired: a tag the venue stopped sending has no row to click, and a
 * moved entry looks perfectly fine tag by tag — every value matches, nothing is red, and the step still
 * failed. This view sees both sides, so it can show the row that is missing and the field that is extra.
 *
 * Every edit is pushed to the step as it happens — nothing reaches *disk* until the workbench's Save — and
 * the diff re-runs after each, so a row that would now pass turns green in front of the author. It emits
 * per-edit rather than behind its own Save button because it used to do the latter, and clicking another
 * step in the list then threw the whole session's repairs away without a word.
 */
@Composable
fun ReconcileView(
    expectation: Expectation,
    actual: MessageView,
    dictionary: FixDictionary?,
    onChange: (Expectation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val original = remember(expectation) { expectation }
    var draft by remember(expectation) { mutableStateOf(expectation) }
    var editing by remember(expectation) { mutableStateOf<Int?>(null) }

    fun update(next: Expectation?) {
        if (next == null) return
        draft = next
        onChange(next)
    }

    val rows = ScenarioReconcile.rows(draft, actual, dictionary)
    // Only offered when there is a re-ordering that actually repairs the rows it is offered for, and moves
    // no assertion onto a field the author did not choose. A button that claims to fix and does not is
    // worse than no button — and one that fixes by re-aiming is worse than either.
    val reorder = ScenarioReconcile.acceptNewOrder(draft, actual)
    val blocks = if (reorder != null) ScenarioReconcile.movedBlocks(rows) else emptyList()

    Column(modifier = modifier.fillMaxWidth().testTag("reconcile-view")) {
        ReconcileHeader(
            rows = rows,
            strict = draft.mode == MatchMode.STRICT,
            onStrictChange = { update(draft.copy(mode = if (it) MatchMode.STRICT else MatchMode.OPEN)) },
            canAcceptOrder = reorder != null,
            onAcceptOrder = { update(reorder) },
            onReseed = { update(ScenarioReconcile.reseed(actual, dictionary, draft.mode).copy(golden = draft.golden)) },
            dirty = draft != original,
            onRevert = { update(original) },
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
            Text("expected (captured)", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.width(300.dp))
            Text("actual (this run)", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, row ->
                // A venue does not move a PartyRole, it moves a *party* — the delimiter and everything under
                // it. The block is bracketed and offered one fix, because three clicks would be three ways to
                // say one thing, each leaving the expectation momentarily wrong.
                blocks.firstOrNull { it.first == i }?.let { block ->
                    MovedBlockBanner(rows = block.map { rows[it] }, onAcceptOrder = { update(reorder) })
                }
                ReconcileRowView(
                    row = row,
                    dictionary = dictionary,
                    editing = editing == i,
                    onToggleEdit = { editing = if (editing == i) null else i },
                    onMatcherChange = { update(ScenarioReconcile.loosen(draft, row.index!!, it)) },
                    onAcceptActual = { update(ScenarioReconcile.acceptActual(draft, row.index!!, row.actual)) },
                    onAssertAbsent = { update(ScenarioReconcile.assertAbsent(draft, row.index!!)) },
                    canAssertAbsent = row.index?.let { ScenarioReconcile.canAssertAbsent(draft, actual, it) } == true,
                    onDrop = { update(ScenarioReconcile.drop(draft, row.index!!)) },
                    dropTakesWholeTag = row.index?.let { ScenarioReconcile.dropTakesWholeTag(draft, it) } == true,
                    onAddAssertion = { update(ScenarioReconcile.addAssertion(draft, actual, row.wireIndex!!, dictionary)) },
                )
            }
        }
    }
}

@Composable
private fun ReconcileHeader(
    rows: List<ScenarioReconcile.Row>,
    strict: Boolean,
    onStrictChange: (Boolean) -> Unit,
    canAcceptOrder: Boolean,
    onAcceptOrder: () -> Unit,
    onReseed: () -> Unit,
    dirty: Boolean,
    onRevert: () -> Unit,
) {
    val judged = rows.filter { it.judged }
    val failing = judged.count { !it.passed }
    val unexpected = rows.count { it.unasserted && !it.passed } // in STRICT an unmentioned tag is a failure
    val unknown = rows.count { it.unknown }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = summary(judged.size, failing, unexpected),
                color = if (judged.isNotEmpty() && failing == 0 && unexpected == 0) AppTheme.Colors.success else AppTheme.Colors.error,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                modifier = Modifier.testTag("reconcile-summary"),
            )
            if (canAcceptOrder) {
                SlimButton("Accept new order", onClick = onAcceptOrder, color = AppTheme.Colors.info, modifier = Modifier.padding(start = 10.dp))
            }
            SlimButton("Re-seed from this message", onClick = onReseed, color = AppTheme.Colors.textSecondary, modifier = Modifier.padding(start = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Checkbox(checked = strict, onCheckedChange = onStrictChange, modifier = Modifier.testTag("reconcile-strict"))
                Text("STRICT", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
            }
            Row(modifier = Modifier.weight(1f)) {}
            if (dirty) {
                SlimButton("Revert", onClick = onRevert, color = AppTheme.Colors.textSecondary)
            }
        }
        // A step that asserts nothing passes every run for ever while saying nothing about the venue. It is
        // the worst outcome this tool can produce, and it is a few Drops away from any failing expectation —
        // so it is never, under any arithmetic, allowed to be reported as a success.
        if (judged.isEmpty()) {
            Text(
                "⚠ this step now asserts nothing about the reply — it would pass every run without checking anything",
                color = AppTheme.Colors.warning,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier.testTag("reconcile-asserts-nothing"),
            )
        }
        if (unknown > 0) {
            Text(
                "· $unknown reference matcher${if (unknown == 1) "" else "s"} cannot be judged here — they resolve against a live run's scope",
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

private fun summary(judged: Int, failing: Int, unexpected: Int): String {
    if (judged == 0) return "✗ nothing is asserted"
    val extras = if (unexpected > 0) " · $unexpected unexpected tag${if (unexpected == 1) "" else "s"}" else ""
    return if (failing == 0 && unexpected == 0) {
        "✓ every assertion would now pass ($judged checked)"
    } else {
        "✗ $failing of $judged assertions fail$extras"
    }
}

/** One bracketed run of rows that dislocated together, with the single fix that puts them back. */
@Composable
private fun MovedBlockBanner(rows: List<ScenarioReconcile.Row>, onAcceptOrder: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.highlightOther).padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        val subject = if (rows.size == 1) "this field is" else "these ${rows.size} fields are"
        val tags = rows.joinToString(", ") { it.tag.toString() }
        Text("⌐ $subject in the reply ($tags), in a different place", color = AppTheme.Colors.warning, fontSize = 10.sp)
        SlimButton("Accept new order", onClick = onAcceptOrder, color = AppTheme.Colors.info, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ReconcileRowView(
    row: ScenarioReconcile.Row,
    dictionary: FixDictionary?,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onMatcherChange: (Matcher) -> Unit,
    onAcceptActual: () -> Unit,
    onAssertAbsent: () -> Unit,
    canAssertAbsent: Boolean,
    onDrop: () -> Unit,
    dropTakesWholeTag: Boolean,
    onAddAssertion: () -> Unit,
) {
    val background =
        when {
            row.unknown -> AppTheme.Colors.surfaceVariant
            row.unasserted && row.passed -> AppTheme.Colors.surfaceVariant
            row.passed -> AppTheme.Colors.surface
            else -> AppTheme.Colors.notificationErrorBackground
        }
    Column(modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(glyph(row), color = statusColor(row), fontSize = 11.sp, modifier = Modifier.width(16.dp))

            Text(
                text = if (row.unasserted) "—" else "${row.tag}${occurrenceSuffix(row)} ${row.name.take(14)}  ${row.expected.take(28)}",
                color = if (row.unasserted) AppTheme.Colors.textDisabled else AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.width(300.dp),
            )
            Text(
                text = row.actual?.let { "${row.tag} = $it" } ?: "—",
                color = if (row.actual == null) AppTheme.Colors.textDisabled else AppTheme.Colors.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.width(200.dp),
            )

            Row(modifier = Modifier.weight(1f)) {}
            RowActions(row, editing, onToggleEdit, onAcceptActual, onAssertAbsent, canAssertAbsent, onDrop, dropTakesWholeTag, onAddAssertion)
        }
        reason(row, dictionary)?.let {
            Text(it, color = statusColor(row), fontSize = 9.sp, modifier = Modifier.padding(start = 16.dp, top = 1.dp))
        }
        // The full matcher vocabulary, on the row that needs it. With only "Loosen → presence" on offer, a
        // price that drifted by a cent could be repaired *only* by pinning it exactly (red again next run)
        // or by not checking it at all. A numeric tolerance is what that failure actually calls for, and so
        // are oneOf, regex and temporal for the failures that call for them.
        if (editing && row.matcher != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, bottom = 2.dp)) {
                MatcherEditor(matcher = row.matcher, capturedValue = row.actual ?: "", onChange = onMatcherChange)
            }
        }
    }
}

/** Only the fixes that fit this row's failure. An action that cannot help is not offered. */
@Composable
private fun RowActions(
    row: ScenarioReconcile.Row,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onAcceptActual: () -> Unit,
    onAssertAbsent: () -> Unit,
    canAssertAbsent: Boolean,
    onDrop: () -> Unit,
    dropTakesWholeTag: Boolean,
    onAddAssertion: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            row.unasserted -> SlimButton("Add assertion", onClick = onAddAssertion, color = AppTheme.Colors.info)

            // A reference we cannot resolve here is not a failing row, and must never be offered "Accept
            // actual": one click would pin the assertion to this run's ClOrdID and quietly destroy the
            // cross-step binding it exists to express — green for ever, for the wrong reason.
            row.unknown -> {
                SlimButton(if (editing) "Close" else "Edit matcher", onClick = onToggleEdit, color = AppTheme.Colors.textSecondary)
                DropButton(onDrop, dropTakesWholeTag)
            }

            row.status == TagStatus.VALUE || row.status == TagStatus.INVALID -> {
                if (row.actual != null) SlimButton("Accept actual", onClick = onAcceptActual, color = AppTheme.Colors.success)
                SlimButton(if (editing) "Close" else "Edit matcher", onClick = onToggleEdit, color = AppTheme.Colors.textSecondary)
                DropButton(onDrop, dropTakesWholeTag)
            }

            row.status == TagStatus.MISSING -> {
                if (canAssertAbsent) SlimButton("Assert absent", onClick = onAssertAbsent, color = AppTheme.Colors.warning)
                DropButton(onDrop, dropTakesWholeTag)
            }

            // A moved row is fixed by the block's Accept-new-order, not per row: an up/down arrow here could
            // swap which occurrence two same-tag rows refer to, which is the whole failure this model exists
            // to prevent. Dropping it is still the author's to choose.
            row.status == TagStatus.MOVED -> DropButton(onDrop, dropTakesWholeTag)

            // A passing row is droppable too: an author may simply stop caring about a tag. What keeps that
            // honest is the header, which refuses to call a step with nothing left in it a success.
            else -> {
                SlimButton(if (editing) "Close" else "Edit matcher", onClick = onToggleEdit, color = AppTheme.Colors.textSecondary)
                DropButton(onDrop, dropTakesWholeTag)
            }
        }
    }
}

/**
 * Dropping one row of a repeated tag would promote its siblings — the second `452` becomes the first and
 * silently starts checking the executing firm's entry. So it takes the tag's rows with it, and the button
 * says so before the click rather than after.
 */
@Composable
private fun DropButton(onDrop: () -> Unit, takesWholeTag: Boolean) {
    SlimButton(
        text = if (takesWholeTag) "Drop tag (all occurrences)" else "Drop",
        onClick = onDrop,
        color = AppTheme.Colors.textSecondary,
    )
}

private fun glyph(row: ScenarioReconcile.Row): String =
    when {
        row.unknown -> "·"
        row.unasserted -> "+"
        row.passed -> "✓"
        row.status == TagStatus.MISSING -> "−"
        row.status == TagStatus.MOVED -> "↕"
        else -> "✗"
    }

private fun statusColor(row: ScenarioReconcile.Row): Color =
    when {
        row.unknown -> AppTheme.Colors.textSecondary
        row.unasserted -> if (row.passed) AppTheme.Colors.textSecondary else AppTheme.Colors.error
        row.passed -> AppTheme.Colors.success
        row.status == TagStatus.MOVED -> AppTheme.Colors.warning
        else -> AppTheme.Colors.error
    }

private fun occurrenceSuffix(row: ScenarioReconcile.Row): String = if (row.occurrence > 0) "#${row.occurrence + 1}" else ""

/** Why this row is the colour it is, in the words of what actually went wrong. */
private fun reason(row: ScenarioReconcile.Row, dictionary: FixDictionary?): String? {
    val name = dictionary?.getFieldName(row.tag)?.let { " ($it)" } ?: ""
    return when {
        row.unknown -> "a reference matcher — it resolves against a live run's scope, so it cannot be judged here"
        row.unasserted && !row.passed -> "STRICT: the reply carries ${row.tag}$name and the expectation never mentions it"
        row.unasserted -> "the reply carries ${row.tag}$name and the expectation never mentions it"
        row.passed -> null
        row.status == TagStatus.MISSING -> "the reply has nothing left at ${row.tag}$name for this row to check"
        row.status == TagStatus.MOVED ->
            "${row.tag}$name is in the reply, but not in this position — the rows must appear in the order the venue sends them"
        row.status == TagStatus.INVALID -> row.reason
        else -> "expected ${row.reason}, got ${row.actual}"
    }
}
