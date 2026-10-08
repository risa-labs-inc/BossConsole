# Managed BOSS AI

The function owns inference routing, authorization, provider metadata and per-user/model allowances.
Secret Manager automatically discovers BOSS AI using the existing generic authenticated Supabase RPC
API. No host-specific broker registration, shared vault definition, or upstream API key on the
desktop is needed.

## Deployment

1. Apply `20260912000000_boss_ai.sql`, `20260912001000_boss_ai_hardening.sql`,
   `20260912002000_boss_ai_validation.sql`, `20260912003000_boss_ai_allowance_preflight.sql`, and
   `20260912004000_boss_ai_exchange_tickets.sql` in order. Earlier files have already been applied
   to the preview branch; fixes are forward migrations, not edits to applied history.
2. Set `BOSS_AI_SIGNING_SECRET` to at least 32 random bytes (encoded as a string). Set upstream API
   keys as secrets named `BOSS_AI_<NAME>`. The signing-secret name is explicitly prohibited as an
   upstream key in both SQL and the handler. Never put these keys in a shared vault entry. Supabase
   supplies its URL and service-role key.
3. Deploy `boss-ai` using this repository's `supabase/config.toml`. `verify_jwt=false` is required
   because `/auth/exchange` redeems a single-use ticket, `/auth/token` supports legacy BOSS
   sessions, and catalog/inference routes verify AI-scoped tokens.
4. Configure one or more connections, models, and permission allowances using the SQL editor or
   service-role administration. No client role can read/write these tables or impersonate a user
   through the accounting RPCs.
5. Install the updated Secret Manager plugin (1.2.25 or later). Existing AI Gateway Chat Completions
   transport and the existing desktop generic Supabase RPC API are sufficient. BOSS AI is created in
   memory automatically; new users do not create or receive a secret.

The integration audit checked AI Gateway's `OpenAiChatFormat.buildPayload` in `WireFormat.kt`:
`model`, `max_tokens`, optional temperature, streaming with usage, tools, and complete message/tool
history all fit the allowlist. This is a constrained Chat Completions surface, not a promise to
support every OpenAI parameter. Unknown top-level fields and function/format/image envelopes are
rejected; JSON Schema contents remain opaque schema data. Also accepted: `reasoning_effort`
(`none`/`minimal`/`low`/`medium`/`high`/`xhigh`, only on models with the `reasoning` capability;
sent as `reasoning.effort` on Responses connections), and on Chat connections `stop` (1-4 strings),
`seed`, `presence_penalty` and `frequency_penalty` (-2 to 2). A `max_tokens` above the model's
`max_output_tokens` is clamped to it. Reasoning usage remains included in the output count.
Streaming always requests usage for accounting; `stream_options` accepts only `include_usage=true`
(or an empty object), never disabling usage or adding vendor fields. Every configured period
applies. Days, ISO weeks starting Monday, and months reset at their UTC calendar boundaries. Usage
belongs to the request's admission period and is never reset by a permission change.

Admission reserves the model's entire configured context allowance, including input, output and
reasoning. This conservative bound works across upstream tokenizers and inline images without
trusting client token estimates. It means a request needs that much remaining capacity even if its
eventual usage is small. Set context limits and allowances together. Actual provider-reported input
plus output tokens replace the reservation on settlement; reasoning is already included in output
and is not counted twice. Unknown or interrupted outcomes retain the full charge. A rejected
pre-inference request releases it. Known validation, authentication and billing rejections
(including OpenRouter 402) are refunded. Fetch rejection, timeout, 408/409 and upstream 5xx remain
unknown: response headers arriving is not evidence of when inference began, and a proxy can fail
after forwarding work. Refunding these automatically would allow cancelled requests to evade quotas.
This intentionally favors a strict spend bound over automatic outage refunds. Operators can inspect
the request ledger for an audited correction; clients cannot refund themselves.

Missing usage emits `usage_unknown_reservation_retained`; it is never silently treated as zero.
Published connections must pass both streaming and non-streaming usage checks before rollout. An
upstream reporting more than the configured context emits `usage_exceeds_configured_context`, and
the charge is capped at the admitted reservation. Settlement retries and late stream truncation
after a usage frame preserve the measured count rather than treating it as unknown. Investigate
either diagnostic before continuing to publish the connection. Settlement is attempted twice; if
both attempts fail, a successful completion is still returned and the full reservation remains
charged. Each diagnostic event is emitted at most once per request, including across retries.

Each settlement attempt has a two-second deadline, including error and streaming cleanup paths.
Timeouts retry safely because `boss_ai_settle` is first-writer-wins for a request ID. Two failed
attempts emit `settlement_failed_reservation_retained`; operators must reconcile the request ledger
by that ID. There is no automatic refund or background reconciler in this change.

