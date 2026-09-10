# Load report — open findings, 2026-09-10

Four things found while demonstrating burst, rate and reactive runs against the bundled RFQ Venue.
None is a regression from the work committed that day (`db9416a0`, `2eb18a9e`, `2ebce510`) — the first
was checked against the pre-session build and cleared. Written down rather than fixed, so whoever picks
them up starts from the evidence rather than from a memory of a conversation.

---

## 1. A load set with the store override issued nothing — once

**What happened.** An inline two-phase set with `"storeAndLog": {"store":"MEMORY","log":"NONE"}` ran to
completion having put **nothing on the wire**:

```
phase 1  requested 600  handedToEngine 600  leftSocket 0   pendingPeak 0
         tool: { neverLeftSocket: 600, issueFailures: 0 }   verdict tool: LIMITED
phase 2  requested 600  handedToEngine 0    unaddressable 600      (nothing to react to)
```

Every send was accepted by QuickFIX/J and **no SEND socket stamp ever arrived**, so the matcher never
saw a request go out and nothing could match.

**It is not a regression, and not the auto-connect path.** Checked three ways, same set, same override:

| build / conditions | result |
| --- | --- |
| pre-session build `ecd817dc`, hand-connected | 600 of 600 issued and answered |
| this build, fresh + hand-connected | 600 of 600 |
| this build, fresh + brought up by the run's own auto-connect | 600 of 600 |
| this build, **straight after a 40,000/s run that left 80,322 replies outstanding** | **0 of 600** |

So the trigger is the preceding heavy run, not the override alone and not who opened the sessions.

**Where to look.** `ViewModelLoadHost.applyOverride` / `reconnect` — the override reconnects each lane
(disconnect, wait for the engine to let go, connect, wait for LOGGED_ON) and `FixMessageSession.connect`
builds a **new** `QuickFixService` with a fresh `onSocketStamp` wiring. The suspicion is that under a
still-draining connection the reconnect completes and reports LOGGED_ON while the stamp source that
`leftSocket` counts is not the one the sends go through. `applyOverride` returned true for all five
lanes (the record says `lanes: 5`), so the bounded wait was satisfied.

**Why it matters.** The bundled `rfq-round-trip` set ships with `MEMORY`/`NONE`, which is also the pair
the guide recommends for measuring a venue rather than a disk. A run that silently issues nothing and
reports `tool: LIMITED` is the right verdict for the wrong reason — the reader is told FixTool got in the
way, not that the run never happened.

**To reproduce:** run a 40,000/s rate for 12s against the RFQ Venue, let it finish, then immediately run
a set with the `MEMORY`/`NONE` override on the same profile.

---

## 2. A run that achieved a third of its rate reports `rate: HELD`

**What happened.** Asked for 40,000/s for 12s:

```
achievedPerSecond   12,489/s
rate report         { requestedPerSecond: 40000, heldForMs: 11000, shortfalls: [], maxLagMs: 62 }
verdict             rate: HELD
```

Eleven of the run's full seconds are counted as having held a 40,000/s schedule while the run put
12,489/s on the wire.

**Root cause.** Both halves of the rate verdict read the pacer's own histogram, which counts what was
**handed to the engine**, not what **left the socket**:

- `Pacer.Tally.record` increments `perSecond[second]` for every message the pacer issued.
- `LoadRunner.rateReport` (`LoadRunner.kt:501`) computes
  `heldForMs = fullSeconds.count { it >= floor } * 1000`.
- `Pacer.shortfalls` compares the same buckets against the same floor.

Meanwhile `Issue.achievedPerSecond` is `leftSocket * 1000 / spanMs` — SEND stamps over the issue span.
So the pacer can hand 40,000 a second to an engine that only gets 12,489 of them out, and the schedule
reads as held while the wire says otherwise.

**The question to settle** is which quantity the rate verdict is *about*. "Issued is three numbers, and
completeness is judged over the last of the three" is already the rule for completeness; the rate verdict
is judged over the first. Either it should move to `leftSocket`, or the report should say plainly that
the schedule is measured at the engine and let the tool block carry the rest — but silently answering
HELD for a run that missed its rate by 3x is the one thing it should not do.

---

## 3. The schedule label sits flush against the right edge

`PerSecondPanel` right-aligns `"150/s schedule"` to `size.width`, where the plot itself stops at
`size.width - RIGHT_PAD`. One line in `LoadCharts.kt`; cosmetic.

## 4. The p95 guide label is the one that gets dropped

Guide names on the outstanding curve are placed left to right and a label that would overlap the
previous one is skipped. When all three percentiles fall inside one decade — sub-millisecond round trips,
which is the ordinary case against a local venue — p50 is drawn, **p95 is skipped**, and p99 is drawn
because it clears p50's label. p95 is the number people are looking for. Placing by priority (p95, then
p50, then p99) rather than by position would keep the important one.
