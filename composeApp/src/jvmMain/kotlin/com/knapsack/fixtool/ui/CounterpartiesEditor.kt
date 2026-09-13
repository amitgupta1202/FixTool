package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.PartyRole

/**
 * **Who a venue expects, and the part each one plays.**
 *
 * The list a relaying venue reads roles from. Roles are declared rather than inferred from behaviour on purpose: a
 * dealer that sends a QuoteRequest is misconfigured, and a venue that learnt its roles from traffic would relay
 * the request instead of saying so. See `docs/rfq-relay-proposal.md`, decision 3.
 *
 * A CompID ending in `*` covers a family — `FIDLRLG*` is every lane of a dealer load client — and an exact entry
 * beats any family, so one member can be carved out of it. The editor says both things beside the row that makes
 * them true, rather than in a help page.
 */
@Composable
fun CounterpartiesEditor(
    counterparties: List<Counterparty>,
    onChange: (List<Counterparty>) -> Unit,
    modifier: Modifier = Modifier,
    /** Seconds an RFQ stays open when its request names no ExpireTime, as typed. Blank: until something ends it. */
    expirySeconds: String = "",
    /** Null hides the field, for a caller that edits only the list. */
    onExpiryChange: ((String) -> Unit)? = null,
) {
    // Dense rows of 16dp buttons: the same touch-target override, for the same reason, as AcceptorRulesEditor.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 16.dp) {
        val problems = counterpartyProblems(counterparties)

        Column(modifier = modifier.fillMaxWidth()) {
            // Only while empty: once there is a list, the section header counts it, and saying it twice is noise.
            if (counterparties.isEmpty()) {
                Text(
                    text = "None declared — rules cannot address a requester or a responder on this venue",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 9.sp,
                )
            }

            counterparties.forEachIndexed { index, counterparty ->
                CounterpartyRow(
                    index = index,
                    counterparty = counterparty,
                    problem = problems[index],
                    onEdit = { edited -> onChange(counterparties.replaced(index, edited)) },
                    onRemove = { onChange(counterparties.without(index)) },
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
                SlimButton(
                    text = "+ counterparty",
                    onClick = { onChange(counterparties + Counterparty("", nextRole(counterparties).word)) },
                    modifier = Modifier.testTag("counterparty-add"),
                )
            }

            // Beside the parties rather than among the connection settings, because it is a fact about the RFQs they
            // negotiate: how long one stays open when its request does not say, which is when a rule on expiry fires.
            onExpiryChange?.let { change ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("An RFQ expires after", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
                    SlimField(
                        value = expirySeconds,
                        onValueChange = { typed -> change(typed.filter(Char::isDigit)) },
                        modifier = Modifier.width(44.dp).testTag("rfq-expiry-seconds"),
                        monospace = true,
                        placeholder = "never",
                    )
                    Text(expiryMeaning(expirySeconds), color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
                }
            }
        }
    }
}

/** A venue's RFQ expiry as the field holds it: the seconds, or blank for never. */
internal fun expiryText(config: FixConnectionConfig): String = config.rfqExpirySeconds?.let { "$it" } ?: ""

/** What the RFQ expiry field says, beside it. */
internal fun expiryMeaning(seconds: String): String =
    if (seconds.toIntOrNull()?.takeIf { it > 0 } == null) {
        "s, when its request names no ExpireTime: blank never expires one"
    } else {
        "s, when its request names no ExpireTime(126)"
    }

/** A requester first when there is none, a responder after: the order a tester fills an RFQ venue in. */
internal fun nextRole(counterparties: List<Counterparty>): PartyRole =
    if (counterparties.any { PartyRole.byWord(it.role) == PartyRole.REQUESTER }) {
        PartyRole.RESPONDER
    } else {
        PartyRole.REQUESTER
    }

/** One counterparty: its CompID, its role, a way to remove it, and what the venue will make of it. */
@Composable
private fun CounterpartyRow(
    index: Int,
    counterparty: Counterparty,
    problem: String?,
    onEdit: (Counterparty) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SlimField(
            value = counterparty.compId,
            onValueChange = { typed -> onEdit(counterparty.copy(compId = typed.trim())) },
            modifier = Modifier.width(120.dp).testTag("counterparty-compid-$index"),
            monospace = true,
            tintBlank = true,
            placeholder = "FIBUY1 or FIDLRLG*",
        )
        RoleMenu(
            role = counterparty.role,
            tag = "counterparty-role-$index",
            onChange = { role -> onEdit(counterparty.copy(role = role)) },
        )
        Box(modifier = Modifier.weight(1f))
        TooltipIconButton(
            tooltip = "Remove counterparty",
            onClick = onRemove,
            modifier = Modifier.size(16.dp).testTag("counterparty-remove-$index"),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove counterparty",
                tint = AppTheme.Colors.textSecondary,
                modifier = Modifier.size(10.dp),
            )
        }
    }
    problem?.let {
        Text(
            text = "⚠ $it",
            color = AppTheme.Colors.warning,
            fontSize = 9.sp,
            modifier = Modifier.padding(start = 4.dp, top = 1.dp).testTag("counterparty-problem-$index"),
        )
    }
}

