package com.knapsack.fixtool.service

import quickfix.DataDictionary
import quickfix.FieldMap
import quickfix.Group
import quickfix.Message
import quickfix.field.MsgType

object FixMessageHelper {
    /**
     * Parses a raw FIX message string (with | delimiters) into a QuickFIX Message
     * Uses QuickFIX/J's native fromString method with the configured data dictionary
     */
    fun String.toQuickFixMessage(
        dataDictionary: DataDictionary,
    ): Message = Message(this.toWireFixMessage(), dataDictionary, false)

    fun String.toQuickFixMessage(): Message = Message(this.toWireFixMessage(), false)

    /**
     * Manually constructs a QuickFIX Message from a raw FIX string using recursive parsing
     * This is useful for complex messages with nested groups that QuickFIX's native parser may struggle with
     *
     * The implementation uses delimiter tracking and ancestor delimiter awareness to correctly
     * handle groups within groups (e.g., NoPartySubIDs within NoPartyIDs within NoOrders).
     */
    fun String.toQuickFixMessageManual(dataDictionary: DataDictionary): Message {
        // Parse message into tag-value pairs
        val fields = parseFixMessage(this)

        // Get message type from tag 35
        val msgTypeValue =
            fields.find { it.first == 35 }?.second
                ?: throw IllegalArgumentException("No message type (tag 35) found in message")

        // Create message with proper type
        val message = Message()
        message.header.setString(MsgType.FIELD, msgTypeValue)

        // Process header fields (tags 8, 9, 35, 49, 56, etc.)
        val headerTags = setOf(8, 9, 35, 49, 56, 34, 43, 52, 122, 212, 213, 347, 369, 627, 628, 629, 630)
        fields.filter { it.first in headerTags }.forEach { (tag, value) ->
            message.header.setString(tag, value)
        }

        // Process trailer fields (tag 10)
        val trailerTags = setOf(10, 93, 89)
        fields.filter { it.first in trailerTags }.forEach { (tag, value) ->
            message.trailer.setString(tag, value)
        }

        // Process body fields recursively
        val bodyFields = fields.filter { it.first !in headerTags && it.first !in trailerTags }
        processFields(bodyFields, 0, message, dataDictionary, msgTypeValue)

        return message
    }

    /**
     * Parses a FIX message string into a list of (tag, value) pairs
     */
    private fun parseFixMessage(raw: String): List<Pair<Int, String>> {
        val delimiter = if (raw.contains('|')) '|' else '\u0001'
        return raw
            .split(delimiter)
            .filter { it.isNotBlank() }
            .mapNotNull { field ->
                val parts = field.split('=', limit = 2)
                if (parts.size == 2) {
                    val tag = parts[0].toIntOrNull()
                    if (tag != null) tag to parts[1] else null
                } else {
                    null
                }
            }
    }

