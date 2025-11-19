package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
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
     * - "${orderId = UUID.randomUUID()}" → "a1b2c3d4-..." (and stores in variable 'orderId')
     * - "${orderId}" → "a1b2c3d4-..." (retrieves stored variable)
     *
     * @param value The string containing template expressions
     * @param incomingMessages Map of latest incoming messages by type (e.g., "D" -> NewOrderSingle message)
     * @param outgoingMessages Map of latest outgoing messages by type (e.g., "R" -> QuoteRequest message)
     * @param variables Mutable map to store and retrieve variables across multiple evaluate() calls.
     *                  If null, a new map is created for this call only.
     */
    fun evaluate(
        value: String,
        incomingMessages: Map<String, FixMessage> = emptyMap(),
        outgoingMessages: Map<String, FixMessage> = emptyMap(),
        variables: MutableMap<String, String>? = null,
    ): String {
        // Use provided variables map or create a new one
        val vars = variables ?: mutableMapOf()

        return EXPRESSION_REGEX.replace(value) { matchResult ->
            val expression = matchResult.groupValues[1].trim()
            evaluateExpression(expression, incomingMessages, outgoingMessages, vars)
        }
    }

    // Regex to detect variable assignments (e.g., "varName = expression")
    private val ASSIGNMENT_REGEX = """^\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(.+)$""".toRegex()

    // Regex to detect simple variable references (e.g., "varName")
    private val VARIABLE_REGEX = """^[a-zA-Z_][a-zA-Z0-9_]*$""".toRegex()

    /**
     * Evaluates a single Kotlin expression and returns its string value.
     * Supports variable assignments and references.
     */
    private fun evaluateExpression(
        expression: String,
        incomingMessages: Map<String, FixMessage>,
        outgoingMessages: Map<String, FixMessage>,
        variables: MutableMap<String, String>,
    ): String {
        // Check if this is a variable assignment (e.g., "orderId = UUID.randomUUID()")
        val assignmentMatch = ASSIGNMENT_REGEX.matchEntire(expression)
        if (assignmentMatch != null) {
            val varName = assignmentMatch.groupValues[1]
            val varExpression = assignmentMatch.groupValues[2]

            // Evaluate the expression and store it
            val result = evaluateKotlinExpression(varExpression, incomingMessages, outgoingMessages, variables)
            variables[varName] = result
            return result
        }

        // Check if this is a simple variable reference (e.g., "orderId")
        if (VARIABLE_REGEX.matches(expression)) {
            // Look up the variable
            return variables[expression] ?: "\${$expression}" // Return as-is if not found
        }

        // Otherwise, evaluate as a Kotlin expression
        return evaluateKotlinExpression(expression, incomingMessages, outgoingMessages, variables)
    }

    /**
     * Evaluates a Kotlin expression using the script engine.
     */
    private val logger = org.slf4j.LoggerFactory.getLogger(FixMessageTemplate::class.java)

    private fun evaluateKotlinExpression(
        expression: String,
        incomingMessages: Map<String, FixMessage>,
        outgoingMessages: Map<String, FixMessage>,
        variables: MutableMap<String, String>,
    ): String =
        try {
            logger.debug("Evaluating expression: {}", expression)
            logger.debug("Available incoming message types: {}", incomingMessages.keys.joinToString(","))
            logger.debug("Available outgoing message types: {}", outgoingMessages.keys.joinToString(","))

            // Create a new script engine for this evaluation
            val engine =
                ScriptEngineManager().getEngineByExtension("kts")
                    ?: error("Kotlin script engine not found.")

            // Build helper objects that can be serialized into the script
            // Extract message data into simple maps
            // Helper to escape strings for Kotlin script literals
            fun String.escapeForKotlinString(): String =
                this.replace("\\", "\\\\")  // Escape backslashes first
                    .replace("\"", "\\\"")   // Escape double quotes
                    .replace("$", "\\$")     // Escape dollar signs (template expressions)

            val incomingData =
                incomingMessages.mapValues { (_, msg) ->
                    val tags = mutableMapOf<Int, String?>()
                    msg.quickfixMessage.iterator().forEach { field ->
                        tags[field.tag] = field.`object`.toString()
                    }
                    tags.toMap()
                }

            val outgoingData =
                outgoingMessages.mapValues { (_, msg) ->
                    val tags = mutableMapOf<Int, String?>()
                    msg.quickfixMessage.iterator().forEach { field ->
                        tags[field.tag] = field.`object`.toString()
                    }
                    tags.toMap()
                }

            // Build helper class definition and data as part of the script
            val helperCode =
                buildString {
                    appendLine("import java.util.UUID")
                    appendLine("import java.time.LocalDateTime")
                    appendLine("import java.time.format.DateTimeFormatter")
                    appendLine("import java.time.Instant")
                    appendLine()
                    appendLine("// Helper class for message tag access")
                    appendLine("class MessageAccessor(private val tags: Map<Int, String?>) {")
                    appendLine("    fun valueOfTag(tag: Int): String? = tags[tag]")
                    appendLine("    operator fun get(tag: Int): String? = tags[tag]")
                    appendLine("}")
                    appendLine()
                    appendLine("// Wrapper for safe map access - returns empty accessor if message type not found")
                    appendLine("class MessageMap(private val messages: Map<String, MessageAccessor>) {")
                    appendLine("    operator fun get(msgType: String): MessageAccessor = messages[msgType] ?: MessageAccessor(emptyMap())")
                    appendLine("}")
                    appendLine()

                    // Add user-defined variables to the script
                    if (variables.isNotEmpty()) {
                        appendLine("// User-defined variables")
                        variables.forEach { (varName, value) ->
                            // Escape the value for use in Kotlin string literal
                            val escapedValue = value.escapeForKotlinString()
                            appendLine("val $varName = \"$escapedValue\"")
                        }
                        appendLine()
                    }

                    appendLine("// Build incoming and outgoing message maps")
                    append("val incoming = MessageMap(mapOf<String, MessageAccessor>(")
                    if (incomingData.isNotEmpty()) {
                        appendLine()
                        incomingData.entries.forEachIndexed { index, (msgType, tags) ->
                            val tagsStr =
                                tags.entries.joinToString(", ") { (tag, value) ->
                                    "$tag to ${value?.let { "\"${it.escapeForKotlinString()}\"" } ?: "null"}"
                                }
                            append("    \"$msgType\" to MessageAccessor(mapOf($tagsStr))")
                            if (index < incomingData.size - 1) appendLine(",") else appendLine()
                        }
                        appendLine("))")
                    } else {
                        appendLine("))")
                    }
                    appendLine()
                    append("val outgoing = MessageMap(mapOf<String, MessageAccessor>(")
                    if (outgoingData.isNotEmpty()) {
                        appendLine()
                        outgoingData.entries.forEachIndexed { index, (msgType, tags) ->
                            val tagsStr =
                                tags.entries.joinToString(", ") { (tag, value) ->
                                    "$tag to ${value?.let { "\"${it.escapeForKotlinString()}\"" } ?: "null"}"
                                }
                            append("    \"$msgType\" to MessageAccessor(mapOf($tagsStr))")
                            if (index < outgoingData.size - 1) appendLine(",") else appendLine()
                        }
                        appendLine("))")
                    } else {
                        appendLine("))")
                    }
                    appendLine()
                    appendLine("// Evaluate the user's expression")
                    appendLine(expression)
                }

            // Evaluate the complete script
            val result = engine.eval(helperCode)
            val resultStr = result?.toString() ?: "null"
            logger.debug("Expression '{}' evaluated to: {}", expression, resultStr)
            resultStr
        } catch (e: ScriptException) {
            // If evaluation fails, return the original expression
            logger.warn("ScriptException evaluating '{}': {}", expression, e.message)
            "\${$expression}"
        } catch (e: Exception) {
            // Any other error, return original
            logger.error("Exception evaluating '{}': {}", expression, e.message, e)
            "\${$expression}"
        }

    /**
     * Checks if a value contains any template expressions
     */
    fun hasTemplateExpressions(value: String): Boolean = EXPRESSION_REGEX.containsMatchIn(value)
}
