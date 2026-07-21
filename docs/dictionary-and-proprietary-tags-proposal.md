# The dictionary the tool ships, and the tags the spec never named

> **Outcome — Part A shipped, Part B's venue overlay shipped, Part B's detector still open
> (2026-07-21).** Proprietary correlation ids are now supported through
> `<dictionary>.roles.json` beside the venue's dictionary — see *The venue overlay* below,
> live-verified against the real BrokerTec dictionary. No proprietary tag entered FixTool's
> source, which was the constraint. What is **not** built is the echo detector that would
> make the overlay discoverable rather than something you must know to write.
>
> **Outcome — Part A shipped (2026-07-21), live-verified.**
> The bundled FIX 4.4 dictionary is now QuickFIX/J's whole one (93 → 916 fields), and the
> stale-timestamp warning asks what the dictionary *knows about the tags at hand* instead
> of whether one is loaded. `ScenarioCapture.captureRisk` is the single source of that
> sentence, shared by both UI doors and — new — by the two control-surface capture
> endpoints, which had no warning at all (`warning` in the response; an agent driving a CI
> job could not otherwise learn it). `BundledDictionaryTest` is the guard that a demo asset
> can never be the default again. 1727 tests green.
>
> **What live verification turned up, and it is worth its own line:** the running app was
> not on the bundled dictionary at all — it was on the BrokerTec venue dictionary named in
> `app_settings.json`, which is **358 fields** and does not define `EffectiveTime(168)` or
> `StrikeTime(443)`. Both were replaying literal captured timestamps, and under the old
> `dictionary == null` test **nothing was said**, because a dictionary was loaded. The new
> warning names them. So the loaded-but-blind case argued hypothetically below was already
> live on this machine, on a real venue's dictionary — Part A's swap does not fix that one
> (a venue dictionary is the author's own file), the warning does.
>
> Two fixture repairs came with the swap, both tests noticing the dictionary got better
> rather than regressions: `NoSecurityAltID(454/455)` is now dictionary-defined, so the two
> heuristic-path tests moved to 9000s tags no standard dictionary can learn (`MoveRuleTest`'s
> own guard caught this, by design), and PartyRole labels are the dictionary's verbatim
> `EXECUTING_FIRM`, not the stub's `ExecutingFirm`.

**Status: Part A implemented; Part B proposed.** Two changes to how capture classifies a
tag. They are independent and deliberately unequal: **Part A is a defect** in the default
path, verified by running the seeder against both dictionaries; **Part B is a feature**,
and the proposal is to build only the half of it that pays for itself before anyone asks.

Both touch the same question — *how does capture know what a tag means?* — which today is
answered by four hardcoded sets (`ScenarioCapture.ID_TAGS`, `ExpectationSeeder.PRESENCE_TAGS`,
the two `LIFETIME_TAGS`) plus the loaded dictionary's type and name. The audit in the
appendix confirms the hardcoded half is clean. The dictionary half is not.

---

## Part A — the bundled FIX 4.4 dictionary is a demo stub

`composeApp/src/jvmMain/resources/dictionaries/FIX44.xml` carries **93 fields and 18
messages**. QuickFIX/J's real FIX44 dictionary carries **916**. Every other bundled
version is complete (FIX43: 655, FIX50SP2: 1432); 4.4 is the only trimmed one, and it is
the only one that is the **product default** — `FixVersion.kt:30` points `FIX_4_4` at it,
`FixVersion.DEFAULT = FIX_4_4`, `AppSettings.defaultFixVersion = FIX_4_4`.

It arrived in `74cf987`, *"Add enhanced FX demo server with bundled dictionary and
templates"*. It is a demo asset that became the default dictionary for every user who has
not set `AppSettings.defaultDataDictionary`.

The four failures below were produced by running `ExpectationSeeder` and
`ScenarioCapture.captureFrom` against the shipped stub and against QuickFIX/J's FIX44,
same input both times.

### A1. Replayed sends carry stale timestamps

`isTimestamp` (`ScenarioCapture.kt:548`) hardcodes tag 60 and asks the dictionary about
everything else. A blind dictionary answers "not a timestamp", and the value replays
verbatim.

Capturing one outgoing QuoteRequest carrying `EffectiveTime(168)` and `StrikeTime(443)`:

```
                     shipped stub                        real FIX44
  35=R               35=R                                35=R
  131=QR-LOCAL-1     131=${quoteReqID = uuid:20}         131=${quoteReqID = uuid:20}
  60=...             60=${utcnow}                        60=${utcnow}
  168=...            168=20260721-11:05:00.000   ← stale 168=${utcnow}
  126=...            126=${utcnow+5min}                  126=${utcnow+5min}
  443=...            443=20260721-11:30:00.000   ← stale 443=${utcnow}
```

Two months later that scenario sends a May timestamp at a July venue. Note what *did*
survive: `60` (hardcoded) and `126` (in `LIFETIME_TAGS`, checked unconditionally before
the dictionary). **The tags we hardcoded are fine; the ones we delegated to the
dictionary are not.** That is the shape of the whole defect.

The stub is blind to **18** UTCTIMESTAMP/TZTIMESTAMP tags that real FIX 4.4 defines:
42 OrigTime, 122 OrigSendingTime, 126 ExpireTime, 168 EffectiveTime, 341–345 TradSes\*Time,
367 QuoteSetValidUntilTime, 438 ContraTradeTime, 443 StrikeTime, 483 TransBkdTime,
515 ExecValuationPoint, 586 OrigOrdModTime, 629 HopSendingTime, 769 TrdRegTimestamp,
779 LastUpdateTime. It knows three: 52, 60, 62.

### A2. Seeded expectations go permanently red

Same blindness on the receive side, and here it is worse, because the seeder's fallback is
`Exact`. Seeding one TradeCaptureReport:

```
  tag 769 (TrdRegTimestamp)   stub: Exact("20260721-11:04:22.100")   real: Temporal(NOW ±60s)
  tag 779 (LastUpdateTime)    stub: Exact("20260721-11:04:22.100")   real: Temporal(NOW ±60s)
  tag  42 (OrigTime)          stub: Exact("20260721-11:04:21.900")   real: Temporal(NOW ±60s)
  tag 168 (EffectiveTime)     stub: Exact("20260721-11:05:00.000")   real: Temporal(NOW ±60s)
```

Four rows asserting that a moment equals a specific past moment. They are red on the
first replay and red for ever — deterministically, on the environment the scenario was
captured on.

This is the exact failure `PRESENCE_TAGS` was written to prevent. Its KDoc
(`ExpectationSeeder.kt:39-43`) says seeding these Exact *"made every captured scenario in
that flow fail its own first replay, deterministically"*. The set fixed it for venue-minted
**ids**; a blind dictionary reinstates it for venue **timestamps**, through the type rule
instead of the tag rule.

### A3. The fix plan then launders the red into lost coverage

This is the one that matters, because it is silent.

The author sees four permanently-red rows and opens the fix plan. It obliges — measured on
tag 769, both dictionaries:

```
                        shipped stub      real FIX44
  numericFamily(769)    false             false
  textFamily(769)       true              false
  identifierFamily(769) true       ←      false
```

`identifierFamily` returns true because the stub returns no name and no type, and the gate
admits *"tags the dictionary does not know at all"* (`ExpectationSeeder.kt:141-144`) — a
clause written for custom 5xxx tags, which a blind dictionary makes true of standard FIX
4.4. So the tool proposes **presence demotion** on a timestamp. The proposal is
default-unchecked and carries its reason, which is the right design — but the author has
four red rows, the reason reads plausibly, and accepting it is the obvious move.

The field is now never asserted again. The chain is: blind dictionary → Exact on a
timestamp → permanent red → tool proposes demotion → author accepts → coverage gone. Every
step looks reasonable. The result is a green scenario checking less than the author
believes, which is the outcome `SessionTags` calls *"the one outcome a testing tool must
never produce"*.

**Nobody files a bug for this.** That is the answer to "leave it until someone complains":
the complaint mechanism does not fire on this class of failure.

### A4. Scenario variables get numeric names

6 of the 13 `ID_TAGS` are absent from the stub, so `mintName` falls through to its
`tag$tag` branch — and the name is written into the saved scenario file for ever:

```
  stub:  66->tag66  70->tag70  320->tag320  335->tag335  526->tag526  583->tag583
  real:  66->listID 70->allocID 320->securityReqID 335->tradSesReqID
         526->secondaryClOrdID 583->clOrdLinkID
```

`${tag320}` in the reference dropdown is precisely the `id0`, `id1`, `id2` problem
`ScenarioVariableNaming` was created to end.

### A5. The guardrail cannot fire

Capture warns that timestamps beyond TransactTime will replay stale — but only when
`dictionary == null` (`FixMessageViewModel.kt:1400`, `:2717`). Here a dictionary *is*
loaded. It is simply almost empty. Every failure above happens with no warning at all.

### The fix

Replace the file with QuickFIX/J's FIX44.xml (already on the classpath via
`quickfixj-messages-all`). One file swap.

