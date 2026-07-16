// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.model.scenario.validationError

/** The matcher type names, in the order shown in the editor's dropdown. */
val MATCHER_TYPES = listOf("exact", "presence", "absent", "oneOf", "regex", "numeric", "temporal", "reference")

/**
 * Every matcher's parameters render inside one slot of this width. Each type used to bring its own —
 * exact 130, reference 180, numeric 80+56, regex 120 plus a label that appeared from nowhere — so the
 * column's right edge was ragged and a row changed shape every time its chip changed type.
 */
private val PARAMS_WIDTH = 210.dp

/** One-line explanation per matcher type, shown in the type dropdown for users new to FIX testing. */
private val MATCHER_HELP = mapOf(
    "exact" to "value must equal exactly",
    "presence" to "tag must exist, any value",
    "absent" to "tag must NOT appear",
    "oneOf" to "value in a set",
    "regex" to "value matches a pattern",
    "numeric" to "number compare ± tolerance",
    "temporal" to "date/time vs now/today",
    "reference" to "equals a \${...} expression",
)

/** Short type label for a [Matcher] (matches the control-surface encodings). */
fun matcherTypeName(matcher: Matcher): String =
    when (matcher) {
        is Matcher.Exact -> "exact"
        is Matcher.Presence -> "presence"
        is Matcher.Absent -> "absent"
        is Matcher.OneOf -> "oneOf"
        is Matcher.Regex -> "regex"
        is Matcher.Numeric -> "numeric"
        is Matcher.Temporal -> "temporal"
        is Matcher.Reference -> "reference"
    }

/** The regex metacharacters, escaped one by one so the seeded pattern stays readable (`1\.5`, not `\Q1.5\E`). */
private val REGEX_META = Regex("""[\\.\[\]{}()*+?^$|]""")

/**
 * The captured value as a pattern that matches **only itself**.
 *
 * Seeding the raw value was a false green, and a quiet one: a Price captured as `1.5` became the
 * pattern `1.5`, where `.` is any character — so an actual price of `125` matched it and the
 * assertion passed. Switching a row to "regex" is the ordinary way an author loosens it, and it was
 * loosening it further than anyone asked, in the direction of passing. An escaped seed means the same
 * thing as `exact` until the author deliberately widens it.
 */
private fun literalPattern(value: String): String = value.replace(REGEX_META) { "\\${it.value}" }

/** A sensible default [Matcher] when switching to [type], seeded from the captured [value]. */
fun defaultMatcherForType(type: String, value: String): Matcher =
    when (type) {
        "presence" -> Matcher.Presence
        "absent" -> Matcher.Absent
        "oneOf" -> Matcher.OneOf(if (value.isBlank()) emptyList() else listOf(value))
        "regex" -> Matcher.Regex(if (value.isBlank()) ".*" else literalPattern(value))
        "numeric" -> Matcher.Numeric(value.toDoubleOrNull() ?: 0.0, 0.0)
        "temporal" -> Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)
        "reference" -> Matcher.Reference("\${out.D.11}")
        else -> Matcher.Exact(value)
    }

/**
 * Edits one [Matcher]: a type dropdown (with a one-line explanation per type) plus type-specific
 * fields, all in the app's slim-input style. Switching type seeds a sensible default from the
 * captured value. Emits the new matcher via [onChange].
 */
@Composable
fun MatcherEditor(
    matcher: Matcher,
    capturedValue: String,
    onChange: (Matcher) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The types this editor may switch to. The reconcile view passes a **narrowed** list.
     *
     * `reference` is not a loosening — it is a *correlation the author writes*, naming a scenario variable
     * the editor cannot know. Offering it from a dropdown meant `defaultMatcherForType` had to invent one, so
     * it seeded a hardcoded `${out.D.11}` onto whatever row was clicked: one click on a failing OrdStatus row
     * asserted that OrdStatus equals the last NewOrderSingle's ClOrdID. Worse, a reference cannot be judged
     * offline, so the row stopped being red, dropped out of the verdict counts, and the bar announced "every
     * assertion would now pass". A one-click manufactured green, in the surface the model names as the
     * likeliest place to manufacture one.
     *
     * Both hazards are properties of an editor with **no scope**. With [scopeVariables] in hand the diff
     * passes the full list again: the seed names a real variable (the one whose value the row actually
     * carries, when there is one) instead of inventing `${out.D.11}`, and the row is judged live against
     * the run's own values, so a wrong pick is a visible red, not a silent unjudged green.
     */
    types: List<String> = MATCHER_TYPES,
    /**
     * The run's variables, when the caller has a run in hand — the reference type then edits as a
     * **picker over these names** (mistyping impossible, value visible) rather than a free-text field.
     */
    scopeVariables: List<ScenarioVariable> = emptyList(),
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SlimDropdown(
            value = matcherTypeName(matcher),
            options = types,
            onValueChange = { type ->
                type?.let { onChange(seededMatcherForType(it, capturedValue, scopeVariables)) }
            },
            displayText = { it },
            itemText = { type -> MATCHER_HELP[type]?.let { "$type — $it" } ?: type },
            modifier = Modifier.width(90.dp),
        )
        // ONE slot, constant width, whatever the type — so the column has a straight edge and a row does
        // not change shape when its chip changes type. What each type needs is drawn inside it; a label
        // it used to wear beside the field ("pattern", "any of") is its field's placeholder now.
        Box(modifier = Modifier.width(PARAMS_WIDTH)) { MatcherParams(matcher, onChange, scopeVariables) }
    }
}

