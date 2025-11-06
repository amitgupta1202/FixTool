package com.knapsack.fixtool.service.demo

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

object DemoServerManager {
    private val logger = LoggerFactory.getLogger(DemoServerManager::class.java)

    private var demoServer: DemoFixServer? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Callback to notify when demo profiles should be created/deleted
    var onDemoProfilesChanged: ((List<FixConnectionProfile>) -> Unit)? = null

    private const val DEMO_PROFILE_PREFIX = "demo-profile-"

    /**
     * Starts the demo server if not already running
     * Also creates demo profiles for each demo client
     */
    fun start() {
        if (demoServer != null) {
            logger.warn("Demo server already running")
            return
        }

        try {
            demoServer = DemoFixServer()
            demoServer?.start()
            _isRunning.value = true
            logger.info("Demo server manager started")

            // Create demo profiles
            val demoProfiles = createDemoProfiles()
            onDemoProfilesChanged?.invoke(demoProfiles)
        } catch (e: Exception) {
            logger.error("Failed to start demo server: {}", e.message, e)
            demoServer = null
            _isRunning.value = false
            throw e
        }
    }

    /**
     * Stops the demo server if running
     * Also removes demo profiles
     */
    fun stop() {
        try {
            demoServer?.stop()
            demoServer = null
            _isRunning.value = false
            logger.info("Demo server manager stopped")

            // Signal to delete demo profiles
            onDemoProfilesChanged?.invoke(emptyList())
        } catch (e: Exception) {
            logger.error("Failed to stop demo server: {}", e.message, e)
        }
    }

    private fun createDemoProfiles(): List<FixConnectionProfile> =
        DemoFixServer.DEMO_CLIENTS.mapIndexed { index, clientId ->
            FixConnectionProfile(
                id = "$DEMO_PROFILE_PREFIX$clientId",
                name = "Demo User ${index + 1}",
                config =
                    FixConnectionConfig(
                        senderCompID = clientId,
                        targetCompID = "DEMO_SERVER",
                        host = "localhost",
                        port = "19876",
                        beginString = "FIX.4.4",
                        heartBtInt = "30",
                        resetOnLogon = true,
                        resetOnLogout = false,
                        resetOnDisconnect = false,
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                    ),
            )
        }

    fun isDemoProfile(profileId: String): Boolean = profileId.startsWith(DEMO_PROFILE_PREFIX)
}
