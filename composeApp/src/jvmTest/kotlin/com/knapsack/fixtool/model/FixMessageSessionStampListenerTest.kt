package com.knapsack.fixtool.model

import com.knapsack.fixtool.service.SocketStamp
import org.junit.Test
import quickfix.SessionID
import kotlin.test.assertEquals

/**
 * The second door onto a session's socket stamps, beside the latency tracker. A load run's matcher listens
 * through it, and closes it in a `finally`, because a forgotten listener is a closure on a hot path for the
 * life of the session.
 */
class FixMessageSessionStampListenerTest {
    @Test
    fun `a listener receives every stamp until its handle is closed`() {
        val session = FixMessageSession(title = "t")
        val seen = mutableListOf<Long>()
        val stamp = { micros: Long -> SocketStamp(SessionID("FIX.4.4", "A", "B"), WireDirection.SEND, "8=FIX.4.4|35=0|", micros) }

        val handle = session.addStampListener { seen += it.micros }
        session.onSocketStamp(stamp(1))
        session.onSocketStamp(stamp(2))
        handle.close()
        session.onSocketStamp(stamp(3))

        assertEquals(listOf(1L, 2L), seen)
        session.destroy()
    }

    @Test
    fun `with no listener and no tracker a stamp costs nothing and breaks nothing`() {
        val session = FixMessageSession(title = "t")
        session.onSocketStamp(SocketStamp(null, WireDirection.RECEIVE, "8=FIX.4.4|35=0|", 5))
        session.destroy()
    }
}
