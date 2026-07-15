package com.knapsack.fixtool.service

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Loading a scenario: missing is not corrupt.**
 *
 * `ScenarioService` shouts — a user-facing error toast — when a scenario file cannot be read, and that is right
 * for a file that *exists but will not parse* (the upgrade-ate-my-scenarios case the codec is built to refuse
 * loudly). But `load(id)` of an id that is simply **not on disk** used to hit the same path: the missing file
 * threw `FileNotFoundException`, the generic catch shouted, and a benign "that id is gone" produced an error
 * popup. Filed since Phase 2 and re-noted every phase; fixed in Phase 8.
 */
class ScenarioServiceLoadTest {
    private lateinit var dir: File

    @Before
    fun setup() {
        dir =
            File.createTempFile("fixtool-load", "").apply {
                delete()
                mkdirs()
            }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `loading an id that is not on disk returns null and does not shout`() {
        val toasts = mutableListOf<String>()
        val service = ScenarioService(onError = { toasts += it }, customDir = dir.absolutePath)

        assertNull(service.load("no-such-id"), "a missing id has no scenario")
        assertTrue(toasts.isEmpty(), "a missing id is not a corrupt file — it must not fire an error toast")
    }

    @Test
    fun `a file that exists but will not parse is still said out loud`() {
        val toasts = mutableListOf<String>()
        val service = ScenarioService(onError = { toasts += it }, customDir = dir.absolutePath)
        // A real file on disk whose contents the codec cannot read — the case the loud behaviour exists for.
        File(dir, "corrupt.json").writeText("{ this is not valid json")

        assertNull(service.load("corrupt"), "a corrupt file yields no scenario")
        assertTrue(toasts.isNotEmpty(), "a file that exists and will not parse is the upgrade-ate-my-scenarios case: shout")
    }
}
