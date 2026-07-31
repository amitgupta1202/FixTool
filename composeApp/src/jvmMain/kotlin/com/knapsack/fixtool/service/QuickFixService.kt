package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorLatencyConfig
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.BookSpec
import com.knapsack.fixtool.model.TAG_MSG_TYPE
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

/**
 * A live acceptor's own state — what it is running and what it is in the middle of doing.
 *
 * [triggersMatched] counts inbound messages that matched a rule; [responsesSent] counts replies that
 * have reached the wire. They are reported separately because the gap between them *is* a state: a
 * sequence that has been triggered and is still playing out has the first ahead of the second, and
 * that is indistinguishable, from the message log alone, from a rule that never matched.
 *
 * [rulesLive] is how many rules are compiled and in force, which is not necessarily how many are
 * saved — a disabled or unusable rule is compiled away, and the difference is the answer to "I saved
 * it, why does nothing happen".
 */
data class AcceptorStatus(
    val rulesLive: Int,
    val latencyActive: Boolean,
    val triggersMatched: Long,
    val responsesSent: Long,
    val pendingResponses: Int,
    /** Counterparties with a session on this acceptor right now. Zero for an acceptor nobody has reached. */
    val clientsConnected: Int = 0,
    /** Logons turned away because they were not addressed to this acceptor — see [VenueEvent.LogonRefused]. */
    val logonsRefused: Long = 0,
)

/**
 * **Something that happened to the venue itself**, as opposed to on one of its sessions.
 *
 * A venue accepting any client has state no single session owns: who arrived, and who was turned
 * away. The second is the more valuable of the two — a refused logon is otherwise the quietest
 * failure in FIX, since the engine answers a logon it does not recognise with nothing at all.
 */
sealed interface VenueEvent {
    val sessionId: SessionID
    val at: LocalDateTime

    /** A counterparty logged on and now has a session of its own. */
    data class ClientArrived(
        override val sessionId: SessionID,
        override val at: LocalDateTime = LocalDateTime.now(),
    ) : VenueEvent

    /**
     * A logon was turned away. [sessionId] is the session that *would* have been created, so its
     * SenderCompID is the venue name the client addressed and its TargetCompID is who they claimed
     * to be — which is exactly the pair a reader needs to see the typo.
     */
    data class LogonRefused(
        override val sessionId: SessionID,
        override val at: LocalDateTime = LocalDateTime.now(),
    ) : VenueEvent
}

/**
 * **Everything a pane can do to one FIX session**, without knowing which one it is.
 *
 * An initiator's pane owns its session; a venue client's pane is one of many sharing a single
 * transport and engine. Both need to send, log out, and drive sequence numbers, and neither should
 * be written twice. [sessionId] is null before a session exists, which is the same "not connected
 * yet" that every operation below already answers for.
 */
interface SessionEndpoint {
    val sessionId: SessionID?

    fun send(rawMessage: String, dictionary: FixDictionary): SendResult

    fun logout()

    fun resetSequenceNumbers(sender: Int?, target: Int?): Boolean

    fun sequenceNumbers(): Pair<Int, Int>?

    fun sendTestRequest(testReqId: String): Boolean

    fun sendResendRequest(beginSeqNo: Int, endSeqNo: Int): Boolean

    fun sendSequenceReset(newSeqNo: Int, gapFill: Boolean): Boolean

    fun forceLogout(reason: String?): Boolean

    fun forceDisconnect(reason: String): Boolean

    fun stopPendingResponses(): Int
}

