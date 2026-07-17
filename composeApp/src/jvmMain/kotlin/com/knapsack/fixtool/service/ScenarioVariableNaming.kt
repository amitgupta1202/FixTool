package com.knapsack.fixtool.service

/**
 * A variable name for tag [tag]: `quoteReqID` from the dictionary's field name, `tag131` without one —
 * suffixed (`quoteReqID2`) rather than reused on collision, because reusing a name is an assignment that
 * silently re-aims every existing `${quoteReqID}`.
 *
 * **The one naming rule every mint path shares.** Capture ([ScenarioCapture]), the editor's ● mint button,
 * and the diff's mint all name a scenario variable through here — so a captured `${clOrdID}` and a
 * hand-minted one are the same kind of name, and the reference dropdown reads in the venue's own vocabulary
 * instead of `id0`, `id1`, `id2`.
 */
fun mintName(tag: Int, fieldName: String?, taken: Set<String>): String {
    val base =
        fieldName
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.lowercase() }
            ?: "tag$tag"
    if (base !in taken) return base
    var n = 2
    while ("$base$n" in taken) n++
    return "$base$n"
}
