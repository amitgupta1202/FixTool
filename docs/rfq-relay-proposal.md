# Two parties, one venue — relaying an RFQ between a requester and a responder

**Status:** proposal, for review. Nothing built.
**Depends on:** #30 rules engine, #32 multi-client venue, #35 order and quote books, the cross-session
Trace, scenario variables. All shipped.
**Mockups:** https://claude.ai/code/artifact/40b7ef67-e9fa-4404-88a9-b29ae447ccb8 (source: `docs/mockups/rfq-relay.html`)

---

## The problem

Asked 2026-09-13, in the user's words: *"I have worked on BrokerTec where 2 parties negotiate before the
deal is done. For example buy side sends a buy request, then sell side prices (Quote), then buy side
accepts. 2 clients involved. How does our FI RFQ venue work?"*

It doesn't work that way. The bundled **Fixed Income RFQ Desk** is a dealer, not a venue. Both of its
clients are requesters, and the acceptor itself does the pricing, using 26 rules. Client 1 and Client 2
never see each other; each has a private conversation with a simulated sell side.

That isn't a mistake in the example. It's the ceiling of the engine, and three facts in the code set it:

- **A reply always goes back to whoever sent the trigger.** `AcceptorDispatch.schedule(sessionId, …)`
  (`AcceptorDispatch.kt:65`) is called with the triggering session and nothing else
  (`QuickFixService.kt:982`). No rule field names a destination; `AcceptorResponseRule` has a trigger and
  a sequence of steps, and a step is a template and a delay.
- **Every book is per counterparty.** `QuoteBookService` is "one quote book per counterparty session"
  (`QuoteBookService.kt:16`), and so is the order book. Nothing in a venue spans two sessions, so nothing
  can know that the Quote arriving from a dealer answers the QuoteRequest that arrived from a buy side.
- **A venue has no idea who plays what part.** The venue pane says so itself: *"Response rules apply to
  every client on this venue."* (`AcceptorOverviewPane.kt:115`). A dealer and a buy side are the same
  thing to it.

So FixTool can sit on **both sides of a real venue** (the major client's scenario shape: one scenario, a
requester session and a responder session). But it can't **be** that venue. A team wanting to develop
those scenarios before the real venue is up, or to demo the flow to someone, has nothing to point at.

## What already exists — do not rebuild

- **`AcceptorResponder`**: `firstMatch`, `explain`, and `plan`, with the whole substitution vocabulary
  (`${req.<tag>}` including header tags such as `${req.49}`, `${req.uuid}`, `${uuid}`, `${now}`, the
  shorthand generators, `${order.…}`, `${quote.…}`) and the structural refusals that stop a reply reading
  something the venue doesn't have.
- **`AcceptorDispatch`**: one thread, replies off the callback thread, pending work tracked **per
  `SessionID`**. Sending to a second counterparty's session is a different key, not a different
  mechanism.
