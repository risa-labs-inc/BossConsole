# Agent Mission Control & Human-in-the-Loop (HITL) Guardrail Engine

## Overview

**Agent Mission Control** is a real-time observability, governance, and human-in-the-loop (HITL) guardrail system built directly into BOSS (`composeApp`).

As autonomous AI agents (Claude Code, Gemini CLI, OpenAI Codex, OpenCode) execute tasks across BOSS's 100+ MCP tools, human operators need total visibility, latency profiling, and immediate intervention capabilities without sacrificing workflow continuity.

```
                  ┌─────────────────────────────────────────────────────────┐
                  │                 Autonomous AI Agents                     │
                  │        (Claude Code, Gemini CLI, Codex, OpenCode)        │
                  └───────────────────────────┬─────────────────────────────┘
                                              │ MCP Call (JSON-RPC)
                                              ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ BOSS Core (McpToolRegistryImpl)                                                        │
│                                                                                        │
│   1. Tool Disabled? ────────► Record BLOCKED ───────────────────► Fail Fast            │
│                                                                                        │
│   2. HITL Guardrail? ───────► Suspend via CompletableDeferred                          │
│                                       │                                                │
│                                       ▼                                                │
│                          ┌──────────────────────────┐                                  │
│                          │  McpApprovalBanner (UI)  │                                  │
│                          │  [Approve] [Deny] [Edit] │                                  │
│                          └────────────┬─────────────┘                                  │
│                                       │ Decision                                       │
│                                       ▼                                                │
│   3. Dispatch Tool Call ◄───── Resumed with Args                                       │
│          │                                                                             │
│          ▼                                                                             │
│   4. Record Interceptor Metrics (Latency, Status, Payloads with Secret Redaction)     │
└──────────────────────────────────────┬─────────────────────────────────────────────────┘
                                       │
                                       ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ Telemetry & Agent Observability                                                        │
│                                                                                        │
│   • McpTelemetry Ring Buffer (200 records, non-blocking, thread-safe Mutex)            │
│   • McpInspectorStatusItem (Real-time bottom bar HUD with active states)               │
│   • McpMissionControlDialog (Full telemetry workbench, replay scratchpad, toggles)     │
│   • Agent Self-Awareness Tools (get_tool_history, diagnose_last_failure)              │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Key Capabilities

### 1. Interceptor & Telemetry Pipeline
- **Zero-overhead Interception**: Transparently wraps `McpToolRegistry.invoke()`. Measures exact duration in milliseconds, captures input parameters, exit codes, and output payloads.
- **Ring Buffer Retention**: Maintains an in-memory sliding window of the last 200 tool invocations. Automatically evicts older items to maintain a strict, low memory footprint.
- **Thread-safe Concurrency**: Uses Kotlin coroutines `Mutex` with non-blocking updates and reactive `StateFlow` streams (`recentCalls`, `telemetryStats`, `pendingApprovals`).
- **Secret Redaction**: Automatically sanitizes sensitive keys and patterns (`password`, `token`, `secret`, `authorization`, `bearer`, `api_key`, `credential`) before payloads hit UI logs or exports.

### 2. Human-in-the-Loop (HITL) Guardrails
- **Configurable Sensitive Tool Interception**: By default protects destructive actions (`mcp__boss__execute_shell`, `mcp__boss__write_file`, `mcp__boss__delete_file`, `mcp__boss__git_push`, `mcp__boss__send_http_request`).
- **Global Safe Mode**: Operators can flip Safe Mode ON to intercept *all* agent tool invocations, or toggle granular approvals per tool.
- **Non-blocking Coroutine Suspension**: Instead of blocking OS threads, the agent's MCP call suspends on a `CompletableDeferred<ApprovalDecision>` with a configurable timeout (default 60s).
- **Interactive Floating HUD (`McpApprovalBanner`)**: When an agent attempts a sensitive call, an animated banner glides into the bottom viewport presenting the tool name, arguments snippet, and quick actions:
  - **Approve**: Resumes execution immediately.
  - **Deny**: Cancels execution and returns a descriptive denial message to the agent.
  - **Inspect & Edit**: Opens the Mission Control dialog allowing the operator to inspect full arguments or modify parameters before approving.

### 3. Desktop Observability UI
- **Live Status Bar HUD (`McpInspectorStatusItem`)**: Located in `BossBottomBar`. Displays pulse animation when an agent tool is running, total calls, pending approval count badge, and error count.
- **Mission Control Dialog (`McpMissionControlDialog`)**:
  - **Metrics Dashboard**: Total calls, active running count, failure rate %, average latency ms, and active Safe Mode badge.
  - **Search & Filter Chips**: Real-time filtering by text search and category (`ALL`, `PENDING`, `ERRORS`, `SUCCESS`).
  - **Detailed Payload Drawer**: Side-by-side view showing arguments, output, error traces, timestamp, and duration.
  - **1-Click Replay Scratchpad**: Allows the human operator to re-run any historical tool call directly with modified arguments.
  - **Kill Switch & Tool Management**: Enable or disable any individual tool on the fly, or enforce approval requirements.
  - **JSON Export**: 1-click export of redacted session logs for debugging and post-mortem analysis.

### 4. Autonomous Agent Recovery Tools (`McpMissionControlToolProvider`)
Enables connected LLM agents to inspect their own tool execution history and diagnose unexpected tool failures:
- `mcp__boss__get_tool_history`:
  - Returns the recent tool calls executed by the agent, filtered by status (`ERROR`, `SUCCESS`, `ALL`) and limit.
- `mcp__boss__diagnose_last_failure`:
  - Returns structured diagnostic context on the most recent failed or blocked tool call, including tool name, arguments, error message, latency, and recommended recovery action.

---

## Architectural Guarantees & Concurrency

1. **Cancellation Safety**: In `McpToolRegistryImpl`, `CancellationException` is explicitly rethrown to preserve structured concurrency and prevent orphaned jobs.
2. **Deterministic Timeouts**: If a human operator does not approve a pending call within 60 seconds, the deferred safely times out, recording `McpCallStatus.TIMEOUT` and returning a clean timeout message to the agent.
3. **Zero UI Freezing**: All state emissions occur through `StateFlow` and Compose `collectAsState()`, decoupled from the tool execution thread pool.

---

## Verification & Test Suite

The feature is accompanied by a thorough test suite in `composeApp/src/desktopTest/kotlin/ai/rever/boss/mcp/McpMissionControlTest.kt`:

| Test Case | Description |
|-----------|-------------|
| `testTelemetryRingBufferLimit` | Verifies sliding window eviction at 200 items without memory leak. |
| `testRecordSuccessfulInvocation` | Validates end-to-end telemetry capture on successful tool calls. |
| `testRecordBlockedInvocation` | Validates fast-fail recording when a disabled tool is invoked. |
| `testRecordTimeout` | Validates timeout handling when approval times out. |
| `testApprovalFlowApprove` | Validates that an approved tool call executes with approved arguments. |
| `testApprovalFlowDeny` | Validates that a denied tool call halts execution and returns a denial result. |
| `testApprovalFlowModifyArgs` | Validates that human modifications to arguments are passed to the tool. |
| `testSecretMasking` | Validates that sensitive parameters (passwords, tokens, API keys) are redacted. |
| `testConcurrentRecordAdditions` | Stresses ring buffer with 50 concurrent threads to ensure thread-safety. |
| `testAgentSelfAwarenessTools` | Validates `get_tool_history` and `diagnose_last_failure` tool providers. |

To run the test suite:
```bash
./gradlew :composeApp:desktopTest --tests "ai.rever.boss.mcp.McpMissionControlTest"
```
