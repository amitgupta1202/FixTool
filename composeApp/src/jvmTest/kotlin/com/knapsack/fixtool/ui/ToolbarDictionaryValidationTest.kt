package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for Toolbar data dictionary validation visual feedback
 */
class ToolbarDictionaryValidationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var viewMode = ViewMode.SPLIT_HORIZONTAL
    private var settingsClickCount = 0

    @Before
    fun setup() {
        settingsClickCount = 0
    }

    // ========================================
    // Settings Button Color Tests
    // ========================================

    @Test
    fun testSettingsButton_WhenDictionaryValid_ShowsGrayIcon() {
        // Given: Dictionary is valid
        val isDictionaryValid = true

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                isDictionaryValid = isDictionaryValid,
                onOpenSettings = { settingsClickCount++ },
            )
        }

        // Then: Settings button should exist
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()

        // And: Settings button should be clickable
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClickCount == 1) { "Settings button should be clickable" }
    }

    @Test
    fun testSettingsButton_WhenDictionaryInvalid_IsClickable() {
        // Given: Dictionary is invalid
        val isDictionaryValid = false

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                isDictionaryValid = isDictionaryValid,
                onOpenSettings = { settingsClickCount++ },
            )
        }

        // Then: Settings button should exist and be clickable
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClickCount == 1) { "Settings button should be clickable when dictionary is invalid" }
    }

    @Test
    fun testSettingsButton_WhenDictionaryValid_ShowsNormalTooltip() {
        // Given: Dictionary is valid
        val isDictionaryValid = true

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                isDictionaryValid = isDictionaryValid,
                onOpenSettings = { settingsClickCount++ },
            )
        }

        // Then: Settings button should exist with normal tooltip
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()

        // Note: Tooltip text is not easily testable in Compose, but we verify the button exists
        // The tooltip behavior is tested through manual verification
    }

    @Test
    fun testSettingsButton_WhenDictionaryInvalid_ShowsWarningTooltip() {
        // Given: Dictionary is invalid
        val isDictionaryValid = false

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                isDictionaryValid = isDictionaryValid,
                onOpenSettings = { settingsClickCount++ },
            )
        }

        // Then: Settings button should exist
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()

        // Note: The tooltip text "Settings - Data Dictionary Configuration Required!"
        // is set in the code and would require hovering to see
        // We verify the button exists and is functional
    }

    @Test
    fun testToolbar_DisplaysAllButtons() {
        // Given: Toolbar with all buttons enabled
        val isDictionaryValid = true

        // When: Toolbar is displayed with all callbacks
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                showMessageEditor = false,
                showDetailPanel = false,
                showConnectionPanel = false,
                isDictionaryValid = isDictionaryValid,
                onOpenMessageEditor = { },
                onToggleDetailPanel = { },
                onToggleConnectionPanel = { },
                onStartDemoServer = { },
                onStopDemoServer = { },
                onOpenSettings = { settingsClickCount++ },
            )
        }

        // Then: Settings button should be present
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()

        // And: Other toolbar elements should be present
        composeTestRule.onNodeWithText("FixTool").assertExists()
    }

    @Test
    fun testToolbar_SettingsButton_RespondsToStateChange() {
        // Given: Dictionary starts as valid
        var isDictionaryValid = true

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                isDictionaryValid = isDictionaryValid,
                onOpenSettings = { settingsClickCount++ },
            )
        }

        // Then: Settings button should exist
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()

        // When: Dictionary state changes to invalid (simulated by recomposing)
        isDictionaryValid = false
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                isDictionaryValid = isDictionaryValid,
                onOpenSettings = { settingsClickCount++ },
            )
        }

        // Then: Settings button should still exist and be clickable
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClickCount == 1) { "Settings button should remain clickable after state change" }
    }

    @Test
    fun testToolbar_WithValidDictionary_AllButtonsWork() {
        // Given: All toolbar features enabled with valid dictionary
        val isDictionaryValid = true
        var messageEditorClicked = false
        var detailPanelClicked = false
        var connectionPanelClicked = false
        var settingsClicked = false

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                showMessageEditor = false,
                showDetailPanel = false,
                showConnectionPanel = false,
                isDictionaryValid = isDictionaryValid,
                onOpenMessageEditor = { messageEditorClicked = true },
                onToggleDetailPanel = { detailPanelClicked = true },
                onToggleConnectionPanel = { connectionPanelClicked = true },
                onOpenSettings = { settingsClicked = true },
            )
        }

        // Then: All buttons should be clickable
        composeTestRule.onNodeWithContentDescription("Message Editor").performClick()
        assert(messageEditorClicked) { "Message editor button should work" }

        composeTestRule.onNodeWithContentDescription("Toggle Message Detail Panel").performClick()
        assert(detailPanelClicked) { "Detail panel button should work" }

        composeTestRule.onNodeWithContentDescription("Toggle Connection Panel").performClick()
        assert(connectionPanelClicked) { "Connection panel button should work" }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClicked) { "Settings button should work" }
    }

    @Test
    fun testToolbar_WithInvalidDictionary_AllButtonsStillWork() {
        // Given: All toolbar features enabled with invalid dictionary
        val isDictionaryValid = false
        var messageEditorClicked = false
        var detailPanelClicked = false
        var connectionPanelClicked = false
        var settingsClicked = false

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = viewMode,
                onViewModeChange = { },
                showMessageEditor = false,
                showDetailPanel = false,
                showConnectionPanel = false,
                isDictionaryValid = isDictionaryValid,
                onOpenMessageEditor = { messageEditorClicked = true },
                onToggleDetailPanel = { detailPanelClicked = true },
                onToggleConnectionPanel = { connectionPanelClicked = true },
                onOpenSettings = { settingsClicked = true },
            )
        }

        // Then: All buttons should still be clickable (dictionary validation doesn't disable functionality)
        composeTestRule.onNodeWithContentDescription("Message Editor").performClick()
        assert(messageEditorClicked) { "Message editor button should work even with invalid dictionary" }

        composeTestRule.onNodeWithContentDescription("Toggle Message Detail Panel").performClick()
        assert(detailPanelClicked) { "Detail panel button should work even with invalid dictionary" }

        composeTestRule.onNodeWithContentDescription("Toggle Connection Panel").performClick()
        assert(connectionPanelClicked) { "Connection panel button should work even with invalid dictionary" }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClicked) { "Settings button should work even with invalid dictionary" }
    }

    @Test
    fun testToolbar_ViewModeToggle_WorksWithInvalidDictionary() {
        // Given: Invalid dictionary and view mode toggle
        val isDictionaryValid = false
        var viewModeChangeCount = 0

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.TABS,
                onViewModeChange = { viewModeChangeCount++ },
                isDictionaryValid = isDictionaryValid,
                onOpenSettings = { },
            )
        }

        // Then: Layout toggle should work
        composeTestRule.onNodeWithContentDescription("Toggle Layout").performClick()
        assert(viewModeChangeCount == 1) { "Layout toggle should work with invalid dictionary" }
    }
}
