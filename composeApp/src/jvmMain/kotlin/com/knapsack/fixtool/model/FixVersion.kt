package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * Enum representing supported FIX protocol versions.
 *
 * FIX 4.0-4.4 use the version string as BeginString (e.g., "FIX.4.4").
 * FIX 5.0+ use FIXT.1.1 as the transport layer with ApplVerID to specify the application version.
 */
@Serializable
enum class FixVersion(
    /** The BeginString value for this version (tag 8) */
    val beginString: String,
    /** Human-readable display name */
    val displayName: String,
    /** ApplVerID for FIXT.1.1 sessions (tag 1137), null for FIX 4.x */
    val applVerID: String?,
    /** Whether this version uses FIXT.1.1 transport layer */
    val isFix50Plus: Boolean,
    /** Resource path for the application data dictionary */
    val dictionaryResourcePath: String,
    /** Resource path for the transport data dictionary (FIXT.1.1), null for FIX 4.x */
    val transportDictionaryResourcePath: String? = null,
) {
    FIX_4_0("FIX.4.0", "FIX 4.0", null, false, "/dictionaries/FIX40.xml"),
    FIX_4_1("FIX.4.1", "FIX 4.1", null, false, "/dictionaries/FIX41.xml"),
    FIX_4_2("FIX.4.2", "FIX 4.2", null, false, "/dictionaries/FIX42.xml"),
    FIX_4_3("FIX.4.3", "FIX 4.3", null, false, "/dictionaries/FIX43.xml"),
    FIX_4_4("FIX.4.4", "FIX 4.4", null, false, "/dictionaries/FIX44.xml"),
    FIX_5_0("FIXT.1.1", "FIX 5.0", "7", true, "/dictionaries/FIX50.xml", "/dictionaries/FIXT11.xml"),
    FIX_5_0_SP1("FIXT.1.1", "FIX 5.0 SP1", "8", true, "/dictionaries/FIX50SP1.xml", "/dictionaries/FIXT11.xml"),
    FIX_5_0_SP2("FIXT.1.1", "FIX 5.0 SP2", "9", true, "/dictionaries/FIX50SP2.xml", "/dictionaries/FIXT11.xml"),
    ;

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(FixVersion::class.java)

        /** Default FIX version when none is specified */
        val DEFAULT = FIX_4_4

        /**
         * Finds a FIX version from a BeginString value.
         * For FIX 4.x, the BeginString directly identifies the version.
         * For FIXT.1.1, the ApplVerID is needed to determine the specific FIX 5.0 version.
         *
         * @param beginString The BeginString value (tag 8)
         * @param applVerID The ApplVerID value (tag 1137), required for FIXT.1.1 sessions
         * @return The matching FixVersion, or DEFAULT if not found
         */
        fun fromBeginString(beginString: String, applVerID: String? = null): FixVersion =
            when (beginString) {
                "FIX.4.0" -> FIX_4_0
                "FIX.4.1" -> FIX_4_1
                "FIX.4.2" -> FIX_4_2
                "FIX.4.3" -> FIX_4_3
                "FIX.4.4" -> FIX_4_4
                "FIXT.1.1" -> {
                    // For FIXT.1.1, need ApplVerID to determine the specific version
                    when (applVerID) {
                        "7" -> FIX_5_0
                        "8" -> FIX_5_0_SP1
                        "9" -> FIX_5_0_SP2
                        else -> {
                            logger.warn(
                                "Unknown ApplVerID '{}' for FIXT.1.1, defaulting to FIX 5.0 SP2",
                                applVerID,
                            )
                            FIX_5_0_SP2 // Default to latest FIX 5.0 if ApplVerID is unknown
                        }
                    }
                }
                else -> {
                    logger.warn("Unknown BeginString '{}', defaulting to {}", beginString, DEFAULT.displayName)
                    DEFAULT
                }
            }

        /**
         * Finds a FIX version by its display name.
         * @param displayName The display name (e.g., "FIX 4.4")
         * @return The matching FixVersion, or null if not found
         */
        fun fromDisplayName(displayName: String): FixVersion? =
            entries.find { it.displayName == displayName }

        /**
         * Returns the header tags for a given FIX version.
         * These tags belong in the message header section.
         *
         * @param version The FIX version
         * @return Set of tag numbers that are header fields
         */
        fun getHeaderTags(version: FixVersion): Set<Int> =
            if (version.isFix50Plus) {
                // FIXT.1.1 header tags (includes ApplVerID)
                FIXT11_HEADER_TAGS
            } else {
                // FIX 4.x header tags (varies slightly by version but we use a common set)
                when (version) {
                    FIX_4_0 -> FIX40_HEADER_TAGS
                    FIX_4_1 -> FIX41_HEADER_TAGS
                    FIX_4_2 -> FIX42_HEADER_TAGS
                    FIX_4_3 -> FIX43_HEADER_TAGS
                    FIX_4_4 -> FIX44_HEADER_TAGS
                    else -> FIX44_HEADER_TAGS
                }
            }

        /**
         * Returns the trailer tags for a given FIX version.
         * These tags belong in the message trailer section.
         *
         * @param version The FIX version
         * @return Set of tag numbers that are trailer fields
         */
        fun getTrailerTags(version: FixVersion): Set<Int> =
            if (version.isFix50Plus) {
                FIXT11_TRAILER_TAGS
            } else {
                // FIX 4.x trailer tags are consistent across versions
                FIX4X_TRAILER_TAGS
            }

        // FIX 4.0 header tags
        private val FIX40_HEADER_TAGS =
            setOf(
                8, // BeginString
                9, // BodyLength
                35, // MsgType
                49, // SenderCompID
                56, // TargetCompID
                34, // MsgSeqNum
                43, // PossDupFlag
                52, // SendingTime
                97, // PossResend
                115, // OnBehalfOfCompID
                128, // DeliverToCompID
                90, // SecureDataLen
                91, // SecureData
            )

        // FIX 4.1 header tags (adds more fields)
        private val FIX41_HEADER_TAGS =
            FIX40_HEADER_TAGS +
                setOf(
                    50, // SenderSubID
                    57, // TargetSubID
                    116, // OnBehalfOfSubID
                    129, // DeliverToSubID
                    122, // OrigSendingTime
                )

        // FIX 4.2 header tags
        private val FIX42_HEADER_TAGS =
            FIX41_HEADER_TAGS +
                setOf(
                    142, // SenderLocationID
                    143, // TargetLocationID
                    144, // OnBehalfOfLocationID
                    145, // DeliverToLocationID
                    212, // XmlDataLen
                    213, // XmlData
                    347, // MessageEncoding
                )

        // FIX 4.3 header tags
        private val FIX43_HEADER_TAGS =
            FIX42_HEADER_TAGS +
                setOf(
                    369, // LastMsgSeqNumProcessed
                    627, // NoHops
                    628, // HopCompID
                    629, // HopSendingTime
                    630, // HopRefID
                )

        // FIX 4.4 header tags (same as 4.3)
        private val FIX44_HEADER_TAGS = FIX43_HEADER_TAGS

        // FIXT.1.1 header tags (FIX 5.0+)
        private val FIXT11_HEADER_TAGS =
            FIX44_HEADER_TAGS +
                setOf(
                    1128, // ApplVerID
                    1129, // CstmApplVerID
                    1156, // ApplExtID
                )

        // FIX 4.x trailer tags (consistent across versions)
        private val FIX4X_TRAILER_TAGS =
            setOf(
                10, // CheckSum
                89, // Signature
                93, // SignatureLength
            )

        // FIXT.1.1 trailer tags (same as FIX 4.x)
        private val FIXT11_TRAILER_TAGS = FIX4X_TRAILER_TAGS
    }
}
