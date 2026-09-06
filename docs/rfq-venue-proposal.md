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

## Verified

Run on 2026-09-06 against a build of `main` at the docs commit, launched with `FIXTOOL_CONTROL_PORT=8799`
(no user instance was on 8765, and 19876 and 19877 were free), the RFQ example opened through
`POST /workspace {"example":"rfq-venue"}`, which copied it to `~/.fixtool/workspaces/rfq-venue`, the venue
and the load client connected, five lanes `LOGGED_ON` within a second. Every `POST /load` answered 202 with
the far-end notice: *The far end is 'RFQ Demo Venue', FixTool's own acceptor. It answers every session on one
thread, so the latencies below are the tool's own ceiling, not a venue's.* Artefacts were kept under the session's scratchpad
(`/private/tmp/claude-501/-Users-amit-gupta-FixTool/a9eb1768-7c36-4326-8499-ab8ce3a0f569/scratchpad/verify/`,
session-local, so the screenshots and the two CLI reports were also handed over as files): the report JSON of every run, the CLI's `r.json`, `r.xml`, `r-fail.json` and
`r-fail.xml`, six screenshots, and `verify.log`.

### The finding, before anything else

The first burst did not complete, and the harness said so rather than flattering the venue.

```
POST /load {"profile":"RFQ Load Client","template":"RFQ Load QuoteRequest","count":500,"seed":{"run":"v1"},"settleMs":30000}
```

| | requested | engine | socket | span | matched | unmatched | dup | late | round trip min / p50 / p95 / p99 / max | tool | exit |
|---|---|---|---|---|---|---|---|---|---|---|---|
| run v1 (before) | 500 | 500 | 500 | 38 ms | 423 | 77 | 0 | 0 | 358 ms / 17.0 s / 28.8 s / 29.8 s / 30.0 s | clean | 1 |

The 77 unanswered were indices 424 to 500, spread evenly over the five lanes, and the venue's own counters
after the run read `triggersMatched 500, responsesSent 500`: every reply was eventually built and sent, 77 of
them after the settle window had closed and the lanes had gone. A 200-message probe answered all 200 in
11.8 s, 59 ms apiece. Ten `jstack` samples of the `fixtool-acceptor-response` thread during that probe
showed it inside the Kotlin compiler every time it was busy: `org.jetbrains.kotlin.cli.jvm.compiler`, ASM
`ClassReader`, call resolution, IR lowering. The quote template's one expression was `62=${utcnow+1min}`.
The shorthand expander turns that into a Kotlin expression rather than a value, and the script engine
compiles it per reply.

This is the acceptor, not the harness: issued was 500 on all three numbers, `discarded`, `neverLeftSocket`
and `issueFailures` were 0, and the report's verdict was `UNMATCHED`, exit 1. It is also not what the
far-end notice says it is. "One thread" was true and was not the cost. The fix landed as
`fix(acceptor): a shorthand timestamp in a reply no longer costs a Kotlin compile per message`: the
send-time pass renders pure shorthand generators through the renderer the load run's compiled template
already had, so the same field now costs microseconds and produces the same string. The FX venue's prices
are Kotlin expressions by design and still compile, so its ceiling stays near 17 quotes a second. That is
now a known number rather than an unexplained one, and it is why the RFQ venue and not the FX venue is
the load target.

Screenshot of the failed run's document: `verify/01-after-burst-quote-requests.png`, the five tiles with
`unmatched 77` and the red verdict, over the five lane panes and the five venue panes. The unmatched table
sits below the visible area and there is no HTTP hook to scroll to it, so it is not in the picture. The
wire of all 77 is in the record's `unmatched.fix`.

### The six steps, on the fixed build

The app was restarted on the fixed build, the workspace reopened (it was remembered), the venue and the
load client reconnected.

**1. Burst of quote requests.** Same command with `"run":"v2"`. Match inferred as 131 to 131,
`perMessageTags [131]`, `fixedTags [35, 146, 55, 54, 38]`.

**2. Second phase books the quotes the first created.**

```
POST /load {"profile":"RFQ Load Client","template":"RFQ Load QuoteResponse","count":500,"seed":{"run":"v2"},"settleMs":30000}
```

Match inferred as 11 to 11, `perMessageTags [693, 117, 11]`, `fixedTags [35, 694, 55, 54, 38, 44]`.
Matched equals the first run's issued. The record's fifty specimen pairs are all a `35=AJ` answered by a
`35=8` with `150=F`, `39=2`, `31=1.09010`, `6=1.09010`, `11` and `693` echoed. Not one `35=AI` among them.

**3. Sustained rate.**

