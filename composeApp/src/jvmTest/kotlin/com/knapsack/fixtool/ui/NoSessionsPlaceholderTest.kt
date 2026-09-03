package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/** The empty session area is where a fresh install meets the demo. */
class NoSessionsPlaceholderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a fresh install is offered the demo and the connection panel`() {
        var started = false
        var opened = false
        rule.setContent {
            NoSessionsPlaceholder(
                onStartDemoWorkspace = { started = true },
                onOpenConnectionPanel = { opened = true },
            )
        }

        rule.onNodeWithText("No active sessions").assertExists()
        rule.onNodeWithTag("empty-start-demo").performClick()
        rule.onNodeWithTag("empty-open-connection").performClick()
        assertTrue(started)
        assertTrue(opened)
    }

    @Test
    fun `an installed workspace withdraws the start button`() {
        rule.setContent {
            NoSessionsPlaceholder(
                demoWorkspaceInstalled = true,
                onStartDemoWorkspace = { },
                onOpenConnectionPanel = { },
            )
        }

        rule.onNodeWithTag("empty-start-demo").assertDoesNotExist()
        rule.onNodeWithTag("empty-open-connection").assertExists()
    }
}
