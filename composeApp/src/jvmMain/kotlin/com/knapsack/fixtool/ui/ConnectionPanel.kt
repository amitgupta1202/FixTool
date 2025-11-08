package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun ConnectionPanel(
    profiles: List<FixConnectionProfile>,
    sessions: List<FixMessageSession>, // Used to trigger recomposition when sessions change
    onConnect: (profileId: String, profile: FixConnectionProfile) -> Unit,
    onDisconnect: (profileId: String) -> Unit,
    onSaveProfile: (profile: FixConnectionProfile) -> Unit,
    onDeleteProfile: (profileId: String) -> Unit,
    onCloneProfile: (profile: FixConnectionProfile) -> FixConnectionProfile,
    onGetProfileSession: (profileId: String) -> FixMessageSession?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Form state
    var selectedProfile by remember { mutableStateOf<FixConnectionProfile?>(null) }
    var profileName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var senderCompID by remember { mutableStateOf("") }
    var targetCompID by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("localhost") }
    var port by remember { mutableStateOf("") }
    var fixVersion by remember { mutableStateOf("FIX.4.4") }
    var heartBtInt by remember { mutableStateOf("30") }
    var resetOnLogon by remember { mutableStateOf(true) }
    var resetOnLogout by remember { mutableStateOf(false) }
    var resetOnDisconnect by remember { mutableStateOf(false) }
    var showHeartbeat by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }
    var customParameters by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var logonFields by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // SSL/TLS state
    var useSSL by remember { mutableStateOf(false) }
    var keyStorePath by remember { mutableStateOf("") }
    var keyStorePassword by remember { mutableStateOf("") }
    var trustStorePath by remember { mutableStateOf("") }
    var trustStorePassword by remember { mutableStateOf("") }
    var keyStoreType by remember { mutableStateOf("JKS") }
    var enabledProtocols by remember { mutableStateOf("TLSv1.2,TLSv1.3") }
    var cipherSuites by remember { mutableStateOf("") }
    var needClientAuth by remember { mutableStateOf(false) }

    // Get connection state for the selected profile (reactive)
    // Re-evaluate when sessions list changes (this triggers recomposition)
    val profileSession =
        selectedProfile?.let { profile ->
            sessions // Access sessions to make this reactive to session changes
            onGetProfileSession(profile.id)
        }
    val connectionState by (
        profileSession?.connectionState
            ?: MutableStateFlow(FixConnectionState.DISCONNECTED)
    ).collectAsState()

    // Track connection error message
    var connectionError by remember { mutableStateOf<String?>(null) }

    // Clear error when connection state changes from ERROR
    LaunchedEffect(connectionState) {
        if (connectionState != FixConnectionState.ERROR) {
            connectionError = null
        } else if (connectionError == null) {
            connectionError = "Connection failed. Please check your settings and try again."
        }
    }

    // Validation state
    fun isPortValid(port: String): Boolean {
        if (port.isBlank()) return false
        return port.toIntOrNull()?.let { it in 1..65535 } ?: false
    }

    fun isFormValid(): Boolean =
        senderCompID.isNotBlank() &&
            targetCompID.isNotBlank() &&
            host.isNotBlank() &&
            isPortValid(port)

    val isSenderCompIDError = senderCompID.isEmpty()
    val isTargetCompIDError = targetCompID.isEmpty()
    val isHostError = host.isEmpty()
    val isPortError = port.isEmpty() || !isPortValid(port)

    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(AppTheme.Colors.background),
    ) {
        // Top border
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // Header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FIX Connection",
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Close button
            TooltipIconButton(
                tooltip = "Close Connection Panel",
                onClick = onClose,
                modifier = iconSize24,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = iconSize16,
                )
            }
        }

        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // Content
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Profile management section
            ProfileSection(
                profiles = profiles,
                selectedProfile = selectedProfile,
                onProfileNameChange = { profileName = it },
                onSelectProfile = { profile ->
                    selectedProfile = profile
                    username = profile.config.username
                    senderCompID = profile.config.senderCompID
                    targetCompID = profile.config.targetCompID
                    password = profile.config.password
                    host = profile.config.host
                    port = profile.config.port
                    fixVersion = profile.config.beginString
                    heartBtInt = profile.config.heartBtInt
                    resetOnLogon = profile.config.resetOnLogon
                    resetOnLogout = profile.config.resetOnLogout
                    resetOnDisconnect = profile.config.resetOnDisconnect
                    showHeartbeat = profile.config.showHeartbeat
                    customParameters = profile.config.customParameters.map { it.key to it.value }
                    logonFields = profile.config.logonFields.map { it.key to it.value }
                    // SSL/TLS
                    useSSL = profile.config.useSSL
                    keyStorePath = profile.config.keyStorePath
                    keyStorePassword = profile.config.keyStorePassword
                    trustStorePath = profile.config.trustStorePath
                    trustStorePassword = profile.config.trustStorePassword
                    keyStoreType = profile.config.keyStoreType
                    enabledProtocols = profile.config.enabledProtocols
                    cipherSuites = profile.config.cipherSuites
                    needClientAuth = profile.config.needClientAuth
                },
                onSaveProfile = {
                    val config =
                        FixConnectionConfig(
                            username = username,
                            senderCompID = senderCompID,
                            targetCompID = targetCompID,
                            password = password,
                            host = host,
                            port = port,
                            beginString = fixVersion,
                            heartBtInt = heartBtInt,
                            resetOnLogon = resetOnLogon,
                            resetOnLogout = resetOnLogout,
                            resetOnDisconnect = resetOnDisconnect,
                            showHeartbeat = showHeartbeat,
                            useSSL = useSSL,
                            keyStorePath = keyStorePath,
                            keyStorePassword = keyStorePassword,
                            trustStorePath = trustStorePath,
                            trustStorePassword = trustStorePassword,
                            keyStoreType = keyStoreType,
                            enabledProtocols = enabledProtocols,
                            cipherSuites = cipherSuites,
                            needClientAuth = needClientAuth,
                            customParameters = customParameters.toMap(),
                            logonFields = logonFields.toMap(),
                        )
                    val profile =
                        FixConnectionProfile(
                            id =
                                selectedProfile?.id ?: java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            name = profileName.ifBlank { "Profile ${profiles.size + 1}" },
                            config = config,
                        )
                    onSaveProfile(profile)
                    selectedProfile = profile
                    // Update the profile name input field with the generated/saved name
                    profileName = profile.name
                },
                onDeleteProfile = { profile ->
                    onDeleteProfile(profile.id)
                    if (selectedProfile?.id == profile.id) {
                        // Clear selected profile and reset all form fields
                        selectedProfile = null
                        profileName = ""
                        username = ""
                        senderCompID = ""
                        targetCompID = ""
                        password = ""
                        host = "localhost"
                        port = ""
                        fixVersion = "FIX.4.4"
                        heartBtInt = "30"
                        resetOnLogon = true
                        resetOnLogout = false
                        resetOnDisconnect = false
                        showHeartbeat = true
                        useSSL = false
                        keyStorePath = ""
                        keyStorePassword = ""
                        trustStorePath = ""
                        trustStorePassword = ""
                        keyStoreType = "JKS"
                        enabledProtocols = "TLSv1.2,TLSv1.3"
                        cipherSuites = ""
                        needClientAuth = false
                        customParameters = emptyList()
                        logonFields = emptyList()
                    }
                },
                onCloneProfile = { profile ->
                    // Clone the profile and get the result
                    val clonedProfile = onCloneProfile(profile)
                    // Auto-select the cloned profile and populate form
                    selectedProfile = clonedProfile
                    profileName = clonedProfile.name
                    username = clonedProfile.config.username
                    senderCompID = clonedProfile.config.senderCompID
                    targetCompID = clonedProfile.config.targetCompID
                    password = clonedProfile.config.password
                    host = clonedProfile.config.host
                    port = clonedProfile.config.port
                    fixVersion = clonedProfile.config.beginString
                    heartBtInt = clonedProfile.config.heartBtInt
                    resetOnLogon = clonedProfile.config.resetOnLogon
                    resetOnLogout = clonedProfile.config.resetOnLogout
                    resetOnDisconnect = clonedProfile.config.resetOnDisconnect
                    showHeartbeat = clonedProfile.config.showHeartbeat
                    useSSL = clonedProfile.config.useSSL
                    keyStorePath = clonedProfile.config.keyStorePath
                    keyStorePassword = clonedProfile.config.keyStorePassword
                    trustStorePath = clonedProfile.config.trustStorePath
                    trustStorePassword = clonedProfile.config.trustStorePassword
                    keyStoreType = clonedProfile.config.keyStoreType
                    enabledProtocols = clonedProfile.config.enabledProtocols
                    cipherSuites = clonedProfile.config.cipherSuites
                    needClientAuth = clonedProfile.config.needClientAuth
                    customParameters = clonedProfile.config.customParameters.map { it.key to it.value }
                    logonFields = clonedProfile.config.logonFields.map { it.key to it.value }
                },
            )

            // Profile Name and Status on same row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Profile Name field
                ConnectionField(
                    label = "Profile Name",
                    value = profileName,
                    onValueChange = { profileName = it },
                    placeholder = "Enter profile name",
                    modifier = Modifier.weight(1f),
                )

                // Status indicator
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Status",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .background(AppTheme.Colors.surfaceVariant, inputShape)
                                .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .background(connectionState.getColor(), RoundedCornerShape(4.dp)),
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        Text(
                            text = connectionState.getDisplayText(),
                            color = connectionState.getColor(),
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }
            }

            // Basic connection fields
            SectionLabel("Connection Settings")

            // SenderCompID and TargetCompID on same row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionField(
                    label = "SenderCompID",
                    value = senderCompID,
                    onValueChange = { senderCompID = it },
                    placeholder = "SenderCompID",
                    isError = isSenderCompIDError,
                    errorMessage = "Required",
                    modifier = Modifier.weight(1f),
                )

                ConnectionField(
                    label = "TargetCompID",
                    value = targetCompID,
                    onValueChange = { targetCompID = it },
                    placeholder = "TargetCompID",
                    isError = isTargetCompIDError,
                    errorMessage = "Required",
                    modifier = Modifier.weight(1f),
                )
            }

            // Username and Password on same row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionField(
                    label = "Username",
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "Username",
                    modifier = Modifier.weight(1f),
                )

                ConnectionField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Password (optional)",
                    isPassword = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionField(
                    label = "Host",
                    value = host,
                    onValueChange = { host = it },
                    placeholder = "localhost",
                    isError = isHostError,
                    errorMessage = "Required",
                    modifier = Modifier.weight(1f),
                )

                ConnectionField(
                    label = "Port",
                    value = port,
                    onValueChange = { port = it },
                    placeholder = "9876",
                    isError = isPortError,
                    errorMessage = if (port.isEmpty()) "Required" else "Invalid port (1-65535)",
                    modifier = Modifier.weight(1f),
                )
            }

            // Info text about auto-detection
            Text(
                text = "ℹ FIX Version is automatically detected from the Data Dictionary (configured in Settings)",
                color = AppTheme.Colors.textDisabled,
                fontSize = 9.sp,
                modifier = Modifier.padding(vertical = 4.dp),
                lineHeight = 12.sp,
            )

            // Advanced settings toggle
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TooltipIconButton(
                    tooltip = if (showAdvanced) "Hide Advanced Settings" else "Show Advanced Settings",
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = iconSize20,
                ) {
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle Advanced",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "Advanced Settings",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 10.sp,
                )
            }

            // Advanced settings (collapsible)
            if (showAdvanced) {
                // HeartBtInt and Show Heartbeat on same row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ConnectionField(
                        label = "HeartBtInt (seconds)",
                        value = heartBtInt,
                        onValueChange = { heartBtInt = it },
                        placeholder = "30",
                        modifier = Modifier.weight(1f),
                    )

                    // Inline checkbox for Show Heartbeat
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .padding(bottom = 2.dp)
                                .clickable { showHeartbeat = !showHeartbeat },
                    ) {
                        Box(
                            modifier =
                                checkboxSize16
                                    .background(
                                        color = checkboxBackgroundColor(showHeartbeat),
                                        shape = checkboxShape,
                                    ).border(
                                        width = 1.dp,
                                        color = checkboxBorderColor(showHeartbeat),
                                        shape = checkboxShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (showHeartbeat) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppTheme.Colors.background,
                                    modifier = iconSize12,
                                )
                            }
                        }
                        Text(
                            text = "Show Heartbeat",
                            color = AppTheme.Colors.textSecondary,
                            fontSize = 9.sp,
                        )
                    }
                }

                // Reset Options - all in one row
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reset Options",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 9.sp,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Reset on Logon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { resetOnLogon = !resetOnLogon },
                    ) {
                        Box(
                            modifier =
                                checkboxSize14
                                    .background(
                                        color = checkboxBackgroundColor(resetOnLogon),
                                        shape = checkboxShape,
                                    ).border(
                                        width = 1.dp,
                                        color = checkboxBorderColor(resetOnLogon),
                                        shape = checkboxShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (resetOnLogon) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppTheme.Colors.background,
                                    modifier = iconSize10,
                                )
                            }
                        }
                        Text(
                            text = "Logon",
                            color = AppTheme.Colors.textSecondary,
                            fontSize = 9.sp,
                        )
                    }

                    // Reset on Logout
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { resetOnLogout = !resetOnLogout },
                    ) {
                        Box(
                            modifier =
                                checkboxSize14
                                    .background(
                                        color = checkboxBackgroundColor(resetOnLogout),
                                        shape = checkboxShape,
                                    ).border(
                                        width = 1.dp,
                                        color = checkboxBorderColor(resetOnLogout),
                                        shape = checkboxShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (resetOnLogout) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppTheme.Colors.background,
                                    modifier = iconSize10,
                                )
                            }
                        }
                        Text(
                            text = "Logout",
                            color = AppTheme.Colors.textSecondary,
                            fontSize = 9.sp,
                        )
                    }

                    // Reset on Disconnect
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { resetOnDisconnect = !resetOnDisconnect },
                    ) {
                        Box(
                            modifier =
                                checkboxSize14
                                    .background(
                                        color = checkboxBackgroundColor(resetOnDisconnect),
                                        shape = checkboxShape,
                                    ).border(
                                        width = 1.dp,
                                        color = checkboxBorderColor(resetOnDisconnect),
                                        shape = checkboxShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (resetOnDisconnect) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppTheme.Colors.background,
                                    modifier = iconSize10,
                                )
                            }
                        }
                        Text(
                            text = "Disconnect",
                            color = AppTheme.Colors.textSecondary,
                            fontSize = 9.sp,
                        )
                    }
                }

                // SSL/TLS Configuration Section
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable { useSSL = !useSSL },
                ) {
                    Box(
                        modifier =
                            checkboxSize14
                                .background(
                                    color = checkboxBackgroundColor(useSSL),
                                    shape = checkboxShape,
                                ).border(
                                    width = 1.dp,
                                    color = checkboxBorderColor(useSSL),
                                    shape = checkboxShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (useSSL) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = AppTheme.Colors.background,
                                modifier = iconSize10,
                            )
                        }
                    }
                    Text(
                        text = "Use SSL/TLS",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 9.sp,
                    )
                }

                // SSL/TLS settings (show only when SSL is enabled)
                if (useSSL) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // KeyStore Path
                    ConnectionField(
                        label = "KeyStore Path",
                        value = keyStorePath,
                        onValueChange = { keyStorePath = it },
                        placeholder = "/path/to/keystore.jks",
                        isError = false,
                    )

                    // KeyStore Password
                    ConnectionField(
                        label = "KeyStore Password",
                        value = keyStorePassword,
                        onValueChange = { keyStorePassword = it },
                        placeholder = "********",
                        isError = false,
                    )

                    // TrustStore Path
                    ConnectionField(
                        label = "TrustStore Path",
                        value = trustStorePath,
                        onValueChange = { trustStorePath = it },
                        placeholder = "/path/to/truststore.jks",
                        isError = false,
                    )

                    // TrustStore Password
                    ConnectionField(
                        label = "TrustStore Password",
                        value = trustStorePassword,
                        onValueChange = { trustStorePassword = it },
                        placeholder = "********",
                        isError = false,
                    )

                    // KeyStore Type
                    ConnectionField(
                        label = "KeyStore Type",
                        value = keyStoreType,
                        onValueChange = { keyStoreType = it },
                        placeholder = "JKS, PKCS12, etc.",
                        isError = false,
                    )

                    // Enabled Protocols
                    ConnectionField(
                        label = "Enabled Protocols",
                        value = enabledProtocols,
                        onValueChange = { enabledProtocols = it },
                        placeholder = "TLSv1.2,TLSv1.3",
                        isError = false,
                    )

                    // Cipher Suites (optional)
                    ConnectionField(
                        label = "Cipher Suites (optional)",
                        value = cipherSuites,
                        onValueChange = { cipherSuites = it },
                        placeholder = "Leave empty for defaults",
                        isError = false,
                    )

                    // Need Client Auth (for acceptors)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { needClientAuth = !needClientAuth },
                    ) {
                        Box(
                            modifier =
                                checkboxSize14
                                    .background(
                                        color = checkboxBackgroundColor(needClientAuth),
                                        shape = checkboxShape,
                                    ).border(
                                        width = 1.dp,
                                        color = checkboxBorderColor(needClientAuth),
                                        shape = checkboxShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (needClientAuth) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppTheme.Colors.background,
                                    modifier = iconSize10,
                                )
                            }
                        }
                        Text(
                            text = "Require Client Auth (Acceptor only)",
                            color = AppTheme.Colors.textSecondary,
                            fontSize = 9.sp,
                        )
                    }
                }

                // Custom Parameters Section
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Custom Parameters",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 9.sp,
                    )

                    TooltipIconButton(
                        tooltip = "Add Custom Parameter",
                        onClick = {
                            customParameters = customParameters + ("" to "")
                        },
                        modifier = iconSize18,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Parameter",
                            tint = AppTheme.Colors.primary,
                            modifier = iconSize14,
                        )
                    }
                }

                customParameters.forEachIndexed { index, param ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val keyInteractionSource = remember { MutableInteractionSource() }
                        val keyIsFocused by keyInteractionSource.collectIsFocusedAsState()

                        BasicTextField(
                            value = param.first,
                            onValueChange = { newKey ->
                                customParameters =
                                    customParameters.toMutableList().apply {
                                        this[index] = newKey to param.second
                                    }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .background(AppTheme.Colors.surface, inputShape)
                                    .border(1.dp, AppTheme.Colors.border, inputShape)
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            textStyle =
                                TextStyle(
                                    fontSize = 10.sp,
                                    color = placeholderColor(param.first.isEmpty(), keyIsFocused),
                                    fontFamily = FontFamily.Monospace,
                                ),
                            singleLine = true,
                            cursorBrush = SolidColor(AppTheme.Colors.primary),
                            interactionSource = keyInteractionSource,
                            decorationBox = { innerTextField ->
                                if (param.first.isEmpty() && !keyIsFocused) {
                                    Text(
                                        text = "Key",
                                        style =
                                            TextStyle(
                                                fontSize = 10.sp,
                                                color = AppTheme.Colors.textDisabled,
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                    )
                                }
                                innerTextField()
                            },
                        )

                        val valueInteractionSource = remember { MutableInteractionSource() }
                        val valueIsFocused by valueInteractionSource.collectIsFocusedAsState()

                        BasicTextField(
                            value = param.second,
                            onValueChange = { newValue ->
                                customParameters =
                                    customParameters.toMutableList().apply {
                                        this[index] = param.first to newValue
                                    }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .background(AppTheme.Colors.surface, inputShape)
                                    .border(1.dp, AppTheme.Colors.border, inputShape)
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            textStyle =
                                TextStyle(
                                    fontSize = 10.sp,
                                    color = placeholderColor(param.second.isEmpty(), valueIsFocused),
                                    fontFamily = FontFamily.Monospace,
                                ),
                            singleLine = true,
                            cursorBrush = SolidColor(AppTheme.Colors.primary),
                            interactionSource = valueInteractionSource,
                            decorationBox = { innerTextField ->
                                if (param.second.isEmpty() && !valueIsFocused) {
                                    Text(
                                        text = "Value",
                                        style =
                                            TextStyle(
                                                fontSize = 10.sp,
                                                color = AppTheme.Colors.textDisabled,
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                    )
                                }
                                innerTextField()
                            },
                        )

                        TooltipIconButton(
                            tooltip = "Delete Parameter",
                            onClick = {
                                customParameters =
                                    customParameters.toMutableList().apply {
                                        removeAt(index)
                                    }
                            },
                            modifier = iconSize18,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = AppTheme.Colors.error,
                                modifier = iconSize14,
                            )
                        }
                    }
                }

                // Logon Fields Section
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Logon Message Fields",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 9.sp,
                    )

                    TooltipIconButton(
                        tooltip = "Add Logon Field",
                        onClick = {
                            logonFields = logonFields + ("" to "")
                        },
                        modifier = iconSize18,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Logon Field",
                            tint = AppTheme.Colors.primary,
                            modifier = iconSize14,
                        )
                    }
                }

                logonFields.forEachIndexed { index, field ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tagInteractionSource = remember { MutableInteractionSource() }
                        val tagIsFocused by tagInteractionSource.collectIsFocusedAsState()

                        BasicTextField(
                            value = field.first,
                            onValueChange = { newTag ->
                                logonFields =
                                    logonFields.toMutableList().apply {
                                        this[index] = newTag to field.second
                                    }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .background(AppTheme.Colors.surface, inputShape)
                                    .border(1.dp, AppTheme.Colors.border, inputShape)
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            textStyle =
                                TextStyle(
                                    fontSize = 10.sp,
                                    color = placeholderColor(field.first.isEmpty(), tagIsFocused),
                                    fontFamily = FontFamily.Monospace,
                                ),
                            singleLine = true,
                            cursorBrush = SolidColor(AppTheme.Colors.primary),
                            interactionSource = tagInteractionSource,
                            decorationBox = { innerTextField ->
                                if (field.first.isEmpty() && !tagIsFocused) {
                                    Text(
                                        text = "Tag",
                                        style =
                                            TextStyle(
                                                fontSize = 10.sp,
                                                color = AppTheme.Colors.textDisabled,
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                    )
                                }
                                innerTextField()
                            },
                        )

                        val valueInteractionSource = remember { MutableInteractionSource() }
                        val valueIsFocused by valueInteractionSource.collectIsFocusedAsState()

                        BasicTextField(
                            value = field.second,
                            onValueChange = { newValue ->
                                logonFields =
                                    logonFields.toMutableList().apply {
                                        this[index] = field.first to newValue
                                    }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .background(AppTheme.Colors.surface, inputShape)
                                    .border(1.dp, AppTheme.Colors.border, inputShape)
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            textStyle =
                                TextStyle(
                                    fontSize = 10.sp,
                                    color = placeholderColor(field.second.isEmpty(), valueIsFocused),
                                    fontFamily = FontFamily.Monospace,
                                ),
                            singleLine = true,
                            cursorBrush = SolidColor(AppTheme.Colors.primary),
                            interactionSource = valueInteractionSource,
                            decorationBox = { innerTextField ->
                                if (field.second.isEmpty() && !valueIsFocused) {
                                    Text(
                                        text = "Value",
                                        style =
                                            TextStyle(
                                                fontSize = 10.sp,
                                                color = AppTheme.Colors.textDisabled,
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                    )
                                }
                                innerTextField()
                            },
                        )

                        TooltipIconButton(
                            tooltip = "Delete Logon Field",
                            onClick = {
                                logonFields =
                                    logonFields.toMutableList().apply {
                                        removeAt(index)
                                    }
                            },
                            modifier = iconSize18,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = AppTheme.Colors.error,
                                modifier = iconSize14,
                            )
                        }
                    }
                }
            }
        }

        // Connection buttons at bottom
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        // Error message display
        if (connectionError != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(errorBackgroundColor)
                        .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = AppTheme.Colors.error,
                    modifier = iconSize16,
                )
                Text(
                    text = connectionError ?: "",
                    color = AppTheme.Colors.error,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                TooltipIconButton(
                    tooltip = "Dismiss",
                    onClick = { connectionError = null },
                    modifier = iconSize20,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize14,
                    )
                }
            }
            HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surface)
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Connect button
            SlimButton(
                text = if (connectionState == FixConnectionState.CONNECTING) "Connecting..." else "Connect",
                onClick = {
                    connectionError = null // Clear any previous error
                    val config =
                        FixConnectionConfig(
                            username = username,
                            senderCompID = senderCompID,
                            targetCompID = targetCompID,
                            password = password,
                            host = host,
                            port = port,
                            beginString = fixVersion,
                            heartBtInt = heartBtInt,
                            resetOnLogon = resetOnLogon,
                            resetOnLogout = resetOnLogout,
                            resetOnDisconnect = resetOnDisconnect,
                            showHeartbeat = showHeartbeat,
                            useSSL = useSSL,
                            keyStorePath = keyStorePath,
                            keyStorePassword = keyStorePassword,
                            trustStorePath = trustStorePath,
                            trustStorePassword = trustStorePassword,
                            keyStoreType = keyStoreType,
                            enabledProtocols = enabledProtocols,
                            cipherSuites = cipherSuites,
                            needClientAuth = needClientAuth,
                            customParameters = customParameters.toMap(),
                            logonFields = logonFields.toMap(),
                        )
                    // Create or update the profile with current form values
                    val profile =
                        FixConnectionProfile(
                            id =
                                selectedProfile?.id ?: java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            name = profileName.ifBlank { selectedProfile?.name ?: "Unnamed" },
                            config = config,
                        )
                    // Update selectedProfile to ensure proper session lookup
                    selectedProfile = profile
                    onConnect(profile.id, profile)
                },
                enabled = connectionState.canConnect() && isFormValid(),
                containerColor = AppTheme.Colors.primary,
                contentColor = AppTheme.Colors.background,
                modifier = Modifier.weight(1f),
            )

            // Disconnect button
            SlimButton(
                text = "Disconnect",
                onClick = {
                    selectedProfile?.let { onDisconnect(it.id) }
                },
                enabled = connectionState.canDisconnect(),
                containerColor = AppTheme.Colors.warning,
                contentColor = AppTheme.Colors.background,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = AppTheme.Colors.primary,
        fontSize = 10.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ConnectionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isPassword: Boolean = false,
    isError: Boolean = false,
    errorMessage: String = "",
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = if (isError) AppTheme.Colors.error else AppTheme.Colors.textSecondary,
            fontSize = 9.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )

        SlimTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(24.dp),
            textStyle = TextStyle(fontSize = 10.sp, color = AppTheme.Colors.text),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            isError = isError,
        )

        if (isError && errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                color = AppTheme.Colors.error,
                fontSize = 8.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SlimTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle = TextStyle(fontSize = 10.sp, color = AppTheme.Colors.text),
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val fieldBorderColor =
        when {
            isError -> AppTheme.Colors.error
            isFocused -> AppTheme.Colors.primary
            else -> AppTheme.Colors.border
        }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .background(AppTheme.Colors.surface, inputShape)
                .border(
                    width = 1.dp,
                    color = fieldBorderColor,
                    shape = inputShape,
                ).padding(horizontal = 4.dp, vertical = 4.dp),
        textStyle = textStyle,
        singleLine = singleLine,
        cursorBrush = SolidColor(AppTheme.Colors.primary),
        interactionSource = interactionSource,
        visualTransformation = visualTransformation,
    )
}

