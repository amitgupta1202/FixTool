package com.knapsack.fixtool.service.demo

import com.knapsack.fixtool.model.AcceptorLatencyConfig
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.service.FxVenuePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * **The demo is a venue you can read.**
 *
 * There used to be a FIX server behind this button — some 1,540 lines of hard-coded QuickFIX/J that
 * priced six pairs, walked a market and filled resting orders, and that nobody could open, reorder or
 * make misbehave on purpose. It was a second implementation of "a venue", and the product already had
 * a better one: the acceptor, with rules on cards, timed multi-step replies, an order-state book and
 * per-message latency.
 *
 * So this installs a **workspace** rather than starting a server: one acceptor profile carrying the
 * [FxVenuePreset] bundle, two client profiles pointed at it, the templates to drive it, and one
 * scenario that runs green out of the box. Everything the demo does afterwards is a shipped feature,
 * which is the whole point — a viewer watching the venue think is watching the product.
 *
 * ### This object builds; the caller persists
 *
 * Nothing here writes to disk. It hands finished artifacts to the three callbacks and the ViewModel
 * saves them through the services that respect the user's configured paths. That is not tidiness: the
 * previous version kept its own `SavedMessagesService` on the *default* path, so demo templates were
 * written to `~/.fixtool` no matter where the app had been told to keep them — and a test starting the
 * demo wrote into the real user's file.
 *
 * ### What "running" means now
 *
 * [isRunning] says **the workspace is installed**, not that a socket is bound. The venue is an ordinary
 * profile with an ordinary state dot, and that dot is the honest answer about whether it is listening;
 * a second status line here could only ever disagree with it.
 */
object DemoServerManager {
    private val logger = LoggerFactory.getLogger(DemoServerManager::class.java)

    /** The port the demo venue binds. Overridable so a test can drive the whole workspace off 19876. */
    const val DEMO_PORT = 19876

    private const val DEMO_PROFILE_PREFIX = "demo-profile-"

    /** The venue's profile id and the CompID clients address. Unchanged from the old server, on purpose. */
    const val VENUE_PROFILE_ID = "${DEMO_PROFILE_PREFIX}venue"
    const val VENUE_NAME = "FX Demo Venue"
    const val VENUE_COMP_ID = "DEMO_SERVER"

    /**
     * Two clients, down from four.
     *
     * The venue takes `TargetCompID=*`, so the reduction costs nothing: a client calling itself
     * `DEMO_CLIENT3` still logs on with no profile of its own. Two panes is what fits on camera beside
     * the venue's rule cards, and two clients on one venue is the multi-client shot.
     */
    val DEMO_CLIENTS = listOf("DEMO_CLIENT1", "DEMO_CLIENT2")

    /**
     * The venue's simulated latency — #36, on camera.
     *
     * Without it the acceptor replies as fast as the machine allows, which is a latency no venue has:
     * the ack lands before the send call has returned, and a client whose timeout logic is wrong passes
     * here and fails in production. 40–80ms is a plausible hop and enough that the eye sees the reply
     * arrive rather than appear.
     */
    val DEMO_LATENCY =
        AcceptorLatencyConfig(mode = AcceptorLatencyConfig.Mode.RANDOM_RANGE, minMillis = 40, maxMillis = 80)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentFixVersion = MutableStateFlow<FixVersion?>(null)
    val currentFixVersion: StateFlow<FixVersion?> = _currentFixVersion.asStateFlow()

    /** The port the running workspace is on, for anything that has to name it (the panel does). */
    @Volatile
    var currentPort: Int = DEMO_PORT
        private set

    /**
     * Install these profiles and connect them — venue first — or, given an empty list, disconnect and
     * remove whatever is installed.
     */
    var onDemoProfilesChanged: ((List<FixConnectionProfile>) -> Unit)? = null

    /** Save these templates, or delete the demo ones when the list is empty. */
    var onDemoTemplatesChanged: ((List<SavedFixMessage>) -> Unit)? = null

    /** Save these scenarios, or delete the demo ones when the list is empty. */
    var onDemoScenariosChanged: ((List<Scenario>) -> Unit)? = null

    /**
     * Installs the demo workspace.
     *
     * [fixVersion] reaches the profiles' `beginString`. Note that a loaded data dictionary still
     * overrides it at connect time (`FixConnectionManager.determineBeginString`) — both demo sides read
     * the same dictionary so they always agree, but picking 4.2 while the app is loaded with 4.4 does
     * not produce a 4.2 session. Pre-existing, and a connection-settings question rather than a demo one.
     */
    fun start(fixVersion: FixVersion = FixVersion.DEFAULT, port: Int = DEMO_PORT) {
        if (_isRunning.value) {
            logger.warn("Demo workspace already installed")
            return
        }
        _currentFixVersion.value = fixVersion
        currentPort = port

        val profiles = demoProfiles(fixVersion, port)
        onDemoProfilesChanged?.invoke(profiles)

        val templates = DemoTemplatesProvider.createDemoTemplates(clientProfileIds().toSet())
        onDemoTemplatesChanged?.invoke(templates)

        onDemoScenariosChanged?.invoke(DemoScenarioProvider.scenarios())

        _isRunning.value = true
        logger.info(
            "Demo workspace installed: '{}' on localhost:{} ({}), {} client(s), {} template(s)",
            VENUE_NAME,
            port,
            fixVersion.displayName,
            DEMO_CLIENTS.size,
            templates.size,
        )
    }

