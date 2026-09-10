package com.knapsack.fixtool.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
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
    /**
     * **False draws the field the way it behaves: sunken, greyed, and refusing the caret.**
     *
     * For the field whose value only counts while a tick or a radio beside it says so. A live field over a
     * value nothing reads is the surface disagreeing with itself, and the number typed into it is taken,
     * kept and thrown away without a word. Greyed, the field says which control decides.
     *
     * The value itself still shows, because a ceiling put there by a ticked box is worth reading back after
     * the box is cleared. It is drawn as what it is: kept, and not in force.
     */
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // A field that is off is never the empty one a form is waiting on, so the blank tint gives way to the
    // disabled fill rather than colouring a row nothing is owed on.
    val background =
        when {
            !enabled -> AppTheme.Colors.surfaceVariant
            tintBlank && value.isBlank() -> AppTheme.Colors.emptyFieldBackground
            else -> AppTheme.Colors.surface
        }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier =
            modifier
                .let { if (singleLine) it.height(24.dp) else it.heightIn(min = 24.dp) }
                .background(background, slimShape)
                .border(1.dp, if (isFocused) AppTheme.Colors.primary else AppTheme.Colors.border, slimShape)
                // Single-line: no vertical padding — the decoration box centers the line instead. A 10sp
                // line measures taller than 24dp minus two 5dp pads, and BasicTextField top-aligns, so the
                // fixed pad was clipping the lower half of the text (the rail's "filter…" showed as tops).
                .padding(horizontal = 4.dp)
                .let { if (singleLine) it else it.padding(vertical = 5.dp) }
                // Said in the semantics as well as in the paint, so a reader that never sees the fill is
                // told the same thing, and so a test can ask.
                .semantics { if (!enabled) disabled() },
        textStyle =
            TextStyle(
                fontSize = 10.sp,
                color = if (enabled) textColor else AppTheme.Colors.textDisabled,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else maxLines.coerceAtLeast(2),
        cursorBrush = SolidColor(AppTheme.Colors.primary),
        interactionSource = interactionSource,
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = if (singleLine) Modifier.fillMaxHeight() else Modifier,
            ) {
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
 * **The field-grid search box**, as the message editor has worn it: a magnifier, a monospace query, and a ×
 * that appears only once there is something to clear.
 *
 * Published here because three surfaces now own a field grid — the message editor, the scenario editor's Send
 * step, and the reconcile diff — and a search that looks and reads differently in each is three features to
 * learn instead of one. What it does *not* dictate is how a match is shown: the message editor and the Send
 * grid tint the row, the diff cannot (its row background is already the pass/fail ledger) and outlines it
 * instead. The rule for *what matches* is [com.knapsack.fixtool.service.FieldSearch] and is not negotiable
 * per surface; the mark is.
 *
 * [trailing] is the slot the diff fills with its match counter and next/prev buttons — in a hundred-row
 * reply, a highlight the author still has to scroll for is half an answer.
 */
@Composable
fun SlimSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search tags, names, or values…",
    testTag: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val queryStyle = TextStyle(fontSize = 10.sp, color = AppTheme.Colors.text, fontFamily = FontFamily.Monospace)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = "Search",
            tint = AppTheme.Colors.textDisabled,
            modifier = Modifier.size(14.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier =
                Modifier
                    .weight(1f)
                    .height(22.dp)
                    .background(AppTheme.Colors.background, slimShape)
                    .border(1.dp, if (focused) AppTheme.Colors.primary else AppTheme.Colors.border, slimShape)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .let { if (testTag != null) it.testTag(testTag) else it },
            textStyle = queryStyle,
            singleLine = true,
            cursorBrush = SolidColor(AppTheme.Colors.primary),
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                // The placeholder retires on focus, not on the first keystroke: an author who has clicked the
                // box has already been told what it is for, and the hint under a cursor reads as content.
                if (query.isEmpty() && !focused) {
                    Text(placeholder, style = queryStyle.copy(color = AppTheme.Colors.textDisabled))
                }
                innerTextField()
            },
        )
        trailing()
        if (query.isNotEmpty()) {
            TooltipIconButton(
                tooltip = "Clear search",
                onClick = { onQueryChange("") },
                modifier = Modifier.size(18.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
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
    /**
     * An option's text colour, wherever it is drawn — its menu row **and** the collapsed chip when it is
     * the selection. One hook for both on purpose: an option dimmed in the menu because it does not belong
     * on this row must stay dimmed after it is picked, or the warning survives only until the menu closes.
     * Options stay clickable whatever colour they are given; this dims, it never disables.
     */
    optionColor: ((T) -> Color)? = null,
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
                color = if (value != null) optionColor?.invoke(value) ?: Color(0xFFE0E0E0) else Color(0xFF6A6A6A),
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
                            color = optionColor?.invoke(option) ?: Color(0xFFE0E0E0),
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

// ---------------------------------------------------------------------------------------------------
// Choice controls: radio, checkbox, segment.
//
// Bespoke rather than Material3 scaled down, the same call the app already made for its text fields and
// buttons: M3's RadioButton is 20dp inside a 48dp minimum interactive size and will not sit in a 24dp row.
//
// What they add over the `"◉ " + label` Text with a bare `selectable` that they replace is narrower than
// it looks, and worth stating exactly. The app is wrapped in MaterialTheme, so that Text was already
// focusable, already toggled on Space and Enter, and already drew the ripple's hover and focus layers.
// What it had not got: a Role, so a screen reader was never told it was a radio (and the multi-select row
// claimed to be one anyway); a hit target bigger than one glyph plus its label; any affordance beyond a
// character; and arrow keys inside a group, which is the difference between a radio group and N buttons.
// ---------------------------------------------------------------------------------------------------

/** The row chrome every choice control wears: 24dp, a focus border in [AppTheme.Colors.primary], no jump. */
@Composable
private fun ChoiceRow(
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
    testTag: String?,
    content: @Composable RowScope.() -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            modifier
                .height(24.dp)
                // Always a border, transparent when unfocused, so gaining focus cannot reflow the row.
                .border(1.dp, if (focused) AppTheme.Colors.primary else Color.Transparent, slimShape)
                .padding(horizontal = 3.dp)
                .let { if (testTag != null) it.testTag(testTag) else it },
        content = content,
    )
}

@Composable
private fun RadioMark(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(11.dp)
                .border(1.dp, if (selected) AppTheme.Colors.primary else AppTheme.Colors.borderDark, CircleShape),
    ) {
        if (selected) Box(Modifier.size(5.dp).background(AppTheme.Colors.primary, CircleShape))
    }
}

@Composable
private fun CheckMark(checked: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(11.dp)
                .background(if (checked) AppTheme.Colors.primary else Color.Transparent, slimShape)
                .border(1.dp, if (checked) AppTheme.Colors.primary else AppTheme.Colors.borderDark, slimShape),
    ) {
        if (checked) {
            Canvas(Modifier.size(7.dp)) {
                val stroke = 1.4.dp.toPx()
                val elbow = Offset(size.width * 0.38f, size.height * 0.82f)
                drawLine(tickColor, Offset(size.width * 0.08f, size.height * 0.52f), elbow, stroke, StrokeCap.Round)
                drawLine(tickColor, elbow, Offset(size.width * 0.94f, size.height * 0.16f), stroke, StrokeCap.Round)
            }
        }
    }
}

