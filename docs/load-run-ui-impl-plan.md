# Build prompt: the load run UI redraw

You are implementing the redraw of FixTool's load run screens. This file is your brief. Read it in full
before writing code.

## What you are building

`docs/mockups/load-run-ui.html` is the design note and it is the spec. Open it and read it in full,
including the mockup panels hidden behind tab buttons (`hidden` attributes) and the two chart scripts.
Published copy: https://claude.ai/code/artifact/180948a8-2bc6-4543-8414-2dae5495a223

The note redraws two screens (the load run dialog and the load run document), adds Compare, fixes the
entry points, and specifies four edge states. It also carries the type scale the rest of the app can
converge on.

Two sections of it are binding and you must not re-open them:

- **Decisions.** Nine decided, four assumed, one superseded, each with its reasoning. The design choices
  are made. If you believe one is wrong, say so and stop, do not quietly build the other thing.
- **Order of work.** Eight steps. The order encodes real dependencies, not preference.

One section is **out of scope**: "Load sets, and whether this design survives them". That is issue #45,
its own build. It appears in the note only to prove the redraw is not a dead end. Build nothing from it
except the one prerequisite named in step 2.

## Traps that cost the design two rounds of review. Do not rediscover them.

1. **The round-trip samples are not in the report.** `LoadRunner.kt:256` reduces the matcher's sorted
   samples to `roundTrip = sorted?.let { RunSetStats.of(it) }`, and `RunSetStats.Distribution` is seven
   numbers (`RunSetStats.kt:35`). No sample reaches `LoadReport` or `load.json`. Neither the bucket
   histogram nor the outstanding curve can be drawn from what is recorded today. That is why step 4
   exists and why it comes before the charts.
2. **`perSecond` is empty for the whole run.** Same line: `result?.perSecond?... ?: emptyList()`, and
   `result` is only non-null in the final report. `pendingPeak` is 0 until then. The live chart has no
   data source until step 4 gives it one.
3. **The Compose test assertions change.** `LoadRunDocumentTest` asserts `load-state` contains
   "unmatched 4", `load-verdict` contains "UNMATCHED  4 of 4,000" and "RUNNING", and
   `load-unmatched-label` contains "pending". The redraw renames those to "unanswered", "ISSUING" and
   "outstanding". Update the assertions with the vocabulary. Keep every `testTag` as it is.
4. **`LoadRunDialogTest` clicks `load-store-profile`,** which now lives inside a collapsed Advanced
   group. Advanced must open itself whenever a refusal names anything inside it, which is both the
   design rule and what keeps that test able to reach the radio.
5. **`Modifier.selectable` already gives focus and keyboard.** The app is wrapped in `MaterialTheme`
   (`WindowChrome.kt:34`), so today's glyph rows are focusable, toggle on Space and Enter, and draw
   ripple layers. The reasons to replace them are missing `Role` semantics, a hit target the size of the
   glyph plus label, no affordance beyond a character, and radio semantics doing a checkbox's job. Do
   not justify the change on grounds that are not true.
6. **`--set` is already taken on the CLI.** `HeadlessLoad.kt:98` and `:359`: `--set <k>=<v>` seeds a
   value. #45 proposes `--set <name>` for a load set, which collides. Comment on #45; do not rename
   anything in this build.

## The steps

Do them in order. Each is a commit. Run the relevant tests before moving on.

### 1. Controls and scale

- Slim radio, checkbox and segmented control in `SlimComponents.kt`, in the same recipe as `SlimField`:
  24dp rows, 10 to 11sp, thin focus border in `AppTheme.Colors.primary`. Bespoke, not Material3 scaled:
  `RadioButton` is 20dp with a 48dp minimum interactive size and will not sit in a 24dp row.
- Give each a `Role` (`Role.RadioButton`, `Role.Checkbox`) and arrow-key movement within a group.
- Four type tokens in `Theme.kt`: `figure` 26sp mono, `head` 13sp sans 600, `body` 11.5sp sans,
  `meta` 10sp mono.
- Lighten `AppTheme.Colors.textDisabled` from `#6A6A6A` to about `#8A8A8A`. On `#1E1E1E` the current
  value is roughly 3.1:1 and fails AA for 10sp text, and `Type.meta` would make that pairing the house
  rule. This is an app-wide change and is deliberate.

Nothing here is load-specific. Other dialogs still drawing glyph radios can follow later, separately.