@Composable
private fun ProfileSection(
    profiles: List<FixConnectionProfile>,
    selectedProfile: FixConnectionProfile?,
    onProfileNameChange: (String) -> Unit,
    onSelectProfile: (FixConnectionProfile) -> Unit,
    onSaveProfile: () -> Unit,
    onDeleteProfile: (FixConnectionProfile) -> Unit,
    onCloneProfile: (FixConnectionProfile) -> Unit,
) {
    Column {
        SectionLabel("Connection Profile")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Profile dropdown/selector
            if (profiles.isNotEmpty()) {
                // Create a dummy option for "Select profile..."
                val profileOptions = listOf<FixConnectionProfile?>(null) + profiles
                SlimDropdown(
                    value = selectedProfile,
                    options = profileOptions,
                    onValueChange = { profile ->
                        profile?.let {
                            onSelectProfile(it)
                            onProfileNameChange(it.name)
                        }
                    },
                    displayText = { it?.name ?: "Select profile..." },
                    placeholder = "Select profile...",
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .background(AppTheme.Colors.surface, inputShape)
                            .border(1.dp, AppTheme.Colors.border, inputShape)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "No saved profiles",
                        color = AppTheme.Colors.textDisabled,
                        fontSize = 10.sp,
                    )
                }
            }

            // Save profile button
            TooltipIconButton(
                tooltip = "Save Profile",
                onClick = onSaveProfile,
                modifier = iconSize24,
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save Profile",
                    tint = AppTheme.Colors.primary,
                    modifier = iconSize16,
                )
            }

            // Clone profile button
            if (selectedProfile != null) {
                TooltipIconButton(
                    tooltip = "Clone Profile",
                    onClick = { onCloneProfile(selectedProfile) },
                    modifier = iconSize24,
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Clone Profile",
                        tint = AppTheme.Colors.info,
                        modifier = iconSize16,
                    )
                }
            }

            // Delete profile button
            if (selectedProfile != null) {
                TooltipIconButton(
                    tooltip = "Delete Profile",
                    onClick = { onDeleteProfile(selectedProfile) },
                    modifier = iconSize24,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Profile",
                        tint = AppTheme.Colors.warning,
                        modifier = iconSize16,
                    )
                }
            }
        }
    }
}

