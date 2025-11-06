package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixDictionary
import org.slf4j.LoggerFactory
import quickfix.*
import java.io.File
import java.io.FileInputStream

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

        val configContent =
            buildString {
                // Default section
                appendLine("[DEFAULT]")
                appendLine("ConnectionType=${config.connectionType.name.lowercase()}")
                appendLine("FileStorePath=${config.fileStorePath}")
                appendLine("FileLogPath=${config.fileLogPath}")
                appendLine("StartTime=${config.startTime.ifBlank { "00:00:00" }}")
                appendLine("EndTime=${config.endTime.ifBlank { "00:00:00" }}")

                // Add data dictionary if configured (must be in DEFAULT section)
                val dataDictionaryPath = dictionary.getFilePath()
                if (dataDictionaryPath != null) {
                    appendLine("DataDictionary=$dataDictionaryPath")
                    logger.info("Using data dictionary: {}", dataDictionaryPath)
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

                // Determine BeginString from data dictionary or use configured value as fallback
                val beginString = determineBeginString(config.beginString)
                appendLine("BeginString=$beginString")
                appendLine("SenderCompID=${config.senderCompID}")
                appendLine("TargetCompID=${config.targetCompID}")
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
        return SessionSettings(FileInputStream(configFile))
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
     * Stops the connection
     */
    fun stop() {
        try {
            initiator?.stop()
            initiator = null

            acceptor?.stop()
            acceptor = null

            logger.info("QuickFIX connection stopped")
        } catch (e: Exception) {
            logger.error("Error stopping QuickFIX connection: {}", e.message, e)
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
                    if (file.name.contains(config.senderCompID) && file.name.contains(config.targetCompID)) {
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
