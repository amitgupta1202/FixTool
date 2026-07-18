package com.knapsack.fixtool.ui

import com.knapsack.fixtool.service.ScenarioAnnotations
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A glyph is learnable only once somebody tells you — so the badge says it in words, and says it as the
 * **cross-reference**: which other step is the counterpart. These tests pin the wording, because the
 * wording is the whole feature; a tooltip that restates the glyph ("this step mints it") is the thing
 * this replaced.
 */
class VarBadgeTooltipTest {
    private fun sites(
        mintedAt: List<Int> = emptyList(),
        capturedAt: List<Int> = emptyList(),
        referencedAt: List<Int> = emptyList(),
        allWritesMuted: Boolean = false,
    ) = ScenarioAnnotations.VarSites(mintedAt, capturedAt, referencedAt, allWritesMuted)

    /** Steps are cited 1-based, as the rows are numbered on screen — never the 0-based index. */
    @Test
    fun `a mint names its readers, in the numbering the rows wear`() {
        val text = varBadgeTooltip("id0", VarRole.MINT, sites(mintedAt = listOf(0), referencedAt = listOf(1, 2)))
        assertEquals(
            "Mints \${id0} — this step chooses the value and puts it on the wire. Referenced by steps 2, 3.",
            text,
        )
    }

    @Test
    fun `a write nothing reads yet says so, rather than trailing an empty list`() {
        val text = varBadgeTooltip("id0", VarRole.MINT, sites(mintedAt = listOf(0)))
        assertTrue(text.endsWith("Nothing references it yet."), text)
    }

    /**
     * The whole point of splitting ● from ↧: a captured value is the *venue's*. A reader who does not
     * know that will hunt for the bug in the scenario when the venue is what changed.
     */
    @Test
    fun `a capture says the value is the venue's`() {
        val text = varBadgeTooltip("qr", VarRole.CAPTURE, sites(capturedAt = listOf(1), referencedAt = listOf(2)))
        assertEquals(
            "Captures \${qr} — the venue chose this value, and this step reads it off the reply. Referenced by step 3.",
            text,
        )
    }

    /**
     * The reference's tooltip is the delete/mute hazard, which is the reason it exists: the badge on
     * step 3 cannot otherwise tell you that step 1 is load-bearing.
     */
    @Test
    fun `a reference names its mint and the hazard of removing it`() {
        val text = varBadgeTooltip("id0", VarRole.REFERENCE, sites(mintedAt = listOf(0), referencedAt = listOf(2)))
        assertEquals(
            "References \${id0}, minted at step 1. Delete or mute that step and this reference ships the literal \${id0} on the wire.",
            text,
        )
    }

    /** Naming the act, not just the step: a reference to a captured value points at a capture. */
    @Test
    fun `a reference to a captured value says captured, not minted`() {
        val text = varBadgeTooltip("qr", VarRole.REFERENCE, sites(capturedAt = listOf(1), referencedAt = listOf(2)))
        assertTrue(text.startsWith("References \${qr}, captured at step 2."), text)
    }

    /** Written both ways, the tooltip stops guessing a verb and says the neutral one. */
    @Test
    fun `a reference to a name written both ways says written`() {
        val text = varBadgeTooltip("id0", VarRole.REFERENCE, sites(mintedAt = listOf(2), capturedAt = listOf(0), referencedAt = listOf(3)))
        assertTrue(text.startsWith("References \${id0}, written at steps 1, 3."), text)
    }

    /** Parked writes do not run — so the reference is already broken, and the wording is present-tense. */
    @Test
    fun `a reference whose only write is muted is told the write never runs`() {
        val text = varBadgeTooltip("id0", VarRole.REFERENCE, sites(mintedAt = listOf(0), referencedAt = listOf(1), allWritesMuted = true))
        assertTrue(text.contains("the step that writes it (step 1) is muted"), text)
        assertTrue(text.contains("ships the literal \${id0} on the wire"), text)
    }

    /** The same leaves-a-literal sentence the variables strip warns with, on the badge that caused it. */
    @Test
    fun `a reference nothing writes is the never-written warning`() {
        val text = varBadgeTooltip("idO", VarRole.REFERENCE, sites(referencedAt = listOf(0)))
        assertEquals(
            "References \${idO}, which no step writes — the engine leaves the literal \${idO} on the wire.",
            text,
        )
    }
}
