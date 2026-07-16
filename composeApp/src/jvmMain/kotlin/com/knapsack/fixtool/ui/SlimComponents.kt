package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

private val slimShape = RoundedCornerShape(2.dp)

/**
 * The app-wide slim text input (24dp tall, 10sp, thin focus border) — the same recipe as the
 * message editor / connection panel fields, published here so new surfaces (e.g. the Scenarios
 * workbench) match the app convention instead of the much taller Material3 OutlinedTextField.
 */
@Composable
fun SlimField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    textColor: Color = AppTheme.Colors.text,
    tintBlank: Boolean = false,
    /** Dim hint drawn inside an empty field — the slim replacement for a label that would shift the row. */
    placeholder: String = "",
    /**
     * False = a real text area: the field grows with its lines (up to [maxLines]) instead of hiding every
     * line but the first, which is what a single-line field silently does to a multi-line paste.
     */
    singleLine: Boolean = true,
    maxLines: Int = 1,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val background = if (tintBlank && value.isBlank()) AppTheme.Colors.emptyFieldBackground else AppTheme.Colors.surface
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .let { if (singleLine) it.height(24.dp) else it.heightIn(min = 24.dp) }
                .background(background, slimShape)
                .border(1.dp, if (isFocused) AppTheme.Colors.primary else AppTheme.Colors.border, slimShape)
                .padding(horizontal = 4.dp, vertical = 5.dp),
        textStyle =
            TextStyle(
                fontSize = 10.sp,
                color = textColor,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else maxLines.coerceAtLeast(2),
        cursorBrush = SolidColor(AppTheme.Colors.primary),
        interactionSource = interactionSource,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        color = AppTheme.Colors.textDisabled,
                        fontSize = 10.sp,
                        maxLines = 1,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    )
                }
                inner()
            }
        },
    )
}

/** A 10sp label to the left of a slim input — replaces the floating Material3 field label. */
@Composable
fun SlimLabeled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(label, color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
        content()
    }
}

/**
 * A tag input with dictionary autocomplete: type a tag number **or a field name** and pick from the
 * matching fields ("11 · ClOrdID"). Plain numeric entry still works keystroke-by-keystroke, so with
 * no dictionary loaded it degrades to a simple tag field.
 */
@Composable
fun SlimTagPicker(
    tag: Int,
    fields: List<Pair<Int, String>>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fieldTestTag: String? = null,
) {
    var text by remember(tag) { mutableStateOf(tag.takeIf { it != 0 }?.toString() ?: "") }
    var expanded by remember { mutableStateOf(false) }
    val matches =
        remember(text, fields) {
            if (text.isBlank()) {
                emptyList()
            } else {
                fields.filter { (t, name) -> t.toString().startsWith(text) || name.contains(text, ignoreCase = true) }.take(12)
            }
        }
    Box(modifier = modifier) {
        SlimField(
            value = text,
            onValueChange = { typed ->
                text = typed
                typed.toIntOrNull()?.let(onPick)
                expanded = typed.isNotBlank() && fields.isNotEmpty()
            },
            monospace = true,
            tintBlank = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .let { if (fieldTestTag != null) it.testTag(fieldTestTag) else it },
        )
        DropdownMenu(
            expanded = expanded && matches.isNotEmpty(),
            onDismissRequest = {
                expanded = false
                // Abandoned name-search (no pick) falls back to the current tag's canonical text.
                text = tag.takeIf { it != 0 }?.toString() ?: ""
            },
            // Not focusable: focus stays in the text field so typing keeps filtering.
            properties = PopupProperties(focusable = false),
            modifier = Modifier.background(Color(0xFF2B2B2B)),
        ) {
            matches.forEach { (t, name) ->
                DropdownMenuItem(
                    text = {
                        Row {
                            Text("$t", color = AppTheme.Colors.tagNumber, fontSize = 10.sp)
                            Text("  $name", color = Color(0xFFE0E0E0), fontSize = 10.sp)
                        }
                    },
                    onClick = {
                        text = t.toString()
                        onPick(t)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }
        }
    }
}

/** A 24dp text button matching [SlimField]/[SlimDropdown] — replaces the 40dp OutlinedButton. */
@Composable
fun SlimButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.Colors.text,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .height(24.dp)
                .background(AppTheme.Colors.surface, slimShape)
                .border(1.dp, AppTheme.Colors.border, slimShape)
                .let { if (enabled) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) color else AppTheme.Colors.textDisabled, fontSize = 10.sp, maxLines = 1)
    }
}

/**
 * A slim dropdown component that matches the SlimTextField styling
 * Supports nullable values where null means "no selection"
 */
@Composable
fun <T> SlimDropdown(
    value: T?,
    options: List<T>,
    onValueChange: (T?) -> Unit,
    displayText: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    allowUnselect: Boolean = false,
    itemText: (T) -> String = displayText,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFF2B2B2B), RoundedCornerShape(2.dp))
                    .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(2.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (value != null) displayText(value) else placeholder,
                color = if (value != null) Color(0xFFE0E0E0) else Color(0xFF6A6A6A),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Dropdown",
                tint = Color(0xFFB0B0B0),
                modifier = Modifier.size(12.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF2B2B2B)),
        ) {
            // Add "None" option if allowUnselect is true and there are options
            if (allowUnselect && options.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "None",
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                        )
                    },
                    onClick = {
                        onValueChange(null)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }

            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemText(option),
                            color = Color(0xFFE0E0E0),
                            fontSize = 10.sp,
                        )
                    },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }
        }
    }
}

/**
 * A slim dropdown component that supports custom text color per item
 * Supports nullable values where null means "no selection"
 */
@Composable
fun <T> SlimDropdownWithColor(
    value: T?,
    options: List<T>,
    onValueChange: (T?) -> Unit,
    displayText: (T) -> String,
    textColor: (T) -> Color,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    allowUnselect: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFF2B2B2B), RoundedCornerShape(2.dp))
                    .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(2.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (value != null) displayText(value) else placeholder,
                color = if (value != null) textColor(value) else Color(0xFF6A6A6A),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Dropdown",
                tint = Color(0xFFB0B0B0),
                modifier = Modifier.size(12.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF2B2B2B)),
        ) {
            // Add "None" option if allowUnselect is true and there are options
            if (allowUnselect && options.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "None",
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                        )
                    },
                    onClick = {
                        onValueChange(null)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }

            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayText(option),
                            color = textColor(option),
                            fontSize = 10.sp,
                        )
                    },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                )
            }
        }
    }
}
