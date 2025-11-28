package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import javax.script.ScriptContext
import javax.script.ScriptEngineManager
import javax.script.ScriptException
import javax.script.SimpleScriptContext

/**
 * Lightweight accessor wrappers exposed to the Kotlin script engine.
 * Using real classes (instead of regenerating Kotlin source for every expression)
 * keeps script parsing overhead low while still giving users the same API.
 */
class MessageAccessor(private val tags: Map<Int, List<String>>) {
    fun valueOfTag(tag: Int): String? = tags[tag]?.firstOrNull()
    fun valueOfTag(tag: Int, index: Int): String? = tags[tag]?.getOrNull(index)
    fun allValuesOfTag(tag: Int): List<String> = tags[tag] ?: emptyList()
    operator fun get(tag: Int): String? = valueOfTag(tag)
}

class MessageMap(private val messages: Map<String, MessageAccessor>) {
    operator fun get(msgType: String): MessageAccessor = messages[msgType] ?: MessageAccessor(emptyMap())
}

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
 * Shorthand syntax is also supported for message references:
 * - ${D.11} or ${D.ClOrdID} - Auto-detect (tries incoming first, then outgoing)
 * - ${in.D.11} - Explicit incoming
 * - ${out.R.131} - Explicit outgoing
 *
 * The expression is evaluated when the message is sent, ensuring fresh timestamps.
 */
object FixMessageTemplate {
    private val EXPRESSION_REGEX = """\$\{([^}]+)}""".toRegex()
    private val SCRIPT_PREAMBLE =
        """
        import java.util.UUID
        import java.time.LocalDateTime
        import java.time.format.DateTimeFormatter
        import java.time.Instant
        import com.knapsack.fixtool.service.MessageAccessor
        import com.knapsack.fixtool.service.MessageMap
        """.trimIndent()

    // Cache the script engine to avoid expensive re-initialization
    // Script engine initialization can take 100-1000ms, so reusing it dramatically improves performance
    private val scriptEngine by lazy {
        ScriptEngineManager().getEngineByExtension("kts")
            ?: error("Kotlin script engine not found.")
    }

    // Cache extracted message data to avoid re-processing the same messages
    // Key: message object identity, Value: extracted field data
    private val extractedDataCache = java.util.concurrent.ConcurrentHashMap<quickfix.Message, Map<Int, List<String>>>()

    // Known FIX repeating group tags - only these tags can contain groups
    // This avoids expensive hasGroup() checks on every field
    // Source: FIX 4.2/4.4/5.0 specifications - common group count tags
    private val KNOWN_GROUP_TAGS = setOf(
        // Standard group tags
        73, 78, 79, 124, 136, 146, 199, 215, 232, 267, 296, 382, 384, 386, 388,
        420, 421, 453, 454, 457, 461, 478, 518, 539, 552, 555, 576, 602, 604, 627,
        670, 683, 702, 711, 735, 753, 768, 782, 802, 804, 832, 862, 864, 868, 870, 872,
        876, 878, 887, 897, 936, 957, 1014, 1016, 1018, 1069, 1070, 1073, 1074, 1078,
        1109, 1116, 1117, 1130, 1132, 1133, 1134, 1135, 1137, 1140, 1158, 1166,
        // NoMDEntries, NoQuoteEntries, NoRelatedSym, etc.
        268, 295, 386, 420, 421, 454, 555, 711, 735, 768, 802
    )

    // Extract all fields including repeating groups, memoized by caller when needed
    private fun extractAllFields(fieldMap: quickfix.FieldMap): Map<Int, List<String>> {
        val startTime = System.currentTimeMillis()
        val tags = mutableMapOf<Int, MutableList<String>>()
        var fieldCount = 0
        var actualGroupsFound = 0

        fieldMap.iterator().forEach { field ->
            fieldCount++
            tags.getOrPut(field.tag) { mutableListOf() }.add(field.`object`.toString())

            if (field.tag in KNOWN_GROUP_TAGS) {
                try {
                    if (fieldMap.hasGroup(field.tag)) {
                        actualGroupsFound++
                        val groupCount = fieldMap.getInt(field.tag)
                        for (i in 1..groupCount) {
                            try {
                                val group = fieldMap.getGroup(i, field.tag)
                                val groupTags = extractAllFields(group)
                                groupTags.forEach { (tag, values) ->
                                    tags.getOrPut(tag) { mutableListOf() }.addAll(values)
                                }
                            } catch (_: Exception) {
                                // ignore extraction errors for individual groups
                            }
                        }
                    }
                } catch (_: Exception) {
                    // not a group count field
                }
            }
        }

        val totalDuration = System.currentTimeMillis() - startTime
        if (totalDuration > 50) {
            logger.debug(
                "extractAllFields: ${totalDuration}ms for $fieldCount fields, $actualGroupsFound groups found",
            )
        }

        return tags.mapValues { it.value.toList() }.toMap()
    }