/** One radio row. [SlimRadioGroup] is what gives a set of them arrow keys; this is the single-option form. */
@Composable
fun SlimRadio(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    focusRequester: FocusRequester? = null,
    label: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    ChoiceRow(
        interactionSource,
        modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .selectable(selected = selected, interactionSource = interactionSource, indication = null, role = Role.RadioButton, onClick = onSelect),
        testTag,
    ) {
        RadioMark(selected)
        label()
    }
}

/**
 * **A radio group: one selection, and arrow keys that move it.**
 *
 * The arrow keys are the point. `Modifier.selectable` on its own makes N independently focusable buttons
 * that happen to be mutually exclusive; a radio *group* is one stop in the tab order whose members the
 * arrows walk. [selectableGroup] tells the semantics tree the same thing.
 */
@Composable
fun <T> SlimRadioGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    optionTestTag: (T) -> String? = { null },
    optionLabel: @Composable RowScope.(T) -> Unit,
) {
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }

    fun move(step: Int): Boolean {
        if (options.size < 2) return false
        val from = options.indexOf(selected).takeIf { it >= 0 } ?: 0
        val next = (from + step + options.size) % options.size
        onSelect(options[next])
        // The requester is attached to a row that exists; a composition that has not laid out yet is the
        // one case it can throw, and losing the focus move there is better than losing the selection.
        runCatching { requesters[next].requestFocus() }
        return true
    }
    val forward = if (horizontal) Key.DirectionRight else Key.DirectionDown
    val back = if (horizontal) Key.DirectionLeft else Key.DirectionUp
    val keys =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                when (event.key) {
                    forward -> move(1)
                    back -> move(-1)
                    else -> false
                }
            }
        }
    val rows: @Composable (Modifier) -> Unit = { rowModifier ->
        options.forEachIndexed { index, option ->
            SlimRadio(
                selected = option == selected,
                onSelect = { onSelect(option) },
                modifier = rowModifier,
                testTag = optionTestTag(option),
                focusRequester = requesters[index],
                label = { optionLabel(option) },
            )
        }
    }
    if (horizontal) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.selectableGroup().then(keys),
        ) { rows(Modifier) }
    } else {
        Column(modifier = modifier.selectableGroup().then(keys)) { rows(Modifier) }
    }
}

