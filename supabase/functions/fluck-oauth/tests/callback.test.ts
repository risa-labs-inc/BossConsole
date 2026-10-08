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
import { exchangeSlackCode } from "../slack.ts"
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
  tokenStatus?: number
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
          { status: options.tokenStatus ?? 200, headers: { "Content-Type": "application/json" } },
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
    configured: {
      clientId: true,
      clientSecret: true,
      githubClientId: false,
      slackClientId: false,
      slackClientSecret: false,
    },
  })
  assertEquals(JSON.stringify(body).includes("secret"), false)
})

Deno.test("health reports the optional GitHub client id without gating on it", async () => {
  const h = harness({ env: { GITHUB_OAUTH_CLIENT_ID: "Ov23liTestClientId00" } })
  const response = await h.handler(new Request("https://example.test/fluck-oauth/health"))
  assertEquals(response.status, 200)
  assertEquals(await response.json(), {
    ok: true,
    configured: {
      clientId: true,
      clientSecret: true,
      githubClientId: true,
      slackClientId: false,
      slackClientSecret: false,
    },
  })
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

Deno.test("client adds the GitHub client id only when it is set", async () => {
  const h = harness({ env: { GITHUB_OAUTH_CLIENT_ID: "Ov23liTestClientId00" } })
  const response = await h.handler(new Request("https://example.test/fluck-oauth/client"))
  assertEquals(response.status, 200)
  assertEquals(await response.json(), {
    client_id: "294223497390-test.apps.googleusercontent.com",
    github_client_id: "Ov23liTestClientId00",
  })
  for (const value of ["", "  "]) {
    const blank = harness({ env: { GITHUB_OAUTH_CLIENT_ID: value } })
    const blankResponse = await blank.handler(
      new Request("https://example.test/fluck-oauth/client"),
    )
    assertEquals(blankResponse.status, 200)
    assertEquals(await blankResponse.json(), {
      client_id: "294223497390-test.apps.googleusercontent.com",
    })
    const health = await (await blank.handler(
      new Request("https://example.test/fluck-oauth/health"),
    )).json()
    assertEquals(health.configured.githubClientId, false)
  }
})

Deno.test("client serves the Google id exactly as configured, untrimmed", async () => {
  const padded = " 294223497390-test.apps.googleusercontent.com "
  const h = harness({ env: { GOOGLE_WEB_CLIENT_ID: padded } })
  const response = await h.handler(new Request("https://example.test/fluck-oauth/client"))
  assertEquals(await response.json(), { client_id: padded })
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

Deno.test("client serves the GitHub id alone when the Google pair is missing", async () => {
  const h = harness({
    env: {
      GOOGLE_WEB_CLIENT_ID: "",
      GOOGLE_WEB_CLIENT_SECRET: "",
      GITHUB_OAUTH_CLIENT_ID: "Ov23liTestClientId00",
    },
  })
  const response = await h.handler(new Request("https://example.test/fluck-oauth/client"))
  assertEquals(response.status, 200)
  assertEquals(await response.json(), { github_client_id: "Ov23liTestClientId00" })
  const health = await h.handler(new Request("https://example.test/fluck-oauth/health"))
  assertEquals(health.status, 503)
  assertEquals((await health.json()).ok, false)
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

// ---------------------------------------------------------------------------------------------
// Slack
// ---------------------------------------------------------------------------------------------

const SLACK_ENV = { SLACK_CLIENT_ID: "1234.5678", SLACK_CLIENT_SECRET: "not-a-slack-secret" }
const XOXP = "xoxp-1111-2222-3333-abcdef"

const SLACK_OK = {
  ok: true,
  app_id: "A0APP",
  authed_user: { id: "U0MEMBER", scope: "search:read", access_token: XOXP, token_type: "user" },
  team: { id: "T0TEAM", name: "Risa Labs" },
}

function slackHarness(options: Parameters<typeof harness>[0] = {}): Harness {
  return harness({
    tokenResponse: SLACK_OK,
    ...options,
    env: { ...SLACK_ENV, ...(options.env ?? {}) },
  })
}

async function slackCallback(h: Harness, query: string): Promise<Response> {
  return await h.handler(
    new Request(`https://example.test/functions/v1/fluck-oauth/slack/callback?${query}`),
  )
}

Deno.test("health reports the optional Slack pair without gating on it", async () => {
  const h = harness({ env: { SLACK_CLIENT_ID: "1234.5678" } })
  const response = await h.handler(new Request("https://example.test/fluck-oauth/health"))
  assertEquals(response.status, 200)
  const body = await response.json()
  assertEquals(body.configured.slackClientId, true)
  assertEquals(body.configured.slackClientSecret, false)
  const both = await (await slackHarness().handler(
    new Request("https://example.test/fluck-oauth/health"),
  )).json()
  assertEquals(both.configured.slackClientSecret, true)
  assertEquals(JSON.stringify(both).includes("not-a-slack-secret"), false)
})

Deno.test("client adds the Slack client id only when it is set", async () => {
  const h = slackHarness()
  const response = await h.handler(new Request("https://example.test/fluck-oauth/client"))
  assertEquals(await response.json(), {
    client_id: "294223497390-test.apps.googleusercontent.com",
    slack_client_id: "1234.5678",
  })
  for (const value of ["", "  "]) {
    const blank = harness({ env: { SLACK_CLIENT_ID: value } })
    const body = await (await blank.handler(
      new Request("https://example.test/fluck-oauth/client"),
    )).json()
    assertEquals("slack_client_id" in body, false)
  }
})

Deno.test("a good Slack callback stores the xoxp token under the slack connector", async () => {
  const h = slackHarness()
  const response = await slackCallback(h, `code=1234.5678.abc&state=${await state()}`)
  assertEquals(response.status, 200)
  const html = await response.text()
  assertStringIncludes(html, PAGES.connected)

  assertEquals(h.requests.length, 1)
  assertEquals(h.requests[0].url, "https://slack.com/api/oauth.v2.access")
  assertEquals(h.requests[0].body.get("client_id"), "1234.5678")
  assertEquals(h.requests[0].body.get("client_secret"), "not-a-slack-secret")
  assertEquals(h.requests[0].body.get("code"), "1234.5678.abc")
  assertEquals(
    h.requests[0].body.get("redirect_uri"),
    `${DEFAULT_PUBLIC_BASE_URL}/slack/callback`,
  )
  assertEquals(h.claimed, [`${INSTANCE_ID}.nonce-one`])

  assertEquals(h.stored, [{
    userId: USER_ID,
    website: `fluck/${WORKSPACE}/slack/SLACK_MCP_XOXP_TOKEN`,
    username: "Risa Labs (U0MEMBER)",
    refreshToken: XOXP,
    notes: "Created by Fluck when a service was connected. Do not edit by hand.",
  }])
  // No /refresh for Slack, so nothing is bound.
  assertEquals(h.bound, [])
  assertEquals(html.includes(XOXP), false)
  assertEquals(h.logs, ["slack callback connected [ws-abcde]"])
})

Deno.test("PUBLIC_BASE_URL moves the Slack redirect uri too", async () => {
  const h = slackHarness({ env: { PUBLIC_BASE_URL: "https://oauth.example.test/fluck-oauth/" } })
  await slackCallback(h, `code=abc&state=${await state()}`)
  assertEquals(
    h.requests[0].body.get("redirect_uri"),
    "https://oauth.example.test/fluck-oauth/slack/callback",
  )
})

Deno.test("a Slack refusal on HTTP 200 is a bad code, and the link is still spent", async () => {
  for (const error of ["invalid_code", "code_already_used", "bad_redirect_uri"]) {
    const h = slackHarness({ tokenResponse: { ok: false, error } })
    const response = await slackCallback(h, `code=abc&state=${await state()}`)
    assertEquals(response.status, 400)
    const html = await response.text()
    assertStringIncludes(html, PAGES.badCode)
    assertEquals(html.includes(error), false)
    assertEquals(h.claimed.length, 1)
    assertEquals(h.stored, [])
    assertEquals(h.logs, ["slack callback failed: bad_code [ws-abcde]"])
  }
})

Deno.test("a grant without an xoxp user token stores nothing", async () => {
  for (
    const tokenResponse of [
      { ok: true, access_token: "xoxb-bot-only", team: { id: "T0", name: "x" } },
      { ...SLACK_OK, authed_user: { id: "U0MEMBER" } },
      { ...SLACK_OK, authed_user: { id: "U0MEMBER", access_token: "xoxe.xoxp-1-rotating" } },
      {
        ...SLACK_OK,
        authed_user: { id: "U0MEMBER", access_token: XOXP, refresh_token: "xoxe-1-r" },
      },
      { ...SLACK_OK, authed_user: { id: "U0MEMBER", access_token: XOXP, expires_in: 43200 } },
    ]
  ) {
    const h = slackHarness({ tokenResponse })
    const response = await slackCallback(h, `code=abc&state=${await state()}`)
    assertEquals(response.status, 400)
    assertStringIncludes(await response.text(), PAGES.slackNoToken)
    assertEquals(h.stored, [])
  }
})

Deno.test("an unreachable or failing Slack is not reported as a bad code", async () => {
  const cases: Parameters<typeof harness>[0][] = [
    { tokenThrows: true },
    { tokenStatus: 503, tokenResponse: { ok: false, error: "service_unavailable" } },
    { tokenStatus: 429, tokenResponse: { ok: false, error: "ratelimited" } },
    { tokenResponse: { ok: false, error: "internal_error" } },
  ]
  for (const options of cases) {
    const h = slackHarness(options)
    const response = await slackCallback(h, `code=abc&state=${await state()}`)
    assertEquals(response.status, 400)
    assertStringIncludes(await response.text(), PAGES.slackUnreachable)
    assertEquals(h.stored, [])
  }
})

Deno.test("a declined Slack consent never echoes Slack's word for it", async () => {
  const h = slackHarness()
  const response = await slackCallback(h, "error=access_denied&state=whatever")
  assertEquals(response.status, 400)
  const html = await response.text()
  assertStringIncludes(html, PAGES.declined)
  assertEquals(html.includes("access_denied"), false)
  assertEquals(h.claimed, [])
  assertEquals(h.requests, [])
})

Deno.test("a bad, crossed or absent state on the Slack callback spends nothing", async () => {
  const other = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ]) as CryptoKeyPair
  for (
    const query of [
      "code=abc",
      "code=abc&state=not.a.token",
      `state=${await state()}`,
      `code=abc&state=${await state({ uid: "another-user" })}`,
      `code=abc&state=${await state({ signer: other.privateKey })}`,
    ]
  ) {
    const h = slackHarness()
    const response = await slackCallback(h, query)
    assertEquals(response.status, 400)
    assertStringIncludes(await response.text(), PAGES.stale)
    assertEquals(h.claimed, [])
    assertEquals(h.requests, [])
    assertEquals(h.stored, [])
  }
})

Deno.test("a replayed Slack link is refused before the exchange", async () => {
  const h = slackHarness({ claim: "replay" })
  const response = await slackCallback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.replay)
  assertEquals(h.requests, [])
  assertEquals(h.stored, [])
  assertEquals(h.logs, ["slack callback refused: replay [ws-abcde]"])
})

Deno.test("the Slack callback refuses when Slack is not configured", async () => {
  const blanks: Record<string, string>[] = [
    { SLACK_CLIENT_ID: "" },
    { SLACK_CLIENT_SECRET: "" },
    { SLACK_CLIENT_ID: "  " },
    { SLACK_CLIENT_SECRET: "  " },
  ]
  for (const env of blanks) {
    const h = slackHarness({ env })
    const response = await slackCallback(h, `code=abc&state=${await state()}`)
    assertEquals(response.status, 503)
    assertStringIncludes(await response.text(), PAGES.unconfigured)
    assertEquals(h.claimed, [])
    assertEquals(h.logs, ["slack callback unconfigured: client"])
  }
})

Deno.test("a failed Slack store is a 503 and says so", async () => {
  const h = slackHarness({ storeOk: false })
  const response = await slackCallback(h, `code=abc&state=${await state()}`)
  assertEquals(response.status, 503)
  assertStringIncludes(await response.text(), PAGES.storeFailed)
  assertEquals(h.logs, ["slack callback failed: store [ws-abcde]"])
})

Deno.test("a POST to the Slack callback is refused", async () => {
  const h = slackHarness()
  const request = new Request("https://example.test/fluck-oauth/slack/callback", {
    method: "POST",
    body: "x",
  })
  const response = await h.handler(request)
  assertEquals(response.status, 405)
  assertStringIncludes(await response.text(), PAGES.notABrowser)
  assertEquals(request.bodyUsed, true)
})

Deno.test("the Slack account label falls back without naming a person", async () => {
  const h = slackHarness({
    tokenResponse: { ok: true, authed_user: { access_token: XOXP } },
  })
  await slackCallback(h, `code=abc&state=${await state()}`)
  assertEquals(h.stored[0].username, "slack account")
  const teamOnly = slackHarness({
    tokenResponse: { ...SLACK_OK, team: { id: "T0TEAM" }, authed_user: { access_token: XOXP } },
  })
  await slackCallback(teamOnly, `code=abc&state=${await state()}`)
  assertEquals(teamOnly.stored[0].username, "T0TEAM")
})

Deno.test("an Enterprise Grid install is labelled with the org name", async () => {
  const h = slackHarness({
    tokenResponse: {
      ...SLACK_OK,
      is_enterprise_install: true,
      team: null,
      enterprise: { id: "E0ORG", name: "Risa Grid" },
    },
  })
  await slackCallback(h, `code=abc&state=${await state()}`)
  assertEquals(h.stored[0].username, "Risa Grid (U0MEMBER)")
  const unnamed = slackHarness({
    tokenResponse: { ...SLACK_OK, team: null, enterprise: { id: "E0ORG" } },
  })
  await slackCallback(unnamed, `code=abc&state=${await state()}`)
  assertEquals(unnamed.stored[0].username, "E0ORG (U0MEMBER)")
})

Deno.test("exchangeSlackCode reports an unparseable body as unreachable", async () => {
  const result = await exchangeSlackCode(
    { clientId: "id", clientSecret: "secret", code: "c", redirectUri: "https://x.test/cb" },
    (() => Promise.resolve(new Response("<html>gateway</html>"))) as unknown as typeof fetch,
  )
  assert(!result.ok)
  assertEquals(result.reason, "unreachable")
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
