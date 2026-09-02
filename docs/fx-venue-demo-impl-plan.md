# The demo is the acceptor — implementation plan

Companion to [the proposal](./fx-venue-demo-proposal.md). That document argues the shape; this one is
the task-level breakdown: what ships, which files it touches, what each new rule actually says, and
what has to be true before the next slice starts.

**Slices A and B ship together as one issue.** Slice C stays deferred until the first video cut.

---

## What the probes settled

The proposal named two "cheap checks with fallbacks". Both were run against the real engine and the
bundled FIX 4.4 dictionary before a line of this plan was written, and they answered four questions
rather than two. Everything below is a **measured** result, not a reading of the source.

| # | Question | Answer | What it forces |
|---|----------|--------|----------------|
| 1 | Can a trigger read `55` inside `NoRelatedSym(146)`? | **No.** With the dictionary loaded, a conformant `35=R` parses the symbol into the group and `Message.isSetField(55)` is `false`. Flat `55` reads `true`. | A condition on `55` and `${req.55}` see nothing on a conformant QuoteRequest. Either the demo template stays flat (non-conformant), or `AcceptorResponder.valueOf` learns to fall back to the group — **deliverable A2**. |
| 2 | Does `"%.5f".format(…)` survive the expression engine? | **Yes.** `${"%.5f".format(java.util.Locale.US, 1.0898 + (0..4).random() * 1.0e-4)}` → `1.08980`. | Prices must be formatted. `${1.0898 + (0..4).random() * 1.0e-4}` rendered `1.09` — a `Double.toString()` whose decimal count depends on the draw, which is not an FX price. |
| 3 | Does `Random.nextInt(0, 5)` work unqualified? | **No, and it fails silently.** The engine returned the literal string `${Random.nextInt(0, 5)}`, which would go on the wire as the value of tag 132. `kotlin.random` is not one of Kotlin's default imports and the script preamble does not add it; `(0..4).random()` (kotlin.ranges) works. | The proposal's own example expression would have shipped broken. **A failed expression yields its own source text, not an empty field**, so `no preset puts an empty field on the wire` does not catch it — a new guard is needed (test A-T2). |
| 4 | Can an acceptor reply carry a repeating group? | **No.** `AcceptorResponder.buildMessage` sets every tag flat on a bare `Message`, which orders the body by tag number: `35=AG\|131\|658\|146=1\|55=…` came out as `55\|131\|146\|658` — the count claims one entry and the group is empty. The app's own `String.toQuickFixMessageManual(dictionary)` produced `131\|658\|146=1\|55=…`, correct. | A conformant `35=AG` (NoRelatedSym is `required="Y"`) is unwritable today. **Deliverable A1** routes acceptor replies through the dictionary-aware builder. |

Two more facts that shape the templates, read off the source rather than measured:

- **Expressions are per-`${…}` script evaluations under a process-wide lock**
  (`FixMessageTemplate.evalExpressionWithContext` is `synchronized(scriptEngine)`), ~44–82ms each.
  A two-price quote reply costs two of them. Fine for a demo; named here because slice C's ticking
  quotes would multiply it, and because a burst of quote requests serialises behind the same lock the
  message editor uses.
- **An expression may contain neither `}` nor `|`.** `ANY_EXPR` is `\$\{([^}]*)}` and
  `FixMessageHelper.parseFixMessage` splits the template on `|`. Arithmetic and `format` are safe;
  a lambda or an `if { … }` is not.

---

## Slice A — the FX venue preset bundle

### A1 · Acceptor replies get the dictionary-aware builder

`AcceptorResponder.buildMessage(raw)` becomes `buildMessage(raw, dictionary)` and calls
`String.toQuickFixMessageManual(dictionary)` when one is loaded, falling back to today's flat
construction when it is not, or when the manual builder throws.

- `PlannedSend` carries the dictionary (it is already handed to `plan()`), so `build()` uses it.
- `AcceptorResponder.replyTo` (the "Reply With…" path) gets it for free — the two must not diverge.

**Files:** `service/AcceptorResponder.kt`, callers in `service/QuickFixService.kt`, tests.

