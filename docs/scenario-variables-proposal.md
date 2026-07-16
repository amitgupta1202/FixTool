# Scenario variables — the scope becomes visible, and defining one becomes a capture

**Status: proposal.** Covers the reconcile-view gap found while testing the redesigned
diff surface: the variable scope a run minted is invisible at reconcile time, `reference`
is missing from the matcher dropdown, and there is no way to *define* a variable from a
value you are looking at — sent or received. Builds on the assertion model in
[`scenario-assertion-model.md`](./scenario-assertion-model.md) and the diff surface from
[`scenario-ux-redesign-proposal.md`](./scenario-ux-redesign-proposal.md); neither is
reopened.

---

## The gap, in one run

Capture auto-detects echoed ids: the first send of a correlation value mints
`11=${id0 = UUID…}` (`ScenarioCapture.idExpr`), the echo becomes `Matcher.Reference("${id0}")`
plus a bind constraint `11=${id0}` (`expectStep`). That automatic path works. But the
moment a run fails and the reconcile diff opens:

1. **You cannot see the variables.** The scope lives as a local `MutableMap` inside
   `ScenarioRunner.run` and dies with the run. The diff gets no resolver
   (`newReconcileSession`, `FixMessageViewModel.kt:605`), so every reference row is
   amber ◌ "unjudged here" — even though the failing run *had* the values.
2. **You cannot author a reference.** The dropdown is `DIFF_MATCHERS =
   MATCHER_TYPES.filter { it != "reference" }` (`DiffSurface.kt:1271`) — deliberately,
   because an unresolvable `${…}` would be unjudgeable and falsely green the verdict.
   The reason is about to evaporate (Part 2), so the ban goes with it.
3. **You cannot define a variable at all** outside free-typing `${name = expr}` into a
   Send's raw text. Two cases matter:
   - a value **we sent** that capture's ID_TAGS heuristic didn't mint — it should be
     mintable after the fact, from the diff, and referenced where it echoes;
   - a value **the venue chose** that our later sends must echo back — the known
     receive→send gap: there is no way to capture a received value into scope.

And a conceptual point this proposal adopts as a rule: **referencing an existing
variable is a matcher; defining one is a capture.** The UI never conflates them —
`reference` joins the matcher dropdown, but *defining* a variable is a distinct
capture operation with its own affordance, even when offered from the same row.

## What we keep

- **The scope model** — one `MutableMap<String,String>` per run, threaded through every
  step, `${name = expr}` mints, `${name}` reads, unknown names left literal
  (`syntax.md`, `FixMessageTemplate.evaluateExpression`). Nothing about resolution
  semantics changes.
- **The S7 invariant** — *given what a reconcile view has, an unreadable row is
  `unknown`, never `false`* (`SemanticsContractTest`). Part 2 narrows "what the view
  has", it never breaches the invariant where it still holds.
- **The positional assertion model** — `bindAs` (Part 4) hangs off a row, so "capture
  the k-th occurrence of tag T" is expressible for free.
- **Capture's auto-detection** stays the automatic path; reconcile gains the manual and
  the *suggested* path, reusing the same value-equality detection.

---

## Part 1 — The scope outlives the run

The runner already owns the scope; today it drops it on the floor.

- `ScenarioReport` gains an additive `variables` key: the final scope as
  `{name: value}`, plus per-name provenance `{mintedAtStepId}` (the runner knows which
  step's evaluation performed each assignment — record it at the `evaluateExpression`
  write). Old report readers are unaffected; old files round-trip unchanged.
- The ViewModel holds the last run's scope beside `_lastRunScenario` /
  `_assertionResults`, and it obeys **the same lifespan table** those got in the
  2026-07-16 fixes: cleared by `dismissRunResult()`, on own-scenario delete, on
  last-run-session close. A scope outliving its run report would be a stale-data bug of
  exactly the kind that review burned down.
- **Control surface:** the run report JSON and the `/scenarios/reconcile` response carry
  `variables`, so agent-driven repair sees what the author sees.