**Verified risk, and it is a single line:** the stub relaxes `TradeCaptureReport(AE)` —
real FIX 4.4 additionally requires `OrderID`, `PreviouslyReported`, `TradeDate`,
`TransactTime`. Every other one of the stub's 18 messages has identical required-field
rules. The demo server never emits `AE` (no reference to it in `service/demo/`), so
nothing in the demo path changes. If a demo template ever needs the relaxed form, it gets
its own resource — a demo dictionary must not be the product default.

Worth adding alongside: widen the A5 warning from `dictionary == null` to *"the loaded
dictionary does not know tag N"*, evaluated over the tags actually being captured. That
turns the whole class of failure from silent into stated, for custom dictionaries too.

### What Part A buys

- **Timestamp handling becomes correct by default** on 18 standard fields, both
  directions — no stale sends, no permanently-red rows.
- **The fix plan stops proposing demotions it should not**, because `identifierFamily`
  stops treating standard FIX as unknown. This restores the classifier's intended scope
  without touching the classifier.
- **Scenario variables read in the venue's vocabulary** on all 13 correlation ids.
- **Seeding gets its numeric/text classification back** for ~820 fields the stub hides —
  every `numericFamily`/`textFamily` decision on them is currently taken blind.
- **The default stops being a demo asset.** A file claiming to be FIX 4.4 while carrying
  10% of it is wrong independent of any behaviour it causes.

