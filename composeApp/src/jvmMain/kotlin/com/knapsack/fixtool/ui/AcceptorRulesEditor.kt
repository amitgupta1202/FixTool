package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
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
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (rules.isEmpty()) "No rules — incoming messages get no reply" else "${rules.size} rule(s), first match wins",
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
                onChange = { updated -> onRulesChange(rules.replaced(ruleIndex, updated)) },
                onDelete = { onRulesChange(rules.without(ruleIndex)) },
                onMove = { by -> onRulesChange(rules.moved(ruleIndex, by)) },
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: AcceptorResponseRule,
    position: Int,
    total: Int,
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
                .padding(4.dp),
    ) {
        // ---- trigger
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    onChange: (FieldCondition) -> Unit,
    onDelete: () -> Unit,
) {
    val matcher = condition.parsed()
    Column(modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Spacer(Modifier.weight(1f))
            TooltipIconButton("Remove condition", onDelete, Modifier.size(16.dp)) {
                Icon(Icons.Default.Close, "Remove condition", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(10.dp))
            }
        }
        if (matcher == null) {
            // Carried verbatim rather than replaced with a default: a matcher this build cannot read
            // is still the author's, and silently swapping it for `exact ""` would lose what they wrote
            // while making the row look fine.
            Text(
                text = "⚠ ${condition.reason()}",
                color = AppTheme.Colors.warning,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            MatcherEditor(
                matcher = matcher,
                capturedValue = (matcher as? Matcher.Exact)?.value.orEmpty(),
                onChange = { updated -> onChange(condition.copy(matcher = MatcherCodec.matcherToJson(updated))) },
                modifier = Modifier.padding(top = 2.dp),
                types = TRIGGER_MATCHER_TYPES,
                paramsWidth = 190.dp,
            )
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
    onChange: (ResponseStep) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    // Two rows, not one. A raw FIX template does not fit beside four controls in a side panel — at one
    // line it showed about twenty characters, so the author was editing a string they could not read.
    // The timing controls keep their row; the template gets the panel's full width and wraps.
    Column(modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Spacer(Modifier.weight(1f))
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
        SlimField(
            value = step.template,
            onValueChange = { onChange(step.copy(template = it)) },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            monospace = true,
            tintBlank = true,
            placeholder = "35=8|150=0|39=0|11=\${req.11}|",
            singleLine = false,
            maxLines = 4,
        )
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
