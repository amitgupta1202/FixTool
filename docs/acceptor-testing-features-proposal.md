# FixTool Acceptor Testing Features - Proposal

## Background

FixTool currently supports FIX Acceptor mode at the infrastructure level — it can listen on a port, accept incoming FIX connections, handle logon/logout, and display messages. However, all responses must be manually crafted and sent by the tester, making it impractical for real-world acceptor testing.

This document proposes a set of features that would make FixTool a complete acceptor testing tool, capable of simulating exchange/broker behavior for QA and development teams.

---

## Feature 1: Auto-Response Rules Engine

### Problem
When a client sends a NewOrderSingle (35=D) to FixTool in acceptor mode, nothing happens. The tester must manually build and send an ExecutionReport. This is slow, error-prone, and blocks any automated client testing.

### Proposed Solution
A rule engine that automatically sends a response when a specific message type is received.

### How It Works
- User defines rules in a new "Auto-Response Rules" panel (accessible in acceptor mode)
- Each rule consists of:
  - **Trigger**: Incoming message type (e.g., 35=D)
  - **Response**: Outgoing message type (e.g., 35=8)
  - **Field Mappings**: Which fields to copy from incoming to outgoing (e.g., ClOrdID, Symbol, Side)
  - **Static Fields**: Fixed values to set on the response (e.g., OrdStatus=0, ExecType=0)
  - **Auto-Generated Fields**: Fields that get unique values per response (e.g., OrderID, ExecID)
  - **Delay**: Optional delay before sending (e.g., 200ms to simulate processing)

### Example Rule
```
Trigger:  35=D (NewOrderSingle)
Response: 35=8 (ExecutionReport)
Copy:     11 (ClOrdID), 55 (Symbol), 54 (Side), 38 (OrderQty), 44 (Price)
Set:      150=0 (ExecType=New), 39=0 (OrdStatus=New), 14=0 (CumQty), 151={38} (LeavesQty=OrderQty)
Generate: 37 (OrderID), 17 (ExecID)
Delay:    100ms
```

### UI Considerations
- Rules list panel with add/edit/delete/enable/disable per rule
- Rules should be saveable per connection profile
- Import/export rules as JSON for sharing across team

### Acceptance Criteria
- User can define auto-response rules via UI
- When an incoming message matches a trigger, the response is automatically sent
- Field mappings correctly copy values from incoming message
- Auto-generated fields produce unique values
- Rules can be enabled/disabled individually
- Rules persist with the connection profile

---

## Feature 2: Response Templates Library

### Problem
Even for manual testing, building an ExecutionReport from scratch every time is tedious. The tester needs to know all the required fields and fill them in correctly.

### Proposed Solution
Pre-built, context-aware response templates that auto-populate fields from the incoming message.

### How It Works
- When viewing an incoming message (e.g., NewOrderSingle), a "Reply With..." button appears
- Clicking it shows a list of appropriate response templates:
  - Order Acknowledgment (35=8, OrdStatus=New)
  - Partial Fill (35=8, OrdStatus=PartiallyFilled)
  - Full Fill (35=8, OrdStatus=Filled)
  - Order Reject (35=8, OrdStatus=Rejected)
  - Cancel Acknowledge (35=8, OrdStatus=Canceled)
  - Cancel Reject (35=9)
  - Business Message Reject (35=j)
- Selecting a template opens the message editor pre-populated with:
  - Fields copied from the incoming message (ClOrdID, Symbol, etc.)
  - Appropriate status fields pre-set
  - Auto-generated OrderID/ExecID
- User can modify any field before sending

### Template Definition
```
Name: "Full Fill"
Applies To: 35=D (NewOrderSingle)
Pre-populate:
  - Copy: 11→11 (ClOrdID), 55→55 (Symbol), 54→54 (Side), 44→44 (Price)
  - Set: 35=8, 150=F (ExecType=Fill), 39=2 (OrdStatus=Filled)
  - Set: 14={38} (CumQty=OrderQty), 151=0 (LeavesQty=0), 32={38} (LastQty=OrderQty)
  - Generate: 37 (OrderID), 17 (ExecID)
Editable: true (user can modify before sending)
```

### UI Considerations
- Right-click context menu or button on incoming messages: "Reply With..."
- Template editor for creating custom templates
- Ship with sensible defaults for common FIX workflows
- Templates should be shareable (export/import)

### Acceptance Criteria
- "Reply With..." action available on incoming messages in acceptor mode
- Default templates provided for common order flow responses
- Fields auto-populated from incoming message
- User can edit pre-populated message before sending
- Custom templates can be created, edited, and deleted

---

## Feature 3: Multi-Session Acceptor Support

### Problem
Currently, FixTool generates a single `[SESSION]` block in the QuickFIX config with one SenderCompID/TargetCompID pair. A real exchange accepts connections from many different clients simultaneously.

