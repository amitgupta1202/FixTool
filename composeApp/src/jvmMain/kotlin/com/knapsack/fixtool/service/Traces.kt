package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage

/**
 * **A message's address across the app**: which session's snapshot, and where in it.
 *
 * The session lives here rather than on [FixMessage] on purpose. `FixMessage` is a `data class` with
 * structural equality — two identical messages on two sessions must still compare equal, because a
 * both-sides test sends the same bytes out of one pane and reads them into another — and it is a holder
 * of *wire facts*. Which pane logged it is the container's knowledge, not the message's. `SearchResult`
 * and `CapturedSession` already carry it this way.
 *
 * [session] is a position in the snapshots list handed to [Traces.group]; [index] a position within
 * that snapshot. Both are positions, not identities, so a `Located` is only meaningful against the
 * snapshots it was computed from — which is the same contract `Conversations.Conversation.indices` has,
 * and for the same reason: the caller already holds the lists.
 */
data class Located(
    val session: Int,
    val index: Int,
)

/**
 * **One exchange followed through every session at once.**
 *
 * A QA testing an RFQ venue holds several sessions in one FixTool: a client session that sends the
 * QuoteRequest in, and two or three liquidity-provider sessions that receive the venue's forwarded copy
 * and quote back. One business event leaves messages in every pane, and the venue mints its own ids on
 * the way through — so the same exchange is `RFQ-A1` on the client, `V-2291` on the LPs and `Q-77` on
 * the quote that bridges them. [Conversations] answers *what happened to RFQ-A1 on this session*.
 * Nothing answered *what happened to RFQ-A1*.
 *
 * **This is [Conversations] over a longer list, and that is the whole design.** The relation was already
 * session-agnostic: it joins on shared correlation-id *values* and has no rulebook to teach about panes.
 * Fed every session's snapshot at once it joins the client's `RFQ-A1 → Q-77` to LP-1's `Q-77 → V-2291`
 * to LP-2's `V-2291`, with no algorithm change. Both callers get their components from
 * [CorrelationComponents], so a pane's conversation *is* its trace's slice on that pane — pinned by
 * `TracesTest`, not asserted by comment.
 *
 * **Merged order** is the key the cross-session search results already sort by: timestamp, then
 * `MsgSeqNum(34)`, then `SenderCompID(49)`, then `(session, index)` as a final tiebreak so the answer is
 * the same on every run. One clock makes the timestamp sound — FixTool held every session, so the `31ms`
 * between a request leaving the client and its copy arriving on LP-1 is the venue's real forwarding
 * time, not two machines' clocks being compared.
 *
 * Restricted to one session that order is the session's own arrival order, because a live session's log
 * is already in it. It is not *forced* to be: a snapshot assembled out of key order (a replayed file, a
 * test corpus with tied timestamps and descending sequence numbers) sorts to the key, and then the
 * trace's slice is that session's conversation re-ordered rather than re-grouped. The membership is the
 * same either way; only the row order can differ.
 *
 * **Where the chain breaks is not repaired here.** A trace crosses a session only where some *value*
 * crosses it. A venue that mints a fresh id per hop and echoes nothing leaves no edge, and joining those
 * would be the tool inventing one. The remedy is to declare the venue's echo tag in its `.roles.json`
 * sidecar, which makes it an edge everywhere with no code — see [Minting.isCorrelationId].
 *
 * Pure and allocation-cheap for its size, but it re-reads every message's fields and re-sorts the merged
 * list, so a caller regrouping on a drain tick should memoise on the tuple of snapshot identities.
 */
