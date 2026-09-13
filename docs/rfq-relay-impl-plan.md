# RFQ relay, implementation plan

Companion to [the proposal](./rfq-relay-proposal.md), which argues the design (decisions 1 to 11 and 9a).
Mockups: https://claude.ai/code/artifact/40b7ef67-e9fa-4404-88a9-b29ae447ccb8 (source
`docs/mockups/rfq-relay.html`). This document is the build order: what changes, in which file, what each
step's tests are, and what must be true before the next step starts.

**Status: plan, revision 2, not started.** Written 2026-09-13 from four read-only surveys of the source,
then checked by two independent reviewers against `4c2ddcf7`:

- **The fact check** confirmed about 190 file:line and behaviour claims, overturned 9, corrected 8 line
  references, and listed the call sites each step would have missed.
- **The design review** found 13 faults. Three of them broke slice A's own acceptance criteria:
  - the flow stopped at message 3, because the RFQ was looked up before it existed;
  - one RFQ could trade twice;
  - "not delivered" could not be counted as specified.

Everything below is what survived. Anything the reviews changed is marked **[review]**, so a reader of
revision 1 can see what moved.

**The review changed four decisions, and the proposal is amended to match:**
- **Sender role and RFQ state move into `conditions` as two new matcher types**, not new rule fields. An
  older build then drops the whole rule instead of running it looser (fact 2).
- **The book is read twice.** Triggers read it *before* the message is recorded; addresses, `${to.…}`
  and `${rfq.…}` read it *after* (decision R2).
- **A trade is recorded when the rule decides it**, on the callback thread, rather than when the fill
  reaches the wire (decision R3).
- **`${to.117}` follows the quote the trigger names**, not the most recent one (decision R5).

**The open questions take the proposal's defaults:** disclosed both ways, firm, every responder online is
asked, no counters, the runner-up is told Cover, the FI desk is replaced. The review added one more,
question 7, and **it is answered**: on BrokerTec the dealer gets both a QuoteResponse and an
ExecutionReport, and a TradeCaptureReport (`35=AE`) goes out for the trade as well, with venue settings
that can turn some of them off. The example sends all three to each side; an optional one is a step a
tester deletes. Only step 8's rules and templates depend on any of these answers.

Work lands as commits straight to `main`, several per step. `main` builds, its tests are green and the
app is usable after every commit.

```
 Step 0      Step 1      Step 2       Step 3       Step 4        Step 5        Step 6       Step 7        Step 8         Step 9
 ground  ─►  the     ─►  the RFQ  ─►  the      ─►  control   ─►  Trace and ─►  authoring ─► a dealer  ─►  the platform ─► time
 three       model       book         engine       surface       Lanes         and the      that         example +      (expiry)
 defects     on disk                  relays       + MCP                       venue pane   answers      FX RFQ fixes
 └──────────────────────────────── slice A ───────────────────────────────────────────┘   └ slice D ┘   └ slice B ─┘   └ slice C ┘
```

**Slice D moves ahead of slice B.** Every bundled example ships a load set that runs without edits, and
`ExampleWorkspacesTest.kt:943-985` pins the FI example's two. A platform nobody quotes on can't keep that
promise.

---

## What the surveys and the fact check settled

