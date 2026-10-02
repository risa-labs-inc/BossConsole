# Runtime diagnostics for agents (`telemetry_*`)

An agent editing code in BOSS can run a build and read stdout, and that is the whole of its
feedback loop. If a change made something slow, leaky or deadlocked, the agent is reduced to
rereading the source and guessing. These MCP tools let it measure a running process instead.

Clients see them as `mcp__boss__telemetry_*`. Implementation:
`composeApp/src/desktopMain/kotlin/ai/rever/boss/mcp/telemetry/`.

## How this differs from the host's own metrics

BOSS already reports on itself, and since `IntrospectionMcpToolProvider` it does so over MCP:
`PerformanceMonitor` drives the status bar chart, and `get_performance_metrics` hands an agent
that same reading. Those answer "how is BOSS itself doing". These tools answer "how is the
process I just started doing", which nothing else here can.

| | `get_performance_metrics` | `telemetry_*` |
|---|---|---|
| Subject | this BOSS process | any local process, including this one |
| Mechanism | in process MXBeans | `jdk.attach` plus JMX into another pid |
| Reports | heap, CPU, GC, window and tab counts | hot frames, deadlock cycles, heap histogram, spans |
| Reach for it | before opening another tab or terminal | when something the agent started is slow or stuck |

So the two are complementary rather than competing. If the question is about BOSS, call
`get_performance_metrics`. If it is about the build, server or test run the agent just launched,
call these.

## The tools

| Tool | `readOnly` | Use it when |
|---|---|---|
| `telemetry_list_targets` | yes | Always first. Only targets reporting `attachable: true` can be profiled. |
| `telemetry_profile_cpu` | yes | Something is slow and burning CPU. |
| `telemetry_thread_state` | yes | Something is stuck but shows no CPU. |
| `telemetry_capture_heap` | **no** | Memory climbs, or you suspect a leak. |
| `telemetry_query_traces` | yes | A request is slow and you want to see where the time went. |
| `telemetry_explain_bottleneck` | yes | You do not yet know which of the above you need. |

Read `self_time_percent` as "the CPU was executing this frame" and `cumulative_time_percent` as
"this frame was somewhere on the stack". A dispatch method sits near 100% cumulative and near 0%
self; the frame worth optimising is the one with high self.

Each profile also returns `collapsed_stacks` in Brendan Gregg's folded format
(`root;mid;leaf count`), which every flamegraph renderer consumes directly.

## Governance

No governance is implemented in the provider. The host already owns the kill switch
(`McpToolRegistryCore`), the policy engine, the approval prompt and the ledger, and a second copy
inside a provider would be a duplicate that can disagree with the real one. What the provider does
is declare itself honestly so those mechanisms classify it correctly.

`telemetry_capture_heap` declares `readOnly = false`. Its `force_gc` argument makes another
process stop and collect, which is a side effect on something the agent does not own, so it
inherits the host's ASK default and an operator is prompted. Everything else only reads.

No tool declares `requiredPermissions`. This matches `WorkspaceMcpToolProvider`: the MCP server is
loopback only for the local machine's own agents, and the documented posture there is that an
undeclared tool is permitted. Operators who want these off should use the Toolbox kill switch,
which is the control that always applies (an admin session short-circuits the RBAC check).

### What a target does NOT disclose

`telemetry_list_targets` reports each process's **executable path**, never its command line.
Argument vectors routinely carry `--token=`, `-Dspring.datasource.password=`, presigned URLs and
API keys; the tool enumerates the whole machine rather than only what the agent spawned; and it
declares `readOnly = true`, so the policy engine allows it with no prompt. Those three together
mean returning argv would relay up to 100 processes' arguments to a remote model with no operator
in the path and no gate anywhere to add one.

