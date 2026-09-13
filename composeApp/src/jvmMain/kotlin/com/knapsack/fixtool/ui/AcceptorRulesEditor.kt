package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.RespondersOnline
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.RfqConstraint
import com.knapsack.fixtool.model.SenderRole
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.model.roleOf
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.AcceptorPreset
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.service.AcceptorResponder
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.MatcherCodec
import java.time.format.DateTimeFormatter

/**
 * Authoring for an acceptor profile's auto-response rules.
 *
 * Until this existed the rules were reachable only by hand-editing a profile's JSON or POSTing to
 * `/profiles`, so every acceptor feature landed for agents and not for the testers the tool is aimed
 * at. A sequence made that worse rather than better: `steps` is a nested array, and the one thing an
 * author gets wrong about it — that a step's delay is measured from the *previous* step, not from the
 * trigger — is invisible in raw JSON. So each row states its own gap **and** the running total beside
 * it; the two numbers agreeing is what tells the author they read the field the way FixTool does.
 *
 * ### What the editor does not do
 *
 * It does not normalise. A rule still carrying the older single-`responseTemplate` spelling is *shown*
 * as the one step it plays, but the rule on disk is left exactly as it was until the author changes
 * something about it — at which point that edit rewrites it to `steps`. Rewriting every rule the
 * moment a panel is opened is the same silent-mutation-on-save that lost these rules in the first
 * place; migration is a consequence of editing, never of looking.
 */
@Composable
fun AcceptorRulesEditor(
    rules: List<AcceptorResponseRule>,
    onRulesChange: (List<AcceptorResponseRule>) -> Unit,
    modifier: Modifier = Modifier,
    /** Names the values a condition's tag can take, so a trigger reads `8 (REJECTED)` and not `8`. */
    dictionary: FixDictionary? = null,
    /**
     * Opens one step in the message editor. Null where there is no editor to open it in — the raw
     * field stays either way, so this adds a way in and takes none away.
     */
    onOpenStepInEditor: ((ruleIndex: Int, stepIndex: Int) -> Unit)? = null,
    /** The step currently being edited elsewhere, so its row can say so. */
    editingStep: Pair<Int, Int>? = null,
    /**
     * The rule that answered most recently, if the caller can vouch that this list is the one it fired
     * in. Null is the right answer whenever it cannot — see [RuleCard]'s marking.
     */
    firedRule: RuleFiredMark? = null,
    /**
     * Who the venue declares, and the part each plays. Null where there is no venue in hand: a rule's relay
     * rows then appear only when it already uses them, and nothing is judged against a list nobody gave.
     */
    counterparties: List<Counterparty>? = null,
    /** The CompIDs logged on to the venue now, so a step can say who it would reach. Null: not known. */
    onlineCompIds: Set<String>? = null,
) {
    // ---- why the touch target is set here
    //
    // Material3 gives every IconButton a 48dp minimum *touch target*, whatever it is drawn at. These
    // rows are 16dp buttons 4dp apart — a pitch of 20dp — so each button's target swallowed its left
    // neighbour's whole visual area, and hit-testing goes to the last sibling drawn. The result: in a
    // row of four, only the rightmost one could be clicked. Everything else looked enabled, drew its
    // hover, and did nothing, which is the worst way for a control to fail because nothing about it
    // says so.
    //
    // Named here rather than by spacing the buttons out: the density is the design, and this is the
    // knob Material3 provides for it — the same one ScenarioDocumentPane and ScenariosRail already set
    // for their own dense rows. DenseIconRowClickTest holds the general case; the row tests below click
    // each button of each row on this surface.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 16.dp) {
        AcceptorRulesEditorContent(
            rules,
            onRulesChange,
            modifier,
            dictionary,
            onOpenStepInEditor,
            editingStep,
            firedRule,
            counterparties,
            onlineCompIds,
        )
    }
}

