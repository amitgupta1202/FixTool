# `fixtool_assert` — superseded

> **This document described the group-path assertion model, which no longer exists.**
> The assertion model is specified in **[`scenario-assertion-model.md`](./scenario-assertion-model.md)**.
> Nothing below is current. The file is kept only so that links to it land somewhere that says so.

## Why it was withdrawn

It located a field inside a repeating group by **identity** — `GroupPath(453, 448, "FIRMA")`, read as
*"the party entry whose PartyID is FIRMA"*. That answer is wrong, and it is wrong on ordinary messages:

```
453=2 |
  448=FIRMA | 447=D | 452=1 |     <- executing firm
  448=FIRMA | 447=D | 452=4 |     <- clearing firm
```

Both entries carry `448=FIRMA`, because one firm can act in two roles. **The identity does not identify.**
Everything built on top of it — de-duplicating entries, numbering them by occurrence, refusing to assert the
ones that could not be told apart — was an attempt to repair a foundation that does not hold, and each repair
shipped a scenario that either checked the wrong entry and reported green, or refused to check an entry it
could have.

## What replaced it

An expectation is an **ordered list of rows**, each a tag and a matcher. The *k*-th row for tag `T` asserts
the *k*-th occurrence of `T` in the message. Position is the address; there is no path, no group and no entry
anywhere in the engine. Two roles of the same firm are simply the first and the second `452`, and both are
asserted, separately and correctly.

Read [`scenario-assertion-model.md`](./scenario-assertion-model.md). It is the only current specification of
this surface — the matcher vocabulary, the OPEN and STRICT semantics, the wire format, the reconcile view,
and what happens when FixTool cannot read a message's wire bytes.
