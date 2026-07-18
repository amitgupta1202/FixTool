package com.knapsack.fixtool.ui

import com.knapsack.fixtool.service.ScenarioAnnotations
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ●/○ glyph is learnable only once somebody tells you — so the badge says it in words, and says it
 * as the **cross-reference**: which other step is the counterpart. These tests pin the wording, because
 * the wording is the whole feature; a tooltip that restates the glyph ("this step mints it") is the
 * thing this replaced.
 */
class VarBadgeTooltipTest {
    private fun sites(
        mintedAt: List<Int> = emptyList(),
        referencedAt: List<Int> = emptyList(),
        mintedFromReply: Boolean = false,
        allMintsMuted: Boolean = false,
    ) = ScenarioAnnotations.VarSites(mintedAt, referencedAt, mintedFromReply, allMintsMuted)

    /** Steps are cited 1-based, as the rows are numbered on screen — never the 0-based index. */
    @Test
    fun `a mint names its readers, in the numbering the rows wear`() {
        val text = varBadgeTooltip("id0", minted = true, sites = sites(mintedAt = listOf(0), referencedAt = listOf(1, 2)))
        assertEquals(
            "Mints \${id0} — the value is captured here and put on the wire. Referenced by steps 2, 3.",
            text,
        )
    }

    @Test
    fun `a mint nothing reads yet says so, rather than trailing an empty list`() {
        val text = varBadgeTooltip("id0", minted = true, sites = sites(mintedAt = listOf(0)))
        assertTrue(text.endsWith("Nothing references it yet."), text)
    }

    /** A bindAs mint reads off the venue's reply; a Send mint puts the value on the wire. Different sentences. */
    @Test
    fun `a reply-side mint is described as a capture off the incoming message`() {
        val text = varBadgeTooltip("qr", minted = true, sites = sites(mintedAt = listOf(1), mintedFromReply = true, referencedAt = listOf(2)))
        assertTrue(text.startsWith("Mints \${qr} from the venue's reply"), text)
    }

    /**
     * The reference's tooltip is the delete/mute hazard, which is the reason it exists: the badge on
     * step 3 cannot otherwise tell you that step 1 is load-bearing.
     */
    @Test
    fun `a reference names its mint and the hazard of removing it`() {
        val text = varBadgeTooltip("id0", minted = false, sites = sites(mintedAt = listOf(0), referencedAt = listOf(2)))
        assertEquals(
            "References \${id0}, minted at step 1. Delete or mute that step and this reference ships the literal \${id0} on the wire.",
            text,
        )
    }

    /** Parked mints do not run — so the reference is already broken, and the wording is present-tense. */
    @Test
    fun `a reference whose only mint is muted is told the mint never runs`() {
        val text = varBadgeTooltip("id0", minted = false, sites = sites(mintedAt = listOf(0), referencedAt = listOf(1), allMintsMuted = true))
        assertTrue(text.contains("its only mint (step 1) is muted"), text)
        assertTrue(text.contains("ships the literal \${id0} on the wire"), text)
    }

    /** The same leaves-a-literal sentence the variables strip warns with, on the badge that caused it. */
    @Test
    fun `a reference nothing mints is the never-minted warning`() {
        val text = varBadgeTooltip("idO", minted = false, sites = sites(referencedAt = listOf(0)))
        assertEquals(
            "References \${idO}, which no step mints — the engine leaves the literal \${idO} on the wire.",
            text,
        )
    }
}
