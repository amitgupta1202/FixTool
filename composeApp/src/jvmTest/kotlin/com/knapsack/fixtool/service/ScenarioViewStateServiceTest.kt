package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.ScenarioSort
import com.knapsack.fixtool.model.ScenarioViewState
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

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
}
