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
        /**
         * Where each of [messages] sat in the list handed to [group], in the same order.
         *
         * Carried rather than re-derived. `ConversationRows` needs positions, not objects — its render
         * rows address the original list — and it used to recover them by building a
         * `HashMap<FixMessage, Int>` over the whole log and looking each message back up. That is a
         * round trip: [group] works in indices internally and threw them away at the last step, so the
         * caller hashed a thousand data-class messages to learn something this function already knew.
         */
        val indices: List<Int>,
    )

    /** Conversations in the order they opened, plus everything that belongs to none of them. */
    data class Grouping(
        val conversations: List<Conversation>,
        val ungrouped: List<FixMessage>,
        /**
         * The correlation ids of each message, by position in the list handed to [group].
         *
         * Exposed so a caller that needs them again does not pay for a second pass. `ConversationRows`
         * builds a per-conversation id column and called [idsOf] a second time for every message to do
         * it — the same answer, recomputed, for the same reason the field parse was.
         */
        val idsPerMessage: List<List<Pair<Int, String>>> = emptyList(),
        /** Positions of [ungrouped] in the list handed to [group], in the same order. */
        val ungroupedIndices: List<Int> = emptyList(),
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
        val ungroupedIndices = mutableListOf<Int>()
        val byRoot = linkedMapOf<String, MutableList<Int>>()
        messages.forEachIndexed { index, message ->
            val root = idsPerMessage[index].firstOrNull()?.second?.let(union::rootOf)
            if (root == null) {
                ungrouped += message
                ungroupedIndices += index
            } else {
                byRoot.getOrPut(root) { mutableListOf() } += index
            }
        }

        // linkedMapOf preserves first-seen order, and the first message of a component is the one that
        // created its entry — so conversations come out in the order they opened, with no sort needed
        // (and none wanted: a sort by timestamp would reorder same-millisecond arrivals).
        val conversations =
            byRoot.map { (_, indices) ->
                val opener = idsPerMessage[indices.first()].first()
                val ids = LinkedHashSet<String>()
                for (i in indices) for (id in idsPerMessage[i]) ids.add(id.second)
                Conversation(
                    label = opener.second,
                    labelTag = opener.first,
                    ids = ids,
                    messages = indices.map { messages[it] },
                    indices = indices,
                )
            }
        return Grouping(conversations, ungrouped, idsPerMessage, ungroupedIndices)
    }

    /**
     * **What the header row is allowed to say**, and the rule is one sentence: it states only what it can
     * point at.
     *
     * A count of what arrived is a fact. The last status a message carried, rendered in the dictionary's
     * own words, is a **quotation** — `Filled` appears because a reply said `39=2` and the dictionary
     * calls that FILLED, not because FixTool added up the fills and formed an opinion. Both are safe.
     *
     * What it must never do is derive a state the messages did not state: infer that an order is stuck,
     * compute an average price nobody sent, or decide a conversation is "done". The moment a summary
     * models a FIX state machine it can be confidently wrong, and a reader has no way to tell which line
     * is quotation and which is inference. [ScenarioReport]'s diagnosis rows take the same position —
     * they explain an absence with the presences around it, and a diagnosis has no vote.
     */
    data class Summary(
        val label: String,
        val labelTag: Int,
        val messageCount: Int,
        /** Message types in first-seen order with their counts — `QuoteRequest ×1, Quote ×2`. */
        val composition: List<Part>,
        /** The last status any message stated, or null where none did. Never inferred. */
        val status: Stated?,
        /** `Symbol(55)` off the opening message — **only when unambiguous**; see [singleValueOn]. */
        val instrument: String?,
        /** `OrderQty(38)` off the opening message, on the same terms. */
        val quantity: String?,
        val elapsedMillis: Long,
    )

    /** One message type's share of a conversation. [name] is the dictionary's word, null if it has none. */
    data class Part(
        val messageType: String,
        val name: String?,
        val count: Int,
    )

    /** A status as the counterparty stated it: the tag, the raw value, and the dictionary's name for it. */
    data class Stated(
        val tag: Int,
        val fieldName: String?,
        val value: String,
        val valueName: String?,
    )

    /**
     * Status fields, standard FIX only — the same rule [ScenarioCapture.ID_TAGS] keeps, and for the same
     * reason: a proprietary tag in this source is a claim about every venue. A venue whose status lives
     * in its own tag shows up through [composition] instead, which needs no declaration.
     */
    private val STATUS_TAGS = listOf(39, 297, 87) // OrdStatus, QuoteStatus, AllocStatus

    fun summarize(conversation: Conversation, dictionary: FixDictionaryAdapter?): Summary {
        val messages = conversation.messages
        val opener = FixMessageHelper.fieldsForDisplay(messages.first())
        val composition =
            messages
                .groupingBy { it.messageType }
                .eachCount()
                .map { (type, count) -> Part(type, dictionary?.getFieldValueDescription(35, type), count) }
        // The LAST message that stated one — the most recent thing said about state, not a scan for the
        // most advanced-looking value, which would be FixTool choosing a winner.
        val status =
            messages.asReversed().firstNotNullOfOrNull { message ->
                val fields = FixMessageHelper.fieldsForDisplay(message)
                STATUS_TAGS.firstNotNullOfOrNull { tag ->
                    fields.firstOrNull { it.first == tag }?.second?.let { value ->
                        Stated(tag, dictionary?.getFieldName(tag), value, dictionary?.getFieldValueDescription(tag, value))
                    }
                }
            }
        return Summary(
            label = conversation.label,
            labelTag = conversation.labelTag,
            messageCount = messages.size,
            composition = composition,
            status = status,
            instrument = singleValueOn(opener, 55),
            quantity = singleValueOn(opener, 38),
            elapsedMillis =
                java.time.Duration
                    .between(messages.first().timestamp, messages.last().timestamp)
                    .toMillis(),
        )
    }

    /**
     * **A value only where the message leaves no doubt which one is meant.**
     *
     * A two-leg RFQ carries two `Symbol(55)`s, and showing the first as *the* instrument is a claim that
     * is wrong half the time and unfalsifiable from the header. Occurring exactly once is the only case
     * where the answer is the message's rather than ours; otherwise the header says nothing and the
     * reader opens the conversation, which is what the nesting is for.
     */
    private fun singleValueOn(fields: List<Pair<Int, String>>, tag: Int): String? {
        val only = fields.singleOrNull { it.first == tag } ?: return null
        return only.second.takeIf { it.isNotBlank() }
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
