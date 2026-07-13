// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.MessageView

/**
 * One editable row in the [ExpectationBuilder].
 *
 * There is no group path. A row's **position in the list** is its address: the k-th row for a tag
 * asserts the k-th occurrence of that tag. So a message with four party entries produces four
 * PartyRole rows — not one, as it did when the builder de-duplicated them and left three entries
 * silently unchecked — and the order of this list must never be sorted or otherwise disturbed.
 */
data class FieldDraft(
    val tag: Int,
    val name: String,
    val value: String,
    val included: Boolean,
    val matcher: Matcher,
    /** The captured value's dictionary description (e.g. "2" → "FILLED"), for FIX newcomers. */
    val description: String = "",
    /** Which occurrence of [tag] this row is, 0-based — shown only when the tag repeats. */
    val occurrence: Int = 0,
)

/** Builds the initial per-tag drafts for a captured message: dictionary-seeded matchers (capture). */
object ExpectationDrafts {
    private fun describe(dictionary: FixDictionary?, tag: Int, value: String): String =
        dictionary?.getFieldValueDescription(tag, value)?.takeIf { it != value } ?: ""

    /**
     * Rebuild editable drafts from a previously saved expectation. Rows come from the **golden ∪ the
     * asserted fields**: a tag the author unticked earlier reappears unticked (with a freshly seeded
     * matcher) instead of vanishing forever — unticking is not a one-way door.
     *
     * Which golden row each saved assertion belongs to is decided by [ExpectationEvaluator.align] — the
     * same pairing the runner uses, against the golden message instead of a live reply. Matching by tag
     * alone would tick the wrong row the moment a tag repeated: with the first `452` unticked, the one
     * saved `452` would re-attach itself to the first party entry rather than the second, and the author
     * would reopen their scenario to find it asserting a different entry than the one they left.
     */
    fun fromExpectation(expectation: Expectation, dictionary: FixDictionary?): List<FieldDraft> {
        val seeds =
            expectation.golden
                ?.let { ExpectationSeeder.seedDetailed(FixMessageHelper.parseFixMessage(it), dictionary) }
                ?: emptyList()
        val goldenWire = seeds.map { it.field.tag to it.capturedValue }

        // align() already emits its rows in reading order — each saved row where it sits, with the
        // golden fields nothing claimed interleaved at the position they occupy. That merged order *is*
        // the draft list, and taking it wholesale is what keeps the saved order intact. Appending the
        // unpaired rows at the end instead (the obvious way to build this) quietly rewrote the order of
        // the expectation the moment the author touched anything — and under this model the order is the
        // assertion.
        val occurrences = mutableMapOf<Int, Int>()
        return ExpectationEvaluator.align(expectation, goldenWire).map { a ->
            val goldenValue = a.wireIndex?.let { seeds[it].capturedValue } ?: ""
            val seededMatcher = a.wireIndex?.let { seeds[it].field.matcher }
            FieldDraft(
                tag = a.tag,
                name = dictionary?.getFieldName(a.tag) ?: "",
                value = goldenValue,
                included = a.row != null,
                matcher = a.row?.matcher ?: seededMatcher ?: Matcher.Presence,
                description = describe(dictionary, a.tag, goldenValue),
                occurrence = occurrences.merge(a.tag, 1) { n, _ -> n + 1 }!! - 1,
            )
        }
    }
}

/**
 * Authors an [Expectation] from a captured message: each row is a tag with an editable matcher chip
 * ([MatcherEditor]) and a **live green/red preview** against the golden message.
 *
 * This is the surface for an expectation that has not been run. A step that *failed* a run is repaired
 * in the [ReconcileView] instead, which can see the message that arrived alongside the expectation — and
 * so can show the row the venue stopped sending, which this builder has no way to render. Matchers are
 * pre-seeded from the dictionary, and **Verify generalizes** re-checks the whole expectation against a
 * second instance so an over-specified field (e.g. an `exact` timestamp) is flagged.
 */
