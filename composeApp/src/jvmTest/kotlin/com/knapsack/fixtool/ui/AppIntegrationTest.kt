package com.knapsack.fixtool.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * App-level UI integration tests for the complete application workflow.
 *
 * Tests the main application components including:
 * - Toolbar functionality (view mode toggle, demo server, settings, buttons)
 * - Connection panel display and interaction
 * - Message editor panel interaction
 * - Message detail panel display
 */
class AppIntegrationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var viewMode by mutableStateOf(ViewMode.SPLIT_HORIZONTAL)
    private var showMessageEditor by mutableStateOf(false)
    private var showDetailPanel by mutableStateOf(false)
    private var showConnectionPanel by mutableStateOf(false)
    private var showSettingsDialog by mutableStateOf(false)
    private var demoServerRunning by mutableStateOf(false)

    @Before
    fun setup() {
        viewMode = ViewMode.SPLIT_HORIZONTAL
        showMessageEditor = false
        showDetailPanel = false
        showConnectionPanel = false
        showSettingsDialog = false
        demoServerRunning = false
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun createTestProfile(name: String): FixConnectionProfile =
        FixConnectionProfile(
            id = "test-profile-${System.currentTimeMillis()}",
            name = name,
            config =
                FixConnectionConfig(
                    username = "testuser",
                    senderCompID = "SENDER",
                    targetCompID = "TARGET",
                    password = "password",
                    host = "localhost",
                    port = "9876",
                    beginString = "FIX.4.4",
                    heartBtInt = "30",
                    resetOnLogon = true,
                    resetOnLogout = false,
                    resetOnDisconnect = false,
                    showHeartbeat = true,
                    customParameters = emptyMap(),
                    logonFields = emptyMap(),
                ),
        )

    // ========================================
    // Toolbar Display Tests
    // ========================================

    @Test
    fun testToolbarDisplaysAppName() {
        // When: Toolbar is rendered
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
            )
        }

        // Then: The toolbar should display app name
        composeTestRule.onNodeWithText("FixTool").assertExists()
    }

    @Test
    fun testToolbarDisplaysMessageEditorButton() {
        // When: Toolbar is rendered with message editor callback
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                showMessageEditor = showMessageEditor,
                onOpenMessageEditor = { showMessageEditor = !showMessageEditor },
            )
        }

        // Then: Message editor button should be displayed
        composeTestRule.onNodeWithContentDescription("Message Editor").assertExists()
    }

    @Test
    fun testToolbarDisplaysConnectionButton() {
        // When: Toolbar is rendered with connection panel callback
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                showConnectionPanel = showConnectionPanel,
                onToggleConnectionPanel = { showConnectionPanel = !showConnectionPanel },
            )
        }

        // Then: Connection panel button should be displayed
        composeTestRule.onNodeWithContentDescription("Toggle Connection Panel").assertExists()
    }

    @Test
    fun testToolbarDisplaysViewModeButton() {
        // When: Toolbar is rendered
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
            )
        }

        // Then: Layout toggle button should be displayed
        composeTestRule.onNodeWithContentDescription("Toggle Layout").assertExists()
    }

    @Test
    fun testToolbarDisplaysSettingsButton() {
        // When: Toolbar is rendered with settings callback
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                onOpenSettings = { showSettingsDialog = !showSettingsDialog },
            )
        }

        // Then: Settings button should be displayed
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun testToolbarDisplaysDemoServerButton() {
        // When: Toolbar is rendered with demo server callback
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                demoServerRunning = demoServerRunning,
                onStartDemoServer = { demoServerRunning = true },
                onStopDemoServer = { demoServerRunning = false },
            )
        }

        // Then: Demo server button should be displayed
        composeTestRule.onNodeWithContentDescription("Demo Server").assertExists()
    }

    // ========================================
    // Toolbar Button Toggle Tests
    // ========================================

    @Test
    fun testMessageEditorButtonTogglesState() {
        // Given: Toolbar with message editor initially off
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                showMessageEditor = showMessageEditor,
                onOpenMessageEditor = { showMessageEditor = !showMessageEditor },
            )
        }

        // Verify initial state
        assertFalse(showMessageEditor, "Message editor should be initially off")

        // When: Message editor button is clicked
        composeTestRule.onNodeWithContentDescription("Message Editor").performClick()
        composeTestRule.waitForIdle()

        // Then: State should toggle
        assertTrue(showMessageEditor, "Message editor should be shown after click")
    }

    @Test
    fun testConnectionPanelButtonTogglesState() {
        // Given: Toolbar with connection panel initially off
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                showConnectionPanel = showConnectionPanel,
                onToggleConnectionPanel = { showConnectionPanel = !showConnectionPanel },
            )
        }

        // Verify initial state
        assertFalse(showConnectionPanel, "Connection panel should be initially off")

        // When: Connection panel button is clicked
        composeTestRule.onNodeWithContentDescription("Toggle Connection Panel").performClick()
        composeTestRule.waitForIdle()

        // Then: State should toggle
        assertTrue(showConnectionPanel, "Connection panel should be shown after click")
    }

    @Test
    fun testSettingsButtonTogglesState() {
        // Given: Toolbar with settings dialog initially off
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                onOpenSettings = { showSettingsDialog = !showSettingsDialog },
            )
        }

        // Verify initial state
        assertFalse(showSettingsDialog, "Settings dialog should be initially off")

        // When: Settings button is clicked
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.waitForIdle()

        // Then: State should toggle
        assertTrue(showSettingsDialog, "Settings dialog should be shown after click")
    }

    @Test
    fun testViewModeButtonCyclesThroughModes() {
        // Given: Toolbar with initial view mode
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
            )
        }

        // Initial state: SPLIT_HORIZONTAL
        assertTrue(viewMode == ViewMode.SPLIT_HORIZONTAL, "Initial view mode should be SPLIT_HORIZONTAL")

        // When: Layout button is clicked
        composeTestRule.onNodeWithContentDescription("Toggle Layout").performClick()
        composeTestRule.waitForIdle()

        // Then: Should change to SPLIT_VERTICAL
        assertTrue(viewMode == ViewMode.SPLIT_VERTICAL, "View mode should be SPLIT_VERTICAL after first click")

        // When: Layout button is clicked again
        composeTestRule.onNodeWithContentDescription("Toggle Layout").performClick()
        composeTestRule.waitForIdle()

        // Then: Should change to TABS
        assertTrue(viewMode == ViewMode.TABS, "View mode should be TABS after second click")

        // When: Layout button is clicked again
        composeTestRule.onNodeWithContentDescription("Toggle Layout").performClick()
        composeTestRule.waitForIdle()

        // Then: Should cycle back to SPLIT_HORIZONTAL
        assertTrue(viewMode == ViewMode.SPLIT_HORIZONTAL, "View mode should cycle back to SPLIT_HORIZONTAL")
    }

    @Test
    fun testDemoServerDropdownStartsServer() {
        // Given: Toolbar with demo server initially off
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                demoServerRunning = demoServerRunning,
                onStartDemoServer = { demoServerRunning = true },
                onStopDemoServer = { demoServerRunning = false },
            )
        }

        // Verify initial state
        assertFalse(demoServerRunning, "Demo server should be initially off")

        // When: Demo server dropdown is clicked and FIX 4.4 is selected
        composeTestRule.onNodeWithContentDescription("Demo Server").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("FIX 4.4").performClick()
        composeTestRule.waitForIdle()

        // Then: Demo server should be started
        assertTrue(demoServerRunning, "Demo server should be running after selecting version")
    }

    // ========================================
    // Quick Connect Dropdown Tests
    // ========================================

    @Test
    fun testQuickConnectDropdownAppearsWhenProfilesExist() {
        // Given: Toolbar with connection profiles
        val testProfile = createTestProfile("Test Profile 1")
        val profiles = listOf(testProfile)

        // When: Toolbar is rendered with profiles
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                connectionProfiles = profiles,
                onQuickConnect = { _, _ -> },
            )
        }

        // Then: Quick Connect dropdown should be displayed
        composeTestRule.onNodeWithText("Quick Connect").assertExists()
    }

    @Test
    fun testQuickConnectDropdownDoesNotAppearWithoutProfiles() {
        // Given: Toolbar with no profiles
        // When: Toolbar is rendered
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                connectionProfiles = emptyList(),
                onQuickConnect = { _, _ -> },
            )
        }

        // Then: Quick Connect dropdown should NOT be displayed
        composeTestRule.onNodeWithText("Quick Connect").assertDoesNotExist()
    }

    @Test
    fun testQuickConnectDropdownShowsProfileNames() {
        // Given: Toolbar with multiple profiles
        val profile1 = createTestProfile("Profile 1")
        val profile2 = createTestProfile("Profile 2")
        val profiles = listOf(profile1, profile2)

        // When: Toolbar is rendered and dropdown is clicked
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                connectionProfiles = profiles,
                onQuickConnect = { _, _ -> },
            )
        }

        // Click to expand dropdown
        composeTestRule.onNodeWithText("Quick Connect").performClick()
        composeTestRule.waitForIdle()

        // Then: Profile names should be visible in dropdown
        composeTestRule.onNodeWithText("Profile 1").assertExists()
        composeTestRule.onNodeWithText("Profile 2").assertExists()
    }

    // ========================================
    // Connection Panel Display Tests
    // ========================================

    @Test
    fun testConnectionPanelDisplaysHeader() {
        // Given: Connection panel is rendered
        composeTestRule.setContent {
            ConnectionPanel(
                profiles = emptyList(),
                sessions = emptyList(),
                onConnect = { _, _ -> },
                onDisconnect = { },
                onSaveProfile = { },
                onDeleteProfile = { },
                onCloneProfile = { it },
                onGetProfileSession = { null },
                onClose = { },
            )
        }

        // Then: Connection panel header should be displayed
        composeTestRule.onNodeWithText("FIX Connection").assertExists()
    }

    @Test
    fun testConnectionPanelDisplaysConnectionFields() {
        // Given: Connection panel is rendered
        composeTestRule.setContent {
            ConnectionPanel(
                profiles = emptyList(),
                sessions = emptyList(),
                onConnect = { _, _ -> },
                onDisconnect = { },
                onSaveProfile = { },
                onDeleteProfile = { },
                onCloneProfile = { it },
                onGetProfileSession = { null },
                onClose = { },
            )
        }

        // Then: Connection fields should be displayed
        composeTestRule.onNodeWithText("SenderCompID").assertExists()
        composeTestRule.onNodeWithText("TargetCompID").assertExists()
        composeTestRule.onNodeWithText("Host").assertExists()
        composeTestRule.onNodeWithText("Port").assertExists()
    }

    @Test
    fun testConnectionPanelDisplaysConnectButton() {
        // Given: Connection panel is rendered
        composeTestRule.setContent {
            ConnectionPanel(
                profiles = emptyList(),
                sessions = emptyList(),
                onConnect = { _, _ -> },
                onDisconnect = { },
                onSaveProfile = { },
                onDeleteProfile = { },
                onCloneProfile = { it },
                onGetProfileSession = { null },
                onClose = { },
            )
        }

        // Then: Connect button should be displayed
        composeTestRule.onNodeWithText("Connect").assertExists()
    }

    @Test
    fun testConnectionPanelDisplaysDisconnectButton() {
        // Given: Connection panel is rendered
        composeTestRule.setContent {
            ConnectionPanel(
                profiles = emptyList(),
                sessions = emptyList(),
                onConnect = { _, _ -> },
                onDisconnect = { },
                onSaveProfile = { },
                onDeleteProfile = { },
                onCloneProfile = { it },
                onGetProfileSession = { null },
                onClose = { },
            )
        }

        // Then: Disconnect button should be displayed
        composeTestRule.onNodeWithText("Disconnect").assertExists()
    }

    @Test
    fun testConnectionPanelDisplaysProfileManagement() {
        // Given: Connection panel is rendered
        composeTestRule.setContent {
            ConnectionPanel(
                profiles = emptyList(),
                sessions = emptyList(),
                onConnect = { _, _ -> },
                onDisconnect = { },
                onSaveProfile = { },
                onDeleteProfile = { },
                onCloneProfile = { it },
                onGetProfileSession = { null },
                onClose = { },
            )
        }

        // Then: Profile management UI should be displayed
        composeTestRule.onNodeWithText("Connection Profile").assertExists()
        composeTestRule.onNodeWithText("Profile Name").assertExists()
        composeTestRule.onNodeWithContentDescription("Save Profile").assertExists()
    }

    @Test
    fun testConnectionPanelCloseButton() {
        // Given: Connection panel with close callback
        var closed = false
        composeTestRule.setContent {
            ConnectionPanel(
                profiles = emptyList(),
                sessions = emptyList(),
                onConnect = { _, _ -> },
                onDisconnect = { },
                onSaveProfile = { },
                onDeleteProfile = { },
                onCloneProfile = { it },
                onGetProfileSession = { null },
                onClose = { closed = true },
            )
        }

        // When: Close button is clicked
        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.waitForIdle()

        // Then: Close callback should be invoked
        assertTrue(closed, "Close callback should be invoked")
    }

    // ========================================
    // Message Detail Panel Display Tests
    // ========================================

    @Test
    fun testMessageDetailPanelDisplaysRawMessageHeader() {
        // Given: Message detail panel is rendered
        val dictionary = FixDictionary.createDefault()

        composeTestRule.setContent {
            MessageDetailPanel(
                message = null,
                dictionary = dictionary,
                onClose = { },
            )
        }

        // Then: Raw message header should be displayed
        composeTestRule.onNodeWithText("RAW MESSAGE").assertExists()
    }

    @Test
    fun testMessageDetailPanelCloseButton() {
        // Given: Message detail panel with close callback
        val dictionary = FixDictionary.createDefault()
        var closed = false

        composeTestRule.setContent {
            MessageDetailPanel(
                message = null,
                dictionary = dictionary,
                onClose = { closed = true },
            )
        }

        // When: Close button is clicked
        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.waitForIdle()

        // Then: Close callback should be invoked
        assertTrue(closed, "Close callback should be invoked")
    }

    // ========================================
    // Toolbar Action Buttons Tests
    // ========================================

    @Test
    fun testClearAllSessionsButtonExists() {
        // When: Toolbar is rendered with clear all callback
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                onClearAll = { },
            )
        }

        // Then: Clear all button should be displayed
        composeTestRule.onNodeWithContentDescription("Clear All").assertExists()
    }

    @Test
    fun testAddSeparatorButtonExists() {
        // When: Toolbar is rendered with add separator callback
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                onAddSeparatorToAll = { },
            )
        }

        // Then: Add separator button should be displayed
        composeTestRule.onNodeWithContentDescription("Blank Line").assertExists()
    }

    @Test
    fun testMessageDetailPanelToggleButton() {
        // Given: Toolbar with message detail panel callback
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                showDetailPanel = showDetailPanel,
                onToggleDetailPanel = { showDetailPanel = !showDetailPanel },
            )
        }

        // Verify initial state
        assertFalse(showDetailPanel, "Detail panel should be initially off")

        // When: Message detail panel button is clicked
        composeTestRule.onNodeWithContentDescription("Toggle Message Detail Panel").performClick()
        composeTestRule.waitForIdle()

        // Then: State should toggle
        assertTrue(showDetailPanel, "Detail panel should be shown after click")
    }
}
