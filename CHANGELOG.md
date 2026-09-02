# Changelog

All notable changes to FixTool will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### ✨ Added

#### Trace across sessions — follow one exchange through every pane
- **Follow across sessions.** One action on a conversation header, a message row's right-click menu, or a correlation-id field in the detail pane follows that exchange through every session at once. Every pane narrows to the trace, a toolbar chip names what is followed (`Following RFQ-A1 · 4 sessions · 14 messages`), and the set grows live as the venue mints new ids. `Esc` or the chip's ✕ stops following and every pane's own filter comes back untouched. It replaces the regex QAs typed into the search box to do the same by hand, which missed hops silently and matched `ORD-9` inside `ORD-91`.
- **The Trace panel (Ledger).** The grouped grid, run over every session at once: one grid in time order with a Session column, per-hop **Elapsed** times on one clock, and group headers that count sessions as well as messages, so *which RFQs touched more than one session* is read off the headers. Nothing is hidden: Ungrouped is last and counted, and a trace whose first message has left a session's buffer says so. A single-session trace carries a hint pointing at the dictionary's `.roles.json` sidecar, where a venue's own echo tag can be declared to join it. Toolbar toggle beside Group.
- **Control surface**: `GET /traces`, `GET /trace?id=` (the merged exchange with a session and elapsed time per message; a substring never matches) and `POST /panel {"panel":"trace","follow":…}`; MCP tools `fixtool_traces`, `fixtool_trace`.

### ✨ Added

#### The demo is the acceptor — an FX venue you can read
- **Pressing Start Demo Server now installs a workspace, not a black box.** The demo was ~1,540 lines of hard-coded QuickFIX/J that priced six pairs behind a button — nobody could open it, read why it replied, reorder a rule or make it misbehave on purpose. It is replaced by an **FX Demo Venue** acceptor profile carrying a new **FX venue** preset bundle (21 rules), two **Demo Client** profiles, ten FX templates and one bundled scenario. Everything the demo does is now a shipped feature you can inspect and change.
- **Three priced pairs, quoted live.** EUR/USD, GBP/USD and USD/JPY (three decimals, the FX convention) are quoted from template expressions evaluated as each reply is sent, so two quotes are never identical. The bid is drawn once and the ask derived from it, so the spread never varies and never inverts. Unknown symbols are refused with a proper `35=AG`, and unpriced orders with `103=1`.
- **The venue answers from its order book**: cancels distinguish *too late to cancel* from *unknown order*, replaces keep the OrderID, status requests report real quantities, and a repeated ClOrdID is rejected as a duplicate. It ships with a 40–80ms simulated latency, so replies do not land suspiciously instantly.
- **One bundled scenario — "EUR/USD order lifecycle"** — runs green on a fresh install and is repeatable, because its setup clears the venue's order book. Break a venue rule and run it again to see the reconcile view name the field that moved.
- The venue takes `TargetCompID=*`, so **any** client CompID can join it, each getting its own pane on the venue side.

### 🔧 Changed
- **Acceptor replies are built through the loaded data dictionary.** They can now carry repeating groups (a conformant `QuoteRequestReject` needs one), and a rule template's header fields — `115` OnBehalfOfCompID, a venue dialect's own header tags — are placed in the header instead of the body, where they previously drew "tag specified out of required order" from the counterparty.
- **Acceptor rules can match on a field inside a repeating group.** A conformant FIX 4.4 `QuoteRequest` carries Symbol inside `NoRelatedSym`, where no trigger could previously see it. Flat fields still take precedence, so no existing rule changes what it matches.

### 🐛 Fixed
- **Stopping the demo and starting it again brought back a demo with no venue in it.** The demo's clients auto-reconnect, so a venue taken down while they were still live was immediately dialled again and minted fresh panes; those outlived the Stop and were adopted by the next Start in the venue's place. The venue is now closed first, and Start sweeps anything a previous Stop could not reach.
- **Stopping the demo left two dead venue panes on screen.** A pane is no longer opened for a venue whose profile has already been removed.
- Demo templates were written to the default store rather than the configured one, ignoring a custom saved-messages path.