---

## Part B — correlation ids the spec never named

`ID_TAGS` is 13 standard tags. A venue's proprietary request id — tag 9482, say — gets no
treatment: it replays verbatim (a duplicate at any venue enforcing uniqueness on it), its
echo is not recognised, and the Expect binds to the first same-type message rather than
this run's reply. Exactly the bug `a42a0c5` fixed for the standard ids, still open for the
custom tail.

### Why the obvious fix is the wrong one first

The obvious fix is a settings list of extra id tags. It works, and nobody will ever fill it
in — you must already know the mechanism exists, know your venue's tag numbers, and know
that not declaring them silently degrades your scenarios. The failure it prevents is
invisible, so the configuration that prevents it is never sought.

Deriving from the dictionary is worse: it cannot distinguish **who mints** the value.
`ClOrdID(11)` and `OrderID(37)` are both STRING fields whose names end in `ID`. Getting
that backwards costs either duplicate rejections at the venue or a scenario asserting that
a field equals a uuid. It is not available as a silent rule.

### The proposal: capture proposes from what it observed

Capture already holds **proof rather than a guess**: we sent this value, and it came back
on a reply. That is an observed echo whatever the tag number is.

The only reason `ID_TAGS` gates the echo test today is the false positive documented at
`ScenarioCapture.kt:504-511` — an ordinary `11=1` correlating against `Side(54)=1`. But
that is a **value-entropy** problem, not a tag-identity problem. `1` coming back is
coincidence. `A7F3C201881B` coming back is not.

