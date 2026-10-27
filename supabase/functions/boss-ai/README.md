# Managed BOSS AI

The function owns inference routing, authorization, provider metadata and per-user/model allowances.
Secret Manager automatically discovers BOSS AI using the existing generic authenticated Supabase RPC
API. No host-specific broker registration, shared vault definition, or upstream API key on the
desktop is needed.

## Deployment

1. Apply `20260912000000_boss_ai.sql`, `20260912001000_boss_ai_hardening.sql`,
   `20260912002000_boss_ai_validation.sql`, `20260912003000_boss_ai_allowance_preflight.sql`, and
   `20260912004000_boss_ai_exchange_tickets.sql` in order; `20261015000000_boss_ai_voice.sql` adds
   audio models. Apply `20261027120000_boss_ai_anthropic_fallbacks.sql` before configuring an
   `anthropic_messages` connection or any fallbacks (it replaces `boss_ai_lookup`/`boss_ai_reserve`
   with the previous bodies plus a `fallbacks` key). Apply
   `20261023090000_boss_ai_voice_admission.sql` before deploying the live-call handler update; it
   adds a service-only admission table/RPC without changing existing accounting functions or
   policies. Existing chat and STT/TTS clients keep working. Earlier files have already been applied
   to the preview branch; fixes are forward migrations, not edits to applied history.
2. Set `BOSS_AI_SIGNING_SECRET` to at least 32 random bytes (encoded as a string). Set upstream API
   keys as secrets named `BOSS_AI_<NAME>`. The signing-secret name is explicitly prohibited as an
   upstream key in both SQL and the handler. Never put these keys in a shared vault entry. Supabase
   supplies its URL and service-role key. Voice calls also need `LIVEKIT_URL`, `LIVEKIT_API_KEY` and
   `LIVEKIT_API_SECRET` (see Live calls); `BOSS_AI_ALLOW_LOCAL_AGENT=1` is for local testing only.
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
rejected; JSON Schema contents remain opaque schema data. `reasoning` is descriptive metadata for
models that reason internally, not an exposed reasoning-effort control. Reasoning usage remains
included in the output count regardless of that metadata. Streaming always requests usage for
accounting; `stream_options` accepts only `include_usage=true` (or an empty object), never disabling
usage or adding vendor fields.

No migration publishes a made-up model, invents an API key, or picks an allowance. Those are
required deployment inputs. Plugin bundling is outside this change.

## Plugin-owned authentication and discovery

1. Secret Manager calls `boss_ai_create_exchange_ticket()` through the existing authenticated RPC
   API. The database derives the user from `auth.uid()` and checks `ai.use`, bans and anonymous
   status. No caller-supplied user id is accepted.
2. The plugin POSTs the returned ticket to `/auth/exchange`. The edge function redeems it through
   the service-only `boss_ai_consume_exchange_ticket` RPC, then mints a five-minute AI token.
3. The plugin GETs `/v1/provider` with that AI token. The versioned metadata supplies the provider
   name, API base and new-user default recommendation. The plugin confines all endpoints to its
   trusted BOSS AI scope and refuses redirects.
4. `/v1/models` supplies permission-filtered models, capabilities, limits, defaults and allowances.
   Inference independently enforces live authorization and usage policy.

Tickets expire after 60 seconds, are stored only as SHA-256 hashes, and are consumed atomically.
Each user may hold at most eight pending tickets; issuance removes their expired tickets. The edge
function alone can redeem them. Ban and permission checks run again at redemption. No BOSS login
token reaches the plugin, and no extra signing secret is required by the database. Credentials and
account-specific catalogs stay in memory. Explicit provider/model preferences remain local and are
never overwritten by a provider default. The stable provider id is `managed:boss-ai`; optional
legacy shared definitions for this same provider are deduplicated.

`/auth/token` remains for compatibility with already shipped host brokers. The plugin does not use
it or require a host broker registration. The function source lives in this repository for backend
deployment; it is not desktop runtime code.

The `/v1/provider` response is:

```json
{
  "schema": "boss-managed-provider-v1",
  "name": "BOSS AI",
  "brokerId": "boss-ai",
  "baseUrl": "https://api.risaboss.com/functions/v1/boss-ai/v1",
  "defaultForNewUsers": true
}
```

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

