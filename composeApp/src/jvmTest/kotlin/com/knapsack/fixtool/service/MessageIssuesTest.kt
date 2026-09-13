package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every problem in a message, each on the row it sits on.**
 *
 * The count this feeds used to be QuickFIX/J's first exception, so a message with three wrong fields read as
 * "1 error". These pin that each problem is found on its own, in QuickFIX/J's own terms, and that the two
 * kinds of line the editor already reports (a refusal's headline, a warning) are read as what they are.
 */
class MessageIssuesTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun fields(vararg pairs: Pair<Int, String>): List<MessageIssues.Field> =
        pairs.mapIndexed { row, (tag, value) -> MessageIssues.Field(row, tag, value) }

    /** A NewOrderSingle FIX 4.4 accepts, so each test below can break exactly one thing. */
    private val order =
        arrayOf(
            35 to "D",
            11 to "ORD-1",
            55 to "EUR/USD",
            54 to "1",
            60 to "20260913-10:00:00.000",
            38 to "1000000",
            40 to "2",
            44 to "1.0921",
        )

    private fun orderWith(vararg changes: Pair<Int, String?>): List<MessageIssues.Field> {
        val changed = changes.toMap()
        val kept = order.mapNotNull { (tag, value) -> if (tag in changed) changed[tag]?.let { tag to it } else tag to value }
        val added = changes.filter { (tag, value) -> value != null && order.none { it.first == tag } }.map { it.first to it.second!! }
        return fields(*(kept + added).toTypedArray())
    }

    @Test
    fun `a message the dictionary accepts has nothing wrong with it`() {
        assertEquals(emptyList(), MessageIssues.check(fields(*order), dictionary))
    }

    /** The defect: three wrong fields were one exception, and so one error. */
    @Test
    fun `three problems are three issues, each on its own row`() {
        val issues = MessageIssues.check(orderWith(54 to "X", 38 to "1,000", 55 to null), dictionary)

        assertEquals(3, issues.size, "got $issues")
        assertTrue(issues.all { it.severity == MessageIssue.Severity.ERROR }, "got $issues")
        assertEquals(setOf(54, 38, 55), issues.map { it.tag }.toSet())
        assertEquals(2, issues.first { it.tag == 54 }.row, "with Symbol gone, Side is the third row")
        assertTrue(issues.first { it.tag == 55 }.missing, "a missing field has no row, only one to add")
        val side = issues.first { it.tag == 54 }.text
        assertTrue("“X”" in side && "1 BUY" in side, side)
        assertTrue("quantity" in issues.first { it.tag == 38 }.text, issues.first { it.tag == 38 }.text)
    }

    /**
     * **An unknown tag is a warning, and it is said once.** The message still goes, as a top-level field the
     * counterparty may reject, which is what the scenario editor's lint has always called a warning. QuickFIX/J
     * refuses the same message for it, and that refusal names the tag already named, so it is not added again.
     */
    @Test
    fun `a tag the message type does not define is one warning, not a warning and an error`() {
        val issues = MessageIssues.check(orderWith(9303 to "DESK7"), dictionary)

        assertEquals(1, issues.size, "got $issues")
        assertEquals(MessageIssue.Severity.WARNING, issues.single().severity)
        assertEquals(9303, issues.single().tag)
    }

    /** A value written by an expression is judged by what it resolves to, which is not known until send. */
    @Test
    fun `a value still to be written by an expression is not judged`() {
        assertEquals(emptyList(), MessageIssues.check(orderWith(38 to "\${qty}", 60 to "\${utcnow}"), dictionary))
    }

    @Test
    fun `errors come before warnings`() {
        val issues = MessageIssues.check(orderWith(9303 to "DESK7", 54 to "X"), dictionary)

        assertEquals(listOf(MessageIssue.Severity.ERROR, MessageIssue.Severity.WARNING), issues.map { it.severity })
    }

    @Test
    fun `no dictionary checks nothing`() {
        assertEquals(emptyList(), MessageIssues.check(orderWith(54 to "X"), null))
        assertEquals(emptyList(), MessageIssues.check(orderWith(54 to "X"), FixDictionaryAdapter.createDefault()))
    }

    /**
     * **The format rule is QuickFIX/J's, reached by reflection, and this is what notices if it moves.** Without
     * it the format check would go quiet on an upgrade and every other test here would still pass.
     */
    @Test
    fun `QuickFIX-J's format rule is reachable`() {
        assertTrue(MessageIssues.formatRuleAvailable, "DataDictionary.checkValidFormat(StringField) has moved")
        assertEquals(listOf(44), MessageIssues.check(orderWith(44 to "cheap"), dictionary).map { it.tag })
    }

    /**
     * **A refusal's lines, as issues.** Its first line is the headline and not one of the problems; a
     * `WARNING:` is a warning whatever else is in the list; `Field 11:` is about tag 11.
     */
    @Test
    fun `the lines an action reports are read as a headline and its issues`() {
        val report =
            MessageIssues.fromLines(
                listOf(
                    "❌ Cannot send message - Fix template expression errors:",
                    "Field 11: Unknown function 'uuidd'",
                    "WARNING: sent, but the venue has no session",
                ),
            )

        assertEquals("Cannot send message: Fix template expression errors", report.headline)
        assertEquals(2, report.issues.size, "the headline is not counted")
        assertEquals(1, report.errors)
        assertEquals(1, report.warnings)
        assertEquals(11, report.issues.first { it.severity == MessageIssue.Severity.ERROR }.tag)
        assertEquals("Unknown function 'uuidd'", report.issues.first { it.tag == 11 }.text)
    }
}
