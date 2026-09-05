package com.knapsack.fixtool.model.load

import com.knapsack.fixtool.model.CorrelationIdType
import com.knapsack.fixtool.service.Minting

/**
 * **The one message a load run issues**, as tag-value pairs with their `${…}` still in them.
 *
 * A saved message from the editor, or one line of a `.fix` file. The compiled form that decides what is
 * resolved per message and what once per lane is `CompiledTemplate`; this is the source it compiles.
 */
data class LoadTemplate(
    val name: String,
    val fields: List<Pair<Int, String>>,
) {
    /** Tag 35, which every template must carry for the matcher to know what it is issuing. */
    val msgType: String? get() = fields.firstOrNull { it.first == MSG_TYPE }?.second?.trim()?.takeIf { it.isNotEmpty() }

    /** The tags this template carries, in order. */
    val tags: List<Int> get() = fields.map { it.first }

    /**
     * **The correlation the run will match on when nobody says**: the first standard correlation tag the
     * message carries, read off the request and expected back on the reply under the same number.
     *
     * A NewOrderSingle with tag 11 wants 11 to 11, a QuoteRequest with 131 wants 131 to 131, and making
     * the common case say nothing is what keeps `fixtool load` to the two lines the issue proposed. Null
     * when the template carries none of them, which the command and the dialog turn into a refusal that
     * names the tags they looked for.
     */
    fun inferMatch(): LoadMatch? = CORRELATION_ORDER.firstOrNull { tag -> fields.any { it.first == tag } }?.let { LoadMatch(it) }

    /** The template as one `|`-delimited line, the form the tool shows and parses everywhere. */
    fun raw(): String = fields.joinToString("|") { "${it.first}=${it.second}" } + "|"

    companion object {
        const val MSG_TYPE = 35

        /**
         * The tags [inferMatch] tries, in order: the latency panel's built-ins first, because a request
         * template is overwhelmingly a ClOrdID, a QuoteReqID or an MDReqID, then every other tag the tool
         * knows as a correlation id, in numeric order so the choice is stable.
         */
        val CORRELATION_ORDER: List<Int> by lazy {
            val builtIn = CorrelationIdType.allTags()
            builtIn + (Minting.STANDARD_CORRELATION_TAGS - builtIn.toSet()).sorted()
        }
    }
}
