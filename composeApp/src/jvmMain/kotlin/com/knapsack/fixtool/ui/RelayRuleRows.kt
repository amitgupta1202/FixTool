package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.QuotesStanding
import com.knapsack.fixtool.model.RespondersOnline
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.SenderRole
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.model.WHEN_RFQ_EXPIRES
import com.knapsack.fixtool.model.roleOf
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MatcherCodec

/**
 * **Which of a rule's conditions are drawn as the venue's rows**, or null when the card draws no such rows.
 *
 * Rows are shown on a venue that declares who plays what, and on any rule already written in these terms wherever
 * it is opened — a rule that relays has to show how it is triggered even where nobody has declared anything, or
 * the refusal under it would name rows the author cannot find. They are kept off every other card, where three
 * rows about RFQs would be furniture on an equity venue.
 *
 * The first `role` on 49 and the first `rfq` on 131 or 117 become rows; a second of either stays in the condition
 * list, where the matcher editor still reads it, so nothing a hand-edited profile carries is hidden.
 */
internal fun venueRowIndices(
    rule: AcceptorResponseRule,
    conditions: List<FieldCondition>,
    relay: RelayContext,
): Set<Int>? {
    val writtenInVenueTerms = rule.asksTheVenue() || rule.whenResponders != null || rule.relays()
    if (relay.counterparties.isNullOrEmpty() && !writtenInVenueTerms) return null
    return setOf(senderRowIndex(conditions), rfqRowIndex(conditions)) - -1
}

/** Where the condition the "sender is" row edits sits in [conditions], or -1 when the rule asks no role. */
internal fun senderRowIndex(conditions: List<FieldCondition>): Int =
    conditions.indexOfFirst { it.tag == TAG_SENDER_COMP_ID && it.parsed() is Matcher.CounterpartyRole }

/** Where the condition the "RFQ is" row edits sits in [conditions], or -1 when the rule asks no RFQ state. */
internal fun rfqRowIndex(conditions: List<FieldCondition>): Int =
    conditions.indexOfFirst { it.tag in RFQ_TAGS && it.parsed() is Matcher.RfqState }

/**
 * [this] with a row's condition set to [matcher] on [tag], or removed when [matcher] is null.
 *
 * Replaced where it stands rather than moved to the end, because the list's order is the order an author reads
 * the trigger in and nothing about picking a word from a menu should reorder it.
 */
internal fun List<FieldCondition>.withRow(index: Int, tag: Int, matcher: Matcher?): List<FieldCondition> =
    when {
        matcher == null && index < 0 -> this
        matcher == null -> without(index)
        index < 0 -> this + FieldCondition(tag, MatcherCodec.matcherToJson(matcher))
        else -> replaced(index, FieldCondition(tag, MatcherCodec.matcherToJson(matcher)))
    }

/**
 * "and the sender is", "and the RFQ is", "and responders online": what a trigger asks the **venue**.
 *
 * The words are stored as matchers (`role` on 49, `rfq` on 131 or 117) and as a string, never as fields of their
 * own, so an older FixTool drops a relay rule rather than running it as a reply to the sender (decision R1). These
 * rows are how an author writes one without having to know that.
 */
