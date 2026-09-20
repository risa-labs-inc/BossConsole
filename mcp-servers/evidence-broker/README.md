# Evidence Broker

An MCP server for the **technical operator** persona: the agent names a credential it never
sees, the broker decides whether that credential may reach that host, and every decision —
allow or deny — is sealed into a hash-chained ledger you can verify afterwards.

Hackathon track: **Extend — build a tool or plugin.** Built for the *Web Workflow Kit*
persona on the idea bench, specifically its "broker credentials / capture evidence / keep
approval with the operator" half.

## The problem

An agent attached to BOSS gets a browser signed in to your accounts and a shell. The moment
it needs to call an authenticated API on your behalf, the usual answer is to put the token in
its context — and now the token is in a transcript, in a log, and in whatever the agent
decides to echo back. The operator has no way to say "yes, that call, once" and no way to
prove afterwards what was actually sent.

## What this adds that BOSS and its plugins do not already do

This needs stating up front, because on the face of it the space is taken. Checked against
`main` at v9.5.21 (`d4db048`), BOSS already has real governance here, and it is good:

- `McpToolRegistryCore.invoke` (declared in `composeApp/.../mcp/McpToolRegistryImpl.kt:743`)
  consults `policyEngine.policyFor(...)`, awaits an operator decision through
  `McpApprovalBus`, and calls `ledger.record(...)`.
- `McpOperationLedger` persists to `~/.boss/mcp-calls.jsonl`, append-only with size-based
  rotation.
- `McpArgumentSanitizer.sanitize` scrubs the arguments before they are recorded, so the host
  is *not* naively writing secrets to its ledger.