/**
 * Slim checkbox component for compact UIs
 */
@Composable
private fun SlimCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(20.dp)
                .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = AppTheme.Colors.textSecondary,
            fontSize = 9.sp,
        )

        Box(
            modifier =
                checkboxSize16
                    .background(
                        color = checkboxBackgroundColor(checked),
                        shape = checkboxShape,
                    ).border(
                        width = 1.dp,
                        color = checkboxBorderColor(checked),
                        shape = checkboxShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppTheme.Colors.background,
                    modifier = iconSize12,
                )
            }
        }
    }
}

/**
 * Slim button component for compact UIs
 */
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
                .height(24.dp)
                .background(
                    color = if (enabled) containerColor else AppTheme.Colors.border,
                    shape = RoundedCornerShape(2.dp),
                ).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
    }
}

// Component-specific color constants (not in AppTheme)
private val errorBackgroundColor = Color(0xFF3A1F1F)

// Helper functions for conditional colors
private fun checkboxBackgroundColor(checked: Boolean) = if (checked) AppTheme.Colors.primary else AppTheme.Colors.surface

private fun checkboxBorderColor(checked: Boolean) = if (checked) AppTheme.Colors.primary else AppTheme.Colors.textDisabled

private fun placeholderColor(isEmpty: Boolean, isFocused: Boolean) = if (isEmpty && !isFocused) AppTheme.Colors.textDisabled else AppTheme.Colors.text

// Common modifiers
private val iconSize24 = Modifier.size(24.dp)
private val iconSize20 = Modifier.size(20.dp)
private val iconSize18 = Modifier.size(18.dp)
private val iconSize16 = Modifier.size(16.dp)
private val iconSize14 = Modifier.size(14.dp)
private val iconSize12 = Modifier.size(12.dp)
private val iconSize10 = Modifier.size(10.dp)
private val checkboxSize16 = Modifier.size(16.dp)
private val checkboxSize14 = Modifier.size(14.dp)
private val checkboxShape = RoundedCornerShape(2.dp)
private val inputShape = RoundedCornerShape(2.dp)
