package com.knapsack.fixtool.model

import com.knapsack.fixtool.service.WorkspacePaths
import kotlinx.serialization.Serializable

@Serializable
data class FixConnectionConfig(
    // Basic connection settings
    val username: String = "",
    val senderCompID: String = "",
    val targetCompID: String = "",
    val sessionQualifier: String = "", // Optional - differentiates sessions with same SenderCompID/TargetCompID
    val sessionCount: Int = 1, // Initiators only - sessions opened per Connect; >1 derives a unique SessionQualifier per session
    val password: String = "",
    val host: String = "localhost",
    val port: String = "",
    // FIX protocol settings
    val beginString: String = "FIX.4.4", // FIX version (or FIXT.1.1 for FIX 5.0+)
    val applVerID: String? = null, // ApplVerID for FIX 5.0+ sessions ("7" = FIX 5.0, "8" = SP1, "9" = SP2)
    val heartBtInt: String = "30", // Heartbeat interval in seconds
    val socketConnectTimeout: String = "10", // TCP connection timeout in seconds
    val reconnectInterval: String = "30", // Seconds between reconnection attempts
    val autoReconnect: Boolean = true, // Automatically retry connection on failure
    val resetOnLogon: Boolean = false,
    val resetOnLogout: Boolean = false,
    val resetOnDisconnect: Boolean = false,
    // Display settings
    val showHeartbeat: Boolean = true, // Show heartbeat messages in session panel
    // Storage settings
    val fileStorePath: String = WorkspacePaths.current.sessionStore.absolutePath,
    val fileLogPath: String = WorkspacePaths.current.sessionLog.absolutePath,
    // Advanced settings
    val socketConnectHost: String = "localhost", // Usually localhost for port-forwarded connections
    val socketConnectPort: String = "", // Maps to port field
    val socketAcceptPort: String = "", // For acceptor mode
    val startTime: String = "", // Session start time (HH:MM:SS)
    val endTime: String = "", // Session end time (HH:MM:SS)
    // Connection type
    val connectionType: ConnectionType = ConnectionType.INITIATOR,
    // SSL/TLS settings
    val useSSL: Boolean = false,
    val keyStorePath: String = "",
    val keyStorePassword: String = "",
    val trustStorePath: String = "",
    val trustStorePassword: String = "",
    val keyStoreType: String = "JKS", // JKS, PKCS12, etc.
    val enabledProtocols: String = "TLSv1.2,TLSv1.3", // Comma-separated list of protocols
    val cipherSuites: String = "", // Comma-separated list (empty = use defaults)
    val needClientAuth: Boolean = false, // For acceptors - require client certificate
    // Custom parameters (free-form key-value pairs)
    val customParameters: Map<String, String> = emptyMap(),
    // Logon message custom fields (tag-value pairs to add to logon message)
    val logonFields: Map<String, String> = emptyMap(),
    // Acceptor mode only: auto-response rules applied to incoming application messages (first match wins)
    val acceptorResponseRules: List<AcceptorResponseRule> = emptyList(),
    // Acceptor mode only: how long the venue waits before an auto-response goes out (default: no delay)
    val acceptorLatency: AcceptorLatencyConfig = AcceptorLatencyConfig(),
) {
    enum class ConnectionType {
        INITIATOR, // Client - initiates connection
        ACCEPTOR, // Server - accepts connections
    }

    /**
     * **Does this acceptor accept a logon from any counterparty**, creating a session per client?
     *
     * A real venue is one endpoint many clients reach, and this is how a profile says so: a literal
     * `*` for TargetCompID. Opt-in on purpose. An acceptor naming one counterparty keeps refusing
     * every other CompID, which is not a limitation to be fixed but a test result — a client
     * addressing the wrong venue *should* fail, and a simulator that silently accepted it would
     * report a green run for a misconfiguration that would have failed in production.
     *
     * [senderCompID] is never wildcarded: it is who this acceptor *is*, and the one identity a
     * counterparty must get right. See [com.knapsack.fixtool.service.VenueSessionProvider].
     */
    fun acceptsAnyClient(): Boolean = connectionType == ConnectionType.ACCEPTOR && targetCompID.trim() == ANY_CLIENT

    companion object {
        /** The TargetCompID that means "any client" — QuickFIX/J's own wildcard, so the two agree. */
        const val ANY_CLIENT = "*"
    }
}