Two plugin proposals sit alongside it: [Agent Warden](https://github.com/risa-labs-inc/boss-plugins/issues/33)
gates which MCP tools an agent may call, and [Run Ledger](https://github.com/risa-labs-inc/boss-plugins/issues/35)
records what produced a result.

Every one of those governs at **tool granularity** — may this tool run — and then records that
it ran. None of them has a concept of a *credential*. Two gaps follow, and they are the whole
reason this package exists:

| | Existing layers | Here |
|---|---|---|
| **Credential scope** | Nothing binds a secret to the hosts it may reach, so a *permitted* tool making a *permitted* call can still carry any credential anywhere | A credential is scoped to hosts and methods; an allowed tool cannot carry `STRIPE_KEY` to `evil.test`, and the target is **parsed** rather than substring-matched (BOSS has that bug class open against itself in [#1250](https://github.com/risa-labs-inc/BossConsole/issues/1250)) |
| **Ledger integrity** | `McpOperationRecord` carries `id`, `timestamp`, `toolName`, `policyApplied`, `approvalDisposition`, `sanitizedArgs` … and no hash, no previous-entry link and no signature. Anyone who can write the file can edit a row, and nothing detects it | Hash-chained, with the head stored separately so entries removed from the *end* are detectable too |

So this is not a replacement for any of them. It composes: Warden answers *may this tool run*,
the host answers *did the operator allow it*, and this answers *may this secret go to that
host, and what can we still prove an hour later*.

## Security properties, and where each one is enforced

Every row here has tests. `npm test` is 119 of them.

- **The secret never crosses the agent boundary.** It is read inside `send`, attached to one
  request, and never returned or sealed. `broker.ts`
- **Redaction covers encodings, not just the raw value.** Raw, URL-encoded, base64, base64url
  and JSON-escaped forms are all scrubbed, longest-literal-first so a short literal cannot
  partially consume a longer one. A secret too short to redact safely (< 6 chars) is refused
  outright rather than handled badly. `redact.ts`
- **The host is parsed.** `api.stripe.com.evil.test` does not match `api.stripe.com`;
  `*.example.com` matches strict subdomains but deliberately not the apex; userinfo
  (`https://api.stripe.com@evil.test/`) is refused outright as ambiguous; non-http(s) schemes
  cannot carry a credential at all. `target.ts`
- **An approval cannot be replayed for a different action.** The operator approves a digest
  over the whole request — credential, method, host, path, header *names*, body hash. Note
  `send(grantId)` takes a grant id and nothing else: there is no parameter through which a
  different request could be substituted, so substitution is unrepresentable at the MCP
  boundary rather than merely checked for. `approval.ts`
- **The agent cannot approve its own request.** There is no `approve` tool. The MCP surface is
  five tools and a test asserts the set, so it cannot grow one silently. `server.ts`
- **Redirects are not followed.** Following one would re-make the allowlist decision on the
  remote server's terms. A 3xx is handed back as data. `broker.ts`
- **Denials are evidence too.** A ledger that only records successes cannot answer "what did
  the agent try". Every refusal is sealed. `broker.ts`
- **Default-deny throughout.** No hosts means no hosts, not any host. No operator channel
  means deny, not allow. Config is validated at startup so a typo fails loudly instead of
  quietly widening an allowlist. `config.ts`

## The operator channel has no network surface

The agent owns this process's stdio, so the operator cannot be prompted there. The obvious
alternative — a loopback HTTP control port — is the wrong trade: it is new listening surface
that then needs its own authentication, and an unauthenticated local endpoint is a bug class
BossConsole already has open against itself ([#1333](https://github.com/risa-labs-inc/BossConsole/issues/1333),
[#1326](https://github.com/risa-labs-inc/BossConsole/issues/1326)).

So approvals are files. The broker writes a request and waits; you answer in your own
terminal. No port, no listener, and the decision is a durable artifact rather than a click
nobody can reconstruct later.

## Use it

Requires Node 22+ (the test runner uses glob arguments, which landed in 22). No network
access is needed to build or test.

```bash
npm install
npm test
npm run build
```

Write `.boss/evidence-broker/config.json` (see `config.example.json`):

```json
{
  "credentials": [
    {
      "name": "STRIPE_KEY",
      "allowedHosts": ["api.stripe.com"],
      "approval": "each-use",
      "allowedMethods": ["GET"],
      "injection": { "kind": "header", "header": "Authorization", "prefix": "Bearer " }
    }
  ]
}
```

Secrets come from the environment, one variable per credential, and never leave the process:

```bash
export BROKER_SECRET_STRIPE_KEY=sk_live_...
```

Point your agent at it:

```json
{ "mcpServers": { "evidence-broker": { "command": "node", "args": ["dist/src/index.js", "serve"] } } }
```

Then, as the operator:

```bash
boss-evidence-broker pending          # what is waiting on you
boss-evidence-broker approve <id>     # yes, that call
boss-evidence-broker deny <id> "not during the freeze"
boss-evidence-broker verify           # does the ledger still verify?
boss-evidence-broker log 20           # the last 20 sealed entries
```

### The agent's tool surface

| Tool | Purpose |
|---|---|
| `list_credentials` | Names and scopes. Never values. |
| `request_credential_use` | Ask for one specific request. Returns a grant id, or a denial. |
| `send` | Execute exactly the approved request. Takes only a grant id. |
| `verify_evidence` | Recompute the chain, check it against the stored head. |
| `recent_evidence` | Recent sealed entries, allows and denials alike. |

## Design notes

The core is pure and injectable — clock, ids, the vault, the operator, and `fetch` are all
constructor dependencies. So the whole suite runs offline and deterministically, and
`test/e2e.test.ts` deliberately does *not* use the doubles: it stands up a real HTTP server on
loopback, uses the real global `fetch`, the real filesystem and the real file-based approval
channel, and has a real operator answer out of band. That is the test that would catch a
component which only works when it is mocked.

Evidence is an **attestation, not an archive**: an entry holds the sha256 of the redacted
response and its byte count, not the body. Enough to prove later that a saved response is the
one you received, without the broker hoarding response bodies. Request paths are recorded
without the query string, so a token in a query parameter cannot reach the ledger that way.

Durable writes are deliberate. A rename is atomic with respect to the directory entry and
says nothing about whether the bytes reached disk, so the head file is written, fsynced, then
renamed, then the directory is fsynced. BossConsole has this exact gap open against its own
`atomicWriteText` ([#1240](https://github.com/risa-labs-inc/BossConsole/issues/1240)), which is
where the shape of it came from.

## What this does not do, and what is not verified

Stated plainly, rather than left for a reviewer to find:

- **The chain is tamper-evident, not tamper-proof.** Someone who can write both `ledger.jsonl`
  and `head.json` can rebuild a consistent chain. Separating the head raises the bar from
  "edit one line" to "rewrite both files", and no further. Closing it properly needs an anchor
  outside the machine — signing with a key the agent cannot reach, or appending the head to a
  remote — which is follow-up work, not something this package claims.
- **It has never been loaded into a running BOSS host.** It is a standalone MCP server and was
  verified as one: 119 tests, plus an `initialize` / `tools/list` handshake driven over real
  stdio. Whether BOSS's own approval dialog should front the gateway instead of the file queue
  is a maintainer's call.
- **Secrets are read from environment variables, not BOSS's vault.** Wiring to the host vault
  is the natural follow-up; the value never leaves the process either way.
- **Grants live in memory**, so restarting the broker drops outstanding approvals. That is the
  safe direction to fail, but it is a real limitation for a long-lived session.
- **Not following redirects will break some legitimate flows.** That is the intended trade;
  the agent gets the 3xx and can request the new target explicitly, which is another approval.
- **Directory fsync is a no-op on Windows**, where opening a directory for it fails. Best
  effort by design: platforms that support it get the guarantee, and the rest are no worse off
  than a plain rename.
- **Concurrency is single-process.** Two brokers sharing one evidence directory would
  interleave writes and break the chain. There is no lock file yet.

## Layout

```
src/
  target.ts        egress URL parsing, host allowlisting        <- the substring bug class
  redact.ts        multi-encoding secret scrubbing
  chain.ts         hash-chained evidence, verification
  approval.ts      action digests, grants, anti-replay
  broker.ts        the orchestrator; the only place a secret is read
  config.ts        strict startup validation, default-deny
  store.ts         append-only ledger, durable atomic writes
  gateway-file.ts  the operator channel, as files
  server.ts        the MCP surface (five tools, no approve tool)
  index.ts         serve / pending / approve / deny / verify / log
test/              119 tests; e2e.test.ts uses the real pieces
```

Apache-2.0, matching BossConsole.
