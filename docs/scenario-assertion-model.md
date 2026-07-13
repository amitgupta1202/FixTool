# Scenario assertions: the sequence model

**Status:** implemented. Replaces the group-path assertion model in
[`fixtool-assert-spec.md`](./fixtool-assert-spec.md) and
[`repeatable-scenarios-proposal.md`](./repeatable-scenarios-proposal.md).

---

## Why this exists

A scenario captures a response and, on replay, compares what came back against what was
captured. For a top-level field that comparison is obvious: tag 39 against tag 39. For a field
inside a repeating group it is not, because the message carries the tag more than once:

```
35=8 | 11=ORD-1 | 39=2 |
453=2 |
  448=FIRMA | 447=D | 452=1 |     <- executing firm
  448=FIRMA | 447=D | 452=4 |     <- clearing firm
```

An assertion that says *"452 must be 1"* has not said **which** 452. The engine therefore needs a
rule that pairs an assertion with the field it refers to.

The current model answers this by **naming the entry**: `GroupPath(453, 448, "FIRMA")` — *"the
party entry whose PartyID is FIRMA"*. That answer is wrong, and it is wrong on ordinary messages:
in the example above **both** entries have `448=FIRMA`, because a firm can act in two roles. The
identity does not identify. Everything built on top of it — de-duplicating entries, numbering them
by occurrence, refusing to assert the ones we cannot tell apart — is an attempt to repair a
foundation that does not hold, and each repair has produced a scenario that either checked the
wrong entry and passed, or refused to check an entry it could have.

This document proposes dropping the concept of a group entry from the assertion engine entirely.

---

## The model

**A FIX message is an ordered list of `tag=value`.** Repeating groups are a convention on top of
that list, not a structure the assertion engine needs to understand.

**An expectation is an ordered list of rows**, in the order the fields were captured. Each row is a
tag and a matcher. There is no path, no group, no entry.

**Pairing rule:** the *k*-th row for tag `T` refers to the *k*-th occurrence of tag `T` in the
message under test.

That is the whole model. The example above becomes:

| # | tag | matcher |
|---|-----|---------|
| 1 | 39  | exact `2` |
| 2 | 453 | exact `2` |
| 3 | 448 | exact `FIRMA` |
| 4 | 447 | exact `D` |
| 5 | 452 | exact `1` |
| 6 | 448 | exact `FIRMA` |
| 7 | 447 | exact `D` |
| 8 | 452 | exact `4` |

Row 5 asserts the first `452`; row 8 asserts the second. Two roles of the same firm, correctly and
separately asserted, with no notion of an entry anywhere in the engine.

The **dictionary is still used** — for field names, for seeding a matcher from a field's type
(`UTCTIMESTAMP` → temporal, `PRICE` → numeric), for enum descriptions, and for building messages.
It is simply no longer used to decide what an assertion refers to.

---

## The two modes

### STRICT — "the venue sent me the same message"

The reply must carry **the same tags, the same number of times, in the same order**, and every
value must satisfy its matcher. A tag that appears, disappears, or moves is a failure.

