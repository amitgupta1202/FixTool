package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.GroupPath
import quickfix.DataDictionary

/**
 * A dictionary-aware structural walk over a flat tag/value list: assigns each repeating-group field
 * the [GroupPath] that locates its entry **by identity** (the entry's delimiter value), which is what
 * the expectation evaluator needs to assert grouped fields without relying on entry order.
 *
 * Single level only for *emission*: fields of a group nested *inside* another group's entry are
 * consumed but not emitted (the evaluator reads one group level; deeper assertions are authored
 * manually). Nesting of any depth is still traversed — see [consumeNested].
 */
object FixStructure {
    /** One field with its location: `path == null` → top level, else inside the located entry. */
    data class StructuredField(val tag: Int, val value: String, val path: GroupPath?)

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun walk(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): List<StructuredField> {
        val dd = try {
            dictionary?.getDataDictionary()
        } catch (e: Exception) {
            null
        }
        val msgType = fields.firstOrNull { it.first == 35 }?.second
        if (dd == null || msgType == null) return fields.map { StructuredField(it.first, it.second, null) }

        val out = mutableListOf<StructuredField>()
        var i = 0
        while (i < fields.size) {
            val (tag, value) = fields[i]
            if (isGroup(dd, msgType, tag)) {
                out += StructuredField(tag, value, null) // the entry count itself is assertable
                i = walkEntries(fields, i + 1, dd, msgType, tag, out)
            } else {
                out += StructuredField(tag, value, null)
                i++
            }
        }
        return out
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    private fun walkEntries(
        fields: List<Pair<Int, String>>,
        start: Int,
        dd: DataDictionary,
        msgType: String,
        groupTag: Int,
        out: MutableList<StructuredField>,
    ): Int {
        val info = try {
            dd.getGroup(msgType, groupTag)
        } catch (e: Exception) {
            null
        } ?: return start
        val delimiter = info.delimiterField
        val groupDD = info.dataDictionary
        // Entries are located by identity, but an identity need not be unique (two MDEntries of the
        // same MDEntryType, two legs on the same symbol). Counting repeats of each identity value
        // keeps such entries distinct instead of collapsing them onto one path.
        val repeats = mutableMapOf<String, Int>()
        var entry: GroupPath? = null
        var i = start
        while (i < fields.size) {
            val (tag, value) = fields[i]
            if (!belongsTo(groupDD, msgType, tag)) return i
            if (tag == delimiter) { // a recurring delimiter starts the next entry
                val occurrence = repeats.getOrDefault(value, 0)
                repeats[value] = occurrence + 1
                entry = GroupPath(groupTag, delimiter, value, occurrence)
            }
            if (isGroup(groupDD, msgType, tag)) {
                i = consumeNested(fields, i + 1, groupDD, msgType, tag)
            } else {
                entry?.let { out += StructuredField(tag, value, it) }
                i++
            }
        }
        return i
    }

    /**
     * Skips a nested group's fields without emitting them (single-level evaluation).
     *
     * Descends into deeper groups rather than scanning flat: a group's dictionary is scoped to its
     * own fields, so a field of a group nested below it (NestedPartySubID(545), defined in
     * NoNestedPartySubIDs(804), not in NoNestedPartyIDs(539)) does not belong to it. A flat scan
     * would stop there and leave the rest of the message to be read as top-level fields.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun consumeNested(
        fields: List<Pair<Int, String>>,
        start: Int,
        parentDD: DataDictionary,
        msgType: String,
        groupTag: Int,
    ): Int {
        val info = try {
            parentDD.getGroup(msgType, groupTag)
        } catch (e: Exception) {
            null
        } ?: return start
        val groupDD = info.dataDictionary
        var i = start
        while (i < fields.size && belongsTo(groupDD, msgType, fields[i].first)) {
            val tag = fields[i].first
            i = if (isGroup(groupDD, msgType, tag)) consumeNested(fields, i + 1, groupDD, msgType, tag) else i + 1
        }
        return i
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun belongsTo(dd: DataDictionary, msgType: String, tag: Int): Boolean =
        try {
            dd.isField(tag) || dd.isGroup(msgType, tag)
        } catch (e: Exception) {
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isGroup(dd: DataDictionary, msgType: String, tag: Int): Boolean =
        try {
            dd.isGroup(msgType, tag)
        } catch (e: Exception) {
            false
        }
}
