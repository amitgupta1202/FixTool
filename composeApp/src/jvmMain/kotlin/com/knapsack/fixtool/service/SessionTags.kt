package com.knapsack.fixtool.service

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
     * Stripped from a **Send** step: the FIX engine supplies them on the way out, so a captured raw
     * that carried them would fight the session (stale sequence numbers, another environment's
     * CompIDs, a checksum for different bytes).
     */
    val REWRITTEN_ON_SEND: Set<Int> =
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
