package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioReconcile
import quickfix.DataDictionary

/**
 * **Where the group entries are** — derived from the dictionary, for *presentation* and for *bounding a
 * move*, and consulted by the assertion engine never.
 *
 * The model has no groups and is not getting any: the *k*-th row for a tag asserts the *k*-th occurrence
 * of it, and that is the whole of the addressing. But an author still edits a *party*, not a run of
 * fields, and an arrow that may cut anywhere can weld the tail of one entry to the head of the next — so
 * something has to say where an entry begins. Until now that was `ScenarioReconcile.entries`, which
 * re-derives the boundaries by period-detection over the row order: correct after three rounds of review,
 * and still a guess at structure the dictionary already knows.
 *
 * The dictionary knows it exactly. A group's *delimiter field* is the first field of every entry, by
 * definition of the FIX spec, so an entry starts at each occurrence of it and nowhere else. **The rotation
 * trap closes by construction**: the window `447,452,448` in a three-party group has an identically-shaped
 * neighbour and is not a party — a dictionary entry cannot start mid-entry, because the entry starts where
 * the delimiter is.
 *
 * Two rules ride along, and both are load-bearing:
 *
 * - **The engine never reads this.** `ExpectationEvaluator` has no dependency on it, and
 *   `GroupOverlayArchitectureTest` fails the build if one appears. An assertion that consulted the overlay
 *   would be asserting something the runner cannot see.
 * - **One overlay, both sides.** The boundaries are a function of the tag sequence and the dictionary
 *   alone — values only ever reach the *label*. So the expectation's side and the message's side cannot
 *   come to disagree about where an entry starts, which is the seam that produced the defect this model
 *   was built to end.
 */
data class GroupOverlay(
    val groups: List<GroupNode>,
) {
    /** Every entry at every depth, outermost first — what the diff surface bands. */
    val entries: List<EntryNode> get() = groups.flatMap { it.allEntries }

    /**
     * Every sibling list, at every depth — the lists an entry is allowed to move *within*.
     *
     * An entry's siblings are the other entries of its own group, and nothing else: a party may change
     * places with another party, never with a sub-id of itself and never with a leg. This is what bounds
     * a drag, so it is the overlay's most consequential answer.
     */
    val siblingGroups: List<List<EntryNode>> get() = groups.flatMap { it.siblingGroups }

    /** Each group's whole footprint — its count row through its last entry. Nothing may be dropped inside it. */
    val spans: List<IntRange> get() = groups.flatMap { it.spans }

    /** The innermost entry containing [row], or null when the row is not inside a group at all. */
    fun entryAt(row: Int): EntryNode? =
        entries.filter { row in it.rows }.minByOrNull { it.rows.last - it.rows.first }

    /** The entries of the group [entry] belongs to — its siblings, itself included, in order. */
    fun siblingsOf(entry: EntryNode): List<EntryNode> =
        groups.firstNotNullOfOrNull { it.find(entry) } ?: listOf(entry)

    companion object {
        /**
         * The overlay for a message. [MessageView] is a flat list of `tag=value`, which is exactly what the
         * walk wants, and tag 35 names the message type the dictionary keys its groups on.
         */
        fun of(message: MessageView, dictionary: FixDictionaryAdapter?): GroupOverlay {
            val fields = message.fields()
            val messageType = fields.firstOrNull { it.first == 35 }?.second
            return build(fields.map { it.first to it.second }, messageType, dictionary)
        }

        /**
         * The overlay for an expectation.
         *
         * A row's value is only known when its matcher *is* a value — an `exact` row says `FIRMA`, a
         * `presence` row says nothing about which firm it is. So the label may come out thinner on this
         * side than on the message's; the **boundaries cannot**, because they never look at a value.
         */
        fun of(expectation: Expectation, dictionary: FixDictionaryAdapter?): GroupOverlay =
            build(
                expectation.fields.map { it.tag to (it.matcher as? Matcher.Exact)?.value },
                expectation.messageType,
                dictionary,
            )

        fun build(
            fields: List<Pair<Int, String?>>,
            messageType: String?,
            dictionary: FixDictionaryAdapter?,
        ): GroupOverlay = Builder(fields, messageType, dictionary).build()
    }
}

/** Which of the two answers this group's boundaries came from. The UI badges the guess. */
enum class EntrySource {
    /** The dictionary named the group and its delimiter. An entry cannot start anywhere else. */
    DICTIONARY,

    /** The dictionary had never heard of this group, so its shape was inferred from the row order. */
    HEURISTIC,
}