So: for a tag with **no known role**, when a value we sent reappears on a reply and clears
an entropy gate, capture review offers it — never applies it:

```
  ☐  tag 9482 — treat as a correlation id?
     Your NewOrderSingle(D) carried  9482=A7F3C201881B
     and ExecutionReport(8) came back with the same value.
     Ticking mints a fresh value per run and verifies the echo.
```

Default-unchecked, with the evidence in the sentence. This is the pattern the codebase
already committed to for presence demotion — `identifierFamily` is *"deliberately wider
than certainty"*, so its proposal is default-unchecked and its reason says so
(`ExpectationSeeder.kt:138-145`). Same shape, same justification.

**The entropy gate** (all must hold, else no proposal):
- length ≥ 8
- not a plain integer under 8 digits — kills `1`, `100`, sequence-like values
- at least two character classes, or ≥ 12 chars of one — kills `AAAAAAAA`
- the dictionary does not list the tag as having enumerated values — an echoed enum is a
  coincidence, not a correlation
- the tag is not already in `ID_TAGS` or `PRESENCE_TAGS` — those are decided

### What ticking it does

Exactly what a standard id gets today, on that tag, for that capture:

```
  before                                   after
  send:   9482=A7F3C201881B                9482=${tag9482 = uuid:20}
  expect: 9482 → Exact("A7F3C201881B")     9482 → Reference("${tag9482}")
          match: {35=8}                    match: {35=8, 9482=${tag9482}}
```

The mint routes through `mintName` like every other path, so with the venue's dictionary
loaded it is `${venueOrderRef}`, not `${tag9482}` — which is a second reason Part A comes
first.

### The venue overlay — **built** (2026-07-21), ahead of the detector

Originally deferred here on the argument that the overlay answers "I am tired of
re-ticking", a complaint that would arrive with the tag numbers attached. Two things
overtook that:

1. **The constraint is explicit**: proprietary tags must not enter FixTool's source, because
   this source is shared across a dozen venues and a hardcoded `20013` is a claim about all
   of them. That makes the overlay *the mechanism*, not a convenience — there is no other
   place a venue's own answer can live.
2. **The candidates are already visible.** The BrokerTec dictionary defines 132 tags outside
   standard FIX 4.4, of which at least nine are id-shaped: `LegQuoteReqID(20013)`,
   `SecondaryQuoteID(1751)`, `SecondaryTradeLinkID(20071)`, `BatchID(20040)`,
   `LegIOIID(20086)`, `CounterpartyAxeID(20093)`, `LegReportId(990)`, `LegID(1788)`,
   `OrigTradeID(1126)`. The overlay is not speculative for that venue.

**Shape as shipped.** `<dictionary-file>.roles.json`, beside the dictionary:

```json
{ "20013": "CLIENT_MINTED_ID", "1751": "CLIENT_MINTED_ID", "20040": "VENUE_MINTED_ID" }
```

Roles are `CLIENT_MINTED_ID`, `VENUE_MINTED_ID`, `LIFETIME` (see `TagRole`). A tag may carry
a **list** of roles — `QuoteID(117)` is the standard case and it is real, since whoever
quotes mints it, and capture resolves per capture by whether this scenario's own send minted
the value. A model storing one role per tag would break dealer-side RFQ.

It hangs off `FixDictionaryAdapter` rather than being threaded through capture and seeding
as a parameter: the roles are the same *kind* of fact as a field's name or type — a property
of the venue's dialect — and every surface that classifies a tag already holds a dictionary.
Zero new arguments anywhere, and the roles cannot drift from the names they sit beside.

The built-in sets are unchanged and the overlay only ever **adds**, so a venue that declares
nothing loses nothing. Malformed entries are skipped individually rather than thrown: one
typo must not silently discard a venue's whole declaration at capture time.

