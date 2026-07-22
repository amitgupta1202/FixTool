package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.MintingSide
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind

/**
 * Builds a first-draft [Expectation] from a captured message by **pre-seeding** each tag's
 * matcher from its dictionary field type. This is the usability lever: without it an author
 * would have to manually downgrade every timestamp / seqnum / ID from `Exact`, and the first
 * replay would fail on a dozen forgotten volatile tags. The author then only corrects the seed.
 *
 * Seeding rules, in the order they are applied:
 * - the envelope ([SessionTags.NEVER_ASSERTED])        → omitted entirely
 * - IDs and addressing ([SessionTags.VALUE_NOT_PORTABLE], OrderID 37, ExecID 17) → `Presence`
 * - `UTCTIMESTAMP` / time-ish     → `Temporal(NOW_WITHIN_TOLERANCE)`
 * - `UTCDATEONLY` / `UTCDATE`     → `Temporal(TODAY)` (a *current* UTC date — never a business date; see [DATE_TYPES])
 * - `PRICE`/`QTY`/`AMT`/`FLOAT`   → `Numeric(captured)` (tolerance 0 = format-robust exact)
 * - everything else               → `Exact(captured)`
 *
 * Presence is checked before the type rules, and the order carries a defect with it — see [seedMatcher].
 */
object ExpectationSeeder {
    /**
     * Never asserted: they identify the connection and the moment, not the behaviour. Seeding
     * SenderCompID(49) or BeginString(8) as Exact made a captured scenario fail on every other
     * environment. Narrower than what a Send step strips — see [SessionTags].
     */
    private val OMITTED_TAGS = SessionTags.NEVER_ASSERTED

    /**
     * Tags asserted for their presence, never their value: the venue-assigned identifiers whose value
     * is minted fresh by the venue on every reply, and the addressing/origin tags whose value belongs
     * to the environment rather than the behaviour (see [SessionTags.VALUE_NOT_PORTABLE]).
     *
     * The venue-assigned set covers standard FIX across the flows FixTool's clients actually run —
     * not just the order lifecycle. Seeding any of these Exact made every captured scenario in that
     * flow fail its own first replay, deterministically: a fresh QuoteID per Quote is what a quoting
     * venue *is*. A tag here that a send once minted still becomes a Reference check — capture's echo
     * correlation replaces the seeded matcher, so presence is only the answer when nothing better is.
     */
    internal val VENUE_MINTED_IDS =
        setOf(
            37, // OrderID
            17, // ExecID
            19, // ExecRefID — refers to the venue's own prior ExecID (busts/corrections)
            117, // QuoteID — minted per Quote by whichever side quotes (RFQ)
            198, // SecondaryOrderID
            278, // MDEntryID — market-data entry handles, new per snapshot
            527, // SecondaryExecID
            571, // TradeReportID — minted by whoever SUBMITS the report; see the note below
            818, // SecondaryTradeReportID — the `Secondary*` rule: assigned by the party that accepts
            880, // TrdMatchID — the venue's match-engine id (post-trade)
            1003, // TradeID
        )

    // Why 571 sits here AND in [ScenarioCapture.ID_TAGS], and 818 only here.
    //
    // `TradeReportID(571)` identifies a trade capture report, and FIX gives it to whoever *submits* one:
    // our own submission mints it, and a report the venue publishes carries the venue's. Listed on one
    // side only it seeded `Exact` for the other, so a captured venue-published TradeCaptureReport asserted
    // an id that is new every run — red from the second replay, on a field that was never the behaviour.
    // Both sets is not ambiguity, it is the `QuoteID(117)` shape: capture resolves it per scenario by
    // whether one of our own Sends minted this value (the echo rewrite in [ScenarioCapture] runs after
    // seeding and wins), so a client-submitted report still becomes a `Reference`.
    //
    // `SecondaryTradeReportID(818)` needs no such resolution: `Secondary<X>ID` in FIX is the identifier
    // assigned by the party that ACCEPTS the message, which is why `SecondaryOrderID(198)` and
    // `SecondaryExecID(527)` are already here. 818 was the family's missing member.
    //
    // Both are standard FIX. Tags whose minter depends on the venue — `TradeLinkID(820)`, a UTI in
    // `RegulatoryTradeID(1903)` — deliberately stay out of this file and belong in the venue's own
    // overlay ([com.knapsack.fixtool.model.TagRoleOverlay]), reachable through [VenueTagScan].

    /**
     * The venue-assigned ids **plus** the addressing tags whose value belongs to the environment. Split
     * from [VENUE_MINTED_IDS] because only the former is a value the venue *mints per reply* — the one a
     * later send may have to quote back from this run rather than from the capture. A SenderSubID is not
     * minted, it is configured, and wiring a capture for it would be nonsense.
     */
    internal val PRESENCE_TAGS = VENUE_MINTED_IDS + SessionTags.VALUE_NOT_PORTABLE