/** One repeating group: its count field, and the entries under it. */
data class GroupNode(
    val groupTag: Int,
    val name: String,
    /** The row carrying the count (`453`), or null when the sequence does not include it. */
    val countRow: Int?,
    val entries: List<EntryNode>,
    val source: EntrySource,
) {
    val allEntries: List<EntryNode> get() = entries.flatMap { listOf(it) + it.children.flatMap { c -> c.allEntries } }

    /** This group's entries, and every nested group's, as the sibling lists a move may travel within. */
    val siblingGroups: List<List<EntryNode>> get() =
        listOf(entries) + entries.flatMap { e -> e.children.flatMap { it.siblingGroups } }

    /** The count row through the last entry — the region no field from outside the group may be dropped into. */
    val spans: List<IntRange> get() =
        listOf((countRow ?: entries.first().rows.first)..entries.last().rows.last) +
            entries.flatMap { e -> e.children.flatMap { it.spans } }

    /** The sibling list [entry] belongs to, searching this group and everything nested in it. */
    fun find(entry: EntryNode): List<EntryNode>? =
        if (entries.any { it === entry }) {
            entries
        } else {
            entries.firstNotNullOfOrNull { e -> e.children.firstNotNullOfOrNull { it.find(entry) } }
        }
}

/**
 * One entry of a repeating group: the delimiter row and everything under it, up to the next delimiter.
 *
 * [rows] is the range a move carries, whole. It is the only thing permitted to say what one drag may
 * rewrite — see `ScenarioReconcile.moveEntry`.
 */
data class EntryNode(
    val groupTag: Int,
    /** 0-based, within its group. */
    val entryIndex: Int,
    val rows: IntRange,
    /** `FIRMA · 1 ExecutingFirm` — the delimiter's value, and the best identity the dictionary can add. */
    val label: String,
    val children: List<GroupNode>,
    val source: EntrySource,
)

/**
 * The walk. Descends into nested groups rather than scanning flat, because a group's dictionary is scoped
 * to its own fields: `NestedPartySubID(545)` is defined in `NoNestedPartySubIDs(804)`, not in the group
 * above it, and a flat scan would stop at it and read the rest of the message as top-level fields.
 */
