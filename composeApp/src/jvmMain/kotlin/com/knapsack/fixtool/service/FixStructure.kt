package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.GroupPath
import quickfix.DataDictionary

/**
 * A dictionary-aware structural walk over a flat tag/value list: assigns each repeating-group field
 * the [GroupPath] that locates its entry **by identity** (the entry's delimiter value), which is what
 * the expectation evaluator needs to assert grouped fields without relying on entry order.
 *
 * Single level only: fields of a group nested *inside* another group's entry are consumed but not
 * emitted (the evaluator reads one group level; deeper assertions are authored manually).
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
        var identity: String? = null
        var i = start
        while (i < fields.size) {
            val (tag, value) = fields[i]
            if (!belongsTo(groupDD, msgType, tag)) return i
            if (tag == delimiter) identity = value // a recurring delimiter starts the next entry
            if (isGroup(groupDD, msgType, tag)) {
                i = consumeNested(fields, i + 1, groupDD, msgType, tag)
            } else {
                identity?.let { out += StructuredField(tag, value, GroupPath(groupTag, delimiter, it)) }
                i++
            }
        }
        return i
    }

    /** Skips a nested group's fields without emitting them (single-level evaluation). */
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
        var i = start
        while (i < fields.size && belongsTo(info.dataDictionary, msgType, fields[i].first)) i++
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
