package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixFields
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.compare.GroupOverlay
import com.knapsack.fixtool.service.compare.Salvaged
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
     * Repeating groups, groups within groups included (NoPartySubIDs within NoPartyIDs within NoOrders),
     * are where [GroupWalk] finds them, the same walk the editor and the diff bracket by.
     *
     * @param dataDictionary The FIX data dictionary for parsing
     * @param fixVersion The FIX version to use for header/trailer tag detection (defaults to FIX 4.4)
     * @param adapter When provided, repeating groups the dictionary does not define are still kept
     *   whole — see [salvageableGroups]. Without it they collapse to their last instance, because a
     *   FieldMap can hold one value per tag and every instance writes the same tags.
     */
    fun String.toQuickFixMessageManual(
        dataDictionary: DataDictionary,
        fixVersion: FixVersion = FixVersion.DEFAULT,
        adapter: FixDictionaryAdapter? = null,
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

        // Version-aware header/trailer tags, EXTENDED by the loaded dictionary's own header/trailer
        // sections — venue dialects add custom fields there (a routing tag, a desk id), and the static
        // list alone put such a field in the body: the wire changed, the venue answered "tag specified
        // out of required order", and Validate — reading the same dictionary — saw nothing wrong. Send
        // and Validate must describe the same message. For FIX 5.0 those sections are in the FIXT
        // transport dictionary, because the application dictionary's header and trailer are empty.
        val headerTags = FixVersion.getHeaderTags(fixVersion)
        val trailerTags = FixVersion.getTrailerTags(fixVersion)
        val transport = adapter?.getTransportDictionary()

        fun isHeader(tag: Int) =
            tag in headerTags || dataDictionary.isHeaderField(tag) || transport?.isHeaderField(tag) == true

        fun isTrailer(tag: Int) =
            tag in trailerTags || dataDictionary.isTrailerField(tag) || transport?.isTrailerField(tag) == true

        // Process header fields
        fields.filter { isHeader(it.first) }.forEach { (tag, value) ->
            message.header.setString(tag, value)
        }

        // Process trailer fields
        fields.filter { isTrailer(it.first) }.forEach { (tag, value) ->
            message.trailer.setString(tag, value)
        }

        // Process body fields. Regions the dictionary reads as a repeating group it has no definition
        // for are carved out first and built as real Group instances. Read as ordinary fields they set
        // the same tags over and over on one FieldMap, and an N-instance group collapses to its last
        // instance with no diagnostic.
        val bodyFields = fields.filter { !isHeader(it.first) && !isTrailer(it.first) }
        buildBody(bodyFields, message, dataDictionary, msgTypeValue, adapter)

        return message
    }

    /**
     * The body, built as runs of ordinary fields with the rescued groups carved out between them. Each run,
     * and each rescued entry, is read at message level by [readInto].
     *
     * The rescued groups are walked with a cursor rather than searched. They used to be a map keyed by
     * count row, asked `salvaged.keys.filter { it > i }.minOrNull()` on every iteration of the loop: a scan
     * of every group per row of the message, and an allocation per row even when there were no groups at
     * all, which is the common case.
     */
    private fun buildBody(
        bodyFields: List<Pair<Int, String>>,
        message: Message,
        dataDictionary: DataDictionary,
        msgType: String,
        adapter: FixDictionaryAdapter?,
    ) {
        val salvaged = salvageableGroups(bodyFields, msgType, adapter).sortedBy { it.countRow }
        var next = 0
        var i = 0
        while (i < bodyFields.size) {
            while (next < salvaged.size && salvaged[next].countRow < i) next++
            val group = salvaged.getOrNull(next)?.takeIf { it.countRow == i }
            if (group == null) {
                val end = salvaged.getOrNull(next)?.countRow ?: bodyFields.size
                readInto(message, bodyFields.subList(i, end), dataDictionary, msgType, adapter)
                i = end
            } else {
                // One instance per rescued entry, each read at message level, as a group with no
                // dictionary of its own has no other context to read it in.
                val delimiter = bodyFields[group.entries.first().first].first
                for (entry in group.entries) {
                    val instance = Group(group.groupTag, delimiter)
                    val rows = bodyFields.subList(entry.first, entry.last + 1)
                    readInto(instance, rows, dataDictionary, msgType, adapter)
                    message.addGroup(instance)
                }
                // addGroup normalised the count to the number of entries found. The wire's own claim wins,
                // mismatch included: rendering a count the venue never sent would hide exactly the defect
                // this tool exists to show.
                message.setString(group.groupTag, bodyFields[group.countRow].second)
                i = group.entries.last().last + 1
            }
        }
    }

    /**
     * The repeating groups worth rescuing from a flat parse: those the dictionary does **not**
     * define, whose shape the [GroupOverlay]'s period-detection guess found anyway, and whose count
     * row names them.
     *
     * A venue's custom group — anything in the 5000s a counterparty invented after this dictionary
     * was cut — is still a repeating group on the wire, and flattening it is not a rendering
     * blemish but data loss: the flat FieldMap keeps one value per tag, so only the last instance
     * survives. The count row is required because it is the group's only witness at the top level —
     * `addGroup` keys the group by its count tag, and without one there is no tag to file the
     * entries under.
     *
     * Dictionary-defined groups are absent deliberately, nested ones included: [GroupWalk] already finds
     * those, inside the entry they belong to.
     *
     * Asked through [GroupOverlay.salvageable] rather than by building a whole overlay and filtering it:
     * the answer is the same, and on the messages where it is "none" (which is every conformant one) it
     * is reached without the walk. See that function for the argument that the two cannot disagree.
     */
    private fun salvageableGroups(
        bodyFields: List<Pair<Int, String>>,
        messageType: String,
        adapter: FixDictionaryAdapter?,
    ): List<Salvaged> {
        if (adapter == null) return emptyList()
        return GroupOverlay.salvageable(bodyFields, messageType, adapter)
    }

    /**
     * Manually constructs a QuickFIX Message from a raw FIX string using a FixDictionaryAdapter.
     * This is a convenience method that extracts version information from the adapter.
     */
    fun String.toQuickFixMessageManual(dictionaryAdapter: FixDictionaryAdapter): Message {
        val dataDictionary =
            dictionaryAdapter.getDataDictionary()
                ?: throw IllegalStateException("No data dictionary loaded in adapter")
        return this.toQuickFixMessageManual(dataDictionary, dictionaryAdapter.fixVersion, dictionaryAdapter)
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
    internal fun parseFixMessage(raw: String): List<Pair<Int, String>> = FixFields.parse(raw)

    /**
     * **The one delimiter decider**, exposed because the paste box has to report what it read — and a second
     * copy of this rule would be a second answer to the only question that matters about a pasted message.
     *
     * It is not a guess: a wire string always contains SOH, and a display or editor string never does, because
     * producing one replaces every SOH with `|`. What it *cannot* tell you is whether a `|` in a pipe-rendered
     * string was a delimiter or a character inside a value — nothing can, from the string alone. That is not
     * this function's business to guess at either; see `WirePaste`, which asks the message's own arithmetic.
     */
    fun delimiterOf(raw: String): Char = FixFields.delimiterOf(raw)

    /** Parses with a **known** delimiter — no guessing, so a `|` inside a value stays inside it. */
    internal fun parseFixMessage(raw: String, delimiter: Char): List<Pair<Int, String>> =
        FixFields.parse(raw, delimiter)

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
    fun wireFields(message: FixMessage): List<Pair<Int, String>>? = message.wireFields

    /** The FIX field delimiter. `|` is the display substitution for it, and only ever that. */
    const val SOH: Char = FixFields.SOH

    /**
     * Joins fields into a raw string that [parseFixMessage] reads back to **the same fields**.
     *
     * `|` is the display substitution for SOH, and it is only faithful while no value carries a
     * literal pipe: `58=filled|in full` pipe-joined re-parses as `58=filled` with the tail silently
     * dropped — the write side of the very misread `WirePaste` refuses on the read side. Such a
     * message is joined with SOH instead, which [delimiterOf] gives precedence, so the round-trip
     * is exact. Everything that turns a field list back into a raw (capture's Send steps, the
     * editor's write-back, the editor's and automation's sends, the editor's lint, a load run's
     * prototype) must come through here rather than `joinToString("|")` its own answer. A tag is
     * written as it reads, so the editor's string tags pass through unchanged.
     */
    fun joinFields(fields: List<Pair<Any, String>>): String {
        val delimiter = if (fields.any { it.second.contains('|') }) SOH else '|'
        return fields.joinToString("") { (tag, value) -> "$tag=$value$delimiter" }
    }

    /**
     * Best-effort fields **for rendering only**: the wire bytes when we have them, the lossy display
     * string when we do not. Never for assertions — the order may be QuickFIX's rather than the venue's,
     * and a `|` inside a value splits one field into two. See [wireFields].
     */
    fun fieldsForDisplay(message: FixMessage): List<Pair<Int, String>> = message.displayFields

    /**
     * [fields] onto [target] read at message level, with every repeating group the dictionary defines built
     * as `Group` instances where [GroupWalk] finds it: the one reading of group structure the editor and the
     * diff bracket by too, so the tree the detail pane draws and the overlay beside it cannot disagree.
     *
     * Only a count whose value is a positive integer opens a group here. A count of `0`, or one that is not
     * a number, is set as the field the venue sent, and the rows after it are read where they stand.
     */
    private fun readInto(
        target: FieldMap,
        fields: List<Pair<Int, String>>,
        dataDictionary: DataDictionary,
        msgType: String,
        adapter: FixDictionaryAdapter?,
    ) {
        val groups = GroupWalk.walk(fields, msgType, dataDictionary, adapter, GroupWalk.Counts.POSITIVE)
        setRows(fields, 0, fields.size, groups, target)
    }

    /** The rows from [from] up to [until] onto [target], each of [groups] as its entries. */
    private fun setRows(
        fields: List<Pair<Int, String>>,
        from: Int,
        until: Int,
        groups: List<WalkedGroup>,
        target: FieldMap,
    ) {
        var next = 0
        var row = from
        while (row < until) {
            val group = groups.getOrNull(next)
            if (group != null && group.countRow == row) {
                addEntries(fields, group, target)
                row = group.end
                next++
            } else {
                val (tag, value) = fields[row]
                try {
                    target.setString(tag, value)
                } catch (e: Exception) {
                    // Skip fields that can't be set (e.g., unknown fields)
                }
                row++
            }
        }
    }

    /**
     * One group onto [target]: an instance per entry the walk found, then empty ones up to the count.
     *
     * A count the venue overstates is kept visible by those empty entries, and a positive count with no
     * entry under it at all by nothing but them. A group gets every entry present, even past a count that
     * claims fewer, so nothing is dropped or set flat on its parent. The parsed message keeps the count as
     * claimed, but a deliberately low count cannot reach the wire: QuickFIX/J writes a group's count as the
     * entries it holds.
     */
    private fun addEntries(
        fields: List<Pair<Int, String>>,
        group: WalkedGroup,
        target: FieldMap,
    ) {
        for (index in group.entries.indices) {
            val entry = group.entries[index]
            val instance = Group(group.countTag, group.delimiter)
            setRows(fields, entry.first, entry.last + 1, entry.groups, instance)
            target.addGroup(instance)
        }
        val claim = fields[group.countRow].second
        val claimed = claim.toIntOrNull() ?: 0
        repeat(claimed - group.entries.size) { target.addGroup(Group(group.countTag, group.delimiter)) }
        // addGroup normalised the count to the entries built. The parsed message keeps the count it claimed,
        // as an overstated one does through its empty entries, so a received message renders what the venue
        // sent.
        if (group.entries.size > claimed) target.setString(group.countTag, claim)
    }

    /**
     * Display → wire. A string that already carries SOH **is** wire: any `|` in it is a character
     * inside a value, and replacing it would corrupt the one kind of value [joinFields] switches
     * to SOH precisely to protect. Only a pipe-rendered string has pipes to substitute.
     *
     * Either way the result ends with a delimiter. QuickFIX/J's parser wants one after every field, the
     * last one included, while the author's `35=0|112=abc` is a whole message without it. Left off, the
     * parse threw "SOH not found at end of field" before the dictionary was asked anything, so the linter
     * failed a valid body on framing and the send without a dictionary failed outright.
     */
    fun String.toWireFixMessage(): String {
        val wire = if (this.contains(SOH)) this else this.replace('|', SOH)
        return if (wire.isBlank() || wire.endsWith(SOH)) wire else wire + SOH
    }

    fun String.toRawFixMessage() = this.replace('\u0001', '|')

    fun Message.toRawFixMessage() = this.toString().replace('\u0001', '|')

    /**
     * Normalizes a FIX message from line-based format to traditional format.
     * Supports two formats:
     * 1. Traditional: "35=R|131=ORD-1|", or the same SOH-delimited, returned as it is but for line breaks around it
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

        // Detect format: if contains newlines and limited use of '=' or a delimiter, use line-based format
        val hasNewlines = this.contains('\n')
        val hasEquals = this.contains('=')
        // SOH counts as a delimiter, and wins (see [delimiterOf]): a wire line copied from a messages.log has
        // a line break and no pipe, and read as the line-based format it came back empty.
        val hasDelimiter = this.contains(delimiterOf(this))

        // If already in traditional format, return as-is, less any line break around it. A line copied from a
        // log brings its own, and left on, QuickFIX/J read the stray segment after the last delimiter as a
        // field with no `=`, and the paste failed.
        if (!hasNewlines || (hasEquals && hasDelimiter)) {
            return this.trim('\r', '\n')
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