## [1.12.0] - 2026-07-24

### ✨ Added

#### Latency simulation for acceptor mode
- **The acceptor delays its auto-responses the way a network does.** FixTool replied to an incoming order as fast as the machine allowed — a latency a real venue never has, so a client whose timeout/retry logic is wrong passed here and failed against a real venue. Latency is now a per-profile property of the simulated venue, configured in a **Latency** section beside the auto-response rules: **Off**, **Fixed**, **Random range**, or **Normal distribution**, plus an independent **spike** probability that occasionally stalls a reply to a large draw (e.g. 5% of replies to 2000–5000ms).
- **One sample per triggering message, shifting the whole reply.** The delay is drawn once for each incoming message that fires a rule and shifts that rule's entire reply — ack, partial, fill — by the one number, so a sequence's authored inter-step order can never invert under an unlucky draw. The per-step delays remain the venue's own processing time, layered on top of the network hop. The applied value is written to the log per trigger, so a delayed reply is explained rather than mysterious.
- **Inert by default.** A profile with no latency configured behaves exactly as before, and nothing samples on the response path until a latency is set — so existing profiles are untouched.

#### The scenario editor is a bottom dock, and the workbench layout persists
- **The scenario editor is now an IntelliJ-style tool window docked full-width beneath the sessions**, the same idiom the terminal already uses, instead of rendering inside the session split where its position was a side effect of the view mode (and, in TABS, could not be resized at all). The dock has its own document tab strip, drag-to-resize, and minimize-to-header that survives composition; opening or focusing any document — a rail step click, a failure deep-link, a capture — restores a minimized dock and lands on that step.
- **Workbench layout persists across launches.** Panel sizes, which panels are open, and the dock heights are saved to a new `layout.json` (view state, kept out of app settings) and restored on launch; writes are debounced and drag sizes persisted on release.
- **A shared resize grab-handle replaces the 1px dividers everywhere** — which also fixes a rail divider that captured its ratio by value and barely moved per drag; the handle applies the delta against live state instead.

## [1.11.0] - 2026-07-23

### ✨ Added

