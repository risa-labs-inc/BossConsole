/**
 * Routing, the token exchange and the callback decisions, against a fake fetch.
 *
 * Run: cd supabase/functions/fluck-oauth && deno task test
 *
 * Every case drives `createHandler` from app.ts, never index.ts, so no listener binds and no
 * Supabase client is constructed. The fake `fetch` is what stands in for Google's token
 * endpoint, and it records what was sent so the redirect_uri and the absence of a code_verifier
 * can be asserted as facts about the request rather than as a reading of the source.
 */
import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import {
  createHandler,
  DEFAULT_PUBLIC_BASE_URL,
  type Dependencies,
  type Instance,
  type NonceClaim,
  page,
  PAGES,
  routePath,
  type StoreRequest,
} from "../app.ts"
import { emailFromIdToken, exchangeCode } from "../google.ts"
import { mintState } from "../state.ts"
import { bodyDigest } from "../signed.ts"

const NOW_SECONDS = 1_800_000_000
const USER_ID = "11111111-2222-3333-4444-555555555555"
const WORKSPACE = "ws-abcdefghij"
const INSTANCE_ID = "instance-abcdefgh-0001"

const INSTALL = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
  "sign",
  "verify",
]) as CryptoKeyPair
const INSTALL_PUBLIC = btoa(
  String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey("raw", INSTALL.publicKey))),
)

function idToken(email: string): string {
  const b64 = (value: string) =>
    btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
  return b64('{"alg":"RS256"}') + "." + b64(JSON.stringify({ email })) + ".signature"
}

async function state(
  overrides: Partial<Record<"ws" | "uid" | "cid" | "nonce" | "iid", string>> & {
    signer?: CryptoKey
  } = {},
) {
  return await mintState(overrides.signer ?? INSTALL.privateKey, {
    v: 1,
    iid: overrides.iid ?? INSTANCE_ID,
    uid: overrides.uid ?? USER_ID,
    ws: overrides.ws ?? WORKSPACE,
    cid: overrides.cid ?? "conversation-1",
    nonce: overrides.nonce ?? "nonce-one",
    iat: NOW_SECONDS - 10,
    exp: NOW_SECONDS + 590,
  })
}

interface Harness {
  handler: (request: Request) => Promise<Response>
  stored: StoreRequest[]
  bound: { tokenSha256: string; userId: string }[]
  claimed: string[]
  requests: { url: string; body: URLSearchParams }[]
  logs: string[]
}

function harness(options: {
  env?: Record<string, string>
  tokenResponse?: unknown
  tokenThrows?: boolean
  claim?: NonceClaim
  storeOk?: boolean
  bindOk?: boolean
  instances?: Record<string, Instance>
} = {}): Harness {
  const stored: StoreRequest[] = []
  const bound: { tokenSha256: string; userId: string }[] = []
  const claimed: string[] = []
  const requests: { url: string; body: URLSearchParams }[] = []
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
    fetch: ((url: string | URL | Request, init?: RequestInit) => {
      if (options.tokenThrows) return Promise.reject(new Error("network"))
      requests.push({
        url: String(url),
        body: new URLSearchParams(String(init?.body ?? "")),
      })
      return Promise.resolve(
        new Response(
          JSON.stringify(
            options.tokenResponse ?? {
              access_token: "ya29.access",
              refresh_token: "1//refresh",
              expires_in: 3599,
              id_token: idToken("person@example.com"),
            },
          ),
          { headers: { "Content-Type": "application/json" } },
        ),
      )
    }) as typeof fetch,
    instance: (id) =>
      Promise.resolve(
        (options.instances ??
          { [INSTANCE_ID]: { userId: USER_ID, linkPublicKey: INSTALL_PUBLIC } })[
            id
          ] ?? null,
      ),
    claimNonce: (nonce) => {
      claimed.push(nonce)
      return Promise.resolve(options.claim ?? "claimed")
    },
    storeRefreshToken: (request) => {
      stored.push(request)
      return Promise.resolve(options.storeOk ?? true)
    },
    bindGrant: (tokenSha256, userId) => {
      bound.push({ tokenSha256, userId })
      return Promise.resolve(options.bindOk ?? true)
    },
    grantOwner: () => Promise.reject(new Error("the callback must not look up a grant")),
    forgetGrant: () => Promise.reject(new Error("the callback must not forget a grant")),
  }
  return { handler: createHandler(deps), stored, bound, claimed, requests, logs }
}