object Traces {
    /**
     * One business exchange as it appears across every session that touched it.
     *
     * [label] follows [Conversations]' rule exactly: the first correlation value on the trace's
     * **earliest** message in merged order. Across sessions that is normally the client's own id — the
     * thing the QA typed — rather than the venue's handle, which does not exist until the reply.
     */
    data class Trace(
        val label: String,
        val labelTag: Int,
        /** Every correlation value in the component — what a header can cite to explain the grouping. */
        val ids: Set<String>,
        /** Every message in the trace, in merged time order. */
        val members: List<Located>,
        /**
         * Where each of [members] sits in [Grouping.order], in the same order.
         *
         * Carried rather than re-derived, the same bargain [Conversations.Conversation.indices] strikes:
         * a renderer needs positions to reach [Grouping.idsPerMessage] for its id columns, and this
         * function works in positions internally. Handing back only `Located` would make the caller
         * build a map to recover what was already known.
         */
        val positions: List<Int>,
        /** The sessions this trace touched, in the order it first appeared on each. */
        val sessions: List<Int>,
        /**
         * Sessions that lost a message this trace would have contained. Empty means nothing was lost.
         *
         * See [group]'s `lostIds` parameter for what that claim rests on and when it can be wrong.
         */
        val truncatedSessions: List<Int>,
    ) {
        /**
         * **This trace is missing history, and it is missing it at the front.**
         *
         * Both of a session's loss mechanisms drop the *oldest* thing they hold — the retained window
         * evicts its head at `bufferSize`, and an overflowing ingest queue discards its head to make
         * room (`FixMessageSession.addMessage`). So a trace that lost anything opened before what is
         * shown, and a header saying so is the discard-counter discipline applied to the view: better
         * `opened before the buffer` than a first row quietly pretending to be the first message.
         */
        val truncatedAtHead: Boolean get() = truncatedSessions.isNotEmpty()
    }

    /** Traces in the order they opened, plus everything that belongs to none of them. */
    data class Grouping(
        val traces: List<Trace>,
        /**
         * Everything carrying no correlation id — heartbeats, logons, an unsolicited venue message — in
         * merged order, kept and countable.
         *
         * Not a residue to be tidied away: a view that quietly drops the 8% it did not understand is the
         * silent-coverage-loss defect this codebase refuses everywhere else.
         */
        val ungrouped: List<Located>,
        /** The merged order every position in this grouping indexes into. */
        val order: List<Located> = emptyList(),
        /** The correlation ids of each message, by position in [order] — exposed so nobody pays twice. */
        val idsPerMessage: List<List<Pair<Int, String>>> = emptyList(),
    ) {
        val total: Int get() = traces.sumOf { it.members.size } + ungrouped.size

        /** How many sessions contributed at least one message, grouped or not. */
        val sessionCount: Int get() = order.mapTo(HashSet()) { it.session }.size
    }

    /**
     * The same relation [Conversations.group] computes, over every session's snapshot at once.
     *
     * [snapshots] is positional: session *s* is `snapshots[s]`, and that is what [Located.session] means.
     *
     * [lostIds], when given, is parallel to [snapshots]: the correlation values of messages session *s*
     * evicted or discarded, from `FixMessageSession.lostCorrelationIds`. A trace whose id set intersects
     * one of those sets lost a member on that session, and only then is it flagged — the rule is that a
     * trace which lost nothing is never flagged, so a caller with no such record passes nothing and gets
     * no flags rather than guesses. The flag can under-report in two ways, both stated at the source: a
     * session only remembers ids from the standard tag set, and it only remembers the last
     * `MAX_LOST_CORRELATION_IDS` of them. It can over-report only where two sessions genuinely reused one
     * id value for different exchanges — the same collision that would already have merged them into one
     * trace, which [Trace.ids] is there to explain.
     */
    fun group(
        snapshots: List<List<FixMessage>>,
        dictionary: FixDictionaryAdapter?,
        lostIds: List<Set<String>> = emptyList(),
    ): Grouping {
        val entries = ArrayList<Entry>(snapshots.sumOf { it.size })
        snapshots.forEachIndexed { session, snapshot ->
            snapshot.forEachIndexed { index, message ->
                // One read of the cached field list serves both the sort key and the id pass; asking
                // Conversations.idsOf afterwards would walk the same list a second time.
                val fields = FixMessageHelper.fieldsForDisplay(message)
                entries +=
                    Entry(
                        located = Located(session, index),
                        message = message,
                        seq = valueOn(fields, MSG_SEQ_NUM)?.toIntOrNull(),
                        sender = valueOn(fields, SENDER_COMP_ID),
                        ids = correlationIdsOn(fields, dictionary),
                    )
            }
        }
        entries.sortWith(MERGED_ORDER)

        val idsPerMessage = entries.map { it.ids }
        val components = CorrelationComponents.of(idsPerMessage)
        val traces =
            components.components.map { positions ->
                val opener = idsPerMessage[positions.first()].first()
                val ids = LinkedHashSet<String>()
                for (position in positions) for (id in idsPerMessage[position]) ids.add(id.second)
                val members = positions.map { entries[it].located }
                Trace(
                    label = opener.second,
                    labelTag = opener.first,
                    ids = ids,
                    members = members,
                    positions = positions,
                    // members is already in merged order, so distinct() is first-seen order.
                    sessions = members.map { it.session }.distinct(),
                    // Probe the small set against the large one, never the other way round: a session's
                    // lost-id memory holds thousands of values and a trace holds a handful.
                    truncatedSessions = lostIds.indices.filter { session -> ids.any { it in lostIds[session] } },
                )
            }
        return Grouping(
            traces = traces,
            ungrouped = components.ungrouped.map { entries[it].located },
            order = entries.map { it.located },
            idsPerMessage = idsPerMessage,
        )
    }

