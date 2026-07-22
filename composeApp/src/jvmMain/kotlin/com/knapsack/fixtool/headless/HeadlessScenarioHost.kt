package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.ConnectAttempt
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.FixMessageView
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioHost
import com.knapsack.fixtool.service.SendResult
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
                val sess = FixMessageSession(title = profile.name, onError = { onLog("error: $it") })
                sessions[profile.name] = sess
                onLog("connecting '${profile.name}'")
                sess.connect(profile.config, appSettings, dictionary)
                ConnectAttempt.Started(profile.name)
            }
        }
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
}
