/**
 * Pins the three low-severity hardening follow-ups from issue #852:
 *
 *   1. The detail/versions page resolves visibility through the
 *      *_for_viewer RPCs with the JWT-derived viewer, mirroring /list - an
 *      organisation member can read their own organisation's plugins, while
 *      an anonymous caller keeps getting exactly the public catalogue.
 *   2. The public catalogue routes (/list, /search, /tags/popular and both
 *      download-info routes) are rate limited per client with the same
 *      in-isolate token bucket the organisation function uses.
 *   3. The CORS allowlist admits http://localhost:3000 only in local dev
 *      deployments (or with an explicit opt-in), not in production.
 *
 * Run: deno test --allow-all tests/hardening.test.ts
 */
import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import browse from "../routes/browse.ts"
import download from "../routes/download.ts"
import { pluginStoreCorsOrigins } from "../utils/cors.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

const VIEWER_ID = "11111111-1111-1111-1111-111111111111"

/** Build a structurally valid JWT, as tests/auth-permissions.test.ts does. */
function jwt(sub: string): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b64({ alg: "HS256", typ: "JWT" })}.${b64({ sub })}.sig`
}

/** The row get_plugin_with_stats(_for_viewer) returns, in the RPC's column names. */
const PLUGIN_ROW = {
  id: "22222222-2222-2222-2222-222222222222",
  plugin_id: "com.example.demo",
  display_name: "Demo Plugin",
  description: "A plugin for tests",
  author_id: VIEWER_ID,
  author_name: "member",
  homepage_url: "https://example.test/demo",
  icon_url: null,
  type: "panel",
  api_version: "1.0",
  verified: false,
  published: true,
  created_at: "2026-01-01T00:00:00Z",
  updated_at: "2026-01-01T00:00:00Z",
  latest_version: "1.0.0",
  latest_version_id: "33333333-3333-3333-3333-333333333333",
  avg_rating: "0",
  rating_count: "0",
  download_count: "0",
  tags: [],
  screenshots: [],
  required_permissions: [],
}

interface RpcCall {
  fn: string
  args: Record<string, unknown>
}

/** Records every RPC the code under test makes, like tests/auth-permissions.test.ts. */
function stubSupabase(): { client: SupabaseClient; calls: RpcCall[] } {
  const calls: RpcCall[] = []
  const client = {
    auth: {
      // optionalViewer resolves the caller from the bearer token; any
      // non-empty token is this user, an empty one is nobody.
      getUser: (token: string) =>
        Promise.resolve(
          token
            ? { data: { user: { id: VIEWER_ID, email: "member@test" } }, error: null }
            : { data: { user: null }, error: new Error("no user for empty token") },
        ),
    },
    rpc: (fn: string, args: Record<string, unknown> = {}) => {
      calls.push({ fn, args })
      if (fn === "get_plugin_with_stats" || fn === "get_plugin_with_stats_for_viewer") {
        return Promise.resolve({ data: [PLUGIN_ROW], error: null })
      }
      // get_plugin_versions(_for_viewer), search_plugins(_for_viewer),
      // get_popular_tags: empty answers, which the handlers already accept.
      return Promise.resolve({ data: [], error: null })
    },
  } as unknown as SupabaseClient
  return { client, calls }
}

/** The download-info happy path: an installable plugin whose latest version sits in storage. */
function downloadStubClient(): SupabaseClient {
  const versionRow = {
    id: "33333333-3333-3333-3333-333333333333",
    plugin_id: PLUGIN_ROW.id,
    version: "1.0.0",
    changelog: "",
    min_boss_version: "1.0.0",
    min_ipc_version: "1.0.0",
    min_api_version: "",
    jar_path: "plugins/com.example.demo/1.0.0/com.example.demo-1.0.0.jar",
    jar_size: 4096,
    sha256: "a".repeat(64),
    signature: null,
    dependencies: [],
    published_at: "2026-01-01T00:00:00Z",
  }

  // getLatestVersion chains .select().eq().neq().gt().order().limit().single()
  // (the neq/gt pair is the #912 finalization gate); every step returns the
  // same chain and single() resolves the version row. The stub row is a
  // finalized version, so the passthrough chain satisfies the gate.
  interface VersionQueryChain {
    eq: () => VersionQueryChain
    neq: () => VersionQueryChain
    gt: () => VersionQueryChain
    order: () => VersionQueryChain
    limit: () => VersionQueryChain
    single: () => Promise<{ data: typeof versionRow; error: null }>
  }
  const chain: VersionQueryChain = {
    eq: () => chain,
    neq: () => chain,
    gt: () => chain,
    order: () => chain,
    limit: () => chain,
    single: () => Promise.resolve({ data: versionRow, error: null }),
  }

  const client = {
    auth: {
      getUser: () => Promise.resolve({ data: { user: null }, error: null }),
    },
    rpc: (fn: string) => {
      if (fn === "user_can_install_plugin") return Promise.resolve({ data: true, error: null })
      // record_plugin_download: tracking must not fail the request.
      return Promise.resolve({ data: "download-id", error: null })
    },
    // getPluginForDownload reads `plugins` directly (.select().eq().eq().maybeSingle());
    // getLatestVersion reads `plugin_versions` through the chain above.
    from: (table: string) =>
      table === "plugins"
        ? {
          select: () => {
            const pluginChain = {
              eq: () => pluginChain,
              maybeSingle: () =>
                Promise.resolve({ data: { id: PLUGIN_ROW.id, required_permissions: [] }, error: null }),
            }
            return pluginChain
          },
        }
        : { select: () => chain },
    storage: {
      from: (_bucket: string) => ({
        createSignedUrl: (_path: string, _expiresIn: number) =>
          Promise.resolve({ data: { signedUrl: "https://storage.example.test/signed-jar" }, error: null }),
      }),
    },
  } as unknown as SupabaseClient
  return client
}

/** Mount a route module on a fresh app with the stub client in its context. */
function mountApp(
  routes: OpenAPIHono<{ Variables: PluginStoreContext }>,
  client: SupabaseClient,
): OpenAPIHono<{ Variables: PluginStoreContext }> {
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", routes)
  return app
}

// ---------------------------------------------------------------------------
// Follow-up 1: the detail page resolves visibility for the JWT viewer
// ---------------------------------------------------------------------------

Deno.test("detail page resolves visibility for the JWT viewer, anonymous keeps the public path", async () => {
  const { client, calls } = stubSupabase()
  const app = mountApp(browse, client)

  // Signed in: both lookups go through the *_for_viewer variants naming the
  // caller, the contract /list already has. The no-viewer RPCs resolve for
  // auth.uid() = NULL under the service-role client and 404 an organisation
  // member's own plugins.
  const signedIn = await app.request("/com.example.demo", {
    headers: { Authorization: `Bearer ${jwt(VIEWER_ID)}` },
  })
  assertEquals(signedIn.status, 200)
  // The answer now depends on who asked, so it must not come from a shared cache.
  assertEquals(signedIn.headers.get("Cache-Control"), "private, no-store")
  assertEquals(calls.map((c) => c.fn), [
    "get_plugin_with_stats_for_viewer",
    "get_plugin_versions_for_viewer",
  ])
  assertEquals(calls[0].args, { p_plugin_id: "com.example.demo", p_viewer_id: VIEWER_ID })
  assertEquals(calls[1].args, { p_plugin_id: "com.example.demo", p_viewer_id: VIEWER_ID })

  // Anonymous: the original RPCs, resolving visibility for nobody - exactly
  // what this route did before, which is the public catalogue.
  const anonymous = await app.request("/com.example.demo")
  assertEquals(anonymous.status, 200)
  assertEquals(anonymous.headers.get("Cache-Control"), "public, max-age=60")
  assertEquals(calls.slice(2).map((c) => c.fn), ["get_plugin_with_stats", "get_plugin_versions"])
  assertEquals(calls[2].args, { p_plugin_id: "com.example.demo" })
})

// ---------------------------------------------------------------------------
// Follow-up 2: the public catalogue routes are rate limited per client
// ---------------------------------------------------------------------------

Deno.test("public catalogue and download-info routes are rate limited per client", async () => {
  resetRateLimits()
  const { client } = stubSupabase()
  const app = mountApp(browse, client)
  const flood = { "x-forwarded-for": "203.0.113.9" }

  let last = 200
  for (let i = 0; i < 60; i++) {
    last = (await app.request("/list", { headers: flood })).status
  }
  assertEquals(last, 200, "sixty catalogue requests inside the window are allowed")

  const throttled = await app.request("/list", { headers: flood })
  assertEquals(throttled.status, 429)
  assertEquals(await throttled.json(), { error: "Too many requests; try again later" })
  assertEquals(Number(throttled.headers.get("Retry-After")) > 0, true)

  // One client, one catalogue budget: /search shares the bucket /list just
  // exhausted, so a client cannot multiply its budget across routes.
  const searchThrottled = await app.request("/search", {
    method: "POST",
    headers: { ...flood, "content-type": "application/json" },
    body: JSON.stringify({ query: "demo" }),
  })
  assertEquals(searchThrottled.status, 429)

  // A different client has an independent bucket - a brake per client, not a
  // global switch.
  const otherClient = await app.request("/list", { headers: { "x-forwarded-for": "198.51.100.7" } })
  assertEquals(otherClient.status, 200)

  // The download-info routes carry their own budget and are limited too.
  resetRateLimits()
  const downloadApp = mountApp(download, downloadStubClient())
  let downloadLast = 200
  for (let i = 0; i < 60; i++) {
    downloadLast = (await downloadApp.request("/com.example.demo/download", { headers: flood })).status
  }
  assertEquals(downloadLast, 200, "sixty download-info requests inside the window are allowed")

  const downloadThrottled = await downloadApp.request("/com.example.demo/download", { headers: flood })
  assertEquals(downloadThrottled.status, 429)
  assertEquals(await downloadThrottled.json(), { error: "Too many requests; try again later" })
  assertEquals(Number(downloadThrottled.headers.get("Retry-After")) > 0, true)
})

// #1628: the key used to be the LEFTMOST X-Forwarded-For entry, which is
// whatever the caller sent. Rotating it bought a fresh bucket per request, so
// this flood never saw a 429. The gateway appends the connection it accepted on
// the right, and that hop is what the budget now follows.
Deno.test("rotating the caller-written X-Forwarded-For prefix does not escape the limiter", async () => {
  resetRateLimits()
  const { client } = stubSupabase()
  const app = mountApp(browse, client)
  const from = (i: number) => ({ "x-forwarded-for": `198.18.${i >> 8}.${i & 255}, 203.0.113.9` })

  for (let i = 0; i < 60; i++) {
    assertEquals((await app.request("/list", { headers: from(i) })).status, 200)
  }
  const throttled = await app.request("/list", { headers: from(60) })
  assertEquals(throttled.status, 429, "a new decoy on the left must not buy a new budget")

  // Writing a victim's address on the left does not spend the victim's budget.
  const victim = await app.request("/list", { headers: { "x-forwarded-for": "198.51.100.7" } })
  assertEquals(victim.status, 200)
})

// ---------------------------------------------------------------------------
// Follow-up 3: localhost joins the CORS allowlist only in local dev
// ---------------------------------------------------------------------------

Deno.test("CORS allowlist admits localhost only in local dev or by explicit opt-in", () => {
  const previousUrl = Deno.env.get("SUPABASE_URL")
  const previousOptIn = Deno.env.get("PLUGIN_STORE_ALLOW_LOCALHOST")
  try {
    // A hosted deployment: localhost is not an origin BOSS controls, so with
    // credentials: true it must not be allowed.
    Deno.env.set("SUPABASE_URL", "https://api.risaboss.com")
    Deno.env.delete("PLUGIN_STORE_ALLOW_LOCALHOST")
    assertEquals(pluginStoreCorsOrigins(), ["boss://plugins", "https://risaboss.com"])

    // A local `supabase start` stack: the dev origin is admitted.
    Deno.env.set("SUPABASE_URL", "http://127.0.0.1:54321")
    assertEquals(pluginStoreCorsOrigins(), [
      "boss://plugins",
      "https://risaboss.com",
      "http://localhost:3000",
    ])

    // A hosted deployment whose operator explicitly opted in.
    Deno.env.set("SUPABASE_URL", "https://api.risaboss.com")
    Deno.env.set("PLUGIN_STORE_ALLOW_LOCALHOST", "true")
    assertEquals(pluginStoreCorsOrigins(), [
      "boss://plugins",
      "https://risaboss.com",
      "http://localhost:3000",
    ])
  } finally {
    if (previousUrl === undefined) Deno.env.delete("SUPABASE_URL")
    else Deno.env.set("SUPABASE_URL", previousUrl)
    if (previousOptIn === undefined) Deno.env.delete("PLUGIN_STORE_ALLOW_LOCALHOST")
    else Deno.env.set("PLUGIN_STORE_ALLOW_LOCALHOST", previousOptIn)
  }
})
