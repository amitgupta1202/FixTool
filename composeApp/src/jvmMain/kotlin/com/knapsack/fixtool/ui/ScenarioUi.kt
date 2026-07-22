// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.describe
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.ScenarioAnnotations

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
        is Matcher.NotEqual -> "≠ ${matcher.value}"
        is Matcher.Presence -> "present"
        is Matcher.Absent -> "absent"
        is Matcher.Regex -> "~ /${matcher.pattern}/"
        is Matcher.OneOf -> "in {${matcher.values.joinToString(",")}}"
        is Matcher.Numeric ->
            if (matcher.tolerance == 0.0) "≈ ${matcher.expected}" else "≈ ${matcher.expected} ±${matcher.tolerance}"
        is Matcher.Range -> matcher.describe()
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
 * **What one step does with one variable.** Three things, not two: a Send *mints* a value we chose, an
 * Expect's `bindAs` *captures* one the venue chose, and either kind can be *referenced* later. The
 * badges used to collapse the two writes into one filled dot, which lost the only distinction that
 * matters when a correlation misbehaves — whose value is it.
 */
enum class VarRole { MINT, CAPTURE, REFERENCE }

/**
 * The glyph the diff gutter already teaches for this act: `↧` is the offer that writes a `bindAs`
 * (`OfferKind.CAPTURE`), `$` is the offer that writes a reference (`OfferKind.TRACK`). A reader who
 * has repaired one diff has learned these; spending different glyphs on the same ideas in the step
 * list would have been a second vocabulary for no gain. `●` stays what it was — a minted value.
 */
private val VarRole.glyph: String
    get() = when (this) {
        VarRole.MINT -> "●"
        VarRole.CAPTURE -> "↧"
        VarRole.REFERENCE -> "\$"
    }

/**
 * A correlation-variable badge: its [role] glyph and the id, in the id's own color. The same id keeps
 * the same color everywhere it appears — that continuity is what makes cross-session correlation
 * visible; the glyph says which end of it this step is.
 *
 * A glyph alone is undiscoverable, so a badge with a [tooltip] says the thing in words, and says it as
 * the *cross-reference* (which other step is the counterpart), which is the question a reader has when
 * they are about to delete or mute a step.
 */
@Composable
fun VarBadge(name: String, color: Color, role: VarRole, tooltip: String = "", modifier: Modifier = Modifier) {
    val badge =
        @Composable {
            Text(
                text = role.glyph + name,
                color = color,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                // A write is the load-bearing end of a correlation; a read is not, and reads outnumber
                // writes in every scenario. Bold the writes so a row's origin is findable at a glance.
                fontWeight = if (role == VarRole.REFERENCE) FontWeight.Normal else FontWeight.Bold,
                // **A badge is a word, not a column.** The diff's capture badge sits last in a row of fixed-
                // width cells, so it inherits whatever few pixels are left — and wrapped one character per
                // line, drawing `↧orderID` as a vertical stack nobody could read. One line, clipped with an
                // ellipsis if it must be: a truncated name is still a name, a vertical one is a glitch.
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = (if (tooltip.isBlank()) modifier else Modifier)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AppTheme.Colors.surfaceHeader)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    if (tooltip.isBlank()) badge() else AppTooltip(tooltip, modifier = modifier) { badge() }
}

/**
 * The words behind one badge's glyph, as a cross-reference to the counterpart step. A write names who
 * reads the value; a read names who writes it — including the case where the only writer is parked,
 * which is the same leaves-a-literal hazard the variables strip warns about.
 */
fun varBadgeTooltip(name: String, role: VarRole, sites: ScenarioAnnotations.VarSites?): String {
    fun steps(indices: List<Int>) = indices.joinToString(", ") { "${it + 1}" }
    fun plural(indices: List<Int>) = if (indices.size > 1) "s" else ""
    val readers = sites?.referencedAt.orEmpty()
    val writers = sites?.writtenAt.orEmpty()
    val readBy =
        if (readers.isEmpty()) " Nothing references it yet." else " Referenced by step${plural(readers)} ${steps(readers)}."
    return when (role) {
        VarRole.MINT ->
            "Mints \${$name} — this step chooses the value and puts it on the wire.$readBy"
        VarRole.CAPTURE ->
            "Captures \${$name} — the venue chose this value, and this step reads it off the reply.$readBy"
        VarRole.REFERENCE ->
            when {
                writers.isEmpty() ->
                    "References \${$name}, which no step writes — the engine leaves the literal \${$name} on the wire."
                sites?.allWritesMuted == true ->
                    "References \${$name}, but the step${plural(writers)} that write${if (writers.size > 1) "" else "s"} it " +
                        "(step${plural(writers)} ${steps(writers)}) ${if (writers.size > 1) "are" else "is"} muted — the " +
                        "write never runs, so this ships the literal \${$name} on the wire."
                else -> {
                    // Name the act, not just the step: "captured at step 2" tells a reader the value is the
                    // venue's, which is the difference between a broken scenario and a venue that changed.
                    val verb =
                        when {
                            sites != null && sites.mintedAt.isEmpty() -> "captured"
                            sites != null && sites.capturedAt.isEmpty() -> "minted"
                            else -> "written"
                        }
                    "References \${$name}, $verb at step${plural(writers)} ${steps(writers)}. " +
                        "Delete or mute that step and this reference ships the literal \${$name} on the wire."
                }
            }
    }
}

/**
 * The badges for one step: what it writes first, then what it reads. Pass [sites] to give each badge
 * its cross-reference tooltip; without it the badges are silent, as they were.
 */
@Composable
fun VarBadges(
    vars: ScenarioAnnotations.StepVars,
    colors: Map<String, Color>,
    sites: Map<String, ScenarioAnnotations.VarSites> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // A step's writes are all one kind — it is a Send, which mints, or an Expect, which captures.
        val writeRole = if (vars.fromReply) VarRole.CAPTURE else VarRole.MINT
        (vars.minted.map { it to writeRole } + vars.referenced.map { it to VarRole.REFERENCE }).forEach { (name, role) ->
            VarBadge(
                name,
                colors[name] ?: AppTheme.Colors.primary,
                role = role,
                tooltip = sites[name]?.let { varBadgeTooltip(name, role, it) }.orEmpty(),
                modifier = Modifier.padding(end = 3.dp),
            )
        }
    }
}

