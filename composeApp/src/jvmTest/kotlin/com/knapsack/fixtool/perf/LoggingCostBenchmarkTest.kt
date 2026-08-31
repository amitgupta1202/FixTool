package com.knapsack.fixtool.perf

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * **What it costs to narrate every message on the thread that has to keep up with the wire.**
 *
 * `QuickFixService` logs one line per FIX message from inside the QuickFIX/J callback — `toAdmin`,
 * `fromAdmin`, `toApp`, `fromApp`, one apiece, so each message is logged once — and the call returns
 * only once the line has reached every appender. The shipped configuration gave it three of them, all
 * synchronous: a console, a rolling file, and a second rolling file behind a threshold filter. Nothing
 * about that is visible at the call site; `logger.info("Received: {}", raw)` looks free.
 *
 * It is not free, and it is not paid by a background thread. Until the appenders return, the session is
 * not reading the socket — so the logging configuration is a throughput setting, and it was set without
 * anyone deciding it was one.
 *
 * **These benchmarks exist because the obvious fix turned out to be wrong.** The first thing tried here
 * was wrapping the file appenders in `AsyncAppender`, on the reasoning that a queue hand-off must beat a
 * disk write. Measured, it was consistently *slower* per call than the synchronous appenders it
 * replaced: the OS absorbs a buffered append into the page cache in a few hundred nanoseconds, while
 * `AsyncAppender` has to prepare a `LoggingEvent`, capture the thread name, and offer to a
 * `BlockingQueue` on every single call, enabled or not. The number below is the reason the shipped fix
 * is "do not log every message at a level that is on by default" rather than "log it asynchronously".
 */
class LoggingCostBenchmarkTest {
    private fun contextFor(xml: String): LoggerContext {
        val context = LoggerContext()
        context.name = "bench-" + System.nanoTime()
        JoranConfigurator().apply { this.context = context }
            .doConfigure(ByteArrayInputStream(xml.toByteArray()))
        return context
    }

    private fun config(
        dir: String,
        appenders: String,
        level: String,
        extra: String = "",
    ): String =
        """
        <configuration>
            <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
            </appender>
            <appender name="FILE" class="ch.qos.logback.core.FileAppender">
                <file>$dir/bench.log</file>
                <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
            </appender>
            <appender name="ERROR_FILE" class="ch.qos.logback.core.FileAppender">
                <file>$dir/bench-errors.log</file>
                <filter class="ch.qos.logback.classic.filter.ThresholdFilter"><level>ERROR</level></filter>
                <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n%ex{full}</pattern></encoder>
            </appender>
            $extra
            <logger name="com.knapsack.fixtool" level="$level"/>
            <root level="INFO">$appenders</root>
        </configuration>
        """.trimIndent()

    private val ALL = """<appender-ref ref="CONSOLE"/><appender-ref ref="FILE"/><appender-ref ref="ERROR_FILE"/>"""

    /**
     * **The headline number: what one message costs when the line is on, against when it is off.**
     *
     * This is the fix, stated as a measurement. Nothing about the call site changes — the same
     * `logger.info("QuickFIX fromApp: {}", raw)` runs in both arms. The only difference is whether the
     * shipped configuration has that logger at a level which enables it.
     *
     * The per-message traffic lines move to DEBUG and the package level moves to INFO, so the lines are
     * still there for anyone debugging a session and cost nothing for everyone who is not.
     */
    @Test
    fun `per-message logging cost, enabled against disabled`() {
        val dir = Files.createTempDirectory("fixtool-log-bench").toFile()
        val raw = Corpus.rfqFlow(1).first().rawMessage

        val enabled = contextFor(config(dir.absolutePath, ALL, level = "DEBUG"))
        val disabled = contextFor(config(dir.absolutePath, ALL, level = "INFO"))

        val on = enabled.getLogger("com.knapsack.fixtool.service.QuickFixService")
        val off = disabled.getLogger("com.knapsack.fixtool.service.QuickFixService")

        val result =
            Bench.compare(
                "One FIX message logged from the QuickFIX callback",
                ops = 2_000,
                before = "shipped: console + 2 files, line enabled" to { on.debug("QuickFIX fromApp: {}", raw) },
                after = "fixed: same call, line at a disabled level" to { off.debug("QuickFIX fromApp: {}", raw) },
            )

        enabled.stop()
        disabled.stop()
        dir.deleteRecursively()

        assertTrue(
            result.allocationFactor > 20.0,
            "a disabled line must allocate essentially nothing; got ${result.allocationFactor}× less",
        )
        assertTrue(
            result.timeFactor > 5.0,
            "a disabled line must be far cheaper than three synchronous appenders; got ${result.timeFactor}×",
        )
    }

