/**
 * `POST /refresh`: an install trades its refresh token for an access token through the one
 * holder of the web client secret.
 *
 * Run: cd supabase/functions/fluck-oauth && deno task test
 *
 * Requests are signed here exactly as the plugin signs them, over `signingString` from
 * signed.ts, so the canonical string is asserted as a fact about the bytes on the wire.
 */
import { assert, assertEquals } from "@std/assert"
import { createHandler, type Dependencies, type GrantOwner, type Instance } from "../app.ts"
import { refreshAccessToken } from "../google.ts"
import { bodyDigest, signingString } from "../signed.ts"

const NOW_SECONDS = 1_800_000_000
const INSTANCE_ID = "instance-abcdefgh-0001"
const USER_ID = "11111111-2222-3333-4444-555555555555"
const OTHER_USER_ID = "99999999-2222-3333-4444-555555555555"
const TOKEN_SHA256 = await bodyDigest("1//refresh-token")

const INSTALL = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
  "sign",
  "verify",
]) as CryptoKeyPair
const INSTALL_PUBLIC = btoa(
  String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey("raw", INSTALL.publicKey))),
)

function b64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_")
    .replaceAll("=", "")
}

async function signed(
  body: string,
  options: {
    signer?: CryptoKey
    ts?: number
    path?: string
    instance?: string | null
    signedPath?: string
  } = {},
): Promise<Request> {
  const ts = options.ts ?? NOW_SECONDS
  const message = signingString(
    "POST",
    options.signedPath ?? "/refresh",
    ts,
    await bodyDigest(body),
  )
  const signature = new Uint8Array(
    await crypto.subtle.sign(
      { name: "Ed25519" },
      options.signer ?? INSTALL.privateKey,
      new TextEncoder().encode(message) as BufferSource,
    ),
  )
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Fluck-Timestamp": String(ts),
    "X-Fluck-Signature": b64url(signature),
  }
  if (options.instance !== null) headers["X-Fluck-Instance"] = options.instance ?? INSTANCE_ID
  return new Request(
    `https://api.risaboss.com/functions/v1/fluck-oauth${options.path ?? "/refresh"}`,
    { method: "POST", headers, body },
  )
}

function harness(options: {
  google?: unknown
  googleThrows?: boolean
  instances?: Record<string, Instance>
  env?: Record<string, string>
  /** Token hash to owner. Defaults to the test token bound to USER_ID. */
  grants?: Record<string, string>
  grantStoreDown?: boolean
} = {}) {
  const sent: URLSearchParams[] = []
  const forgotten: string[] = []
  const grants = options.grants ?? { [TOKEN_SHA256]: USER_ID }
  const logs: string[] = []
  const env: Record<string, string> = {
    GOOGLE_WEB_CLIENT_ID: "294223497390-test.apps.googleusercontent.com",
    GOOGLE_WEB_CLIENT_SECRET: "not-a-real-secret",
    ...(options.env ?? {}),
  }
  const deps: Dependencies = {
    env: (name) => env[name],
    now: () => NOW_SECONDS * 1000,
    log: (line) => logs.push(line),
    fetch: ((_url: string | URL | Request, init?: RequestInit) => {
      if (options.googleThrows) return Promise.reject(new Error("network"))
      sent.push(new URLSearchParams(String(init?.body ?? "")))
      return Promise.resolve(
        new Response(
          JSON.stringify(
            options.google ??
              { access_token: "ya29.fresh", expires_in: 3599, scope: "openid email" },
          ),
        ),
      )
    }) as typeof fetch,
    instance: (id) =>
      Promise.resolve(
        (options.instances ??
          { [INSTANCE_ID]: { userId: USER_ID, linkPublicKey: INSTALL_PUBLIC } })[id] ?? null,
      ),
    claimNonce: () => Promise.reject(new Error("refresh must not claim a nonce")),
    storeRefreshToken: () => Promise.reject(new Error("refresh must not store anything")),
    bindGrant: () => Promise.reject(new Error("refresh must not bind a grant")),
    grantOwner: (tokenSha256): Promise<GrantOwner> =>
      Promise.resolve(
        options.grantStoreDown
          ? { status: "unavailable" }
          : grants[tokenSha256]
          ? { status: "bound", userId: grants[tokenSha256] }
          : { status: "unbound" },
      ),
    forgetGrant: (tokenSha256) => {
      forgotten.push(tokenSha256)
      return Promise.resolve()
    },
  }
  return { handler: createHandler(deps), sent, logs, forgotten }
}

const BODY = JSON.stringify({ refresh_token: "1//refresh-token" })

Deno.test("a signed refresh returns the access token, expiry and scope", async () => {
  const h = harness()
  const response = await h.handler(await signed(BODY))
  assertEquals(response.status, 200)
  assertEquals(response.headers.get("cache-control"), "no-store")
  assertEquals(await response.json(), {
    access_token: "ya29.fresh",
    expires_in: 3599,
    scope: "openid email",
  })
  assertEquals(h.sent.length, 1)
  assertEquals(h.sent[0].get("grant_type"), "refresh_token")
  assertEquals(h.sent[0].get("refresh_token"), "1//refresh-token")
  assertEquals(h.sent[0].get("client_secret"), "not-a-real-secret")
})

