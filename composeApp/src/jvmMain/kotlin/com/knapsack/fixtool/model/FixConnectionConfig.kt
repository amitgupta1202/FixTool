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
    /**
     * Who a venue expects and the part each plays, requester or responder — what lets a rule address "the
     * dealers" or refuse a QuoteRequest from one. Empty is a venue that relays nothing, which is every venue
     * written before relaying. See `docs/rfq-relay-proposal.md`, decision 3.
     */
    val counterparties: List<Counterparty> = emptyList(),
    /**
     * How long an RFQ stays open when its request carries no ExpireTime(126). Null: until something ends it.
     * Read by the RFQ book's expiry, and ignored by a venue that relays nothing.
     */
    val rfqExpirySeconds: Int? = null,
    /**
     * Where QuickFIX/J keeps this session's sequence numbers and sent messages. [MessageStoreKind.FILE]
     * is the interactive default: resend works and the numbers survive a restart. [MessageStoreKind.MEMORY]
     * is for a load or soak run, where the per-message file appends cap how fast a lane can issue and a
     * store that grows for the length of the run is not wanted. See [storeProblem].
     */
    val messageStore: MessageStoreKind = MessageStoreKind.FILE,
    /**
     * Whether QuickFIX/J writes its per-session message and event log under [fileLogPath]. The pane and
     * the run records are the tool's own evidence, so [MessageLogKind.NONE] loses nothing they keep.
     */
    val messageLog: MessageLogKind = MessageLogKind.FILE,
) {
    enum class ConnectionType {
        INITIATOR, // Client - initiates connection
        ACCEPTOR, // Server - accepts connections
    }

    /** The QuickFIX/J message store behind a session: files under [fileStorePath], or a map on the heap. */
    enum class MessageStoreKind {
        FILE,
        MEMORY,
    }

    /** The QuickFIX/J message log behind a session: files under [fileLogPath], or nothing at all. */
    enum class MessageLogKind {
        FILE,
        NONE,
    }

    /**
     * **Why this configuration must not be connected**, or null when it may.
     *
     * A memory store starts every logon at sequence number 1, while a venue that was not told to reset
     * expects the number it last saw. That is the classic failure that appears only on the first run
     * after a restart, and the whole point of a memory store is a repeatable run. So a memory store
     * without Reset on Logon is refused, in one sentence owned here, rather than fixed behind the user's
     * back by flipping a flag on a profile they did not ask to change. The connection manager, the
     * connection panel, the load dialog and the command line all read the same words from this one place.
     */
    fun storeProblem(): String? =
        if (messageStore == MessageStoreKind.MEMORY && !resetOnLogon) {
            "Memory store, and Reset on Logon is off: the next logon will start at 1 while the venue expects " +
                "the number it last saw. Turn Reset on Logon on, or keep the file store."
        } else {
            null
        }

    /** An acceptor binds a port and waits; an initiator dials one. Which way round decides who goes first. */
    fun isAcceptor(): Boolean = connectionType == ConnectionType.ACCEPTOR

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
    fun acceptsAnyClient(): Boolean = isAcceptor() && targetCompID.trim() == ANY_CLIENT

    /**
     * **The port the engine actually dials**, which is not always [port].
     *
     * `SocketConnectPort=${socketConnectPort.ifBlank { port }}` is the line QuickFIX/J is handed, and a
     * profile that carries both — an imported one, or one whose panel-edited [port] moved while the
     * advanced field stayed — dials the advanced one. Anything reasoning about where these lanes go has
     * to read it the same way the connection does, or it is reasoning about a different endpoint.
     */
    fun connectPort(): String = socketConnectPort.ifBlank { port }

    /** The port the engine binds, by the same rule and for the same reason. */
    fun acceptPort(): String = socketAcceptPort.ifBlank { port }

    /**
     * **Is this acceptor the session [lanes] address?** — the counterparty they name, and one it would
     * let in.
     *
     * A port number is not an identity. Two saved acceptors can carry the same port and be different
     * venues in different environments, which is the ordinary shape of a desk's workspace: one
     * counterparty copied per environment, differing in a host and a port. So what makes an acceptor the
     * far end of these lanes is the CompIDs on the session they would share — this acceptor *is* who they
     * address, and it would accept who they say they are.
     */
    fun answersFor(lanes: FixConnectionConfig): Boolean =
        isAcceptor() &&
            senderCompID.isNotBlank() &&
            senderCompID == lanes.targetCompID &&
            (acceptsAnyClient() || targetCompID == lanes.senderCompID)

    companion object {
        /** The TargetCompID that means "any client" — QuickFIX/J's own wildcard, so the two agree. */
        const val ANY_CLIENT = "*"
    }
}