### Proposed Solution
Support multiple concurrent sessions in acceptor mode, either through wildcard matching or explicit multi-session configuration.

### How It Works

**Option A: Wildcard Sessions**
- Allow TargetCompID to be set to `*` (any)
- Any client with any CompID can connect
- FixTool dynamically creates session tabs for each connected client

**Option B: Multi-Session Config**
- In acceptor mode, allow defining multiple SenderCompID/TargetCompID pairs on the same port
- Each pair appears as a separate session in the UI

**Option C: Dynamic Session Acceptance (Recommended)**
- Use QuickFIX/J's `AcceptorTemplate` or dynamic session creation
- Accept any incoming logon and auto-create the session
- Display each connected client as a separate tab/panel

### UI Considerations
- Session list sidebar showing all connected clients
- Each session has its own message history
- Auto-response rules can apply globally or per-session
- Connection count indicator

### Acceptance Criteria
- Multiple clients can connect to the acceptor simultaneously
- Each client session is displayed separately in the UI
- Messages from different clients are not mixed
- Auto-response rules work across all sessions
- Session list updates in real-time as clients connect/disconnect

---

## Feature 4: Scenario Playbooks

### Problem
Real exchange behavior involves sequences of messages with timing. A single auto-response rule can only send one message. Testing realistic flows (ack -> partial fill -> partial fill -> full fill) requires chaining multiple responses.

### Proposed Solution
Scenario playbooks — ordered sequences of responses triggered by an incoming message, with configurable delays between steps.

### How It Works
- User defines a "Scenario" consisting of ordered steps
- Each step has: message to send, field values, and delay before sending
- When a trigger message arrives, the entire scenario plays out automatically

### Example Scenario: "Normal Order Lifecycle"
```yaml
name: "Normal Order Lifecycle"
trigger: 35=D
steps:
  - name: "Acknowledge"
    delay: 50ms
    send: 35=8
    fields:
      150: "0"        # ExecType=New
      39: "0"         # OrdStatus=New
      14: "0"         # CumQty=0
      151: "{38}"     # LeavesQty=OrderQty
    copy: [11, 55, 54, 38, 44]
    generate: [37, 17]

  - name: "Partial Fill (50%)"
    delay: 500ms
    send: 35=8
    fields:
      150: "F"        # ExecType=Trade
      39: "1"         # OrdStatus=PartiallyFilled
      32: "{38}/2"    # LastQty=OrderQty/2
      14: "{38}/2"    # CumQty=OrderQty/2
      151: "{38}/2"   # LeavesQty=OrderQty/2
      31: "{44}"      # LastPx=Price
    copy: [11, 55, 54, 38, 44, 37]
    generate: [17]

  - name: "Full Fill"
    delay: 1000ms
    send: 35=8
    fields:
      150: "F"        # ExecType=Trade
      39: "2"         # OrdStatus=Filled
      32: "{38}/2"    # LastQty=remaining
      14: "{38}"      # CumQty=OrderQty
      151: "0"        # LeavesQty=0
      31: "{44}"      # LastPx=Price
    copy: [11, 55, 54, 38, 44, 37]
    generate: [17]
```

### UI Considerations
- Scenario editor with visual step builder (drag-and-drop reorder)
- Timeline visualization showing the sequence
- Play/pause/stop controls for running scenarios
- Scenario library with import/export
- Visual indicator in session panel showing which scenario is running

### Acceptance Criteria
- User can define multi-step scenarios via UI
- Scenarios trigger on incoming message type
- Each step executes with configured delay
- Field expressions (copy, static, generated, computed) work correctly
- Running scenarios can be stopped mid-execution
- Multiple scenarios can be defined; only one active per message type

---

## Feature 5: Conditional Response Logic

### Problem
A static rule always sends the same response. Real exchanges behave differently based on message content — different symbols, quantities, or order types may produce different outcomes.

### Proposed Solution
Add conditions to auto-response rules and scenarios, allowing different responses based on incoming message field values.

### How It Works
- Each rule/scenario gets an optional list of conditions
- Conditions are evaluated against the incoming message
- First matching rule wins (priority-ordered)

### Example Rules (evaluated top-to-bottom)
```
Rule 1: IF 35=D AND 55="REJECT"   → Send 35=8, OrdStatus=8 (Rejected)
Rule 2: IF 35=D AND 55="PARTIAL"  → Run "Partial Fill Scenario"
Rule 3: IF 35=D AND 38>10000      → Send 35=j (BusinessMessageReject, quantity too large)
Rule 4: IF 35=D                    → Send 35=8, OrdStatus=0 (default: acknowledge)
```