@Composable
fun ExpectationBuilder(
    messageType: String,
    initialFields: List<FieldDraft>,
    goldenView: MessageView?,
    secondView: MessageView? = null,
    initialMode: MatchMode = MatchMode.OPEN,
    onSave: ((Expectation) -> Unit)? = null,
    onChange: ((Expectation) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val drafts = remember { mutableStateListOf<FieldDraft>().apply { addAll(initialFields) } }
    var strict by remember { mutableStateOf(initialMode == MatchMode.STRICT) }
    var overSpecified by remember { mutableStateOf<Set<Int>?>(null) }

    // Read from `drafts` at call time, never from a list captured during composition: closing over a
    // snapshot meant every onChange reported the state *before* the edit that triggered it, so the
    // author's last matcher change was silently dropped on save.
    fun includedRows(): List<IndexedValue<FieldDraft>> = drafts.withIndex().filter { it.value.included }

    fun expectation(): Expectation =
        Expectation(
            fields = includedRows().map { FieldExpectation(it.value.tag, it.value.matcher) },
            messageType = messageType,
            mode = if (strict) MatchMode.STRICT else MatchMode.OPEN,
        )

    fun notifyChange() {
        onChange?.invoke(expectation())
    }

    /**
     * Ticking is per **tag**, not per row, and that is a correctness rule rather than a convenience.
     *
     * A row's position among its same-tag siblings *is* its occurrence — the second `452` row asserts
     * the second `452`. So unticking the first one does not "keep the second": it promotes the survivor
     * to occurrence 0, and the saved expectation now asserts the *executing* firm's role while the row
     * on screen still reads `#2`. That is a false green authored by the tool, and it is precisely the
     * failure this model exists to make impossible.
     *
     * There is no way to say "check only the k-th occurrence" — the model cannot express it, so the UI
     * must not pretend to. To ignore one entry while checking another, keep both rows and loosen the one
     * you do not care about (`presence`). Its position is what addresses the other.
     */
    fun setIncluded(tag: Int, included: Boolean) {
        drafts.forEachIndexed { i, d -> if (d.tag == tag) drafts[i] = d.copy(included = included) }
        notifyChange()
    }

    // Evaluated once for the whole expectation, never row by row. A single-row evaluation pairs with
    // the *first* occurrence of its tag, so every repeated row — the second party's PartyRole, the
    // third leg's Symbol — would have been previewed against the first entry's value, and shown the
    // author a green dot for an assertion that checks a different field than the one on the row.
    val current = expectation()
    val included = includedRows()
    val livePasses = previewPasses(goldenView, current, included)
    // STRICT's unexpected-tag failures carry no row index, so they never reach a row's dot. Counting
    // them here is what stops the builder swearing an all-green preview for an expectation that cannot
    // pass its own golden message — untick one row in STRICT and it fails on that very tag.
    val unlistedInStrict =
        if (goldenView != null && strict) {
            ExpectationEvaluator.evaluate(goldenView, current).count { !it.passed && it.index == null }
        } else {
            0
        }

    Column(modifier = modifier.fillMaxWidth()) {
        BuilderHeader(
            messageType = messageType,
            strict = strict,
            onStrictChange = {
                strict = it
                notifyChange()
            },
            canVerify = secondView != null,
            onVerify = {
                val results = ExpectationEvaluator.evaluate(secondView!!, expectation())
                overSpecified = results.filterNot { it.passed }.map { it.tag }.toSet()
            },
            overSpecified = overSpecified,
            unlistedInStrict = unlistedInStrict,
            onSave = onSave?.let { save -> { save(expectation()) } },
        )
        // Plain Column (not LazyColumn) so this builder can be embedded inside scrollable parents
        // (the step detail panel) without the "infinity max height" nesting crash.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            val repeated = drafts.groupingBy { it.tag }.eachCount().filterValues { it > 1 }.keys
            drafts.forEachIndexed { index, draft ->
                FieldDraftRow(
                    draft = draft,
                    showOccurrence = draft.tag in repeated,
                    livePass = livePasses[index],
                    overSpecified = overSpecified?.contains(draft.tag) == true,
                    // The tick belongs to the tag; the matcher belongs to the row. See setIncluded.
                    onIncludedChange = { setIncluded(draft.tag, it) },
                    onChange = {
                        drafts[index] = it
                        notifyChange()
                    },
                )
            }
        }
    }
}

/**
 * Pass/fail per **draft index**, by evaluating the expectation whole and mapping each result back to
 * the row it came from. Null = not asserted, or a reference matcher (which resolves against a live
 * run's scope — offline there is none, and an honest "unknown" beats a misleading red dot).
 */
