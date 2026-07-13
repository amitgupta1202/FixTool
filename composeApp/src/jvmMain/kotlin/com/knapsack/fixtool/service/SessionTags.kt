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
     * Never asserted on an **Expect** step, and never counted as an unexpected extra in STRICT: these
     * identify the connection and the moment, not the behaviour under test, so a scenario captured on
     * DEV would go red on QA on every step purely because the CompIDs differ.
     *
     * Deliberately narrower than [REWRITTEN_ON_SEND]. PossDupFlag(43), PossResend(97),
     * OrigSendingTime(122) and the SubID/LocationID routing tags are *not* here: a resend or routing
     * scenario exists precisely to assert them, and silently dropping them would let it pass while
     * checking nothing.
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
        )
}
