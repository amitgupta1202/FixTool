package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.QuotesStanding
import com.knapsack.fixtool.model.RespondersOnline
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.SenderRole
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.model.roleOf

/*
 * The words a relay rule's card is written in: what each menu entry means, who a step would reach, and how an
 * address prints. Kept apart from the rows that show them, so every one of them can be read — and tested — as
 * a string, without a window.
 */

internal const val TAG_SENDER_COMP_ID = 49
internal const val TAG_QUOTE_REQ_ID = 131
internal const val TAG_QUOTE_ID = 117
private const val MSG_QUOTE_RESPONSE = "AJ"

/** The two ids an "RFQ is" row can read the RFQ through. */
internal val RFQ_TAGS = listOf(TAG_QUOTE_REQ_ID, TAG_QUOTE_ID)

/** The id an "RFQ is" row reads through when it is first set: a lift names a quote, everything else a request. */
internal fun defaultRfqTag(whenMsgType: String): Int =
    if (whenMsgType.trim() == MSG_QUOTE_RESPONSE) TAG_QUOTE_ID else TAG_QUOTE_REQ_ID

internal fun senderRoleMeaning(word: String?): String =
    when (SenderRole.byWord(word)) {
        null -> "the rule does not ask who sent it"
        SenderRole.REQUESTER -> "declared a requester on this venue"
        SenderRole.RESPONDER -> "declared a responder on this venue"
        SenderRole.UNLISTED -> "logged on, but no counterparty the venue declares covers it"
    }

internal fun rfqStateMeaning(word: String?, tag: Int): String =
    when (RfqConstraint.byWord(word)) {
        null -> "the rule does not ask the RFQ book"
        RfqConstraint.UNKNOWN -> "the venue holds no RFQ the message's $tag names"
        RfqConstraint.OPEN ->
            if (tag == TAG_QUOTE_ID) {
                "still live, and the quote it names is its dealer's latest"
            } else {
                "asked, and still able to take a quote"
            }
        RfqConstraint.DONE -> "traded, passed or refused"
        RfqConstraint.EXPIRED -> "its time ran out while it was still live"
    }

internal fun rfqTagMeaning(tag: Int): String =
    if (tag == TAG_QUOTE_ID) {
        "the quote id the sender was given — a lift or a pass"
    } else {
        "the QuoteReqID the sender was sent or sent — a quote, a pass, a request"
    }

internal fun quotesStandingMeaning(word: String?): String =
    when (QuotesStanding.byWord(word)) {
        null -> "the rule does not ask"
        QuotesStanding.NONE -> "no quote stands on the RFQ: nobody quoted, or every quote has gone"
        QuotesStanding.SOME -> "at least one quote stands on the RFQ"
    }

internal fun respondersOnlineMeaning(word: String?): String =
    when (RespondersOnline.byWord(word)) {
        null -> "the rule does not ask"
        RespondersOnline.NONE -> "no declared responder is logged on to be asked"
        RespondersOnline.SOME -> "at least one declared responder is logged on"
    }

/** What each address in the To menu means, in the words the menu prints beside it. */
internal fun addressMeaning(address: StepAddress): String =
    when (address) {
        StepAddress.Sender -> "whoever sent this message"
        StepAddress.Requester -> "opened the RFQ it belongs to"
        StepAddress.Quotes -> "the requester, once for each quote that stands"
        StepAddress.Quoter -> "the responder whose quote it names"
        StepAddress.Cover -> "best other live quote on the traded side"
        StepAddress.Others -> "live quotes, not the quoter or the cover"
        StepAddress.Quoted -> "every responder with a live quote"
        StepAddress.Asked -> "every responder the RFQ went to"
        StepAddress.Responders -> "every declared responder, as it stands when the rule fires"
        is StepAddress.CompId -> "one named counterparty"
    }

/** An address as a card prints it: the word, or the CompID alone for one named counterparty. */
internal fun addressLabel(address: StepAddress?, written: String?): String =
    when (address) {
        null -> "'$written'"
        is StepAddress.CompId -> address.compId
        else -> address.word
    }

/**
 * The addresses a step's To menu offers: the fixed words, then each counterparty the venue names exactly, then the
 * one this step already has if it is none of those — so opening the menu never hides where a step goes.
 */
internal fun addressOptions(counterparties: List<Counterparty>, current: StepAddress?): List<StepAddress> {
    val fixed =
        listOf(
            StepAddress.Sender,
            StepAddress.Requester,
            StepAddress.Quotes,
            StepAddress.Quoter,
            StepAddress.Cover,
            StepAddress.Others,
            StepAddress.Quoted,
            StepAddress.Asked,
            StepAddress.Responders,
        )
    val named = counterparties.filterNot { it.isPrefix }.map { StepAddress.CompId(it.compId) }.distinct()
    val kept = listOfNotNull((current as? StepAddress.CompId)?.takeIf { it !in named })
    return fixed + named + kept
}

/**
 * **Who a step would reach, said before anything is connected.**
 *
 * Only for the two addresses that are knowable from the venue alone — every responder, and one CompID. The rest
 * are found in the RFQ book when the rule fires, and a preview that guessed at them would be a claim about a
 * negotiation that has not happened; their menu line already says how they are found. Null for those.
 *
 * [online] null is a panel with no venue running: the names are said, and nothing is claimed about sessions.
 */
internal fun recipientsPreview(
    address: StepAddress,
    counterparties: List<Counterparty>,
    online: Set<String>?,
): String? =
    when (address) {
        StepAddress.Responders -> respondersPreview(counterparties, online)
        is StepAddress.CompId -> {
            val part = roleOf(counterparties, address.compId)?.word ?: "not a counterparty this venue declares"
            when {
                online == null -> "${address.compId} · $part"
                address.compId in online -> "${address.compId} · $part · logged on"
                else -> "${address.compId} · $part · $ABSENT"
            }
        }
        else -> null
    }

private const val ABSENT = "not logged on, so counted as not delivered"

private fun respondersPreview(counterparties: List<Counterparty>, online: Set<String>?): String {
    val declared = counterparties.filter { PartyRole.byWord(it.role) == PartyRole.RESPONDER }
    return when {
        declared.isEmpty() -> "no counterparty is declared a responder, so this reaches nobody"
        online == null -> declared.joinToString(", ") { it.compId }
        else -> {
            val on = online.filter { roleOf(counterparties, it) == PartyRole.RESPONDER }.sorted()
            // A family (`FIDLRLG*`) is only ever reached through the members that are logged on, so it has no
            // absentees to count; a CompID named exactly and not logged on is owed the message and counted as
            // not delivered.
            val off = declared.filterNot { it.isPrefix || it.compId in online }.map { it.compId }
            listOfNotNull(
                on.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")} online now" },
                off.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")} $ABSENT" },
            ).ifEmpty { listOf("no responder is logged on now") }
                .joinToString(" · ")
        }
    }
}
