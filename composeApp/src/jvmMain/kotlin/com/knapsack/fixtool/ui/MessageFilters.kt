package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessage.Direction.INCOMING
import com.knapsack.fixtool.model.FixMessage.Direction.OUTGOING
import com.knapsack.fixtool.model.Separator

/**
 * **What one pane shows, decided in one place for every layout.**
 *
 * There used to be one of these, inside `SplitView`, and the tabs layout simply did not call it: the
 * TabBar's filter button toggled a panel that never applied, so the same pane filtered in split view and
 * not in tabs. That is the kind of divergence a shared function makes impossible rather than merely
 * unlikely — both layouts now hand the same inputs to the same code and cannot disagree.
 *
 * Three filters are ANDed here and they are deliberately different kinds of thing:
 *
 * - **the pane's own** ([Pane]) — what this session's filter panel says, and the only one stored on
 *   `FixMessageSession`;
 * - **the global one** ([Global]) — the toolbar's box, held on the ViewModel. It used to be *written
 *   through* into every session's `filterRegex`, which destroyed each pane's own filter to say
 *   something the toolbar already knew. ANDing it here means the toolbar can be typed into and cleared
 *   with every pane's filter still exactly as its owner left it;
 * - **the followed trace** ([followedUids]) — see below.
 *
 * **Follow is a membership test, not a regex, and that is the whole point of it.** A regex over
 * `toDisplayString()` finds what the reader already knew to type: it is stale the moment the venue mints
 * an id the reader has not read yet, it matches `ORD-9` inside `ORD-91` because it never sees a field
 * boundary, and when it misses a leg it says nothing at all — the reader concludes the venue never
 * forwarded the order. A followed set is computed by `Traces` from shared correlation *values*, so it
 * grows on its own as the exchange runs, cannot match a substring of an unrelated id, and narrows every
 * pane to exactly the messages the relation put in the component. Nothing about it is stored on a
 * session, so un-following restores each pane's own filters untouched.
 */
object MessageFilters {
    /** One pane's own filter panel, as `FixMessageSession` holds it. */
    data class Pane(
        val regex: String = "",
        val showIncoming: Boolean = true,
        val showOutgoing: Boolean = true,
        val showSeparator: Boolean = true,
        val messageTypes: String = "",
    ) {
        companion object {
            /** No pane filter at all — every field at its permissive default. */
            val NONE = Pane()
        }
    }

    /** The toolbar's filter, which applies to every pane and belongs to none of them. */
    data class Global(
        val regex: String = "",
        val showIncoming: Boolean = true,
        val showOutgoing: Boolean = true,
    ) {
        companion object {
            val NONE = Global()
        }
    }

    /**
     * The messages [pane] should render, in order.
     *
     * [followedUids] is null when nothing is followed. When it is not null the pane is narrowed to the
     * followed trace *before* anything else is considered, and separators go with it: a blank line the
     * user inserted belongs to no exchange, so it cannot be part of one.
     *
     * Both "no regex" cases stay permissive, which is long-standing behaviour worth restating: a blank
     * pattern matches everything, and so does an invalid one — a half-typed `ORD[` must not blank the
     * view mid-keystroke. The two regexes are compiled once here rather than inside the per-message
     * lambda, where they used to be recompiled for every message in the session.
     */
    fun apply(
        messages: List<AppMessage>,
        pane: Pane = Pane.NONE,
        global: Global = Global.NONE,
        followedUids: Set<Long>? = null,
    ): List<AppMessage> {
        val paneRegex = compile(pane.regex)
        val globalRegex = compile(global.regex)
        val wantedTypes =
            pane.messageTypes
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        return messages.filter { message ->
            val directionMatch =
                when (message) {
                    // A separator carries no correlation id, so it is in no trace: while following, it
                    // is not "hidden" so much as absent from the answer.
                    is Separator -> return@filter followedUids == null && pane.showSeparator
                    is FixMessage -> {
                        if (followedUids != null && message.uid !in followedUids) return@filter false
                        when (message.direction) {
                            INCOMING -> pane.showIncoming && global.showIncoming
                            OUTGOING -> pane.showOutgoing && global.showOutgoing
                        }
                    }
                }

            if (!directionMatch) return@filter false

            val messageTypeMatch =
                pane.messageTypes.isBlank() ||
                    wantedTypes.isEmpty() ||
                    wantedTypes.any { it.equals(message.messageType, ignoreCase = true) }

            if (!messageTypeMatch) return@filter false

            if (paneRegex == null && globalRegex == null) return@filter true
            // One display string for both patterns: it is the expensive part, and building it twice per
            // message is how a pane with a global filter typed into it becomes the slow one.
            val text = message.toDisplayString()
            (paneRegex == null || text.contains(paneRegex)) &&
                (globalRegex == null || text.contains(globalRegex))
        }
    }

    /** Null for "admits everything" — which covers both a blank pattern and one that will not parse. */
    private fun compile(pattern: String): Regex? =
        if (pattern.isBlank()) {
            null
        } else {
            try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                null
            }
        }
}
