# Order state for acceptor mode — proposal

**Issue:** [#35](https://github.com/amitgupta1202/FixTool/issues/35)
**Depends on:** #30 (rules engine, shipped), #34 (conditional triggers, shipped), #31 (presets, shipped),
#32 (multi-client venue, shipped), #40 ("Reply With…", shipped)
**Mockups:** https://claude.ai/code/artifact/47dbc8fa-ce28-4f94-b4fa-7d155f2a4875

---

## The problem

The venue has no memory. Every reply is a function of the message in front of it and nothing else, so
three things a real venue does are currently impossible:

**A cancel for an order that was never placed gets acknowledged.** `35=F` matches the cancel rule and
the venue cheerfully reports the order canceled. A real venue answers `35=9` with `102=1` (unknown
order), and a client's error handling for that path is exactly the thing a tester is trying to
exercise. Today the only way to see a cancel reject is to enable the reject rule *instead*, which
then rejects every cancel including the legitimate ones — so the client can be shown one behaviour or
the other, never the venue's actual rule.

**Quantities are arithmetic the author maintains by hand.** `AcceptorPresets.PARTIAL_FILL` fills half
and leaves half because that is all a stateless rule can say. Two partials in a row report the same
`14=` twice, and a client tracking CumQty sees the second fill undo the first. Anything more truthful
means the author computing `14` and `151` themselves, per step, per order size.

**An order's OrderID changes between replies.** `37=${req.uuid}` is drawn once per *triggering
message*, so a rule's own sequence is consistent — but two separate replies are two draws. Since #40,
acknowledging an order and then filling it by hand is a normal thing to do, and it sends the client
two different OrderIDs for one order. There is nowhere to keep the first one. This is a defect today,
and the book is the only place that fixes it.

All three are the same missing thing: nothing remembers what the venue has already said.

## What already exists — do not rebuild

- **`AcceptorResponder`**: trigger matching (`firstMatch`/`explain`), the reply planner (`plan`), and
  the substitution vocabulary — `${req.<tag>}` standing alone *and* inside expressions, `${req.uuid}`,
  `${uuid}`, `${now}`, plus the Kotlin expression pass for `${req.38 / 2}`.
- **`AcceptorDispatch`**: one thread, replies off the callback thread, per-session cancellation.
- **The wire, already captured**: `QuickFixService.toApp` builds a `FixMessage` for every outgoing
  application message and `fromApp` for every incoming one. Both already carry the `SessionID`.
- **A pane per counterparty** (#32), and `attachVenueClient` keeping a pane across a reconnect.
- **The rules editor, the preset catalogue, `/acceptor/rules`, `/acceptor/test`, and `Reply With…`.**

## The decisions

### 1. The book records. The rules still decide.

The issue asks for cancels to be "auto-rejected with correct reason codes". Building that as venue
behaviour would put a second decider next to the rule list: a tester whose cancel got rejected would
have to work out whether a rule did it or the book did, and turning it off would mean finding a
setting rather than a rule.

So the book answers questions and never sends anything. What it adds to the engine is one optional
constraint on a trigger — `whenOrder` — beside the tag conditions a rule already has:

```
when 35=F and the order is unknown   → 35=9, 102=1
when 35=F and the order is working   → pending cancel, then canceled
```

First-match-wins remains the whole of the decision, both rules are on the card, either can be
switched off, and `explain` reports the new constraint exactly as it reports a tag condition. The
auto-reject the issue asks for ships as a **preset**, like every other venue behaviour.

*Rejected:* a `rejectUnknownCancels` config flag. It is the same behaviour, invisible on the rule it
contradicts.

### 2. The book learns from the wire, not from intentions.

An order's state moves when a message is **sent or received**, read out of `fromApp` and `toApp` —
not when a rule fires or a plan is built.

This is what keeps the book honest under everything that is not a rule: a reply typed into the editor
by hand, a `Reply With…` shape, a bulk send, a scenario step. All of them go out through `toApp`. A
book fed by the rules engine would silently disagree with the client's view the first time a tester
answered an order themselves — which, since #40, is the feature we just shipped.

It also gives the ordering for free. Within a sequence, step one's `toApp` runs inside its `send()`,
which returns before step two is built 250ms later, so step two's `${order.*}` sees step one's
effect. That is a claim worth a test rather than a comment.

*Consequence to accept:* the book is downstream of the wire, so a reply the venue sends **wrongly**
is recorded faithfully. That is the correct direction to be wrong in — the book shows the client's
view, which is the view under test.

### 3. One book per counterparty, keyed by ClOrdID.

ClOrdID is unique per client, not per venue. Two clients may both send `ORD-1` and they are two
orders; a single global book would collapse them and answer a cancel from one with the other's state.

So a book belongs to a `SessionID` — the same key `AcceptorDispatch` uses and the same thing a pane
is built around. Within it: keyed by ClOrdID, with a secondary index by OrderID (37) for lookup, and
`OrigClOrdID` (41) followed for cancel and replace.

The book lives with the **pane**, so a client that logs out and returns finds its orders where it
left them — the same call `attachVenueClient` already makes for message history.

### 4. A small state vocabulary, not the OrdStatus zoo.

`whenOrder` takes one of four words:

| word | means |
|------|-------|
| `unknown` | no order with this ClOrdID (or OrigClOrdID) on this session |
| `pending` | the venue has the order; the client has not been told anything yet |
| `working` | acknowledged and not finished — `LeavesQty > 0` |
| `done` | filled, canceled, replaced or rejected |

Four words a tester can hold in their head, and each one names a rule someone actually wants to
write. The full FIX `OrdStatus` set is still reachable where precision is wanted — `${order.ordStatus}`
in a template, and later a matcher on it if anyone asks. Shipping the zoo as a trigger vocabulary
would mean an author choosing between `39=1` and `39=2` to express "still working".

**`pending` earns its place** by being the difference between two rules that would otherwise fight: a
cancel arriving 2ms after the order, before the ack has left, must not be answered "unknown order".
A venue that has the order has the order.

### 5. `${order.…}` substitutes where `${req.…}` does.

Same mechanism, same place in the pipeline: resolved textually before the Kotlin expression pass, in
both spellings, so `${order.leavesQty}` is the value and `${order.leavesQty / 2}` is arithmetic —
exactly as `${req.38}` and `${req.38 / 2}` work today.

Names, not tags: `orderId`, `clOrdId`, `origClOrdId`, `symbol`, `side`, `orderQty`, `cumQty`,
`leavesQty`, `avgPx`, `price`, `ordStatus`. A tag number would be a lie here — `${order.14}` is not a
field of a message, it is a fact the venue computed.

This is what makes the presets truthful:

```
fill the rest:      14=${order.orderQty}   151=0                             32=${order.leavesQty}
partial of what's   14=${order.cumQty + order.leavesQty / 2}
left:               151=${order.leavesQty - order.leavesQty / 2}             32=${order.leavesQty / 2}
either:             37=${order.orderId}    ← the OrderID the client was already given
```

*Rejected:* a new binding in the script engine. `KotlinJsr223` freezes binding identifiers at compile
time, which this codebase has been bitten by before, and the textual pass has no such hazard.

### 6. Nothing is written to disk.

The book is in memory and dies with the app. A venue simulator that remembered yesterday's orders
would answer today's cancel with a state nobody can see the history of, and the file would then need
a format, a version, and a migration. Clearing a book by hand is a button; the same button covers
"start this test again".

### 6a. A stateful venue cannot re-derive its own reasons, so it records them.

**This is the decision the rest of the surfacing hangs off, and it is a property being lost rather
than a feature being added.**

Today the engine is pure. "Why did the venue answer this cancel that way?" can be reconstructed at any
time from the rule list and the message — which is exactly what `/acceptor/test` does, and why nothing
anywhere records which rule answered which message. It does not need to. The answer is a function of
two things that are both still on screen.

The book breaks that. A cancel rejected at 09:14:22 because the order was unknown will, at 09:14:25,
re-derive as *accepted* — because by then the order exists. Nothing is wrong with either answer; they
were asked at different times. But a tool that re-derives the reason after the fact would state the
wrong one **confidently**, and a QA tool that misreports why it did something is worse than one that
declines to say.

So from slice B, the reply carries its reason, written when the decision is made:

```
sent by rule 3 — 35=F matched, and the book said ORD-1 was unknown at 09:14:22.418
```

Attached to the outgoing message, so it is on the message row and in the detail panel next to the
bytes it explains. `RuleOutcome` already reports the value each tag condition read (`actual`); the
book constraint reports what the book said, by the same mechanism and in the same place.

*Consequence for the dry run:* `/acceptor/test` renders what a rule **would** send, and once a trigger
can read state that rendering is conditional. It has to name the state it assumed — and take one, so
"what would this rule do if the order were already filled" is answerable without arranging for an
order to be already filled.

### 6b. The book is a fold over a log, and the log is what is shown.

A row saying `CumQty 2500` is a claim. The same row backed by the two fills that made it is evidence,
and evidence is what a QA tool is for.

So `BookedOrder` is derived — a fold over an ordered list of events, each one a message that moved
the order: what changed, when, and the message that caused it. That is the same work the book was
already doing; recording it costs a list.

What it buys is that every number on the panel opens:

```
ORD-5000  ▾
  09:14:22.418  →  working    35=8 150=0  ack            17=E-1  ↑ sent
  09:14:23.006  →  working    35=8 150=F  fill 1500      17=E-2  ↑ sent   cum 1500 · leaves 3500
  09:14:23.418  →  working    35=8 150=F  fill 1000      17=E-3  ↑ sent   cum 2500 · leaves 2500
```

It also answers the issue's own "click an order to see full execution history" better than filtering
the grid does, because the grid shows messages and this shows *what they did to the state* — which is
the thing that was previously in nobody's head but the venue's.

### 7. What the book cannot attribute is counted, and the count opens.

A report with no ClOrdID, or one naming an order the book has never seen, is not silently dropped: it
increments an *unattributed* counter shown beside the book — and the counter is a link to the list of
those messages, because "2 unattributed" tells a tester that something is wrong and nothing about
what. A book that quietly ignores what it does
not understand looks identical to a book that is working, which is the failure mode that would waste
a tester's afternoon.

### 8. Where it shows.

A panel per session pane, the way the latency panel already works — one book per counterparty,
beside that counterparty's messages. The venue overview (`AcceptorOverviewPane`) gains a roll-up
line per client: how many orders, how many working.

A row per order: ClOrdID, OrderID, Symbol, Side, OrderQty, CumQty, LeavesQty, Status, and the time of
the last thing that moved it. A row expands to its trail (decision 6b). Selecting one filters the grid
to that order's messages, which is the other question a row prompts — *what did we tell them?*

Three things are shown that are not orders, because each one is a way the book can be wrong:

- **the unattributed list** — reports the book could not tie to an order;
- **the eviction count** (decision 8a) — orders dropped to stay inside the cap;
- **when the book was last cleared, and by what** — an empty book otherwise reads identically as "no
  orders yet" and "somebody pressed clear", and those lead a tester to opposite conclusions.

### 8a. The book is bounded, and says when it drops something.

A QA tool gets pointed at load tests. An unbounded book under a soak run is a leak, and one that
silently evicts is worse than one that leaks — a cancel for an evicted order comes back "unknown",
which is a venue behaviour the tester did not configure and cannot see the cause of.

So: a per-session cap on booked orders, oldest *finished* orders evicted first (a working order is
never evicted ahead of a done one), with the count of evictions shown beside the book and the cap
settable. This follows the discard counter the session ingestion path already carries, and for the
same reason: a number the user can see beats a silence they cannot.

## What changes where

| file | change |
|------|--------|
| `model/OrderBook.kt` *(new)* | `OrderState`, `OrderEvent`, `BookedOrder` as a **fold** over its events, and the transition as a pure function of (current, message, direction) |
| `service/OrderBookService.kt` *(new)* | one book per `SessionID`, fed from `fromApp`/`toApp`, thread-safe, bounded |
| `service/QuickFixService.kt` | feed the book from both callbacks; expose it for the pane and the responder; record the decision on each auto-reply |
| `model/AcceptorResponseRule.kt` | `whenOrder: OrderConstraint? = null`, folded into `trigger()`'s report and `validationError()` |
| `service/AcceptorResponder.kt` | `firstMatch`/`explain` consult the book and report what it said; `${order.…}` resolved alongside `${req.…}` |
| `service/AcceptorPresets.kt` | book-aware rules and reply shapes; the existing ones keep working untouched |
| `ui/OrderBookPanel.kt` *(new)* | the table, the trail, the unattributed list, and the roll-up on the overview pane |
| `ui/MessageDetailPanel.kt` | the reason a reply was sent, beside the bytes it explains |
| `control/ControlServer.kt` | `GET /acceptor/orders` (orders, trails, unattributed, evictions); a state argument to `/acceptor/test` |

## Slices

**A — the book, recording and shown, with its working.** No behaviour change: no rule can ask about it
and no template can read it, so nothing that works today works differently. The panel, the per-order
trail, the roll-up, the unattributed list, the eviction count and the cleared-at line, and
`GET /acceptor/orders` (+ `fixtool_acceptor_orders`). Shippable alone, and immediately worth having —
"what does the venue think it is holding" currently has no answer at all.

The trail is in this slice rather than a later one because it is not a nicety on top of the book: it
is the difference between a number the tester has to trust and a number they can check, and a QA tool
that asks to be trusted has picked the wrong side of that.

The endpoint is deliberately in the *first* slice too: it is what lets this be driven and verified
without a mouse, which is the gap #40 left open.

**B — rules can ask, and say what they read.** `whenOrder` on the rule, in the editor, in `explain`,
in `/acceptor/rules`, and in `/acceptor/test` — which gains a state argument, since a dry run of a
stateful trigger has to name the state it assumed. Every auto-reply records the rule that chose it and
what the book said at that moment (decision 6a), shown against the reply in the detail panel. Presets:
*cancel rejected — unknown order* (conditioned `unknown`), *cancel accepted* (conditioned `working`),
*duplicate ClOrdID rejected* (a `35=D` whose ClOrdID is already known). This is the slice that answers
the issue's acceptance criteria about validation.

**C — templates can read.** `${order.…}`, and the presets and `Reply With…` shapes rewritten to use
it: fills that accumulate, and `37=${order.orderId}` so an order keeps one OrderID across every reply
it ever gets. Fixes the identity defect named at the top.

**D — act from the row.** *Fill*, *Partial fill*, *Cancel* on an order in the book, opening the same
editor `Reply With…` opens. This is #40 with the book as the entry point instead of the message, and
it is the shape the issue sketches with its right-click menu. Genuinely optional: by slice C the same
replies are two clicks away in the message grid.

## Testing

The layers this codebase distinguishes, and what only each can say:

- **Unit** — the state machine, as message sequences: an order acked then partially filled twice ends
  `working` with the right CumQty; a replace supersedes and carries CumQty across; a fill for an
  unknown ClOrdID lands in the unattributed count; **every row's numbers are the fold of its own
  trail**, which is the assertion that stops the panel and the evidence drifting apart; eviction takes
  a finished order before a working one.
- **Engine** — `explain` reports `whenOrder` like any other condition; a rule conditioned `unknown`
  does not fire for an order the book holds; `${order.…}` resolves in both spellings.
- **Integration (real venue, real client, real port)** — the claims nothing below can make: a cancel
  for an order that was never sent comes back `35=9`, and one for a live order comes back canceled,
  *from the same rule list*; a partial then a full fill leave the client's own view of CumQty
  consistent; two clients each sending `ORD-1` get two books and neither cancel answers the other's
  order; a two-step reply reads its own first step's effect; **and the reason recorded against a reply
  still names the state that produced it after the order has moved on** — the one claim that fails the
  moment reasons are re-derived instead of recorded.

## What this is not

Not a matching engine: no book of *other people's* orders, no price-time priority, no crossing, no
market data. Not positions or P&L. Not persistence. Not an initiator-side view of orders a client has
sent — that is a different feature for a different user, and folding it in here would make the book
answer two questions with one table.

**Not an assertion target, yet.** "At this point in the run the venue holds ORD-1 filled" is a
scenario expectation over venue state, and it is the natural next thing a QA user asks for once the
book exists. It is deliberately out of these four slices — it needs the scenario engine's
expectation vocabulary, not the acceptor's — but it is the reason the book is a fold over an
addressable log rather than a live table: a shape you can assert against later costs nothing to
choose now.

## Open questions

Each is a decision I have taken a position on but would change on a word. The example is what it
looks like either way, not an abstraction of it.

- **Does `${order.…}` on an order the book has never seen refuse, or substitute empty?**

  This is the same hazard as `${req.44}` on a market order, one level up. A cancel arrives for
  `ORD-9`, which the book does not hold, and the rule that answers it reads `37=${order.orderId}`:

  ```
  substitute empty →  35=8|37=|11=CXL-4|...      the exact malformed message the preset
                                                  discipline exists to prevent
  refuse           →  the rule does not fire; the next rule for 35=F answers instead
  ```

  *Recommendation: refuse, and make it structural rather than a check.* A template reading
  `${order.…}` **implies** `whenOrder` is at least `pending` — the rule cannot match an order that is
  not there, the same way `AcceptorPresets` conditions the fill on `40 = 2` rather than testing for a
  price at send time. For a hand-picked reply the shape is greyed out with the reason, exactly as
  `Reply With…` already refuses Fill on a market order. **This answers the next question too.**

- **Do the book-aware presets replace the stateless ones, or sit beside them?**

  ```
  ack-partial-fill        (today)  14=${req.38 / 2}          half the order, always
  partial-fill-remaining  (new)    14=${order.cumQty + order.leavesQty / 2}
  ```

  The new one is strictly more truthful *and* strictly more fragile: on a venue whose book was just
  cleared, or one the tester pointed at a client mid-session, it does not fire at all.

  *Recommendation: beside, both shipped, the stateless ones unchanged.* They are the ones that work
  with no history — which is exactly the state a tester is in for the first five minutes of every
  session — and a preset that silently needs state is a preset that silently does nothing.

- **Is `pending` worth the fourth word?**

  A client sends the order and cancels it 2ms later, before the ack has left the dispatch thread:

  ```
  09:14:22.418  ← 35=D  ORD-9
  09:14:22.420  ← 35=F  CXL-4 (41=ORD-9)      ← the ack has not been sent yet
  ```

  With three words that cancel reads `unknown` and the venue answers `102=1 Unknown order` for an
  order it is holding. The client did nothing wrong and the tester has a venue bug to chase that is
  ours.

  *Recommendation: keep it.* The cost is honest — a fourth entry in the editor's dropdown and a fourth
  column in every state test — and the race is real rather than theoretical, since replies are
  deliberately dispatched off the callback thread.

- **Does the recorded reason cover replies a person sent, or only rules?**

  ```
  rule    sent by rule 3 — 35=F matched, and the book said ORD-9 was unknown at 09:14:22.418
  hand    sent by hand — "Fill the remainder" against ORD-5000, working, 2500 leaves at 09:14:24.881
  ```

  *Recommendation: both, one line, same place.* The client cannot tell which of them sent it and
  neither should the record; a log where hand-sent replies are the silent ones is a log that is
  hardest to read exactly when a person was improvising. The reply path already knows which shape was
  picked and what the book said — this is a field, not a mechanism.

- **What bounds the book — its own setting, or the session's message buffer?**

  A soak run sends 50,000 orders through a session whose grid keeps 1,000 messages.

  ```
  derived   ~1,000 messages of history ⇒ a few hundred orders booked, and a cancel for
            order 400 comes back "unknown" because the *grid* was scrolled past it
  own       5,000 orders regardless of how much message history the tester chose to keep
  ```

  *Recommendation: its own setting, default 5,000, finished orders evicted before working ones.*
  How much scrollback a tester wants and how much order state the venue keeps are unrelated
  questions, and tying one to the other is the mistake the ingestion path already made once when it
  derived queue depth from buffer size — a display preference silently became a throughput limit.

- **Is slice D (act from a book row) in, or does the manual path stay where #40 put it?**

  ```
  with D      book row ORD-5000 → Fill → editor opens on the remainder
  without C   grid → find the order's message → Reply With… → Fill the remainder
  ```

  *Recommendation: leave it out, and revisit after C is in use.* Two clicks versus three is not
  nothing, but the book row and the message are two entry points to one editor, and the second one
  already exists. If the answer after a week of use is "I keep going to the book first", that is a
  much better reason to build it than this document guessing.
