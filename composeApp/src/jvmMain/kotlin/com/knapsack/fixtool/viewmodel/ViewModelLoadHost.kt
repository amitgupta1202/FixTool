package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.load.LoadHost
import com.knapsack.fixtool.service.load.LoadLane
import com.knapsack.fixtool.service.load.SessionLoadLane
import org.slf4j.LoggerFactory

/**
 * **A [LoadHost] over the window's live sessions.**
 *
 * The lanes and listeners are gathered by the view model on its own thread before the run starts, so
 * nothing here reads Compose state. What this adds is the store and log override: a lane whose config
 * differs from what the run asked for is reconnected with the run's config before it issues, and
 * reconnected back on release, so a profile borrowed for one load run goes back exactly as it was.
 * Reconnecting means a logout and a fresh logon, which the dialog says.
 */
class ViewModelLoadHost(
    private val lanes: List<Pair<Lane, FixMessageSession>>,
    private val listeners: List<FixMessageSession>,
    private val resolve: (template: String, scope: Map<String, String>, sessionTitle: String) -> String,
    /**
     * Named for what they are, not what they return. A constructor property called `dictionary` beside
     * `override fun dictionary()` resolves the call inside the override to the function itself, and the first
     * live run found that as a StackOverflowError before a single message left.
     */
    private val dictionaryProvider: () -> FixDictionaryAdapter,
    private val settingsProvider: () -> AppSettings,
) : LoadHost {
    private val logger = LoggerFactory.getLogger(ViewModelLoadHost::class.java)

    /** Sessions reconnected under the override, with the config to put back. */
    private val restore = mutableListOf<Pair<FixMessageSession, FixConnectionConfig>>()

    override fun openLanes(profileId: String, override: StoreAndLogOverride?): List<LoadLane> {
        val ready = lanes.filter { (_, session) -> applyOverride(session, override) }
        return ready.map { (lane, session) -> SessionLoadLane(lane, session) }
    }

    override fun openListeners(profileIds: List<String>, override: StoreAndLogOverride?): List<LoadLane> =
        listeners
            .filter { applyOverride(it, override) }
            .map { SessionLoadLane(Lane(0, it.title, it.currentConfig?.senderCompID.orEmpty(), it.sessionQualifier), it) }

    override fun resolveOnce(template: String, scope: Map<String, String>, lane: LoadLane): String = resolve(template, scope, lane.lane.sessionTitle)

    override fun dictionary(): FixDictionaryAdapter = dictionaryProvider()

    /** Puts every overridden session back on its own config. The sessions stay up: they are the user's. */
    override fun release() {
        restore.reversed().forEach { (session, config) -> reconnect(session, config) }
        restore.clear()
    }

    /**
     * True when the session is logged on with the store and log the run wants, reconnecting it if it was
     * not. False when it could not be brought back, in which case it is left out of the run and the
     * shortfall is reported like any other lane that did not log on.
     */
    private fun applyOverride(session: FixMessageSession, override: StoreAndLogOverride?): Boolean {
        val current = session.currentConfig ?: return false
        if (override == null || (current.messageStore == override.store && current.messageLog == override.log)) {
            return session.connectionState.value == FixConnectionState.LOGGED_ON
        }
        val wanted = override.applyTo(current)
        if (wanted.storeProblem() != null) {
            logger.warn("Not overriding '{}': {}", session.title, wanted.storeProblem())
            return false
        }
        restore += session to current
        return reconnect(session, wanted)
    }

    /** Disconnects, waits for the engine to let go, connects with [config], waits for logon. Bounded. */
    private fun reconnect(session: FixMessageSession, config: FixConnectionConfig): Boolean {
        session.disconnect()
        awaitState(session, RECONNECT_TIMEOUT_MS) { it != FixConnectionState.LOGGED_ON && it != FixConnectionState.CONNECTED && it != FixConnectionState.CONNECTING }
        session.connect(config, settingsProvider(), dictionaryProvider())
        return awaitState(session, RECONNECT_TIMEOUT_MS) { it == FixConnectionState.LOGGED_ON }
    }

    private fun awaitState(session: FixMessageSession, timeoutMs: Long, done: (FixConnectionState) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (done(session.connectionState.value)) return true
            Thread.sleep(POLL_MS)
        }
        return done(session.connectionState.value)
    }

    private companion object {
        const val RECONNECT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
