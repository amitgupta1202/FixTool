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
import com.knapsack.fixtool.service.ExpectationEvaluator
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
    /** Messages FixTool could not read the wire bytes for — see [UnreadableNotice]. */
    unreadable: List<FixMessage> = emptyList(),
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
        UnreadableNotice(unreadable, dictionary)
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
 * The messages this capture cannot offer, and why — because a capture is a claim about coverage.
 *
 * A message whose wire bytes FixTool does not have cannot become an assertion: every seeded expectation is
 * an ordered list of rows, the order is half of what it asserts, and seeding one from a field order we had
 * to guess writes a fabricated order into the golden. So the message is left out — and *said*. Dropping it
 * quietly would hand the author a scenario that omits a reply and looks complete, which is the one thing a
 * testing tool must never do.
 *
 * In practice this is empty: QuickFIX/J retains the bytes it parsed. If it is ever not empty, that is worth
 * knowing about, and the log line at `QuickFixService.wireBytesOf` names the message.
 */
@Composable
private fun UnreadableNotice(unreadable: List<FixMessage>, dictionary: FixDictionary?) {
    if (unreadable.isEmpty()) return
    val types = unreadable.joinToString(", ") { msgTypeLabel(dictionary, it.messageType) }
    Text(
        text = "⚠ ${unreadable.size} message${if (unreadable.size == 1) "" else "s"} left out — FixTool has no " +
            "wire bytes for ${if (unreadable.size == 1) "it" else "them"} ($types), so their field order is " +
            "unknown and an assertion seeded from them would check an order the venue never sent. This " +
            "scenario will not cover ${if (unreadable.size == 1) "it" else "them"}.",
        color = AppTheme.Colors.warning,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp).testTag("capture-unreadable"),
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

/**
 * What an outgoing message becomes: one row per **occurrence**, in wire order.
 *
 * It used to be one row per *tag* (`distinctBy { it.first }`, and a `Map` of the replay values, which
 * collapses duplicates the same way). A message with two party entries showed three rows where six fields
 * were captured, and the review screen — the last thing between an author and a saved scenario — described
 * a message shorter than the one it was about to save.
 */
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
    // The Send raw is the captured fields, in order, minus the transport headers the framework re-stamps.
    // So walking the two with one cursor pairs each captured field with what will actually be sent for it —
    // and a field the cursor does not advance past is one that was dropped. A Map keyed by tag could not
    // express either fact: it answers for the *last* occurrence of a repeated tag, whichever row is asking.
    val transformed = FixMessageHelper.parseFixMessage(step.raw)
    val repeated = repeatedTags(candidate.fields)
    val occurrences = mutableMapOf<Int, Int>()
    var cursor = 0
    candidate.fields.forEach { (tag, value) ->
        val occurrence = nextOccurrence(occurrences, tag)
        val replay = transformed.getOrNull(cursor)?.takeIf { it.first == tag }?.second
        if (replay != null) cursor++
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            TagAndName(tag, dictionary)
            OccurrenceLabel(occurrence, show = tag in repeated)
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

/**
 * What an incoming message becomes: one row per **occurrence**, in wire order, each showing the matcher
 * that will actually check *that* field.
 *
 * It used to collapse both sides by tag — `distinctBy { it.first }` over the captured fields, and
 * `associateBy { it.tag }` over the assertion rows — and the two collapses did not even agree with each
 * other. `distinctBy` keeps the *first* occurrence; `associateBy` keeps the *last*. So on an
 * ExecutionReport with two party entries the screen showed three rows where six assertions had been
 * seeded, and the one PartyRole row it did show read "captured 1 → asserts exact 4": the executing firm's
 * value beside the clearing firm's matcher, an assertion that exists nowhere.
 *
 * The pairing comes from [ExpectationEvaluator.align] — the same function the runner uses to decide which
 * field a row refers to. Anything else here would be a second rule for a question the engine has already
 * answered, and this screen would eventually describe a scenario the engine does not run.
 */
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
    val aligned = ExpectationEvaluator.align(step.expectation, candidate.fields)
    val rowAt = aligned.mapNotNull { a -> a.wireIndex?.let { w -> a.row?.let { w to it } } }.toMap()
    val repeated = repeatedTags(candidate.fields)
    val occurrences = mutableMapOf<Int, Int>()
    candidate.fields.forEachIndexed { wireIndex, (tag, value) ->
        val occurrence = nextOccurrence(occurrences, tag)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            TagAndName(tag, dictionary)
            OccurrenceLabel(occurrence, show = tag in repeated)
            Text(
                valueWithDescription(dictionary, tag, value).take(34),
                color = AppTheme.Colors.textDisabled,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.width(180.dp),
            )
            val fe = rowAt[wireIndex]
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

/** Tags the message carries more than once — the only ones whose rows need an occurrence label. */
private fun repeatedTags(fields: List<Pair<Int, String>>): Set<Int> {
    val counts = mutableMapOf<Int, Int>()
    fields.forEach { counts.merge(it.first, 1, Int::plus) }
    return counts.filterValues { it > 1 }.keys
}

/** The 0-based occurrence of [tag], advancing the running count. */
private fun nextOccurrence(counts: MutableMap<Int, Int>, tag: Int): Int {
    val seen = counts.getOrDefault(tag, 0)
    counts[tag] = seen + 1
    return seen
}

/**
 * Which entry this row is — `#2` for the second occurrence of a repeating tag, blank when the tag is
 * unique. Same convention as the ExpectationBuilder, and for the same reason: four identical "452
 * PartyRole" rows are four rows the author cannot tell apart, and the one they mean is a guess.
 */
@Composable
private fun OccurrenceLabel(occurrence: Int, show: Boolean) {
    Text(
        text = if (show) "#${occurrence + 1}" else "",
        color = AppTheme.Colors.groupTag,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        modifier = Modifier.width(26.dp),
    )
}

@Composable
private fun TagAndName(tag: Int, dictionary: FixDictionary?) {
    Text("$tag", color = AppTheme.Colors.tagNumber, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
    Text(dictionary?.getFieldName(tag) ?: "", color = AppTheme.Colors.textSecondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(120.dp))
}