Sidecar rather than an attribute inside the venue's XML: the venue ships you that file and
their next release overwrites your edits.

**Verified live on the real BrokerTec dictionary** — same paste, same venue dictionary, with
and without the sidecar:

```
without:  20013=LEG-A7F3C201                     expect 20013 → exact("LEG-A7F3C201")
          bind [131]
with:     20013=${legQuoteReqID = uuid:20}       expect 20013 → reference(${legQuoteReqID})
          bind [131, 20013, 1751]
```

The variable names come from the venue's own dictionary via `mintName`, so the reference
dropdown reads `${legQuoteReqID}`, not `${tag20013}`.

**Still open: the detector.** The overlay is *declaration*, and declaration-first config is
undiscoverable — you must already know the mechanism exists to fill it in. The echo proposal
above is what makes it discoverable, and it is not built yet. Until it is, a venue's ids are
supported but must be declared by hand.

### What Part B buys

- **Proprietary correlation ids work**, on the venue that has them, without a config file
  anyone has to discover.
- **The demand becomes measurable.** Today "do our clients use custom correlation ids?" is
  unanswerable; after this it is answered by whether the row appears.
- **No new vocabulary.** It reuses `Reference`, the bind constraint, and `mintName` — the
  scenario file format does not change at all.
- **It cannot silently do harm**: nothing is applied unticked, and a wrong tick is one
  matcher the author can revert.

---

## Sequencing

1. **Swap the FIX 4.4 dictionary.** Standalone, ships immediately, no design surface.
2. **Widen the stale-timestamp warning** from "no dictionary" to "this dictionary does not
   know tag N". Small, and it covers custom dictionaries that Part A's swap cannot.
3. **The echo proposal in capture review.** Detector plus one row type.
4. **The venue overlay** — only when a capture says it is needed.

---

## Appendix: the hardcoded tags are all standard FIX

Checked every hardcoded tag in the capture/seeding path against the nine bundled
dictionaries plus QuickFIX/J's full FIX44. **All 40 distinct tags are genuine standard
FIX, and every KDoc name matches the dictionary name.** Nothing sits in the user-defined
range (5000–9999) or the private range (10000+).

| Set | Tags | Verdict |
|---|---|---|
| `ScenarioCapture.ID_TAGS` | 11, 41, 66, 70, 117, 131, 262, 320, 335, 526, 571, 583, 693 | clean |
| `ExpectationSeeder.PRESENCE_TAGS` | 17, 19, 37, 117, 198, 278, 527, 880, 1003 | clean |
| `LIFETIME_TAGS` (both copies) | 62, 126 | clean |
| `STABLE_VALUE_DISCRIMINATORS` | 39, 150 | clean |
| `SessionTags.REWRITTEN_ON_SEND` | 8, 9, 10, 34, 43, 49, 50, 52, 56, 57, 97, 115, 122, 128, 142–145, 369 | clean |
| `SessionTags.NEVER_ASSERTED` | 8, 9, 10, 34, 49, 52, 56, 369 | clean |
| `SessionTags.VALUE_NOT_PORTABLE` | 50, 57, 115, 122, 128, 142–145 | clean |

Two spec renamings, both **in prose only, not in any set**: tag 22 was `IDSource` in FIX
4.2 (`SecurityIDSource` from 4.3) and tag 297 was `QuoteAckStatus` in 4.2 (`QuoteStatus`
from 4.3). The KDoc at `ExpectationSeeder.kt:116,128` uses the modern names. No change
needed.

The one deliberate overlap is correct and must be preserved by anything Part B adds:
`QuoteID(117)` is in **both** `ID_TAGS` and `PRESENCE_TAGS`, resolved at capture time by
whether this scenario's own send minted the value (`ScenarioCapture.kt:56-59`) — which is
what makes dealer-side RFQ work. A role model that stores one role per tag would regress
it.
