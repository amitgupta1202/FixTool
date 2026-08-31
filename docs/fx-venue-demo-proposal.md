# The demo is the acceptor — FX venue preset + demo workspace (proposal)

**Replaces:** the QuickFIX/J demo sim under `service/demo/` (`DemoFixServer`, `FxMarketData`,
`FxOrderBook`, `FxOrderProcessor`, `FxOrder` — ~1,700 lines)
**Builds on (all shipped):** #30/#34 rules engine, #31 presets, #32 multi-client, #36 latency,
#40 Reply With…, #35 order-state book
**Not touched:** `tools/fake-venue` — that is a wire-order test rig, not a demo

---

## The problem

FixTool carries **two implementations of "a venue"**, and demos use the wrong one.

The demo server is already FX-shaped — 6 pairs, pip-aware prices, a ticking `FxMarketData`, resting
limit orders filled on price crossings — but it is hard-coded Kotlin behind a start button. Nobody
can open it, read why it replied, reorder a rule, or make it misbehave on purpose. And its edges are
what a viewer actually hits: `35=G` is always rejected ("Order replace not supported"), a
QuoteRequest gets one static quote, and the ticking prices are invisible because nothing streams
them.

Meanwhile the acceptor — the feature that is currently *experimental and uncommunicated* — does
everything the demo server does and more: a rule list you can read on cards, timed multi-step fills,
a real order-state book answering cancels and status requests, per-message latency, multi-client
sessions, and refusal diagnostics. The demo never shows any of it.

For the YouTube videos this is the difference between "here is a tool talking to a black box" and
"here is a venue you can see thinking, and it is the same product." And for testing the acceptor, no
external server is needed at all: `ScenarioOrderBookIntegrationTest` already connects a FixTool
acceptor and a FixTool initiator to each other **in one instance** and runs a scenario across both
sides. That is the demo, and it is already true.

## What already exists — do not rebuild

- **Rule content and placement**: `AcceptorPresets` — 16 presets + the starter bundle, `insert()`
  placing conditioned rules where they can fire, the "never read a tag the trigger does not
  guarantee" discipline, `AcceptorPresetsTest` enforcing it.
- **Reply machinery**: multi-step delayed sequences (`ResponseStep`, per-session scheduler in
  `AcceptorDispatch`), `${req.<tag>}` / `${uuid}` / `${now}` / `${order.…}` resolved per step as
  sent, and **full Kotlin expressions** in templates — `${1.0898 + Random.nextInt(0, 5) * 1.0e-4}`
  is a quote that differs every time, with zero new engine work.
- **The book**: cancels answered by what the venue actually holds, status requests answered with
  real quantities, duplicate ClOrdID rejection.
- **Demo lifecycle UX**: `DemoServerManager` + the collapsible ConnectionPanel section — start/stop,
  FIX version picker, auto-created demo profiles and templates, cleanup on stop. This UX is right;
  only its guts are the wrong venue.
- **Both sides in one scenario**: an acceptor profile and an initiator profile in the same instance,
  one scenario driving and asserting both.

## The shape

Three slices. A and B ship together as one issue; C is deferred until the videos prove they need it.

### Slice A — the "FX venue" preset bundle

One bundle in the existing catalogue (`GROUP_BUNDLES`), inserted as ordinary rules like everything
else. **Three priced pairs — EUR/USD, GBP/USD, USD/JPY** — few enough that the rule list reads as
cards, and USD/JPY buys the realism flex (3-decimal prices, 0.01 pips) that tells a FIX audience we
know the domain. Base prices and pip sizes move from `FxCurrencyPair` into preset constants.

| Rule(s) | Trigger | Reply | Notes |
|---|---|---|---|
| Quote — per pair (3 cards) | `35=R`, `55` exact | `35=S`, echo `131`, bid/ask via jittered expressions | ~2-pip spread; every quote prices slightly differently, per send |
| Quote — unknown symbol | `35=R`, unconditional, below the pair cards | `35=AG`, `658=1` | first-match ordering does the "not one of" — no negated matcher needed |
| Limit flow | `35=D`, `55` oneOf the 3 pairs, `40=2` | existing ack→fill / ack-partial-fill shapes, priced at `${req.44}` | reuse of the shipped templates, narrowed to known symbols |
| Market fill — per pair (3 cards) | `35=D`, `55` exact, `40=1` | ack, then fill at the pair's price ± jitter | discipline holds: a market order carries no 44, so the price is the rule's own content |
| Order — unknown symbol | `35=D`, unconditional, bottom | `39=8`, `103=1` | catches what the oneOf rules let through |
| Oversize reject | `35=D`, `38 > 10000000` | existing shape | 10M is an FX-plausible bound |
| Cancel / replace / status / duplicate | — | **starter-venue + book rules verbatim** | symbol-agnostic already; nothing re-typed |

