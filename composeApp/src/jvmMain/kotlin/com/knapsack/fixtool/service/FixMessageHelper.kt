package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import quickfix.DataDictionary
import quickfix.FieldMap
import quickfix.Group
import quickfix.Message
import quickfix.field.MsgType

object FixMessageHelper {
    /**
     * Parses a raw FIX message string (with | delimiters) into a QuickFIX Message
     * Uses QuickFIX/J's native fromString method with the configured data dictionary
     * @param validate if true, validates the message against the data dictionary
     */
    fun String.toQuickFixMessage(
        dataDictionary: DataDictionary,
        validate: Boolean = false,
    ): Message = Message(this.toWireFixMessage(), dataDictionary, validate)

    fun String.toQuickFixMessage(): Message = Message(this.toWireFixMessage(), false)

    /**
     * Manually constructs a QuickFIX Message from a raw FIX string using recursive parsing
     * This is useful for complex messages with nested groups that QuickFIX's native parser may struggle with
     *
     * The implementation uses delimiter tracking and ancestor delimiter awareness to correctly
     * handle groups within groups (e.g., NoPartySubIDs within NoPartyIDs within NoOrders).
     *
     * @param dataDictionary The FIX data dictionary for parsing
     * @param fixVersion The FIX version to use for header/trailer tag detection (defaults to FIX 4.4)
     */
    fun String.toQuickFixMessageManual(
        dataDictionary: DataDictionary,
        fixVersion: FixVersion = FixVersion.DEFAULT,
    ): Message {
        // Parse message into tag-value pairs
        val fields = parseFixMessage(this)

        // Get message type from tag 35
        val msgTypeValue =
            fields.find { it.first == 35 }?.second
                ?: throw IllegalArgumentException("No message type (tag 35) found in message")

        // Create message with proper type
        val message = Message()
        message.header.setString(MsgType.FIELD, msgTypeValue)

        // Use version-aware header/trailer tags
        val headerTags = FixVersion.getHeaderTags(fixVersion)
        val trailerTags = FixVersion.getTrailerTags(fixVersion)

        // Process header fields
        fields.filter { it.first in headerTags }.forEach { (tag, value) ->
            message.header.setString(tag, value)
        }

        // Process trailer fields
        fields.filter { it.first in trailerTags }.forEach { (tag, value) ->
            message.trailer.setString(tag, value)
        }

        // Process body fields recursively
        val bodyFields = fields.filter { it.first !in headerTags && it.first !in trailerTags }
        processFields(bodyFields, 0, message, dataDictionary, msgTypeValue)

        return message
    }

    /**
     * Manually constructs a QuickFIX Message from a raw FIX string using a FixDictionaryAdapter.
     * This is a convenience method that extracts version information from the adapter.
     */
    fun String.toQuickFixMessageManual(dictionaryAdapter: FixDictionaryAdapter): Message {
        val dataDictionary =
            dictionaryAdapter.getDataDictionary()
                ?: throw IllegalStateException("No data dictionary loaded in adapter")
        return this.toQuickFixMessageManual(dataDictionary, dictionaryAdapter.fixVersion)
    }

    /**
     * Parses a FIX message string into a list of (tag, value) pairs, establishing the delimiter from the
     * string itself — **SOH wins**, and the precedence is the whole point.
     *
     * It is not a guess, because the two forms are totally discriminated: a wire string always contains
     * SOH (FIX terminates every field with it, `8=FIX.4.4<SOH>` included), and a display or editor string
     * never does, because producing one replaces every SOH with `|`.
     *
     * This used to ask `contains('|')` first, which is the same test run backwards, and it failed on the
     * one case that matters: `|` is an *ordinary character inside a FIX value*. A venue rejecting an order
     * with `58=Rejected|insufficient margin` sends a perfectly good SOH-delimited message that happens to
     * contain a pipe — and the old precedence split it on the pipe, shredding the message into fields the
     * venue never sent. That reached the expectation builder through a captured `golden`, where every row
     * lost its captured value, and the reconcile view, where "Accept actual" would have written a
     * truncated value into the scenario as the thing to assert from then on.
     */
    internal fun parseFixMessage(raw: String): List<Pair<Int, String>> =
        parseFixMessage(raw, delimiter = delimiterOf(raw))

    /**
     * **The one delimiter decider**, exposed because the paste box has to report what it read — and a second
     * copy of this rule would be a second answer to the only question that matters about a pasted message.
     *
     * It is not a guess: a wire string always contains SOH, and a display or editor string never does, because
     * producing one replaces every SOH with `|`. What it *cannot* tell you is whether a `|` in a pipe-rendered
     * string was a delimiter or a character inside a value — nothing can, from the string alone. That is not
     * this function's business to guess at either; see `WirePaste`, which asks the message's own arithmetic.
     */
    fun delimiterOf(raw: String): Char = if (raw.contains(SOH)) SOH else '|'

    /** Parses with a **known** delimiter — no guessing, so a `|` inside a value stays inside it. */
    internal fun parseFixMessage(raw: String, delimiter: Char): List<Pair<Int, String>> =
        raw
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

    /**
     * The fields of a captured message, in wire order, read from the bytes the venue actually sent —
     * or **null when we do not have those bytes**.
     *
     * The single door the assertion engine and the capture path both go through, and it does not guess.
     * It used to fall back to the `|`-substituted display string, which read as a harmless convenience
     * and was not one: on the very path where [FixMessage.wireRaw] was missing, that display string was
     * itself built from `quickfix.Message.toString()` — so the "fallback" handed the engine a body sorted
     * by ascending tag with every repeating group relocated to the end of it. Under the subsequence rule
     * that is a message no venue sent, and the step went red citing the venue.
     *
     * Null is the honest answer, and a caller that asserts must **report** it rather than route around
     * it: a scenario that cannot check order is not a scenario that passes. For rendering — where a
     * best-effort field list beats a blank pane — use [fieldsForDisplay].
     */
    fun wireFields(message: FixMessage): List<Pair<Int, String>>? =
        message.wireRaw?.let { parseFixMessage(it, delimiter = '\u0001') }

    /** The FIX field delimiter. `|` is the display substitution for it, and only ever that. */
    const val SOH: Char = '\u0001'

    /**
     * Best-effort fields **for rendering only**: the wire bytes when we have them, the lossy display
     * string when we do not. Never for assertions — the order may be QuickFIX's rather than the venue's,
     * and a `|` inside a value splits one field into two. See [wireFields].
     */
    fun fieldsForDisplay(message: FixMessage): List<Pair<Int, String>> =
        wireFields(message) ?: parseFixMessage(message.rawMessage)

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

            // IMPORTANT: If we encounter a non-delimiter field that's already set in the current group,
            // this means we've moved to the next group instance. This handles cases where the template
            // doesn't follow FIX protocol order (fields appearing before the delimiter).
            if (delimiterTag != null && tag != delimiterTag && fieldMap.isSetField(tag)) {
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
