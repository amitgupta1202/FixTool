# Handoff — repeatable scenarios, after Phase 3 + two full reviews

**Status:** Phases 0–3 are done, reviewed twice, and committed to `main`. 1055 tests green, tree clean.
The release is still HELD (Amit's rule: nothing ships without assertions).

**Amit has driven the reconcile view by hand for the first time and found issues. They are listed
under "OPEN — what Amit found" below and are the first thing to work on.**

---

## READ FIRST, IN THIS ORDER

1. `docs/scenario-assertion-model.md` — the spec. It is marked **implemented**, and open questions 4
   and 5 are answered in it. It supersedes `fixtool-assert-spec.md` (now a stub) and the assertion
   parts of `repeatable-scenarios-proposal.md` (now pointers).
2. The diff-viewer mockup: https://claude.ai/code/artifact/a3162995-9e9e-4713-8005-b1b6b06f1a50
   **Open it with WebFetch before touching the reconcile view.** A previous session skipped it, built
   the view from the spec's ASCII sketch, and Amit rejected it on sight. There is no mockup for the
   capture-review screen or the expectation builder — Amit confirmed this. If unsure, ask.
3. `git log 79bf7ed..HEAD` — the whole sequence-model implementation.

---

## THE MODEL (do not re-litigate — it cost five review rounds)

- An expectation is an **ordered list of rows** `{tag, matcher}`. Position is the address. No path, no
  group, no entry, no identity.
- **Pairing:** the *k*-th row for tag `T` asserts the *k*-th **occurrence** of `T`. Pairing looks at the
  tag and the position, **never** at whether the matcher would pass. This is the one thing that must not
  be made cleverer — see below.
- **OPEN** = the expectation must be a **subsequence** of the reply. **STRICT** = same tags, same count,
  same order, plus unexpected tags fail.
- `absent` rows take no part in pairing.
- The session envelope (8, 9, 10, 34, 49, 52, 56, 369) is never seeded and never a STRICT extra — but a
  row an author *explicitly writes* on one is evaluated normally.
- `wireRaw` is the venue's actual bytes **or null**. Never `quickfix.Message.toString()` for an incoming
  message. When it is null the engine **refuses to judge** and says the fault is FixTool's, not the venue's.

### Two principles. Every serious defect in this project has violated one.

1. **Exactly one thing decides any given question.** The recurring cause of every bad bug.
2. **A false red is survivable. A false green is not.**

---

## WHERE THE WORK STANDS

### Phase 3 (done)
- **3a — the wire-order hole (open question 5).** `toString()` doesn't just sort the body ascending; it
  **relocates every repeating group to the end**. `Message.toRawString()` had the venue's real bytes all
  along and this repo never called it, which made `RawMessageCapturingLogFactory` a second, leakier answer
  to a question QuickFIX had already answered. Deleted. Incoming reads `toRawString()`.
- **3b — capture review.** The preview was still collapsing by tag (`distinctBy` kept the *first*
  occurrence, `associateBy` kept the *last*), so it showed 3 rows where 6 assertions were seeded and paired
  entry 1's value with entry 2's matcher. Now per-occurrence, driven off `ExpectationEvaluator.align`.
  Also fixed a delimiter bug: `parseFixMessage` asked `contains('|')` **before** `contains(SOH)`, which
  shreds any message with a pipe inside a value.
- **3c — docs + MCP.** Both MCP surfaces advertised `path?` in the save schema, which the codec *rejects*.
  `syntax.md`'s flagship example violated its own subsequence rule. `help.html` still taught the deleted
  group-path model and the quick-fix chips Phase 1 removed. All fixed. **Open questions 4 and 5 answered
  in the spec.**
- **3d — live verify.** Done against a purpose-built hostile venue (see below).

### The two reviews (both found real false greens)
- Full-implementation review (`max`, 63 agents): **15 confirmed defects**, all fixed.
- Re-review of the fixes (`high`, 36 agents): **10 more**, 9 fixed, 1 knowingly declined.

The reconcile view was the epicentre of both. See "What keeps going wrong" below.

---

## OPEN — WHAT AMIT FOUND (fill this in — it is the top of the queue)

Amit drove the reconcile view by hand (first time any human has) using the staged demo below, and found
issues. **They have not been captured, diagnosed or fixed.** Get them from him and start here.

### 1. Running a scenario is a dead end — no route from the failure to the reconcile view

The run happens in a **pop-up**. The pop-up says the scenario failed on its last run, and then offers
**no way to get to the reconcile view**. You have to go back to the main window and find the failed
message yourself.

This is the whole point of the feature and it is unreachable from where the failure is reported. The
deep-link exists (`FixMessageViewModel.openScenarioEditorForFailure`) — the run pop-up just does not
offer it. It should: "Reconcile assertions →" belongs on the failed step, right there.

### 2. Unclean UI — no row spacing in the step editor

See the "Binds to" / match-constraint rows in the Expect step editor: the tag / name / value row is
cramped, with no vertical rhythm. Screenshot on Amit's desktop
(`Screenshot 2026-07-14 at 06.19.03.png`). General polish pass needed on `ScenarioEditor`'s
`MatchEditor` rows.

### 3. Opening the Scenarios workbench moves the parent window

Clicking the repeatable-scenarios button **shifts the main window**. Annoying, and it should not
happen. Likely the new `Window(...)` in `ScenarioWorkbenchWindow` (`ScenarioWorkbench.kt:52`) taking
focus/position in a way that disturbs the parent. Needs a `WindowState` that does not reposition the
owner.

### 4. NO WAY TO MOVE ROWS — the manual block arrows from the mockup were never built

**This is the substantive one.**

Amit: *"I don't see the ability to move rows in the reconcile view. The mockup had moving the group,
which we can't do as I understand — then we should have a way to move the rows, otherwise how will I
fix it?"*

Half right, and the half that is wrong matters:

- **Entry (block) moves ARE supported and ARE the right unit.** `Accept new order` *is* an entry move.
  The mockup also has manual **`move entry ↑ ↓`** arrows on the *block header*, for the case where the
  diff aligns wrongly (two entries that genuinely swapped *and* changed a value are ambiguous). Those
  arrows were **never implemented** — `MovedBlockHeader` (`ReconcileView.kt:533`) renders only the
  button. **That is the gap. Build them, on the block.**
- **Per-ROW arrows must NOT be built.** The spec's own reasoning: moving the second `452` above the
  first silently swaps which occurrence each row checks, so a row reading "the clearing firm's role is
  4" comes to mean "the executing firm's" — while still saying `452 exact 4` on screen. That is the
  assert-the-wrong-field false green this model exists to eliminate, re-entered through the editor. It
  also lets a user build an order no real message has (`448, 447, 448, 452, 452`).

**Arrows on the block, never on the row.**

**Second question hiding inside this one, and it needs answering:** in DEMO B (the role swap) there is
deliberately **no block** — a role swap is a regression, not a reorder — so there is nothing to arrow,
and that is correct. The author fixes it with **Accept actual** on the two `448` rows, knowingly
re-baselining to the venue's new behaviour. If that was not obvious from the UI, that is a real
usability finding: **the view tells you what is wrong but not what to do about it when no move is
offered.**

**UNVERIFIED — check this first:** it is not known whether DEMO A actually rendered a bracketed party
block with `Accept new order`. If it did not, that is a **bug** (the block detection failing in the
real app), not a missing feature. `plan()` should offer a move for DEMO A — the entries genuinely
swapped. Reproduce and confirm before building anything.

---

## THE LIVE DEMO — how to reproduce Amit's session

```bash
# 1. the hostile venue (see tools/fake-venue/README.md — the built-in demo acceptor CANNOT do this)
echo golden > /tmp/fake_venue_mode
python3 tools/fake-venue/fake_venue.py &          # listens on 127.0.0.1:19999

# 2. the app
FIXTOOL_CONTROL_PORT=8799 ./gradlew :composeApp:run --quiet &

# 3. profile + connect + send + capture two scenarios from the golden reply
B=http://127.0.0.1:8799
curl -s -XPOST $B/profiles -d '{"name":"Venue (reconcile demo)","config":{"senderCompID":"FIXTOOL","targetCompID":"FAKE_VENUE","host":"127.0.0.1","port":"19999","beginString":"FIX.4.4","heartBtInt":"30","resetOnLogon":true}}'
curl -s -XPOST $B/connect -d '{"profile":"Venue (reconcile demo)"}'
curl -s -XPOST $B/send -d '{"session":"Venue (reconcile demo)","raw":"8=FIX.4.4|35=D|11=DEMO-1|55=EUR/USD|54=1|38=1000000|40=1|60=20260101-00:00:00|"}'
curl -s -XPOST $B/scenarios/capture -d '{"name":"DEMO A","sessions":["Venue (reconcile demo)"]}'
curl -s -XPOST $B/scenarios/capture -d '{"name":"DEMO B","sessions":["Venue (reconcile demo)"]}'

# 4. make them fail, in two different ways
echo shape > /tmp/fake_venue_mode  ; curl -s -XPOST $B/scenarios/run -d '{"id":"<A>"}'
echo swap  > /tmp/fake_venue_mode  ; curl -s -XPOST $B/scenarios/run -d '{"id":"<B>"}'
curl -s -XPOST $B/panel -d '{"panel":"scenarios"}'
```

- **DEMO A (`shape`)** — the party **entries swap places** (benign; FIRMA still holds role 1) + a real
  `151` regression + a tag added + a tag dropped. Six red rows. *Should* offer one bracketed
  **Accept new order** for the party, and Accept-actual/Loosen/Drop on `151`.
- **DEMO B (`swap`)** — the two firms **swap ROLES**. FIRMB now holds role 1. Nothing moved. Two red
  `448` rows. **Must offer NO move at all.** If a move is offered here, a false green is back.

**Do not use the built-in demo acceptor to test wire order or reordering.** It is QuickFIX-based, so its
wire bytes already come out ascending-with-groups-at-the-end — byte-identical to `toString()`. Against it,
the broken code and the fixed code are indistinguishable. That is why `tools/fake-venue/` exists.

---

## THE RECONCILE VIEW — the rules, and why they are what they are

`ScenarioReconcile.plan()` decides whether a failing step can be safely made green. It has been wrong
**three times**, each time as a false green, each time caught by a review or by building the demo.

A reply is a **reorder** (safe, one click) rather than a **regression** only if one of these holds:

1. **`placeByOccurrence`** — the *k*-th row for a tag lands on the *k*-th occurrence of it. Structurally
   cannot re-aim. Covers a venue reshaping an entry internally (`447` before `448`).
   **Guard:** a row that ALREADY PASSES keeps the field *the engine* has it checking. The greedy cursor and
   the occurrence rule can disagree, and where they do, this silently re-aims a green row onto a different
   party.
2. **`placeByMovedEntry`** — a contiguous block appears **VERBATIM** in the reply (same tags, **same
   values**, contiguous, in order). The view's own label says exactly this; the code did not enforce the
   middle clause.
   **Guard 1:** a one-row block only when its tag occurs once (else it is just row-by-row re-aiming).
   **Guard 2 — THE SOLID SPAN RULE:** the moved rows must form a solid span with **no stationary row
   stranded inside it**. An entry that really moved takes all of itself. Without this, a *fragment* moves:
   `448+447` crosses to the other entry while the `452` stays behind (it still passes), and the regression
   is greened.

**Never re-aim a failing row individually onto whatever field its matcher happens to satisfy.** That is the
matcher-driven pairing `align()` forbids, and it cannot tell a benign entry reorder from a role swap.

Also in the view:
- `acceptActual` **re-seeds** (keeps a numeric numeric, with its tolerance) and is **not offered** on
  `temporal` or `reference` rows — accepting either pins the scenario to a moment or an id that will never
  recur, and the author then deletes the assertion to make the red stop. `ScenarioReconcile.canAcceptActual`
  owns that rule so the button and the engine cannot differ.
- "Accept every shape change" **never bulk-drops a repeated tag** (drop takes the whole tag, so it would
  delete the value-failing sibling and every matcher the author chose), and only asserts unmentioned tags in
  **STRICT** (in OPEN an unmentioned tag is the whole point of OPEN).
- Staging is keyed on **`stepKey` (the step's index)** — not on `expectation` (every fix replaces it) and
  not on `crumb` (built from the editable session and messageType).

---

## WHAT KEEPS GOING WRONG — read this before writing a test

**Five times this session the root cause was the same: the fixtures dodged the hard case.**

- No fixture had a **pipe inside a FIX value** → a truncated `58=Rejected|insufficient margin` went unseen.
- No fixture set **`wireRaw`** → a shape production never produces.
- No fixture had **two different firms** in a party group → the role swap was undetectable.
- No fixture fed **`onChange` back** the way `ScenarioEditor` does → a completely dead staging mechanism
  survived seven passing tests.
- My own party fixture had **4 rows** (`448`,`452`), the real message has **6** (`448`,`447`,`452`) → the
  fragment bug only appeared when the demo was built.

A green suite has proved nothing here. **Confirm every bug reproduces on the old code before claiming a
fix**, and mutation-check your guards (delete the guard, watch the test fail).

`AlignmentPropertiesTest` is the model for this: it enumerates ~465k (message, expectation) pairs and checks
the pairing invariants against an independently written oracle. Making `align()` matcher-aware — the
forbidden false green — is caught by 3 of its 6 tests, and **not** by the two "passes its own golden" tests,
because a matcher-aware pairing still passes its own golden. That is the blind spot every example-based test
on this had.

---

## KNOWN-RED / KNOWN-FLAKY (not yours)

- **ktlint and detekt are red on `main`, pre-existing.** Keep YOUR files at or below baseline:
  `./gradlew :composeApp:ktlintJvmMainSourceSetCheck :composeApp:ktlintJvmTestSourceSetCheck`, then compare
  per-file counts against `git stash`-ed HEAD. Do not try to fix the baseline.
- `ControlServerIntegrationTest` and `MultiSessionConnectIntegrationTest` are flaky under the full suite and
  pass in isolation.
- **Do not run Gradle while the app is running from `composeApp/build/`** — it recompiles the class files
  out from under the live process and the app throws `NoClassDefFoundError`. (This is what caused the
  "Unexpected Error" popup Amit saw; it is not a bug in FixTool.)
- The review workflow's agents write scratch probe files into `composeApp/src/jvmTest/` (`Zz*Test.kt`,
  `*ProbeTest.kt`). Delete them after a review; they break the build.

---

## HOUSE RULES

- Conventional commit prefixes. **NO** `Co-Authored-By`, **NO** `Claude-Session` trailers.
- **Never weigh dev effort** in a recommendation. Rank by defect severity and impact.
- Implement one thing, run `/code-review high` on the diff, fix what it finds, commit, stop. The reviews have
  found a real false green **every single time**, including in the fixes for the previous review's findings.
- Commit directly to `main` by default.

---

## THE ONE-LINE SUMMARY

The engine is sound (proven exhaustively). Everything that has gone wrong has been in the layers around it,
and the reconcile view — where a tired engineer clicks buttons to turn a red build green — has produced a
false green in every single review. Attack it accordingly.
