package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import java.time.Duration
import java.time.LocalDateTime

/**
 * **Which side of the connection a pane's profile made it** — a fact read off the profile, never
 * inferred from what the traffic looks like.
 *
 * The distinction is the one FIX itself draws: an initiator dials out, an acceptor holds a port and is
 * dialled. `FixConnectionConfig.connectionType` is where a pane's answer comes from, including for the
 * per-client panes a multi-client venue opens — those are made from the acceptor profile's config, so
 * they say ACCEPTOR, which is what they are.
 *
 * [UNKNOWN] is a real answer and not a default to be tidied away: a pane that has never been given a
 * config has no side, and guessing one from its CompIDs would be exactly the inference this codebase
 * refuses everywhere the grouping touches. It sorts last and carries no divider.
 */
enum class LaneRole {
    INITIATOR,
    ACCEPTOR,
    UNKNOWN,
}

/**
 * **One trace as the picture a QA draws on a whiteboard**: a column per session, time running down, and
 * every message a chip in the lane of the pane that logged it.
 *
 * This is [TraceRows] over the same trace, re-shaped. It draws nothing the Ledger does not already
 * hold and decides nothing the Ledger decides differently — both read [Traces.Trace.members] in the one
 * merged order, so a reader switching renderings is switching *shape*, never answers.
 *
 * **The venue under test is the space between the lanes, and nothing is drawn there.** FixTool holds no
 * session with itself, so there is no lane for the thing in the middle. What it does hold is *both ends
 * of every hop* — the request leaving the client's lane and arriving in three acceptor lanes a few
 * milliseconds later — which is the one picture a venue's own logs cannot draw, because a venue sees
 * each end from a different process and reconciles them across two clocks. Here there is one clock, and
 * the gap between the lanes is where the venue's own time went.
 *
 * **What a pair is.** In a both-sides test — FixTool playing the client *and* the venue in one instance
 * — the same bytes appear as an OUTGOING on one pane and an INCOMING on another. [build] draws those as
 * **one** row with one arrow, and the only thing it rests on is that the bytes are identical. That is a
 * fact about two strings.
 *
 * **What a pair is not.** It is not a claim that the venue forwarded anything, that these two messages
 * are causally related, or that the hop drawn between the lanes is the route the message took. Two
 * panes logging identical bytes is evidence of exactly that and nothing more; a venue that rewrote one
 * tag leaves two rows, and it is right that it does. The Ledger keeps both rows in every case, because
 * both panes logged it — pairing is a *rendering*, not a re-grouping, and the two views are reconcilable
 * message for message because of it.
 *
 * **Elapsed states a gap and never a cause.** Every timestamp comes off one clock, so the number between
 * two rows is a real measurement — but it says only that this much time passed between two messages. It
 * does not say what filled it: not the network, not queueing, not the venue being slow. Same stance as
 * [TraceRows], [Conversations.Summary] and `ScenarioReport`'s diagnosis rows: quote, never infer.
 *
 * Pure, and cheap for its size — it reads each message's already-cached fields not at all, only its
 * timestamp, direction and bytes.
 */
object TraceLanes {
    /** One session's column: which pane it is, what it is called, and which side of the wire it holds. */
    data class Lane(
        val session: Int,
        val title: String,
        val role: LaneRole,
    )

    /**
     * One message in its lane.
     *
     * [session] and [index] address the snapshots [build] was handed, exactly as [Located] does — and
     * [located] is how a click reaches `navigateToTraceMember` with the same address the Ledger row
     * click uses. [message] is carried rather than re-fetched for the reason `TraceRows.Row.Member`
     * carries it: a renderer that looked it up again could look it up in a newer generation of
     * snapshots than the one this row was built against.
     */
    data class Entry(
        val session: Int,
        val index: Int,
        val message: FixMessage,
    ) {
        val located: Located get() = Located(session, index)
    }

