package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import java.time.Duration

/**
 * **Which trace a fold, a follow or a click is talking about.**
 *
 * A label alone will not do, and across sessions that is not an edge case but the normal case.
 * `ConversationRows` already warns that two venues may both mint `ORD-1`; feed every pane's snapshot to
 * one grouping and the same label arrives from several places at once. Folding one must not fold the
 * others.
 *
 * **The opener's session is named by [openerSessionTitle], not by its index, and that is the whole
 * reason this type exists.** A `Located.session` is a position in the snapshot list of the moment it was
 * computed, and that list is rebuilt from `viewModel.sessions` on every tick — open a pane, close a
 * pane, and every index after it shifts by one. A collapse set keyed on indices would silently re-point
 * at neighbouring traces the instant a session comes or goes, folding a row the reader never touched.
 * A title is what the reader sees on the pane and survives its neighbours.
 *
 * Two panes with the same title collide, and the collision is honest: the app names them the same, so a
 * reader could not tell those two traces apart on screen either.
 */
data class TraceKey(
    val openerSessionTitle: String,
    val label: String,
)

/**
 * **The Ledger's render list** — every trace across every session, flattened into rows a `LazyColumn`
 * can walk.
 *
 * This is [ConversationRows] for [Traces]: the same bargain, one level up. A header per exchange, its
 * members beneath it when it is open, and everything the grouping could not explain last and counted.
 * Pure and cheap, so the panel can rebuild it whenever the grouping behind it changes.
 *
 * **What Elapsed is.** [Row.Member.elapsedMillis] is the gap between one message of a trace and the one
 * before it *in that same trace*, on whichever session each landed. Every timestamp comes off one clock
 * — FixTool held both panes — so the `+31 ms` between a request going OUT of the client and its copy
 * coming IN on an LP is the venue's real forwarding time: the number a venue's own logs cannot give and
 * two machines' logs cannot reconcile.
 *
 * **What Elapsed is not.** It is not a claim about cause. The tool states a measured gap between two
 * messages and says nothing about what filled it — not queueing, not the network, not the venue being
 * slow. It is also not the per-session latency column, which pairs a request with its response and is
 * defined only within one pane; the two are different measurements and deliberately never share a
 * column. Same stance as `Conversations.Summary` and `ScenarioReport`'s diagnosis rows: quote, never
 * infer.
 *
 * The ungrouped bucket's members carry no elapsed at all. They are messages that belong to no exchange
 * and therefore to no sequence, so a gap between two of them would measure the distance between two
 * unrelated events and read as though it meant something.
 */
object TraceRows {
    /**
     * What a trace seen on exactly one session is told, at the moment it matters.
     *
     * A trace crosses a session only where some *value* crosses it. A venue that mints a fresh id per
     * hop and echoes nothing leaves no edge, and joining those would be the tool inventing one — so
     * instead of a silently short trace the header says what the reader can actually do about it. The
     * remedy needs no code: naming the venue's echo tag in the dictionary's `.roles.json` sidecar makes
     * it a correlation id everywhere (`Minting.isCorrelationId` / `TagRoles`).
     */
    const val SIDECAR_HINT: String =
        "one session only · if the far side echoes this under its own tag, name that tag in the " +
            "dictionary's .roles.json sidecar and the trace joins"

    sealed interface Row {
        /** A trace's summary line: everything a reader needs to decide whether to open it. */
        data class Header(
            val key: TraceKey,
            val label: String,
            /** Composition, stated status, instrument, quantity, end-to-end elapsed, session count. */
            val summary: Traces.Summary,
            val sessionCount: Int,
            /** The sessions this trace touched, by index into the snapshots, in first-seen order. */
            val sessions: List<Int>,
            /**
             * Panes whose ring dropped a message this trace would have contained, by title.
             *
             * Non-empty means the trace opened before what is shown. Said on the header rather than
             * left to a first row quietly pretending to be the first message.
             */
            val truncatedSessionTitles: List<String>,
            val expanded: Boolean,
            /** This is the app's one followed trace — the header draws its Follow affordance pressed. */
            val isFollowed: Boolean,
            val memberCount: Int,
            /** [SIDECAR_HINT] when this trace touched one session and the app holds more than one. */
            val hint: String? = null,
        ) : Row