@Composable
private fun AcceptorRulesEditorContent(
    rules: List<AcceptorResponseRule>,
    onRulesChange: (List<AcceptorResponseRule>) -> Unit,
    modifier: Modifier,
    dictionary: FixDictionary?,
    onOpenStepInEditor: ((ruleIndex: Int, stepIndex: Int) -> Unit)?,
    editingStep: Pair<Int, Int>?,
    firedRule: RuleFiredMark?,
    counterparties: List<Counterparty>?,
    onlineCompIds: Set<String>?,
) {
    // The connection panel is drag-resizable from a tenth of the window to six tenths of it, so this
    // editor is asked to live at anything from ~250dp to ~1000dp. Sized for the narrow end only, it
    // stayed narrow-shaped inside all that room: a template wrapping across three lines in a column
    // with 700dp of empty space beside it. Measured here, once, and the answer handed down — so every
    // row of every rule agrees about which layout it is in.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN

        // Which rule was just displaced, and why. Transient on purpose: it explains the edit that has
        // just happened, not a property of the rule, so the next edit of any kind clears it.
        var placement by remember { mutableStateOf<Pair<Int, String>?>(null) }

        // ---- why cards start closed
        //
        // The FX venue preset is twenty-one rules, and open they were twenty-one forms: a checkbox, a
        // MsgType field, a matcher per condition, a delay and a raw template per step. None of that is
        // wrong to *edit* and all of it is wrong to *read* — which is what somebody does first, and
        // what a demo does exclusively. Closed, a rule is two lines saying what it answers and what it
        // sends back, and twenty-one of those are a list you can scroll.
        //
        // Kept here by position rather than per card, so one button can open or close the lot.
        var expanded by remember { mutableStateOf(emptySet<Int>()) }

        fun edit(updated: List<AcceptorResponseRule>) {
            placement = null
            onRulesChange(updated)
        }

        // A move or a delete renumbers every rule below it, and these are positions — so the honest
        // thing is to drop them all rather than leave a card that was open showing its neighbour's
        // fields. Only these two: routing an ordinary edit through here would close the card being
        // typed into on every keystroke.
        fun structuralEdit(updated: List<AcceptorResponseRule>) {
            expanded = emptySet()
            edit(updated)
        }

        fun add(preset: AcceptorPreset) {
            val insertion = AcceptorPresets.insert(rules, preset)
            onRulesChange(insertion.rules)
            placement = insertion.note?.let { insertion.index to it }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ruleSummary(rules),
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 9.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (rules.isNotEmpty()) {
                        val allOpen = expanded.size == rules.size
                        TooltipIconButton(
                            tooltip = if (allOpen) "Close every rule" else "Open every rule",
                            onClick = { expanded = if (allOpen) emptySet() else rules.indices.toSet() },
                            modifier = Modifier.size(18.dp).testTag("rules-expand-all"),
                        ) {
                            Icon(
                                imageVector = if (allOpen) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                contentDescription = if (allOpen) "Close every rule" else "Open every rule",
                                tint = AppTheme.Colors.textSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    PresetMenu(existing = rules, onPick = { add(it) })
                    TooltipIconButton(
                        tooltip = "Add an empty rule",
                        onClick = {
                            // Opened as it is added. A preset arrives already saying what it does, so it
                            // is left closed like everything else; an empty rule says nothing until it
                            // is filled in, and the fields to fill are the ones behind the fold.
                            expanded = expanded + rules.size
                            edit(rules + AcceptorResponseRule(whenMsgType = "", steps = listOf(ResponseStep(template = ""))))
                        },
                        modifier = Modifier.size(18.dp),
                    ) {
                        Icon(Icons.Default.Add, "Add rule", tint = AppTheme.Colors.primary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Offered only while there is nothing here. It is the answer to the question an empty list
            // poses — "what does this venue do?" — and once anything answers it, the menu is the way in.
            if (rules.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)?.let { starter ->
                        SlimButton(
                            text = "Starter venue — ${starter.rules.size} rules",
                            onClick = { add(starter) },
                            color = AppTheme.Colors.primary,
                        )
                    }
                    Text(
                        text = "acknowledge and fill, cancel, replace",
                        color = AppTheme.Colors.textDisabled,
                        fontSize = 9.sp,
                    )
                }
            }

            rules.forEachIndexed { ruleIndex, rule ->
                RuleCard(
                    rule = rule,
                    position = ruleIndex,
                    total = rules.size,
                    dictionary = dictionary,
                    wide = wide,
                    shadowedBy = AcceptorResponder.shadowingRule(rules, ruleIndex),
                    note = placement?.takeIf { it.first == ruleIndex }?.second,
                    fired = firedRule?.takeIf { it.ruleIndex == ruleIndex },
                    counterparties = counterparties,
                    onlineCompIds = onlineCompIds,
                    expanded = ruleIndex in expanded,
                    onToggleExpanded = {
                        expanded = if (ruleIndex in expanded) expanded - ruleIndex else expanded + ruleIndex
                    },
                    onOpenStep = onOpenStepInEditor?.let { open -> { step -> open(ruleIndex, step) } },
                    editingStep = editingStep?.takeIf { it.first == ruleIndex }?.second,
                    onChange = { updated -> edit(rules.replaced(ruleIndex, updated)) },
                    onDelete = { structuralEdit(rules.without(ruleIndex)) },
                    onMove = { by -> structuralEdit(rules.moved(ruleIndex, by)) },
                )
            }
        }
    }
}

/**
 * The preset library, as a menu.
 *
 * Grouped by what a venue does rather than by message type, because a tester arrives wanting "an order
 * that fills" or "a cancel that gets rejected" and not wanting 35=D. Each entry's tooltip is the rule
 * **as it will be inserted** — the template text, not a rendering of it, so what lands in the editor is
 * what was read. `/acceptor/test` is where a rendering belongs; it has a message to render against.
 */
@Composable
private fun PresetMenu(existing: List<AcceptorResponseRule>, onPick: (AcceptorPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        SlimButton(text = "+ preset", onClick = { open = true }, color = AppTheme.Colors.primary)
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(AppTheme.Colors.surface),
        ) {
            var lastGroup: String? = null
            AcceptorPresets.all.forEach { preset ->
                if (preset.group != lastGroup) {
                    lastGroup = preset.group
                    Text(
                        text = preset.group,
                        color = AppTheme.Colors.textDisabled,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
                    )
                }
                AppTooltip(text = presetPreview(preset, existing), monospace = true, maxWidth = 520.dp) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(preset.name, color = AppTheme.Colors.text, fontSize = 10.sp)
                                Text(preset.summary, color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
                            }
                        },
                        onClick = {
                            onPick(preset)
                            open = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** What a preset will insert, and — when it matters — where. */
internal fun presetPreview(preset: AcceptorPreset, existing: List<AcceptorResponseRule>): String {
    val body =
        if (preset.rules.size == 1) {
            val rule = preset.rules.single()
            (listOf(triggerLine(rule)) + stepLines(rule)).joinToString("\n")
        } else {
            preset.rules.joinToString("\n") { rule ->
                "• ${triggerLine(rule)} · ${rule.sequence().size} step(s)"
            }
        }
    val note = AcceptorPresets.insert(existing, preset).note
    return body + (note?.let { "\n\n$it" } ?: "")
}

private fun triggerLine(rule: AcceptorResponseRule): String =
    "when 35=${rule.whenMsgType}" +
        rule.trigger().joinToString("") { condition -> " and " + conditionPhrase(condition) } +
        // Last, and in words, because it is the one constraint that is not about the message at all —
        // reading it as though it were another tag is the misreading worth spending four characters on.
        (rule.whenOrder?.let { " and the order is ${it.word}" } ?: "") +
        (rule.whenQuote?.let { " and the quote is ${it.word}" } ?: "") +
        (rule.whenResponders?.let { " and responders online: $it" } ?: "")

/**
 * One condition as the card reads it: `38 range > 10000000`, in the matcher vocabulary — except the two that ask
 * the venue rather than the tag they sit on, which are said as the rows that edit them say them. `49 role
 * requester` would read as a claim about SenderCompID's value, and it is not one.
 */
private fun conditionPhrase(condition: FieldCondition): String =
    when (val matcher = condition.parsed()) {
        is Matcher.CounterpartyRole -> "the sender is ${matcher.role}"
        is Matcher.RfqState -> "the RFQ is ${matcher.state} (via ${condition.tag})"
        null -> "${condition.tag} ?"
        else -> "${condition.tag} " + ExpectationEvaluator.describe(matcher)
    }

/**
 * **A rule in one line: what has to be true, and what goes back.**
 *
 * The closed card's whole content, so it carries the two facts the open card spends a form on. Written
 * in the tool's own matcher vocabulary (`oneOf [...]`, `range > 10000000`) rather than translated into
 * English, because that is the vocabulary of the editor the reader is about to open, of the scenario
 * workbench's expectations, and of `/acceptor/test`'s verdicts — a fourth phrasing that existed only
 * on the closed card would be a dialect nobody could search for.
 *
 * The reply is a count and a span rather than its templates: `3 steps over 500ms` is the claim a
 * sequence makes about time, and it is the one thing about a rule that is invisible in the raw JSON
 * the templates are made of.
 */
private fun ruleDigest(rule: AcceptorResponseRule): String {
    val conditions = rule.trigger().map(::conditionPhrase)
    // Last, and in words, for the same reason [triggerLine] puts it last: it is the one clause that is
    // not about the message at all.
    val order = rule.whenOrder?.let { "the order is ${it.word}" }
    val quote = rule.whenQuote?.let { "the quote is ${it.word}" }
    val responders = rule.whenResponders?.let { "responders online: $it" }
    // Said out loud, because "no conditions" is not a rule doing nothing — it is the catch-all, and the
    // reason every card above it in the same MsgType has to be read in order.
    val trigger =
        (conditions + listOfNotNull(order, quote, responders)).ifEmpty { listOf("any 35=${rule.whenMsgType}") }

    val steps = rule.sequence()
    val span = steps.sumOf { it.delayMillis.coerceAtLeast(0) }
    val reply =
        when {
            steps.size == 1 -> "1 step"
            span > 0 -> "${steps.size} steps over ${span}ms"
            else -> "${steps.size} steps"
        }
    // Who the reply goes to, only once it goes to anyone but the sender: a rule written before relaying reads
    // exactly as it did, and a relay rule cannot be mistaken for one that answers the counterparty that asked.
    val addressed =
        if (rule.relays()) {
            reply + " to " + steps.map { addressLabel(it.address(), it.to) }.distinct().joinToString(", ")
        } else {
            reply
        }

    return (trigger + addressed + listOfNotNull("books a trade".takeIf { rule.booksATrade() })).joinToString(" · ")
}

/** The same clock the reply's own `SendReason` line prints, so the card and the message agree. */
private val FIRED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun stepLines(rule: AcceptorResponseRule): List<String> {
    var offset = 0L
    return rule.sequence().mapIndexed { index, step ->
        offset += step.delayMillis.coerceAtLeast(0)
        val to = step.address()?.takeIf { it.relays }?.let { " to ${addressLabel(it, step.to)}" }.orEmpty()
        "${index + 1}. +${offset}ms$to  ${step.template}"
    }
}

/** An address as a card prints it: the word, or the CompID alone for one named counterparty. */
internal fun addressLabel(address: StepAddress?, written: String?): String =
    when (address) {
        null -> "'$written'"
        is StepAddress.CompId -> address.compId
        else -> address.word
    }

/**
 * Below this the rows stack; at or above it they sit on one line.
 *
 * It is the width at which a step's template still gets ~300dp after the number, the delay, the
 * running total and three buttons have taken theirs — i.e. the point where one line stops being a
 * worse way to show the same string.
 */
private val WIDE_LAYOUT_MIN = 560.dp

@Composable
private fun RuleCard(
    rule: AcceptorResponseRule,
    position: Int,
    total: Int,
    dictionary: FixDictionary?,
    wide: Boolean,
    /** The earlier rule that answers every message of this type, if one provably does. */
    shadowedBy: Int?,
    /** Why this rule is where it is, when it did not simply go on the end. Cleared by the next edit. */
    note: String?,
    /** Set when this is the rule that answered most recently. */
    fired: RuleFiredMark?,
    counterparties: List<Counterparty>?,
    onlineCompIds: Set<String>?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenStep: ((Int) -> Unit)?,
    /** Which of this rule's steps is open in the message editor, if one is. */
    editingStep: Int?,
    onChange: (AcceptorResponseRule) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    // The editor always edits the sequence, whichever way the rule spelled it, and any edit writes the
    // sequence back — which is what retires the older spelling for that rule and only that rule.
    val steps = rule.sequence()

    fun withSteps(updated: List<ResponseStep>) = onChange(rule.copy(steps = updated, responseTemplate = ""))

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(2.dp))
                // **The rule that just answered, wearing the colour the grid already uses for "this
                // just happened".** Drawn over the card's own fill rather than replacing it, because
                // it is a 25%-opacity wash — the same one a recently-sent message row gets, so the two
                // halves of "my order went out and rule 7 answered it" are marked in one language.
                //
                // Never the only signal: the header prints the time beside it. A mark a colour-blind
                // reader cannot see is a mark that is not there, and this one exists to be pointed at.
                .then(
                    if (fired != null) {
                        Modifier.background(AppTheme.Colors.messageRecentlySent, RoundedCornerShape(2.dp))
                    } else {
                        Modifier
                    },
                ).padding(4.dp)
                // Dimmed, not hidden and not greyed into unreadability: a disabled rule is still being
                // read and edited — switching it off is how an author asks "what happens without this
                // one", and the answer is only useful while they can still see what "this one" was.
                .alpha(if (rule.enabled) 1f else 0.45f),
    ) {
        // ---- trigger
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TooltipIconButton(
                tooltip = if (rule.enabled) "Disable this rule" else "Enable this rule",
                onClick = { onChange(rule.copy(enabled = !rule.enabled)) },
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    imageVector = if (rule.enabled) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (rule.enabled) "Enabled" else "Disabled",
                    tint = if (rule.enabled) AppTheme.Colors.primary else AppTheme.Colors.textDisabled,
                    modifier = Modifier.size(12.dp),
                )
            }
            // ---- the number
            //
            // Every other place that names a rule names it by this: the shadowing warning below says
            // "rule 1 answers every 35=D", `SendReason` puts "sent by rule 7" on each reply the venue
            // sends, and `/acceptor/rules` addresses rules by index. The card was the one surface that
            // knew its own number and did not print it — so a reader told which rule answered them had
            // no way to find it, and counted cards.
            Text(
                text = "${position + 1}.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("rule-number-$position"),
            )
            Text("When 35=", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
            SlimField(
                value = rule.whenMsgType,
                onValueChange = { onChange(rule.copy(whenMsgType = it)) },
                modifier = Modifier.width(40.dp),
                monospace = true,
                tintBlank = true,
                placeholder = "D",
            )
            Spacer(Modifier.weight(1f))
            fired?.let {
                Text(
                    text = "fired ${it.at.format(FIRED_AT)}",
                    color = AppTheme.Colors.highlightCurrent,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 2.dp).testTag("rule-fired-$position"),
                )
            }
            // First match wins, so a rule's position is part of what it means — not a display preference.
            TooltipIconButton("Move earlier", { onMove(-1) }, Modifier.size(16.dp), enabled = position > 0) {
                Icon(Icons.Default.ArrowUpward, "Move rule earlier", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(12.dp))
            }
            TooltipIconButton("Move later", { onMove(1) }, Modifier.size(16.dp), enabled = position < total - 1) {
                Icon(Icons.Default.ArrowDownward, "Move rule later", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(12.dp))
            }
            // A deleted rule takes its steps with it, so it asks in the row it sat in. Keyed on the
            // rule's position, so re-ordering the list disarms rather than leaving the question pointed
            // at whichever rule slid into that slot.
            val armed = rememberArmed(position)
            InlineConfirm(
                armed = armed.value,
                onConfirm = {
                    armed.value = false
                    onDelete()
                },
                onCancel = { armed.value = false },
                tag = "rule-delete-confirm-$position",
            ) {
                TooltipIconButton(
                    "Delete rule",
                    { armed.value = true },
                    Modifier.size(16.dp).testTag("rule-delete-$position"),
                ) {
                    Icon(Icons.Default.Close, "Delete rule", tint = AppTheme.Colors.error, modifier = Modifier.size(12.dp))
                }
            }
            TooltipIconButton(
                tooltip = if (expanded) "Close this rule" else "Open this rule to edit it",
                onClick = onToggleExpanded,
                modifier = Modifier.size(16.dp).testTag("rule-expand-$position"),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Close rule" else "Open rule",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        // ---- closed: the rule in a line
        //
        // What it asks of a message and what it sends back, which together are the whole of what a rule
        // *is*. Clickable, because a reader who has read it and wants the fields is already pointing at
        // it — and the row is text, so there is no field here for the click to belong to.
        if (!expanded) {
            Text(
                text = ruleDigest(rule),
                color = AppTheme.Colors.textSecondary,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleExpanded)
                        .padding(start = 8.dp, top = 1.dp, bottom = 1.dp)
                        .testTag("rule-digest-$position"),
            )
        }

        // ---- open: the fields
        //
        // Behind the fold and nothing else is — every warning below stays on the closed card, because a
        // rule that can never fire has to say so whether or not anybody has opened it.
        if (expanded) {
            // Both spellings, shown as the one list they behave as. Any edit writes the whole list back
            // as `conditions` and clears `whenFields` — the same rule as the reply's two spellings:
            // migration is a consequence of editing, never of looking.
            val conditions = rule.trigger()

            fun withConditions(updated: List<FieldCondition>) =
                onChange(rule.copy(whenFields = emptyMap(), conditions = updated))

            // ---- the venue's rows, and the conditions they stand for
            //
            // Shown on a venue that declares who plays what, and on any rule already written in these terms
            // wherever it is opened — a rule that relays has to show how it is triggered even where nobody
            // has declared anything, or the refusal below it would name rows the author cannot find. Kept
            // off every other card, where three rows about RFQs would be furniture on an equity venue.
            //
            // The first `role` on 49 and the first `rfq` on 131 or 117 are drawn as rows and left out of the
            // list; a second of either stays in the list, where the matcher editor still reads it. Nothing a
            // hand-edited profile carries is hidden.
            val venueRows =
                counterparties.orEmpty().isNotEmpty() || rule.asksTheVenue() || rule.whenResponders != null || rule.relays()
            val senderAt = if (venueRows) senderRowIndex(conditions) else -1
            val rfqAt = if (venueRows) rfqRowIndex(conditions) else -1

            conditions.forEachIndexed { index, condition ->
                if (index == senderAt || index == rfqAt) return@forEachIndexed
                ConditionRow(
                    condition = condition,
                    dictionary = dictionary,
                    wide = wide,
                    onChange = { updated -> withConditions(conditions.replaced(index, updated)) },
                    onDelete = { withConditions(conditions.without(index)) },
                )
            }

            // The one condition no tag can express, so it gets a row of its own rather than a place in
            // the list above. It is always shown — including as "any", its off position — because a rule
            // that *could* ask the book and does not is a thing an author needs to see in order to
            // change, and hiding it behind an "+ add" would make the venue's memory a feature you have
            // to already know about. See decision 1: everything the book causes is on a card that can
            // be read.
            OrderConstraintRow(
                constraint = rule.whenOrder,
                onChange = { updated -> onChange(rule.copy(whenOrder = updated)) },
            )

            // The quote book's row, on the same terms and for the same reason. Always shown, including
            // at "any": an RFQ venue that could refuse an expired hit and does not is the single most
            // common way a simulated venue stops behaving like a real one, and it must be visible on
            // the card rather than something an author has to know to go looking for.
            QuoteConstraintRow(
                constraint = rule.whenQuote,
                onChange = { updated -> onChange(rule.copy(whenQuote = updated)) },
            )

            if (venueRows) {
                val senderRole = (conditions.getOrNull(senderAt)?.parsed() as? Matcher.CounterpartyRole)?.role
                VenueWordRow(
                    label = "and the sender is",
                    current = senderRole,
                    words = SenderRole.words,
                    meaning = ::senderRoleMeaning,
                    tag = "rule-when-sender",
                    onChange = { word ->
                        val updated = conditions.withRow(senderAt, TAG_SENDER_COMP_ID, word?.let { Matcher.CounterpartyRole(it) })
                        if (updated != conditions) withConditions(updated)
                    },
                )

                val rfqCondition = conditions.getOrNull(rfqAt)
                val rfqState = (rfqCondition?.parsed() as? Matcher.RfqState)?.state
                VenueWordRow(
                    label = "and the RFQ is",
                    current = rfqState,
                    words = RfqConstraint.words,
                    meaning = { word -> rfqStateMeaning(word, rfqCondition?.tag ?: defaultRfqTag(rule.whenMsgType)) },
                    tag = "rule-when-rfq",
                    onChange = { word ->
                        val tag = rfqCondition?.tag ?: defaultRfqTag(rule.whenMsgType)
                        val updated = conditions.withRow(rfqAt, tag, word?.let { Matcher.RfqState(it) })
                        if (updated != conditions) withConditions(updated)
                    },
                ) {
                    // Which id names the RFQ is the author's call and not the MsgType's: a lift names the quote it
                    // hits (117), a dealer's quote names the request it answers (131), and a QuoteCancel carries
                    // both. Offered once the row asks anything, because "any" reads nothing through anything.
                    if (rfqCondition != null && rfqState != null) {
                        RfqTagMenu(
                            tag = rfqCondition.tag,
                            onChange = { tag -> withConditions(conditions.replaced(rfqAt, rfqCondition.copy(tag = tag))) },
                        )
                    }
                }

                VenueWordRow(
                    label = "and responders online",
                    current = rule.whenResponders,
                    words = RespondersOnline.words,
                    meaning = ::respondersOnlineMeaning,
                    tag = "rule-responders-online",
                    onChange = { word -> if (word != rule.whenResponders) onChange(rule.copy(whenResponders = word)) },
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 8.dp)) {
                SlimButton(
                    text = "+ condition",
                    onClick = {
                        withConditions(conditions + FieldCondition(0, MatcherCodec.matcherToJson(Matcher.Exact(""))))
                    },
                )
            }

            // ---- reply
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Reply", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
                SlimButton(
                    text = "+ step",
                    onClick = { withSteps(steps + ResponseStep(template = "", delayMillis = 0)) },
                )
            }

            var offset = 0L
            steps.forEachIndexed { stepIndex, step ->
                offset += step.delayMillis.coerceAtLeast(0)
                StepRow(
                    step = step,
                    number = stepIndex + 1,
                    ruleNumber = position,
                    offsetMillis = offset,
                    wide = wide,
                    canMoveUp = stepIndex > 0,
                    canMoveDown = stepIndex < steps.size - 1,
                    onOpen = onOpenStep?.let { open -> { open(stepIndex) } },
                    editing = editingStep == stepIndex,
                    counterparties = counterparties,
                    onlineCompIds = onlineCompIds,
                    onChange = { updated -> withSteps(steps.replaced(stepIndex, updated)) },
                    onDelete = { withSteps(steps.without(stepIndex)) },
                    onMove = { by -> withSteps(steps.moved(stepIndex, by)) },
                )
            }

            // Said on the card because it is the one thing a relay rule does that no step shows: the book
            // records the trade when the rule is chosen, before the first step leaves (decision R3), so a
            // second lift already queued behind this one reads "done" and is refused by whichever rule says so.
            if (rule.booksATrade()) {
                Text(
                    text = "↳ books a trade: the RFQ is done the moment this rule fires, so a second lift reads done",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp, start = 8.dp).testTag("rule-books-trade-$position"),
                )
            }
        }

        // Said here because there is nowhere else it can be said: a rule that cannot reply looks
        // configured, and the engine only warns to a log nobody has open. Judged against the venue's
        // counterparties when the caller has them, which is what the venue itself will judge it against.
        rule.validationError(counterparties)?.let { problem ->
            Text(
                text = "⚠ $problem",
                color = AppTheme.Colors.warning,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // The same slot, for the fault a well-formed rule can still have: it is unreachable. Nothing
        // about the rule is wrong, so it validates, and nothing comes back at run time either — which
        // reads as the venue being broken rather than the list being ordered.
        shadowedBy?.let { earlier ->
            Text(
                text =
                    "⚠ never fires — rule ${earlier + 1} answers every 35=${rule.whenMsgType}. " +
                        "Move it earlier, or give it a condition.",
                color = AppTheme.Colors.warning,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        note?.let {
            Text(
                text = "↳ $it",
                color = AppTheme.Colors.textDisabled,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "3 rules, first match wins · 1 off" — a switched-off rule has to be visible in the count, not just on its card. */
private fun ruleSummary(rules: List<AcceptorResponseRule>): String {
    if (rules.isEmpty()) return "No rules — incoming messages get no reply"
    val off = rules.count { !it.enabled }
    return "${rules.size} rule(s), first match wins" + if (off > 0) " · $off off" else ""
}

/**
 * One condition: the tag on its own row with the delete button, the matcher on the next.
 *
 * The matcher is edited by [MatcherEditor] — *the* matcher editor, the one the scenario workbench
 * uses. That is the whole point of the trigger speaking [Matcher] rather than a conditional DSL of
 * its own: a second editor would be a second vocabulary wearing the first one's clothes, and the two
 * would drift. `reference` is withheld because it resolves against a scenario run's scope and a
 * trigger has none; [FieldCondition.reason] refuses one that arrives by hand-edited JSON.
 */
@Composable
private fun ConditionRow(
    condition: FieldCondition,
    dictionary: FixDictionary?,
    wide: Boolean,
    onChange: (FieldCondition) -> Unit,
    onDelete: () -> Unit,
) {
    val matcher = condition.parsed()
    val enumValues =
        remember(dictionary, condition.tag) {
            if (dictionary?.hasFieldValues(condition.tag) == true) {
                dictionary.getFieldEnumValues(condition.tag)
            } else {
                emptyList()
            }
        }
    val offFamily = remember(dictionary, condition.tag) { offFamilyMatchers(condition.tag, dictionary) }

    val tagField: @Composable () -> Unit = {
        Text("and tag", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
        SlimField(
            value = if (condition.tag == 0) "" else condition.tag.toString(),
            onValueChange = { typed ->
                onChange(condition.copy(tag = typed.filter { it.isDigit() }.toIntOrNull() ?: 0))
            },
            modifier = Modifier.width(44.dp),
            monospace = true,
            tintBlank = true,
            placeholder = "38",
        )
    }
    val removeButton: @Composable () -> Unit = {
        TooltipIconButton("Remove condition", onDelete, Modifier.size(16.dp)) {
            Icon(Icons.Default.Close, "Remove condition", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(10.dp))
        }
    }
    // Carried verbatim rather than replaced with a default: a matcher this build cannot read is still
    // the author's, and silently swapping it for `exact ""` would lose what they wrote while making the
    // row look fine.
    val unreadable: @Composable (Modifier) -> Unit = { mod ->
        Text("⚠ ${condition.reason()}", color = AppTheme.Colors.warning, fontSize = 9.sp, modifier = mod)
    }
    val editor: @Composable (Modifier) -> Unit = { mod ->
        MatcherEditor(
            matcher = matcher ?: Matcher.Exact(""),
            capturedValue = (matcher as? Matcher.Exact)?.value.orEmpty(),
            onChange = { updated -> onChange(condition.copy(matcher = MatcherCodec.matcherToJson(updated))) },
            modifier = mod,
            types = TRIGGER_MATCHER_TYPES,
            // The params slot grows with the panel: at the narrow end it is what fits, at the wide end
            // it is what an oneOf set or a two-bound range actually wants.
            paramsWidth = if (wide) 260.dp else 190.dp,
            enumValues = enumValues,
            // A trigger condition is judged by the same evaluator, so a numeric on a STRING tag is the
            // same unjudgeable row here — and worse: a rule that can never fire is a response that never
            // comes, which reads as the venue simulator being broken rather than the condition being.
            offFamily = offFamily,
        )
    }

    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tagField()
            if (matcher == null) unreadable(Modifier.weight(1f)) else editor(Modifier)
            Spacer(Modifier.weight(1f))
            removeButton()
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tagField()
                Spacer(Modifier.weight(1f))
                removeButton()
            }
            if (matcher == null) unreadable(Modifier.padding(top = 2.dp)) else editor(Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * The trigger's matcher list: the scenario's, minus the one only a scenario can resolve, plus the one
 * only a venue can.
 *
 * The two swaps are the same swap. A `reference` names a scenario variable and a trigger has no run to
 * resolve it against; a `quoteField` names the venue's own quote and a scenario has no book to resolve
 * *that* against. Each is refused by name on the other side rather than offered and left to fail
 * silently — see `FieldCondition.reason()` and `Matcher.validationError()`.
 */
private val TRIGGER_MATCHER_TYPES = MATCHER_TYPES.filterNot { it == "reference" } + "quoteField"

/**
 * "and the order is [ any ▾ ]" — the book constraint, as one word from a closed list.
 *
 * A menu rather than a field, because the vocabulary *is* the feature: four words chosen so a tester
 * can hold them in their head (decision 4), and a free-text box would invite `39=1` and then silently
 * never fire. Each entry carries what it means, since "pending" and "working" are the pair an author
 * has to choose between and the difference between them is the venue's own answer having left or not.
 */
@Composable
private fun OrderConstraintRow(constraint: OrderConstraint?, onChange: (OrderConstraint?) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("and the order is", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
        Box {
            SlimButton(
                text = (constraint?.word ?: "any") + " ▾",
                onClick = { open = true },
                color = if (constraint == null) AppTheme.Colors.textDisabled else AppTheme.Colors.primary,
                modifier = Modifier.testTag("rule-when-order"),
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(AppTheme.Colors.surface),
            ) {
                (listOf(null) + OrderConstraint.entries).forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option?.word ?: "any", color = AppTheme.Colors.text, fontSize = 10.sp)
                                Text(
                                    text = orderConstraintMeaning(option),
                                    color = AppTheme.Colors.textDisabled,
                                    fontSize = 9.sp,
                                )
                            }
                        },
                        onClick = {
                            onChange(option)
                            open = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The quote book's constraint, picked from its four words — [OrderConstraintRow]'s twin, and written
 * as one on purpose.
 *
 * Two rows rather than one combined "and the venue is" control, because a rule may ask both and they
 * are about different things: `35=AJ` names a quote, and the same message may also name an order the
 * venue is holding. Folding them together would force an author to choose.
 */
@Composable
private fun QuoteConstraintRow(constraint: QuoteConstraint?, onChange: (QuoteConstraint?) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("and the quote is", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
        Box {
            SlimButton(
                text = (constraint?.word ?: "any") + " ▾",
                onClick = { open = true },
                color = if (constraint == null) AppTheme.Colors.textDisabled else AppTheme.Colors.primary,
                modifier = Modifier.testTag("rule-when-quote"),
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(AppTheme.Colors.surface),
            ) {
                (listOf(null) + QuoteConstraint.entries).forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option?.word ?: "any", color = AppTheme.Colors.text, fontSize = 10.sp)
                                Text(
                                    text = quoteConstraintMeaning(option),
                                    color = AppTheme.Colors.textDisabled,
                                    fontSize = 9.sp,
                                )
                            }
                        },
                        onClick = {
                            onChange(option)
                            open = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

internal fun quoteConstraintMeaning(constraint: QuoteConstraint?): String =
    when (constraint) {
        null -> "the rule does not ask the quote book"
        QuoteConstraint.UNKNOWN -> "this venue never sent that quote"
        QuoteConstraint.OPEN -> "sent, unanswered, still inside its validity"
        QuoteConstraint.EXPIRED -> "sent and unanswered, but its ValidUntilTime has passed"
        QuoteConstraint.DONE -> "already hit or already passed on"
    }

internal fun orderConstraintMeaning(constraint: OrderConstraint?): String =
    when (constraint) {
        null -> "the rule does not ask the book"
        OrderConstraint.UNKNOWN -> "this venue has never seen it"
        OrderConstraint.PENDING -> "the venue has it; the client has not been told anything yet"
        OrderConstraint.WORKING -> "acknowledged and not finished"
        OrderConstraint.DONE -> "filled, canceled, replaced or rejected"
    }

/**
 * "and the sender is [ requester ▾ ]", and its two siblings: what a trigger asks the **venue**, each one word from
 * a closed list, drawn the way the order and quote rows are and for the same reason — the vocabulary is the
 * feature, and a field would invite a value that never matches.
 *
 * The words are stored as matchers (`role` on 49, `rfq` on 131 or 117) and as a string, never as fields of their
 * own, so an older FixTool drops a relay rule rather than running it as a reply to the sender (decision R1). The
 * row is how an author writes one without having to know that.
 */
@Composable
private fun VenueWordRow(
    label: String,
    current: String?,
    words: List<String>,
    meaning: (String?) -> String,
    tag: String,
    onChange: (String?) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
        Box {
            SlimButton(
                text = (current ?: "any") + " ▾",
                onClick = { open = true },
                color = if (current == null) AppTheme.Colors.textDisabled else AppTheme.Colors.primary,
                modifier = Modifier.testTag(tag),
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(AppTheme.Colors.surface),
            ) {
                (listOf(null) + words).forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option ?: "any", color = AppTheme.Colors.text, fontSize = 10.sp)
                                Text(text = meaning(option), color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
                            }
                        },
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
                    text = {
                        Column {
                            Text("$option", color = AppTheme.Colors.text, fontSize = 10.sp)
                            Text(rfqTagMeaning(option), color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
                        }
                    },
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

private const val TAG_SENDER_COMP_ID = 49
private const val TAG_QUOTE_REQ_ID = 131
private const val TAG_QUOTE_ID = 117
private const val MSG_QUOTE_RESPONSE = "AJ"
private val RFQ_TAGS = listOf(TAG_QUOTE_REQ_ID, TAG_QUOTE_ID)

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

/** The id an "RFQ is" row reads through when it is first set: a lift names a quote, everything else a request. */
internal fun defaultRfqTag(whenMsgType: String): Int = if (whenMsgType.trim() == MSG_QUOTE_RESPONSE) TAG_QUOTE_ID else TAG_QUOTE_REQ_ID

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
        StepAddress.Quoter -> "the responder whose quote it names"
        StepAddress.Cover -> "best other live quote on the traded side"
        StepAddress.Others -> "live quotes, not the quoter or the cover"
        StepAddress.Quoted -> "every responder with a live quote"
        StepAddress.Asked -> "every responder the RFQ went to"
        StepAddress.Responders -> "every declared responder, as it stands when the rule fires"
        is StepAddress.CompId -> "one named counterparty"
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
            val role = roleOf(counterparties, address.compId)
            val part = role?.word ?: "not a counterparty this venue declares"
            when {
                online == null -> "${address.compId} · $part"
                address.compId in online -> "${address.compId} · $part · logged on"
                else -> "${address.compId} · $part · not logged on, so counted as not delivered"
            }
        }
        else -> null
    }

private fun respondersPreview(counterparties: List<Counterparty>, online: Set<String>?): String {
    val declared = counterparties.filter { PartyRole.byWord(it.role) == PartyRole.RESPONDER }
    if (declared.isEmpty()) return "no counterparty is declared a responder, so this reaches nobody"
    if (online == null) return declared.joinToString(", ") { it.compId }
    val on = online.filter { roleOf(counterparties, it) == PartyRole.RESPONDER }.sorted()
    // A family (`FIDLRLG*`) is only ever reached through the members that are logged on, so it has no absentees
    // to count; a CompID named exactly and not logged on is owed the message and counted as not delivered.
    val off = declared.filterNot { it.isPrefix || it.compId in online }.map { it.compId }
    return listOfNotNull(
        on.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")} online now" },
        off.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")} not logged on, so counted as not delivered" },
    ).ifEmpty { listOf("no responder is logged on now") }
        .joinToString(" · ")
}

@Composable
private fun StepRow(
    step: ResponseStep,
    number: Int,
    /** This step's rule, so a test can address one step of one rule and not the third icon on screen. */
    ruleNumber: Int,
    offsetMillis: Long,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    wide: Boolean,
    /** Opens this step in the message editor, where the tags have names and the values have menus. */
    onOpen: (() -> Unit)?,
    editing: Boolean,
    counterparties: List<Counterparty>?,
    onlineCompIds: Set<String>?,
    onChange: (ResponseStep) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    // Offered on a venue that declares counterparties, and on any step that already goes somewhere — never on a
    // venue with no parties to address, where "to sender" on every step would be a menu with one right answer.
    val address = step.address()
    val showTo = counterparties.orEmpty().isNotEmpty() || step.to != null
    val preview = address?.takeIf { it.relays }?.let { recipientsPreview(it, counterparties.orEmpty(), onlineCompIds) }

    val timing: @Composable () -> Unit = {
        Text("$number.", color = AppTheme.Colors.textDisabled, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        // Beside the step's number, because that is what it opens. Kept away from the template field:
        // a control sharing an edge with a text field is a control that field can take the click for.
        onOpen?.let { open ->
            TooltipIconButton(
                tooltip = "Edit this step in the message editor",
                onClick = open,
                modifier = Modifier.size(16.dp).testTag("step-edit-$ruleNumber-$number"),
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Edit in the message editor",
                    tint = if (editing) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Text("+", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
        SlimField(
            // Only digits reach the model, so a half-typed value cannot silently become 0 and move
            // the step to a moment the author never chose.
            value = step.delayMillis.toString(),
            onValueChange = { typed ->
                val digits = typed.filter { it.isDigit() }
                onChange(step.copy(delayMillis = digits.toLongOrNull() ?: 0L))
            },
            modifier = Modifier.width(48.dp),
            monospace = true,
        )
        // The gap and the running total, side by side. The gap is what the author writes; the total
        // is what the counterparty experiences, and seeing both is what reveals that this field is
        // relative — the one thing about a sequence that raw JSON cannot warn anybody about.
        Text("ms → ${offsetMillis}ms", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
        if (showTo) {
            StepToMenu(
                step = step,
                address = address,
                counterparties = counterparties.orEmpty(),
                tag = "step-to-$ruleNumber-$number",
                onChange = onChange,
            )
        }
    }
    val recipients: @Composable () -> Unit = {
        preview?.let {
            Text(
                text = "↳ $it",
                color = AppTheme.Colors.textDisabled,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 16.dp, top = 1.dp).testTag("step-to-preview-$ruleNumber-$number"),
            )
        }
    }
    val buttons: @Composable () -> Unit = {
        TooltipIconButton("Move earlier", { onMove(-1) }, Modifier.size(16.dp), enabled = canMoveUp) {
            Icon(Icons.Default.ArrowUpward, "Move step earlier", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(10.dp))
        }
        TooltipIconButton("Move later", { onMove(1) }, Modifier.size(16.dp), enabled = canMoveDown) {
            Icon(Icons.Default.ArrowDownward, "Move step later", tint = AppTheme.Colors.textSecondary, modifier = Modifier.size(10.dp))
        }
        TooltipIconButton("Delete step", onDelete, Modifier.size(16.dp)) {
            Icon(Icons.Default.Close, "Delete step", tint = AppTheme.Colors.error, modifier = Modifier.size(10.dp))
        }
    }
    // Wrapping is what a narrow panel can do, not what anyone wants: the template is one line of FIX
    // and reads as one line. Given the width, it gets one — and only then, because at the narrow end a
    // single row showed about twenty characters of it.
    val template: @Composable (Modifier) -> Unit = { mod ->
        SlimField(
            value = step.template,
            onValueChange = { onChange(step.copy(template = it)) },
            modifier = mod,
            monospace = true,
            tintBlank = true,
            placeholder = "35=8|150=0|39=0|11=\${req.11}|",
            singleLine = wide,
            maxLines = if (wide) 1 else 4,
        )
    }

    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            timing()
            template(Modifier.weight(1f))
            buttons()
        }
        recipients()
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                timing()
                Spacer(Modifier.weight(1f))
                buttons()
            }
            template(Modifier.fillMaxWidth().padding(top = 2.dp))
            recipients()
        }
    }
}

/**
 * "to quoter ▾": who one step goes to.
 *
 * Picking the sender writes nothing — `to` stays absent — so a step moved back to the sender is byte-for-byte the
 * step it was before relaying existed. An address this build cannot read is shown as written, in the warning
 * colour, and kept until the author picks another: the refusal on the card says what it could have been.
 */
@Composable
private fun StepToMenu(
    step: ResponseStep,
    address: StepAddress?,
    counterparties: List<Counterparty>,
    tag: String,
    onChange: (ResponseStep) -> Unit,
) {
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
                    text = {
                        Column {
                            // The address the step already has, marked, so opening the menu to check where a
                            // step goes answers the question without anything being picked.
                            Text(
                                text = addressLabel(option, null),
                                color = if (option == (address ?: StepAddress.Sender)) AppTheme.Colors.primary else AppTheme.Colors.text,
                                fontSize = 10.sp,
                            )
                            Text(
                                text = addressMeaning(option) + (role?.let { " · $it" } ?: ""),
                                color = AppTheme.Colors.textDisabled,
                                fontSize = 9.sp,
                            )
                        }
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

// ----------------------------------------------------------------- list edits
//
// Ordering is meaning in both lists — rules are first-match-wins, steps are played in order — so a
// move is a real edit and not a view preference. These keep that arithmetic in one place.

internal fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().apply { this[index] = value }

internal fun <T> List<T>.without(index: Int): List<T> = toMutableList().apply { removeAt(index) }

/** Moves the item at [index] by [by] places, clamped — a move off either end is a no-op, not a wrap. */
internal fun <T> List<T>.moved(index: Int, by: Int): List<T> {
    val target = (index + by).coerceIn(0, lastIndex)
    if (target == index) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
