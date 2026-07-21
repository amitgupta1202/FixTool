package com.knapsack.fixtool.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.TooltipIconButton
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** A titled group of related controls inside a settings page. */
@Composable
fun SettingsBlock(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, fontSize = 13.sp, color = AppTheme.Colors.text, fontWeight = FontWeight.Medium)
        if (description != null) {
            Text(text = description, fontSize = 11.sp, color = AppTheme.Colors.textDisabled, lineHeight = 15.sp)
        }
        content()
    }
}

@Composable
fun SettingsCheckbox(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(18.dp)
                    .background(AppTheme.Helpers.checkboxBackground(checked), RoundedCornerShape(2.dp))
                    .border(1.dp, AppTheme.Helpers.checkboxBorder(checked), RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppTheme.Colors.background,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, fontSize = 12.sp, color = AppTheme.Colors.text)
            Text(text = description, fontSize = 11.sp, color = AppTheme.Colors.textDisabled, lineHeight = 14.sp)
        }
    }
}

@Composable
fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor =
        when {
            isError -> AppTheme.Colors.error
            isFocused -> AppTheme.Colors.primary
            else -> AppTheme.Separators.color
        }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .height(28.dp)
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(2.dp))
                .border(1.dp, borderColor, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp),
        textStyle = TextStyle(fontSize = 12.sp, color = AppTheme.Colors.text),
        singleLine = true,
        cursorBrush = SolidColor(AppTheme.Colors.primary),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && !isFocused) {
                    Text(text = placeholder, fontSize = 12.sp, color = AppTheme.Colors.textDisabled, maxLines = 1)
                }
                innerTextField()
            }
        },
    )
}

@Composable
fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = AppTheme.Colors.primary,
    contentColor: Color = AppTheme.Colors.background,
) {
    Box(
        modifier =
            modifier
                .height(28.dp)
                .background(
                    color = if (enabled) containerColor else AppTheme.Colors.surface,
                    shape = RoundedCornerShape(4.dp),
                ).clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else AppTheme.Colors.textDisabled,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * A number with its range shown while it is being typed and its complaint shown the moment it stops
 * fitting — rather than after Save has already stored something else.
 */
@Composable
fun NumberField(
    draft: SettingsDraft,
    setting: NumberSetting,
    modifier: Modifier = Modifier,
    fieldWidth: androidx.compose.ui.unit.Dp = 110.dp,
) {
    val error = draft.errorOf(setting)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = setting.label, fontSize = 12.sp, color = AppTheme.Colors.text, modifier = Modifier.width(150.dp))
            SettingsField(
                value = draft.textOf(setting),
                onValueChange = { draft.type(setting, it) },
                placeholder = setting.range.first.toString(),
                isError = error != null,
                modifier = Modifier.width(fieldWidth).testTag("settings-number-${setting.name}"),
            )
            Text(text = setting.unit, fontSize = 11.sp, color = AppTheme.Colors.textDisabled)
        }
        Text(
            text = error ?: "${setting.range.first}–${setting.range.last}",
            fontSize = 10.sp,
            color = if (error != null) AppTheme.Colors.error else AppTheme.Colors.textDisabled,
            modifier = Modifier.padding(start = 158.dp),
        )
    }
}

/** What the path in a [PathField] is expected to name, which decides what "valid" means for it. */
enum class PathKind { EXISTING_FILE, FILE_TO_WRITE, DIRECTORY }

/**
 * A path with a browse button and a live verdict underneath.
 *
 * One control for all five paths the app configures. Each used to carry its own copy of the same
 * chooser plumbing and its own slightly different opinion about what a missing file meant.
 */
@Composable
fun PathField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    kind: PathKind,
    chooserTitle: String,
    modifier: Modifier = Modifier,
    fileFilter: Pair<String, String>? = null,
    emptyNote: String? = null,
    trailing: String = "",
    detail: (File) -> String? = { null },
    startNear: () -> String = { "" },
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
            )
            TooltipIconButton(
                tooltip = chooserTitle,
                onClick = {
                    val chooser =
                        JFileChooser().apply {
                            dialogTitle = chooserTitle
                            fileSelectionMode =
                                if (kind == PathKind.DIRECTORY) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
                            fileFilter?.let { (label, ext) -> setFileFilter(FileNameExtensionFilter(label, ext)) }
                            val anchor = value.ifBlank { startNear() }
                            if (anchor.isNotBlank()) {
                                val file = File(anchor)
                                if (file.exists()) {
                                    if (kind == PathKind.DIRECTORY) {
                                        currentDirectory = file
                                    } else {
                                        currentDirectory = file.parentFile
                                        if (value.isNotBlank()) selectedFile = file
                                    }
                                }
                            }
                        }
                    val approved =
                        if (kind == PathKind.FILE_TO_WRITE) {
                            chooser.showSaveDialog(null)
                        } else {
                            chooser.showOpenDialog(null)
                        }
                    if (approved == JFileChooser.APPROVE_OPTION) onValueChange(chooser.selectedFile.absolutePath)
                },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Browse",
                    tint = AppTheme.Colors.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (value.isBlank()) {
            if (emptyNote != null) {
                Text(text = emptyNote, fontSize = 11.sp, color = AppTheme.Colors.textDisabled)
            }
        } else {
            // Keyed on the path, because [detail] reads the file — for a dictionary that means parsing
            // its XML, and composition happens on every keystroke anywhere on the page. Existence is
            // still checked live below; only the reading of the file is remembered.
            val detected = remember(value) { detail(File(value)) }
            val (message, color) = pathVerdict(File(value), kind, detected)
            Text(text = message + trailing, fontSize = 11.sp, color = color)
        }
    }
}

