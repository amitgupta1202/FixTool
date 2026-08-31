package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.perf.Corpus
import com.knapsack.fixtool.service.ConversationRows
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The grouped view over a live session, end to end: ingest, drain, group, draw.**
 *
 * The unit tests around `Conversations` and `ConversationRows` hand them a list. Nothing exercised the
 * path the app actually runs — messages arriving through `FixMessageSession.addMessage`, surviving the
 * queue and the drain, being published as a snapshot, and only then being grouped into rows — and that
 * is the path where the caching added for the grouping benchmark could go wrong in ways a list-based
 * test cannot see.
 *
 * Specifically: the field cache lives on the message object, and the session hands the *same* message
 * objects to every reader through an immutable snapshot. A cache that were keyed on identity in the
 * wrong place, or invalidated by the ring buffer's eviction, or computed against a message whose
 * `wireRaw` had not been set yet, would pass every unit test here and produce a grid that grouped the
 * first hundred messages correctly and the rest not at all.
 *
 * These assert the outcome — which conversations exist, which rows point where — rather than any of the
 * mechanism, so the caching is free to change again.
 */
class GroupedViewIngestIntegrationTest {
    private val dictionary = FixDictionaryAdapter.createDefault()

    private fun sessionHolding(messages: List<FixMessage>, bufferSize: Int = 5_000): FixMessageSession {
        val session = FixMessageSession(title = "grouped", bufferSize = bufferSize)
        messages.forEach { session.addMessage(it) }
        awaitRetained(session, minOf(messages.size, bufferSize))
        return session
    }

    /**
     * Waits until the session has published [expected] messages.
     *
     * `flushMessageQueue` alone is not enough, and the reason is worth writing down: it races with the
     * session's own drain coroutine. The poller can take a batch off the queue and be descheduled before
     * it folds that batch into the retained window — at which point a synchronous flush drains only what
     * is *left*, folds and publishes that, and a test reading straight afterwards sees a handful of
     * messages instead of all of them. The poller then lands its batch and the count corrects itself a
     * moment later.
     *
     * That is a pre-existing hazard in the test helper rather than anything about ingest — nothing is
     * lost, it is only briefly under-reported — but it makes any assertion that reads immediately after
     * a flush intermittently wrong. Waiting for the count is the honest way to ask.
     */
    private fun awaitRetained(session: FixMessageSession, expected: Int) {
        session.flushMessageQueue()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (session.messages.value.size >= expected) return
            Thread.sleep(5)
            session.flushMessageQueue()
        }
        throw AssertionError(
            "waited 5s for $expected messages; the session published ${session.messages.value.size}",
        )
    }

    /**
     * The whole point of the view: 50 messages of interleaved RFQ traffic come out as 10 exchanges, each
     * with its five messages under one header, and every render row still addresses the message it means.
     */
    @Test
    fun `a session's traffic groups into the exchanges it contains`() {
        val traffic = Corpus.rfqFlow(50)
        val session = sessionHolding(traffic)
        try {
            val published = session.messages.value
            assertEquals(50, published.size, "every message must survive ingest before anything groups it")

            val rows = ConversationRows.build(published, dictionary, collapsed = emptySet())
            val headers = rows.filterIsInstance<ConversationRows.Row.Header>()
            val messageRows = rows.filterIsInstance<ConversationRows.Row.Message>()

            assertEquals(10, headers.size, "50 messages of a 5-message RFQ flow is 10 exchanges")
            assertEquals(50, messageRows.size, "every message must be drawn under exactly one header")
            headers.forEach { assertEquals(5, it.count, "each exchange holds its five messages") }

            // The load-bearing property: a row's index addresses the ORIGINAL published list, so the
            // grid's selection, search highlighting and assertion tints land on the right message.
            messageRows.forEach { row ->
                assertTrue(row.index in published.indices, "row ${row.index} must address a real message")
            }
            assertEquals(
                messageRows.map { it.index }.toSet().size,
                messageRows.size,
                "no message may be drawn twice",
            )
        } finally {
            session.destroy()
        }
    }

    /**
     * **Nothing is hidden.** A message carrying no correlation id is not dropped from the grouped view —
     * it lands in the ungrouped bucket, which is counted and shown. A view that quietly omitted the
     * traffic it did not understand would be the silent-coverage-loss defect this codebase refuses.
     */
    @Test
    fun `heartbeats and separators land in the ungrouped bucket rather than vanishing`() {
        val traffic = Corpus.rfqFlow(10)
        val session = FixMessageSession(title = "mixed", bufferSize = 5_000)
        traffic.forEach { session.addMessage(it) }
        session.addSeparator()
        awaitRetained(session, traffic.size + 1)

        try {
            val published = session.messages.value
            val rows = ConversationRows.build(published, dictionary, collapsed = emptySet())
            val drawn = rows.filterIsInstance<ConversationRows.Row.Message>().map { it.index }.toSet()

            assertEquals(
                published.indices.toSet(),
                drawn,
                "every published entry — the separator included — must be drawn somewhere",
            )
            val ungrouped = rows.filterIsInstance<ConversationRows.Row.Header>()
                .singleOrNull { it.key == ConversationRows.UNGROUPED_KEY }
            assertTrue(ungrouped != null, "the residue must get a header of its own, not be silently appended")
            assertEquals(1, ungrouped.count, "the separator is the only entry with no exchange to belong to")
        } finally {
            session.destroy()
        }
    }

    /**
     * The ring buffer evicts as it rolls, and the grouped view must follow it rather than describe a log
     * that is no longer there. Pinned because the field cache is per message and eviction drops messages:
     * a grouping that held its own references would keep drawing rows for messages the session had
     * already let go.
     */
    @Test
    fun `grouping follows the retained window as it rolls`() {
        val session = FixMessageSession(title = "rolling", bufferSize = 25)
        Corpus.rfqFlow(100).forEach { session.addMessage(it) }
        awaitRetained(session, 25)

        try {
            val published = session.messages.value
            assertEquals(25, published.size, "the window keeps the newest 25")

            val rows = ConversationRows.build(published, dictionary, collapsed = emptySet())
            val messageRows = rows.filterIsInstance<ConversationRows.Row.Message>()
            assertEquals(25, messageRows.size, "exactly the retained messages, no more and no fewer")
            messageRows.forEach { assertTrue(it.index in published.indices) }
        } finally {
            session.destroy()
        }
    }

    /**
     * A collapsed exchange hides its rows and keeps its header — and, critically, keeps its *count*. The
     * header is the only thing left saying how much is folded away, so a collapse that also zeroed the
     * count would hide the traffic twice.
     */
    @Test
    fun `collapsing an exchange folds its rows but not its count`() {
        val session = sessionHolding(Corpus.rfqFlow(15))
        try {
            val published = session.messages.value
            val open = ConversationRows.build(published, dictionary, collapsed = emptySet())
            val label = open.filterIsInstance<ConversationRows.Row.Header>().first().label

            val folded = ConversationRows.build(published, dictionary, collapsed = setOf(label))
            val foldedHeader = folded.filterIsInstance<ConversationRows.Row.Header>().first { it.label == label }

            assertTrue(foldedHeader.collapsed, "the header must know it is folded")
            assertEquals(5, foldedHeader.count, "and must still say how many messages it is hiding")
            assertEquals(
                open.filterIsInstance<ConversationRows.Row.Message>().size - 5,
                folded.filterIsInstance<ConversationRows.Row.Message>().size,
                "exactly that exchange's rows come out of the list",
            )
        } finally {
            session.destroy()
        }
    }
}
