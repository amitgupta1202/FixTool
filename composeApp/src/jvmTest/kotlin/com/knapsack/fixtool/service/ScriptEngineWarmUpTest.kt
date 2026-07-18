package com.knapsack.fixtool.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Kotlin script engine is warmed at startup, and deliberately **not** paired with a cache of
 * compiled scripts.
 *
 * Warming is free of behavioural consequence — it only moves a ~1.4s one-time cost off the moment
 * the user sends their first template expression. Caching is not: `Compilable.compile()` resolves
 * free identifiers at compile time and materialises them as script properties, so a cached
 * `CompiledScript` re-evaluated under different bindings silently yields the values it was compiled
 * with. Since bulk-send re-resolves templates per session across up to 100 sessions, that would
 * make every session after the first send the first one's values.
 *
 * The re-resolution tests below are the guard rail: they fail if a naive per-expression compiled
 * script cache is ever introduced.
 */
class ScriptEngineWarmUpTest {
    @Test
    fun `warm up is safe to call and idempotent`() {
        repeat(3) { FixMessageTemplate.warmUp() }

        // Still evaluates correctly afterwards.
        val result = FixMessageTemplate.evaluate("\${1 + 1}")
        assertEquals("2", result, "warm-up must not disturb evaluation")
    }

    @Test
    fun `warm up leaves the engine able to evaluate expressions`() {
        FixMessageTemplate.warmUp()
        val uuid = FixMessageTemplate.evaluate("\${UUID.randomUUID()}")

        assertTrue(uuid.isNotBlank(), "expected a generated value")
        assertNotEquals("\${UUID.randomUUID()}", uuid, "expression should have been evaluated, not echoed")
    }

    /**
     * The same expression text under different variable bindings must yield different results.
     * A per-expression compiled-script cache would return the first binding's value for both.
     */
    @Test
    fun `same expression re-resolves against changed variables`() {
        val expression = "\${qty}"

        val first = FixMessageTemplate.evaluate(expression, variables = mutableMapOf("qty" to "100"))
        val second = FixMessageTemplate.evaluate(expression, variables = mutableMapOf("qty" to "250"))

        assertEquals("100", first)
        assertEquals(
            "250",
            second,
            "the expression re-resolved to the first binding's value — a compiled-script cache " +
                "would cause exactly this, and would make bulk-send reuse the first session's values",
        )
    }

    /**
     * The per-session shape directly: one expression, many sessions, each with its own variables.
     * This is what bulk-send does across up to 100 sessions.
     */
    @Test
    fun `one expression resolved across many sessions yields each session's own value`() {
        val expression = "\${clOrdId}"
        val perSession = (1..25).map { "ORDER-$it" }

        val resolved =
            perSession.map { id ->
                FixMessageTemplate.evaluate(expression, variables = mutableMapOf("clOrdId" to id))
            }

        assertEquals(perSession, resolved, "each session must resolve to its own variable value")
        assertEquals(
            perSession.size,
            resolved.toSet().size,
            "results collapsed to fewer distinct values than sessions — bindings are being reused",
        )
    }

    /** A fresh evaluation each time: expressions with side effects must not be memoised. */
    @Test
    fun `repeated evaluation of the same expression produces fresh values`() {
        val expression = "\${UUID.randomUUID()}"
        val values = (1..5).map { FixMessageTemplate.evaluate(expression) }

        assertEquals(values.size, values.toSet().size, "each evaluation must produce a fresh UUID")
    }
}
