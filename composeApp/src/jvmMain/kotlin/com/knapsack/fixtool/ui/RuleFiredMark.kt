package com.knapsack.fixtool.ui

import java.time.LocalDateTime

/**
 * **The rule that answered most recently, as the rules editor needs it**: a position and a time.
 *
 * A hand-made pair rather than the engine's own `VenueEvent.RuleFired`, so the editor keeps knowing
 * nothing about sessions, CompIDs or QuickFIX — a card needs to mark one row and print one clock
 * reading, and anything else it were handed would be something a future edit could start depending on.
 *
 * In its own file because it is the one type two surfaces share: [AcceptorRulesEditor] draws it and the
 * connection panel decides whether it is safe to show at all.
 */
data class RuleFiredMark(
    /** Position in the profile's authored list — the number on the card, and `SendReason`'s `ruleIndex`. */
    val ruleIndex: Int,
    val at: LocalDateTime,
)