        /**
         * One message of a trace, or of the ungrouped bucket.
         *
         * [located] addresses the snapshots this list was built from — it is what a click acts on, so
         * the panel can raise the right pane. [message] is carried rather than re-fetched because the
         * builder had it in hand and a renderer that looked it up again could look it up in a newer
         * generation of snapshots than the one the row was built against.
         */
        data class Member(
            val located: Located,
            val message: FixMessage,
            /** Since the previous member of the SAME trace; null on the first, and on every ungrouped row. */
            val elapsedMillis: Long?,
            /** Null for the ungrouped bucket, which is not a trace. */
            val traceKey: TraceKey?,
        ) : Row

        /**
         * Everything carrying no correlation id, counted.
         *
         * Last in the list and never omitted: a view that quietly drops the messages it could not
         * explain is the silent-coverage-loss defect this codebase refuses everywhere else.
         */
        data class UngroupedHeader(
            val count: Int,
            val expanded: Boolean,
        ) : Row
    }

    /**
     * The key a trace is folded and followed by. Public because the follow state has to compute the
     * same key from the same trace, and two derivations of one key is one derivation too many.
     */
    fun keyOf(
        trace: Traces.Trace,
        sessionTitles: List<String>,
    ): TraceKey =
        TraceKey(
            // members is in merged order, so its first element opened the exchange; `sessions` is that
            // same order deduplicated, and is the fallback for a trace with no members to read.
            openerSessionTitle =
                (trace.members.firstOrNull()?.session ?: trace.sessions.firstOrNull())
                    ?.let { sessionTitles.getOrNull(it) }
                    .orEmpty(),
            label = trace.label,
        )

    /**
     * Traces in the order the grouping opened them, each header followed by its members while it is
     * expanded, and the ungrouped bucket last.
     *
     * Takes the three fields a `TraceIndex` holds rather than the index itself, so the row builder stays
     * in `service` beside the grouping it renders instead of reaching up into `viewmodel` — the caller
     * passes `index.snapshots, index.sessionTitles, index.grouping`, which are only meaningful together
     * anyway. [grouping] must be the one computed from [snapshots]: every [Located] here indexes into
     * them directly, and a mismatched pair is a programming error rather than a row to skip quietly.
     *
     * Everything is collapsed by whoever owns [expanded] and [ungroupedExpanded]; nothing here decides
     * what is open. That includes the followed trace — it is marked [Row.Header.isFollowed] and no more,
     * because a header forced open by its own state could never be closed by the reader.
     */
    fun build(
        snapshots: List<List<FixMessage>>,
        sessionTitles: List<String>,
        grouping: Traces.Grouping,
        dictionary: FixDictionaryAdapter?,
        expanded: Set<TraceKey> = emptySet(),
        ungroupedExpanded: Boolean = false,
        followedAnchor: String? = null,
    ): List<Row> {
        // A single-session trace is only worth remarking on where crossing a session was possible at
        // all. With one pane open every trace is single-session by definition, and saying so on every
        // header would be noise the reader learns to skip past.
        val crossSessionPossible = sessionTitles.size > 1

        val rows = ArrayList<Row>(grouping.traces.size + grouping.total + 1)
        for (trace in grouping.traces) {
            val key = keyOf(trace, sessionTitles)
            val isExpanded = key in expanded
            rows +=
                Row.Header(
                    key = key,
                    label = trace.label,
                    summary = Traces.summarize(trace, snapshots, dictionary),
                    sessionCount = trace.sessions.size,
                    sessions = trace.sessions,
                    truncatedSessionTitles = trace.truncatedSessions.mapNotNull { sessionTitles.getOrNull(it) },
                    expanded = isExpanded,
                    isFollowed = followedAnchor != null && followedAnchor in trace.ids,
                    memberCount = trace.members.size,
                    hint = if (crossSessionPossible && trace.sessions.size == 1) SIDECAR_HINT else null,
                )
            if (!isExpanded) continue
            var previous: FixMessage? = null
            for (member in trace.members) {
                val message = snapshots[member.session][member.index]
                rows +=
                    Row.Member(
                        located = member,
                        message = message,
                        elapsedMillis =
                            previous?.let { Duration.between(it.timestamp, message.timestamp).toMillis() },
                        traceKey = key,
                    )
                previous = message
            }
        }

        if (grouping.ungrouped.isNotEmpty()) {
            rows += Row.UngroupedHeader(count = grouping.ungrouped.size, expanded = ungroupedExpanded)
            if (ungroupedExpanded) {
                for (located in grouping.ungrouped) {
                    rows +=
                        Row.Member(
                            located = located,
                            message = snapshots[located.session][located.index],
                            elapsedMillis = null,
                            traceKey = null,
                        )
                }
            }
        }
        return rows
    }
}