### Supported Conditions
- Field equals value: `55 = "AAPL"`
- Field not equals: `55 != "REJECT"`
- Numeric comparison: `38 > 10000`, `44 <= 100.00`
- Field present/absent: `EXISTS(44)`, `NOT_EXISTS(58)`
- Regex match: `55 MATCHES "^TEST.*"`
- Compound: `AND`, `OR` combinations

### UI Considerations
- Condition builder with dropdowns (field, operator, value)
- Rule priority ordering (drag-and-drop)
- Test mode: paste an incoming message and see which rule would match
- Visual indicator showing which rule fired for each incoming message

### Acceptance Criteria
- Conditions can be added to any auto-response rule or scenario
- All comparison operators work correctly
- Rules are evaluated in priority order; first match wins
- Compound conditions (AND/OR) are supported
- UI allows easy condition building without writing code

---

## Feature 6: Order State Management

### Problem
FIX order lifecycle is stateful. An order goes through states (New -> PartiallyFilled -> Filled). Cancel and Replace requests reference existing orders. Without state tracking, the acceptor can't validate or correctly respond to lifecycle messages.

### Proposed Solution
An in-memory order book that tracks order state, enabling correct responses to cancel/replace requests and accurate cumulative quantity tracking.

### How It Works
- When FixTool (acceptor) acknowledges a NewOrderSingle, it stores the order in an order book
- Order state tracks: ClOrdID, OrderID, Symbol, Side, OrderQty, CumQty, LeavesQty, OrdStatus
- When a Cancel Request (35=F) arrives, FixTool looks up the order by OrigClOrdID and:
  - If found and active: sends Cancel Ack with updated status
  - If not found: sends Cancel Reject (35=9) with reason
- When a Replace Request (35=G) arrives, similar lookup and validation

### Order Book UI
```
+----------+----------+--------+------+--------+--------+-----------+----------+
| ClOrdID  | OrderID  | Symbol | Side | OrdQty | CumQty | LeavesQty | Status   |
+----------+----------+--------+------+--------+--------+-----------+----------+
| ORD-001  | EX-1001  | AAPL   | Buy  | 1000   | 500    | 500       | PartFill |
| ORD-002  | EX-1002  | MSFT   | Sell | 200    | 200    | 0         | Filled   |
| ORD-003  | EX-1003  | GOOG   | Buy  | 5000   | 0      | 0         | Canceled |
+----------+----------+--------+------+--------+--------+-----------+----------+
```

### Features
- Order book panel visible in acceptor mode
- Click an order to see full history (all execution reports sent)
- Manual actions: right-click an order to Fill, Partial Fill, Cancel, or Reject it
- Auto-fill scenarios update the order book automatically
- CumQty and LeavesQty calculated correctly across partial fills

### Acceptance Criteria
- Order book tracks all acknowledged orders
- Cancel/Replace requests validated against order book
- CumQty and LeavesQty update correctly across fills
- Invalid cancel/replace requests auto-rejected with correct reason codes
- Order book UI displays current state of all orders
- Order history viewable per order

---

## Feature 7: Latency Simulation

### Problem
Real exchanges have variable latency. Testing a client application with instant responses doesn't reflect production conditions. Clients need to handle delays, timeouts, and variable response times.

### Proposed Solution
Configurable latency simulation that adds realistic delays to acceptor responses.

### How It Works
- Global latency settings in acceptor mode:
  - **Fixed delay**: Every response delayed by N milliseconds
  - **Random range**: Delay between min and max milliseconds (e.g., 50-500ms)
  - **Distribution**: Normal distribution around a mean with configurable standard deviation
- Per-rule delay overrides (already covered in Feature 1, but this adds global defaults)
- Spike simulation: Occasionally inject a large delay to simulate network issues

### Configuration
```
Latency Mode: Random Range
Min Delay: 50ms
Max Delay: 300ms
Spike Probability: 5%
Spike Delay: 2000-5000ms
```

### Acceptance Criteria
- Global latency settings configurable in acceptor mode
- Delay applied to all auto-responses
- Per-rule overrides take precedence over global settings
- Spike simulation works at configured probability
- Latency values visible in message log for debugging

---

## Summary and Recommended Build Order

| Phase | Feature | Impact | Effort | Dependencies |
|-------|---------|--------|--------|--------------|
| 1 | Auto-Response Rules Engine | High | Medium | None |
| 2 | Response Templates Library | High | Small | None |
| 3 | Multi-Session Acceptor | Medium | Medium | None |
| 4 | Scenario Playbooks | High | Large | Feature 1 |
| 5 | Conditional Response Logic | Medium | Medium | Feature 1 |
| 6 | Order State Management | Medium | Large | Feature 1 |
| 7 | Latency Simulation | Low | Small | Feature 1 |

**Recommendation**: Features 1 and 2 together provide the most immediate value. A tester can get auto-responses for basic flows and use templates for edge cases, making FixTool a usable acceptor testing tool with moderate development effort.