**Decided: final scope, not per-step snapshots.** Mints are one-per-name in practice
(`id0`, `id1`…); the only distortion is a name deliberately re-assigned mid-run, which
capture never produces. Per-step snapshots buy that edge at the cost of a fatter report.
Revisit only if re-assignment becomes a real idiom.

## Part 2 — The reconcile view learns to read

- `ReconcileSession` already takes `resolver: (String) -> String?` (default `{ null }`).
  `newReconcileSession` supplies one backed by the captured scope — **only when the
  reference slot's provenance is THIS_RUN.** Against golden / paste / second-instance
  the scope does not correspond to that wire; those rows stay amber ◌. This is the S7
  invariant applied honestly: resolving `${id0}` against another run's paste would show
  a confident ✗ on a row that is fine — worse than unknown.
- A THIS_RUN reference row becomes a real ✓/✗, value shown resolved:
  `${id0} → a1b2c3d4…`. It re-judges live per keystroke like every other row.
- When a new run lands and V9/S2's `rebindDiffWindows` swaps `thisRunWire`, the scope
  snapshot swaps with it — wire and scope travel as one unit or the judgments lie.
- **The variables strip**: a collapsible strip in the diff window (and the same
  component in the scenario editor), one chip per variable in the existing
  `VarBadge`/`varColorMap` idiom — `●id0 = a1b2c3…`, minted-at-step, referenced-by.
  Clicking a chip highlights every row whose expected or actual value contains it —
  the fastest answer to "why did this bind to the wrong message".
- **Free lint:** `ScenarioAnnotations` already computes minted vs referenced names. A
  name referenced but never minted (a typo — today silently left literal on the wire)
  gets a warning chip in the strip and on the step badge. Authoring-time only; the
  engine's leave-literal behaviour is unchanged.

## Part 3 — `reference` joins the matcher dropdown

With a resolver present, the ban's reason is gone. Conditionally lift it:

- On a THIS_RUN reconcile, the dropdown gains **reference**. It is not a free-text
  field: it is a **picker over the scope's names**, each shown with its run value —
  `id0 (= a1b2c3…)` — so the name cannot be mistyped and the row re-judges the moment
  it is picked.
- On a non-THIS_RUN surface the entry stays absent, same reason as today.
- **Detection-driven gutter offer:** when a row's actual equals some variable's value,
  the gutter offers **Track `${id0}`** directly — one click for the common case, the
  reconcile-time analogue of capture's echo detection.

## Part 4 — Defining a variable is a capture

Two sources, one rule: the definition is written *where the value originates*, and the
reference is written where it is used. Both may be steps other than the one being
reconciled — the draft workspace already spans the scenario, and each such edit is one
undoable command on the existing stack (multi-step edit, single ⌘Z).

### 4a. From a value we sent — mint at the Send