/**
 * One chip of a [VariablesStrip]: a variable's name, the value a run left in it (null when there is no
 * run to ask), and whether it is the warning kind — referenced somewhere, minted nowhere, which the
 * engine leaves **literal on the wire**: ten silent characters of `${idO}` in a real FIX field.
 */
data class VariableChipData(
    val name: String,
    val value: String?,
    val warning: Boolean = false,
    val tooltip: String = "",
)

/** `a1b2c3d4e5f6a7b8…` — enough of a value to recognise it in a grid row, no more. */
fun shortValue(value: String, max: Int = 18): String = if (value.length <= max) value else value.take(max - 1) + "…"

/**
 * **The variables of a scenario (or of a run), on one line.** The step badges say which step touches a
 * name; this strip is the other half of the answer — what the names *are*, what the last run left in
 * them, and which of them are typos nothing ever mints. In the diff window the chips are clickable and
 * highlight every row that references or carries the variable; in the editor they are informational.
 */
@Composable
fun VariablesStrip(
    chips: List<VariableChipData>,
    colors: Map<String, Color>,
    highlighted: String? = null,
    onToggle: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.horizontalScroll(rememberScrollState()).testTag("variables-strip"),
    ) {
        Text("VARIABLES", color = AppTheme.Colors.textDisabled, fontSize = 9.sp, modifier = Modifier.padding(end = 6.dp))
        chips.forEach { chip ->
            val color = if (chip.warning) AppTheme.Colors.warning else colors[chip.name] ?: AppTheme.Colors.primary
            AppTooltip(chip.tooltip) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(AppTheme.Colors.surfaceHeader)
                            .then(if (chip.name == highlighted) Modifier.border(1.dp, color, RoundedCornerShape(3.dp)) else Modifier)
                            .then(if (onToggle != null) Modifier.clickable { onToggle(chip.name) } else Modifier)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                            .testTag("variable-chip-${chip.name}"),
                ) {
                    Text(
                        text = (if (chip.warning) "⚠ " else "●") + chip.name,
                        color = color,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    when {
                        chip.warning ->
                            Text(" never minted", color = color, fontSize = 9.sp)
                        chip.value != null ->
                            Text(
                                " = ${shortValue(chip.value)}",
                                color = AppTheme.Colors.textSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                    }
                }
            }
        }
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
