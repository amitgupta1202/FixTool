package com.knapsack.fixtool.service

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Evaluates template expressions in FIX message field values.
 *
 * Supports expressions like:
 * - ${UUID.randomUUID()} - Generates a random UUID
 * - ${timestamp()} - Current timestamp in FIX format (YYYYMMDD-HH:MM:SS.sss)
 * - ${timestamp("yyyyMMdd")} - Custom timestamp format
 * - ${now()} - Alias for timestamp()
 * - ${currentTimeMillis()} - Unix timestamp in milliseconds
 */
object FixMessageTemplate {

    private val EXPRESSION_REGEX = """\$\{([^}]+)}""".toRegex()

    /**
     * Evaluates all template expressions in the given string value.
     * Returns the string with all expressions replaced by their evaluated results.
     */
    fun evaluate(value: String): String {
        return EXPRESSION_REGEX.replace(value) { matchResult ->
            val expression = matchResult.groupValues[1].trim()
            evaluateExpression(expression)
        }
    }

    /**
     * Evaluates a single expression and returns its string value.
     */
    private fun evaluateExpression(expression: String): String {
        return try {
            when {
                // UUID generation
                expression == "UUID.randomUUID()" -> UUID.randomUUID().toString()

                // Timestamp functions
                expression == "timestamp()" || expression == "now()" -> {
                    LocalDateTime.now().format(FIX_TIMESTAMP_FORMAT)
                }

                expression.startsWith("timestamp(") && expression.endsWith(")") -> {
                    val formatStr = extractStringArg(expression, "timestamp")
                    val formatter = DateTimeFormatter.ofPattern(formatStr)
                    LocalDateTime.now().format(formatter)
                }

                expression == "currentTimeMillis()" -> System.currentTimeMillis().toString()

                // If no match, return the expression as-is
                else -> "\${$expression}"
            }
        } catch (e: Exception) {
            // If evaluation fails, return the original expression
            "\${$expression}"
        }
    }

    /**
     * Extracts a string argument from a function call like: functionName("arg")
     */
    private fun extractStringArg(expression: String, functionName: String): String {
        val argsStart = expression.indexOf('(') + 1
        val argsEnd = expression.lastIndexOf(')')
        val args = expression.substring(argsStart, argsEnd).trim()

        // Remove quotes if present
        return args.removeSurrounding("\"").removeSurrounding("'")
    }

    /**
     * Checks if a value contains any template expressions
     */
    fun hasTemplateExpressions(value: String): Boolean {
        return EXPRESSION_REGEX.containsMatchIn(value)
    }

    // FIX timestamp format: YYYYMMDD-HH:MM:SS.sss
    private val FIX_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
}
