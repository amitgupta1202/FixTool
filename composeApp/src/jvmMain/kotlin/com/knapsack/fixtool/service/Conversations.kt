package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage

/**
 * **Groups a session log into the business exchanges it actually contains.**
 *
 * A flat chronological grid is the truth and it is unreadable: one RFQ's six messages sit interleaved
 * with three other RFQs, a heartbeat and someone's market-data refresh, and *"did RFQ-B2 ever trade?"*
 * becomes an eye exercise. At a hundred sessions it stops being answerable at all.
 *
 * **The edges come from the messages, not from a rulebook.** Two messages belong together when they
 * share a correlation-id *value*, and a conversation is a connected component under that relation. The
 * chain that matters falls straight out of it, with no `QuoteReqID → QuoteID → ClOrdID` rule written
 * anywhere:
 *
 * ```
 *   QuoteRequest    131=RFQ-A1                    ->  {RFQ-A1}
 *   Quote           117=Q-77  131=RFQ-A1          ->  {RFQ-A1, Q-77}      (joined by RFQ-A1)
 *   NewOrderSingle   11=ORD-9 117=Q-77            ->  {RFQ-A1, Q-77, ORD-9}   (joined by Q-77)
 *   ExecutionReport  37=V-551  11=ORD-9           ->  ... and so on
 * ```
 *
 * Which is the same relation capture reads to wire `${clOrdID}` — an id minted here and echoed there is
 * an edge — applied to display instead of to replay. So a venue's own correlation ids work the moment
 * they are declared in the sidecar beside its dictionary, with nothing added here. See [Minting].
 *
 * **Only declared correlation tags draw edges** ([Minting.isCorrelationId]). Joining on any equal value
 * would put every `54=1` message in one blob — the same false-correlation defect capture already carries
 * a scar from.
 *
 * **Nothing is hidden.** A message carrying no correlation id at all — heartbeats, logons, an
 * unsolicited venue message, anything whose id tag this dictionary cannot place — lands in
 * [Grouping.ungrouped], which the caller must show and count. A view that quietly drops the 8% it did
 * not understand is the silent-coverage-loss defect this codebase refuses everywhere else.
 *
 * Pure and allocation-cheap, but it re-reads every message's fields, so a caller regrouping on each
 * arrival should memoize on the log rather than call it per frame.
 */
object Conversations {
    /**
     * One business exchange: every message that shares a correlation id with any other, transitively.
     *
     * [label] is what the header row shows. It is the first correlation value on the conversation's
     * **earliest** message in wire order — the QuoteReqID of the request that started an RFQ, the
     * ClOrdID of the order that started a lifecycle — because the thing that opened the exchange is
     * what a reader recognises it by. Not the lowest tag number, and not the venue's handle, which is
     * only minted in the reply.
     */
    data class Conversation(
        val label: String,
        val labelTag: Int,
        /** Every correlation value in the component — what the header can cite to explain the grouping. */
        val ids: Set<String>,
        /** In the order they were given, which for a session log is arrival order. */
        val messages: List<FixMessage>,
    )

    /** Conversations in the order they opened, plus everything that belongs to none of them. */
    data class Grouping(
        val conversations: List<Conversation>,
        val ungrouped: List<FixMessage>,
    ) {
        val total: Int get() = conversations.sumOf { it.messages.size } + ungrouped.size
    }

    /** The correlation values a message carries, in wire order, without blanks or repeats. */
    fun idsOf(message: FixMessage, dictionary: FixDictionaryAdapter?): List<Pair<Int, String>> =
        FixMessageHelper
            .fieldsForDisplay(message)
            .filter { (tag, value) -> value.isNotBlank() && Minting.isCorrelationId(tag, dictionary) }
            .distinctBy { it.second }

    fun group(messages: List<FixMessage>, dictionary: FixDictionaryAdapter?): Grouping {
        val union = Union()
        val idsPerMessage = messages.map { idsOf(it, dictionary) }
        // Every value on one message is the same conversation as every other value on it. That single
        // rule is what chains QuoteReqID to QuoteID to ClOrdID: the Quote carrying both joins them.
        for (ids in idsPerMessage) {
            val first = ids.firstOrNull()?.second ?: continue
            ids.forEach { (_, value) -> union.join(first, value) }
        }

        val ungrouped = mutableListOf<FixMessage>()
        val byRoot = linkedMapOf<String, MutableList<Int>>()
        messages.forEachIndexed { index, message ->
            val root = idsPerMessage[index].firstOrNull()?.second?.let(union::rootOf)
            if (root == null) ungrouped += message else byRoot.getOrPut(root) { mutableListOf() } += index
        }

        // linkedMapOf preserves first-seen order, and the first message of a component is the one that
        // created its entry — so conversations come out in the order they opened, with no sort needed
        // (and none wanted: a sort by timestamp would reorder same-millisecond arrivals).
        val conversations =
            byRoot.map { (_, indices) ->
                val opener = idsPerMessage[indices.first()].first()
                Conversation(
                    label = opener.second,
                    labelTag = opener.first,
                    ids = indices.flatMapTo(linkedSetOf()) { i -> idsPerMessage[i].map { it.second } },
                    messages = indices.map { messages[it] },
                )
            }
        return Grouping(conversations, ungrouped)
    }

    /** Union-find over id values, with path compression. Small, private, and the whole algorithm. */
    private class Union {
        private val parent = mutableMapOf<String, String>()

        fun rootOf(value: String): String {
            var root = parent.getOrPut(value) { value }
            while (root != parent.getValue(root)) root = parent.getValue(root)
            var walk = value
            while (walk != root) {
                val next = parent.getValue(walk)
                parent[walk] = root
                walk = next
            }
            return root
        }

        fun join(a: String, b: String) {
            val ra = rootOf(a)
            val rb = rootOf(b)
            if (ra != rb) parent[rb] = ra
        }
    }
}