@Composable
internal fun VenueRows(rule: AcceptorResponseRule, onChange: (AcceptorResponseRule) -> Unit) {
    val conditions = rule.trigger()

    // An edit that changes nothing writes nothing, so opening a menu and picking what was there never migrates a
    // rule's older spelling (see AcceptorRulesEditor: migration is a consequence of editing, never of looking).
    fun withConditions(updated: List<FieldCondition>) {
        if (updated != conditions) onChange(rule.copy(whenFields = emptyMap(), conditions = updated))
    }

    // A rule on expiry has no sender to ask about and an RFQ that is always expired, so those two rows would only
    // offer words that can never hold. The questions it can ask are the two below.
    val onExpiry = rule.whenMsgType == WHEN_RFQ_EXPIRES
    val senderAt = senderRowIndex(conditions)
    if (!onExpiry) {
        VenueWordRow(
            menu = WordMenu("and the sender is", "rule-when-sender", SenderRole.words, ::senderRoleMeaning),
            current = (conditions.getOrNull(senderAt)?.parsed() as? Matcher.CounterpartyRole)?.role,
            onChange = { word ->
                val role = word?.let { Matcher.CounterpartyRole(it) }
                withConditions(conditions.withRow(senderAt, TAG_SENDER_COMP_ID, role))
            },
        )
    }

    val rfqAt = rfqRowIndex(conditions)
    val rfqCondition = conditions.getOrNull(rfqAt)
    val rfqTag = rfqCondition?.tag ?: defaultRfqTag(rule.whenMsgType)
    val rfqState = (rfqCondition?.parsed() as? Matcher.RfqState)?.state
    if (!onExpiry) {
        VenueWordRow(
            menu =
                WordMenu("and the RFQ is", "rule-when-rfq", RfqConstraint.words) { rfqStateMeaning(it, rfqTag) },
            current = rfqState,
            onChange = { word ->
                withConditions(conditions.withRow(rfqAt, rfqTag, word?.let { Matcher.RfqState(it) }))
            },
        ) {
            // Which id names the RFQ is the author's call and not the MsgType's: a lift names the quote it hits (117),
            // a dealer's quote names the request it answers (131), and a QuoteCancel carries both. Offered once the row
            // asks anything, because "any" reads nothing through anything.
            if (rfqCondition != null && rfqState != null) {
                RfqTagMenu(tag = rfqTag) { tag ->
                    withConditions(conditions.replaced(rfqAt, rfqCondition.copy(tag = tag)))
                }
            }
        }
    }

    VenueWordRow(
        menu =
            WordMenu(
                label = "and responders online",
                tag = "rule-responders-online",
                words = RespondersOnline.words,
                meaning = ::respondersOnlineMeaning,
            ),
        current = rule.whenResponders,
        onChange = { word -> if (word != rule.whenResponders) onChange(rule.copy(whenResponders = word)) },
    )

    VenueWordRow(
        menu =
            WordMenu(
                label = "and quotes standing",
                tag = "rule-quotes-standing",
                words = QuotesStanding.words,
                meaning = ::quotesStandingMeaning,
            ),
        current = rule.whenQuotes,
        onChange = { word -> if (word != rule.whenQuotes) onChange(rule.copy(whenQuotes = word)) },
    )
}

/**
 * **What fires a rule**: a MsgType typed in, or — on a venue that relays — an RFQ's time running out, which no message
 * carries. Said in words on the card, because `35=@rfq-expired` is how it is stored and not how anyone thinks of it.
 */
