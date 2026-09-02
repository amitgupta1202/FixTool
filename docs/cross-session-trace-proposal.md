# Trace across sessions — follow one exchange through every pane

**Status: proposal, 2026-09-02.** Nothing here is implemented. It answers "the grouping feature is
useful, but QAs need to see one flow *across* sessions, and today they do that with a regex in the
search box" — and argues the tool already computes the answer and only needs to show it.

**Mockups:** [`docs/mockups/cross-session-trace.html`](mockups/cross-session-trace.html) — interactive
(click **Follow ↗** on a group header, fold groups, switch Ledger / Lanes); download and open locally,
since GitHub does not run a `.html` file's script from its file view. Built against FixTool's own dark
palette so the frames read as the app's screens.

---

## The problem, as the code sees it

A QA testing an RFQ venue holds several sessions in one FixTool: a client session that sends the
QuoteRequest in, and two or three liquidity-provider sessions that receive the venue's forwarded copy
and quote back. One business event leaves messages in every pane, and the venue mints its own ids on
the way through, so the same exchange is `RFQ-A1` on the client, `V-2291` on the LPs, `Q-LP1-77` on
the winning quote, and `V-ORD-8813` once the order lands.

The grouped grid (`Conversations`, shipped 2026-07-22) answers *what happened to RFQ-A1 on this
session* well. Nothing answers *what happened to RFQ-A1*. So the QA reads ids off the panes and types
`RFQ-A1|V-2291|ORD-9` into a filter. There are **four** search surfaces to choose from, and each fails
this job differently:

| Surface | Scope | Matches on | Why it fails cross-session |
|---|---|---|---|
| In-grid find bar (`FixMessageDisplay.kt:93-107`) | one pane | substring of the display string | one pane |
| Per-pane filter panel (`FixMessageSession.kt:104-136`, `SplitView.kt:901-943`) | one pane | regex over the display string | typed once per pane |
| Global filter box (`FixMessageViewModel.kt:5107-5111`) | every pane | regex over the display string | **overwrites** every pane's own filter |
| Search All Sessions + pinned results (`FixMessageViewModel.kt:3799-3883`, `SearchResultsPane.kt`) | every session | regex-or-literal over the display string | query-gated, matched text only, no fields |

And the regex itself is the wrong instrument, in ways that compound:

- **It only finds what the QA already knows.** A venue that mints per hop adds ids as the flow runs
  (`V-ORD-8813` appears three messages in). The regex is stale by the time the order leg arrives.
- **A missed id is silent.** LP-1's order leg is filtered out and *nothing says so*; the QA concludes
  the venue never forwarded the order.
- **Substring, not value.** Filtering runs over `toDisplayString()` (`FixMessage.kt:141-147`), so
  `ORD-9` matches `ORD-91`, and any `OrderID` that contains it.
- **Tabs view does not filter at all.** `App.kt:389` passes the unfiltered list to `FixMessageDisplay`;
  `filterSessionMessages` is only called from `SplitView.kt:819`. The TabBar filter button toggles a
  panel that never applies. *(Defect regardless of this proposal.)*
- **The global box clobbers per-pane filters** (`setGlobalFilterRegex` writes through to every session).
  *(Defect regardless of this proposal.)*
- **Order across panes is in the reader's head**, read off timestamps across four grids.

## What already exists — do not rebuild

- **The relation is already session-agnostic.** `Conversations.group` (`Conversations.kt:95`) takes a
  flat list of messages and joins by shared correlation-id *values* — union-find, no rulebook. Fed all
  sessions' messages at once it joins `RFQ-A1 → Q-LP1-77` (client) to `Q-LP1-77 → V-2291` (LP-1) to
  `V-2291` on every other LP, today, with no algorithm change. What is missing is a session tag on the
  way in and on the way out (`Conversation.indices` addresses one flat list, `Conversations.kt:67`).