@Composable
private fun RoleMenu(role: String, tag: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val known = PartyRole.byWord(role)

    Box {
        SlimButton(
            text = (known?.word ?: role.ifBlank { "?" }) + " ▾",
            onClick = { open = true },
            color = if (known == null) AppTheme.Colors.warning else AppTheme.Colors.primary,
            modifier = Modifier.testTag(tag),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(AppTheme.Colors.surface),
        ) {
            PartyRole.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.word, color = AppTheme.Colors.text, fontSize = 10.sp)
                            Text(partyRoleMeaning(option), color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
                        }
                    },
                    onClick = {
                        if (option.word != role) onChange(option.word)
                        open = false
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

internal fun partyRoleMeaning(role: PartyRole): String =
    when (role) {
        PartyRole.REQUESTER -> "asks for prices: a buy side raising an RFQ"
        PartyRole.RESPONDER -> "answers them: a dealer quoting, passing, or being lifted"
    }

/** "2 requesters · 3 responders (1 family)" — the list in a line, for the section header and the card above it. */
internal fun counterpartySummary(counterparties: List<Counterparty>): String {
    val roles = counterparties.groupingBy { PartyRole.byWord(it.role) }.eachCount()
    val families = counterparties.count { it.isPrefix }
    return listOfNotNull(
        roles[PartyRole.REQUESTER]?.let { plural(it, "requester") },
        roles[PartyRole.RESPONDER]?.let { plural(it, "responder") },
        roles[null]?.let { "$it with no role" },
    ).joinToString(" · ") + if (families > 0) " (${plural(families, "family", "families")})" else ""
}

private fun plural(count: Int, one: String, many: String = "${one}s") = "$count ${if (count == 1) one else many}"

/**
 * What is wrong with each row, by position, or null for a row that is fine.
 *
 * Nothing here refuses to save. A half-typed list is a list being written, and the venue already skips an entry
 * it cannot read (see `roleOf`) — so the job is to say, beside the row, what the venue will make of it.
 */
internal fun counterpartyProblems(counterparties: List<Counterparty>): List<String?> =
    counterparties.mapIndexed { index, counterparty ->
        val compId = counterparty.compId
        when {
            compId.isBlank() -> "no CompID, so this covers nobody"
            compId == "*" -> "a bare * covers every CompID — list the family's common start, like FIDLR*"
            compId.dropLast(1).contains('*') -> "only a trailing * is read; '$compId' names that CompID literally"
            PartyRole.byWord(counterparty.role) == null ->
                "'${counterparty.role}' is not a role, so the venue skips this entry — pick requester or responder"
            counterparties.take(index).any { it.compId == compId } ->
                "$compId is listed above already, and that entry decides its role"
            else -> null
        }
    }