/** One checkbox row, with [Role.Checkbox] semantics — which is what the glyph radios were borrowing. */
@Composable
fun SlimCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    label: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    ChoiceRow(
        interactionSource,
        modifier.toggleable(
            value = checked,
            interactionSource = interactionSource,
            indication = null,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        ),
        testTag,
    ) {
        CheckMark(checked)
        label()
    }
}

/**
 * **A segmented control**: two or three exclusive options in one 24dp track, for a choice whose options
 * are one word each and belong side by side (Burst / Rate). Left and right walk it, as in a radio group.
 */
@Composable
fun <T> SlimSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    optionTestTag: (T) -> String? = { null },
) {
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }

    fun move(step: Int): Boolean {
        if (options.size < 2) return false
        val next = (options.indexOf(selected).coerceAtLeast(0) + step + options.size) % options.size
        onSelect(options[next])
        runCatching { requesters[next].requestFocus() }
        return true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .height(24.dp)
                .background(AppTheme.Colors.surfaceVariant, slimShape)
                .border(1.dp, AppTheme.Colors.border, slimShape)
                .selectableGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionRight -> move(1)
                            Key.DirectionLeft -> move(-1)
                            else -> false
                        }
                    }
                },
    ) {
        options.forEachIndexed { index, option ->
            val on = option == selected
            val interactionSource = remember(option) { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .focusRequester(requesters[index])
                        .selectable(
                            selected = on,
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.RadioButton,
                        ) { onSelect(option) }
                        .background(if (on) AppTheme.Colors.selectionPrimary else Color.Transparent, slimShape)
                        .border(1.dp, if (focused) AppTheme.Colors.primary else Color.Transparent, slimShape)
                        .padding(horizontal = 9.dp)
                        .let { optionTestTag(option)?.let { tag -> it.testTag(tag) } ?: it },
            ) {
                Text(label(option), color = if (on) AppTheme.Colors.text else AppTheme.Colors.textSecondary, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** The tick, drawn dark on the primary fill rather than white, so it reads as ink and not as a highlight. */
private val tickColor = AppTheme.Colors.background
