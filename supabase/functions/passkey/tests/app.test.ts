/**
 * Entry-point regressions: server diagnostics must never reach a response body.
 *
 * The #567 review found the two 500 paths in the entrypoint file echoing raw
 * error messages (an unauthenticated maintenance endpoint and the global error
 * handler), while the logs were the redacted half of the same values. These
 * tests import the real entrypoint app (../app.ts) - the one index.ts serves -
 * and drive it through app.request(), so the handlers under test are the
 * production handlers rather than a test-built twin (tests/routes.test.ts
 * builds its own app and cannot see index-level handlers).
 */

import { assert, assertEquals } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { encodeBase64Url } from "@std/encoding/base64url"
import { app } from "../app.ts"
import { setPasskeyClientForTests, resetPasskeyClientForTests } from "../utils/client.ts"
import { generateAuthChallenge, completeAuthentication } from "../services/auth.ts"
import { generateRegistrationChallenge } from "../services/registration.ts"
import { createAssertion, TEST_RP_ID, TEST_ORIGIN } from "./helpers/webauthn.ts"
import { createMockSupabaseClient, mockPasskey } from "./helpers/mocks.ts"

const CLEANUP_CANARY = 'pg-canary: relation "passkey_challenges" does not exist'
const CHALLENGE_CANARY = "pg-canary: duplicate key value violates unique constraint"
const COMPLETE_CANARY = 'pg-canary: completed_authentications violates check constraint'

// Pin the relying party so the real-crypto assertion verifies (matches e2e.test.ts)
Deno.env.set('PASSKEY_RP_ID', TEST_RP_ID)

function captureLogs(action: () => Promise<void>): Promise<string> {
  const output: unknown[][] = []
  const originals = { log: console.log, error: console.error, warn: console.warn }
  const record = (...args: unknown[]) => output.push(args)
  console.log = record
  console.error = record
  console.warn = record
  return Promise.resolve(action()).finally(() => Object.assign(console, originals)).then(() => JSON.stringify(output))
}

Deno.test("maintenance cleanup failure returns a generic 500 to unauthenticated callers", async () => {
  const client = createMockSupabaseClient()
  client.mockResponse('passkey_challenges', {
    data: null,
    error: { code: '42P01', message: CLEANUP_CANARY }
  }, 'delete')
  setPasskeyClientForTests(client as unknown as SupabaseClient)
  try {
    const response = await app.request('/passkey/maintenance/cleanup', { method: 'POST' })
    assertEquals(response.status, 500)
    const body = await response.text()
    assert(!body.includes(CLEANUP_CANARY), `cleanup 500 leaked the database message: ${body}`)
    assert(body.includes('Internal server error'))
  } finally {
    setPasskeyClientForTests(null)
  }
})

// The global handler runs when passkeyClient() creates the client for the first
// time. That creation reads SUPABASE_URL/SUPABASE_SERVICE_ROLE_KEY from the
// environment, so the test throws from Deno.env.get to surface a diagnostic in
// the production code path.
//
// Ordering dependency: this relies on `productionClient` in utils/client.ts
// still being null when the test runs. deno test shares module instances across
// files in one process, so a test that drives app.request() without setting a
// stub first would cache a client and this test would break. resetPasskeyClientForTests
// below clears that cache so the first-creation path is exercised regardless of
// order.
Deno.test("global error handler returns a generic 500 and keeps the diagnostic in logs only", async () => {
  const diagnostic = "gotrue-canary: identity provider refused the session mint"
  const originalEnvGet = Deno.env.get
  Deno.env.get = (key: string) => {
    if (key === 'SUPABASE_URL') throw new Error(diagnostic)
    return originalEnvGet(key)
  }
  resetPasskeyClientForTests()
  const output = await captureLogs(async () => {
    try {
      const response = await app.request('/passkey/health', { method: 'GET' })
      assertEquals(response.status, 500)
      const body = await response.text()
      assert(!body.includes(diagnostic), `global 500 leaked the thrown diagnostic: ${body}`)
      assert(body.includes('Internal server error'))
    } finally {
      Deno.env.get = originalEnvGet
      resetPasskeyClientForTests()
    }
  })
  // The handler logs through authFailureDetails, whose shape is { failed, code?,
  // status? } and never carries a message. Assert the shape the helper actually
  // emits - this pins "a failure was recorded" without being able to pass
  // vacuously the way a plain !includes(diagnostic) was.
  assert(output.includes('Global error:'), "the global handler logged the failure")
  assert(output.includes('"failed":true'), "the global handler logged a structured failure detail")
  assert(!output.includes(diagnostic), "the raw message never reached the log")
})

