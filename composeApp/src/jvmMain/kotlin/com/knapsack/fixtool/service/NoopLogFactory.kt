package com.knapsack.fixtool.service

import quickfix.Log
import quickfix.LogFactory
import quickfix.SessionID

/**
 * **A QuickFIX/J log that writes nothing**, for a session whose profile says
 * [com.knapsack.fixtool.model.FixConnectionConfig.MessageLogKind.NONE].
 *
 * QuickFIX/J ships no null log. Its `ScreenLogFactory` with every switch off still formats each line
 * before deciding not to print it, and its `FileLogFactory` is the per-message append a load run exists
 * to avoid. This does the one thing asked of it, which is nothing, and costs a virtual call per message.
 *
 * Nothing the tool shows depends on this log: the pane holds the messages, the run records hold the
 * bytes, and the socket stamps hold the timings.
 */
object NoopLogFactory : LogFactory {
    override fun create(sessionID: SessionID): Log = NoopLog

    private object NoopLog : Log {
        override fun clear() = Unit

        override fun onIncoming(message: String?) = Unit

        override fun onOutgoing(message: String?) = Unit

        override fun onEvent(text: String?) = Unit

        override fun onErrorEvent(text: String?) = Unit
    }
}
