package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MatcherCodec

/**
 * Authoring for an acceptor profile's auto-response rules.
 *
 * Until this existed the rules were reachable only by hand-editing a profile's JSON or POSTing to
 * `/profiles`, so every acceptor feature landed for agents and not for the testers the tool is aimed
 * at. A sequence made that worse rather than better: `steps` is a nested array, and the one thing an
 * author gets wrong about it — that a step's delay is measured from the *previous* step, not from the
 * trigger — is invisible in raw JSON. So each row states its own gap **and** the running total beside
 * it; the two numbers agreeing is what tells the author they read the field the way FixTool does.
 *
 * ### What the editor does not do
 *
 * It does not normalise. A rule still carrying the older single-`responseTemplate` spelling is *shown*
 * as the one step it plays, but the rule on disk is left exactly as it was until the author changes
 * something about it — at which point that edit rewrites it to `steps`. Rewriting every rule the
 * moment a panel is opened is the same silent-mutation-on-save that lost these rules in the first
 * place; migration is a consequence of editing, never of looking.
 */
@Composable
fun AcceptorRulesEditor(
    rules: List<AcceptorResponseRule>,
    onRulesChange: (List<AcceptorResponseRule>) -> Unit,
    modifier: Modifier = Modifier,
    /** Names the values a condition's tag can take, so a trigger reads `8 (REJECTED)` and not `8`. */
    dictionary: FixDictionary? = null,
) {
    // The connection panel is drag-resizable from a tenth of the window to six tenths of it, so this
    // editor is asked to live at anything from ~250dp to ~1000dp. Sized for the narrow end only, it
    // stayed narrow-shaped inside all that room: a template wrapping across three lines in a column
    // with 700dp of empty space beside it. Measured here, once, and the answer handed down — so every
    // row of every rule agrees about which layout it is in.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ruleSummary(rules),
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 9.sp,
                )
                TooltipIconButton(
                    tooltip = "Add rule",
                    onClick = { onRulesChange(rules + AcceptorResponseRule(whenMsgType = "", steps = listOf(ResponseStep(template = "")))) },
                    modifier = Modifier.size(18.dp),
                ) {
                    Icon(Icons.Default.Add, "Add rule", tint = AppTheme.Colors.primary, modifier = Modifier.size(14.dp))
                }
            }

            rules.forEachIndexed { ruleIndex, rule ->
                RuleCard(
                    rule = rule,
                    position = ruleIndex,
                    total = rules.size,
                    dictionary = dictionary,
                    wide = wide,
                    onChange = { updated -> onRulesChange(rules.replaced(ruleIndex, updated)) },
                    onDelete = { onRulesChange(rules.without(ruleIndex)) },
                    onMove = { by -> onRulesChange(rules.moved(ruleIndex, by)) },
                )
            }
        }
    }
}

/**
 * Below this the rows stack; at or above it they sit on one line.
 *
 * It is the width at which a step's template still gets ~300dp after the number, the delay, the
 * running total and three buttons have taken theirs — i.e. the point where one line stops being a
 * worse way to show the same string.
 */
private val WIDE_LAYOUT_MIN = 560.dp