```
POST /load {"profile":"RFQ Load Client","template":"RFQ Load QuoteRequest","rate":100,"forMs":10000,"seed":{"run":"r1"},"settleMs":30000}
```

| | requested | engine | socket | span | matched | unmatched | dup | late | round trip min / p50 / p95 / p99 / max | tool | exit |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1. burst 500 (v2) | 500 | 500 | 500 | 50 ms | 500 | 0 | 0 | 0 | 21.3 ms / 64.0 ms / 96.9 ms / 98.1 ms / 98.4 ms | clean | 0 |
| 2. book 500 (v2) | 500 | 500 | 500 | 24 ms | 500 | 0 | 0 | 0 | 6.5 ms / 51.3 ms / 65.7 ms / 67.4 ms / 67.6 ms | clean | 0 |
| 3. rate 100/s × 10 s | 1000 | 1000 | 1000 | 9.99 s | 1000 | 0 | 0 | 0 | 286 µs / 562 µs / 818 µs / 1.24 ms / 10.3 ms | clean | 0 |

The rate run's verdict was `HELD`: achieved 100 a second, held for the nine full seconds the tolerance is
judged over, no shortfall spans, maximum lag 10 ms against a 2 percent tolerance, `pendingPeak 2`. Every
per-second bucket issued 100 and matched 100, one boundary split as 101 and 99. The venue keeps up with 100
a second with two requests outstanding at any moment, so the question "what rate can it hold" was not
reached. The burst numbers are the tool's ceiling under a queue: 500 requests arrive in 50 ms and drain in
about 100 ms, so the median request waits behind roughly half the burst.

**4. Exit codes from the command line.** The app's load client was disconnected first, because the CLI
opens the same five CompIDs. The command ran as `java -cp <the module's runtime classpath>
com.knapsack.fixtool.MainKt load …`, which is what the `fixtool` launcher runs.

```
fixtool load "RFQ Load QuoteRequest" --profile "RFQ Load Client" --count 500 --set run=cli1 --settle 30s \
  --json r.json --junit r.xml --home ~/.fixtool/workspaces/rfq-venue
```

Exit 0. `500 of 500 answered`, elapsed 55 ms, drain 5 ms, round trip min 943 µs, p50 10 ms, p95 12 ms,
p99 12 ms, max 13 ms. `r.xml` is one `<testsuite tests="3" failures="0" skipped="1">`, the rate case
skipped for a burst. Then the failure path:

```
fixtool load "RFQ Load QuoteRequest" --profile "RFQ Load Client" --count 500 --set run=cli2 --match 131=117 \
  --settle 15s --json r-fail.json --junit r-fail.xml --home ~/.fixtool/workspaces/rfq-venue
```

Exit 1. Matched 0, unmatched 500, `strays 500` ("replies to nothing this run issued", which is exactly
what a reply matched on the wrong tag is), the summary names the first six unmatched ids with their lanes,
`r-fail.xml` has `failures="1"` with the first twenty ids and "and 480 more", and `r-fail.json` carries
`unmatchedTotal 500`. Both records were written under the workspace's `loads/`, so the app's Recent list
showed seven runs afterwards, the two CLI ones included.

**5. The app surface.** `verify/11-after-burst-quote-requests.png` (the document with `matched 500`,
`complete`), `verify/12-after-burst-quote-responses.png` (two document tabs in the dock, the booking run's
five tiles), `verify/13-after-rate-run.png` (three tabs, `issued 1,000`, `matched 1,000`),
`verify/14-after-cli-runs.png` (after the lanes were reconnected). The load document opened in the scenario
dock on every `POST /load` without any click. No dialog was opened, since it has no HTTP hook, so nothing is
claimed about it here beyond its Compose tests.

**6. #42 on this venue.** After every run, in the app and from the CLI, the workspace's `store/` held
exactly 25 files, all `FIX.4.4-RFQ_SERVER-RFQLG{1..5}.*`, the venue's own file store for its five client
sessions, and `log/` likewise. Nothing named `FIX.4.4-RFQLG…` appeared for the lanes.

### Where the result differs from the expectation, and why

- **The first burst failed.** Expected `matched 500`, got 423. Cause and fix above. The rerun matched 500.
- **Phase two is matched on 11, not 131.** Expected from the brief, changed in the design because the
  dictionary gives an ExecutionReport no 131. The inferred match was 11 to 11 and matched 500.
- **`perMessageTags` is `[131]`, not `[131, 60]`.** The request template carries no TransactTime, since a
  QuoteRequest has none in FIX 4.4.
