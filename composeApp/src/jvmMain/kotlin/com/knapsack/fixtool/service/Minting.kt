package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.MintingSide
import com.knapsack.fixtool.model.TagRole

/**
 * **Who mints this tag's value — us or them — given which end of the session we are on.**
 *
 * The one decider for a question capture and [ExpectationSeeder] both ask and used to answer separately,
 * each hard-coding "we are the client". They cannot disagree now: the standard sets and the venue's own
 * [TagRole] declarations are read here, resolved against [MintingSide], and nowhere else.
 *
 * [byUs] and [byThem] are deliberately **not** each other's negation. A tag nobody has classified — most
 * of a message — is minted by neither of us as far as FixTool knows, and both answer false for it. That
 * is the correct reading: an unclassified field is a business value to assert as it stands, not an
 * identifier to parameterize on a guess.
 */
object Minting {
    /** `ClOrdID(11)` and friends: the standard set, plus whatever the venue declared for its own tags. */
    private fun clientMints(tag: Int, dictionary: FixDictionaryAdapter?): Boolean =
        tag in ScenarioCapture.ID_TAGS || dictionary?.hasRole(tag, TagRole.CLIENT_MINTED_ID) == true

    /** `OrderID(37)`, `ExecID(17)` and friends, on the same terms. */
    private fun venueMints(tag: Int, dictionary: FixDictionaryAdapter?): Boolean =
        tag in ExpectationSeeder.VENUE_MINTED_IDS || dictionary?.hasRole(tag, TagRole.VENUE_MINTED_ID) == true

    /**
     * **We invent this value.** A Send parameterizes it fresh (venues enforce uniqueness on these), and
     * its echo on a reply becomes a `Reference` check plus a bind constraint.
     */
    fun byUs(tag: Int, dictionary: FixDictionaryAdapter?, side: MintingSide): Boolean =
        when (side) {
            MintingSide.CLIENT -> clientMints(tag, dictionary)
            MintingSide.VENUE -> venueMints(tag, dictionary)
        }

    /**
     * **They invent this value.** An Expect asserts its presence rather than a literal that is new every
     * run, and a later Send that quotes it back reads it from *this run* via `bindAs` — never from the
     * capture, which is stale by the second run.
     */
    fun byThem(tag: Int, dictionary: FixDictionaryAdapter?, side: MintingSide): Boolean =
        when (side) {
            MintingSide.CLIENT -> venueMints(tag, dictionary)
            MintingSide.VENUE -> clientMints(tag, dictionary)
        }

    /** A lifetime stamp — side-independent: an expiry is an expiry whoever wrote it. */
    fun isLifetime(tag: Int, dictionary: FixDictionaryAdapter?): Boolean =
        tag in ScenarioCapture.LIFETIME_TAGS || dictionary?.hasRole(tag, TagRole.LIFETIME) == true

    /**
     * **A correlation id at all, whoever mints it** — the union of [byUs] and [byThem], which is the same
     * set from either chair, so this one takes no side.
     *
     * Asked by anything that cares that two messages are *about the same thing* rather than who invented
     * the value: [Conversations] draws its edges from exactly these tags, and restricting it to them is
     * what keeps an ordinary `54=1` from linking two unrelated flows.
     */
    fun isCorrelationId(tag: Int, dictionary: FixDictionaryAdapter?): Boolean =
        clientMints(tag, dictionary) || venueMints(tag, dictionary)
}
