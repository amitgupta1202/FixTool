package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * Application-wide settings
 */
@Serializable
data class AppSettings(
    // Default data dictionary path (when useBundledDictionary is false)
    val defaultDataDictionary: String = "",
    // Transport dictionary path for FIX 5.0+ (FIXT11.xml) - only used when useBundledDictionary is false
    val defaultTransportDictionary: String = "",
    // Default FIX version (when useBundledDictionary is true)
    val defaultFixVersion: FixVersion = FixVersion.FIX_4_4,
    // Whether to use bundled dictionary based on defaultFixVersion
    // Default is false to preserve backward compatibility for users upgrading from v1.1.0
    // who have custom dictionaries configured (settings migration doesn't include this field)
    val useBundledDictionary: Boolean = false,
    // QuickFIX/J Validation Settings
    val validateFieldsOutOfOrder: Boolean = false,
    val validateFieldsHaveValues: Boolean = false,
    val validateUserDefinedFields: Boolean = false,
    val validateIncomingMessage: Boolean = false,
    // Session Settings
    val sessionBufferSize: Int = 1000, // Maximum number of messages to retain per session
    // Grid View Settings
    val gridViewColumns: List<Int> = listOf(11, 131, 693), // List of FIX tags to display as columns (ClOrdID, QuoteReqID, QuoteRequestRejectReason)
    // Protocol Tags Settings
    val hideProtocolTags: Boolean = true, // Hide protocol tags in message details (global setting)
    val protocolTags: Set<Int> = defaultProtocolTags, // List of FIX tags considered as protocol tags
    // Message Color Scheme
    val messageColorScheme: MessageColorScheme = MessageColorScheme.default(),
    // Rejection Rules
    val rejectionRules: List<RejectionRule> = RejectionRule.defaultRules(),
    // Default View Mode
    val defaultViewMode: String = "grid", // Default view mode for new sessions: "grid" or "terminal"
    // Default Layout
    val defaultLayout: String = "horizontal", // Default layout: "tabs", "horizontal", or "vertical"
    // Configurable Data Directories
    val connectionProfilesPath: String = "", // Path to connection profiles JSON file (empty = default: ~/.fixtool/connection_profiles.json)
    val savedMessagesPath: String = "", // Path to saved messages JSON file (empty = default: ~/.fixtool/saved_messages.json)
    // Latency Tracking Settings
    val enableLatencyTracking: Boolean = false, // Enable latency tracking feature
    val captureNetworkInterface: String = "", // Network interface for packet capture (empty = auto-detect)
    // Tags to use for correlation (ClOrdID, QuoteReqID, QuoteID, MDReqID, OrderID, ExecID)
    val latencyCorrelationTags: List<Int> = listOf(11, 131, 117, 262, 37, 17),
    val latencyHistorySize: Int = 10000, // Maximum number of latency samples to retain
    val latencyWarningThresholdMicros: Long = 100_000L, // 100ms - threshold for warning color
    val latencyCriticalThresholdMicros: Long = 500_000L, // 500ms - threshold for critical/red color
    val showLatencyColumn: Boolean = true, // Show latency column in grid view when tracking is enabled
    // Session-Editor Sync Settings
    val autoSyncSessionToEditor: Boolean = true, // Auto-sync selected session tab to message editor connection dropdown
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
                // 34, // MsgSeqNum - removed from hidden list so it's visible in grid
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