This is not only about `35=AG`. Today a hand-written rule that puts `115=CLIENTA` in its template
sends OnBehalfOfCompID in the **body**, because `buildMessage` routes only tag 35 to the header. The
manual builder routes every header and trailer tag where it belongs, including the ones a venue
dialect declares in its own dictionary's `<header>` section.

**Risk and its bound:** every acceptor reply changes construction path. The shipped presets carry no
header tags and no groups, so their bytes are unchanged — pinned by re-running
`AcceptorPresetsTest` and `AcceptorPresetIntegrationTest` unmodified. The fallback keeps a
dictionary-less acceptor behaving exactly as it does now.

### A2 · A trigger may read a tag from the first group instance

`AcceptorResponder.valueOf(msg, tag)` gains a final fallback: when the flat body, header and trailer
have no such tag, look inside the **first instance** of each group the message carries.

This is what `FxOrderProcessor.extractSymbol` does today — the code this change deletes — and without
it FixTool's acceptor cannot condition on any field of any repeating group, which is a strange
limitation for the venue side of a FIX tool.

- Flat wins: checked first, so no existing rule changes behaviour.
- "First instance" is a documented approximation. A two-symbol RFQ is answered about its first
  symbol, and the KDoc says so rather than pretending otherwise.

**Files:** `service/AcceptorResponder.kt`, `service/AcceptorResponderTest.kt`.

**If A2 is dropped:** the demo's QuoteRequest template must carry a flat `55` — which is what the old
demo server's own template does, and which is not a conformant FIX 4.4 QuoteRequest. That is a real
cost for a video whose selling point is domain credibility, which is why A2 is recommended rather
than the proposal's fallback.

### A3 · The pairs, as constants

`FxCurrencyPair.SUPPORTED_PAIRS` loses three pairs and moves into the new preset file as a private
data class. Nothing else survives from `FxCurrencyPair` — `normalizeSymbol`, `pipsToPrice` and the
lookup map exist only for the processor being deleted.

| Symbol | Mid | Spread | Jitter | Decimals |
|---|---|---|---|---|
| EUR/USD | 1.09000 | 2 pips (0.0002) | 0–9 pipettes (1.0E-5) | 5 |
| GBP/USD | 1.27000 | 3 pips (0.0003) | 0–9 pipettes (1.0E-5) | 5 |
| USD/JPY | 149.500 | 2 pips (0.02) | 0–9 fractional pips (0.001) | 3 |

Jitter at the **pipette**, not the pip: jittering whole pips and formatting to 5 decimals produces
prices that always end in `0`, which reads as a placeholder. USD/JPY at 3 decimals is the realism
flex the proposal asked for and the one thing on screen that says we know the domain.

### A4 · The two prices come from one draw

Bid and ask cannot be two independent draws — the spread would vary and could invert, and a quote
with the bid above the ask is the one mistake an FX audience will catch instantly. The engine already
has what is needed: `FixMessageTemplate.evaluate` shares a variable map across every `${…}` of one
template, an assignment expression both stores and emits its value, and stored variables are injected
into the script context by bare name.

```
132=${bid = "%.5f".format(java.util.Locale.US, 1.08990 + (0..9).random() * 1.0E-5)}
133=${"%.5f".format(java.util.Locale.US, bid.toDouble() + 2.0E-4)}
```

The bid is drawn once and the ask is derived from it, so the spread is exactly 2 pips every time and
the two move together. `132` must precede `133` in the template text, because the replace runs left
to right — pinned by a test, since nothing else about the file would tell a reader that.

`java.util.Locale.US` explicitly. A JVM whose default locale uses a comma decimal separator would put
`1,08995` on the wire, and it would pass every test run on a US-locale machine.

### A5 · The rules

New file `service/FxVenuePreset.kt` — `AcceptorPresets` is already 818 lines against a detekt
`LargeClass` threshold of 600, and this adds 21 rules. It exposes one `AcceptorPreset`, which
`AcceptorPresets.all` includes; the catalogue stays single, the file does not.