### 2. The dialog, and `LoadPlan.problems()` with it

- Three groups: What to send, How hard, Advanced. Advanced collapsed when everything inside it is fine,
  open (and saying why) whenever a refusal names anything inside it.
- Move the refusal list out of the composable (`LoadRunDialog.kt:105` to `:117`) into
  `LoadPlan.problems(): List<String>`, called by the dialog, `fixtool load` and `POST /load`, so the
  three cannot drift. This is also #45's prerequisite.
- Pinned footer that states why Run is disabled. Esc cancels, Ctrl+Enter runs, and the hint reads
  `⌘↵` on macOS and `Ctrl+↵` elsewhere.
- Refusals inline under the row that caused them, with a `fix` marker. **Print `storeProblem()` and
  `fanOutFarEndNotice()` verbatim**, prefixed only by the profile name. Do not paraphrase them and do
  not append a second remedy: that bug was just fixed at `LoadRunDialog.kt:110` and the sentence is
  owned in one place on purpose (`FixConnectionConfig.kt:92`).
- Preset chips for the four shapes, tags shown with their dictionary names, Seed as two fields inside
  Advanced, pickers with a chevron, "Copy as `fixtool load`".
- Persist last-used shape, count, rate, settle and seed per profile in the **view-state JSON store, not
  `AppSettings`**. `SettingsPagesTest` enforces that split.

### 3. The document

- Verdict as a badge in the header, three judgement pills in the footer.
- Two lead figures (answered, unanswered) at `Type.figure`; issued, duplicates, late, strays and peak
  outstanding demoted to one quiet strip with separators.
- 8dp progress bar with counts and a remaining estimate. Give a failing segment a visible minimum width.
- Round-trip stat card in the `LatencyPanel.StatisticsCard` idiom. This is a **new** card, not a reuse:
  that one is private, takes `LatencyStatistics`, and shows p90 and stddev that `Distribution` lacks.
- Unanswered rows clickable through to the message in its session, ⌘C copies the wire, "copy all as FIX".
- Records as actions (Copy JSON, Reveal records), not a printed path.
- The four edge states, each with its rule from the note: **stopped** is reported and not judged (no
  verdict, no exit code); **nothing matched** spends its empty chart slot on the diagnosis, which the
  tool can already prove from `Replies.strays`; **pruned** offers to run the surviving plan again;
  **narrow** (460px) drops leads to 20sp, the stat row to three columns, header actions to an overflow,
  and gives the chart its own horizontal scroll.

### 4. The measurements the charts need

Engine work, no UI.

- A fixed log-spaced round-trip histogram, about 30 buckets from 0.1ms to 100s, on `LoadReport` and in
  `LoadReportCodec`. Increment it in `StampMatcher.record()` inside the `synchronized(samples)` block
  that already exists at `:167`. Thirty integers bound the memory whatever the run's size.
- Maintain the per-second issued and matched counters and each second's p95 incrementally instead of
  rebuilding them in `buckets()`, and expose a `bucketsSoFar()` beside `roundTripsSoFar()` so the live
  document has data.
- Per-lane completeness counts (matched, unanswered, duplicates). The lane is already in hand:
  `Pending.laneSlot` at `StampMatcher.kt:42`, and `record()` receives that `Pending` at `:165`.
- Exact percentiles keep coming from the sorted array at `finish()`. Nothing already reported changes.

### 5. The charts

Compose `Canvas`, no dependency.

- **Per-second panel** when `issue.spanMs` exceeds a few seconds: one column per second up to 600, the
  issued schedule as a line over them with a dark halo (it lands exactly on the bar tops, which is the
  point), the p95 panel below on its own scale sharing the x axis, and a guide joining a throughput dip
  to its latency spike. A second draws red when it falls under the pacer's own floor,
  `500/s × (1 - Pacer.TOLERANCE)`, so the picture cannot disagree with the rate verdict. Past ten
  minutes, downsample as a min-to-max range, never as a single "worst second" value.
- **Outstanding curve**, always, in the stat card's slot: share still outstanding against round trip on
  log-log axes, from the histogram. Percentile guides land on their complementary decade (p95 at 5%,
  p99 at 1%), and the floor the curve never gets under is the unanswered share.

### 6. One record shape

A run becomes a one-phase set on disk: one reader, one JSON schema, one Compare. Include a
back-compatible read for the records already in `~/.fixtool/loads`. Before Compare, because Compare
reads that schema.

