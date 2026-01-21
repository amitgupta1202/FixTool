package com.knapsack.fixtool.service.demo

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.service.SavedMessagesService
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

    // Callback to notify when demo templates are created/deleted
    var onDemoTemplatesChanged: ((List<SavedFixMessage>) -> Unit)? = null

    private const val DEMO_PROFILE_PREFIX = "demo-profile-"

    // Service for managing saved messages (templates)
    private val savedMessagesService by lazy {
        SavedMessagesService(
            onError = { errorMsg -> logger.error("SavedMessagesService error: {}", errorMsg) }
        )
    }

    /**
     * Starts the demo server if not already running
     * Also creates demo profiles and templates
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

            // Create demo templates
            val profileIds = demoProfiles.map { it.id }.toSet()
            createDemoTemplates(profileIds)
        } catch (e: Exception) {
            logger.error("Failed to start demo server: {}", e.message, e)
            demoServer = null
            _isRunning.value = false
            throw e
        }
    }

    /**
     * Stops the demo server if running
     * Also removes demo profiles and templates
     */
    fun stop() {
        try {
            demoServer?.stop()
            demoServer = null
            _isRunning.value = false
            logger.info("Demo server manager stopped")

            // Delete demo templates first (before profiles are removed)
            deleteDemoTemplates()

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
                config = FixConnectionConfig(
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

    /**
     * Creates demo templates and saves them to storage
     */
    private fun createDemoTemplates(profileIds: Set<String>) {
        val templates = DemoTemplatesProvider.createDemoTemplates(profileIds)

        templates.forEach { template ->
            // Use the first profile ID for saving (templates are associated with all profiles via userTags)
            val firstProfileId = profileIds.firstOrNull() ?: return@forEach
            savedMessagesService.saveMessage(firstProfileId, template)
                .onSuccess {
                    logger.debug("Created demo template: {}", template.name)
                }
                .onFailure { error ->
                    logger.error("Failed to create demo template {}: {}", template.name, error.message)
                }
        }

        logger.info("Created {} demo templates", templates.size)

        // Notify about template changes
        onDemoTemplatesChanged?.invoke(templates)
    }

    /**
     * Deletes all demo templates from storage
     * Only deletes messages with the demo- prefix, preserving user data
     */
    private fun deleteDemoTemplates() {
        val templateIds = DemoTemplatesProvider.getDemoTemplateIds()
        var deletedCount = 0

        templateIds.forEach { templateId ->
            // Need a profile ID for the delete operation
            val profileId = "$DEMO_PROFILE_PREFIX${DemoFixServer.DEMO_CLIENTS.first()}"
            savedMessagesService.deleteMessage(profileId, templateId)
                .onSuccess {
                    deletedCount++
                    logger.debug("Deleted demo template: {}", templateId)
                }
                .onFailure { error ->
                    // Template might not exist, which is fine
                    logger.debug("Could not delete demo template {}: {}", templateId, error.message)
                }
        }

        logger.info("Deleted {} demo templates", deletedCount)

        // Notify about template changes (empty list signals deletion)
        onDemoTemplatesChanged?.invoke(emptyList())
    }

    /**
     * Checks if a profile ID is a demo profile
     */
    fun isDemoProfile(profileId: String): Boolean = profileId.startsWith(DEMO_PROFILE_PREFIX)

    /**
     * Checks if a message ID is a demo template
     */
    fun isDemoTemplate(messageId: String): Boolean = DemoTemplatesProvider.isDemoTemplate(messageId)

    /**
     * Returns the profile IDs for all demo profiles
     */
    fun getDemoProfileIds(): List<String> =
        DemoFixServer.DEMO_CLIENTS.map { "$DEMO_PROFILE_PREFIX$it" }
}