    // Lifetime stamps (ValidUntilTime, ExpireTime) used to be listed again here. One decider now:
    // [Minting.isLifetime] reads ScenarioCapture's set, so the send side and the assert side cannot drift.
    // Why Presence and not Temporal(~now): a UTCTIMESTAMP whose *meaning* is "a moment shortly after
    // sending" reds under ~now on any quote that outlives the tolerance, and reds under Exact for ever.
    // That the venue said how long it is good for is the behaviour.

    /** Default tolerances per numeric family. 0 still ignores formatting (parsed as numbers). */
    private const val DEFAULT_NUMERIC_TOLERANCE = 0.0

    /**
     * Default tolerance (seconds) for timestamp fields seeded as "now ± N". Internal because it is the
     * ONE default: MatcherCodec answers an omitted `toleranceSeconds` with this same number, so the
     * documented-optional field cannot quietly mean ±0s — a matcher that passes only when the venue's
     * stamp equals the judging instant to the exact second.
     */
    internal const val DEFAULT_TIME_TOLERANCE_SECONDS = 60L

    /** One seeded assertion plus the captured value it was seeded from (for editor/preview rows). */
    data class SeededField(val field: FieldExpectation, val capturedValue: String)

    fun seed(
        fields: List<Pair<Int, String>>,
        dictionary: FixDictionaryAdapter?,
        side: MintingSide = MintingSide.CLIENT,
    ): Expectation {
        val messageType = fields.firstOrNull { it.first == 35 }?.second
        return Expectation(
            fields = seedDetailed(fields, dictionary, side).map { it.field },
            messageType = messageType,
            mode = MatchMode.OPEN,
        )
    }

    /**
     * Walk the captured fields in wire order, drop the envelope, seed a matcher per field. There is no
     * structure walk, because there is nothing structural left to decide.
     *
     * **Every occurrence gets its own row**, and that is the change. The old seeder de-duplicated by
     * `(tag, path)`, so a message with four party entries produced *one* PartyRole assertion: three of
     * the four entries were never checked, and the scenario looked complete. Now the *k*-th `452` row
     * asserts the *k*-th `452` in the reply, so all four are covered — and a group whose entries are
     * indistinguishable by any identity, which the old model refused to assert at all, is asserted like
     * anything else. Position is the identity, and every message has one.
     */
    fun seedDetailed(
        fields: List<Pair<Int, String>>,
        dictionary: FixDictionaryAdapter?,
        side: MintingSide = MintingSide.CLIENT,
    ): List<SeededField> =
        fields
            .filterNot { it.first in OMITTED_TAGS }
            .map { (tag, value) ->
                SeededField(
                    field = FieldExpectation(tag = tag, matcher = seedMatcher(tag, value, dictionary, side)),
                    capturedValue = value,
                )
            }

    /**
     * **May this field honestly carry a numeric tolerance?** The same families [seedMatcher] seeds
     * `Numeric` from — prices, quantities, amounts — and nothing else. This is the one decider the fix
     * plan's bulk loosen consults, and it is the seeder's on purpose: an enum-coded int (`PartyRole(452)`,
     * `QuoteStatus(297)`) parses as a number just fine, and `4 ± 3` over it is a matcher that accepts
     * seven different meanings while reading like a tolerance. The type system knows which ints are
     * codes; a second opinion here would eventually disagree with the seed.
     */
    fun numericFamily(tag: Int, dictionary: FixDictionaryAdapter?): Boolean =
        fieldTypeName(tag, dictionary) in NUMERIC_TYPES

    /**
     * **May this field honestly carry a `now`/`today` matcher?** Exactly the families [seedMatcher] seeds
     * `Temporal` from, and for the same reasons: the time-ish types, plus the dates that genuinely mean
     * *the current UTC date*. A `LOCALMKTDATE` or `MONTHYEAR` is deliberately out — a settlement or
     * maturity date is not "today", which is the whole argument [DATE_TYPES] makes. Same one-decider rule
     * as [numericFamily]: the editor's dropdown, the seed and the fix plan all read this, so none of them
     * can tell the author a different story about what a field is.
     */
    fun temporalFamily(tag: Int, dictionary: FixDictionaryAdapter?): Boolean =
        fieldTypeName(tag, dictionary).let { it in TIMESTAMP_TYPES || it in DATE_TYPES }

    /**
     * **May this field honestly carry an inferred pattern?** Free-text string fields and fields the
     * dictionary does not know (custom tags) — and never an enum-coded one, whatever its declared type:
     * `SecurityIDSource(22)` is a STRING whose values are a vocabulary, and a "shape" over a vocabulary
     * is a matcher that admits members nobody listed. Typed fields that are not strings are out too —
     * a `LOCALMKTDATE` failing `20260722` against `20260723` would generalise to `2026072\d+`, which
     * full-matches both sides while asserting nothing a settlement date means. Same decider rule as
     * [numericFamily]: the classification lives here so the fix plan and the gutter cannot disagree
     * with the seed about what a field is.
     */
    fun textFamily(tag: Int, dictionary: FixDictionaryAdapter?): Boolean {
        if (dictionary?.hasFieldValues(tag) == true) return false
        val type = fieldTypeName(tag, dictionary)
        return type == null || type == "STRING"
    }

