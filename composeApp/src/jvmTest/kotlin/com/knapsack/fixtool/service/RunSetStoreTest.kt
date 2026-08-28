package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.RunPolicy
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Selection is a file.** The first draft of this feature proposed waking `Scenario.userTags` for a
 * `--tag nightly` selector; review found that field is the per-profile scenario filter and part of the
 * `GET /scenarios` payload, so a `nightly` tag would have hidden the scenario from every profile-filtered
 * listing. A saved set is a file like everything else the app keeps — and a file is what CI can name.
 */
class RunSetStoreTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("fixtool-sets", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a saved set round-trips, and is found by name`() {
        val store = RunSetStore(customDir = dir.absolutePath)
        val set =
            SavedRunSet(
                name = "nightly",
                entries = listOf(SavedRunEntry("smoke-nos"), SavedRunEntry("book-a-trade", repeat = 3)),
                policy = RunPolicy(stopOnFirstFailure = true, pauseBetweenMs = 250),
            )

        assertTrue(store.save(set))
        val back = assertNotNull(store.load("nightly"))

        assertEquals(set.entries, back.entries)
        assertEquals(set.policy, back.policy)
        assertEquals(listOf("nightly"), store.list().map { it.name })
        assertTrue(store.delete("nightly"))
        assertNull(store.load("nightly"))
    }

    /** A repeat in the file is N entries in the plan — the set's queue is visible before it runs. */
    @Test
    fun `planning expands repeats and numbers the iterations`() {
        val smoke = scenario("smoke-nos")
        val trade = scenario("book-a-trade")
        val saved = SavedRunSet("nightly", listOf(SavedRunEntry(smoke.id), SavedRunEntry(trade.name, repeat = 3)))

        val planned = saved.plan(listOf(smoke, trade), now = 0L)

        assertEquals(
            listOf("smoke-nos" to 1, "book-a-trade" to 1, "book-a-trade" to 2, "book-a-trade" to 3),
            planned.set.entries.map { it.scenarioName to it.iteration },
            "by id or by name, and a repeat becomes iterations",
        )
        assertEquals(RunSource.Saved("nightly"), planned.set.source)
        assertTrue(planned.missing.isEmpty())
    }

    /**
     * A set of twelve with one deleted scenario runs the eleven and says which name it could not find.
     * The alternative is a nightly suite that stops existing the day somebody renames a file.
     */
    @Test
    fun `a name nothing answers to is reported, and the rest still run`() {
        val smoke = scenario("smoke-nos")
        val saved = SavedRunSet("nightly", listOf(SavedRunEntry("smoke-nos"), SavedRunEntry("deleted-one")))

        val planned = saved.plan(listOf(smoke), now = 0L)

        assertEquals(listOf("smoke-nos"), planned.set.entries.map { it.scenarioName })
        assertEquals(listOf("deleted-one"), planned.missing)
    }

    private fun scenario(name: String) =
        Scenario(id = "id-$name", name = name, steps = listOf(ScenarioStep.Send("35=D|", session = "s")))
}