private fun previewPasses(
    view: MessageView?,
    expectation: Expectation,
    included: List<IndexedValue<FieldDraft>>,
): Map<Int, Boolean?> {
    if (view == null) return emptyMap()
    val results = ExpectationEvaluator.evaluate(view, expectation)
    return results
        .mapNotNull { r -> r.index?.let { it to r } }
        .filter { (rowIndex, _) -> rowIndex in included.indices }
        .associate { (rowIndex, r) ->
            val draftIndex = included[rowIndex].index
            val matcher = included[rowIndex].value.matcher
            draftIndex to if (matcher is Matcher.Reference) null else r.passed
        }
}

@Composable
private fun BuilderHeader(
    messageType: String,
    strict: Boolean,
    onStrictChange: (Boolean) -> Unit,
    canVerify: Boolean,
    onVerify: () -> Unit,
    overSpecified: Set<Int>?,
    onSave: (() -> Unit)?,
    unlistedInStrict: Int = 0,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Expectation · msg $messageType", color = AppTheme.Colors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Checkbox(checked = strict, onCheckedChange = onStrictChange, modifier = Modifier.testTag("strict-mode"))
            Text("STRICT", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
        SlimButton("Verify generalizes", onClick = onVerify, enabled = canVerify, modifier = Modifier.padding(start = 12.dp))
        if (onSave != null) {
            SlimButton("Save expectation", onClick = onSave, color = AppTheme.Colors.success, modifier = Modifier.padding(start = 8.dp))
        }
        if (overSpecified != null) {
            val msg = if (overSpecified.isEmpty()) "✓ generalizes" else "⚠ ${overSpecified.size} over-specified"
            val color = if (overSpecified.isEmpty()) AppTheme.Colors.success else AppTheme.Colors.warning
            Text(msg, color = color, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
        }
        // In STRICT, an unticked row is not "ignored" — it becomes an *unexpected tag*, and the step
        // fails on it. Those failures carry no row, so no dot on any row can show them, and the preview
        // would otherwise read all-green for an expectation that cannot even pass its own golden.
        if (unlistedInStrict > 0) {
            Text(
                "⚠ $unlistedInStrict unticked tag${if (unlistedInStrict == 1) "" else "s"} — STRICT fails on each",
                color = AppTheme.Colors.warning,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 10.dp).testTag("strict-unlisted"),
            )
        }
    }
}

@Composable
private fun FieldDraftRow(
    draft: FieldDraft,
    livePass: Boolean?,
    overSpecified: Boolean,
    onChange: (FieldDraft) -> Unit,
    onIncludedChange: (Boolean) -> Unit,
    showOccurrence: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant).padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Ticks the whole *tag*, every occurrence of it — see ExpectationBuilder.setIncluded. To
            // stop caring about one entry, keep its row and loosen it; its position addresses the others.
            Checkbox(checked = draft.included, onCheckedChange = onIncludedChange)
            PreviewDot(livePass, overSpecified)
            Text("${draft.tag}", color = AppTheme.Colors.tagNumber, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(40.dp))
            // Which entry this row is. Without it, four identical "452 PartyRole" rows are four rows the
            // author cannot tell apart — and the one they mean to edit is a guess.
            Text(
                text = if (showOccurrence) "#${draft.occurrence + 1}" else "",
                color = AppTheme.Colors.groupTag,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.width(24.dp),
            )
            Text(draft.name.take(18), color = AppTheme.Colors.fieldName, fontSize = 10.sp, modifier = Modifier.width(110.dp))
            if (draft.included) {
                MatcherEditor(matcher = draft.matcher, capturedValue = draft.value, onChange = { onChange(draft.copy(matcher = it)) })
                if (draft.description.isNotEmpty()) {
                    Text("(${draft.description})", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
                Row(modifier = Modifier.weight(1f)) {}
            } else {
                Text(draft.value.take(24), color = AppTheme.Colors.textDisabled, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                if (draft.description.isNotEmpty()) {
                    Text("(${draft.description})", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun PreviewDot(livePass: Boolean?, overSpecified: Boolean) {
    val color = when {
        overSpecified -> AppTheme.Colors.warning
        livePass == true -> AppTheme.Colors.success
        livePass == false -> AppTheme.Colors.error
        else -> AppTheme.Colors.textDisabled
    }
    Text(
        text = when {
            overSpecified -> "⚠"
            livePass == true -> "✓"
            livePass == false -> "✗"
            else -> "·"
        },
        color = color,
        fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(2.dp)).padding(horizontal = 4.dp),
    )
}
