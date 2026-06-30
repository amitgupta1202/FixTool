package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import quickfix.Group

/**
 * Adapts a captured [FixMessage] to the [MessageView] the [ExpectationEvaluator] reads.
 * Top-level lookups go through QuickFIX (header/body/trailer); the present-tag set is taken
 * from the raw string so STRICT mode sees exactly what was on the wire; repeating-group
 * entries are read from the parsed QuickFIX message.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
class FixMessageView(private val message: FixMessage) : MessageView {
    override fun valueOfTag(tag: Int): String? = message.valueOfTag(tag)

    override fun presentTags(): Set<Int> =
        FixMessageHelper.parseFixMessage(message.rawMessage).map { it.first }.toSet()

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

    override fun presentTags(): Set<Int> = emptySet()

    override fun groupEntries(groupTag: Int): List<MessageView> = emptyList()
}
