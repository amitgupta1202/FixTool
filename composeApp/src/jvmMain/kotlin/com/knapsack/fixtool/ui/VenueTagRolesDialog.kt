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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.TagRole
import com.knapsack.fixtool.model.TagRoleOverlay
import com.knapsack.fixtool.service.FieldSearch
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
 * the whole reason this file has to exist — so every role here is the author's answer. The likely ones are
 * in the opening view and *nothing is hidden*: a correlation id whose name does not end in `ID` is exactly
 * the case a filter would lose, and so is a standard tag FixTool has no opinion about.
 *
 * **Order is the tag number, always.** The tier decides which rows open the dialog, not where they sit:
 * sorting by tier first produced a list of several interleaved ascending runs, so finding `820` meant
 * knowing which tier it had landed in before you could look for it. The tier rides on the row instead, and
 * the search box reaches every tag whether or not the current view holds it.
 */
@Composable
fun VenueTagRolesDialog(
    dictionary: FixDictionaryAdapter?,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val path = dictionary?.getFilePath()
    // Not merely "is there a path": a bundled dictionary has one, pointing at a temp file.
    val savable = path != null && dictionary?.isStandard != true
    val candidates = remember(dictionary) { VenueTagScan.scan(dictionary) }
    // Only what the author actually changed. Seeding this from every candidate's current role and writing
    // the whole map back collapsed a two-role declaration (`QuoteID(117)` is the standard case) to its
    // first role the moment anyone pressed Save on an unrelated row.
    val edits = remember(dictionary) { mutableStateMapOf<Int, TagRole?>() }
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val opening = candidates.filter { it.tier == VenueTagScan.Tier.DECLARED || it.tier == VenueTagScan.Tier.IDENTIFIER }
    val searching = query.isNotBlank()
    val visible =
        when {
            // A query searches the whole dictionary, not the current view: the reason to type `820` is
            // that it is not on screen, so making the author find the right view first defeats the box.
            searching -> candidates.filter { FieldSearch.matches(query, it.tag, it.name, null) }
            showAll -> candidates
            else -> opening
        }

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

            if (!savable) {
                // A bundled dictionary is extracted to a temp file, so it *has* a path while having
                // nowhere durable to put anything beside it: an answer written there is thrown away at
                // the next launch, silently. Say so rather than showing an editor that appears to work.
                Text(
                    if (dictionary?.isStandard == true) {
                        "This is a bundled standard dictionary, extracted to a temp file — a declaration " +
                            "saved beside it would not survive a restart, and FixTool already answers for " +
                            "standard FIX. Point the Data Dictionary at your venue's own file."
                    } else {
                        "No dictionary file is loaded. Venue tag roles live beside the venue's own dictionary — " +
                            "set a data dictionary path first."
                    },
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
                        "This dictionary defines nothing that could carry a role — only the transport " +
                            "envelope, which the engine rewrites on every send whatever is declared here.",
                        color = AppTheme.Colors.textSecondary,
                        fontSize = 11.sp,
                    )
                } else {
                    SlimSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Search all ${candidates.size} tags by number or name…",
                        testTag = "venue-tag-roles-search",
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visible, key = { it.tag }) { candidate ->
                            RoleRow(
                                candidate = candidate,
                                role = if (candidate.tag in edits) edits[candidate.tag] else candidate.roles.firstOrNull(),
                                onRole = { edits[candidate.tag] = it },
                            )
                        }
                    }

                    when {
                        searching ->
                            Text(
                                text = "${visible.size} of ${candidates.size} tags match",
                                color = AppTheme.Colors.textSecondary,
                                fontSize = 10.sp,
                                modifier = Modifier.testTag("venue-tag-roles-match-count"),
                            )
                        candidates.size > opening.size ->
                            Text(
                                text =
                                    if (showAll) {
                                        "▾ showing all ${candidates.size} tags"
                                    } else {
                                        "▸ show all ${candidates.size} tags (${candidates.size - opening.size} more)"
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
                    enabled = savable,
                    color = AppTheme.Colors.primary,
                    onClick = {
                        // Start from what the sidecar already says — including any tag carrying two roles —
                        // and apply only this session's edits over it.
                        val roles = candidates.filter { it.roles.isNotEmpty() }.associate { it.tag to it.roles }.toMutableMap()
                        edits.forEach { (tag, role) -> if (role == null) roles.remove(tag) else roles[tag] = setOf(role) }
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

/**
 * **What already answers for this tag** — the note that makes the built-in tier worth showing.
 *
 * A row saying only "FixTool handles it" invites the author to move on; a row saying *what* it handles it
 * as is the one thing that lets them notice the answer is wrong for their venue and add to it.
 */
private fun note(candidate: VenueTagScan.Candidate): String? =
    when {
        candidate.builtIn.isNotEmpty() ->
            "FixTool: " + candidate.builtIn.sortedBy { it.name }.joinToString(", ") {
                when (it) {
                    TagRole.CLIENT_MINTED_ID -> "we mint it"
                    TagRole.VENUE_MINTED_ID -> "the venue mints it"
                    TagRole.LIFETIME -> "a lifetime stamp"
                }
            }
        candidate.builtInReason != null -> candidate.builtInReason
        candidate.custom -> "this venue's own tag"
        else -> null
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        note(candidate)?.let {
            Text(
                text = it,
                color = AppTheme.Colors.textSecondary,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp),
            )
        }
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
