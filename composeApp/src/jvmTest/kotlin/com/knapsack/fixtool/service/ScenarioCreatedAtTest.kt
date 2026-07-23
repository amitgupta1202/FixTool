package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `createdAt` is minted once — at the first save of a genuinely new scenario — and never again. That single
 * rule is the whole migration contract behind the rail's "creation order" sort: it lets the field exist
 * without rewriting one file that predates it, and without re-stamping a birth time on every edit.
 */
class ScenarioCreatedAtTest {
    private lateinit var dir: File

    @Before
    fun setup() {
        dir = File.createTempFile("fixtool-createdat", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun scenario(id: String, name: String) =
        Scenario(id = id, name = name, steps = listOf(ScenarioStep.Send("35=D|11=x|")))

    @Test
    fun `first save mints createdAt for a new scenario`() {
        val svc = ScenarioService(customDir = dir.absolutePath, clock = { 1_700_000_000_000L })
        val fresh = scenario("c1", "new one")
        assertNull(fresh.createdAt, "a fresh scenario has no birth time until it is saved")
        assertTrue(svc.save(fresh))
        assertEquals(1_700_000_000_000L, svc.load("c1")!!.createdAt)
    }

    @Test
    fun `editing a scenario preserves createdAt, never re-mints it`() {
        var now = 1_700_000_000_000L
        val svc = ScenarioService(customDir = dir.absolutePath, clock = { now })
        assertTrue(svc.save(scenario("c2", "v1")))
        val loaded = svc.load("c2")!!
        now = 1_800_000_000_000L // the clock moves on between the create and the edit
        assertTrue(svc.save(loaded.copy(name = "v2"))) // an edit — the file already exists
        assertEquals(1_700_000_000_000L, svc.load("c2")!!.createdAt, "an edit must not re-stamp the birth time")
    }

    @Test
    fun `a legacy file with no createdAt is never rewritten to add one`() {
        val svc = ScenarioService(customDir = dir.absolutePath, clock = { 9L })
        // A file authored before the field existed: written straight to disk, with no createdAt key.
        File(dir, "legacy.json").writeText(ScenarioCodec.toJson(scenario("leg", "legacy")).toString())

        val loaded = svc.load("leg")!!
        assertNull(loaded.createdAt)

        assertTrue(svc.save(loaded.copy(name = "legacy edited"))) // the file exists, so save must not stamp
        assertNull(svc.load("leg")!!.createdAt, "editing a pre-field file must not mint a birth time")

        val onDisk = dir.listFiles()!!.first { it.readText().contains("\"leg\"") }.readText()
        assertFalse(onDisk.contains("createdAt"), "the key was never added to the legacy file: $onDisk")
    }
}
