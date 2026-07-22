// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.MintingSide
import com.knapsack.fixtool.model.TagRole
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.EchoDetector
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.ScenarioAnnotations
import com.knapsack.fixtool.service.ScenarioCapture
import java.time.format.DateTimeFormatter

/** Recognizes the expressions the capturer emits, for the "what this becomes" preview chips —
 *  both today's `uuid`/`uuid:20` shorthand and the Kotlin longhand older scenarios still carry. */
private val MINT_EXPR = Regex("^\\$\\{(\\w+) = (?:uuid\\b|UUID\\.randomUUID\\(\\)).*}$")
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
    modifier: Modifier = Modifier,
    /** Per-session [MintingSide], so the preview below seeds exactly what Save will write. */
    sides: Map<String, MintingSide> = emptyMap(),
    /** Messages FixTool could not read the wire bytes for — see [UnreadableNotice]. */
    unreadable: List<FixMessage> = emptyList(),
    /**
     * The curation, held by the host. This is a *tab* now, and only the active tab is composed — a
     * `remember`ed checklist would be destroyed by a glance at the session grid it was scanned from, which
     * is the one thing the capture review is now next to. See [ScenarioDoc].
     */
    state: CaptureReviewState = remember(candidates.size) { CaptureReviewState.of(candidates.size) },
    onStateChange: (CaptureReviewState) -> Unit = {},
    /** Selecting a candidate selects its source message in the session grid and the detail panel. */
    onSelectSource: (FixMessage) -> Unit = {},
    /** Capture's second source. Null = the live sessions; non-null = the author is pasting wire (S9). */
    paste: ScenarioDoc.Capture.Paste? = null,
    onPasteChange: (String, String) -> Unit = { _, _ -> },
    /** A direction the bytes could not settle, settled by the author. Nothing else may settle it. */
    onSetDirection: (Int, FixMessage.Direction) -> Unit = { _, _ -> },
    sessionOptions: List<String> = emptyList(),
    /**
     * Accepting an echo proposal: declares the roles on the venue's dictionary and re-seeds the capture.
     * Null hides the band entirely — a host that cannot persist a declaration must not offer to.
     */
    onDeclareRoles: ((Map<Int, TagRole>) -> Unit)? = null,
) {
    val name = state.name
    val selectedIdx = state.selectedIdx

    val selection = candidates.filterIndexed { i, _ -> state.includes(i) }
    // Re-run capture over the current selection: cheap, pure, and it keeps badges/preview honest —
    // excluding the send that mints an id visibly downgrades its echoes from reference to exact.
    val previewSteps =
        remember(state.included) {
            ScenarioCapture.captureFrom("preview", "preview", null, selection, dictionary, sides).steps
        }
    val stepVars = remember(previewSteps) { ScenarioAnnotations.annotate(previewSteps) }
    // The badge tooltips cite row numbers, and here a row is not its step: excluded and undirected rows
    // make no step at all. Translate the step-indexed sites onto the rows the reader is looking at, or a
    // tooltip would point at some other candidate entirely.
    val varSites =
        remember(previewSteps, state.included) {
            val rowOfStep = candidates.indices.filter { state.includes(it) && candidates[it].direction != null }
            ScenarioAnnotations.sites(previewSteps).mapValues { (_, sites) ->
                sites.copy(
                    mintedAt = sites.mintedAt.mapNotNull { rowOfStep.getOrNull(it) },
                    capturedAt = sites.capturedAt.mapNotNull { rowOfStep.getOrNull(it) },
                    referencedAt = sites.referencedAt.mapNotNull { rowOfStep.getOrNull(it) },
                )
            }
        }
    val varColors = varColorMap(stepVars.flatMap { it.minted })
    val sessionColors = sessionColorMap(candidates.map { it.session })

    /**
     * Index into [previewSteps] for an included candidate row (steps mirror the selection order).
     *
     * **Only DIRECTED rows become steps** — `captureFrom` skips the undirected ones — so an undirected row
     * upstream must not shift the count, or the preview would draw one candidate's chips against another
     * candidate's step.
     */
    fun stepIndexOf(candidateIdx: Int): Int =
        (0 until candidateIdx).count { state.includes(it) && candidates.getOrNull(it)?.direction != null }

    fun include(index: Int, on: Boolean) {
        val next = state.included.toMutableList()
        while (next.size <= index) next.add(true)
        next[index] = on
        onStateChange(state.copy(included = next))
    }

    fun select(index: Int) {
        onStateChange(state.copy(selectedIdx = index))
        // A pasted candidate has no source row to highlight — and inventing one would put a message in
        // the grid that never arrived on any wire.
        candidates.getOrNull(index)?.source?.let { onSelectSource(it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // One control row — name, the From/To range, the help affordance, the count, and Save — instead of the
        // four stacked rows this was. The tab already says "capture", so the screen title is gone; the long
        // "what this becomes" paragraph now lives behind the ⓘ, out of the way until it is asked for.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        ) {
            SlimLabeled("Name") {
                SlimField(name, { onStateChange(state.copy(name = it)) }, modifier = Modifier.width(160.dp).testTag("capture-name"))
            }
            RangeSelectors(
                candidates = candidates,
                dictionary = dictionary,
                included = state.included,
                onIncludedChange = { onStateChange(state.copy(included = it)) },
            )
            CaptureHelp()
            Text(
                "${selection.size}/${candidates.size} selected",
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            SlimButton(
                text = "Save scenario",
                onClick = { onSave(name.ifBlank { ScenarioCapture.defaultName() }, selection) },
                // **An undirected row cannot be saved.** A reply mis-marked as a Send becomes a step that
                // asserts NOTHING — the scenario sends the venue's own reply back at it and reports green — so
                // a direction nobody has settled blocks the save by name rather than defaulting into silence.
                enabled = selection.isNotEmpty() && ScenarioCapture.undirected(selection).isEmpty(),
                color = AppTheme.Colors.success,
                modifier = Modifier.testTag("capture-save"),
            )
        }
        val undirected = ScenarioCapture.undirected(selection)
        if (undirected.isNotEmpty()) {
            Text(
                "${undirected.size} message${if (undirected.size == 1) "" else "s"} still ${
                    if (undirected.size == 1) "has" else "have"
                } no direction. A paste does not carry one, and these bytes' SenderCompID(49) does not match " +
                    "the session's — so say which way each one went. A reply saved as a Send becomes a step " +
                    "that asserts nothing.",
                color = AppTheme.Colors.warning,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("capture-undirected"),
            )
        }
        paste?.let { PasteSource(it, sessionOptions, onPasteChange) }
        UnreadableNotice(unreadable, dictionary)
        if (onDeclareRoles != null) EchoProposals(selection, dictionary, onDeclareRoles)
        if (candidates.isEmpty()) {
            Text(
                // The paste review's messages come from the paste box, not the sessions — telling its author
                // to "drive the flow in the main window" sent them away from the flow they were in.
                if (paste != null) {
                    "Nothing captured yet — paste wire above, one message per line."
                } else {
                    "Nothing to capture: no business messages in any session. Drive the flow in the main window first."
                },
                color = AppTheme.Colors.textDisabled,
                fontSize = 12.sp,
                modifier = Modifier.padding(12.dp),
            )
            return@Column
        }
        // The split is draggable, same as the editor's, so a wide message can be given the room to read.
        var split by remember { mutableStateOf(0.56f) }
        var paneWidth by remember { mutableStateOf(0) }
        Row(modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { paneWidth = it.width }) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(split).fillMaxHeight()) {
                itemsIndexed(candidates) { i, candidate ->
                    val isIncluded = state.includes(i)
                    CandidateRow(
                        index = i,
                        candidate = candidate,
                        dictionary = dictionary,
                        included = isIncluded,
                        selected = i == selectedIdx,
                        sessionColor = sessionColors[candidate.session] ?: AppTheme.Colors.primary,
                        // An undirected row makes no step yet, so it has no vars — and the step indices below it
                        // have not shifted, because captureFrom skips it too.
                        vars = if (isIncluded && candidate.direction != null) stepVars.getOrNull(stepIndexOf(i)) else null,
                        varSites = varSites,
                        varColors = varColors,
                        onToggle = { include(i, it) },
                        onSelect = { select(i) },
                        onSetDirection = onSetDirection,
                    )
                }
            }
            PaneDivider(onDrag = { dx -> if (paneWidth > 0) split = (split + dx / paneWidth).coerceIn(0.20f, 0.80f) }, testTag = "capture-pane-divider")
            Column(modifier = Modifier.weight(1f - split).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp)) {
                if (selectedIdx in candidates.indices) {
                    CandidateDetail(
                        index = selectedIdx,
                        candidate = candidates[selectedIdx],
                        dictionary = dictionary,
                        included = state.includes(selectedIdx),
                        previewStep = if (state.includes(selectedIdx)) previewSteps.getOrNull(stepIndexOf(selectedIdx)) else null,
                        varColors = varColors,
                        sessionColor = sessionColors[candidates[selectedIdx].session] ?: AppTheme.Colors.primary,
                        onToggle = { include(selectedIdx, it) },
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
/**
 * **Correlation ids this capture can see but nobody has declared** — offered, never applied.
 *
 * The settings scan asks about tags that *look* like identifiers. This asks about values that **actually
 * came back**, which is both stronger evidence and the only way to reach an id whose name says nothing.
 * It sits in the review because that is where the flow is: the proposal cannot exist without one.
 *
 * Every row is unticked. The gate is deliberately wider than certainty — the same call the fix plan's
 * presence demotion makes — so the evidence is printed in full and the author confirms it. Accepting
 * writes the role to the venue's dictionary sidecar, which is what makes the answer outlive this capture.
 */
@Composable
private fun EchoProposals(
    selection: List<ScenarioCapture.Candidate>,
    dictionary: FixDictionary?,
    onDeclareRoles: (Map<Int, TagRole>) -> Unit,
) {
    val proposals = remember(selection, dictionary) { EchoDetector.detect(selection, dictionary) }
    if (proposals.isEmpty()) return
    val ticked = remember(proposals) { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp).testTag("echo-proposals"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "${proposals.size} value${if (proposals.size == 1) "" else "s"} came back — correlation ids?",
            color = AppTheme.Colors.text,
            fontSize = 11.sp,
        )
        proposals.forEach { p ->
            val key = p.tags.joinToString(",")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(
                    checked = ticked[key] == true,
                    onCheckedChange = { ticked[key] = it },
                    modifier = Modifier.testTag("echo-proposal-$key"),
                )
                Column {
                    Text(
                        text =
                            "tag ${p.tags.joinToString("/")} — " +
                                if (p.kind == EchoDetector.Kind.MINT) {
                                    "mint \${${p.suggestedName}} fresh each run"
                                } else {
                                    "read the venue's value into \${${p.suggestedName}} and send that back"
                                },
                        color = AppTheme.Colors.text,
                        fontSize = 11.sp,
                    )
                    // The evidence, not a confidence score: the author can check this claim against the two
                    // messages in front of them, which is the only thing that makes a wider-than-certain
                    // gate safe to offer at all.
                    Text(p.evidence, color = AppTheme.Colors.textSecondary, fontSize = 10.sp)
                }
            }
        }
        val chosen = proposals.filter { ticked[it.tags.joinToString(",")] == true }
        SlimButton(
            text = if (chosen.isEmpty()) "Declare selected" else "Declare ${chosen.sumOf { it.tags.size }} tag(s)",
            enabled = chosen.isNotEmpty(),
            color = AppTheme.Colors.primary,
            onClick = { onDeclareRoles(chosen.flatMap { p -> p.tags.map { it to p.role } }.toMap()) },
            modifier = Modifier.testTag("echo-proposals-declare"),
        )
    }
}

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
    varSites: Map<String, ScenarioAnnotations.VarSites>,
    varColors: Map<String, androidx.compose.ui.graphics.Color>,
    onToggle: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onSetDirection: (Int, FixMessage.Direction) -> Unit = { _, _ -> },
) {
    val outgoing = candidate.outgoing
    val bg = if (selected) AppTheme.Colors.selectionPrimary else AppTheme.Colors.surfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(bg).clickable(onClick = onSelect).padding(end = 8.dp).testTag("candidate-$index"),
    ) {
        Checkbox(checked = included, onCheckedChange = onToggle, modifier = Modifier.testTag("candidate-check-$index"))
        RowIndex(index, dimmed = !included)
        SessionBadge(candidate.session, sessionColor, modifier = Modifier.width(130.dp))
        // **A paste carries no direction, and it is not guessed.** Where the bytes settle it, the glyph; where
        // they do not, the author is asked — because a reply saved as a Send is a step that asserts nothing.
        if (candidate.direction == null) {
            DirectionToggle(index, onSetDirection)
        } else {
            DirectionGlyph(outgoing, modifier = Modifier.padding(end = 6.dp))
        }
        Text(
            text =
                when {
                    candidate.direction == null -> msgTypeLabel(dictionary, candidate.messageType)
                    outgoing -> "Send ${msgTypeLabel(dictionary, candidate.messageType)}"
                    else -> "Expect ${msgTypeLabel(dictionary, candidate.messageType)}"
                },
            color = if (included) AppTheme.Colors.text else AppTheme.Colors.textDisabled,
            fontSize = 12.sp,
            maxLines = 1,
        )
        if (candidate.pasted) {
            Text(
                "pasted",
                color = AppTheme.Colors.warning,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 6.dp).testTag("candidate-pasted-$index"),
            )
        }
        if (vars != null) {
            VarBadges(vars, varColors, varSites, modifier = Modifier.padding(start = 8.dp))
        }
        Row(modifier = Modifier.weight(1f)) {}
        Text(candidate.timestamp.format(TIME_FMT), color = AppTheme.Colors.textDisabled, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Everything before [first] is out; [first] itself is in. What is already ticked below it stays ticked. */
private fun List<Boolean>.trimmedBefore(first: Int): List<Boolean> =
    mapIndexed { i, on ->
        when {
            i < first -> false
            i == first -> true
            else -> on
        }
    }

/** The mirror image: everything after [last] is out, and [last] itself is in. */
private fun List<Boolean>.trimmedAfter(last: Int): List<Boolean> =
    mapIndexed { i, on ->
        when {
            i > last -> false
            i == last -> true
            else -> on
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
    included: List<Boolean>,
    onIncludedChange: (List<Boolean>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    fun label(i: Int): String {
        val c = candidates[i]
        val glyph = if (c.outgoing) "▶" else "◀"
        return "#${i + 1} $glyph ${msgTypeLabel(dictionary, c.messageType)} · ${c.session}"
    }
    val first = included.indexOfFirst { it }.takeIf { it >= 0 }
    val last = included.indexOfLast { it }.takeIf { it >= 0 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        SlimLabeled("From") {
            SlimDropdown(
                value = first,
                options = candidates.indices.toList(),
                onValueChange = { picked -> if (picked != null) onIncludedChange(included.trimmedBefore(picked)) },
                displayText = ::label,
                placeholder = "first message",
                modifier = Modifier.width(180.dp).testTag("capture-from"),
            )
        }
        SlimLabeled("To") {
            SlimDropdown(
                value = last,
                options = candidates.indices.toList(),
                onValueChange = { picked -> if (picked != null) onIncludedChange(included.trimmedAfter(picked)) },
                displayText = ::label,
                placeholder = "last message",
                modifier = Modifier.width(180.dp).testTag("capture-to"),
            )
        }
    }
}

/**
 * The "what this becomes" explanation, folded behind an ⓘ so it costs one glyph of space instead of a
 * two-line paragraph. Hover reveals the full text — the same words the screen used to spend a row on.
 * The editor's sections fold their guidance the same way ([HintIcon]), so one habit reads everywhere.
 */
@Composable
private fun CaptureHelp() {
    HintIcon(
        "Sends become parameterized Send steps (fresh ids, live timestamps); responses become " +
            "assertions.\n\n●id marks where an id is minted, ○id where it must echo back — including across sessions.",
        modifier = Modifier.testTag("capture-help"),
    )
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
    val outgoing = candidate.outgoing
    Row(verticalAlignment = Alignment.CenterVertically) {
        DirectionGlyph(outgoing)
        Text(
            text = "  #${index + 1}  ${msgTypeLabel(dictionary, candidate.messageType)}",
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
    val dropped = mutableListOf<Int>()
    candidate.fields.forEach { (tag, value) ->
        val occurrence = nextOccurrence(occurrences, tag)
        val replay = transformed.getOrNull(cursor)?.takeIf { it.first == tag }?.second
        if (replay != null) cursor++
        // The payload leads. Six rows of "dropped (session/transport header)" before the first business
        // field buried what the step actually sends; the drops are summarized in one line below.
        if (replay == null) {
            dropped += tag
            return@forEach
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            TagAndName(tag, dictionary)
            OccurrenceLabel(occurrence, show = tag in repeated)
            if (replay == value) {
                Text(valueWithDescription(dictionary, tag, value), color = AppTheme.Colors.fieldValue, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            } else {
                Text(value, color = AppTheme.Colors.textDisabled, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("  →  ", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
                ReplayChip(replay, varColors)
            }
        }
    }
    if (dropped.isNotEmpty()) {
        Text(
            "${dropped.size} session/transport header${if (dropped.size == 1) "" else "s"} dropped — " +
                "re-stamped by the engine on send (${dropped.joinToString(", ")})",
            color = AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp).testTag("send-preview-dropped"),
        )
    }
}

/** Renders the replay expression as a human chip: fresh id / reuse id / send-time timestamp. */
@Composable
private fun ReplayChip(expression: String, varColors: Map<String, androidx.compose.ui.graphics.Color>) {
    val mint = MINT_EXPR.find(expression)?.groupValues?.get(1)
    val ref = REF_EXPR.find(expression)?.groupValues?.get(1)
    when {
        mint != null -> {
            VarBadge(mint, varColors[mint] ?: AppTheme.Colors.primary, VarRole.MINT)
            Text(" fresh id each run", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
        ref != null -> {
            VarBadge(ref, varColors[ref] ?: AppTheme.Colors.primary, VarRole.REFERENCE)
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
                    VarBadge(refVar, varColors[refVar] ?: AppTheme.Colors.primary, VarRole.REFERENCE)
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
 * unique. Same convention as the diff surface's occurrence suffix, and for the same reason: four identical
 * "452 PartyRole" rows are four rows the author cannot tell apart, and the one they mean is a guess.
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

/**
 * **Capture's second source.** One message per line, read by the same reader the diff's paste sheet uses — so
 * a message means the same thing whichever door it comes through. A line the reader refuses is not a
 * candidate, and it is **reported**: leaving a message out has to be something the author is told.
 *
 * The session is not decoration. It is what settles the **direction** — `SenderCompID(49)` against that
 * session's own CompIDs — so changing it re-reads the paste, and a row that was a Send may become an Expect.
 */
@Composable
private fun PasteSource(
    paste: ScenarioDoc.Capture.Paste,
    sessionOptions: List<String>,
    onChange: (String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("capture-paste")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PASTE WIRE — ONE MESSAGE PER LINE", color = AppTheme.Colors.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            SlimLabeled("Session", modifier = Modifier.padding(start = 12.dp)) {
                SlimDropdown(
                    value = paste.session.ifBlank { null },
                    options = sessionOptions,
                    onValueChange = { onChange(paste.text, it.orEmpty()) },
                    displayText = { it },
                    placeholder = "pick a session",
                    modifier = Modifier.width(160.dp).testTag("capture-paste-session"),
                )
            }
        }
        // A real text area: "one message per line" over a single-line field displayed the first line and
        // silently hid the rest — the reader then refused "line 3" of a paste the author could not even see.
        SlimField(
            value = paste.text,
            onValueChange = { onChange(it, paste.session) },
            monospace = true,
            singleLine = false,
            maxLines = 6,
            placeholder = "8=FIX.4.4|9=…|35=D|…  — one message per line",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("capture-paste-field"),
        )
        // What could not be read, in the reader's own words. Never dropped on the floor — a capture is a claim
        // about coverage, and a silent omission turns a scenario that checks four of five replies into one
        // that looks complete. GROUPED by reason: three lines refused for the same arithmetic used to print
        // the same two-line lecture three times.
        groupRefusals(paste.refused).forEach { why ->
            Text(
                "✗ $why",
                color = AppTheme.Colors.error,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp).testTag("capture-paste-refused"),
            )
        }
    }
}

/**
 * Refusals without the wall of red: `line 1: X`, `line 2: X` → `lines 1, 2: X`; and where the reasons differ
 * only in their *fact* sentence ("…sum to 091" vs "…sum to 065") but share the lecture after it verbatim,
 * later entries keep their fact and defer the lecture — three identical two-line paragraphs taught nothing
 * the first one had not. First-seen order; a refusal not shaped `line N: ` passes through untouched.
 */
internal fun groupRefusals(refused: List<String>): List<String> {
    val lineShape = Regex("^line (\\d+): (.+)$", RegexOption.DOT_MATCHES_ALL)
    val byReason = linkedMapOf<String, MutableList<String>>()
    val passthrough = mutableListOf<String>()
    refused.forEach { entry ->
        val match = lineShape.find(entry)
        if (match == null) passthrough += entry else byReason.getOrPut(match.groupValues[2]) { mutableListOf() }.add(match.groupValues[1])
    }
    val seenLectures = mutableMapOf<String, String>()
    return byReason.map { (reason, lines) ->
        val label = if (lines.size == 1) "line ${lines.single()}" else "lines ${lines.joinToString(", ")}"
        val sentenceEnd = reason.indexOf(". ")
        val fact = if (sentenceEnd < 0) reason else reason.substring(0, sentenceEnd + 1)
        val lecture = if (sentenceEnd < 0) "" else reason.substring(sentenceEnd + 2)
        val saidAt = if (lecture.isBlank()) null else seenLectures.putIfAbsent(lecture, label)
        when {
            lecture.isBlank() || saidAt == null -> "$label: $reason"
            else -> "$label: $fact (otherwise as $saidAt)"
        }
    } + passthrough
}

/**
 * **The direction a paste does not carry.** Offered only where nothing has settled it — the bytes are asked
 * first (`SenderCompID(49)` against the session), and the author only where they cannot answer.
 */
@Composable
private fun DirectionToggle(index: Int, onSet: (Int, FixMessage.Direction) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("capture-direction-$index")) {
        Text("which way?", color = AppTheme.Colors.warning, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
        SlimButton(
            "▶ Send",
            onClick = { onSet(index, FixMessage.Direction.OUTGOING) },
            color = AppTheme.Colors.textSecondary,
            modifier = Modifier.padding(end = 2.dp).testTag("capture-direction-out-$index"),
        )
        SlimButton(
            "◀ Expect",
            onClick = { onSet(index, FixMessage.Direction.INCOMING) },
            color = AppTheme.Colors.textSecondary,
            modifier = Modifier.testTag("capture-direction-in-$index"),
        )
    }
}
