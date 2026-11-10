package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import quickfix.DataDictionary
import quickfix.FieldType

/**
 * One repeating group the dictionary defines, as [GroupWalk] found it on a flat list of rows.
 *
 * Rows are indices into the list the walk was given. A group whose count row opened no entry has no
 * [entries], and the walk never reports an empty entry: a count that claims more than the wire carries is
 * the reader's to show, not the walk's to invent.
 */
internal class WalkedGroup(
    /** The row carrying the count. */
    val countRow: Int,
    val countTag: Int,
    /** The first field of the group in the dictionary. An entry need not open with it. */
    val delimiter: Int,
    val entries: List<WalkedEntry>,
    /** The row after the group: the row after its last entry, or after its count row when it has none. */
    val end: Int,
)

/** One entry of a [WalkedGroup]: its rows, and the groups whose count row it holds directly. */
internal class WalkedEntry(
    val first: Int,
    val last: Int,
    /** In row order. Each one's rows lie inside this entry's. */
    val groups: List<WalkedGroup>,
) {
    val rows: IntRange get() = first..last
}

/**
 * **Where a message's repeating groups are**, read from the tag sequence and the dictionary alone.
 *
 * The one reading of group structure in the app. `FixMessageHelper` builds the `quickfix.Message` tree from
 * it, which the grid and the detail pane draw and the send path serialises, and `GroupOverlay` brackets the
 * editor and the diff with it. They used to walk the rows with two rule sets of their own, and on a venue's
 * real traffic they disagreed: the overlay only began an entry at the group's delimiter, and both ended an
 * entry, and its group with it, at the first tag the group's own dictionary does not define.
 *
 * No value is consulted for a boundary, only for whether a count opens a group at all ([Counts]). So an
 * expectation, whose rows may carry no value, brackets exactly as the message it is judged against.
 *
 * At message level every field is accepted, and a count the dictionary defines for the message type opens
 * its group. A group G owns a tag when G's dictionary defines it as a field or as a nested group, and its
 * first entry opens at the row after the count, provided that row is not the delimiter of an enclosing
 * group and G either owns it or keeps it under the rules for a foreign tag below. Inside an entry each tag
 * is judged in this order:
 *
 * - a. The delimiter of an enclosing group ends the entry and G.
 * - b. G's delimiter, when the entry has already seen it, ends the entry, and the next entry opens there.
 * - c. Any other tag the entry already holds directly (a field of its own, the count of a nested group, or
 *   a tag it kept under the rules below) ends the entry, and the next entry opens there.
 * - d. A tag G does not own is foreign. It stays in the entry under S1 to S4, and otherwise the entry and G
 *   end, and the enclosing entry judges the tag by these same rules.
 * - e. A count G's dictionary defines opens a nested group, walked in G's dictionary.
 * - f. Anything else is a field of the entry.
 *
 * A foreign tag X at row i of an entry of G stays when:
 *
 * - **S1, learned.** An earlier entry of this group instance kept X.
 * - **S2, the group goes on.** G's delimiter occurs after i before a hard stop, and nothing outside G
 *   could hold that delimiter (no enclosing group's dictionary defines it, and the message does not).
 *   When the delimiter is also legal outside G, a later one proves nothing.
 * - **S3, unknown inside an open entry.** No open context owns X (not G, no enclosing group, not the
 *   message), X is not a header or trailer tag, X does not look like a count, and the first tag after i
 *   that an open context owns belongs to a group rather than only to the message. A tag that looks like a
 *   count, met before any owned tag, ends the scan with no: that is a venue's own group starting.
 * - **S4, nowhere to go.** The nearest enclosing entry P whose dictionary owns X already holds X, P has
 *   seen its own delimiter, and P's delimiter does not occur after i before a hard stop. Leaving would make
 *   X a repeat in P, which could only open another entry of P's group, and nothing ahead says there is one.
 *
 * A hard stop for a scan made on behalf of a group H is the count tag of H, the delimiter or the count tag
 * of any group enclosing H, or the end of the rows.
 *
 * Why the guards are there: a run of unknown tags after the last entry of a top-level group is not kept
 * (nothing owned follows it, or only the message's fields do), a venue's own counted group is left for the
 * salvage to rescue (it looks like a count), and an underlying sent without its delimiter after a nested
 * group still opens the next underlying (its entry never saw its delimiter, so S4 does not hold).
 */
internal object GroupWalk {
    /** Which count rows open a group. */
    enum class Counts {
        /** Every count the dictionary defines, whatever its value, as the editor needs while one is typed. */
        ANY,

        /** Only a count whose value is a positive integer, as a parse needs to build entries from it. */
        POSITIVE,
    }

