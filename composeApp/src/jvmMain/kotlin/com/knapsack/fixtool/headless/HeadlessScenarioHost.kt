package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.ConnectAttempt
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.FixMessageView
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioHost
import com.knapsack.fixtool.service.SendResult
import com.knapsack.fixtool.service.SessionIdentityResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * A [ScenarioHost] that owns its FIX sessions directly, with no ViewModel and no window.
 *
 * The runner has always been pure, but the only host that could actually *send* was built on the
 * Compose ViewModel — so running a scenario meant booting the GUI, giving it a display, opening the
 * control port, and translating a JSON field into an exit code by hand. This is the other
 * implementation of the same nine methods.
 *
 * It is markedly simpler than the ViewModel one, and for a reason worth stating: every EDT hop there
 * exists because Compose state is EDT-bound. Nothing here is. [FixMessageSession] keeps its messages
 * and its connection state in `StateFlow`s, which are safe to read from any thread, so this host is
 * the same logic with the marshalling removed rather than a reimplementation of it.
 *
 * **Sessions are created on demand, by name.** A scenario names its sessions; [connectSession] finds
 * the saved profile of that name and brings one up. Nothing is connected speculatively — a scenario
 * that touches one of five profiles dials one venue.
 *
 * **A multi-session profile's slots are named `"Name [n]"`**, exactly as the app names them, and a step
 * may name one directly. Resolving `"Name [3]"` back to profile `"Name"` and dialling *that slot's*
 * identity is what lets a fan-out run headless: without it the host created a single session called
 * `"Name [3]"` from no profile at all, or one session per profile name that ignored `sessionCount`
 * entirely — three lanes that were one client wearing three labels.
 */
