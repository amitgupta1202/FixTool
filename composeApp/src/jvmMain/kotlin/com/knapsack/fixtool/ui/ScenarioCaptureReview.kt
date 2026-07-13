// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList")

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.ScenarioAnnotations
import com.knapsack.fixtool.service.ScenarioCapture
import java.time.format.DateTimeFormatter

/** Recognizes the expressions the capturer emits, for the "what this becomes" preview chips. */
private val MINT_EXPR = Regex("^\\$\\{(\\w+) = UUID\\.randomUUID\\(\\)}$")
private val REF_EXPR = Regex("^\\$\\{(\\w+)}$")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/**
 * Capture review — nothing is saved blind. Every business message currently in the session logs is a
 * row (all sessions, oldest first); the author unticks noise or trims with start/end, watches the
 * correlation badges update live, inspects what each row will become (parameterized Send / seeded
 * Expect), names the scenario, and only then saves.
 */
@Composable
fun ScenarioCaptureReview(
    candidates: List<ScenarioCapture.Candidate>,
    dictionary: FixDictionary?,
    onSave: (String, List<ScenarioCapture.Candidate>) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var selectedIdx by remember { mutableStateOf(if (candidates.isEmpty()) -1 else 0) }
    val included = remember { mutableStateListOf<Boolean>().apply { repeat(candidates.size) { add(true) } } }

    val selection = candidates.filterIndexed { i, _ -> included.getOrElse(i) { false } }
    // Re-run capture over the current selection: cheap, pure, and it keeps badges/preview honest —
    // excluding the send that mints an id visibly downgrades its echoes from reference to exact.
    val previewSteps =
        remember(included.toList()) {
            ScenarioCapture.captureFrom("preview", "preview", null, selection, dictionary).steps
        }
    val stepVars = remember(previewSteps) { ScenarioAnnotations.annotate(previewSteps) }
    val varColors = varColorMap(stepVars.flatMap { it.minted })
    val sessionColors = sessionColorMap(candidates.map { it.session })

    /** Index into [previewSteps] for an included candidate row (steps mirror the selection order). */
    fun stepIndexOf(candidateIdx: Int): Int = (0 until candidateIdx).count { included.getOrElse(it) { false } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AppTheme.Colors.textSecondary)
            }
            Text("Capture scenario", color = AppTheme.Colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            SlimLabeled("Name", modifier = Modifier.padding(start = 16.dp)) {
                SlimField(name, { name = it }, modifier = Modifier.width(240.dp).testTag("capture-name"))
            }
            Text(
                "${selection.size} of ${candidates.size} messages selected",
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
            SlimButton(
                text = "Save scenario",
                onClick = { onSave(name.ifBlank { "Captured scenario" }, selection) },
                enabled = selection.isNotEmpty(),
                color = AppTheme.Colors.success,
                modifier = Modifier.testTag("capture-save"),
            )
        }
        Text(
            text = "Sends become parameterized Send steps (fresh ids, live timestamps); responses become assertions. " +
                "●id badges mark where an id is minted, ○id where it must echo back — including across sessions.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        UnassertableNotice(remember(included.toList()) { ScenarioCapture.unassertable(selection, dictionary) })
        RangeSelectors(
            candidates = candidates,
            dictionary = dictionary,
            included = included,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        if (candidates.isEmpty()) {
            Text(
                "Nothing to capture: no business messages in any session. Drive the flow in the main window first.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 12.sp,
                modifier = Modifier.padding(12.dp),
            )
            return@Column
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(0.56f).fillMaxHeight()) {
                itemsIndexed(candidates) { i, candidate ->
                    val isIncluded = included.getOrElse(i) { false }
                    CandidateRow(
                        index = i,
                        candidate = candidate,
                        dictionary = dictionary,
                        included = isIncluded,
                        selected = i == selectedIdx,
                        sessionColor = sessionColors[candidate.session] ?: AppTheme.Colors.primary,
                        vars = if (isIncluded) stepVars.getOrNull(stepIndexOf(i)) else null,
                        varColors = varColors,
                        onToggle = { included[i] = it },
                        onSelect = { selectedIdx = i },
                    )
                }
            }
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(AppTheme.Colors.border))
            Column(modifier = Modifier.weight(0.44f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp)) {
                if (selectedIdx in candidates.indices) {
                    CandidateDetail(
                        index = selectedIdx,
                        candidate = candidates[selectedIdx],
                        dictionary = dictionary,
                        included = included.getOrElse(selectedIdx) { false },
                        previewStep = if (included.getOrElse(selectedIdx) { false }) previewSteps.getOrNull(stepIndexOf(selectedIdx)) else null,
                        varColors = varColors,
                        sessionColor = sessionColors[candidates[selectedIdx].session] ?: AppTheme.Colors.primary,
                        onToggle = { included[selectedIdx] = it },
                    )
                }
            }
        }
    }
}

