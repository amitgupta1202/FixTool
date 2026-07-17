package com.knapsack.fixtool.service

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionMappingServiceTest {
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File.createTempFile("test_fixtool_mappings", "")
        testDir.delete()
        testDir.mkdirs()
    }

    @After
    fun cleanup() {
        testDir.deleteRecursively()
    }

    private fun service() = SessionMappingService(customPath = File(testDir, "session_mappings.json").absolutePath)

    private val qa = SessionMapping(id = "m1", name = "QA", map = mapOf("dev-buyside" to "qa-buyside", "dev-sellside" to "qa-sellside"))
    private val uat = SessionMapping(id = "m2", name = "UAT", map = mapOf("dev-buyside" to "uat-buyside"))

    @Test
    fun `mappings round-trip through disk`() {
        val svc = service()
        svc.saveMapping(qa)
        svc.saveMapping(uat)
        // A fresh instance reading the same file — what an app restart sees.
        assertEquals(listOf(qa, uat), service().loadMappings())
    }

    @Test
    fun `saving an existing id updates in place instead of duplicating`() {
        val svc = service()
        svc.saveMapping(qa)
        val renamed = qa.copy(name = "QA2")
        assertEquals(listOf(renamed), svc.saveMapping(renamed))
    }

    @Test
    fun `deleting a mapping also forgets it as any scenario's last-used`() {
        val svc = service()
        svc.saveMapping(qa)
        svc.saveMapping(uat)
        svc.noteUsed("scenario-1", qa.id)
        svc.noteUsed("scenario-2", uat.id)
        svc.deleteMapping(qa.id)
        val reread = service()
        assertEquals(listOf(uat), reread.loadMappings())
        // A last-used entry pointing at a deleted mapping would preselect nothing forever.
        assertNull(reread.loadLastUsed()["scenario-1"])
        assertEquals(uat.id, reread.loadLastUsed()["scenario-2"])
    }

    @Test
    fun `last-used is per scenario and survives re-noting`() {
        val svc = service()
        svc.saveMapping(qa)
        svc.saveMapping(uat)
        svc.noteUsed("s", qa.id)
        assertEquals(mapOf("s" to uat.id), svc.noteUsed("s", uat.id))
    }
}
