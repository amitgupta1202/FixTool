package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.ConnectAttempt
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.FixMessageView
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioHost
import com.knapsack.fixtool.service.SendResult
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * The [ScenarioHost] that bridges a [ScenarioRunner][com.knapsack.fixtool.service.ScenarioRunner] to
 * the live [FixMessageViewModel] — used by both the in-app "Run scenario" action and the
 * `fixtool_run_scenario` control endpoint, so there is a single implementation. ViewModel state and
 * every mutation are marshalled onto the Swing EDT (Compose state is EDT-bound).
 *
 * A session's message log is the exception: [FixMessageSession.messages] and `connectionState` are
 * [kotlinx.coroutines.flow.StateFlow]s, not Compose state, so reading `.value` is thread-safe and
 * needs no EDT hop. That matters because the runner polls these on a 100ms loop — a 30-second
 * `expect` step is ~300 round-trips, and each one used to drag a full O(N) `filterIsInstance` copy
 * of the session's message list onto the EDT, freezing the UI for the length of the scenario.
 */
class ViewModelScenarioHost(private val viewModel: FixMessageViewModel) : ScenarioHost {
    override fun resolve(raw: String, scope: MutableMap<String, String>, session: String?): String {
        val sess = resolveSession(session)
        val msgs = if (sess == null) emptyList() else sess.fixMessages()
        return FixMessageTemplate.evaluate(
            raw,
            byType(msgs, incoming = true),
            byType(msgs, incoming = false),
            scope,
            onEdt { viewModel.dictionary },
        )
    }

    override fun send(raw: String, session: String?): Boolean =
        onEdt {
            // Send to the session directly — never via the active tab, so a multi-session run
            // doesn't flip what the user is looking at (or race their own selection).
            val sess = resolveSession(session) ?: return@onEdt false
            val result = sess.sendFixMessage(raw, viewModel.dictionary)
            result !is SendResult.Failed
        }

    override fun messages(session: String?): List<FixMessage> {
        val sess = resolveSession(session) ?: return emptyList()
        return sess.fixMessages()
    }

    override fun discarded(session: String?): Long = resolveSession(session)?.discarded?.value ?: 0

    override fun connectionState(session: String?): String? {
        val sess = resolveSession(session) ?: return null
        return sess.connectionState.value.name
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun referenceResolver(session: String?, scope: Map<String, String>): (String) -> String? {
        val sess = resolveSession(session)
        val msgs = if (sess == null) emptyList() else sess.fixMessages()
        val incoming = byType(msgs, incoming = true)
        val outgoing = byType(msgs, incoming = false)
        val dictionary = onEdt { viewModel.dictionary }
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
        val sess = resolveSession(session) ?: return false
        onEdt { sess.clearMessages() }
        return true
    }

    override fun resetSeqNum(session: String?, sender: Int?, target: Int?): Boolean {
        val sess = resolveSession(session) ?: return false
        onEdt { sess.resetSequenceNumbers(sender, target) }
        return true
    }

    override fun connectSession(session: String?): ConnectAttempt =
        onEdt {
            val sess = resolveSession(session)
            if (sess != null) {
                // The session exists but is down: reconnect through its own profile, which re-resolves
                // config edits the same way the Connect button does.
                val profile = viewModel.profileForSession(sess)
                    ?: return@onEdt ConnectAttempt.Failed("session '${sess.title}' has no saved connection profile to reconnect from")
                viewModel.connectProfile(profile.id, profile)
                ConnectAttempt.Started(profile.name)
            } else {
                val key = session
                    ?: return@onEdt ConnectAttempt.Failed("no session exists and the step names none, so no profile can be looked up")
                // A multi-session slot is titled "Name [n]"; connecting profile "Name" creates every slot.
                val base = key.replace(SLOT_SUFFIX, "")
                val matches =
                    viewModel.connectionProfiles
                        .filter { it.id == key || it.name == key || it.name == base }
                        .distinctBy { it.id }
                when {
                    matches.isEmpty() -> ConnectAttempt.Failed("no saved connection profile named '$key'")
                    matches.size > 1 ->
                        ConnectAttempt.Failed("${matches.size} saved profiles answer to '$key' — connect the right one manually")
                    else -> {
                        val profile = matches.single()
                        viewModel.connectProfile(profile.id, profile)
                        ConnectAttempt.Started(profile.name)
                    }
                }
            }
        }

    /**
     * The session's message log, off the EDT. `messages` is a StateFlow, so `.value` is a
     * thread-safe read of an immutable snapshot — the copy costs the caller's thread, not the UI's.
     */
    private fun FixMessageSession.fixMessages(): List<FixMessage> = messages.value.filterIsInstance<FixMessage>()

    private fun byType(msgs: List<FixMessage>, incoming: Boolean): Map<String, FixMessage> {
        val want = if (incoming) FixMessage.Direction.INCOMING else FixMessage.Direction.OUTGOING
        return msgs.filter { it.direction == want }.associateBy { it.messageType }
    }

    /**
     * Session lookup, off the EDT. `viewModel.sessions` is a Compose `SnapshotStateList`, and the
     * snapshot system serves reads from any thread against a consistent snapshot — so resolving a
     * session needs no EDT hop either. Keeping one here would have left the runner's 100ms poll
     * bouncing off the EDT on every iteration even after the expensive copy moved off it.
     *
     * Mutations are a different matter and still go through [onEdt].
     */
    private fun resolveSession(key: String?): FixMessageSession? {
        val list = viewModel.sessions
        return when {
            key == null -> list.firstOrNull()
            key.toIntOrNull() != null -> list.getOrNull(key.toInt())
            else -> list.firstOrNull { it.id == key || it.title == key }
        }
    }

    private companion object {
        /** The " [n]" tail a multi-session slot's title wears over its profile's name. */
        val SLOT_SUFFIX = Regex("""\s\[\d+]$""")
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val holder = AtomicReference<Any?>()
        SwingUtilities.invokeAndWait {
            holder.set(runCatching(block).fold(onSuccess = { it as Any? }, onFailure = { it }))
        }
        return when (val result = holder.get()) {
            is Throwable -> throw result
            else ->
                @Suppress("UNCHECKED_CAST")
                (result as T)
        }
    }
}
