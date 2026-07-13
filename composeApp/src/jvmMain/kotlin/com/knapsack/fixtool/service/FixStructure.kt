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
 *
 * An identity only locates an entry if it is **unique** within its group. When two entries share a
 * delimiter value — a market-data snapshot with two MDEntries of the same MDEntryType, two legs on
 * the same symbol — nothing in the path tells them apart. The walk reports those groups in
 * [Structure.ambiguousGroups] rather than quietly picking one: FixTool refuses to assert what it
 * cannot locate, because a scenario that asserts the wrong entry and passes is worse than one that
 * says it cannot check this.
 */
object FixStructure {
    /**
     * One field with its location: `path == null` → top level, else inside the located entry.
     *
     * [entryIndex] is the entry's ordinal within its group in wire order — a *view* concern (it lets
     * a reader iterate real entries even when two share an identity), never persisted and never part
     * of an assertion.
     */
    data class StructuredField(val tag: Int, val value: String, val path: GroupPath?, val entryIndex: Int? = null)

    /** The walked fields plus the groups whose entries are not distinguishable by their identity. */
    data class Structure(val fields: List<StructuredField>, val ambiguousGroups: Set<Int>)

    fun walk(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): List<StructuredField> =
        structure(fields, dictionary).fields

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun structure(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): Structure {
        val dd = try {
            dictionary?.getDataDictionary()
        } catch (e: Exception) {
            null
        }
        val msgType = fields.firstOrNull { it.first == 35 }?.second
        if (dd == null || msgType == null) {
            return Structure(fields.map { StructuredField(it.first, it.second, null) }, emptySet())
        }

        val out = mutableListOf<StructuredField>()
        val identities = mutableMapOf<Int, MutableList<String>>() // group tag → each entry's identity, in wire order
        var i = 0
        while (i < fields.size) {
            val (tag, value) = fields[i]
            out += StructuredField(tag, value, null) // the entry count itself is assertable
            i = if (isGroup(dd, msgType, tag)) walkEntries(fields, i + 1, dd, msgType, tag, out, identities) else i + 1
        }
        val ambiguous = identities.filterValues { it.size != it.distinct().size }.keys
        return Structure(out, ambiguous)
    }

    /**
     * Walks one group's entries.
     *
     * An entry begins at its delimiter — but venues do not always put the delimiter first, so a tag
     * that *repeats* within the current entry also begins the next one (the rule FixMessageHelper
     * already uses to build such messages). Fields seen ahead of an entry's delimiter are held and
     * attributed to the entry that delimiter opens, rather than being dropped or, worse, folded into
     * the previous entry.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "LongParameterList", "NestedBlockDepth")
    private fun walkEntries(
        fields: List<Pair<Int, String>>,
        start: Int,
        dd: DataDictionary,
        msgType: String,
        groupTag: Int,
        out: MutableList<StructuredField>,
        identities: MutableMap<Int, MutableList<String>>,
    ): Int {
        val info = try {
            dd.getGroup(msgType, groupTag)
        } catch (e: Exception) {
            null
        } ?: return start
        val delimiter = info.delimiterField
        val groupDD = info.dataDictionary
        val entries = identities.getOrPut(groupTag) { mutableListOf() }

        var entry: GroupPath? = null
        var entryIndex = -1
        val seenInEntry = mutableSetOf<Int>()
        val pending = mutableListOf<Pair<Int, String>>() // fields of an entry that arrived before its delimiter

        var i = start
        while (i < fields.size) {
            val (tag, value) = fields[i]
            if (!belongsTo(groupDD, msgType, tag)) break

            when {
                tag == delimiter -> {
                    entry = GroupPath(groupTag, delimiter, value)
                    entryIndex = entries.size
                    entries += value
                    seenInEntry.clear()
                    seenInEntry += tag
                    // The held fields are this entry's — and they count as seen in it, so a later
                    // repeat of one of them is recognised as the start of the *next* entry.
                    pending.forEach { (t, v) ->
                        out += StructuredField(t, v, entry, entryIndex)
                        seenInEntry += t
                    }
                    pending.clear()
                    out += StructuredField(tag, value, entry, entryIndex)
                    i++
                }
                isGroup(groupDD, msgType, tag) -> i = consumeNested(fields, i + 1, groupDD, msgType, tag)
                entry == null || tag in seenInEntry -> {
                    // Either we have not met this entry's delimiter yet, or this tag already appeared in
                    // the current entry — which means the next entry has started ahead of its delimiter.
                    entry = null
                    seenInEntry.clear()
                    pending += tag to value
                    i++
                }
                else -> {
                    seenInEntry += tag
                    out += StructuredField(tag, value, entry, entryIndex)
                    i++
                }
            }
        }
        // A trailing run with no delimiter belongs to no entry we can name. Surface it at top level so
        // the dictionary lint reports it, rather than folding it into an entry it may not belong to.
        pending.forEach { (t, v) -> out += StructuredField(t, v, null) }
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
