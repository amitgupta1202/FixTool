package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import quickfix.DataDictionary

/**
 * A dictionary-aware walk over a flat tag/value list that answers one question: **where does this field sit,
 * and does the dictionary define it there?**
 *
 * Its only caller is [DictionaryLint], which names the tags a message carries where the loaded dictionary
 * does not define them: a *send-time* lint about message construction, with nothing to do with assertions.
 *
 * It reads group structure from [GroupWalk], the walk the parse builds the sent message by. It used to walk
 * the rows with a rule of its own, which ended a group at the first tag the group's dictionary does not
 * define. The parse keeps such a tag in its entry when the entry goes on after it, so the lint said that
 * `9110, 79, 80, 15, 37, 11` were "sent as plain top-level fields" while the wire carried every one of them
 * inside its group. Reading the parse's own walk, the two cannot disagree.
 */
object FixStructure {
    /**
     * One field and where it sits.
     *
     * [groupTag] is null at the top level, and otherwise the outermost repeating group the field is inside.
     * [inGroup] is the innermost one, the group whose entry holds the field directly. [defined] is whether
     * the dictionary defines the tag there: for the message type at the top level, for that group inside one.
     * [row] is the field's index in the list walked.
     */
    data class StructuredField(
        val tag: Int,
        val value: String,
        val groupTag: Int?,
        val row: Int = 0,
        val inGroup: Int? = null,
        val defined: Boolean = true,
    )

    /**
     * Every field of [fields], in order, placed as the parse places it.
     *
     * [counts] says which counts open a group: [GroupWalk.Counts.POSITIVE], the default, is what a send builds.
     * The editor passes [GroupWalk.Counts.ANY], the rule its indentation follows, so a count being typed does
     * not turn the entries under it into top-level fields for a keystroke.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    internal fun walk(
        fields: List<Pair<Int, String>>,
        dictionary: FixDictionaryAdapter?,
        counts: GroupWalk.Counts = GroupWalk.Counts.POSITIVE,
    ): List<StructuredField> {
        val dd =
            try {
                dictionary?.getDataDictionary()
            } catch (e: Exception) {
                null
            }
        val msgType = fields.firstOrNull { it.first == 35 }?.second
        if (dd == null || msgType == null) {
            return fields.mapIndexed { row, (tag, value) -> StructuredField(tag, value, null, row) }
        }

        // The parse walks the body alone, header and trailer set aside, so this does too.
        val envelope = envelopeOf(dictionary)
        val bodyRows = fields.indices.filterNot { envelope(fields[it].first) }
        val body = bodyRows.map { fields[it] }
        val groups =
            try {
                GroupWalk.walk(body, msgType, dd, dictionary, counts)
            } catch (e: Exception) {
                emptyList()
            }
        val placed = arrayOfNulls<Placement>(body.size)
        groups.forEach { place(it, it.countTag, dd, msgType, placed) }

        val bodyIndex = IntArray(fields.size) { -1 }
        bodyRows.forEachIndexed { index, row -> bodyIndex[row] = index }
        return fields.mapIndexed { row, (tag, value) ->
            val placement = bodyIndex[row].takeIf { it >= 0 }?.let { placed[it] }
            when {
                bodyIndex[row] < 0 -> StructuredField(tag, value, null, row)
                placement == null ->
                    StructuredField(tag, value, null, row, defined = definedAtTop(dd, msgType, tag))
                else ->
                    StructuredField(
                        tag,
                        value,
                        placement.outer,
                        row,
                        inGroup = placement.inner,
                        defined = owns(placement.dictionary, msgType, tag),
                    )
            }
        }
    }

    /**
     * Whether a tag is a header or trailer field, wherever this dictionary keeps them: the version's standard
     * set, the dictionary's own sections, and the FIXT transport dictionary's. A FIX 5.0 application dictionary
     * has an empty `<header/>` and `<trailer/>`, because the session fields live in the transport dictionary,
     * and a venue declares its custom header fields there. The parse sets the same tags aside.
     */
    internal fun envelopeOf(dictionary: FixDictionaryAdapter?): (Int) -> Boolean {
        val dd = dictionary?.getDataDictionary()
        val transport = dictionary?.getTransportDictionary()
        val standard = dictionary?.let { it.getHeaderTags() + it.getTrailerTags() }.orEmpty()
        return { tag ->
            tag in standard ||
                dd?.isHeaderField(tag) == true ||
                dd?.isTrailerField(tag) == true ||
                transport?.isHeaderField(tag) == true ||
                transport?.isTrailerField(tag) == true
        }
    }

    /** The group a body row's entry belongs to: the outermost, the innermost, and the innermost's dictionary. */
    private class Placement(
        val outer: Int,
        val inner: Int,
        val dictionary: DataDictionary,
    )

    /**
     * Every row of [group]'s entries placed in it, then each nested group's rows in that one. A nested group's
     * count row is a field of the entry that holds it, so it keeps this group's placement.
     */
    private fun place(
        group: WalkedGroup,
        outer: Int,
        parent: DataDictionary,
        msgType: String,
        into: Array<Placement?>,
    ) {
        val info = parent.getGroup(msgType, group.countTag) ?: return
        val placement = Placement(outer, group.countTag, info.dataDictionary)
        for (entry in group.entries) {
            for (row in entry.rows) into[row] = placement
            entry.groups.forEach { place(it, outer, info.dataDictionary, msgType, into) }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun definedAtTop(
        dd: DataDictionary,
        msgType: String,
        tag: Int,
    ): Boolean =
        try {
            dd.isMsgField(msgType, tag) || dd.isGroup(msgType, tag)
        } catch (e: Exception) {
            true // benefit of the doubt: never flag on dictionary errors
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun owns(
        dd: DataDictionary,
        msgType: String,
        tag: Int,
    ): Boolean =
        try {
            dd.isField(tag) || dd.isGroup(msgType, tag)
        } catch (e: Exception) {
            true
        }
}
