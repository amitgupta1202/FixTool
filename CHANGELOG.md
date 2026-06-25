# Changelog

All notable changes to FixTool will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
