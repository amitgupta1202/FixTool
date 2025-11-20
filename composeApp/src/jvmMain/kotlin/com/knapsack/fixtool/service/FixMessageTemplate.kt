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

            // Helper function to extract all fields including from repeating groups
            fun extractAllFields(fieldMap: quickfix.FieldMap): Map<Int, List<String>> {
                val startTime = System.currentTimeMillis()
                val tags = mutableMapOf<Int, MutableList<String>>()

                // Optimize: Single iteration through all fields
                // Extract regular fields AND check for groups in one pass
                var fieldCount = 0
                var groupCheckCount = 0
                var actualGroupsFound = 0

                fieldMap.iterator().forEach { field ->
                    fieldCount++

                    // Always add the field value first
                    tags.getOrPut(field.tag) { mutableListOf() }.add(field.`object`.toString())

                    // Then check if this field also defines a group
                    try {
                        val hasGroupCheck = System.currentTimeMillis()
                        val hasGroup = fieldMap.hasGroup(field.tag)
                        val hasGroupDuration = System.currentTimeMillis() - hasGroupCheck
                        groupCheckCount++

                        if (hasGroupDuration > 10) {
                            logger.warn("extractAllFields: hasGroup check for tag ${field.tag} took ${hasGroupDuration}ms")
                        }

                        if (hasGroup) {
                            actualGroupsFound++
                            val groupCount = fieldMap.getInt(field.tag)
                            logger.debug("extractAllFields: Found group at tag ${field.tag} with ${groupCount} instances")

                            // Extract fields from each group instance
                            for (i in 1..groupCount) {
                                try {
                                    val group = fieldMap.getGroup(i, field.tag)
                                    // Recursively extract from this group (handles nested groups too)
                                    val groupTags = extractAllFields(group)
                                    groupTags.forEach { (tag, values) ->
                                        tags.getOrPut(tag) { mutableListOf() }.addAll(values)
                                    }
                                } catch (e: Exception) {
                                    logger.warn("extractAllFields: Failed to extract group instance $i for tag ${field.tag}: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Not a group count field, skip
                    }
                }

                val totalDuration = System.currentTimeMillis() - startTime
                if (totalDuration > 100) {
                    logger.warn("extractAllFields: SLOW EXTRACTION - Total: ${totalDuration}ms for ${fieldCount} fields, ${groupCheckCount} hasGroup checks, ${actualGroupsFound} groups found")
                }

                return tags.mapValues { it.value.toList() }.toMap()
            }

            // Extract message data - support multiple values per tag (repeating groups)
            val incomingData =
                incomingMessages.mapValues { (_, msg) ->
                    extractAllFields(msg.quickfixMessage)
                }

            val outgoingData =
                outgoingMessages.mapValues { (_, msg) ->
                    extractAllFields(msg.quickfixMessage)
                }

            // Build helper class definition and data as part of the script
            val helperCode =
                buildString {
                    appendLine("import java.util.UUID")
                    appendLine("import java.time.LocalDateTime")
                    appendLine("import java.time.format.DateTimeFormatter")
                    appendLine("import java.time.Instant")
                    appendLine()
                    appendLine("// Helper class for message tag access with repeating group support")
                    appendLine("class MessageAccessor(private val tags: Map<Int, List<String>>) {")
                    appendLine("    // Get first value of tag (backwards compatible)")
                    appendLine("    fun valueOfTag(tag: Int): String? = tags[tag]?.firstOrNull()")
                    appendLine("    ")
                    appendLine("    // Get value at specific index (for repeating groups)")
                    appendLine("    fun valueOfTag(tag: Int, index: Int): String? = tags[tag]?.getOrNull(index)")
                    appendLine("    ")
                    appendLine("    // Get all values of a tag (for repeating groups)")
                    appendLine("    fun allValuesOfTag(tag: Int): List<String> = tags[tag] ?: emptyList()")
                    appendLine("    ")
                    appendLine("    // Operator overload for convenience")
                    appendLine("    operator fun get(tag: Int): String? = valueOfTag(tag)")
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
                                tags.entries.joinToString(", ") { (tag, values) ->
                                    val valuesList = values.joinToString(", ") { "\"${it.escapeForKotlinString()}\"" }
                                    "$tag to listOf($valuesList)"
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
                                tags.entries.joinToString(", ") { (tag, values) ->
                                    val valuesList = values.joinToString(", ") { "\"${it.escapeForKotlinString()}\"" }
                                    "$tag to listOf($valuesList)"
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
