package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage

/**
 * Adapts a captured [FixMessage] to the [MessageView] the [ExpectationEvaluator] reads.
 *
 * Straight off the wire, in wire order. Not through QuickFIX's parsed field maps: those answer
 * "what is tag 452?" per group entry, which required this class and [RawMessageView] to agree on what
 * an entry contains — and they did not. The engine asserts by occurrence now, so it wants the bytes as
 * they arrived, and both views hand it the same list from the same parser.
 *
 * **It cannot be built from a message whose wire order is unknown.** That is the point of the private
 * constructor: the order *is* the assertion under this model, so a view that quietly substituted
 * QuickFIX's re-serialisation would not be a degraded view — it would be a view of a different message.
 * [of] returns null instead, and the caller says so out loud.
 */
class FixMessageView private constructor(private val fields: List<Pair<Int, String>>) : MessageView {
    override fun fields(): List<Pair<Int, String>> = fields

    companion object {
        /** The view, or **null when we do not have the venue's bytes** — see [FixMessageHelper.wireFields]. */
        fun of(message: FixMessage): FixMessageView? =
            FixMessageHelper.wireFields(message)?.let(::FixMessageView)

        /**
         * **Tests only.** A production caller reaching for this has an escape hatch straight back to the
         * defect the private constructor exists to close — `ofFields(fieldsForDisplay(message))` compiles,
         * reads reasonably, and hands the engine a guessed order. `internal` keeps it inside the module.
         */
        internal fun ofFields(fields: List<Pair<Int, String>>): FixMessageView = FixMessageView(fields)
    }
}
