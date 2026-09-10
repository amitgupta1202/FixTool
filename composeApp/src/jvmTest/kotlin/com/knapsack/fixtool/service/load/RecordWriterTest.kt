package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.service.load.LoadFixtures.burstReport
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The busiest thing in a set**: one thread, and always the latest record there is.
 *
 * A phase publishes about four times a second while it is issuing and once a poll while it is settling,
 * and a set multiplies that by the number of phases it is running. Every one of those writes used to
 * happen on the phase's own thread, in the middle of its schedule. So a phase hands a finished record
 * over and goes back to its schedule, and [LoadSetRunner.RecordWriter] writes whichever record is the
 * latest when it gets there.
 *
 * That is worth having only if three things hold, and none of them was pinned. A tick nobody had time
 * for is **replaced** and not queued, or a slow disk turns into a queue of stale pictures that outlives
 * the set. The last record offered is written **however busy the writer was** when the set ended, which
 * is what leaves a set that threw with its last picture on disk. And nothing lands **on top of** the
 * record the set finished with.
 *
 * The writer is handed the write rather than a store here, which is what lets a test give it one that
 * takes its time and count what it did.
 */
class RecordWriterTest {
    /** A record with [at] in it, because what these tests follow is which record went where. */
    private fun record(at: Long): LoadRecord = LoadRecord.of(burstReport().copy(startedAt = at))

    /**
     * **A tick nobody had time for is replaced, not replayed.**
     *
     * The writer is held inside its first write while nine more records are offered behind it. Only the
     * newest of the nine is a picture of anything: the eight before it were true for a quarter of a
     * second each and every reader of the file re-reads it anyway. A writer that queued them would
     * spend the rest of the set writing history, and the file would hold an older picture than one it
     * had already been given.
     */
    @Test
    fun `the record written is the latest one offered, and the ticks behind it are replaced`() {
        val written = CopyOnWriteArrayList<Long>()
        val insideFirstWrite = CountDownLatch(1)
        val letTheFirstWriteFinish = CountDownLatch(1)
        val writer =
            LoadSetRunner.RecordWriter({ record ->
                written += record.startedAt
                if (written.size == 1) {
                    insideFirstWrite.countDown()
                    letTheFirstWriteFinish.await()
                }
            })

        writer.offer(record(1))
        assertTrue(insideFirstWrite.await(WAIT_S, TimeUnit.SECONDS), "the writer never picked the first record up")
        (2..10).forEach { writer.offer(record(it.toLong())) }
        letTheFirstWriteFinish.countDown()
        writer.close()

        assertEquals(listOf(1L, 10L), written.toList(), "the ticks the writer was too busy for were replayed rather than replaced")
    }

    /**
     * **The close waits for the write it found in progress**, which is what makes the record the set
     * writes last the record left on disk. Returning while a write was still going would let the set's
     * own final write be overtaken by the tick before it.
     */
    @Test
    fun `close does not return until the write it found in progress has finished`() {
        val written = CopyOnWriteArrayList<Long>()
        val insideTheWrite = CountDownLatch(1)
        val writer =
            LoadSetRunner.RecordWriter({ record ->
                insideTheWrite.countDown()
                Thread.sleep(SLOW_WRITE_MS)
                written += record.startedAt
            })

        writer.offer(record(1))
        assertTrue(insideTheWrite.await(WAIT_S, TimeUnit.SECONDS), "the writer never picked the record up")
        writer.close()

        assertEquals(listOf(1L), written.toList(), "the close returned while the writer was still writing")
    }

    /**
     * **A record offered while the writer was busy is written by the close itself.**
     *
     * The writer is inside a write, the last record of the set is offered behind it, and the close
     * lands while both are true. The writer's own loop sees the close first and ends without picking
     * that record up, so the only thing left to write it is the close. This is the path a set that
     * threw takes, and without it its last picture is the one nobody ever wrote.
     */
    @Test
    fun `a record offered while the writer was busy is still written when the writer closes`() {
        val written = CopyOnWriteArrayList<Long>()
        val insideFirstWrite = CountDownLatch(1)
        val letTheFirstWriteFinish = CountDownLatch(1)
        val writer =
            LoadSetRunner.RecordWriter({ record ->
                written += record.startedAt
                if (written.size == 1) {
                    insideFirstWrite.countDown()
                    letTheFirstWriteFinish.await()
                }
            })

        writer.offer(record(1))
        assertTrue(insideFirstWrite.await(WAIT_S, TimeUnit.SECONDS), "the writer never picked the first record up")
        writer.offer(record(2))
        val closing = Thread({ writer.close() }, "closing-the-record-writer").apply { start() }
        awaitJoining(closing)
        letTheFirstWriteFinish.countDown()
        closing.join()

        assertEquals(listOf(1L, 2L), written.toList(), "the record the writer never picked up was never written")
    }

    /**
     * **Nothing lands on top of the record the set finished with.** The set closes the writer, writes
     * its own last record, and closes again in the teardown that runs whatever happened. A record
     * offered anywhere in there is an older picture, and the second close used to write it.
     */
    @Test
    fun `a record offered after the close is never written on top of the last one`() {
        val written = CopyOnWriteArrayList<Long>()
        val writer = LoadSetRunner.RecordWriter({ record -> written += record.startedAt })

        writer.offer(record(1))
        writer.close()
        writer.offer(record(2))
        writer.close()

        assertEquals(listOf(1L), written.toList(), "a record offered after the close reached the disk")
    }

    /** A set that keeps no record hands the writer no write, and offering to that one is a no-op. */
    @Test
    fun `a writer with nothing to write starts no thread and drops what it is offered`() {
        val writer = LoadSetRunner.RecordWriter(null)

        writer.offer(record(1))
        writer.close()

        assertTrue(
            Thread.getAllStackTraces().keys.none { it.name == "fixtool-load-record" },
            "a set that keeps no record still started a thread to write one with",
        )
    }

    /**
     * Waits until [thread] is inside a join, in real time, which for the close is after it has said that
     * nothing more is coming. A hung test says nothing, so this one says what it was waiting for.
     */
    private fun awaitJoining(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (thread.state != Thread.State.WAITING) {
            check(System.nanoTime() < deadline) { "waited ${WAIT_S}s for the close to reach its join, in ${thread.state}" }
            Thread.sleep(1)
        }
    }

    private companion object {
        /** How long a handshake waits before it says so, in real seconds. */
        const val WAIT_S = 10L

        /** A write slow enough that a close returning without waiting for it would be plain to see. */
        const val SLOW_WRITE_MS = 200L
    }
}
