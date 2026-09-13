package com.knapsack.fixtool.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * **The enums a profile carries on disk, pinned value for value.**
 *
 * Adding a value to one of these is not a local change. An older FixTool reading a profile that uses the new
 * value fails to decode it, `ConnectionProfileService.loadProfiles()` returns an empty list, and that build's
 * next save writes a single profile over the whole file. So a failure here is not "update the list": it is
 * "carry the new word as a string instead", the way `StepAddress`, `SenderRole` and `RfqConstraint` are.
 * See `docs/rfq-relay-impl-plan.md`, fact 2.
 */
class PersistedEnumsTest {
    @Test
    fun `no persisted enum has grown a value`() {
        assertEquals(listOf("unknown", "pending", "working", "done"), OrderConstraint.entries.map { it.word })
        assertEquals(listOf("unknown", "open", "expired", "done"), QuoteConstraint.entries.map { it.word })
        assertEquals(listOf("INITIATOR", "ACCEPTOR"), FixConnectionConfig.ConnectionType.entries.map { it.name })
        assertEquals(listOf("FILE", "MEMORY"), FixConnectionConfig.MessageStoreKind.entries.map { it.name })
        assertEquals(listOf("FILE", "NONE"), FixConnectionConfig.MessageLogKind.entries.map { it.name })
        assertEquals(listOf("NONE", "FIXED", "RANDOM_RANGE", "NORMAL"), AcceptorLatencyConfig.Mode.entries.map { it.name })
    }
}
