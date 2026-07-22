package com.knapsack.fixtool.integration

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Ports for tests that stand up a real listener, from a range the OS will not hand to anyone else.**
 *
 * Every integration test here used to allocate with `ServerSocket(0).use { it.localPort }` — open an
 * ephemeral port, **close it**, then give the number to QuickFIX/J to bind a moment later. That is a
 * race with the whole machine, and on this suite it was a losing one:
 *
 *  - `ServerSocket(0)` allocates from the OS ephemeral range (49152–65535 on macOS, similar on Linux);
 *  - that is the *same* range outbound sockets draw their source port from — and this suite opens
 *    plenty, since every QuickFIX initiator and every test HTTP client is an outbound socket;
 *  - so between the close and the bind, one of those connections could be assigned the very port the
 *    acceptor was about to take. The bind then failed, no acceptor listened, and the test died at
 *    *"client should log on to the FixTool acceptor"* — pointing at logon, which was never the problem.
 *
 * Which is why it presented as *flakiness with no owner*: a different test failed on each run, because
 * which one lost depended on who happened to be dialling out at that instant. Three separate tests were
 * observed failing across three full-suite runs, each passing in isolation.
 *
 * Two changes fix it. The range is **below** the ephemeral one, so no outbound socket can ever be
 * assigned a port this hands out; and a JVM-wide counter only ever moves forward, so no two callers in
 * one run get the same number even if the first has not bound yet. The bindability check that remains is
 * a courtesy against software outside this JVM, and its race is harmless now that nothing inside can
 * take the port.
 */
object TestPorts {
    /** Above FixTool's demo server (19876), far below the ephemeral floor (49152). */
    private const val FIRST = 21_000
    private const val LAST = 28_999

    private val next = AtomicInteger(FIRST)

    /** A port no other caller in this JVM run has been given, and that nothing is currently listening on. */
    fun free(): Int {
        repeat(LAST - FIRST + 1) {
            val candidate = next.getAndUpdate { if (it >= LAST) FIRST else it + 1 }
            if (isBindable(candidate)) return candidate
        }
        error("no free port in $FIRST..$LAST — something is listening on the whole test range")
    }

    private fun isBindable(port: Int): Boolean =
        try {
            ServerSocket(port).use { true }
        } catch (e: IOException) {
            false
        }
}
