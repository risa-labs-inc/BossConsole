# Secret references in MCP tool calls

An agent can direct a governed tool to use a stored credential without ever holding the
credential itself. It writes a reference where the value would go:

```json
{ "path": ".env", "content": "STRIPE_KEY={{secret:6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5}}" }
```

The host resolves the reference at the MCP governance boundary, after the operator approves the
call, and hands the plugin the real value. The agent's transcript, the approval dialog, the
operation ledger and the host log carry only the reference.

This document is the contract: what is guaranteed, what is not, and why each part is where it is.

## The guarantee, stated exactly

**Capability without direct credential disclosure to the agent.**

On the governed path (every `mcp__boss__*` tool that reaches `McpToolRegistryCore.invoke`), a
resolved secret value never appears in:

- the arguments the agent authored (it wrote a reference),
- the result the agent reads (see "Result scrubbing" for the limits),
- the approval request and dialog the operator sees,
- the operation ledger (`~/.boss/mcp-calls.jsonl`),
- host log entries.

This is a **non-disclosure** guarantee. It is **not** a **non-exfiltration** guarantee. A tool the
operator authorizes to receive a secret can send it anywhere that tool can reach; that is the
same trust an operator extends today when approving any tool call, and this feature makes the
secret's use visible at the approval site rather than hidden behind an earlier `secret_get`.

### Non-goals, so nobody assumes more than exists

| Not covered | Why |
|---|---|
| Exfiltration by an authorized tool | The tool is trusted with the value by the operator's approval. Plugins run in-process and already hold the whole vault through `PluginContext.secretDataProvider` (`DefaultPlugin.kt`); references add no plugin-side exposure. |
| Values the tool transforms (hash, base64, ciphertext, case change, double encoding) | The scrubber recognises exact forms only. See the table under "Result scrubbing". |
| BossTerm's built-in shell tools (`run_command`, `send_input`, `read_scrollback`, ...) and terminal-tab's `run_in_sidebar` / `cli` | They are served by BossTerm's own MCP server and never reach the host registry (BossConsole#495). A reference typed into one of them is never resolved and passes through as literal text. |
| Plugin-internal logging | A handler that logs its own arguments logs the value. The host controls its own log, not a plugin's. |
| TOTP codes and recovery codes | Not addressable by a reference. A six-digit code cannot be scrubbed, and no BOSS workflow consumes one through a tool today. |

## Syntax

```
{{secret:<uuid>}}            the secret's password
{{secret:<uuid>.password}}   the same
{{secret:<uuid>.username}}
{{secret:<uuid>.notes}}
```

- `<uuid>` is the id `secrets_list` or `secret_search` returns. Upper or lower case hex.
- References are recognised inside JSON **string values** at any depth, including inside arrays
  and nested objects. They are never recognised in keys.
- Anything else of the shape `{{secret:...}}` is malformed and refuses the whole call. A tool is
  never handed placeholder text it might mistake for a value.
- One call may carry several references. They resolve all or nothing.

## What happens to a call, in order

```text
agent --> terminal-tab bridge --> McpToolRegistryCore.invoke(name, argsJson)
  [0] kill-switch and RBAC for the tool               unchanged; a withheld tool reads no vault
  [1] substring scan for "{{secret:"                  a call without it pays only this
  [2] parse the argument tree                          malformed reference -> SECRET_UNRESOLVED, refused
  [3] secretReferencesEnabled = false                  -> SECRET_FORBIDDEN, refused
  [4] no secret.read (non-admin)                       -> SECRET_FORBIDDEN, refused
  [5] tool or provider policy DENY                     -> POLICY_DENIED, refused, no vault read
  [6] secretBearingCalls = DENY                        -> SECRET_FORBIDDEN, refused, no vault read
  [7] more than 16 distinct references                 -> SECRET_UNRESOLVED, refused, no vault read
  [8] resolve every reference, one vault read each     unknown id, empty field, vault failure -> SECRET_UNRESOLVED
                                                        AI-provider key -> SECRET_FORBIDDEN
  [9] prompt the operator                              ALWAYS, whatever the tool's rule or session trust says;
                                                        the dialog lists website (username) - field per secret,
                                                        and risk is raised to at least HIGH
 [10] confirm (secret fence, then revocation fence)    secret.read lost meanwhile -> SECRET_FORBIDDEN;
                                                        a DENY or reset saved meanwhile -> POLICY_DENIED
 [11] substitute                                       one rewrite of the argument tree; scalar map and raw JSON agree
 [12] execute                                          unchanged timeout and failure handling
 [13] scrub                                            defense in depth; before the cap
 [14] cap                                              unchanged
 [15] ledger                                           the ORIGINAL arguments (references intact) + secretRefs
```

