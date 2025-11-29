# Changelog

All notable changes to FixTool will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
