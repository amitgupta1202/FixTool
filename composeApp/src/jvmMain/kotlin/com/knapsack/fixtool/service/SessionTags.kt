package com.knapsack.fixtool.service

/**
 * The session/transport layer's tags: the envelope, not the business message — CompIDs, sequence
 * numbers, the FIX version, sending times, the checksum.
 *
 * They belong to the connection, not to the behaviour under test. A scenario is captured on one
 * environment, committed, and replayed on another, so asserting SenderCompID(49) or BeginString(8)
 * as Exact fails every step on a teammate's session for reasons that have nothing to do with what
 * the scenario is checking.
 *
 * One list, three consumers — capture strips them from Send steps, the seeder never asserts them,
 * and STRICT never counts them as unexpected extras. They were three separate lists that disagreed,
 * and the disagreement is exactly what made captured scenarios non-portable.
 */
object SessionTags {
    val TRANSPORT: Set<Int> =
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
}