Deno.test("every auth failure is the same 401 and never reaches Google", async () => {
  const other = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ]) as CryptoKeyPair
  const unsigned = new Request("https://x.test/fluck-oauth/refresh", {
    method: "POST",
    body: BODY,
  })
  const tampered = await signed(BODY)
  const cases: [string, Request, ReturnType<typeof harness>][] = [
    ["no headers", unsigned, harness()],
    ["no instance header", await signed(BODY, { instance: null }), harness()],
    ["malformed instance", await signed(BODY, { instance: "x" }), harness()],
    ["unknown or revoked instance", await signed(BODY), harness({ instances: {} })],
    ["another install's key", await signed(BODY, { signer: other.privateKey }), harness()],
    ["stale timestamp", await signed(BODY, { ts: NOW_SECONDS - 121 }), harness()],
    ["signed for another route", await signed(BODY, { signedPath: "/requests" }), harness()],
    [
      "body swapped after signing",
      new Request(tampered.url, {
        method: "POST",
        headers: tampered.headers,
        body: JSON.stringify({ refresh_token: "1//other" }),
      }),
      harness(),
    ],
  ]
  for (const [name, request, h] of cases) {
    const response = await h.handler(request)
    assertEquals(response.status, 401, name)
    assertEquals(await response.json(), { error: "unauthorized" }, name)
    assertEquals(h.sent.length, 0, name)
  }
})

Deno.test("the route is the same under every mount point", async () => {
  for (const path of ["/refresh", "/refresh/"]) {
    const h = harness()
    const response = await h.handler(await signed(BODY, { path }))
    assertEquals(response.status, 200, path)
  }
})

Deno.test("invalid_grant passes through and the dead grant's binding is deleted", async () => {
  const h = harness({ google: { error: "invalid_grant", error_description: "Token revoked" } })
  const response = await h.handler(await signed(BODY))
  assertEquals(response.status, 400)
  assertEquals(await response.json(), { error: "invalid_grant" })
  assertEquals(h.sent.length, 1)
  assertEquals(h.forgotten, [TOKEN_SHA256])
})

Deno.test("an unbound token is invalid_grant and never reaches Google", async () => {
  const h = harness({ grants: {} })
  const response = await h.handler(await signed(BODY))
  assertEquals(response.status, 400)
  assertEquals(await response.json(), { error: "invalid_grant" })
  assertEquals(h.sent.length, 0)
  assertEquals(h.forgotten, [])
  assertEquals(h.logs, ["refresh refused: unbound [instance]"])
})

Deno.test("a token bound to another user is invalid_grant and never reaches Google", async () => {
  const h = harness({ grants: { [TOKEN_SHA256]: OTHER_USER_ID } })
  const response = await h.handler(await signed(BODY))
  assertEquals(response.status, 400)
  assertEquals(await response.json(), { error: "invalid_grant" })
  assertEquals(h.sent.length, 0)
  assertEquals(h.forgotten, [])
  assertEquals(h.logs, ["refresh refused: unbound [instance]"])
})

Deno.test("an unreachable grant store is 502, not invalid_grant", async () => {
  const h = harness({ grantStoreDown: true })
  const response = await h.handler(await signed(BODY))
  assertEquals(response.status, 502)
  assertEquals(await response.json(), { error: "unavailable" })
  assertEquals(h.sent.length, 0)
})

Deno.test("other Google failures keep the binding", async () => {
  const h = harness({ google: { error: "invalid_client" } })
  await h.handler(await signed(BODY))
  assertEquals(h.forgotten, [])
})

Deno.test("any other Google refusal or outage is 502 unavailable, not invalid_grant", async () => {
  for (
    const h of [
      harness({ google: { error: "invalid_client" } }),
      harness({ googleThrows: true }),
      harness({ google: "not an object" }),
    ]
  ) {
    const response = await h.handler(await signed(BODY))
    assertEquals(response.status, 502)
    assertEquals(await response.json(), { error: "unavailable" })
  }
})

Deno.test("a signed but malformed body is a 400, not a Google call", async () => {
  for (const body of ["", "{", "[]", "{}", '{"refresh_token":""}', '{"refresh_token":7}']) {
    const h = harness()
    const response = await h.handler(await signed(body))
    assertEquals(response.status, 400, body)
    assertEquals(await response.json(), { error: "body" }, body)
    assertEquals(h.sent.length, 0, body)
  }
})

Deno.test("an unconfigured client is a 503 after auth", async () => {
  const h = harness({ env: { GOOGLE_WEB_CLIENT_SECRET: "" } })
  const response = await h.handler(await signed(BODY))
  assertEquals(response.status, 503)
  assertEquals(await response.json(), { error: "unconfigured" })
})

Deno.test("a GET is refused", async () => {
  const h = harness()
  const response = await h.handler(new Request("https://x.test/fluck-oauth/refresh"))
  assertEquals(response.status, 405)
})

Deno.test("no log line carries a token or the full install id", async () => {
  const h = harness()
  await h.handler(await signed(BODY))
  await harness({ google: { error: "invalid_grant" } }).handler(await signed(BODY))
  assertEquals(h.logs, ["refresh ok [instance]"])
  for (const line of h.logs) {
    assert(!line.includes("1//refresh-token"))
    assert(!line.includes("ya29"))
    assert(!line.includes(INSTANCE_ID))
  }
})

Deno.test("refreshAccessToken never reports an unexpected success shape as success", async () => {
  const result = await refreshAccessToken(
    { clientId: "id", clientSecret: "s", refreshToken: "r" },
    (() =>
      Promise.resolve(
        new Response(JSON.stringify({ access_token: "a" })),
      )) as unknown as typeof fetch,
  )
  assert(!result.ok)
  assertEquals(result.reason, "unavailable")
})
