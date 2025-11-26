package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Test
import quickfix.Message
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lightweight sanity/perf check for updateMessageMaps.
 *
 * The real goal is to make sure we no longer rescan large message histories
 * and that the method still produces the expected last-per-type results.
 */
class UpdateMessageMapsBenchmarkTest {
    @Test
    fun updateMessageMaps_smokeAndTiming() {
        val viewModel = FixMessageViewModel()

        // Access session list for seeding large histories
        val sessionsField = FixMessageViewModel::class.java.getDeclaredField("_sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(viewModel) as MutableList<FixMessageSession>

        val messageTypes = listOf("D", "8", "9", "AE", "0")
        val quickFixMessage = Message() // Lightweight placeholder

        repeat(3) { sessionIndex ->
            val session = FixMessageSession(title = "S$sessionIndex")
            // Seed a few thousand mixed messages
            repeat(2000) { msgIndex ->
                val type = messageTypes[msgIndex % messageTypes.size]
                val direction =
                    if (msgIndex % 2 == 0) FixMessage.Direction.INCOMING else FixMessage.Direction.OUTGOING
                val raw = "8=FIX.4.4|35=$type|49=SENDER|56=TARGET|34=$msgIndex|"
                session.addMessage(
                    FixMessage(
                        timestamp = java.time.LocalDateTime.now(),
                        direction = direction,
                        rawMessage = raw,
                        quickfixMessage = quickFixMessage,
                    ),
                )
            }
            sessions.add(session)
        }

        // Warm-up to avoid first-run costs in timing
        viewModel.updateMessageMaps()

        val elapsedMs =
            measureTimeMillis {
                viewModel.updateMessageMaps()
            }

        val incomingSize = viewModel.incomingMessagesByType.size
        val outgoingSize = viewModel.outgoingMessagesByType.size

        println("updateMessageMaps() took ${elapsedMs}ms for ${sessions.size} sessions with ${messageTypes.size} types")
        println("Incoming types cached: $incomingSize, outgoing types cached: $outgoingSize")

        // Should have a latest entry for every message type in each direction
        assertEquals(messageTypes.size, incomingSize)
        assertEquals(messageTypes.size, outgoingSize)
        assertTrue(elapsedMs < 1000, "updateMessageMaps should stay well under 1s for moderate histories")

        // Clean up to stop polling coroutines in FixMessageSession
        sessions.forEach { it.destroy() }
    }
}
