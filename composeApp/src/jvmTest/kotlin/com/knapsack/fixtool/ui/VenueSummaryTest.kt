package com.knapsack.fixtool.ui

import com.knapsack.fixtool.service.AcceptorStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A healthy venue says almost nothing, and that is the requirement.**
 *
 * [VenueSummary] is the contract between a venue's pane and its minimized chip, and the half of it
 * worth testing is the deviation rule: identity and live state always, everything else only when it
 * has stopped being boring. Get that wrong in the lenient direction and the strip carries five badges
 * on every venue in the world, which is the furniture this change exists to remove; get it wrong in
 * the strict direction and a refused logon goes unreported, which is the one fact the app holds
 * nowhere else.
 *
 * Pure, because the rule is pure. No Compose, no engine, no venue.
 */
class VenueSummaryTest {
    private fun status(
        rules: Int = 21,
        latency: Boolean = false,
        triggered: Long = 41,
        sent: Long = 41,
        pending: Int = 0,
    ) = AcceptorStatus(
        rulesLive = rules,
        latencyActive = latency,
        triggersMatched = triggered,
        responsesSent = sent,
        pendingResponses = pending,
    )

    private fun venue(
        listening: Boolean = true,
        up: Int = 2,
        gone: Int = 0,
        refused: Int = 0,
        status: AcceptorStatus? = status(),
    ) = VenueSummary.of(
        senderCompID = "DEMO_SERVER",
        port = "19876",
        listening = listening,
        clientsConnected = up,
        clientsGone = gone,
        refused = refused,
        status = status,
    )

    @Test
    fun `a venue running normally has nothing to badge`() {
        val summary = venue()
        assertTrue(summary.quiet, "a healthy venue must reduce to identity and a client count")
        assertFalse(summary.showGone)
        assertFalse(summary.showRefused)
        assertFalse(summary.showPending)
        assertFalse(summary.showLatency)
        assertFalse(summary.noRules)
        assertFalse(summary.sendsDiverge)
    }

    @Test
    fun `twenty-one rules loaded is furniture but zero rules is a warning`() {
        assertFalse(venue(status = status(rules = 21)).noRules)
        assertTrue(venue(status = status(rules = 0, triggered = 0, sent = 0)).noRules)
        // The inversion is the point: the chip carries no permanent rule count, only the absence.
        assertTrue(venue(status = status(rules = 21)).quiet)
        assertFalse(venue(status = status(rules = 0, triggered = 0, sent = 0)).quiet)
    }

    @Test
    fun `triggered equals sent is boring and a shortfall is not`() {
        assertFalse(venue(status = status(triggered = 41, sent = 41)).sendsDiverge)
        assertTrue(venue(status = status(triggered = 41, sent = 38)).sendsDiverge)
        assertEquals("41/38 sent", venue(status = status(triggered = 41, sent = 38)).divergenceLabel())
    }

    @Test
    fun `a client leaving, a refusal, a pending reply and injected latency each break the quiet`() {
        assertTrue(venue(gone = 1).showGone)
        assertFalse(venue(gone = 1).quiet)
        assertTrue(venue(refused = 1).showRefused)
        assertFalse(venue(refused = 1).quiet)
        assertTrue(venue(status = status(pending = 3)).showPending)
        assertFalse(venue(status = status(pending = 3)).quiet)
        assertTrue(venue(status = status(latency = true)).showLatency)
        assertFalse(venue(status = status(latency = true)).quiet)
    }

    @Test
    fun `an acceptor with no status at all reads as having no rules rather than crashing`() {
        val summary = venue(status = null)
        assertTrue(summary.noRules)
        assertEquals(0, summary.pending)
        assertEquals(0L, summary.triggered)
        // Nothing to diverge from: a venue that has answered nothing has not failed to answer anything.
        assertFalse(summary.sendsDiverge)
    }

    @Test
    fun `both surfaces get the same words for the same fact`() {
        // An earlier draft said "2 up" on the chip and "2 connected" on the pane, which is two names
        // for one number and exactly the drift this type exists to prevent.
        assertEquals("2 connected", venue(up = 2).clientsLabel())
        assertEquals("2 connected, 1 gone", venue(up = 2, gone = 1).clientsLabel())
        assertEquals("no clients", venue(up = 0, gone = 0).clientsLabel())
        assertEquals("listening on 19876", venue().portLabel())
        assertEquals("not listening", venue(listening = false).portLabel())
    }

    @Test
    fun `a stopped venue still reports its port, because that is identity and not status`() {
        val stopped = venue(listening = false, up = 0)
        assertEquals("not listening", stopped.portLabel())
        assertEquals("19876", stopped.port)
        assertEquals("DEMO_SERVER", stopped.senderCompID)
    }

    @Test
    fun `panes outliving their sessions do not inflate the connected count`() {
        // A client that logs out keeps its pane on purpose — the traffic just before a drop is what
        // explains the drop — so "3 connected" over one live client would be the more misleading number.
        val summary = venue(up = 1, gone = 2)
        assertEquals("1 connected, 2 gone", summary.clientsLabel())
        assertEquals(1, summary.clientsConnected)
    }

    @Test
    fun `the pane's counter line grows only by what has deviated`() {
        assertEquals("21 rules  ·  41 triggered  ·  41 sent", venue().rulesLabel())
        assertEquals(
            "21 rules  ·  41 triggered  ·  38 sent  ·  3 pending  ·  latency on",
            venue(status = status(triggered = 41, sent = 38, pending = 3, latency = true)).rulesLabel(),
        )
    }
}
