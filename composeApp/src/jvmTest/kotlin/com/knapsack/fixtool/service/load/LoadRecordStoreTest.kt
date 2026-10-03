package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.SetOutcome
import com.knapsack.fixtool.service.load.LoadFixtures.burstReport
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A load record is `load.json`, the unanswered requests' wire and fifty specimens. Never every message. */
class LoadRecordStoreTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "fixtool-loads-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a report written is the report read back, beside its evidence files`() {
        val store = LoadRecordStore(dir.absolutePath)
        val report = burstReport()
        val id = store.reserve(report.id)

        assertTrue(store.write(report.copy(id = id)))
        assertTrue(
            store.writeEvidence(
                id,
                LoadReport.Evidence.forPhase(1),
                unmatched = listOf(StampMatcher.Unmatched("ORD-1", 3, 1L, "8=FIX.4.435=D11=ORD-1")),
                specimens = listOf(StampMatcher.Specimen("8=FIX.4.4|35=D|11=A|", "8=FIX.4.4|35=8|11=A|", 14)),
            ),
        )

        assertEquals(report.copy(id = id), store.read(id))
        assertEquals(
            listOf("8=FIX.4.4|35=D|11=ORD-1|"),
            store.unmatchedWire(id, LoadReport.Evidence.forPhase(1)),
            "SOH becomes the pipe the tool shows",
        )
        assertEquals(
            listOf("8=FIX.4.4|35=D|11=A|", "8=FIX.4.4|35=8|11=A|"),
            File(store.directoryFor(id), "01-specimens.fix").readLines(),
            "the phase number is in the name, so a set's phases never overwrite each other",
        )
    }

    @Test
    fun `reserve hands out a free id, list is newest first, and prune keeps the newest`() {
        val store = LoadRecordStore(dir.absolutePath)
        val first = store.reserve("run")
        val second = store.reserve("run")
        assertEquals("run", first)
        assertEquals("run-2", second)

        store.write(burstReport().copy(id = first, startedAt = 1_000))
        store.write(burstReport().copy(id = second, startedAt = 2_000))
        store.write(burstReport().copy(id = store.reserve("later"), startedAt = 3_000))

        assertEquals(listOf("later", "run-2", "run"), store.list().map { it.id })
        store.prune(keep = 2)
        assertEquals(listOf("later", "run-2"), store.list().map { it.id })
        assertNull(store.read(first))
    }

    /**
     * **Load runs on disjoint sessions overlap, and retention must not reach into one that is still going.**
     *
     * Ranked by `startedAt` alone, a long run on one profile was the oldest record on disk when two short
     * runs on another finished, so the second one's prune deleted it mid-run, evidence and all, and its next
     * tick recreated a `load.json` with nothing beside it. And once the long run did finish, its own prune
     * would have deleted it at once, being the oldest start.
     */
    @Test
    fun `pruning never deletes a load that is still running, and keeps the one that just finished`() {
        val live = mutableSetOf<String>()
        val store = LoadRecordStore(dir.absolutePath, isLive = { it in live })
        val long =
            burstReport(status = LoadStatus.RUNNING)
                .copy(id = store.reserve("long-on-a"), stage = LoadStage.ISSUING, startedAt = 1, finishedAt = null)
        live += long.id
        assertTrue(store.write(long))

        listOf("short-on-b-1" to 2L, "short-on-b-2" to 4L).forEach { (id, startedAt) ->
            store.write(burstReport().copy(id = store.reserve(id), startedAt = startedAt, finishedAt = startedAt + 1))
            store.prune(keep = 2)
        }

        assertEquals(LoadStatus.RUNNING, store.read(long.id)?.status, "the running load's record is still on disk")

        store.write(long.copy(status = LoadStatus.DONE, stage = LoadStage.DONE, finishedAt = 6))
        live -= long.id
        store.prune(keep = 2)

        assertEquals(
            setOf("long-on-a", "short-on-b-2"),
            store.list().map { it.id }.toSet(),
            "the load that just finished is the newest result, whenever it started",
        )
    }

    @Test
    fun `a record that says running with no process behind it reads as stopped, once`() {
        val store = LoadRecordStore(dir.absolutePath, isLive = { false })
        val running = burstReport(status = LoadStatus.RUNNING).copy(stage = LoadStage.SETTLING, finishedAt = null, settleLeftMs = 4_000)
        store.write(running)

        val healed = assertNotNull(store.read(running.id))

        assertEquals(LoadStatus.STOPPED, healed.status)
        assertEquals(LoadStage.DONE, healed.stage)
        assertNull(healed.settleLeftMs)
        assertNotNull(healed.finishedAt)
        assertEquals(1, healed.verdict.exitCode)
        assertEquals(healed, LoadRecordStore(dir.absolutePath, isLive = { true }).read(running.id), "healed on disk, not only in the answer")
    }

    /**
     * **A set whose process died leaves three different pictures**, and the store heals each on the way out.
     *
     * The phase that was going is STOPPED, and the phases still to come are SKIPPED rather than left
     * PENDING for ever: a live status on a record nobody is running is what the app draws as a spinner that
     * never stops, and `exitCode` would stay absent, which a poller reads as "still going".
     */
    @Test
    fun `a set whose process ended stops the phase that was going and skips the phases still to come`() {
        val store = LoadRecordStore(dir.absolutePath, isLive = { false })
        val done = burstReport(unmatched = 0)
        val running =
            burstReport(unmatched = 0, status = LoadStatus.RUNNING)
                .copy(label = "Hit them", stage = LoadStage.ISSUING, finishedAt = null, settleLeftMs = 8_000)
        val pending =
            done.copy(label = "Pass the rest", status = LoadStatus.PENDING, finishedAt = null, verdict = done.verdict.copy(exitCode = null))
        store.write(
            LoadRecord(
                id = "set-interrupted",
                label = "Round trip",
                startedAt = 1_000,
                finishedAt = null,
                phases = listOf(done, running, pending),
                set = LoadRecord.SetInfo("round-trip", OnFailure.STOP),
                seed = mapOf("run" to "b7f2"),
            ),
        )

        val healed = assertNotNull(store.readRecord("set-interrupted"))

        assertEquals(listOf(LoadStatus.DONE, LoadStatus.STOPPED, LoadStatus.SKIPPED), healed.phases.map { it.status })
        assertEquals("the set's process ended before this phase", healed.phases[2].note)
        assertEquals(LoadStage.DONE, healed.phases[1].stage)
        assertNull(healed.phases[1].settleLeftMs)
        assertEquals(LoadStatus.STOPPED, healed.status)
        assertEquals(SetOutcome.STOPPED, healed.verdict.outcome)
        assertEquals(1, healed.exitCode, "the set is over, and it did not pass")
        assertEquals(
            healed,
            LoadRecordStore(dir.absolutePath, isLive = { true }).readRecord("set-interrupted"),
            "healed on disk, not only in the answer",
        )
    }

    /**
     * **A reader never catches the record half written**, because every reader of it is in this process
     * beside the writer.
     *
     * `writeText` empties the file and then fills it, and a `GET /loads`, the poll behind `awaitLoad` or a
     * document reopening inside that window parses a truncated JSON object and puts a notification in
     * front of somebody about a record nothing is wrong with. A set writes this file on every progress
     * tick of every phase it has running, so the window is as common as the phase count makes it.
     *
     * Written whole to a temp file and renamed, a reader sees the record it saw before or the whole new
     * one. Against the plain write this fails inside the first few reads.
     */
    @Test
    fun `a record read while it is being written is never half of one`() {
        val store = LoadRecordStore(dir.absolutePath)
        val phases = (1..12).map { burstReport(unmatched = 0).copy(label = "Phase $it") }
        val record =
            LoadRecord(
                id = store.reserve("busy"),
                label = "Round trip",
                startedAt = 1_000,
                finishedAt = null,
                phases = phases,
                set = LoadRecord.SetInfo("round-trip", OnFailure.CONTINUE),
            )
        store.write(record)
        val torn = AtomicReference<Throwable?>()
        val reads = AtomicInteger()
        val writing = AtomicBoolean(true)
        val reader =
            Thread({
                while (writing.get()) {
                    try {
                        assertEquals(12, assertNotNull(store.readRecord(record.id)).phases.size)
                        reads.incrementAndGet()
                    } catch (t: Throwable) {
                        torn.compareAndSet(null, t)
                        return@Thread
                    }
                }
            }, "load-record-reader")

        reader.start()
        repeat(WRITES) { n -> store.write(record.copy(startedAt = 1_000L + n)) }
        writing.set(false)
        reader.join()

        assertNull(torn.get(), "a reader parsed a record that was only half on disk: ${torn.get()}")
        assertTrue(reads.get() > 0, "the reader never got a turn, so this proved nothing")
        assertTrue(
            dir.walkTopDown().none { it.name.endsWith(LoadRecordStore.TEMP_SUFFIX) },
            "the temp file is moved into place, never left beside the record",
        )
    }

    /**
     * **Two writers of one record never take each other's temp file.**
     *
     * A record has more than one writer. The run producing it writes a tick at a time, and a reader that
     * finds it RUNNING with nobody running it heals it and writes back what it healed, which two readers
     * can do at once. One temp name between them is one writer's half-written bytes renamed into place by
     * the other, or a rename with nothing left to rename, and the second of those reaches the catch in
     * `write`: the tick is lost and an error goes in the log about a record nothing is wrong with.
     *
     * A name per write leaves nothing to share. What each writer renames into place is its own whole
     * record, and the last one to arrive is the one on disk.
     */
    @Test
    fun `two writers of one record never take each other's temp file`() {
        val store = LoadRecordStore(dir.absolutePath)
        val id = store.reserve("busy")
        val record = burstReport(unmatched = 0).copy(id = id)
        store.write(record)
        val refused = AtomicInteger()
        val torn = AtomicReference<Throwable?>()
        val writing = AtomicBoolean(true)
        val writers =
            (1..WRITERS).map { writer ->
                Thread({
                    repeat(WRITES_EACH) { n ->
                        if (!store.write(record.copy(startedAt = writer * 1_000L + n))) refused.incrementAndGet()
                    }
                }, "load-record-writer-$writer")
            }
        val reader =
            Thread({
                while (writing.get()) {
                    try {
                        assertNotNull(store.read(id))
                    } catch (t: Throwable) {
                        torn.compareAndSet(null, t)
                        return@Thread
                    }
                }
            }, "load-record-reader")

        reader.start()
        writers.forEach { it.start() }
        writers.forEach { it.join() }
        writing.set(false)
        reader.join()

        assertEquals(0, refused.get(), "a write could not put its record in place")
        assertNull(torn.get(), "a reader was handed something that was not one whole record: ${torn.get()}")
        assertNotNull(store.read(id), "and the record left on disk is one whole record")
        assertTrue(
            dir.walkTopDown().none { it.name.endsWith(LoadRecordStore.TEMP_SUFFIX) },
            "a temp file was left beside the record",
        )
    }

    private companion object {
        /** Enough turns that a reader lands inside a write, which against the plain write it does at once. */
        const val WRITES = 300

        /** Enough writers of one record, and turns each, that one shared temp name collides at once. */
        const val WRITERS = 4
        const val WRITES_EACH = 60
    }
}
