package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixConnectionState.*
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import com.knapsack.fixtool.service.FixMessageHelper.toRawFixMessage
import com.knapsack.fixtool.util.NotifyingLogger
import quickfix.Application
import quickfix.Message
import quickfix.Session
import quickfix.SessionID
import java.time.LocalDateTime

/**
 * Result of sending a FIX message
 */
sealed class SendResult {
    /** Message sent successfully with validation */
    object Success : SendResult()

    /** Message sent but validation was bypassed (manual construction used) */
    data class SuccessWithWarning(
        val warning: String,
    ) : SendResult()

    /** Message failed to send */
    data class Failed(
        val error: String,
    ) : SendResult()
}

class QuickFixService(
    private val config: FixConnectionConfig,
    private val onMessageReceived: (FixMessage) -> Unit,
    private val onStateChanged: (FixConnectionState) -> Unit,
    private val onError: ((String) -> Unit)? = null,
) : Application {
    private val logger = NotifyingLogger(QuickFixService::class.java, onError)
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
                            logger.error("Invalid tag number: $tag")
                        } catch (e: Exception) {
                            logger.error("Error adding logon field tag=$tag: ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error in toAdmin: ${e.message}", e)
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
            logger.error("Error displaying outgoing admin message: ${e.message}", e)
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
            logger.error("Error processing admin message: ${e.message}", e)
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
            logger.error("Error displaying outgoing app message: ${e.message}", e)
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
            logger.error("Error processing application message: ${e.message}", e)
        }
    }

    /**
     * Sends a FIX message through the QuickFIX session
     *
     * Uses a two-tier approach:
     * 1. First tries QuickFIX's default parser with validation enabled
     * 2. If validation fails, falls back to manual construction
     *
     * @return SendResult indicating success, success with warning, or failure
     */
    fun sendMessage(rawMessage: String, dictionary: com.knapsack.fixtool.model.FixDictionary): SendResult {
        val startTime = System.nanoTime()
        val sessionID = currentSessionID
        if (sessionID == null) {
            logger.error("Cannot send message: No active FIX session", notifyUser = true)
            return SendResult.Failed("No active FIX session")
        }

        try {
            val dataDictionary = dictionary.getDataDictionary()
            var validationWarning: String? = null

            val message =
                if (dataDictionary != null) {
                    // Two-tier approach: try validated construction first
                    try {
                        logger.debug("Attempting message construction with validation enabled")
                        rawMessage.toQuickFixMessage(dataDictionary, validate = true)
                    } catch (validationException: Exception) {
                        // Validation failed - fall back to manual construction
                        logger.warn("Message validation failed, using manual construction: ${validationException.message}")

                        // Store warning to return later
                        validationWarning = validationException.message ?: "QuickFIX validation failed"

                        // Use manual construction as fallback (must be last expression to return Message)
                        rawMessage.toQuickFixMessageManual(dataDictionary)
                    }
                } else {
                    logger.info("Sending message without data dictionary validation")
                    rawMessage.toQuickFixMessage()
                }

            val parseEndTime = System.nanoTime()

            var duration = (parseEndTime - startTime) / 1_000_000 // Convert to milliseconds

            if (duration > 200) {
                logger.warn("Message parse took ${duration}ms (exceeded 200ms threshold)")
            }

            val sent = Session.sendToTarget(message, sessionID)
            duration = (System.nanoTime() - parseEndTime) / 1_000_000 // Convert to milliseconds

            if (duration > 200) {
                logger.warn("Message send took ${duration}ms (exceeded 200ms threshold)")
            }

            return if (sent) {
                if (validationWarning != null) {
                    SendResult.SuccessWithWarning(validationWarning)
                } else {
                    SendResult.Success
                }
            } else {
                SendResult.Failed("Failed to send to target")
            }
        } catch (e: Exception) {
            logger.error("Error sending message: ${e.message}", e, notifyUser = true)
            return SendResult.Failed(e.message ?: "Unknown error")
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
            logger.error("Error sending logout: ${e.message}", e)
        }
    }
}