private class Builder(
    private val fields: List<Pair<Int, String?>>,
    private val messageType: String?,
    private val dictionary: FixDictionaryAdapter?,
) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private val dd: DataDictionary? =
        try {
            dictionary?.getDataDictionary()
        } catch (e: Exception) {
            null
        }

    private val covered = mutableSetOf<Int>()

    fun build(): GroupOverlay = GroupOverlay(known() + guessed())

    /** The groups the dictionary defines, at the top level of the message. */
    private fun known(): List<GroupNode> {
        val dd = dd ?: return emptyList()
        if (messageType == null) return emptyList()
        val groups = mutableListOf<GroupNode>()
        var i = 0
        while (i < fields.size) {
            val found = groupAt(dd, i)
            if (found == null) {
                i++
            } else {
                groups += found.first
                i = found.second
            }
        }
        return groups
    }

    /** The group whose count row is [at], and the row after it ends — or null when [at] starts no group. */
    private fun groupAt(parent: DataDictionary, at: Int): Pair<GroupNode, Int>? {
        val tag = fields[at].first
        if (!isGroup(parent, tag)) return null
        val (entries, next) = entriesOf(parent, tag, at + 1)
        if (entries.isEmpty()) return null
        return GroupNode(tag, name(tag), at, entries, EntrySource.DICTIONARY) to next
    }

    /**
     * The entries of [groupTag], starting at [start] — one per occurrence of the group's delimiter field.
     *
     * The delimiter is the whole answer, and the reason this cannot be fooled the way period-detection
     * could: an entry begins where the delimiter is, so a window that begins one row late is not an entry
     * and is never offered as one.
     */
    private fun entriesOf(parent: DataDictionary, groupTag: Int, start: Int): Pair<List<EntryNode>, Int> {
        val info = group(parent, groupTag) ?: return emptyList<EntryNode>() to start
        val groupDD = info.dataDictionary
        val delimiter = info.delimiterField
        val entries = mutableListOf<EntryNode>()
        var i = start
        while (i < fields.size && fields[i].first == delimiter && belongsTo(groupDD, delimiter)) {
            val (entry, next) = entryOf(groupDD, groupTag, delimiter, i, entries.size)
            entries += entry
            i = next
        }
        return entries to i
    }

    /** One entry: the delimiter row, everything under it, and any group nested inside it. */
    private fun entryOf(
        groupDD: DataDictionary,
        groupTag: Int,
        delimiter: Int,
        from: Int,
        index: Int,
    ): Pair<EntryNode, Int> {
        val children = mutableListOf<GroupNode>()
        var i = from + 1
        while (i < fields.size && belongsTo(groupDD, fields[i].first) && fields[i].first != delimiter) {
            val nested = groupAt(groupDD, i)
            if (nested == null) {
                i++
            } else {
                children += nested.first
                i = nested.second
            }
        }
        val rows = from..(i - 1)
        covered += rows
        return EntryNode(groupTag, index, rows, label(rows), children, EntrySource.DICTIONARY) to i
    }

    /**
     * What the dictionary could not answer, guessed the old way.
     *
     * A venue's custom group — `NoStrategyParameters` on a dictionary that predates it, anything in the
     * 5000s a counterparty invented — is still a repeating group to the author looking at it, and refusing
     * to bracket it would take the entry drag away from exactly the venues that need it most. So the
     * period-detection that used to be the *only* source is kept as the fallback, and every entry it
     * produces says so, because it is a guess and the UI has to be able to admit that.
     */
    private fun guessed(): List<GroupNode> {
        val runs = ScenarioReconcile.entryRegions(fields.map { it.first })
        return runs
            .filter { region -> region.none { entry -> entry.any { it in covered } } }
            .mapNotNull { region ->
                val first = region.first()
                val delimiter = fields[first.first].first
                // The count row, if the sequence carries one: a `No…` field immediately above the run.
                val countRow = (first.first - 1).takeIf { it >= 0 && dictionary?.isGroupTag(fields[it].first) == true }
                val entries =
                    region.mapIndexed { index, rows ->
                        EntryNode(delimiter, index, rows, label(rows), emptyList(), EntrySource.HEURISTIC)
                    }
                GroupNode(
                    groupTag = countRow?.let { fields[it].first } ?: delimiter,
                    name = countRow?.let { name(fields[it].first) } ?: name(delimiter),
                    countRow = countRow,
                    entries = entries,
                    source = EntrySource.HEURISTIC,
                )
            }
    }

    /**
     * `FIRMA · 1 ExecutingFirm` — computed here, not in the UI, so the two sides of a diff cannot label the
     * same entry differently.
     *
     * The delimiter's value identifies the entry, and one described field says what the entry is *for* —
     * which is what an author is actually looking for when all four party entries read `448=FIRMA` and only
     * their roles differ.
     *
     * **The last described field, not the first.** A FIX group entry conventionally reads *identifier,
     * how that identifier is encoded, what it is for* — `448 PartyID, 447 PartyIDSource, 452 PartyRole` —
     * so the first described field is the *encoding*, and taking it labels every party in every message
     * `ProprietaryCustomCode`, which distinguishes nothing from nothing. The role is the last one.
     *
     * A delimiter that has a description of its own keeps it (`NoMDEntries` is delimited by `MDEntryType`,
     * whose value is `0` and whose meaning is `Bid`), and a row whose matcher is not a value contributes
     * nothing at all: silence beats a label that is only true of some of the entries it could describe.
     */
    private fun label(rows: IntRange): String {
        val delimiter = described(rows.first)
        val identity = rows.drop(1).mapNotNull { described(it, onlyIfDescribed = true) }.lastOrNull()
        return listOfNotNull(delimiter, identity).joinToString(" · ")
    }

    /** `1 ExecutingFirm`, or `FIRMA` where the dictionary has no word for the value. */
    private fun described(row: Int, onlyIfDescribed: Boolean = false): String? {
        val (tag, value) = fields[row]
        if (value == null) return null
        val description = dictionary?.getFieldValueDescription(tag, value)
        if (description == null && onlyIfDescribed) return null
        return listOfNotNull(value, description).joinToString(" ")
    }

    private fun name(tag: Int): String = dictionary?.getFieldName(tag) ?: tag.toString()

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun group(dd: DataDictionary, tag: Int): DataDictionary.GroupInfo? =
        try {
            dd.getGroup(messageType, tag)
        } catch (e: Exception) {
            null
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isGroup(dd: DataDictionary, tag: Int): Boolean =
        try {
            dd.isGroup(messageType, tag)
        } catch (e: Exception) {
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun belongsTo(dd: DataDictionary, tag: Int): Boolean =
        try {
            dd.isField(tag) || dd.isGroup(messageType, tag)
        } catch (e: Exception) {
            false
        }
}