- **Which tags are correlation ids is already declared, and extensible.** `Minting.isCorrelationId`
  (`Minting.kt:61`) = the standard client-minted set (`ScenarioCapture.ID_TAGS`) + venue-minted set
  (`ExpectationSeeder.VENUE_MINTED_IDS`) + anything in the dictionary's `.roles.json` sidecar
  (`TagRoles.kt:80-124`). A venue's proprietary echo tag is one sidecar line away from being an edge.
- **The cross-session timeline has been drawn once already**, inside capture: `ScenarioCapture.scan`
  merges all business messages across all sessions chronologically with a `session` per candidate
  (`ScenarioCapture.kt:188, 232-256`), and the review screen renders session badges plus
  `●minted / ○echoed` correlation badges (`ScenarioCaptureReview.kt`). This proposal promotes that
  view from a one-shot inside capture to a live panel.
- **A multi-session list already renders.** `SearchResultsPane` draws messages from many sessions in
  one chronological grid with per-session colour badges (`AppTheme.Colors.usernameColors`) and has a
  bottom-panel slot in all three layouts (`App.kt:442, 701, 900`). The Trace panel lives in that slot.
- **Per-pane view state has a pattern.** Grouping and collapse are per-session on `FixMessageSession`
  because a both-sides test puts the same label in two panes (`FixMessageSession.kt:107-113`). Follow
  is *deliberately not* per-pane — see [State](#state-what-is-global-and-what-is-not).

## The shape

Vocabulary: a **conversation** is what the grid groups today, within one session. A **trace** is the
same relation computed over every session at once. A pane's conversation is the trace's slice on that
session. Nothing about a conversation changes.

### 1. Follow — the gesture that replaces the regex

One action, **Follow across sessions**, in three places: a conversation's header row (grouped view),
any message row's context menu, and any correlation-id field in the detail pane. Following sets the
app's one **followed trace** to the component that contains that id, and:

- **Every pane narrows to the trace's messages**, in tabs *and* split view. This is a structured
  filter (membership in the followed set), never a regex written into `filterRegex` — so a pane's own
  filter is ANDed on top and comes back untouched when following stops.
- **A chip in the toolbar names what is followed**: `Following RFQ-A1 · 4 sessions · 14 messages · ✕`.
  The chip is the whole filter; there is no text to keep in sync.
- **It is live.** The component is recomputed on the same 100 ms drain tick that already regroups
  panes, so an id the venue mints after Follow was pressed joins the trace when it arrives.
- **The Trace panel opens** (below), with the followed trace expanded and every other trace collapsed.
- `Esc` or ✕ clears. Following a second trace replaces the first; there is one followed trace.

### 2. The Trace panel — Ledger

The bottom panel where pinned search results live, showing **every trace across every session**:
the grouped grid the app already draws, over the merged, time-ordered list, with two additions.

| Column | Meaning |
|---|---|
| **Session** | which session the row happened on: colour badge + name, the search-results treatment |
| **Elapsed** | time since the *previous message in this trace*, on whichever session it was |

Header rows summarise as today (`Conversations.summarize`) plus a session count with badges:
`RFQ-A1 · EUR/USD 10M · ●●●● 4 sessions · QuoteRequest ×4 · Quote ×4 · Order ×2 · Filled · 644 ms`.
So *which RFQs touched more than one session* is read off the headers without following anything.

**Elapsed is a measurement, not a diagnosis.** Every timestamp comes from one clock because FixTool
held every session, so `+31 ms` between the client's request going OUT and LP-1's copy coming IN is
the venue's real forwarding time — the number a venue's own logs cannot give and two machines' logs
cannot reconcile. The tool states the gap; it does not say what caused it. (Same stance as
`ScenarioReport`'s diagnosis rows and `Conversations.Summary`: quote, never infer.) The existing
per-session latency column (request→response pairing, opt-in) is unchanged and orthogonal.

**Nothing is hidden.** Ungrouped is last and counted, as today. A trace whose earliest message has
already left a session's 1,000-message ring (`FixMessageSession.kt:54, 365-371`) says
`opened before the buffer` in its header instead of pretending it started later — the discard-counter
discipline, applied to the view.

### 3. The Trace panel — Lanes

A second rendering of the *same rows*, for one followed trace: one column per session, time running
down, each message a chip in its lane with an arrow pointing out of FixTool (OUT) or into it (IN).

- **Initiators left of a dashed rule, acceptors right of it.** Read from the profiles' mode — a fact,
  not an inference about topology.
- **The venue is the space between lanes.** Nothing is drawn there; FixTool holds no session with
  itself. What it holds is *both ends of every hop*, which is why a request leaving the client lane
  and arriving in three acceptor lanes milliseconds later is the one picture the venue cannot draw.
- **Both-sides tests**: when FixTool plays client *and* venue in one instance, the same bytes appear as
  OUT in one lane and IN in another. Lanes draws those as one arrow **when `wireRaw` is identical** —
  a fact about the bytes. The Ledger keeps both rows, because both panes logged it.
- Lanes is for one trace. Browsing many traces is the Ledger's job; the toggle sits in the panel
  header.

### 4. Where the chain breaks — the honest limit

A trace only crosses a session where some *value* crosses it. A venue that mints a fresh id per hop
and echoes nothing leaves no edge, and joining those would be the tool inventing one. Two remedies,
neither of which guesses:

1. **Declare the tag.** Most venues carry the originating id somewhere on the far side, often in a
   proprietary tag. Naming it in the `.roles.json` sidecar makes it a correlation id everywhere
   (already supported, `TagRoles`), and the trace joins with no code. The Ledger's single-session
   traces carry a one-line hint saying exactly this, at the moment it matters.
2. **Link by hand.** `Link to another trace…` on a header: the QA asserts two traces are one exchange.
   Drawn joined with a dotted rule labelled *linked by you*; held for the app's lifetime; never
   inferred from time proximity. *(Follow-up slice, only if 1 proves insufficient in the field.)*

### 5. Control surface

Agents get the same one-call answer QAs get:

- `GET /traces` — the Ledger's headers: label, ids, sessions, counts, elapsed, truncated flag.
- `GET /trace?id=RFQ-A1` — the merged trace at full fidelity: each message with `session`, direction,
  timestamp, and the ordered `fields` array `/messages` already emits (`ControlServer.kt:702`), not the
  matched-text shape `/search` returns.
- `POST /panel {"panel":"trace","follow":"RFQ-A1"}` / `{"panel":"trace","follow":null}` — drive the
  UI, as `"conversations"` does today (`ControlServer.kt:462-482`).

`POST /search` stays as it is.

## Model

```kotlin
/** A message's address across the app: which session's snapshot, and where in it. */
data class Located(val session: SessionRef, val index: Int)

object Traces {
    data class Trace(
        val label: String, val labelTag: Int,
        val ids: Set<String>,
        val members: List<Located>,          // merged, time-ordered
        val sessions: List<SessionRef>,      // first-seen order
        val truncatedAtHead: Boolean,        // opener fell out of a ring
    )
    data class Grouping(val traces: List<Trace>, val ungrouped: List<Located>)

    fun group(snapshots: List<Pair<SessionRef, List<FixMessage>>>, dictionary: FixDictionaryAdapter?): Grouping
}
```

- **`FixMessage` does not grow a session field.** It is a `data class` with structural equality (two
  identical messages on two sessions compare equal) and a wire-fact holder; session is the container's
  knowledge. `Located` carries it, the way `SearchResult.session` and `CapturedSession.title` already do.
- **`Traces.group` is `Conversations.group` with located inputs.** Same union-find, same "only declared
  correlation tags draw edges" rule, same label rule (first id on the earliest message). The two should
  share the `Union` and the id-extraction pass; a pane's conversation must equal the trace's slice, and
  a test pins that (`Traces.group(one session) == Conversations.group(that session)` per message).
- **Time order across sessions** sorts by the same key the search results pane uses: timestamp, then
  `MsgSeqNum`, then `SenderCompID` (`FixMessageViewModel.kt:3866-3871`). One clock makes this sound.
- **Collapse keys are `(opener session, label)`**, never label alone — `ConversationRows.kt:27` already
  warns two venues may both say `ORD-1`, and across sessions that is the normal case, not the edge.
- **Cost.** Union-find is naturally incremental: messages only append, so new edges are added to a
  kept `Union` and only the ring-drop case forces a rebuild. Memoise on the tuple of per-session
  snapshot identities (each snapshot is an immutable list, so identity comparison is free) and recompute
  on the 100 ms tick only when one changed. Add a case to `GroupingBenchmarkTest`: 8 sessions × 1,000
  messages regrouped under the tick.

## State: what is global and what is not

| State | Scope | Why |
|---|---|---|
| followed trace | **app** (one) | it is a cross-pane notion by definition; a per-pane follow is the regex again |
| pane narrowing while following | derived, per pane | membership of that pane's messages in the followed set, ANDed with the pane's own filters |
| Trace panel open / rendering (Ledger, Lanes) | app | one panel, like pinned search results |
| Trace panel collapse set | app, keyed `(session, label)` | the panel is one view; keys are unique across sessions |
| grouping toggle, collapse, per-pane filters | per session, **unchanged** | the both-sides lesson (`55b5da8`) stands |

## Slices

Ranked by what they change about being wrong, not by size.

| Slice | Contents | Why this order |
|---|---|---|
| **Trace A** | Follow (three entry points, chip, live, tabs + split) · Ledger · `/traces`, `/trace`, `/panel` · fix tabs-mode filtering · global filter box stops clobbering (it becomes "set every pane" only when the pane has no filter of its own, or is removed in favour of Follow) | Removes the whole failure class: silent omission, false substring match, stale regex, clobbered filters, no order. Correct in both modes. |
| **Trace B** | Lanes rendering · same-bytes arrow pairing for both-sides tests | Changes how fast one exchange is read; drawn from A's rows, so it cannot disagree with the Ledger. |
| **Trace C** | Link by hand · scenario report deep-link ("show this run's trace") | Only if sidecar declaration proves insufficient in the field; the deep-link is free once A exists. |

## Acceptance criteria — Trace A

- Follow is reachable from a conversation header, a message row's context menu, and a correlation-id
  field in the detail pane; the followed set is the union-find component over **all** sessions'
  current snapshots and grows live as messages arrive.
- While following, every pane (tabs and split) shows only trace messages; a pane's own regex /
  direction / type filters still apply on top; stopping restores them exactly.
- The Trace panel lists every trace with Session and Elapsed columns; the followed trace is expanded,
  others collapsed; Ungrouped last and counted; a head-truncated trace is labelled.
- A pane's conversation equals the trace's slice for that session, message for message (test).
- Collapse keys in the panel are `(session, label)`.
- `GET /trace?id=` returns the merged trace with `session` and `fields` per message; `POST /panel`
  drives follow and rendering; `docs/AUTOMATION.md` documents all three routes.
- Tabs view applies the per-pane filter; the global filter box no longer overwrites a pane's own filter.
- `GroupingBenchmarkTest` covers 8 × 1,000 under the drain tick; a 300-quote live burst on 4 sessions
  keeps the panel responsive (the conversation-view verification recipe, re-run).

## Open questions

1. **Should the chip persist across restarts?** Recommendation: no. A followed trace is a reading
   position, not a setting; it dies with the session buffers it points into.
2. **Follow from the scenario report.** A both-sides scenario already knows which sessions a step ran
   on; "show this run's trace" is one id away. Recommended for Trace C rather than A, so A's scope stays
   the live grid.
3. **Elapsed vs the latency column.** Two numbers with different meanings on adjacent columns is a
   reading hazard. Recommendation: the Trace panel shows Elapsed only; the per-session latency column
   stays in the panes where its pairing is defined.

## Not doing

- **Joining by time proximity**, or any inferred edge. The union-find joins on values or not at all.
- **A sequence-diagram export** (PlantUML, Mermaid). Lanes on screen first; an export is a follow-up if
  anyone asks, and it is a serialisation of A's rows.
- **Persisting traces to disk.** Run records (`docs/multi-run-scenarios-proposal.md`) are the durable
  artifact; the Trace panel reads live buffers.