    private fun createScriptContext(
        variables: Map<String, String>,
        incomingData: Map<String, MessageAccessor>,
        outgoingData: Map<String, MessageAccessor>,
    ): ScriptContext {
        val context = SimpleScriptContext()
        val bindings = scriptEngine.createBindings()
        bindings["incoming"] = MessageMap(incomingData)
        bindings["outgoing"] = MessageMap(outgoingData)
        variables.forEach { (name, value) -> bindings[name] = value }
        context.setBindings(bindings, ScriptContext.ENGINE_SCOPE)
        return context
    }

    private fun evalExpressionWithContext(
        expression: String,
        variables: Map<String, String>,
        incomingData: Map<String, MessageAccessor>,
        outgoingData: Map<String, MessageAccessor>,
    ): Any? {
        val context = createScriptContext(variables, incomingData, outgoingData)
        val script = "$SCRIPT_PREAMBLE\n$expression"
        return scriptEngine.eval(script, context)
    }

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
     * - "${D.11}" → Shorthand for incoming["D"].valueOfTag(11)
     * - "${D.ClOrdID}" → Same using tag name
     *
     * @param value The string containing template expressions
     * @param incomingMessages Map of latest incoming messages by type (e.g., "D" -> NewOrderSingle message)
     * @param outgoingMessages Map of latest outgoing messages by type (e.g., "R" -> QuoteRequest message)
     * @param variables Mutable map to store and retrieve variables across multiple evaluate() calls.
     *                  If null, a new map is created for this call only.
     * @param dictionary Optional FIX data dictionary for tag name resolution in shorthand syntax
     */
    fun evaluate(
        value: String,
        incomingMessages: Map<String, FixMessage> = emptyMap(),
        outgoingMessages: Map<String, FixMessage> = emptyMap(),
        variables: MutableMap<String, String>? = null,
        dictionary: FixDictionaryAdapter? = null,
    ): String {
        // Use provided variables map or create a new one
        val vars = variables ?: mutableMapOf()

        // Expand shorthand syntax before evaluation (fast string processing)
        val expandedValue = ShorthandTemplateExpander.expand(value, dictionary)

        return EXPRESSION_REGEX.replace(expandedValue) { matchResult ->
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
            val evaluationStart = System.currentTimeMillis()
            logger.debug("Evaluating expression: {}", expression)
            logger.debug("Available incoming message types: {}", incomingMessages.keys.joinToString(","))
            logger.debug("Available outgoing message types: {}", outgoingMessages.keys.joinToString(","))

            // Performance optimization: Check if expression references messages
            // If not, we can skip expensive field extraction
            val needsIncoming = expression.contains("incoming[")
            val needsOutgoing = expression.contains("outgoing[")

            logger.debug("Expression needs incoming: $needsIncoming, outgoing: $needsOutgoing")

            // Performance optimization: Only extract message data if the expression needs it
            // This avoids expensive field extraction + hasGroup() checks for simple expressions
            val extractionStart = System.currentTimeMillis()
            val incomingData: Map<String, MessageAccessor> =
                if (needsIncoming && incomingMessages.isNotEmpty()) {
                    incomingMessages.mapValues { (_, msg) ->
                        // Use cache to avoid re-extracting the same message
                        extractedDataCache.getOrPut(msg.quickfixMessage) {
                            extractAllFields(msg.quickfixMessage)
                        }.let { MessageAccessor(it) }
                    }
                } else {
                    emptyMap()
                }

            val outgoingData: Map<String, MessageAccessor> =
                if (needsOutgoing && outgoingMessages.isNotEmpty()) {
                    outgoingMessages.mapValues { (_, msg) ->
                        // Use cache to avoid re-extracting the same message
                        extractedDataCache.getOrPut(msg.quickfixMessage) {
                            extractAllFields(msg.quickfixMessage)
                        }.let { MessageAccessor(it) }
                    }
                } else {
                    emptyMap()
                }
            val extractionDuration = System.currentTimeMillis() - extractionStart
            if (extractionDuration > 50) {
                logger.warn("Message extraction took ${extractionDuration}ms")
            }

            // Evaluate the complete script using cached script engine
            val scriptStart = System.currentTimeMillis()
            val result = evalExpressionWithContext(expression, variables, incomingData, outgoingData)
            val scriptDuration = System.currentTimeMillis() - scriptStart

            val resultStr = result?.toString() ?: "null"

            val totalDuration = System.currentTimeMillis() - evaluationStart
            if (totalDuration > 100) {
                logger.warn(
                    "SLOW EVALUATION: Total ${totalDuration}ms (extraction: ${extractionDuration}ms, script: ${scriptDuration}ms) for expression: $expression",
                )
            }
            logger.debug("Expression '{}' evaluated to: {} in {}ms", expression, resultStr, totalDuration)
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

    /**
     * PERFORMANCE OPTIMIZED: Evaluates multiple field values in a single batch.
     * This avoids rebuilding the script helper code and message extraction for each field.
     *
     * @param fieldsWithExpressions List of pairs (fieldIndex, fieldValue) for fields that have expressions
     * @param incomingMessages Map of latest incoming messages by type
     * @param outgoingMessages Map of latest outgoing messages by type
     * @param variables Shared mutable map for variables across all evaluations
     * @param dictionary Optional FIX data dictionary for tag name resolution in shorthand syntax
     * @return Map of fieldIndex to resolved value
     */
    fun evaluateBatch(
        fieldsWithExpressions: List<Pair<Int, String>>,
        incomingMessages: Map<String, FixMessage> = emptyMap(),
        outgoingMessages: Map<String, FixMessage> = emptyMap(),
        variables: MutableMap<String, String> = mutableMapOf(),
        dictionary: FixDictionaryAdapter? = null,
    ): Map<Int, String> {
        if (fieldsWithExpressions.isEmpty()) return emptyMap()

        val batchStart = System.currentTimeMillis()
        val results = mutableMapOf<Int, String>()

        // Expand shorthand syntax in all fields first (fast string processing)
        val expandedFields = fieldsWithExpressions.map { (index, value) ->
            index to ShorthandTemplateExpander.expand(value, dictionary)
        }

        // Check if ANY expression needs message data (after shorthand expansion)
        val allValues = expandedFields.map { it.second }
        val needsIncoming = allValues.any { it.contains("incoming[") }
        val needsOutgoing = allValues.any { it.contains("outgoing[") }

        val incomingData: Map<String, MessageAccessor> =
            if (needsIncoming && incomingMessages.isNotEmpty()) {
                incomingMessages.mapValues { (_, msg) ->
                    extractedDataCache.getOrPut(msg.quickfixMessage) {
                        extractAllFields(msg.quickfixMessage)
                    }.let { MessageAccessor(it) }
                }
            } else emptyMap()

        val outgoingData: Map<String, MessageAccessor> =
            if (needsOutgoing && outgoingMessages.isNotEmpty()) {
                outgoingMessages.mapValues { (_, msg) ->
                    extractedDataCache.getOrPut(msg.quickfixMessage) {
                        extractAllFields(msg.quickfixMessage)
                    }.let { MessageAccessor(it) }
                }
            } else emptyMap()

        // Now evaluate each field - we can process sequentially to support variable assignments
        for ((fieldIndex, fieldValue) in expandedFields) {
            try {
                val resolvedValue = EXPRESSION_REGEX.replace(fieldValue) { matchResult ->
                    val expression = matchResult.groupValues[1].trim()

                    // Check for variable reference first (fast path)
                    if (VARIABLE_REGEX.matches(expression) && expression in variables) {
                        return@replace variables[expression]!!
                    }

                    // Check for variable assignment
                    val assignmentMatch = ASSIGNMENT_REGEX.matchEntire(expression)
                    val (varName, actualExpression) = if (assignmentMatch != null) {
                        assignmentMatch.groupValues[1] to assignmentMatch.groupValues[2]
                    } else {
                        null to expression
                    }

                    try {
                        val result =
                            evalExpressionWithContext(
                                actualExpression,
                                variables,
                                incomingData,
                                outgoingData,
                            )?.toString() ?: "null"
                        // Store variable if this was an assignment
                        if (varName != null) {
                            variables[varName] = result
                        }
                        result
                    } catch (e: Exception) {
                        logger.warn("Failed to evaluate expression '$expression': ${e.message}")
                        "\${$expression}"
                    }
                }
                results[fieldIndex] = resolvedValue
            } catch (e: Exception) {
                logger.warn("Failed to resolve field $fieldIndex: ${e.message}")
                results[fieldIndex] = fieldValue // Return original on error
            }
        }

        val batchDuration = System.currentTimeMillis() - batchStart
        if (batchDuration > 100) {
            logger.info("Batch evaluation of ${fieldsWithExpressions.size} fields took ${batchDuration}ms")
        }

        return results
    }

    /**
     * Validates all template expressions in a value and returns validation errors
     * @param value The string containing template expressions to validate
     * @param incomingMessages Map of latest incoming messages by type
     * @param outgoingMessages Map of latest outgoing messages by type
     * @param dictionary Optional FIX data dictionary for tag name resolution in shorthand syntax
     * @return List of error messages, empty if all expressions are valid
     */
    fun validateExpressions(
        value: String,
        incomingMessages: Map<String, FixMessage> = emptyMap(),
        outgoingMessages: Map<String, FixMessage> = emptyMap(),
        dictionary: FixDictionaryAdapter? = null,
    ): List<String> {
        val errors = mutableListOf<String>()
        val vars = mutableMapOf<String, String>()

        // First validate shorthand syntax (fast check for unknown tag names)
        errors.addAll(ShorthandTemplateExpander.validateShorthand(value, dictionary))

        // Expand shorthand before validating the full expressions
        val expandedValue = ShorthandTemplateExpander.expand(value, dictionary)

        EXPRESSION_REGEX.findAll(expandedValue).forEach { matchResult ->
            val expression = matchResult.groupValues[1].trim()
            try {
                // Try to evaluate the expression - will throw if invalid
                validateSingleExpression(expression, incomingMessages, outgoingMessages, vars)
            } catch (e: ScriptException) {
                // Extract meaningful error message from script exception
                val errorMsg = e.message?.lines()?.firstOrNull { it.isNotBlank() } ?: "Script evaluation failed"
                errors.add("Template error in '\${$expression}': $errorMsg")
            } catch (e: Exception) {
                errors.add("Template error in '\${$expression}': ${e.message ?: e.javaClass.simpleName}")
            }
        }

        return errors
    }

    /**
     * Validates a single expression by attempting to evaluate it
     * Throws exception if evaluation fails (used for validation)
     */
    private fun validateSingleExpression(
        expression: String,
        incomingMessages: Map<String, FixMessage>,
        outgoingMessages: Map<String, FixMessage>,
        variables: MutableMap<String, String>,
    ) {
        // Check if this is a variable assignment
        val assignmentMatch = ASSIGNMENT_REGEX.matchEntire(expression)
        if (assignmentMatch != null) {
            val varName = assignmentMatch.groupValues[1]
            val varExpression = assignmentMatch.groupValues[2]
            // Validate and evaluate the expression (will throw on error)
            val result = evaluateKotlinExpressionValidating(varExpression, incomingMessages, outgoingMessages, variables)
            if (result == "null") {
                throw IllegalArgumentException("Expression evaluates to null")
            }
            variables[varName] = result
            return
        }

        // Check if this is a simple variable reference
        if (VARIABLE_REGEX.matches(expression)) {
            // Variable reference - just check if it exists
            if (expression !in variables) {
                throw IllegalArgumentException("Undefined variable: $expression")
            }
            return
        }

        // Otherwise, evaluate as a Kotlin expression (will throw on error)
        val result = evaluateKotlinExpressionValidating(expression, incomingMessages, outgoingMessages, variables)
        if (result == "null") {
            throw IllegalArgumentException("Expression evaluates to null")
        }
    }

    /**
     * Evaluates a Kotlin expression for validation (throws exceptions instead of catching them)
     */
    private fun evaluateKotlinExpressionValidating(
        expression: String,
        incomingMessages: Map<String, FixMessage>,
        outgoingMessages: Map<String, FixMessage>,
        variables: MutableMap<String, String>,
    ): String {
        val needsIncoming = expression.contains("incoming[")
        val needsOutgoing = expression.contains("outgoing[")

        val incomingData: Map<String, MessageAccessor> =
            if (needsIncoming && incomingMessages.isNotEmpty()) {
                incomingMessages.mapValues { (_, msg) ->
                    extractedDataCache.getOrPut(msg.quickfixMessage) {
                        extractAllFields(msg.quickfixMessage)
                    }.let { MessageAccessor(it) }
                }
            } else {
                emptyMap()
            }

        val outgoingData: Map<String, MessageAccessor> =
            if (needsOutgoing && outgoingMessages.isNotEmpty()) {
                outgoingMessages.mapValues { (_, msg) ->
                    extractedDataCache.getOrPut(msg.quickfixMessage) {
                        extractAllFields(msg.quickfixMessage)
                    }.let { MessageAccessor(it) }
                }
            } else {
                emptyMap()
            }

        val result = evalExpressionWithContext(expression, variables, incomingData, outgoingData)

        return result?.toString() ?: "null"
    }
}
