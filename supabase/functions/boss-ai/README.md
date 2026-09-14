# Managed BOSS AI

The function owns inference routing, authorization and per-user/model allowances. Secret Manager
discovers the provider from a shared vault definition. It does not ship a BOSS AI provider entry or
an upstream API key. The host registers only the trusted `boss-ai` credential broker, whose endpoint
cannot be changed by a share.

## Deployment

1. Apply `20260912000000_boss_ai.sql`, `20260912001000_boss_ai_hardening.sql`,
   `20260912002000_boss_ai_validation.sql`, and `20260912003000_boss_ai_allowance_preflight.sql` in
   order. Earlier files have already been applied to the preview branch; fixes are forward
   migrations, not edits to applied history.
2. Set `BOSS_AI_SIGNING_SECRET` to at least 32 random bytes (encoded as a string). Set upstream API
   keys as secrets named `BOSS_AI_<NAME>`. The signing-secret name is explicitly prohibited as an
   upstream key in both SQL and the handler. Never put these keys in a shared vault entry. Supabase
   supplies its URL and service-role key.
3. Deploy `boss-ai` using this repository's `supabase/config.toml`. `verify_jwt=false` is required
   because the handler verifies two distinct credentials: BOSS sessions on `/auth/token`, AI-scoped
   tokens elsewhere.
4. Configure one or more connections, models, and permission allowances using the SQL editor or
   service-role administration. No client role can read/write these tables or impersonate a user
   through the accounting RPCs.
5. Create the shared definition below in Secret Manager and share it read-only with the baseline
   `user` role. Only an administrator with role-sharing permission can do this. The owner retains
   editing control.
6. Install a host build with the broker registration and the updated Secret Manager plugin. Existing
   AI Gateway Chat Completions transport is sufficient.

The integration audit checked AI Gateway's `OpenAiChatFormat.buildPayload` in `WireFormat.kt`:
`model`, `max_tokens`, optional temperature, streaming with usage, tools, and complete message/tool
history all fit the allowlist. This is a constrained Chat Completions surface, not a promise to
support every OpenAI parameter. Unknown top-level fields and function/format/image envelopes are
rejected; JSON Schema contents remain opaque schema data. `reasoning` is descriptive metadata for
models that reason internally, not an exposed reasoning-effort control. Reasoning usage remains
included in the output count regardless of that metadata. Streaming always requests usage for
accounting; `stream_options` accepts only `include_usage=true` (or an empty object), never disabling
usage or adding vendor fields.

No migration publishes a made-up model, invents an API key, or picks an allowance. Those are
required deployment inputs. Plugin bundling is outside this change.

## Shared vault definition

Create a normal secret with website/name `BOSS AI`, username `BOSS sign-in`, password
`Managed by BOSS` (an inert placeholder, never used as a credential), tag `ai-provider-definition`,
and these notes:

```json
{
  "schema": "boss-managed-provider-v1",
  "name": "BOSS AI",
  "brokerId": "boss-ai",
  "baseUrl": "https://api.risaboss.com/functions/v1/boss-ai/v1",
  "defaultForNewUsers": true
}
```

The provider identity is derived from the secret's UUID, so renaming the shared entry does not lose
selections. Users' model choices are local preferences and never modify the shared definition. A
shared note can reference only a broker known to the host and endpoints within that broker's
declared scope. A forged share cannot send a login token or a broker credential to an arbitrary URL.

Share with `user` to distribute to everyone, or use existing user/role/organisation sharing for
narrower distribution. Availability of a definition and permission to spend on a model are separate:
inference always rechecks the model's live policy. The `ai.use` permission is granted to the
baseline user role by the migration. Additional allowance permissions use the existing RBAC
administration.

## Server configuration example

This is a template, not an automatically applied seed. Replace the upstream model and all limits
with the approved production values; verify capabilities against the actual endpoint before setting
`published=true`.

```sql
INSERT INTO public.boss_ai_connections
  (id, base_url, api_key_secret, api_type)
VALUES ('openrouter', 'https://openrouter.ai/api/v1',
        'BOSS_AI_OPENROUTER', 'openai_chat');

INSERT INTO public.boss_ai_models
  (id, display_name, connection_id, upstream_model, capabilities,
   context_length, max_output_tokens, published, is_default)
VALUES ('boss-general', 'BOSS General', 'openrouter', '<upstream-model-id>',
        ARRAY['text','tools'], 32768, 4096, false, true);

INSERT INTO public.boss_ai_allowances
  (model_id, permission_name, tokens_per_day, tokens_per_week, tokens_per_month)
VALUES ('boss-general', 'ai.use', 100000, 500000, 2000000);
```

Repeat the model and allowance inserts to publish multiple models. Connections may use `openai_chat`
or `openai_responses`; the base URL includes the API version prefix, not `/chat/completions` or
`/responses`. Modify mappings in a transaction; each admitted request keeps its captured routing
configuration. Unpublish to stop new requests. In-flight requests are allowed to finish.

The Responses adapter translates client function calls, results, structured output and SSE events
to/from Chat Completions. It requests `store=false` and disables Responses truncation. No
upstream-hosted tools, conversation IDs, client-selected providers, redirects, or arbitrary request
extensions are forwarded. Vision accepts inline PNG/JPEG/WebP images, not remote image URLs. The
endpoint must enforce the configured context limit; this is part of the upstream contract, not an
assertion inferred from a model name.

## Allowance semantics

Request validation uses a read-only, permission-filtered preflight before inserting any ledger row.
Admission rechecks current policy/configuration under its lock, and the handler revalidates against
the admitted snapshot. Normal malformed/capability-invalid requests create no ledger rows; a
configuration change between preflight and admission can still require a zero-charge settlement.
Admission requires READ COMMITTED so the post-lock usage recount sees earlier admissions. Other
transaction isolation levels are refused explicitly. An effective allowance smaller than the model's
context cannot admit even one request: preflight returns `503 misconfigured_allowance`, without a
ledger row. This is distinct from temporary quota exhaustion (`429`).

For each model, use the maximum allowance from matching permissions for each period, never the sum.
Every configured period applies. Days, ISO weeks starting Monday, and months reset at their UTC
calendar boundaries. Usage belongs to the request's admission period and is never reset by a
permission change.

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
