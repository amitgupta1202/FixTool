# The RFQ Venue example, and the load run verified against it

A second shipped example beside the FX Venue: a request-for-quote desk a client drives through the whole
flow and books a trade on. QuoteRequest (35=R) in, Quote (35=S) out, QuoteResponse (35=AJ) in,
ExecutionReport (35=8) out for the booked trade, and a QuoteStatusReport (35=AI) for everything that
cannot be booked. It is data, copied into a workspace the user names, exactly as the FX Venue is, and it
is the first real use case for the load run feature (#42, #43): a burst of quote requests, then a second
run that books the quotes the first one created without having seen them.

Two slices. The **first is echo based and ships**: every reply is built from the triggering message and
the rule's own content, with no quote state in the venue. The **second adds quote state to the book**
(keyed by QuoteID, born on 35=S, moved by 35=AJ and 35=AI, as `BookSpec`'s own KDoc names it) and the
guard rule "a QuoteResponse for a quote this venue never sent is rejected". The second slice is sketched
at the end and deliberately not built: it needs `OrderBookService` to hold a second book, and the first
slice must not wait on that.

## The flow, against the bundled FIX 4.4 dictionary

The brief was written from memory of FIX and the dictionary corrects it in four places. Each one changes
a message shape below, and each is the kind of thing a client's validator would have caught first.

- **QuoteResponse (AJ) carries no QuoteReqID (131).** Its required fields are QuoteRespID (693),
  QuoteRespType (694) and Instrument (55). QuoteID (117), ClOrdID (11), Side, OrderQtyData, Price (44)
  and ValidUntilTime (62) are optional.
- **ExecutionReport (8) carries neither QuoteID (117) nor QuoteReqID (131).** It does carry QuoteRespID
  (693) and ClOrdID (11). So the trade report cannot echo the quote's ids, and it ties back to the
  response by 693 and to whatever the client named its trade in 11.
- **QuoteStatusReport (AI) has no QuoteRejectReason (300).** It says why in QuoteStatus (297) and Text
  (58). QuoteID (117) and Instrument (55) are required on it.
- **QuoteRespType 6 is Pass, not 5.** 5 is Done Away. 1 Hit/Lift, 2 Counter, 3 Expired, 4 Cover.

With those in hand, what the venue sends:

| Client sends | Venue answers |
|---|---|
| `35=R` for a priced pair, with `38` | `35=S` with `131` echoed, `117=Q-<131>`, fixed `132`/`133` for the pair, `134`/`135` = the requested `38`, `15`, `62` one minute out, `60` |
| `35=R` for a priced pair, no `38` | `35=AG`, `658=99`, "QuoteRequest without OrderQty: this venue quotes a size" |
| `35=R` for anything else | `35=AG`, `658=1` Unknown symbol, the FX venue's own rule by name |
| `35=AJ` `694=1`, priced pair, `54` and `44` on the quoted side, `11` and `38` present | `35=8` `150=F` `39=2`, `37` and `17` minted, `11` `55` `54` `38` `693` echoed, `31` and `6` the quoted price, `32` and `14` the quantity, `151=0`, `60` |
| `35=AJ` `694=1`, priced pair, `11` and `38` present, any other `44` | `35=AI` `297=5` Rejected, "Price is not the quoted price" |
| `35=AJ` `694=1`, priced pair, `11` or `38` missing | `35=AI` `297=5`, "A hit needs ClOrdID (11) and OrderQty (38) to book" |
| `35=AJ` `694=2` Counter, priced pair | `35=AI` `297=5`, "Counter not accepted: this venue quotes firm" |
| `35=AJ` `694=6` Pass, priced pair | `35=AI` `297=11` Pass |
| `35=AJ` any other `694`, priced pair | `35=AI` `297=5`, "QuoteRespType not accepted: hit or lift, counter, or pass" |
| `35=AJ` for an unpriced symbol | `35=AI` `297=5`, "Unknown symbol" |
| `35=AJ` without `117` | `35=j` `372=AJ` `379=<693>` `380=5` Conditionally required field missing |

Every AI reads `${req.117}`, because the dictionary requires 117 on an AI and nothing else on an AJ can
supply it, so every AI rule is conditioned on 117 being present and the one AJ that lacks it gets a
BusinessMessageReject instead. That is the preset discipline the FX venue lives by: a rule never reads a
tag its own trigger does not guarantee, and the dictionary's required list is the only other guarantee.

## Decisions

**The QuoteID is minted from the request: `117=Q-${req.131}`, not `${uuid}`.** This is the one place
the RFQ venue differs from the FX venue on purpose. A load run's second phase has to address the quotes
the first phase created without having seen them, and a client can only do that if the QuoteID follows
from the QuoteReqID it chose. `RFQ-${run}-${messageIndex}` in phase one becomes `Q-RFQ-${run}-${messageIndex}`
in phase two, from the same seed.

**The second phase is matched on ClOrdID (11), not on 131.** The brief asked for 131 to 131 both ways.
Phase one is 131 to 131, inferred, because a QuoteRequest and a Quote both carry it. Phase two cannot be:
the AJ has no 131 in FIX 4.4 and the ExecutionReport has neither 131 nor 117. What both do carry is
ClOrdID, which is exactly what the optional 11 on a QuoteResponse is for: the client names the trade it
wants booked, and the report names it back. So the booking template carries `11=RFQ-${run}-${messageIndex}`
beside `117=Q-RFQ-${run}-${messageIndex}`, the venue's 8 echoes 11 and 693, and the match infers as 11 to
11 because 11 is the first tag in the tool's correlation order. The booking rules therefore require 11
present. A hit without a ClOrdID is answered, not booked, and the reason names the tag.

**Prices are fixed per pair, not jittered.** The FX venue draws a price per quote so two quotes are never
the same. The RFQ venue quotes the FX venue's mid-market price with no draw: EUR/USD 1.08990/1.09010,
GBP/USD 1.26985/1.27015, USD/JPY 149.490/149.510, the FX pair table's bid floor and bid floor plus
spread, read from `FxVenuePreset` by name so the two venues cannot disagree about what is priced. Two
reasons. The second phase of a load run has to know the price to hit without having seen the quote, and
the refusal the brief asks for, "a price that is not the quoted one is rejected", is only possible if the
venue can know the quoted price from its own rules. A rule conditions on `44` equal to the pair's offer
for a buy and the pair's bid for a sell, and fills at that price. The cost is that the quotes do not move,
which the FX venue already demonstrates they can. `62` still differs per quote.

**Sizes come from the request.** The FX venue omits 134/135 because OrderQty is optional on a
QuoteRequest and inventing a size would be the venue answering a question nobody asked. The RFQ venue's
position is that an RFQ without an amount is not an RFQ: the quote rules require 38 present and echo it
as BidSize and OfferSize, and a request for a priced pair without one is refused with `658=99` and a
sentence, rather than quoted at a size the client never named.

**A counter is refused, not re-quoted.** The alternative was a second 35=S. With fixed prices and a
QuoteID derived from the request, the venue could send the same quote again, but a client reading a
second Quote with the same QuoteID cannot tell a re-quote from a resend, and cannot tell either from its
counter having been accepted at its own price. A QuoteStatusReport with `297=5` and a sentence is
unambiguous: this venue does not negotiate. A pass is acknowledged with `297=11`, which is what that value
exists for. Any other QuoteRespType is refused with `297=5` and the same shape, so nothing an AJ can say
is answered with silence.

**Expiry is not enforced in this slice, and the proposal says how it would be.** The quote carries
`62=${utcnow+1min}`. A minute rather than a few seconds because the template shorthand's finest unit is
the minute (`min`, `h`, `d`, `w`, `m`, `y`), and a Kotlin expression that did seconds would make the rule
card unreadable for the sake of a number nobody asserts on. The venue cannot reject an AJ after 62 has
passed: 62 is optional on an AJ, so it is only there if the client echoes it, and the matcher vocabulary
has `today` and `within N seconds of now` but no `before now`, so even an echoed 62 cannot be tested
against the clock by a rule. Expiry belongs to quote state, where the venue remembers what it quoted and
when, and a rule can ask `whenQuote = expired`. The bundled templates do not echo 62 either: a template that
read it from a quote lacking one would put `62=null` on the wire, and the second slice reads the venue's own
memory of the quote, not an echo.

**The venue injects no latency.** The FX venue simulates 40 to 80 ms so replies do not land instantly.
This venue is a load target, and a fixed sleep in the venue would be the largest term in every round trip
the report prints. Its acceptor latency is off, so what the load run measures is FixTool's own path, which
is what the far-end notice already says it measures. Anyone who wants a slower venue turns latency on in
the profile.

**Bookings are unattributed on the venue's order book.** The book models orders, born on a received
35=D. An ExecutionReport the venue sends for a hit names a ClOrdID no 35=D ever opened, so it is counted
under the book's unattributed reports rather than silently dropped, as the book's decision 7 requires.
That is correct and it is noise, and it is the clearest argument for the second slice: a quote book would
own these. The bundled scenarios clear the venue pane's book in their setup as the FX scenarios do.

**One venue, two example ids, no third code path.** The venue profile is `RFQ Demo Venue`, SenderCompID
`RFQ_SERVER`, TargetCompID `*`, on `19877`, a constant beside `DEMO_VENUE_PORT` so both examples can be
described without sharing a number. Two client profiles, `RFQ Client 1` (`RFQ_CLIENT1`) and `RFQ Client 2`,
and a third initiator `RFQ Load Client` with `sessionCount` 5 (`RFQLG{n}`), Reset on Logon on, memory
store and no message log, so the example is load-ready out of the box and demonstrates #42's setting.
Stable ids, `createdAt: 0`, no passwords, no paths off the machine, the same pins `ExampleWorkspacesTest`
puts on the FX bundle. The empty-state placeholder stops naming the FX venue and offers one button per
bundled example, which is what the workspace switcher already does. `POST /demo` stays the FX venue for
compatibility. The RFQ example opens through `POST /workspace {"example":"rfq-venue"}`, which already
exists, and through the switcher.

## Where it plugs in

- `service/RfqVenuePreset.kt`, an `object` in the shape of `FxVenuePreset` with `ID = "rfq-venue"`, a
  summary and ordered rules, registered in `AcceptorPresets.all` beside the FX bundle so the rule editor's
  Bundles group offers it. It reads `FxVenuePreset`'s pair table and its unknown-symbol rule by name, and
  `AcceptorPresets.executionReport` and `ORDER_ECHO` for the trade report, which become `internal`.
- `resources/examples/rfq-venue/` with `manifest.json`, `connection_profiles.json`, `saved_messages.json`
  and `scenarios/*.json`, and `"rfq-venue"` appended to `examples/index.json`.
  `ExampleWorkspaces.RFQ_VENUE = "rfq-venue"`.
- Templates, tagged to the profiles that send them: a QuoteRequest per priced pair for the two clients,
  `RFQ Hit last quote` and `RFQ Pass last quote` reading the last incoming 35=S, and for the load client
  `RFQ Load QuoteRequest` (`131=RFQ-${run}-${messageIndex}`) and `RFQ Load QuoteResponse`
  (`117=Q-RFQ-${run}-${messageIndex}`, `11=RFQ-${run}-${messageIndex}`, the EUR/USD offer).
- Scenarios, bound to `this_run`, setup clearing the client pane and the venue pane's book: `RFQ book a
  trade` (request, expect S with 131, 117 and both prices, hit, expect 8 with `150=F` and `31` the quoted
  price) and `RFQ pass and counter` (a pass answered `297=11`, a counter answered `297=5`, a hit at the
  wrong price answered `297=5`).
- `AcceptorPresetsTest.sampleFor` learns 35=AJ, since it samples every rule of every preset in the
  catalogue and errors on a MsgType it has no message for. `RfqVenuePresetTest` in `FxVenuePresetTest`'s
  shape: card order, reachability, no unevaluated expression, prices with the pair's decimals, a booking
  at the quoted price, the refusals. `ExampleWorkspacesTest` gains the RFQ pins. Integration tests in the
  shape of the FX ones: the venue over a socket, and the example opened, connected and run twice.
- `help.html` chapter 2 becomes "The Examples" with the FX Venue and the RFQ Venue as sections.
  `CHANGELOG.md` under Unreleased, `README.md` where it names the example, `AUTOMATION.md` for `/demo`.

## The second slice, sketched and not built

`BookSpec.QUOTES`: key tag 117, no chain tag, born by a **sent** 35=S, moved by a received 35=AJ and a
sent 35=AI or 35=8, reading 117, 131, 55, 132, 133, 62, 297. `OrderBookService` today holds one book per
session for one spec, and `route` treats "born" as received and "moved" as sent, which is the client's
direction. A quote is born by the venue and moved by the client, so either the spec grows a direction per
event or the service holds a second book with the roles swapped. That touches order behaviour, so it
stops here as the brief asked. With it, the rule editor's `whenOrder` idiom gets a sibling `whenQuote`
with `unknown`, `open`, `done`, `expired`, and one guard rule: `35=AJ` with `whenQuote = unknown` answered
`35=AI 297=9` Quote not found. That is the RFQ desk's version of the unknown cancel, and the first slice
is written so that adding it is one rule at the top of the AJ block.

## Verification plan

Against a build of this branch on `FIXTOOL_CONTROL_PORT=8799`, the RFQ example opened through
`POST /workspace`, the venue and the load client connected, every lane `LOGGED_ON`:

1. A burst of 500 `RFQ Load QuoteRequest` with a `run` seed, settle 30 s. Expect issued 500 on all three
   numbers, matched 500, unmatched 0, duplicates 0, the match inferred as 131 to 131.
2. A burst of 500 `RFQ Load QuoteResponse` with the same seed. Expect matched equal to the first run's
   issued, the match inferred as 11 to 11.
3. A rate run, 100 per second for 10 s. Read honestly: against FixTool's own single-threaded acceptor
   the round trips are the tool's ceiling, and a shortfall is a fact about the acceptor.
4. The same burst from the command line with `--json` and `--junit`, exit 0, then `--match 131=117` to
   see exit 1 with the unmatched named.
5. `GET /screenshot` after each run.
6. Nothing under the workspace's `store/` named `FIX.4.4-RFQLG…`.

Results go under **Verified** below once the runs have happened.
