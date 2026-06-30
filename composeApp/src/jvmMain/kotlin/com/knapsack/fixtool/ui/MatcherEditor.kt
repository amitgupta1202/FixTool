// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind

/** The matcher type names, in the order shown in the editor's dropdown. */
val MATCHER_TYPES = listOf("exact", "presence", "absent", "oneOf", "regex", "numeric", "temporal", "reference")

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

/** A sensible default [Matcher] when switching to [type], seeded from the captured [value]. */
fun defaultMatcherForType(type: String, value: String): Matcher =
    when (type) {
        "presence" -> Matcher.Presence
        "absent" -> Matcher.Absent
        "oneOf" -> Matcher.OneOf(if (value.isBlank()) emptyList() else listOf(value))
        "regex" -> Matcher.Regex(value.ifBlank { ".*" })
        "numeric" -> Matcher.Numeric(value.toDoubleOrNull() ?: 0.0, 0.0)
        "temporal" -> Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)
        "reference" -> Matcher.Reference("\${out.D.11}")
        else -> Matcher.Exact(value)
    }

/**
 * Edits one [Matcher]: a type dropdown plus type-specific fields. Switching type seeds a sensible
 * default from the captured value. Emits the new matcher via [onChange].
 */
@Composable
fun MatcherEditor(matcher: Matcher, capturedValue: String, onChange: (Matcher) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TypeDropdown(matcher) { type -> onChange(defaultMatcherForType(type, capturedValue)) }
        MatcherParams(matcher, onChange)
    }
}

@Composable
private fun TypeDropdown(matcher: Matcher, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(matcherTypeName(matcher), fontSize = 11.sp, color = AppTheme.Colors.text)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MATCHER_TYPES.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type, fontSize = 12.sp) },
                    onClick = {
                        onPick(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MatcherParams(matcher: Matcher, onChange: (Matcher) -> Unit) {
    when (matcher) {
        is Matcher.Presence, is Matcher.Absent -> Unit
        is Matcher.Exact -> SmallField("value", matcher.value) { onChange(Matcher.Exact(it)) }
        is Matcher.Regex -> SmallField("pattern", matcher.pattern) { onChange(Matcher.Regex(it)) }
        is Matcher.Reference -> SmallField("expression", matcher.expression, 200.dp) { onChange(Matcher.Reference(it)) }
        is Matcher.OneOf ->
            SmallField("values (comma)", matcher.values.joinToString(","), 180.dp) {
                onChange(Matcher.OneOf(it.split(",").map(String::trim).filter(String::isNotEmpty)))
            }
        is Matcher.Numeric -> {
            SmallField("value", numText(matcher.expected), 90.dp) {
                onChange(Matcher.Numeric(it.toDoubleOrNull() ?: matcher.expected, matcher.tolerance))
            }
            SmallField("± tol", numText(matcher.tolerance), 90.dp) {
                onChange(Matcher.Numeric(matcher.expected, it.toDoubleOrNull() ?: matcher.tolerance))
            }
        }
        is Matcher.Temporal -> {
            TemporalKindDropdown(matcher.kind) { onChange(Matcher.Temporal(it, matcher.toleranceSeconds)) }
            if (matcher.kind == TemporalKind.NOW_WITHIN_TOLERANCE) {
                SmallField("± sec", matcher.toleranceSeconds.toString(), 80.dp) {
                    onChange(Matcher.Temporal(matcher.kind, it.toLongOrNull() ?: matcher.toleranceSeconds))
                }
            }
        }
    }
}

@Composable
private fun TemporalKindDropdown(kind: TemporalKind, onPick: (TemporalKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (kind == TemporalKind.TODAY) "today" else "now±", fontSize = 11.sp, color = AppTheme.Colors.text)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("today") }, onClick = { onPick(TemporalKind.TODAY); expanded = false })
            DropdownMenuItem(
                text = { Text("now_within_tolerance") },
                onClick = { onPick(TemporalKind.NOW_WITHIN_TOLERANCE); expanded = false },
            )
        }
    }
}

@Composable
private fun SmallField(label: String, value: String, fieldWidth: androidx.compose.ui.unit.Dp = 120.dp, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 9.sp) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
        modifier = Modifier.width(fieldWidth).padding(0.dp),
    )
}

/** Renders a double without a trailing ".0" so integer-ish values read cleanly. */
private fun numText(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
