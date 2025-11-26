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
            val evaluationStart = System.currentTimeMillis()
            logger.debug("Evaluating expression: {}", expression)
            logger.debug("Available incoming message types: {}", incomingMessages.keys.joinToString(","))
            logger.debug("Available outgoing message types: {}", outgoingMessages.keys.joinToString(","))

            // Performance optimization: Check if expression references messages
            // If not, we can skip expensive field extraction
            val needsIncoming = expression.contains("incoming[")
            val needsOutgoing = expression.contains("outgoing[")

            logger.debug("Expression needs incoming: $needsIncoming, outgoing: $needsOutgoing")

            // Build helper objects that can be serialized into the script
            // Extract message data into simple maps
            // Helper to escape strings for Kotlin script literals
            fun String.escapeForKotlinString(): String =
                this
                    .replace("\\", "\\\\") // Escape backslashes first
                    .replace("\"", "\\\"") // Escape double quotes
                    .replace("$", "\\$") // Escape dollar signs (template expressions)

            // Helper function to extract all fields including from repeating groups
            // OPTIMIZED: Only check hasGroup() for known group tags (O(n) instead of O(n²))
            fun extractAllFields(fieldMap: quickfix.FieldMap): Map<Int, List<String>> {
                val startTime = System.currentTimeMillis()
                val tags = mutableMapOf<Int, MutableList<String>>()
                var fieldCount = 0
                var actualGroupsFound = 0

                fieldMap.iterator().forEach { field ->
                    fieldCount++

                    // Always add the field value first
                    tags.getOrPut(field.tag) { mutableListOf() }.add(field.`object`.toString())

                    // OPTIMIZATION: Only check for groups if this is a known group count tag
                    // This avoids expensive hasGroup() calls on every field
                    if (field.tag in KNOWN_GROUP_TAGS) {
                        try {
                            if (fieldMap.hasGroup(field.tag)) {
                                actualGroupsFound++
                                val groupCount = fieldMap.getInt(field.tag)

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
                                        // Skip failed group extraction
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Not a group count field, skip
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

            // Performance optimization: Only extract message data if the expression needs it
            // This avoids expensive field extraction + hasGroup() checks for simple expressions
            val extractionStart = System.currentTimeMillis()
            val incomingData =
                if (needsIncoming && incomingMessages.isNotEmpty()) {
                    incomingMessages.mapValues { (_, msg) ->
                        // Use cache to avoid re-extracting the same message
                        extractedDataCache.getOrPut(msg.quickfixMessage) {
                            extractAllFields(msg.quickfixMessage)
                        }
                    }
                } else {
                    emptyMap()
                }

            val outgoingData =
                if (needsOutgoing && outgoingMessages.isNotEmpty()) {
                    outgoingMessages.mapValues { (_, msg) ->
                        // Use cache to avoid re-extracting the same message
                        extractedDataCache.getOrPut(msg.quickfixMessage) {
                            extractAllFields(msg.quickfixMessage)
                        }
                    }
                } else {
                    emptyMap()
                }
            val extractionDuration = System.currentTimeMillis() - extractionStart
            if (extractionDuration > 50) {
                logger.warn("Message extraction took ${extractionDuration}ms")
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

            // Evaluate the complete script using cached script engine
            val scriptStart = System.currentTimeMillis()
            val result = scriptEngine.eval(helperCode)
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
     * @return Map of fieldIndex to resolved value
     */
    fun evaluateBatch(
        fieldsWithExpressions: List<Pair<Int, String>>,
        incomingMessages: Map<String, FixMessage> = emptyMap(),
        outgoingMessages: Map<String, FixMessage> = emptyMap(),
        variables: MutableMap<String, String> = mutableMapOf(),
    ): Map<Int, String> {
        if (fieldsWithExpressions.isEmpty()) return emptyMap()

        val batchStart = System.currentTimeMillis()
        val results = mutableMapOf<Int, String>()

        // Check if ANY expression needs message data
        val allValues = fieldsWithExpressions.map { it.second }
        val needsIncoming = allValues.any { it.contains("incoming[") }
        val needsOutgoing = allValues.any { it.contains("outgoing[") }

        // Helper to escape strings
        fun String.escapeForKotlinString(): String =
            this
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")

        // Extract message data ONCE for all expressions
        fun extractAllFieldsOptimized(fieldMap: quickfix.FieldMap): Map<Int, List<String>> {
            val tags = mutableMapOf<Int, MutableList<String>>()
            fieldMap.iterator().forEach { field ->
                tags.getOrPut(field.tag) { mutableListOf() }.add(field.`object`.toString())
                if (field.tag in KNOWN_GROUP_TAGS) {
                    try {
                        if (fieldMap.hasGroup(field.tag)) {
                            val groupCount = fieldMap.getInt(field.tag)
                            for (i in 1..groupCount) {
                                try {
                                    val group = fieldMap.getGroup(i, field.tag)
                                    val groupTags = extractAllFieldsOptimized(group)
                                    groupTags.forEach { (tag, values) ->
                                        tags.getOrPut(tag) { mutableListOf() }.addAll(values)
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
            return tags.mapValues { it.value.toList() }.toMap()
        }

        val incomingData = if (needsIncoming && incomingMessages.isNotEmpty()) {
            incomingMessages.mapValues { (_, msg) ->
                extractedDataCache.getOrPut(msg.quickfixMessage) {
                    extractAllFieldsOptimized(msg.quickfixMessage)
                }
            }
        } else emptyMap()

        val outgoingData = if (needsOutgoing && outgoingMessages.isNotEmpty()) {
            outgoingMessages.mapValues { (_, msg) ->
                extractedDataCache.getOrPut(msg.quickfixMessage) {
                    extractAllFieldsOptimized(msg.quickfixMessage)
                }
            }
        } else emptyMap()

        // Build the base helper code ONCE (imports, classes, message data)
        val baseHelperCode = buildString {
            appendLine("import java.util.UUID")
            appendLine("import java.time.LocalDateTime")
            appendLine("import java.time.format.DateTimeFormatter")
            appendLine("import java.time.Instant")
            appendLine()
            appendLine("class MessageAccessor(private val tags: Map<Int, List<String>>) {")
            appendLine("    fun valueOfTag(tag: Int): String? = tags[tag]?.firstOrNull()")
            appendLine("    fun valueOfTag(tag: Int, index: Int): String? = tags[tag]?.getOrNull(index)")
            appendLine("    fun allValuesOfTag(tag: Int): List<String> = tags[tag] ?: emptyList()")
            appendLine("    operator fun get(tag: Int): String? = valueOfTag(tag)")
            appendLine("}")
            appendLine()
            appendLine("class MessageMap(private val messages: Map<String, MessageAccessor>) {")
            appendLine("    operator fun get(msgType: String): MessageAccessor = messages[msgType] ?: MessageAccessor(emptyMap())")
            appendLine("}")
            appendLine()

            // Build incoming data
            append("val incoming = MessageMap(mapOf<String, MessageAccessor>(")
            if (incomingData.isNotEmpty()) {
                appendLine()
                incomingData.entries.forEachIndexed { index, (msgType, tags) ->
                    val tagsStr = tags.entries.joinToString(", ") { (tag, values) ->
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

            // Build outgoing data
            append("val outgoing = MessageMap(mapOf<String, MessageAccessor>(")
            if (outgoingData.isNotEmpty()) {
                appendLine()
                outgoingData.entries.forEachIndexed { index, (msgType, tags) ->
                    val tagsStr = tags.entries.joinToString(", ") { (tag, values) ->
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

            // Mutable map for variables
            appendLine("val __vars = mutableMapOf<String, String>()")
            if (variables.isNotEmpty()) {
                variables.forEach { (varName, value) ->
                    appendLine("__vars[\"$varName\"] = \"${value.escapeForKotlinString()}\"")
                }
            }
            appendLine()
        }

        // Now evaluate each field - we can process sequentially to support variable assignments
        for ((fieldIndex, fieldValue) in fieldsWithExpressions) {
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

                    // Build script for this expression
                    val script = buildString {
                        append(baseHelperCode)
                        // Add current variables
                        variables.forEach { (vName, vValue) ->
                            appendLine("val $vName = \"${vValue.escapeForKotlinString()}\"")
                        }
                        appendLine()
                        appendLine(actualExpression)
                    }

                    try {
                        val result = scriptEngine.eval(script)?.toString() ?: "null"
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
     * @return List of error messages, empty if all expressions are valid
     */
    fun validateExpressions(
        value: String,
        incomingMessages: Map<String, FixMessage> = emptyMap(),
        outgoingMessages: Map<String, FixMessage> = emptyMap(),
    ): List<String> {
        val errors = mutableListOf<String>()
        val vars = mutableMapOf<String, String>()

        EXPRESSION_REGEX.findAll(value).forEach { matchResult ->
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
        // Performance optimization: Check if expression references messages
        val needsIncoming = expression.contains("incoming[")
        val needsOutgoing = expression.contains("outgoing[")

        // Helper to escape strings for Kotlin script literals
        fun String.escapeForKotlinString(): String =
            this
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")

        // Helper function to extract all fields including from repeating groups
        // OPTIMIZED: Only check hasGroup() for known group tags
        fun extractAllFields(fieldMap: quickfix.FieldMap): Map<Int, List<String>> {
            val tags = mutableMapOf<Int, MutableList<String>>()
            fieldMap.iterator().forEach { field ->
                tags.getOrPut(field.tag) { mutableListOf() }.add(field.`object`.toString())
                // OPTIMIZATION: Only check for groups if this is a known group count tag
                if (field.tag in KNOWN_GROUP_TAGS) {
                    try {
                        if (fieldMap.hasGroup(field.tag)) {
                            val groupCount = fieldMap.getInt(field.tag)
                            for (i in 1..groupCount) {
                                try {
                                    val group = fieldMap.getGroup(i, field.tag)
                                    val groupTags = extractAllFields(group)
                                    groupTags.forEach { (tag, values) ->
                                        tags.getOrPut(tag) { mutableListOf() }.addAll(values)
                                    }
                                } catch (e: Exception) {
                                    // Skip failed group extraction
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Not a group count field
                    }
                }
            }
            return tags.mapValues { it.value.toList() }.toMap()
        }

        val incomingData =
            if (needsIncoming && incomingMessages.isNotEmpty()) {
                incomingMessages.mapValues { (_, msg) ->
                    extractedDataCache.getOrPut(msg.quickfixMessage) {
                        extractAllFields(msg.quickfixMessage)
                    }
                }
            } else {
                emptyMap()
            }

        val outgoingData =
            if (needsOutgoing && outgoingMessages.isNotEmpty()) {
                outgoingMessages.mapValues { (_, msg) ->
                    extractedDataCache.getOrPut(msg.quickfixMessage) {
                        extractAllFields(msg.quickfixMessage)
                    }
                }
            } else {
                emptyMap()
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
                appendLine("    fun valueOfTag(tag: Int): String? = tags[tag]?.firstOrNull()")
                appendLine("    fun valueOfTag(tag: Int, index: Int): String? = tags[tag]?.getOrNull(index)")
                appendLine("    fun allValuesOfTag(tag: Int): List<String> = tags[tag] ?: emptyList()")
                appendLine("    operator fun get(tag: Int): String? = valueOfTag(tag)")
                appendLine("}")
                appendLine()
                appendLine("class MessageMap(private val messages: Map<String, MessageAccessor>) {")
                appendLine("    operator fun get(msgType: String): MessageAccessor = messages[msgType] ?: MessageAccessor(emptyMap())")
                appendLine("}")
                appendLine()

                if (variables.isNotEmpty()) {
                    appendLine("// User-defined variables")
                    variables.forEach { (varName, value) ->
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

        // Evaluate - let exceptions bubble up for validation
        val result = scriptEngine.eval(helperCode)
        return result?.toString() ?: "null"
    }
}
