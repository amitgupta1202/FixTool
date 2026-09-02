package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import quickfix.Message
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **What an acceptor can read off a message, and what it can put back on the wire.**
 *
 * Both halves of the same limitation, and both were measured before they were fixed: with a
 * dictionary loaded, a conformant `35=R` hides Symbol inside `NoRelatedSym(146)` where no trigger
 * could see it, and a reply naming that group came out with its fields sorted by tag number so the
 * group was empty. Neither failed loudly. A rule that cannot match looks exactly like a trigger that
 * has not come up yet, and a malformed reply is blamed on the client.
 *
 * These tests are deliberately about *bytes and matches*, not about the FX venue that needed them —
 * the capability is the acceptor's, and the preset that uses it is content on top.
 */
class AcceptorReplyBuilderTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val soh = ''

    /** A conformant FIX 4.4 QuoteRequest: the symbol lives inside NoRelatedSym, where the spec puts it. */
    private fun groupedQuoteRequest(symbol: String = "EUR/USD"): Message {
        val raw =
            listOf(
                "8=FIX.4.4",
                "9=100",
                "35=R",
                "49=CLI",
                "56=VEN",
                "34=2",
                "52=20260101-00:00:00",
                "131=Q-1",
                "146=1",
                "55=$symbol",
                "54=1",
                "38=1000000",
                "10=000",
            ).joinToString(soh.toString()) + soh
        return Message().apply { fromString(raw, dictionary.getDataDictionary(), false) }
    }

    // ---------------------------------------------------------------- reading a group

    /**
     * The match that was impossible. A rule conditioned `55 = EUR/USD` has to fire against the message
     * a real client sends, not only against the flattened one FixTool's own template happens to send.
     */
    @Test
    fun `a trigger matches a symbol the message carries inside a repeating group`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(condition(55, "EUR/USD")),
                steps = listOf(ResponseStep("35=S|117=Q|55=\${req.55}")),
            )

        val outcome = AcceptorResponder.explain(listOf(rule), groupedQuoteRequest()).single()

        assertTrue(outcome.selected, "a rule conditioned on the symbol must fire for the symbol")
        assertEquals("EUR/USD", outcome.conditions.single().actual, "the trigger should report what it read")
    }

    /** Its other half: the group is read, not ignored — a different symbol still does not match. */
    @Test
    fun `a trigger does not match a different symbol in the same group`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(condition(55, "EUR/USD")),
                steps = listOf(ResponseStep("35=S|117=Q")),
            )

        val outcome = AcceptorResponder.explain(listOf(rule), groupedQuoteRequest("GBP/USD")).single()

        assertTrue(!outcome.matched, "reading the group must not mean matching anything in it")
    }

    /** `${req.55}` reads the group too, or a rule could match and then send an empty field. */
    @Test
    fun `a reply substitutes a tag the request carried in a group`() {
        val rule =
            AcceptorResponseRule(whenMsgType = "R", steps = listOf(ResponseStep("35=S|117=Q|55=\${req.55}")))

        val sent = AcceptorResponder.plan(rule, groupedQuoteRequest()).single().render()

        assertTrue(sent.contains("55=EUR/USD"), "got: $sent")
    }

    /**
     * Flat wins, and this is the whole of backward compatibility: a message carrying the tag both ways
     * reads the flat one, so no rule written before groups were readable changes what it matches.
     */
    @Test
    fun `a flat tag is preferred over the same tag inside a group`() {
        val raw =
            listOf(
                "8=FIX.4.4",
                "9=100",
                "35=R",
                "49=CLI",
                "56=VEN",
                "34=2",
                "52=20260101-00:00:00",
                "131=Q-1",
                "55=FLAT",
                "146=1",
                "55=GROUPED",
                "10=000",
            ).joinToString(soh.toString()) + soh
        val message = Message().apply { fromString(raw, dictionary.getDataDictionary(), false) }
        val rule = AcceptorResponseRule(whenMsgType = "R", steps = listOf(ResponseStep("35=S|117=Q|55=\${req.55}")))

        assertTrue(
            AcceptorResponder
                .plan(rule, message)
                .single()
                .render()
                .contains("55=FLAT"),
        )
    }

    /** A tag that is nowhere is still absent — the fallback must not invent one. */
    @Test
    fun `a tag in neither the body nor any group reads as absent`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "R",
                conditions = listOf(condition(44, "1.09")),
                steps = listOf(ResponseStep("35=S|117=Q")),
            )

        val outcome = AcceptorResponder.explain(listOf(rule), groupedQuoteRequest()).single()

        assertNull(outcome.conditions.single().actual)
        assertTrue(!outcome.matched)
    }

    // ---------------------------------------------------------------- building a group

    /**
     * **The reply that could not be sent.** `NoRelatedSym` is `required="Y"` on a QuoteRequestReject, so
     * a venue turning down an unknown symbol has to name the symbol it is turning down — inside a group.
     *
     * Asserted on the *wire order*, because that is the only version of the claim a counterparty agrees
     * with: built flat, the body sorts by tag number and `55` arrives before the `146` that is supposed
     * to contain it, leaving a group that claims one entry and holds none.
     */
    @Test
    fun `a reply carries a repeating group in wire order`() {
        val built = AcceptorResponder.buildMessage("35=AG|131=Q-1|658=1|146=1|55=XXX/YYY", dictionary)

        val wire = built.toString().replace(soh, '|')
        assertTrue(
            wire.indexOf("146=1") < wire.indexOf("55=XXX/YYY"),
            "the group count must precede its entry, or the entry is not in the group: $wire",
        )
        assertEquals("XXX/YYY", built.getGroup(1, 146).getString(55), "the symbol should be inside the group")
    }

    /** Without a dictionary there is nothing to build a group from, and the reply still goes out. */
    @Test
    fun `a reply is still built when no dictionary is loaded`() {
        val built = AcceptorResponder.buildMessage("35=8|150=0|39=0|11=ORD-1", null)

        assertEquals("8", built.header.getString(35))
        assertEquals("ORD-1", built.getString(11))
    }

    /**
     * The quieter fix. Built flat, only tag 35 reached the header, so a rule template carrying
     * OnBehalfOfCompID sent a header field in the body and the counterparty answered "tag specified out
     * of required order".
     */
    @Test
    fun `a header tag in a template is built into the header`() {
        val built = AcceptorResponder.buildMessage("35=8|115=CLIENTA|150=0|39=0", dictionary)

        assertTrue(built.header.isSetField(115), "115 belongs in the header, and the dictionary says so")
        assertTrue(!built.isSetField(115), "and therefore not in the body")
    }

    /** A sequence's steps are built the same way, which is what routes the dictionary through `plan`. */
    @Test
    fun `a planned step builds through the dictionary it was planned with`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "R",
                steps = listOf(ResponseStep("35=AG|131=\${req.131}|658=1|146=1|55=\${req.55}")),
            )

        val built = AcceptorResponder.plan(rule, groupedQuoteRequest(), dictionary = dictionary).single().build()

        assertEquals("EUR/USD", built.getGroup(1, 146).getString(55))
        assertEquals("Q-1", built.getString(131))
    }

    // ---------------------------------------------------------------- helper

    private fun condition(tag: Int, value: String) =
        FieldCondition(tag, MatcherCodec.matcherToJson(Matcher.Exact(value)))
}
