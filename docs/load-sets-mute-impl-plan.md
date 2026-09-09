# Muting a phase of a load set: build brief

You are implementing one feature in the FixTool repo (Kotlin, Compose Desktop, Gradle module `composeApp`). You start with no context beyond this brief, so read it fully before touching code, and read the neighbouring code before writing in any file. The approved design note is at `/Users/amit.gupta/FixTool/docs/mockups/load-sets-mute.html` in the MAIN working tree (not in your worktree): open it in a browser or read its HTML for the mockups and exact wording. Its predecessor `docs/mockups/load-sets.html` and the earlier build prompt `docs/load-sets-impl-plan.md` are in your worktree and explain how sets work.

## Where you are

You are in a git worktree. Before anything else: `git rev-parse --short HEAD` must print `fb444b3` (main). If it does not, run `git merge --ff-only main` first. Do not touch the main working tree at `/Users/amit.gupta/FixTool` except to READ the design note named above and copy it (see Docs). The main tree has another session's uncommitted work in `LoadSet.kt` (a KDoc on `listen`), `LoadSetRunner.kt` (a two-pass `openEveryLane`), `LoadRunner.kt`, `LoadFakes.kt` and `LoadSetRunnerTest.kt`. You will not see it. Keep your edit to `openEveryLane` minimal so it rebases cleanly later: filter the phases once into a local `live` list at the top of the function and iterate that, do not restructure the loops.

## What this is

A load set (`model/load/LoadSet.kt`: `LoadSet`, `LoadPhaseSpec`; `service/load/LoadSetRunner.kt`; `ui/LoadSetsDialog.kt`) is an ordered list of phases, each a complete load plan. This adds one thing: a phase can be **muted**. It stays in the file with its template, profile, shape and place in the order, and the set skips it on every run.

The word, the file key and the look are the scenario editor's, not new ones: `ScenarioStep.muted` ("Parked, not deleted", `model/scenario/Scenario.kt` around line 171), the `MUTED` chip (`MutedChip` in `ui/ScenarioEditor.kt` around line 875), the ⊘ rail glyph (`ui/ScenariosRail.kt` around line 1950), and the default-omitting `"muted": true` on disk (`service/ScenarioCodec.kt` around line 172). A phase is the third thing that can be parked, after a step and an example row.

## The decision this build rests on

**Any phase can be muted. Position does not matter. The validator refuses the one case it can prove is broken: a later phase reading a capture that only a muted phase keeps.**

Why not "last phase only": position is a proxy for dependency, and the set already knows the real dependency graph, which is captures. In the bundled RFQ set (`load-sets/rfq-round-trip.json` in the RFQ example bundle), phase 2 (hit indices 1 to 2,000) and phase 3 (pass 2,001 to 4,000) both read only phase 1's `${quoteId}` and `${offer}`. Muting phase 2 to bisect a failure is legitimate. Where a dependency runs through the derived seed the tool cannot see it, and muting the earlier phase becomes a negative test whose report says what happened. The set's existing rule applies: nothing dials until `problems()` is empty (see the KDoc on `LoadSet.problems`).

## Model and file

- `LoadPhaseSpec.muted: Boolean = false`. KDoc in the file's voice: parked, not deleted, the runner skips it entirely, the record carries it as skipped with the note below, additive and default-omitting on disk, the same bargain as a step's `muted`.
- `LoadPlan.muted: Boolean = false` (`model/load/LoadPlan.kt`), set by `LoadSet.plan()` from the spec. `indexFrom` and `capture` are the precedent for set-only fields on a plan.
- `service/load/LoadSetCodec.kt`: `phaseToJson` writes `"muted": true` only when set, `phaseFromJson` reads absent as false. This is the same codec the inline `POST /load {"phases": [...]}` body goes through, so the API gets it for free. Set file schema stays 1.
- `LoadPhaseSpec.describe()` is unchanged. The row shows the state with a chip, not in the plan line.

## Validation: `LoadSet.problems()`

The line is **resolve, do not judge**. A muted phase is refused for structural faults, because the record needs its plan to say what would have run, and never for environmental ones.

