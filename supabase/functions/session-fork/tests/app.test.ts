import { assertEquals } from "@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { claimsOf, createHandler, freshnessRefusal, FRESH_TOKEN_MAX_AGE_S } from "../app.ts"

const NOW = 1_800_000_000
const USER = { id: "11111111-2222-3333-4444-555555555555", email: "a@example.com" }

function token(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_")
  return `${b64({ alg: "HS256" })}.${b64(claims)}.sig`
}

const freshClaims = { sub: USER.id, role: "authenticated", iat: NOW - 5 }

interface Calls {
  generateLink: number
  verifyOtp: number
}

function fakeClient(opts: { user?: typeof USER | null; sessionUserId?: string } = {}): { client: () => SupabaseClient; calls: Calls } {
  const calls: Calls = { generateLink: 0, verifyOtp: 0 }
  const user = opts.user === undefined ? USER : opts.user
  const client = {
    auth: {
      getUser: (_: string) => Promise.resolve(user ? { data: { user }, error: null } : { data: { user: null }, error: { message: "bad" } }),
      admin: {
        generateLink: () => {
          calls.generateLink++
          return Promise.resolve({ data: { properties: { hashed_token: "h" } }, error: null })
        },
      },
      verifyOtp: () => {
        calls.verifyOtp++
        return Promise.resolve({
          data: {
            session: {
              access_token: "new-access",
              refresh_token: "new-refresh",
              expires_in: 3600,
              user: { id: opts.sessionUserId ?? USER.id },
            },
          },
          error: null,
        })
      },
    },
  } as unknown as SupabaseClient
  return { client: () => client, calls }
}

function post(authorization?: string): Request {
  const headers: Record<string, string> = {}
  if (authorization) headers["Authorization"] = authorization
  return new Request("http://localhost/session-fork", { method: "POST", headers })
}

Deno.test("a fresh token for a live user gets a new session for that user", async () => {
  const { client, calls } = fakeClient()
  const res = await createHandler(client, () => NOW)(post(`Bearer ${token(freshClaims)}`))
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "no-store")
  const body = await res.json()
  assertEquals(body, { access_token: "new-access", refresh_token: "new-refresh", expires_in: 3600, user_id: USER.id })
  assertEquals(calls, { generateLink: 1, verifyOtp: 1 })
})

Deno.test("a token older than the freshness window mints nothing", async () => {
  const { client, calls } = fakeClient()
  const stale = token({ ...freshClaims, iat: NOW - FRESH_TOKEN_MAX_AGE_S - 1 })
  const res = await createHandler(client, () => NOW)(post(`Bearer ${stale}`))
  assertEquals(res.status, 401)
  assertEquals((await res.json()).error, "stale_token")
  assertEquals(calls, { generateLink: 0, verifyOtp: 0 })
})

Deno.test("a token GoTrue rejects mints nothing", async () => {
  const { client, calls } = fakeClient({ user: null })
  const res = await createHandler(client, () => NOW)(post(`Bearer ${token(freshClaims)}`))
  assertEquals(res.status, 401)
  assertEquals(calls.generateLink, 0)
})

Deno.test("a token whose subject is not the validated user mints nothing", async () => {
  const { client, calls } = fakeClient()
  const other = token({ ...freshClaims, sub: "99999999-2222-3333-4444-555555555555" })
  const res = await createHandler(client, () => NOW)(post(`Bearer ${other}`))
  assertEquals(res.status, 401)
  assertEquals(calls.generateLink, 0)
})

Deno.test("a session minted for someone else is not returned", async () => {
  const { client } = fakeClient({ sessionUserId: "99999999-2222-3333-4444-555555555555" })
  const res = await createHandler(client, () => NOW)(post(`Bearer ${token(freshClaims)}`))
  assertEquals(res.status, 502)
  assertEquals((await res.json()).access_token, undefined)
})

Deno.test("requests without a bearer token or with the wrong method are refused", async () => {
  const { client } = fakeClient()
  const handler = createHandler(client, () => NOW)
  assertEquals((await handler(post())).status, 401)
  assertEquals((await handler(new Request("http://localhost/session-fork"))).status, 405)
})

Deno.test("only a signed-in user's token qualifies", () => {
  assertEquals(freshnessRefusal({ ...freshClaims, role: "anon" }, NOW) !== null, true)
  assertEquals(freshnessRefusal({ ...freshClaims, role: "service_role" }, NOW) !== null, true)
  assertEquals(freshnessRefusal({ ...freshClaims, iat: NOW + 600 }, NOW) !== null, true)
  assertEquals(freshnessRefusal(freshClaims, NOW), null)
})

Deno.test("claims parse from base64url payloads and garbage is refused", () => {
  assertEquals(claimsOf(token(freshClaims))?.sub, USER.id)
  assertEquals(claimsOf("not-a-jwt"), null)
  assertEquals(claimsOf("a.!!!.c"), null)
})
