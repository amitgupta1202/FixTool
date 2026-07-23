package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.ScenarioSort
import com.knapsack.fixtool.model.scenario.Scenario
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rail's ordering rules, tested where they are pure — no Compose tree. Each sort must be total, so the
 * list never reshuffles on an idle recomposition, and the "creation order" fallback to file mtime (for a
 * scenario authored before `createdAt` existed) must place such files honestly rather than all at once.
 */
class RailViewTest {
    private fun sc(id: String, name: String, createdAt: Long? = null) =
        Scenario(id = id, name = name, createdAt = createdAt)

    @Test
    fun `name sort is A to Z, case-insensitive, ties broken stably by id`() {
        val out = railSections(listOf(sc("2", "beta"), sc("1", "Alpha"), sc("3", "alpha")), emptySet(), ScenarioSort.NAME) { null }
        assertEquals(listOf("Alpha", "alpha", "beta"), out.others.map { it.name })
        assertEquals(listOf("1", "3", "2"), out.others.map { it.id }, "the two alphas keep a stable id order")
    }

    @Test
    fun `recently modified is newest touch first, unknown mtime last`() {
        val mtimes = mapOf("a" to 100L, "b" to 300L, "c" to null)
        val out = railSections(listOf(sc("a", "a"), sc("b", "b"), sc("c", "c")), emptySet(), ScenarioSort.RECENTLY_MODIFIED) { mtimes[it] }
        assertEquals(listOf("b", "a", "c"), out.others.map { it.id })
    }

    @Test
    fun `creation order is oldest first, and a null createdAt falls back to mtime`() {
        val mtimes = mapOf("old" to 999L, "mid" to 200L, "new" to 999L)
        val items = listOf(sc("new", "n", createdAt = 300L), sc("old", "o", createdAt = 100L), sc("mid", "m", createdAt = null))
        val out = railSections(items, emptySet(), ScenarioSort.CREATED) { mtimes[it] }
        // old(createdAt 100) < mid(mtime 200, no createdAt) < new(createdAt 300)
        assertEquals(listOf("old", "mid", "new"), out.others.map { it.id })
    }

    @Test
    fun `favourites split out of the others, each section sorted independently`() {
        val items = listOf(sc("1", "zebra"), sc("2", "apple"), sc("3", "mango"))
        val out = railSections(items, setOf("1", "3"), ScenarioSort.NAME) { null }
        assertEquals(listOf("mango", "zebra"), out.favourites.map { it.name })
        assertEquals(listOf("apple"), out.others.map { it.name })
    }

    @Test
    fun `an empty list yields two empty sections`() {
        val out = railSections(emptyList(), setOf("x"), ScenarioSort.NAME) { null }
        assertTrue(out.favourites.isEmpty() && out.others.isEmpty())
    }
}