    /**
     * **Does this field carry an identifier the venue may be minting per run?** The gate behind the fix
     * plan's presence demotion — [textFamily], narrowed to names that say identifier (`…ID`, which covers
     * `…RefID`) or to tags the dictionary does not know at all, because a custom 5xxx tag failing with a
     * fresh value every run is exactly the case presence demotion exists for (S1). Deliberately wider
     * than certainty: `SecurityID(48)` passes this gate, and a changed instrument id is a regression —
     * which is why a presence proposal is default-unchecked (D2) and its reason says so.
     */
    fun identifierFamily(tag: Int, dictionary: FixDictionaryAdapter?): Boolean {
        if (!textFamily(tag, dictionary)) return false
        val name = dictionary?.getFieldName(tag)
        return name == null || name.endsWith("ID")
    }

    private fun seedMatcher(
        tag: Int,
        value: String,
        dictionary: FixDictionaryAdapter?,
        side: MintingSide,
    ): Matcher {
        val fieldType = fieldTypeName(tag, dictionary)
        return when {
            // Ahead of the type rules, not after them: OrigSendingTime(122) is a UTCTIMESTAMP, so a
            // type-first walk seeded it "~now ±60s" — and a resend's OrigSendingTime is *by definition*
            // the old message's, minutes or hours in the past. Every resend scenario went red on every
            // run, on the environment it was captured on.
            // Whose id it is depends on which end we are: on an acceptor session the counterparty's
            // ClOrdID is the value new on every run, and OUR ExecID is the one we mint. [Minting] holds
            // that resolution, and the venue's own tags reach it through the overlay beside its
            // dictionary — never through FixTool's source, which is shared by every venue.
            Minting.byThem(tag, dictionary, side) || tag in SessionTags.VALUE_NOT_PORTABLE -> Matcher.Presence
            // Also ahead of the type rules, for the mirror reason: an expiry is deliberately NOT ~now.
            Minting.isLifetime(tag, dictionary) -> Matcher.Presence
            fieldType in TIMESTAMP_TYPES ->
                Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, DEFAULT_TIME_TOLERANCE_SECONDS)
            // Only *current* UTC dates land here (an MDEntryDate on a live snapshot is today's date). Business
            // dates — LOCALMKTDATE, MONTHYEAR — are deliberately excluded from DATE_TYPES and fall through to
            // Exact below, so the golden stays green against its own captured value. See [DATE_TYPES].
            fieldType in DATE_TYPES -> Matcher.Temporal(TemporalKind.TODAY)
            fieldType in NUMERIC_TYPES && value.toDoubleOrNull() != null ->
                Matcher.Numeric(value.toDouble(), DEFAULT_NUMERIC_TOLERANCE)
            else -> Matcher.Exact(value)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    /**
     * The field's type, for deciding how to TREAT a value — a comprehension question, so it goes through
     * [FixDictionaryAdapter.fieldType] and its standard-FIX floor rather than to the conformance
     * dictionary. It used to read `getDataDictionary().getFieldType()` directly, which meant a venue
     * subset omitting `AvgPx(6)` made it an untyped string: seeded Exact, permanently red, and offered
     * "loosen to presence" as its only repair.
     */
    internal fun fieldTypeName(tag: Int, dictionary: FixDictionaryAdapter?): String? = dictionary?.fieldType(tag)

    // QuickFIX FieldType enum names, matched as strings so we don't bind to a specific QF/J version.
    private val TIMESTAMP_TYPES = setOf("UTCTIMESTAMP", "UTCTIMEONLY", "TZTIMESTAMP", "TZTIMEONLY", "TIME")

    // Only the types that genuinely mean "the current UTC date": an MDEntryDate(272) on a live snapshot is
    // today's date, and seeding it Exact would wrongly go red on daily replay. LOCALMKTDATE and MONTHYEAR are
    // deliberately NOT here. A SettlDate(64)/TradeDate(75) is a fixed *business* date and a MONTHYEAR is a
    // maturity — a settlement, trade or maturity date is not "today", so Temporal(TODAY) turned red on the
    // golden's own captured value on every T+n (and the only reconcile repairs on a temporal row are
    // Loosen/Drop, so the coverage silently eroded). Excluded from this set, they fall through to Exact(value)
    // — the value the venue echoes — which keeps a freshly-captured scenario green against its own capture.
    private val DATE_TYPES = setOf("UTCDATEONLY", "UTCDATE")
    private val NUMERIC_TYPES = setOf("PRICE", "QTY", "AMT", "FLOAT", "PRICEOFFSET", "PERCENTAGE")
}