| # | Fact | Where | What it forces |
|---|---|---|---|
| 1 | **A reply to a logged-out session is still captured and booked.** QFJ `Session.sendRaw` runs `toApp` and persists before its `isLoggedOn()` check (`Session.java:2630-2639`), and `AcceptorDispatch`'s `send` discards `sendToTarget`'s `false`. `onSent` runs anyway. | `AcceptorDispatch.kt:39`, `:103`; `QuickFixService.kt:383` | Step 0 checks logged-on **before** building, through an injectable seam, and counts not delivered. |
| 2 | **An older build decodes profiles with `ignoreUnknownKeys = true`.** **[review]** A new *field* is silently dropped, so a relay rule runs there as a reply-to-sender rule with a looser trigger. The next save rewrites the file without the field. A new *value* in an existing enum is worse: the decode fails, `loadProfiles()` returns empty, and the next save writes one profile over the file. | `ConnectionProfileService.kt:22`, `:71-75`, `:97-111`; `AcceptorResponder.kt:165-174` | Role and RFQ state are **matcher types** in `conditions`. An older `MatcherCodec` can't parse them, and `compile` drops the rule, which is the safe direction. Any rule with a non-sender `to` or `whenResponders` must carry one. No new value in any persisted enum; a test pins them all. |
| 3 | **One `QuickFixService` serves every client of a venue.** `channels` holds only clients that have a pane or buffer. The QFJ session registry is static. The service has no handle on the acceptor: `FixConnectionManager.acceptor` is private (`:26`), and the manager is built after the service (`FixMessageSession.kt:594`). | `QuickFixService.kt:197`, `:200`, `:502-514`, `:536-538` | Recipients resolve through declared CompIDs and QFJ lookups, not `channels`. The acceptor handle is wired in after construction (step 3). |
| 4 | **Pending sends are keyed by destination.** A relay to Dealer 1 is cancelled when Dealer 1 logs out, not when the buy side does. `cancelAll` reports nothing about what it dropped. | `AcceptorDispatch.kt:48`, `:66`, `:83-85`; `QuickFixService.kt:471` | Destination keying is kept, on purpose. `cancelAll` returns what it dropped, so legs can be marked and counted. |
| 5 | **The destination's `toApp` captures the reason and books the message.** | `QuickFixService.kt:738-777`, `:790-815` | The RFQ book learns about sent relays in `book()`, from `fixMessage.sendReason.relay`. |
| 6 | **The trigger carries no reason, and it is delivered before any rule runs.** `uid` is process-local and outside the constructor. | `QuickFixService.kt:875`, `:894` **[corrected]**; `FixMessage.kt:26`, `:88-96` | The Trace edge is a reference from the relayed message's reason back to the trigger's `uid`. |
| 7 | **A lane holds exactly one session.** Pairing refuses same-*session* matches only. The `TraceIndex` memo ignores roles and titles. `Traces.Grouping.idsPerMessage` is public and returned whole. | `TraceLanes.kt:63-67`, `:129-133`, `:273`; `TraceFollow.kt:288`, `:316-320`; `Traces.kt:121`, `:192` | `Lane.sessions` becomes a list, pairing refuses same-lane matches, and the memo key grows. Synthetic ids go to `CorrelationComponents` in a **separate** list, never into `idsPerMessage`. |
| 8 | **Rules go over HTTP as hand-built JSON, and `whenQuote` has never been emitted.** | `ControlServer.kt:3480-3548` vs `:331-361` | Step 0 fixes `whenQuote`. **[review]** Step 1, not 4, emits the new fields, so an HTTP read-and-write never drops them. |
| 9 | **The MCP tool count is pinned at 54.** Every tool is probed with `{}`, except `fixtool_demo`, which gets `{"action":"stop"}` **[corrected]**. | `ControlServerIntegrationTest.kt:440`, `:501`, `:509` | Step 4 adds `fixtool_acceptor_rfqs` and moves the pin to 55. |
| 10 | **No example bundle has a committed generator.** The FI test compares the rule list in full; nothing else in the bundle is compared to anything **[corrected]**. The Gradle `Test` task forwards no system properties. | `ExampleWorkspacesTest.kt:894-899`; `composeApp/build.gradle.kts:241-254` | Step 8 commits a generator **and** forwards `fixtool.regenerate` to the test JVM. |
| 11 | **A lift after a Cover or Done Away still books.** The FI desk answers both with `297=6`, and `answered()` closes a quote only on `35=8` or `297=11`. | `QuoteBookService.kt:134-144` (close at `:137`) **[corrected]**; `FiRfqVenuePreset.kt:422`, `:430` | Fixed in step 0. |
| 12 | **Each load lane is its own `QuickFixService`,** built from a copy of the config. | `FixMessageViewModel.kt:5777-5821`; `SessionIdentityResolver.kt:31-55` | Rules on an initiator run per lane. **[review]** A live reload swaps `authoredRules`, not `config` (`QuickFixService.kt:161`, `:256-261`), so the slice D gate reads the rules and never the config. |
| 13 | **The rules editor is gated on the form's connection type.** The panel keeps state per field and copies fields by name. | `ConnectionPanel.kt:1516`, state `:105-106`, copies `:189-190` / `:385-386` / `:427-428`, save `:332-333`, connect `:1855-1856` **[review]** | Counterparties need all six sites, or edits vanish. |
| 14 | **A load lane has no hook for unsolicited inbound messages.** | `LoadSetRunner.kt:116-117`; `StampMatcher.kt:67-73` | The dealer side of the load set is rules (slice D). |
| 15 | **A Kotlin expression costs about 60 ms under one process-wide lock.** | `AcceptorResponder.kt:680-687`; lock at `FixMessageTemplate.kt:305` **[corrected]** | Relay templates stay native. Opposite sides come from twin rules conditioned on `54`. |
| 16 | **[review] `validationError()` has seven callers, and none of them passes the venue.** | `QuickFixService.kt:244-245`; `AcceptorResponder.kt:279`; `ControlServer.kt:478`, `:3377`, `:3543`, `:3959`; `AcceptorRulesEditor.kt:659` | Refusals that need the counterparties live in a new `validationError(counterparties)`. The zero-argument form never makes them. |
| 17 | **[review] A missing `${req.<tag>}` substitutes empty.** | `AcceptorResponder.kt:638` | A relay template reads only tags its trigger requires. `54=${req.54}` needs a `54 present` condition, with a twin rule for two-way. |
| 18 | **[review] `quoteField` is a matcher type resolved against the venue.** Its handling is the template for the two new types. | `Matcher.kt:130`, `:176`; `MatcherCodec.kt:128`, `:188`; `ExpectationEvaluator.kt:385`, `:559`; `MatcherEditor.kt:58`, `:120`, `:154`, `:323`; `ScenarioUi.kt:141`; `AcceptorResponseRule.kt:52`; `AcceptorResponder.kt:235`; `AcceptorRulesEditor.kt:802`; `MatcherDocsTest.kt:50`; `HelpDocTest.kt:195` | Step 1 adds `role` and `rfq` at every one of these sites. |

## The decisions the review added

These refine the proposal and are written into it.

- **R1. Role and RFQ state are matchers.**
  - `{"type":"role","role":"requester|responder|unlisted"}` is allowed only on tag `49`.
  - `{"type":"rfq","state":"unknown|open|done|expired"}` is allowed on `131` (the RFQ the sender's
    QuoteReqID names) or `117` (the RFQ behind the quote id the sender was given). On `117`, `open`
    also requires that quote to be its leg's current one.
  - Both are refused in scenarios, as `quoteField` is. The card still draws them as rows.
  - `whenResponders` and a non-sender `to` are valid only on a rule that carries one of them (fact 2).
- **R2. Two readings.** A trigger reads the book as it stood **before** the message, as decision 4a
  requires. Addresses, `RelayRef.rfqId`, `${rfq.…}` and `${to.…}` read it **after** `book()` has recorded
  the trigger, the way `${order.…}` already reads the live book. Without this the opening `35=R` has no
  RFQ to address (review finding 1).