Refusals at [2] to [8] happen before any prompt, so the agent gets an immediate, precise error
and the operator is never asked about a call that could not run.

### Why the vault is read before the prompt

The operator must see *which* secret the tool would receive. That metadata (website, username)
comes from the same RPC that returns the value, so one read serves both. The value is held for
this call only, on the host side, and delivered to the handler only after the operator approves
and the revocation fence passes. Reading twice (metadata first, value after approval) would be
theatre: the value has already been in process memory either way.

What that read costs is bounded on both sides. Each reference is one `get_user_secret_by_id`
RPC, which decrypts the referenced row and no other (its visibility rule is the listing's: the
user's own secrets and their organisations'), so a reference to an id that does not exist costs
the vault one lookup and brings nothing else into host memory. And a call may carry at most 16
distinct references, refused before any read above that, so the number of reads an agent can
cause with one call, and the number of lines the operator has to read in the dialog, both have a
ceiling. Before the by-id RPC existed the resolver walked `get_user_secrets` page by page, every
row decrypted server-side on the way, and an unknown id walked the whole vault before it was
refused; that is the shape this replaces.

## Approval semantics

`Permission(tool)` is not `Permission(tool, secret)`.

- A secret-bearing call always asks. Tool-wide "Always Allow", provider trust ("Trust this
  plugin") and session trust all apply to the tool and never satisfy a secret-bearing call.
- The host policy `secretBearingCalls` is `ASK` (default) or `DENY`. There is no `ALLOW`. The
  primitive contains no configuration that turns it into silent delivery.
- `secretReferencesEnabled = false` removes the feature: secret-bearing calls are refused, not
  passed through.
- Silent execution for a specific tool and secret, if ever wanted, is a later design:
  `grant(tool, secret, scope, expiry)` with its own review and revocation surface. It is not
  sketched here.

Precedence, most authoritative first: kill-switch, RBAC (tool, then secret.read), unreadable
policy fault, tool/provider DENY, secret policy, session trust, tool rule, provider ALLOW,
defaults.

## Result scrubbing (defense in depth)

The guarantee rests on the argument path, the approval and the ledger. The scrubber exists for
an honest handler that echoes its input in the same call: a tool that returns the request it was
given, a stack trace that quotes an argument, a write that answers with the content it wrote. It
replaces each value resolved for that call with `[secret:<id>.<field>]` in that call's result and
failure text.

Its scope is the one call. The host holds a value only for the duration of the invocation that
carried the reference, so a later call has nothing to scrub against: a `codebase_read` of a file
that a previous approved `codebase_write` filled from a reference returns the file as it is on
disk, plaintext included. That is the operator's decision at approval (writing a credential to disk
puts it where every file read can reach it), and the dialog names the secret so the decision is an
informed one. Scrubbing every later result against every value delivered in the session would
cover that case at the price of retaining plaintext in the registry for the session and of giving
up INV5 (calls without references stay byte-identical); it is listed under future work, not done.

| Transformation of the value | Scrubbed |
|---|---|
| identity | yes |
| JSON string escaping | yes |
| URL percent-encoding (UTF-8), `+` or `%20` for space | yes |
| double percent-encoding | no |
| base64 | no |
| hashing, encryption | no (undetectable) |
| case change | no (credentials are case-sensitive; folding would over-match) |
| values shorter than 8 characters | not scrubbed at all (would match inside ordinary words) |