    /**
     * The groups the dictionary defines at the top level of [fields], each with its entries and their nested
     * groups. [adapter] answers which tags look like a count, and which belong to the header or trailer, for
     * rule S3. Without one the dictionary's own NUMINGROUP type and header and trailer sections answer.
     *
     * A message with no group costs one dictionary lookup per row and allocates nothing.
     */
    fun walk(
        fields: List<Pair<Int, *>>,
        messageType: String,
        dictionary: DataDictionary,
        adapter: FixDictionaryAdapter?,
        counts: Counts,
    ): List<WalkedGroup> {
        var walk: Walk? = null
        var groups: MutableList<WalkedGroup>? = null
        var row = 0
        while (row < fields.size) {
            val info = dictionary.getGroup(messageType, fields[row].first)
            if (info == null || !opens(counts, fields[row].second)) {
                row++
                continue
            }
            val walker = walk ?: Walk(fields, messageType, dictionary, adapter, counts).also { walk = it }
            val group = walker.group(row, info)
            (groups ?: ArrayList<WalkedGroup>().also { groups = it }).add(group)
            row = group.end
        }
        return groups ?: emptyList()
    }

    fun opens(
        counts: Counts,
        value: Any?,
    ): Boolean = counts == Counts.ANY || ((value as? String)?.toIntOrNull() ?: 0) > 0
}