/**
 * [defaultMatcherForType], except that a `reference` is seeded from the **scope** when one is in hand:
 * the variable whose value this row actually carries (the correlation the author almost certainly
 * means), else the first — never an invented `${out.D.11}`.
 */
private fun seededMatcherForType(type: String, value: String, scopeVariables: List<ScenarioVariable>): Matcher =
    if (type == "reference" && scopeVariables.isNotEmpty()) {
        val match = scopeVariables.firstOrNull { it.value == value } ?: scopeVariables.first()
        Matcher.Reference("\${${match.name}}")
    } else {
        defaultMatcherForType(type, value)
    }

@Composable
private fun MatcherParams(matcher: Matcher, onChange: (Matcher) -> Unit, scopeVariables: List<ScenarioVariable> = emptyList()) {
    when (matcher) {
        is Matcher.Presence, is Matcher.Absent -> Unit
        is Matcher.Exact ->
            SlimField(matcher.value, { onChange(Matcher.Exact(it)) }, monospace = true, modifier = Modifier.fillMaxWidth())
        is Matcher.Regex -> {
            // The one place a pattern is judged. The codec carries a bad one through unharmed, so the
            // author never loses a scenario to a half-typed character class — they are told here,
            // while they are typing it, and the row stays red until it compiles.
            val problem = remember(matcher.pattern) { matcher.validationError() }
            Column {
                SlimField(
                    matcher.pattern,
                    { onChange(Matcher.Regex(it)) },
                    monospace = true,
                    textColor = if (problem != null) AppTheme.Colors.error else AppTheme.Colors.text,
                    placeholder = "pattern",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (problem != null) {
                    Text(
                        problem,
                        color = AppTheme.Colors.error,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is Matcher.Reference -> {
            // A picker over the scope's names when the expression IS one of them — the name cannot be
            // mistyped, and the value it held this run is right there in the menu row. An expression the
            // scope cannot name (`${out.D.11}`, a hand-written form) keeps the free-text field: the picker
            // must never eat an expression it could not have produced.
            val scopeName = scopeVariables.firstOrNull { "\${${it.name}}" == matcher.expression.trim() }
            if (scopeName != null) {
                SlimDropdown(
                    value = scopeName,
                    options = scopeVariables,
                    onValueChange = { picked -> picked?.let { onChange(Matcher.Reference("\${${it.name}}")) } },
                    displayText = { "\${${it.name}}" },
                    itemText = { "\${${it.name}} = ${shortValue(it.value)}" },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                SlimField(
                    matcher.expression,
                    { onChange(Matcher.Reference(it)) },
                    monospace = true,
                    placeholder = "\${...}",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is Matcher.OneOf ->
            SlimField(
                matcher.values.joinToString(","),
                { onChange(Matcher.OneOf(it.split(",").map(String::trim).filter(String::isNotEmpty))) },
                monospace = true,
                placeholder = "value, value, …",
                modifier = Modifier.fillMaxWidth(),
            )
        is Matcher.Numeric ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SlimField(
                    numText(matcher.expected),
                    { onChange(Matcher.Numeric(it.toDoubleOrNull() ?: matcher.expected, matcher.tolerance)) },
                    monospace = true,
                    modifier = Modifier.weight(1f),
                )
                // A bare "±" rather than a "± tol" label: in a narrow diff column the label had nowhere to go
                // and wrapped to one character per line, which is not a label, it is a decoration.
                Text("±", color = AppTheme.Colors.textDisabled, fontSize = 11.sp, maxLines = 1)
                SlimField(
                    numText(matcher.tolerance),
                    { onChange(Matcher.Numeric(matcher.expected, it.toDoubleOrNull() ?: matcher.tolerance)) },
                    monospace = true,
                    modifier = Modifier.width(64.dp),
                )
            }
        is Matcher.Temporal ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SlimDropdown(
                    value = matcher.kind,
                    options = TemporalKind.values().toList(),
                    onValueChange = { kind -> kind?.let { onChange(Matcher.Temporal(it, matcher.toleranceSeconds)) } },
                    displayText = { if (it == TemporalKind.TODAY) "today" else "now ±" },
                    modifier = Modifier.width(84.dp),
                )
                if (matcher.kind == TemporalKind.NOW_WITHIN_TOLERANCE) {
                    Text("±", color = AppTheme.Colors.textDisabled, fontSize = 11.sp, maxLines = 1)
                    SlimField(
                        matcher.toleranceSeconds.toString(),
                        { onChange(Matcher.Temporal(matcher.kind, it.toLongOrNull() ?: matcher.toleranceSeconds)) },
                        monospace = true,
                        modifier = Modifier.width(48.dp),
                    )
                    Text("s", color = AppTheme.Colors.textDisabled, fontSize = 11.sp, maxLines = 1)
                }
            }
    }
}

/** Renders a double without a trailing ".0" so integer-ish values read cleanly. */
private fun numText(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