`AcceptorPresets` gains `const val FX_VENUE = "fx-venue"` beside `STARTER_VENUE`, so
`DemoServerManager` names the bundle rather than rebuilding it.

**How it reads on screen** (the order `AcceptorPresets.insert` produces):

| # | Trigger | Reply |
|---|---|---|
| 1–3 | `35=R`, `55` exact per pair | `35=S` · `117=${uuid}`, `131=${req.131}`, `55`, jittered `132`/`133`, `15`, `60` |
| 4 | `35=R`, unconditional | `35=AG` · `131=${req.131}`, `658=1`, `146=1`+`55=${req.55}`, `58=Symbol not supported` |
| 5–6 | `35=D`, `whenOrder = working` / `pending` | duplicate ClOrdID reject (`103=6`) — verbatim `duplicateWorking`, `duplicatePending` |
| 7 | `35=D`, `38 > 10000000` | over-size reject (`103=3`) — the shipped `ORDER_REJECT` template, FX bound |
| 8 | `35=D`, `55` oneOf the three, `40=2` | ack → partial → remainder, priced at `${req.44}` — the shipped `ackPartialFill` shape |
| 9–11 | `35=D`, `55` exact per pair, `40=1` | ack, then fill at the pair's own jittered price |
| 12 | `35=D`, unconditional | `39=8` · `103=1` · `58=Unknown symbol` |
| 13–16 | `35=F`, `whenOrder` unknown / pending / working / done | verbatim from the starter venue: reject-unknown, accept, accept, too-late |
| 17–18 | `35=G`, `whenOrder = working` / `38` present | `replaceAcceptedSameId`, then `replaceAccepted` |
| 19–21 | `35=H`, `whenOrder` unknown / working / done | verbatim `statusRequestUnknown`, `statusRequestWorking`, `statusRequestDone` |

**The list in the source is not this order.** `insert` places a conditioned rule above the first
enabled rule for its MsgType and appends an unconditional one, so the bundle lists each conditioned
block backwards — exactly the trick the starter venue's cancel rules already use, and for the same
reason. The declaration order is:

```
quoteJpy, quoteGbp, quoteEur, quoteUnknown,          // R: unconditional appends to the bottom
marketJpy, marketGbp, marketEur, limitFlow,
overSize, duplicatePending, duplicateWorking,        // each lands above the D block
orderUnknownSymbol,                                  // unconditional: bottom of the D block
cancelTooLate, cancelAcceptedWorking,
cancelAcceptedPending, cancelRejectedUnknown,
replaceAccepted, replaceAcceptedSameId,
statusRequestDone, statusRequestWorking, statusRequestUnknown
```

Three constraints this order exists to satisfy, each of which a test pins:

- **The over-size reject must sit above the fills.** A 20M EUR/USD limit order matches both, and
  first-match-wins. This is the defect that was live-caught when presets shipped.
- **The duplicate rules must sit above everything for `35=D`**, or a re-sent ClOrdID is filled a
  second time instead of rejected.
- **Both unconditional rules must sit at the bottom of their own block.** `insert` appends them at
  the end of the whole list at the moment they are inserted, so they must be declared before the
  next MsgType's rules or they land beneath the cancels and the card list reads as noise.

**Reading a tag the trigger does not guarantee** — the discipline `AcceptorPresetsTest` enforces —
is satisfied as follows. `131` on `35=R` and `41` on `35=F` are `required="Y"` in the dictionary, so
they are read without a presence condition, exactly as the shipped `CANCEL_ECHO` reads `41` today.
The market-fill rules carry `40=1` and therefore price from the rule's own content, never `${req.44}`.
The quote replies deliberately omit `134`/`135` (BidSize/OfferSize): a QuoteRequest's OrderQty is
optional and lives inside the group, so there is no quantity the trigger guarantees.

### A5a · The initialisation cycle, and why the catalogue is `by lazy`