Plus a **default latency config** on the demo venue profile (~40–80ms jitter): #36 on camera, and
replies that don't land suspiciously instantly.

Two cheap checks before committing to templates (both have fallbacks, neither blocks the shape):

1. **`55` inside `NoRelatedSym(146)`** — can a condition and `${req.55}` read it there? The demo
   templates can carry `55` flat either way; if group reads work, condition on the group form too.
2. **Formatted prices** — `"%.3f".format(…)` in an expression for JPY's 3 decimals; contains no `|`,
   so the template splitter is safe, but pin it with a test.

### Slice B — the demo workspace swap

`DemoServerManager.start(fixVersion)` keeps its signature, the ConnectionPanel section keeps its
place, port 19876 keeps its number. What changes is what starts:

- **One acceptor profile — "FX Demo Venue"** — carrying the slice-A bundle and the latency config.
  `TargetCompID=*` (#32), so every demo client logs on to the one venue.
- **Two initiator client profiles** (down from four — two panes fit on camera; two clients hitting
  one venue is the multi-client shot).
- **Templates, retargeted**: the existing `DemoTemplatesProvider` list plus what the venue now
  earns — a replace, a status request, quote requests per pair — at slice-A's prices.
- **One bundled demo scenario** — "EUR/USD order lifecycle" (limit order → ack → fill →
  cancel-too-late), shipped as code the way templates are, so a fresh install runs green in the
  first minute and every video is reproducible by anyone who follows it.
- **Stop** disconnects and deletes demo-prefixed profiles/templates — the existing pattern.

Then delete `DemoFixServer`, `FxMarketData`, `FxOrderBook`, `FxOrderProcessor`, `FxOrder`
(~1,700 lines). `FxCurrencyPair`'s data survives as preset constants; `DemoServerManager` and
`DemoTemplatesProvider` survive, re-pointed. The FIX version picker maps to the acceptor profile's
`beginString`.

What is deliberately **lost**: resting limit orders filled by a later price tick. A rule engine is
reactive; the sim's random walk crossing a resting order is the one behaviour with no rule-shaped
equivalent. The demo scenario doesn't need it — the ack-partial-fill sequence shows a lifecycle
better than a fill of unpredictable timing does — and if it is ever missed, it is slice C's problem.

### Slice C — ticking quotes (deferred)

The one genuinely new capability: **unsolicited timed sends** — "every N ms, send this template" —
a streamer card beside the rules, driven by the same scheduler and expression engine. It would give
videos live-moving prices and MD-project clients a feature they'd recognise. Deferred because the
lifecycle story is the stronger demo for the QA/BA audience, and because it should be designed as a
real feature (subscription-aware, `35=W/X`) rather than as demo garnish. Decide after the first
video cut: if the frame feels static without it, this is the slice that fixes it.

## The video story this buys

One window. Left pane: a demo client sends a EUR/USD limit order from a template. Right pane: the FX
Demo Venue's rule cards — the viewer watches the trigger match, the ack and the partial land ~250ms
apart with real latency, the book update. Then the scenario tab: the same flow as a repeatable run,
green. Then open a venue rule, change the fill price, run again — red, and the reconcile view says
exactly why. No competitor screenshot shows the venue side thinking, and everything on screen is a
shippable feature, not a prop.

## Open questions

1. **Three pairs or six?** Three recommended (cards stay readable; USD/JPY covers the convention
   flex). Six matches the old sim but doubles the per-pair cards for no extra story.
2. **Bundle a second, deliberately-red demo scenario** for the reconcile demo, or leave breaking the
   green one to the presenter? (The video script above suggests presenter-broken is the better
   moment.)
3. **Slice C trigger**: decided by the first video cut, or is there an MD-side client ask that pulls
   it forward on its own merits?