- **R3. A trade is recorded when it is decided.** A selected rule with a `35=8` step addressed to
  `quoter` is a trade. On the callback thread, before anything is scheduled, the book marks the RFQ
  `done`, the quoter's leg lifted, and every other relayed quote superseded.
  - It is the one place the book records a decision instead of the wire. The wire is the dispatch
    thread, and a second lift is already queued behind the first on the single QFJ thread. Recording on
    the wire would let both lifts trade (review finding 2).
  - A refused lift is not a trade, so it leaves the RFQ open.
- **R4. An RFQ's life:**
  - *requested* when a requester's `35=R` arrives;
  - *open* once a `35=R` has been relayed to any responder;
  - *refused* when the venue sends the requester a `35=AG`;
  - *passed* when the requester sends `694=6`;
  - *done* when a trade is decided (R3);
  - *expired* when the clock passes its expiry.

  A trigger sees four words: requested and open read `open`; refused, passed and done read `done`;
  expired reads `expired`. The book's own view keeps the full word.
- **R5. `${to.<tag>}` is defined per recipient.**
  - To the requester: `131` is its own opening id, and `117` is the relayed quote id the trigger names.
  - To a dealer: `131` is the venue id it was sent. `117` is the dealer's own quote id: for the quoter,
    the one **linked to the relayed quote the trigger names**; for cover, others and quoted, their current
    live quote.
  - Any other tag: the last value exchanged with that recipient on that leg, refused at render if there
    is none.
  - Links are keyed by `(session, tag, value)`. A new quote from a leg supersedes its previous one
    (review finding 7).
- **R6. Two more addresses.**
  - `quoted`: responders holding a live quote.
  - `others` becomes quoted minus the quoter and the cover.
  - `cover` ranks live quotes only: the lowest offer when the lift was a buy, the highest bid when it
    was a sell.
  - `asked` reaches every leg, but only for `131`-level messages. `${to.117}` on it is refused
    structurally (review finding 8).
- **R7. `responders` resolves against what was declared.** A declared exact CompID that isn't logged on
  is a recipient that isn't delivered to: counted, and its leg marked. A prefix entry (`FIDLRLG*`) matches
  only sessions that exist. Anything `cancelAll` drops is counted the same way (review finding 3).

---

## Probes to run before step 3

**Outcome (2026-09-13, step 3 built):**
- **P1** was settled by design, not measured. The venue keeps its own map of every counterparty session it
  has created, by CompID, from `onCreate`, and checks each with `Session.lookupSession(id)?.isLoggedOn`. That
  needs no handle on the acceptor (fact 3) and does not depend on which panes exist. `RfqRelayIntegrationTest`
  exercises it with four real clients and a declared fifth that never connects.
- **P2** is not measured yet. The integration test's templates carry no `Parties`; step 8's preset test
  validates the platform's replies against the dictionary, and that is where `453` placement gets checked.
- **P3** is confirmed by the integration test: the dealer's pane holds the relayed fill with
  `sendReason.relay` set, recorded by the dealer session's own `toApp`.

- **P1. Listing a venue's logged-on counterparties.** Try `SocketAcceptor.getManagedSessions()` (it
  needs the handle from fact 3) against `Session.lookupSession(new SessionID(…))` for each declared CompID.
  With three clients on and one off, which is exact and safe on the callback thread?
- **P2. A relayed `35=R` with `NoRelatedSym` and `Parties`** built through `buildMessage(raw, dictionary)`
  against FIX 4.4. Where does `453` land, and does the dealer's side validate it?
- **P3. Sending across sessions from the dispatch thread.** Does B's `toApp` see `PendingSendReason`, and
  does B's `book()` see `sendReason.relay`?

---

## Step 0: three defects, fixed first (M)

| Item | File | Change |
|---|---|---|
| **Not delivered is not sent** | `service/AcceptorDispatch.kt:37-46`, `:96-110` | `send: (Message, SessionID) -> Boolean`, and a new `isLoggedOn: (SessionID) -> Boolean` seam defaulting to QFJ. Checked before `build()`; a miss calls `onNotDelivered(sessionId, reason)`. `onSent` fires only on `true`. **[review]** `cancelAll` returns the reasons it dropped. |
| Count it | `service/QuickFixService.kt:69`, `:203-205`, `:381-386`, `:431-432`, `:471` | `AcceptorStatus.notDelivered`; `VenueEvent.NotDelivered(sessionId, at, ruleIndex)`; `onLogout` counts what `cancelAll` dropped. |
| **[review] Exhaustive `when`s** | `viewmodel/FixMessageViewModel.kt:5834-5847`; `model/FixMessageSession.kt:303-308` | Add the `NotDelivered` arm. |
| Surface it | `ui/VenueSummary.kt:25-135`; `ui/MinimizedStrip.kt:204-210`; `control/ControlServer.kt:283-287` | Add a "⚠ N not delivered" badge. **[review]** `sendsDiverge` (`VenueSummary.kt:54`) and `quiet` (`:62-63`) compare sent with triggered **minus not delivered**, so the red "N/M sent" badge doesn't fire as well. |
| `whenQuote` over HTTP | `control/ControlServer.kt:3508`; `control/McpTools.kt:684-685`, `:737` | Emit it, and name it in the tool texts. |
| Cover and Done Away close the quote | `service/QuoteBookService.kt:137`, KDoc `:131-132` | `297` of `11` **or `6`** closes the quote. |

Tests:
- `AcceptorDispatchTest`: update the three lambdas that end in `countDown()` (`:33-36`, `:56-59`, `:77-80`) and pass `isLoggedOn = { true }` in all five. Add: a logged-out step is not built, not sent and is counted; `cancelAll` returns what it dropped.
- `ControlServerIntegrationTest`: `whenQuote` is emitted.
- `QuoteBookServiceTest`: a Cover answered with `297=6` closes the quote, and a later lift reads `done`. Rename the test at `:116`.
- `FiRfqVenuePresetTest:324-332`: a lift after Cover is refused as spent.