Found while building A5, not predicted by this plan. [FxVenuePreset] composes `AcceptorPresets`' rules
by name, and `AcceptorPresets.all` holds the FX bundle — a cycle between two Kotlin `object`s. With an
eager `all` it resolves in exactly one direction: reach `AcceptorPresets` first and everything works,
because `all` is declared after the templates it reads. Reach **`FxVenuePreset`** first and its
initialiser calls back into `AcceptorPresets`, whose initialiser runs `all`, which reads a `preset`
that is still being constructed — a null in the catalogue, or a `StackOverflowError`, decided by
whichever class the JVM happened to load first.

`val all: ... by lazy { … }` breaks it: neither object is ever observed half-built, because the list is
not part of either initialiser. The rule that keeps it safe is written on the declaration — **nothing
eagerly initialised in `AcceptorPresets` may read `all`**.

`FxVenuePresetTest` proves it from both directions with a fresh `URLClassLoader` each way, since within
one JVM a class initialises once and the order cannot be re-run.

### A6 · The latency default

The bundle itself carries no latency — a latency config lives on the profile, not on a rule. Slice B
sets `AcceptorLatencyConfig(mode = RANDOM_RANGE, minMillis = 40, maxMillis = 80)` on the demo venue
profile. Recorded here because it is the reason the demo's replies do not land suspiciously instantly,
and because #36 on camera is half the point.

### A7 · Tests

Three layers, and they are not interchangeable — the doctrine the presets already ship under.

**`AcceptorPresetsTest` (engine):**

- `sampleFor(rule)` must be rebuilt. It currently keys off `whenMsgType` and a single "has a 38
  condition" branch, and `else -> error("no sample message for 35=…")` will fail the whole suite the
  moment an `R` rule exists. Derive the sample from the rule's own conditions: the symbol from an
  exact `55`, market-vs-limit from `40`, over-size from a `38` range, plus a `35=R` sample and an
  unknown-symbol sample for the two catch-all rules.
