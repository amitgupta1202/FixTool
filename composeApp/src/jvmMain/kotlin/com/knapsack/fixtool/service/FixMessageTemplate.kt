package com.knapsack.fixtool.service

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.script.ScriptEngineManager
import javax.script.ScriptException

/**
 * Evaluates Kotlin expressions in FIX message field values using Kotlin's scripting engine.
 *
 * Allows users to write any valid Kotlin expression in field values, for example:
 * - UUID.randomUUID()
 * - LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"))
 * - System.currentTimeMillis()
 * - "ORDER-${UUID.randomUUID()}"
 *
 * The expression is evaluated when the message is sent, ensuring fresh timestamps.
 */
object FixMessageTemplate {

    private val EXPRESSION_REGEX = """\$\{([^}]+)}""".toRegex()

    // Create script engine with common imports available
    private val scriptEngine by lazy {
        val engine = ScriptEngineManager().getEngineByExtension("kts")
            ?: throw IllegalStateException("Kotlin script engine not found. Ensure kotlin-scripting-jsr223 is in classpath.")

        // Pre-import commonly used classes for convenience
        val imports = """
            import java.util.UUID
            import java.time.LocalDateTime
            import java.time.format.DateTimeFormatter
            import java.time.Instant
        """.trimIndent()

        try {
            engine.eval(imports)
        } catch (e: ScriptException) {
            // Imports failed, but engine is still usable
        }

        engine
    }

    /**
     * Evaluates all Kotlin expressions in the given string value.
     * Expressions are in the format: ${kotlinExpression}
     *
     * Examples:
     * - "${UUID.randomUUID()}" → "a1b2c3d4-..."
     * - "ORDER-${UUID.randomUUID()}" → "ORDER-a1b2c3d4-..."
     * - "${LocalDateTime.now()}" → "2025-01-16T14:32:45.123"
     */
    fun evaluate(value: String): String {
        return EXPRESSION_REGEX.replace(value) { matchResult ->
            val expression = matchResult.groupValues[1].trim()
            evaluateExpression(expression)
        }
    }

    /**
     * Evaluates a single Kotlin expression and returns its string value.
     */
    private fun evaluateExpression(expression: String): String {
        return try {
            val result = scriptEngine.eval(expression)
            result?.toString() ?: ""
        } catch (e: ScriptException) {
            // If evaluation fails, return the original expression
            "\${$expression}"
        } catch (e: Exception) {
            // Any other error, return original
            "\${$expression}"
        }
    }

    /**
     * Checks if a value contains any template expressions
     */
    fun hasTemplateExpressions(value: String): Boolean {
        return EXPRESSION_REGEX.containsMatchIn(value)
    }
}
