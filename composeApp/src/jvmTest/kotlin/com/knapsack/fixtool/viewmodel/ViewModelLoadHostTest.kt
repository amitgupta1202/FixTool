package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.load.SessionLoadLane
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The host over live sessions delegates to what the view model hands it. The first live run died of a
 * `StackOverflowError` because `dictionary()` called itself, so the delegation is pinned here where a unit
 * test can reach it.
 */
class ViewModelLoadHostTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val settings = AppSettings()

    private fun host(lanes: List<Pair<Lane, FixMessageSession>> = emptyList()) =
        ViewModelLoadHost(
            lanes = lanes,
            listeners = emptyList(),
            resolve = { template, scope, title -> "$title:$template:${scope["run"]}" },
            dictionaryProvider = { dictionary },
            settingsProvider = { settings },
        )

    @Test
    fun `the dictionary and the evaluator are the view model's, not the host's own`() {
        val session = FixMessageSession(title = "LOADGEN [3]")
        val lane = SessionLoadLane(Lane(3, session.title, "LG03", ""), session)

        assertSame(dictionary, host().dictionary())
        assertEquals("LOADGEN [3]:\${out.D.11}:b7f2", host().resolveOnce("\${out.D.11}", mapOf("run" to "b7f2"), lane))
        session.destroy()
    }

    @Test
    fun `a lane that is not logged on is left out, and release with nothing overridden touches nothing`() {
        val session = FixMessageSession(title = "LOADGEN [1]")
        val h = host(listOf(Lane(1, session.title, "LG01", "") to session))

        assertEquals(emptyList(), h.openLanes("p", override = null), "a session that never logged on is not a lane")
        assertEquals(emptyList(), h.openLanes("p", override = StoreAndLogOverride.FOR_LOAD), "and has no config to override")
        h.release()
        session.destroy()
    }
}
