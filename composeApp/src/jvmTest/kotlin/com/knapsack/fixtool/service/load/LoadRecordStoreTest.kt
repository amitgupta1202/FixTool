package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadPhase
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.service.load.LoadFixtures.burstReport
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
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
                unmatched = listOf(StampMatcher.Unmatched("ORD-1", 3, 1L, "8=FIX.4.435=D11=ORD-1")),
                specimens = listOf(StampMatcher.Specimen("8=FIX.4.4|35=D|11=A|", "8=FIX.4.4|35=8|11=A|", 14)),
            ),
        )

        assertEquals(report.copy(id = id), store.read(id))
        assertEquals(listOf("8=FIX.4.4|35=D|11=ORD-1|"), store.unmatchedWire(id), "SOH becomes the pipe the tool shows")
        assertEquals(listOf("8=FIX.4.4|35=D|11=A|", "8=FIX.4.4|35=8|11=A|"), File(store.directoryFor(id), LoadRecordStore.SPECIMENS_FILE).readLines())
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

    @Test
    fun `a record that says running with no process behind it reads as stopped, once`() {
        val store = LoadRecordStore(dir.absolutePath, isLive = { false })
        val running = burstReport(status = LoadStatus.RUNNING).copy(phase = LoadPhase.SETTLING, finishedAt = null, settleLeftMs = 4_000)
        store.write(running)

        val healed = assertNotNull(store.read(running.id))

        assertEquals(LoadStatus.STOPPED, healed.status)
        assertEquals(LoadPhase.DONE, healed.phase)
        assertNull(healed.settleLeftMs)
        assertNotNull(healed.finishedAt)
        assertEquals(1, healed.verdict.exitCode)
        assertEquals(healed, LoadRecordStore(dir.absolutePath, isLive = { true }).read(running.id), "healed on disk, not only in the answer")
    }
}
