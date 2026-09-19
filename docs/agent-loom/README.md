# Agent Loom

A durable replay viewer for MCP sessions.

BOSS already writes every governed MCP tool call to an append-only JSONL ledger
(`~/.boss/mcp-calls.jsonl`). The activity log only exposes a 100 entry in-memory ring buffer, so the
persisted record has been written but never read back. Agent Loom reads it back and turns it into a
scrubbable visual replay.

The output is one HTML file. It opens from a `file://` path in any browser, on any machine, with no
BOSS, no login, no server and no network connection.

## Export

```
./gradlew :composeApp:exportAgentLoom
```

Defaults to `~/.boss/mcp-calls.jsonl` in and `composeApp/build/agent-loom/agent-loom-session.html`
out. Both are overridable:

```
./gradlew :composeApp:exportAgentLoom \
  -Pledger=docs/agent-loom/sample-mcp-calls.jsonl \
  -Pout=docs/agent-loom/demo-session.html
```

The command also runs standalone, because the exporter is a plain `main`:

```
java -cp <desktop jar and runtime classpath> ai.rever.boss.mcp.loom.AgentLoomCliKt <ledger> <out.html>
```

Rotated backups (`mcp-calls.jsonl.1` and up) are read too, oldest first, using the ledger's own
rotation ordering. A gap in the rotation is reported in the artifact rather than silently skipped.

## What the replay shows

* A timeline of every reconstructed call, coloured by derived risk, with distinct markers for
  approvals, denials and errors.
* Transport controls: play/pause, step forward and back, jump to first and last, and a scrubber that
  covers the whole session. Space, the arrow keys, Home and End work as well.
* An event stream with per-call cards, expandable details, and the sanitized arguments exactly as the
  ledger persisted them.
* Filters by search text, outcome, risk and tool, plus an errors-only toggle.
* Session summary: event count, span, start and end, approval and denial counts, error count, tool
  usage, risk distribution and outcome distribution.
* A ledger notes panel listing anything that could not be reconstructed.

Play steps event by event rather than in real time. A session can contain long idle gaps (waiting for
an approval, for example), and replaying those in real time would mean watching nothing happen.

## How the ledger is reconstructed

`AgentLoomReplay.kt` reads the JSONL line by line and turns each record into a `LoomEvent`:

* **Order.** Events are sorted by the recorded `timestamp`, with the file order that produced them as
  the tie-break, so equal-millisecond records keep the order they were written in. Each event carries
  its offset from the first event, which is what the timeline plots.
* **Tolerance.** A line that is not JSON, is not a JSON object, or carries no `toolName` becomes a
  diagnostic. The rest of the session still replays. One corrupt line must not cost an operator the
  audit trail around it.
* **Missing fields.** A record written by an older build, or one that lost a field, is reconstructed
  with the missing field named on the event and shown as `not recorded`. Nothing is filled in with a
  plausible value.
* **Outcome.** The recorded `policyApplied` and `approvalDisposition` are collapsed into one of
  allowed, approved, denied, timed out, cancelled, queue rejected or unknown. Only values the ledger
  can actually contain are classified; anything else stays `UNKNOWN` and keeps its raw value on the
  card, because a replay that guesses at a governance decision is worse than one that says it does
  not know.
* **Risk.** Risk is the one field that is not in the ledger at all. It is re-derived from the tool
  name and the sanitized arguments by `DefaultMcpRiskEvaluator`, the same evaluator the runtime uses,
  and the viewer labels it as derived everywhere it appears.
* **Session identity.** The ledger does not record a session id, so the replay is identified by its
  source files and its time span. The artifact says so rather than inventing a name.

## Why the artifact needs nothing

The viewer is a single HTML document with the reconstructed session embedded in a JSON block. There
is no `<script src>`, no stylesheet link, no font request, no `fetch`, no `XMLHttpRequest` and no
telemetry, which the tests assert rather than assume. Everything the artifact can show is decided by
the exporter on the JVM, so the replay cannot disagree with the ledger it came from.

The one thing the artifact does not do is parse a raw `mcp-calls.jsonl` in the browser. Ordering,
outcome classification and risk derivation live in Kotlin; re-implementing them in the viewer's
JavaScript would create two definitions of the same reconstruction that can drift apart. Import is
the export command, not the viewer.

## Privacy

* The ledger is read, never written. The exporter is read-only, and a test asserts a ledger file is
  byte for byte unchanged after a replay.
* Nothing is sent anywhere. The artifact has no network calls of any kind.
* Arguments are shown as the ledger persisted them. The runtime sanitizes before writing, and Agent
  Loom does not re-sanitize, because doing so would imply it trusts a different redaction rule than
  the one that produced the file.
* Ledger values are escaped before rendering, so an argument containing markup is displayed as text
  rather than interpreted.

## Files here

* `sample-mcp-calls.jsonl` is a synthetic session used for the demo. It is not a real ledger, and its
  hashes are illustrative rather than a verifiable chain.
* `demo-session.html` is that sample exported by the command above.
