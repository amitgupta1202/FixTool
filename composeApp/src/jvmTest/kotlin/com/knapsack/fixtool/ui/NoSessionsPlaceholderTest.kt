package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The empty session area is where a fresh install meets the bundled example. */
class NoSessionsPlaceholderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a fresh install is offered the bundled example and the connection panel`() {
        val started = mutableListOf<String>()
        var opened = false
        rule.setContent {
            NoSessionsPlaceholder(
                examples =
                    listOf(
                        ExampleEntry("fx-venue", "FX Venue", "bundled"),
                        ExampleEntry("rfq-venue", "RFQ Venue", "bundled"),
                    ),
                onOpenExample = { started += it },
                onOpenConnectionPanel = { opened = true },
            )
        }

        rule.onNodeWithText("No active sessions").assertExists()
        rule.onNodeWithText("Open FX Venue example").assertExists()
        rule.onNodeWithTag("empty-open-example-fx-venue").performClick()
        rule.onNodeWithTag("empty-open-example-rfq-venue").performClick()
        rule.onNodeWithTag("empty-open-connection").performClick()
        assertEquals(listOf("fx-venue", "rfq-venue"), started)
        assertTrue(opened)
    }

    @Test
    fun `saved profiles withdraw the example button`() {
        rule.setContent {
            NoSessionsPlaceholder(
                hasProfiles = true,
                examples = listOf(ExampleEntry("fx-venue", "FX Venue", "bundled")),
                onOpenExample = { },
                onOpenConnectionPanel = { },
            )
        }

        rule.onNodeWithTag("empty-open-example-fx-venue").assertDoesNotExist()
        rule.onNodeWithTag("empty-open-connection").assertExists()
    }
}
