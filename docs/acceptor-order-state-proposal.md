# Order state for acceptor mode — proposal

**Issue:** [#35](https://github.com/amitgupta1202/FixTool/issues/35)
**Status:** approved 2026-07-31 as sliced; amended by that review (decisions 2a, 3a, 3b). **Slices A
and B shipped 2026-07-31**, both live-verified; C and D not started. Slice B added decision 4a and
found the routing defect fixed with it.
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

### 2a. The fold believes the report, including when it takes something back.

Where a sent report carries `CumQty` (14) and `LeavesQty` (151), **those are the values booked** — the
fold does not recompute them from its own running total and does not prefer its arithmetic to the
message. Only when a report omits them does the fold derive.

The case that makes this load-bearing is a **bust or a correct**. `150=H` (trade cancel) and `150=G`
(trade correct) exist to take back or restate a fill the venue has already reported, and the client's
own position moves when they arrive:

```
09:14:23.006  150=F  fill 1500   14=1500  151=3500
09:14:23.418  150=F  fill 1000   14=2500  151=2500
09:14:24.900  150=H  bust  1000  14=1500  151=3500     ← the second fill never happened
```

A fold that only ever added would sit at `14=2500` while the client sits at `1500`, and every
subsequent `${order.leavesQty}` would fill quantity that is not there. That is the book silently
disagreeing with the client's view — precisely the direction decision 2 says not to be wrong in.

This applies to **any** sent execution report, hand-sent ones included: a tester who busts a fill from
the message editor is doing the same thing to the client's view as a rule that busts it, and the book
cannot tell the difference because there is no difference.

### 3. One book per counterparty, keyed by ClOrdID.

ClOrdID is unique per client, not per venue. Two clients may both send `ORD-1` and they are two
orders; a single global book would collapse them and answer a cancel from one with the other's state.

So a book belongs to a `SessionID` — the same key `AcceptorDispatch` uses and the same thing a pane
is built around. Within it: keyed by ClOrdID, with a secondary index by OrderID (37) for lookup, and
`OrigClOrdID` (41) followed for cancel and replace.

The book lives with the **pane**, so a client that logs out and returns finds its orders where it
left them — the same call `attachVenueClient` already makes for message history.

### 3a. A replace chain is *recorded*, never *inherited*.

**The book records the 37 the venue actually sent. Chain inheritance across a replace is a property
of the preset (`37=${order.orderId}`), not of the fold.**

FixTool is pointed at RFQ, FX, equities, crypto and futures venues, and they do not agree here. Plenty
keep one OrderID for the life of a chain; several crypto exchanges and some futures venues issue a
**new** OrderID on every replace. Both are venues someone needs to simulate. So a preset written
`37=${uuid}` must produce a book that records the *new* OrderID, and one written `37=${order.orderId}`
must produce a book that records the inherited one — and the fold must not be able to tell them apart,
because the difference is the author's, not ours.

Concretely, the transition function may not hard-code OrderID inheritance:

```
150=5  Replaced   11=ORD-7  41=ORD-6  37=EX-100006     → ORD-7 books OrderID EX-100006
150=5  Replaced   11=ORD-7  41=ORD-6  37=EX-200042     → ORD-7 books OrderID EX-200042
```

What *is* structural is the **link**: `41` names the order this one supersedes, so the chain is
recorded either way and a cancel naming the superseded ClOrdID can still be answered. The same rule
governs quantities, per decision 2a — a replace carrying `14`/`151` books those, and one that omits
them carries the previous values forward.

This constrains slice A, which is why it was raised as blocking: a fold that inherits OrderID would
have to be *un*built later, and every book recorded in the meantime would be wrong about a venue the
tester was faithfully simulating.

### 3b. The fold is keyed by data, not by `35=D`.

The chain key, the message types that start a chain, and the ones that move it are **parameters of
the fold**, not constants inside an `OrderBook` type. `orders` is then one configuration of it: keyed
by ClOrdID, born on `35=D`, moved by `35=8`/`35=9`, chained through `41`.

This costs nothing now and it is the difference between a sibling and a redesign. Quote state is the
obvious next one — keyed by QuoteID, born on `35=S`, moved by `35=AJ`/`35=AG` — and *"an order
referencing a quote we never sent"* is the RFQ desk's version of the unknown cancel, which is the
whole reason this feature exists. A second fold over the same event log answers it; a hard-coded
order book does not.

**Not now.** Quotes are named in *What this is not* alongside the matching engine, and stay there
until someone asks. The point of this decision is only that saying yes later costs a configuration
rather than a rewrite.

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

### 4a. `whenOrder` asks what the venue held **before** this message.

*Added by slice B's build, which could not otherwise write the duplicate-ClOrdID rule at all.*

The book is fed from the wire (decision 2) and the wire is read before the rules are (slice A put the
`book()` call ahead of `maybeAutoRespond` deliberately, so a template could read the order it was
answering). Both are right. Together they mean that **by the time any rule is asked, the message's own
arrival has already been recorded** — so a brand-new `35=D` reads `pending`, and a rule conditioned
`unknown` on `35=D` could never fire for the message that created the order.

```
35=D ORD-1, never seen before    read after  → pending    read before → unknown
35=D ORD-1, already working      read after  → working    read before → working
```

Read *after*, the two are indistinguishable in the case that matters and a duplicate cannot be
detected. Read *before*, `unknown` means "a new order" and anything else means "we already have this
id", which is exactly what a tester means by a duplicate.

So the reading is taken in `fromApp` **before** `book()` records the message, and handed to
`firstMatch`. One reading per inbound message, taken once, so every rule in the list is judged against
the same answer — a reading reassembled per rule could straddle a message arriving on another thread,
and the rule that fired would be one no state ever justified.

The vocabulary is unchanged by this: `unknown` still means "no order with this ClOrdID or OrigClOrdID
on this session", asked at the moment the message arrived. And the `pending` race of decision 4 is
untouched, because a cancel is not booked at all — the order it names reads `pending` either way.

### 4b. A cancel reports on the order it names; only a replace opens a chain.

*A slice-A defect the cancel presets found, fixed in slice B.*

The router chained on "new ClOrdID naming a 41 the book holds", which is a replacement — and also
exactly the shape of a venue's own cancel reply:

```
150=5  11=ORD-7  41=ORD-6    a replacement: ORD-6 ends, ORD-7 begins
150=4  11=CXL-2  41=ORD-1    ORD-1 is canceled; CXL-2 is the request's id, not an order
```

Read the same way, the accepted cancel opened a book entry for the *cancel request* and left ORD-1
reading `working` after the client had been told it was canceled — the book disagreeing with the
client's own view of the message it had just sent them, which is the one direction decision 2 says
not to be wrong in. `OrderBook.supersedes` already existed for this distinction (`150=5`, or `39=5`);
the router simply was not asking it. Nothing about a replace changes.

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

### 6a-i. Whatever draws the book must *watch* it.

Learned by running slice A: the panel read the book with a plain call, drew `CumQty 0` against a wire
that had already traded 2500, and every test still passed — the unit tests feed the fold their own
events, and the panel test hands the panel a fresh view per assertion, so nothing below a running app
was in a position to notice the panel is never handed a second one.

So the book publishes a view on every change and anything on screen collects it. The snapshot form
stays, because a one-shot reader (the control surface) wants exactly that. A stale book is worse than
no book: it is wrong with a straight face, and the reader has no way to tell.

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
| `model/OrderBook.kt` *(new)* | `OrderState`, `OrderEvent`, `BookedOrder` as a **fold** over its events, and the transition as a pure function of (current, message, direction). Chain key, birth types and move types are **data** (decision 3b); OrderID is read off the message, never inherited (decision 3a) |
| `service/OrderBookService.kt` *(new)* | one book per `SessionID`, fed from `fromApp`/`toApp`, thread-safe, bounded |
| `service/QuickFixService.kt` | feed the book from both callbacks; expose it for the pane and the responder; record the decision on each auto-reply |
| `model/AcceptorResponseRule.kt` | `whenOrder: OrderConstraint? = null`, folded into `trigger()`'s report and `validationError()` |
| `service/AcceptorResponder.kt` | `firstMatch`/`explain` consult the book and report what it said; `${order.…}` resolved alongside `${req.…}` |
| `model/SendReason.kt` *(new, slice B)* | the reason a reply carries, and the thread-local handoff from whoever decided to the capture in `toApp` |
| `service/AcceptorPresets.kt` | book-aware rules and reply shapes; the existing ones keep working untouched |
| `ui/OrderBookPanel.kt` *(new)* | the table, the trail, the unattributed list, and the roll-up on the overview pane |
| `ui/MessageDetailPanel.kt` | the reason a reply was sent, beside the bytes it explains |
| `control/ControlServer.kt` | `GET /acceptor/orders` (orders, trails, unattributed, evictions); a state argument to `/acceptor/test` |

## Slices

**A — the book, recording and shown, with its working. SHIPPED 2026-07-31** (`b741997`, `21c2f9a`,
`0a85751`). No behaviour change: no rule can ask about it and no template can read it, so nothing that
works today works differently. The panel, the per-order
trail, the roll-up, the unattributed list, the eviction count and the cleared-at line, and
`GET /acceptor/orders` (+ `fixtool_acceptor_orders`). Shippable alone, and immediately worth having —
"what does the venue think it is holding" currently has no answer at all.

The trail is in this slice rather than a later one because it is not a nicety on top of the book: it
is the difference between a number the tester has to trust and a number they can check, and a QA tool
that asks to be trusted has picked the wrong side of that.

The endpoint is deliberately in the *first* slice too: it is what lets this be driven and verified
without a mouse, which is the gap #40 left open.

**B — rules can ask, and say what they read. SHIPPED 2026-07-31**, live-verified. `whenOrder` on the
rule, in the editor, in `explain`, in `/acceptor/rules`, and in `/acceptor/test` — which gained an
`orderState` argument and reports `assumedOrderState` back whether or not one was given, since a dry
run of a stateful trigger has to name the state it assumed. Every auto-reply records the rule that
chose it and what the book said at that moment (decision 6a), shown against the reply in the detail
panel; hand-sent replies record the shape that was picked and the state it was composed against.
Presets: *cancel rejected — unknown order*, *cancel accepted — the order is working*, *duplicate
ClOrdID rejected* (two rules, `pending` and `working` — see below), and *status request — unknown
order*. This is the slice that answers the issue's acceptance criteria about validation.

Four things the build settled that the design had not:

- **Decision 4a**, above: the constraint reads the state *before* the message. Without it the
  duplicate preset is unwritable.
- **Decision 4b**, above: a routing defect in slice A that the cancel presets exposed.
- **Duplicate ClOrdID is two rules, not one.** "The book already holds this id" spans `pending` and
  `working`, and the vocabulary has no word for "either". Inventing one to save a card would cost the
  four words the property that earns them (decision 4), so the preset ships both and an author who
  wants duplicates of *finished* orders rejected adds the same rule with `done`.
- **The status-request presets are two venues, not one.** "Unsupported message" (`380=3`) is a venue
  that does not answer `35=H` at all; the new one (`380=1`, Unknown ID) answers them but has never
  heard of this order. Conditioning the first into the second would have left neither available, so
  the existing preset is untouched and the new one sits beside it. Same call as the settled question
  about stateless presets, for the same reason.

The structural refusal from settled question 1 ships here too, ahead of the feature it constrains: a
rule whose reply contains `${order.…}` and whose `whenOrder` is absent or `unknown` is a
`validationError`. Slice C therefore cannot ship the hazard.

**C — templates can read.** `${order.…}`, and the presets and `Reply With…` shapes rewritten to use
it: fills that accumulate, and `37=${order.orderId}` so an order keeps one OrderID across every reply
it ever gets — where the author wants that (decision 3a). Fixes the identity defect named at the top.

Also the preset that only becomes writable here, and needs no new mechanism at all — **status request
answered from the book**:

```
when 35=H and the order is working →
  35=8|150=I|37=${order.orderId}|11=${req.11}|39=${order.ordStatus}
      |14=${order.cumQty}|151=${order.leavesQty}|55=${order.symbol}|54=${order.side}|60=${now}
```

`150=I` is ExecType *Order Status*, which is exactly what an unsolicited state dump is, and every
field of it is a fact the book already holds. It pairs with B's unknown-order variant: between them a
venue answers "where is my order?" the way a venue does, and neither rule needed a primitive that did
not already exist. It is the clearest evidence that decision 1 was the right shape — the book is
worth having because rules can *read* it, not because it acts.

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
  a finished order before a working one. Two more that exist because of the review:
  **the same replace, sent twice with `37=${order.orderId}` and with `37=${uuid}`, books the OrderID
  the venue sent each time** (decision 3a — the test that fails if inheritance is ever hard-coded);
  and **a `150=H` bust moves CumQty down**, from a hand-sent report as readily as a rule's (2a).
- **Engine** — `explain` reports `whenOrder` like any other condition; a rule conditioned `unknown`
  does not fire for an order the book holds; `${order.…}` resolves in both spellings.
- **Integration (real venue, real client, real port)** — the claims nothing below can make: a cancel
  for an order that was never sent comes back `35=9`, and one for a live order comes back canceled,
  *from the same rule list*; a partial then a full fill leave the client's own view of CumQty
  consistent; two clients each sending `ORD-1` get two books and neither cancel answers the other's
  order; a two-step reply reads its own first step's effect; **and the reason recorded against a reply
  still names the state that produced it after the order has moved on** — the one claim that fails the
  moment reasons are re-derived instead of recorded.

Slice B added one more layer distinction worth naming. `firstMatch` and `explain` are separate
implementations on purpose, and `whenOrder` joined the existing cross-check that re-asks every case
through both — against **all five** book states, including *no book at all*, which is the case a
caller that forgot to take a reading lands in and whose answer must not be "fires anyway". The
duplicate-ClOrdID integration test is what pins decision 4a: reverse the two lines in `fromApp` and it
fails while every unit test still passes.

## What this is not

Not a matching engine: no book of *other people's* orders, no price-time priority, no crossing, no
market data. Not positions or P&L. Not persistence. Not an initiator-side view of orders a client has
sent — that is a different feature for a different user, and folding it in here would make the book
answer two questions with one table.

**Not quote or RFQ negotiation state.** `35=R`/`35=S`/`35=AJ` keyed by QuoteID, and *"an order
referencing a quote we never sent"* — the RFQ desk's unknown cancel. It is the most likely next
sibling and decision 3b is what keeps it a configuration of the same fold rather than a second
design, but it is **excluded from these four slices** and stays excluded until someone asks for it by
name. Naming it here is the difference between a boundary and an oversight.

**Not mass cancel (`35=q`) or mass status (`35=AF`).** These are excluded by a *decision*, not by
inattention: one inbound message has to fan out over N book entries, and the acceptor is built on
one-message-in → replies-out. A rule that answered a mass cancel would have to emit a report per
working order, which is not a shape `plan()` can express, and bolting it on would make the sequence
model mean two different things. If a venue needs to be simulated cancelling forty orders at once,
that is its own design with its own issue.

**Not an assertion target, yet.** "At this point in the run the venue holds ORD-1 filled" is a
scenario expectation over venue state, and it is the natural next thing a QA user asks for once the
book exists. It is deliberately out of these four slices — it needs the scenario engine's
expectation vocabulary, not the acceptor's — but it is the reason the book is a fold over an
addressable log rather than a live table: a shape you can assert against later costs nothing to
choose now.

## Open questions — settled

**Design review 2026-07-31: approved as sliced, and all six recommendations below accepted as
written.** They are kept here as the record of what was decided and why, not as anything still open.
The review's own amendments are decisions 2a, 3a and 3b, the status-request presets in B and C, and
the two exclusions above; #35's build starts from slice A.

Each was a decision I had taken a position on but would have changed on a word. The example is what
it looks like either way, not an abstraction of it.

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
