package com.knapsack.fixtool.ui

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * **The retired phrasings, and a test that fails when one comes back.**
 *
 * The audit behind `docs/mockups/pane-grammar.html` found twelve phrasings for closing something, seven for
 * clearing something, four words for a blank line, four vocabularies for connecting, and Title Case in most
 * tooltips beside sentence case in the newer ones. Fixing that was a sweep of every file in `ui/`. A sweep
 * of that size does not stay fixed on its own: the next feature writes the tooltip that reads best to
 * whoever is writing it, which is exactly how there came to be four words for a blank line.
 *
 * So this greps the UI sources. It is a cheap test for a thing no reviewer reliably catches, and it is what
 * makes the sweep worth doing rather than worth doing twice.
 *
 * **Comments are exempt, and deliberately.** Half of what this file forbids is quoted in the KDoc of the
 * thing that replaced it — "the same button was 'Add Separator' in tabs and 'Add Blank Line' in split" is
 * the sentence that explains why `PaneHeader` exists. A rule that could not say what it retired would cost
 * the codebase its own history.
 */
class UiStringsTest {
    private val uiSources = File("src/jvmMain/kotlin/com/knapsack/fixtool/ui")

    /**
     * What each retired phrasing was replaced by — the note's section 6 table, as a map, so a failure says
     * what to write instead rather than only that something is wrong.
     */
    private val retired =
        mapOf(
            // Blank line: four words for one action.
            "Add Separator" to "Add blank line",
            "Add Blank Line" to "Add blank line",
            "Add Blank Line to All Sessions" to "Add blank line to all panes",
            // Clear: seven phrasings, one verb.
            "Clear All Messages" to "Clear messages",
            "Clear All Sessions" to "Clear all panes",
            "Clear Statistics" to "Clear statistics",
            "Clear All Fields" to "Clear fields",
            "Clear this book" to "Clear book",
            "Clear Selection" to "Clear selection · Esc",
            // Hide for a tool window, Close for content, Dismiss for a report.
            "Close Detail Panel" to "Hide Detail",
            "Close Connection Panel" to "Hide Connection",
            "Close Panel" to "Hide <the dock's noun>",
            // Connect: an initiator connects, an acceptor listens.
            "Connect Session" to "Connect",
            "Disconnect Session" to "Disconnect",
            "Start Listening" to "Start listening",
            "Stop Listening" to "Stop listening",
            // Expand: the object only when two things could expand.
            "Expand All Groups" to "Expand all",
            "Collapse All Groups" to "Collapse all",
            // One overflow glyph, one word.
            "More Options" to "More",
            "More actions" to "More",
            // Run again, in all four places.
            "Run set again" to "Run again",
            "Run this set again" to "Run again",
            "Run this plan again" to "Run again",
            "Re-run as it ran" to "Run again",
            // Search: ⌘F is the pane, ⌘⇧F is every session.
            "Show Search" to "Search in pane",
            "Hide Search" to "Search in pane",
            "Search All Sessions" to "Search all sessions",
            "Open in Search Window" to "Pin results",
            // A toggle says on by looking on, so its tooltip names the thing.
            "click to hide" to "the noun alone — the pressed look carries the state",
            "click to show" to "the noun alone — the pressed look carries the state",
            "Hide Group Indentation" to "Group indentation",
            "Show Group Indentation" to "Group indentation",
            "Hide Description column" to "Description column",
            "Show Description column" to "Description column",
            "Hide Advanced Settings" to "Advanced settings",
            "Show Advanced Settings" to "Advanced settings",
            "Toggle Filter" to "Filter pane",
            "Toggle Search" to "Search in pane",
            "Toggle Text Wrap" to "Wrap lines",
            "Toggle Indentation" to "Group indentation",
            "Toggle Description" to "Description column",
            // One name per component, and it is the one its stripe tab says.
            "Message Editor" to "Editor",
            "Message Details" to "Detail",
            "FIX Connection" to "Connection",
            "Latency Tracking" to "Latency",
            "Latency Stats" to "Latency",
            "Repeatable Scenarios" to "Scenarios",
            "Help & Documentation" to "Help",
            // The pane vocabulary, in both layouts.
            "Minimize Pane" to "Minimize",
            "Move Session Left" to "Move left",
            "Move Session Right" to "Move right",
            "Close Session" to "Close session",
            "Scroll to Bottom" to "Scroll to bottom",
        )

    /**
     * Every line of every UI source that is code rather than commentary.
     *
     * Block comments, line comments and KDoc continuation lines are dropped, because the retired strings
     * are quoted in the documentation of what replaced them — on purpose, and this test must not make that
     * impossible to write.
     */
    private fun codeLines(source: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlock = false
        source.lines().forEachIndexed { i, line ->
            val trimmed = line.trim()
            when {
                inBlock -> if (trimmed.contains("*/")) inBlock = false
                trimmed.startsWith("/*") -> if (!trimmed.contains("*/")) inBlock = true
                trimmed.startsWith("//") || trimmed.startsWith("*") -> Unit
                else -> out += (i + 1) to line
            }
        }
        return out
    }

    private val literal = Regex("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"")

    @Test
    fun `no UI source says a retired phrasing`() {
        val offences = mutableListOf<String>()
        uiSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            codeLines(file.readText()).forEach { (number, line) ->
                literal.findAll(line).forEach { match ->
                    val text = match.groupValues[1]
                    retired.forEach { (old, instead) ->
                        if (old in text) offences += "${file.name}:$number  \"$old\" → say \"$instead\""
                    }
                }
            }
        }

        assertTrue(
            offences.isEmpty(),
            "the vocabulary sweep has been undone in ${offences.size} place(s):\n" + offences.joinToString("\n"),
        )
    }

    /**
     * **The sources are where this test says they are.**
     *
     * A grep test that greps nothing passes, silently and forever, and the day somebody moves the UI
     * package it would go on passing while guarding an empty directory. This is the assertion that makes
     * the one above mean something.
     */
    @Test
    fun `the sweep actually has sources to sweep`() {
        val files = uiSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            files.size > 40,
            "found only ${files.size} UI sources under ${uiSources.absolutePath} — the retired-phrasings " +
                "test is guarding nothing",
        )
    }
}