async function callback(h: Harness, query: string): Promise<Response> {
  return await h.handler(
    new Request(`https://example.test/functions/v1/fluck-oauth/callback?${query}`),
  )
}

Deno.test("routePath strips both the edge runtime prefix and the function name", () => {
  assertEquals(routePath("/functions/v1/fluck-oauth/callback"), "/callback")
  assertEquals(routePath("/fluck-oauth/callback"), "/callback")
  assertEquals(routePath("/callback"), "/callback")
  assertEquals(routePath("/callback/"), "/callback")
  assertEquals(routePath("/functions/v1/fluck-oauth"), "/")
  assertEquals(routePath("/fluck-oauth/health"), "/health")
})

Deno.test("health reports readiness as booleans and never a value", async () => {
  const h = harness()
  const response = await h.handler(new Request("https://example.test/fluck-oauth/health"))
  assertEquals(response.status, 200)
  const body = await response.json()
  assertEquals(body, {
    ok: true,
    configured: { clientId: true, clientSecret: true },
  })
  assertEquals(JSON.stringify(body).includes("secret"), false)
})

Deno.test("health is 503 when the client secret is missing", async () => {
  const h = harness({ env: { GOOGLE_WEB_CLIENT_SECRET: "" } })
  const response = await h.handler(new Request("https://example.test/fluck-oauth/health"))
  assertEquals(response.status, 503)
})

Deno.test("client returns the configured client id, uncached", async () => {
  const h = harness()
  const response = await h.handler(
    new Request("https://example.test/functions/v1/fluck-oauth/client"),
  )
  assertEquals(response.status, 200)
  assertEquals(response.headers.get("Content-Type"), "application/json")
  assertEquals(response.headers.get("Cache-Control"), "no-store")
  assertEquals(await response.json(), {
    client_id: "294223497390-test.apps.googleusercontent.com",
  })
  assertEquals(routePath("/fluck-oauth/client/"), "/client")
})

Deno.test("client is 503 when the client id is missing or blank", async () => {
  for (const value of ["", "  "]) {
    const h = harness({ env: { GOOGLE_WEB_CLIENT_ID: value } })
    const response = await h.handler(new Request("https://example.test/fluck-oauth/client"))
    assertEquals(response.status, 503)
    assertEquals(await response.json(), { error: "unconfigured" })
    assertEquals(h.logs, ["client unconfigured"])
  }
})

Deno.test("a POST to client is refused and its body drained", async () => {
  const h = harness()
  const request = new Request("https://example.test/fluck-oauth/client", {
    method: "POST",
    body: "x",
  })
  const response = await h.handler(request)
  assertEquals(response.status, 405)
  assertEquals(await response.json(), { error: "method" })
  assertEquals(request.bodyUsed, true)
})

Deno.test("an unknown route is a 404 page", async () => {
  const h = harness()
  const response = await h.handler(new Request("https://example.test/fluck-oauth/start"))
  assertEquals(response.status, 404)
  assertStringIncludes(await response.text(), PAGES.notFound)
})

Deno.test("a signed state cannot write as a different BOSS user", async () => {
  const h = harness()
  const response = await callback(h, `code=code&state=${await state({ uid: "another-user" })}`)
  assertEquals(response.status, 400)
  assertEquals(h.claimed, [])
  assertEquals(h.requests, [])
  assertEquals(h.stored, [])
})

