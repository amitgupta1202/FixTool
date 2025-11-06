package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionary
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    dictionary: FixDictionary,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var dataDictionaryPath by remember { mutableStateOf(currentSettings.defaultDataDictionary) }
    var validateFieldsOutOfOrder by remember { mutableStateOf(currentSettings.validateFieldsOutOfOrder) }
    var validateFieldsHaveValues by remember { mutableStateOf(currentSettings.validateFieldsHaveValues) }
    var validateUserDefinedFields by remember { mutableStateOf(currentSettings.validateUserDefinedFields) }
    var validateIncomingMessage by remember { mutableStateOf(currentSettings.validateIncomingMessage) }
    var gridViewColumns by remember { mutableStateOf(currentSettings.gridViewColumns.toMutableList()) }
    var hideProtocolTagsByDefault by remember { mutableStateOf(currentSettings.hideProtocolTagsByDefault) }
    var protocolTags by remember { mutableStateOf(currentSettings.protocolTags.toMutableSet()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .width(950.dp)
                    .heightIn(max = 800.dp),
            shape = RoundedCornerShape(8.dp),
            color = AppTheme.Colors.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Header
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(AppTheme.Colors.background)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Settings",
                        fontSize = 16.sp,
                        color = AppTheme.Colors.text,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Restore Defaults button
                        SlimButton(
                            text = "Restore Defaults",
                            onClick = {
                                val defaults = AppSettings.default()
                                dataDictionaryPath = defaults.defaultDataDictionary
                                validateFieldsOutOfOrder = defaults.validateFieldsOutOfOrder
                                validateFieldsHaveValues = defaults.validateFieldsHaveValues
                                validateUserDefinedFields = defaults.validateUserDefinedFields
                                validateIncomingMessage = defaults.validateIncomingMessage
                                gridViewColumns = defaults.gridViewColumns.toMutableList()
                                hideProtocolTagsByDefault = defaults.hideProtocolTagsByDefault
                                protocolTags = defaults.protocolTags.toMutableSet()
                            },
                            containerColor = Color(0xFF4A4A4A),
                            contentColor = AppTheme.Colors.textSecondary,
                            modifier = Modifier.height(28.dp).width(140.dp),
                        )

                        TooltipIconButton(
                            tooltip = "Close",
                            onClick = onDismiss,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = AppTheme.Colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                // Content
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Section: Data Dictionary
                    Text(
                        text = "Default Data Dictionary",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SlimTextField(
                            value = dataDictionaryPath,
                            onValueChange = { dataDictionaryPath = it },
                            placeholder = "e.g., /path/to/FIX44.xml",
                            modifier = Modifier.weight(1f),
                        )

                        TooltipIconButton(
                            tooltip = "Browse for Data Dictionary File",
                            onClick = {
                                val fileChooser =
                                    JFileChooser().apply {
                                        dialogTitle = "Select Data Dictionary File"
                                        fileSelectionMode = JFileChooser.FILES_ONLY
                                        fileFilter = FileNameExtensionFilter("XML Files (*.xml)", "xml")

                                        // Set initial directory if path exists
                                        if (dataDictionaryPath.isNotBlank()) {
                                            val file = File(dataDictionaryPath)
                                            if (file.exists()) {
                                                currentDirectory = file.parentFile
                                                selectedFile = file
                                            }
                                        }
                                    }

                                val result = fileChooser.showOpenDialog(null)
                                if (result == JFileChooser.APPROVE_OPTION) {
                                    dataDictionaryPath = fileChooser.selectedFile.absolutePath
                                }
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

                    // Show file validation status
                    if (dataDictionaryPath.isNotBlank()) {
                        val file = File(dataDictionaryPath)
                        if (file.exists() && file.isFile) {
                            Text(
                                text = "✓ File exists",
                                fontSize = 11.sp,
                                color = AppTheme.Colors.primary,
                            )
                        } else {
                            Text(
                                text = "⚠ File not found",
                                fontSize = 11.sp,
                                color = Color(0xFFFFA500),
                            )
                        }
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Validation Settings
                    Text(
                        text = "QuickFIX/J Validation Settings",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Text(
                        text = "Enable strict validation (recommended: keep all disabled for flexibility)",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textTertiary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    TwoColumnCheckboxRow(
                        leftLabel = "Validate Fields Out of Order",
                        leftDescription = "Reject messages if fields are not in dictionary order",
                        leftChecked = validateFieldsOutOfOrder,
                        onLeftCheckedChange = { validateFieldsOutOfOrder = it },
                        rightLabel = "Validate Fields Have Values",
                        rightDescription = "Reject messages with empty field values",
                        rightChecked = validateFieldsHaveValues,
                        onRightCheckedChange = { validateFieldsHaveValues = it },
                    )

                    TwoColumnCheckboxRow(
                        leftLabel = "Validate User-Defined Fields",
                        leftDescription = "Reject messages with custom fields not in dictionary",
                        leftChecked = validateUserDefinedFields,
                        onLeftCheckedChange = { validateUserDefinedFields = it },
                        rightLabel = "Validate Incoming Messages",
                        rightDescription = "Enable full validation of all incoming messages",
                        rightChecked = validateIncomingMessage,
                        onRightCheckedChange = { validateIncomingMessage = it },
                    )

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Grid View Settings
                    Text(
                        text = "Grid View Columns",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Text(
                        text = "Select FIX tags to display as columns in grid view (top-level tags only)",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textTertiary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    // Get all available fields from dictionary
                    val availableFields = remember { dictionary.getAllFields() }

                    // Show selected tags
                    if (gridViewColumns.isNotEmpty()) {
                        Text(
                            text = "Selected Columns:",
                            fontSize = 12.sp,
                            color = AppTheme.Colors.textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )

                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 100.dp)
                                    .verticalScroll(rememberScrollState())
                                    .background(AppTheme.Colors.background, RoundedCornerShape(2.dp))
                                    .border(1.dp, AppTheme.Separators.color, RoundedCornerShape(2.dp))
                                    .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            gridViewColumns.forEach { tag ->
                                val fieldName = dictionary.getFieldName(tag) ?: "Unknown"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "$tag - $fieldName",
                                        fontSize = 12.sp,
                                        color = AppTheme.Colors.text,
                                    )
                                    TooltipIconButton(
                                        tooltip = "Remove",
                                        onClick = {
                                            gridViewColumns = gridViewColumns.filter { it != tag }.toMutableList()
                                        },
                                        modifier = Modifier.size(20.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = AppTheme.Colors.textSecondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Add tag dropdown
                    if (availableFields.isNotEmpty()) {
                        var showTagSelector by remember { mutableStateOf(false) }
                        var searchText by remember { mutableStateOf("") }

                        SlimButton(
                            text = "Add Column",
                            onClick = { showTagSelector = !showTagSelector },
                            containerColor = AppTheme.Colors.primary,
                            contentColor = AppTheme.Colors.background,
                            modifier = Modifier.width(120.dp),
                        )

                        if (showTagSelector) {
                            Spacer(modifier = Modifier.height(4.dp))

                            // Search box
                            SlimTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                placeholder = "Search tag or name...",
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Tag list
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 150.dp)
                                        .verticalScroll(rememberScrollState())
                                        .background(AppTheme.Colors.background, RoundedCornerShape(2.dp))
                                        .border(1.dp, AppTheme.Separators.color, RoundedCornerShape(2.dp)),
                            ) {
                                val filteredFields =
                                    availableFields.filter { (tag, name) ->
                                        if (searchText.isBlank()) {
                                            true
                                        } else {
                                            tag.toString().contains(searchText, ignoreCase = true) ||
                                                name.contains(searchText, ignoreCase = true)
                                        }
                                    }

                                filteredFields.forEach { (tag, name) ->
                                    val isSelected = tag in gridViewColumns
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (isSelected) {
                                                        gridViewColumns =
                                                            gridViewColumns.filter { it != tag }.toMutableList()
                                                    } else {
                                                        gridViewColumns = (gridViewColumns + tag).toMutableList()
                                                    }
                                                }.background(if (isSelected) Color(0xFF2D4F7C) else Color.Transparent)
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "$tag - $name",
                                            fontSize = 12.sp,
                                            color = if (isSelected) Color(0xFFE0E0E0) else AppTheme.Colors.text,
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = AppTheme.Colors.primary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No data dictionary loaded. Configure a dictionary above to enable this feature.",
                            fontSize = 11.sp,
                            color = AppTheme.Colors.textTertiary,
                        )
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Protocol Tags Settings
                    Text(
                        text = "Protocol Tags",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Text(
                        text = "Configure which FIX tags are considered protocol/session-level tags",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textTertiary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    CheckboxSetting(
                        label = "Hide Protocol Tags by Default",
                        description = "Automatically hide protocol tags when viewing message details",
                        checked = hideProtocolTagsByDefault,
                        onCheckedChange = { hideProtocolTagsByDefault = it },
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Show protocol tags list
                    if (protocolTags.isNotEmpty()) {
                        Text(
                            text = "Protocol Tags (${protocolTags.size}):",
                            fontSize = 12.sp,
                            color = AppTheme.Colors.textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )

                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 150.dp)
                                    .verticalScroll(rememberScrollState())
                                    .background(AppTheme.Colors.background, RoundedCornerShape(2.dp))
                                    .border(1.dp, AppTheme.Separators.color, RoundedCornerShape(2.dp))
                                    .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            protocolTags.sorted().forEach { tag ->
                                val fieldName = dictionary.getFieldName(tag) ?: "Unknown"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "$tag - $fieldName",
                                        fontSize = 12.sp,
                                        color = AppTheme.Colors.text,
                                    )
                                    TooltipIconButton(
                                        tooltip = "Remove",
                                        onClick = {
                                            protocolTags = protocolTags.filter { it != tag }.toMutableSet()
                                        },
                                        modifier = Modifier.size(20.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = AppTheme.Colors.textSecondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Add tag
                    if (availableFields.isNotEmpty()) {
                        var showProtocolTagSelector by remember { mutableStateOf(false) }
                        var protocolSearchText by remember { mutableStateOf("") }

                        SlimButton(
                            text = "Add Protocol Tag",
                            onClick = { showProtocolTagSelector = !showProtocolTagSelector },
                            containerColor = AppTheme.Colors.primary,
                            contentColor = AppTheme.Colors.background,
                            modifier = Modifier.width(150.dp),
                        )

                        if (showProtocolTagSelector) {
                            Spacer(modifier = Modifier.height(4.dp))

                            // Search box
                            SlimTextField(
                                value = protocolSearchText,
                                onValueChange = { protocolSearchText = it },
                                placeholder = "Search tag or name...",
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Tag list
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 150.dp)
                                        .verticalScroll(rememberScrollState())
                                        .background(AppTheme.Colors.background, RoundedCornerShape(2.dp))
                                        .border(1.dp, AppTheme.Separators.color, RoundedCornerShape(2.dp)),
                            ) {
                                val filteredProtocolFields =
                                    availableFields.filter { (tag, name) ->
                                        if (protocolSearchText.isBlank()) {
                                            true
                                        } else {
                                            tag.toString().contains(protocolSearchText, ignoreCase = true) ||
                                                name.contains(protocolSearchText, ignoreCase = true)
                                        }
                                    }

                                filteredProtocolFields.forEach { (tag, name) ->
                                    val isSelected = tag in protocolTags
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (isSelected) {
                                                        protocolTags = protocolTags.filter { it != tag }.toMutableSet()
                                                    } else {
                                                        protocolTags = (protocolTags + tag).toMutableSet()
                                                    }
                                                }.background(if (isSelected) Color(0xFF2D4F7C) else Color.Transparent)
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "$tag - $name",
                                            fontSize = 12.sp,
                                            color = if (isSelected) Color(0xFFE0E0E0) else AppTheme.Colors.text,
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = AppTheme.Colors.primary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )
                }

                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                // Footer with action buttons
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(AppTheme.Colors.surface)
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SlimButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        containerColor = Color(0xFF3A3A3A),
                        contentColor = AppTheme.Colors.textSecondary,
                        modifier = Modifier.width(90.dp),
                    )

                    SlimButton(
                        text = "Save",
                        onClick = {
                            val newSettings =
                                currentSettings.copy(
                                    defaultDataDictionary = dataDictionaryPath.trim(),
                                    validateFieldsOutOfOrder = validateFieldsOutOfOrder,
                                    validateFieldsHaveValues = validateFieldsHaveValues,
                                    validateUserDefinedFields = validateUserDefinedFields,
                                    validateIncomingMessage = validateIncomingMessage,
                                    gridViewColumns = gridViewColumns.toList(),
                                    hideProtocolTagsByDefault = hideProtocolTagsByDefault,
                                    protocolTags = protocolTags.toSet(),
                                )
                            onSave(newSettings)
                            onDismiss()
                        },
                        containerColor = AppTheme.Colors.primary,
                        contentColor = AppTheme.Colors.background,
                        modifier = Modifier.width(90.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlimTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor = if (isFocused) AppTheme.Colors.primary else AppTheme.Separators.color

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .height(32.dp)
                .background(AppTheme.Colors.surface, RoundedCornerShape(2.dp))
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(2.dp),
                ).padding(horizontal = 8.dp, vertical = 6.dp),
        textStyle =
            TextStyle(
                fontSize = 13.sp,
                color = AppTheme.Colors.text,
            ),
        singleLine = true,
        cursorBrush = SolidColor(AppTheme.Colors.primary),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && !isFocused) {
                    Text(
                        text = placeholder,
                        fontSize = 13.sp,
                        color = AppTheme.Colors.textTertiary,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SlimButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    containerColor: Color = AppTheme.Colors.primary,
    contentColor: Color = AppTheme.Colors.background,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(32.dp)
                .background(
                    color = if (enabled) containerColor else Color(0xFF3A3A3A),
                    shape = RoundedCornerShape(4.dp),
                ).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else AppTheme.Colors.textTertiary,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
    }
}

@Composable
private fun CheckboxSetting(
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
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Checkbox
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .background(
                        color = if (checked) AppTheme.Colors.primary else AppTheme.Colors.surface,
                        shape = RoundedCornerShape(2.dp),
                    ).border(
                        width = 1.dp,
                        color = if (checked) AppTheme.Colors.primary else AppTheme.Separators.color,
                        shape = RoundedCornerShape(2.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppTheme.Colors.background,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Label and description
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = AppTheme.Colors.text,
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = AppTheme.Colors.textTertiary,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun TwoColumnCheckboxRow(
    leftLabel: String,
    leftDescription: String,
    leftChecked: Boolean,
    onLeftCheckedChange: (Boolean) -> Unit,
    rightLabel: String,
    rightDescription: String,
    rightChecked: Boolean,
    onRightCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CheckboxSetting(
            label = leftLabel,
            description = leftDescription,
            checked = leftChecked,
            onCheckedChange = onLeftCheckedChange,
            modifier = Modifier.weight(1f),
        )
        CheckboxSetting(
            label = rightLabel,
            description = rightDescription,
            checked = rightChecked,
            onCheckedChange = onRightCheckedChange,
            modifier = Modifier.weight(1f),
        )
    }
}
