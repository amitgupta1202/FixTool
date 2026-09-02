package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The picture's model** — which columns exist, in which order, and which two messages are one hop.
 *
 * Two claims carry the file. The first is that lane order is read from the **profiles**: initiators
 * left, acceptors right, and nothing about the traffic changes that, because a venue that happens to
 * speak first is still the side that was dialled. The second is the pairing rule, which is the only
 * place Lanes says something the Ledger does not — and it says exactly one thing, that two byte strings
 * are identical. Every refusal below is that same sentence read back: a pair is about bytes, and about
 * nothing else.
 */
class TraceLanesTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val out = FixMessage.Direction.OUTGOING

    private val epoch = LocalDateTime.of(2026, 9, 2, 10, 0, 0)

    /** A message at [millis] past the epoch, so a test states the merged order and the gaps it expects. */
    private fun at(
        millis: Long,
        raw: String,
        dir: FixMessage.Direction = FixMessage.Direction.INCOMING,
        wire: String? = raw.replace('|', FixMessageHelper.SOH),
    ): FixMessage =
        FixMessage(
            timestamp = epoch.plusNanos(millis * 1_000_000L),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = wire,
        )

    private fun lanesOf(
        snapshots: List<List<FixMessage>>,
        titles: List<String>,
        roles: List<LaneRole>,
    ): TraceLanes.Lanes {
        val grouping = Traces.group(snapshots, dictionary)
        return TraceLanes.build(grouping.traces.first(), snapshots, titles, roles)
    }

    // ---------------------------------------------------------------- lane order

    /**
     * The side that dials out on the left, the side that is dialled on the right, and within each the
     * order the exchange reached them in. The trace here arrives at the acceptors *first*, which is the
     * whole point: order comes from the profiles, not from who spoke.
     */
    @Test
    fun `lanes are initiators, then acceptors, then unknown, stable by first appearance`() {
        val snapshots =
            listOf(
                // 0: an acceptor the trace touches first
                listOf(at(0, "35=R|131=RFQ-A1|")),
                // 1: an unconfigured pane
                listOf(at(5, "35=R|131=RFQ-A1|")),
                // 2: the second acceptor
                listOf(at(10, "35=R|131=RFQ-A1|")),
                // 3: the initiator, and the last to see it
                listOf(at(20, "35=R|131=RFQ-A1|", out)),
            )
        val lanes =
            lanesOf(
                snapshots,
                listOf("LP-1", "MYSTERY", "LP-2", "CLIENT"),
                listOf(LaneRole.ACCEPTOR, LaneRole.UNKNOWN, LaneRole.ACCEPTOR, LaneRole.INITIATOR),
            )

        assertEquals(
            listOf("CLIENT", "LP-1", "LP-2", "MYSTERY"),
            lanes.lanes.map { it.title },
            "initiators, then acceptors in first-appearance order, then unknown",
        )
        assertEquals(
            listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR, LaneRole.ACCEPTOR, LaneRole.UNKNOWN),
            lanes.lanes.map { it.role },
        )
        assertEquals(1, lanes.acceptorDividerAt, "the dashed rule goes before the first acceptor column")
        assertEquals(0, lanes.laneOf(3), "the client's messages go in the first column")
        assertEquals(-1, lanes.laneOf(9), "a session this trace never touched has no column")
    }

    /** With one side missing there is nothing to divide, and a rule dividing nothing is a claim. */
    @Test
    fun `no divider when the trace touched only one side`() {
        val snapshots = listOf(listOf(at(0, "35=R|131=RFQ-A1|")), listOf(at(5, "35=S|131=RFQ-A1|117=Q-77|")))
        val acceptorsOnly =
            lanesOf(snapshots, listOf("LP-1", "LP-2"), listOf(LaneRole.ACCEPTOR, LaneRole.ACCEPTOR))
        val unknownOnly =
            lanesOf(snapshots, listOf("A", "B"), listOf(LaneRole.UNKNOWN, LaneRole.UNKNOWN))

        assertNull(acceptorsOnly.acceptorDividerAt, "two acceptors is not two sides")
        assertNull(unknownOnly.acceptorDividerAt)
    }

    /** A session with no role recorded is unconfigured, not an initiator by default. */
    @Test
    fun `a session with no role recorded is UNKNOWN rather than guessed`() {
        val snapshots = listOf(listOf(at(0, "35=R|131=RFQ-A1|", out)))

        val lanes = lanesOf(snapshots, listOf("CLIENT"), roles = emptyList())

        assertEquals(listOf(LaneRole.UNKNOWN), lanes.lanes.map { it.role })
    }

    /** The degenerate shape the panel opens on constantly: one pane, one exchange, one column. */
    @Test
    fun `a trace over one session gives one lane and no pairs`() {
        val snapshots =
            listOf(
                listOf(
                    at(0, "35=R|131=RFQ-A1|", out),
                    at(30, "35=S|131=RFQ-A1|117=Q-77|"),
                ),
            )

        val lanes = lanesOf(snapshots, listOf("CLIENT"), listOf(LaneRole.INITIATOR))

        assertEquals(1, lanes.lanes.size)
        assertEquals(listOf(0), lanes.lanes.map { it.session })
        assertEquals(2, lanes.rows.size, "one message, one row")
        assertTrue(lanes.rows.none { it.paired })
        assertNull(lanes.acceptorDividerAt)
    }

    // ---------------------------------------------------------------- pairing

    /**
     * **The both-sides picture**: FixTool plays the client and the venue at once, so the request it sent
     * is the request it received. One hop, one row, one arrow — and the elapsed on it is the gap between
     * the two ends, which is the number the picture exists to show.
     */
    @Test
    fun `identical bytes out of one session and into another become one row`() {
        val request = "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|"
        val snapshots =
            listOf(
                listOf(at(0, request, out)),
                listOf(at(31, request)),
            )

        val lanes = lanesOf(snapshots, listOf("CLIENT", "VENUE"), listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR))

        val row = lanes.rows.single()
        assertTrue(row.paired, "two panes logged the same bytes; that is one hop")
        assertEquals(0, row.from.session, "from is the OUT side")
        assertEquals(FixMessage.Direction.OUTGOING, row.from.message.direction)
        assertEquals(1, assertNotNull(row.to).session)
        assertEquals(FixMessage.Direction.INCOMING, assertNotNull(row.to).message.direction)
        assertEquals(31L, row.hopMillis, "the gap between the two ends of the hop it draws")
        assertNull(row.elapsedMillis, "the first row has no previous")
    }

    /** A pane that logs its own echo has one message twice, not a lane crossing. */
    @Test
    fun `two messages on the same session are never paired`() {
        val request = "35=R|131=RFQ-A1|"
        val snapshots = listOf(listOf(at(0, request, out), at(31, request)))

        val lanes = lanesOf(snapshots, listOf("CLIENT"), listOf(LaneRole.INITIATOR))

        assertEquals(2, lanes.rows.size, "same pane, so two rows")
        assertTrue(lanes.rows.none { it.paired })
    }

    /** A hop has one end leaving and one arriving; two arrivals is a venue fanning out to two panes. */
    @Test
    fun `two INs of the same bytes are two rows, and so are two OUTs`() {
        val request = "35=R|131=RFQ-A1|"
        val twoIns = listOf(listOf(at(0, request)), listOf(at(31, request)))
        val twoOuts = listOf(listOf(at(0, request, out)), listOf(at(31, request, out)))

        val roles = listOf(LaneRole.ACCEPTOR, LaneRole.ACCEPTOR)
        assertTrue(lanesOf(twoIns, listOf("LP-1", "LP-2"), roles).rows.none { it.paired })
        assertTrue(lanesOf(twoOuts, listOf("LP-1", "LP-2"), roles).rows.none { it.paired })
    }

    /** One message arrives once: a venue broadcasting to two LPs leaves two arrows, not one drawn twice. */
    @Test
    fun `an OUT is claimed by the first IN and no other`() {
        val request = "35=R|131=RFQ-A1|"
        val snapshots =
            listOf(
                listOf(at(0, request, out), at(1, request, out)),
                listOf(at(20, request)),
                listOf(at(25, request)),
            )

        val lanes =
            lanesOf(
                snapshots,
                listOf("VENUE", "LP-1", "LP-2"),
                listOf(LaneRole.ACCEPTOR, LaneRole.ACCEPTOR, LaneRole.ACCEPTOR),
            )

        assertEquals(2, lanes.rows.size, "two OUTs and two INs is two hops")
        assertTrue(lanes.rows.all { it.paired })
        assertEquals(
            listOf(0 to 1, 1 to 2),
            lanes.rows.map { it.from.index to assertNotNull(it.to).session },
            "each IN takes the earliest OUT still unspoken for",
        )
        assertEquals(listOf(20L, 24L), lanes.rows.map { it.hopMillis }, "each arrow carries its own hop")
        assertEquals(
            listOf(null, 1L),
            lanes.rows.map { it.elapsedMillis },
            "the gutter is the gap since the previous row STARTED: the second copy left 1 ms after the first, " +
                "and 'since the previous row ended' would print -19 here",
        )
    }

    /**
     * Differing bytes is the end of the question; nothing else is consulted.
     *
     * These two are one trace — they share `RFQ-A1`, so the Ledger lists them together — and they are
     * still two rows here, because a venue that rewrote one tag on the way through did not send the
     * same message and the picture must not say it did.
     */
    @Test
    fun `differing bytes are never paired`() {
        val snapshots =
            listOf(
                listOf(at(0, "35=R|131=RFQ-A1|55=EUR/USD|", out)),
                listOf(at(31, "35=R|131=RFQ-A1|55=GBP/USD|")),
            )

        val lanes = lanesOf(snapshots, listOf("CLIENT", "VENUE"), listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR))

        assertEquals(2, lanes.rows.size)
        assertTrue(lanes.rows.none { it.paired })
    }

    /**
     * **`wireRaw` decides whenever both sides have it**, and a matching display string does not overrule
     * it. `rawMessage` replaces SOH with `|`, which is lossy — two messages whose wire bytes differ can
     * flatten to the same display string, and pairing on that would draw a hop out of the substitution
     * rather than out of the traffic.
     */
    @Test
    fun `a matching display string does not pair messages whose wire bytes differ`() {
        val soh = FixMessageHelper.SOH
        // The lossy substitution made visible: a `|` inside Text(58) and a real field separator
        // flatten to the same display string, and these two are not remotely the same bytes.
        val outWire = "35=R${soh}131=RFQ-A1${soh}58=x|y$soh"
        val inWire = "35=R${soh}131=RFQ-A1${soh}58=x${soh}y$soh"
        val display = "35=R|131=RFQ-A1|58=x|y|"
        val snapshots =
            listOf(
                listOf(at(0, display, out, wire = outWire)),
                listOf(at(31, display, wire = inWire)),
            )

        val lanes = lanesOf(snapshots, listOf("CLIENT", "VENUE"), listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR))

        assertEquals(2, lanes.rows.size, "the wire bytes are the answer when both sides have them")
        assertTrue(lanes.rows.none { it.paired })
    }

    /** With no wire record on one side there is nothing better to compare, so the display string decides. */
    @Test
    fun `the display string decides only where one side has no wire record`() {
        val display = "35=R|131=RFQ-A1|"
        val snapshots =
            listOf(
                listOf(at(0, display, out)),
                listOf(at(31, display, wire = null)),
            )

        val lanes = lanesOf(snapshots, listOf("CLIENT", "VENUE"), listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR))

        assertTrue(lanes.rows.single().paired)
    }

    // ---------------------------------------------------------------- elapsed

    /**
     * An unpaired row measures from the previous row's **last** timestamp — which for a paired row is
     * the far end of its arrow, not the near one. Measuring from the OUT would count the hop twice.
     */
    @Test
    fun `elapsed runs from the previous row's last timestamp, and a hop measures itself`() {
        val request = "35=R|131=RFQ-A1|55=EUR/USD|"
        val snapshots =
            listOf(
                listOf(
                    at(0, request, out),
                    at(200, "35=D|11=ORD-9|117=Q-77|", out),
                ),
                listOf(
                    at(31, request),
                    at(90, "35=S|131=RFQ-A1|117=Q-77|", out),
                ),
            )

        val lanes = lanesOf(snapshots, listOf("CLIENT", "VENUE"), listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR))

        assertEquals(3, lanes.rows.size, "four messages, one of them a hop drawn once")
        assertEquals(
            listOf(true, false, false),
            lanes.rows.map { it.paired },
        )
        assertEquals(listOf(31L, null, null), lanes.rows.map { it.hopMillis }, "only the hop has a hop time")
        assertEquals(
            listOf(null, 90L, 110L),
            lanes.rows.map { it.elapsedMillis },
            "since the previous row started: the quote left 90 after the request left; the order 110 after the quote",
        )
    }

    /** The first row has no previous, so it states nothing rather than a gap from zero. */
    @Test
    fun `the first unpaired row has no elapsed`() {
        val snapshots = listOf(listOf(at(0, "35=R|131=RFQ-A1|", out), at(40, "35=S|131=RFQ-A1|117=Q-77|")))

        val lanes = lanesOf(snapshots, listOf("CLIENT"), listOf(LaneRole.INITIATOR))

        assertNull(lanes.rows.first().elapsedMillis)
        assertEquals(40L, lanes.rows[1].elapsedMillis)
    }

    /**
     * **Lanes and the Ledger draw the same messages.** Folding a hop into one row is a rendering choice,
     * and a rendering that quietly dropped a message would be the silent-coverage-loss defect this
     * codebase refuses everywhere — so every member of the trace appears exactly once, as a `from` or
     * as a `to`.
     */
    @Test
    fun `every message of the trace is drawn exactly once`() {
        val request = "35=R|131=RFQ-A1|55=EUR/USD|"
        val snapshots =
            listOf(
                listOf(at(0, request, out), at(120, "35=S|131=RFQ-A1|117=Q-77|")),
                listOf(at(31, request), at(90, "35=S|131=RFQ-A1|117=Q-77|", out)),
            )
        val grouping = Traces.group(snapshots, dictionary)
        val trace = grouping.traces.single()

        val lanes = TraceLanes.build(trace, snapshots, listOf("CLIENT", "VENUE"), listOf(LaneRole.INITIATOR, LaneRole.ACCEPTOR))

        val drawn = lanes.rows.flatMap { listOfNotNull(it.from, it.to) }.map { it.located }
        assertEquals(trace.members.size, drawn.size, "nothing dropped and nothing drawn twice")
        assertEquals(trace.members.toSet(), drawn.toSet(), "the same messages the Ledger lists")
        assertEquals(2, lanes.rows.size, "two hops, because both messages crossed")
    }
}