    /**
     * **Where the cost sits, so the fix is aimed rather than guessed.**
     *
     * Decomposing the three appenders was what settled the question. The console is the expensive one and
     * also the useless one: a packaged desktop app has no terminal attached, so every one of those writes
     * is formatted and pushed into a stream nobody will ever read — and `ConsoleAppender` extends
     * `OutputStreamAppender` with `immediateFlush` on, because a console log that lost its last lines in a
     * crash would be worthless. That flush is a syscall per message.
     *
     * Reported, not asserted: which appender dominates depends on whether stdout is a terminal, a pipe, or
     * closed, and a test cannot pin the machine it runs on.
     */
    @Test
    fun `decomposing the three appenders`() {
        val dir = Files.createTempDirectory("fixtool-log-decomp").toFile()
        val raw = Corpus.rfqFlow(1).first().rawMessage

        val contexts =
            listOf(
                "console only" to config(dir.absolutePath, """<appender-ref ref="CONSOLE"/>""", "DEBUG"),
                "rolling file only" to config(dir.absolutePath, """<appender-ref ref="FILE"/>""", "DEBUG"),
                "error file only (filtered out)" to config(dir.absolutePath, """<appender-ref ref="ERROR_FILE"/>""", "DEBUG"),
                "all three (as shipped)" to config(dir.absolutePath, ALL, "DEBUG"),
            ).map { (name, xml) -> name to contextFor(xml) }

        println("\n┌─ Cost of one per-message log line, by appender")
        contexts.forEach { (name, ctx) ->
            val logger = ctx.getLogger("com.knapsack.fixtool.service.QuickFixService")
            val r = Bench.measure(name, ops = 2_000) { logger.debug("QuickFIX fromApp: {}", raw); true }
            println("│  " + r.render())
        }
        println("└─\n")

        contexts.forEach { (_, ctx) -> ctx.stop() }
        dir.deleteRecursively()
    }

    /**
     * **A disabled level is not a free call when the message was built before the call.**
     *
     * 27 log sites in this module interpolate with a Kotlin template — `logger.info("... $x ...")` —
     * which builds the whole string at the call site and hands the finished result to SLF4J. Logback then
     * discards it if the level is off. The `{}` form defers that: arguments are passed as references and
     * the string is assembled only if something is going to read it.
     *
     * This is what makes the level change in the first test actually pay. Moving a line to DEBUG buys
     * nothing if the line built its own string on the way there.
     */
    @Test
    fun `eager template interpolation is paid even at a disabled level`() {
        val dir = Files.createTempDirectory("fixtool-log-bench2").toFile()
        val raw = Corpus.rfqFlow(1).first().rawMessage
        val seq = 42

        // Level set so these calls are DISABLED — the point is what a disabled call still costs.
        val context = contextFor(config(dir.absolutePath, ALL, level = "WARN"))
        val logger = context.getLogger("com.knapsack.fixtool.service.QuickFixService")

        val result =
            Bench.compare(
                "A log line that will be discarded, at a disabled level",
                ops = 20_000,
                before = "Kotlin template: \"seq=\$seq raw=\$raw\"" to { logger.info("Received seq=$seq raw=$raw") },
                after = "SLF4J placeholders: \"seq={} raw={}\"" to { logger.info("Received seq={} raw={}", seq, raw) },
            )

        context.stop()
        dir.deleteRecursively()

        assertTrue(
            result.after.bytesPerOp == 0L,
            "a disabled placeholder call must allocate nothing; got ${result.after.bytesPerOp} B/op",
        )
        assertTrue(
            result.before.bytesPerOp > 100L,
            "the template arm must show the string it built; got ${result.before.bytesPerOp} B/op",
        )
    }
}
