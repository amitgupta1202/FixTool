package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * **Two ways to ask before something is lost, and never a third.**
 *
 * The audit behind `docs/mockups/pane-grammar.html` found twenty-two actions that destroy something behind
 * one unguarded click. The answer is not a confirmation dialog: a modal over the window is the one pattern
 * this app has never needed, and it is the one that trains people to click through without reading.
 *
 * So there are two, both of which already shipped in exactly one place each before this file existed:
 *
 *  - **Arm.** The button becomes its own question and the second click is the answer. For the things whose
 *    name is the question — Close *this session* — where a second control would be a second thing to aim
 *    at. It gives up after a few seconds, so an armed button never sits waiting for somebody who has
 *    forgotten what it is armed for. See [rememberArmed].
 *  - **Confirm inline.** Delete and Cancel take the place of the row's own controls, in the row, in place.
 *    For the things where the question is *which* one — a profile in a list of eleven — because an armed
 *    glyph in a list says nothing about which row it belongs to. See [InlineConfirm].
 *
 * **What decides between destroy-with-a-question and destroy-quietly is what comes back.** A cleared pane
 * refills the moment traffic flows, a disconnected session reconnects in one click, a closed workspace
 * reopens from Recent — so Clear, Disconnect and Close workspace stay one click. A closed session takes its
 * log with it and a deleted profile takes its settings, and nothing brings either back.
 */
private const val ARMED_MS = 5_000L

/**
 * **A button that is armed, and disarms itself.**
 *
 * [key] restarts the countdown: an armed Close all whose pane count changed underneath it is armed over a
 * number that is no longer true, so the clock starts again rather than leaving the old question standing.
 *
 * Returns the state rather than a boolean so the caller can disarm it on its own terms — a refusal
 * appearing, the row going away, the thing being destroyed.
 */
@Composable
fun rememberArmed(key: Any? = Unit): MutableState<Boolean> {
    val armed = remember(key) { mutableStateOf(false) }
    if (armed.value) {
        LaunchedEffect(key) {
            delay(ARMED_MS)
            armed.value = false
        }
    }
    return armed
}

/**
 * **Delete and Cancel, in the row the control sat in.**
 *
 * Nothing is drawn over the window: the row's own trailing controls step aside and the question takes their
 * place, so the answer is given where the question was asked and the row it is about is still on screen
 * around it. The Scenarios rail has done it this way since it grew a Delete; this is that, shared.
 *
 * @param armed whether the question is being asked. The caller owns it, because in a list it is
 *   "*which* row is asking" rather than a boolean — one `String?` for the whole list, not one flag per row.
 * @param confirm the destructive verb, which is the word on the button: Delete, Remove, Restore defaults.
 * @param otherwise the controls the row draws when nothing is being asked.
 */
@Composable
fun InlineConfirm(
    armed: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    confirm: String = "Delete",
    tag: String = "inline-confirm",
    otherwise: @Composable () -> Unit,
) {
    if (!armed) {
        otherwise()
        return
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SlimButton(
            confirm,
            onClick = onConfirm,
            color = AppTheme.Colors.error,
            modifier = Modifier.testTag(tag),
        )
        SlimButton(
            "Cancel",
            onClick = onCancel,
            color = AppTheme.Colors.textSecondary,
            modifier = Modifier.testTag("$tag-cancel"),
        )
    }
}