Deno.test("authentication challenge store failure logs a code, never the database message", async () => {
  const client = createMockSupabaseClient()
  client.mockResponse('rpc.find_user_by_email', {
    data: [{ id: 'user-456', email: 'canary-user@example.com' }],
    error: null
  }, 'call')
  client.mockResponse('user_passkeys', { data: [mockPasskey], error: null }, 'select')
  client.mockResponse('passkey_challenges', {
    data: null,
    error: { code: '23505', message: CHALLENGE_CANARY }
  }, 'insert')

  const output = await captureLogs(async () => {
    const result = await generateAuthChallenge(client as unknown as SupabaseClient, 'canary-user@example.com')
    // The failure stays inert and indistinguishable from the other pre-auth
    // failure states, with nothing extra in the response.
    assertEquals(result.success, true)
    assert('allowCredentials' in result)
    assertEquals(result.allowCredentials.length, 0)
  })
  assert(!output.includes(CHALLENGE_CANARY), "store failure leaked the raw Postgres message")
  assert(output.includes('23505'), "the allowlisted code is retained for diagnosis")
})

Deno.test("registration challenge store failure reports a code, never the database message", async () => {
  const client = createMockSupabaseClient()
  client.mockResponse('passkey_challenges', {
    data: null,
    error: { code: '23505', message: CHALLENGE_CANARY }
  }, 'insert')

  const result = await generateRegistrationChallenge(client as unknown as SupabaseClient, 'user-456')
  assertEquals(result.success, false)
  if (!result.success) {
    assert(!result.error.includes(CHALLENGE_CANARY), `registration 400 leaked the database message: ${result.error}`)
    assertEquals(result.error, 'Failed to store challenge (23505)')
  }
})

// auth/complete has no caller authentication: possession of a live challenge is
// the only gate. When the completion row fails to store, the 400 body must carry
// the allowlisted code and never the raw database message. This is the more
// exposed sibling of the register/challenge case above, which is why it gets its
// own canary. It drives the real completeAuthentication to the point where
// storeCompletedAuthentication runs, using a genuine signed assertion.
Deno.test("authentication completion store failure reports a code, never the database message", async () => {
  const client = createMockSupabaseClient()
  const userId = 'user-complete-123'
  const testEmail = 'complete-canary@example.com'

  // A real ES256 assertion so the verification path runs end to end
  const keyPair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
  const credentialId = crypto.getRandomValues(new Uint8Array(16))
  const challenge = 'complete-canary-challenge'
  const credential = await createAssertion({ challenge, credentialId, privateKey: keyPair.privateKey, origin: TEST_ORIGIN })

  const publicKey = encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey('raw', keyPair.publicKey)))
  const passkeyRow = {
    id: 'passkey-complete',
    user_id: userId,
    credential_id: encodeBase64Url(credentialId),
    public_key: publicKey,
    display_name: 'Complete canary passkey',
    transports: ['internal']
  }

  // verifyChallenge: the challenge row exists, is unexpired, and carries a session
  client.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-complete',
      challenge,
      type: 'authentication',
      user_id: userId,
      session_id: 'session-complete',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  // findPasskeyByCredentialId
  client.mockResponse('user_passkeys', { data: passkeyRow, error: null }, 'select')

  // recordPasskeyUse
  client.mockResponse('user_passkeys', { data: { id: 'passkey-complete' }, error: null }, 'update')

  // getUserWithEmail (drives the session mint; a real email so tokens are minted)
  client.mockResponse('users', { data: { id: userId, email: testEmail }, error: null }, 'select')
  client.mockAuthUser(testEmail, userId)

  // consumeChallengeRow
  client.mockResponse('passkey_challenges', { data: { id: 'challenge-complete' }, error: null }, 'delete')

  // storeCompletedAuthentication - the row write fails with the canary
  client.mockResponse('completed_authentications', {
    data: null,
    error: { code: '23503', message: COMPLETE_CANARY }
  }, 'insert')

  const output = await captureLogs(async () => {
    const result = await completeAuthentication(client as unknown as SupabaseClient, credential, challenge)
    assertEquals(result.success, false, "the failed store must surface as a failure")
    if (!result.success) {
      const message = result.error
      assert(!message.includes(COMPLETE_CANARY), `auth/complete 400 leaked the database message: ${message}`)
      assert(message.includes('23503'), `the allowlisted code is retained for diagnosis: ${message}`)
    }
  })
  assert(!output.includes(COMPLETE_CANARY), "store failure leaked the raw Postgres message to the log")
})
