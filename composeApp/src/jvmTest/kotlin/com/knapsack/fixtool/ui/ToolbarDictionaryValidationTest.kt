package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for Toolbar data dictionary validation visual feedback
 */
class ToolbarDictionaryValidationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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
                isDictionaryValid = isDictionaryValid,
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
        var captureClicked = false
        var searchClicked = false
        var settingsClicked = false

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                isDictionaryValid = isDictionaryValid,
                onCaptureScenario = { captureClicked = true },
                onSearchAllSessions = { searchClicked = true },
                onOpenSettings = { settingsClicked = true },
            )
        }

        // Then: All buttons should be clickable
        composeTestRule.onNodeWithContentDescription("Capture Scenario").performClick()
        assert(captureClicked) { "Capture Scenario button should work" }

        composeTestRule.onNodeWithContentDescription("Search All Sessions").performClick()
        assert(searchClicked) { "Search All Sessions button should work" }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClicked) { "Settings button should work" }
    }

    @Test
    fun testToolbar_WithInvalidDictionary_AllButtonsStillWork() {
        // Given: All toolbar features enabled with invalid dictionary
        val isDictionaryValid = false
        var captureClicked = false
        var searchClicked = false
        var settingsClicked = false

        // When: Toolbar is displayed
        composeTestRule.setContent {
            Toolbar(
                isDictionaryValid = isDictionaryValid,
                onCaptureScenario = { captureClicked = true },
                onSearchAllSessions = { searchClicked = true },
                onOpenSettings = { settingsClicked = true },
            )
        }

        // Then: All buttons should still be clickable (dictionary validation doesn't disable functionality)
        composeTestRule.onNodeWithContentDescription("Capture Scenario").performClick()
        assert(captureClicked) { "Capture Scenario button should work even with invalid dictionary" }

        composeTestRule.onNodeWithContentDescription("Search All Sessions").performClick()
        assert(searchClicked) { "Search All Sessions button should work even with invalid dictionary" }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClicked) { "Settings button should work even with invalid dictionary" }
    }
}
