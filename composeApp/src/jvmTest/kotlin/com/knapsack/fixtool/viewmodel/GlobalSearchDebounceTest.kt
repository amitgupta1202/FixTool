package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgSeqNum
import quickfix.field.MsgType
import quickfix.field.SenderCompID
import quickfix.field.TargetCompID
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Global search is debounced and scans off the UI thread.
 *
 * It used to run inline on the EDT on every keystroke — every session x up to 1000 messages, a
 * display string built per message, a regex match, then a three-key sort of the hits. Typing in the
 * search box froze the window.
 */
class GlobalSearchDebounceTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-debounce-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun addMessage(session: FixMessageSession, seqNum: Int, raw: String) {
        val message =
            Message().apply {
                header.setField(MsgType("D"))
                header.setField(MsgSeqNum(seqNum))
                header.setField(SenderCompID("SENDER"))
                header.setField(TargetCompID("TARGET"))
            }
        session.addMessage(
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = raw,
                messageType = "D",
                quickfixMessage = message,
            ),
        )
    }

    private suspend fun awaitGlobalSearch() = delay(GLOBAL_SEARCH_DEBOUNCE_MS + SETTLE_MARGIN_MS)

    /**
     * The scan is not run on the caller's thread. Immediately after setting a query that certainly
     * matches, results must not yet be present — they arrive only after the debounce elapses.
     */
    @Test
    fun `setting a query does not scan synchronously`() =
        runBlocking {
            val session = viewModel.createSessionForTest("S1")
            addMessage(session, 1, "8=FIX.4.2|35=D|11=FINDME|")
            delay(INGEST_SETTLE_MS)

            viewModel.setGlobalSearchQuery("FINDME")

            // Same turn: the debounce has not elapsed, so nothing has been scanned yet.
            assertTrue(
                viewModel.globalSearchResults.value.isEmpty(),
                "results appeared synchronously — the scan is running on the caller's thread again",
            )

            awaitGlobalSearch()
            assertEquals(1, viewModel.globalSearchResults.value.size, "results should arrive after the debounce")
        }

    /**
     * Rapid typing collapses to a single scan of the final query. `mapLatest` cancels a scan in
     * flight when the next keystroke lands, so intermediate queries never publish.
     */
    @Test
    fun `rapid typing yields only the final query's results`() =
        runBlocking {
            val session = viewModel.createSessionForTest("S1")
            addMessage(session, 1, "8=FIX.4.2|35=D|11=ALPHA|")
            addMessage(session, 2, "8=FIX.4.2|35=D|11=BETA|")
            delay(INGEST_SETTLE_MS)

            // Type "BETA" one character at a time, faster than the debounce window.
            listOf("B", "BE", "BET", "BETA").forEach { partial ->
                viewModel.setGlobalSearchQuery(partial)
                delay(TYPING_INTERVAL_MS)
            }
            awaitGlobalSearch()

            val results = viewModel.globalSearchResults.value
            assertEquals(1, results.size, "expected only the final query's single match")
            assertTrue(
                results.single().message.rawMessage.contains("BETA"),
                "results are from an intermediate query, not the final one",
            )
        }

    /** Clearing the box empties results immediately — there is nothing to compute. */
    @Test
    fun `blank query clears without waiting for the debounce`() =
        runBlocking {
            val session = viewModel.createSessionForTest("S1")
            addMessage(session, 1, "8=FIX.4.2|35=D|11=FINDME|")
            delay(INGEST_SETTLE_MS)

            viewModel.setGlobalSearchQuery("FINDME")
            awaitGlobalSearch()
            assertEquals(1, viewModel.globalSearchResults.value.size, "precondition: a result was found")

            viewModel.setGlobalSearchQuery("")
            assertTrue(
                viewModel.globalSearchResults.value.isEmpty(),
                "clearing the query should empty results on the spot",
            )
        }

    private companion object {
        const val INGEST_SETTLE_MS = 300L
        const val SETTLE_MARGIN_MS = 400L
        const val TYPING_INTERVAL_MS = 30L
    }
}
