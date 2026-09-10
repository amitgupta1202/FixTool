package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.LoadRunDefaults
import com.knapsack.fixtool.model.LoadShapeChoice
import com.knapsack.fixtool.model.ScenarioSort
import com.knapsack.fixtool.model.ScenarioViewState
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rail's view-chrome store holds nothing that cannot be regenerated, so its one hard promise is to
 * never throw into the rail: a missing, truncated, or hand-mangled file must load as defaults.
 */
class ScenarioViewStateServiceTest {
    private lateinit var dir: File

    @Before
    fun setup() {
        dir = File.createTempFile("fixtool-viewstate", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun service() = ScenarioViewStateService(customPath = File(dir, "scenario_view.json").absolutePath)

    @Test
    fun `a missing file loads as defaults`() {
        assertEquals(ScenarioViewState(), service().load())
    }

    @Test
    fun `it round-trips sort, favourites and collapsed sections`() {
        val state = ScenarioViewState(ScenarioSort.CREATED, setOf("a", "b"), setOf("favourites"))
        service().save(state)
        assertEquals(state, service().load())
    }

    @Test
    fun `a corrupt file loads as defaults, never throwing`() {
        File(dir, "scenario_view.json").writeText("{ this is not json")
        assertEquals(ScenarioViewState(), service().load())
    }

    @Test
    fun `an unknown key is ignored, not fatal`() {
        File(dir, "scenario_view.json").writeText("""{"sortMode":"RECENTLY_MODIFIED","aFieldFromTheFuture":42}""")
        assertEquals(ScenarioSort.RECENTLY_MODIFIED, service().load().sortMode)
    }

    /**
     * **A file written before the Shape segment had three options opens on the shape it was saved with.**
     *
     * The whole migration: the old file says `"burst": false` and nothing else about the shape, and the
     * only wrong answer is to reset it to a burst because the new key is absent. Written as raw JSON
     * rather than through the old data class, because the old data class is gone and the file is what
     * actually survives an upgrade.
     */
    @Test
    fun `a rate saved before reactive existed still opens as a rate`() {
        File(dir, "scenario_view.json").writeText(
            """{"loadRuns":{"lg":{"burst":false,"count":"250","rate":"750","forText":"5m","settle":"5s"}}}""",
        )

        val defaults = service().load().loadRuns.getValue("lg")

        assertEquals(LoadShapeChoice.RATE, defaults.shape, "a saved rate must not open as a burst")
        assertEquals("750", defaults.rate)
        assertEquals("250", defaults.count, "and the burst it is not still keeps its own count")
    }

    /** The other half of the same file, so the fallback is not read as "everything old is a rate". */
    @Test
    fun `a burst saved before reactive existed still opens as a burst`() {
        File(dir, "scenario_view.json").writeText("""{"loadRuns":{"lg":{"burst":true,"count":"250"}}}""")

        val defaults = service().load().loadRuns.getValue("lg")

        assertEquals(LoadShapeChoice.BURST, defaults.shape)
    }

    /**
     * A file this FixTool writes carries both keys, so a downgrade reads the same shape back. The new key
     * wins on the way in, which is what makes a reactive phase possible at all.
     */
    @Test
    fun `a saved choice round-trips and still says burst or rate to an older reader`() {
        val defaults = LoadRunDefaults.of(LoadShapeChoice.RATE, count = "250", rate = "750")
        service().save(ScenarioViewState(loadRuns = mapOf("lg" to defaults)))

        val written = File(dir, "scenario_view.json").readText()

        assertEquals(defaults, service().load().loadRuns.getValue("lg"))
        assertTrue(written.contains("\"burst\": false"), written)
        assertTrue(written.contains("\"shapeKind\": \"RATE\""), written)
    }
}