    /**
     * One line of the picture: a single message, or one hop drawn from both of its ends.
     *
     * [to] is null for all but a same-bytes pair. When it is set, [from] is the OUTGOING side and [to]
     * the INCOMING one, on a different session — see [TraceLanes] for what that does and does not claim.
     *
     * **[elapsedMillis] is the gap since the previous row started**, on every row, paired or not: the
     * time between the previous line's first timestamp and this one's. Rows come out in merged order,
     * so it is never negative — which "since the previous row *ended*" cannot promise once a venue fans
     * one request out to three lanes (the second copy leaves before the first copy lands). Where no
     * pairing happens, which is every real venue test (a re-minted hop never shares bytes), it is the
     * Ledger's Elapsed to the millisecond. Null on the first row, which has no previous.
     *
     * **[hopMillis] is the gap between the two ends of a paired row** — how long the thing between the
     * lanes took — and null on an unpaired row. The renderer prints it *on the arrow*, where what it
     * measures is unambiguous, and the gutter keeps [elapsedMillis], so a paired row shows both numbers
     * and neither is repeated. Both are measurements on one clock; neither is a diagnosis.
     */
    data class LaneRow(
        val elapsedMillis: Long?,
        val hopMillis: Long?,
        val from: Entry,
        val to: Entry?,
    ) {
        /** Two panes logged these bytes; this row draws the hop between them. */
        val paired: Boolean get() = to != null

        /** The later of the row's ends — what the next row measures its gap from. */
        val lastTimestamp: LocalDateTime get() = (to ?: from).message.timestamp
    }

    /**
     * The whole picture: the columns, the rows, and where the rule between the two sides goes.
     *
     * [acceptorDividerAt] is the lane index the dashed rule is drawn *before*, and it is null unless the
     * trace actually touched both sides. A rule with nothing on one side of it would be a divider
     * dividing nothing, which reads as a claim about topology rather than as the profiles' own answer.
     */
    data class Lanes(
        val lanes: List<Lane>,
        val rows: List<LaneRow>,
        val acceptorDividerAt: Int?,
    ) {
        private val laneBySession: Map<Int, Int> =
            lanes.withIndex().associate { (position, lane) -> lane.session to position }

        /** Which column a session's chips go in, or -1 for a session this trace never touched. */
        fun laneOf(session: Int): Int = laneBySession[session] ?: -1
    }

    /**
     * The lanes and rows for one trace.
     *
     * [snapshots], [sessionTitles] and [sessionRoles] are all positional and all mean *session s*, the
     * same index [Located.session] carries — they are captured together in `TraceIndex` for that reason,
     * because a role read a tick later than the snapshot it labels would name the wrong pane the moment
     * one is opened or closed. A session with no role recorded is [LaneRole.UNKNOWN] rather than a guess.
     *
     * **Lane order is initiators, then acceptors, then unknown**, and within each group the order the
     * trace first appeared on each session. That is the mockup's picture — the side that dials out on
     * the left, the side that is dialled on the right, the dashed rule between them — and it is read
     * from the profiles rather than from the traffic, so a venue that happens to speak first does not
     * change which column it sits in.
     */
    fun build(
        trace: Traces.Trace,
        snapshots: List<List<FixMessage>>,
        sessionTitles: List<String>,
        sessionRoles: List<LaneRole>,
    ): Lanes {
        val lanes =
            trace.sessions
                .map { session ->
                    Lane(
                        session = session,
                        title = sessionTitles.getOrNull(session) ?: "session $session",
                        role = sessionRoles.getOrNull(session) ?: LaneRole.UNKNOWN,
                    )
                }
                // Stable, so within a role the columns keep the order the trace reached them in — which
                // for an RFQ is the order the QA would have drawn them.
                .sortedBy { it.role.ordinal }

        val entries =
            trace.members.map { member ->
                Entry(member.session, member.index, snapshots[member.session][member.index])
            }
        return Lanes(
            lanes = lanes,
            rows = rowsOf(entries),
            acceptorDividerAt =
                lanes
                    .indexOfFirst { it.role == LaneRole.ACCEPTOR }
                    .takeIf { it >= 0 && lanes.any { lane -> lane.role == LaneRole.INITIATOR } },
        )
    }

    /**
     * The rows, in merged order, with each same-bytes pair folded into the row its OUT opened.
     *
     * Two passes and not one, because the OUT is always the earlier of a pair: walking once would have
     * to emit the OUT's row before knowing whether an IN is going to complete it, and a picture that
     * drew the same hop as one row or two depending on what came later would be a different picture on
     * every tick.
     */
    private fun rowsOf(entries: List<Entry>): List<LaneRow> {
        val partner = IntArray(entries.size) { UNPAIRED }
        val absorbed = BooleanArray(entries.size)
        pair(entries, partner, absorbed)

        val rows = ArrayList<LaneRow>(entries.size)
        var previousStart: LocalDateTime? = null
        entries.forEachIndexed { position, entry ->
            // Already drawn as the far end of an earlier row's arrow. One hop, one row.
            if (absorbed[position]) return@forEachIndexed
            val mate = partner[position].takeIf { it != UNPAIRED }?.let { entries[it] }
            val started = entry.message.timestamp
            rows +=
                LaneRow(
                    elapsedMillis = previousStart?.let { gap(it, started) },
                    hopMillis = mate?.let { gap(started, it.message.timestamp) },
                    from = entry,
                    to = mate,
                )
            previousStart = started
        }
        return rows
    }

