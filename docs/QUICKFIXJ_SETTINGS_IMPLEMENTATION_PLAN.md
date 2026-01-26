# QuickFIX/J Settings Implementation Plan

## Overview

This document outlines the implementation plan for integrating missing QuickFIX/J configuration settings into the FixTool application UI. The settings are prioritized based on common production needs and user impact.

**Document Version:** 1.0
**Created:** January 2025
**Status:** Planning

---

## Table of Contents

1. [High Priority Features](#1-high-priority-features)
2. [Medium Priority Features](#2-medium-priority-features)
3. [UI/UX Considerations](#3-uiux-considerations)
4. [Data Model Changes](#4-data-model-changes)
5. [Implementation Phases](#5-implementation-phases)
6. [Testing Strategy](#6-testing-strategy)

---

## 1. High Priority Features

### 1.1 Logon/Logout Timeout Settings

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `LogonTimeout` | Integer | 10 | Seconds to wait for logon response before disconnecting |
| `LogoutTimeout` | Integer | 2 | Seconds to wait for logout response before disconnecting |

**Implementation Details:**

- **UI Location:** Advanced Settings section, near existing timeout settings (SocketConnectTimeout, ReconnectInterval)
- **UI Component:** Two numeric input fields with validation (min: 1, max: 300)
- **Grouping:** Create a "Timeout Settings" subsection containing:
  - Socket Connect Timeout (existing)
  - Reconnect Interval (existing)
  - Logon Timeout (new)
  - Logout Timeout (new)

**Code Changes Required:**
1. Add `logonTimeout` and `logoutTimeout` fields to `ConnectionConfig` data class
2. Update `ConnectionSettingsPanel` to include new input fields
3. Update `QuickFixConfigBuilder` to emit `LogonTimeout` and `LogoutTimeout` settings
4. Add migration logic for existing saved profiles (use defaults)

**Validation Rules:**
- LogonTimeout: 1-300 seconds (default: 10)
- LogoutTimeout: 1-60 seconds (default: 2)

---

### 1.2 Session Scheduling (StartTime/EndTime/TimeZone)

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `StartTime` | String (HH:MM:SS) | None | Time when session activates |
| `EndTime` | String (HH:MM:SS) | None | Time when session deactivates |
| `TimeZone` | String | UTC | Timezone for start/end times |

**Implementation Details:**

- **UI Location:** New "Session Schedule" subsection in Advanced Settings
- **UI Components:**
  - Enable Schedule toggle (when disabled, session runs 24/7)
  - Start Time picker (HH:MM:SS format)
  - End Time picker (HH:MM:SS format)
  - TimeZone dropdown (populated from Java TimeZone IDs)
- **Conditional Display:** Time pickers only visible when schedule is enabled

**Code Changes Required:**
1. Add fields to `ConnectionConfig`:
   ```kotlin
   val scheduleEnabled: Boolean = false
   val startTime: String? = null  // HH:MM:SS
   val endTime: String? = null    // HH:MM:SS
   val timeZone: String = "UTC"
   ```
2. Create `SessionScheduleSection` composable component
3. Update `QuickFixConfigBuilder` to conditionally emit schedule settings
4. Add timezone list utility (filter to common business timezones)

**Validation Rules:**
- StartTime/EndTime must be valid HH:MM:SS format
- EndTime must be different from StartTime
- TimeZone must be valid Java TimeZone ID

**Common TimeZones to Display:**
- UTC, America/New_York, America/Chicago, America/Los_Angeles
- Europe/London, Europe/Paris, Europe/Frankfurt
- Asia/Tokyo, Asia/Hong_Kong, Asia/Singapore, Asia/Mumbai

---

### 1.3 Sub-Identifiers (SenderSubID/TargetSubID)

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `SenderSubID` | String | None | Sub-identifier for sender (tag 50) |
| `TargetSubID` | String | None | Sub-identifier for target (tag 57) |

**Implementation Details:**

- **UI Location:** Basic Connection Settings, below SenderCompID/TargetCompID
- **UI Components:** Two optional text input fields
- **Visibility:** Always visible but clearly marked as optional

**Code Changes Required:**
1. Add fields to `ConnectionConfig`:
   ```kotlin
   val senderSubID: String = ""
   val targetSubID: String = ""
   ```
2. Update connection form UI to include new fields
3. Update `QuickFixConfigBuilder` to emit when non-empty
4. Update session ID display to include SubIDs when present

**Validation Rules:**
- Optional fields, no validation required
- Max length: 64 characters (FIX standard)

---

### 1.4 Message Persistence Control

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `PersistMessages` | Boolean | Y | Whether to persist messages to storage |

**Implementation Details:**

- **UI Location:** Advanced Settings, new "Storage" subsection
- **UI Component:** Checkbox with descriptive label
- **Default:** Enabled (matches QuickFIX/J default)

**Code Changes Required:**
1. Add field to `ConnectionConfig`:
   ```kotlin
   val persistMessages: Boolean = true
   ```
2. Add checkbox to Advanced Settings
3. Update `QuickFixConfigBuilder` to emit `PersistMessages=N` when disabled

**User Warning:**
Display warning when disabled: "Disabling message persistence means messages cannot be recovered after restart. Sequence number synchronization may fail."

---

### 1.5 Latency Checking

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `CheckLatency` | Boolean | Y | Enable/disable latency checking |
| `MaxLatency` | Integer | 120 | Maximum allowed latency in seconds |

**Implementation Details:**

- **UI Location:** Advanced Settings > Validation subsection
- **UI Components:**
  - Checkbox: "Check Message Latency"
  - Numeric input: "Max Latency (seconds)" - only visible when check enabled
- **Default:** Enabled with 120 seconds

**Code Changes Required:**
1. Add fields to `ConnectionConfig`:
   ```kotlin
   val checkLatency: Boolean = true
   val maxLatency: Int = 120
   ```
2. Add UI components with conditional visibility
3. Update `QuickFixConfigBuilder`

**Validation Rules:**
- MaxLatency: 1-3600 seconds

---

## 2. Medium Priority Features

### 2.1 Proxy Support

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `ProxyType` | String | None | Proxy type: "http" or "socks" |
| `ProxyHost` | String | None | Proxy server hostname |
| `ProxyPort` | Integer | None | Proxy server port |
| `ProxyUser` | String | None | Proxy authentication username |
| `ProxyPassword` | String | None | Proxy authentication password |
| `ProxyVersion` | String | Auto | Protocol version (Socks: 4/5, HTTP: 1.0/1.1) |
| `ProxyDomain` | String | None | Domain for HTTP NTLM auth |
| `ProxyWorkstation` | String | None | Workstation for HTTP NTLM auth |

**Implementation Details:**

- **UI Location:** New collapsible "Proxy Settings" section in Advanced Settings
- **UI Components:**
  - Enable Proxy toggle
  - Proxy Type dropdown (HTTP, SOCKS4, SOCKS5)
  - Host/Port inputs
  - Authentication subsection (collapsible):
    - Username/Password
    - Domain/Workstation (only for HTTP type)

**Code Changes Required:**
1. Create `ProxyConfig` data class:
   ```kotlin
   data class ProxyConfig(
       val enabled: Boolean = false,
       val type: ProxyType = ProxyType.SOCKS5,
       val host: String = "",
       val port: Int = 1080,
       val username: String = "",
       val password: String = "",
       val domain: String = "",      // HTTP only
       val workstation: String = ""  // HTTP only
   )

   enum class ProxyType(val quickfixValue: String, val defaultPort: Int) {
       HTTP("http", 8080),
       SOCKS4("socks", 1080),  // ProxyVersion=4
       SOCKS5("socks", 1080)   // ProxyVersion=5
   }
   ```
2. Add `proxyConfig` to `ConnectionConfig`
3. Create `ProxySettingsSection` composable
4. Update `QuickFixConfigBuilder` to emit proxy settings

**Validation Rules:**
- Host: Required when proxy enabled
- Port: 1-65535
- Password: Masked input field

---

### 2.2 Failover/Backup Hosts

**QuickFIX/J Parameters:**
| Parameter | Type | Description |
|-----------|------|-------------|
| `SocketConnectHost<n>` | String | Backup host (n=1,2,3...) |
| `SocketConnectPort<n>` | Integer | Backup port (n=1,2,3...) |

**Implementation Details:**

- **UI Location:** Basic Connection Settings, below primary Host/Port
- **UI Components:**
  - "Add Backup Server" button
  - Dynamic list of host/port pairs with remove buttons
  - Drag-and-drop reordering (optional enhancement)
- **Maximum:** 5 backup servers

**Code Changes Required:**
1. Add field to `ConnectionConfig`:
   ```kotlin
   val backupServers: List<ServerAddress> = emptyList()

   data class ServerAddress(
       val host: String,
       val port: Int
   )
   ```
2. Create `BackupServersSection` composable with dynamic list
3. Update `QuickFixConfigBuilder` to emit numbered settings:
   ```
   SocketConnectHost1=backup1.example.com
   SocketConnectPort1=9876
   ```

**UI Behavior:**
- Show current failover order
- Allow adding up to 5 backup servers
- Display connection attempt order (Primary → Backup1 → Backup2...)

---

### 2.3 Socket Tuning Options

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `SocketKeepAlive` | Boolean | None | Enable TCP keep-alive |
| `SocketTcpNoDelay` | Boolean | Y | Disable Nagle's algorithm |
| `SocketReceiveBufferSize` | Integer | None | Receive buffer size (bytes) |
| `SocketSendBufferSize` | Integer | None | Send buffer size (bytes) |
| `SocketLinger` | Integer | None | Linger timeout on close (seconds) |
| `SocketSynchronousWrites` | Boolean | N | Write messages synchronously |
| `SocketSynchronousWriteTimeout` | Integer | 30000 | Sync write timeout (ms) |
| `MaxScheduledWriteRequests` | Integer | 0 | Max queued writes before disconnect |

**Implementation Details:**

- **UI Location:** New collapsible "Socket Options" section in Advanced Settings
- **Target Users:** Advanced users only
- **UI Components:**
  - SocketKeepAlive: Checkbox (default: unchecked/system default)
  - SocketTcpNoDelay: Checkbox (default: checked)
  - Buffer sizes: Optional numeric inputs with "Use System Default" option
  - Synchronous writes: Checkbox with timeout input

**Code Changes Required:**
1. Create `SocketConfig` data class:
   ```kotlin
   data class SocketConfig(
       val keepAlive: Boolean? = null,        // null = system default
       val tcpNoDelay: Boolean = true,
       val receiveBufferSize: Int? = null,    // null = system default
       val sendBufferSize: Int? = null,
       val linger: Int? = null,               // null = disabled
       val synchronousWrites: Boolean = false,
       val synchronousWriteTimeout: Int = 30000,
       val maxScheduledWriteRequests: Int = 0
   )
   ```
2. Add `socketConfig` to `ConnectionConfig`
3. Create `SocketOptionsSection` composable
4. Update `QuickFixConfigBuilder`

**Validation Rules:**
- Buffer sizes: 1024 - 10485760 (1KB - 10MB)
- Linger: 0-300 seconds
- SynchronousWriteTimeout: 1000-300000 ms

---

### 2.4 Weekly Session Schedule

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `StartDay` | String | None | Start day (e.g., "Monday") |
| `EndDay` | String | None | End day (e.g., "Friday") |
| `Weekdays` | String | None | Active days (comma-separated) |
| `NonStopSession` | Boolean | N | Session never resets |

**Implementation Details:**

- **UI Location:** Session Schedule subsection (extension of 1.2)
- **UI Components:**
  - Schedule Type selector:
    - "Daily" (uses StartTime/EndTime only)
    - "Weekly Range" (StartDay to EndDay)
    - "Specific Days" (Weekdays list)
    - "Non-Stop" (24/7, no reset)
  - Day selectors based on type

**Code Changes Required:**
1. Extend schedule fields in `ConnectionConfig`:
   ```kotlin
   enum class ScheduleType {
       DAILY, WEEKLY_RANGE, SPECIFIC_DAYS, NON_STOP
   }

   val scheduleType: ScheduleType = ScheduleType.DAILY
   val startDay: DayOfWeek? = null
   val endDay: DayOfWeek? = null
   val weekdays: Set<DayOfWeek> = emptySet()
   val nonStopSession: Boolean = false
   ```
2. Update `SessionScheduleSection` with conditional UI
3. Update `QuickFixConfigBuilder`

---

### 2.5 Additional Validation Settings

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `ValidateSequenceNumbers` | Boolean | Y | Check sequence numbers |
| `ValidateChecksum` | Boolean | Y | Validate message checksums |
| `ValidateUnorderedGroupFields` | Boolean | Y | Validate field order in groups |
| `CheckCompID` | Boolean | Y | Verify CompID matches |
| `AllowUnknownMsgFields` | Boolean | N | Allow unknown fields |
| `RejectGarbledMessage` | Boolean | N | Reject protocol-invalid messages |
| `RejectInvalidMessage` | Boolean | Y | Reject dict-invalid messages |
| `RequiresOrigSendingTime` | Boolean | Y | Require OrigSendingTime on PossDup |

**Implementation Details:**

- **UI Location:** Extend existing Validation settings (currently in App Settings)
- **Decision:** Keep as global app settings OR move to per-connection settings
- **Recommendation:** Make these per-connection settings for flexibility

**Code Changes Required:**
1. Add validation fields to `ConnectionConfig`:
   ```kotlin
   data class ValidationConfig(
       val validateFieldsOutOfOrder: Boolean = true,
       val validateFieldsHaveValues: Boolean = true,
       val validateUserDefinedFields: Boolean = true,
       val validateIncomingMessage: Boolean = true,
       val validateSequenceNumbers: Boolean = true,
       val validateChecksum: Boolean = true,
       val validateUnorderedGroupFields: Boolean = true,
       val checkCompID: Boolean = true,
       val allowUnknownMsgFields: Boolean = false,
       val rejectGarbledMessage: Boolean = false,
       val rejectInvalidMessage: Boolean = true,
       val requiresOrigSendingTime: Boolean = true
   )
   ```
2. Move validation settings from global to per-connection
3. Provide "Reset to Defaults" button
4. Consider adding presets: "Strict", "Lenient", "Custom"

---

### 2.6 Error Handling Options

**QuickFIX/J Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `ResetOnError` | Boolean | N | Auto-reset sequence numbers on error |
| `DisconnectOnError` | Boolean | N | Auto-disconnect on error |
| `RejectMessageOnUnhandledException` | Boolean | N | Send Reject on app exception |
| `ContinueInitializationOnError` | Boolean | N | Continue startup on session error |

**Implementation Details:**

- **UI Location:** Advanced Settings > Error Handling subsection
- **UI Components:** Four checkboxes with clear descriptions

**Code Changes Required:**
1. Add fields to `ConnectionConfig`:
   ```kotlin
   val resetOnError: Boolean = false
   val disconnectOnError: Boolean = false
   val rejectOnException: Boolean = false
   val continueOnInitError: Boolean = false
   ```
2. Create `ErrorHandlingSection` composable
3. Update `QuickFixConfigBuilder`

**User Guidance:**
- ResetOnError: "Warning: May cause message loss"
- DisconnectOnError: "Recommended for production environments"

---

## 3. UI/UX Considerations

### 3.1 Advanced Settings Reorganization

Current structure is becoming complex. Proposed new organization:

```
Advanced Settings
├── Timeout Settings
│   ├── Socket Connect Timeout
│   ├── Reconnect Interval
│   ├── Logon Timeout (new)
│   └── Logout Timeout (new)
│
├── Session Schedule (new section)
│   ├── Enable Schedule
│   ├── Schedule Type
│   ├── Start/End Time
│   ├── Days Configuration
│   └── TimeZone
│
├── Sequence Number Reset
│   ├── Reset on Logon
│   ├── Reset on Logout
│   └── Reset on Disconnect
│
├── Error Handling (new section)
│   ├── Reset on Error
│   ├── Disconnect on Error
│   └── Reject on Exception
│
├── Validation Settings
│   ├── [Existing settings]
│   ├── Check Latency (new)
│   └── [Additional validation options]
│
├── Proxy Settings (new section)
│   └── [Proxy configuration]
│
├── Socket Options (new section)
│   └── [Socket tuning options]
│
├── SSL/TLS Settings
│   └── [Existing SSL settings]
│
└── Custom Parameters
    └── [Existing custom params]
```

### 3.2 Collapsible Sections

Implement collapsible sections to reduce visual clutter:
- Default collapsed: Proxy, Socket Options, Custom Parameters
- Default expanded: Timeout Settings, Reset Options

### 3.3 Presets/Profiles

Consider adding configuration presets:
- **Development:** Lenient validation, verbose logging
- **Production:** Strict validation, error handling enabled
- **Testing:** All validation disabled, non-stop session

### 3.4 Import/Export Enhancement

Ensure new settings are included in profile import/export functionality.

---

## 4. Data Model Changes

### 4.1 ConnectionConfig Updates

```kotlin
data class ConnectionConfig(
    // Existing fields...

    // New High Priority Fields
    val logonTimeout: Int = 10,
    val logoutTimeout: Int = 2,
    val senderSubID: String = "",
    val targetSubID: String = "",
    val scheduleEnabled: Boolean = false,
    val startTime: String? = null,
    val endTime: String? = null,
    val timeZone: String = "UTC",
    val persistMessages: Boolean = true,
    val checkLatency: Boolean = true,
    val maxLatency: Int = 120,

    // New Medium Priority Fields
    val proxyConfig: ProxyConfig = ProxyConfig(),
    val backupServers: List<ServerAddress> = emptyList(),
    val socketConfig: SocketConfig = SocketConfig(),
    val scheduleType: ScheduleType = ScheduleType.DAILY,
    val startDay: String? = null,
    val endDay: String? = null,
    val weekdays: List<String> = emptyList(),
    val nonStopSession: Boolean = false,
    val validationConfig: ValidationConfig = ValidationConfig(),
    val resetOnError: Boolean = false,
    val disconnectOnError: Boolean = false,
    val rejectOnException: Boolean = false,
    val continueOnInitError: Boolean = false
)
```

### 4.2 Migration Strategy

For existing saved profiles:
1. Load existing profile
2. Apply default values for new fields
3. Save updated profile on first load
4. Increment profile schema version

---

## 5. Implementation Phases

### Phase 1: High Priority (Core Functionality)
**Estimated Scope:** Core connection settings

| Feature | Components Affected |
|---------|---------------------|
| Logon/Logout Timeout | ConnectionConfig, ConnectionSettingsPanel, QuickFixConfigBuilder |
| SenderSubID/TargetSubID | ConnectionConfig, ConnectionForm, QuickFixConfigBuilder |
| PersistMessages | ConnectionConfig, AdvancedSettings, QuickFixConfigBuilder |
| CheckLatency/MaxLatency | ConnectionConfig, AdvancedSettings, QuickFixConfigBuilder |

**Dependencies:** None

### Phase 2: Session Scheduling
**Estimated Scope:** Time-based session control

| Feature | Components Affected |
|---------|---------------------|
| StartTime/EndTime/TimeZone | ConnectionConfig, new SessionScheduleSection |
| Weekly Schedule (StartDay/EndDay/Weekdays) | Extension of above |
| NonStopSession | Extension of above |

**Dependencies:** Phase 1 (data model foundation)

### Phase 3: Network Features
**Estimated Scope:** Proxy and failover support

| Feature | Components Affected |
|---------|---------------------|
| Proxy Support | ProxyConfig, new ProxySettingsSection |
| Backup Servers | ServerAddress list, new BackupServersSection |

**Dependencies:** Phase 1

### Phase 4: Advanced Options
**Estimated Scope:** Socket tuning and validation

| Feature | Components Affected |
|---------|---------------------|
| Socket Options | SocketConfig, new SocketOptionsSection |
| Extended Validation | ValidationConfig, updated ValidationSection |
| Error Handling | ConnectionConfig, new ErrorHandlingSection |

**Dependencies:** Phase 1

### Phase 5: UI Polish
**Estimated Scope:** UX improvements

| Feature | Description |
|---------|-------------|
| Collapsible Sections | Implement expand/collapse for all advanced sections |
| Configuration Presets | Add preset profiles (Dev/Prod/Test) |
| Validation & Help | Add tooltips, validation messages, help links |

**Dependencies:** Phases 1-4

---

## 6. Testing Strategy

### 6.1 Unit Tests

- Test new data model serialization/deserialization
- Test QuickFixConfigBuilder output for all new settings
- Test validation logic for new fields
- Test migration of existing profiles

### 6.2 Integration Tests

- Test actual QuickFIX/J session creation with new settings
- Test proxy connectivity (requires proxy server in test environment)
- Test failover behavior with backup servers
- Test session scheduling (start/stop at configured times)

### 6.3 UI Tests

- Test all new form fields render correctly
- Test conditional visibility (e.g., proxy auth fields)
- Test form validation messages
- Test profile save/load with new settings

### 6.4 Manual Testing Checklist

- [ ] Logon timeout triggers correctly
- [ ] Logout timeout triggers correctly
- [ ] Session starts/stops at scheduled times
- [ ] Proxy connection works (HTTP and SOCKS)
- [ ] Failover switches to backup server on primary failure
- [ ] Socket options affect connection behavior
- [ ] Validation settings are applied correctly
- [ ] Error handling options work as expected
- [ ] Profile migration preserves existing settings
- [ ] New settings export/import correctly

---

## Appendix A: QuickFIX/J Configuration Reference

For complete QuickFIX/J documentation, see:
- [QuickFIX/J Configuration Guide](https://www.quickfixj.org/usermanual/2.3.0/usage/configuration.html)
- [QuickFIX/J GitHub Repository](https://github.com/quickfix-j/quickfixj)

---

## Appendix B: Related Files in Codebase

Key files that will require modification:

```
composeApp/src/commonMain/kotlin/
├── model/
│   └── ConnectionConfig.kt          # Data model
├── ui/
│   └── components/
│       └── ConnectionSettingsPanel.kt  # Main settings UI
└── service/
    └── QuickFixConfigBuilder.kt     # Config generation
```

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | January 2025 | - | Initial document |