- **A-T1 — the bundle's cards read in the documented order.** Mirrors `the starter venue's cancel
  rules read in state order`, and asserts the whole list, because the three ordering constraints
  above are invisible in the source.
- **A-T2 — no preset ships an unevaluated expression.** Render every step of every preset and assert
  no `${` survives. This is the guard probe 3 proved missing: a failed expression emits its own source
  text, which the empty-field guard cannot see.
- **A-T3 — every price a preset sends has the pair's decimals.** Assert `132`, `133` and `31` match
  `\d+\.\d{5}` (or `\d{3}` for JPY). Catches the locale trap, the unformatted-double trap, and a pair
  whose constants and format string drift apart.
- **A-T4 — the bid is below the ask, over many renders.** Render each quote rule 100 times and assert
  `132 < 133` and `133 - 132` is exactly the pair's spread. This is what A4 exists for.
- **A-T5 — an unknown symbol falls past the three pairs**, for both `35=R` and `35=D`, through
  `explain` over the whole bundle.
- **A-T6 — every rule of the bundle is reachable** (`shadowingRule` is null at every index) and the
  over-size and duplicate rules win against a message that also matches a fill rule.

**`ControlServerIntegrationTest`:** the bundle appears in `GET /acceptor/presets` and inserts through
`POST /acceptor/rules {"preset":"fx-venue"}` with the same placement. Both are existing loops over
`AcceptorPresets.all`, so this is a count/name assertion rather than new machinery.

**New `AcceptorFxVenueIntegrationTest`** (modelled on `AcceptorPresetIntegrationTest` — real acceptor,
real client, real port, `TestPorts.free()`):

- a `35=R` for EUR/USD comes back as a `35=S` whose `132`/`133` parse as doubles with `132 < 133`;
- two quote requests in a row come back with **different** prices — the claim "every quote prices
  slightly differently" is otherwise untested;
- a `35=R` for `XXX/YYY` comes back as `35=AG` whose `146` group holds the symbol — the only test that
  can prove A1, since a rendered string cannot;
- a market order for USD/JPY is acked and then filled at a 3-decimal price;
- `nothing the venue sends carries a tag with no value`, re-asserted against these rules.

### A8 · Exit criteria

- The bundle inserts from the `+ preset` menu and from the control surface, and reads in the
  documented order on the cards.
- Full suite green, including the untouched `AcceptorPresetsTest` and `AcceptorPresetIntegrationTest`
  assertions that pin A1's byte-for-byte compatibility.
- Live-verified through `/verify`: a `VERIFY TEMP` venue carrying the bundle, a client sending an RFQ
  and a market order, prices visibly different between two quotes.

---

## Slice B — the demo workspace swap

### B1 · What `DemoServerManager` becomes

The public surface holds: `start(fixVersion)`, `stop()`, `isRunning`, `currentFixVersion`,
`isDemoProfile`, `isDemoTemplate`, `getDemoProfileIds`, and the two change callbacks. Inside, there is
no server — there are profiles.

```kotlin
const val VENUE_PROFILE_ID = "demo-profile-venue"
const val VENUE_NAME  = "FX Demo Venue"
const val VENUE_COMP_ID = "DEMO_SERVER"
const val DEMO_PORT = 19876
val DEMO_CLIENTS = listOf("DEMO_CLIENT1", "DEMO_CLIENT2")

fun start(fixVersion: FixVersion = FixVersion.DEFAULT, port: Int = DEMO_PORT)
```

The `port` parameter is new and exists so an integration test can drive the whole workspace on a free
port. The fixed 19876 is a singleton the test suite cannot share, and a demo workspace that has no
end-to-end test is the thing this slice is replacing.

**The venue profile:** `ACCEPTOR`, `senderCompID = DEMO_SERVER`, `targetCompID = "*"`,
`socketAcceptPort = port`, `beginString` from the picked version,
`acceptorResponseRules = AcceptorPresets.insert(emptyList(), byId(FX_VENUE)!!).rules`, and the A6
latency config. The wildcard is what makes the two demo clients — and anyone else's ad-hoc client —
reach one venue.

**The client profiles:** `Demo Client 1` and `Demo Client 2`, `INITIATOR`, `targetCompID = DEMO_SERVER`,
`resetOnLogon = true`, and **`reconnectInterval = "5"`** — see B6.

`DEMO_CLIENTS` drops from four to two. `TargetCompID=*` means the reduction costs nothing: a client
calling itself `DEMO_CLIENT3` still logs on without a profile existing for it, which is exactly what
the `/verify` skill's recipe does.

### B2 · Starting means connecting

`onDemoProfilesChanged` currently only edits the in-memory profile list. It must now also **connect**:
the venue first, then the clients. `FixMessageViewModel.handleDemoProfilesChanged` grows that, and
`stop()`'s empty-list call grows the mirror image — disconnect the clients, disconnect the venue, and
close the venue's per-client panes (`FX Demo Venue ← DEMO_CLIENT1`), which are sessions registered
under the venue profile's id and would otherwise be left behind pointing at nothing.

`isRunning` stops meaning "a socket is bound" and starts meaning "the demo workspace is installed".
The ConnectionPanel's status line changes with it, from `Running: FIX 4.4 on localhost:19876` to the
venue profile's name and port. **The venue's actual connection state is deliberately not mirrored into
that line** — it is a profile in the list now, with a state dot of its own, and that is the whole point
of the change. A synthetic status line that could disagree with the profile beside it would be worse
than none.

### B3 · Templates

`DemoTemplatesProvider` keeps its shape (id prefix, `getDemoTemplateIds`, `isDemoTemplate`) and gains
what the venue now answers:

| Template | Notes |
|---|---|
| FX Market Buy / Sell EUR/USD | unchanged |
| FX Limit Buy / Sell EUR/USD | prices unchanged (1.0850 / 1.0950 straddle the new 1.09000 mid) |
| FX Quote Request — EUR/USD, GBP/USD, USD/JPY | **carries `146=1` with `55` inside it** (A2), and drops `54`/`38`, which the venue does not read |
| FX Order Cancel Request | unchanged — `${outgoing["D"].valueOfTag(11)}` still names the last order |
| FX Order Cancel/Replace Request (`35=G`) | new; `41` from the last `35=D`, a new `11`, `38` present because the venue's replace rule reads it |
| FX Order Status Request (`35=H`) | new; the message the old sim answered with "not supported" |

The quote-request template is the one place A2 is load-bearing. If A2 is not taken, these carry a flat
`55` and a comment saying why they are not conformant.

### B4 · The bundled scenario

New `service/demo/DemoScenarioProvider.kt`, built as code the way templates are, and written through
a new `onDemoScenariosChanged` callback so the **ViewModel's own** `ScenarioService` saves it — the
one that respects the configured scenarios path and fires `onChanged` so the rail refreshes. (The
existing `SavedMessagesService` inside `DemoServerManager` ignores the configured path; do not copy
that.)

`demo-scenario-eurusd-lifecycle`, "EUR/USD order lifecycle", `binding = THIS_RUN`:

```
setup:  ClearMessages("Demo Client 1")
        ClearOrderBook("FX Demo Venue ← DEMO_CLIENT1")
steps:  Send    35=D|11=DEMO-LIFECYCLE-1|55=EUR/USD|54=1|40=2|44=1.08950|38=1000000|60=${now}
        Expect  35=8 · 150=0 39=0 151=1000000
        Expect  35=8 · 150=F 39=1 14=500000 151=500000 31=1.08950
        Expect  35=8 · 150=F 39=2 14=1000000 151=0
        Send    35=F|11=DEMO-CANCEL-1|41=DEMO-LIFECYCLE-1|55=EUR/USD|54=1|60=${now}
        Expect  35=9 · 102=0        (too late to cancel — the order already filled)
```

Four decisions inside that:

- **A fixed ClOrdID, not `${uuid}`.** It is what makes `ClearOrderBook` load-bearing: without it the
  second run is answered by the duplicate rule out of the first run's memory, red, with the venue
  behaving exactly as told. That failure and its fix are already pinned by
  `ScenarioOrderBookIntegrationTest`; here they are the teaching example a fresh install ships with.
- **The venue-side session is named `FX Demo Venue ← DEMO_CLIENT1`** — the pane title
  `attachVenueClient` mints. It is deterministic, and it is the only session that owns a book to clear;
  preflight refuses a `ClearOrderBook` aimed anywhere else, by name.
- **Field expectations, hand-written; no captured golden.** The venue's market prices jitter by
  design, so a seeded golden would be red on the second run. The limit flow is the one path whose
  price is deterministic — it fills at `${req.44}` — which is why the scenario uses a limit order and
  can assert `31`.
- **It asserts the partial and the remainder**, because the ack-partial-fill sequence is what shows a
  lifecycle rather than a single round trip, and because two reports 250ms apart are what a latency
  and ordering claim looks like on screen.

Only one scenario ships. The reconcile demo's red run is the presenter breaking a venue rule live —
a better moment than a second bundled artifact, and one that shows the tool doing the thing.

### B5 · Deletions

`DemoFixServer` (509), `FxOrderProcessor` (319), `FxOrder` (253), `FxMarketData` (182), `FxOrderBook`
(149), `FxCurrencyPair` (128) — **1,540 lines**, referenced by nothing outside the demo package.
`DemoServerManager` and `DemoTemplatesProvider` survive, re-pointed.

**Two commits, not one, and the gap between them is the safety window.** The first switches the
button over — the old files are still in the tree, just unreachable — so a single `git revert` brings
the whole old demo back if it disappoints during the manual test. The second deletes the six files,
and is made only after that test passes. There is no deprecation period beyond that gap and no
toggle: nothing outside `service/demo/` references these classes and no test covers them, so this is
deleting code one button reaches, not retiring an API with callers. Keeping both behind one button
would be two implementations of a venue, which is the thing the proposal opens by complaining about.

Deliberately lost: resting limit orders filled by a later price tick. A rule engine is reactive; the
random walk crossing a resting order has no rule-shaped equivalent, and the ack-partial-fill sequence
shows a lifecycle better than a fill of unpredictable timing does. Slice C's problem if it is ever
missed.

### B6 · The two ordering hazards

- **Bind before connect.** The clients must not attempt a logon before the venue's acceptor has bound
  the port. Connecting the venue profile first is the intent; `reconnectInterval = "5"` on the demo
  clients is the guard, because QuickFIX/J's default is 30 seconds and a lost race would look like a
  demo that does not work for half a minute. Verify during implementation whether
  `FixMessageSession.connect` binds synchronously; if it does not, a short poll on the venue's
  connection state before connecting the clients is the fix, not a sleep.
- **The FIX version picker is partly cosmetic today, and stays so.**
  `FixConnectionManager.determineBeginString` returns the *loaded dictionary's* version whenever a
  dictionary is loaded, overriding `config.beginString`. Both demo sides read the same dictionary, so
  they always agree and the demo works whatever is picked — but picking FIX 4.2 while the app's
  dictionary is 4.4 does not produce a 4.2 session. Pre-existing; noted so it is not mistaken for a
  regression, and left alone because fixing it is a connection-settings change, not a demo change.

### B7 · Documentation and tooling that name the old demo

| File | What changes |
|---|---|
| `resources/help.html` §2 "Demo FX Server" (lines 218–489) | Rewritten as "the demo is a venue you can read": three pairs not six, the rule cards, the two clients, the bundled scenario, no price-tick section. Keep the `id="demo-server"` anchor — `HelpDocTest` pins every internal link, and §3 and §11 both point at it. |
| `resources/help.html` §3 "Demo Server" sidebar | Same, shorter. |
| `control/McpTools.kt` `fixtool_demo` | Description: it installs an FX venue profile plus two client profiles, not "a demo FIX server". |
| `control/ControlServer.kt` `/demo` | Response gains the venue profile name; `docs/AUTOMATION.md` row updated with it. |
| `.claude/skills/verify/SKILL.md` | `targetCompID: "DEMO_SERVER"` still correct; the "accepts DEMO_CLIENT1–4" line becomes "accepts any CompID (`TargetCompID=*`), and the demo ships clients 1–2". |
| `config/detekt/baseline.xml` | Six suppression entries name `DemoFixServer.kt`; they go with the file, or the baseline carries rules for code that no longer exists. |
| `tools/fake-venue/README.md` line 9 | Points at `service/demo/DemoFixServer.kt` as the example acceptor. Re-point it at the venue profile, or at `tools/fake-venue` standing alone. |
| `CHANGELOG.md` | One entry for the pair. |

### B8 · Tests

- **`DemoWorkspaceTest`** (unit): `start()` produces one acceptor profile carrying the FX bundle's 21
  rules and the latency config, plus two initiator profiles; every id is `demo-`-prefixed;
  `getDemoTemplateIds()` covers every template `createDemoTemplates` returns — a mismatch there is a
  silent leak into the user's saved messages, which the current code has no guard against.
- **`DemoScenarioProviderTest`**: the bundled scenario round-trips through `ScenarioCodec`, names only
  sessions the workspace creates, and its `ClearOrderBook` targets the venue-client pane title.
- **New `DemoWorkspaceIntegrationTest`**: `DemoServerManager.start(FIX_4_4, port = TestPorts.free())`
  in a test ViewModel; both clients reach `LOGGED_ON`; the bundled scenario runs **green twice in a
  row** — the second run is the assertion that matters, and it is the one the `ClearOrderBook` step
  exists for; `stop()` leaves no demo profile, template or scenario behind, and no live session.

### B9a · What live verification found

Three defects that every test layer passed. Recorded because each one is a shape worth expecting again:

1. **Stop left two dead venue panes on screen.** The demo's clients auto-reconnect, so while everything
   was winding down the venue kept accepting them and minting fresh panes — *after* the sweep meant to
   remove them. The unit test asserted "no demo session is LOGGED_ON", which a disconnected orphan
   satisfies. Fixed by removing the demo's profiles **before** its sessions, and by having
   `attachVenueClient` refuse to open a pane for a venue profile that is no longer present. The test now
   asserts the panes are *gone*, not merely quiet.
2. **Stop then Start came back with no venue in it.** Those same orphan panes stayed filed under the
   venue's profile id, so the next `connectProfile` adopted them as the venue's own sessions and never
   created the venue. Fixed by closing the venue *first* (a closed port is a reconnect with nowhere to
   land) and by sweeping stale demo sessions on the way **up** as well as down.
3. **The demo's own quote-request template warns under a venue-subset dictionary.** Not a defect — the
   lint is correct. The verifying machine had `useBundledDictionary=false` and a BrokerTec subset
   configured, which does not define NoRelatedSym for `35=R`, so the tool says the group will be sent
   flat. Worth knowing that the demo works either way: grouped under the bundled dictionary (the trigger
   reads the group, per A2) and flat under a subset, because flat is checked first.

### B9 · Exit criteria

- A fresh install: press **Start Demo Server**, and within seconds there is a venue profile with 21
  readable rule cards, two connected clients, templates, and one scenario that runs green — twice.
- Press **Stop**: nothing demo-prefixed remains in `~/.fixtool`, and no session is left connected.
- Live-verified through `/verify`, on a non-default control port, with a screenshot of the venue's
  rule cards beside a client pane mid-lifecycle — the frame the video opens on.

---

## Slice C — ticking quotes (deferred)

Unchanged from the proposal: unsolicited timed sends, a streamer card beside the rules, driven by the
same scheduler and expression engine. Decided after the first video cut.

One thing this plan adds to that decision. The expression engine evaluates one `${…}` at a time under
a process-wide lock at ~44–82ms each. A streamer sending a two-price quote every 250ms would hold that
lock roughly a third of the time, against a message editor and a scenario runner that share it. Slice C
is therefore not only a scheduling feature — it needs a cheaper price source than a script eval, which
is another reason it should be designed as a real feature rather than as demo garnish.

---

## Decisions taken

| Proposal question | Decision |
|---|---|
| Three pairs or six? | **Three.** The cards stay readable and USD/JPY carries the convention flex; six doubles the per-pair cards for no extra story. |
| A second, deliberately-red demo scenario? | **No.** The presenter breaks a venue rule live, which is the better moment and shows the tool working. |
| Slice C trigger | The first video cut, plus the lock cost above. |
| `35=AG` or a generic `35=j`? | **`35=AG`**, which needs A1. `35=j\|380=2` (Unknown security) is the fallback if A1 is dropped — flat, conformant, and a weaker answer for an FX audience. |
| Group reads | **Take A2.** The proposal's fallback (flat `55`) works but ships a non-conformant QuoteRequest as the demo's own template. |
| Demo port | **Parameterized**, defaulting to 19876, so the workspace is testable end-to-end. |
| `isRunning` semantics | "The workspace is installed", not "a socket is bound". The venue's real state is the profile's own state dot. |

## Risks

| Risk | Bound |
|---|---|
| A1 changes the construction path of every acceptor reply | The shipped presets carry no header tags and no groups; the existing preset tests are re-run unmodified as the compatibility proof, and a throw falls back to today's builder. |
| A2 changes what a trigger can read | Flat is checked first, so no existing rule's behaviour moves. "First group instance" is an approximation, documented as one. |
| 21 cards is a long list to read on camera | Six of them are the per-pair repetition that buys the FX story; collapsing the market fills into one rule would price USD/JPY like EUR/USD, which is worse. Reviewed against the first cut. |
| The client/venue connect race | `reconnectInterval = "5"` plus venue-first ordering; a state poll if `connect` proves asynchronous. |
| `ktlintFormat` is repo-wide and has rewritten ~40 unrelated files | Hand-fix the new files; never run it while holding uncommitted work. **Done for slice A** — the long lines added were wrapped by hand and the pre-existing counts in `AcceptorPresets.kt` (5) and `AcceptorPresetsTest.kt` (6) are unchanged. |
| Two `object`s composing each other's content is an initialisation cycle | Hit in A5; see A5a. The catalogue is `by lazy` and a class-loader test pins both directions. |
| A venue's per-client panes outlive a teardown that only waits on connection state | Hit in B; see B9a. Profiles are removed before sessions, pane creation is refused for an absent profile, and the test asserts panes are gone rather than idle. |
