package com.knapsack.fixtool.integration

import androidx.compose.runtime.mutableStateListOf
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [settled] over the exact shape that broke the v1.16.0 release build: a `SnapshotStateList` being
 * appended to and cleared by one thread while another iterates it.
 *
 * Without this, the snapshot in [settled] reads like ceremony somebody could unwrap — and the cost of
 * unwrapping it is a red release build nobody can reproduce locally, which is how it was found.
 */
class AwaitTest {
    /** A writer doing to a list what a venue does to the session list: growing it, and taking it away. */
    private fun churning(block: (list: MutableList<String>) -> Unit) {
        val sessions = mutableStateListOf<String>()
        val stop = AtomicBoolean(false)
        val writer =
            thread {
                var n = 0
                while (!stop.get()) {
                    sessions.add("FX Demo Venue <- CLIENT${n++}")
                    if (sessions.size > 500) sessions.clear()
                }
            }
        try {
            block(sessions)
        } finally {
            stop.set(true)
            writer.join()
        }
    }

    @Test
    fun `a read that did not tear is passed straight through`() {
        assertTrue(settled { true })
        assertFalse(settled { false })
    }

    /**
     * The property the snapshot buys: within one evaluation the list does not move, so a predicate that
     * reads it more than once cannot contradict itself. Two reads of a raw `SnapshotStateList` from this
     * thread can disagree; two reads inside one snapshot cannot.
     */
    @Test
    fun `one evaluation sees one view of a list another thread is changing`() {
        churning { sessions ->
            repeat(5_000) {
                assertTrue(
                    settled {
                        val first = sessions.size
                        val copied = sessions.toList().size
                        val second = sessions.size
                        first == second && second == copied
                    },
                    "two reads inside one evaluation disagreed, so the view was not stable",
                )
            }
        }
    }

    /**
     * The hazard itself, not a stand-in for it. The raw read is counted rather than asserted on,
     * because a scheduler that never interleaves is unlucky rather than wrong — but the wrapped read
     * letting one out is always wrong, and that is what is asserted.
     *
     * The list moving underneath a reader surfaces as `ConcurrentModificationException` when the
     * modification count changes mid-iteration and as `IndexOutOfBoundsException` when it shrinks; CI
     * happened to hit the first. Both are counted, because both are the same event.
     */
    @Test
    fun `settled lets nothing escape while another thread grows the list`() {
        var rawTears = 0
        var escaped = 0

        churning { sessions ->
            repeat(20_000) {
                try {
                    sessions.any { it == "a title that is never added" }
                } catch (e: RuntimeException) {
                    if (e is ConcurrentModificationException || e is IndexOutOfBoundsException) rawTears++ else throw e
                }
                try {
                    settled { sessions.any { it == "a title that is never added" } }
                } catch (e: RuntimeException) {
                    if (e is ConcurrentModificationException || e is IndexOutOfBoundsException) escaped++ else throw e
                }
            }
        }

        assertEquals(0, escaped, "a torn read escaped settled, which is the failure this exists to stop")
        println("AwaitTest: raw reads that tore = $rawTears (0 means this run never interleaved)")
    }

    @Test
    fun `a predicate that fails for a real reason is not swallowed`() {
        var thrown = false
        try {
            settled { error("a real failure, not a torn read") }
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown, "settled must make a read stable, not make failures disappear")
    }
}
