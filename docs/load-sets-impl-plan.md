# Build prompt: load sets, captured values, and an RFQ venue that behaves like a real one

You are implementing three connected pieces of FixTool. This file is your brief. Read it in full before
writing code, then read the design note it points at.

## What you are building, and why in this order

1. **Load sets** (issue #45): several load runs in order under one seed, with one report. The design is
   `docs/mockups/load-sets.html` and it is the spec. Published copy:
   https://claude.ai/code/artifact/93bb963b-62c6-4886-8452-36dcada8f9c1
2. **Captured values**: a phase keeps named tag values off each matched reply, and a later phase reads them
   per message. The note sketches this in "Does it pass for RFQ?" and defers it. You are building it, and
   this file carries its design.
3. **A realistic RFQ venue**: the bundled RFQ example stops being addressable from the seed. Opaque QuoteIDs,
   a price per quote, quote state, expiry enforced, double hits refused. The example then ships a three-phase
   load set that only works because of piece 2, which is the proof that piece 2 works against something the
   client cannot predict.

The order is the dependency order and also the order that keeps `main` usable after every commit. Sets
work against today's predictable venue. Captures can be verified against today's venue too, because
capturing a QuoteID off a Quote works whether or not the id was derivable. The venue flips last, together
with the templates and the shipped set, so there is no window where the example is broken.

Why this matters to the project: the RFQ flow is the load case the tool exists for, and today it passes only
because our own venue was built to be predictable (`docs/rfq-venue-proposal.md:55` says so). A venue that
mints its own QuoteID and prices its own quotes is what every real counterparty does.

## Two sections of the design note are binding

- **Decisions.** Read every one. Where this file adds to them (captures, the venue), this file is binding
  in the same way. If you believe one is wrong, say so and stop. Do not quietly build the other thing.
- **Order of work** for part 1 is the note's eight steps. Do them in that order.

## Traps. Each cost a review round already. Do not rediscover them.

1. **`--set` is taken on `fixtool load`.** It seeds a value (`HeadlessLoad.kt:98`). The note renames it to
   `--seed` and tolerates `--set k=v` for one release. `fixtool run --set <name>` is the precedent for what
   `--set` means afterwards. Reword `Surface.CLI`'s remedy at `LoadPlan.kt:67` and the dialog's copy line at
   `LoadRunDialog.kt:700` in the same commit.
2. **`LoadPhase` is the lifecycle, not the set member.** `LoadReport.kt:259` names PREPARING, ISSUING,
   SETTLING, DONE. The record's array of set members is already `phases[]`. Rename the lifecycle to
   `LoadStage`, JSON key `stage`, reader accepting `phase` for records already written. Schema goes to 3.
3. **`--json` and `GET /loads/<id>` write the bare report today** (`HeadlessLoad.kt:223`,
   `ControlServer.kt:1848`) while the file on disk is the record. Both move to
   `LoadReportCodec.recordToJson`. This breaks a reader of `--json`'s top-level `.verdict`. Say so in the
   changelog. Do not keep two shapes.
4. **`${now}` is two clocks.** `AcceptorResponder.resolveAtSendTime` (`AcceptorResponder.kt:524`) renders it
   in UTC. `CompiledTemplate.generate` renders it on the local clock (`CompiledTemplate.kt:238`).
   `${utcnow}` is UTC on both paths. The quote's validity and the expiry check must both be UTC, so the
   venue writes `62=${utcnow+30s}` and never `${now+30s}`.
5. **A rule condition cannot read the book today.** `FieldCondition` is `(tag, matcher: JsonObject)` and
   `firstMatch` (`AcceptorResponder.kt:161`) only evaluates matchers against the incoming message.
   `whenOrder` reads state only. "Refuse a hit whose 44 is not the quoted price" therefore needs the one new
   matcher this file defines (`quoteField`). Do not try to express it with `exact`.
6. **`OrderBook.route()` decides by direction.** Born by a *received* message, moved by a *sent* one
   (`OrderBook.kt:413`). A quote is born by a *sent* 35=S and moved by a *received* 35=AJ. Do not bend
   `BookSpec` to carry quotes. Build the small `QuoteBook` this file describes.
7. **First match wins, and presets insert backwards.** `firstMatch` returns the first rule in profile order.
   `AcceptorPresets.insert` inserts above the first rule for a MsgType, so `RfqVenuePreset.preset` declares
   its rules in reverse (`RfqVenuePreset.kt:243`). The guard rules (`whenQuote` unknown, expired, done) must
   end up *above* the hit rules, or a stale hit books.
8. **Five tests pin the behaviour you are changing.** `RfqVenuePresetTest` (QuoteID follows QuoteReqID L171,
   two quotes identical L193, 62 about a minute out L207), `ExampleWorkspacesTest` L321 (load templates share
   the seed, `44 == "1.09010"`), `AcceptorRfqVenueIntegrationTest` (hit at the fixed price L82), and the
   `ExampleWorkspacesTest` L254 pin that the bundled JSON equals the preset. Update each to assert the new
   behaviour. Do not delete a test to make it pass.
9. **`resolveOrderRefs` throws** (`AcceptorResponder.kt:493`) rather than rendering empty. `${quote.…}` does
   the same, with a sentence naming the missing quote.
10. **No rebuild during a live load run.** A parallel Gradle build ends the run with `NoClassDefFoundError`.
    Finish the live check before touching code again.
11. **The set's `${messageIndex}` restarts at 1 in every phase.** That is what `indexFrom` exists for. A
    captured value for a later phase's message is looked up by *that phase's* message index, after
    `indexFrom` is applied, so "hit 1 to 2,000" and "pass 2,001 to 4,000" both read the table phase 1 filled
    at 1 to 4,000.

## Part 1 · Load sets

Build the note's eight steps in order. Each is a commit. The note names the files and the line numbers
you start from, and its Order of work section lists the tests. Two things to hold onto that the note
states but a builder can miss:

- **`indexFrom`** is on `LoadPhaseSpec` and `LoadPlan`, default 1, added to the pacer's counter before
  `LanePrototype.render` (`CompiledTemplate.kt:156`, called from `RenderAhead.produce` at
  `RenderAhead.kt:60` and the inline fallback at `LoadRunner.kt:106`). It appears in the phase row, the
  terminal block ("×2,000 from 2,001") and the record.
- **The reply router** (`StampMatcher.offer(stamp): Boolean`, the set owning one listener per session) is a
  part 1 deliverable, not a part 2 one. Captures ride on the matcher that already exists per phase.

Exit criteria for part 1: `fixtool load --set <name>` runs a saved set against `TestFixServer` in
`HeadlessLoadIntegrationTest`'s shape, prints the blocks the note draws, writes the `<testsuites>` JUnit and
the record, exits 1 on a failed phase with the remaining phases SKIPPED. The set document draws all four
states. `Load sets…` saves, edits and runs a set from the app.

## Part 2 · Captured values

### The file

A phase gains an optional `capture`, names to tags, read off each matched reply:

```jsonc
{ "label": "Ask for a quote", "template": "RFQ Load QuoteRequest", "profile": "RFQ Load Client",
  "match": { "requestTag": 131, "replyTag": 131, "replyType": "S" },
  "shape": { "kind": "burst", "count": 4000 }, "settleMs": 60000,
  "capture": { "quoteId": 117, "offer": 133, "bid": 132 } }
```

A later phase reads a capture as a per-message name in its template: `117=${quoteId}`, `44=${offer}`.

### Validation, in `LoadSet.problems()`

- A capture name matches the `VARIABLE` regex (`CompiledTemplate.kt:172`), is unique across the set, and is
  not a seed key, a lane name (`Lane.SEED_NAMES`) or `messageIndex`. Sentence: "Phase 1 · Ask for a quote:
  the capture quoteId is also a seed. Rename one of them."
- A template may read a capture only from an *earlier* phase. Sentence, in the phase's voice: "Phase 2 ·
  Hit the first 2,000: the template reads ${quoteId} and no earlier phase captures it. Add a capture to a
  phase before it, or seed it."
- `LoadPlan.problems()` for a single run keeps today's sentence. `Surface.CLI`'s remedy grows the second
  half: "Pass --seed quoteId=… on the command line, or capture it in an earlier phase of a set."

### The engine

- `Pending` (`StampMatcher.kt:41`) gains `messageIndex: Int`. The matcher learns the index at issue time:
  the runner's issue callback (`LoadRunner.kt:105`) reads the request id off the rendered message
  (header first, then body, the same order `AcceptorResponder.valueOf` uses) and calls
  `matcher.issued(id, messageIndex)` before `send`. `onSend` (`StampMatcher.kt:193`) takes the index out of
  that map. The SEND stamp always follows the `send` call, so the map is filled before it is read.
- `StampMatcher` takes `captures: List<Pair<String, Int>>` and a `CaptureTable(names, size)` of
  `Array<Array<String?>>`. In `record()` (`StampMatcher.kt:232`), inside the `synchronized(samples)` block
  that already exists, read each capture tag off `reply.wire` with `WireTags.tagValue` and store it at
  `[messageIndex]`. A reply lacking the tag stores null. The table is part of `Result` and lives for the
  whole set, on the set runner.
- `CompiledTemplate.prepare` gains `lookups: Map<String, (Int) -> String?>`. `classify` turns a
  `Part.Variable` whose name is a lookup into `Part.Captured(name)`. `LanePrototype.render(messageIndex)`
  resolves it by the lookup. When any lookup returns null the message is **unaddressable**: `render` returns
  a `Rendered.Unaddressable(index, name)` rather than a message, `RenderAhead.produce` queues that value, and
  the issue callback records it and sends nothing. The pacer keeps its schedule: a burst moves on, a rate
  run leaves the slot empty.
- `Issue` gains `unaddressable: Int`, and the report gains `unaddressable: [{index, missing}]` capped like
  `unmatched` at 1,000 with a total. **A requested message that was not sent fails the phase.** The bar
  stays "every requested message answered", so completeness is `INCOMPLETE` with the count, exit 1, and the
  terminal line reads `INCOMPLETE  4 of 2,000 not sent: no quoteId from phase 1 for index 412 …`. Under STOP
  the set has usually stopped before this can happen. Under CONTINUE it is the honest number.
- Evidence: `NN-captured.tsv` beside the phase's other files, one line per index, tab-separated
  `index name=value …`, named in the phase's `evidence` block.
- The record's phase gains `capture: { "names": {...}, "captured": { "quoteId": 3996, "offer": 3996 } }`.

### The doors

- **Terminal**: the phase block gains `captured   quoteId, offer on 3,996 replies` when a phase captures,
  and the `INCOMPLETE` line above when a later phase could not address.
- **Document**: the quiet strip shows the capture count. An unaddressable table in the shape of the
  unanswered one: index, the missing name, the phase it should have come from.
- **Editor**: the phase editor's Advanced group gains a Capture row of chips, `quoteId ← 117 QuoteID`,
  `+ capture`, with the dictionary name beside the tag as the match row already does. The template picker's
  sub-line lists the names the template reads and where each comes from ("${quoteId} from phase 1").
- **CLI and API**: nothing new. Captures live in the file, and inline `phases` on `POST /load` carry them.

### Tests

`StampMatcherTest`: a capture stored at the right index, a missing tag stored as null, a duplicate reply
not overwriting. `CompiledTemplateTest`: a captured part renders, a null lookup yields Unaddressable.
`LoadRunnerTest` with `FakeLane`'s answer policy minting an opaque id in tag 117: phase 2 addresses phase 1's
ids and books, one missing capture becomes one unaddressable and exit 1. `LoadSetTest`: the three refusal
sentences. `HeadlessLoadIntegrationTest`: a two-phase set against `TestFixServer` whose reply carries
`117=Q-<uuid>`, phase 2 reading `${quoteId}`, matched equals requested.

Exit criteria for part 2: a set whose phase 2 sends `117=${quoteId}` books against today's RFQ venue over
the control surface, and a set whose venue omits the tag reports the unaddressable count and exits 1.

## Part 3 · The RFQ venue behaves like a real one

### What "real" means here, and what it does not

In scope: an opaque QuoteID per quote, a price per quote inside a band, a validity of 30 seconds enforced,
a hit at a price other than the quoted one refused, a hit or pass on a quote the venue never sent refused,
a second hit on a quote already booked refused. Still true afterwards: one dispatch thread, no injected
latency, so the numbers a load run prints are the tool's ceiling and the far-end notice stays. Out of
scope: re-quotes and quote streams, partial fills, multi-leg, mass quote.

### 3.1 · Two shorthand generators

`ShorthandTemplateExpander.kt` and `CompiledTemplate.generate` are the one native renderer both the
acceptor's send-time pass and the load run's compiled template use. Add:

- **Seconds.** The unit alternation at `ShorthandTemplateExpander.kt:62` and `:65` becomes
  `(min|[shdwmy])`, `shifted()` (`CompiledTemplate.kt:244`) gains `"s" -> plusSeconds`, and
  `expandTimestamp` (`ShorthandTemplateExpander.kt:363`) emits `.plusSeconds(n)`. `min` stays first so
  `5min` is never read as five months.
- **`${random:<min>:<max>:<decimals>}`.** `Generator.Random(min: BigDecimal, max: BigDecimal, decimals: Int)`,
  uniform in the closed range, quantised to `decimals`, rendered with `toPlainString()`. Regex in
  `generatorOf`, `SHORTHAND_KEYWORDS` gains `random`, `expand()` emits equivalent Kotlin for the old path,
  `validateShorthand` accepts it. In a load template it is a per-message generator like `uuid`.
  `AcceptorSendTimeGeneratorsTest` gains a case for each and keeps its "a thousand replies inside a second"
  guard.

### 3.2 · Quote state: `QuoteBook`

A new, small pair beside the order book. Not a `BookSpec`, because of trap 6.

```kotlin
data class QuoteEntry(
    val quoteId: String, val quoteReqId: String?, val symbol: String?,
    val bid: String?, val offer: String?, val bidSize: String?, val offerSize: String?,
    val validUntil: Long?,            // 62 parsed as UTC yyyyMMdd-HH:mm:ss.SSS, epoch millis
    val state: QuoteState,            // OPEN | DONE
    val doneBy: String?,              // "8" hit, "AI" pass
)
enum class QuoteConstraint { UNKNOWN, OPEN, EXPIRED, DONE }   // lowercase on disk, like OrderConstraint
data class QuoteReading(val quoteId: String?, val entry: QuoteEntry?, val word: String)
```

`QuoteBookService(clock: () -> Long)` holds one book per counterparty session, like `OrderBookService`
(`OrderBookService.kt:163`), fed from `QuickFixService.book()` (`QuickFixService.kt:755`) with the same
`sent` flag:

- sent `35=S` → born, reading 117, 131, 55, 132, 133, 134, 135, 62.
- received `35=AJ` → remember `693 → 117` for this session. The AJ itself moves nothing.
- sent `35=8` or `35=AI` carrying 693 → resolve the quote through that map. An `8` marks DONE by hit. An
  `AI` with `297=11` marks DONE by pass. Any other AI leaves the quote as it was.
- The reading for a rule: UNKNOWN when 117 is absent or not in the book, DONE when done, EXPIRED when open
  and `validUntil < clock()`, else OPEN. Taken **before** the incoming AJ is recorded, exactly as
  `heldBefore` is for orders (`QuickFixService.kt:837`).
- When the quote book claims a sent `8` through 693, the order book is not offered it. That removes the
  unattributed noise the RFQ proposal records at `:111`.
- `clearOrderBook` clears both books. Cap and eviction as the order book, DONE first.

### 3.3 · Rules can ask the quote

- `AcceptorResponseRule` gains `whenQuote: QuoteConstraint?` beside `whenOrder`. `readsTheBook()` covers it.
  `satisfiesBook` in `firstMatch` and the per-rule verdict in `explain` learn it. The rule card prints
  "when the quote is open" in the idiom the order card uses.
- **One new matcher, `quoteField`.** `Matcher.QuoteField(name: String)` with `type: "quoteField"` in
  `MatcherCodec`. `firstMatch` resolves it against the `QuoteReading` before evaluating: a known quote turns
  it into `Exact(entry.<name>)`, an unknown or missing quote makes the condition false. Names are the
  `${quote.*}` vocabulary below. A scenario refuses it with the sentence `Matcher.Reference` gets today
  ("a scenario has no venue book"). The rule editor's matcher menu offers it under a "the quote's…" group
  only when the profile is an acceptor. The card prints "equal to the quote's offer".
- **`${quote.<name>}` in reply templates**, resolved in `plan()` after `${order.…}` by a `resolveQuoteRefs`
  in the shape of `resolveOrderRefs`. Vocabulary: `quoteId, quoteReqId, symbol, bid, offer, bidSize,
  offerSize, validUntil, state`. Absent when the venue has not said it, and a refusal sentence when the
  template asks for a quote that is unknown.
- `/acceptor/test` reports the quote reading the way it reports the order one (`assumedQuoteState`), so the
  dry run can say why a hit was refused.

### 3.4 · The preset, the bundle, the templates, the scenarios

`RfqVenuePreset` stops deriving anything from the request:

- **Quote**: `117=${uuid}`, `132=${random:<bidLow>:<bidHigh>:<dp>}`, `133=${random:<offerLow>:<offerHigh>:<dp>}`,
  `134`/`135` echo 38 as today, `62=${utcnow+30s}`, `60=${utcnow}`. Bands from `FxVenuePreset.PAIRS`: bid in
  `[bidFloor, bidFloor + 9 ticks]`, offer in `[bidFloor + spread, bidFloor + spread + 9 ticks]`. The bands
  are disjoint for every pair in the table (the spread is 20 ticks or more), so the spread can never invert.
  Assert that in `RfqVenuePresetTest` rather than assuming it.
- **QuoteResponse rules, top to bottom** (declare them in reverse for `insert`, trap 7):
  1. `whenQuote = unknown` → `35=AI 297=9` Quote not found.
  2. `whenQuote = expired` → `35=AI 297=7` Expired.
  3. `whenQuote = done` → `35=AI 297=5 58=Quote already answered`.
  4. `694=1`, 11 and 38 present, `54=1`, `44 quoteField(offer)`, `55 quoteField(symbol)` → the trade at
     `31=${quote.offer} 6=${quote.offer}`.
  5. `694=1`, 11 and 38 present, `54=2`, `44 quoteField(bid)`, `55 quoteField(symbol)` → the trade at the bid.
  6. `694=1`, 11 and 38 present, `55 quoteField(symbol)` → `297=5 58=Price is not the quoted price`. The
     symbol condition is load-bearing: without it this rule also catches a hit at the right price on the
     wrong pair and blames the price, which sends a client to check pricing that is not at fault.
  7. `694=1`, 11 and 38 present → `297=5 58=Instrument is not the quoted one`. Directly under rule 6, and
     said by position rather than by a condition: every rule above it carries the quoted symbol, so
     anything arriving here named something else or named nothing.
  8. `694=1` → `297=5 58=A hit needs ClOrdID (11) and OrderQty (38) to book`.
  9. `694=2` → `297=5 58=Counter not accepted: this venue quotes firm`.
  10. `694=6` → `297=11`. The book marks the quote done by pass.
  11. any other 694 → `297=5`. No 117 → the `35=j` reject, as today.
  The six per-pair hit rules collapse into rules 4 and 5, because the quote knows its own symbol and prices,
  which leaves the bundle at 17 rules where the first slice of the venue needed 18.
  The QuoteRequest rules stay as they are. The summary string changes to say what the venue now is.
- **`connection_profiles.json`** in the bundle is regenerated to equal the preset (the L254 pin). Keep the
  ids, ports and `createdAt: 0`.
- **Client templates**: the lift, hit, counter and pass templates already read the incoming Quote through
  expressions and keep working. **`rfq-book-a-trade`** hard-codes `117=Q-DEMO-RFQ-1`: change it to
  `${in.S.117}`, and every expectation on a price becomes a `reference` to the quote's 133 rather than a
  literal. Check `rfq-pass-and-counter` the same way. Both scenarios must still run green twice in
  `RfqExampleWorkspaceIntegrationTest`.
- **Load templates**: `RFQ Load QuoteResponse` becomes `117=${quoteId}`, `44=${offer}`, keeping
  `11=RFQ-${run}-${messageIndex}` and `693=AJ-RFQ-${run}-${messageIndex}`. Add `RFQ Load Pass`:
  `35=AJ 693=P-RFQ-${run}-${messageIndex} 694=6 117=${quoteId} 55=EUR/USD`. Both tagged to the load
  profile. Opened alone in Load run… they refuse with the capture sentence, which is correct.
- **The shipped set**, `load-sets/rfq-round-trip.json`, added to the manifest:
  phase 1 `RFQ Load QuoteRequest` ×4,000, 131 to 131, reply S, capture `quoteId=117, offer=133`;
  phase 2 `RFQ Load QuoteResponse` ×2,000, 11 to 11, reply 8, settle 60s;
  phase 3 `RFQ Load Pass` ×2,000 from 2,001, 117 to 117, reply AI, settle 30s.
  Seed `run=${uuid:4}`, memory store and no log, stop on failure. `ExampleWorkspacesTest` pins it.

### 3.5 · Tests that change, and tests that are new

- `RfqVenuePresetTest`: QuoteID is a UUID and two quotes differ, prices inside their band with the pair's
  decimals, 62 about thirty seconds out, a hit at the quoted price books at that price given an OPEN reading
  with that price, a hit at another price is refused, an unknown quote is 297=9, an expired one 297=7, a done
  one 297=5, no unevaluated `${` on any reply, every reply validates against the dictionary. The offline
  tests construct a `QuoteReading` the way they construct an order reading today.
- `AcceptorRfqVenueIntegrationTest` over the socket: read 117 and 133 off the received S and hit at that
  price, expect the fill at it. A second hit on the same 117 gets 297=5. A hit on `117=nope` gets 297=9. For
  expiry, an `internal fun rules(validitySeconds: Int)` on the preset lets the test build a venue quoting
  one second, sleep past it, and read 297=7.
- `QuoteBookServiceTest`: born on sent S, done by sent 8 through 693, done by pass, expired by the clock,
  cleared with the order book, a sent 8 claimed by the quote book not reaching the order book.
- `AcceptorResponderTest`: `quoteField` resolves to Exact against a reading and is false without one.
  `explain` names the quote verdict.
- `AcceptorPresetsTest.sampleFor` keeps knowing 35=AJ.

### 3.6 · Docs

- `help.html`: the RFQ chapter (L395 to L500). "Firm Prices" becomes a band table. "What the Venue Answers"
  gains the three guard rules and says the venue now keeps quote state. The load section (L478) describes the
  shipped set and the captured QuoteID and price. The load runs chapter (L683) gains a paragraph on sets and
  captures. Chapter 13's Substitutions table (L3160) documents `${uuid:N}`, `${utcnow±N}`, `${now:pattern}`,
  `${random:…}` and `${quote.*}`, and "Rules that read the book" (L3314) gains `whenQuote`.
- `docs/AUTOMATION.md`: the load section (L149 to L220) for `--set`, `--seed`, `--on-failure`, the record
  shape, `GET /load-sets`. The acceptor section: `quoteField` in the matcher list (L694), `whenQuote` beside
  `whenOrder` (L824), `${quote.*}` beside `${order.*}` (L860).
- `CHANGELOG.md`: add `## [Unreleased]` above 1.17.0 in the file's own shape. Under Added: load sets, captured
  values, the RFQ venue's quote state with the three refusals, the two generators. Under Changed: `--seed`,
  the record shape on `--json` and `GET /loads/<id>` (name the break), `stage`. Write each bullet for the
  reader, as the 1.17.0 entries do.
- `docs/rfq-venue-proposal.md`: a dated section at the end saying the second slice landed and where it
  departed from the sketch (a `QuoteBook`, not a `BookSpec`).

## Live verification, with the `verify` skill

Do this once after part 3, and once more if anything in the engine changes afterwards.

1. Open the RFQ example through `POST /workspace {"example":"rfq-venue"}`, connect the venue and
   `RFQ Load Client`, wait for five lanes `LOGGED_ON`.
2. `POST /load {"set": "rfq-round-trip"}`. Expect `PASSED`, three phases, phase 1 `captured quoteId, offer on
   4,000 replies`, phase 2 matched 2,000 with every specimen a `35=8 150=F` at a price equal to the captured
   133, phase 3 matched 2,000 with every specimen `297=11`. Screenshot the set document.
3. The same set from the command line: `fixtool load --set rfq-round-trip --home <workspace> --seed
   run=cli1 --json r.json --junit r.xml`. Exit 0. `r.json` has `schema 3`, three phases, `capture` blocks.
   `r.xml` is one `<testsuites>` with three suites.
4. A deliberate wrong price: an inline set on `POST /load` whose phase 2 template carries `44=1.00000`
   instead of `${offer}`. Expect every hit answered `297=5`, phase 2 `UNMATCHED · 2,000` (an AI carries no 11),
   `FAILED · PHASE 2`, phase 3 skipped. Screenshot.
5. A double hit: an inline three-phase set whose phase 3 is the hit template again over 1 to 2,000. Expect
   phase 3 `297=5` throughout and `FAILED · PHASE 3`.
6. Recent shows the set rows with the verdict first. Compare the two passing sets from steps 2 and 3 and
   read the phase-pair rail.
7. Nothing under the workspace's `store/` named `FIX.4.4-RFQLG…`.

Name temporary artifacts `VERIFY TEMP …` and delete them afterwards. `/screenshot` captures screen pixels,
so keep the app window uncovered or verify through control-surface state.

## Project conventions, non-negotiable

- **The commit-msg hook** at `/Users/amit.gupta/.git-hooks/commit-msg` (via `core.hooksPath`) rejects any
  mention of Claude or Anthropic and the pattern `all N tests pass`. Say what changed. A pre-push hook
  re-checks every commit.
- **Do not push unless asked.** Commit locally on `main`. `main` builds, its tests are green and the app is
  usable after every commit.
- **ktlint and detekt fail module-wide on pre-existing findings.** Format only the files you touched. A
  module-wide `ktlintFormat` rewrites about a hundred unrelated files.
- **UI and view state go in the view-state JSON store, not `AppSettings`.** `SettingsPagesTest` enforces it.
  The last-used load set and the phase editor's state go there.
- **This machine's app runs a dealer dictionary** (`~/.fixtool/app_settings.json`, `useBundledDictionary:
  false`, a BrokerTec 4.4 file where tag 132 is `TargetLocationID`). A bundled example asserting on quote
  prices goes red in the app here while tests and the CLI are green. Check the dictionary before reading a
  red example as a venue bug.
- **No em dashes and no semicolons in prose**, including commit messages, docs and help text.
- **`ControlServerIntegrationTest` pins the MCP tool count at 52.** `fixtool_load` gains a `set` argument.
  Do not add a tool.

## Definition of done

- Part 1: the note's exit criteria, and its four document states drawn.
- Part 2: a two-phase set books against a venue that mints its ids, and a missing capture is counted, named
  and fails the phase.
- Part 3: the bundled RFQ venue refuses an unknown, expired or already-answered quote and a wrong price, prices
  every quote inside its band, and the shipped `rfq-round-trip` set passes over the control surface and from
  the command line. The seven live steps above are done and their screenshots handed over.
- Every test named in this file exists and is green. Every doc touchpoint named in 3.6 is updated.

Report after each part: what landed, what tests ran, and anything in this file or the note you found to be
wrong. Both have been through review and about a dozen of their citations were corrected against the code.
If a line number does not match, trust the code and say so.
