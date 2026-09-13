package com.knapsack.fixtool.ui

import androidx.compose.runtime.compositionLocalOf
import com.knapsack.fixtool.model.Counterparty

/**
 * What a rule card needs to know about the venue it is on, to draw a relay rule: who the venue declares, and who
 * is logged on now. [counterparties] null is a card with no venue in hand; [onlineCompIds] null, no venue running.
 */
internal data class RelayContext(
    val counterparties: List<Counterparty>? = null,
    val onlineCompIds: Set<String>? = null,
)

/**
 * The venue the rules editor is drawing rules for, provided once by [AcceptorRulesEditor] and read by the card and
 * step rows that draw relay rules. A local rather than a parameter threaded through every row, because only the
 * rows that address counterparties read it, and every card between them would otherwise carry it for them.
 */
internal val LocalRelayContext = compositionLocalOf { RelayContext() }