**Exit:** `./gradlew jvmTest` green.

---

## Step 1: the model and what goes on disk (L)

| Item | File | Change |
|---|---|---|
| Matcher types | `model/scenario/Matcher.kt:130` | `Matcher.CounterpartyRole(role: String)` and `Matcher.RfqState(state: String)`. The strings are parsed on read (fact 2). |
| Everywhere `quoteField` is handled | fact 18's sites | Codec read and write; the evaluator (resolved before evaluation, like `resolveQuoteField` at `AcceptorResponder.kt:234-239`, and refused if unresolved); `validationError` refuses both in a scenario; the editor's type list for triggers only (`AcceptorRulesEditor.kt:802`); `ScenarioUi` text; `MatcherDocsTest` and `HelpDocTest` pins. |
| `FieldCondition.reason()` | `model/AcceptorResponseRule.kt:36-61` | `role` is allowed only on tag 49 and `rfq` only on 131 or 117. An unknown word is named. |
| Step address | `model/AcceptorResponseRule.kt:75-78`; new `model/StepAddress.kt` | `ResponseStep.to: String? = null`. `StepAddress` covers sender, requester, quoter, cover, others, quoted, asked, responders and `compId:X`. |
| Responders online | `model/AcceptorResponseRule.kt:106-147` | `whenResponders: String? = null`, taking `none` or `some`. |
| Relay reference | `model/SendReason.kt:27-43` | **[review] Moved here from step 3**, because step 2's book reads it. `relay: RelayRef? = null`, where `RelayRef(triggerUid, triggerSession, triggerCompId, address, recipientCompId, rfqId)`. |
| Counterparties | `model/FixConnectionConfig.kt:55-57` | `counterparties: List<Counterparty>`, where `Counterparty(compId, role: String)`; `roleOf(compId)`, with an exact match beating the longest prefix. `rfqExpirySeconds: Int? = null` also lands here, for step 9. |
| `isUnconditional` | `model/AcceptorResponseRule.kt:208` | **[review]** Also false when the rule has a `role` or `rfq` condition or `whenResponders`. Otherwise `shadowingRule` (`AcceptorResponder.kt:343`) and `AcceptorPresets.insert` (`:814`) misplace rule 1. |
| Venue-aware validation | `model/AcceptorResponseRule.kt:236-276` | `validationError(counterparties: List<Counterparty>?)`, with the zero-argument form delegating with `null` and skipping venue checks (fact 16). The refusals are listed below. |
| Compile | `service/AcceptorResponder.kt:165-174` | Drops a rule whose address doesn't parse, as it already does for an unusable condition. |
| HTTP | `control/ControlServer.kt:3480-3548`, `:3513-3525` | **[review]** `to` on every step, `whenResponders`, and the new matcher types inside `trigger[]`. |
| Enum pin | new `jvmTest/model/PersistedEnumsTest.kt` | Pins `QuoteConstraint`, `OrderConstraint`, `ConnectionType`, **[review]** `MessageStoreKind` (`FixConnectionConfig.kt:77`), `MessageLogKind` (`:83`) and `AcceptorLatencyConfig.Mode` (`:52`). |

What `validationError` refuses:

- `to` isn't an address; the error names the addresses.
- A non-sender `to`, or `whenResponders`, on a rule with no `role` or `rfq` condition: an older build would run it looser.
- `requester`, `asked`, `quoted`, `quoter`, `cover` or `others` without an `rfq` condition, since after-reading (R2) still needs an RFQ to exist. The exception is a `35=R` trigger from a requester: the book opens the RFQ before the rule runs, so `requester` is allowed there.
- `quoter`, `cover` or `others` without an `rfq` condition on `117`, since the trigger must name a quote.
- `${to.117}` on `asked`, `requester` or `responders` without a `117` trigger (R6). `${to.…}` on `responders` or `compId:` (a counterparty being asked hasn't seen the RFQ).
- `${rfq.<name>}` outside `requester, quoter, asked, quoted, state`.
- With counterparties supplied: a relay address or `role` condition on a venue that declares none.

Tests:
- `AcceptorTriggerTest:292-310`: round-trip with every new field and matcher; a pre-relay profile loads unchanged.
- `ConnectionProfileServiceTest`: counterparties round-trip.
- New `StepAddressTest` and `CounterpartyRoleTest`.
- One validation case per refusal.
- `AcceptorQuoteRulesTest`-style codec tests for `role` and `rfq`.
- A scenario that uses either is refused.
- **[review]** A decode test that simulates an older codec: an unknown matcher type makes `compile` drop the whole rule, not just the condition.

**Exit:** every existing acceptor suite green, **unmodified**.

---

## Step 2: the RFQ book (XL)

| Item | File | Change |
|---|---|---|
| Model | `model/RfqBook.kt` | `RfqEntry(rfqId, requesterKey, requesterCompId, openedBy131, openingUid, opening: Map<Int,String>, symbol, securityId, securityType, side, qty, expireAt, state, legs)`. `Leg(responderKey, compId, venue131, quotes: List<LegQuote>, outcome)`. `LegQuote(dealerQuoteId, venueQuoteId, bid, offer, validUntil, current)`. The `RfqReading` is frozen when taken, like `QuoteReading` (`QuoteBook.kt:119-149` **[corrected]**). State words follow R4. |
| Service | new `service/RfqBookService.kt` | One per `QuickFixService`. Links are keyed by `(sessionKey, tag, value)` **[review]**. Per-counterparty counters for asked, quoted, passed and not delivered **[review]**. Links and values are evicted with their RFQ; the cap is set through `QuickFixService.setOrderBookCap` (`:307-310`) **[review]**. |
| Reading group fields | `service/AcceptorResponder.kt:748-768` | **[review]** `valueOf` becomes `internal`, so the book reads `54`, `38`, `55` and `126` inside `NoRelatedSym`. A flat read like `QuoteBookService.fieldsOf` returns null for them. |
| Received | `record(received)` | A requester's `35=R` opens a *requested* entry and keeps the opening fields and uid. A responder's `35=S` finds the RFQ through `(session, 131, value)` and adds a current `LegQuote`, superseding the previous one. A responder's `35=AG` marks the leg passed. A requester's `35=AJ 694=6` naming a relayed quote marks the RFQ passed. |
| Sent | `record(sent)` | Only messages with `sendReason.relay`. Every correlation id on the message is linked, for the destination, to `relay.rfqId`. A `35=R` to a responder adds an asked leg and moves the RFQ to open. A `35=S` to the requester links its `117` to the leg's current quote. An `AJ 694=4/5/6` records that leg's outcome. A `35=AG` to the requester naming its `131` marks it refused. |
| Deciding a trade | `RfqBookService.decideTrade(rfqId, quoterLeg, triggerUid)` | R3. Called by step 3. |
| Claim | `QuickFixService.kt:790-815` | `book()` offers the message to the RFQ book first. A relayed `35=8` it recorded is claimed, so the order book never sees it. |
| Readings | `QuickFixService.kt:875-894` | `rfqBefore`, taken before `book()` (for triggers), and `rfqAfter(sessionId, message)`, taken after (for step 3, per R2). |
| Clear and flow | `QuickFixService.kt:370-374`; `RfqBookService.views()` | Cleared with the venue's books. `StateFlow` with a trailing publish. |

Tests (new `RfqBookServiceTest`):
- Requested, then open on the first relay; refused; passed.
- Two requesters sending the same `131` get two RFQs.
- A re-quote supersedes the earlier one, and a lift on the superseded id reads `done` on `117`.
- `decideTrade` makes a second lift read `done`.
- Cover ranking over live quotes.
- A leg that passed is not in `quoted`.
- Claiming; reading before and after; expiry by clock; caps and eviction; group fields read.

**Exit:** the book is filled from a scripted wire. Production is unchanged.

---

## Step 3: the engine relays (XL)

| Item | File | Change |
|---|---|---|
| The port | new `service/RelayVenue.kt` | `interface RelayVenue { roleOf(compId); recipients(address, trigger, rfqAfter): Resolution; toValue(recipient, rfqAfter, trigger, tag); rfqField(rfqAfter, name); isLoggedOn(key) }`. `Resolution(recipients, notDelivered)`. **[review]** `rfqField` serves `${rfq.asked}` and `${rfq.quoted}`. |
| Wiring | `service/QuickFixService.kt`; `service/FixConnectionManager.kt:26`, `:278`; `model/FixMessageSession.kt:594` | **[review]** The service implements the port. After it builds the acceptor, `FixConnectionManager` hands the handle back through `quickFixService.attachAcceptor(connector)`. |
| Triggers | `service/AcceptorResponder.kt:187-239` | `role` and `rfq` resolve like `quoteField`, against a `VenueReading(senderRole, rfqBefore131, rfqBefore117)` added **as a defaulted last parameter** to `firstMatch` and `explain`, so existing callers compile. `whenResponders` is judged against the port. |
| Explain | `service/AcceptorResponder.kt:263-323`, `RuleOutcome` at `:112-124` (one caller, `:321`) | The new conditions appear as ordinary `ConditionOutcome`s. `respondersOutcome` is added. `AcceptorResponderExplainTest:139` covers each. |
| Planning | `service/AcceptorResponder.kt:366-401` | **[review]** A **new overload**, `planRelay(rule, incoming, request, dictionary, venue, rfqAfter, quote, order)`. The existing `plan` keeps its signature, because trailing-lambda callers (`QuickFixService.kt:947`, `ControlServer.kt:3868`, `AcceptorPresetsTest.kt:244`, `FxVenuePresetTest.kt:80`, `AcceptorOrderRefTest.kt:58`) and positional-lambda callers (`CryptoVenuePresetTest.kt:57`, `EquityVenuePresetTest.kt:59`/`:193`, `FiRfqVenuePresetTest.kt:72`/`:158`, `RfqVenuePresetTest.kt:197`) would all break. Each `PlannedSend` gains `to: Recipient?` and `authoredStep: Int`. `replyTo` (`:571-586`) keeps `plan(...).single()`. |
| References | `service/AcceptorResponder.kt` | `${to.…}` (R5) and `${rfq.…}` resolve in the render thunk after the book refs, and throw a named refusal instead of substituting empty. |
| Scheduling | `service/QuickFixService.kt:909-987` | Uses `planRelay` whenever the rule has a non-sender step (otherwise today's path, untouched). **R3:** if the rule trades, call `decideTrade` before scheduling. Each send goes to `to.sessionId`. `RuleFired.steps` and `SendReason.step/steps` count **authored** steps, not planned sends **[review]** (`:958`, `:977-978`). Undelivered recipients emit `NotDelivered` and mark their leg. |
| Latency | `QuickFixService.kt:932-936` **[corrected]** | One sample per trigger, added to every recipient's offset. |
| Pending count | `QuickFixService.kt:425-430` | **[review]** A venue sums the dispatch's own keys, not `channels.keys`. |
| Reason line | `model/SendReason.kt:68-79` | `ruleLine` appends *"→ quoter FIDLR1, relayed from FIBUY1's 35=AJ"*. `Source` stays unchanged. |
| Harness | new `jvmTest/integration/VenueHarness.kt` | **[review]** `connectVenue`, `connectClient` and `awaitPane` are extracted from `MultiClientAcceptorIntegrationTest.kt:356`, `:381`, `:406`, where they are private, and that test is switched to use them. `Await.settled` (`:31-38`) is internal and reused as is. |

Tests:
- New `AcceptorRelayPlanTest` against a fake port: every address, including `quoted`, `others` over quoted, cover over live quotes only, and nobody; fan-out draws; `${to.117}` per recipient following the linked quote after a re-quote; every refusal.
- `AcceptorSequenceTest`, `AcceptorOrderRefTest` and the preset tests stay unmodified, which proves the sender path is untouched.
- New `RfqRelayIntegrationTest` with `FIBUY1`, `FIBUY2`, `FIDLR1` and `FIDLR2` on `VenueHarness`:
  1. The R is re-keyed to the logged-on dealers only.
  2. A declared dealer that is offline is counted.
  3. Both buyers use `131=RFQ-1`, and each quote reaches the right buyer carrying that buyer's own `131`.
  4. A lift sends two `35=8` with one `37` and opposite `54`, the quoter's own `117` on its `35=AJ`, and `694=4` to the cover.
  5. **[review]** A second lift on the same RFQ, sent straight after the first, is refused and books nothing.
  6. **[review]** A re-quote followed by a lift on the old relayed id is refused.
  7. A quote for a done RFQ is refused.
  8. A pass tells the dealers who quoted and nobody else.
  9. Nothing on any pane is unattributed.
- `perf/AcceptorReplyBenchmarkTest`: 2,000 relayed RFQs to two dealers.

**Exit:** the integration test is green, and probe results are written into this section.

---

## Step 4: control surface, MCP and the guide (L)

| Item | File | Change |
|---|---|---|
| Dry run | `control/ControlServer.kt:3621-3707`, `:3856-3886` | Body gains `from`, `rfqState`, `rfq{…}` and `online[]`. The response carries recipients, a nobody note and not-delivered. **[review]** `ruleOutcomeJson` (`:3889-3960`) gains the responders outcome. `acceptorProblems` (`:476-479`) and the POST response (`:3377`) call `validationError(counterparties)`. |
| RFQ book | `control/ControlServer.kt:210-213` | `GET /acceptor/rfqs?profile=` and `POST {"profile":…,"clear":true}`. |
| Status | `control/ControlServer.kt:283-287` | `notDelivered`. |
| MCP | `control/McpTools.kt` (after `:838`); dispatch at `ControlServer.kt:4244` **[corrected]** | `fixtool_acceptor_rfqs`. The tool texts name `to`, the matcher types, `whenResponders` and `from`. |
| Guide | `docs/AUTOMATION.md:379-384`, `:792-1194` | A new subsection, *Rules that address another counterparty*, covering fact 4's cancellation rule, R2 and R3. *Testing a rule* gains `from` and `rfq`. |

Tests (`ControlServerIntegrationTest`):
- CRUD carries every new field.
- `from` picks the rule by role.
- Recipients and the nobody note.
- **[review]** A dry run of `${to.131}` addressed to responders comes back as a validation error and never as a `131=` send.
- `/acceptor/rfqs`, including clear.
- Tool count 55.

**Exit:** a relay can be authored, dry-run and inspected without the UI.

---

## Step 5: Trace follows the relay; Lanes draws the venue (L)

| Item | File | Change |
|---|---|---|
| The edge | `service/Traces.kt:144-194` | The first pass collects `uid → RelayRef`. The second builds a **separate** `extraEdges: List<Pair<Int, Int>>` (merged positions), which `CorrelationComponents.of` joins after the id pass. `idsPerMessage`, `Trace.ids` and labels are untouched (fact 7). |
| `CorrelationComponents` | `service/CorrelationComponents.kt:48-66` | `of(idsPerMessage, extraEdges = emptyList())`. For each edge, union the two messages' first ids, or a position key when a message has none. `Conversations` passes nothing. |
| The invariant | `service/Traces.kt:35-40` | Reworded to "a pane's conversation sits whole inside one trace". `TracesTest:133-153` keeps its single-session fixture. |
| Lanes | `service/TraceLanes.kt:63-67`, `:129-133`, `:150-181`, `:257-280` | `Lane(sessions)`, merged venue groups, role order, and a same-lane pairing refusal. |
| Inputs and memo | `viewmodel/TraceFollow.kt:27-41`, `:119-125`, `:288`, `:292-297` **[review]**, `:316-320`; `FixMessageViewModel.kt:6902-6927` | `Input` gains `venueGroup` and `partyRole`. `TraceIndex` carries both. The memo compares titles, roles, groups and party roles. |
| Callers | `ui/BottomDock.kt:361` **[review]** | Passes groups and party roles. |
| Reason under the arrow | `ui/TraceLanesView.kt:204`, `:332`, `:362`, `:404-444` | A 38dp row carries the reason. |
| HTTP | `control/ControlServer.kt:2441-2458` | `/trace` messages gain `sendReason{line, relayedFrom}`. |

Tests:
- `TracesTest`: an edge joins; `ids` and labels never show it; a missing trigger joins nothing.
- **[review]** The relay test gives each buyer a distinct `131`, because messages that share an id value already join by value and would pass for the wrong reason.
- `TraceLanesTest`: update `:133` (`it.session`) and the builder calls at `:52` and `:351`; merged lanes; role order; the pairing refusal; `:341` still draws every message once.
- `TraceLanesViewTest`: update `:90`; the reason text.
- `TraceFollowTest`: changing a role rebuilds the index.
- `TracesIntegrationTest`: following the buy side's R gives all **fourteen** messages **[review: the proposal said nine; the TradeCaptureReports add two]**. `571` is a correlation id (`ScenarioCapture.ID_TAGS`), so both reports join the trace by value as well as by relay.
- `GroupingBenchmarkTest`: update `:268` and `:271`, and compare against the trace benchmark at `:132-191` **[corrected]**.

**Exit:** the fourteen-message trace, and Lanes shows the mockup's four lanes.

---

## Step 6: authoring and the venue pane (L)

| Item | File | Change |
|---|---|---|
| Counterparties editor | new `ui/CounterpartiesEditor.kt`; `ui/ConnectionPanel.kt` | Adds the state and the copies at all six sites from fact 13. |
| Rows | `ui/AcceptorRulesEditor.kt:600-612` | `role` and `rfq` conditions render as the rows "and the sender is" and "and the RFQ is", plus "and responders online", with tags `rule-when-sender`, `rule-when-rfq` and `rule-responders-online`. They are shown when the venue declares counterparties or the rule already uses one. |
| To chip | `ui/AcceptorRulesEditor.kt:930-1029` | An address menu with meanings, and a recipients preview. Tag `step-to-$rule-$step`. A rule that trades (R3) says so on its card. |
| Plumbing | `ui/ConnectionPanel.kt:1636`; `ui/AcceptorRulesEditor.kt:81-128` **[review]** | Passes counterparties and `recipientsPreview`. The card calls `validationError(counterparties)` (`:659`). |
| Digest | `ui/AcceptorRulesEditor.kt:337-346` (`triggerLine`) **[review]**, `:361-386` (`ruleDigest`) | Adds the sender, the RFQ and each step's address. |
| Venue pane | `ui/AcceptorOverviewPane.kt:45-134`; new `ui/RfqBookPanel.kt`; `model/FixMessageSession.kt:721-784` | Counterparties with per-CompID counts, and the RFQ book (legs, ids both ways, 32nds for Treasuries). |

Tests:
- `AcceptorRulesEditorActionsTest`: the rows; picking an address; the preview.
- `AcceptorRuleCardFoldTest`: the digest.
- New `CounterpartiesEditorTest`: survives save, connect, reset and clone.
- New `RfqBookPanelTest`.
- `UiStringsTest` green.

**Live verification.** Use the `verify` skill with a temp `FIXTOOL_WORKSPACE` and port 8799. **First check
that no FixTool is running from this checkout.** Then:
1. Drive the mockup's flow with `/send`.
2. Take screenshots of the venue pane, the To menu, and Lanes.
3. Read the relay reason line in the detail panel.

**Exit:** slice A is complete and live-verified.

---

## Step 7: a dealer that answers by itself (L), slice D

| Item | File | Change |
|---|---|---|
| The gate | `service/QuickFixService.kt:417`, `:791`, `:918` | **[review]** Each `config.connectionType != ACCEPTOR` becomes `!answersByRule()`, which is `connectionType == ACCEPTOR \|\| authoredRules.isNotEmpty()`. It reads the swapped field, so a live reload opens the gate. |
| Books | `model/FixMessageSession.kt:721-784` | Same predicate. |
| Reload | `viewmodel/FixMessageViewModel.kt:6085-6093` | Drop the ACCEPTOR guard at `:6086`. |
| Editor | `ui/ConnectionPanel.kt:1513-1516` | Rules are shown for initiators too, and the comment is reworded. |
| Control surface | `control/ControlServer.kt:3453-3460`, `:3795-3801`, `:463-466` **[review]** | The `inactive` texts go, and live-session counts are reported for initiators. |
| Texts | `McpTools.kt:777-778`, `docs/AUTOMATION.md:1142`, `model/FixConnectionConfig.kt:54-55` **[review]** | "Only on acceptors" is reworded. |
| Addresses | `model/AcceptorResponseRule.kt` | On an initiator, any address other than sender is refused. |
| Left acceptor-only, on purpose | `FixMessageViewModel.kt:6502` (hand-reply offers), `:5013` (`mintingSides`) | No change. |

Tests:
- **[review]** Update `ControlServerIntegrationTest.kt:1030-1048`, which asserts `inactive` for an initiator with a rule.
- An initiator quote rule answers.
- Five lanes answer.
- **[review]** An initiator connected with **no** rules, then given one by save, starts answering.

**Exit:** a responder load client quotes relayed RFQs by itself.

---

## Step 8: the platform example and the FX RFQ corrections (XL), slice B

### 8.1 The FX RFQ venue's three corrections

Unchanged from revision 1. The fact check spot-checked the break list: `RfqVenuePresetTest:99-102`,
`:110-137`, `:253-272`, `:283-287`, `:472-479`, `:549`; `AcceptorRfqVenueIntegrationTest:86`;
`ExampleWorkspacesTest:443-450`; the two rfq-venue scenarios and two templates;
`RfqExampleWorkspaceIntegrationTest:145-172`; `help.html:529-560`.

### 8.2 A generator

- New `jvmTest/service/ExampleBundleGenerator.kt`, run with `-Dfixtool.regenerate=<id>`. **[review]**
  `composeApp/build.gradle.kts:241-254` gains
  `systemProperty("fixtool.regenerate", System.getProperty("fixtool.regenerate") ?: "")`.
- It writes through the real services with absolute paths and zeroed `createdAt`.
- `ExampleWorkspacesTest` compares the whole bundle.

### 8.3 The FI RFQ Platform

| Item | Change |
|---|---|
| Preset | `FiRfqVenuePreset.kt` becomes `FiRfqPlatformPreset.kt`. ID stays `fi-rfq-venue`; it keeps `ISSUES`, `instrument()`, `SETTLEMENT` and `TERMS`. |
| Rules, in read order **[review]** | 1 R, sender responder → `658=6`. 2 R, unknown CUSIP → `658=1`. 3 R, no size → `658=99`. 4 R, sender requester, responders online none → `658=99`. **5a** R, sender requester, `54` present → R to responders with `54`. **5b** R, sender requester → two-way R to responders. 6 S, sender responder, `rfq open` on 131, per quote shape (offer, bid, two-way, each conditioned on its tags) → S to requester. 7 S, `rfq` not open → `AI 297=5` to sender. **8** AJ `694=1`, `54=1`, `rfq open` on 117 → `8` then `AE` to sender; `AJ 694=1`, `8`, then `AE` to quoter; `AJ 694=4` to cover; `AJ 694=5` to others. Each `35=AE` carries FIX 4.4's required `571` (one `${req.uuid}`-derived id shared by both reports), `570=N`, `55`, `32`, `31`, `75`, `60` and `552=1` with that side's `54` and the shared `37`; `FiRfqPlatformPresetTest` validates both against the bundled dictionary, repeating group included. **9** the twin for `54=2`. **10** AJ `694=6`, `rfq open` → `AJ 694=6` to quoted, `AI 297=11` to sender. **11** AJ, `rfq done` on 117 → `AI 297=5`. 12 AJ for an unknown or expired quote → the desk's existing refusals. 13 anything else → `35=j`. |
| Dealer pricing | Moves onto Dealer Load Client's rules (step 7). **[review]** It must have no rule that answers `AJ 694=1`: on a firm platform the venue's own `35=8` confirms the trade. |
| Profiles, templates, scenarios, load set, help | As revision 1. The manifest is `composeApp/src/jvmMain/resources/examples/fi-rfq-venue/manifest.json` **[corrected]**; `displayName` becomes "Fixed Income RFQ Platform", and `defaultWorkspaceName` stays. |

What 8.3 breaks:
- `FiRfqVenuePresetTest`: replaced wholesale.
- `ExampleWorkspacesTest`: `:894-899`, `:902-907`, `:917`, `:925-962`, `:970-985`, `:987-994`.
- `FiRfqExampleWorkspaceIntegrationTest`: `:28-30`, `:105-107`, `:109-110`, `:120`, `:127`, `:133`, `:142`, `:148`, `:153`. `:68` and `:101` stay, because the id and workspace name are kept **[corrected]**.
- `HelpDocTest`: `:216-238`, `:244-268`.
- **[review]** `AcceptorPresetsTest:264-274`: validate with the preset's counterparties. `:283-300`: `explain` each rule with a fake `RelayVenue` and a sender reading.
- `sampleFor` (`:49-142`) gains `35=S` and `35=AG`.

**Exit:** the platform opens, and its four scenarios and load set pass live.

---

## Step 9: time (L), slice C

| Item | File | Change |
|---|---|---|
| Trigger word | `model/AcceptorResponseRule.kt` | `whenMsgType = "@rfq-expired"`. An older build treats it as a MsgType nobody sends, and the rule's `rfq` condition makes it drop the rule anyway. |
| The timer | `service/AcceptorDispatch.kt` | **[review]** `scheduleTimer(key, delayMillis, action)` and `cancelTimer(key)` on the venue's own dispatch thread, so expiry is ordered with replies. It is not `OrderBookService.kt:101-104`'s executor, which is process-wide and argued against a thread per connection. Armed when the RFQ opens (from `126`, else `rfqExpirySeconds`). Cancelled on done, refused, passed, clear and shutdown. **Not** cancelled when the requester logs out. |
| Firing | `service/AcceptorResponder.kt` | **[review]** A separate `firstMatchOnExpiry(compiled, entry)`, which selects `@rfq-expired` rules, evaluates tag conditions against `entry.opening` through `valueOf`'s group fallback, and reads `rfq` as `expired`. `firstMatch` compares the incoming `35` and could never select these rules. |
| Addresses | `model/StepAddress.kt` | `quotes`: the requester, once per live quote, with `${to.117}` bound to that quote. Dealers are told through `quoted`, never `asked`, because `35=AI` requires `117` **[review]**. |
| Example | `FiRfqPlatformPreset.kt` | Expired and quoted → `AI 297=7` to quotes and to quoted. Expired and unquoted → `35=AG 658=99` to the requester. Refused and passed RFQs never arm a timer, so a refused request is never later told it expired **[review]**. |

Tests:
- Arming, firing once, and cancelling on done, refused and passed but not on a requester logout.
- The integration test's short-expiry RFQ.
- The fourth scenario.

---

## Risks and how each is bounded

| Risk | Bound |
|---|---|
| An older build runs a relay rule looser | R1: every relay rule carries a matcher it cannot parse, so it drops the rule; step 1 has a test for that. |
| An older build's save drops `counterparties` | Accepted and documented. The rules that need them are already dropped in that build, so nothing misfires. |
| One RFQ trades twice | R3, and integration case 5. |
| Every acceptor reply changes path | Sender-only rules keep `plan` and today's scheduling path. The existing suites stay unmodified. |
| Relay throughput | Native templates; step 3's benchmark. |
| Building under a running app | Before any gradle task past `compile`, `pgrep -f compose.application`. |
| Another session's commits | Pathspec commits. Re-read `ConnectionPanel.kt` line numbers before step 6: that session's UI commits landed after the surveys. |

## Not doing, on purpose

- **Order routing** (a `35=D` relayed to a liquidity provider): the same link mechanism, keyed by ClOrdID, as a sibling book.
- **Counters, named dealers, last look, anonymity as a switch.** Rules and templates, once the open questions are answered. Named dealers would add one address.
- **Persisting the RFQ book.**
