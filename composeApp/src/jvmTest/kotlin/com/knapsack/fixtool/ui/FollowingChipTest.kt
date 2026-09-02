package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The chip is the whole filter, so it has to *say* what it is filtering to.
 *
 * A narrowing nobody can name is the defect this feature replaces — a regex typed into four boxes,
 * silently dropping the leg it did not know to match. These pin that the chip appears only while
 * following, names the trace and its counts, says which pane lost history, and can be dismissed.
 */
class FollowingChipTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `no chip when nothing is followed`() {
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
            )
        }

        composeTestRule.onNodeWithTag("following-chip").assertDoesNotExist()
    }

    @Test
    fun `the chip names the trace, its sessions and its messages`() {
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                followingLabel = "RFQ-A1",
                followingSessionCount = 4,
                followingMessageCount = 14,
            )
        }

        composeTestRule
            .onNodeWithTag("following-chip-label")
            .assertTextContains("Following RFQ-A1 · 4 sessions · 14 messages", substring = true)
    }

    @Test
    fun `a head-truncated trace says which pane lost history`() {
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                followingLabel = "RFQ-A1",
                followingSessionCount = 1,
                followingMessageCount = 1,
                followingTruncatedOn = listOf("LP-1", "LP-2"),
            )
        }

        composeTestRule
            .onNodeWithTag("following-chip-label")
            .assertTextContains("· history lost on LP-1, LP-2", substring = true)
    }

    @Test
    fun `the chip's cross unfollows`() {
        var unfollowed = 0
        composeTestRule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                followingLabel = "RFQ-A1",
                followingSessionCount = 2,
                followingMessageCount = 5,
                onUnfollow = { unfollowed++ },
            )
        }

        composeTestRule.onNodeWithTag("unfollow-chip").performClick()
        assertEquals(1, unfollowed)
    }
}
