package com.knapsack.fixtool.ui

import com.knapsack.fixtool.service.AcceptorStatus

/**
 * **What a venue says about itself, at either size.**
 *
 * A venue is drawn twice: as a pane, and as a chip in the minimized strip. Those were built separately
 * once, and the chip promptly grew a counter and two buttons the pane did not have — which quietly
 * made the venue two features instead of one thing at two densities. This type is the contract that
 * stops it happening again: both surfaces derive everything from here, so the chip can only ever be a
 * *subset* of the pane, and neither can learn a fact the other cannot say.
 *
 * The `show*` flags are the other half of it. What earns a place is **identity and live state always,
 * everything else only when it deviates from boring** — the rule this file's neighbours already state
 * twice, in [FixMessageSession.refusedLogons] (drawn only when non-empty) and on the discarded-messages
 * badge ("a badge that is always there is furniture, not a warning"). Twenty-one rules loaded is
 * furniture; *zero* rules loaded is the answer to why the venue is silent, so [noRules] is a warning
 * and there is no permanent rule count on the chip at all. Triggered and sent are boring while equal
 * and a real finding when they are not, so only [sendsDiverge] is promoted.
 *
 * The consequence is the one worth having: a healthy venue's chip is the short one, and it widens in
 * proportion to how much has gone wrong.
 */
data class VenueSummary(
    val senderCompID: String,
    val port: String,
    val listening: Boolean,
    val clientsConnected: Int,
    val clientsGone: Int,
    val refused: Int,
    val rulesLive: Int,
    val latencyActive: Boolean,
    val pending: Int,
    val triggered: Long,
    val sent: Long,
) {
    /** A client left. Zero departed is the resting state and says nothing. */
    val showGone: Boolean get() = clientsGone > 0

    /** A logon was turned away. The one fact that exists nowhere else in the app. */
    val showRefused: Boolean get() = refused > 0

    /** Replies still in flight. Zero is the resting state. */
    val showPending: Boolean get() = pending > 0

    /** Injected latency is on, so the timings on screen are deliberately skewed. */
    val showLatency: Boolean get() = latencyActive

    /** No rules loaded, which is why this venue is answering nothing. */
    val noRules: Boolean get() = rulesLive == 0

    /** Replies were owed and not sent. Equal counts are boring; unequal ones are a bug. */
    val sendsDiverge: Boolean get() = sent < triggered

    /**
     * Nothing has deviated, so a chip needs no badges at all.
     *
     * Not merely cosmetic: it is the assertion that keeps the strip narrow, and the thing a test can
     * hold onto. A venue running normally must reduce to identity plus a client count.
     */
    val quiet: Boolean
        get() = !showGone && !showRefused && !showPending && !showLatency && !noRules && !sendsDiverge

    /**
     * "listening on 19876", or "not listening".
     *
     * One phrasing, used by both surfaces. A venue that is not bound has to *look* not bound at either
     * size, which is why this is here and not inlined at two call sites that could drift apart.
     */
    fun portLabel(): String = if (listening) "listening on ${port.ifBlank { "?" }}" else "not listening"

    /**
     * "2 connected", "2 connected, 1 gone", or "no clients".
     *
     * The same words at both sizes. An earlier draft said "2 up" on the chip to save seven characters,
     * which is a different word for the same fact and exactly the drift this type exists to prevent.
     *
     * Counts only what is on the venue right now. Panes outlive their sessions on purpose — a client's
     * history is most wanted just after it drops — so the number of panes is not the number of
     * connections, and a header reading "5 clients" over two live ones would be the more misleading of
     * the two numbers.
     */
    fun clientsLabel(): String =
        when {
            clientsConnected == 0 && clientsGone == 0 -> "no clients"
            clientsGone == 0 -> "$clientsConnected connected"
            else -> "$clientsConnected connected, $clientsGone gone"
        }

    /** "41 triggered · 41 sent", for the pane. The chip promotes only [sendsDiverge]. */
    fun rulesLabel(): String =
        buildString {
            append("$rulesLive rules")
            append("  ·  $triggered triggered")
            append("  ·  $sent sent")
            if (showPending) append("  ·  $pending pending")
            if (showLatency) append("  ·  latency on")
        }

    /** "41/38 sent", the chip's form of [sendsDiverge]. */
    fun divergenceLabel(): String = "$triggered/$sent sent"

    companion object {
        /**
         * Reads a venue from what its pane already has in hand.
         *
         * Takes plain values rather than the session, so it stays free of Compose and of
         * [com.knapsack.fixtool.model.FixMessageSession] — the deviation rule is the part worth
         * testing, and it should be testable without a UI or an engine.
         */
        fun of(
            senderCompID: String,
            port: String,
            listening: Boolean,
            clientsConnected: Int,
            clientsGone: Int,
            refused: Int,
            status: AcceptorStatus?,
        ): VenueSummary =
            VenueSummary(
                senderCompID = senderCompID,
                port = port,
                listening = listening,
                clientsConnected = clientsConnected,
                clientsGone = clientsGone,
                refused = refused,
                rulesLive = status?.rulesLive ?: 0,
                latencyActive = status?.latencyActive == true,
                pending = status?.pendingResponses ?: 0,
                triggered = status?.triggersMatched ?: 0L,
                sent = status?.responsesSent ?: 0L,
            )
    }
}
