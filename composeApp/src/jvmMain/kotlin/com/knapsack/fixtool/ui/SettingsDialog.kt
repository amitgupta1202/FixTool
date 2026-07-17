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
import androidx.compose.material.icons.filled.Delete
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
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.MessageColorScheme
import com.knapsack.fixtool.model.RejectionRule
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
    var transportDictionaryPath by remember { mutableStateOf(currentSettings.defaultTransportDictionary) }
    var defaultFixVersion by remember { mutableStateOf(currentSettings.defaultFixVersion) }
    var useBundledDictionary by remember { mutableStateOf(currentSettings.useBundledDictionary) }
    var validateFieldsOutOfOrder by remember { mutableStateOf(currentSettings.validateFieldsOutOfOrder) }
    var validateFieldsHaveValues by remember { mutableStateOf(currentSettings.validateFieldsHaveValues) }
    var validateUserDefinedFields by remember { mutableStateOf(currentSettings.validateUserDefinedFields) }
    var validateIncomingMessage by remember { mutableStateOf(currentSettings.validateIncomingMessage) }
    var sessionBufferSize by remember { mutableStateOf(currentSettings.sessionBufferSize.toString()) }
    var gridViewColumns by remember { mutableStateOf(currentSettings.gridViewColumns.toMutableList()) }
    var hideProtocolTags by remember { mutableStateOf(currentSettings.hideProtocolTags) }
    var protocolTags by remember { mutableStateOf(currentSettings.protocolTags.toMutableSet()) }
    var messageColorScheme by remember { mutableStateOf(currentSettings.messageColorScheme) }
    var rejectionRules by remember { mutableStateOf(currentSettings.rejectionRules.toMutableList()) }
    var defaultViewMode by remember { mutableStateOf(currentSettings.defaultViewMode) }
    var defaultLayout by remember { mutableStateOf(currentSettings.defaultLayout) }
    var connectionProfilesPath by remember { mutableStateOf(currentSettings.connectionProfilesPath) }
    var savedMessagesPath by remember { mutableStateOf(currentSettings.savedMessagesPath) }
    var scenariosPath by remember { mutableStateOf(currentSettings.scenariosPath) }
    // Latency tracking settings
    var enableLatencyTracking by remember { mutableStateOf(currentSettings.enableLatencyTracking) }
    var captureNetworkInterface by remember { mutableStateOf(currentSettings.captureNetworkInterface) }
    var latencyHistorySize by remember { mutableStateOf(currentSettings.latencyHistorySize.toString()) }
    var latencyWarningThreshold by remember { mutableStateOf((currentSettings.latencyWarningThresholdMicros / 1000).toString()) } // Display in ms
    var latencyCriticalThreshold by remember { mutableStateOf((currentSettings.latencyCriticalThresholdMicros / 1000).toString()) } // Display in ms
    var showLatencyColumn by remember { mutableStateOf(currentSettings.showLatencyColumn) }
    var autoSyncSessionToEditor by remember { mutableStateOf(currentSettings.autoSyncSessionToEditor) }
    var automationControlEnabled by remember { mutableStateOf(currentSettings.automationControlEnabled) }
    var automationControlPort by remember { mutableStateOf(currentSettings.automationControlPort.toString()) }

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
                                transportDictionaryPath = defaults.defaultTransportDictionary
                                validateFieldsOutOfOrder = defaults.validateFieldsOutOfOrder
                                validateFieldsHaveValues = defaults.validateFieldsHaveValues
                                validateUserDefinedFields = defaults.validateUserDefinedFields
                                validateIncomingMessage = defaults.validateIncomingMessage
                                sessionBufferSize = defaults.sessionBufferSize.toString()
                                gridViewColumns = defaults.gridViewColumns.toMutableList()
                                hideProtocolTags = defaults.hideProtocolTags
                                protocolTags = defaults.protocolTags.toMutableSet()
                                messageColorScheme = defaults.messageColorScheme
                                rejectionRules = defaults.rejectionRules.toMutableList()
                                defaultViewMode = defaults.defaultViewMode
                                defaultLayout = defaults.defaultLayout
                                connectionProfilesPath = defaults.connectionProfilesPath
                                savedMessagesPath = defaults.savedMessagesPath
                                scenariosPath = defaults.scenariosPath
                                enableLatencyTracking = defaults.enableLatencyTracking
                                captureNetworkInterface = defaults.captureNetworkInterface
                                latencyHistorySize = defaults.latencyHistorySize.toString()
                                latencyWarningThreshold = (defaults.latencyWarningThresholdMicros / 1000).toString()
                                latencyCriticalThreshold = (defaults.latencyCriticalThresholdMicros / 1000).toString()
                                showLatencyColumn = defaults.showLatencyColumn
                                autoSyncSessionToEditor = defaults.autoSyncSessionToEditor
                                automationControlEnabled = defaults.automationControlEnabled
                                automationControlPort = defaults.automationControlPort.toString()
                            },
                            containerColor = restoreDefaultsButtonColor,
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

                    // Use bundled dictionary checkbox
                    CheckboxSetting(
                        label = "Use bundled dictionary",
                        description = "Use the bundled FIX dictionary for the selected version",
                        checked = useBundledDictionary,
                        onCheckedChange = { useBundledDictionary = it },
                    )

                    // FIX Version dropdown (shown when using bundled dictionary)
                    if (useBundledDictionary) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "FIX Version:",
                                fontSize = 12.sp,
                                color = AppTheme.Colors.textSecondary,
                                modifier = Modifier.width(100.dp),
                            )
                            SlimDropdown(
                                value = defaultFixVersion,
                                options = FixVersion.entries.toList(),
                                onValueChange = { it?.let { version -> defaultFixVersion = version } },
                                displayText = { it.displayName },
                                placeholder = "Select FIX Version",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Info text for FIX 5.0+
                        if (defaultFixVersion.isFix50Plus) {
                            Text(
                                text = "FIX 5.0+ uses FIXT.1.1 transport layer with ApplVerID=${defaultFixVersion.applVerID}",
                                fontSize = 10.sp,
                                color = AppTheme.Colors.info,
                                modifier = Modifier.padding(start = 108.dp),
                            )
                        }
                    } else {
                        // Custom dictionary path (shown when not using bundled)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SlimTextField(
                                value = dataDictionaryPath,
                                onValueChange = { dataDictionaryPath = it },
                                placeholder = "Path to custom data dictionary XML",
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
                                // Try to detect version from the file
                                val detectedVersion =
                                    try {
                                        FixDictionaryAdapter.detectVersionFromFile(file)
                                    } catch (e: Exception) {
                                        null
                                    }
                                Text(
                                    text = if (detectedVersion != null) "✓ File exists (${detectedVersion.displayName})" else "✓ File exists",
                                    fontSize = 11.sp,
                                    color = AppTheme.Colors.primary,
                                )
                            } else {
                                Text(
                                    text = "⚠ File not found",
                                    fontSize = 11.sp,
                                    color = warningColor,
                                )
                            }
                        }

                        // Transport Dictionary section (for FIX 5.0+ or custom setups)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Transport Dictionary (Optional - for FIX 5.0+)",
                            fontSize = 12.sp,
                            color = AppTheme.Colors.textSecondary,
                        )
                        Text(
                            text = "FIX 5.0+ requires a separate transport dictionary (e.g., FIXT11.xml) for session messages like Logon",
                            fontSize = 10.sp,
                            color = AppTheme.Colors.textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SlimTextField(
                                value = transportDictionaryPath,
                                onValueChange = { transportDictionaryPath = it },
                                placeholder = "Path to FIXT11.xml transport dictionary",
                                modifier = Modifier.weight(1f),
                            )

                            TooltipIconButton(
                                tooltip = "Browse for Transport Dictionary File",
                                onClick = {
                                    val fileChooser =
                                        JFileChooser().apply {
                                            dialogTitle = "Select Transport Dictionary File (FIXT11.xml)"
                                            fileSelectionMode = JFileChooser.FILES_ONLY
                                            fileFilter = FileNameExtensionFilter("XML Files (*.xml)", "xml")

                                            // Set initial directory from app dictionary path
                                            if (transportDictionaryPath.isNotBlank()) {
                                                val tFile = File(transportDictionaryPath)
                                                if (tFile.exists()) {
                                                    currentDirectory = tFile.parentFile
                                                    selectedFile = tFile
                                                }
                                            } else if (dataDictionaryPath.isNotBlank()) {
                                                val dFile = File(dataDictionaryPath)
                                                if (dFile.exists()) {
                                                    currentDirectory = dFile.parentFile
                                                }
                                            }
                                        }

                                    val result = fileChooser.showOpenDialog(null)
                                    if (result == JFileChooser.APPROVE_OPTION) {
                                        transportDictionaryPath = fileChooser.selectedFile.absolutePath
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
                        // Validate transport dictionary file
                        if (transportDictionaryPath.isNotBlank()) {
                            val tFile = File(transportDictionaryPath)
                            if (tFile.exists() && tFile.isFile) {
                                Text(
                                    text = "✓ Transport dictionary file exists",
                                    fontSize = 11.sp,
                                    color = AppTheme.Colors.primary,
                                )
                            } else {
                                Text(
                                    text = "⚠ Transport dictionary file not found",
                                    fontSize = 11.sp,
                                    color = warningColor,
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Connection Profiles Directory
                    Text(
                        text = "Connection Profiles File",
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
                            value = connectionProfilesPath,
                            onValueChange = { connectionProfilesPath = it },
                            placeholder = "Default: ~/.fixtool/connection_profiles.json",
                            modifier = Modifier.weight(1f),
                        )

                        TooltipIconButton(
                            tooltip = "Browse for Connection Profiles File",
                            onClick = {
                                val fileChooser =
                                    JFileChooser().apply {
                                        dialogTitle = "Select Connection Profiles File"
                                        fileSelectionMode = JFileChooser.FILES_ONLY
                                        fileFilter = FileNameExtensionFilter("JSON Files (*.json)", "json")

                                        // Set initial directory if path exists
                                        if (connectionProfilesPath.isNotBlank()) {
                                            val file = File(connectionProfilesPath)
                                            if (file.exists()) {
                                                currentDirectory = file.parentFile
                                                selectedFile = file
                                            }
                                        }
                                    }

                                val result = fileChooser.showSaveDialog(null)
                                if (result == JFileChooser.APPROVE_OPTION) {
                                    connectionProfilesPath = fileChooser.selectedFile.absolutePath
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

                    // Validation message
                    if (connectionProfilesPath.isNotBlank()) {
                        val file = File(connectionProfilesPath)
                        if (file.exists() && file.isFile) {
                            Text(
                                text = "✓ File exists",
                                fontSize = 11.sp,
                                color = AppTheme.Colors.primary,
                            )
                        } else if (file.parentFile?.exists() == true) {
                            Text(
                                text = "⚠ File will be created on first save",
                                fontSize = 11.sp,
                                color = AppTheme.Colors.warning,
                            )
                        } else {
                            Text(
                                text = "⚠ Parent directory does not exist",
                                fontSize = 11.sp,
                                color = AppTheme.Colors.error,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AppTheme.Spacing.small))

                    // Section: Saved Messages Directory
                    Text(
                        text = "Saved Messages File",
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
                            value = savedMessagesPath,
                            onValueChange = { savedMessagesPath = it },
                            placeholder = "Default: ~/.fixtool/saved_messages.json",
                            modifier = Modifier.weight(1f),
                        )

                        TooltipIconButton(
                            tooltip = "Browse for Saved Messages File",
                            onClick = {
                                val fileChooser =
                                    JFileChooser().apply {
                                        dialogTitle = "Select Saved Messages File"
                                        fileSelectionMode = JFileChooser.FILES_ONLY
                                        fileFilter = FileNameExtensionFilter("JSON Files (*.json)", "json")

                                        // Set initial directory if path exists
                                        if (savedMessagesPath.isNotBlank()) {
                                            val file = File(savedMessagesPath)
                                            if (file.exists()) {
                                                currentDirectory = file.parentFile
                                                selectedFile = file
                                            }
                                        }
                                    }

                                val result = fileChooser.showSaveDialog(null)
                                if (result == JFileChooser.APPROVE_OPTION) {
                                    savedMessagesPath = fileChooser.selectedFile.absolutePath
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

                    // Validation message
                    if (savedMessagesPath.isNotBlank()) {
                        val file = File(savedMessagesPath)
                        if (file.exists() && file.isFile) {
                            Text(
                                text = "✓ File exists",
                                fontSize = 11.sp,
                                color = AppTheme.Colors.primary,
                            )
                        } else if (file.parentFile?.exists() == true) {
                            Text(
                                text = "⚠ File will be created on first save",
                                fontSize = 11.sp,
                                color = AppTheme.Colors.warning,
                            )
                        } else {
                            Text(
                                text = "⚠ Parent directory does not exist",
                                fontSize = 11.sp,
                                color = AppTheme.Colors.error,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AppTheme.Spacing.small))

                    // Section: Scenarios Directory — point it at a git repo to version-track scenarios.
                    Text(
                        text = "Scenarios Directory",
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
                            value = scenariosPath,
                            onValueChange = { scenariosPath = it },
                            placeholder = "Default: ~/.fixtool/scenarios",
                            modifier = Modifier.weight(1f),
                        )

                        TooltipIconButton(
                            tooltip = "Browse for Scenarios Directory",
                            onClick = {
                                val chooser =
                                    JFileChooser().apply {
                                        dialogTitle = "Select Scenarios Directory"
                                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                        if (scenariosPath.isNotBlank()) {
                                            val d = File(scenariosPath)
                                            if (d.exists()) currentDirectory = d
                                        }
                                    }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    scenariosPath = chooser.selectedFile.absolutePath
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

                    // Each scenario is one JSON file named for the scenario (e.g. book-a-trade--3f2a9c1b.json),
                    // so a git-tracked directory diffs and PRs cleanly. Applied on restart (like the paths above).
                    if (scenariosPath.isNotBlank()) {
                        val d = File(scenariosPath)
                        when {
                            d.exists() && d.isDirectory ->
                                Text("✓ Directory exists — one git-friendly JSON per scenario · applied on restart", fontSize = 11.sp, color = AppTheme.Colors.primary)
                            d.parentFile?.exists() == true ->
                                Text("⚠ Directory will be created on first save · applied on restart", fontSize = 11.sp, color = AppTheme.Colors.warning)
                            else ->
                                Text("⚠ Parent directory does not exist", fontSize = 11.sp, color = AppTheme.Colors.error)
                        }
                    } else {
                        Text("Point this at a git repo to version-track your scenarios · applied on restart", fontSize = 11.sp, color = AppTheme.Colors.textDisabled)
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
                        color = AppTheme.Colors.textDisabled,
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

                    // Section: Session Settings
                    Text(
                        text = "Session Settings",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Message Buffer Size",
                            fontSize = 12.sp,
                            color = AppTheme.Colors.text,
                        )
                        SlimTextField(
                            value = sessionBufferSize,
                            onValueChange = { newValue ->
                                // Only allow digits
                                if (newValue.all { it.isDigit() }) {
                                    sessionBufferSize = newValue
                                }
                            },
                            placeholder = "1000",
                            modifier = Modifier.width(100.dp),
                        )
                        Text(
                            text = "messages per session (applies to new sessions)",
                            fontSize = 11.sp,
                            color = AppTheme.Colors.textDisabled,
                        )
                    }

                    // Validation message for buffer size
                    val bufferSizeInt = sessionBufferSize.toIntOrNull()
                    if (bufferSizeInt == null || bufferSizeInt < 100) {
                        Text(
                            text = "⚠ Buffer size must be at least 100",
                            fontSize = 11.sp,
                            color = AppTheme.Colors.warning,
                        )
                    } else if (bufferSizeInt > 100000) {
                        Text(
                            text = "⚠ Large buffer sizes may impact memory usage",
                            fontSize = 11.sp,
                            color = AppTheme.Colors.warning,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    CheckboxSetting(
                        label = "Auto-sync Session to Editor",
                        description = "Automatically select the connection profile in the message editor when switching session tabs",
                        checked = autoSyncSessionToEditor,
                        onCheckedChange = { autoSyncSessionToEditor = it },
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
                        color = AppTheme.Colors.textDisabled,
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
                                                }.background(if (isSelected) selectedItemBackgroundColor else Color.Transparent)
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "$tag - $name",
                                            fontSize = 12.sp,
                                            color = if (isSelected) selectedItemTextColor else AppTheme.Colors.text,
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
                            color = AppTheme.Colors.textDisabled,
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
                        color = AppTheme.Colors.textDisabled,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    CheckboxSetting(
                        label = "Hide Protocol Tags by Default",
                        description = "Automatically hide protocol tags when viewing message details",
                        checked = hideProtocolTags,
                        onCheckedChange = { hideProtocolTags = it },
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
                                                }.background(if (isSelected) selectedItemBackgroundColor else Color.Transparent)
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "$tag - $name",
                                            fontSize = 12.sp,
                                            color = if (isSelected) selectedItemTextColor else AppTheme.Colors.text,
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

                    // Section: Message Color Scheme
                    Text(
                        text = "Message Color Scheme",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Text(
                        text = "Choose color scheme for message display based on direction and type",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textDisabled,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    // Preset buttons in a row
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Default scheme button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color =
                                            if (messageColorScheme ==
                                                MessageColorScheme.default()
                                            ) {
                                                AppTheme.Colors.primary
                                            } else {
                                                AppTheme.Colors.surface
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color =
                                            if (messageColorScheme ==
                                                MessageColorScheme.default()
                                            ) {
                                                AppTheme.Colors.primary
                                            } else {
                                                AppTheme.Separators.color
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { messageColorScheme = MessageColorScheme.default() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Default",
                                    fontSize = 11.sp,
                                    color =
                                        if (messageColorScheme ==
                                            MessageColorScheme.default()
                                        ) {
                                            AppTheme.Colors.background
                                        } else {
                                            AppTheme.Colors.text
                                        },
                                )
                                // Color preview boxes
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Box(Modifier.size(12.dp).background(Color(0xFF569CD6), RoundedCornerShape(2.dp))) // Outgoing
                                    Box(Modifier.size(12.dp).background(Color(0xFF4EC9B0), RoundedCornerShape(2.dp))) // Incoming
                                    Box(Modifier.size(12.dp).background(Color(0xFFE06C75), RoundedCornerShape(2.dp))) // Rejection
                                }
                            }
                        }

                        // Green/Red scheme button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color =
                                            if (messageColorScheme ==
                                                MessageColorScheme.greenRed()
                                            ) {
                                                AppTheme.Colors.primary
                                            } else {
                                                AppTheme.Colors.surface
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color =
                                            if (messageColorScheme ==
                                                MessageColorScheme.greenRed()
                                            ) {
                                                AppTheme.Colors.primary
                                            } else {
                                                AppTheme.Separators.color
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { messageColorScheme = MessageColorScheme.greenRed() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Green/Red",
                                    fontSize = 11.sp,
                                    color =
                                        if (messageColorScheme ==
                                            MessageColorScheme.greenRed()
                                        ) {
                                            AppTheme.Colors.background
                                        } else {
                                            AppTheme.Colors.text
                                        },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Box(Modifier.size(12.dp).background(Color(0xFF98C379), RoundedCornerShape(2.dp))) // Outgoing
                                    Box(Modifier.size(12.dp).background(Color(0xFF569CD6), RoundedCornerShape(2.dp))) // Incoming
                                    Box(Modifier.size(12.dp).background(Color(0xFFE06C75), RoundedCornerShape(2.dp))) // Rejection
                                }
                            }
                        }

                        // High Contrast scheme button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color =
                                            if (messageColorScheme ==
                                                MessageColorScheme.highContrast()
                                            ) {
                                                AppTheme.Colors.primary
                                            } else {
                                                AppTheme.Colors.surface
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color =
                                            if (messageColorScheme ==
                                                MessageColorScheme.highContrast()
                                            ) {
                                                AppTheme.Colors.primary
                                            } else {
                                                AppTheme.Separators.color
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { messageColorScheme = MessageColorScheme.highContrast() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "High Contrast",
                                    fontSize = 11.sp,
                                    color =
                                        if (messageColorScheme ==
                                            MessageColorScheme.highContrast()
                                        ) {
                                            AppTheme.Colors.background
                                        } else {
                                            AppTheme.Colors.text
                                        },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Box(Modifier.size(12.dp).background(Color(0xFFFF8C00), RoundedCornerShape(2.dp))) // Outgoing (Orange)
                                    Box(Modifier.size(12.dp).background(Color(0xFF9370DB), RoundedCornerShape(2.dp))) // Incoming (Purple)
                                    Box(Modifier.size(12.dp).background(Color(0xFFE06C75), RoundedCornerShape(2.dp))) // Rejection (Red)
                                }
                            }
                        }

                        // Monochrome scheme button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color =
                                            if (messageColorScheme ==
                                                MessageColorScheme.monochrome()
                                            ) {
                                                AppTheme.Colors.primary
                                            } else {
                                                AppTheme.Colors.surface
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color =
                                            if (messageColorScheme ==
                                                MessageColorScheme.monochrome()
                                            ) {
                                                AppTheme.Colors.primary
                                            } else {
                                                AppTheme.Separators.color
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { messageColorScheme = MessageColorScheme.monochrome() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Monochrome",
                                    fontSize = 11.sp,
                                    color =
                                        if (messageColorScheme ==
                                            MessageColorScheme.monochrome()
                                        ) {
                                            AppTheme.Colors.background
                                        } else {
                                            AppTheme.Colors.text
                                        },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Box(
                                        Modifier
                                            .size(
                                                12.dp,
                                            ).background(
                                                Color(0xFFFFFFFF),
                                                RoundedCornerShape(2.dp),
                                            ).border(0.5.dp, Color(0xFF666666), RoundedCornerShape(2.dp)),
                                    ) // Outgoing (White with border)
                                    Box(
                                        Modifier
                                            .size(
                                                12.dp,
                                            ).background(
                                                Color(0xFFFFFFFF),
                                                RoundedCornerShape(2.dp),
                                            ).border(0.5.dp, Color(0xFF666666), RoundedCornerShape(2.dp)),
                                    ) // Incoming (White with border)
                                    Box(
                                        Modifier.size(12.dp).background(Color(0xFFE06C75), RoundedCornerShape(2.dp)),
                                    ) // Rejection (Red - only colored)
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Default View Mode
                    Text(
                        text = "Default View Mode",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Text(
                        text = "Choose the default view mode for new sessions",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textDisabled,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    // View mode selection buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Grid view button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color = if (defaultViewMode == "grid") AppTheme.Colors.primary else AppTheme.Colors.surface,
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color = if (defaultViewMode == "grid") AppTheme.Colors.primary else AppTheme.Separators.color,
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { defaultViewMode = "grid" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Grid View",
                                fontSize = 11.sp,
                                color = if (defaultViewMode == "grid") AppTheme.Colors.background else AppTheme.Colors.text,
                            )
                        }

                        // Terminal view button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color = if (defaultViewMode == "terminal") AppTheme.Colors.primary else AppTheme.Colors.surface,
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color = if (defaultViewMode == "terminal") AppTheme.Colors.primary else AppTheme.Separators.color,
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { defaultViewMode = "terminal" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Terminal View",
                                fontSize = 11.sp,
                                color = if (defaultViewMode == "terminal") AppTheme.Colors.background else AppTheme.Colors.text,
                            )
                        }
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Default Layout
                    Text(
                        text = "Default Layout",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Text(
                        text = "Choose the default layout for the application",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textDisabled,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    // Layout selection buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Horizontal split button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color = if (defaultLayout == "horizontal") AppTheme.Colors.primary else AppTheme.Colors.surface,
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color = if (defaultLayout == "horizontal") AppTheme.Colors.primary else AppTheme.Separators.color,
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { defaultLayout = "horizontal" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Horizontal Split",
                                fontSize = 11.sp,
                                color = if (defaultLayout == "horizontal") AppTheme.Colors.background else AppTheme.Colors.text,
                            )
                        }

                        // Vertical split button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color = if (defaultLayout == "vertical") AppTheme.Colors.primary else AppTheme.Colors.surface,
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color = if (defaultLayout == "vertical") AppTheme.Colors.primary else AppTheme.Separators.color,
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { defaultLayout = "vertical" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Vertical Split",
                                fontSize = 11.sp,
                                color = if (defaultLayout == "vertical") AppTheme.Colors.background else AppTheme.Colors.text,
                            )
                        }

                        // Tabs button
                        Box(
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .weight(1f)
                                    .background(
                                        color = if (defaultLayout == "tabs") AppTheme.Colors.primary else AppTheme.Colors.surface,
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color = if (defaultLayout == "tabs") AppTheme.Colors.primary else AppTheme.Separators.color,
                                        shape = RoundedCornerShape(4.dp),
                                    ).clickable { defaultLayout = "tabs" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Tabs",
                                fontSize = 11.sp,
                                color = if (defaultLayout == "tabs") AppTheme.Colors.background else AppTheme.Colors.text,
                            )
                        }
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Rejection Rules
                    Text(
                        text = "Rejection Rules",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Text(
                        text = "Configure which FIX messages should be highlighted in red as rejections",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textDisabled,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    // List of rejection rules
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rejectionRules.forEachIndexed { index, rule ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(AppTheme.Colors.surface, RoundedCornerShape(4.dp))
                                        .border(1.dp, AppTheme.Separators.color, RoundedCornerShape(4.dp))
                                        .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Message Type (Tag 35)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Message Type (35)",
                                        fontSize = 10.sp,
                                        color = AppTheme.Colors.textDisabled,
                                    )
                                    SlimTextField(
                                        value = rule.messageType,
                                        onValueChange = { newValue ->
                                            rejectionRules[index] = rule.copy(messageType = newValue)
                                        },
                                        placeholder = "e.g., 3, j, 9, AZ",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // Additional Tag (optional)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Additional Tag (optional)",
                                        fontSize = 10.sp,
                                        color = AppTheme.Colors.textDisabled,
                                    )
                                    SlimTextField(
                                        value = rule.additionalTag?.toString() ?: "",
                                        onValueChange = { newValue ->
                                            val tag = newValue.toIntOrNull()
                                            rejectionRules[index] = rule.copy(additionalTag = tag)
                                        },
                                        placeholder = "e.g., 905",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // Additional Value (optional)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Additional Value (optional)",
                                        fontSize = 10.sp,
                                        color = AppTheme.Colors.textDisabled,
                                    )
                                    SlimTextField(
                                        value = rule.additionalValue ?: "",
                                        onValueChange = { newValue ->
                                            rejectionRules[index] =
                                                rule.copy(
                                                    additionalValue = newValue.ifBlank { null },
                                                )
                                        },
                                        placeholder = "e.g., 3",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // Delete button
                                TooltipIconButton(
                                    tooltip = "Remove Rule",
                                    onClick = {
                                        rejectionRules =
                                            rejectionRules.toMutableList().apply {
                                                removeAt(index)
                                            }
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = AppTheme.Colors.textSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        // Add new rule button
                        SlimButton(
                            text = "+ Add Rejection Rule",
                            onClick = {
                                rejectionRules =
                                    rejectionRules.toMutableList().apply {
                                        add(RejectionRule(messageType = ""))
                                    }
                            },
                            containerColor = AppTheme.Colors.surface,
                            contentColor = AppTheme.Colors.primary,
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                        )
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Latency Tracking Settings
                    Text(
                        text = "Latency Tracking",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    Text(
                        text = "Measure round-trip time for correlated FIX messages (requires elevated privileges for packet-level capture)",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textDisabled,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    CheckboxSetting(
                        label = "Enable Latency Tracking",
                        description = "Track message round-trip times using correlation IDs (ClOrdID, QuoteReqID, etc.)",
                        checked = enableLatencyTracking,
                        onCheckedChange = { enableLatencyTracking = it },
                    )

                    if (enableLatencyTracking) {
                        Spacer(modifier = Modifier.height(8.dp))

                        CheckboxSetting(
                            label = "Show Latency Column in Grid View",
                            description = "Display latency values in the message grid",
                            checked = showLatencyColumn,
                            onCheckedChange = { showLatencyColumn = it },
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Network Interface
                        Text(
                            text = "Network Interface (leave empty for auto-detect)",
                            fontSize = 12.sp,
                            color = AppTheme.Colors.text,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        SlimTextField(
                            value = captureNetworkInterface,
                            onValueChange = { captureNetworkInterface = it },
                            placeholder = "Auto-detect",
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Threshold settings
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Warning Threshold (ms)",
                                    fontSize = 12.sp,
                                    color = AppTheme.Colors.text,
                                )
                                SlimTextField(
                                    value = latencyWarningThreshold,
                                    onValueChange = { newValue ->
                                        if (newValue.all { it.isDigit() }) {
                                            latencyWarningThreshold = newValue
                                        }
                                    },
                                    placeholder = "100",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Critical Threshold (ms)",
                                    fontSize = 12.sp,
                                    color = AppTheme.Colors.text,
                                )
                                SlimTextField(
                                    value = latencyCriticalThreshold,
                                    onValueChange = { newValue ->
                                        if (newValue.all { it.isDigit() }) {
                                            latencyCriticalThreshold = newValue
                                        }
                                    },
                                    placeholder = "500",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // History size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "History Size",
                                fontSize = 12.sp,
                                color = AppTheme.Colors.text,
                            )
                            SlimTextField(
                                value = latencyHistorySize,
                                onValueChange = { newValue ->
                                    if (newValue.all { it.isDigit() }) {
                                        latencyHistorySize = newValue
                                    }
                                },
                                placeholder = "10000",
                                modifier = Modifier.width(100.dp),
                            )
                            Text(
                                text = "samples",
                                fontSize = 11.sp,
                                color = AppTheme.Colors.textDisabled,
                            )
                        }
                    }

                    HorizontalDivider(
                        color = AppTheme.Separators.color,
                        thickness = AppTheme.Separators.dividerThickness,
                        modifier = Modifier.padding(vertical = AppTheme.Spacing.small),
                    )

                    // Section: Automation Control
                    Text(
                        text = "Automation Control",
                        fontSize = 14.sp,
                        color = AppTheme.Colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        text = "Run a loopback-only control + MCP server so Claude/MCP/curl can drive FixTool for " +
                            "automated testing. Off by default; applied when you click Save.",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textDisabled,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    CheckboxSetting(
                        label = "Enable automation control",
                        description = "Starts a local server on 127.0.0.1 at the port below (the FIXTOOL_CONTROL_PORT " +
                            "env var, if set, overrides this)",
                        checked = automationControlEnabled,
                        onCheckedChange = { automationControlEnabled = it },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "Port", fontSize = 12.sp, color = AppTheme.Colors.text)
                        SlimTextField(
                            value = automationControlPort,
                            onValueChange = { newValue -> if (newValue.all { it.isDigit() }) automationControlPort = newValue },
                            placeholder = "8765",
                            modifier = Modifier.width(100.dp),
                        )
                        Text(
                            text = "loopback only (127.0.0.1)",
                            fontSize = 11.sp,
                            color = AppTheme.Colors.textDisabled,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connect Claude Code with this command (uses the embedded MCP server):",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.textDisabled,
                    )
                    Text(
                        text = "claude mcp add --transport http fixtool " +
                            "http://127.0.0.1:${automationControlPort.ifBlank { "8765" }}/mcp",
                        fontSize = 11.sp,
                        color = AppTheme.Colors.text,
                    )

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
                        containerColor = cancelButtonColor,
                        contentColor = AppTheme.Colors.textSecondary,
                        modifier = Modifier.width(90.dp),
                    )

                    SlimButton(
                        text = "Save",
                        onClick = {
                            val newSettings =
                                currentSettings.copy(
                                    defaultDataDictionary = if (useBundledDictionary) "" else dataDictionaryPath.trim(),
                                    defaultTransportDictionary = if (useBundledDictionary) "" else transportDictionaryPath.trim(),
                                    defaultFixVersion = defaultFixVersion,
                                    useBundledDictionary = useBundledDictionary,
                                    validateFieldsOutOfOrder = validateFieldsOutOfOrder,
                                    validateFieldsHaveValues = validateFieldsHaveValues,
                                    validateUserDefinedFields = validateUserDefinedFields,
                                    validateIncomingMessage = validateIncomingMessage,
                                    sessionBufferSize = sessionBufferSize.toIntOrNull()?.coerceIn(100, 1000000) ?: 1000,
                                    gridViewColumns = gridViewColumns.toList(),
                                    hideProtocolTags = hideProtocolTags,
                                    protocolTags = protocolTags.toSet(),
                                    messageColorScheme = messageColorScheme,
                                    rejectionRules = rejectionRules.toList(),
                                    defaultViewMode = defaultViewMode,
                                    defaultLayout = defaultLayout,
                                    connectionProfilesPath = connectionProfilesPath.trim(),
                                    savedMessagesPath = savedMessagesPath.trim(),
                                    scenariosPath = scenariosPath.trim(),
                                    enableLatencyTracking = enableLatencyTracking,
                                    captureNetworkInterface = captureNetworkInterface.trim(),
                                    latencyHistorySize = latencyHistorySize.toIntOrNull()?.coerceIn(100, 100000) ?: 10000,
                                    latencyWarningThresholdMicros = (latencyWarningThreshold.toLongOrNull() ?: 100L) * 1000L,
                                    latencyCriticalThresholdMicros = (latencyCriticalThreshold.toLongOrNull() ?: 500L) * 1000L,
                                    showLatencyColumn = showLatencyColumn,
                                    autoSyncSessionToEditor = autoSyncSessionToEditor,
                                    automationControlEnabled = automationControlEnabled,
                                    automationControlPort = automationControlPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 8765,
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
                        color = AppTheme.Colors.textDisabled,
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
                    color = if (enabled) containerColor else cancelButtonColor,
                    shape = buttonCornerRadius,
                ).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else AppTheme.Colors.textDisabled,
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
                color = AppTheme.Colors.textDisabled,
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

// Constants
private val warningColor = Color(0xFFFFA500)
private val restoreDefaultsButtonColor = Color(0xFF4A4A4A)
private val cancelButtonColor = Color(0xFF3A3A3A)
private val selectedItemBackgroundColor = Color(0xFF2D4F7C)
private val selectedItemTextColor = Color(0xFFE0E0E0)

private val buttonCornerRadius = RoundedCornerShape(4.dp)