/** The state of one walk: the groups open around the row being judged, outermost first. */
private class Walk(
    private val fields: List<Pair<Int, *>>,
    private val messageType: String,
    private val message: DataDictionary,
    private val adapter: FixDictionaryAdapter?,
    private val counts: GroupWalk.Counts,
) {
    private val open = ArrayList<Frame>(INITIAL_DEPTH)

    /** One open group and the state of its open entry. */
    private class Frame(
        val countRow: Int,
        val countTag: Int,
        val delimiter: Int,
        val dictionary: DataDictionary,
    ) {
        var entries: ArrayList<WalkedEntry>? = null
        var start = 0
        var seenDelimiter = false
        var children: ArrayList<WalkedGroup>? = null

        /** Whether the entry that just ended was ended by the next one (rules b and c). */
        var next = false

        fun begin(row: Int) {
            start = row
            seenDelimiter = false
            children = null
            next = false
        }
    }

    /** The group whose count is at [countRow], walked to its end. */
    fun group(
        countRow: Int,
        info: DataDictionary.GroupInfo,
    ): WalkedGroup {
        val frame = Frame(countRow, fields[countRow].first, info.delimiterField, info.dataDictionary)
        open.add(frame)
        var row = countRow + 1
        do {
            val end = entry(frame, row)
            if (end > row) {
                val entry = WalkedEntry(row, end - 1, frame.children ?: emptyList())
                (frame.entries ?: ArrayList<WalkedEntry>().also { frame.entries = it }).add(entry)
            }
            row = end
        } while (frame.next)
        open.removeAt(open.lastIndex)
        return WalkedGroup(countRow, frame.countTag, frame.delimiter, frame.entries ?: emptyList(), row)
    }

    /** One entry of [frame]'s group from [from], returning the row after it. [from] itself means none opened. */
    private fun entry(
        frame: Frame,
        from: Int,
    ): Int {
        frame.begin(from)
        var row = from
        while (row < fields.size) {
            val tag = fields[row].first
            if (endsHere(frame, row, tag)) return row
            val nested = frame.dictionary.getGroup(messageType, tag)
            row =
                when {
                    nested != null && GroupWalk.opens(counts, fields[row].second) -> nest(frame, row, nested)
                    nested != null || frame.dictionary.isField(tag) -> {
                        if (tag == frame.delimiter) frame.seenDelimiter = true
                        row + 1
                    }
                    stays(frame, row, tag) -> row + 1
                    else -> return row
                }
        }
        return row
    }

    /** Rules a to c. True when the entry ends at [row], with [Frame.next] set when the next entry opens there. */
    private fun endsHere(
        frame: Frame,
        row: Int,
        tag: Int,
    ): Boolean {
        val enclosing = isEnclosingDelimiter(tag)
        frame.next = !enclosing &&
            (if (tag == frame.delimiter) frame.seenDelimiter else holds(frame.start, row, frame.children, tag))
        return enclosing || frame.next
    }

    private fun nest(
        frame: Frame,
        row: Int,
        info: DataDictionary.GroupInfo,
    ): Int {
        val child = group(row, info)
        (frame.children ?: ArrayList<WalkedGroup>().also { frame.children = it }).add(child)
        return child.end
    }

    /** True when [tag] is the delimiter of a group enclosing the innermost one. */
    private fun isEnclosingDelimiter(tag: Int): Boolean {
        for (depth in 0 until open.lastIndex) {
            if (open[depth].delimiter == tag) return true
        }
        return false
    }

    /**
     * True when an entry holds [tag] directly in its rows from [from] up to [until]: in a row that is not
     * inside one of its [nested] groups. A nested group's count row is the entry's own, and the rows of its
     * entries are not.
     */
    private fun holds(
        from: Int,
        until: Int,
        nested: List<WalkedGroup>?,
        tag: Int,
    ): Boolean {
        var child = 0
        var row = from
        while (row < until) {
            if (fields[row].first == tag) return true
            val group = nested?.getOrNull(child)
            if (group != null && group.countRow == row) {
                row = group.end
                child++
            } else {
                row++
            }
        }
        return false
    }

    /** Whether a tag foreign to [frame]'s group stays in its open entry: rules S1 to S4. */
    private fun stays(
        frame: Frame,
        row: Int,
        tag: Int,
    ): Boolean =
        learned(frame, tag) ||
            groupGoesOn(frame, row) ||
            unknownInsideOpenEntry(row, tag) ||
            nowhereToGo(row, tag)

    /** S1: an earlier entry of this group instance kept [tag], which it could only have kept as foreign. */
    private fun learned(
        frame: Frame,
        tag: Int,
    ): Boolean = frame.entries?.any { holds(it.first, it.last + 1, it.groups, tag) } == true

    /** S2: the group's delimiter is ahead, and nothing outside the group could hold it. */
    private fun groupGoesOn(
        frame: Frame,
        row: Int,
    ): Boolean = !ownedOutside(frame.delimiter) && aheadBeforeStop(frame.delimiter, open.lastIndex, row)

    /** S3: nothing open owns [tag], it is neither envelope nor count, and an entry is still going on ahead. */
    private fun unknownInsideOpenEntry(
        row: Int,
        tag: Int,
    ): Boolean {
        val ownedSomewhere = ownedByAnOpenGroup(tag) || message.isMsgField(messageType, tag)
        val plain = !isEnvelope(tag) && !looksLikeCount(tag)
        return !ownedSomewhere && plain && groupFieldAhead(row)
    }

    /** S4: the tag would be a repeat in the nearest entry that owns it, and that entry has nowhere to go. */
    private fun nowhereToGo(
        row: Int,
        tag: Int,
    ): Boolean {
        var depth = open.lastIndex - 1
        while (depth >= 0 && !owns(open[depth].dictionary, tag)) depth--
        if (depth < 0) return false
        val parent = open[depth]
        return parent.seenDelimiter &&
            holds(parent.start, open[depth + 1].countRow + 1, parent.children, tag) &&
            !aheadBeforeStop(parent.delimiter, depth, row)
    }

    /** True when an enclosing group's dictionary, or the message's, defines [tag]. */
    private fun ownedOutside(tag: Int): Boolean {
        if (message.isMsgField(messageType, tag)) return true
        for (depth in 0 until open.lastIndex) {
            if (owns(open[depth].dictionary, tag)) return true
        }
        return false
    }

    private fun ownedByAnOpenGroup(tag: Int): Boolean {
        for (depth in open.indices) {
            if (owns(open[depth].dictionary, tag)) return true
        }
        return false
    }

    /** The first tag after [row] that an open context owns belongs to a group, and no count comes first. */
    private fun groupFieldAhead(row: Int): Boolean {
        for (ahead in row + 1 until fields.size) {
            val tag = fields[ahead].first
            if (ownedByAnOpenGroup(tag)) return true
            if (message.isMsgField(messageType, tag) || looksLikeCount(tag)) return false
        }
        return false
    }

    /**
     * True when [tag] occurs after [row] before a hard stop for the group open at [depth]: that group's count
     * tag, or the delimiter or count tag of a group enclosing it.
     */
    private fun aheadBeforeStop(
        tag: Int,
        depth: Int,
        row: Int,
    ): Boolean {
        for (ahead in row + 1 until fields.size) {
            val seen = fields[ahead].first
            if (seen == tag) return true
            if (isHardStop(seen, depth)) return false
        }
        return false
    }

    private fun isHardStop(
        tag: Int,
        depth: Int,
    ): Boolean {
        if (tag == open[depth].countTag) return true
        for (outer in 0 until depth) {
            if (tag == open[outer].delimiter || tag == open[outer].countTag) return true
        }
        return false
    }

    private fun owns(
        dictionary: DataDictionary,
        tag: Int,
    ): Boolean = dictionary.isField(tag) || dictionary.isGroup(messageType, tag)

    private fun looksLikeCount(tag: Int): Boolean =
        adapter?.isGroupTag(tag) ?: (message.getFieldType(tag) == FieldType.NUMINGROUP)

    private fun isEnvelope(tag: Int): Boolean {
        if (message.isHeaderField(tag) || message.isTrailerField(tag)) return true
        val version = adapter?.fixVersion ?: FixVersion.DEFAULT
        val transport = adapter?.getTransportDictionary()
        return tag in FixVersion.getHeaderTags(version) ||
            tag in FixVersion.getTrailerTags(version) ||
            transport?.isHeaderField(tag) == true ||
            transport?.isTrailerField(tag) == true
    }

    private companion object {
        /** Groups nest two or three deep on any real message, so the stack rarely grows. */
        const val INITIAL_DEPTH = 4
    }
}