### 7. Compare

Its own tab, opened from the document header or two runs selected in Recent.

- **Build the not-comparable state first.** Comparability is request MsgType, match tags and reply type.
  If any differ, name the difference and refuse, showing the three deciding rows first. Everything else
  (lanes, shape, rate, settle, store) may differ and is context, not a delta.
- Deltas: counts, the six percentiles, rate verdict and max lag, the tool block, peak outstanding, and
  seconds whose p95 crossed a threshold. The threshold defaults to a fixed 100ms and is applied
  identically to both runs.
- Completeness up, latency down, tool counts down. A percentage only where the earlier value is
  non-zero, so `77 → 0` reads "cleared".
- "Run this plan again", refusing when the template or profile the plan names no longer exists.

### 8. The render-ahead producer, then per-lane latency

`LoadRunner.kt:97` renders and sends every lane from one pacer loop, round-robin, so lane N is issued
systematically later than lane 1 by roughly (N-1) times the per-message cost. Give each lane a
render-ahead producer so its sends are not queued behind other lanes' rendering. **Only then** collect
and show per-lane latency. Until this step lands, per-lane latency is neither collected nor shown, and
per-lane completeness (step 4) is what ships.

## Entry points

Fold these into step 3, since they are the same vocabulary:

- Recent's merged rows put the verdict in column one and the kind in column two, for both row types.
  Today a load row leads with `⚡` (kind) and a scenario row with `✓`/`✗` (verdict), so the column you
  scan means two different things (`ScenariosRail.kt:1057` and `:1067`).
- A passing load run gets a mark. Today the `✗` is appended only on a non-zero exit, so a pass renders
  as whitespace.
- The rail's Run menu count says what it counts: "Load run…  (2)" is a count of profiles that can supply
  lanes (`ScenariosRail.kt:1131`), which reads as two lanes.

## Project conventions, non-negotiable

- **Two commit-message rules are enforced by a hook,** at `/Users/amit.gupta/.git-hooks/commit-msg`
  (wired in through `core.hooksPath`, so it is not in `.git/hooks`). It checks the message only, never
  file contents. It rejects any mention of Claude or Anthropic, so no attribution trailers. It also
  rejects the pattern `all N tests pass`, which is worth knowing before you write eight commits: say
  what changed, not that the suite is green. A `pre-push` hook re-checks the same first rule across
  every commit being pushed.
- **Do not push unless asked.** Commit locally on `main`.
- **ktlint and detekt fail module-wide on pre-existing findings** and are not gates here. Format only
  the files you touched. A module-wide `ktlintFormat` rewrites about a hundred unrelated files.
- **UI and view state go in their own JSON store, not `AppSettings`.** `SettingsPagesTest` enforces it.
- **This machine's app runs a dealer dictionary.** `~/.fixtool/app_settings.json` has
  `useBundledDictionary: false` and points at a BrokerTec `fix-dictionary-4-4.xml` in which tag 132 is
  `TargetLocationID` and a header field. A bundled example asserting on quote prices goes red in the app
  here while tests and the CLI (bundled dictionary) are green. Check the dictionary before reading a red
  example as a venue bug.

## Verifying

Unit and Compose tests per step, then a live check with the `verify` skill:

- Point lanes at the **RFQ venue**: port 19877, profile `RFQ Load Client`, five lanes on a memory store.
  It is the good load target.
- The **FX venue is a poor load target**: its price expressions are real Kotlin and compile per quote,
  giving about 15 to 17 quotes a second.
- `/screenshot` captures screen pixels, so a window covering the app breaks a visual check. Verify via
  control-surface state when the window cannot be brought forward.
- Name temporary artifacts `VERIFY TEMP …` and delete them afterwards.
- For steps 4 and 5, verify with both shapes: a `×4,000` burst (which exercises the outstanding curve
  and the unanswered table) and a `500/s for 10m` rate run (which exercises the per-second panel, and
  where a stall should show as a red second joined by a guide to its p95 spike).

## Definition of done

Steps 1 to 3 answer "it is ugly" and depend on nothing else, so they can land and be judged on their
own. Steps 4 to 7 answer "it is not intuitive". Step 8 makes a number honest before showing it.

Report after each step: what landed, what tests ran, and anything in the note you found to be wrong.
The note has been through two review rounds and about a dozen of its claims were corrected, so if a
citation does not match the code, trust the code and say so.