    /**
     * Recursively processes fields and groups, returning the index of the next unprocessed field
     *
     * @param fields List of tag-value pairs to process
     * @param startIndex Starting index in the fields list
     * @param fieldMap The message or group to populate
     * @param dataDictionary The FIX data dictionary
     * @param msgType Message type (e.g., "E" for NewOrderList)
     * @param parentGroupDD Data dictionary for the current group context (null for message level)
     * @param delimiterTag Delimiter tag for the current group (null for message level)
     * @param ancestorDelimiters Set of delimiter tags from all ancestor groups
     * @return Index of the next unprocessed field
     */
    private fun processFields(
        fields: List<Pair<Int, String>>,
        startIndex: Int,
        fieldMap: FieldMap,
        dataDictionary: DataDictionary,
        msgType: String,
        parentGroupDD: DataDictionary? = null,
        delimiterTag: Int? = null,
        ancestorDelimiters: Set<Int> = emptySet(),
    ): Int {
        var index = startIndex
        var seenDelimiter = delimiterTag == null // If no delimiter, consider it seen

        while (index < fields.size) {
            val (tag, value) = fields[index]

            // Use the appropriate DataDictionary based on context
            val currentDD = parentGroupDD ?: dataDictionary

            // If we encounter any ancestor delimiter, we've moved to a new instance of an outer group
            if (tag in ancestorDelimiters) {
                return index
            }

            // If we're in a group and we see the delimiter again after already seeing it once,
            // it means we've reached the next instance of the parent group
            if (delimiterTag != null && tag == delimiterTag && seenDelimiter) {
                return index
            }

            // Mark that we've seen the delimiter
            if (delimiterTag != null && tag == delimiterTag) {
                seenDelimiter = true
            }

            // Check if this field belongs to the current context (message or group)
            val isFieldInContext =
                if (parentGroupDD != null) {
                    try {
                        currentDD.isField(tag) || currentDD.isGroup(msgType, tag)
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    true // At message level, all fields are valid
                }

            // If field doesn't belong to current group context, return to parent
            if (!isFieldInContext) {
                return index
            }

            // Check if this is a group count field using the CURRENT context's DataDictionary
            // This is crucial for nested groups (e.g., NoPartyIDs within NoOrders)
            val isGroup =
                try {
                    currentDD.isGroup(msgType, tag)
                } catch (e: Exception) {
                    false
                }

            if (isGroup) {
                val groupCount = value.toIntOrNull() ?: 0
                index++ // Move past the group count field

                if (groupCount > 0) {
                    try {
                        // Get the group info from the CURRENT context's data dictionary
                        val groupInfo = currentDD.getGroup(msgType, tag)
                        val groupDD = groupInfo.dataDictionary
                        val groupDelimiterTag = groupInfo.delimiterField

                        // Process each group instance
                        for (i in 1..groupCount) {
                            val group = Group(tag, groupDelimiterTag)

                            // Build the set of ancestor delimiters for nested groups to respect
                            val newAncestorDelimiters =
                                if (delimiterTag != null) {
                                    ancestorDelimiters + delimiterTag
                                } else {
                                    ancestorDelimiters
                                }

                            // Recursively process this group's fields
                            index =
                                processFields(
                                    fields,
                                    index,
                                    group,
                                    dataDictionary,
                                    msgType,
                                    groupDD,
                                    groupDelimiterTag,
                                    newAncestorDelimiters,
                                )

                            // Add the populated group to the parent field map
                            fieldMap.addGroup(group)
                        }
                    } catch (e: Exception) {
                        // If we can't process the group, skip it silently
                    }
                }
            } else {
                // Regular field - just set it
                try {
                    fieldMap.setString(tag, value)
                } catch (e: Exception) {
                    // Skip fields that can't be set (e.g., unknown fields)
                }
                index++
            }
        }

        return index
    }

    fun String.toWireFixMessage() = this.replace('|', '\u0001')

    fun String.toRawFixMessage() = this.replace('\u0001', '|')

    fun Message.toRawFixMessage() = this.toString().replace('\u0001', '|')

    /**
     * Normalizes a FIX message from line-based format to traditional format.
     * Supports two formats:
     * 1. Traditional: "35=R|131=ORD-1|"
     * 2. Line-based: "35 R\n131 ORD-1\n# comment"
     *
     * Line-based format rules:
     * - Each line is "tag value" (space-separated)
     * - Lines starting with # are comments (ignored)
     * - Blank lines are ignored
     * - Extra whitespace is trimmed
     * - Supports both \n (Mac/Unix) and \r\n (Windows) line endings
     *
     * @return Message in traditional format "tag=value|tag=value|"
     */
    fun String.normalizeFixMessage(): String {
        if (this.isBlank()) return ""

        // Detect format: if contains newlines and limited use of '=' or '|', use line-based format
        val hasNewlines = this.contains('\n')
        val hasEquals = this.contains('=')
        val hasPipes = this.contains('|')

        // If already in traditional format, return as-is
        if (!hasNewlines || (hasEquals && hasPipes)) {
            return this
        }

        // Convert line-based format to traditional format
        val fields =
            this
                .lines() // Split by newlines (handles both \n and \r\n)
                .map { line ->
                    // Strip inline comments (everything after #)
                    val commentIndex = line.indexOf('#')
                    if (commentIndex >= 0) {
                        line.substring(0, commentIndex).trim()
                    } else {
                        line.trim()
                    }
                }.filter { it.isNotBlank() } // Remove blank lines
                .mapNotNull { line ->
                    // Split by whitespace, taking first token as tag and rest as value
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) {
                        val tag = parts[0].trim()
                        val value = parts[1].trim()
                        // Validate tag is numeric
                        if (tag.toIntOrNull() != null) {
                            "$tag=$value"
                        } else {
                            null // Skip non-numeric tags
                        }
                    } else if (parts.size == 1 && parts[0].toIntOrNull() != null) {
                        // Tag with no value (edge case)
                        "${parts[0]}="
                    } else {
                        null
                    }
                }

        // Return empty string if no fields, otherwise join with pipe delimiter
        return if (fields.isEmpty()) "" else fields.joinToString("|", postfix = "|")
    }
}