@Composable
internal fun TriggerWord(rule: AcceptorResponseRule, position: Int, onChange: (AcceptorResponseRule) -> Unit) {
    if (rule.whenMsgType == WHEN_RFQ_EXPIRES) {
        Text(
            "When the RFQ expires",
            color = AppTheme.Colors.text,
            fontSize = 9.sp,
            modifier = Modifier.testTag("rule-on-expiry-$position"),
        )
        SlimButton(
            text = "on a message instead",
            onClick = { onChange(rule.copy(whenMsgType = "")) },
            color = AppTheme.Colors.textSecondary,
            modifier = Modifier.testTag("rule-on-message-$position"),
        )
        return
    }
    Text("When 35=", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
    SlimField(
        value = rule.whenMsgType,
        onValueChange = { onChange(rule.copy(whenMsgType = it)) },
        modifier = Modifier.width(40.dp),
        monospace = true,
        tintBlank = true,
        placeholder = "D",
    )
    val relays = LocalRelayContext.current.counterparties
    if (!relays.isNullOrEmpty()) {
        SlimButton(
            text = "or when the RFQ expires",
            onClick = { onChange(rule.copy(whenMsgType = WHEN_RFQ_EXPIRES)) },
            color = AppTheme.Colors.textSecondary,
            modifier = Modifier.testTag("rule-choose-expiry-$position"),
        )
    }
}

/** One venue row's menu: its label, its test tag, the words it offers, and what each means (null: "any"). */
private class WordMenu(
    val label: String,
    val tag: String,
    val words: List<String>,
    val meaning: (String?) -> String,
)

/**
 * "and the sender is [ requester ▾ ]": one word from a closed list, drawn the way the order and quote rows are and
 * for the same reason — the vocabulary is the feature, and a field would invite a value that never matches.
 */
@Composable
private fun VenueWordRow(
    menu: WordMenu,
    current: String?,
    onChange: (String?) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(menu.label, color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
        Box {
            SlimButton(
                text = (current ?: "any") + " ▾",
                onClick = { open = true },
                color = if (current == null) AppTheme.Colors.textDisabled else AppTheme.Colors.primary,
                modifier = Modifier.testTag(menu.tag),
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(AppTheme.Colors.surface),
            ) {
                (listOf(null) + menu.words).forEach { option ->
                    DropdownMenuItem(
                        text = { MenuEntry(option ?: "any", menu.meaning(option), highlighted = false) },
                        onClick = {
                            onChange(option)
                            open = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        trailing()
    }
}

/** "via 117 ▾": which id on the trigger names the RFQ the row asks about. */
@Composable
private fun RfqTagMenu(tag: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        SlimButton(
            text = "via $tag ▾",
            onClick = { open = true },
            color = AppTheme.Colors.textSecondary,
            modifier = Modifier.testTag("rule-when-rfq-tag"),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(AppTheme.Colors.surface),
        ) {
            RFQ_TAGS.forEach { option ->
                DropdownMenuItem(
                    text = { MenuEntry("$option", rfqTagMeaning(option), highlighted = option == tag) },
                    onClick = {
                        if (option != tag) onChange(option)
                        open = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** A menu entry: the word, and under it what the word means. */
@Composable
private fun MenuEntry(word: String, meaning: String, highlighted: Boolean) {
    Column {
        Text(word, color = if (highlighted) AppTheme.Colors.primary else AppTheme.Colors.text, fontSize = 10.sp)
        Text(meaning, color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
    }
}

/**
 * "to quoter ▾": who one step goes to — offered on a venue that declares counterparties, and on any step that
 * already goes somewhere. Never on a venue with no parties to address, where "to sender" on every step would be a
 * menu with one right answer.
 *
 * Picking the sender writes nothing — `to` stays absent — so a step moved back to the sender is byte-for-byte the
 * step it was before relaying existed. An address this build cannot read is shown as written, in the warning
 * colour, and kept until the author picks another: the refusal on the card says what it could have been.
 */
@Composable
internal fun StepToMenu(
    step: ResponseStep,
    tag: String,
    onChange: (ResponseStep) -> Unit,
) {
    val counterparties = LocalRelayContext.current.counterparties.orEmpty()
    if (counterparties.isEmpty() && step.to == null) return
    val address = step.address()
    var open by remember { mutableStateOf(false) }

    Box {
        SlimButton(
            text = "to ${addressLabel(address, step.to)} ▾",
            onClick = { open = true },
            color =
                when {
                    address == null -> AppTheme.Colors.warning
                    address.relays -> AppTheme.Colors.primary
                    else -> AppTheme.Colors.textDisabled
                },
            modifier = Modifier.testTag(tag),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(AppTheme.Colors.surface),
        ) {
            addressOptions(counterparties, address).forEach { option ->
                val role = (option as? StepAddress.CompId)?.let { roleOf(counterparties, it.compId)?.word }
                DropdownMenuItem(
                    // The address the step already has is marked, so opening the menu to check where a step goes
                    // answers the question without anything being picked.
                    text = {
                        MenuEntry(
                            word = addressLabel(option, null),
                            meaning = addressMeaning(option) + (role?.let { " · $it" } ?: ""),
                            highlighted = option == (address ?: StepAddress.Sender),
                        )
                    },
                    onClick = {
                        val written = option.takeIf { it.relays }?.word
                        if (written != step.to) onChange(step.copy(to = written))
                        open = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** "↳ FIDLR1, FIDLR2 online now": who a relayed step would reach, under the step, when the venue alone can say. */
@Composable
internal fun StepRecipients(step: ResponseStep, tag: String) {
    val relay = LocalRelayContext.current
    val address = step.address()?.takeIf { it.relays } ?: return
    val preview = recipientsPreview(address, relay.counterparties.orEmpty(), relay.onlineCompIds) ?: return
    Text(
        text = "↳ $preview",
        color = AppTheme.Colors.textDisabled,
        fontSize = 9.sp,
        modifier = Modifier.padding(start = 16.dp, top = 1.dp).testTag(tag),
    )
}
