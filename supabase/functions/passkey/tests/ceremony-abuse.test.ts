/**
 * Route-level abuse tests for the passkey ceremony endpoints.
 *
 * /auth/challenge has its own suite (auth-challenge-route.test.ts,
 * BossConsole#768 follow-ups). These cover the loops that stayed open
 * around it:
 *
 * - /register/challenge relays the caller's bearer token to the Auth API
 *   (auth.getUser) before anything else, so a garbage-bearer script turns
 *   the route into a free auth-service prober: the limiter must trip BEFORE
 *   that relay, spending nothing on the over-budget probe.
 * - /auth/complete and /register/complete verify an ES256 signature BEFORE
 *   the challenge row is consumed, so one captured challenge can replay into
 *   unlimited verification CPU unless the replay loop is braked.
 * - /manage/* is authenticated but was unbounded: one bearer relays into
 *   unlimited service-role lookups.
 * - /maintenance/cleanup issues a full-table DELETE on a verify_jwt=false
 *   deployment and has no legitimate caller in the tree: it must demand the
 *   service-role key and fail closed when the deployment has none.
 */

import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import auth from "../routes/auth.ts"
import register from "../routes/register.ts"
import management from "../routes/management.ts"
import maintenance from "../routes/maintenance.ts"
import { createMockSupabaseClient, type MockSupabaseClient } from "./helpers/mocks.ts"
import { resetRateLimiter } from "../utils/rate-limit.ts"

// Mirrors the route budgets (routes/register.ts, routes/auth.ts,
// routes/management.ts). Distinct x-forwarded-for per test keeps the
// per-client buckets from leaking between tests.
const REGISTER_CHALLENGE_LIMIT = 60
const REGISTER_COMPLETE_LIMIT = 120
const AUTH_COMPLETE_LIMIT = 120
const MANAGE_LIMIT = 240

function buildApp(mockClient: MockSupabaseClient) {
  const app = new OpenAPIHono<{ Variables: PasskeyContext }>()
  app.use("*", async (ctx, next) => {
    // deno-lint-ignore no-explicit-any
    ctx.set("supabase", mockClient as any)
    await next()
  })
  app.route("/auth", auth)
  app.route("/register", register)
  app.route("/manage", management)
  app.route("/maintenance", maintenance)
  return app
}

function postJson(
  app: ReturnType<typeof buildApp>,
  path: string,
  headers: Record<string, string>,
  body: unknown
) {
  return app.request(path, {
    method: 'POST',
    headers,
    body: JSON.stringify(body)
  })
}

// Schema-valid bodies whose clientDataJSON cannot be decoded: they reach the
// handler (and so the limiter) and are answered 400 before any service call.
// Copied from routes.test.ts so the two suites stay in sync.
const undecodableAuthCompleteBody = {
  challenge: 'some-challenge',
  credential: {
    id: 'cred',
    rawId: 'cred',
    type: 'public-key',
    response: {
      clientDataJSON: 'not!!!valid!!!base64!!',
      authenticatorData: 'dGVzdA',
      signature: 'dGVzdA'
    }
  }
}

const undecodableRegisterCompleteBody = {
  userId: 'user-1',
  challenge: 'some-challenge',
  credential: {
    id: 'cred',
    rawId: 'cred',
    type: 'public-key',
    response: {
      clientDataJSON: 'not!!!valid!!!base64!!',
      attestationObject: 'dGVzdA'
    }
  }
}

Deno.test("register/challenge - the limiter trips before the auth relay: the 61st probe is a 429 with nothing spent on it", async () => {
  resetRateLimiter()
  const mockClient = createMockSupabaseClient()
  // A token the mock CAN resolve, so the over-budget probe is provably
  // refused by the limiter rather than by the auth relay it precedes.
  mockClient.mockAccessToken('valid-session-token', { id: 'user-789' })
  const app = buildApp(mockClient)

  const headers = {
    'content-type': 'application/json',
    // A bearer the Auth API cannot resolve: every request relays auth.getUser
    // and is rejected with 401 - exactly the loop being braked.
    'authorization': 'Bearer garbage-bearer-token',
    'x-forwarded-for': '10.77.0.1',
  }

  for (let i = 0; i < REGISTER_CHALLENGE_LIMIT; i++) {
    const res = await postJson(app, '/register/challenge', headers, {})
    assertEquals(res.status, 401, `probe ${i + 1} within budget must be answered (401), not braked`)
  }

  const lookupsBefore = mockClient.getQueryHistory().length

  // The very next probe from the same client carries a FULLY VALID session:
  // if the limiter ran after the auth relay, this would mint a challenge
  // (an insert in the query history) instead of a 429.
  const res = await postJson(app, '/register/challenge', {
    ...headers,
    'authorization': 'Bearer valid-session-token',
  }, {})
  assertEquals(res.status, 429, "the 61st probe in the window must be rate-limited even with a valid session")
  assertEquals(
    mockClient.getQueryHistory().length,
    lookupsBefore,
    "the rate-limited probe must not spend a challenge insert or any other lookup",
  )
  resetRateLimiter()
})

