package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import quickfix.Group

/**
 * Adapts a captured [FixMessage] to the [MessageView] the [ExpectationEvaluator] reads.
 * Top-level lookups go through QuickFIX (header/body/trailer) and repeating-group entries are read
 * from the parsed QuickFIX message.
 *
 * [presentTags] is **top level only**, which is what STRICT mode means by an "extra" tag. It is
 * resolved through [FixStructure] with the same [dictionary] the expectation was seeded with, so the
 * two sides agree on what lives inside a group: taking every tag off the wire instead made STRICT
 * re-report each grouped field the expectation *did* assert as unexpected, and no message carrying a
 * repeating group could ever pass. Without a dictionary there is no group knowledge on either side —
 * everything reads as top level, and the two still agree.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
class FixMessageView(
    private val message: FixMessage,
    private val dictionary: FixDictionaryAdapter? = null,
) : MessageView {
    private val topLevelTags: Set<Int> by lazy {
        FixStructure.walk(FixMessageHelper.parseFixMessage(message.rawMessage), dictionary)
            .filter { it.path == null }
            .map { it.tag }
            .toSet()
    }

    override fun valueOfTag(tag: Int): String? = message.valueOfTag(tag)

    override fun presentTags(): Set<Int> = topLevelTags

    override fun groupEntries(groupTag: Int): List<MessageView> =
        try {
            message.quickfixMessage.getGroups(groupTag).map { GroupView(it) }
        } catch (e: Exception) {
            emptyList()
        }
}

/** A repeating-group entry as a [MessageView]. Nested groups are not traversed (single level). */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private class GroupView(private val group: Group) : MessageView {
    override fun valueOfTag(tag: Int): String? =
        try {
            if (group.isSetField(tag)) group.getString(tag) else null
        } catch (e: Exception) {
            null
        }

    /**
     * The entry's own fields. STRICT reads this to spot a tag the venue added inside a group, so
     * returning nothing here would quietly answer "no extras" to a question never actually asked.
     */
    override fun presentTags(): Set<Int> =
        try {
            group.iterator().asSequence().map { it.tag }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

    override fun groupEntries(groupTag: Int): List<MessageView> = emptyList()
}
