package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.TagRole
import com.knapsack.fixtool.model.TagRoleOverlay
import com.knapsack.fixtool.service.VenueTagScan

/**
 * **Venue tag roles** — the surface where a venue's own correlation ids get declared.
 *
 * The overlay file could always be written by hand; nobody ever would. You would have to already know the
 * mechanism exists, know your venue's tag numbers, and know that saying nothing quietly degrades every
 * capture — and the failure it prevents is invisible, so the configuration that prevents it is never
 * sought. This is the door: it reads the dictionary the author has **already configured** and shows the
 * dozen tags worth deciding about.
 *
 * Reachable from Settings rather than raised when a dictionary loads, and the difference matters: every
 * existing user configured their dictionary long ago, so a load-time prompt would reach precisely the
 * people who do not need it. A venue shipping a new dictionary version has the same shape.
 *
 * **It proposes, and never decides.** Nothing in a FIX dictionary records who *mints* a value — that is
 * the whole reason this file has to exist — so every role here is the author's answer. The list is sorted
 * so the likely ones are on top and *nothing is hidden*: a correlation id whose name does not end in `ID`
 * is exactly the case a filter would lose.
 */
@Composable
fun VenueTagRolesDialog(
    dictionary: FixDictionaryAdapter?,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val path = dictionary?.getFilePath()
    val candidates = remember(dictionary) { VenueTagScan.scan(dictionary) }
    val chosen = remember(dictionary) { mutableStateMapOf<Int, TagRole?>().apply { candidates.forEach { put(it.tag, it.roles.firstOrNull()) } } }
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val identifiers = candidates.filter { it.tier != VenueTagScan.Tier.OTHER }
    val others = candidates.filter { it.tier == VenueTagScan.Tier.OTHER }
    val visible = if (showAll) candidates else identifiers

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .width(620.dp)
                    .background(AppTheme.Colors.background, RoundedCornerShape(4.dp))
                    .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(4.dp))
                    .padding(12.dp)
                    .testTag("venue-tag-roles"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Venue tag roles", color = AppTheme.Colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)

            if (path == null) {
                // A bundled standard dictionary has no venue tags by definition, and writing a sidecar
                // beside a temp-extracted resource would put the author's decisions somewhere that does
                // not survive a restart. Say so rather than showing an empty list they cannot act on.
                Text(
                    "No dictionary file is loaded. Venue tag roles live beside the venue's own dictionary — " +
                        "set a data dictionary path first.",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 11.sp,
                )
            } else {
                Text(VenueTagScan.summary(dictionary), color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
                Text(
                    "Saved to ${TagRoleOverlay.sidecarFor(path).name}, beside the dictionary — commit it with " +
                        "the dictionary so the team shares one answer.",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 10.sp,
                )

                if (candidates.isEmpty()) {
                    Text(
                        "This dictionary adds nothing beyond standard FIX, so there is nothing to declare.",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 11.sp,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visible, key = { it.tag }) { candidate ->
                            RoleRow(candidate, chosen[candidate.tag]) { chosen[candidate.tag] = it }
                        }
                    }

                    if (others.isNotEmpty()) {
                        Text(
                            text =
                                if (showAll) {
                                    "▾ showing all ${candidates.size} venue tags"
                                } else {
                                    "▸ show all ${candidates.size} venue tags (${others.size} more)"
                                },
                            color = AppTheme.Colors.primary,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable { showAll = !showAll }.testTag("venue-tag-roles-show-all"),
                        )
                    }
                }
            }

            error?.let { Text(it, color = AppTheme.Colors.error, fontSize = 10.sp) }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                SlimButton("Cancel", onClick = onDismiss)
                SlimButton(
                    text = "Save",
                    enabled = path != null,
                    color = AppTheme.Colors.primary,
                    onClick = {
                        val roles = chosen.entries.mapNotNull { (tag, role) -> role?.let { tag to setOf(it) } }.toMap()
                        val result = runCatching { TagRoleOverlay.writeBeside(path!!, roles) }
                        result.fold(
                            onSuccess = { onSaved(it.absolutePath) },
                            onFailure = { error = "could not write the sidecar: ${it.message}" },
                        )
                    },
                )
            }
        }
    }
}

/** The author's vocabulary, not the enum's — `CLIENT_MINTED_ID` is a name for us, not a sentence for them. */
private fun label(role: TagRole): String =
    when (role) {
        TagRole.CLIENT_MINTED_ID -> "we mint it — fresh per run"
        TagRole.VENUE_MINTED_ID -> "the venue mints it"
        TagRole.LIFETIME -> "a lifetime stamp"
    }

@Composable
private fun RoleRow(candidate: VenueTagScan.Candidate, role: TagRole?, onRole: (TagRole?) -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(AppTheme.Colors.surface, RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp)
                .testTag("venue-tag-row-${candidate.tag}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = candidate.tag.toString(),
            color = AppTheme.Colors.textSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp),
        )
        Text(
            // A tag the venue's own dictionary does not name either. Said plainly, because it is the case
            // where the author has least to go on and most reason to be careful.
            text = candidate.name ?: "(unnamed in this dictionary)",
            color = if (candidate.name != null) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.width(190.dp)) {
            SlimDropdown(
                value = role,
                options = TagRole.entries.toList(),
                onValueChange = onRole,
                displayText = { label(it) },
                placeholder = "— not declared —",
                allowUnselect = true,
            )
        }
    }
}
