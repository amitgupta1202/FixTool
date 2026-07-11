package com.knapsack.fixtool.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.TagResult
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.BeginString
import quickfix.field.MsgSeqNum
import quickfix.field.MsgType
import quickfix.field.SenderCompID
import quickfix.field.SendingTime
import quickfix.field.TargetCompID
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO

/**
 * The assertion-failure rendering in the message detail panel: a verdict banner leads (what failed,
 * how many), failed rows are red with a full expected-vs-actual line (with the actual value's
 * dictionary meaning), and passed rows stay subtle so failures dominate visually.
 */
class AssertionResultsUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile

    private fun execReport(): FixMessage {
        val message = Message()
        message.header.setString(BeginString.FIELD, "FIX.4.4")
        message.header.setString(MsgType.FIELD, "8")
        message.header.setString(SenderCompID.FIELD, "SERVER")
        message.header.setString(TargetCompID.FIELD, "CLIENT")
        message.header.setInt(MsgSeqNum.FIELD, 2)
        message.header.setString(SendingTime.FIELD, "20260711-12:00:00")
        message.setString(11, "ORD-1") // ClOrdID
        message.setString(150, "0") // ExecType = NEW (enum -> description shown in failure line)
        message.setString(44, "1.0851") // Price
        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = "8=FIX.4.4|9=100|35=8|49=SERVER|56=CLIENT|34=2|52=20260711-12:00:00|11=ORD-1|150=0|44=1.0851|10=000|",
            messageType = "8",
            quickfixMessage = message,
        )
    }

    @Test
    fun `failure banner leads and failed rows spell out expected vs actual`() {
        val tagResults = mapOf(
            11 to TagResult(11, "reference", "\${id0}", "ORD-1", passed = true),
            44 to TagResult(44, "numeric", "≈ 1.0850", "1.0851", passed = false),
            150 to TagResult(150, "exact", "= 2", "0", passed = false),
        )
        composeTestRule.setContent {
            MessageDetailPanel(
                message = execReport(),
                dictionary = FixDictionary.createDefault(),
                onClose = {},
                tagResults = tagResults,
            )
        }
        // The verdict is announced before the tag list...
        composeTestRule.onNodeWithText("✗ Scenario assertion failed — 2 of 3 checked tags").assertExists()
        // ...and a failed row carries a readable expected-vs-actual line (enum meaning when known).
        composeTestRule.onNodeWithText("expected = 2 · actual 0", substring = true).assertExists()
        snapshot("assertion_failure_detail.png")
    }

    @Test
    fun `all-green run is a single quiet banner`() {
        val tagResults = mapOf(
            11 to TagResult(11, "reference", "\${id0}", "ORD-1", passed = true),
            44 to TagResult(44, "numeric", "≈ 1.0851", "1.0851", passed = true),
        )
        composeTestRule.setContent {
            MessageDetailPanel(
                message = execReport(),
                dictionary = FixDictionary.createDefault(),
                onClose = {},
                tagResults = tagResults,
            )
        }
        composeTestRule.onNodeWithText("✓ Scenario assertion passed — all 2 checked tags matched").assertExists()
        snapshot("assertion_pass_detail.png")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[AssertionResultsUiTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
