// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.model.scenario.validationError

/** The matcher type names, in the order shown in the editor's dropdown. */
val MATCHER_TYPES = listOf("exact", "presence", "absent", "oneOf", "regex", "numeric", "temporal", "reference")

/** Shared by the pattern field and the error beneath it, so the message wraps instead of widening the row. */
private val PATTERN_FIELD_WIDTH = 120.dp

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
     */
    types: List<String> = MATCHER_TYPES,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SlimDropdown(
            value = matcherTypeName(matcher),
            options = types,
            onValueChange = { type -> type?.let { onChange(defaultMatcherForType(it, capturedValue)) } },
            displayText = { it },
            itemText = { type -> MATCHER_HELP[type]?.let { "$type — $it" } ?: type },
            modifier = Modifier.width(90.dp),
        )
        MatcherParams(matcher, onChange)
    }
}

@Composable
private fun MatcherParams(matcher: Matcher, onChange: (Matcher) -> Unit) {
    when (matcher) {
        is Matcher.Presence, is Matcher.Absent -> Unit
        is Matcher.Exact ->
            SlimField(matcher.value, { onChange(Matcher.Exact(it)) }, monospace = true, modifier = Modifier.width(130.dp))
        is Matcher.Regex -> {
            // The one place a pattern is judged. The codec carries a bad one through unharmed, so the
            // author never loses a scenario to a half-typed character class — they are told here,
            // while they are typing it, and the row stays red until it compiles.
            //
            // The reason goes *under* the field, in its own column: as a sibling of the enclosing Row it
            // was laid out beside the field instead, and a 50-character message measured before the
            // row's trailing controls left them nothing to occupy.
            val problem = remember(matcher.pattern) { matcher.validationError() }
            SlimLabeled("pattern") {
                Column {
                    SlimField(
                        matcher.pattern,
                        { onChange(Matcher.Regex(it)) },
                        monospace = true,
                        textColor = if (problem != null) AppTheme.Colors.error else AppTheme.Colors.text,
                        modifier = Modifier.width(PATTERN_FIELD_WIDTH),
                    )
                    if (problem != null) {
                        Text(
                            problem,
                            color = AppTheme.Colors.error,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            modifier = Modifier.width(PATTERN_FIELD_WIDTH),
                        )
                    }
                }
            }
        }
        is Matcher.Reference ->
            SlimField(matcher.expression, { onChange(Matcher.Reference(it)) }, monospace = true, modifier = Modifier.width(180.dp))
        is Matcher.OneOf ->
            SlimLabeled("any of") {
                SlimField(
                    matcher.values.joinToString(","),
                    { onChange(Matcher.OneOf(it.split(",").map(String::trim).filter(String::isNotEmpty))) },
                    monospace = true,
                    modifier = Modifier.width(140.dp),
                )
            }
        is Matcher.Numeric -> {
            SlimField(numText(matcher.expected), { onChange(Matcher.Numeric(it.toDoubleOrNull() ?: matcher.expected, matcher.tolerance)) }, monospace = true, modifier = Modifier.width(80.dp))
            SlimLabeled("± tol") {
                SlimField(numText(matcher.tolerance), { onChange(Matcher.Numeric(matcher.expected, it.toDoubleOrNull() ?: matcher.tolerance)) }, monospace = true, modifier = Modifier.width(70.dp))
            }
        }
        is Matcher.Temporal -> {
            SlimDropdown(
                value = matcher.kind,
                options = TemporalKind.values().toList(),
                onValueChange = { kind -> kind?.let { onChange(Matcher.Temporal(it, matcher.toleranceSeconds)) } },
                displayText = { if (it == TemporalKind.TODAY) "today" else "now ±" },
                modifier = Modifier.width(76.dp),
            )
            if (matcher.kind == TemporalKind.NOW_WITHIN_TOLERANCE) {
                SlimLabeled("± sec") {
                    SlimField(matcher.toleranceSeconds.toString(), { onChange(Matcher.Temporal(matcher.kind, it.toLongOrNull() ?: matcher.toleranceSeconds)) }, monospace = true, modifier = Modifier.width(56.dp))
                }
            }
        }
    }
}

/** Renders a double without a trailing ".0" so integer-ish values read cleanly. */
private fun numText(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
