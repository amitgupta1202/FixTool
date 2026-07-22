package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The grid's render list when grouping is on: headers, their messages, and the residue last. */
class ConversationRowsTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private var clock = 0

    private fun msg(raw: String): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 7, 22, 10, 0, 0).plusNanos(clock++ * 1_000_000L),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', FixMessageHelper.SOH),
        )

    /** Index 0 opens A, 1 is a heartbeat, 2 opens B, 3 answers A. Deliberately interleaved. */
    private fun log(): List<AppMessage> =
        listOf(
            msg("35=R|131=RFQ-A1|"),
            msg("35=0|"),
            msg("35=R|131=RFQ-B2|"),
            msg("35=S|117=Q-77|131=RFQ-A1|"),
        )

    private fun rows(collapsed: Set<String> = emptySet()) = ConversationRows.build(log(), dictionary, collapsed)

    /**
     * **The load-bearing property.** Search matches, the selection and the assertion overlay are all
     * keyed on a message's position in the ungrouped list. A row that renumbered itself to its grouped
     * position would point every highlight at the wrong message — and only while a search is open.
     */
    @Test
    fun `a message row keeps its index in the original list, not its grouped position`() {
        val messageRows = rows().filterIsInstance<ConversationRows.Row.Message>()
        assertEquals(listOf(0, 3, 2, 1), messageRows.map { it.index }, "grouped order, original indices")
    }

    @Test
    fun `each conversation gets a header, in the order they opened`() {
        val headers = rows().filterIsInstance<ConversationRows.Row.Header>()
        assertEquals(listOf("RFQ-A1", "RFQ-B2", "Ungrouped"), headers.map { it.label })
        assertEquals(listOf(2, 1, 1), headers.map { it.count })
    }

    /** The residue goes last: burying what the grouping could not explain is the omission to avoid. */
    @Test
    fun `the ungrouped bucket comes last and carries no summary`() {
        val all = rows()
        val ungrouped = all.filterIsInstance<ConversationRows.Row.Header>().last()
        assertEquals(ConversationRows.UNGROUPED_KEY, ungrouped.key)
        assertEquals(null, ungrouped.summary, "a residue is not an exchange and has nothing to summarise")
        assertTrue(all.indexOf(ungrouped) < all.size - 1, "its one message follows it")
    }

    /** A conversation's header always shows; collapsing removes only its messages. */
    @Test
    fun `a collapsed conversation contributes its header and nothing else`() {
        val collapsed = rows(setOf("RFQ-A1"))
        assertEquals(3, collapsed.filterIsInstance<ConversationRows.Row.Header>().size, "every header still shows")
        assertEquals(
            listOf(2, 1),
            collapsed.filterIsInstance<ConversationRows.Row.Message>().map { it.index },
            "RFQ-A1's two messages are gone; B2's and the heartbeat remain",
        )
        assertTrue(collapsed.filterIsInstance<ConversationRows.Row.Header>().first().collapsed)
    }

    /** Every message is reachable when nothing is collapsed — the grid must not lose rows to grouping. */
    @Test
    fun `expanded rows cover every message exactly once`() {
        val indices = rows().filterIsInstance<ConversationRows.Row.Message>().map { it.index }
        assertEquals(log().indices.toList(), indices.sorted())
    }
}
