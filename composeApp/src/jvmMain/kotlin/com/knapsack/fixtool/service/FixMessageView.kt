package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage

/**
 * Adapts a captured [FixMessage] to the [MessageView] the [ExpectationEvaluator] reads.
 *
 * Straight off the wire, in wire order. Not through QuickFIX's parsed field maps: those answer
 * "what is tag 452?" per group entry, which required this class and [RawMessageView] to agree on what
 * an entry contains — and they did not. The engine asserts by occurrence now, so it wants the bytes as
 * they arrived, and both views hand it the same list from the same parser.
 */
class FixMessageView(private val message: FixMessage) : MessageView {
    private val fields by lazy { FixMessageHelper.wireFields(message) }

    override fun fields(): List<Pair<Int, String>> = fields
}
