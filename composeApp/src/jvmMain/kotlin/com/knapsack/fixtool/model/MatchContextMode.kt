package com.knapsack.fixtool.model

/**
 * How much surrounding context to reveal for a search match that lives inside a repeating group,
 * in the message detail panel.
 *
 * - [BARE]: legacy behaviour — only the rows that match the query are shown.
 * - [IDENTITY]: each matching group instance also shows its identity field (its first simple field,
 *   e.g. PartyID) so you can tell which entry a match belongs to.
 * - [FULL]: each matching group instance is shown in its entirety.
 */
enum class MatchContextMode(val label: String) {
    BARE("Bare"),
    IDENTITY("Identity"),
    FULL("Full"),
    ;

    companion object {
        /** Parses a case-insensitive name (bare|identity|full); null if unrecognised. */
        fun fromString(value: String): MatchContextMode? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
