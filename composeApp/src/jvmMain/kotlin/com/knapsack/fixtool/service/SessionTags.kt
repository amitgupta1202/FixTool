package com.knapsack.fixtool.service

import quickfix.SessionID

/**
 * The session/transport layer's tags — the envelope rather than the business message.
 *
 * Two different questions, so two different lists. Conflating them cost either correctness or
 * coverage, both ways round: one list too short and captured scenarios asserted the CompIDs of the
 * environment they were captured on (red on everyone else's session); one list too long and tags
 * that carry real meaning — PossDupFlag on a resend, the SubIDs a venue routes on — silently stopped
 * being asserted at all.
 */
object SessionTags {
    /**
     * Written by QuickFIX/J on **every** send, whatever the session: the frame, the sequence number,
     * the moment, and the two CompIDs. A value the author gives any of them is overwritten on the way
     * out, so the editor holds them back and a captured Send step drops them.
     */
    val WRITTEN_ON_EVERY_SEND: Set<Int> =
        setOf(
            8, // BeginString
            9, // BodyLength
            10, // CheckSum
            34, // MsgSeqNum
            49, // SenderCompID
            52, // SendingTime
            56, // TargetCompID
        )

    /**
     * What QuickFIX/J writes on a send through [session]: [WRITTEN_ON_EVERY_SEND], plus each sub and
     * location ID the SessionID itself names.
     *
     * The four IDs were once taken to be written on every send too, and they are not. The engine sets
     * one only when the session's SessionID carries it, and otherwise leaves the message's own value
     * alone (`Session.initializeHeader`). No FixTool profile gives a session a SubID (only a hand-typed
     * custom parameter could), so on every session it builds they are the author's to send, and holding
     * them back meant they were never sent: a venue that routes on SenderSubID could not be tested.
     *
     * Null, a session not yet created, is [WRITTEN_ON_EVERY_SEND].
     */
    fun writtenOnSend(session: SessionID?): Set<Int> {
        if (session == null) return WRITTEN_ON_EVERY_SEND
        val named =
            listOf(
                50 to session.senderSubID, // SenderSubID
                142 to session.senderLocationID, // SenderLocationID
                57 to session.targetSubID, // TargetSubID
                143 to session.targetLocationID, // TargetLocationID
            ).filter { (_, value) -> value != SessionID.NOT_SET }.map { (tag, _) -> tag }
        return WRITTEN_ON_EVERY_SEND + named
    }

    /**
     * Stripped from a captured **Send** step, whose replay is a fresh send on someone else's session.
     *
     * [WRITTEN_ON_EVERY_SEND], which would fight the session (stale sequence numbers, another
     * environment's CompIDs, a checksum for different bytes). The resend markers, whose captured value
     * would make a fresh send look like a replay of an old one. And LastMsgSeqNumProcessed, a sequence
     * number from the session it was captured on.
     *
     * The routing tags are kept: the sub and location IDs, OnBehalfOf and DeliverTo. The engine writes
     * the SubIDs only on a session that names them and the rest never, so a step without them would
     * replay an order with no desk and no client on it.
     */
    val NEVER_REPLAYED: Set<Int> =
        WRITTEN_ON_EVERY_SEND +
            setOf(
                43, // PossDupFlag
                97, // PossResend
                122, // OrigSendingTime
                369, // LastMsgSeqNumProcessed
            )

    /**
     * The transport envelope: the frame, the sequence numbers, the resend markers and the addressing.
     * FixTool answers for each of these itself, so echo detection never proposes one, the venue tag
     * scan never offers one a role, and capture never warns that the dictionary cannot name one.
     *
     * Not the list of what QuickFIX/J writes ([writtenOnSend]) or of what a Send step drops
     * ([NEVER_REPLAYED]). It stood for both once, and the routing tags in it were dropped from every
     * Send step on the strength of a claim that held only for [WRITTEN_ON_EVERY_SEND].
     */
    val TRANSPORT_ENVELOPE: Set<Int> =
        setOf(
            8, // BeginString
            9, // BodyLength
            10, // CheckSum
            34, // MsgSeqNum
            43, // PossDupFlag
            49, // SenderCompID
            50, // SenderSubID
            52, // SendingTime
            56, // TargetCompID
            57, // TargetSubID
            97, // PossResend
            115, // OnBehalfOfCompID
            122, // OrigSendingTime
            128, // DeliverToCompID
            142, // SenderLocationID
            143, // TargetLocationID
            144, // OnBehalfOfLocationID
            145, // DeliverToLocationID
            369, // LastMsgSeqNumProcessed
        )

    /**
     * The envelope: never seeded on an **Expect** step, and never counted as an unexpected extra in
     * STRICT. These are the frame's own bookkeeping and the two CompIDs that name the endpoints — they
     * are present on every message by definition, they differ on every environment, and no assertion
     * on them could ever say anything about the venue's behaviour. A row for BodyLength is noise that
     * cannot fail; a row for SenderCompID is a scenario that only runs where it was captured.
     *
     * Deliberately *not* the same list as [VALUE_NOT_PORTABLE]. "Its value is environment-specific" and
     * "it should not be asserted at all" are different claims, and collapsing them is how the routing
     * tags came to be dropped from the assertion engine entirely — see that field's note.
     */
    val NEVER_ASSERTED: Set<Int> =
        setOf(
            8, // BeginString — the dialect's version, not the behaviour
            9, // BodyLength
            10, // CheckSum
            34, // MsgSeqNum — depends on session history
            49, // SenderCompID — whose environment this is
            52, // SendingTime — the moment, not the content
            56, // TargetCompID
            369, // LastMsgSeqNumProcessed — how much of our history the venue has seen
        )

    /**
     * Seeded as **Presence**: the tag's *presence* is the venue's behaviour, its *value* belongs to
     * this environment or this moment. Asserting that a routed ExecutionReport still carries a
     * TargetSubID is portable and is worth checking; asserting that it carries `DESK7` passes only on
     * the desk it was captured on.
     *
     * The pair of mistakes this sits between, both of which we shipped:
     * - Seeding them **Exact** (what the code did before) made every captured scenario non-portable:
     *   red on QA on every step, for reasons that have nothing to do with the venue.
     * - **Omitting** them (the obvious repair, and the wrong one) silently deleted routing coverage.
     *   Unseeded *and* excluded from STRICT's extras, a venue could start delivering to the wrong desk,
     *   or stop populating DeliverToCompID, and every scenario would still report green. That is a
     *   false green — the one outcome a testing tool must never produce — and the recovery it assumed
     *   ("the author adds the row by hand") does not exist: the builder can only show rows the seeder
     *   produced, so the coverage would have left the scenario with nothing on screen to say so.
     *
     * Presence is the cut that keeps both properties. The row is portable, the row is *there* — visible,
     * with its captured value beside it — so a routing test tightens it to Exact from the dropdown, and
     * because it is listed, STRICT still reports an addressing tag that appears when none was captured.
     */
    val VALUE_NOT_PORTABLE: Set<Int> =
        setOf(
            // The address: which desk, which location, on whose behalf.
            50, // SenderSubID
            57, // TargetSubID
            115, // OnBehalfOfCompID
            128, // DeliverToCompID
            142, // SenderLocationID
            143, // TargetLocationID
            144, // OnBehalfOfLocationID
            145, // DeliverToLocationID
            // The moment the *original* was sent. A resend scenario asserts that a resend carries an
            // OrigSendingTime — never what it says, which is minutes or hours old by definition. Seeded
            // from its UTCTIMESTAMP type it became "~now ±60s" and went red on every single run.
            122, // OrigSendingTime
        )
}
