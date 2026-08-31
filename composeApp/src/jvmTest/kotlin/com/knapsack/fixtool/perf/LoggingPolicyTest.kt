package com.knapsack.fixtool.perf

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The shipped logging configuration is a throughput setting, so it is pinned like one.**
 *
 * `LoggingCostBenchmarkTest` measures what a per-message log line costs on the QuickFIX/J callback
 * thread: ~9.7us and ~3.4KB of garbage each, against ~35ns and nothing when the line is disabled. That
 * is not a difference anyone would accept if it were written as a config field called
 * `maxIngestRate` — but it is exactly what `<logger level="DEBUG"/>` means here, and it was set to
 * DEBUG by a change that had no idea it was touching ingest.
 *
 * So this test reads the **actual shipped `logback.xml`** off the classpath, not a fixture, and asserts
 * the policy the fix established. It exists because the regression is a one-word edit that no other
 * test would notice: everything still works, everything is just slower, and the number that reveals it
 * lives on a socket under load.
 *
 * The policy is deliberately narrow. It does not forbid debug logging, and it does not forbid anyone
 * turning DEBUG on when they are debugging — it forbids *shipping* with the per-message lines enabled.
 */
class LoggingPolicyTest {
    private fun shippedContext(): LoggerContext {
        val stream =
            requireNotNull(javaClass.getResourceAsStream("/logback.xml")) {
                "the shipped logback.xml must be on the classpath"
            }
        val context = LoggerContext()
        context.name = "shipped-policy-check"
        JoranConfigurator().apply { this.context = context }.doConfigure(stream)
        return context
    }

    /**
     * The four callbacks that see every message. If any of these is enabled by the shipped config, every
     * session on this build pays ~9.7us per message to write a line that duplicates QuickFIX/J's own
     * `FileLogFactory` message log.
     */
    @Test
    fun `the shipped config does not enable per-message logging`() {
        val context = shippedContext()
        val logger = context.getLogger("com.knapsack.fixtool.service.QuickFixService")

        assertFalse(
            logger.isDebugEnabled,
            "QuickFixService's per-message lines (Sending / Received / toApp / fromApp) are DEBUG, and the " +
                "shipped config must leave them off — enabling them costs ~9.7us and ~3.4KB per message on " +
                "the callback thread. See LoggingCostBenchmarkTest.",
        )
        context.stop()
    }

    /**
     * The other half of the policy, and the reason the lines were moved rather than deleted: a support
     * engineer must still be able to get them. A config that made them unreachable would have traded a
     * throughput bug for a diagnosability one.
     */
    @Test
    fun `the per-message lines are still reachable by turning the package to DEBUG`() {
        val context = shippedContext()
        val appLogger = context.getLogger("com.knapsack.fixtool")
        appLogger.level = Level.DEBUG

        val logger = context.getLogger("com.knapsack.fixtool.service.QuickFixService")
        assertTrue(
            logger.isDebugEnabled,
            "turning com.knapsack.fixtool to DEBUG must bring the message log back",
        )
        context.stop()
    }

    /**
     * Errors and warnings are not traffic — they happen once per problem, not once per message, so they
     * stay on. A config that silenced them to buy throughput would be hiding the failures this tool
     * exists to surface.
     */
    @Test
    fun `warnings and errors remain enabled`() {
        val context = shippedContext()
        val logger = context.getLogger("com.knapsack.fixtool.service.QuickFixService")

        assertTrue(logger.isWarnEnabled, "warnings must survive the level change")
        assertTrue(logger.isErrorEnabled, "errors must survive the level change")
        assertTrue(logger.isInfoEnabled, "lifecycle INFO (connects, logons) must survive the level change")
        context.stop()
    }

    /**
     * The threshold filter on ERROR_FILE is what keeps a third appender from being a third write. The
     * benchmark's decomposition puts it at ~49ns against ~5us for the two unfiltered ones — it rejects
     * the event before any encoding happens. Pinned because removing the filter would look harmless.
     */
    @Test
    fun `the error appender still filters below ERROR`() {
        val context = shippedContext()
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val errorAppender =
            requireNotNull(root.getAppender("ERROR_FILE")) { "ERROR_FILE appender must exist" }

        assertEquals(
            1,
            errorAppender.copyOfAttachedFiltersList.size,
            "ERROR_FILE must keep its ThresholdFilter — without it every INFO line is encoded three times",
        )
        context.stop()
    }
}