`resultScrubbingEnabled = false` switches the scrubber off. The invariant tests run with it off
to prove that every other surface still holds. An echoing handler's result then contains the
value, and that returned text can also reach the persisted ledger's error snippet.

Scrubbing runs before the result cap, so a cut can never land inside a value and leave half of it
readable.

## What the ledger records

Each record gains `secretRefs`, a list of `<id>.<field>`. The `sanitizedArgs` field is built from
the arguments the agent wrote, references intact; the substituted arguments never reach the
ledger. Old records without the field decode with an empty default. Two new dispositions:

- `SECRET_FORBIDDEN`: the host would not deliver (permission, policy, feature off, AI-provider key,
  or `secret.read` lost while the prompt was open).
- `SECRET_UNRESOLVED`: the host could not deliver (malformed, more than 16 references, unknown id,
  empty field, vault failure).

Both count as "withheld" in the MCP activity log, next to `QUEUE_FULL` and
`POLICY_PERSIST_FAILED`: the tool never ran and no operator answered.

## Cost, measured

`SecretReferenceOverheadTest` prints both numbers on every run. On the laptop this was written on
(JDK 17, x64):

| Path | Cost |
|---|---|
| Call without references: the marker scan added to every governed call | 174 ns per call, against the 5.8 µs the existing argument parse already costs (1.5 KB of arguments) |
| Call with references | one `get_user_secret_by_id` RPC per distinct id, at most 16; network-bound |
| Scrubbing a result at the host cap (150,000 characters, 3 values, 4 encodings each) | 0.42 ms per result, linear in the input |

The test asserts loose bounds (an order of magnitude above these) so a slow CI runner does not
fail; they catch a regression to something quadratic, not a microsecond.

## Threat model

| Actor | Trust | What they can do | Control |
|---|---|---|---|
| BOSS host | trusted | resolves secrets, enforces policy | this design |
| Operator | authorization authority | approves or denies each secret-bearing call | approval dialog with descriptors |
| Agent / model | untrusted | authors tool names and arguments; reads results | never receives values; sees descriptors and scrubbed results |
| Plugin handler | partially trusted (already holds the vault) | receives substituted arguments; may log or forward them | unchanged trust; risk shown at approval |
| External service | untrusted | receives whatever the tool sends | operator's decision; non-goal |
| Persistent surfaces (ledger, host log, transcripts) | must never hold values | | ledger from pre-substitution arguments; scrubbed failure text; log-capture invariant test |
| BossTerm built-in tools | outside the boundary | | documented exclusion; references are never resolved there |

**Enforcement boundary:** `McpToolRegistryCore.invoke`, for governed MCP tool traffic. It is not a
security boundary for the process: plugins are in-process and already hold the vault.

## Invariants (each has a test in `SecretReferenceInvariantTest`)

1. **Non-disclosure.** With references present, the value and each scrubbed encoding of it
   appear in none of: result text, ledger record, approval request, captured host log entries,
   sanitized failure text.
2. **Atomicity.** If any reference is unresolvable, the handler is not called and no argument is
   partially substituted.
3. **Ordering.** Tool/provider DENY, missing permission, feature off and `secretBearingCalls =
   DENY` are decided before any vault read; session trust and tool-wide ALLOW never bypass the
   secret prompt.
4. **Binding.** An approval is bound to the exact (tool, provider, references, revocation stamp);
   a DENY saved while the prompt is open refuses the call.
5. **Transparency.** A call without references behaves byte-for-byte as before: same raw
   arguments to the handler, no vault read, empty `secretRefs`.
6. **Consistency.** After substitution, `args.string(k)` and the raw JSON a handler might parse
   agree for every key, including nested objects and arrays.
7. **Bounded work.** Parsing and scrubbing are linear; the grammar is anchored and its character
   classes exclude braces, so adversarial input cannot backtrack.

