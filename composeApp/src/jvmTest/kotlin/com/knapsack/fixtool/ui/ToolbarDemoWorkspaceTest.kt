package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The demo workspace's home in the toolbar: a trailing item in Quick Connect ▾.
 *
 * The cases that matter are the fresh install (no profiles, so the menu must still show and must
 * offer Start), the installed state (Stop, and no Start), and that the Start item asks for a FIX
 * version inside the same popup rather than firing on its own.
 */
class ToolbarDemoWorkspaceTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `quick connect shows on a fresh install and starts the demo at the picked version`() {
        var started: FixVersion? = null
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                connectionProfiles = emptyList(),
                onQuickConnect = { _, _ -> },
                onStartDemoWorkspace = { started = it },
                onStopDemoWorkspace = { },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithText("No saved profiles").assertExists()
        rule.onNodeWithTag("demo-stop").assertDoesNotExist()

        rule.onNodeWithTag("demo-start").performClick()
        assertNull(started, "the Start item asks for a version; it must not start on its own")

        rule.onNodeWithTag("demo-version-${FixVersion.FIX_4_2.name}").performClick()
        assertEquals(FixVersion.FIX_4_2, started)
        rule.onNodeWithTag("demo-version-back").assertDoesNotExist()
    }

    @Test
    fun `the default version is marked and back returns to the profile list`() {
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
                onStartDemoWorkspace = { },
                onStopDemoWorkspace = { },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("demo-start").performClick()
        rule.onNodeWithText("${FixVersion.DEFAULT.displayName}  (default)").assertExists()
        rule.onNodeWithText("No saved profiles").assertDoesNotExist()

        rule.onNodeWithTag("demo-version-back").performClick()
        rule.onNodeWithText("No saved profiles").assertExists()
        rule.onNodeWithTag("demo-start").assertExists()
    }

    @Test
    fun `an installed workspace offers stop and not start`() {
        var stopped = false
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
                demoWorkspaceInstalled = true,
                onStartDemoWorkspace = { },
                onStopDemoWorkspace = { stopped = true },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("demo-start").assertDoesNotExist()
        rule.onNodeWithTag("demo-stop").performClick()
        assertTrue(stopped)
    }

    @Test
    fun `without the demo callbacks an empty profile list still hides quick connect`() {
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
            )
        }

        rule.onNodeWithTag("quick-connect").assertDoesNotExist()
    }
}