class QuickFixService(
    private val config: FixConnectionConfig,
    private val dictionary: FixDictionary,
    private val onMessageReceived: (FixMessage) -> Unit,
    private val onStateChanged: (FixConnectionState) -> Unit,
    private val onError: ((String) -> Unit)? = null,
    private val onConnectionFailed: (() -> Unit)? = null,
    private val onWarning: ((String) -> Unit)? = null,
    /**
     * Where a venue's own news goes — a client arriving, a logon refused. Null for everything that is
     * not an acceptor open to any client, which is every connection that has exactly one session and
     * therefore nothing to report that its own state does not already say.
     */
    private val onVenueEvent: ((VenueEvent) -> Unit)? = null,
) : Application {
    private val logger = NotifyingLogger(QuickFixService::class.java, onError, onWarning)

    /**
     * The one session this service is bound to — an initiator's, or the single counterparty of an
     * acceptor that names one. Null for a venue, whose sessions are held in [channels] instead
     * because there is no "the" session to be bound to.
     */
    private var boundSessionId: SessionID? = null

    /** True when this acceptor creates a session per client; see [FixConnectionConfig.acceptsAnyClient]. */
    private val isVenue = config.acceptsAnyClient()

    /** One per counterparty that has reached this venue, alive for as long as its pane is. */
    private val channels = java.util.concurrent.ConcurrentHashMap<SessionID, ClientChannel>()

    /** Logons turned away, counted for [acceptorStatus] and reported one by one through [onVenueEvent]. */
    private val logonsRefused = java.util.concurrent.atomic.AtomicLong()

    /**
     * The rules' triggers, parsed **once per ruleset** rather than per inbound message — re-parsing
     * per message would put JSON on the path of every message a loaded acceptor receives, to reach an
     * answer that has not changed. A rule that cannot be compiled is left out and said so once, when
     * it is compiled, rather than failing quietly on each message that would have matched it.
     *
     * Swappable, via [reloadAcceptorRules], because the ruleset *can* change while a session is up:
     * these used to be compiled once for the life of the service, so editing a rule under a logged-on
     * session changed the file and nothing else. The author then watched the old rule keep firing with
     * no indication anywhere that they were driving a stale ruleset — which reads exactly like a rule
     * that does not work, and sends them off to rewrite a rule that was already correct.
     *
     * The reference is swapped, never mutated, and each compiled list is immutable, so a reader on the
     * callback thread sees one whole ruleset or the other and never a half-applied edit. Replies
     * already queued keep the templates they were planned with; the swap governs the next trigger.
     */
    @Volatile
    private var compiledRules: List<CompiledRule> = compileAcceptorRules(config.acceptorResponseRules)

    /** The latency in force, swapped alongside the rules — it is edited on the same panel and the same save. */
    @Volatile
    private var acceptorLatency: AcceptorLatencyConfig = config.acceptorLatency

    private fun compileAcceptorRules(rules: List<AcceptorResponseRule>): List<CompiledRule> {
        rules
            .filter { it.validationError() != null }
            .forEach { logger.warn("Acceptor rule on {}: {}", it.whenMsgType, it.validationError()) }
        return AcceptorResponder.compile(rules)
    }

    /**
     * Swaps in a ruleset saved since this session connected, without dropping the session.
     *
     * Returns how many rules are now live — which is not necessarily how many were passed, since a
     * disabled or unusable one is compiled away. That difference is the whole answer to "I saved it,
     * why does nothing happen", so it is what the caller gets rather than a bare success.
     */
    fun reloadAcceptorRules(rules: List<AcceptorResponseRule>, latency: AcceptorLatencyConfig): Int {
        compiledRules = compileAcceptorRules(rules)
        acceptorLatency = latency
        return compiledRules.size
    }

    /**
     * The source of each response's simulated latency. One per service, so a config's random-range and
     * normal modes draw an independent sample per triggering message; `java.util.Random` for its
     * `nextGaussian`, and thread-safe, which matters because the draw happens on the QuickFIX callback
     * thread. Deterministic under a seed — see the config's own tests.
     */
    private val latencyRandom = java.util.Random()

    /**
     * **What this venue is holding, per counterparty** — fed from `fromApp` and `toApp`, so it
     * records what the client was told rather than what a rule intended.
     *
     * One per service and keyed inside by `SessionID`, because a multi-client venue is one service
     * with a session per client, and ClOrdID is unique per client rather than per venue.
     */
    private val orderBooks = OrderBookService()

    /**
     * This venue's book for one counterparty, or an empty one for a client it has never heard from.
     *
     * [sessionId] null means "whichever session this service is bound to", which is the single-client
     * acceptor: it has exactly one counterparty and no pane of its own to name it with.
     */
    fun orderBook(sessionId: SessionID? = null): BookView =
        orderBooks.view((sessionId ?: boundSessionId)?.toString().orEmpty())

    /** Every counterparty this venue has booked anything for. */
    fun orderBookSessions(): List<String> = orderBooks.sessions()

    fun clearOrderBook(sessionId: SessionID? = null, by: String = "manually") =
        orderBooks.clear((sessionId ?: boundSessionId)?.toString().orEmpty(), by)

    /**
     * The one place an acceptor auto-response reaches the wire. Off the callback thread on purpose —
     * see [AcceptorDispatch] for why a reply sent inline is not merely early but impossibly early.
     */
    private val autoResponseDispatch =
        AcceptorDispatch(
            onSent = { response ->
                responsesSent.incrementAndGet()
                logger.info("Acceptor auto-responded with {}", response.header.getString(35))
            },
            onError = { message, e -> logger.error(message, e) },
        )

    /**
     * How many inbound messages have matched a rule, and how many replies have actually left.
     *
     * Two numbers rather than one because they answer different questions and their *difference* is
     * the interesting state: a sequence that has matched but is still playing out reads as
     * `triggersMatched` ahead of `responsesSent`, which is exactly the window a latency or ordering
     * test is looking at. Cumulative for the life of the session, so they only ever go up and a reader
     * can diff two reads rather than reason about when something was reset.
     */
    private val triggersMatched = java.util.concurrent.atomic.AtomicLong()
    private val responsesSent = java.util.concurrent.atomic.AtomicLong()

    /**
     * What this acceptor is doing right now.
     *
     * Answering "did my rule fire?" and "is a sequence still in flight?" otherwise means reading the
     * message list and inferring — which cannot distinguish a rule that never matched from one that
     * matched and whose reply is still sitting in the dispatch queue behind a delay. Those look the
     * same in the log (nothing there yet) and have completely different causes.
     *
     * Null for a session that is not an acceptor, so the caller reports the section only where it
     * means something rather than showing five zeroes on every initiator.
     */
    fun acceptorStatus(): AcceptorStatus? {
        if (config.connectionType != FixConnectionConfig.ConnectionType.ACCEPTOR) return null
        return AcceptorStatus(
            rulesLive = compiledRules.size,
            latencyActive = acceptorLatency.isActive(),
            triggersMatched = triggersMatched.get(),
            responsesSent = responsesSent.get(),
            // Summed across every client on a venue: a reply still queued is in flight whoever it is
            // owed to, and a caller asking "is this acceptor finished?" wants all of them.
            pendingResponses =
                if (isVenue) {
                    channels.keys.sumOf { autoResponseDispatch.pendingCount(it) }
                } else {
                    boundSessionId?.let { autoResponseDispatch.pendingCount(it) } ?: 0
                },
            clientsConnected = channels.values.count { it.isLoggedOn() },
            logonsRefused = logonsRefused.get(),
        )
    }

    /**
     * Capture current time in microseconds for latency tracking.
     * Called at the very start of QuickFIX/J callbacks for accurate timing.
     */
    private fun captureTimeMicros(): Long =
        System.currentTimeMillis() * 1000 + (System.nanoTime() % 1_000_000) / 1000

    override fun onCreate(sessionId: SessionID) {
        logger.info("QuickFIX Session created: {}", sessionId)
        if (isVenue) {
            channelFor(sessionId).state(CONNECTING)
            return
        }
        boundSessionId = sessionId
        onStateChanged(CONNECTING)
    }

    override fun onLogon(sessionId: SessionID) {
        logger.info("QuickFIX Session logged on: {}", sessionId)
        if (isVenue) {
            // Also the *re*connect path: QuickFIX/J keeps a dynamically created session registered, so
            // a client that logs out and comes back gets no second onCreate. Resolving the channel here
            // too is what lets that client find its old pane instead of arriving as a stranger.
            channelFor(sessionId).state(LOGGED_ON)
            return
        }
        boundSessionId = sessionId
        onStateChanged(LOGGED_ON)
    }

    override fun onLogout(sessionId: SessionID) {
        logger.info("QuickFIX Session logged out: {}", sessionId)
        // Replies still queued for a counterparty that has gone away are dropped, not attempted. A
        // half-played sequence whose remaining steps fail one by one buries the logout that caused it.
        autoResponseDispatch.cancelAll(sessionId)

        if (isVenue) {
            // One client leaving is not the venue closing. The channel is kept so its pane keeps the
            // history that usually explains the departure, and onConnectionFailed is deliberately not
            // called: for an acceptor that means "stop listening", which would let a single client's
            // logout take the venue and every other client down with it.
            channels[sessionId]?.state(DISCONNECTED)
            return
        }

        boundSessionId = null
        onStateChanged(DISCONNECTED)

        // If auto-reconnect is disabled, stop trying after any disconnect
        if (!config.autoReconnect) {
            logger.info("Auto-reconnect disabled - stopping connection attempts")
            onConnectionFailed?.invoke()
        }
    }

    // ---------------------------------------------------------------- venue channels

    /**
     * This counterparty's channel, announcing it the first time it is asked for.
     *
     * The channel is created *here*, on the engine's thread, rather than by whoever handles the
     * announcement — because the Logon that caused it is already on its way to [fromAdmin] and pane
     * creation has a UI thread to reach first. A channel that does not exist yet buffers; a channel
     * that is created late loses the Logon, which is the one message a reader most often wants.
     */
    private fun channelFor(sessionId: SessionID): ClientChannel {
        var arrived = false
        val channel = channels.computeIfAbsent(sessionId) { arrived = true; ClientChannel() }
        if (arrived) {
            logger.info("Client session on venue {}: {}", config.senderCompID, sessionId)
            onVenueEvent?.invoke(VenueEvent.ClientArrived(sessionId))
        }
        return channel
    }

    /** Records a logon this venue turned away. Called by [VenueSessionProvider]. */
    fun noteRefusedLogon(sessionId: SessionID) {
        logonsRefused.incrementAndGet()
        onVenueEvent?.invoke(VenueEvent.LogonRefused(sessionId))
    }

    /**
     * Points a client's pane at its session: everything buffered since the session was created is
     * delivered, in order, before this returns, and everything after it goes straight through.
     */
    fun attachClient(sessionId: SessionID, onMessage: (FixMessage) -> Unit, onState: (FixConnectionState) -> Unit) {
        channelFor(sessionId).attach(onMessage, onState)
    }

    /** Detaches a closed pane. The session itself is untouched — closing a pane is not a logout. */
    fun detachClient(sessionId: SessionID) {
        channels.remove(sessionId)
    }

    /** Where a message for [sessionId] belongs. Falls back to the owner's sink for a single-session connection. */
    private fun deliver(sessionId: SessionID, message: FixMessage) {
        if (isVenue) channelFor(sessionId).deliver(message) else onMessageReceived(message)
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

        // Serialised once. `toRawFixMessage()` is `toString().replace(SOH, '|')`, so taking both from one
        // string spares a second full serialisation — QFJ recomputes BodyLength and re-checksums the whole
        // message each time — and, more to the point, guarantees the two fields are the same serialisation
        // of the same object rather than two independent ones taken a moment apart.
        val wire = message.toString()
        val rawMessage = wire.toRawFixMessage()
        logger.info("Sending: {}", rawMessage)

        try {
            val fixMessage =
                FixMessage(
                    timestamp = LocalDateTime.now(),
                    direction = FixMessage.Direction.OUTGOING,
                    rawMessage = rawMessage,
                    quickfixMessage = message,
                    captureTimeMicros = captureMicros,
                    wireRaw = if (rewrittenAfterThisCallback(message)) null else wire,
                )
            deliver(sessionId, fixMessage)
        } catch (e: Exception) {
            logger.error("Error displaying outgoing admin message: ${e.message}", e)
        }
    }

    /**
     * Will QuickFIX/J still change this outgoing admin message after we have looked at it?
     *
     * For a **Logon carrying `ResetSeqNumFlag=Y`** it will. `Session.sendRaw` (QFJ 2.3.2, ~line 2600) calls
     * `application.toAdmin(...)` — this callback — and only *then* does `resetState()` and
     * `header.setInt(MsgSeqNum, getExpectedSenderNum())`, before serialising. So the `MsgSeqNum` we can see
     * here is the pre-reset one, and the venue receives `34=1`.
     *
     * That makes `toString()` at this moment **not the wire**, and the difference is exactly the kind an
     * assertion would be written about: a step checking the Logon's `34` would have been judged against a
     * sequence number that was never sent, and could pass on it. So we record no wire bytes at all and let
     * the engine refuse the message — a false red on an exotic assertion, rather than a false green on a
     * sequence number the counterparty never saw.
     *
     * The guard is deliberately a shade wider than QFJ's own condition (which also requires that no reset
     * was *received*), because that state is not visible from here. Erring wide costs a refusal; erring
     * narrow costs a green.
     */
    private fun rewrittenAfterThisCallback(message: Message): Boolean =
        try {
            message.header.getString(35) == "A" &&
                message.isSetField(RESET_SEQ_NUM_FLAG) &&
                message.getBoolean(RESET_SEQ_NUM_FLAG)
        } catch (e: Exception) {
            logger.debug("Could not read ResetSeqNumFlag: ${e.message}")
            false
        }

    /**
     * The bytes an incoming message actually arrived as — **the one source of wire order**.
     *
     * QuickFIX/J keeps what it parsed (`Message.messageData`, exposed as `toRawString()`), so there is
     * nothing to intercept, correlate or cache. This used to be a `Log` decorator that stashed every
     * incoming string in a process-wide map keyed by `sender->target:seqnum` and consumed it
     * destructively. That map was a second implementation of a fact QFJ already had, and it was wrong in
     * three ways it could not be right in: the key carried no BeginString or session qualifier, so two
     * sessions sharing a CompID pair addressed the same slot; the read was destructive, so a PossDup
     * replay of a sequence number found nothing; and every message QFJ rejected before dispatch leaked
     * its entry for the life of the process. Asking QFJ has none of those failure modes.
     *
     * Null only if QFJ hands us a message it did not parse from bytes. The caller must **say so** rather
     * than substitute `toString()`, which is not the venue's order — see [FixMessage.wireRaw].
     */
    private fun wireBytesOf(message: Message): String? {
        val raw = message.toRawString()?.takeIf { it.isNotBlank() }
        if (raw == null) {
            // Name the message. This is the one line that fires when the design's load-bearing assumption
            // breaks, and on a multi-session run "an incoming message" tells an operator nothing they can
            // act on — not which venue, not which sequence number, not whether it is one message or all of
            // them. Every scenario step bound to such a message will fail citing FixTool, and this is where
            // they find out why.
            logger.warn(
                "No wire bytes for incoming {} from {} to {} seq {} — order-sensitive assertions on it " +
                    "cannot be evaluated",
                headerOrNull(message, 35),
                headerOrNull(message, 49),
                headerOrNull(message, 56),
                headerOrNull(message, 34),
            )
        }
        return raw
    }

    private fun headerOrNull(message: Message, tag: Int): String? =
        try {
            if (message.header.isSetField(tag)) message.header.getString(tag) else null
        } catch (e: Exception) {
            logger.debug("Could not read header tag $tag: ${e.message}")
            null
        }

    /**
     * Called for administrative messages (from admin) received
     */
    override fun fromAdmin(message: Message, sessionId: SessionID) {
        // Capture timestamp immediately for accurate latency tracking
        val captureMicros = captureTimeMicros()

        try {
            val wireMessage = wireBytesOf(message)

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
                    wireRaw = wireMessage,
                )
            deliver(sessionId, fixMessage)
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

        // One serialisation, two views of it — see toAdmin.
        val wire = message.toString()
        val rawMessage = wire.toRawFixMessage()
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
                    // Genuinely the wire, and unlike toAdmin nothing mutates the message after this call:
                    // `Session.sendRaw` serialises it on the very next statement after `toApp` returns, and
                    // sends that string. (It is QFJ's field ordering rather than the order the author typed
                    // in the editor — but that is what the counterparty receives, and a capture must record
                    // what was sent, not what was meant.)
                    wireRaw = wire,
                )
            deliver(sessionId, fixMessage)

            // Every reply the client will see passes through here — a rule's, one typed into the
            // editor, a Reply With… shape, a scenario step. That is exactly why the book is fed from
            // the wire and not from the rules engine (decision 2): by the time these bytes exist,
            // who composed them is no longer a distinction the counterparty could make.
            book(sessionId, fixMessage, message, sent = true)
        } catch (e: Exception) {
            logger.error("Error displaying outgoing app message: ${e.message}", e)
        }
    }

    /**
     * Records one application message against this counterparty's order book.
     *
     * **Acceptors only.** The book is what a venue is holding *for a client*; a client's own view of
     * the orders it has sent is a different feature for a different user, and is named as out of
     * scope in `docs/acceptor-order-state-proposal.md`.
     *
     * Failure here is swallowed on purpose and logged without notifying: the book is a view of the
     * conversation, and no defect in it may cost the user the conversation itself.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun book(sessionId: SessionID, fixMessage: FixMessage, message: Message, sent: Boolean) {
        if (config.connectionType != FixConnectionConfig.ConnectionType.ACCEPTOR) return
        try {
            orderBooks.record(
                sessionKey = sessionId.toString(),
                at = fixMessage.timestamp,
                sent = sent,
                fields = bookFields(message),
                raw = fixMessage.rawMessage,
                messageUid = fixMessage.uid,
            )
        } catch (e: Exception) {
            logger.warn("Order book could not record a message: {}", e.message)
        }
    }

    /** The message reduced to the tags the book reads — see [OrderBookService.record]. */
    private fun bookFields(message: Message): Map<Int, String> {
        val wanted = BookSpec.ORDERS.readTags + TAG_MSG_TYPE
        return wanted.mapNotNull { tag ->
            val value =
                try {
                    when {
                        message.header.isSetField(tag) -> message.header.getString(tag)
                        message.isSetField(tag) -> message.getString(tag)
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
            value?.let { tag to it }
        }.toMap()
    }

    /**
     * Called for application messages received
     */
    override fun fromApp(message: Message, sessionId: SessionID) {
        // Capture timestamp immediately for accurate latency tracking
        val captureMicros = captureTimeMicros()

        // The bytes as they arrived — QFJ kept them; see wireBytesOf.
        val wireMessage = wireBytesOf(message)

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
                    // The bytes as they arrived, SOH and all — or null, honestly, when we do not have
                    // them. rawMessage above has substituted '|' for SOH so a human can read it, and that
                    // substitution cannot be undone: a value that legitimately contains '|' is
                    // indistinguishable from a field boundary. The assertion engine reads this instead,
                    // and it reads it *for the order* as much as for the values — so `message.toString()`
                    // is not an acceptable stand-in here, however plausible its output looks. See
                    // FixMessage.wireRaw.
                    wireRaw = wireMessage,
                )

            // Route to the message handler
            deliver(sessionId, fixMessage)

            // Before the reply is planned, so a rule that reads the book (slice B) sees the order it
            // is answering. The book is fed from the wire either way — see book().
            book(sessionId, fixMessage, parsedMessage, sent = false)

            // Acceptor auto-response: if configured, reply to the incoming message per the rules.
            maybeAutoRespond(parsedMessage, fixMessage, sessionId)
        } catch (e: Exception) {
            logger.error("Error processing application message: ${e.message}", e)
        }
    }

    /**
     * When running as an acceptor with response rules, replies to [incoming] using the first
     * matching rule. A no-op for initiators or rule-less acceptors, so existing behaviour is
     * unchanged.
     *
     * Reading [incoming] happens here, on the callback thread, because that message belongs to this
     * call — so the plan's `${req.<tag>}` substitutions are fixed before anything is queued. The
     * rest of each step is built and sent by [autoResponseDispatch], at its own moment.
     */
    private fun maybeAutoRespond(incoming: Message, request: FixMessage, sessionId: SessionID) {
        if (config.connectionType != FixConnectionConfig.ConnectionType.ACCEPTOR) return
        val rule = AcceptorResponder.firstMatch(compiledRules, incoming) ?: return
        triggersMatched.incrementAndGet()
        try {
            // Drawn once for this triggering message, then added to every step's offset so the whole
            // reply slides by the one number and its authored order cannot invert. Skipped entirely
            // for an inert config, so a rule-less-latency acceptor puts no RNG on the callback thread
            // and behaves exactly as it did before this existed. See [AcceptorLatencyConfig].
            // Read once into a local: the field is swappable, and a sequence must be shifted by one
            // sample even if a save lands between the check and the draw.
            val latency = acceptorLatency
            val latencyMillis = if (latency.isActive()) latency.sample(latencyRandom) else 0L
            if (latencyMillis > 0L) {
                logger.info("Acceptor applying {}ms simulated latency to {} response", latencyMillis, rule.whenMsgType)
            }
            AcceptorResponder.plan(rule, incoming, request, dictionary).forEach { planned ->
                autoResponseDispatch.schedule(sessionId, planned.offsetMillis + latencyMillis, planned::build)
            }
        } catch (e: Exception) {
            logger.error("Acceptor auto-response failed to plan: ${e.message}", e)
        }
    }

    /**
     * Drops every auto-response still waiting to go out on this session, and reports how many.
     *
     * A sequence is a claim about time — "then, four seconds later, the fill" — so the moment an
     * author realises it is the wrong claim they need it to stop *now*, not after it has finished
     * being wrong. Rules already sent are not recalled; there is no such thing on a wire.
     */
    private fun stopPendingResponses(sessionID: SessionID?): Int {
        val sessionId = sessionID ?: return 0
        val dropped = autoResponseDispatch.pendingCount(sessionId)
        autoResponseDispatch.cancelAll(sessionId)
        if (dropped > 0) logger.info("Dropped {} queued acceptor response(s)", dropped)
        return dropped
    }

    /**
     * Releases the auto-response dispatch thread. Called when the connection is torn down; safe to
     * call more than once, and a no-op for any session that never auto-responded.
     */
    fun shutdown() {
        autoResponseDispatch.close()
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
    private fun sendMessage(
        sessionID: SessionID?,
        rawMessage: String,
        dictionary: com.knapsack.fixtool.model.FixDictionary,
    ): SendResult {
        val startTime = System.nanoTime()
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
    private fun logout(sessionID: SessionID?) {
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

    /** Looks up the named QuickFIX session and runs [action]; returns false if absent or on error. */
    private fun withSession(sessionID: SessionID?, action: (Session, SessionID) -> Unit): Boolean {
        if (sessionID == null) {
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
    private fun resetSequenceNumbers(sessionID: SessionID?, sender: Int?, target: Int?): Boolean =
        withSession(sessionID) { session, _ ->
            if (sender == null && target == null) {
                session.reset()
            } else {
                sender?.let { session.setNextSenderMsgSeqNum(it) }
                target?.let { session.setNextTargetMsgSeqNum(it) }
            }
        }

    /** Current next expected sender/target sequence numbers, or null if there is no active session. */
    private fun sequenceNumbers(sessionID: SessionID?): Pair<Int, Int>? {
        val session = sessionID?.let { Session.lookupSession(it) } ?: return null
        return try {
            session.expectedSenderNum to session.expectedTargetNum
        } catch (e: Exception) {
            logger.error("Failed to read sequence numbers: ${e.message}", e)
            null
        }
    }

    /**
     * The endpoint for **this connection's own session** — an initiator's, or the single counterparty
     * of an acceptor that names one. Resolved at each call rather than captured, because the session
     * does not exist until logon and the pane holding this is created before that.
     */
    fun ownerEndpoint(): SessionEndpoint = BoundEndpoint { boundSessionId }

    /** The endpoint for one client of a venue. Fixed: this pane speaks to this counterparty and no other. */
    fun endpointFor(sessionId: SessionID): SessionEndpoint = BoundEndpoint { sessionId }

    /**
     * A pane's whole view of the engine, narrowed to one session.
     *
     * The session is resolved through a function rather than held, so the same class serves the
     * initiator (whose session arrives later) and a venue client (whose session is why the pane
     * exists at all) without either needing to know which it is.
     */
    private inner class BoundEndpoint(private val resolve: () -> SessionID?) : SessionEndpoint {
        override val sessionId: SessionID? get() = resolve()

        override fun send(rawMessage: String, dictionary: FixDictionary): SendResult =
            sendMessage(resolve(), rawMessage, dictionary)

        override fun logout() = this@QuickFixService.logout(resolve())

        override fun resetSequenceNumbers(sender: Int?, target: Int?): Boolean =
            this@QuickFixService.resetSequenceNumbers(resolve(), sender, target)

        override fun sequenceNumbers(): Pair<Int, Int>? = this@QuickFixService.sequenceNumbers(resolve())

        override fun sendTestRequest(testReqId: String): Boolean =
            withSession(resolve()) { session, _ -> session.generateTestRequest(testReqId) }

        override fun sendResendRequest(beginSeqNo: Int, endSeqNo: Int): Boolean =
            withSession(resolve()) { _, sessionID ->
                val msg = Message()
                msg.header.setString(35, "2") // MsgType = ResendRequest
                msg.setInt(7, beginSeqNo) // BeginSeqNo
                msg.setInt(16, endSeqNo) // EndSeqNo (0 = up to latest)
                Session.sendToTarget(msg, sessionID)
            }

        override fun sendSequenceReset(newSeqNo: Int, gapFill: Boolean): Boolean =
            withSession(resolve()) { _, sessionID ->
                val msg = Message()
                msg.header.setString(35, "4") // MsgType = SequenceReset
                msg.setInt(36, newSeqNo) // NewSeqNo
                msg.setBoolean(123, gapFill) // GapFillFlag
                Session.sendToTarget(msg, sessionID)
            }

        override fun forceLogout(reason: String?): Boolean =
            withSession(resolve()) { session, _ ->
                if (reason.isNullOrBlank()) session.logout() else session.logout(reason)
            }

        /** Drops the connection without a graceful logout (for resilience/recovery testing). */
        override fun forceDisconnect(reason: String): Boolean =
            withSession(resolve()) { session, _ -> session.disconnect(reason, false) }

        override fun stopPendingResponses(): Int = this@QuickFixService.stopPendingResponses(resolve())
    }

    /**
     * **One counterparty's traffic and state, and where they are delivered** — which is not known at
     * the moment the session is created.
     *
     * Everything is buffered until a pane attaches, and the buffer is what makes the client's Logon
     * survive: it reaches [fromAdmin] on the engine's thread within microseconds of the session
     * existing, while creating the pane has a UI thread to get to first. The lock is held only long
     * enough to decide *where* a message goes, never while delivering it, so a slow pane cannot stall
     * the engine's callback thread.
     */
    private class ClientChannel {
        private val lock = Any()
        private var messages: ((FixMessage) -> Unit)? = null
        private var states: ((FixConnectionState) -> Unit)? = null
        private val buffered = ArrayDeque<FixMessage>()
        private var lastState: FixConnectionState = CONNECTING

        fun deliver(message: FixMessage) {
            val sink =
                synchronized(lock) {
                    messages.also { sink ->
                        if (sink == null) {
                            // Bounded: a pane that never arrives is a bug, and an unbounded buffer would
                            // turn it into an OOM that takes the evidence with it.
                            if (buffered.size >= MAX_BUFFERED) buffered.removeFirst()
                            buffered.addLast(message)
                        }
                    }
                }
            sink?.invoke(message)
        }

        fun state(newState: FixConnectionState) {
            val sink = synchronized(lock) { lastState = newState; states }
            sink?.invoke(newState)
        }

        fun isLoggedOn(): Boolean = synchronized(lock) { lastState == LOGGED_ON }

        fun attach(onMessage: (FixMessage) -> Unit, onState: (FixConnectionState) -> Unit) {
            val (drained, state) =
                synchronized(lock) {
                    messages = onMessage
                    states = onState
                    val pending = buffered.toList()
                    buffered.clear()
                    pending to lastState
                }
            drained.forEach(onMessage)
            onState(state)
        }

        private companion object {
            const val MAX_BUFFERED = 1000
        }
    }

    private companion object {
        /** ResetSeqNumFlag — the one field whose presence means QFJ will rewrite the Logon after toAdmin. */
        const val RESET_SEQ_NUM_FLAG = 141
    }
}
