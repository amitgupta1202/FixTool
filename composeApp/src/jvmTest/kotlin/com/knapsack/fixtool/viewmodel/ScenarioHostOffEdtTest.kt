package com.knapsack.fixtool.viewmodel

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scenario runner's read-only host calls must not require the Swing EDT.
 *
 * `ScenarioRunner` polls `messages()` and `connectionState()` roughly every 100ms for the life of a
 * step, so a 30-second `expect` is ~300 calls. When each one was an `invokeAndWait`, every poll
 * bounced off the EDT — and dragged a full O(N) copy of the session's message log onto it — which
 * froze the UI for the length of the scenario run.
 *
 * This test blocks the EDT and asserts the read path still completes. It fails if an EDT hop is
 * reintroduced anywhere in that path, including in session lookup.
 */
class ScenarioHostOffEdtTest {
    private lateinit var testDir: File
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var host: ViewModelScenarioHost

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-host-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        host = ViewModelScenarioHost(viewModel)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `read-only host calls complete while the EDT is blocked`() {
        val edtOccupied = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)

        SwingUtilities.invokeLater {
            edtOccupied.countDown()
            // Held far longer than READ_WATCHDOG_SECONDS on purpose: if the EDT freed itself on the
            // same deadline the assertion uses, a blocked read would unblock just in time and the
            // test would pass against the very bug it exists to catch.
            releaseEdt.await(EDT_HOLD_SECONDS, TimeUnit.SECONDS)
        }
        assertTrue(edtOccupied.await(EDT_HOLD_SECONDS, TimeUnit.SECONDS), "EDT never picked up the task")

        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        Thread {
            try {
                // Both are pure reads: they must not need the EDT, whether or not a session exists.
                host.messages(null)
                host.connectionState(null)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }.start()

        val completed = done.await(READ_WATCHDOG_SECONDS, TimeUnit.SECONDS)
        releaseEdt.countDown() // always free the EDT, pass or fail

        assertTrue(
            completed,
            "host.messages()/connectionState() blocked on the busy EDT — an EDT hop was reintroduced " +
                "into the scenario runner's polling path",
        )
        assertNull(failure.get(), "off-EDT host call threw: ${failure.get()}")
    }

    private companion object {
        /** How long a read is allowed to take before we call it blocked. */
        const val READ_WATCHDOG_SECONDS = 3L

        /** How long the EDT stays occupied — must comfortably outlast the read watchdog. */
        const val EDT_HOLD_SECONDS = 30L
    }
}