    /**
     * What a Ledger header may say about a trace: everything a single-session header says, plus the one
     * thing only a trace knows.
     *
     * [Conversations.summarize] does the work — the quote-never-infer rule it enforces is the same rule
     * here, and stating it twice would be inviting the two to drift.
     */
    data class Summary(
        /** Composition, stated status, instrument, quantity and elapsed, over the merged members. */
        val exchange: Conversations.Summary,
        /** How many sessions the trace touched — what makes `4 sessions` readable off a header row. */
        val sessionCount: Int,
    )

    fun summarize(
        trace: Trace,
        snapshots: List<List<FixMessage>>,
        dictionary: FixDictionaryAdapter?,
    ): Summary =
        Summary(
            exchange =
                Conversations.summarize(
                    trace.label,
                    trace.labelTag,
                    trace.members.map { snapshots[it.session][it.index] },
                    dictionary,
                ),
            sessionCount = trace.sessions.size,
        )

    private const val MSG_SEQ_NUM = 34
    private const val SENDER_COMP_ID = 49

    /**
     * The cross-session order, which is the search-results pane's key
     * (`FixMessageViewModel.searchAllSessions`) plus a tiebreak it did not need.
     *
     * A message with no `MsgSeqNum` or `SenderCompID` sorts last among its ties rather than first: a
     * session's own notice has no place ahead of the wire traffic it sits between. `(session, index)`
     * finishes the job so that two messages alike in every sorted field still come out in one fixed
     * order — a grid whose rows shuffle between two identical rebuilds is a bug report nobody can
     * reproduce.
     */
    private val MERGED_ORDER: Comparator<Entry> =
        compareBy<Entry> { it.message.timestamp }
            .thenBy(nullsLast()) { it.seq }
            .thenBy(nullsLast()) { it.sender }
            .thenBy { it.located.session }
            .thenBy { it.located.index }

    private fun valueOn(fields: List<Pair<Int, String>>, tag: Int): String? =
        fields.firstOrNull { it.first == tag }?.second?.takeIf { it.isNotBlank() }

    /**
     * [Conversations.idsOf]'s body over fields already in hand.
     *
     * The same predicate and the same `distinctBy`, deliberately — [Minting.isCorrelationId] stays the one
     * decider about which tags draw edges. Only the parse is skipped, because the caller read the field
     * list a line earlier to build the sort key and `idsOf` would walk the message again to get it.
     */
    private fun correlationIdsOn(
        fields: List<Pair<Int, String>>,
        dictionary: FixDictionaryAdapter?,
    ): List<Pair<Int, String>> =
        fields
            .filter { (tag, value) -> value.isNotBlank() && Minting.isCorrelationId(tag, dictionary) }
            .distinctBy { it.second }

    /**
     * One message on its way into the merged list: where it came from, what it sorts by, what it joins on.
     *
     * The sort key is read from the message's own cached fields rather than from `quickfixMessage.header`
     * — the same bytes, one parse already paid for, and an answer that still exists for a message whose
     * QuickFIX/J object never got built.
     */
    private class Entry(
        val located: Located,
        val message: FixMessage,
        val seq: Int?,
        val sender: String?,
        val ids: List<Pair<Int, String>>,
    )
}