/**
 * What this capture will *not* assert — groups whose entries share an identity, so no assertion can
 * say which entry it means.
 *
 * Shown here, while the author can still act on it, rather than leaving them to trust a green run
 * over a part of the message nothing actually checked. FixTool would rather admit a gap than paper
 * over one.
 */
@Composable
private fun UnassertableNotice(unassertable: List<ExpectationSeeder.UnassertableGroup>) {
    if (unassertable.isEmpty()) return
    Text(
        text = "Not asserted — " + unassertable.joinToString("; ") { it.reason } +
            ". Everything else in these messages is captured as normal.",
        color = AppTheme.Colors.warning,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp).testTag("capture-unassertable"),
    )
}

@Composable
private fun CandidateRow(
    index: Int,
    candidate: ScenarioCapture.Candidate,
    dictionary: FixDictionary?,
    included: Boolean,
    selected: Boolean,
    sessionColor: androidx.compose.ui.graphics.Color,
    vars: ScenarioAnnotations.StepVars?,
    varColors: Map<String, androidx.compose.ui.graphics.Color>,
    onToggle: (Boolean) -> Unit,
    onSelect: () -> Unit,
) {
    val outgoing = candidate.message.direction == FixMessage.Direction.OUTGOING
    val bg = if (selected) AppTheme.Colors.selectionPrimary else AppTheme.Colors.surfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(bg).clickable(onClick = onSelect).padding(end = 8.dp).testTag("candidate-$index"),
    ) {
        Checkbox(checked = included, onCheckedChange = onToggle, modifier = Modifier.testTag("candidate-check-$index"))
        RowIndex(index, dimmed = !included)
        SessionBadge(candidate.session, sessionColor, modifier = Modifier.width(130.dp))
        DirectionGlyph(outgoing, modifier = Modifier.padding(end = 6.dp))
        Text(
            text = if (outgoing) "Send ${msgTypeLabel(dictionary, candidate.message.messageType)}" else "Expect ${msgTypeLabel(dictionary, candidate.message.messageType)}",
            color = if (included) AppTheme.Colors.text else AppTheme.Colors.textDisabled,
            fontSize = 12.sp,
            maxLines = 1,
        )
        if (vars != null) {
            VarBadges(vars.minted, vars.referenced, varColors, modifier = Modifier.padding(start = 8.dp))
        }
        Row(modifier = Modifier.weight(1f)) {}
        Text(candidate.message.timestamp.format(TIME_FMT), color = AppTheme.Colors.textDisabled, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * From/To range dropdowns (the message editor's dropdown convention): pick the first and last
 * message of the flow instead of hunting per-row. Rows outside the range are excluded; individual
 * checkboxes still fine-tune within it.
 */
@Composable
private fun RangeSelectors(
    candidates: List<ScenarioCapture.Candidate>,
    dictionary: FixDictionary?,
    included: MutableList<Boolean>,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    fun label(i: Int): String {
        val c = candidates[i]
        val glyph = if (c.message.direction == FixMessage.Direction.OUTGOING) "▶" else "◀"
        return "#${i + 1} $glyph ${msgTypeLabel(dictionary, c.message.messageType)} · ${c.session}"
    }
    val first = included.indexOfFirst { it }.takeIf { it >= 0 }
    val last = included.indexOfLast { it }.takeIf { it >= 0 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier) {
        SlimLabeled("From") {
            SlimDropdown(
                value = first,
                options = candidates.indices.toList(),
                onValueChange = { picked ->
                    if (picked != null) {
                        (0 until picked).forEach { included[it] = false }
                        included[picked] = true
                    }
                },
                displayText = ::label,
                placeholder = "first message",
                modifier = Modifier.width(280.dp).testTag("capture-from"),
            )
        }
        SlimLabeled("To") {
            SlimDropdown(
                value = last,
                options = candidates.indices.toList(),
                onValueChange = { picked ->
                    if (picked != null) {
                        (picked + 1 until candidates.size).forEach { included[it] = false }
                        included[picked] = true
                    }
                },
                displayText = ::label,
                placeholder = "last message",
                modifier = Modifier.width(280.dp).testTag("capture-to"),
            )
        }
    }
}

@Composable
private fun CandidateDetail(
    index: Int,
    candidate: ScenarioCapture.Candidate,
    dictionary: FixDictionary?,
    included: Boolean,
    previewStep: ScenarioStep?,
    varColors: Map<String, androidx.compose.ui.graphics.Color>,
    sessionColor: androidx.compose.ui.graphics.Color,
    onToggle: (Boolean) -> Unit,
) {
    val outgoing = candidate.message.direction == FixMessage.Direction.OUTGOING
    Row(verticalAlignment = Alignment.CenterVertically) {
        DirectionGlyph(outgoing)
        Text(
            text = "  #${index + 1}  ${msgTypeLabel(dictionary, candidate.message.messageType)}",
            color = AppTheme.Colors.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        SessionBadge(candidate.session, sessionColor, modifier = Modifier.padding(start = 10.dp))
        SlimButton(
            text = if (included) "Exclude" else "Include",
            onClick = { onToggle(!included) },
            color = if (included) AppTheme.Colors.warning else AppTheme.Colors.success,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    Row(modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)) {}
    when {
        !included ->
            Text("Excluded — this message will not be part of the scenario.", color = AppTheme.Colors.textDisabled, fontSize = 12.sp)
        previewStep is ScenarioStep.Send -> SendPreview(candidate, previewStep, dictionary, varColors)
        previewStep is ScenarioStep.Expect -> ExpectPreview(candidate, previewStep, dictionary, varColors)
        else -> Unit
    }
}

/** What an outgoing message becomes: per-tag "captured value → replay behavior". */
@Composable
private fun SendPreview(
    candidate: ScenarioCapture.Candidate,
    step: ScenarioStep.Send,
    dictionary: FixDictionary?,
    varColors: Map<String, androidx.compose.ui.graphics.Color>,
) {
    Text("Becomes a Send step — replayed with:", color = AppTheme.Colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
    val unknownTags = com.knapsack.fixtool.service.DictionaryLint.unknownTags(candidate.fields, dictionary)
    if (unknownTags.isNotEmpty()) {
        Text(
            "⚠ " + com.knapsack.fixtool.service.DictionaryLint.describe(unknownTags, candidate.fields, dictionary),
            color = AppTheme.Colors.warning,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    val transformed = FixMessageHelper.parseFixMessage(step.raw).toMap()
    candidate.fields.distinctBy { it.first }.forEach { (tag, value) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            TagAndName(tag, dictionary)
            val replay = transformed[tag]
            when {
                replay == null ->
                    Text("dropped (session/transport header)", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
                replay == value ->
                    Text(valueWithDescription(dictionary, tag, value), color = AppTheme.Colors.fieldValue, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                else -> {
                    Text(value, color = AppTheme.Colors.textDisabled, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("  →  ", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
                    ReplayChip(replay, varColors)
                }
            }
        }
    }
}

/** Renders the replay expression as a human chip: fresh id / reuse id / send-time timestamp. */
@Composable
private fun ReplayChip(expression: String, varColors: Map<String, androidx.compose.ui.graphics.Color>) {
    val mint = MINT_EXPR.find(expression)?.groupValues?.get(1)
    val ref = REF_EXPR.find(expression)?.groupValues?.get(1)
    when {
        mint != null -> {
            VarBadge(mint, varColors[mint] ?: AppTheme.Colors.primary, minted = true)
            Text(" fresh id each run", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
        ref != null -> {
            VarBadge(ref, varColors[ref] ?: AppTheme.Colors.primary, minted = false)
            Text(" reuses the id minted earlier", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
        expression.contains("now()") ->
            Text("timestamp at send time", color = AppTheme.Colors.info, fontSize = 11.sp)
        else ->
            Text(expression, color = AppTheme.Colors.fieldValue, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

/** What an incoming message becomes: per-tag matcher summary (references shown as id badges). */
@Composable
private fun ExpectPreview(
    candidate: ScenarioCapture.Candidate,
    step: ScenarioStep.Expect,
    dictionary: FixDictionary?,
    varColors: Map<String, androidx.compose.ui.graphics.Color>,
) {
    Text(
        "Becomes an assertion — each run waits for this message and checks:",
        color = AppTheme.Colors.textSecondary,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    val byTag = step.expectation.fields.associateBy { it.tag }
    candidate.fields.distinctBy { it.first }.forEach { (tag, value) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            TagAndName(tag, dictionary)
            Text(
                valueWithDescription(dictionary, tag, value).take(34),
                color = AppTheme.Colors.textDisabled,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.width(180.dp),
            )
            val fe = byTag[tag]
            if (fe == null) {
                Text("not asserted", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
            } else {
                val refVar = ((fe.matcher as? Matcher.Reference)?.expression)?.let { REF_EXPR.find(it)?.groupValues?.get(1) }
                if (refVar != null) {
                    Text("must echo ", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
                    VarBadge(refVar, varColors[refVar] ?: AppTheme.Colors.primary, minted = false)
                } else {
                    Text(matcherSummary(fe.matcher, dictionary, tag), color = AppTheme.Colors.fieldName, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun TagAndName(tag: Int, dictionary: FixDictionary?) {
    Text("$tag", color = AppTheme.Colors.tagNumber, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
    Text(dictionary?.getFieldName(tag) ?: "", color = AppTheme.Colors.textSecondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(120.dp))
}
