package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * Application-wide settings
 */
@Serializable
data class AppSettings(
    // Default data dictionary path
    val defaultDataDictionary: String = "",
    // QuickFIX/J Validation Settings
    val validateFieldsOutOfOrder: Boolean = false,
    val validateFieldsHaveValues: Boolean = false,
    val validateUserDefinedFields: Boolean = false,
    val validateIncomingMessage: Boolean = false,
    // Grid View Settings
    val gridViewColumns: List<Int> = listOf(11, 131, 693), // List of FIX tags to display as columns (ClOrdID, QuoteReqID, QuoteRequestRejectReason)
    // Protocol Tags Settings
    val hideProtocolTagsByDefault: Boolean = true, // Hide protocol tags by default in message details
    val protocolTags: Set<Int> = defaultProtocolTags, // List of FIX tags considered as protocol tags
    // Future settings can be added here
    // val theme: String = "dark",
    // val fontSize: Int = 12,
    // etc.
) {
    companion object {
        /**
         * Default protocol tags that are typically managed by the FIX engine
         */
        val defaultProtocolTags: Set<Int> =
            setOf(
                8, // BeginString
                9, // BodyLength
                10, // CheckSum
                34, // MsgSeqNum
                49, // SenderCompID
                50, // SenderSubID
                52, // SendingTime
                56, // TargetCompID
                57, // TargetSubID
                142, // SenderLocationID
                143, // TargetLocationID
                369, // LastMsgSeqNumProcessed
                98, // EncryptMethod
                108, // HeartBtInt
                141, // ResetSeqNumFlag
            )

        /**
         * Returns default settings
         */
        fun default(): AppSettings = AppSettings()
    }
}
