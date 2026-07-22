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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.SessionIdentityResolver

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
    onGetProfileSessions: (profileId: String) -> List<FixMessageSession> = { emptyList() },
    onClose: () -> Unit,
    /** Profile id or name to load into the form, driven by the control surface. */
    selectionRequest: String? = null,
    demoServerRunning: Boolean = false,
    demoServerFixVersion: FixVersion? = null,
    onStartDemoServer: ((FixVersion) -> Unit)? = null,
    onStopDemoServer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Form state
    var selectedProfile by remember { mutableStateOf<FixConnectionProfile?>(null) }
    var profileName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var senderCompID by remember { mutableStateOf("") }
    var targetCompID by remember { mutableStateOf("") }
    var sessionQualifier by remember { mutableStateOf("") }
    var sessionCount by remember { mutableStateOf("1") }
    var password by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("localhost") }
    var port by remember { mutableStateOf("") }
    var selectedFixVersion by remember { mutableStateOf(FixVersion.DEFAULT) }
    var heartBtInt by remember { mutableStateOf("30") }
    var socketConnectTimeout by remember { mutableStateOf("10") }
    var reconnectInterval by remember { mutableStateOf("30") }
    var autoReconnect by remember { mutableStateOf(true) }
    var resetOnLogon by remember { mutableStateOf(true) }
    var resetOnLogout by remember { mutableStateOf(false) }
    var resetOnDisconnect by remember { mutableStateOf(false) }
    var showHeartbeat by remember { mutableStateOf(true) }
    var connectionType by remember { mutableStateOf(FixConnectionConfig.ConnectionType.INITIATOR) }
    var socketAcceptPort by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var customParameters by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var logonFields by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var acceptorRules by remember { mutableStateOf<List<AcceptorResponseRule>>(emptyList()) }

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

    // Get connection states for the selected profile's sessions (reactive).
    // A profile owns multiple sessions when sessionCount > 1, so aggregate across the group.
    // Re-evaluate when sessions list changes (this triggers recomposition)
    val profileSessions =
        selectedProfile
            ?.let { profile ->
                sessions // Access sessions to make this reactive to session changes
                onGetProfileSessions(profile.id).ifEmpty { listOfNotNull(onGetProfileSession(profile.id)) }
            }.orEmpty()
    val sessionStates = profileSessions.map { it.connectionState.collectAsState().value }

    // Representative state: the most-connected session in the group (identical to the
    // session's own state for single-session profiles)
    fun stateRank(state: FixConnectionState): Int =
        when (state) {
            FixConnectionState.LOGGED_ON -> 0
            FixConnectionState.CONNECTED -> 1
            FixConnectionState.CONNECTING -> 2
            FixConnectionState.ERROR -> 3
            FixConnectionState.DISCONNECTED -> 4
        }
    val connectionState = sessionStates.minByOrNull { stateRank(it) } ?: FixConnectionState.DISCONNECTED
    val anySessionInError = sessionStates.any { it == FixConnectionState.ERROR }
    val loggedOnSessionCount = sessionStates.count { it == FixConnectionState.LOGGED_ON }

    // Track connection error message
    var connectionError by remember { mutableStateOf<String?>(null) }

    // The whole of "this profile is now the one on the form". Extracted from the profile-list click
    // handler so the control surface can reach it too — a form only a mouse can populate is a form an
    // agent cannot screenshot or drive.
    fun loadProfileIntoForm(profile: FixConnectionProfile) {
        selectedProfile = profile
        username = profile.config.username
        senderCompID = profile.config.senderCompID
        targetCompID = profile.config.targetCompID
        sessionQualifier = profile.config.sessionQualifier
        sessionCount = profile.config.sessionCount.toString()
        password = profile.config.password
        host = profile.config.host
        port = profile.config.port
        selectedFixVersion = FixVersion.fromBeginString(profile.config.beginString, profile.config.applVerID)
        heartBtInt = profile.config.heartBtInt
        socketConnectTimeout = profile.config.socketConnectTimeout
        reconnectInterval = profile.config.reconnectInterval
        autoReconnect = profile.config.autoReconnect
        resetOnLogon = profile.config.resetOnLogon
        resetOnLogout = profile.config.resetOnLogout
        resetOnDisconnect = profile.config.resetOnDisconnect
        showHeartbeat = profile.config.showHeartbeat
        connectionType = profile.config.connectionType
        socketAcceptPort = profile.config.socketAcceptPort
        customParameters = profile.config.customParameters.map { it.key to it.value }
        logonFields = profile.config.logonFields.map { it.key to it.value }
        acceptorRules = profile.config.acceptorResponseRules
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
    }

    // A profile named through POST /panel {"panel":"connection","profile":"…"} lands on the form.
    LaunchedEffect(selectionRequest, profiles) {
        val key = selectionRequest ?: return@LaunchedEffect
        profiles.firstOrNull { it.id == key || it.name == key }?.let { loadProfileIntoForm(it) }
    }

    // Clear error when no session is in ERROR state anymore
    LaunchedEffect(anySessionInError) {
        if (!anySessionInError) {
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

    fun isSessionCountValid(count: String): Boolean = count.isBlank() || count.toIntOrNull()?.let { it in 1..100 } ?: false

    fun parsedSessionCount(): Int = sessionCount.toIntOrNull()?.coerceIn(1, 100) ?: 1

    // Minimal config carrying just the per-session identity fields, for validation and preview
    fun identityPreviewConfig(): FixConnectionConfig =
        FixConnectionConfig(
            username = username,
            senderCompID = senderCompID,
            targetCompID = targetCompID,
            sessionQualifier = sessionQualifier,
            password = password,
        )

    fun identityErrors(): List<String> =
        if (connectionType == FixConnectionConfig.ConnectionType.INITIATOR) {
            SessionIdentityResolver.validate(identityPreviewConfig(), parsedSessionCount())
        } else {
            emptyList()
        }

    fun isFormValid(): Boolean =
        senderCompID.isNotBlank() &&
            targetCompID.isNotBlank() &&
            if (connectionType == FixConnectionConfig.ConnectionType.INITIATOR) {
                host.isNotBlank() && isPortValid(port) && isSessionCountValid(sessionCount) && identityErrors().isEmpty()
            } else {
                isPortValid(socketAcceptPort.ifBlank { port })
            }

    val isSenderCompIDError = senderCompID.isEmpty()
    val isTargetCompIDError = targetCompID.isEmpty()
    val isHostError = connectionType == FixConnectionConfig.ConnectionType.INITIATOR && host.isEmpty()
    val isPortError = connectionType == FixConnectionConfig.ConnectionType.INITIATOR && (port.isEmpty() || !isPortValid(port))
    val isAcceptPortError =
        connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR &&
            (socketAcceptPort.ifBlank { port }.isEmpty() || !isPortValid(socketAcceptPort.ifBlank { port }))

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
                onSelectProfile = { profile -> loadProfileIntoForm(profile) },
                onSaveProfile = {
                    // Built by copying the profile being edited, not by constructing a fresh config
                    // from the panel's fields. Constructing one silently reset every setting this
                    // panel does not show — fileStorePath, fileLogPath, socketConnectPort, startTime,
                    // endTime and the acceptor response rules all went back to their defaults the
                    // first time anyone pressed Save, and the rules were *deleted*, having no default
                    // to fall back to. Copying inverts the default: a field this panel does not edit
                    // survives, and a field added to FixConnectionConfig later survives without
                    // anyone having to remember this line.
                    val config =
                        (selectedProfile?.config ?: FixConnectionConfig()).copy(
                            username = username,
                            senderCompID = senderCompID,
                            targetCompID = targetCompID,
                            sessionQualifier = sessionQualifier,
                            sessionCount = parsedSessionCount(),
                            password = password,
                            host = host,
                            port = port,
                            socketConnectHost = host, // Use the same host for connection
                            connectionType = connectionType,
                            socketAcceptPort = socketAcceptPort,
                            beginString = selectedFixVersion.beginString,
                            applVerID = selectedFixVersion.applVerID,
                            heartBtInt = heartBtInt,
                            socketConnectTimeout = socketConnectTimeout,
                            reconnectInterval = reconnectInterval,
                            autoReconnect = autoReconnect,
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
                            acceptorResponseRules = acceptorRules,
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
                        sessionQualifier = ""
                        sessionCount = "1"
                        password = ""
                        host = "localhost"
                        port = ""
                        selectedFixVersion = FixVersion.DEFAULT
                        heartBtInt = "30"
                        socketConnectTimeout = "10"
                        reconnectInterval = "30"
                        autoReconnect = true
                        resetOnLogon = true
                        resetOnLogout = false
                        resetOnDisconnect = false
                        showHeartbeat = true
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR
                        socketAcceptPort = ""
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
                        acceptorRules = emptyList()
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
                    sessionQualifier = clonedProfile.config.sessionQualifier
                    sessionCount = clonedProfile.config.sessionCount.toString()
                    password = clonedProfile.config.password
                    host = clonedProfile.config.host
                    port = clonedProfile.config.port
                    selectedFixVersion = FixVersion.fromBeginString(clonedProfile.config.beginString, clonedProfile.config.applVerID)
                    heartBtInt = clonedProfile.config.heartBtInt
                    socketConnectTimeout = clonedProfile.config.socketConnectTimeout
                    reconnectInterval = clonedProfile.config.reconnectInterval
                    autoReconnect = clonedProfile.config.autoReconnect
                    resetOnLogon = clonedProfile.config.resetOnLogon
                    resetOnLogout = clonedProfile.config.resetOnLogout
                    resetOnDisconnect = clonedProfile.config.resetOnDisconnect
                    showHeartbeat = clonedProfile.config.showHeartbeat
                    connectionType = clonedProfile.config.connectionType
                    socketAcceptPort = clonedProfile.config.socketAcceptPort
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
                    acceptorRules = clonedProfile.config.acceptorResponseRules
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
                            text =
                                if (profileSessions.size > 1) {
                                    "${connectionState.getDisplayText()} ($loggedOnSessionCount/${profileSessions.size})"
                                } else {
                                    connectionState.getDisplayText()
                                },
                            color = connectionState.getColor(),
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }
            }

            // Basic connection fields
            SectionLabel("Connection Settings")

            // Connection Type selection
            Column {
                Text(
                    text = "Connection Type",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )

                SlimDropdown(
                    value = connectionType,
                    options = FixConnectionConfig.ConnectionType.entries.toList(),
                    onValueChange = { it?.let { type -> connectionType = type } },
                    displayText = {
                        when (it) {
                            FixConnectionConfig.ConnectionType.INITIATOR -> "Initiator (Client)"
                            FixConnectionConfig.ConnectionType.ACCEPTOR -> "Acceptor (Server)"
                        }
                    },
                    placeholder = "Select Connection Type",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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

            // SessionQualifier (optional field to differentiate sessions with same SenderCompID/TargetCompID)
            // and session count (initiators only - opens N concurrent sessions per Connect)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionField(
                    label = "SessionQualifier (optional)",
                    value = sessionQualifier,
                    onValueChange = { sessionQualifier = it },
                    placeholder = "e.g., DEV, LOCAL, QA - use when multiple sessions share same SenderCompID/TargetCompID",
                    modifier = Modifier.weight(3f),
                )

                if (connectionType == FixConnectionConfig.ConnectionType.INITIATOR) {
                    ConnectionField(
                        label = "Sessions",
                        value = sessionCount,
                        onValueChange = { sessionCount = it },
                        placeholder = "1",
                        isError = !isSessionCountValid(sessionCount),
                        errorMessage = "1-100",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (connectionType == FixConnectionConfig.ConnectionType.INITIATOR && parsedSessionCount() > 1) {
                val count = parsedSessionCount()
                val errors = identityErrors()
                if (errors.isNotEmpty()) {
                    errors.forEach { error ->
                        Text(text = error, color = AppTheme.Colors.error, fontSize = 8.sp)
                    }
                } else {
                    val first = SessionIdentityResolver.resolve(identityPreviewConfig(), 1, count)
                    val last = SessionIdentityResolver.resolve(identityPreviewConfig(), count, count)
                    val previewText =
                        if (first.senderCompID != last.senderCompID || first.targetCompID != last.targetCompID) {
                            "Connect opens $count sessions: " +
                                "${first.senderCompID} → ${first.targetCompID} … ${last.senderCompID} → ${last.targetCompID}"
                        } else {
                            "Connect opens $count sessions sharing these CompIDs, each with an auto-generated SessionQualifier " +
                                "(${first.sessionQualifier} … ${last.sessionQualifier}). " +
                                "For distinct CompIDs per session use {n}/{nn} numbering (e.g. LOADGEN{nn}) " +
                                "or a comma-separated list in SenderCompID/TargetCompID/Username/Password."
                        }
                    Text(
                        text = previewText,
                        color = AppTheme.Colors.info,
                        fontSize = 8.sp,
                    )
                }
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

            if (connectionType == FixConnectionConfig.ConnectionType.INITIATOR) {
                // Initiator mode: Host and Port
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
            } else {
                // Acceptor mode: Accept Port only
                ConnectionField(
                    label = "Accept Port (listen for incoming connections)",
                    value = socketAcceptPort.ifBlank { port },
                    onValueChange = { socketAcceptPort = it },
                    placeholder = "9876",
                    isError = isAcceptPortError,
                    errorMessage = if (socketAcceptPort.ifBlank { port }.isEmpty()) "Required" else "Invalid port (1-65535)",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // FIX Version selection
            Column {
                Text(
                    text = "FIX Version",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )

                SlimDropdown(
                    value = selectedFixVersion,
                    options = FixVersion.entries.toList(),
                    onValueChange = { it?.let { version -> selectedFixVersion = version } },
                    displayText = { it.displayName },
                    placeholder = "Select FIX Version",
                    modifier = Modifier.fillMaxWidth(),
                )

                // Info text for FIX 5.0+
                if (selectedFixVersion.isFix50Plus) {
                    Text(
                        text = "FIX 5.0+ uses FIXT.1.1 transport with ApplVerID=${selectedFixVersion.applVerID}",
                        color = AppTheme.Colors.info,
                        fontSize = 8.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

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

                // Connection timeout settings
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConnectionField(
                        label = "Connect Timeout (seconds)",
                        value = socketConnectTimeout,
                        onValueChange = { socketConnectTimeout = it },
                        placeholder = "10",
                        modifier = Modifier.weight(1f),
                    )
                    ConnectionField(
                        label = "Reconnect Interval (seconds)",
                        value = reconnectInterval,
                        onValueChange = { reconnectInterval = it },
                        placeholder = "30",
                        enabled = autoReconnect,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Auto-reconnect checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable { autoReconnect = !autoReconnect },
                ) {
                    Box(
                        modifier =
                            checkboxSize16
                                .background(
                                    color = checkboxBackgroundColor(autoReconnect),
                                    shape = checkboxShape,
                                ).border(
                                    width = 1.dp,
                                    color = checkboxBorderColor(autoReconnect),
                                    shape = checkboxShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (autoReconnect) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = AppTheme.Colors.background,
                                modifier = iconSize12,
                            )
                        }
                    }
                    Text(
                        text = "Auto-reconnect on failure",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 9.sp,
                    )
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

            // Auto-response rules (collapsible) — acceptor only, because that is the only mode in
            // which they fire (QuickFixService.maybeAutoRespond returns early otherwise). Showing
            // them to an initiator would offer a setting that does nothing.
            if (connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR) {
                var showAcceptorRules by remember { mutableStateOf(false) }

                // Open itself when the loaded profile has rules — the same auto-expand the demo
                // server section does. A profile whose acceptor answers orders on its own should not
                // be able to look, at a glance, like one that stays silent.
                LaunchedEffect(acceptorRules.isNotEmpty()) {
                    if (acceptorRules.isNotEmpty()) showAcceptorRules = true
                }

                HorizontalDivider(
                    color = AppTheme.Separators.color,
                    thickness = AppTheme.Separators.dividerThickness,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { showAcceptorRules = !showAcceptorRules }
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.QuestionAnswer,
                        contentDescription = "Auto-Responses",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )
                    Text(
                        text = "Auto-Responses",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 10.sp,
                    )
                    if (acceptorRules.isNotEmpty()) {
                        Text(
                            text = "(${acceptorRules.size})",
                            color = AppTheme.Colors.textDisabled,
                            fontSize = 9.sp,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (showAcceptorRules) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showAcceptorRules) "Collapse" else "Expand",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )
                }

                if (showAcceptorRules) {
                    AcceptorRulesEditor(
                        rules = acceptorRules,
                        onRulesChange = { acceptorRules = it },
                    )
                }
            }

            // Demo Server section (collapsible)
            if (onStartDemoServer != null && onStopDemoServer != null) {
                var showDemoServer by remember { mutableStateOf(demoServerRunning) }

                // Auto-expand when server starts running
                LaunchedEffect(demoServerRunning) {
                    if (demoServerRunning) showDemoServer = true
                }

                HorizontalDivider(
                    color = AppTheme.Separators.color,
                    thickness = AppTheme.Separators.dividerThickness,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                // Collapsible header row
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { showDemoServer = !showDemoServer }
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = "Demo Server",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )

                    Text(
                        text = "Demo Server",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 10.sp,
                    )

                    // Status dot
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (demoServerRunning) AppTheme.Colors.primary else AppTheme.Colors.textDisabled,
                                    RoundedCornerShape(4.dp),
                                ),
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Icon(
                        imageVector = if (showDemoServer) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle Demo Server",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )
                }

                // Expanded content
                if (showDemoServer) {
                    if (demoServerRunning) {
                        // Running state: show status and stop button
                        Text(
                            text = "Running: ${demoServerFixVersion?.displayName ?: "Unknown"} on localhost:19876",
                            color = AppTheme.Colors.primary,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )

                        SlimButton(
                            text = "Stop Demo Server",
                            onClick = { onStopDemoServer() },
                            containerColor = AppTheme.Colors.warning,
                            contentColor = AppTheme.Colors.background,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // Stopped state: show version picker and start button
                        var demoFixVersion by remember { mutableStateOf(FixVersion.DEFAULT) }

                        Column {
                            Text(
                                text = "FIX Version",
                                color = AppTheme.Colors.textSecondary,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )

                            SlimDropdown(
                                value = demoFixVersion,
                                options = FixVersion.entries.toList(),
                                onValueChange = { it?.let { version -> demoFixVersion = version } },
                                displayText = { it.displayName },
                                placeholder = "Select FIX Version",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        SlimButton(
                            text = "Start Demo Server",
                            onClick = { onStartDemoServer(demoFixVersion) },
                            containerColor = AppTheme.Colors.primary,
                            contentColor = AppTheme.Colors.background,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
            // Connect button - for multi-session profiles it reconnects any session that is
            // down and tops the group up to the configured session count
            val canConnectAnySession =
                sessionStates.isEmpty() ||
                    sessionStates.any { it.canConnect() } ||
                    (connectionType == FixConnectionConfig.ConnectionType.INITIATOR && sessionStates.size < parsedSessionCount())
            SlimButton(
                text =
                    when {
                        sessionStates.any { it == FixConnectionState.CONNECTING } -> "Connecting..."
                        connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR -> "Start Listening"
                        else -> "Connect"
                    },
                onClick = {
                    connectionError = null // Clear any previous error
                    // Copied from the profile being connected, not constructed — see the Save
                    // handler. This config is the one handed to QuickFixService, so a field
                    // dropped here is a field the live session never had: the acceptor response
                    // rules were absent from every GUI-initiated connection, which is why the
                    // auto-response feature only ever worked when driven through /connect.
                    val config =
                        (selectedProfile?.config ?: FixConnectionConfig()).copy(
                            username = username,
                            senderCompID = senderCompID,
                            targetCompID = targetCompID,
                            sessionQualifier = sessionQualifier,
                            sessionCount = parsedSessionCount(),
                            password = password,
                            host = host,
                            port = port,
                            socketConnectHost = host, // Use the same host for connection
                            connectionType = connectionType,
                            socketAcceptPort = socketAcceptPort,
                            beginString = selectedFixVersion.beginString,
                            applVerID = selectedFixVersion.applVerID,
                            heartBtInt = heartBtInt,
                            socketConnectTimeout = socketConnectTimeout,
                            reconnectInterval = reconnectInterval,
                            autoReconnect = autoReconnect,
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
                            acceptorResponseRules = acceptorRules,
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
                enabled = canConnectAnySession && isFormValid(),
                containerColor = AppTheme.Colors.primary,
                contentColor = AppTheme.Colors.background,
                modifier = Modifier.weight(1f),
            )

            // Disconnect button
            SlimButton(
                text = if (connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR) "Stop Listening" else "Disconnect",
                onClick = {
                    selectedProfile?.let { onDisconnect(it.id) }
                },
                enabled = sessionStates.any { it.canDisconnect() },
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
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.then(if (enabled) Modifier else Modifier.alpha(0.4f))) {
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
            enabled = enabled,
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
    enabled: Boolean = true,
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
        enabled = enabled,
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
