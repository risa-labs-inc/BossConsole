# Agent Mission Control

Open Mission Control from the MCP status item in the bottom bar to inspect recent tool calls,
filter their status, require approval for a tool, turn on global safe mode, or disable a tool.
Approval settings last for the current app run and default to off. Kill switches use the existing
persisted registry settings. Approval is requested after existing permissions and kill switches pass;
permissions and provider identity are checked again before executing an approved call.

A pending call can be approved, denied, or edited in the scratchpad. Approving edited arguments
resolves that same pending call. Replaying a completed call creates a new invocation through the
registry and its current controls. Edited or replayed arguments must be a JSON object with omitted
values replaced. Closing the workbench cancels a replay coroutine; no background replay is detached.

The recorder retains at most 200 history rows and 16 pending approvals. Each invocation has one row.
Approvals expire after 60 seconds. Caller cancellation removes the pending request and records
cancellation. Aggregate counts describe retained history, not a lifetime audit total.

Arguments are bounded and redacted by sensitive key names and credential patterns. Nested sensitive
objects are redacted; malformed or oversized JSON is omitted. This cannot identify arbitrary secrets
under ordinary keys. Tool result bodies are omitted from shared history because an arbitrary returned
secret has no reliable recognizable shape. The original tool caller still receives its result.

The built-in get_tool_history and diagnose_last_failure tools require admin access because their
history crosses tool boundaries. History is held in memory only and is cleared on restart. This is
not a tamper-proof or persistent audit ledger.

Related proposals: #336 adds risk-tier classification and #371 adds persistent policy and a JSONL
ledger. #342 supplies a separate dockable trace panel. Their registry interception and history ownership
need explicit integration before they ship together; this workbench does not fully supersede them.

Local automated validation covers recording, bounded history, approvals, edited arguments, denial,
timeout, cancellation cleanup, revocation, secret-output omission and diagnostic access. Manual checks
remain for banner/dialog placement over native browser surfaces, narrow windows and light/dark themes.