    /**
     * Match every INCOMING against the earliest unpaired OUTGOING carrying the same bytes on **another**
     * session.
     *
     * Four refusals, and each of them is the same refusal — that a pair is a statement about bytes and
     * about nothing else:
     *
     * - **Never two messages on one session.** A pane that logs its own echo has not drawn a hop; it has
     *   one message twice, and joining them would invent a lane crossing where there is no second lane.
     * - **Never two INs or two OUTs.** A hop has one end leaving and one end arriving. Two arrivals of
     *   the same bytes on two panes is a venue fanning out to two counterparties — two rows, because
     *   that is two things that happened.
     * - **Never an OUT that is already paired.** A message arrives once. A venue that broadcasts one
     *   quote to three LPs leaves three OUTs and three INs, and each IN takes the earliest OUT still
     *   unspoken for, so three arrows are drawn rather than one arrow drawn three times.
     * - **Never differing bytes.** [sameBytes] is the whole test.
     *
     * Earliest-first matching is what makes the answer stable: the same trace laid out twice must give
     * the same picture, and "whichever candidate the map happened to hand back" would not.
     */
    private fun pair(
        entries: List<Entry>,
        partner: IntArray,
        absorbed: BooleanArray,
    ) {
        // Unpaired OUT positions, indexed by every string an IN could legitimately match them on — a
        // linear scan per IN is quadratic on the both-sides traces this view is *for*, where every
        // message on one pane has a twin on another.
        val pending = HashMap<String, ArrayDeque<Int>>()
        entries.forEachIndexed { position, entry ->
            if (entry.message.direction == FixMessage.Direction.OUTGOING) {
                forEachBytesKey(entry.message) { key -> pending.getOrPut(key) { ArrayDeque() }.addLast(position) }
                return@forEachIndexed
            }
            val match = earliestMatch(pending, partner, entries, entry)
            if (match != UNPAIRED) {
                partner[match] = position
                absorbed[position] = true
            }
        }
    }

    private fun earliestMatch(
        pending: Map<String, ArrayDeque<Int>>,
        partner: IntArray,
        entries: List<Entry>,
        incoming: Entry,
    ): Int {
        var best = UNPAIRED
        forEachBytesKey(incoming.message) { key ->
            val queue = pending[key] ?: return@forEachBytesKey
            // A paired candidate can never match again, so retiring it from the head keeps this
            // amortised rather than walking a lengthening tail of dead entries on every arrival.
            while (queue.isNotEmpty() && partner[queue.first()] != UNPAIRED) queue.removeFirst()
            for (candidate in queue) {
                if (best != UNPAIRED && candidate >= best) break
                if (partner[candidate] != UNPAIRED) continue
                val out = entries[candidate]
                if (out.session == incoming.session) continue
                if (!sameBytes(out.message, incoming.message)) continue
                best = candidate
                break
            }
        }
        return best
    }

    /**
     * **Are these the same bytes?**
     *
     * [FixMessage.wireRaw] is the venue's own bytes in the venue's own field order, and it is the answer
     * whenever both sides have it. [FixMessage.rawMessage] is the fallback and a weaker one — it is a
     * *display* string with SOH replaced by `|`, which is lossy — so it decides only where one side has
     * no wire record to compare. Comparing a wire string against a display string would never match
     * anyway, and calling the pair off on that basis would drop a hop for a reason that has nothing to
     * do with the messages.
     */
    private fun sameBytes(
        a: FixMessage,
        b: FixMessage,
    ): Boolean {
        val aWire = a.wireRaw
        val bWire = b.wireRaw
        return if (aWire != null && bWire != null) aWire == bWire else a.rawMessage == b.rawMessage
    }

    /**
     * Every string this message could be matched on, so that [sameBytes] never has to be asked about a
     * candidate the index could not have offered — one side having no [FixMessage.wireRaw] moves the
     * comparison to [FixMessage.rawMessage], and the index has to hold both keys for that to be reachable.
     */
    private inline fun forEachBytesKey(
        message: FixMessage,
        action: (String) -> Unit,
    ) {
        val wire = message.wireRaw
        if (wire != null) action(wire)
        if (wire != message.rawMessage) action(message.rawMessage)
    }

    private fun gap(
        from: LocalDateTime,
        to: LocalDateTime,
    ): Long = Duration.between(from, to).toMillis()

    private const val UNPAIRED = -1
}