Repeat the model and allowance inserts to publish multiple models. Connections may use
`openai_chat`, `openai_responses` or `anthropic_messages`; the base URL includes the API version
prefix, not `/chat/completions`, `/responses` or `/messages`. Modify mappings in a transaction; each
admitted request keeps its captured routing configuration. Unpublish to stop new requests. In-flight
requests are allowed to finish.

`boss_ai_models.provider_routing` (NULL by default) is an operator-set OpenRouter `provider` object
sent with every request for that model. Accepted keys: `order`, `only`, `ignore` (each a list of 1
to 32 provider slugs matching `^[a-z0-9][a-z0-9._/-]{0,63}$`), `allow_fallbacks`,
`require_parameters` (booleans) and `sort` (`price`, `throughput` or `latency`). The table check
(`boss_ai_provider_routing_valid()`) and the function enforce the same rules. Non-empty routing on a
connection whose base URL is not `https://openrouter.ai` is refused, so keep routed models on the
OpenRouter connection. Either fault fails as a 503 configuration error before dispatch.

```sql
UPDATE public.boss_ai_models SET provider_routing =
  '{"order":["cerebras","coreweave"],"allow_fallbacks":true,"ignore":["groq","amazon-bedrock"]}'
WHERE id = 'optimist';
```

**Prompt caching.** For an OpenRouter connection whose upstream is `anthropic/*` or `google/gemini*`
(the upstreams that cache only where the request marks it), the function marks two `cache_control`
breakpoints itself: the first system message, which caches the tools and system prompt, and the
newest user message. Other upstreams get the request unchanged. Usage passes the upstream's
`prompt_tokens_details` (`cached_tokens`, `cache_write_tokens`) and `completion_tokens_details`
(`reasoning_tokens`) through; `total_tokens` and accounting are unchanged.

The Responses adapter translates client function calls, results, structured output and SSE events
to/from Chat Completions. It requests `store=false` and disables Responses truncation. No
upstream-hosted tools, conversation IDs, client-selected providers, redirects, or arbitrary request
extensions are forwarded. Vision accepts inline PNG/JPEG/WebP images, not remote image URLs. The
endpoint must enforce the configured context limit; this is part of the upstream contract, not an
assertion inferred from a model name.

## Native Anthropic (`anthropic_messages`)

Clients still speak Chat Completions. For a connection with `api_type='anthropic_messages'` the
function posts to `${base_url}/messages` with `x-api-key` and `anthropic-version: 2023-06-01` (never
Bearer; audio routes stay Bearer) and translates both ways. Anthropic's OpenAI-compat endpoint
(`api_type='openai_chat'` on `https://api.anthropic.com/v1`) has no prompt caching; do not use it
for agentic traffic.

- Request: `system`/`developer` messages become top-level `system` text blocks; tools become
  `{name, description, input_schema}`; `tool_choice` `auto`/`none`/`required`/named maps to
  `auto`/`none`/`any`/`tool`, and `parallel_tool_calls:false` to `disable_parallel_tool_use`. Inline
  image data URLs become base64 image blocks. Assistant `tool_calls` become `tool_use` (arguments
  parsed; invalid JSON is a 400), `role:"tool"` becomes a user `tool_result` (ids are mapped to
  `[a-zA-Z0-9_-]` identically on both sides). Same-role neighbours merge so roles alternate, with
  `tool_result` blocks first in a user turn. `max_tokens` is always the computed maximum.
- Not forwarded: `temperature`/`top_p` (current Claude models reject non-default sampling; the
  request is still validated), `store`, `provider`, `stream_options`. `response_format`
  `json_schema` becomes `output_config.format` (`json_schema`; Anthropic requires
  `additionalProperties:false` on every object and rejects numeric/string constraints, which
  surfaces as an upstream 400); `json_object` is a 400 for this api_type; `text` is a no-op.
- Thinking is never requested. OpenAI-format clients cannot round-trip thinking blocks and
  signatures, so response thinking blocks are dropped and history is replayed without them. Models
  that always think (Opus 5.5, Sonnet 5.5: `thinking:{type:"disabled"}` is a 400) still run adaptive
  thinking server-side when the parameter is omitted, and bill for it in `output_tokens`. Forced
  `tool_choice` (`required`, named) is a 400 on those same models; with fallbacks configured the
  request is served by the next route.