Redacting instead of omitting was considered and rejected: masking the value of a `--key=value`
pair leaves a bare positional secret (`myapp s3cr3t`) untouched, so redaction would have to guess
which words are secrets. An executable path cannot carry one. The cost is that two `java`
processes look alike in the listing - identify the one you want by the pid you started it with,
which is how an agent reaches these tools anyway. `TelemetryUnitsTest` pins this by asserting the
reported command equals `ProcessHandle.Info.command()` and is shorter than the full argv.

The host's own governance still applies to everything else here: sanitized arguments and error
snippets reach the ledger under the caveat `AGENTS.md` records for them.

## How it works, and what that costs

Sampling goes through the JDK Attach API: attach to the target, `startLocalManagementAgent()`,
connect JMX, then poll `ThreadMXBean.dumpAllThreads` at 99 Hz and fold the stacks. This is the
technique VisualVM's sampler uses.

It is deliberately **not** async-profiler. That needs a per OS native library, a relaxed
`ptrace_scope` on Linux and a signed entitlement on macOS. Everything here is pure JDK, so it
works on all three platforms with no native artefact to ship. `jdk.attach` and
`jdk.internal.jvmstat` were already in the jlink module list in `composeApp/build.gradle.kts`, so
packaged builds need no change.

What that costs, stated so nobody over reads the numbers:

- Samples land on safepoints, so a method the VM never polls inside is under represented.
- A thread blocked in a native call reports the Java frame that called it.
- Sampling only sees threads that are RUNNABLE. Time spent waiting on a database or a network
  call does not appear as a hot frame, which is why an idle verdict says so explicitly rather
  than reporting "healthy".

Good enough to answer "which of my methods is burning CPU". Not a substitute for a production
profiler when chasing a JIT or GC pathology.

### Sampler noise, and why the filter exists

The first spike against a target whose only busy method was `hotMethodUnderTest` reported four
frames at 40 samples out of 40, and three were artefacts:

```
  TOP 40  sun.nio.ch.Net.accept                               <- the JMX listener we started
  TOP 40  Target.hotMethodUnderTest                           <- the only real answer
  TOP 40  java.lang.ref.Reference.waitForReferencePendingList <- idle, but reports RUNNABLE
  TOP 40  sun.management.ThreadImpl.dumpThreads0              <- the sampler observing itself
```

Unfiltered, a profile of an idle process is 75% noise. `dumpThreads0` is worse than noise: it is
an observer effect that scales with sampling frequency, so raising the rate to get a better answer
makes the answer worse. `SamplingFilters` drops these, and `SamplingFiltersTest` pins each case.

### Limits that are enforced, not advisory

- Duration is clamped to 1-10 seconds. The host wraps every MCP call in a 60 second timeout, so a
  longer profile would be killed mid flight and return nothing at all.
- Frequency is clamped to 1-1000 Hz, defaulting to 99 rather than 100 so sampling does not lock
  step with timers that also tick on that boundary.
- Reported frames are capped (20 by default, 100 maximum), and `telemetry_list_targets` caps at
  100 processes. The cap is also a safety property: the RSS lookup passes its pid list to a
  subprocess, and `ProcessFootprint` documents that an unbounded list deadlocks.
- A target that exits mid profile returns the samples already taken with `partial: true` and a
  reason. It never throws.

### Non JVM targets

Node, Python and native processes are listed with `attachable: false` and are not profiled. This
is reported rather than inferred from the executable name: a JVM started with
`-XX:+DisableAttachMechanism`, or owned by another user, is a `java` process that still cannot be
profiled, and claiming otherwise sends an agent to a call that can only fail.

### The host can profile itself

The JDK refuses self attach unless `-Djdk.attach.allowAttachSelf=true` was set at launch, which
cannot be required of an already running host. Passing BOSS's own pid therefore reads the local
platform beans directly, which is both allowed and cheaper.

## The OTLP receiver

