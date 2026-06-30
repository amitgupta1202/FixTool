package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
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
 * `fixtool_run_scenario` control endpoint, so there is a single implementation. All ViewModel /
 * session access is marshalled onto the Swing EDT (Compose state is EDT-bound).
 */
class ViewModelScenarioHost(private val viewModel: FixMessageViewModel) : ScenarioHost {
    override fun resolve(raw: String, scope: MutableMap<String, String>, session: String?): String {
        val sess = resolveSession(session)
        val msgs = if (sess == null) emptyList() else onEdt { sess.messages.value.filterIsInstance<FixMessage>() }
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
            val sess = resolveSession(session) ?: return@onEdt false
            val index = viewModel.sessions.indexOf(sess)
            if (index < 0) return@onEdt false
            viewModel.setActiveSession(index)
            val result = viewModel.sendMessage(raw)
            result != null && result !is SendResult.Failed
        }

    override fun messages(session: String?): List<FixMessage> {
        val sess = resolveSession(session) ?: return emptyList()
        return onEdt { sess.messages.value.filterIsInstance<FixMessage>() }
    }

    override fun connectionState(session: String?): String? {
        val sess = resolveSession(session) ?: return null
        return onEdt { sess.connectionState.value.name }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun referenceResolver(session: String?, scope: Map<String, String>): (String) -> String? {
        val sess = resolveSession(session)
        val msgs = if (sess == null) emptyList() else onEdt { sess.messages.value.filterIsInstance<FixMessage>() }
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

    override fun view(message: FixMessage): MessageView = FixMessageView(message)

    override fun clearMessages(session: String?) {
        val sess = resolveSession(session) ?: return
        onEdt { sess.clearMessages() }
    }

    override fun resetSeqNum(session: String?, sender: Int?, target: Int?) {
        val sess = resolveSession(session) ?: return
        onEdt { sess.resetSequenceNumbers(sender, target) }
    }

    private fun byType(msgs: List<FixMessage>, incoming: Boolean): Map<String, FixMessage> {
        val want = if (incoming) FixMessage.Direction.INCOMING else FixMessage.Direction.OUTGOING
        return msgs.filter { it.direction == want }.associateBy { it.messageType }
    }

    private fun resolveSession(key: String?): FixMessageSession? =
        onEdt {
            val list = viewModel.sessions
            when {
                key == null -> list.firstOrNull()
                key.toIntOrNull() != null -> list.getOrNull(key.toInt())
                else -> list.firstOrNull { it.id == key || it.title == key }
            }
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
