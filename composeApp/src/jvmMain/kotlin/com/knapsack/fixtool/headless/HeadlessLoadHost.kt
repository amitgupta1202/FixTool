package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.load.LoadHost
import com.knapsack.fixtool.service.load.LoadLane
import com.knapsack.fixtool.service.load.SessionLoadLane

/**
 * **A [LoadHost] with no window**, over the same session host `fixtool run` uses.
 *
 * The lanes are the profile's slots, brought up exactly as a fan-out brings them up, with the run's store
 * and log override applied to each lane's config before it dials. A listener is one session of each named
 * profile, logged on and otherwise left alone. The once-per-lane expressions go through the scenario host's
 * own evaluator, so `${out.D.11}` means in a load template what it means in a scenario step.
 */
class HeadlessLoadHost(
    private val profiles: List<FixConnectionProfile>,
    private val dictionary: FixDictionaryAdapter,
    appSettings: AppSettings,
    private val onLog: (String) -> Unit = {},
) : LoadHost {
    private val inner = HeadlessScenarioHost(profiles, dictionary, appSettings, onLog)

    override fun openLanes(profileId: String, override: StoreAndLogOverride?): List<LoadLane> {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return emptyList()
        val configure = configureWith(override)
        onLog("opening ${profile.config.sessionCount.coerceAtLeast(1)} lanes of '${profile.name}'" + (override?.let { " (${it.describe()})" } ?: ""))
        val lanes = inner.openLanes(profile, configure = configure)
        val shortfall = profile.config.sessionCount.coerceAtLeast(1) - lanes.size
        if (shortfall > 0) onLog("$shortfall of '${profile.name}'s sessions did not reach LOGGED_ON")
        return lanes.mapNotNull { lane -> inner.opened.firstOrNull { it.title == lane.sessionTitle }?.let { SessionLoadLane(lane, it) } }
    }

    override fun openListeners(profileIds: List<String>, override: StoreAndLogOverride?): List<LoadLane> =
        profileIds.mapNotNull { key ->
            val profile = profiles.firstOrNull { it.id == key || it.name == key }
            if (profile == null) {
                onLog("no saved connection profile named '$key' to listen on")
                return@mapNotNull null
            }
            val session = inner.openSingle(profile, configure = configureWith(override))
            if (session == null) {
                onLog("'${profile.name}' did not reach LOGGED_ON, so it is not listening")
                return@mapNotNull null
            }
            SessionLoadLane(Lane(0, session.title, session.currentConfig?.senderCompID.orEmpty(), session.sessionQualifier), session)
        }

    override fun resolveOnce(template: String, scope: Map<String, String>, lane: LoadLane): String =
        inner.resolve(template, scope.toMutableMap(), lane.lane.sessionTitle)

    override fun dictionary(): FixDictionaryAdapter = dictionary

    override fun release() = inner.disconnectAll()

    private fun configureWith(override: StoreAndLogOverride?): (FixConnectionConfig) -> FixConnectionConfig =
        { config -> override?.applyTo(config) ?: config }
}
