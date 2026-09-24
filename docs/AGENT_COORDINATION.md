# Agent coordination (`agent_*`)

BOSS is the harness that runs several agents at once: Claude Code in one pane, Codex in another,
on the same repository. Give two of them overlapping work and they will silently edit the same
file, with the second write winning. Nothing in BOSS, and nothing in any MCP server, tells either
one that the other exists.

These tools are a shared board where agents say what they are working on, and find out who else
has claimed the same files.

Clients see them as `mcp__boss__agent_*`. Implementation:
`composeApp/src/desktopMain/kotlin/ai/rever/boss/mcp/coordination/`.

## The three tools

| Tool | `readOnly` | Purpose |
|---|---|---|
| `agent_claim` | **no** | Announce what you are working on, and immediately learn who else claimed those files. |
| `agent_peers` | yes | List other active agents, what they are doing, and your overlap with them. |
| `agent_release` | **no** | Retire your claim when the work is done. |

A typical exchange:

```
agent_claim  {"agent_id":"claude-1","task":"refactor auth","files":["src/Auth.kt"]}
  -> claimed, overlap_count 0

agent_claim  {"agent_id":"codex-2","task":"add logging","files":["src/Auth.kt","src/Log.kt"]}
  -> claimed, overlap_count 1
     claude-1 is working on "refactor auth" and also claimed src/Auth.kt
```

## What this is not

This is the important section, and it is repeated inside the tool descriptions because the agent
reading them is the one that needs to know what the answer is worth.

**MCP carries no caller identity.** `McpOperationRecord` has no session id, no terminal pane and
no client name, so the host cannot tell which agent made a call. Every agent therefore **declares
its own name**, and nothing verifies it. Two consequences:

- **It is not a lock.** Nothing prevents an agent editing a file another agent claimed. An
  overlap is reported so the agent can decide; it is never refused. A lock that nothing enforces
  is a lock that gets broken, and shipping one would promise more than the mechanism can deliver.
- **It is not a security boundary.** An agent can claim any name, including another agent's. This
  coordinates cooperating agents on one machine. It defends against nothing.

## Design decisions worth knowing

**Path matching deliberately over matches.** `src\Foo.kt`, `./src/Foo.kt`, `src//Foo.kt` and
`SRC/FOO.KT` are all one file. Comparison is case insensitive because Windows and macOS default
to case insensitive filesystems, so treating `Foo.kt` and `foo.kt` as different there would miss
real collisions. On Linux this can pair two genuinely different files.

That direction is the whole decision: a **missed** overlap is two agents editing one file
believing they are alone, which is the failure this exists to prevent. A **false** overlap is one
agent double checking something it did not need to. The second is much cheaper.

What it does not do is resolve relative paths against the project root or follow symlinks, so
`src/Foo.kt` and `/home/me/proj/src/Foo.kt` will not match. The tool description tells agents to
spell paths the way other agents would.

**Claims expire.** Default 30 minutes, ceiling 240. This is what makes a crashed agent's claim
disappear with nobody cleaning up after it, and it is why forgetting `agent_release` is
survivable rather than permanent.

**A second claim from one agent replaces the first**, rather than adding to it. Re-claiming is a
new statement of what an agent is doing.

**Claiming no files collides with nobody.** "I am busy on something" is a legitimate claim and
must not collide with every other agent.

**Governance blocks are not loops and not collisions.** Writing to the board is a side effect
other agents read, so `agent_claim` and `agent_release` declare `readOnly = false` and inherit the
host's ASK default. An operator is asked before an agent can write to shared state.

## Storage

`~/.boss/agent-claims.json`, written atomically through a temp file and a move, because
`outputStream()` truncates on open and writing in place would destroy a good board the instant a
write began.

A file rather than in-memory state, because the interesting case is not guaranteed to be one
process: BOSS can restart mid task, and an operator can run a second BOSS.

An unreadable board is reported as `board_unreadable` and treated as empty, rather than failing
the call. This is coordination advice; the honest answer when the file is corrupt is "I cannot see
any peers", and the next claim replaces the file.

Bounds: 50 agents, 100 files per claim, 64 character ids, 300 character tasks, 400 character
paths. The oldest claim is evicted when the board is full, so a runaway minting new ids cannot
wedge it permanently.

**Known limit:** concurrent writes are serialised within one JVM only. Two BOSS processes racing
can lose a claim. That is acceptable for advisory data and is not solved with a lock file.

## Trying it

```bash
./gradlew :composeApp:desktopTest --tests "ai.rever.boss.mcp.coordination.*"
```

From two agents in two BossTerm panes, have the first claim a file and the second claim the same
file. The second call reports the overlap immediately.
