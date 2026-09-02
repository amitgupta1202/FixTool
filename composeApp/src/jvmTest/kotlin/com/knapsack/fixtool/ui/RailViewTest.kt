package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.ScenarioSort
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rail's pure decisions, tested where they are pure — no Compose tree.
 *
 * **Ordering:** each sort must be total, so the list never reshuffles on an idle recomposition, and the
 * "creation order" fallback to file mtime (for a scenario authored before `createdAt` existed) must place
 * such files honestly rather than all at once.
 *
 * **Refusal:** a Run that is greyed out owes the author a sentence naming what is holding it — the wording
 * is the assertion, because the wording is the whole feature.
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

    // --- why a picked set cannot start: the sentence a greyed-out Run owes the author ---

    private fun on(vararg sessions: String) =
        Scenario(
            id = sessions.joinToString("-"),
            name = sessions.joinToString("+"),
            steps = sessions.map { ScenarioStep.Send("35=D|", session = it) },
        )

    private fun runView(busy: Map<String, String>) =
        RunView(ran = null, result = null, running = busy.isNotEmpty(), busySessions = busy.keys, busyHolders = busy)

    @Test
    fun `nothing held, nothing to say`() {
        assertNull(blockedReason(listOf(on("QUOTE1"), on("TRADE1")), runView(emptyMap())))
        // And a run holding a session none of the picked scenarios touches is not this set's problem.
        assertNull(blockedReason(listOf(on("QUOTE1")), runView(mapOf("LOADGEN" to "load test"))))
    }

    @Test
    fun `the refusal names how many, which sessions, and what is holding them`() {
        val picked = listOf(on("QUOTE1"), on("TRADE1"), on("UAT"))
        assertEquals(
            "1 of 3 wait on QUOTE1 — held by 'nightly'",
            blockedReason(picked, runView(mapOf("QUOTE1" to "nightly"))),
        )
    }

    @Test
    fun `two runs holding two sessions are both named, once each`() {
        val picked = listOf(on("QUOTE1", "TRADE1"), on("TRADE1"), on("UAT"))
        assertEquals(
            "2 of 3 wait on QUOTE1, TRADE1 — held by 'nightly', 'smoke'",
            blockedReason(picked, runView(mapOf("QUOTE1" to "nightly", "TRADE1" to "smoke"))),
        )
    }

    /**
     * A scenario naming no session runs on whichever session happens to be first, so it waits for all of
     * them — the one case the claim cannot reason about, and so the one the button must not encourage.
     */
    @Test
    fun `a scenario that names no session waits for every held session`() {
        val unnamed = Scenario(id = "u", name = "u", steps = listOf(ScenarioStep.Send("35=D|")))
        assertEquals(
            "1 of 1 wait on LOADGEN — held by 'load test'",
            blockedReason(listOf(unnamed), runView(mapOf("LOADGEN" to "load test"))),
        )
    }

    @Test
    fun `an unnamed holder still says which session, rather than nothing`() {
        val view = RunView(null, null, running = true, busySessions = setOf("QUOTE1"), busyHolders = emptyMap())
        assertEquals("1 of 1 wait on QUOTE1", blockedReason(listOf(on("QUOTE1")), view))
    }
}