Two adversarial handlers anchor the test suite: one that echoes every supported encoding of its
input (nothing leaks), and one that returns a SHA-256 of its input (the hash is returned, the
plaintext still is not: the boundary, demonstrated rather than described).

## Operating it

- **Finding an id:** `secrets_list` or `secret_search` return `id <tab> website <tab> username`.
- **Kill-switching `secret_get`:** references are a separate host path. Disabling or uninstalling
  the Secret Manager plugin does not disable them. To remove agent credential delivery entirely,
  set `secretBearingCalls` to `DENY` (or `secretReferencesEnabled` to `false`) in the policy file
  and restart.
- **Configuration:** three fields in `~/.boss/mcp-tool-policy.json`, all optional:

  ```json
  { "secretReferencesEnabled": true, "secretBearingCalls": "ASK", "resultScrubbingEnabled": true }
  ```

  Policies are loaded at startup; editing the file requires a restart, as for every other field.
- **CLI:** `boss mcp invoke <tool> --args '{"...":"{{secret:<id>}}"}'` takes the same path and
  prompts in the running BOSS window.

## Troubleshooting

| Message | Cause | Next step |
|---|---|---|
| `Malformed secret reference {{secret:...}}: ...` | Not a UUID, or a field other than `password`, `username`, `notes` | Fix the spelling; get the id from `secrets_list` |
| `Secret references require the secret.read permission` | Non-admin user without `secret.read` | Ask an admin for the role; the same permission gates `secret_get` |
| `Secret references are disabled on this host` | `secretReferencesEnabled = false` | Operator decision; edit the policy file and restart |
| `Secret-bearing calls are refused by host policy` | `secretBearingCalls = DENY` | Operator decision |
| `no secret with id ...` | Unknown, or not visible to the signed-in user (their own and their organisations' secrets are) | Shared-with-me secrets are not resolvable in v1 |
| `A call may carry at most 16 secret references` | More than 16 distinct references in one call | Split the call, or reference fewer secrets |
| `Secret access (secret.read) was lost while awaiting approval` | Signed out, or the permission was removed, between the prompt and the approval | Sign in again and retry; the ledger records `SECRET_FORBIDDEN` |
| `secret ... has no notes` | The field is empty | Use another field or fill it in |
| `... is an AI provider key ...` | Tagged `ai-provider` | Configure the provider in the host's AI settings; parity with `secret_get` |
| `the vault could not be read (...)` | Signed out, offline, RPC failure | Sign in; retry |
| `No secret vault is available on this host` | Registry constructed without a lookup (tests, or a build without secrets) | Expected outside the desktop host |

## Extending: the seam this establishes

The governed path used to be `arguments → policy → approval → execute → cap`. It is now
`arguments → resolve → policy → approval → execute → filter → cap`, with one pre-pass
(`McpSecretPrePass`, which owns the ordering above), one resolver (`SecretReferenceResolver`) and
one filter (`McpResultScrubber`, behind `McpResultFilter`). The registry core itself gained one
call into the pre-pass, one small `authorize` branch and one filter parameter on execution.

No generic resolver framework is implemented: no registry of resolvers, no plugin SPI, no grammar
beyond `secret`. The seam exists so that a later host-resolved reference kind (a window id, a
project path, an open editor buffer, a brokered short-lived token) has a place to go without
each plugin inventing its own approval and ledger semantics. Adding one is a host design decision
with its own threat model, not a drop-in: the invariants above are the bar it has to meet.

## Future work, deliberately not built

- Environment injection for terminal tabs and Space templates, so shell commands can use secrets
  without the value on the command line (needs terminal-tab and BossTerm changes; BossConsole#495).
- Secrets shared with the user by others (`getUserSecretsWithShared`).
- A per-(tool, secret) grant with scope and expiry, for operators who want fewer prompts.
- Session-scoped scrubbing: replace every value delivered in the session in every later result,
  so a read-back of a file written from a reference is covered too. Costs retained plaintext in
  the registry and the byte-identical property of reference-free calls; needs its own decision.
- TOTP, if a concrete agent workflow demands it, with the "code visible in output" caveat stated
  up front.
