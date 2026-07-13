package com.knapsack.fixtool.service

/**
 * A [MessageView] over a raw pipe/SOH-delimited FIX string — the expectation builder's live preview,
 * where a parsed QuickFIX message is not on hand.
 *
 * It takes no dictionary, because there is nothing left to ask one. It used to walk [FixStructure] to
 * decide which fields lived inside a repeating group, mirroring what [FixMessageView] worked out from
 * QuickFIX — and the two answers were not always the same, which is how the preview came to contradict
 * the runner. Neither has to decide now: a message is an ordered list of `tag=value`, and both views
 * read it from the same parser.
 */
class RawMessageView(raw: String) : MessageView {
    private val fields = FixMessageHelper.parseFixMessage(raw)

    override fun fields(): List<Pair<Int, String>> = fields
}