class HeadlessScenarioHost(
    private val profiles: List<FixConnectionProfile>,
    private val dictionary: FixDictionary,
    private val appSettings: AppSettings,
    private val onLog: (String) -> Unit = {},
) : ScenarioHost {
    private val sessions = ConcurrentHashMap<String, FixMessageSession>()

    /** Every session this run brought up, for teardown. */
    val opened: Collection<FixMessageSession> get() = sessions.values

    override fun resolve(raw: String, scope: MutableMap<String, String>, session: String?): String {
        val msgs = fixMessages(session)
        return FixMessageTemplate.evaluate(raw, byType(msgs, true), byType(msgs, false), scope, dictionary)
    }

    override fun send(raw: String, session: String?): Boolean {
        val sess = resolve(session) ?: return false
        return sess.sendFixMessage(raw, dictionary) !is SendResult.Failed
    }

    override fun messages(session: String?): List<FixMessage> = fixMessages(session)

    override fun discarded(session: String?): Long = resolve(session)?.discarded?.value ?: 0

    override fun connectionState(session: String?): String? = resolve(session)?.connectionState?.value?.name

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun referenceResolver(session: String?, scope: Map<String, String>): (String) -> String? {
        val msgs = fixMessages(session)
        val incoming = byType(msgs, true)
        val outgoing = byType(msgs, false)
        val vars = scope.toMutableMap()
        return { expression ->
            try {
                FixMessageTemplate
                    .evaluate(expression, incoming, outgoing, vars, dictionary)
                    .takeIf { it != expression }
            } catch (e: Exception) {
                null
            }
        }
    }

    // Null when the venue's bytes are unavailable — the runner turns that into a failed step that says
    // so, instead of asserting against QuickFIX's re-ordered re-serialisation. See FixMessage.wireRaw.
    override fun view(message: FixMessage): MessageView? = FixMessageView.of(message)

    override fun clearMessages(session: String?): Boolean {
        val sess = resolve(session) ?: return false
        sess.clearMessages()
        return true
    }

    override fun resetSeqNum(session: String?, sender: Int?, target: Int?): Boolean {
        val sess = resolve(session) ?: return false
        sess.resetSequenceNumbers(sender, target)
        return true
    }

    // A headless run hosts a venue exactly as the app does when the profile is an acceptor, so the same
    // book answers here. It is the run-boundary reset a CI suite needs most: nothing else in the process
    // remembers between scenarios.
    override fun ownsOrderBook(session: String?): Boolean = resolve(session)?.orderBook() != null

    override fun clearOrderBook(session: String?): Boolean {
        val sess = resolve(session) ?: return false
        if (sess.orderBook() == null) return false
        sess.clearOrderBook(by = "a scenario step")
        return true
    }

    /**
     * Brings up the session a step names, from the saved profile of the same name.
     *
     * [ConnectAttempt.Started] means the dial is under way, not that logon succeeded — the runner owns
     * the bounded wait for LOGGED_ON, and reports the failure in its own words if it never arrives.
     * An already-open session is reconnected rather than duplicated, which is what a scenario that
     * disconnects and reconnects in its own steps needs.
     */
    override fun connectSession(session: String?): ConnectAttempt {
        val existing = resolve(session)
        if (existing != null) {
            onLog("reconnecting session '${existing.title}'")
            existing.reconnect()
            return ConnectAttempt.Started(existing.title)
        }
        if (session == null) {
            return ConnectAttempt.Failed(
                "no session is open and the step names none, so there is no profile to look up — " +
                    "name the session on the step, or pass --session",
            )
        }
        val key = session
        val matches = profiles.filter { it.id == key || it.name == key }.distinctBy { it.id }
        return when {
            matches.isEmpty() ->
                ConnectAttempt.Failed("no saved connection profile named '$key' (see ~/.fixtool)")
            // Refused rather than guessed: picking one of two identically-named profiles would dial a
            // venue the author did not choose, and a run against the wrong environment is worse than
            // one that stops.
            matches.size > 1 ->
                ConnectAttempt.Failed("${matches.size} saved profiles answer to '$key' — rename one, or pass --session")
            else -> {
                val profile = matches.single()
                val slots = laneCount(profile)
                // A step naming "Name [3]" wants slot 3 of the group, not a fresh session of its own.
                val slot =
                    SLOT_SUFFIX.find(key)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                if (slot != null && slot in 1..slots) {
                    openSlot(profile, slot, slots)
                } else {
                    openSlot(profile, slot = if (slots > 1) 1 else 0, slots = slots)
                }
                ConnectAttempt.Started(profile.name)
            }
        }
    }

    /**
     * **Brings up every lane of a multi-session profile** — what `--fan-out` needs and what dialling by
     * name could never give it, since one name maps to one session however many the profile opens.
     *
     * Returns the lanes that reached LOGGED_ON, numbered by **profile slot** so lane 7 is the same client
     * on every run. A shortfall is reported to the caller by returning fewer lanes than the profile
     * declares, never by refusing: if a venue admits 38 of 50, 38 lanes is a load test and zero is not.
     */
    fun openLanes(
        profile: FixConnectionProfile,
        timeoutMs: Long = LANE_LOGON_TIMEOUT_MS,
        /** A last word on each lane's config, for a load run's per-run store and log. Identity by default. */
        configure: (FixConnectionConfig) -> FixConnectionConfig = { it },
    ): List<Lane> {
        val slots = laneCount(profile)
        val opened = (1..slots).map { slot -> slot to openSlot(profile, slot, slots, configure) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline &&
            opened.any { (_, sess) -> sess.connectionState.value != FixConnectionState.LOGGED_ON }
        ) {
            sleep(LANE_POLL_MS)
        }
        return opened
            .filter { (_, sess) -> sess.connectionState.value == FixConnectionState.LOGGED_ON }
            .map { (slot, sess) ->
                Lane(
                    slot = slot,
                    sessionTitle = sess.title,
                    senderCompID = sess.currentConfig?.senderCompID.orEmpty(),
                    qualifier = sess.sessionQualifier,
                )
            }
    }

    /** Acceptors bind one listen port, so they are one session however the field reads. */
    private fun laneCount(profile: FixConnectionProfile): Int =
        if (profile.config.connectionType == FixConnectionConfig.ConnectionType.INITIATOR) {
            profile.config.sessionCount.coerceAtLeast(1)
        } else {
            1
        }

    /**
     * One slot of a profile's group, created if absent and dialled. [slot] is 0 for a single-session
     * profile — the same convention [FixMessageSession.profileSlot] uses, and the same titling the app's
     * `createMissingSessions` applies, so a record made headless names its lanes as the window would.
     */
    /**
     * **One session of a profile, for a load run that only listens on it**: slot 1 of a group, or the single
     * session. Waits for logon and answers null when it did not arrive, so a listener that never logged on is
     * reported rather than silently absent from the matching.
     */
    fun openSingle(
        profile: FixConnectionProfile,
        timeoutMs: Long = LANE_LOGON_TIMEOUT_MS,
        configure: (FixConnectionConfig) -> FixConnectionConfig = { it },
    ): FixMessageSession? {
        val slots = laneCount(profile)
        val session = openSlot(profile, slot = if (slots > 1) 1 else 0, slots = slots, configure = configure)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && session.connectionState.value != FixConnectionState.LOGGED_ON) {
            sleep(LANE_POLL_MS)
        }
        return session.takeIf { it.connectionState.value == FixConnectionState.LOGGED_ON }
    }

    private fun openSlot(
        profile: FixConnectionProfile,
        slot: Int,
        slots: Int,
        configure: (FixConnectionConfig) -> FixConnectionConfig = { it },
    ): FixMessageSession {
        val title = if (slots > 1) "${profile.name} [$slot]" else profile.name
        sessions[title]?.let {
            onLog("reconnecting session '$title'")
            it.reconnect()
            return it
        }
        val config = configure(if (slots > 1) SessionIdentityResolver.resolve(profile.config, slot, slots) else profile.config)
        val sess =
            FixMessageSession(
                title = title,
                sessionQualifier = config.sessionQualifier,
                profileSlot = if (slots > 1) slot else 0,
                onError = { onLog("error: $it") },
            )
        sessions[title] = sess
        onLog("connecting '$title'" + if (slots > 1) " as ${config.senderCompID}" else "")
        sess.connect(config, appSettings, dictionary)
        return sess
    }

    /** Closes every session this run opened. Best-effort: teardown must not fail a passing run. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun disconnectAll() {
        sessions.values.forEach {
            try {
                it.disconnect()
            } catch (e: Exception) {
                onLog("disconnect of '${it.title}' failed: ${e.message}")
            }
        }
    }

    /**
     * The session a step means: the one it names, or — when it names none — the only one open.
     *
     * "The only one open" is deliberately not "the first one open". With several sessions up, a step
     * that names none is ambiguous, and picking one would send an order to whichever venue happened to
     * be created first. Answering null instead makes the runner say so.
     */
    private fun resolve(session: String?): FixMessageSession? =
        if (session == null) sessions.values.singleOrNull() else sessions[session]

    private fun fixMessages(session: String?): List<FixMessage> =
        resolve(session)?.messages?.value?.filterIsInstance<FixMessage>() ?: emptyList()

    private fun byType(msgs: List<FixMessage>, incoming: Boolean): Map<String, FixMessage> {
        val want = if (incoming) FixMessage.Direction.INCOMING else FixMessage.Direction.OUTGOING
        return msgs.filter { it.direction == want }.associateBy { it.messageType }
    }

    companion object {
        /** The `[n]` a multi-session slot's title ends with — the app's naming, read back. */
        private val SLOT_SUFFIX = Regex("""\s\[(\d+)]$""")
        private const val LANE_LOGON_TIMEOUT_MS = 10_000L
        private const val LANE_POLL_MS = 100L
    }
}
