package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.scenario.Lane
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A reactive phase's renderer, and what it does while nothing is happening.**
 *
 * The rest of it is covered where it is used, in [ReactivePhaseTest], because what a renderer hands back
 * is only interesting as the messages a phase sent. What cannot be seen from there is the waiting: a
 * reactive phase can sit between two triggers for the length of its trigger's settle window, and a minute
 * of silence looks exactly like a minute of being stuck.
 */
class RenderOnTriggerTest {
    private val compiled =
        CompiledTemplate.compile(LoadTemplate("Quote", listOf(35 to "AJ", 117 to "Q-\${messageIndex}")))

    private fun prototype(slot: Int) =
        compiled.prepare(Lane(slot, "LOADGEN [$slot]", "LG0$slot", ""), emptyMap(), FixDictionaryAdapter.createDefault()) { it }

    /**
     * **A phase waiting for a trigger publishes, rather than going quiet with stale counts.**
     *
     * A phase emits progress from its issue path, and a reactive phase's issue path only runs when a
     * trigger lands. So the live document showed it ISSUING with whatever counts it had when the last
     * message went, for as long as its trigger took, and its replies landing in the meantime were
     * invisible. Every empty poll of the merged queue is the moment to say where it is, and that poll is
     * on the issuing thread, which is what lets the counters it reads stay unsynchronised.
     */
    @Test
    fun `waiting for a trigger is a tick, not a silence`() {
        val buffer = TriggerBuffer(phase = 2, firedBy = "phase 1 · Ask for a quote")
        val idle = AtomicInteger()
        val handed = ArrayBlockingQueue<Pacer.Ready>(4)

        RenderOnTrigger(
            prototypes = listOf(prototype(1)),
            buffer = buffer,
            indexFrom = 1,
            requested = 2,
            missing = "reply to phase 1 · Ask for a quote",
            cancelled = { false },
            onIdle = { idle.incrementAndGet() },
        ).use { source ->
            val issuing = Thread({ source.next()?.let { handed.offer(it) } }, "test-issuing")
            issuing.start()
            // Real milliseconds, because the poll interval is real: the clock a set runs on is virtual and
            // a thread waiting on a queue is not something virtual time can move.
            awaitAtLeast(TICKS, idle::get)

            buffer.post(1)

            val ready = assertNotNull(handed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the posted index was never handed over")
            assertEquals(1, ready.rendered.index)
            assertTrue(ready.fired, "an index a trigger released is a message that happened")
            assertTrue(idle.get() >= TICKS, "the wait said nothing at all")
            issuing.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }
    }

    /** Waits for [want], and says what it was waiting for rather than hanging without a word. */
    private fun awaitAtLeast(want: Int, have: () -> Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (have() < want) {
            check(System.nanoTime() < deadline) { "waited ${TIMEOUT_SECONDS}s for $want idle ticks and saw ${have()}" }
            Thread.sleep(1)
        }
    }

    private companion object {
        /** Enough polls to be a rhythm rather than an accident. The interval is 20ms, so this is 60ms. */
        const val TICKS = 3
        const val TIMEOUT_SECONDS = 5L
    }
}