Limits are sized for agent clients: at most 512 tools and 512 replayed calls per message, 2,048
messages, 32,768-character tool descriptions, and parameter/structured-output schemas of 262,144
serialized characters, depth 64 and 32,768 visited values. The 16 MiB request body cap is the
aggregate limit. Prompt length is judged by the model: an upstream context-length rejection is
returned as a refunded 400 `context_length_exceeded` (the upstream text is never echoed). Valid
signed-in sessions without AI eligibility receive 403, not a request to sign in again; eligibility
RPC failures return 503 without minting a token.

Requests are limited to four minutes, below the five-minute concurrency lease. A worker crash leaves
the charge in place but releases its concurrency slot after the lease. Usage rows should be retained
for accounting; archival must preserve all rows contributing to any current period. Deleting an
account intentionally cascades its usage rows. `/v1/usage` returns
`{object:"list",data:[{model,
allowance}]}`, including UTC reset timestamps; it is inside the same
broker scope as `/v1/models`.

| State                                              | Admission/accounting behavior                              |
| -------------------------------------------------- | ---------------------------------------------------------- |
| Missing/invalid BOSS login                         | Token exchange returns 401                                 |
| Wrong audience, expired or forged AI credential    | No catalog or inference access                             |
| Permission revoked, user banned, model unpublished | New inference returns 403                                  |
| Multiple active requests                           | Atomic per-user/model reservation prevents double spending |
| Client disconnected / worker interrupted           | Full reservation retained                                  |
| Successful result with usage                       | Charge input plus output once                              |
| Retried settlement                                 | First settlement wins                                      |
| Role allowance reduced                             | Existing usage survives; further requests may be refused   |

AI credentials last five minutes and renew automatically. BOSS sign-out clears the client cache. A
copied token may remain usable until expiry; this mechanism authenticates a BOSS-issued session, not
the executable making the request. Live permissions and bans are checked on catalog/inference
requests. App attestation and device-bound signing are not implemented.

No inference content or credentials are logged. Errors use owned messages. Diagnostics contain only
owned event/phase names, numeric upstream statuses, request UUIDs, and validated database SQLSTATE
codes, never exception messages, SQL parameters or upstream bodies. Every response carries
`X-Request-ID`. No automatic cross-provider fallback is enabled. Routing secrets and configuration
are private, but generated content and provider-specific choice metadata are not an
upstream-identity anonymization boundary.

## Verification

Run `deno task check` and `deno task test` in this directory. The database test runs the migration
in PostgreSQL/WASM with an auth/RBAC fixture, including grants, permission revocation, duplicate
requests, concurrent-slot limits, allowance aggregation and settlement. CI also runs
`supabase/tests/boss_ai_test.sql` against the complete migrated Supabase/RBAC schema, covering real
service-role access, denied client grants, bans, connection revocation, lease expiry and UTC period
boundaries. `scripts/test/test-boss-ai-concurrency.py` holds one PostgreSQL transaction open while
proving a second session waits on the advisory lock, then verifies it cannot overspend. The same
script refuses REPEATABLE READ/SERIALIZABLE and derives the local container name from
`supabase/config.toml`. Run it from the repository with Python 3.11+ after `supabase start` to
repeat the real concurrency proof locally. Use a disposable local database; a hard-killed test may
leave fixtures, so discard that test database before retrying. Cleanup failures preserve the
original error. The committed dependency lockfile is enforced by both Deno tasks; `check` also
checks formatting and type-checks the test modules.

SSE requires the protocol's completed event (`[DONE]` for Chat Completions) and complete data
frames. A non-null finish reason alone is not enough: usage can arrive in a subsequent chunk.
Trailing comments are harmless, but partial data frames are failures. Browser CORS and desktop
executable attestation are intentionally not supplied. Production gateway rate limits should also
cover `/auth/token`; authenticated model quotas are not a substitute for HTTP abuse protection. This
also applies to valid requests rejected by the upstream: refunded token reservations do not limit
request frequency or ledger growth. Operators must configure request-rate protection before
publishing models. A redirecting upstream URL is a configuration error with unknown inference
outcome, so it retains the reservation just like other ambiguous fetch failures; validate the final
URL first.

Wire contracts were checked against:

- https://developers.openai.com/api/docs/guides/function-calling
- https://developers.openai.com/api/docs/guides/streaming-responses
- https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/retrieve
- https://supabase.com/docs/guides/functions/limits

Live upstream compatibility, project migrations, and the real role-sharing flow still require
staging credentials and configured models before production rollout.
