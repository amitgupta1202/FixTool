package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter

/**
 * A [MessageView] over a raw pipe/SOH-delimited FIX string. Used by the expectation builder's live
 * preview when authoring from a stored "golden" message, where a fully parsed QuickFIX message isn't
 * on hand. With a [dictionary] it is **group-aware** (via [FixStructure]): `valueOfTag` answers
 * top-level fields only and `groupEntries` locates entries — mirroring how [FixMessageView] behaves
 * at run time, so the preview predicts the runner instead of contradicting it.
 */
class RawMessageView(raw: String, dictionary: FixDictionaryAdapter? = null) : MessageView {
    private val fields = FixMessageHelper.parseFixMessage(raw)
    private val structured = FixStructure.walk(fields, dictionary)
    private val topLevel = structured.filter { it.path == null }.associate { it.tag to it.value }

    override fun valueOfTag(tag: Int): String? = topLevel[tag]

    /** Top level only — STRICT's notion of an "extra" tag. Group members are not extras. */
    override fun presentTags(): Set<Int> = topLevel.keys

    override fun groupEntries(groupTag: Int): List<MessageView> =
        structured
            .filter { it.path?.groupTag == groupTag }
            // Keyed by the entry's position on the wire, not by its identity: two entries that share an
            // identity are still two entries, and merging them would hide one — and hide the ambiguity
            // the evaluator has to report.
            .groupBy { it.entryIndex } // insertion-ordered: entries keep wire order
            .map { (_, entryFields) -> EntryView(entryFields.associate { it.tag to it.value }) }

    private class EntryView(private val tags: Map<Int, String>) : MessageView {
        override fun valueOfTag(tag: Int): String? = tags[tag]

        override fun presentTags(): Set<Int> = tags.keys

        override fun groupEntries(groupTag: Int): List<MessageView> = emptyList()
    }
}