Deno.test("register/complete - replay spam is braked: the 121st replay in the window is a 429", async () => {
  resetRateLimiter()
  const app = buildApp(createMockSupabaseClient())
  const headers = {
    'content-type': 'application/json',
    'x-forwarded-for': '10.77.0.2',
  }

  for (let i = 0; i < REGISTER_COMPLETE_LIMIT; i++) {
    const res = await postJson(app, '/register/complete', headers, undecodableRegisterCompleteBody)
    assertEquals(res.status, 400, `replay ${i + 1} within budget must be answered (400), not braked`)
  }

  const res = await postJson(app, '/register/complete', headers, undecodableRegisterCompleteBody)
  assertEquals(res.status, 429, "the 121st replay in the window must be rate-limited")
  resetRateLimiter()
})

Deno.test("auth/complete - replay spam is braked: the 121st replay in the window is a 429", async () => {
  resetRateLimiter()
  const app = buildApp(createMockSupabaseClient())
  const headers = {
    'content-type': 'application/json',
    'x-forwarded-for': '10.77.0.3',
  }

  for (let i = 0; i < AUTH_COMPLETE_LIMIT; i++) {
    const res = await postJson(app, '/auth/complete', headers, undecodableAuthCompleteBody)
    assertEquals(res.status, 400, `replay ${i + 1} within budget must be answered (400), not braked`)
  }

  const res = await postJson(app, '/auth/complete', headers, undecodableAuthCompleteBody)
  assertEquals(res.status, 429, "the 121st replay in the window must be rate-limited")
  resetRateLimiter()
})

Deno.test("manage/list - unauthenticated spam is braked: the 241st request in the window is a 429", async () => {
  resetRateLimiter()
  const app = buildApp(createMockSupabaseClient())
  const headers = {
    'content-type': 'application/json',
    'authorization': 'Bearer garbage-bearer-token',
    'x-forwarded-for': '10.77.0.4',
  }

  for (let i = 0; i < MANAGE_LIMIT; i++) {
    const res = await postJson(app, '/manage/list', headers, { userId: 'user-1' })
    assertEquals(res.status, 401, `request ${i + 1} within budget must be answered (401), not braked`)
  }

  const res = await postJson(app, '/manage/list', headers, { userId: 'user-1' })
  assertEquals(res.status, 429, "the 241st request in the window must be rate-limited")
  resetRateLimiter()
})

Deno.test("maintenance/cleanup - refuses every caller but the service role, and never spends the DELETE on a refusal", async () => {
  resetRateLimiter()
  const mockClient = createMockSupabaseClient()
  const app = buildApp(mockClient)
  Deno.env.set('SUPABASE_SERVICE_ROLE_KEY', 'test-service-role-key')
  try {
    const base = { 'content-type': 'application/json' }

    // No Authorization header at all: until this change the public anon key
    // (or nothing at all) was enough to drive the full-table DELETE.
    const noHeader = await postJson(app, '/maintenance/cleanup', base, {})
    assertEquals(noHeader.status, 401, "a caller with no credential must be refused")

    // A bearer that is not this deployment's service key.
    const wrongKey = await postJson(app, '/maintenance/cleanup', {
      ...base,
      'authorization': 'Bearer not-the-service-key',
    }, {})
    assertEquals(wrongKey.status, 401, "a caller with the wrong key must be refused")

    assertEquals(
      mockClient.getQueryHistory().length,
      0,
      "refused callers must not reach the full-table DELETE",
    )

    // The scheduled-job caller is served and the DELETE actually runs.
    mockClient.mockResponse('passkey_challenges', { data: null, error: null }, 'delete')
    const ok = await postJson(app, '/maintenance/cleanup', {
      ...base,
      'authorization': 'Bearer test-service-role-key',
    }, {})
    assertEquals(ok.status, 200, "the service-role caller must be served")
    assertEquals((await ok.json()).message, 'Cleanup completed successfully')
    assertEquals(
      mockClient.getQueryHistory().some(
        query => query.table === 'passkey_challenges' && query.operation === 'delete'
      ),
      true,
      "the accepted cleanup must have issued the DELETE scan",
    )
  } finally {
    Deno.env.delete('SUPABASE_SERVICE_ROLE_KEY')
    resetRateLimiter()
  }
})

Deno.test("maintenance/cleanup - fails closed when the deployment has no service key configured", async () => {
  resetRateLimiter()
  const mockClient = createMockSupabaseClient()
  const app = buildApp(mockClient)
  const hadKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
  Deno.env.delete('SUPABASE_SERVICE_ROLE_KEY')
  try {
    const res = await postJson(app, '/maintenance/cleanup', {
      'content-type': 'application/json',
      'authorization': 'Bearer test-service-role-key',
    }, {})
    assertEquals(
      res.status,
      401,
      "with no service key configured every caller must be refused - never fail-open",
    )
    assertEquals(mockClient.getQueryHistory().length, 0, "the refused caller must not reach the DELETE scan")
  } finally {
    if (hadKey !== undefined) Deno.env.set('SUPABASE_SERVICE_ROLE_KEY', hadKey)
    else Deno.env.delete('SUPABASE_SERVICE_ROLE_KEY')
    resetRateLimiter()
  }
})
