package com.knapsack.fixtool.ui

/**
 * **The toolbar's actions, named once for the toolbar and for the menu bar.**
 *
 * The toolbar is the favourites and the menu bar is the catalogue, so every chip on the toolbar is a row in a
 * menu. A chip has room for a word and a row has room for the whole name, which is why there are two strings;
 * what there is not is a second place to write either of them. The chip prints [word], its tooltip and the
 * menu row print [label], and both print the same [chord].
 */
internal enum class WindowAction(
    val label: String,
    val word: String = label,
    val chord: Chord? = null,
) {
    CONNECT("Connect"),
    DISCONNECT_ALL("Disconnect all"),
    CLOSE_ALL("Close all"),
    CAPTURE("Capture scenario from all sessions", word = "Capture"),
    SEARCH_ALL("Search all sessions", word = "Search", chord = Shortcuts.SEARCH_ALL_SESSIONS),
    BLANK_LINE_ALL("Add blank line to all panes", word = "Blank line", chord = Shortcuts.BLANK_LINE_ALL_PANES),
    CLEAR_ALL("Clear all panes", word = "Clear all"),
    SETTINGS("Settings…", word = "Settings", chord = Shortcuts.SETTINGS),
    HELP("Help"),
    ;

    /** The name with its shortcut, as a tooltip prints it: "Search all sessions · ⌘⇧F". */
    val hover: String get() = chord?.let { "$label · ${it.label}" } ?: label
}
