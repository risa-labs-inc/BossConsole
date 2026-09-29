# PR #1684 Supabase validation and rollout

## Scope

Reviewed the Supabase delta from main to dev at `d0c275777`: the Fluck OAuth
callback and RPCs, BOSS AI eligibility/accounting/wire validation, passkey log
masking, and the RLS migration comment change. This is a release-scoped review,
not a claim that all historical backend code is defect-free.

The downloaded production function sources match the expected pre-release
versions. Production migration history has the earlier RLS migrations; only the
Fluck OAuth and BOSS AI eligibility release migrations were pending.

## Additional fixes

- Add a forward nonce migration tolerating up to one minute of database/edge
  clock skew. Keep spent nonces for that extra minute too, preventing cleanup
  from permitting replay while the edge still accepts the signed state.
- Invalid nonce/expiry input raises SQLSTATE 22023, distinct from a replay.
  The callback renders an invalid-link message, never exchanges the code, and
  unexpected RPC results fail closed.
- Escape quote delimiters in OAuth HTML output as well as markup delimiters.

## Validation

- Nine Edge Function suites: 794 tests passed, including 39 Fluck OAuth tests.
- Changed functions type-check; Fluck OAuth and BOSS AI formatting checks pass.
- All migrations applied to an isolated disposable database, not the existing
  local development database. All 51 pgTAP suites passed: 1,149 assertions.
- Independent-session tests passed for AI allowance admission, ticket
  single-spend/rollback/issuance limits, and domain claim quotas.
- Sixteen concurrent OAuth nonce claims produced exactly one winner.
- Production baseline: passkey health 200; unauthenticated BOSS AI 401;
  the existing OAuth health endpoint 200.

## Deployment gate

Target is the Boss production project `pcnwqamqdnsadranufjv`.
Apply database migrations before changing the functions. Do not deploy seeds,
roles, unrelated functions, or configuration wholesale.

Production lacks `FLUCK_USER_ID`, required by the hardened callback. An operator
must identify the BOSS account bound to the existing `FLUCK_STATE_KEY` before
deploying `fluck-oauth`; guessing this binding could authorize the wrong user or
disable sign-in. No key rotation is required or authorized by this rollout.
Passkey and BOSS AI can deploy independently after their migrations pass.

Health and refusal probes do not substitute for a real browser Google consent
flow or an interactive passkey login. Those require the account holder.

## Production result (2026-09-25)

- Applied `20260924230000`, `20260924231000`, and `20260925080000`.
  A subsequent dry run reports no pending migrations. No seeds or roles applied.
- Verified all three new/hardened RPCs allow `service_role` execution and deny
  both `anon` and `authenticated` execution.
- Deployed BOSS AI v43 and passkey v130; both report ACTIVE with their intended
  internal authentication (`verify_jwt=false`).
- Post-deployment checks: passkey health 200; unauthenticated AI model listing
  and token minting both 401; existing OAuth health 200 and invalid callback 400.
- Fluck OAuth initially remained on v7 pending the account binding. The operator
  subsequently confirmed the sole owner of the existing `fluck-state-key` record.
  Set `FLUCK_USER_ID` to that confirmed account without reading or rotating the
  signing key, then deployed Fluck OAuth v9 (ACTIVE, `verify_jwt=false`).
- Post-deployment OAuth health returns 200 with all four required configuration
  flags true; an invalid callback returns 400 and a POST callback returns 405.
  A real Google consent flow remains an account-holder smoke test. Backend
  deployment is not approval of the separate desktop release blockers.