Order is enforced. A venue that reorders its group entries, or its body fields, fails a STRICT
step. That is deliberate: STRICT is for the flows where a change in shape *is* a regression, and
where being told about it is the point. The [reconcile view](#reconciling-a-failure) makes
accepting a legitimate reorder a single click, so a false red costs a moment and cannot be mistaken
for a pass.

### OPEN — "the venue said at least this much, in the order it said it"

**The expectation must be a subsequence of the reply.** The listed rows must appear in the order they
are listed, with anything else allowed in between. Any tag the reply carries that the expectation does
not mention is ignored — a venue adding an optional field does not break the scenario.

Given a reply carrying tags `1, 2, 3, 4, 5` in that order:

| Expectation | Result |
|---|---|
| `1, 3, 5` | ✅ a subsequence — `2` and `4` are simply not mentioned |
| `1, 2, 3` | ✅ |
| `1, 2, 4` | ✅ |
| `1, 6, 3` | ❌ there is no `6` in the reply — `missing` |
| `1, 3, 2` | ❌ the reply puts `2` before `3` — `moved` |

So OPEN is tolerant about what it does **not** mention, and strict about what it does.

This is a **stronger** rule than the pairing rule alone, and the difference is deliberate. Pairing on
its own (*k*-th row for `T` ↔ *k*-th occurrence of `T`, order across different tags unchecked) already
catches the case this model was built for — two swapped party entries fail either way, because the
first `452` row still lands on the first `452` in the reply. What the subsequence rule adds is
everything else about the message's shape: a reply that keeps every per-tag sequence intact but
reshuffles the fields between them (`448 448 452 452` where the venue used to send
`448 452 448 452`) is a message no venue sent, and OPEN says so.

The price is a **false red on a hand-authored expectation whose rows are not in wire order**. If the
venue sends `37` before `11` and the author lists `11` before `37`, the step fails with `moved`. That
is the `1, 3, 2` row of the table, and it is the rule working. Capture always seeds rows in wire order,
so a captured scenario is a subsequence of its own golden by construction; a hand-written one, or one
an agent composes over `fixtool_assert`, must follow the venue's order — and the failure says so, in
those words, on the row.

OPEN also supports the negative assertion: a row whose matcher is `absent` asserts the tag does
**not** appear. Absent rows take no part in pairing; they are checked against the whole message.

### Which failures each mode can produce

| Situation | STRICT | OPEN |
|---|---|---|
| Asserted value differs | fail | fail |
| Asserted tag missing from the reply | fail | fail |
| Reply carries a tag the expectation does not mention | fail | pass |
| Reply repeats an asserted tag more times than captured | fail | pass (extra occurrences unchecked) |
| Group entries reordered | fail | fail |
| Two asserted rows swap relative order | fail | fail (subsequence) |
| An unasserted tag moves | fail | pass |
| Tag asserted `absent` is present | fail | fail |

---

## What this model cannot do

It cannot silently assert the wrong field.

That is the property worth paying for. If the venue reorders two party entries, the *k*-th
occurrence of `452` no longer holds what it held at capture, and the comparison **fails with a
diff**. It does not quietly bind to a different entry and report green. Every previous design
could do exactly that, and did.

The cost is a **false red on a benign reorder** — a message that is semantically the same but
shaped differently. A false red is visible, diffable, and one click from fixed. A false green is
invisible, and it is the only outcome a testing tool must never produce. We take the trade.

---

## Reconciling a failure

A failed step is a **diff between two lists**: the expectation (captured) and the message that
actually arrived. Align them the way `git diff` aligns two files — longest common subsequence over
`(tag, matcher-satisfied)` — and every kind of failure falls out of the same view:

```
  expected (captured)              actual (this run)
  ────────────────────────────────────────────────────────────
  39   exact 2                     39   = 2                     ✓
  453  exact 2                     453  = 2                     ✓
  448  exact FIRMA                 448  = FIRMA                 ✓
  447  exact D                     447  = D                     ✓
- 452  exact 1                     452  = 4                     ✗ value
+                                  2376 = Y                     ✗ unexpected
  448  exact FIRMA                 448  = FIRMA                 ✓
  447  exact D                     447  = D                     ✓
- 452  exact 4                     452  = 1                     ✗ value  (moved?)
- 58   exact ok                    —                            ✗ missing
```

Each row offers the fix that fits it — the same batched, nothing-is-written-until-Save discipline
the current quick-fix chips already use:

| Row state | Offered actions |
|---|---|
| Value differs | **Accept actual** (rewrite the matcher to the new value) · **Loosen** (presence / one-of / numeric tolerance / regex) · **Drop** the row |
| Reply has a tag we do not assert | **Add assertion** (seeded from its type, inserted at the right position) · **Ignore** (leave unasserted) |
| Asserted tag missing | **Assert absent** · **Drop** the row |
| Same values, different order | **Accept new order** — reorders the expectation's rows to match the reply |
| Whole step wrong | **Re-seed from this message** (recapture the step against what actually arrived) |

"Accept new order" is what makes order-sensitivity affordable: a venue that legitimately reorders is
a one-click acknowledgement, not a redesign of the scenario. The view is close to the message editor
in feel — two columns, per-row actions, an explicit Save — so it reads as an editing surface rather
than a report.

### Why a move is an entry-level action, not per-tag arrows

The obvious control for "this row is in the wrong place" is an up/down arrow per row. It should not
be the primary one, for three reasons:

1. **It is the wrong unit.** A venue does not move `PartyRole`; it moves a *party* — the delimiter
   and everything under it, three to six tags travelling together. Fixing that with per-row arrows is
   several clicks to express one fact, and each click leaves the expectation in a state that is
   momentarily wrong.
2. **It can produce an order no message has.** Arrows let a user interleave rows freely:
   `448, 447, 448, 452, 452`. That is not a FIX message, it is not what any venue sent, and the tool
   would have helped them build it.
3. **It can silently re-aim an assertion.** Move the second `452` above the first and the two rows
   swap which occurrence they check — so a row that read *"the clearing firm's role is 4"* now means
   *"the executing firm's role is 4"*. It still says `452 exact 4` on screen. That is the
   assert-the-wrong-field failure this model was designed to eliminate, walked back in through the
   editor.

So the diff detects **moved runs** — a contiguous block whose tags and values match but whose
position changed — brackets them as one unit, and offers a single **Accept new order** that rewrites
the expectation's row order to match the reply exactly. Atomic, correct by construction, and it
cannot invent an ordering.

Manual arrows still exist, on the **block**, for the case the diff aligns wrongly (two entries that
genuinely swapped *and* changed a value can be ambiguous to align). They move an entry, never a
loose tag. And every hand edit is checked live against the failing message — the row shows whether
it *would now pass* — so a user cannot save an expectation they have quietly broken.

---

### Where fixing happens — and where it does not

**The message detail viewer diagnoses. The diff view authors.** There is exactly one surface that can
change an assertion.

The viewer keeps everything it does today: messages tinted red/green after a run, failing tag rows in
red with expected against actual, so a tester scanning the session sees what broke without going
anywhere. It gains one action — **Reconcile assertions →**, opening the diff view for that step,
scrolled to the row that was clicked, as a side panel rather than a separate window.

It loses the ability to *change* anything: no quick-fix chips, no accept/loosen/drop, no pending
edits, no Save. Two reasons, and the first is structural:

1. **The viewer renders the message that arrived, so it has no row for anything that did not.** A tag
   the venue stopped sending — the most ordinary way a venue regresses — has nothing to click. A
   moved entry looks perfectly fine tag by tag: every value matches, nothing is red, and the step
   still failed. The viewer can only ever fix value mismatches, which makes it permanently the
   incomplete surface, and teaches users that fixing lives in two places.
2. **Two editing surfaces are two chances to rewrite the wrong assertion.** That is not hypothetical:
   the quick-fix path shipped exactly those bugs — a pending-edit map keyed globally by `(tag, path)`
   that wrote one message's edit into another message's step, and entry matching by identity alone
   that rewrote the second party's assertion when the first party's row was clicked.

Removing it deletes `AssertionQuickFixes`, `_pendingAssertionEdits` and its save/discard plumbing, the
chips and `resultsForGroupEntry` in `MessageDetailPanel`, and both of those defects. The existing
deep-link from a failed message to the step that failed stays — it points at the diff view now.

## Wire format

The expectation loses `path` and gains nothing:

```json
{
  "messageType": "8",
  "mode": "strict",
  "fields": [
    { "tag": 39,  "matcher": { "type": "exact", "value": "2" } },
    { "tag": 453, "matcher": { "type": "exact", "value": "2" } },
    { "tag": 448, "matcher": { "type": "exact", "value": "FIRMA" } },
    { "tag": 447, "matcher": { "type": "exact", "value": "D" } },
    { "tag": 452, "matcher": { "type": "exact", "value": "1" } },
    { "tag": 448, "matcher": { "type": "exact", "value": "FIRMA" } },
    { "tag": 447, "matcher": { "type": "exact", "value": "D" } },
    { "tag": 452, "matcher": { "type": "exact", "value": "4" } },
    { "tag": 58,  "matcher": { "type": "absent" } }
  ]
}
```

Rows are stored in captured wire order; that order *is* the assertion in STRICT mode. The matcher
vocabulary is unchanged — `exact`, `presence`, `absent`, `regex`, `oneOf`, `numeric`, `temporal`,
`reference`.

A result row reports its position, so a failure is addressable:

```json
{ "index": 4, "tag": 452, "occurrence": 1, "matcher": "exact 1",
  "expected": "1", "actual": "4", "passed": false, "status": "value" }
```

`status` is one of `ok` · `value` · `missing` · `unexpected` · `moved` · `invalid`.

`invalid` is the row's own fault rather than the venue's — an uncompilable regex. It is reported as a
failed row quoting the compiler's reason, never as an exception: the codec carries a bad pattern
verbatim in both directions, because refusing to write it failed the save of the entire scenario over
one half-typed character class, and refusing to read it produced a file that could never be loaded back.
The pattern is judged where it can be acted on — live in the editor, and on the failing row of a run.

### Tags that are never asserted — and the ones asserted only for presence

Orthogonal to all of the above, and there are **two** lists, because "its value belongs to this
environment" and "it should not be asserted at all" are different claims. Collapsing them is how the
routing tags came to be dropped from the engine entirely, which silently deleted routing coverage.

**The envelope — not seeded, and never an unexpected extra in STRICT.** `BeginString(8)`,
`BodyLength(9)`, `CheckSum(10)`, `MsgSeqNum(34)`, `SendingTime(52)`, `SenderCompID(49)`,
`TargetCompID(56)`, `LastMsgSeqNumProcessed(369)`. They are on every message by definition and differ
on every environment: a row for `BodyLength` is noise that cannot fail, and a row for `SenderCompID` is
a scenario that only runs where it was captured. A capture reports them under `notAsserted`.

**The address — seeded as `Presence`.** `SenderSubID(50)`, `TargetSubID(57)`, `OnBehalfOfCompID(115)`,
`DeliverToCompID(128)`, the LocationIDs `142`/`143`/`144`/`145`, and `OrigSendingTime(122)`. Their
*presence* is the venue's behaviour; their *value* is this environment's or this moment's. Asserting
that a routed ExecutionReport still carries a `TargetSubID` is portable and worth checking; asserting
that it carries `DESK7` passes only on the desk it was captured on.

Presence is the cut between two mistakes we shipped in turn. Seeding them **exact** made every captured
scenario non-portable — red on QA on every step. **Omitting** them (the obvious repair) was worse: not
seeded *and* excluded from STRICT's extras, a venue could deliver to the wrong desk, or stop populating
`DeliverToCompID`, and every scenario would still report green. Presence keeps both properties: the row
is portable, and it is *there* — visible, with its captured value beside it — so a routing test tightens
it to `exact` from the dropdown, and because it is listed, STRICT still reports an addressing tag that
appears when none was captured.

`PossDupFlag(43)`, `PossResend(97)` are neither: they are behaviour, identical on every environment, and
stay `exact`. `OrigSendingTime(122)` sits with the address because it is a *moment*, not a value — typed
`UTCTIMESTAMP` it seeded as "~now ±60s", and a resend's OrigSendingTime is hours old by definition, so
every resend scenario went red on every run.

---

## What this deletes

- `GroupPath`, and every consumer that has to thread it correctly (the codec, the report JSON, the
  builder's path editor, the detail panel's quick-fix matching).
- `FixStructure`'s entry-boundary heuristics, `ambiguousGroups`, `UnassertableGroup`,
  `ExpectationSeeder.seedAll`'s refusal path, and the capture-review notice that explains it.
- The divergence between `RawMessageView` (structure from `FixStructure`) and `FixMessageView`
  (structure from QuickFIX). Neither has to decide what an entry is, so they cannot disagree — the
  seam that produced the STRICT-fails-on-nested-groups defect stops existing.
- Every defect three review rounds found in that machinery.
- `AssertionQuickFixes`, the ViewModel's pending-edit map, and the message viewer's quick-fix chips —
  fixing moves to the diff view, the only surface that can see the whole failure.

The seeder becomes: walk the captured fields in order, drop the never-asserted tags, seed a matcher
per field from its dictionary type. No structure walk at all.

---

## Migration

Nothing has been released with the group-path format, so there is nothing in the wild to migrate.
Scenarios captured against a dev build must be re-captured; a scenario file carrying `"path"` or
`"occurrence"` should be **rejected at load with a clear message** rather than silently dropping
the key — a scenario that quietly means something different after an upgrade is the failure mode
this whole exercise exists to remove.

---

## Open questions

1. ~~Should OPEN enforce relative order?~~ **Decided: yes — the expectation must be a subsequence of
   the reply.** Given a reply of `1,2,3,4,5`: `1,3,5` and `1,2,4` hold; `1,6,3` does not (no `6`), and
   neither does `1,3,2` (the reply puts `2` first). The accepted cost is a false red on a hand-authored
   expectation whose rows are not in wire order; the failure row says so and names the fix.
2. ~~Repeated occurrences beyond what was captured.~~ **Decided: the count tag is enough.** A valid FIX
   group always carries its `NoXXX` count, the seeder seeds it `exact`, so a reply with five parties
   where four were captured already fails on `453` (expected 4, actual 5). No "closed" flag, no per-row
   occurrence bound — nothing new to be wrong about. If an author loosens or drops the count row, the
   diff view shows they did.
3. ~~Reference matchers across occurrences.~~ **Decided: references stay scalar.** No current flow needs
   "the second leg's ClOrdID echoes the second order". It stays additive: a later `${out.D.11#2}` is a
   resolver change only, with no change to the scenario format, so nothing captured now needs recapture.
4. ~~Re-seed granularity.~~ **Decided: Accept-actual *is* the per-row re-seed — once it stops flattening
   the row.** It did not cover the case; it was actively destroying it. `acceptActual` wrote `Exact(actual)`
   over *whatever matcher the row had*, so one click on a numeric row threw away its tolerance and its
   format-robustness: `Numeric(500000, ±0)` parses both sides as numbers and survives a venue that starts
   sending `500000.00`, and `Exact("500000")` goes red on it. The seeder chose numeric for that field on
   purpose and the reconcile view was quietly un-choosing it.

   It re-seeds now, keeping the matcher's kind and moving only its baseline. And on two kinds there is
   nothing to accept, so the button is not drawn:

   - **Temporal.** `~now ±60s` failing is a statement about a *moment*, not a value. Accepting the actual
     pins the row to a timestamp that will not recur, so the step is red on every run from then on — and the
     author does the only thing left, loosens it to `presence` or drops it, and the scenario silently stops
     checking the timestamp. A red that leads to a deleted assertion is a green by a longer route.
   - **Reference.** Accepting an echoed id pins the assertion to this run's ClOrdID and deletes the
     cross-step binding the row exists to express.

   The rule lives in `ScenarioReconcile.canAcceptActual`, not in the button that happens to draw it, so the
   view and the engine cannot come to differ about it. No separate per-row re-seed action is needed.
5. ~~The wire-order fallback.~~ **Decided: the engine reads the venue's bytes, or it refuses to judge.**

   It was worse than the question assumed. QuickFIX's `toString()` does not merely sort the body by tag —
   with no `fieldOrder` set it emits the flat fields in ascending order and then **appends every repeating
   group at the end of the body** (`FieldMap.calculateString`, QFJ 2.3.2). A party block captured mid-message
   came back after `Text`. It is not a subtle reshuffle; it is a message no venue sent, wearing SOH and
   `tag=value` so that nothing downstream could tell.

   The blast radius was a **false red, never a false green** — pairing looks at the tag and the position and
   never at whether a matcher would pass, so a reordering can only fail to pair. But it was the worst kind of
   false red: it read as a venue regression and it was not one.

   Three changes:

   - **Incoming messages read `quickfix.Message.toRawString()`** — the bytes QFJ retained when it parsed
     them. It had been there all along and this repo never called it, which made `RawMessageCapturingLogFactory`
     a second implementation of a fact QuickFIX already had. It is deleted. (It could not have been right
     anyway: its key carried no BeginString or session qualifier, so two sessions sharing a CompID pair
     addressed the same slot; its read was destructive, so a PossDup replay found nothing; and every message
     QFJ rejected before dispatch leaked its entry for the life of the process.)
   - **`wireRaw` is the venue's bytes or it is null, and nothing guesses.** `wireFields` returns null rather
     than falling back to the `|`-substituted display string — on the only path that reached that fallback,
     the display string was *itself* built from `toString()`, so the graceful degradation was the bug in
     disguise. The runner, `fixtool_assert` and `fixtool_capture_expectation` fail loudly and **name FixTool
     rather than the counterparty**: a red that sends an engineer hunting a venue bug that does not exist is
     barely better than a green.
   - **Outgoing messages keep `toString()`, because there it genuinely is the wire** — `Session.sendRaw`
     serialises the same object on the next statement and writes that string. With one exception: for a Logon
     carrying `ResetSeqNumFlag=Y`, QFJ calls `toAdmin`, *then* resets and rewrites `MsgSeqNum`, *then*
     serialises. We were recording a sequence number the venue never saw, and an assertion could have passed
     on it. Those messages now carry no wire bytes.

   The order of the fields the engine reads is half of what an expectation asserts. It is not something to be
   reconstructed on a best-effort basis.
