package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.viewmodel.TraceFollow
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **What the Ledger is allowed to draw, and in what order.**
 *
 * The panel's own test proves the grid consumes this list; this proves the list is right, because every
 * claim the Ledger makes is decided here: which trace a fold belongs to, what a gap between two rows
 * means, whether a single-session trace gets told why, and whether the messages nothing explained are
 * still on screen at the bottom being counted.
 *
 * The fixture is the venue shape in miniature — `CLIENT` says `RFQ-A1`, `LP-1` says `V-2291`, and the
 * `Q-77` on both quotes is the only edge between them — because that is the case the whole feature
 * exists for and the one where a row builder that got sessions wrong would look plausible.
 */
class TraceRowsTest {
    private val dictionary: FixDictionaryAdapter = FixDictionaryAdapter.createDefault()
    private val epoch: LocalDateTime = LocalDateTime.of(2026, 9, 2, 10, 0, 0)

    /** A message at [millis] past the epoch, so a test can state the gaps it expects. */
    private fun at(
        millis: Long,
        raw: String,
        direction: FixMessage.Direction = FixMessage.Direction.INCOMING,
    ): FixMessage =
        FixMessage(
            timestamp = epoch.plusNanos(millis * 1_000_000L),
            direction = direction,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', FixMessageHelper.SOH),
        )

    private val out = FixMessage.Direction.OUTGOING

    private val clientPane =
        listOf(
            at(0, "35=R|131=RFQ-A1|55=EUR/USD|38=10000000|", out),
            at(5, "35=0|"), // heartbeat — belongs to no exchange
            at(40, "35=S|131=RFQ-A1|117=Q-77|"),
        )

    private val lpPane =
        listOf(
            at(10, "35=R|131=V-2291|55=EUR/USD|38=10000000|"),
            at(30, "35=S|131=V-2291|117=Q-77|", out),
        )

    private val titles = listOf("CLIENT", "LP-1")
    private val snapshots = listOf(clientPane, lpPane)

    private fun build(
        expanded: Set<TraceKey> = emptySet(),
        ungroupedExpanded: Boolean = false,
        followedAnchor: String? = null,
        snapshots: List<List<FixMessage>> = this.snapshots,
        titles: List<String> = this.titles,
    ): List<TraceRows.Row> =
        TraceRows.build(
            snapshots = snapshots,
            sessionTitles = titles,
            grouping = Traces.group(snapshots, dictionary),
            dictionary = dictionary,
            expanded = expanded,
            ungroupedExpanded = ungroupedExpanded,
            followedAnchor = followedAnchor,
        )

    private fun headers(rows: List<TraceRows.Row>) = rows.filterIsInstance<TraceRows.Row.Header>()

    private fun members(rows: List<TraceRows.Row>) = rows.filterIsInstance<TraceRows.Row.Member>()

    private val rfqKey = TraceKey("CLIENT", "RFQ-A1")

    // ------------------------------------------------------------------ order and shape

    @Test
    fun `collapsed by default, so the headers are what the panel opens on`() {
        val rows = build()

        assertEquals(1, headers(rows).size)
        assertEquals(emptyList(), members(rows), "nothing is expanded until someone expands it")
        val header = headers(rows).single()
        assertEquals("RFQ-A1", header.label, "the first id on the earliest message, as Conversations names it")
        assertEquals(rfqKey, header.key)
        assertEquals(2, header.sessionCount)
        assertEquals(listOf(0, 1), header.sessions, "first-seen order")
        assertEquals(4, header.memberCount, "the heartbeat is not part of the exchange")
        assertFalse(header.expanded)
        assertFalse(header.isFollowed)
    }

    /**
     * The bucket goes last and says how big it is. A view that quietly dropped the messages it could
     * not explain would be the silent-coverage-loss defect this codebase refuses everywhere else.
     */
    @Test
    fun `ungrouped is last and counted, whether or not it is open`() {
        val shut = build()
        assertTrue(shut.last() is TraceRows.Row.UngroupedHeader, "last row, always")
        assertEquals(1, (shut.last() as TraceRows.Row.UngroupedHeader).count, "the heartbeat, counted")

        val open = build(ungroupedExpanded = true)
        val bucket = open.indexOfFirst { it is TraceRows.Row.UngroupedHeader }
        assertEquals(open.size - 2, bucket, "still last, with its one member under it")
        assertEquals(1, members(open).size)
        assertNull(members(open).single().traceKey, "an ungrouped row belongs to no trace")
    }

    @Test
    fun `an expanded trace lists its members in merged order across both panes`() {
        val rows = build(expanded = setOf(rfqKey))

        assertTrue(rows.first() is TraceRows.Row.Header, "the header, then its members")
        assertEquals(
            listOf(0 to 0, 1 to 0, 1 to 1, 0 to 2),
            members(rows).map { it.located.session to it.located.index },
            "merged time order across panes, not one pane after the other",
        )
        assertTrue(members(rows).all { it.traceKey == rfqKey }, "every member names the trace it folds under")
    }