- Response: text and `tool_use` become a `chat.completion`; `end_turn`/`stop_sequence`/`pause_turn`
  map to `stop`, `max_tokens` to `length`, `tool_use` to `tool_calls`, `refusal` to
  `content_filter`. Streams translate the Anthropic SSE events to `chat.completion.chunk`s
  (`input_json_delta` to `function.arguments`, index and id stable per tool block), then a usage
  chunk and `[DONE]`. Settlement uses the measured total.
- Usage: `prompt_tokens = input + cache_read + cache_creation`, `completion_tokens = output`,
  `total_tokens` their sum,
  `prompt_tokens_details {cached_tokens: cache_read, cache_write_tokens:
  cache_creation}`.
  Accounting is unchanged in meaning: every processed token is charged, cached or not.

**Caching is always on for this api_type.** `cache_control: ephemeral` (5 minute TTL) is set on the
last tool, the last system block, the last content block of the final message and the last content
block of the message before the most recent user turn: at most 4 breakpoints, coinciding targets
collapse, blocks that cannot carry the mark are skipped. The last one is the previous request's
write point, which keeps reads inside Anthropic's 20-block lookback during long tool loops. Prefixes
under the model's minimum cacheable length (512 to 4096 tokens by model) silently do not cache. The
OpenRouter `anthropic/*` marking above is unchanged.

`cache_miss_large_prompt` is audited when a native or OpenRouter `anthropic/*` response reports more
than 8000 prompt tokens with zero cache reads and zero cache writes, so a silent caching regression
shows up in the logs.

## Fallbacks

