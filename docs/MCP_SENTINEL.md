# BOSS MCP Sentinel — ToolDNA Integrity & Poisoning Detection

## 1. Overview

**MCP Sentinel** (internal concept: **ToolDNA**) is a content-aware and change-aware security subsystem built into BOSS Console. It protects BOSS users and autonomous agents against Model Context Protocol (MCP) tool integrity attacks, including:

1. **Tool Poisoning**: Prompt injection payloads hidden in tool descriptions, schemas, or titles (e.g., instructing an LLM agent to ignore previous rules, steal tokens, or execute arbitrary shell commands).
2. **MCP Rug Pulls**: A previously trusted tool definition changing silently after the user or policy has already allowed it.
3. **Tool-Definition Tampering**: Invisible Unicode characters (zero-width spaces, BiDi control overrides), HTML comment concealment (`<!-- ... -->`), or Base64-encoded instruction blobs inside tool metadata.
4. **Cross-Server Tool Shadowing / Name Collisions**: Multiple MCP providers exposing conflicting or ambiguous tool identities (exact name or normalized name collisions).
5. **Schema Widening & Destructive Parameter Expansions**: Schema updates adding destructive parameters (e.g., `delete_after_read`, `force`, `override`) or removing parameters.

---

## 2. Architecture & Dataflow

MCP Sentinel integrates directly into BOSS Console's core MCP layer (`McpToolRegistryCore` / `McpToolRegistryImpl` in package `ai.rever.boss.mcp.sentinel`).

```
                    MCP PROVIDER REGISTRATION
                               │
                               ▼
                    BOSS Tool Registry (McpToolRegistryCore)
                               │
                      [recompute / reload]
                               │
                               ▼
                    ToolDNA Canonicalization & SHA-256
                               │
                               ▼
                   Static Security Scanner & Diff Engine
                               │
                               ▼
                      Shadowing Collision Detector
                               │
                               ▼
                     Trust State Machine Evaluation
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
      TRUSTED / NEW                      CHANGED / SUSPICIOUS / BLOCKED
            │                                     │
            ▼                                     ▼
   Allow Execution                       Block or Require Operator
            │                                 Review / Re-approval
            ▼                                     │
    McpOperationLedger                            ▼
     (Audit History)                    McpSentinelPanel (UI)
```

### Key Components

- `ToolDnaFingerprinter`: Computes canonical JSON representations (key-sorted, minified, normalized) and SHA-256 hex digests with algorithm versioning.
- `ToolDnaDiffEngine`: Computes structural and semantic schema diffs between baseline definitions and newly registered definitions, categorizing changes (`DESCRIPTION_CHANGED`, `INPUT_SCHEMA_CHANGED`, `DESTRUCTIVE_PARAMETER_ADDED`, `CAPABILITY_EXPANSION`, etc.).
- `ToolContentScanner`: Explainable static analyzer that inspects tool text for prompt injection patterns, invisible Unicode obfuscation, HTML comment concealment, and Base64 instruction payloads with zero false-positive over-flagging on benign terms.
- `ToolShadowingDetector`: Scans all active providers for exact and normalized tool name collisions (`providerA/read_file` vs `providerB/read_file`).
- `ToolDnaBaselineStore`: Persists accepted baselines and historical fingerprints to `mcp-tooldna-baseline.json` via `BossDirectories.resolve(...)` (located in the BOSS root data directory, typically `~/.boss/`).
- `McpSentinelEngine`: Orchestrates the trust state machine, evaluation flows, and audit log events.
- `McpSentinelPanel`: Compose Multiplatform UI panel for monitoring tool integrity, inspecting diffs, reviewing scanner findings, and executing operator re-approvals or blocks.

---

## 3. Trust State Machine

An MCP tool monitored by Sentinel transitions through explicit states:

```
  [Unobserved Tool] ──► NEW
                         │ (User/Policy Approve)
                         ▼
                      TRUSTED ──► (Fingerprint Mismatch) ──► CHANGED / REVIEW_REQUIRED
                         │                                         │
                         ├─────────────────────────────────────────┼──► SUSPICIOUS
                         │ (High Severity Scanner Finding)         │
                         ▼                                         ▼
                      BLOCKED ◄──────────────────────────── (Operator Block)
```

- **NEW**: Tool has been observed for the first time.
- **TRUSTED**: Current tool definition fingerprint matches its approved baseline in `ToolDnaBaselineStore`.
- **CHANGED**: Tool definition fingerprint differs from baseline. Execution is withheld until explicitly re-approved.
- **SUSPICIOUS**: Static scanner flagged prompt injection, invisible Unicode, or high-risk findings.
- **REVIEW_REQUIRED**: Definition change introduced structural modifications or destructive capability expansion.
- **BLOCKED**: Tool is explicitly blocked by policy or operator.

> **Critical Security Invariant**: `CHANGE != MALICIOUS`, but `CHANGE -> RE-EVALUATION`. Sentinel never silently converts a `CHANGED` tool to `TRUSTED`. Explicit human re-approval is required.

---

## 4. Threat Model & Security Boundaries

### What Sentinel Detects
- Modifications to tool descriptions, parameter names, or parameter types post-trust.
- Prompt injection attempts attempting to hijack LLM context ("ignore previous instructions", "secretly exfiltrate").
- Hidden formatting controls (BiDi overrides `U+202A..U+202E`, Zero-width spaces `U+200B..U+200D`).
- HTML comment hidden instructions (`<!-- system prompt: ... -->`).
- Ambiguous cross-provider tool shadowing.

### Limitations
- **Server Implementation Risk**: A matching fingerprint proves that the *definition metadata* has not changed, but does not guarantee the server-side backend code is benign.
- **Static Analysis**: Scanner heuristics identify known threat patterns and suspicious payloads, but cannot guarantee 100% detection of novel adversarial phrasing.

---

## 5. Developer Guide: Adding a Scanner Rule

To add a new static analysis detection rule to `ToolContentScanner`:

1. Open `composeApp/src/commonMain/kotlin/ai/rever/boss/mcp/sentinel/ToolContentScanner.kt`.
2. Add a `ScannerRule` to `INJECTION_RULES` or dedicated rule array:

```kotlin
ScannerRule(
    id = "INJ-007",
    severity = FindingSeverity.HIGH,
    pattern = Regex("(?i)\\byour_pattern_here\\b"),
    explanation = "Description of why this pattern is suspicious.",
)
```

3. Add unit test cases in `ToolContentScannerTest.kt` verifying both positive match detection and false-positive controls on benign contexts.

---

## 6. Testing & Security Evaluation Suite

Run the full MCP Sentinel security evaluation suite:

```bash
./gradlew composeApp:test --tests "ai.rever.boss.mcp.sentinel.*"
```

The test suite covers:
- `ToolDnaFingerprintTest`: Deterministic hashing, key-order invariance, canonicalization.
- `ToolContentScannerTest`: Heuristic scanner rules and false-positive controls.
- `ToolDnaDiffEngineTest`: Structural schema diffs and destructive parameter additions.
- `ToolShadowingDetectorTest`: Cross-provider tool collisions.
- `McpSentinelEngineTest`: State machine transitions, restart persistence, and re-approval workflow.
- `McpSentinelEvaluationMatrixTest`: Standardized security evaluation scenarios (`EVAL-RUGPULL-001`, `EVAL-POISON-001`, `EVAL-UNICODE-001`, `EVAL-SHADOW-001`, `EVAL-SCHEMA-001`, `EVAL-FALSEPOS-001`, `EVAL-RETRUST-001`).
