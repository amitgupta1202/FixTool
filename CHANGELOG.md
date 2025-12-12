# Changelog

All notable changes to FixTool will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
