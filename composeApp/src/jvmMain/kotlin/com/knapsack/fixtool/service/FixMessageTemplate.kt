package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
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
 * - incoming["D"].valueOfTag(11) - Reference value from latest incoming NewOrderSingle
 * - outgoing["R"].valueOfTag(131) - Reference value from latest outgoing QuoteRequest
 *
 * The expression is evaluated when the message is sent, ensuring fresh timestamps.
 */
object FixMessageTemplate {

    private val EXPRESSION_REGEX = """\$\{([^}]+)}""".toRegex()

    /**
     * Evaluates all Kotlin expressions in the given string value.
     * Expressions are in the format: ${kotlinExpression}
     *
     * Examples:
     * - "${UUID.randomUUID()}" → "a1b2c3d4-..."
     * - "ORDER-${UUID.randomUUID()}" → "ORDER-a1b2c3d4-..."
     * - "${LocalDateTime.now()}" → "2025-01-16T14:32:45.123"
     * - "${incoming[\"D\"].valueOfTag(11)}" → "ORDER123"
     *
     * @param value The string containing template expressions
     * @param incomingMessages Map of latest incoming messages by type (e.g., "D" -> NewOrderSingle message)
     * @param outgoingMessages Map of latest outgoing messages by type (e.g., "R" -> QuoteRequest message)
     */
    fun evaluate(
        value: String,
        incomingMessages: Map<String, FixMessage> = emptyMap(),
        outgoingMessages: Map<String, FixMessage> = emptyMap(),
    ): String {
        return EXPRESSION_REGEX.replace(value) { matchResult ->
            val expression = matchResult.groupValues[1].trim()
            evaluateExpression(expression, incomingMessages, outgoingMessages)
        }
    }

    /**
     * Evaluates a single Kotlin expression and returns its string value.
     */
    private fun evaluateExpression(
        expression: String,
        incomingMessages: Map<String, FixMessage>,
        outgoingMessages: Map<String, FixMessage>,
    ): String {
        return try {
            // Create a new script engine for this evaluation
            val engine = ScriptEngineManager().getEngineByExtension("kts")
                ?: throw IllegalStateException("Kotlin script engine not found.")

            // Pre-import commonly used classes
            val imports = """
                import java.util.UUID
                import java.time.LocalDateTime
                import java.time.format.DateTimeFormatter
                import java.time.Instant
            """.trimIndent()

            engine.eval(imports)

            // Expose the message maps to the script
            engine.put("incoming", incomingMessages)
            engine.put("outgoing", outgoingMessages)

            // Evaluate the expression
            val result = engine.eval(expression)
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
