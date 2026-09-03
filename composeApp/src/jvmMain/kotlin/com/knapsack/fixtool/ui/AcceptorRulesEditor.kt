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
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.AcceptorPreset
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.service.AcceptorResponder
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.MatcherCodec
import java.time.LocalDateTime
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
        rule.trigger().joinToString("") { condition ->
            val matcher = condition.parsed()
            " and ${condition.tag} " + (matcher?.let { ExpectationEvaluator.describe(it) } ?: "?")
        } +
        // Last, and in words, because it is the one constraint that is not about the message at all —
        // reading it as though it were another tag is the misreading worth spending four characters on.
        (rule.whenOrder?.let { " and the order is ${it.word}" } ?: "")

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
    val conditions =
        rule.trigger().map { condition ->
            val matcher = condition.parsed()
            "${condition.tag} " + (matcher?.let { ExpectationEvaluator.describe(it) } ?: "?")
        }
    // Last, and in words, for the same reason [triggerLine] puts it last: it is the one clause that is
    // not about the message at all.
    val order = rule.whenOrder?.let { "the order is ${it.word}" }
    // Said out loud, because "no conditions" is not a rule doing nothing — it is the catch-all, and the
    // reason every card above it in the same MsgType has to be read in order.
    val trigger = (conditions + listOfNotNull(order)).ifEmpty { listOf("any 35=${rule.whenMsgType}") }

    val steps = rule.sequence()
    val span = steps.sumOf { it.delayMillis.coerceAtLeast(0) }
    val reply =
        when {
            steps.size == 1 -> "1 step"
            span > 0 -> "${steps.size} steps over ${span}ms"
            else -> "${steps.size} steps"
        }

    return (trigger + reply).joinToString(" · ")
}

/** The same clock the reply's own `SendReason` line prints, so the card and the message agree. */
private val FIRED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun stepLines(rule: AcceptorResponseRule): List<String> {
    var offset = 0L
    return rule.sequence().mapIndexed { index, step ->
        offset += step.delayMillis.coerceAtLeast(0)
        "${index + 1}. +${offset}ms  ${step.template}"
    }
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
            TooltipIconButton("Delete rule", onDelete, Modifier.size(16.dp)) {
                Icon(Icons.Default.Close, "Delete rule", tint = AppTheme.Colors.error, modifier = Modifier.size(12.dp))
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

            conditions.forEachIndexed { index, condition ->
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
                    onChange = { updated -> withSteps(steps.replaced(stepIndex, updated)) },
                    onDelete = { withSteps(steps.without(stepIndex)) },
                    onMove = { by -> withSteps(steps.moved(stepIndex, by)) },
                )
            }
        }

        // Said here because there is nowhere else it can be said: a rule that cannot reply looks
        // configured, and the engine only warns to a log nobody has open.
        rule.validationError()?.let { problem ->
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

/** Every matcher type except `reference`, which needs a scenario scope a trigger does not have. */
private val TRIGGER_MATCHER_TYPES = MATCHER_TYPES.filterNot { it == "reference" }

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

internal fun orderConstraintMeaning(constraint: OrderConstraint?): String =
    when (constraint) {
        null -> "the rule does not ask the book"
        OrderConstraint.UNKNOWN -> "this venue has never seen it"
        OrderConstraint.PENDING -> "the venue has it; the client has not been told anything yet"
        OrderConstraint.WORKING -> "acknowledged and not finished"
        OrderConstraint.DONE -> "filled, canceled, replaced or rejected"
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
    onChange: (ResponseStep) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
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
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 3.dp, start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                timing()
                Spacer(Modifier.weight(1f))
                buttons()
            }
            template(Modifier.fillMaxWidth().padding(top = 2.dp))
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
