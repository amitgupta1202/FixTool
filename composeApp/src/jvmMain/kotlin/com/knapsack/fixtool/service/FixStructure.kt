package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import quickfix.DataDictionary

/**
 * A dictionary-aware walk over a flat tag/value list that answers one question: **is this field at the
 * top level, or inside a repeating group?**
 *
 * That is all it answers, and all anything asks it. Its only caller is [DictionaryLint], which flags
 * top-level tags the loaded dictionary does not define for the message type — a *send-time* lint about
 * message construction, with nothing to do with assertions.
 *
 * It used to do much more: locate each group entry by the value of its delimiter, hand that
 * [com.knapsack.fixtool.model.scenario.FieldExpectation] a path, and report the groups whose entries it
 * could not tell apart. All of that is gone with the assertion model it served. The identity never
 * identified — the same firm appears twice under different PartyRoles, so both entries carry
 * `448=FIRMA` — and the engine that needed it now asserts the *k*-th occurrence of a tag and needs no
 * structure at all. What is left cannot disagree with QuickFIX about what an entry contains, because it
 * no longer has an opinion about entries.
 */
object FixStructure {
    /** One field and where it sits: `groupTag == null` → top level, else inside that repeating group. */
    data class StructuredField(val tag: Int, val value: String, val groupTag: Int?)

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun walk(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): List<StructuredField> {
        val dd = try {
            dictionary?.getDataDictionary()
        } catch (e: Exception) {
            null
        }
        val msgType = fields.firstOrNull { it.first == 35 }?.second
        if (dd == null || msgType == null) {
            return fields.map { StructuredField(it.first, it.second, null) }
        }

        val out = mutableListOf<StructuredField>()
        var i = 0
        while (i < fields.size) {
            val (tag, value) = fields[i]
            out += StructuredField(tag, value, null) // the entry count itself is a top-level field
            i = if (isGroup(dd, msgType, tag)) consume(fields, i + 1, dd, msgType, tag, tag, out) else i + 1
        }
        return out
    }

    /**
     * Consumes a group's fields, attributing each to [outerGroupTag].
     *
     * Descends into deeper groups rather than scanning flat: a group's dictionary is scoped to its own
     * fields, so a field of a group nested below it (NestedPartySubID(545), defined in
     * NoNestedPartySubIDs(804), not in NoNestedPartyIDs(539)) does not belong to it. A flat scan would
     * stop there and leave the rest of the message to be read as top-level fields — which would make
     * the lint flag half the message as undefined.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "LongParameterList")
    private fun consume(
        fields: List<Pair<Int, String>>,
        start: Int,
        parentDD: DataDictionary,
        msgType: String,
        groupTag: Int,
        outerGroupTag: Int,
        out: MutableList<StructuredField>,
    ): Int {
        val info = try {
            parentDD.getGroup(msgType, groupTag)
        } catch (e: Exception) {
            null
        } ?: return start
        val groupDD = info.dataDictionary
        var i = start
        while (i < fields.size && belongsTo(groupDD, msgType, fields[i].first)) {
            val (tag, value) = fields[i]
            out += StructuredField(tag, value, outerGroupTag)
            i = if (isGroup(groupDD, msgType, tag)) {
                consume(fields, i + 1, groupDD, msgType, tag, outerGroupTag, out)
            } else {
                i + 1
            }
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
