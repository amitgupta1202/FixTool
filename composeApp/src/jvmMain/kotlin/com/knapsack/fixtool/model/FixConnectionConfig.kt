package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

@Serializable
data class FixConnectionConfig(
    // Basic connection settings
    val username: String = "",
    val senderCompID: String = "",
    val targetCompID: String = "",
    val sessionQualifier: String = "", // Optional - differentiates sessions with same SenderCompID/TargetCompID
    val password: String = "",
    val host: String = "localhost",
    val port: String = "",
    // FIX protocol settings
    val beginString: String = "FIX.4.4", // FIX version (or FIXT.1.1 for FIX 5.0+)
    val applVerID: String? = null, // ApplVerID for FIX 5.0+ sessions ("7" = FIX 5.0, "8" = SP1, "9" = SP2)
    val heartBtInt: String = "30", // Heartbeat interval in seconds
    val socketConnectTimeout: String = "10", // TCP connection timeout in seconds
    val reconnectInterval: String = "30", // Seconds between reconnection attempts
    val resetOnLogon: Boolean = false,
    val resetOnLogout: Boolean = false,
    val resetOnDisconnect: Boolean = false,
    // Display settings
    val showHeartbeat: Boolean = true, // Show heartbeat messages in session panel
    // Storage settings
    val fileStorePath: String = "${System.getProperty("user.home")}/.fixtool/store",
    val fileLogPath: String = "${System.getProperty("user.home")}/.fixtool/log",
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
) {
    enum class ConnectionType {
        INITIATOR, // Client - initiates connection
        ACCEPTOR, // Server - accepts connections
    }
}