private fun pathVerdict(file: File, kind: PathKind, detail: String?): Pair<String, Color> {
    val wanted = if (kind == PathKind.DIRECTORY) "Directory" else "File"
    val present = if (kind == PathKind.DIRECTORY) file.isDirectory else file.isFile
    return when {
        present -> "✓ $wanted exists${detail?.let { " ($it)" } ?: ""}" to AppTheme.Colors.primary
        file.exists() -> "⚠ Not a $wanted" to AppTheme.Colors.error
        kind == PathKind.EXISTING_FILE -> "⚠ $wanted not found" to AppTheme.Colors.warning
        file.parentFile?.exists() == true -> "⚠ $wanted will be created on first save" to AppTheme.Colors.warning
        else -> "⚠ Parent directory does not exist" to AppTheme.Colors.error
    }
}

/** One option in a [SegmentedChoice]. */
class Choice<T>(val value: T, val label: String, val swatches: List<Color> = emptyList())

/**
 * A row of mutually exclusive options.
 *
 * Replaces four hand-rolled copies of the same selected/unselected `Box` — one per option, each
 * repeating the same three conditionals for background, border and text.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option.value == selected
            Box(
                modifier =
                    Modifier
                        .height(30.dp)
                        .weight(1f)
                        .background(
                            color = if (isSelected) AppTheme.Colors.primary else AppTheme.Colors.surface,
                            shape = RoundedCornerShape(4.dp),
                        ).border(
                            width = 1.dp,
                            color = if (isSelected) AppTheme.Colors.primary else AppTheme.Separators.color,
                            shape = RoundedCornerShape(4.dp),
                        ).clickable { onSelect(option.value) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option.label,
                        fontSize = 11.sp,
                        color = if (isSelected) AppTheme.Colors.background else AppTheme.Colors.text,
                        maxLines = 1,
                    )
                    if (option.swatches.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            option.swatches.forEach { swatch ->
                                Box(
                                    Modifier
                                        .size(11.dp)
                                        .background(swatch, RoundedCornerShape(2.dp))
                                        .border(0.5.dp, AppTheme.Colors.borderDark, RoundedCornerShape(2.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pick FIX tags out of the loaded dictionary.
 *
 * The results list is lazy and fixed in height. Its predecessor built a row for every field that
 * matched — with the whole FIX 4.4 dictionary now bundled rather than the 93-field stub, an empty
 * search term meant composing well over a thousand rows to show eight of them.
 */
@Composable
fun TagListEditor(
    selected: List<Int>,
    fields: List<Pair<Int, String>>,
    nameOf: (Int) -> String,
    onChange: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    emptyNote: String = "None selected.",
    testTagPrefix: String? = null,
) {
    var query by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (selected.isEmpty()) {
            Text(text = emptyNote, fontSize = 11.sp, color = AppTheme.Colors.textDisabled)
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height((selected.size * 24).coerceAtMost(120).dp)
                        .background(AppTheme.Colors.background, RoundedCornerShape(2.dp))
                        .border(1.dp, AppTheme.Separators.color, RoundedCornerShape(2.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                items(selected, key = { it }) { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "$tag · ${nameOf(tag)}", fontSize = 11.sp, color = AppTheme.Colors.text)
                        TooltipIconButton(
                            tooltip = "Remove",
                            onClick = { onChange(selected.filter { it != tag }) },
                            modifier = Modifier.size(18.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = AppTheme.Colors.textSecondary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }

        if (fields.isEmpty()) {
            Text(
                text = "No data dictionary loaded — configure one under Protocol to pick tags by name.",
                fontSize = 11.sp,
                color = AppTheme.Colors.textDisabled,
            )
            return@Column
        }

        SettingsField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Add a tag — type a number or a field name",
            modifier =
                Modifier
                    .fillMaxWidth()
                    .let { if (testTagPrefix != null) it.testTag("$testTagPrefix-search") else it },
        )

        if (query.isNotBlank()) {
            val matches =
                remember(query, fields) {
                    fields.filter { (tag, name) ->
                        tag.toString().startsWith(query) || name.contains(query, ignoreCase = true)
                    }
                }
            if (matches.isEmpty()) {
                Text(text = "No field matches \"$query\".", fontSize = 11.sp, color = AppTheme.Colors.textDisabled)
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(AppTheme.Colors.background, RoundedCornerShape(2.dp))
                            .border(1.dp, AppTheme.Separators.color, RoundedCornerShape(2.dp)),
                ) {
                    items(matches, key = { it.first }) { (tag, name) ->
                        val isSelected = tag in selected
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onChange(if (isSelected) selected.filter { it != tag } else selected + tag)
                                    }.background(if (isSelected) selectedRowBackground else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "$tag · $name", fontSize = 11.sp, color = AppTheme.Colors.text)
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = AppTheme.Colors.primary,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val selectedRowBackground = Color(0xFF2D4F7C)