Deno.test("an unknown or revoked install is refused before anything is spent", async () => {
  // `fluck_vault_instance` returns no row for either, so both reach here as null.
  const h = harness({ instances: {} })
  const response = await callback(h, `code=code&state=${await state()}`)
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.stale)
  assertEquals(h.claimed, [])
  assertEquals(h.requests, [])
  assertEquals(h.stored, [])
})

Deno.test("a state signed by another install's key is refused for this install id", async () => {
  const other = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ]) as CryptoKeyPair
  const h = harness()
  const response = await callback(h, `code=code&state=${await state({ signer: other.privateKey })}`)
  assertEquals(response.status, 400)
  assertEquals(h.claimed, [])
  assertEquals(h.stored, [])
})

Deno.test("any registered install can connect, for its own owner only", async () => {
  const otherUser = "99999999-2222-3333-4444-555555555555"
  const h = harness({
    instances: {
      [INSTANCE_ID]: { userId: USER_ID, linkPublicKey: INSTALL_PUBLIC },
      "second-install-000001": { userId: otherUser, linkPublicKey: INSTALL_PUBLIC },
    },
  })
  const ok = await callback(
    h,
    `code=abc&state=${await state({ iid: "second-install-000001", uid: otherUser })}`,
  )
  assertEquals(ok.status, 200)
  assertEquals(h.stored[0].userId, otherUser)
  const crossed = await callback(
    h,
    `code=abc&state=${await state({ iid: "second-install-000001", uid: USER_ID })}`,
  )
  assertEquals(crossed.status, 400)
  assertEquals(h.stored.length, 1)
})

Deno.test("a good callback exchanges, stores, and says one sentence", async () => {
  const h = harness()
  const response = await callback(h, `code=4%2F0Aabc-def_ghi&state=${await state()}`)
  assertEquals(response.status, 200)
  const html = await response.text()
  assertStringIncludes(html, PAGES.connected)

  assertEquals(h.requests.length, 1)
  assertEquals(h.requests[0].url, "https://oauth2.googleapis.com/token")
  // The code arrives percent encoded and must be decoded exactly once on the way out.
  assertEquals(h.requests[0].body.get("code"), "4/0Aabc-def_ghi")
  assertEquals(h.requests[0].body.get("grant_type"), "authorization_code")
  assertEquals(h.requests[0].body.get("redirect_uri"), `${DEFAULT_PUBLIC_BASE_URL}/callback`)
  // No PKCE: the exchanging party holds the client secret, so there is no verifier to send.
  assertEquals(h.requests[0].body.get("code_verifier"), null)

  assertEquals(h.stored.length, 1)
  assertEquals(h.stored[0].userId, USER_ID)
  assertEquals(h.stored[0].website, `fluck/${WORKSPACE}/google/GOOGLE_REFRESH_TOKEN`)
  assertEquals(h.stored[0].username, "person@example.com")
  assertEquals(h.stored[0].refreshToken, "1//refresh")

  // The page carries no token, no code and no state.
  assertEquals(html.includes("1//refresh"), false)
  assertEquals(html.includes("4/0Aabc"), false)
  assertEquals(html.includes("ya29"), false)
})

Deno.test("PUBLIC_BASE_URL moves the redirect uri for the custom domain", async () => {
  const h = harness({ env: { PUBLIC_BASE_URL: "https://api.bossresa.com/fluck-oauth/" } })
  await callback(h, `code=abc&state=${await state()}`)
  assertEquals(
    h.requests[0].body.get("redirect_uri"),
    "https://api.bossresa.com/fluck-oauth/callback",
  )
})

Deno.test("a declined consent never echoes the provider's own word for it", async () => {
  const h = harness()
  const response = await callback(h, "error=access_denied&state=whatever")
  assertEquals(response.status, 400)
  const html = await response.text()
  assertStringIncludes(html, PAGES.declined)
  assertEquals(html.includes("access_denied"), false)
  assertEquals(h.requests.length, 0)
  assertEquals(h.stored.length, 0)
})

