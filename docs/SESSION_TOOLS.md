# Agent session self-awareness (`session_*`)

Agents get stuck and cannot tell. They repeat a call that has already failed three times, they do
not notice that a call was **denied by policy and never ran**, and they cannot answer "what have I
already tried". Nothing in a BOSS session, human or agent, could answer that before these tools.

Clients see them as `mcp__boss__session_*`. Implementation:
`composeApp/src/desktopMain/kotlin/ai/rever/boss/mcp/session/`.

## Scope: the whole process, not one agent

`McpToolRegistryImpl` is a process-wide singleton, and `McpOperationRecord` carries **no caller
identity**: no session id, no terminal pane, no client name. So these tools report every MCP call
made in this BOSS process, by every agent in it, and **cannot attribute a call to one agent**.

Stated plainly because it cuts both ways:

- A limitation if you wanted "what did *I* do". There is no way to answer that without adding a
  caller id to the ledger record, which is a host change these tools do not make.
- Useful if two agents are working on overlapping things, because a loop or a policy block caused
  by one is visible to the other.

What it is **not** is a coordination mechanism. Agents cannot address each other, claim work, or
tell each other apart through this surface.

## Why this can only exist inside BOSS

Every MCP call is recorded by the host with its governance decision attached: which tool, which
provider, what the policy said, whether an operator approved it, how long it took, whether it
errored. That record is `~/.boss/mcp-calls.jsonl`, written by `McpOperationLedger`.

No external MCP server has it, because none of them sit inside the governance boundary. There are
plenty of good third party servers for profiling, debugging and tracing a JVM. There is no third
party server that can tell an agent it is going in circles inside a governed harness, because the
evidence for that only exists here.

## The two tools

| Tool | `readOnly` | Permission | Returns |
|---|---|---|---|
| `session_review` | yes | none | Counts, per tool statistics, policy blocks, detected loops. Tool names and numbers only. |
| `session_inspect_calls` | yes | `mcp.activity.read` | Individual calls including sanitized arguments and error text. |

Both only read a log, so neither is mutating.

### What `session_review` answers

- How many calls, how many failed, error rate.
- Per tool: calls, errors, median and max duration. Median rather than mean, so one slow outlier
  does not make a tool look uniformly slow.
- **Calls that never ran.** Dispositions such as `POLICY_DENIED`, `DENIED_BY_OPERATOR`, `TIMEOUT`
  and `QUEUE_FULL` mean governance stopped the call. An agent often does not realise a tool was
  blocked rather than executed, and then reasons from an outcome that never happened. The payload
  says so in words.
- **Loops**, in three shapes, each with different advice because the right next step differs:

| Kind | Meaning | Advice |
|---|---|---|
| `REPEATING_FAILURE` | Same tool, identical arguments, 3+ times, all failing | Stop. Change the arguments or fix the cause. |
| `FLAILING` | Same tool, same error, different arguments, 3+ times | The arguments are not the problem. Check a precondition. |
| `REDUNDANT` | Same tool, identical arguments, 5+ times, all succeeding | The answer is not changing. Reuse the result. |

Telling `REPEATING_FAILURE` from `FLAILING` is the point of having both. Advising "vary the
arguments" to an agent that has already varied them sends it round the loop it is in.

## The permission split is the design

`AGENTS.md` (issue #416) states that a ledger read surface "must be host-implemented and
permission-gated", warning that ungated access would disclose "every other plugin's tool names,
sanitized arguments and error snippets".

Loop detection needs none of that content. So the useful half stays ungated and only the
sensitive half is gated:

- `session_review` is **structurally incapable** of leaking argument values or error text, because
  `SessionReport` has nowhere to put them. Identity is carried as `args_fingerprint`, a short
  non reversible hash, which is enough to say "these two calls were identical" without saying what
  they were. A test feeds a marker string through and asserts it is absent from the payload.
- `session_inspect_calls` returns the detail and requires `mcp.activity.read`.

Known consequence, stated rather than hidden: a gated tool is **hidden from a session with no
signed in user**, because `mcpToolPermitted` checks `permissions.containsAll(...)` against an empty
set for a non admin. Admins bypass it. That is the correct fail closed posture for the sensitive
half, and it is exactly why the loop detection half is not gated.

## Reading the ledger

Three properties matter more than the parsing.

1. **Only the tail is read.** The ledger rotates at 10 MB. Answering a tool call must not pull that
   into the host heap, so the last 2 MB and at most 5000 records are read, and the payload says
   `truncated` when the window was cut short.
2. **A torn line is normal.** The writer appends from another coroutine while this reads, so the
   final line can be half written. Malformed lines are skipped and **counted**, never thrown, and
   the count is reported as `unreadable_ledger_lines`.
3. **In memory records win.** `McpOperationLedger` writes to disk asynchronously in a `finally`, so
   the newest call may not be on disk yet. Its 100 entry ring buffer is merged and deduplicated by
   `id`. Without that, running `session_review` immediately after a failure would not see it.

A ledger written by a newer build stays readable: unknown fields are ignored and an unknown
disposition falls back to "executed" rather than "blocked", because over reporting "governance
stopped you" would be a confusing lie.

### The session window

Default is this BOSS process, from `RuntimeMXBean.startTime`. That needs no state of its own and
cannot drift. `since_minutes` overrides it.

## Trying it

```bash
./gradlew :composeApp:desktopTest --tests "ai.rever.boss.mcp.session.*"
```

From an agent in BossTerm:

```
mcp__boss__session_review          {}
mcp__boss__session_review          {"since_minutes": 30}
mcp__boss__session_inspect_calls   {"tool_name": "codebase_tree", "errors_only": true}
```

To see loop detection work, call any tool so that it fails three times with identical arguments,
then run `session_review`.
