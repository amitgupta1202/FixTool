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

### 7. What the book cannot attribute is counted, not hidden.

A report with no ClOrdID, or one naming an order the book has never seen, is not silently dropped: it
increments an *unattributed* counter shown beside the book. A book that quietly ignores what it does
not understand looks identical to a book that is working, which is the failure mode that would waste
a tester's afternoon.

### 8. Where it shows.

A panel per session pane, the way the latency panel already works — one book per counterparty,
beside that counterparty's messages. The venue overview (`AcceptorOverviewPane`) gains a roll-up
line per client: how many orders, how many working.

A row per order: ClOrdID, OrderID, Symbol, Side, OrderQty, CumQty, LeavesQty, Status, and the time of
the last thing that moved it. Selecting one filters the grid to that order's messages, which is the
question a row prompts — *what did we tell them?*

## What changes where

| file | change |
|------|--------|
| `model/OrderBook.kt` *(new)* | `OrderState`, `BookedOrder`, and the state machine as a pure function of (current, message, direction) |
| `service/OrderBookService.kt` *(new)* | one book per `SessionID`, fed from `fromApp`/`toApp`, thread-safe |
| `service/QuickFixService.kt` | feed the book from both callbacks; expose it for the pane and the responder |
| `model/AcceptorResponseRule.kt` | `whenOrder: OrderConstraint? = null`, folded into `trigger()`'s report and `validationError()` |
| `service/AcceptorResponder.kt` | `firstMatch`/`explain` consult the book; `${order.…}` resolved alongside `${req.…}` |
| `service/AcceptorPresets.kt` | book-aware rules and reply shapes; the existing ones keep working untouched |
| `ui/OrderBookPanel.kt` *(new)* | the table, and the roll-up on the overview pane |
| `control/ControlServer.kt` | `GET /acceptor/orders` |

## Slices

**A — the book, recording and shown.** No behaviour change: no rule can ask about it and no template
can read it, so nothing that works today works differently. The panel, the roll-up, the unattributed
counter, and `GET /acceptor/orders` (+ `fixtool_acceptor_orders`). Shippable alone, and immediately
worth having — "what does the venue think it is holding" currently has no answer at all.

The endpoint is deliberately in the *first* slice: it is what lets this be driven and verified
without a mouse, which is the gap #40 left open.

**B — rules can ask.** `whenOrder` on the rule, in the editor, in `explain`, in `/acceptor/rules`, and
in `/acceptor/test`. Presets: *cancel rejected — unknown order* (conditioned `unknown`), *cancel
accepted* (conditioned `working`), *duplicate ClOrdID rejected* (a `35=D` whose ClOrdID is already
known). This is the slice that answers the issue's acceptance criteria about validation.

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
  unknown ClOrdID lands in the unattributed count.
- **Engine** — `explain` reports `whenOrder` like any other condition; a rule conditioned `unknown`
  does not fire for an order the book holds; `${order.…}` resolves in both spellings.
- **Integration (real venue, real client, real port)** — the claims nothing below can make: a cancel
  for an order that was never sent comes back `35=9`, and one for a live order comes back canceled,
  *from the same rule list*; a partial then a full fill leave the client's own view of CumQty
  consistent; two clients each sending `ORD-1` get two books and neither cancel answers the other's
  order; a two-step reply reads its own first step's effect.

## What this is not

Not a matching engine: no book of *other people's* orders, no price-time priority, no crossing, no
market data. Not positions or P&L. Not persistence. Not an initiator-side view of orders a client has
sent — that is a different feature for a different user, and folding it in here would make the book
answer two questions with one table.

## Open questions

1. **`pending`** — worth the fourth word, or is "the venue has it" enough (three words: `unknown`,
   `working`, `done`)? Recommendation: keep it; the race it names is real and a tester hitting it
   would blame the tool.
2. **Do the book-aware presets replace the current ones or sit beside them?** Recommendation: beside,
   with the stateless ones kept — they work on a venue whose book was cleared, and a preset that
   silently needs state is a preset that silently does nothing.
3. **Slice D** — in scope, or leave the manual path where #40 put it?