Deno.test("an unsigned, forged or absent state is refused before anything is spent", async () => {
  for (
    const query of [
      "code=abc",
      "code=abc&state=not.a.token",
      `state=${await state()}`,
    ]
  ) {
    const h = harness()
    const response = await callback(h, query)
    assertEquals(response.status, 400)
    assertStringIncludes(await response.text(), PAGES.stale)
    assertEquals(h.claimed.length, 0)
    assertEquals(h.requests.length, 0)
    assertEquals(h.stored.length, 0)
  }
})

Deno.test("the conversation id is opaque and does not change the write", async () => {
  const h = harness()
  const response = await callback(h, `code=abc&state=${await state({ cid: "any-conversation" })}`)
  assertEquals(response.status, 200)
  assertEquals(h.stored[0].website, `fluck/${WORKSPACE}/google/GOOGLE_REFRESH_TOKEN`)
})

Deno.test("an invalid nonce expiry is not called a replay and never reaches Google", async () => {
  const h = harness({ claim: "invalid" })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 400)
  const html = await response.text()
  assertStringIncludes(html, PAGES.stale)
  assertEquals(html.includes(PAGES.replay), false)
  assertEquals(h.requests.length, 0)
  assertEquals(h.stored.length, 0)
})

Deno.test("HTML output escapes all markup and quote delimiters", async () => {
  const html = await page(400, '<script>"&', "'unsafe'").text()
  assertStringIncludes(html, "&lt;script&gt;&quot;&amp;")
  assertStringIncludes(html, "&#39;unsafe&#39;")
})

Deno.test("a replayed nonce is refused before the exchange", async () => {
  const h = harness({ claim: "replay" })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.replay)
  assertEquals(h.claimed, [`${INSTANCE_ID}.nonce-one`])
  assertEquals(h.requests.length, 0)
  assertEquals(h.stored.length, 0)
})

Deno.test("the nonce is claimed even when the exchange then fails, so a link is single use", async () => {
  const h = harness({ tokenResponse: { error: "invalid_grant" } })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.badCode)
  assertEquals(h.claimed, [`${INSTANCE_ID}.nonce-one`])
  assertEquals(h.stored.length, 0)
})

Deno.test("tokens without a refresh token are a different failure with a different remedy", async () => {
  const h = harness({ tokenResponse: { access_token: "ya29.only", expires_in: 3599 } })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.noRefresh)
  assertEquals(h.stored.length, 0)
})

Deno.test("an unreachable token endpoint is not reported as a bad code", async () => {
  const h = harness({ tokenThrows: true })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.unreachable)
})

Deno.test("a good callback binds the refresh token's hash to the state's user", async () => {
  const h = harness()
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 200)
  assertEquals(h.bound, [{ tokenSha256: await bodyDigest("1//refresh"), userId: USER_ID }])
  assert(/^[0-9a-f]{64}$/.test(h.bound[0].tokenSha256))
})

Deno.test("a failed bind fails the callback and writes no secret", async () => {
  const h = harness({ bindOk: false })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 503)
  assertStringIncludes(await response.text(), PAGES.storeFailed)
  assertEquals(h.stored, [])
  assertEquals(h.logs, ["callback failed: bind [ws-abcde]"])
})

Deno.test("a failed store is a 503, not a success page", async () => {
  const h = harness({ storeOk: false })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 503)
  assertStringIncludes(await response.text(), PAGES.storeFailed)
})

Deno.test("an unreachable nonce store never reaches Google", async () => {
  const h = harness({ claim: "unavailable" })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 503)
  assertEquals(h.requests.length, 0)
})

Deno.test("a function with no client configured refuses rather than half finishing", async () => {
  const h = harness({ env: { GOOGLE_WEB_CLIENT_SECRET: "" } })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 503)
  assertStringIncludes(await response.text(), PAGES.unconfigured)
  assertEquals(h.claimed.length, 0)
})

