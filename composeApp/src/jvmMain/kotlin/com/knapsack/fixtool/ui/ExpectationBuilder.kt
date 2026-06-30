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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
)

/** Builds the initial per-tag drafts for a captured message: dictionary-seeded matchers (capture). */
object ExpectationDrafts {
    fun fromRaw(raw: String, dictionary: FixDictionary?): List<FieldDraft> {
        val fields = FixMessageHelper.parseFixMessage(raw)
        val seeded = ExpectationSeeder.seed(fields, dictionary).fields.associateBy { it.tag }
        return fields.distinctBy { it.first }.map { (tag, value) ->
            val fe = seeded[tag]
            FieldDraft(
                tag = tag,
                name = dictionary?.getFieldName(tag) ?: "",
                value = value,
                included = fe != null,
                matcher = fe?.matcher ?: Matcher.Exact(value),
            )
        }
    }

    /** Rebuild editable drafts from a previously saved expectation (preserving relaxed matchers). */
    fun fromExpectation(expectation: Expectation, dictionary: FixDictionary?): List<FieldDraft> {
        val goldenValues =
            expectation.golden
                ?.let { FixMessageHelper.parseFixMessage(it).associate { f -> f.first to f.second } }
                ?: emptyMap()
        return expectation.fields.map { fe ->
            FieldDraft(
                tag = fe.tag,
                name = dictionary?.getFieldName(fe.tag) ?: "",
                value = goldenValues[fe.tag] ?: "",
                included = true,
                matcher = fe.matcher,
                path = fe.path,
            )
        }
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
    goldenView: MessageView,
    secondView: MessageView? = null,
    onSave: (Expectation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drafts = remember { mutableStateListOf<FieldDraft>().apply { addAll(initialFields) } }
    var strict by remember { mutableStateOf(false) }
    var overSpecified by remember { mutableStateOf<Set<Int>?>(null) }

    fun expectation(): Expectation =
        Expectation(
            fields = drafts.filter { it.included }.map { FieldExpectation(it.tag, it.matcher, it.path) },
            messageType = messageType,
            mode = if (strict) MatchMode.STRICT else MatchMode.OPEN,
        )

    Column(modifier = modifier.fillMaxWidth()) {
        BuilderHeader(
            messageType = messageType,
            strict = strict,
            onStrictChange = { strict = it },
            canVerify = secondView != null,
            onVerify = {
                val results = ExpectationEvaluator.evaluate(secondView!!, expectation())
                overSpecified = results.filterNot { it.passed }.map { it.tag }.toSet()
            },
            overSpecified = overSpecified,
            onSave = { onSave(expectation()) },
        )
        // Plain Column (not LazyColumn) so this builder can be embedded inside scrollable parents
        // (the scenario builder / dialog) without the "infinity max height" nesting crash.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            drafts.forEachIndexed { index, draft ->
                FieldDraftRow(
                    draft = draft,
                    livePass = if (draft.included) previewPass(draft, goldenView) else null,
                    overSpecified = overSpecified?.contains(draft.tag) == true,
                    onChange = { drafts[index] = it },
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
    onSave: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Expectation · msg $messageType", color = AppTheme.Colors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Checkbox(checked = strict, onCheckedChange = onStrictChange)
            Text("STRICT", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
        OutlinedButton(onClick = onVerify, enabled = canVerify, modifier = Modifier.padding(start = 12.dp)) {
            Text("Verify generalizes", color = AppTheme.Colors.text, fontSize = 11.sp)
        }
        OutlinedButton(onClick = onSave, modifier = Modifier.padding(start = 8.dp)) {
            Text("Save expectation", color = AppTheme.Colors.success, fontSize = 11.sp)
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
            Text("${draft.tag}", color = AppTheme.Colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(44.dp))
            Text(draft.name.take(16), color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.width(110.dp))
            if (draft.included) {
                MatcherEditor(matcher = draft.matcher, capturedValue = draft.value, onChange = { onChange(draft.copy(matcher = it)) })
                OutlinedButton(onClick = { groupOpen = !groupOpen }, modifier = Modifier.padding(start = 6.dp)) {
                    Text(if (draft.path != null) "grp✓" else "grp", fontSize = 10.sp, color = AppTheme.Colors.textSecondary)
                }
            } else {
                Text(draft.value.take(24), color = AppTheme.Colors.textDisabled, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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

@Composable
private fun GroupPathEditor(path: GroupPath?, onChange: (GroupPath?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("group:", color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
        GroupField("groupTag", path?.groupTag?.toString() ?: "") { onChange(updatePath(path, groupTag = it.toIntOrNull())) }
        GroupField("idTag", path?.identityTag?.toString() ?: "") { onChange(updatePath(path, identityTag = it.toIntOrNull())) }
        GroupField("idValue", path?.identityValue ?: "") { onChange(updatePath(path, identityValue = it)) }
        OutlinedButton(onClick = { onChange(null) }) { Text("clear", fontSize = 10.sp, color = AppTheme.Colors.textSecondary) }
    }
}

@Composable
private fun GroupField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 9.sp) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
        modifier = Modifier.width(96.dp),
    )
}

private fun updatePath(path: GroupPath?, groupTag: Int? = null, identityTag: Int? = null, identityValue: String? = null): GroupPath? {
    val g = groupTag ?: path?.groupTag ?: return path
    val t = identityTag ?: path?.identityTag ?: 0
    val v = identityValue ?: path?.identityValue ?: ""
    return GroupPath(g, t, v)
}