`telemetry_query_traces` is backed by an OpenTelemetry receiver speaking **OTLP/HTTP with the
JSON encoding** on `POST http://127.0.0.1:4318/v1/traces`. Point any instrumented app at it:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/json
```

JSON rather than Protobuf because OTLP defines both as first class encodings and every mainstream
SDK emits JSON when pointed at an `http/json` endpoint. Protobuf would have meant a code
generator, a `protoc` toolchain and a new host dependency, for a wire format the sender can simply
be asked not to use.

### No server framework was added

`composeApp/build.gradle.kts` deliberately **excludes** the entire ktor server stack. The reason
is written there: 529 `io.ktor.server.*` classes in the host classloader are fallback targets for
any plugin that bundles its own ktor server, which is exactly the material a plugin classloader
parent fallback turns into a loader constraint `LinkageError`. `KtorServerAbsentFromHostTest` is
the CI guard, and it scans the classpath for `io/ktor/server/` rather than for specific names.

Adding a framework back for one endpoint would reverse that decision. The receiver uses
`com.sun.net.httpserver` from the JDK instead: no dependency, no classpath additions, and
`jdk.httpserver` was already in the jlink module list, so packaged builds need no build change.

### Posture and bounds

- **Nothing listens until an agent asks for traces.** The receiver is started lazily by the first
  `telemetry_query_traces` call, so an install where nobody uses tracing never opens a port. The
  first call says so and tells you how to configure the exporter.
- **Loopback only**, enforced by binding to the loopback address. That is a kernel level
  guarantee, not a check that can be bypassed. The handler re-checks the peer anyway.
- **The buffer is bounded on two axes**, 10,000 spans and 64 MB, whichever is reached first,
  evicting oldest. A count alone does not bound memory, since one span can carry a large
  attribute; a byte ceiling alone degrades when spans are tiny. A single span larger than the
  whole ceiling is kept rather than evicting the buffer to empty and looping.
- **Request bodies are capped at 8 MB**, checked against `Content-Length` and again while
  reading, because a chunked request declares no length.
- **And capped in TIME, because there are only two handler threads.** A client may declare a body
  and then send nothing, which is well formed and under no obligation to finish; two of those
  would otherwise hold both handlers for as long as they liked and trace ingestion would simply
  stop. A body that has not arrived within ten seconds is abandoned with a 408 and counted.

  This is two mechanisms on purpose. The read loop checks a deadline between chunks, which covers
  a client dripping bytes slowly. It cannot cover a client that sends nothing at all, because the
  first read blocks before any check runs and `com.sun.net.httpserver` exposes no socket to set
  `SO_TIMEOUT` on - so `sun.net.httpserver.maxReqTime` is set as well (only when absent, since it
  is a global JVM property), which makes the server itself close the exchange and free the thread.
  That property is read during static initialisation, so it takes effect only if nothing in the
  process created an `HttpServer` first, which is why it is the backstop rather than the fix.
  `OtlpReceiverTest` stalls one client per handler thread and asserts a valid export still
  returns 200.
- **A busy port is not an error state.** If something already owns 4318 (a real collector, say),
  the tool says so and the other five tools keep working.
- **Malformed input is skipped, never guessed at.** One bad span does not lose the rest of its
  batch, and the reply carries an OTLP `partialSuccess` count so a partial decode is visible.

## Trying it

```bash
./gradlew :composeApp:desktopTest --tests "ai.rever.boss.mcp.telemetry.*"
```

In a running BOSS, open Toolbox > MCP and confirm the five tools are listed, then from an agent in
BossTerm:

```
mcp__boss__telemetry_list_targets   {"filter_type": "jvm"}
mcp__boss__telemetry_profile_cpu    {"pid": <pid>, "duration_seconds": 3}
mcp__boss__telemetry_thread_state   {"pid": <pid>}
mcp__boss__telemetry_query_traces   {"min_duration_ms": 500, "status_error_only": true}
```

The live tests spawn real child JVMs. They **skip rather than fail** where the OS forbids attach
(Linux `ptrace_scope=2`, hardened container seccomp profiles), because a red build on such a
machine says nothing about the code.
