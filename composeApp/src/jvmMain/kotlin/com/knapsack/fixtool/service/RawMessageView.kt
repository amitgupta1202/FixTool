package com.knapsack.fixtool.service

/**
 * A [MessageView] over a raw pipe/SOH-delimited FIX string (top-level fields only). Used by the
 * expectation builder's live preview when authoring from a pasted "golden" message, where a fully
 * parsed QuickFIX message isn't on hand. Repeating-group traversal is not supported here (group
 * matchers still work at run time via [FixMessageView]).
 */
class RawMessageView(raw: String) : MessageView {
    private val fields = FixMessageHelper.parseFixMessage(raw)
    private val byTag = fields.associate { it.first to it.second }

    override fun valueOfTag(tag: Int): String? = byTag[tag]

    override fun presentTags(): Set<Int> = fields.map { it.first }.toSet()

    override fun groupEntries(groupTag: Int): List<MessageView> = emptyList()
}
