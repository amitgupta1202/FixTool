package com.knapsack.fixtool.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Severity has to survive the hop from the logger to the notification popup: an advisory (the
 * dictionary-mismatch lint, say — the message still goes out) must not be dressed up as a failure.
 * Before, every notifyUser call landed on the one error channel and the UI painted it red.
 */
class NotifyingLoggerTest {
    private var errors = mutableListOf<String>()
    private var warnings = mutableListOf<String>()

    private fun logger() =
        NotifyingLogger(
            NotifyingLoggerTest::class.java,
            onNotify = { errors += it },
            onNotifyWarning = { warnings += it },
        )

    @Test
    fun `a warning notifies through the warning channel, not the error channel`() {
        logger().warn("tags not defined for QuoteRequest (R)", notifyUser = true)

        assertEquals(listOf("tags not defined for QuoteRequest (R)"), warnings)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `an error notifies through the error channel`() {
        logger().error("No active FIX session", notifyUser = true)

        assertEquals(listOf("No active FIX session"), errors)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `without a warning channel a warning still reaches the user`() {
        var seen: String? = null
        NotifyingLogger(NotifyingLoggerTest::class.java, onNotify = { seen = it })
            .warn("no warning channel wired", notifyUser = true)

        assertEquals("no warning channel wired", seen)
    }

    @Test
    fun `nothing is notified unless notifyUser is set`() {
        val log = logger()
        log.warn("quiet")
        log.error("quiet")

        assertEquals(emptyList(), warnings)
        assertEquals(emptyList(), errors)
        assertNull(warnings.firstOrNull())
    }
}