@Composable
private fun RuleCard(
    rule: AcceptorResponseRule,
    position: Int,
    total: Int,
    dictionary: FixDictionary?,
    wide: Boolean,
    onChange: (AcceptorResponseRule) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    // The editor always edits the sequence, whichever way the rule spelled it, and any edit writes the
    // sequence back — which is what retires the older spelling for that rule and only that rule.
    val steps = rule.sequence()

    fun withSteps(updated: List<ResponseStep>) = onChange(rule.copy(steps = updated, responseTemplate = ""))

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(2.dp))
                .padding(4.dp)
                // Dimmed, not hidden and not greyed into unreadability: a disabled rule is still being
                // read and edited — switching it off is how an author asks "what happens without this
                // one", and the answer is only useful while they can still see what "this one" was.
                .alpha(if (rule.enabled) 1f else 0.45f),
    ) {
        // ---- trigger
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TooltipIconButton(
                tooltip = if (rule.enabled) "Disable this rule" else "Enable this rule",
                onClick = { onChange(rule.copy(enabled = !rule.enabled)) },
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    imageVector = if (rule.enabled) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (rule.enabled) "Enabled" else "Disabled",
                    tint = if (rule.enabled) AppTheme.Colors.primary else AppTheme.Colors.textDisabled,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text("When 35=", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
            SlimField(
                value = rule.whenMsgType,
                onValueChange = { onChange(rule.copy(whenMsgType = it)) },
                modifier = Modifier.width(40.dp),
                monospace = true,
                tintBlank = true,
                placeholder = "D",
            )
            Spacer(Modifier.weight(1f))
            // First match wins, so a rule's position is part of what it means — not a display preference.
            TooltipIconButton("Move earlier", { onMove(-1) }, Modifier.size(16.dp), enabled = position > 0) {
                Icon(Icons.Default.ArrowUpward, "Up", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(12.dp))
            }
            TooltipIconButton("Move later", { onMove(1) }, Modifier.size(16.dp), enabled = position < total - 1) {
                Icon(Icons.Default.ArrowDownward, "Down", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(12.dp))
            }
            TooltipIconButton("Delete rule", onDelete, Modifier.size(16.dp)) {
                Icon(Icons.Default.Close, "Delete rule", tint = AppTheme.Colors.error, modifier = Modifier.size(12.dp))
            }
        }

        // Both spellings, shown as the one list they behave as. Any edit writes the whole list back as
        // `conditions` and clears `whenFields` — the same rule as the reply's two spellings: migration
        // is a consequence of editing, never of looking.
        val conditions = rule.trigger()

        fun withConditions(updated: List<FieldCondition>) =
            onChange(rule.copy(whenFields = emptyMap(), conditions = updated))

        conditions.forEachIndexed { index, condition ->
            ConditionRow(
                condition = condition,
                dictionary = dictionary,
                wide = wide,
                onChange = { updated -> withConditions(conditions.replaced(index, updated)) },
                onDelete = { withConditions(conditions.without(index)) },
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 8.dp)) {
            SlimButton(
                text = "+ condition",
                onClick = {
                    withConditions(conditions + FieldCondition(0, MatcherCodec.matcherToJson(Matcher.Exact(""))))
                },
            )
        }

        // ---- reply
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reply", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
            SlimButton(text = "+ step", onClick = { withSteps(steps + ResponseStep(template = "", delayMillis = 0)) })
        }

        var offset = 0L
        steps.forEachIndexed { stepIndex, step ->
            offset += step.delayMillis.coerceAtLeast(0)
            StepRow(
                step = step,
                number = stepIndex + 1,
                offsetMillis = offset,
                wide = wide,
                canMoveUp = stepIndex > 0,
                canMoveDown = stepIndex < steps.size - 1,
                onChange = { updated -> withSteps(steps.replaced(stepIndex, updated)) },
                onDelete = { withSteps(steps.without(stepIndex)) },
                onMove = { by -> withSteps(steps.moved(stepIndex, by)) },
            )
        }

        // Said here because there is nowhere else it can be said: a rule that cannot reply looks
        // configured, and the engine only warns to a log nobody has open.
        rule.validationError()?.let { problem ->
            Text(
                text = "⚠ $problem",
                color = AppTheme.Colors.warning,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "3 rules, first match wins · 1 off" — a switched-off rule has to be visible in the count, not just on its card. */
private fun ruleSummary(rules: List<AcceptorResponseRule>): String {
    if (rules.isEmpty()) return "No rules — incoming messages get no reply"
    val off = rules.count { !it.enabled }
    return "${rules.size} rule(s), first match wins" + if (off > 0) " · $off off" else ""
}

/**
 * One condition: the tag on its own row with the delete button, the matcher on the next.
 *
 * The matcher is edited by [MatcherEditor] — *the* matcher editor, the one the scenario workbench
 * uses. That is the whole point of the trigger speaking [Matcher] rather than a conditional DSL of
 * its own: a second editor would be a second vocabulary wearing the first one's clothes, and the two
 * would drift. `reference` is withheld because it resolves against a scenario run's scope and a
 * trigger has none; [FieldCondition.reason] refuses one that arrives by hand-edited JSON.
 */
@Composable
private fun ConditionRow(
    condition: FieldCondition,
    dictionary: FixDictionary?,
    wide: Boolean,
    onChange: (FieldCondition) -> Unit,
    onDelete: () -> Unit,
) {
    val matcher = condition.parsed()
    val enumValues =
        remember(dictionary, condition.tag) {
            if (dictionary?.hasFieldValues(condition.tag) == true) {
                dictionary.getFieldEnumValues(condition.tag)
            } else {
                emptyList()
            }
        }

    val tagField: @Composable () -> Unit = {
        Text("and tag", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
        SlimField(
            value = if (condition.tag == 0) "" else condition.tag.toString(),
            onValueChange = { typed ->
                onChange(condition.copy(tag = typed.filter { it.isDigit() }.toIntOrNull() ?: 0))
            },
            modifier = Modifier.width(44.dp),
            monospace = true,
            tintBlank = true,
            placeholder = "38",
        )
    }
    val removeButton: @Composable () -> Unit = {
        TooltipIconButton("Remove condition", onDelete, Modifier.size(16.dp)) {
            Icon(Icons.Default.Close, "Remove condition", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(10.dp))
        }
    }
    // Carried verbatim rather than replaced with a default: a matcher this build cannot read is still
    // the author's, and silently swapping it for `exact ""` would lose what they wrote while making the
    // row look fine.
    val unreadable: @Composable (Modifier) -> Unit = { mod ->
        Text("⚠ ${condition.reason()}", color = AppTheme.Colors.warning, fontSize = 9.sp, modifier = mod)
    }
    val editor: @Composable (Modifier) -> Unit = { mod ->
        MatcherEditor(
            matcher = matcher ?: Matcher.Exact(""),
            capturedValue = (matcher as? Matcher.Exact)?.value.orEmpty(),
            onChange = { updated -> onChange(condition.copy(matcher = MatcherCodec.matcherToJson(updated))) },
            modifier = mod,
            types = TRIGGER_MATCHER_TYPES,
            // The params slot grows with the panel: at the narrow end it is what fits, at the wide end
            // it is what an oneOf set or a two-bound range actually wants.
            paramsWidth = if (wide) 260.dp else 190.dp,
            enumValues = enumValues,
        )
    }

    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tagField()
            if (matcher == null) unreadable(Modifier.weight(1f)) else editor(Modifier)
            Spacer(Modifier.weight(1f))
            removeButton()
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tagField()
                Spacer(Modifier.weight(1f))
                removeButton()
            }
            if (matcher == null) unreadable(Modifier.padding(top = 2.dp)) else editor(Modifier.padding(top = 2.dp))
        }
    }
}

/** Every matcher type except `reference`, which needs a scenario scope a trigger does not have. */
private val TRIGGER_MATCHER_TYPES = MATCHER_TYPES.filterNot { it == "reference" }

@Composable
private fun StepRow(
    step: ResponseStep,
    number: Int,
    offsetMillis: Long,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    wide: Boolean,
    onChange: (ResponseStep) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val timing: @Composable () -> Unit = {
        Text("$number.", color = AppTheme.Colors.textDisabled, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("+", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
        SlimField(
            // Only digits reach the model, so a half-typed value cannot silently become 0 and move
            // the step to a moment the author never chose.
            value = step.delayMillis.toString(),
            onValueChange = { typed ->
                val digits = typed.filter { it.isDigit() }
                onChange(step.copy(delayMillis = digits.toLongOrNull() ?: 0L))
            },
            modifier = Modifier.width(48.dp),
            monospace = true,
        )
        // The gap and the running total, side by side. The gap is what the author writes; the total
        // is what the counterparty experiences, and seeing both is what reveals that this field is
        // relative — the one thing about a sequence that raw JSON cannot warn anybody about.
        Text("ms → ${offsetMillis}ms", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
    }
    val buttons: @Composable () -> Unit = {
        TooltipIconButton("Move earlier", { onMove(-1) }, Modifier.size(16.dp), enabled = canMoveUp) {
            Icon(Icons.Default.ArrowUpward, "Up", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(10.dp))
        }
        TooltipIconButton("Move later", { onMove(1) }, Modifier.size(16.dp), enabled = canMoveDown) {
            Icon(Icons.Default.ArrowDownward, "Down", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(10.dp))
        }
        TooltipIconButton("Delete step", onDelete, Modifier.size(16.dp)) {
            Icon(Icons.Default.Close, "Delete step", tint = AppTheme.Colors.error, modifier = Modifier.size(10.dp))
        }
    }
    // Wrapping is what a narrow panel can do, not what anyone wants: the template is one line of FIX
    // and reads as one line. Given the width, it gets one — and only then, because at the narrow end a
    // single row showed about twenty characters of it.
    val template: @Composable (Modifier) -> Unit = { mod ->
        SlimField(
            value = step.template,
            onValueChange = { onChange(step.copy(template = it)) },
            modifier = mod,
            monospace = true,
            tintBlank = true,
            placeholder = "35=8|150=0|39=0|11=\${req.11}|",
            singleLine = wide,
            maxLines = if (wide) 1 else 4,
        )
    }

    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            timing()
            template(Modifier.weight(1f))
            buttons()
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                timing()
                Spacer(Modifier.weight(1f))
                buttons()
            }
            template(Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }
}

// ----------------------------------------------------------------- list edits
//
// Ordering is meaning in both lists — rules are first-match-wins, steps are played in order — so a
// move is a real edit and not a view preference. These keep that arithmetic in one place.

internal fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().apply { this[index] = value }

internal fun <T> List<T>.without(index: Int): List<T> = toMutableList().apply { removeAt(index) }

/** Moves the item at [index] by [by] places, clamped — a move off either end is a no-op, not a wrap. */
internal fun <T> List<T>.moved(index: Int, by: Int): List<T> {
    val target = (index + by).coerceIn(0, lastIndex)
    if (target == index) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
