# Scenario assertions: the sequence model

**Status:** proposal. Replaces the group-path assertion model in
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

Only the listed rows are checked. Any tag the reply carries that the expectation does not mention
is ignored — a venue adding an optional field does not break the scenario.

**Relative order is enforced.** The listed rows must appear in the reply *in the order they are
listed*, with anything else allowed in between: a **subsequence** match, not a set match. This is
what keeps OPEN honest about repeating groups. Under set semantics, an expectation listing
`452=1` then `452=4` would still pass against a reply that sent the roles the other way round —
the two entries swapped, the assertion none the wiser. Under subsequence semantics it fails, and
says so.

So OPEN is tolerant about what it does **not** mention, and strict about what it does.

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

`status` is one of `ok` · `value` · `missing` · `unexpected` · `moved`.

### Tags that are never asserted

Unchanged, and orthogonal to all of the above: `BeginString(8)`, `BodyLength(9)`, `CheckSum(10)`,
`MsgSeqNum(34)`, `SendingTime(52)`, `SenderCompID(49)`, `TargetCompID(56)` and
`LastMsgSeqNumProcessed(369)` identify the connection and the moment, not the behaviour. They are
never seeded and never counted as unexpected in STRICT — otherwise a scenario captured on DEV goes
red on QA on every step.

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

1. ~~Should OPEN enforce relative order?~~ **Decided: yes.** OPEN is a subsequence match — the listed
   rows must appear in the listed order, with anything else allowed between them. Set semantics would
   let two swapped group entries pass unnoticed, which is the failure this model exists to prevent.
2. **Repeated occurrences beyond what was captured.** OPEN ignores a fifth party when four were
   captured. Should the count tag (`453`) being asserted `exact 2` be enough to catch that, or does
   OPEN need an explicit "no more than captured" option?
3. **Reference matchers across occurrences.** `${out.D.11}` resolves to one value. Does any real
   flow need "the second leg's ClOrdID echoes the second order"? If so, references need an
   occurrence index too.
4. **Re-seed granularity.** "Re-seed from this message" replaces a whole step. Is a per-row re-seed
   worth having, or does Accept-actual already cover it?
