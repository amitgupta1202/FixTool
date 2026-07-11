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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.MessageView

/** One editable tag row in the [ExpectationBuilder]. */
data class FieldDraft(
    val tag: Int,
    val name: String,
    val value: String,
    val included: Boolean,
    val matcher: Matcher,
    val path: GroupPath? = null,
    /** The captured value's dictionary description (e.g. "2" → "FILLED"), for FIX newcomers. */
    val description: String = "",
)

/** Builds the initial per-tag drafts for a captured message: dictionary-seeded matchers (capture). */
object ExpectationDrafts {
    private fun describe(dictionary: FixDictionary?, tag: Int, value: String): String =
        dictionary?.getFieldValueDescription(tag, value)?.takeIf { it != value } ?: ""

    /**
     * Rebuild editable drafts from a previously saved expectation. Rows come from the **golden ∪ the
     * asserted fields**: a tag the author unticked earlier reappears unticked (with a freshly seeded
     * matcher) instead of vanishing forever — unticking is not a one-way door.
     */
    fun fromExpectation(expectation: Expectation, dictionary: FixDictionary?): List<FieldDraft> {
        val remaining = expectation.fields.toMutableList()

        fun claim(tag: Int, path: GroupPath?): FieldExpectation? {
            // Prefer an exact (tag, path) match; fall back to tag-only so scenarios saved before
            // group-aware seeding (paths were null) still map onto their rows.
            val found = remaining.firstOrNull { it.tag == tag && it.path == path }
                ?: remaining.firstOrNull { it.tag == tag && it.path == null }
            found?.let { remaining.remove(it) }
            return found
        }

        val goldenDrafts =
            expectation.golden?.let { golden ->
                ExpectationSeeder.seedDetailed(FixMessageHelper.parseFixMessage(golden), dictionary).map { sf ->
                    val existing = claim(sf.field.tag, sf.field.path)
                    FieldDraft(
                        tag = sf.field.tag,
                        name = dictionary?.getFieldName(sf.field.tag) ?: "",
                        value = sf.capturedValue,
                        included = existing != null,
                        matcher = existing?.matcher ?: sf.field.matcher,
                        path = existing?.path ?: sf.field.path,
                        description = describe(dictionary, sf.field.tag, sf.capturedValue),
                    )
                }
            } ?: emptyList()

        // Asserted fields with no golden row (hand-authored, or no golden at all) stay editable.
        val orphanDrafts = remaining.map { fe ->
            FieldDraft(
                tag = fe.tag,
                name = dictionary?.getFieldName(fe.tag) ?: "",
                value = "",
                included = true,
                matcher = fe.matcher,
                path = fe.path,
            )
        }
        return goldenDrafts + orphanDrafts
    }
}

/**
 * Authors an [Expectation] from a captured message: each tag is a row with an editable matcher chip
 * ([MatcherEditor]) and a **live green/red preview** against the golden message. Matchers are
 * pre-seeded from the dictionary (capture). A **group path** can be attached per tag (match a
 * repeating-group entry by identity), and **Verify generalizes** re-checks the whole expectation
 * against a second instance so an over-specified field (e.g. an `exact` timestamp) is flagged.
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

    fun expectation(): Expectation =
        Expectation(
            fields = drafts.filter { it.included }.map { FieldExpectation(it.tag, it.matcher, it.path) },
            messageType = messageType,
            mode = if (strict) MatchMode.STRICT else MatchMode.OPEN,
        )

    fun notifyChange() {
        onChange?.invoke(expectation())
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
            onSave = onSave?.let { save -> { save(expectation()) } },
        )
        // Plain Column (not LazyColumn) so this builder can be embedded inside scrollable parents
        // (the step detail panel) without the "infinity max height" nesting crash.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            drafts.forEachIndexed { index, draft ->
                FieldDraftRow(
                    draft = draft,
                    livePass = if (draft.included && goldenView != null) previewPass(draft, goldenView) else null,
                    overSpecified = overSpecified?.contains(draft.tag) == true,
                    onChange = {
                        drafts[index] = it
                        notifyChange()
                    },
                )
            }
        }
    }
}

private fun previewPass(draft: FieldDraft, golden: MessageView): Boolean {
    val results = ExpectationEvaluator.evaluate(golden, Expectation(listOf(FieldExpectation(draft.tag, draft.matcher, draft.path))))
    return results.firstOrNull()?.passed ?: false
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
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Expectation · msg $messageType", color = AppTheme.Colors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Checkbox(checked = strict, onCheckedChange = onStrictChange)
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
    }
}

@Composable
private fun FieldDraftRow(draft: FieldDraft, livePass: Boolean?, overSpecified: Boolean, onChange: (FieldDraft) -> Unit) {
    var groupOpen by remember { mutableStateOf(draft.path != null) }
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant).padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = draft.included, onCheckedChange = { onChange(draft.copy(included = it)) })
            PreviewDot(livePass, overSpecified)
            Text("${draft.tag}", color = AppTheme.Colors.tagNumber, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(40.dp))
            Text(draft.name.take(18), color = AppTheme.Colors.fieldName, fontSize = 10.sp, modifier = Modifier.width(110.dp))
            if (draft.included) {
                MatcherEditor(matcher = draft.matcher, capturedValue = draft.value, onChange = { onChange(draft.copy(matcher = it)) })
                if (draft.description.isNotEmpty()) {
                    Text("(${draft.description})", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
                Row(modifier = Modifier.weight(1f)) {}
                SlimButton(
                    text = if (draft.path != null) "grp✓" else "grp",
                    onClick = { groupOpen = !groupOpen },
                    color = if (draft.path != null) AppTheme.Colors.groupTag else AppTheme.Colors.textSecondary,
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                )
            } else {
                Text(draft.value.take(24), color = AppTheme.Colors.textDisabled, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                if (draft.description.isNotEmpty()) {
                    Text("(${draft.description})", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (groupOpen && draft.included) {
            GroupPathEditor(draft.path) { onChange(draft.copy(path = it)) }
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

/**
 * Locates a repeating-group entry by identity ("the entry whose PartyRole(452) = 1"), never by
 * position — group order is not guaranteed. Labeled in plain words for users new to FIX groups.
 */
@Composable
private fun GroupPathEditor(path: GroupPath?, onChange: (GroupPath?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("in group", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
        SlimField(path?.groupTag?.toString() ?: "", { onChange(updatePath(path, groupTag = it.toIntOrNull())) }, monospace = true, tintBlank = true, modifier = Modifier.width(56.dp))
        Text("pick the entry where tag", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
        SlimField(path?.identityTag?.toString() ?: "", { onChange(updatePath(path, identityTag = it.toIntOrNull())) }, monospace = true, tintBlank = true, modifier = Modifier.width(56.dp))
        Text("=", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
        SlimField(path?.identityValue ?: "", { onChange(updatePath(path, identityValue = it)) }, monospace = true, tintBlank = true, modifier = Modifier.width(80.dp))
        SlimButton("clear", onClick = { onChange(null) }, color = AppTheme.Colors.textSecondary)
    }
}

private fun updatePath(path: GroupPath?, groupTag: Int? = null, identityTag: Int? = null, identityValue: String? = null): GroupPath? {
    val g = groupTag ?: path?.groupTag ?: return path
    val t = identityTag ?: path?.identityTag ?: 0
    val v = identityValue ?: path?.identityValue ?: ""
    return GroupPath(g, t, v)
}