    /** Removes the demo workspace: sessions down, profiles, templates and scenarios gone. */
    fun stop() {
        if (!_isRunning.value) return
        // Templates and scenarios first, while the profiles they are tagged with still exist.
        onDemoTemplatesChanged?.invoke(emptyList())
        onDemoScenariosChanged?.invoke(emptyList())
        onDemoProfilesChanged?.invoke(emptyList())

        _isRunning.value = false
        _currentFixVersion.value = null
        currentPort = DEMO_PORT
        logger.info("Demo workspace removed")
    }

    /**
     * The venue, then the clients — **and the order is the contract**, because the caller connects them
     * in the order given and an initiator whose acceptor has not bound the port yet waits out a
     * reconnect interval before trying again.
     */
    fun demoProfiles(fixVersion: FixVersion = FixVersion.DEFAULT, port: Int = DEMO_PORT): List<FixConnectionProfile> =
        listOf(venueProfile(fixVersion, port)) + clientProfiles(fixVersion, port)

    /**
     * The venue: an acceptor open to any client, carrying the FX bundle and the demo latency.
     *
     * `TargetCompID=*` is what makes this one endpoint many clients reach — and it is opt-in everywhere
     * else for a good reason, so it is stated here rather than inherited: a demo venue that refused
     * every CompID but two would be a worse teaching surface than the thing it replaces.
     */
    private fun venueProfile(fixVersion: FixVersion, port: Int) =
        FixConnectionProfile(
            id = VENUE_PROFILE_ID,
            name = VENUE_NAME,
            config =
                FixConnectionConfig(
                    connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                    senderCompID = VENUE_COMP_ID,
                    targetCompID = FixConnectionConfig.ANY_CLIENT,
                    host = "localhost",
                    port = port.toString(),
                    socketAcceptPort = port.toString(),
                    beginString = fixVersion.beginString,
                    applVerID = fixVersion.applVerID,
                    heartBtInt = "30",
                    acceptorResponseRules =
                        AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(FxVenuePreset.ID)!!).rules,
                    acceptorLatency = DEMO_LATENCY,
                ),
        )

    private fun clientProfiles(fixVersion: FixVersion, port: Int) =
        DEMO_CLIENTS.mapIndexed { index, clientId ->
            FixConnectionProfile(
                id = "$DEMO_PROFILE_PREFIX$clientId",
                name = clientName(index),
                config =
                    FixConnectionConfig(
                        senderCompID = clientId,
                        targetCompID = VENUE_COMP_ID,
                        host = "localhost",
                        port = port.toString(),
                        beginString = fixVersion.beginString,
                        applVerID = fixVersion.applVerID,
                        heartBtInt = "30",
                        // Five seconds, not the default thirty. The venue and its clients come up
                        // together, and a client that loses that race by a few milliseconds would
                        // otherwise sit dark for half a minute — which looks exactly like a demo that
                        // does not work.
                        reconnectInterval = "5",
                        resetOnLogon = true,
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                    ),
            )
        }

    /** `Demo Client 1` — the pane title a bundled scenario names, so it is minted in one place. */
    fun clientName(index: Int): String = "Demo Client ${index + 1}"

    /**
     * The venue's pane for a given client — `FX Demo Venue ← DEMO_CLIENT1`.
     *
     * The title `FixMessageViewModel.attachVenueClient` mints, repeated here because the bundled
     * scenario's `ClearOrderBook` step has to name it and a scenario that names the wrong session is
     * refused by preflight rather than run.
     */
    fun venuePaneFor(clientCompId: String): String = "$VENUE_NAME ← $clientCompId"

    fun isDemoProfile(profileId: String): Boolean = profileId.startsWith(DEMO_PROFILE_PREFIX)

    fun isDemoTemplate(messageId: String): Boolean = DemoTemplatesProvider.isDemoTemplate(messageId)

    fun isDemoScenario(scenarioId: String): Boolean = DemoScenarioProvider.isDemoScenario(scenarioId)

    /** Every demo profile id, venue first. */
    fun getDemoProfileIds(): List<String> = listOf(VENUE_PROFILE_ID) + clientProfileIds()

    private fun clientProfileIds(): List<String> = DEMO_CLIENTS.map { "$DEMO_PROFILE_PREFIX$it" }
}