`boss_ai_models.fallbacks` (`jsonb NOT NULL DEFAULT '[]'`) is an ordered array of at most 4
`{"connection_id", "upstream_model", "provider_routing"?}`; unknown keys, a missing connection or an
invalid `provider_routing` are refused by the table checks and triggers (a connection used by a
fallback cannot be deleted or renamed). `boss_ai_lookup`/`boss_ai_reserve` return the resolved list
as `fallbacks`, each entry with its connection row; disabled connections are dropped. A fallback
route uses its own connection's `api_type`, its own `upstream_model` and its own `provider_routing`
(the model's primary routing never applies to it).

- The primary is tried first, then each fallback in order, on a network error, an upstream timeout
  before any response (streaming requests only: non-final routes get `attemptTimeoutMs`, 90 s, to
  answer, since a non-stream upstream answers only after generating; the 4 minute request deadline
  still applies) and any non-2xx status. A client cancel or the overall deadline ends the request. A
  route whose request cannot be built (for example routing on a non-OpenRouter connection) is
  skipped.
- Nothing falls back once the upstream has answered 2xx: a stream that breaks afterwards ends with
  the usual `stream_failed` frame. The request keeps one reservation and settles with the measured
  tokens of the route that served. A failed attempt keeps the existing refund rule (400/401/402/403/
  404/405/406/413/415/422/429 prove no work, anything else may have run); a later refundable status
  never refunds an earlier attempt that may have run.
- Per-isolate circuit breaker: a route (`connection_id|upstream_model`) that failed with
  401/402/403/429/5xx, or with a 400 whose message names a usage/spend/credit/quota/balance limit,
  is skipped for 60 s. When every route is cooling all of them are tried. Other 400s do not trip it.
- Every failed attempt logs one line,
  `{event:"upstream_error", request_id, route, status, type,
  message}`: at most 4 KB of the
  upstream body is read, only `error.type` (or `error.code`) and `error.message` (300 characters)
  are kept; network failures log `type` `timeout`/`fetch_error` with no text. Request content and
  keys are never logged. `upstream_fallback` is audited when a fallback served. The user-facing
  failure message is unchanged.

Operator data (applied by hand, not a migration). After
`20261027120000_boss_ai_anthropic_fallbacks.sql`:

```sql
update boss_ai_connections set api_type='anthropic_messages' where id='anthropic';
update boss_ai_models set connection_id='anthropic', upstream_model='claude-opus-5-5', provider_routing=null, fallbacks='[{"connection_id":"openrouter-optimist","upstream_model":"anthropic/claude-opus-5.5","provider_routing":{"order":["anthropic","amazon-bedrock","google-vertex"],"allow_fallbacks":true}},{"connection_id":"openrouter-optimist","upstream_model":"anthropic/claude-haiku-5.5"}]' where id in ('optimist','optimist-strong');
```

Set `provider_routing` to NULL on a model moved to the Anthropic connection: routing on a
non-OpenRouter connection fails the request as a 503 configuration error.

## Voice (audio models)

`boss_ai_models.kind` is `chat` (default), `transcription` or `speech`. Audio routes use the same
bearer, admission, ledger and error shape as chat, and take JSON bodies. The upstream URL is the
connection `base_url` plus `/audio/transcriptions` or `/audio/speech`, whatever its `api_type`.
`/v1/chat/completions` and each audio route reject a model of another kind with 400
`wrong_model_kind` (after the permission lookup, before any reservation). `/v1/models` lists `kind`
per model. `provider_routing` is not forwarded on audio requests (OpenRouter's audio endpoints are
not verified to accept it); it still applies to chat.

`POST /v1/audio/transcriptions`, at most 4 MiB:

```json
{ "model": "max-stt", "input_audio": { "data": "<base64>", "format": "wav" }, "language": "en" }
```

`format` is `wav`, `mp3`, `webm`, `ogg`, `flac` or `m4a`; `language` is optional. The audio is
validated for base64 shape and forwarded undecoded as
`{model: upstream_model, input_audio, language?}`. Response:
`{"text", "usage": {"seconds",
"billed_seconds", "remaining_seconds"?, "resets_at"?}}`.

`POST /v1/audio/speech`:

```json
{ "model": "max-tts", "input": "One sentence or a few.", "voice": "optional override" }
```

`input` is 1 to 2000 characters and at most `max_output_tokens` x 14 characters (400 `invalid_input`
otherwise; checked before and after admission, since the model row may change between). The proxy
accepts only `speed: 1` and `stream_format: "audio"` when supplied (unsupported variants return
400). It sends `response_format: "mp3"` and the voice, taken from the request, else the model row's
`voice`, else omitted. The upstream body is streamed through as `audio/mpeg` without buffering and
is cut off past 16 MiB. Headers: `X-Request-ID`, `X-Billed-Seconds`, and `X-Remaining-Seconds` and
`X-Resets-At` (both omitted unless `resets_at` is an ISO timestamp).

After settling, both routes read the pool with `boss_ai_usage` and report the period with the
smallest remaining (`remaining_seconds`, `resets_at`). If that read fails or times out the fields
are omitted; the request never fails because of it.

**Metering is seconds**; the `tokens_*` columns hold seconds for audio models, and ledger rows keep
the actual `model_id`. Admission reserves `max_output_tokens` (the most seconds one request may
bill) instead of `context_length`; `boss_ai_lookup`'s `misconfigured_allowance` check and the
remaining-allowance check use the same amount. Settlement:

- Transcription: `ceil(upstream usage.seconds)`, ignoring a negative, non-finite or non-numeric
  value; if missing, estimated from the WAV (RIFF chunks parsed, PCM only, byte rate consistent with
  the format and within 2,000 to 1,536,000 bytes/s, duration from the bytes actually present);
  otherwise the reservation is kept (`usage_unknown_reservation_retained`). `billed_seconds` reports
  the ledger charge, capped at the reservation. `usage.seconds` preserves the measured duration when
  it exceeds that cap (`usage_exceeds_reservation`).
- Speech: `max(1, ceil(chars / 14))`, settled once upstream returns 200, before the stream starts.
  Input is bounded so this never exceeds the reservation.
- Upstream rejection that proves no work (400/401/402/403/404/405/406/413/415/422/429) settles 0;
  other failures keep the reservation, as for chat.

**Shared pool.** `allowance_model_id` makes a model draw from another model's allowance and usage;
the pool key is `coalesce(allowance_model_id, id)`. Allowances, the policy, usage sums, the
concurrency limit and the admission lock are all per user and pool, so speech and transcription
share one budget. Pools are one level deep (a trigger refuses self, chains and re-pooling a target).
Publication, enablement and connection checks still apply to the requested model itself.
`allowance_exceeded` (429) includes the pool's `usage`:

```json
{
  "error": {
    "code": "allowance_exceeded",
    "message": "...",
    "usage": {
      "month": { "limit": 3600, "used": 3600, "remaining": 0, "resets_at": "2026-11-01T00:00:00Z" }
    }
  }
}
```

Operator data (applied by hand, not a migration), for a shared hour of talk time:

```sql
INSERT INTO boss_ai_models (id, display_name, connection_id, upstream_model, kind, voice, context_length, max_output_tokens, published, enabled)
VALUES ('max-stt','Max STT','openrouter-optimist','nvidia/parakeet-tdt-0.6b-v3','transcription',NULL,1024,60,true,true),
       ('max-tts','Max TTS','openrouter-optimist','elevenlabs/eleven-flash-v2.5','speech','sarah',1024,20,true,true);
UPDATE boss_ai_models SET allowance_model_id='max-stt' WHERE id='max-tts';
INSERT INTO boss_ai_allowances (model_id, permission_name, tokens_per_day, tokens_per_week, tokens_per_month, max_concurrent)
VALUES ('max-stt','ai.use',3600,3600,3600,6);
```

### Live calls (`POST /v1/voice/live`)

Starts a LiveKit Cloud room for a Fluck voice call and dispatches the `max-voice-agent` agent worker
into it. The caller sends an inference-scoped bearer:

```json
{
  "agent_url": "https://<fluck origin>/api/voice/agent",
  "agent_token": "<32..256 chars>",
  "max_seconds": 3600
}
```

- `agent_url`: HTTPS, at most 2048 characters, no credentials, nonstandard port, query or fragment.
  Desktop Cloudflare quick tunnels (`https://<single-label>.trycloudflare.com`) are supported;
  Cloudflare owns their DNS/TLS and routes them to the sharing agent. Other callback origins must be
  explicitly trusted via `BOSS_AI_AGENT_ORIGINS`, a comma-separated list of exact HTTPS origins (no
  path). Before rollout, configure every hosted agent origin, including new `*.bossconsole.ai` hosts
  individually; no wildcard or arbitrary custom DNS name is implicitly trusted. The companion
  `voice-agent/agent.py` uses HTTPX without redirects; retain that restriction in worker updates.
  `http://127.0.0.1` and `http://localhost` are accepted only when `BOSS_AI_ALLOW_LOCAL_AGENT=1`
  (local testing).
- `agent_token`: opaque per-call secret, 32 to 256 printable characters. It is only relayed to the
  worker in the dispatch metadata.
- `max_seconds`: 60 to 3600, default 3600 (a call ends at one hour or when the caller hangs up).
  Other fields are rejected (400).

The caller needs a policy for `max-stt` (the voice pool; 403 otherwise) with at least
`max(60, max-stt.max_output_tokens)` seconds left in its smallest period, else 429
`allowance_exceeded` with the pool `usage`. Nothing is reserved here: talk time is metered by the
worker's `max-tts` speech calls and its `/v1/voice/usage` reports (below).

A service-only atomic database claim limits each user to one start attempt per 30 seconds across
edge instances, including failed attempts. An existing room blocks additional calls. LiveKit RPCs
have a five-second timeout with regional replay disabled. Failed create/dispatch attempts delete the
room; the participant token is signed before either operation.

The proxy creates room `fluck-call-<user UUID>` (`emptyTimeout` 60, `departureTimeout` 120,
`maxParticipants` 2), dispatches agent `max-voice-agent` with JSON metadata
`{agent_url, agent_token, inference: {base_url, token}, stt_model: "max-stt", tts_model: "max-tts",
max_seconds, user}`,
and mints a participant token (identity `user-<first 8 of user id>`, name "You",
join/publish/subscribe/publish-data for that room only, TTL `max_seconds + 300`).
`inference.base_url` is `BOSS_AI_PUBLIC_URL` + `/v1`, otherwise `SUPABASE_URL` +
`/functions/v1/boss-ai/v1`. Both public configuration values require HTTPS. The worker enforces
`max_seconds` with its call timer; LiveKit room timeouts only handle empty rooms and departures. The
SQL migration and handler can precede a worker update; the metadata contract is unchanged. Response:

```json
{
  "server_url": "wss://...",
  "participant_token": "...",
  "room": "fluck-call-...",
  "expires_at": "<iso>",
  "quota": { "remaining_seconds": 3500, "resets_at": "<iso>" }
}
```

Secrets: `LIVEKIT_URL` (wss), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`. If any is missing the route
returns 503 `voice_unavailable`. A LiveKit failure is a generic 503; no detail is returned. The
worker calls back on `BOSS_AI_PUBLIC_URL` (e.g. `https://api.risaboss.com/functions/v1/boss-ai`),
else `SUPABASE_URL` + `/functions/v1/boss-ai`.

The call's conversational model is `max-voice`, a fast chat model (operator data, not a migration):

```sql
INSERT INTO boss_ai_models (id, display_name, connection_id, upstream_model, capabilities, context_length, max_output_tokens, published, enabled, kind)
VALUES ('max-voice','Max Voice','openrouter-optimist','google/gemini-3.5-flash-lite',ARRAY['text','tools','vision'],32768,1024,true,true,'chat');
INSERT INTO boss_ai_allowances (model_id, permission_name, tokens_per_day, tokens_per_week, tokens_per_month, max_concurrent)
VALUES ('max-voice','ai.use',2000000,6000000,20000000,6);
```

`vision` is needed: a voice turn's tool results can carry an image.

### Call speech usage (`POST /v1/voice/usage`)

The worker transcribes on LiveKit Cloud Inference, so the caller's speech never passes through
`/v1/audio/transcriptions`. It reports it instead, about every 30 s and at the end of the call:
`{"seconds": 12.4}` (a positive number no greater than 60; other fields and larger reports are 400).
The proxy rounds up, reserves on `max-stt` with `boss_ai_reserve` and settles that many seconds with
`boss_ai_settle`, then answers `{billed_seconds, remaining_seconds, resets_at}`. Settlement retries
once; if both attempts fail, the response has `billed_seconds: null` and
`billing_status: "reservation_retained"` because the full reservation remains charged. No policy is
403; an exhausted pool is 429 `allowance_exceeded` with `usage`, and the worker then ends the call.

**Token scopes.** `inference` tokens (300 s max age) are accepted by ordinary inference and
live-call start routes, but not `/v1/voice/usage`. The call-scoped `voice` token placed in the
dispatch metadata uses the same signer, has `sub` = user and `exp = now + max_seconds + 300` (max
age `exp - iat`, capped at 3900 s), and is accepted only by `/v1/audio/transcriptions` for `max-stt`
and `/v1/audio/speech` for `max-tts`; `/v1/voice/usage` takes `voice` only. Future-issued tokens are
rejected. `/v1/chat/completions`, `/v1/models`, `/v1/usage`, `/v1/provider` and `/v1/voice/live`
take `inference` only (401 otherwise).

## Restricting a model to an organisation

An allowance names a permission, so a model can be limited to one group without code. Give the
group's organisation user-kind role a dedicated permission (for example `ai.optimist`) and put the
model's only allowance on it. Members see and use the model; everyone else gets neither the catalog
entry nor admission. Platform admins hold every permission and see it too. Organisation admins
cannot grant `ai.*` through `grant_organisation_role_permission`; only a platform admin or an
operator write can, so holders cannot pass a model on to another organisation.

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
upstream reporting more than the configured context emits `usage_exceeds_configured_context`
(`usage_exceeds_reservation` for audio models), and the charge is capped at the admitted
reservation. Settlement retries and late stream truncation after a usage frame preserve the measured
count rather than treating it as unknown. Investigate either diagnostic before continuing to publish
the connection. Settlement is attempted twice; if both attempts fail, a successful completion is
still returned and the full reservation remains charged. Each diagnostic event is emitted at most
once per request, including across retries.

Each settlement attempt has a two-second deadline, including error and streaming cleanup paths.
Timeouts retry safely because `boss_ai_settle` is first-writer-wins for a request ID. Two failed
attempts emit `settlement_failed_reservation_retained`; operators must reconcile the request ledger
by that ID. There is no automatic refund or background reconciler in this change.

Tool envelopes allow at most 128 tools and 128 replayed calls per message. Per-tool descriptions are
limited to 4,096 characters; parameter and structured-output schemas to 65,536 serialized
characters, depth 32 and 8,192 visited values. The overall 4 MiB body cap is the aggregate limit.
Valid signed-in sessions without AI eligibility receive 403, not a request to sign in again;
eligibility RPC failures return 503 without minting a token.

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
`X-Request-ID`. Fallbacks are opt-in per model (see Fallbacks). Routing secrets and configuration
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
