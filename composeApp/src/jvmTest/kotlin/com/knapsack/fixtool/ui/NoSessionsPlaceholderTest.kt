package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/** The empty session area is where a fresh install is told where the bundled examples are. */
class NoSessionsPlaceholderTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * The examples are folders where Open starts, so the empty state points there and has no button of
     * its own for any of them. It had one each, and at five they no longer fit in the row.
     */
    @Test
    fun `a fresh install is pointed at the examples, and offered Open workspace and the connection panel`() {
        var browsed = false
        var opened = false
        rule.setContent {
            NoSessionsPlaceholder(
                onOpenWorkspace = { browsed = true },
                onOpenConnectionPanel = { opened = true },
            )
        }

        rule.onNodeWithText("No active sessions").assertExists()
        rule.onNodeWithText("bundled examples are already folders", substring = true).assertExists()
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
        rule.onNodeWithTag("empty-open-workspace").performClick()
        rule.onNodeWithTag("empty-open-connection").performClick()
        assertTrue(browsed)
        assertTrue(opened)
    }

    @Test
    fun `saved profiles mean reconnecting, and the examples go unmentioned`() {
        rule.setContent {
            NoSessionsPlaceholder(
                hasProfiles = true,
                onOpenWorkspace = { },
                onOpenConnectionPanel = { },
            )
        }

        rule.onNodeWithText("bundled examples", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("empty-open-connection").assertExists()
    }
}
