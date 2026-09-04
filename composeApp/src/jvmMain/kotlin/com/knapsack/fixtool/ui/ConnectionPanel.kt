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
import com.knapsack.fixtool.model.AcceptorLatencyConfig
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.EditorTarget
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.ReplyStepApply
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
    /**
     * A profile whose auto-response rules should be unfolded, from the venue's own Rules button.
     *
     * Needed only for the case the auto-expand below cannot see: a venue with no rules yet. That is the
     * likeliest reason to have pressed the button, and the least helpful thing to answer with a folded
     * section. Cleared through [onRulesExpandConsumed] once acted on.
     */
    rulesExpandRequest: String? = null,
    onRulesExpandConsumed: (() -> Unit)? = null,
    /** Names the enum values an auto-response condition can be built from. */
    dictionary: FixDictionary? = null,
    /** Loads one reply step into the message editor. Null where there is no editor to load it into. */
    onOpenReplyStepInEditor: ((profileId: String, ruleIndex: Int, stepIndex: Int, template: String) -> Unit)? = null,
    /** A step the editor has finished with, to be verified and staged here. Consumed by [onReplyStepConsumed]. */
    replyStepApply: ReplyStepApply? = null,
    onReplyStepConsumed: (() -> Unit)? = null,
    /** Which step the editor currently holds, so its row can say so. */
    editingReplyStep: EditorTarget.ReplyStep? = null,
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

    // Expanded by default: an unconfigured profile needs these before it needs anything else.
    var showConnectionSettings by remember { mutableStateOf(true) }
    var customParameters by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var logonFields by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var acceptorRules by remember { mutableStateOf<List<AcceptorResponseRule>>(emptyList()) }
    var acceptorLatency by remember { mutableStateOf(AcceptorLatencyConfig()) }

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

    // **Which rule answered most recently, on any of this profile's sessions.**
    //
    // Collected across the group and reduced to the latest, because a venue's rules are answering four
    // clients from one list and the question the list is asked is "which of you just replied to me",
    // not "which of you replied to DEMO_CLIENT2". In practice only the session holding the engine
    // reports at all — a venue client's pane shares its venue's service — so this is a max over one
    // non-null value, and written as a reduction anyway so a multi-session acceptor cannot show the
    // older of two answers.
    val lastRuleFired =
        profileSessions
            .map { it.lastRuleFired.collectAsState().value }
            .filterNotNull()
            .maxByOrNull { it.at }

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
        acceptorLatency = profile.config.acceptorLatency
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
                            acceptorLatency = acceptorLatency,
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
                        acceptorLatency = AcceptorLatencyConfig()
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
                    acceptorLatency = clonedProfile.config.acceptorLatency
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

            // Collapsible, because the two halves of this panel are used on different schedules: the
            // CompIDs and the port are set once, and then the auto-response rules are iterated on for
            // as long as the venue behaviour is being pinned down. Folding the half that is finished
            // gives the half being worked on the panel's length.
            CollapsibleSectionHeader(
                label = "Connection Settings",
                expanded = showConnectionSettings,
                onToggle = { showConnectionSettings = !showConnectionSettings },
            )

            // Folded, the section still has to answer "who am I and where am I pointed" — otherwise a
            // blank CompID leaves Connect disabled with its reason hidden inside the very thing that
            // was collapsed, and the panel is silently unusable. Missing parts are named as `?` rather
            // than skipped, so the gap is what draws the eye.
            if (!showConnectionSettings) {
                val where =
                    if (connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR) {
                        "listening on ${socketAcceptPort.ifBlank { "?" }}"
                    } else {
                        "${host.ifBlank { "?" }}:${port.ifBlank { "?" }}"
                    }
                Text(
                    text = "${senderCompID.ifBlank { "?" }} → ${targetCompID.ifBlank { "?" }} · $where",
                    color = if (isFormValid()) AppTheme.Colors.textSecondary else AppTheme.Colors.warning,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            if (showConnectionSettings) {
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
                        placeholder =
                            if (connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR) {
                                "TargetCompID, or * for any client"
                            } else {
                                "TargetCompID"
                            },
                        isError = isTargetCompIDError,
                        errorMessage = "Required",
                        modifier = Modifier.weight(1f),
                    )
                }

                // What a venue's wildcard does, and — when it is not set — that it is available. A real
                // exchange is one endpoint many clients reach, and the alternative to saying so here is
                // a tester discovering it by running out of ports.
                if (connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR) {
                    val venue = targetCompID.trim() == FixConnectionConfig.ANY_CLIENT
                    Text(
                        text =
                            if (venue) {
                                "Accepts a logon from any client addressed to ${senderCompID.ifBlank { "?" }}, " +
                                    "each in its own tab. A logon naming a different acceptor is still refused."
                            } else {
                                "Only ${targetCompID.ifBlank { "?" }} can connect. Use * to accept any client."
                            },
                        color = if (venue) AppTheme.Colors.info else AppTheme.Colors.textDisabled,
                        fontSize = 8.sp,
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

                // Asked for by name, so it opens even when there is nothing in it to auto-open for.
                LaunchedEffect(rulesExpandRequest, selectedProfile?.id) {
                    val wanted = rulesExpandRequest ?: return@LaunchedEffect
                    if (selectedProfile?.id == wanted) {
                        showAcceptorRules = true
                        onRulesExpandConsumed?.invoke()
                    }
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
                    // A sequence is a claim about time — "then, four seconds later, the fill" — so the
                    // moment an author sees it is the wrong claim they need it to stop now, not after
                    // it has finished being wrong. Offered only while a session exists to stop it on.
                    val liveSessions = selectedProfile?.let { onGetProfileSessions(it.id) }.orEmpty()
                    if (liveSessions.isNotEmpty()) {
                        TooltipIconButton(
                            tooltip = "Drop replies still queued on this session",
                            onClick = { liveSessions.forEach { it.stopPendingResponses() } },
                            modifier = iconSize18,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop queued responses",
                                tint = AppTheme.Colors.warning,
                                modifier = iconSize14,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (showAcceptorRules) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showAcceptorRules) "Collapse" else "Expand",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )
                }

                if (showAcceptorRules) {
                    // Applied is not saved. The step is written into the staged list, exactly as
                    // typing into its raw field would have been; Save is still what persists it and,
                    // since rules travel to live sessions, still the only thing the venue notices.
                    var applyNote by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(replyStepApply) {
                        val applied = replyStepApply ?: return@LaunchedEffect
                        val profile = selectedProfile
                        applyNote =
                            when {
                                // The panel moved on — a different profile is loaded, so the rule list
                                // in front of the author is not the one this step came from.
                                profile == null || profile.id != applied.profileId ->
                                    "the step was applied to a profile that is no longer loaded"
                                // An index is only an address while the list holds still. Deleting or
                                // reordering a rule with a step of it open moves what lives here.
                                acceptorRules
                                    .getOrNull(applied.ruleIndex)
                                    ?.sequence()
                                    ?.getOrNull(applied.stepIndex)
                                    ?.template != applied.snapshot ->
                                    "rule ${applied.ruleIndex + 1} step ${applied.stepIndex + 1} has changed since " +
                                        "it was opened, so the edit was not applied over it"
                                else -> {
                                    val rule = acceptorRules[applied.ruleIndex]
                                    val steps =
                                        rule.sequence().replaced(
                                            applied.stepIndex,
                                            rule.sequence()[applied.stepIndex].copy(template = applied.template),
                                        )
                                    acceptorRules =
                                        acceptorRules.replaced(
                                            applied.ruleIndex,
                                            rule.copy(steps = steps, responseTemplate = ""),
                                        )
                                    null
                                }
                            }
                        onReplyStepConsumed?.invoke()
                    }

                    AcceptorRulesEditor(
                        rules = acceptorRules,
                        onRulesChange = {
                            applyNote = null
                            acceptorRules = it
                        },
                        dictionary = dictionary,
                        // ---- withheld the moment it could be wrong
                        //
                        // The number is a position in the ruleset the *session* is running, which is
                        // the one last saved. The list on screen is the staged one, and an unsaved
                        // insert or reorder moves what lives at that position — so a mark that kept
                        // showing would point at whichever card had drifted into rule 7's place. That
                        // is worse than no mark: it is a confident wrong answer to the one question
                        // this whole feature exists to answer. Equality of the two lists is the cheap
                        // proof they agree, and the marker comes back the moment Save makes them.
                        firedRule =
                            lastRuleFired
                                ?.takeIf { acceptorRules == selectedProfile?.config?.acceptorResponseRules }
                                ?.let { RuleFiredMark(ruleIndex = it.ruleIndex, at = it.at) },
                        onOpenStepInEditor =
                            onOpenReplyStepInEditor?.let { open ->
                                { ruleIndex, stepIndex ->
                                    selectedProfile?.let { profile ->
                                        acceptorRules
                                            .getOrNull(ruleIndex)
                                            ?.sequence()
                                            ?.getOrNull(stepIndex)
                                            ?.let { step -> open(profile.id, ruleIndex, stepIndex, step.template) }
                                    }
                                }
                            },
                        editingStep =
                            editingReplyStep
                                ?.takeIf { it.profileId == selectedProfile?.id }
                                ?.let { it.ruleIndex to it.stepIndex },
                    )

                    applyNote?.let {
                        Text(
                            text = "⚠ $it",
                            color = AppTheme.Colors.warning,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // Latency (collapsible) — its own section beside the rules because it is the other half
                // of the venue's timing: the rules say what comes back and how far apart, this says how
                // long the wire takes. Acceptor-only for the same reason the rules are — the delay is
                // applied in QuickFixService.maybeAutoRespond, which an initiator never reaches.
                var showAcceptorLatency by remember { mutableStateOf(false) }

                // Open itself when the loaded profile has latency configured, the same auto-expand the
                // rules section does — a venue that delays its replies should not look, at a glance,
                // like one that answers instantly.
                LaunchedEffect(acceptorLatency.isActive()) {
                    if (acceptorLatency.isActive()) showAcceptorLatency = true
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
                            .clickable { showAcceptorLatency = !showAcceptorLatency }
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Latency",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )
                    Text(
                        text = "Latency",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 10.sp,
                    )
                    if (acceptorLatency.isActive()) {
                        Text(
                            text = describeLatency(acceptorLatency),
                            color = AppTheme.Colors.textDisabled,
                            fontSize = 9.sp,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (showAcceptorLatency) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showAcceptorLatency) "Collapse" else "Expand",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = iconSize16,
                    )
                }

                if (showAcceptorLatency) {
                    AcceptorLatencyEditor(
                        latency = acceptorLatency,
                        onLatencyChange = { acceptorLatency = it },
                    )
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
                            acceptorLatency = acceptorLatency,
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

/** A [SectionLabel] that folds its section — the chevron sits where Advanced Settings' does. */
@Composable
private fun CollapsibleSectionHeader(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = AppTheme.Colors.primary,
            fontSize = 10.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse $label" else "Expand $label",
            tint = AppTheme.Colors.textSecondary,
            modifier = iconSize16,
        )
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
