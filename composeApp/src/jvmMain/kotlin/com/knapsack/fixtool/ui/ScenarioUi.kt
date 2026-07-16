// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TemporalKind

/**
 * Shared visual vocabulary of the Scenarios workbench: session color badges, direction glyphs,
 * and correlation-variable badges. Capture-review and the editor render the *same* flow language
 * so a scenario looks identical while being curated and while being edited.
 */

/**
 * Guidance behind one glyph. The editor's sections used to spend a standing paragraph each on words a
 * user needs exactly once — hover the ⓘ and the same words are there, and a user who has learned the
 * tool pays one glyph of space for them instead of two rows.
 */
@Composable
fun HintIcon(text: String, modifier: Modifier = Modifier) {
    AppTooltip(text) {
        Text("ⓘ", color = AppTheme.Colors.info, fontSize = 13.sp, modifier = modifier)
    }
}

/** Stable session→color assignment by first appearance, reusing the app's distinct-color palette. */
fun sessionColorMap(titles: List<String>): Map<String, Color> =
    titles.distinct().mapIndexed { i, t -> t to AppTheme.Colors.usernameColors[i % AppTheme.Colors.usernameColors.size] }.toMap()

/** Stable variable→color assignment (offset into the palette so ids read differently to sessions). */
fun varColorMap(names: List<String>): Map<String, Color> =
    names.distinct().mapIndexed { i, n ->
        n to AppTheme.Colors.usernameColors[(i + 3) % AppTheme.Colors.usernameColors.size]
    }.toMap()

/** The session a step targets, or null for "the active session". */
fun ScenarioStep.sessionOrNull(): String? =
    when (this) {
        is ScenarioStep.Send -> session
        is ScenarioStep.Wait -> session
        is ScenarioStep.Expect -> session
        is ScenarioStep.ClearMessages -> session
        is ScenarioStep.ResetSeqNum -> session
    }

/** Every session named by a scenario, in first-use order. */
fun Scenario.sessionsInvolved(): List<String> =
    (setup + steps + teardown).mapNotNull { it.sessionOrNull() }.distinct()

/** "QUOTE_REQUEST" → "QuoteRequest" (dictionary enum descriptions are SHOUTY_SNAKE). */
private fun prettify(description: String): String =
    description.split('_').joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }

/** Human message-type label, e.g. "QuoteRequest (R)"; falls back to the raw type. */
fun msgTypeLabel(dictionary: FixDictionary?, messageType: String?): String {
    if (messageType.isNullOrBlank()) return "?"
    val name = dictionary?.getFieldEnumValues(35)?.firstOrNull { it.first == messageType }?.second
    return if (name != null) "${prettify(name)} ($messageType)" else messageType
}

/** One-line step description used by both the review and editor flow lists. */
fun stepLabel(step: ScenarioStep, dictionary: FixDictionary?): String =
    when (step) {
        is ScenarioStep.Send -> "Send ${msgTypeLabel(dictionary, step.messageTypeOrNull())}"
        is ScenarioStep.Expect -> {
            val type = step.expectation.messageType ?: step.match?.messageType
            "Expect ${msgTypeLabel(dictionary, type)} · ${step.expectation.fields.size} tags"
        }
        is ScenarioStep.Wait -> "Wait ${step.state ?: "message"} (${step.timeoutMs}ms)"
        is ScenarioStep.ClearMessages -> "Clear messages"
        is ScenarioStep.ResetSeqNum -> "Reset seqnums"
    }

/** MsgType(35) of a Send's raw, if present. */
fun ScenarioStep.Send.messageTypeOrNull(): String? =
    raw.split('|', '\u0001').firstOrNull { it.startsWith("35=") }?.substringAfter('=')

/** "2" → "2 (FILLED)" when the dictionary defines the value; otherwise the value unchanged. */
fun valueWithDescription(dictionary: FixDictionary?, tag: Int, value: String): String {
    val description = dictionary?.getFieldValueDescription(tag, value)?.takeIf { it != value }
    return if (description != null) "$value ($description)" else value
}

/** Dictionary-aware [matcherSummary]: enum values in Exact/OneOf carry their names for FIX newcomers. */
fun matcherSummary(matcher: Matcher, dictionary: FixDictionary?, tag: Int): String =
    when (matcher) {
        is Matcher.Exact -> "= ${valueWithDescription(dictionary, tag, matcher.value)}"
        is Matcher.OneOf -> "in {${matcher.values.joinToString(", ") { valueWithDescription(dictionary, tag, it) }}}"
        else -> matcherSummary(matcher)
    }

/** Compact human description of a matcher, e.g. "≈ 1.0851 ±0.0001", "now ±60s", "= ${id0}". */
fun matcherSummary(matcher: Matcher): String =
    when (matcher) {
        is Matcher.Exact -> "= ${matcher.value}"
        is Matcher.Presence -> "present"
        is Matcher.Absent -> "absent"
        is Matcher.Regex -> "~ /${matcher.pattern}/"
        is Matcher.OneOf -> "in {${matcher.values.joinToString(",")}}"
        is Matcher.Numeric ->
            if (matcher.tolerance == 0.0) "≈ ${matcher.expected}" else "≈ ${matcher.expected} ±${matcher.tolerance}"
        is Matcher.Temporal ->
            if (matcher.kind == TemporalKind.TODAY) "today" else "now ±${matcher.toleranceSeconds}s"
        is Matcher.Reference -> "= ${matcher.expression}"
    }

/** A colored dot + session title; the session identity of a flow row. */
@Composable
fun SessionBadge(title: String?, color: Color, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = title ?: "(active)",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/** ▶ outgoing (we send) / ◀ incoming (we assert), in the app's direction colors. */
@Composable
fun DirectionGlyph(outgoing: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (outgoing) "▶" else "◀",
        color = if (outgoing) AppTheme.Colors.messageOutgoing else AppTheme.Colors.messageIncoming,
        fontSize = 11.sp,
        modifier = modifier,
    )
}

/**
 * A correlation-variable badge: filled when this step *mints* the id, outlined-ish (dimmed) when it
 * *reuses/checks* it. The same id keeps the same color everywhere it appears — that continuity is
 * what makes cross-session correlation visible.
 */
@Composable
fun VarBadge(name: String, color: Color, minted: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (minted) "●$name" else "○$name",
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (minted) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(AppTheme.Colors.surfaceHeader)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** The badges for one step: minted ids first (filled), then referenced ids (hollow). */
@Composable
fun VarBadges(minted: List<String>, referenced: List<String>, colors: Map<String, Color>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        minted.forEach { VarBadge(it, colors[it] ?: AppTheme.Colors.primary, minted = true, modifier = Modifier.padding(end = 3.dp)) }
        referenced.forEach { VarBadge(it, colors[it] ?: AppTheme.Colors.primary, minted = false, modifier = Modifier.padding(end = 3.dp)) }
    }
}

/**
 * The failure a run report speaks for: the first step that did not pass. The runner stops the `steps`
 * phase there, so it is *the* failure — and the one the report's reconcile route must lead to. One
 * definition, so the sentence and the button can never be about different steps.
 */
fun ScenarioResult.firstFailure(): StepResult? = steps.firstOrNull { !it.passed }

/** Small width-stable index cell for flow rows. */
@Composable
fun RowIndex(index: Int, dimmed: Boolean = false) {
    Text(
        text = "%2d".format(index + 1),
        color = if (dimmed) AppTheme.Colors.textDisabled else AppTheme.Colors.textSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.width(26.dp),
    )
}
