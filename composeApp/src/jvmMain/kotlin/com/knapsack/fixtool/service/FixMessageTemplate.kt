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

            // Build helper objects that can be serialized into the script
            // Extract message data into simple maps
            val incomingData = incomingMessages.mapValues { (_, msg) ->
                val tags = mutableMapOf<Int, String?>()
                msg.quickfixMessage.iterator().forEach { field ->
                    tags[field.tag] = field.`object`.toString()
                }
                tags.toMap()
            }

            val outgoingData = outgoingMessages.mapValues { (_, msg) ->
                val tags = mutableMapOf<Int, String?>()
                msg.quickfixMessage.iterator().forEach { field ->
                    tags[field.tag] = field.`object`.toString()
                }
                tags.toMap()
            }

            // Build helper class definition and data as part of the script
            val helperCode = buildString {
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
                appendLine("// Build incoming and outgoing message maps")
                append("val incoming = MessageMap(mapOf<String, MessageAccessor>(")
                if (incomingData.isNotEmpty()) {
                    appendLine()
                    incomingData.entries.forEachIndexed { index, (msgType, tags) ->
                        val tagsStr = tags.entries.joinToString(", ") { (tag, value) ->
                            "$tag to ${value?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"}"
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
                        val tagsStr = tags.entries.joinToString(", ") { (tag, value) ->
                            "$tag to ${value?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"}"
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
            result?.toString() ?: "null"
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