- **In-app lanes read slower than headless lanes against the same venue.** A 500 burst from the app's
  lanes drained in 97 ms with p50 64 ms. The same burst from the CLI's lanes drained in 5 ms with p50 10 ms.
  The venue was the same process both times, so about 90 ms of the in-app figure is the app's own
  client-side path, the lane panes ingesting 500 replies, not the venue. At 100 a second the in-app p50 was
  562 µs, so per message the path is fast and the cost only shows under a queue. That is the tool's ceiling
  the notice talks about, and it is now a measured number.
- **The CLI, run from a classpath, prints logback INFO lines on stdout before the summary block.** The
  automation guide says the report goes to stdout and progress to stderr. Whether the packaged launcher
  configures logging differently was not checked here.
- **The far-end notice's explanation was incomplete.** It says one thread. The cost that mattered was a
  compile per reply, which one thread only made serial. The notice is still true and is left as it is.

### Verified in the UI, by clicking

A second pass on 2026-09-06 drove the app through its own screen with real clicks (System Events) and
captured windows by id, so a terminal in front of the app could stay there. Screenshots under the same
scratchpad, `ui/`, and handed over as files.

- **The switcher.** Closing to Default and opening the workspace switcher: `Open workspace` lists `FX Venue`
  and `RFQ Venue` with where each lands (`ui/22-open-workspace-submenu.png`). Clicking `RFQ Venue` opened
  the copy. The empty session area offered no example buttons because Default holds saved profiles, which
  is the designed withdrawal.
- **The venue's rules from its chip.** With the venue and seven client sessions connected, `Rules` on the
  venue chip opened the connection panel on RFQ Demo Venue with `Auto-Responses (18)` unfolded and the
  cards readable (`ui/25-venue-rules.png`).
- **The flow, message by message, on RFQ Client 1.** A QuoteRequest for GBP/USD 500,000 drew a Quote with
  `117=Q-UI-RFQ-1`, `131=UI-RFQ-1`, `132=1.26985`, `133=1.27015`, sizes 500,000 and `62` a minute out
  (`ui/33-quote-detail.png`). A QuoteResponse `694=1` at the offer, naming `11=UI-TRADE-1`, drew an
  ExecutionReport `150=F 39=2 31=1.27015 6=1.27015 32=500000`, echoing `11` and `693=UI-RESP-1`
  (`ui/32-trade-booked-detail.png`, the detail panel).
- **The rail door to a load run.** After the lane-count fix below, `Run ▾` reads `Fan out over sessions…  (1)`
  and `Load run…  (1)`, enabled (`ui/40-run-menu-after-fix.png`). `Load run…` opened the dialog as its own
  window with the template `RFQ Load QuoteRequest` (per message 131, fixed 35, 146, 55, 54, 38), `RFQ Load
  Client` with its five lanes named, the match prefilled 131 to 131, and the red refusal *The template reads
  ${run} and nothing seeds it* with Run disabled (`ui/41-load-dialog-from-rail.png`). Typing `run=ui1` and a
  count of 500 cleared the refusal (`ui/42-load-dialog-ready.png`). Clicking Run closed the dialog and opened
  the document: issued 500, matched 500, unmatched 0, complete, exit 0, round trip p50 59.6 ms and max
  80.7 ms, store and log recorded as MEMORY and NONE (`ui/44-load-document-done.png`).

Two things the clicking found that the control surface had not:

- **The Run menu's lane count was stale.** It read `(0)` for both items with five lanes logged on, because
  it was remembered on the active run set alone and computed before any lane had logged on. Fixed in
  `fix(scenarios): the Run menu's lane count follows sessions logging on`, with a Compose test that draws
  the rail first and connects two lanes to a loopback venue afterwards.
- **The bundled scenario fails in this installation's app, and the reason is its dictionary.** `RFQ book a
  trade` went red on its Quote step with `132 TargetLocationID … moved`. Settings → Protocol on this machine
  points at a dealer's dialect (`fix-dictionary-4-4.xml` under a `brokertec-quote` checkout) in which tag 132
  is `TargetLocationID` and a header field, and BidPx does not exist. The venue's message builder honours the
  loaded dictionary's header section, so the quote's `132` was placed in the header and the expectation's row
  order no longer held. Under the bundled FIX 4.4 dictionary, which the tests and the CLI use, the same
  scenario runs green twice. The help's examples chapter now says the examples are written to the bundled
  dictionary and what a dialect does to them. Not changed: the scenario, since a dictionary without BidPx
  cannot run an RFQ example meaningfully whatever order its rows are in.

### Not verified

The editor's ⚡ Load button (Compose tests only; the rail door was clicked), the venue's behaviour after ValidUntilTime passes (not enforced in
this slice by design), and the FX venue's throughput (implied by the same mechanism at 59 ms a compile,
not measured).