Picking "reference the value sent at step N" (offered when the row's actual equals an
earlier send's value for some tag) rewrites that send's raw at that tag:
`55=EURUSD` → `55=${sym = "EURUSD"}`. The bytes on the wire are identical — the
assignment evaluates to the same literal — but the value now has a name, and this row
becomes `Reference("${sym}")`. A value that was already an expression is wrapped the
same way (`${LocalDateTime.now()}` → `${ts = LocalDateTime.now()}`). Default name from
the dictionary's field name, editable at mint time.

### 4b. From a value the venue chose — `bindAs` on the expectation row

The engine primitive, and the closure of the receive→send gap:

- **Model:** `FieldExpectation` gains additive `bindAs: String? = null`. Codec writes
  it only when set (invariant 5); old files byte-identical.
- **Runner:** the evaluator stays pure. After a message binds and evaluation runs, the
  *runner* walks rows with `bindAs` and writes the bound message's actual value (the
  row's occurrence, per the positional model) into scope. A row can assert `Present`
  *and* capture — assert and bind are orthogonal.
- **Failure stance:** `bindAs` on a row whose tag is absent from the bound message
  leaves the name unminted; a later `${name}` stays literal and the send lint
  (Part 2's warning chip) names it. No silent empty-string mint.
- **UI:** a right-side row's gutter offers **Capture as `${…}`** (names it, default
  from the field name); in the editor the row carries the ● mint badge exactly as
  send-side mints do. It is *not* an entry in the matcher dropdown — per the rule, it
  is not a matcher.
- **Later, not now:** capture can auto-seed received→sent echoes (detect a received
  value reappearing in a later send, emit `bindAs` + reference) the way it already
  auto-seeds sent→received. Deferred until the manual path proves the shape.

### Decided: row-level definitions, no scenario-level Variables table

Definitions live where they are used — a mint on the send that first carries the value,
a `bindAs` on the expect that receives it — matching the capture-first philosophy. A
declarations table is a *parameterization* feature (symbol/account per run), a different
proposal if wanted.

---

## Delivery slices

1. **Visibility** — scope into report + VM lifecycle + control surface; resolver into
   the reconcile session (provenance-gated); variables strip; never-minted lint.
   No engine semantics change.
2. **Reference authoring** — dropdown entry + picker; Track-`${id0}` gutter offer.
3. **Capture** — `bindAs` model/runner/codec; mint-at-send rewrite; Capture-as gutter
   offer; cross-step undo commands.

Each slice gates on full jvmTest + a live control-surface run against `tools/fake-venue`
(a scenario whose echo the venue answers, reconciled with variables visible).

## Delivery deviations (recorded as built, 2026-07-16)

All three slices shipped same-day (commits `6689bb4`, `9eb237c`, slice 3 following). Two deliberate
deviations from the text above:

1. **Part 4a's "reference the value sent at step N" gutter offer was not built as a cross-step diff
   operation.** The diff's whole safety promise is the snapshot stack — *nothing is written until
   Save, and ⌘Z restores exactly* — and the stack snapshots **expectations**. A gutter offer that
   also rewrites a *Send step's raw* would either bypass the stack (an edit ⌘Z cannot take back) or
   demand the stack learn to snapshot whole scenarios. Instead: the **editor's send grid** gained a
   per-field **● mint** button (`55=EURUSD` → `55=${sym = "EURUSD"}`, same bytes, editor-owned like
   every other editor edit), and after the next run the diff's **$ Track** offer (slice 2) completes
   the wiring one-click, because the mint is now in the scope. Same outcome, two honest surfaces.
2. **The capture offer (`↧`) is green-rows-only.** A failing row's business is repair; wiring a
   correlation through a value the step disputes would capture a value nobody has agreed is right —
   and venue-assigned ids are Presence-seeded, so the rows that matter are green.

Also settled during delivery: the track offer appears on **green** rows too (an `exact` pinning a
minted id is the first-replay landmine — green today because it was minted today); reference-row
judging is **per-row** (a scope that answers one row and not another judges the first and leaves the
second amber, replacing the any-reference-resolves flag); and `reseed` carries `bindAs` across the
way it already carries reference matchers.

## File touch map (orientation, not a contract)

| Area | Files |
|---|---|
| Report/scope | `ScenarioRunner.kt`, `ScenarioReport.kt`, `FixMessageTemplate.kt` (mint provenance) |
| VM lifecycle | `FixMessageViewModel.kt` (`newReconcileSession`, `dismissRunResult`, rebind) |
| Diff UI | `DiffSurface.kt` (dropdown, gutter offers, strip), `ReconcileSession.kt`, `DiffModelBuilder.kt` |
| Model/codec | `Scenario.kt` (`FieldExpectation.bindAs`), `ScenarioCodec.kt`, `MatcherCodec.kt` |
| Annotations/lint | `ScenarioAnnotations.kt`, `ScenarioUi.kt` (strip component) |
| Control | `ControlServer.kt`, `tools/fixtool-mcp/index.mjs`, `AUTOMATION.md`, `help.html` §12 |