Deno.test("a POST to the callback is refused", async () => {
  const h = harness()
  const response = await h.handler(
    new Request("https://example.test/fluck-oauth/callback", { method: "POST", body: "x" }),
  )
  assertEquals(response.status, 405)
  assertStringIncludes(await response.text(), PAGES.notABrowser)
})

Deno.test("an account with no readable id_token still connects, under a placeholder", async () => {
  const h = harness({
    tokenResponse: { access_token: "ya29.a", refresh_token: "1//r", expires_in: 3599 },
  })
  const response = await callback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 200)
  assertEquals(h.stored[0].username, "google account")
})

Deno.test("logs carry the route, the outcome and a workspace prefix, and nothing else", async () => {
  const h = harness()
  await callback(h, `code=4%2F0Aabc&state=${await state()}`)
  assertEquals(h.logs, ["callback connected [ws-abcde]"])
  for (const line of h.logs) {
    assertEquals(line.includes("1//refresh"), false)
    assertEquals(line.includes("4/0Aabc"), false)
    assertEquals(line.includes("person@example.com"), false)
    assertEquals(line.includes(WORKSPACE), false)
  }
})

Deno.test("emailFromIdToken reads the claim and never throws on rubbish", () => {
  assertEquals(emailFromIdToken(idToken("a@b.test")), "a@b.test")
  assertEquals(emailFromIdToken(null), "")
  assertEquals(emailFromIdToken("not-a-token"), "")
  assertEquals(emailFromIdToken("a.!!!.c"), "")
})

Deno.test("exchangeCode reports an unparseable body as unreachable", async () => {
  const result = await exchangeCode(
    { clientId: "id", clientSecret: "secret", code: "c", redirectUri: "https://x.test/callback" },
    (() => Promise.resolve(new Response("<html>gateway</html>"))) as unknown as typeof fetch,
  )
  assert(!result.ok)
  assertEquals(result.reason, "unreachable")
})

Deno.test("no user facing sentence contains a dash or an emoji", () => {
  for (const sentence of Object.values(PAGES)) {
    assertEquals(sentence.includes("-"), false, sentence)
    assertEquals(sentence.includes("—"), false, sentence)
    assertEquals(/\p{Extended_Pictographic}/u.test(sentence), false, sentence)
  }
})

Deno.test("a pre-1.0.120 HS256 state gets an update page, not a stale link page", async () => {
  const b64 = (value: string) =>
    btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
  const legacy = b64('{"alg":"HS256","typ":"JWT"}') + "." +
    b64(JSON.stringify({ uid: USER_ID, ws: WORKSPACE, exp: NOW_SECONDS + 600 })) + ".mac"
  const h = harness()
  const response = await callback(h, `code=abc&state=${legacy}`)
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.updatePlugin)
  assertEquals(h.claimed.length, 0)
  assertEquals(h.requests.length, 0)
  assertEquals(h.stored.length, 0)
  assertEquals(h.logs, ["callback refused: legacy state"])
})

Deno.test("a three part state that is not HS256 is just stale", async () => {
  const h = harness()
  const response = await callback(h, "code=abc&state=a.b.c")
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.stale)
})

Deno.test("a blank client id or secret is unconfigured on every route alike", async () => {
  const blanks: Record<string, string>[] = [
    { GOOGLE_WEB_CLIENT_ID: "  " },
    { GOOGLE_WEB_CLIENT_SECRET: " " },
  ]
  for (const env of blanks) {
    const h = harness({ env })
    const health = await h.handler(new Request("https://example.test/fluck-oauth/health"))
    assertEquals(health.status, 503)
    const response = await callback(h, `code=abc&state=${await state()}`)
    assertEquals(response.status, 503)
    assertStringIncludes(await response.text(), PAGES.unconfigured)
    assertEquals(h.requests.length, 0)
  }
})
