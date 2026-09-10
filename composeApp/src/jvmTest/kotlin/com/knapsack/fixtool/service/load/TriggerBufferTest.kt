package com.knapsack.fixtool.service.load

import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The buffer a reactive phase will read from**, pinned before anything posts to it.
 *
 * Nothing in the product fires a trigger yet. What these say is the part a set already depends on: a post
 * from a stamp thread never blocks and never throws, a close wakes a reader that is waiting rather than
 * leaving it there, and the first reason a buffer was closed for is the reason it keeps.
 */
class TriggerBufferTest {
    @Test
    fun `what is posted comes back in the order it was posted, and then the end`() {
        val buffer = TriggerBuffer(phase = 3)

        buffer.post(1)
        buffer.post(7)
        buffer.close("phase 2 has finished, so nothing more will fire this one")

        assertEquals(1, buffer.next())
        assertEquals(7, buffer.next())
        assertNull(buffer.next(), "the end of the buffer, and it stays the answer")
        assertNull(buffer.next())
    }

    @Test
    fun `a reader waiting on an empty buffer is woken by the close`() {
        val buffer = TriggerBuffer(phase = 2)
        val read = CopyOnWriteArrayList<Int?>()
        val waiting = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread({
            waiting.countDown()
            read += buffer.next()
            done.countDown()
        }, "trigger-buffer-reader").start()

        assertTrue(waiting.await(WAIT_S, TimeUnit.SECONDS))
        buffer.close("the set ended, so nothing more will fire this one")

        assertTrue(done.await(WAIT_S, TimeUnit.SECONDS), "the reader was left waiting for a trigger that cannot come")
        assertEquals(listOf<Int?>(null), read)
    }

    @Test
    fun `the first reason a buffer was closed for is the reason it keeps`() {
        val buffer = TriggerBuffer(phase = 3)

        buffer.close("phase 2 did not run, so nothing would have fired this one")
        buffer.close("the set ended, so nothing more will fire this one")

        assertTrue(buffer.closed)
        assertEquals("phase 2 did not run, so nothing would have fired this one", buffer.note)
    }

    /**
     * The poster is a MINA processor thread shared by every session on it, and `SocketStampFilter` swallows
     * whatever comes back out of a stamp listener. So a post after the close has to be dropped rather than
     * refused, or the trigger vanishes with nothing said about it and the processor takes the throw.
     */
    @Test
    fun `a post after the close is dropped rather than thrown at the stamp thread`() {
        val buffer = TriggerBuffer(phase = 2)
        buffer.close("phase 1 has finished, so nothing more will fire this one")

        buffer.post(4)

        assertEquals(0, buffer.waiting)
        assertNull(buffer.next())
    }

    /** Unbounded, so a burst of matches on a processor thread is never a trigger the buffer quietly drops. */
    @Test
    fun `a burst of posts is all there, however many of them there are`() {
        val buffer = TriggerBuffer(phase = 2)

        (1..POSTS).forEach { buffer.post(it) }

        assertEquals(POSTS, buffer.waiting)
        assertEquals((1..POSTS).toList(), (1..POSTS).map { buffer.next() })
    }

    private companion object {
        const val WAIT_S = 5L

        /** More than any bounded queue this would have been given, so a drop would show. */
        const val POSTS = 10_000
    }
}
