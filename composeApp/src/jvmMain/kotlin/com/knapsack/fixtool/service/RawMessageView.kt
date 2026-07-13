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
    // Through the one delimiter decider. This used to split on '|' unconditionally, which quietly made the
    // reconcile view — the surface that *authors* assertions from a failure — diff against the lossy
    // display string: a venue's `58=Rejected|insufficient margin` arrived here as `58=Rejected` plus a
    // phantom field, and "Accept actual" would have written the truncated value into the scenario as the
    // thing to assert forever after.
    private val fields = FixMessageHelper.parseStoredMessage(raw)

    override fun fields(): List<Pair<Int, String>> = fields
}