#### The Scenarios rail, made usable at 20–30 scenarios
- **Sort.** A picker (⇅) in the rail header orders the list three ways: **Name** (A–Z, the default and prior behaviour), **Recently modified** (by the file's mtime, so the one you just touched floats up), or **Creation order** (oldest first — the order the suite was built). Ordering lives in a pure, unit-tested helper, so every sort is total and the list never reshuffles on an idle redraw.
- **★ Favourites.** A star on each row — filled and always shown once starred, an outline on hover for the rest. Starred scenarios lift into a **★ Favourites** section pinned above **All**; each section sorts independently and folds shut on its own header. With nothing starred the rail reads exactly as it did before: one flat list, no headers.
- **Collapse-all / expand-all.** One button (⊟/⊞) in the header folds every open scenario's steps shut at once, or opens them all.
- **A scenario carries a `createdAt`**, minted once at first save — the birth time the "Creation order" sort reads. Additive and default-omitting on disk: a file authored before the field existed grows no key and is never rewritten to add one (it sorts by mtime instead), so the change touches nothing already on disk.

### 🔧 Changed
- **The run report opens compact** — the verdict, the one first-failure line, and the route — with the "what else arrived" diagnosis behind an explicit **Show full error ▾**. Expanding reveals the whole error in a bounded, scrolling box, so seeing all of it can never reclaim the list's height.
- **The scenario that just ran is pinned** to a **CURRENT RUN** block at the top of the list until the report is dismissed, so a failure sorted down the alphabet is where the eye already is — its steps, their verdicts, and each step's reconcile route in reach.
- **Sort, favourites, and collapsed sections persist locally**, to `~/.fixtool/scenario_view.json` — never in a scenario file (a star never rewrites one) and never in app settings. A missing or corrupt file loads as defaults and never throws.

### 🐛 Fixed
- **A tall failure report could push the scenario list off the bottom of the rail, with no way to scroll to it.** The list was measured against the full pane height and rendered *below* the report, so a large error left its lower rows unreachable — routine on a list of 20–30. The list now owns the height the header and report leave and scrolls within it; the report is height-capped either way.

## [1.10.0] - 2026-07-20

### ✨ Added

#### The reconcile window is the pass, not the step
- **One diff window per scenario.** Reconciling is one act — repair a failing step, Save & re-run, meet the next failure — and the runner stops at the first failure, so a scenario diverging in five places surfaces one step per run. Keyed on the step, that loop opened a *new* window at every stop and left the finished ones on screen showing green rows: repair ten steps, close ten windows. The window is now the scenario's, and the step in view moves inside it.
- **A step strip** across the top of the diff: one chip per Expect step, coloured by where it stands (failing / repaired-not-saved / passing / not reached / not run), the current one filled, click any to jump. A pinned summary says what is left (`2 of 12 failing · 1 unsaved`), and the current chip is scrolled back into view whenever the pass moves it.
- **The pass has an ending.** A re-run that comes back green swaps the body for a completion state naming every repair the pass made — accumulated across all its Save & re-runs, not just the last one — with a single `Done`.

#### Bulk repair
- **New repair classes** in the fix plan: `oneOf`, `regex` and presence, alongside the numeric and temporal bands. A regex is inferred from the authored value and the actual by common prefix/suffix plus the narrowest class that full-matches both, and is refused if it would assert nothing.
- **A repair notices its siblings** — the same fix offered across every row of the step it can legitimately reach, folded into one edit and one undo.
- **The same fix travels across steps** — previewed per step against that step's own reply, staged into the draft (never past it), with a one-shot revert.
- **The author may overrule a class, downward**, in one click.

### 🔧 Changed
- `Cancel` in the diff is now **`Revert this step`**, and leaves the window open: with a whole pass in one window, closing on it would discard every other step's repairs.
- The close prompt counts what it would discard — *"Discard unsaved repairs to 3 steps and close?"*.
- The diff window title carries the step and the scenario's total (`rfq flow · Step 3 of 12 · reconcile`), and reads `green` once the pass has ended.
- **Control surface:** `?window=<step text>` no longer addresses a *step*. A scenario has exactly one reconcile window, so the step in its title is whichever it happens to be showing. Address the scenario, and drive the step through the reconcile API.

### 🐛 Fixed
- **A repair that travelled into a step could be silently reverted.** Cross-step repairs write sibling expectations straight into the draft, bypassing those steps' sessions; the next edit in such a step then wrote its stale expectation back, undoing work the author had watched happen, with no message. Sessions now adopt external writes — keeping their own undo stack and staged count, which a rebase would have flattened.
- A run re-bound only the first of a window's steps; every step after it silently kept the previous run's reply.
- Saving rebased only the visible step, so steps behind it went on counting edits already on disk.
- An armed reference slot now carries the step it was armed on: a grid click binds that step and returns the window to it, rather than binding somewhere the author cannot see.
- Deleting a step drops its reconcile state instead of leaving an orphan that kept writing into the draft; a scenario with no reconcilable step left closes its window rather than showing a dead end.
- A travelling repair no longer re-points a step's golden — that rule now depends on what the author actually reconciled against, not on which chips they happened to click.

## [1.9.0] - 2026-07-20

> Backfilled: 1.9.0 shipped without a changelog entry. Reconstructed from its commits.

### ✨ Added
- **Send fields: move and insert**, where position is load-bearing on the wire.
- **Exclude a send field without deleting it** — keep it in the scenario, off the message.
- **Variable badges split capture from mint**, and say which is which in words rather than by a `●`/`○` the reader has to decode.

### ⚡ Performance
- The scenario runner's polling moved off the EDT.
- Global search debounced and scanned off the UI thread.
- The template script engine warms at startup, off the UI thread.
- Per-row work is no longer rebuilt on every frame.

### 🐛 Fixed
- A `No*`-named field is only treated as a group count when its dictionary type agrees.
- Indentation is derived from the group overlay rather than guessed from shape, with one definition of depth shared by the tree walk and the wire overlay.
- Undefined counted groups survive the manual parse whole; a group count that builds no group is still kept on the message.
- The viewer reads group counts without writing to the message.
- Message rows are keyed by identity, not list position.
- Grid column widths fit every expanded message, not just the last one opened.

## [1.8.0] - 2026-07-17

### ✨ Added

#### Repeatable Scenarios
- **Scenario engine**: record a (multi-session) FIX flow and replay it as a repeatable test — a runner, a store, red/green run-results in the session window with per-tag drill-in, and a CI report.
- **Capture-driven authoring**: capture a live flow (or paste a FIX log) straight into a dedicated Scenarios workbench; every step is badged by source, and the review screen describes the scenario it is about to save.
- **Assertion vocabulary**: `fixtool_assert` with a matcher vocabulary (exact / regex / numeric & temporal bands / presence), auto-seeding, and assert-the-k-th-occurrence of a tag rather than a fragile group path.
- **Portable, git-friendly scenarios**: readable `slug--shortid` filenames, a configurable scenarios directory you can point at a git repo, and scenarios that never assert session identity so they move between environments.
- **Session mappings & save-as**: run a recorded flow against another environment's sessions, with preflight that auto-connects the sessions a run needs; "save as scenario" writes an environment copy rather than sharing mappings.
- **Variables & scope**: named capture variables per field (`${clOrdID}` not `${id0}`), shorthands `${uuid:N}`, `${utcnow}`, `${..+Nmin}`, `bindAs` to echo a venue's value back, and a visible run scope carried by `THIS_RUN` that outlives the run.
- **Traffic mode** (a strict run fails on unbound incoming messages) and **muted steps** (skipped by the runner, kept in the file).

#### Diff & Reconcile
- **The diff surface**: a failed step opens as an editable diff document in its own window — the one surface that repairs an assertion. Reconcile a failure where both sides are visible, move entries by hand with a rule that says why a move is withheld, and follow the crossing connector between paired rows.
- **Authoring on green**: delete any asserted row or add an absent assertion, plus a **fix plan** that bulk-widens numeric and temporal bands, previewed and staged as one edit.
- **Chained re-runs**: the pass carries forward — entry-scoped drop, clickable assertion rows, Save & re-run, and row-level deep links.
- **Plain diff viewer**: a scenario-less, read-only "Diff against…" / "Diff messages…" window with a one-way Seed door.

#### Embedded Terminal
- **Run Claude inside FixTool**: an embedded terminal docked as a resizable bottom pane drives the app over MCP; minimize it to reclaim space while keeping the session, and it survives a layout reflow. App-matching dark styling and a thin scrollbar.

#### Automation Control Surface
- New MCP / HTTP endpoints: `fixtool_get_scenario` (read a scenario's full definition), `fixtool_diff` / `POST /scenarios/diff` (open the diff door), relax/constrain assertions at the point of failure, and discoverable template & matcher syntax.
- **Acceptor auto-response rules** and preflight session auto-connect support self-contained round-trip tests.

### 🔧 Changed
- Scenarios moved to a dedicated workbench window; the from-scratch UI was removed in favour of capture-driven authoring.
- The reconcile/authoring surfaces were consolidated so one surface authors an assertion and the verdict is counted once, in one place.
- Captured timestamps are stamped in UTC; the demo server stamps `TransactTime`/`ValidUntilTime` in UTC.

### 🐛 Fixed
- Numerous correctness fixes across the diff/reconcile pairing rules (role-swap vs reorder, false greens, ghost interleaving), capture round-trips (pipe values, ambiguous CompIDs, pasted admin messages), STRICT-mode group handling, and the frame parser (a frame ends at `CheckSum(10)`; trailing bytes are refused, not parsed).

## [1.7.0] - 2026-06-25

### ✨ Added

#### Automation Control Surface & MCP Server
- **Agent-driven testing**: an opt-in, loopback-only (127.0.0.1) HTTP control surface lets Claude / an MCP client / curl drive FixTool for automated testing. Enable it from **Settings** (or set `FIXTOOL_CONTROL_PORT`), with an optional `X-Control-Token` for auth.
- **Embedded MCP server**: the app serves the Model Context Protocol over HTTP at `/mcp` — register it with `claude mcp add --transport http …`. A standalone Node MCP server (`tools/fixtool-mcp`) mirrors the same tools over stdio.
- **Full workflow exposed** as endpoints / MCP tools:
  - **Connections & profiles** — connect, disconnect, profiles CRUD
  - **Sending** — send, send-to-all (bulk / load testing), send-template (expressions resolved per session)
  - **Reading & inspection** — read parsed messages (`{tag, value}` fields), `wait` (block until a state or matching message), clear, select, **detail** (drive the detail-panel tag search), cross-session `search` timeline, grid `filter`, `screenshot`
  - **Config & control** — templates CRUD, dictionary read/switch, message `validate`, session/admin control (seqnum, reset-seqnum, test-request, resend-request, sequence-reset, logout, disconnect), and the built-in demo FIX server
- **Acceptor auto-response rules**: run FixTool as an acceptor that auto-replies to matching messages from a response template (e.g. `35=D` → `35=8` echoing the request's `ClOrdID` / `Symbol`), for self-contained round-trip tests.
- Documented in `docs/AUTOMATION.md`, `tools/fixtool-mcp/README.md`, and the in-app Help.

#### Context-Preserving Tag Search
- **Match-context modes** in the message detail panel: searching a nested tag (e.g. `PartyRole` inside a `NoPartyIDs` group) no longer collapses to bare matching rows that lose their context. A toggle, shown while searching, chooses how much surrounding context each match reveals:
  - **Bare** — matched rows only (previous behaviour)
  - **Identity** (default) — each matching repeating-group entry also shows its identity field (its first simple field, e.g. `PartyID`) so you can tell which entry matched
  - **Full** — the whole matching entry
- Only matching group instances are revealed, each under its group ancestor header so the match keeps its path, and the matched text is highlighted.
- Drivable via automation: **`POST /detail`** / the **`fixtool_detail_search`** MCP tool set the query and mode, so an agent can inspect a nested tag end to end (`select` → `detail_search` → `screenshot`).

### 🔧 Changed
- Flaky timing/order-sensitive integration and UI tests are now retried on CI (`org.gradle.test-retry`, CI-only) so transient failures don't fail the release build.
- Dependency and GitHub Actions upgrades; Linux installer naming fix in docs.

## [1.6.0] - 2026-06-12

### ✨ Added

#### Multi-Session Load Testing
- **Session count per profile**: Connect opens up to 100 concurrent sessions from one profile in a single click (initiators only)
- **Per-session identities**: SenderCompID, TargetCompID, Username, and Password accept `{n}`/`{nn}` numbering patterns (e.g. `LOADGEN{nn}` → LOADGEN01, LOADGEN02, …) or comma-separated lists for arbitrary server-assigned IDs; a single shared value falls back to auto-derived SessionQualifiers for servers that allow duplicate CompIDs
- **Group-aware connection panel**: aggregated status ("Logged On (3/3)"), live preview of resolved identities, group disconnect, and automatic top-up of closed sessions on reconnect

#### Bulk Send
- **Send to all sessions**: new message editor button sends the current message to every logged-on session, re-resolving template expressions per session so dynamic values (e.g. `${UUID.randomUUID()}` in MDReqID) are unique per session
- **Per-session template variables**: `${sessionIndex}`, `${sessionQualifier}`, `${sessionTitle}`, `${sessionSenderCompID}` available in message templates (also for single-session Send)

#### Release
- **macOS installer**: release builds now include the macOS DMG (Apple Silicon)

---

## [1.5.0] - 2026-03-18

### ✨ Added

#### FIX Acceptor Mode
- **FIX Acceptor mode** with connection type selection (Initiator/Acceptor) and accept port configuration

#### UI Improvements
- **Tag numbers in expanded groups**: Show tag number in GroupHeaderRow aligned with FieldRow tag column
- **Session tab sync control**: Setting to disable auto-sync of session tab to editor connection dropdown

### 🔧 Fixed

- **Save Message Template dialog**: Add scroll to Share with Users list
- **Message list checkboxes**: Fix checkboxes not selectable when messages reach the bottom of the session window

---

## [1.4.1] - 2026-02-07

### 🔧 Fixed

- **Saved message visibility**: Show untagged saved messages in message browser for all profiles, not just the profile that created them

---

## [1.4.0] - 2026-02-06

### ✨ Added

#### Connection Settings
- **Socket connection timeout** setting for configurable connection timeouts
- **Reconnect interval** setting for controlling delay between reconnection attempts
- **Auto-reconnect toggle** to enable/disable automatic reconnection per session

#### Improved Message Parsing
- **Re-parse incoming messages using wire bytes** for complete repeating group fields, ensuring all group entries are correctly captured

#### UI Improvements
- **Demo Server moved from toolbar to Connection Panel** for a cleaner toolbar and more logical grouping
- **Scroll-to-bottom button** in session header bar and Tab View for quick navigation to latest messages
- **Improved scrollbar visibility** with grey scrollbar color for better contrast

### 🔧 Fixed

- **Auto-reconnect behavior**: Stop reconnection after any disconnect when auto-reconnect is disabled
- **Send button activation**: Enable send button by activating session when auto-selecting profile on connect
- **Fix Logs grid layout**: Sync separator row dimensions with message summary rows for consistent alignment

---

## [1.3.1] - 2026-01-23

### 🔧 Fixed

- **FIX 5.0+ session headers**: Use FIXT.1.1 as BeginString and set default ApplVerID (FIX.5.0SP2) when transport dictionary is configured, ensuring proper FIX 5.0+ protocol compliance

---

## [1.3.0] - 2026-01-23

### ✨ Added

#### Transport Dictionary Support
- **Separate transport dictionary configuration** for FIX 5.0+ sessions
- New `defaultTransportDictionary` field in Settings for custom transport dictionaries
- Transport dictionary field visible in Settings when using custom dictionaries
- Supports custom setups where app dictionary has incorrect version headers

#### Demo Server FIX Version Selection
- **FIX version dropdown** replaces toggle button in toolbar
- Select any FIX version (4.0, 4.1, 4.2, 4.3, 4.4, 5.0, 5.0 SP1, 5.0 SP2)
- Demo profiles automatically configured for selected version
- Current running version displayed in toolbar

### 🔧 Fixed

- **Dictionary loading for FIX 5.0+**: Transport dictionary now used regardless of detected version, allowing proper FIX 5.0+ connections even with mislabeled dictionaries

---

## [1.2.2] - 2026-01-23

### 🔧 Fixed

- **Backward compatible dictionary default**: Changed `useBundledDictionary` default from `true` to `false` to prevent custom dictionaries from being silently ignored during settings migration. Users upgrading from v1.1.0 now have their custom dictionaries respected.
- **FIX version preservation in QuickFIX messages**: Fixed header/trailer tag detection to properly preserve the FIX version when constructing QuickFIX messages

---

## [1.2.1] - 2026-01-22

### 🔧 Fixed

- **Template variable sharing on send**: Variables defined in earlier fields (e.g., `${abc = uuid}`) are now available to later fields (e.g., `${abc}`) when validating before sending messages

---

## [1.2.0] - 2026-01-21

### ✨ Added

#### Multi-FIX Version Support
- **FIX 4.0 through 4.4** protocol version support
- **FIX 5.0, 5.0 SP1, and 5.0 SP2** protocol version support
- Seamless switching between FIX versions in session configuration

#### Enhanced FX Demo Server
- **Bundled FIX dictionary** for FX-specific message types
- **Pre-built templates** for common FX trading workflows
- Improved demo experience with realistic FX trading scenarios

### 🔧 Fixed

- **Profile session map sync**: Fixed issue where profileToSessionMap wasn't updated when tabs are reordered
- **Template variable sharing**: Variables are now correctly shared across fields during template validation

---

## [1.1.0] - 2026-01-18

### ✨ Added

#### FIX Message Latency Tracking
- **Packet-level timestamp capture** for accurate latency measurement
- **Latency displayed in message list** showing round-trip time for request/response pairs
- **Logon latency tracking** (35=A → 35=A) to measure session establishment time
- **Timestamps captured at QuickFIX/J callback layer** for minimal overhead
- **Support for TLS and localhost connections** with improved accuracy

#### Expandable Long Field Values
- **Click-to-expand** for long tag values in the Message Detail Panel
- Values longer than 50 characters show a `▼` indicator and are truncated with "..."
- **Click to toggle** between collapsed (single line) and expanded (full content) views
- Visual feedback with `▲`/`▼` indicators for expand/collapse state

### 📝 Documentation

- Added **Latency Measurement** section to help documentation explaining how latency tracking works

---

## [1.0.3] - 2026-01-15

### ✨ Added

#### Timestamp Offset Shorthand
- **New date/time offset syntax** for flexible timestamp manipulation in templates
- Supported units: `h` (hours), `d` (days), `w` (weeks), `m` (months), `y` (years)
- Examples:
  - `${now+1h}` → 1 hour from now
  - `${now-2d}` → 2 days ago
  - `${now+1w}` → 1 week from now
  - `${now+1m}` → 1 month from now
  - `${now-1y}` → 1 year ago
- **Custom format support**: `${now+1d:yyyyMMdd}` → tomorrow in date-only format
- **Variable assignment support**: `${expiry = now+30d}`
- Case-insensitive units (h/H, d/D, w/W, m/M, y/Y)

#### Multi-Select Message Operations
- **Multi-select FIX messages** in the message list with Shift+Click and Ctrl/Cmd+Click
- **Bulk copy**: Copy multiple selected messages to clipboard
- **Bulk save to file**: Save selected messages to a file

### 🔧 Fixed

- **Profile dropdown sync**: Message editor profile dropdown now correctly syncs with active session tab

---

## [1.0.2] - 2025-01-30

### 🔧 Fixed

#### Critical: Saved Message Race Condition
- **Fixed race condition** in `SavedMessagesService` that could cause message loss
- Added **thread-safe synchronization** using `synchronized(fileLock)` for all read/write operations
- Eliminated load-modify-save race condition when multiple operations happen concurrently
- **Impact:** No more lost messages when auto-save and manual save occur simultaneously

### 🧹 Removed

#### Backward Compatibility Cleanup (Post v1.0.0)
- Removed legacy `messagesByProfile` field and migration code
- Removed deprecated `profileId` field from `SavedFixMessage`
- Removed auto-deduplication on every load (performance improvement)
- Cleaned up backward compatibility code from `getAllUserTags()`
- **Result:** Cleaner codebase, better performance, simpler data model

### ✨ Added

#### Migration Version System
- Implemented schema versioning system (`CURRENT_VERSION = 1`)
- Added `migrate()` framework for future version upgrades
- Version checking on load with automatic migration support
- Ready for v1.1, v1.2+ migrations

#### Comprehensive Sharing Tests
- **17 new integration tests** in `MessageSharingTest.kt`
- Complete coverage of multi-user sharing scenarios:
  - Share/unshare with multiple users
  - Clone and modify messages independently
  - Complex multi-user workflows
  - Edge cases and error conditions
- **Total test count:** 632+ tests (all passing)

#### Installation & Distribution Documentation
- **INSTALLATION.md**: Complete cross-platform installation guide
  - macOS security warning explanations (unsigned app is normal for open source)
  - Right-click workaround and terminal commands
  - Windows SmartScreen bypass instructions
  - Linux installation steps
- **QUICK_INSTALL_GUIDE.md**: One-page TL;DR installation reference
- **DISTRIBUTION_GUIDE.md**: Developer guide for unsigned distribution
  - Build script for all platforms
  - GitHub release template
  - FAQ for open source distribution
- **MESSAGE_SHARING_TEST_COVERAGE.md**: Detailed test scenario documentation
- Updated README.md with clear macOS security section

#### Build Automation
- **scripts/build-release.sh**: Automated build script for all platforms
  - Builds DMG (macOS), MSI (Windows), DEB (Linux)
  - Generates SHA-256 checksums automatically
  - Creates CHECKSUMS.txt for user verification
  - No signing required (standard for open source projects)

### 📝 Changed

- Updated build configuration to support optional code signing (commented out by default)
- Improved user communication about security warnings on macOS/Windows
- Emphasized open source nature and comparison to major projects (Audacity, OBS Studio, GIMP)

### 🧪 Testing

- All 632+ tests passing
- New test categories:
  - Basic sharing operations (3 tests)
  - Cloning workflows (3 tests)
  - Complex multi-user scenarios (4 tests)
  - Edge cases (3 tests)
  - Sharing + cloning combined (2 tests)
  - Metadata preservation (2 tests)

### 📦 Distribution

- **Unsigned distribution** (no $99/year Apple fee, no $300/year Windows certificate)
- Clear user instructions for security warning bypass
- Checksums provided for download verification
- Professional documentation for open source approach

---

## [1.0.0] - 2025-01-29

### 🎉 Initial Release

FixTool 1.0.0 is a professional FIX protocol testing tool built with Kotlin Multiplatform and Compose Desktop.

### ✨ Core Features

#### Message Management
- **Template Browser**: Searchable browser with IntelliJ-style search, grouping by type, user, and favorites
- **Save as New**: Duplicate existing templates with one click
- **Duplicate Prevention**: Global duplicate name checking with case-insensitive validation

#### Template System
- **Dynamic Templates**: Use expressions like `${UUID.randomUUID()}` and `${currentTimestamp()}`
- **Message References**: Reference incoming/outgoing messages with shorthand syntax
- **Validation**: Real-time template expression validation with error blocking

#### Profile Management
- **Multi-Profile Support**: Manage multiple FIX sessions with different configurations
- **Profile Dropdown**: Smart profile dropdown with connection status indicators
- **Disconnected Profiles**: Select and manage disconnected profiles for template editing

#### User Experience
- **Layout Options**: Choose between horizontal and vertical split layouts
- **Session Management**: Configurable buffer size for message retention
- **Search**: Fast IntelliJ-style search across all templates

### 🛠️ Technical
- **Kotlin Multiplatform**: Built with modern Kotlin and Compose Desktop
- **QuickFIX/J**: Industry-standard FIX engine integration
- **JVM 17**: Requires Java 17 or higher
- **Cross-Platform**: Native installers for Windows, macOS, and Linux

### 📦 Installation

**System Requirements:**
- Java 17 or higher
- Windows 10/11, macOS 11+, or Linux (Ubuntu 20.04+)

**Platform-Specific:**
- Windows: MSI installer
- macOS: DMG installer
- Linux: DEB package (Debian/Ubuntu)

### 🧪 Testing

- 617 unit and integration tests
- Comprehensive test coverage for:
  - Template evaluation and validation
  - Duplicate checking and migration
  - Message filtering and search
  - Profile management
  - Favorites system

### 📝 Notes

This is the first stable release of FixTool. All core features are production-ready and battle-tested.

For bug reports and feature requests, please visit: https://github.com/amitgupta1202/FixTool/issues

---

## Future Releases

See [GitHub Releases](https://github.com/amitgupta1202/FixTool/releases) for upcoming versions.
