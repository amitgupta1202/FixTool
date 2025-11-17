package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixConnectionState.*
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import com.knapsack.fixtool.service.FixMessageHelper.toRawFixMessage
import org.slf4j.LoggerFactory
import quickfix.Application
import quickfix.Message
import quickfix.Session
import quickfix.SessionID
import java.time.LocalDateTime

class QuickFixService(
    private val config: FixConnectionConfig,
    private val onMessageReceived: (FixMessage) -> Unit,
    private val onStateChanged: (FixConnectionState) -> Unit,
) : Application {
    private var currentSessionID: SessionID? = null

    override fun onCreate(sessionId: SessionID) {
        logger.info("QuickFIX Session created: {}", sessionId)
        currentSessionID = sessionId
        onStateChanged(CONNECTING)
    }

    override fun onLogon(sessionId: SessionID) {
        logger.info("QuickFIX Session logged on: {}", sessionId)
        currentSessionID = sessionId
        onStateChanged(LOGGED_ON)
    }

    override fun onLogout(sessionId: SessionID) {
        logger.info("QuickFIX Session logged out: {}", sessionId)
        currentSessionID = null
        onStateChanged(DISCONNECTED)
    }

    /**
     * Called for administrative messages (to admin) before they are sent
     */
    override fun toAdmin(message: Message, sessionId: SessionID) {
        // For Logon messages, add password if required
        try {
            val msgType = message.header.getString(35) // MsgType field
            if (msgType == "A") { // Logon message
                // Password handling if needed
                if (config.username.isNotBlank()) message.setString(553, config.username) // Tag 553 = username
                if (config.password.isNotBlank()) message.setString(554, config.password) // Tag 554 = Password

                // Add custom logon fields
                config.logonFields.forEach { (tag, value) ->
                    if (tag.isNotBlank() && value.isNotBlank()) {
                        try {
                            message.setString(tag.toInt(), value)
                            logger.info("Added custom logon field: tag={}, value={}", tag, value)
                        } catch (_: NumberFormatException) {
                            logger.error("Invalid tag number: {}", tag)
                        } catch (e: Exception) {
                            logger.error("Error adding logon field tag={}: {}", tag, e.message)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error in toAdmin: {}", e.message, e)
        }

        val rawMessage = message.toRawFixMessage()
        logger.info("Sending: {}", rawMessage)

        try {
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = rawMessage,
                    quickfixMessage = message,
                )
            onMessageReceived(fixMessage)
        } catch (e: Exception) {
            logger.error("Error displaying outgoing admin message: {}", e.message, e)
        }
    }

    /**
     * Called for administrative messages (from admin) received
     */
    override fun fromAdmin(message: Message, sessionId: SessionID) {
        // Route admin messages to the UI if needed
        try {
            val rawMessage = message.toRawFixMessage()
            logger.info("Received: {}", rawMessage)
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = rawMessage,
                    messageType = message.header.getString(35),
                    quickfixMessage = message,
                )
            onMessageReceived(fixMessage)
        } catch (e: Exception) {
            logger.error("Error processing admin message: {}", e.message, e)
        }
    }

    /**
     * Called for application messages before they are sent
     */
    override fun toApp(message: Message, sessionId: SessionID) {
        val rawMessage = message.toRawFixMessage()
        logger.info("QuickFIX toApp: {}", rawMessage)

        // Capture outgoing application message for UI display
        try {
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = rawMessage,
                    quickfixMessage = message,
                )
            onMessageReceived(fixMessage)
        } catch (e: Exception) {
            logger.error("Error displaying outgoing app message: {}", e.message, e)
        }
    }

    /**
     * Called for application messages received
     */
    override fun fromApp(message: Message, sessionId: SessionID) {
        val rawMessage = message.toString().toRawFixMessage()
        logger.info("QuickFIX fromApp: {}", rawMessage)

        try {
            // Convert QuickFIX message to our FIX message model
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = rawMessage,
                    quickfixMessage = message,
                )

            // Route to the message handler
            onMessageReceived(fixMessage)
        } catch (e: Exception) {
            logger.error("Error processing application message: {}", e.message, e)
        }
    }

    /**
     * Sends a FIX message through the QuickFIX session
     */
    fun sendMessage(rawMessage: String, dictionary: com.knapsack.fixtool.model.FixDictionary): Boolean {
        val sessionID = currentSessionID
        if (sessionID == null) {
            logger.error("QuickFIX Session does not exist: sessionID={}", rawMessage)
            return false
        }

        try {
            val dataDictionary = dictionary.getDataDictionary()
            val message =
                if (dataDictionary != null) {
                    rawMessage.toQuickFixMessageManual(dataDictionary)
                } else {
                    logger.info("Sending message without data dictionary validation")
                    rawMessage.toQuickFixMessage()
                }

            return Session.sendToTarget(message, sessionID)
        } catch (e: Exception) {
            logger.error("Error sending message: {}", e.message, e)
            return false
        }
    }

    /**
     * Sends a FIX Logout message to gracefully disconnect from the server
     * Safe to call even if session is not logged on
     */
    fun logout() {
        val sessionID = currentSessionID
        if (sessionID == null) {
            logger.warn("Cannot logout: No active session")
            return
        }

        try {
            logger.info("Sending FIX Logout message for session: {}", sessionID)
            val session = Session.lookupSession(sessionID)
            session?.logout()
        } catch (e: Exception) {
            logger.error("Error sending logout: {}", e.message, e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(QuickFixService::class.java)
    }
}