- **The multi-client venue** (#32): one acceptor, one `QuickFixService`, a session and a pane per
  counterparty, and refused logons made visible.
- **The quote book**, which already judges a requester's hit as stale, spent or unknown against the
  quotes the venue *showed that requester*. A relayed quote is still a quote the venue sent.
- **`SendReason`**, the recorded "why the venue sent this", which a relay needs to extend, not
  replace.
- **Traces**, the union-find over correlation-id values across every session.
- **Scenarios**: every step already names its own session, so a two-party scenario (send on the buy
  side, expect on the dealer, send on the dealer, expect on the buy side) can be written **today**. It
  just has nothing to run against.
- **The rule card** (`AcceptorRulesEditor`): the "and the order is" / "and the quote is" rows are the
  pattern new constraints follow.

## The flow this makes possible, against the bundled FIX 4.4 dictionary

A buy side wants 10mm of the ten-year and asks two dealers through the platform. Every id the venue
passes across is re-keyed (decision 4), so each party only ever sees ids it minted or was given.

| # | From → to | Message | What the reader should notice |
|---|---|---|---|
| 1 | Buy Side 1 → Platform | `35=R` `131=BUY-RFQ-7` `146=1` `55=T 4.25 11/15/36` `48=91282CMF7` `22=1` `54=1` `38=10000000` | the buy side's own id |
| 2 | Platform → Dealer 1, Dealer 2 | `35=R` `131=V-RFQ-1042` … same instrument, side and size | one rule, two messages, a venue id |
| 3 | Dealer 1 → Platform | `35=S` `131=V-RFQ-1042` `117=D1-Q-88` `133=98.515625` `634=4.250` `62=+30s` `537=1` | the dealer's own quote id |
| 3′ | Dealer 2 → Platform | `35=S` `131=V-RFQ-1042` `117=D2-551` `133=98.531250` | a worse offer |
| 4 | Platform → Buy Side 1 (×2) | `35=S` `131=BUY-RFQ-7` `117=V-Q-1042-1` `133=98.515625` `453=1` `448=FIDLR1` `452=35` | the buy side's id back, a venue quote id, the dealer named as liquidity provider |
| 5 | Buy Side 1 → Platform | `35=AJ` `693=BUY-RESP-7` `694=1` `117=V-Q-1042-1` `11=BUY-TRD-7` `54=1` `38=10000000` `44=98.515625` | lifts the better offer |
| 6a | Platform → Buy Side 1 | `35=8` `37=V-T-1042` `11=BUY-TRD-7` `693=BUY-RESP-7` `150=F` `39=2` `54=1` `31=98.515625` `32=10000000` | confirmation, in the buy side's words |
| 6a′ | Platform → Buy Side 1 | `35=AE` `571=V-TR-1042` `570=N` `55=…` `32=10000000` `31=98.515625` `75=…` `552=1` `54=1` `37=V-T-1042` | the trade capture report, as BrokerTec sends one |
| 6b | Platform → Dealer 1 | `35=AJ` `694=1` `117=D1-Q-88` `11=V-ORD-1042` then `35=8` `37=V-T-1042` `11=V-ORD-1042` `54=2` `31=98.515625`, then `35=AE` with `54=2` | "you were lifted", the fill on **its** side, and its trade capture report |
| 6c | Platform → Dealer 2 | `35=AJ` `694=4` `117=D2-551` | Cover: the runner-up is told |

Checked against `dictionaries/FIX44.xml`: `694` is 1 Hit/Lift, 2 Counter, 3 Expired, 4 Cover, 5 Done
Away, 6 Pass. `658` includes 6 *Not authorized to request quote* and 10 *Pass*. `452` includes 17 *Contra
firm* and 35 *Liquidity provider*. A FIX 4.4 ExecutionReport has **no QuoteID and no QuoteReqID**, which
is why the dealer is told about the lift with a QuoteResponse naming its own `117` before the fill. And a
QuoteStatusReport **requires** `117`, which matters for expiry (decision 10).

## The decisions

### 1. The venue relays by rule. Nothing is forwarded by default.

A platform that passed messages through on its own would be a second decider beside the rule list.
It would also hide the part a tester most needs to see: what the venue did to a message on the way
across (which ids it replaced, what it added, what it refused). So a relay is a **reply step with an
address**, on a card that can be read, reordered and switched off. This is decision 1 of
`acceptor-order-state-proposal.md` again: the book records, the rules still decide.

The cost is that relay rules are written out in full rather than toggled on. Presets carry that
cost (the platform example ships them), and the dry run shows every message a rule would send to every
party before anything connects.

### 2. A step says who it goes to, and "sender" is the default.

`ResponseStep` gains `to`. Absent means **sender**, so every rule on disk means exactly what it means
today.

| `to` | Goes to | Typical use |
|---|---|---|
| `sender` | whoever sent the trigger | every rule written before this |
| `requester` | the counterparty that opened the RFQ this message belongs to | relaying a quote back |
| `quoter` | the responder whose quote this message names | telling a dealer it was lifted |
| `cover` | the responder with the best other **live** quote on the traded side | `694=4` |
| `others` | every responder holding a live quote, except the quoter and the cover | `694=5` |
| `quoted` | every responder holding a live quote | a pass, expiry notices |
| `asked` | every responder the RFQ was sent to, quoted or not | `131`-level notices only |
| `responders` | every counterparty in the responder role that is logged on **now** | opening an RFQ |
| `compId:<X>` | one named counterparty | a fixed route |

**A step addressed to several parties renders once per recipient.** `${uuid}` draws per recipient,
and `${to.compId}` names the one being written to. The offset is the step's own; a fan-out isn't a
sequence.

A step whose address resolves to nobody (a lift with no cover, `responders` with nobody logged on)
**sends nothing and says so**, on the dry run and in the venue pane's count (decision 7). It isn't an
error: a single-dealer RFQ has no cover.

### 3. Roles are declared on the venue, by CompID.

The venue profile gains **Counterparties**: a list of CompIDs (a trailing `*` allowed, so `FIDLRLG*`
covers a five-lane load client), each marked **requester** or **responder**. A counterparty that logs
on and matches no entry still connects (`TargetCompID=*` stays opt-in and unchanged), but it has no
role, and the only address that reaches it is `sender`.

The trigger gains one row, beside the order and quote rows: **and the sender is** any / requester /
responder / unlisted. *(Amended by the implementation review: on disk it is a `role` matcher on tag 49
inside `conditions`, not a new rule field. An older build silently drops unknown fields and would run the
rule looser, while a matcher type it can't parse makes it drop the whole rule, which is the safe
direction. The card still draws it as a row. Any rule with a non-sender address must carry one.)*
That makes the platform's entitlement checks ordinary rules: *When 35=R and the
sender is not a requester → `35=AG 658=6`, not authorized to request quote.* Refusals are test results
(#32's constraint): a dealer that sends a QuoteRequest is misconfigured, and a simulator that relayed it
would report green on a real defect.

Declared rather than inferred. "Whoever sends an R is a requester" would make that refusal
unwritable, and the role would change under the tester's feet the moment a dealer misbehaved.

### 4. The venue re-keys every id it passes across.

Two reasons, and the second is the one that forces it:

- **A real platform does.** A buy side never sees a dealer's internal quote id, and a dealer never sees
  the buy side's.
- **Ids are only unique per counterparty.** Two buy sides can both send `131=RFQ-1` on the same morning,
  and a venue that passed them through would hand both dealers two different RFQs with one id.

So the book keys everything by **(counterparty, id)** (decision 5). A relay template writes the ids it
wants: a venue id drawn once when the RFQ opens (`131=${req.uuid}`, the same value for every dealer), a
fresh one per dealer (`131=${uuid:10}`), or the counterparty's own id handed back (decision 6). A tester who
wants pass-through writes `131=${req.131}` and gets it, knowing what it costs.

### 5. One RFQ book per venue: the first thing in the venue that spans sessions.

Beside the per-counterparty order and quote books, and fed the same way (from the wire, never from
intentions). What it records is **links**:

- **An RFQ is requested** when a requester's `35=R` arrives (requester, its `131`, instrument, side,
  size), **open** once it has been relayed to a responder, **refused** when the venue answers the
  requester with `35=AG`, **passed** when the requester sends `694=6`, **done** when a trade is decided,
  and **expired** by the clock.
- **A relay records what crossed**: when a step sends message *M* to counterparty *C* because trigger *T*
  arrived from counterparty *S*, every correlation id on *M* is linked, **for C**, to the RFQ *T*
  belongs to. That's how the dealer's later `35=S 131=V-RFQ-1042` finds the RFQ, and how the buy side's
  `35=AJ 117=V-Q-1042-1` finds Dealer 1 and `D1-Q-88`.
- **A leg** is one responder on one RFQ: asked, quoted (its `117` and its price), passed (`35=AG`), lifted,
  covered, done away.

The trigger gains **and the RFQ is** unknown / open / done / expired (an `rfq` matcher on `131` or `117`,
for the reason decision 3 gives). Same shape and same rule as `whenQuote`: **a trigger** reads what the venue
held **before** this message (decision 4a of the order-state proposal). A dealer quoting an RFQ that has
already traded matches `done` and is refused, not relayed. **Addresses and references read it after** the
message is recorded, the way `${order.…}` reads the live book; otherwise the opening `35=R` would have no
RFQ to relay.

**A trade is recorded when it is decided, not when its fill reaches the wire.** A rule that sends a `35=8`
to the quoter is a trade, and the book marks the RFQ done on the callback thread before anything is
scheduled. It is the one thing the book takes from a decision rather than the wire, and it has to be: the
fills go out on the dispatch thread, and a second lift is already queued behind the first. Recording on the
wire would let one RFQ trade twice.

The requester's own **quote book is unchanged and still does its job**: the relayed `35=S` is a quote
the venue sent that counterparty, so a late, repeated or invented hit is refused exactly as the FX RFQ
venue refuses one today.

### 6. `${to.<tag>}` is the id as the recipient knows it.

The whole re-keying vocabulary is one reference. **`${to.117}` is the quote id this recipient knows for
the quote the trigger is about.** Written to Dealer 1 about a lift of `V-Q-1042-1` it's `D1-Q-88`, the quote
that relayed id was linked to, not the dealer's most recent one, which after a re-quote would be the wrong
price. Written to the buy side it's `V-Q-1042-1`. `${to.131}` is `V-RFQ-1042` to a dealer and `BUY-RFQ-7` to
the buy side. Any other tag is the last value exchanged with that recipient on that leg.

It **refuses rather than substituting empty**, like `${order.…}` and `${quote.…}`: a QuoteRequest fanned
out to dealers who have never heard of this RFQ has no `${to.131}` to read, and `131=` on the wire would
be the malformed message the preset discipline exists to prevent. The refusal is on the card before
anything connects.

Two small additions round it out: `${rfq.requester}` and `${rfq.quoter}` (CompIDs, for `448`/`375`), and
`${rfq.asked}` / `${rfq.quoted}` (counts). Prices need nothing new: the relayed quote is in the requester's
quote book, so `31=${quote.offer}` already reads it.

### 7. A relay to someone who isn't there is not queued. It's counted and shown.

`AcceptorDispatch` already drops pending work for a session that logs out, because a counterparty that
has gone away should not be replied to. A relay to a responder who **was never logged on** gets the
same honesty up front: nothing is sent, the RFQ book's leg says **not delivered**, the venue pane counts
it beside refused logons, and the venue's event stream names the rule and the counterparty (*"rule 4
addressed responders; FIDLR2 is not logged on"*). A received message carries no reason of its own, so this
is said where the venue speaks, not on the trigger. Anything dropped because a recipient logged out after
the rule fired is counted the same way.

The trigger gains **and responders online** any / none. That lets the platform refuse at once, the way a
real one does (*When 35=R and responders online is none → `35=AG 658=99 58=No dealer online`*), rather than
opening an RFQ that nobody will ever see.

### 8. The trade is confirmed to both sides, each in its own words.

Not a mechanism, a consequence of decisions 2 and 6, stated because it's the moment the example has to
get right. On a lift, one rule sends:

1. to **sender**: `35=8` with the buy side's `11`, `693` and side, then a `35=AE` TradeCaptureReport;
2. to **quoter**: `35=AJ 694=1 117=${to.117}` (the dealer's own quote), then `35=8` with the venue's `11`
   and the **opposite** side, then its own `35=AE`;
3. to **cover**: `35=AJ 694=4`; to **others**: `35=AJ 694=5`.

Both `35=8`s carry the same `37`, drawn once for the trigger (`${req.uuid}`). That's the one id a
reconciliation between the two sides would join on, so it's the one the venue must not re-key.

**The RFQ book claims what it relayed**, the way the quote book already claims a booked hit
(`QuickFixService.kt:790`). The `35=8` sent to the dealer carries a ClOrdID the dealer's order book never
saw, and without the claim every lift would leave an "unattributed" line on the dealer's pane.

### 9. Trace follows a relay without a shared id.

Re-keying breaks the chain Trace draws: no id value appears on both legs, so the union-find
(`CorrelationComponents.of`) sees two unrelated conversations. A heuristic join by time or instrument
is exactly the false correlation that component was built to refuse.

The venue doesn't need to guess. It **knows**, at the moment it relays, which message it relayed from,
and `SendReason` already records the decision at the moment it's made (decision 6a). The reason gains the
trigger's identity, and Traces joins a relayed message to its trigger as an edge. It's a record, not a
derivation. The reason line on the message reads *"relayed by rule 3 from FIBUY1's 35=R"*.

### 9a. When FixTool is the venue, Lanes draws the venue as one lane in the middle.

The shipped Lanes (`TraceLanes`, e34f5815) draws one lane per pane: initiators on the left, acceptors on
the right, and "the venue under test is the space between the lanes, and nothing is drawn there". That
was written for a venue FixTool is testing from outside. Here FixTool **is** the venue, with one pane per
counterparty, so the same trace would draw six lanes (Buy Side 1, Dealer 1, Dealer 2, then three
`Platform ← …` panes) with the negotiation zig-zagging across the divider.

When the venue declares roles, Lanes changes in three ways:

- **The venue's per-counterparty panes merge into one lane**, labelled with the venue and its pane count.
- **Lanes are ordered by role**: requesters, the venue, responders.
- **A relayed arrow carries its reason** under it ("relayed · rule 4 · to responders"), read from
  `SendReason`, never re-derived.

Same-bytes pairing is unchanged: the buy side's `35=R` OUT and the venue pane's IN are still one row.
The Ledger is unchanged too: it still lists every pane's copy.

In the mockup, the id colours (by whoever minted each id) and the hover that lights linked ids are
explanation, not part of this decision. They could be built from the RFQ book's links, but they aren't
proposed here.

### 10. Time is a trigger: an RFQ that nobody trades still ends.

Every rule today fires on an arriving message. An RFQ nobody lifts, a dealer who never answers, a quote
that lapses: a real platform answers all three **without** being sent anything, and today the
negotiation just stops. So a rule can be written **When the RFQ expires** (at the request's `126`
ExpireTime, or a venue default when it has none), with the same steps and addresses.

The dictionary constrains the words: a QuoteStatusReport requires `117`, so an RFQ that expires with
quotes gets `35=AI 297=7` per quote, and one that expires unquoted gets `35=AG` to the requester. That's
the example's choice, written on its cards, not the engine's.

A separate slice (C) because it adds the first thing in the venue that runs without an inbound message:
a scheduled check, cancelled when the RFQ ends and on logout.

### 11. A responder that answers by itself is rules on an initiator.

For a live demo, a person playing the dealer is better: that's the point of two parties. For **volume**,
somebody has to quote 2,000 relayed RFQs, and every bundled example promises a load set that runs
without editing. The rule engine already knows how to quote (the FI desk's 26 rules do nothing else); it
just refuses to run on an initiator (`QuickFixService.kt:918`).

So an initiator profile may carry rules too, with the same card and dry run. Its books are the quote
book it already fits (a dealer sends `S` and receives `AJ`, the direction that book is built on). The
FI desk's pricing rules become **Dealer Load Client**'s rules, and the load set is burst RFQs on the buy
side, a dealer that quotes by itself, and a reactive lift.

Its own slice (D), and separable: slices A to C are complete without it, minus the load set.

## Where it shows

- **Venue settings → Counterparties**: CompID, role, and a live dot. The same list on the venue pane,
  with *not delivered* counted beside *refused logons*.
- **The rule card**: "and the sender is", "and the RFQ is", "and responders online" rows; a **To** chip on
  every step, showing the recipients it resolves to on the dry run.
- **RFQ book**: on the venue's own pane (it spans counterparties, so it can't live on a client pane the
  way the order book does). One row per RFQ, legs underneath, each id shown the way each side knows it.
- **Trace, Lanes**: the relay drawn through the venue lane, the reason on the arrow.

All drawn in the mockups.

## The example: Fixed Income RFQ Platform

**Recommended: the Fixed Income RFQ Desk becomes the platform.** The desk is what confused a reader who
works in this market, and a single-dealer RFQ is already what the FX RFQ Venue demonstrates. Each bundled
example should prove something the others can't, and two single-dealer RFQ venues don't. The FX RFQ
venue keeps the dealer shape and picks up the three corrections it still owes (quote one way to a
disclosed side, `537`, Cover and Done Away accepted), taken from the FI desk.

| Profile | CompID | Role |
|---|---|---|
| FI RFQ Platform | `FIRFQ_VENUE`, port 19880, `TargetCompID=*` | venue |
| Buy Side 1, Buy Side 2 | `FIBUY1`, `FIBUY2` | requester |
| Dealer 1, Dealer 2 | `FIDLR1`, `FIDLR2` | responder |
| Buy Side Load Client (5 lanes) | `FIBUYLG{n}` | requester (slice D) |
| Dealer Load Client (5 lanes, rules) | `FIDLRLG{n}` | responder (slice D) |

The platform's own rules are **relay and entitlement only**: open an RFQ to every responder online;
refuse an R from a non-requester, for an unknown CUSIP (`658=1`), with no size, or with nobody online;
relay a quote to its requester; refuse a quote for an RFQ that is unknown, done or expired; on a lift,
confirm both sides and tell the cover and the others; relay a pass as a leg state only.

Templates: the buy side's request (two-way, buying, selling), lift, hit, pass; the dealer's quote (offer,
bid, two-way), pass (`35=AG 658=10`). Dealer templates read `${in.R.131}`, so a person playing the dealer
answers the RFQ in front of them.

Scenarios, each two-party and each asserting both sides:

1. **Buy side lifts Dealer 1's offer**: request, relayed request on the dealer, quote, relayed quote,
   lift, both confirmations with the same `37`.
2. **Two dealers quote; the better one is lifted**: Dealer 1 filled, Dealer 2 told Cover.
3. **A dealer cannot request**: Dealer 1 sends an R and gets `658=6`; no buy side sees anything.
4. **Unknown CUSIP is refused at the platform**: no dealer is asked. *(slice C adds: nobody lifts, and
   everyone is told it expired.)*

The existing `fixed-income-rfq` copies on disk keep the desk (examples are copied once); Reset lays down
the platform.

## Slices

Ranked by what each one unlocks. Each ships green and live-verified before the next starts.

- **A: Relay.** `ResponseStep.to`; Counterparties and roles on the venue; the RFQ book (open, link, legs,
  claim); `whenSender`, `whenRfq`, `respondersOnline`; `${to.<tag>}` and `${rfq.…}` with their refusals;
  fan-out rendering; not-delivered accounting; `SendReason` relay line; Trace edge; dry run and
  `/acceptor/test` showing every recipient; `/acceptor/rfqs` on the control surface and an MCP tool; rule
  card rows and the To chip; RFQ book on the venue pane.
- **B: The platform example.** Reshape `fi-rfq-venue` as above; the three corrections move to the FX RFQ
  venue; help chapter; `ExampleWorkspacesTest`, reset counts, integration test, `HelpDocTest` pins.
- **C: Time.** When the RFQ expires; the scheduled check, cancelled on end and logout; the fourth
  scenario.
- **D: A responder that answers by itself.** Rules on initiator profiles; Dealer Load Client; the
  platform's load set with a reactive lift.

## Acceptance criteria — slice A

- A rule written before this, loaded from disk, sends exactly what it sent before. Pinned by the
  existing acceptor suites passing untouched.
- A `35=R` from a requester reaches every logged-on responder, re-keyed, and nobody else; a responder
  that isn't logged on gets nothing and is counted.
- The dealer's `35=S` naming the venue's `131` reaches the right requester with the requester's own `131`,
  including with two requesters using the **same** `131`.
- A lift sends a `35=8` to each side carrying the same `37` and opposite sides, the quoter's own `117` on
  its QuoteResponse, and `694=4` to the cover.
- A quote for an RFQ that has traded is refused to its dealer, not relayed.
- A second lift on the same RFQ, sent straight after the first, is refused and books nothing.
- A lift of a quote its dealer has since replaced is refused.
- `${to.131}` on a step that can't have one is refused on the card and in `/acceptor/test`, never sent
  as `131=`.
- Follow on the buy side's `35=R` shows all fourteen messages of the negotiation (the flow table's rows,
  with each fan-out and each two-step confirmation counted as sent) in one trace.
- Nothing unattributed on either pane after a full lift.

## Open questions (for the person who knows the market)

1. **Disclosed or anonymous?** Does the buy side see which dealer quoted (`453`/`452=35`), and does the
   dealer see who asked? The proposal puts it in the templates (the engine doesn't care); the example
   needs a default. Proposed: disclosed both ways.
2. **Firm or last look?** On a lift, does the platform trade at once (proposed) or ask the dealer to
   confirm first? A "subject" variant is two more rules, not a feature, but the example should pick one.
3. **Who chooses the dealers?** Proposed: the platform asks every responder online. Real multi-dealer RFQs
   often name the dealers in the request's `Parties`; that would be an address reading `453` off the
   trigger, a small follow-up.
4. **Counters.** Is a `694=2` counter relayed back to the dealer for another round? Proposed: not in B;
   one round first.
5. **Cover.** Does the runner-up get told Cover, and everyone else Done Away? Proposed: yes.
6. **Replace the FI desk, or add a sixth example?** Proposed: replace (above).
7. **On a firm platform, how is the dealer told its quote traded?** *Answered 2026-09-13:* on BrokerTec
   the dealer gets **both** a QuoteResponse and an ExecutionReport, and **a TradeCaptureReport (`35=AE`) is
   sent for the trade as well**; some of these can be switched off by venue settings. So the example sends
   all three, to each side, in that order. Making one optional is deleting that step from the rule.

## What this is not

- **Not a router.** The addresses are an RFQ's parts. A venue that routes a client's `35=D` to a
  liquidity provider and relays the fills back is the same link mechanism under a different vocabulary
  (keyed by ClOrdID), and a sibling to this one, not a flag on it.
- **Not matching.** The platform never decides a price. The buy side lifts; the platform confirms.
- **Not per-session rules.** Roles decide addresses and conditions; there's still one rule list per venue.
- **Not persistence.** The RFQ book lives with the venue session, like the order and quote books.
