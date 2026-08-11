# The LLM gateway contract

The gateway is not in this repository. It lives in **`risa-labs-inc/risa-llm-gateway`** (private).
This file records what the code here depends on, because after the move nothing links the two
repositories at compile time or in CI, so drift is silent in both directions.

Client side, all in `composeApp/src/desktopMain/kotlin/ai/rever/boss/llm/`:
`CredentialBrokers` (the id to endpoint map), `RisaLlmTokenCommand` (the `BOSS llm-token` verb Codex
invokes), `BrokeredCredentialProviderImpl` (what plugins reach through `PluginContext`).

## What this repo sends

`POST https://llm.risa.inc/auth/token`, with the signed-in user's Supabase access token as
`Authorization: Bearer <session>`. `RISA_LLM_TOKEN_URL` overrides the host, and is a **dev-only**
override for pointing a local build at a staging gateway - it does not weaken the id-not-URL rule,
because it is host-side and no plugin can set it.

## What this repo expects back

| Field | Used for |
|---|---|
| `access_token` | The downstream credential. Must be non-blank or the exchange is treated as failed. |
| `refresh_after_seconds` | When to mint again. `0` when absent. |
| `expires_at` | The credential's own expiry. Secret Manager caps its reuse window at this, because a refresh interval longer than the key's life is how a dead token got served for a whole window. |

`scopedTo` is published to plugins as `https://llm.risa.inc/v1` so a careful plugin can check where a
bearer token is about to be posted. The gateway does not send it; this repo declares it.

## Errors

The gateway owns its error messages and maps upstream status to its own wording; the raw upstream
body is not meant to reach a client. `401` and `403` mean the credential is bad or not permitted -
both are **renewable**, not misconfiguration, because a brokered key is short-lived by design.

## Who changes what

- A new response field, a new route, or a change of error mapping: the gateway repo.
- A change to how the session is exchanged or how the credential is cached: here.
- Either one alone can break the pair, and no test in either repo will notice. A change to the table
  above should land in both.

## Release notes

Gateway changes do not belong in BOSS release notes. They ship on the gateway's own deploy, not with
a BOSS version, and describing them as shipped in a BOSS release overstates what a user gets: BOSS
`v9.4.5` credits the error-mapping fix that stops upstream text reaching clients, which is merged in
the gateway repo and, at the time of that note, was not deployed.
