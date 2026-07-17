package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * **Scenario files are named for the scenario, not its UUID** — the point of the change, so a git-tracked
 * store reads legibly and a PR shows which scenario moved. The id inside the file stays the identity: a
 * rename rewrites under a new slug and drops the old file, lookups resolve by content, and a store written
 * by the old `<id>.json` scheme is still found and migrates on next save.
 */
class ScenarioServiceNamingTest {
    private lateinit var dir: File
    private lateinit var service: ScenarioService

    @Before
    fun setup() {
        dir = File.createTempFile("fixtool-naming", "").apply { delete(); mkdirs() }
        service = ScenarioService(customDir = dir.absolutePath)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val uuidA = "3f2a9c1b-e5f6-47a8-9c1b-2d3e4f5a6b7c"
    private val uuidB = "bbbb2222-e5f6-47a8-9c1b-2d3e4f5a6b7c"

    private fun scenario(id: String, name: String) =
        Scenario(id = id, name = name, steps = listOf(ScenarioStep.Send("35=D|11=X|", "S1")))

    private fun jsonFiles(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.map { it.name }.sorted()

    @Test
    fun `filename is a legible slug of the name plus a short id`() {
        service.save(scenario(uuidA, "Book a Trade (captured)"))
        assertEquals(listOf("book-a-trade-captured--3f2a9c1b.json"), jsonFiles())
    }

    @Test
    fun `load resolves by the id inside the file, not the filename`() {
        service.save(scenario(uuidA, "Book a Trade"))
        assertEquals("Book a Trade", service.load(uuidA)?.name)
    }

    @Test
    fun `renaming rewrites under the new slug and drops the old file`() {
        service.save(scenario(uuidA, "Book a Trade"))
        service.save(scenario(uuidA, "Booked and gone"))
        assertEquals(listOf("booked-and-gone--3f2a9c1b.json"), jsonFiles(), "one file, renamed — not two")
        assertEquals("Booked and gone", service.load(uuidA)?.name)
    }

    @Test
    fun `two scenarios sharing a name get distinct files, both loadable`() {
        service.save(scenario(uuidA, "Trade"))
        service.save(scenario(uuidB, "Trade"))
        assertEquals(listOf("trade--3f2a9c1b.json", "trade--bbbb2222.json"), jsonFiles())
        assertNotNull(service.load(uuidA))
        assertNotNull(service.load(uuidB))
    }

    @Test
    fun `a legacy id-named file is found by id and migrates to a slug on next save`() {
        // A store written by the old scheme: filename == id. Produce valid content, then rename the file.
        service.save(scenario(uuidA, "Legacy Flow"))
        dir.listFiles { f -> f.name.endsWith(".json") }!!.single().renameTo(File(dir, "$uuidA.json"))
        assertEquals(listOf("$uuidA.json"), jsonFiles(), "precondition: a legacy id-named file")

        assertEquals("Legacy Flow", service.load(uuidA)?.name, "still found by the id it carries")

        service.save(service.load(uuidA)!!)
        assertEquals(listOf("legacy-flow--3f2a9c1b.json"), jsonFiles(), "migrated; the old <id>.json is gone")
    }
}
