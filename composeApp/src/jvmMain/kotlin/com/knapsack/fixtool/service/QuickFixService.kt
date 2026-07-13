package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixConnectionState.*
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
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
    /** Message sent, and it satisfies the loaded dictionary. */
    object Success : SendResult()

    /**
     * Message sent, but something about it is worth saying: it does not satisfy the loaded
     * dictionary, or it carries tags that dictionary does not define for its type. The send still
     * happened — this is advisory, and [warning] says what is wrong with the message itself.
     */
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
    private val dictionary: FixDictionary,
    private val onMessageReceived: (FixMessage) -> Unit,
    private val onStateChanged: (FixConnectionState) -> Unit,
    private val onError: ((String) -> Unit)? = null,
    private val onConnectionFailed: (() -> Unit)? = null,
    private val onWarning: ((String) -> Unit)? = null,
) : Application {
    private val logger = NotifyingLogger(QuickFixService::class.java, onError, onWarning)
    private var currentSessionID: SessionID? = null

    /**
     * Capture current time in microseconds for latency tracking.
     * Called at the very start of QuickFIX/J callbacks for accurate timing.
     */
    private fun captureTimeMicros(): Long =
        System.currentTimeMillis() * 1000 + (System.nanoTime() % 1_000_000) / 1000

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

        // If auto-reconnect is disabled, stop trying after any disconnect
        if (!config.autoReconnect) {
            logger.info("Auto-reconnect disabled - stopping connection attempts")
            onConnectionFailed?.invoke()
        }
    }

    /**
     * Called for administrative messages (to admin) before they are sent
     */
    override fun toAdmin(message: Message, sessionId: SessionID) {
        // Capture timestamp immediately for accurate latency tracking
        val captureMicros = captureTimeMicros()

        // For Logon messages, add password if required
        try {
            val msgType = message.header.getString(35) // MsgType field
            if (msgType == "A") { // Logon message
                // Password handling if needed
                if (config.username.isNotBlank()) message.setString(553, config.username) // Tag 553 = username
                if (config.password.isNotBlank()) message.setString(554, config.password) // Tag 554 = Password

                // For FIX 5.0+ sessions (FIXT.1.1), add DefaultApplVerID (tag 1137) to logon
                if (config.beginString == "FIXT.1.1") {
                    val applVerID =
                        config.applVerID ?: run {
                            // Try to determine from config, default to FIX 5.0 SP2
                            FixVersion.FIX_5_0_SP2.applVerID
                        }
                    if (applVerID != null && !message.isSetField(1137)) {
                        message.setString(1137, applVerID) // Tag 1137 = DefaultApplVerID
                        logger.info("Added DefaultApplVerID to logon: {}", applVerID)
                    }
                }

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
                    captureTimeMicros = captureMicros,
                )
            onMessageReceived(fixMessage)
        } catch (e: Exception) {
            logger.error("Error displaying outgoing admin message: ${e.message}", e)
        }
    }

    /**
     * Retrieves the raw wire message from the capturing log factory using message header fields.
     */
    private fun getWireMessage(message: Message): String? =
        try {
            val header = message.header
            val sender = header.getString(49)
            val target = header.getString(56)
            val seqNum = header.getInt(34)
            RawMessageCapturingLogFactory.RawMessageCapturingLog.getAndRemoveRawIncoming(sender, target, seqNum)
        } catch (e: Exception) {
            logger.debug("Could not extract wire message key: ${e.message}")
            null
        }

    /**
     * Called for administrative messages (from admin) received
     */
    override fun fromAdmin(message: Message, sessionId: SessionID) {
        // Capture timestamp immediately for accurate latency tracking
        val captureMicros = captureTimeMicros()

        try {
            val wireMessage = getWireMessage(message)

            val rawMessage =
                wireMessage?.toRawFixMessage()
                    ?: message.toRawFixMessage()

            logger.info("Received: {}", rawMessage)
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = rawMessage,
                    messageType = message.header.getString(35),
                    quickfixMessage = message, // Admin messages don't need re-parse
                    captureTimeMicros = captureMicros,
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
        // Capture timestamp immediately for accurate latency tracking
        val captureMicros = captureTimeMicros()

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
                    captureTimeMicros = captureMicros,
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
        // Capture timestamp immediately for accurate latency tracking
        val captureMicros = captureTimeMicros()

        // Get actual wire message from ConcurrentHashMap (keyed by sender/target/seqnum)
        val wireMessage = getWireMessage(message)

        // Raw message for display: prefer wire bytes, fall back to parsed
        val rawMessage =
            wireMessage?.toRawFixMessage()
                ?: message.toString().toRawFixMessage()

        // Re-parse using manual parser (handles non-standard delimiter ordering)
        val parsedMessage =
            if (wireMessage != null) {
                try {
                    val dataDictionary = dictionary.getDataDictionary()
                    if (dataDictionary != null) {
                        wireMessage.toQuickFixMessageManual(dictionary)
                    } else {
                        message
                    }
                } catch (e: Exception) {
                    logger.warn("Manual re-parse failed, using QuickFIX parsed message: ${e.message}")
                    message
                }
            } else {
                message
            }

        logger.info("QuickFIX fromApp: {}", rawMessage)

        try {
            // Convert QuickFIX message to our FIX message model
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING,
                    rawMessage = rawMessage,
                    quickfixMessage = parsedMessage,
                    captureTimeMicros = captureMicros,
                )

            // Route to the message handler
            onMessageReceived(fixMessage)

            // Acceptor auto-response: if configured, reply to the incoming message per the rules.
            maybeAutoRespond(parsedMessage, sessionId)
        } catch (e: Exception) {
            logger.error("Error processing application message: ${e.message}", e)
        }
    }

    /**
     * When running as an acceptor with response rules, replies to [incoming] using the first
     * matching rule. A no-op for initiators or rule-less acceptors, so existing behaviour is
     * unchanged.
     */
    private fun maybeAutoRespond(incoming: Message, sessionId: SessionID) {
        if (config.connectionType != FixConnectionConfig.ConnectionType.ACCEPTOR) return
        val rule = AcceptorResponder.firstMatch(config.acceptorResponseRules, incoming) ?: return
        try {
            val response = AcceptorResponder.buildMessage(AcceptorResponder.resolve(rule.responseTemplate, incoming))
            Session.sendToTarget(response, sessionId)
            logger.info("Acceptor auto-responded to {} with {}", rule.whenMsgType, response.header.getString(35))
        } catch (e: Exception) {
            logger.error("Acceptor auto-response failed: ${e.message}", e)
        }
    }

    /**
     * Sends a FIX message through the QuickFIX session.
     *
     * The message is constructed through the dictionary-aware manual builder, which handles the
     * nested groups QuickFIX's own frame parser struggles with, and judged by [FixMessageValidator] —
     * the same call the editor's linter makes, so Validate and Send can never disagree. A message
     * that does not satisfy the dictionary is still sent (this is a testing tool; sending a bad
     * message on purpose is a legitimate thing to want), with a warning saying what is wrong with it.
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

            // Name a dictionary mismatch locally instead of leaving a cryptic counterparty reject:
            // tags the loaded dictionary doesn't define for this message type get sent flat.
            val unknownTags = DictionaryLint.unknownTags(FixMessageHelper.parseFixMessage(rawMessage), dictionary)
            if (unknownTags.isNotEmpty()) {
                val lint = DictionaryLint.describe(unknownTags, FixMessageHelper.parseFixMessage(rawMessage), dictionary)
                logger.warn(lint, notifyUser = true)
                validationWarning = lint
            }

            val message =
                if (dataDictionary != null) {
                    // What we are handed is a message *body*, not a wire frame: BodyLength(9) and
                    // CheckSum(10) are computed on the way out and the session supplies the sequencing
                    // header. Judging it as a frame therefore failed every message that ever passed
                    // through here ("Header fields out of order"), so every send came back warning
                    // "validation bypassed" — noise that drowned the real thing and contradicted the
                    // editor's linter. Ask the linter itself, so the two cannot disagree.
                    FixMessageValidator.validate(rawMessage, dictionary).errors.firstOrNull()
                        // The lint above already named these tags, in better words. Saying it twice
                        // reads as two problems when there is one.
                        ?.takeUnless { problem -> DictionaryLint.alreadyNamed(problem, unknownTags) }
                        ?.let { problem ->
                            logger.warn("Message does not satisfy the loaded dictionary: $problem")
                            validationWarning = listOfNotNull(validationWarning, problem).joinToString("; ")
                        }
                    // Construct through the dictionary-aware manual builder, which handles the nested
                    // groups QuickFIX's own frame parser struggles with.
                    rawMessage.toQuickFixMessageManual(dictionary)
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

    // ---------------------------------------------------------------- admin / session control

    /** Looks up the active QuickFIX session and runs [action]; returns false if absent or on error. */
    private fun withSession(action: (Session, SessionID) -> Unit): Boolean {
        val sessionID = currentSessionID ?: run {
            logger.warn("No active session for admin action")
            return false
        }
        val session = Session.lookupSession(sessionID) ?: run {
            logger.warn("Session not found: {}", sessionID)
            return false
        }
        return try {
            action(session, sessionID)
            true
        } catch (e: Exception) {
            logger.error("Admin action failed: ${e.message}", e)
            false
        }
    }

    /** Resets sequence numbers. With both null, performs a full session reset (clears the store). */
    fun resetSequenceNumbers(sender: Int?, target: Int?): Boolean =
        withSession { session, _ ->
            if (sender == null && target == null) {
                session.reset()
            } else {
                sender?.let { session.setNextSenderMsgSeqNum(it) }
                target?.let { session.setNextTargetMsgSeqNum(it) }
            }
        }

    /** Current next expected sender/target sequence numbers, or null if there is no active session. */
    fun sequenceNumbers(): Pair<Int, Int>? {
        val sessionID = currentSessionID ?: return null
        val session = Session.lookupSession(sessionID) ?: return null
        return try {
            session.expectedSenderNum to session.expectedTargetNum
        } catch (e: Exception) {
            logger.error("Failed to read sequence numbers: ${e.message}", e)
            null
        }
    }

    fun sendTestRequest(testReqId: String): Boolean =
        withSession { session, _ -> session.generateTestRequest(testReqId) }

    fun sendResendRequest(beginSeqNo: Int, endSeqNo: Int): Boolean =
        withSession { _, sessionID ->
            val msg = Message()
            msg.header.setString(35, "2") // MsgType = ResendRequest
            msg.setInt(7, beginSeqNo) // BeginSeqNo
            msg.setInt(16, endSeqNo) // EndSeqNo (0 = up to latest)
            Session.sendToTarget(msg, sessionID)
        }

    fun sendSequenceReset(newSeqNo: Int, gapFill: Boolean): Boolean =
        withSession { _, sessionID ->
            val msg = Message()
            msg.header.setString(35, "4") // MsgType = SequenceReset
            msg.setInt(36, newSeqNo) // NewSeqNo
            msg.setBoolean(123, gapFill) // GapFillFlag
            Session.sendToTarget(msg, sessionID)
        }

    fun forceLogout(reason: String?): Boolean =
        withSession { session, _ -> if (reason.isNullOrBlank()) session.logout() else session.logout(reason) }

    /** Drops the connection without a graceful logout (for resilience/recovery testing). */
    fun forceDisconnect(reason: String): Boolean = withSession { session, _ -> session.disconnect(reason, false) }
}
