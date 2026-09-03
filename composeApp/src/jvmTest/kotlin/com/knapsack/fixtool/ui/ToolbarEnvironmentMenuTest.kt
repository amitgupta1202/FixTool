package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.Environment
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Picking a profile asks which environment, but only where there are any.
 *
 * The additive claim under test: a workspace that has never extracted an environment must behave
 * exactly as it did — click a profile, it connects — because that is every workspace until someone
 * decides otherwise.
 */
class ToolbarEnvironmentMenuTest {
    @get:Rule
    val rule = createComposeRule()

    private val buySide =
        FixConnectionProfile(
            id = "p1",
            name = "BuySide",
            config = FixConnectionConfig(senderCompID = "BUY", targetCompID = "VENUE", host = "saved.host"),
        )

    private val environments = listOf(Environment("UAT1", host = "uat.host"), Environment("QA1", host = "qa.host"))

    @Test
    fun `with no environments a profile connects on the first click`() {
        var connected: String? = null
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                connectionProfiles = listOf(buySide),
                onQuickConnect = { id, _ -> connected = id },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("quick-connect-BuySide").performClick()
        assertEquals("p1", connected)
    }

    @Test
    fun `with environments a profile asks which one, and connecting names both`() {
        var connectedIn: Pair<String, String>? = null
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                connectionProfiles = listOf(buySide),
                onQuickConnect = { _, _ -> },
                environments = environments,
                onConnectProfileIn = { profile, environment -> connectedIn = profile.name to environment.name },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("quick-connect-BuySide").performClick()
        assertNull(connectedIn, "picking a profile asks for an environment; it must not connect on its own")

        rule.onNodeWithText("BuySide in…").assertExists()
        rule.onNodeWithTag("environment-UAT1").performClick()
        assertEquals("BuySide" to "UAT1", connectedIn)
    }

    @Test
    fun `the endpoint the profile already names is still offered`() {
        var connectedAsSaved: String? = null
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                connectionProfiles = listOf(buySide),
                onQuickConnect = { id, _ -> connectedAsSaved = id },
                environments = environments,
                onConnectProfileIn = { _, _ -> },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("quick-connect-BuySide").performClick()
        rule.onNodeWithText("As saved (saved.host)").assertExists()
        rule.onNodeWithTag("environment-as-saved").performClick()
        assertEquals("p1", connectedAsSaved)
    }

    @Test
    fun `back returns to the profile list`() {
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                connectionProfiles = listOf(buySide),
                onQuickConnect = { _, _ -> },
                environments = environments,
                onConnectProfileIn = { _, _ -> },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("quick-connect-BuySide").performClick()
        rule.onNodeWithTag("environment-back").performClick()
        rule.onNodeWithTag("quick-connect-BuySide").assertExists()
    }
}