    // ------------------------------------------------------------------ elapsed

    /**
     * Elapsed is the gap to the previous message **of this trace**, on whichever pane it landed — one
     * clock timed both ends, so it is the venue's real forwarding time. Never a gap to the previous
     * *row*, which would silently measure across two unrelated exchanges.
     */
    @Test
    fun `elapsed is measured within one trace, and the first member has none`() {
        val rows = build(expanded = setOf(rfqKey), ungroupedExpanded = true)
        val trace = members(rows).filter { it.traceKey == rfqKey }

        assertNull(trace.first().elapsedMillis, "the first message has nothing to be measured from")
        assertEquals(listOf(10L, 20L, 10L), trace.drop(1).map { it.elapsedMillis })
    }

    /** No sequence, no gap: two messages that belong to no exchange are not two points on one line. */
    @Test
    fun `ungrouped members carry no elapsed at all`() {
        val withTwoHeartbeats =
            listOf(clientPane + at(80, "35=0|"), lpPane)
        val rows =
            build(ungroupedExpanded = true, snapshots = withTwoHeartbeats)

        val bucket = members(rows).filter { it.traceKey == null }
        assertEquals(2, bucket.size)
        assertTrue(bucket.all { it.elapsedMillis == null })
    }

    // ------------------------------------------------------------------ the follow set

    @Test
    fun `the followed trace is marked, by any id it carries`() {
        assertTrue(headers(build(followedAnchor = "V-2291")).single().isFollowed, "the venue's own handle finds it")
        assertTrue(headers(build(followedAnchor = "Q-77")).single().isFollowed, "so does the bridging quote id")
        assertFalse(headers(build(followedAnchor = "RFQ-A")).single().isFollowed, "a substring is not an id")
    }

    /**
     * Following opens what it followed — once. The reader may then shut it and go on following, which
     * is why the fold set is state and not a function of what is followed.
     */
    @Test
    fun `following expands the trace it followed, and the reader can still collapse it`() {
        val follow = TraceFollow()
        follow.openTracePanel()
        follow.follow("V-2291")
        follow.refresh(
            listOf(
                TraceFollow.Input("CLIENT", clientPane),
                TraceFollow.Input("LP-1", lpPane),
            ),
            dictionary,
        )

        assertEquals(setOf(rfqKey), follow.expandedTraces.value, "keyed by (opener session, label)")
        assertTrue(headers(build(expanded = follow.expandedTraces.value)).single().expanded)

        follow.toggleTrace(rfqKey)
        follow.refresh(
            listOf(
                TraceFollow.Input("CLIENT", clientPane),
                TraceFollow.Input("LP-1", lpPane),
            ),
            dictionary,
        )
        assertEquals(emptySet(), follow.expandedTraces.value, "a later tick must not re-open what was closed")
    }

    // ------------------------------------------------------------------ the honest limits

    /**
     * The sidecar hint exists to be read at the moment it matters — on a trace that stopped at one pane
     * while other panes were open. With one pane open every trace is single-session by construction and
     * the sentence would be noise on every row.
     */
    @Test
    fun `the sidecar hint appears only where crossing a session was possible`() {
        // Three panes: the exchange that crossed two of them, and one that stopped where it started.
        val threePanes =
            build(
                snapshots = listOf(clientPane, lpPane, listOf(at(60, "35=R|131=UNRELATED|"))),
                titles = listOf("CLIENT", "LP-1", "LP-2"),
            )

        val unrelated = headers(threePanes).first { it.label == "UNRELATED" }
        assertEquals(TraceRows.SIDECAR_HINT, unrelated.hint, "one session, with others open — say why")
        assertNull(
            headers(threePanes).first { it.label == "RFQ-A1" }.hint,
            "a trace that already crossed a session has nothing to be told",
        )

        val onePane = build(snapshots = listOf(clientPane), titles = listOf("CLIENT"))
        assertTrue(headers(onePane).all { it.hint == null }, "with one pane open, every trace is single-session")
    }

    @Test
    fun `a truncated trace names the panes that lost its history, by title`() {
        val grouping = Traces.group(snapshots, dictionary, lostIds = listOf(emptySet(), setOf("V-2291")))
        val rows =
            TraceRows.build(
                snapshots = snapshots,
                sessionTitles = titles,
                grouping = grouping,
                dictionary = dictionary,
            )

        assertEquals(listOf("LP-1"), headers(rows).single().truncatedSessionTitles)
    }

    /**
     * The key names the opener's *session*, not its index. Indices shift the moment a pane is opened or
     * closed, and a fold set keyed on them would silently re-point at a neighbouring trace.
     */
    @Test
    fun `the key survives a session being inserted before it`() {
        val before = headers(build()).single().key
        val shifted =
            build(
                snapshots = listOf(emptyList(), clientPane, lpPane),
                titles = listOf("NEW", "CLIENT", "LP-1"),
            )

        assertEquals(before, headers(shifted).single().key, "same trace, same fold, one pane later")
    }
}
