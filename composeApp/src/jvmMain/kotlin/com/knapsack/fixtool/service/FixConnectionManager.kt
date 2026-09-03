package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixDictionary
import org.slf4j.LoggerFactory
import quickfix.*
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress

/**
 * Manages the lifecycle of a QuickFIX/J connection
 */
class FixConnectionManager(
    private val config: FixConnectionConfig,
    private val quickFixService: QuickFixService,
    private val appSettings: AppSettings,
    private val dictionary: FixDictionary,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(FixConnectionManager::class.java)
    }

    private var initiator: SocketInitiator? = null
    private var acceptor: SocketAcceptor? = null
    private val messageStoreFactory: MessageStoreFactory
    private val logFactory: LogFactory
    private val messageFactory: MessageFactory

    init {
        // Ensure directories exist
        File(config.fileStorePath).mkdirs()
        File(config.fileLogPath).mkdirs()

        // Create factories - these will be initialized with settings during start()
        val settings = createSessionSettings()
        messageStoreFactory = FileStoreFactory(settings)
        // Plain FileLogFactory. This used to be wrapped in a RawMessageCapturingLogFactory that stashed
        // every incoming string in a process-wide map so the wire bytes could be recovered later; QFJ
        // already retains them (Message.toRawString()), so the wrapper was a second, leakier answer to a
        // question QuickFIX had already answered. See QuickFixService.wireBytesOf.
        logFactory = FileLogFactory(settings)
        messageFactory = DefaultMessageFactory()
    }

    /**
     * Determines the BeginString (FIX version) from the data dictionary
     * Falls back to the provided default if dictionary is not available
     */
    private fun determineBeginString(defaultBeginString: String): String {
        if (!dictionary.isLoaded()) {
            logger.info("No data dictionary available, using default BeginString: {}", defaultBeginString)
            return defaultBeginString
        }

        return try {
            val dataDictionary = dictionary.getDataDictionary()
            val version = dataDictionary?.version ?: defaultBeginString
            logger.info("Extracted BeginString from data dictionary: {}", version)
            version
        } catch (e: Exception) {
            logger.warn("Failed to extract version from data dictionary, using default: {}", defaultBeginString, e)
            defaultBeginString
        }
    }

    /**
     * Creates QuickFIX SessionSettings from our configuration
     */
    private fun createSessionSettings(): SessionSettings {
        // Create a temporary config file
        val configFile = File.createTempFile("quickfix", ".cfg")
        configFile.deleteOnExit()

        // Detect FIX version to determine configuration approach
        val fixVersion = dictionary.fixVersion
        val isFix50Plus = fixVersion.isFix50Plus

        val configContent =
            buildString {
                // Default section
                appendLine("[DEFAULT]")
                appendLine("ConnectionType=${config.connectionType.name.lowercase()}")
                appendLine("FileStorePath=${config.fileStorePath}")
                appendLine("FileLogPath=${config.fileLogPath}")
                appendLine("StartTime=${config.startTime.ifBlank { "00:00:00" }}")
                appendLine("EndTime=${config.endTime.ifBlank { "00:00:00" }}")
                appendLine("SocketConnectTimeout=${config.socketConnectTimeout.ifBlank { "10" }}")
                appendLine("ReconnectInterval=${config.reconnectInterval.ifBlank { "30" }}")

                // Add data dictionary configuration based on FIX version or transport dictionary availability
                val dataDictionaryPath = dictionary.getFilePath()
                val transportPath = dictionary.getTransportFilePath()

                if (dataDictionaryPath != null) {
                    // Use separate dictionaries if:
                    // 1. FIX 5.0+ version detected, OR
                    // 2. A transport dictionary is explicitly configured (for custom setups with incorrect version headers)
                    if (isFix50Plus || transportPath != null) {
                        // FIX 5.0+ style: separate transport and application dictionaries
                        if (transportPath != null) {
                            appendLine("TransportDataDictionary=$transportPath")
                            logger.info("Using transport data dictionary (FIXT.1.1): {}", transportPath)
                        }
                        appendLine("AppDataDictionary=$dataDictionaryPath")
                        logger.info("Using application data dictionary: {}", dataDictionaryPath)

                        // Add DefaultApplVerID for FIX 5.0+ sessions
                        // Priority: config.applVerID > fixVersion.applVerID > default "9" (FIX 5.0 SP2) when transport dict is used
                        val applVerID =
                            config.applVerID
                                ?: fixVersion.applVerID
                                ?: if (transportPath != null) "9" else null // Default to FIX 5.0 SP2 when transport dict configured
                        if (applVerID != null) {
                            appendLine("DefaultApplVerID=$applVerID")
                            logger.info("Using DefaultApplVerID: {}", applVerID)
                        }
                    } else {
                        // FIX 4.x uses a single DataDictionary
                        appendLine("DataDictionary=$dataDictionaryPath")
                        logger.info("Using data dictionary: {}", dataDictionaryPath)
                    }
                }

                // Apply validation settings from AppSettings
                appendLine("ValidateFieldsOutOfOrder=${if (appSettings.validateFieldsOutOfOrder) "Y" else "N"}")
                appendLine("ValidateFieldsHaveValues=${if (appSettings.validateFieldsHaveValues) "Y" else "N"}")
                appendLine("ValidateUserDefinedFields=${if (appSettings.validateUserDefinedFields) "Y" else "N"}")
                appendLine("ValidateIncomingMessage=${if (appSettings.validateIncomingMessage) "Y" else "N"}")

                // SSL/TLS configuration
                if (config.useSSL) {
                    appendLine("SocketUseSSL=Y")

                    if (config.keyStorePath.isNotBlank()) {
                        appendLine("SocketKeyStore=${config.keyStorePath}")
                    }
                    if (config.keyStorePassword.isNotBlank()) {
                        appendLine("SocketKeyStorePassword=${config.keyStorePassword}")
                    }
                    if (config.trustStorePath.isNotBlank()) {
                        appendLine("SocketTrustStore=${config.trustStorePath}")
                    }
                    if (config.trustStorePassword.isNotBlank()) {
                        appendLine("SocketTrustStorePassword=${config.trustStorePassword}")
                    }
                    if (config.keyStoreType.isNotBlank() && config.keyStoreType != "JKS") {
                        appendLine("KeyStoreType=${config.keyStoreType}")
                    }
                    if (config.enabledProtocols.isNotBlank()) {
                        appendLine("EnabledProtocols=${config.enabledProtocols}")
                    }
                    if (config.cipherSuites.isNotBlank()) {
                        appendLine("CipherSuites=${config.cipherSuites}")
                    }
                    if (config.needClientAuth && config.connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR) {
                        appendLine("NeedClientAuth=Y")
                    }

                    logger.info("SSL/TLS enabled for connection")
                }

                appendLine()

                // Session section
                appendLine("[SESSION]")

                // Determine BeginString:
                // - If transport dictionary is configured, use FIXT.1.1 (required for FIX 5.0+)
                // - Otherwise, use data dictionary version or configured value as fallback
                val beginString =
                    if (transportPath != null) {
                        logger.info("Transport dictionary configured, using BeginString: FIXT.1.1")
                        "FIXT.1.1"
                    } else {
                        determineBeginString(config.beginString)
                    }
                appendLine("BeginString=$beginString")
                appendLine("SenderCompID=${config.senderCompID}")
                appendLine("TargetCompID=${config.targetCompID}")

                // A venue's [SESSION] is a *template*, not a session: QuickFIX/J registers the socket
                // and binds the port without creating a session for it, and every counterparty's
                // session is then created from this block as they log on. Without it the wildcard
                // TargetCompID would be taken literally, and only a client calling itself "*" could
                // connect. See VenueSessionProvider, which supplies the sessions themselves — this
                // setting alone accepts nobody.
                if (config.acceptsAnyClient()) {
                    appendLine("AcceptorTemplate=Y")
                    logger.info("Acceptor accepts any client addressed to {}", config.senderCompID)
                }

                // Add SessionQualifier if specified (to differentiate sessions with same SenderCompID/TargetCompID)
                if (config.sessionQualifier.isNotBlank()) {
                    appendLine("SessionQualifier=${config.sessionQualifier}")
                    logger.info("Using SessionQualifier: {}", config.sessionQualifier)
                }

                appendLine("HeartBtInt=${config.heartBtInt}")

                if (config.connectionType == FixConnectionConfig.ConnectionType.INITIATOR) {
                    appendLine("SocketConnectHost=${config.socketConnectHost}")
                    appendLine("SocketConnectPort=${config.socketConnectPort.ifBlank { config.port }}")
                } else {
                    appendLine("SocketAcceptPort=${config.socketAcceptPort.ifBlank { config.port }}")
                }

                appendLine("ResetOnLogon=${if (config.resetOnLogon) "Y" else "N"}")
                appendLine("ResetOnLogout=${if (config.resetOnLogout) "Y" else "N"}")
                appendLine("ResetOnDisconnect=${if (config.resetOnDisconnect) "Y" else "N"}")

                // Custom parameters
                config.customParameters.forEach { (key, value) ->
                    appendLine("$key=$value")
                }
            }

        configFile.writeText(configContent)
        return FileInputStream(configFile).use { SessionSettings(it) }
    }

    /**
     * Starts the connection (initiator or acceptor)
     */
    fun start() {
        try {
            // If resetOnLogon is true, clear the store files to avoid sequence number issues
            if (config.resetOnLogon) {
                clearStoreFiles()
            }

            val settings = createSessionSettings()

            when (config.connectionType) {
                FixConnectionConfig.ConnectionType.INITIATOR -> {
                    initiator =
                        SocketInitiator(
                            quickFixService,
                            messageStoreFactory,
                            settings,
                            logFactory,
                            messageFactory,
                        )
                    // Before start(): the connector reads its chain builder once, when it is built.
                    initiator?.setIoFilterChainBuilder(SocketStampFilter(quickFixService::deliverStamp).chainBuilder())
                    initiator?.start()
                    logger.info("QuickFIX Initiator started")
                }

                FixConnectionConfig.ConnectionType.ACCEPTOR -> {
                    acceptor =
                        SocketAcceptor(
                            quickFixService,
                            messageStoreFactory,
                            settings,
                            logFactory,
                            messageFactory,
                        )
                    // Before start(), for the same reason as the initiator's.
                    acceptor?.setIoFilterChainBuilder(SocketStampFilter(quickFixService::deliverStamp).chainBuilder())
                    if (config.acceptsAnyClient()) attachVenueSessionProvider(acceptor!!, settings)
                    acceptor?.start()
                    logger.info("QuickFIX Acceptor started")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to start QuickFIX connection: {}", e.message, e)
            throw e
        }
    }

    /**
     * Gives the acceptor a provider that creates a session per client, and points it at the port the
     * settings bind.
     *
     * **Must precede `start()`.** The provider is read once, when the port is bound, and an acceptor
     * without one falls back to a map of statically configured sessions — which for a venue is empty,
     * so every logon would be refused by an acceptor that is nevertheless listening.
     *
     * The address has to be the *same* address QuickFIX/J derives from the settings, since that is
     * the key it looks this up under. No `SocketAcceptAddress` is written, so it is the port alone.
     */
    private fun attachVenueSessionProvider(acceptor: SocketAcceptor, settings: SessionSettings) {
        val templateId =
            settings.sectionIterator().asSequence().firstOrNull() ?: run {
                logger.error("No session template in the acceptor settings — no client will be able to log on")
                return
            }
        val port =
            config.socketAcceptPort.ifBlank { config.port }.toIntOrNull() ?: run {
                logger.error("Acceptor port '{}' is not a number — cannot accept clients", config.socketAcceptPort)
                return
            }
        acceptor.setSessionProvider(
            InetSocketAddress(port),
            VenueSessionProvider(
                settings = settings,
                template = templateId,
                application = quickFixService,
                messageStoreFactory = messageStoreFactory,
                logFactory = logFactory,
                messageFactory = messageFactory,
                onRefused = quickFixService::noteRefusedLogon,
            ),
        )
    }

    /**
     * Stops the connection
     * @throws Exception if stopping the connection fails
     */
    fun stop() {
        try {
            initiator?.stop()
            initiator = null

            acceptor?.stop()
            acceptor = null

            // After the transport, not before: a rule's reply scheduled a moment ago is still worth
            // sending while the session is up, and dropping it first would lose it silently.
            quickFixService.shutdown()

            logger.info("QuickFIX connection stopped")
        } catch (e: Exception) {
            logger.error("Error stopping QuickFIX connection: {}", e.message, e)
            throw e
        }
    }

    /**
     * Clears the QuickFIX store files to reset sequence numbers
     */
    private fun clearStoreFiles() {
        try {
            val storeDir = File(config.fileStorePath)
            if (storeDir.exists() && storeDir.isDirectory) {
                storeDir.listFiles()?.forEach { file ->
                    // A venue's counterparty is not known until a client logs on, so its store files
                    // are named for clients this config has never heard of. Matching on the target
                    // too would therefore match nothing at all, and ResetOnLogon would quietly stop
                    // resetting — sequence numbers surviving a reset that reported success. Our own
                    // CompID is the whole identity we have, and it is enough: these are our files.
                    val matchesSenderTarget =
                        file.name.contains(config.senderCompID) &&
                            (config.acceptsAnyClient() || file.name.contains(config.targetCompID))
                    val matchesQualifier = config.sessionQualifier.isBlank() || file.name.contains(config.sessionQualifier)

                    if (matchesSenderTarget && matchesQualifier) {
                        val deleted = file.delete()
                        if (deleted) {
                            logger.info("Deleted store file: {}", file.name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Error clearing store files: {}", e.message)
        }
    }
}