Still checked for a muted phase:
- the duplicate-label check,
- its profile resolves, its template resolves (the existing two sentences),
- the template has a MsgType and carries a correlation tag or the spec says which (the existing sentence), because `plan()` cannot build a `LoadPlan` without a match.

Not checked for a muted phase:
- template reads (the missing-variable part of `LoadPlan.templateProblems`), the store sentence (`storeProblem`, and a muted phase's profile does not enter `judgedProfiles`), its own capture claims (`captureProblems`, and it does not write into `claimed`), lane logon (runner).

Captures of a muted phase leave the `earlier` set. A later phase reading such names gets **one sentence per muted phase**, listing every name that muted phase keeps and this phase reads, in the phase's voice through `Problem(n, …)`. With two names:

```
the template reads ${quoteId} and ${offer}, and the phase that keeps them, phase 1 · Ask for a quote, is muted. Unmute it, or <remedy>.
```

With one name: `the template reads ${quoteId}, and the phase that keeps it, phase 1 · Ask for a quote, is muted. Unmute it, or <remedy>.` The remedy follows the `LoadPlan.Surface` the way the existing seed sentence does: DIALOG "add them under Seed" / "add it under Seed", CLI "seed them with --seed" / "seed it with --seed", API "seed them" / "seed it". Look at how the existing "nothing seeds it" sentence varies its remedy by surface and mirror it.

Hand the muted-captured names to `templateProblems` as though seeded, exactly as `tooLate` is today, so the "nothing seeds it" sentence does not also fire. Precedence: seeded, or captured by an earlier **live** phase, means no refusal at all. Only when neither holds and an earlier muted phase captures it does the sentence fire. A name captured by a muted phase and later by a live phase, read after the live one, is fine. If two different muted phases each keep names this phase reads, that is two sentences, one per muted phase.

Set level, replacing "A set needs a phase" when the list is non-empty but every phase is muted:

```
Every phase is muted. Unmute one, or the set has nothing to run.
```

An all-muted set is refused, never run to FAILED.

## Runner: `LoadSetRunner.run()` and `openEveryLane()`

- `openEveryLane`: iterate only live phases (local `live` list, minimal edit). A muted phase's profile is not opened and not judged for logon.
- `compile()`: a muted plan whose template will not compile must not refuse the set. For a muted plan wrap in `runCatching` and fall back to `LoadReport.TemplateInfo(plan.template.name, plan.template.msgType ?: "", emptyList(), emptyList(), emptyList())`. Live phases keep throwing `LoadRefused`.
- The initial `reports` list: a muted phase is stubbed **at set start** as `LoadReport.stub(plan, LoadStatus.SKIPPED, lanes = 0, compiled, startedAt, note = LoadRecord.MUTED_NOTE)` with `finishedAt = startedAt`, so the first published record already shows it as skipped rather than queued.
- The phase loop: `if (plan.muted) return@forEachIndexed` **before** `skipNote`, so a stop or a STOP-policy skip never overwrites the muted note.
- Capture table: `names` and the `indexTo` sizing read live phases only. `earlier` for a later phase is `planned.phases.take(index).filterNot { it.muted }.flatMap { it.capture.keys }`.
- `matchers[index]` stays null for a muted phase; `withLate` already tolerates that.
- Phase numbering does not change. Phase 3 stays phase 3, its evidence files still `03-*`, Compare still pairs by position.

## Record and verdict: `model/load/LoadRecord.kt`

- `const val MUTED_NOTE = "muted in the set"` beside `STOPPED_NOTE`, KDoc saying the verdict reads it, the same reason `STOPPED_NOTE` lives here.
- One helper, one place: `LoadReport.isMuted` (or on `LoadRecord`) meaning `status == SKIPPED && note == MUTED_NOTE`.
- Status stays `SKIPPED`. No new `LoadStatus`, no schema bump.
- `SetVerdict` gains `muted: Int = 0`. `counts()` prints `"$it muted"` last, so a clean set with one muted phase reads `2 passed, 1 muted`. The `skipped` count excludes muted phases.
- Three fixes in `verdict`: `firstSkipped` must skip muted stubs; the `nothingJudged && phases.first().note == STOPPED_NOTE` check must look at the first **unmuted** phase; the STOPPED and FAILED `phase` fallbacks use the corrected `firstSkipped`.
- `exitCode` and `lead` already exclude SKIPPED and need no change. `LoadRecordStore`'s recovery of PENDING phases (around line 176) leaves a muted stub alone because it is already SKIPPED. Confirm both with a test or a read, do not assume.
- `service/load/LoadReportCodec.kt`: write `"muted"` into the verdict object the way `"stopped"` is written (only when non-zero). The phase object needs nothing new. JUnit needs nothing new: `casesFor` already turns a skipped phase into three `<skipped message="…"/>` cases carrying the note.

## Doors

- Terminal (`headless/HeadlessLoad.kt`, `setSummary`): a muted phase prints `MUTED` in the status column instead of `SKIPPED`, followed by the note. The verdict line comes from `counts()` unchanged.
- `fixtool load --help`: one clause on the `--set` line, for example "a muted phase is reported as skipped".
- Set document (`ui/LoadSetDocument.kt`): `phaseMark` gives a muted phase `"⊘"` in `textDisabled`, `phaseCount` says `"muted"`, `phaseWord` says `"MUTED"`, the timeline's word says `"muted"`. The judgement pill already reads the note.
- `control/ControlServer.kt`, `loadSetSummary`: `put("muted", n)` when non-zero, so `GET /load-sets` and the MCP `fixtool_load_sets` list say it. The 202 body of `startLoadSet` is unchanged.
- Run ▾ `Load set ▸ <name>` is unchanged: it runs the file as saved.

## Editor: `ui/LoadSetsDialog.kt`

- `PhaseRow` gains `onToggleMute`. Add `Chip(if (spec.muted) "muted" else "mute", on = spec.muted, tag = "load-set-phase-mute-$n")` beside `duplicate` (the `Chip` composable is in `ui/LoadDialogChrome.kt`). Keep ↑ ↓ edit duplicate.
- A muted row: label in `textDisabled`, a `MUTED` tag in the scenario editor's style (warning colour, 9sp, bold, letter-spaced, see `MutedChip` in `ScenarioEditor.kt`, tooltip saying the set skips this phase and keeps its place). Do not show the "N fix" count on a muted row.
- `readySentence`: `"3 phases, 1 muted · run = ${uuid:4} · memory store, no log"` when any phase is muted, unchanged otherwise.
- `alreadyMemoryStores`: judge live phases only.
- `capturedBefore(index)`: leave it counting muted phases. The phase editor's hint keeps attributing a name to "phase 1" as before, and the set band carries the muted sentence.

## Tests

Names in the repo's sentence style (backticked function names that read as a sentence). Put each beside the tests of the class it exercises.

`LoadSetTest` (`service/load/LoadSetTest.kt`):
- a muted phase is not judged, and does not block the set (its template reads a name nothing seeds, and its profile's store would draw the store sentence: zero problems)
- a muted phase still has to name a template and a profile
- a phase reading a muted phase's captures is refused in one sentence naming the phase (the exact two-name sentence above, and the "nothing seeds it" sentence is absent)
- a name captured by a muted phase and by a later live phase is readable after the live one
- a muted phase's captures do not claim the name
- a set with every phase muted is refused

Set codec round trip (beside the existing one, wherever it lives): `muted` is written only when true, and an absent key reads false.

`LoadSetRunnerTest` (fake host in `LoadFakes.kt`; extend it to record lane opens if it does not):
- a muted phase is skipped from the first record, its lanes never open, and the rest run (first published record has phase 2 SKIPPED with `MUTED_NOTE`, the muted profile's `openLanes` was never called, phases 1 and 3 ran, verdict PASSED, `counts()` is `2 passed, 1 muted`, `exitCode` 0)
- a muted phase stays muted when the set stops (note is `MUTED_NOTE`, not `STOPPED_NOTE`)
- a set stopped before phase one dialled, with phase one muted, is STOPPED and not FAILED
- a muted phase whose template will not compile does not refuse the set

`LoadReportTest` (verdict and codec): phases muted, failed-under-STOP, skipped give verdict phase 2 and counts `1 failed, 1 skipped, 1 muted`; the verdict JSON carries `muted` only when non-zero; the muted phase becomes three `<skipped message="muted in the set"/>` JUnit cases.

`HeadlessLoadTest`: `setSummary` prints a `MUTED` status line for the phase and `2 passed, 1 muted` on the verdict line.

`ui/LoadSetsDialogTest`:
- the mute chip parks a phase, the footer says so, and Save writes the key (click `load-set-phase-mute-2`, footer contains `1 muted`, saved JSON has `"muted": true` on phase 2 and no `muted` key elsewhere)
- a phase reading a muted phase's captures shows the refusal under the reading phase, and Run set is off

`ui/LoadSetDocumentTest`: a muted phase's rail row shows ⊘ and `muted`, its badge says `MUTED`.

`integration/ControlServerLoadIntegrationTest` (or wherever `GET /load-sets` rows are asserted): a row carries `muted` when a phase is muted and no such key otherwise.

## Docs

- Copy `/Users/amit.gupta/FixTool/docs/mockups/load-sets-mute.html` from the main tree into your worktree at `docs/mockups/load-sets-mute.html` unchanged, and commit it with the docs commit. It is the approved design note.
- Save this brief verbatim as `docs/load-sets-mute-impl-plan.md` and commit it with the docs commit.
- `docs/mockups/load-sets.html`: add one row to the Decisions table (the `.qa` block near the end, same markup as its neighbours) recording that a phase can be muted and linking to `load-sets-mute.html`. Nothing else in that file.
- `composeApp/src/jvmMain/resources/help.html`, the `id="load-sets"` paragraph: two sentences on muting a phase and how the report shows it.
- `CHANGELOG.md`, under `### ✨ Added`, in the "Load sets" group: one bullet, bold lead, in the voice of the bullets around it. Lead with **A phase can be muted.**
- `docs/AUTOMATION.md`: if the `GET /load-sets` row fields are listed anywhere, add `muted`. Otherwise leave the file alone (another session has uncommitted edits in it).

## Conventions

- No em dashes anywhere you write: code comments, KDoc, docs, help, changelog, commit messages. Use a comma, a colon, parentheses or a new sentence. No semicolons in English prose.
- KDoc and refusal sentences in the file's existing voice. Read the neighbours before writing.
- ktlint: format **only the files you touched** (`ktlintFormat` module-wide rewrites about a hundred unrelated files). At the end run `./gradlew :composeApp:ktlintCheck :composeApp:detekt` and fix what is yours.
- Do not run a Gradle build while a FixTool app is live on this machine (a parallel build kills a live load run). Check with `pgrep -fl fixtool` and `jps` before building; if an app is running, wait or ask.
- Commits: two or three, in the repo's style (`feat(load): …`, `docs(load): …`), imperative, lower-case scope, first line under 72 characters, body in prose. **No trailer of any kind and no mention of Claude or Anthropic anywhere in a commit**, the commit-msg hook rejects it. Do not push.

## What proves it

1. The named test classes, green:
   `./gradlew :composeApp:jvmTest --tests '*LoadSetTest' --tests '*LoadSetRunnerTest' --tests '*LoadReportTest' --tests '*HeadlessLoadTest' --tests '*LoadSetsDialogTest' --tests '*LoadSetDocumentTest' --tests '*ControlServerLoadIntegrationTest'`
2. Then the full `./gradlew :composeApp:jvmTest` once. Known pre-existing flakes under full-suite load: `HeadlessRunIntegrationTest --set`, `MultiSessionConnectIntegrationTest.disconnectProfile`, `AwaitTest`, `ReplyWithTest`, and a `BindException` in `ControlServerLoadIntegrationTest.setup`. Rerun any of those in isolation before calling it a failure, and report exactly what failed and what you reran.
3. `ktlintCheck` and `detekt` clean on your files.

## Report back

The worktree path and branch, the commit hashes with one line each, the test output summary (counts, anything red and whether it was a listed flake), the exact refusal sentences as they came out of the tests, and anything in this brief you found to be wrong about the code and how you resolved it.
