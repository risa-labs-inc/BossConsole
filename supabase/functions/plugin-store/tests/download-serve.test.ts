/**
 * Route tests for the artifact serve path (routes/download.ts).
 *
 * The serve path resolves the plugin through get_plugin_for_download, whose
 * WHERE clause IS user_can_install_plugin: publication state and organisation
 * entitlements are enforced inside the RPC, and no row means the same 404 as a
 * missing plugin. What deserves pinning at the route seam:
 *
 *   - the viewer that reaches the serve RPC is the route-resolved caller
 *     (JWT user or API-key owner), not auth.uid() under the service-role
 *     client (which is always NULL, and the bug this path just fixed);
 *   - a refused resolution is an indistinguishable 404 for unentitled
 *     callers, unpublished rows and unknown ids alike;
 *   - requiredPermissions still gates at the route once the RPC has admitted
 *     the row, fails closed for API-key callers (no claim to read), and an
 *     admin claim bypasses it;
 *   - an admitted caller gets a signed URL minted for the version's jar path,
 *     and record_plugin_download receives the caller's identity.
 *
 * Run: deno test --allow-all plugin-store/tests/download-serve.test.ts
 */
import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import download from "../routes/download.ts"

const MEMBER_ID = "21111111-1111-4111-8111-111111111111"
const API_KEY_OWNER_ID = "22222222-2222-4222-8222-222222222222"
const PLUGIN_ROW_UUID = "33333333-3333-4333-8333-333333333333"
const VERSION_ROW_UUID = "44444444-4444-4444-8444-444444444444"
const VALID_KEY = "boss_pk_a1B2c3D4e5F6g6H8i9J0k1L2m3N9o9P9" // 40 chars
const JAR_PATH = "plugins/org.plugin/1.0.0/org.plugin-1.0.0.jar"
const SIGNED_URL = `http://storage.test/signed/${JAR_PATH}?expires=3600`

/** base64url-encoded JSON segments, the shape decodeJwtPayload reads. */
function jwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b64({ alg: "HS256", typ: "JWT" })}.${b64(claims)}.sig`
}

/** The row get_plugin_for_download hands back for an admitted caller. */
function pluginRow(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: PLUGIN_ROW_UUID,
    plugin_id: "org.plugin",
    display_name: "Org Plugin",
    published: true,
    required_permissions: [],
    visibility: "public",
    org_id: null,
    ...overrides,
  }
}

interface StubOptions {
  /** Rows get_plugin_for_download returns; [] refuses the caller. */
  pluginRows?: Array<Record<string, unknown>>
  /** Caller's JWT claims; also drives the Authorization header. */
  claims?: Record<string, unknown> | null
  /** When set, the caller presents an API key instead of a JWT. */
  apiKey?: boolean
  /** Simulate the version lookup finding nothing (PGRST116). */
  missingVersion?: boolean
}

interface StubCalls {
  serve: Array<{ p_plugin_id: string; p_viewer_id: string | null }>
  record: Array<Record<string, unknown>>
}

function stubSupabase(
  opts: StubOptions = {},
): { client: SupabaseClient; calls: StubCalls; headers: Record<string, string> } {
  const calls: StubCalls = { serve: [], record: [] }
  const versionRow = {
    id: VERSION_ROW_UUID,
    plugin_id: PLUGIN_ROW_UUID,
    version: "1.0.0",
    changelog: "",
    min_boss_version: "1.0.0",
    min_ipc_version: "1.0.0",
    min_api_version: "1.0",
    jar_path: JAR_PATH,
    jar_size: 1234,
    sha256: "deadbeef",
    signature: null,
    dependencies: [],
    published_at: "2026-01-01T00:00:00Z",
  }
  const versionOutcome = opts.missingVersion
    ? { data: null, error: { code: "PGRST116", message: "no rows" } }
    : { data: versionRow, error: null }

  const client = {
    auth: {
      // getUserFromToken verifies via this call; the claims it acts on are
      // decoded from the token string itself.
      getUser: (token: string) =>
        token
          ? Promise.resolve({ data: { user: { id: MEMBER_ID, email: "member@test" } }, error: null })
          : Promise.resolve({ data: { user: null }, error: new Error("bad token") }),
    },
    from: (table: string) => {
      if (table === "users") {
        // validateApiKey's follow-up lookup of the key owner's email.
        return {
          select: () => ({
            eq: () => ({
              single: () => Promise.resolve({ data: { email: "key-owner@test" }, error: null }),
            }),
          }),
        }
      }
      // plugin_versions: getLatestVersion chains .select().eq().neq().gt().order()
      // .limit().single() and getVersion chains .select().eq().eq().neq().gt().single()
      // — the neq/gt pair is the #912 finalization gate on upstream/dev. Every step
      // returns the same chain and single() resolves the version row; the stub row
      // is a finalized version, so the passthrough chain satisfies the gate.
      interface VersionQueryChain {
        eq: () => VersionQueryChain
        neq: () => VersionQueryChain
        gt: () => VersionQueryChain
        order: () => VersionQueryChain
        limit: () => VersionQueryChain
        single: () => Promise<typeof versionOutcome>
      }
      const chain: VersionQueryChain = {
        eq: () => chain,
        neq: () => chain,
        gt: () => chain,
        order: () => chain,
        limit: () => chain,
        single: () => Promise.resolve(versionOutcome),
      }
      return { select: () => chain }
    },
    rpc: (fn: string, args: Record<string, unknown>) => {
      if (fn === "get_plugin_for_download") {
        calls.serve.push(args as { p_plugin_id: string; p_viewer_id: string | null })
        return Promise.resolve({ data: opts.pluginRows ?? [], error: null })
      }
      if (fn === "record_plugin_download") {
        calls.record.push(args)
        return Promise.resolve({ data: "download-id", error: null })
      }
      if (fn === "validate_plugin_api_key") {
        return Promise.resolve({
          data: [{ key_id: "key-uuid", user_id: API_KEY_OWNER_ID, scopes: ["plugins"], org_id: null }],
          error: null,
        })
      }
      return Promise.resolve({ data: null, error: null })
    },
    storage: {
      from: () => ({
        createSignedUrl: (path: string, expiresIn: number) =>
          Promise.resolve({
            data: { signedUrl: `http://storage.test/signed/${path}?expires=${expiresIn}` },
            error: null,
          }),
      }),
    },
  } as unknown as SupabaseClient

  const headers: Record<string, string> = {}
  if (opts.claims !== undefined && opts.claims !== null) {
    headers.Authorization = `Bearer ${jwt(opts.claims)}`
  } else if (opts.apiKey) {
    headers["x-api-key"] = VALID_KEY
  }

  return { client, calls, headers }
}

function app(client: SupabaseClient) {
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", download)
  return app
}

Deno.test("an anonymous caller is served a public plugin and the serve RPC sees a NULL viewer", async () => {
  const { client, calls } = stubSupabase({ pluginRows: [pluginRow()] })
  const response = await app(client).request("/org.plugin/download")
  assertEquals(response.status, 200)
  const body = await response.json()
  assertEquals(body.downloadUrl, SIGNED_URL)
  assertEquals(body.sha256, "deadbeef")
  assertEquals(body.version, "1.0.0")
  assertEquals(body.versionId, VERSION_ROW_UUID)
  assertEquals(calls.serve, [{ p_plugin_id: "org.plugin", p_viewer_id: null }])
  assertEquals(calls.record, [{
    p_plugin_id: PLUGIN_ROW_UUID,
    p_version_id: VERSION_ROW_UUID,
    p_user_id: null,
    p_ip_hash: null,
  }])
})

Deno.test("an entitled organisation member is served and their id reaches the serve RPC, not auth.uid()", async () => {
  const { client, calls, headers } = stubSupabase({
    pluginRows: [pluginRow({ visibility: "org" })],
    claims: { sub: MEMBER_ID, user_permissions: [] },
  })
  const response = await app(client).request("/org.plugin/download", { headers })
  assertEquals(response.status, 200)
  assertEquals((await response.json()).downloadUrl, SIGNED_URL)
  assertEquals(calls.serve, [{ p_plugin_id: "org.plugin", p_viewer_id: MEMBER_ID }])
  assertEquals(calls.record[0].p_user_id, MEMBER_ID)
})

Deno.test("the serve RPC refusing the row is the same 404 for unentitled callers, unpublished rows and unknown ids", async () => {
  const { client, calls, headers } = stubSupabase({
    pluginRows: [],
    claims: { sub: MEMBER_ID, user_permissions: [] },
  })
  for (const path of ["/org.plugin/download", "/org.plugin/download/1.0.0"]) {
    const response = await app(client).request(path, { headers })
    assertEquals(response.status, 404)
    assertEquals(await response.json(), { error: "Plugin not found" })
  }
  assertEquals(calls.serve, [
    { p_plugin_id: "org.plugin", p_viewer_id: MEMBER_ID },
    { p_plugin_id: "org.plugin", p_viewer_id: MEMBER_ID },
  ])
  assertEquals(calls.record.length, 0)
})

Deno.test("requiredPermissions still gates after the RPC admits the row", async () => {
  const gated = pluginRow({ required_permissions: ["dangerous.permission"] })
  // Missing the claim => 403, not a URL.
  const missing = stubSupabase({ pluginRows: [gated], claims: { sub: MEMBER_ID, user_permissions: [] } })
  assertEquals(
    (await app(missing.client).request("/org.plugin/download", { headers: missing.headers })).status,
    403,
  )
  // Holding the claim => 200.
  const held = stubSupabase({
    pluginRows: [gated],
    claims: { sub: MEMBER_ID, user_permissions: ["dangerous.permission"] },
  })
  assertEquals(
    (await app(held.client).request("/org.plugin/download", { headers: held.headers })).status,
    200,
  )
  // An admin claim bypasses the permission gate.
  const admin = stubSupabase({ pluginRows: [gated], claims: { sub: MEMBER_ID, is_admin: true } })
  assertEquals(
    (await app(admin.client).request("/org.plugin/download", { headers: admin.headers })).status,
    200,
  )
})

Deno.test("an API-key caller is resolved to the key owner and fails closed on requiredPermissions", async () => {
  // Open plugin: the key owner is served, and the owner id is the viewer.
  const open = stubSupabase({ pluginRows: [pluginRow()], apiKey: true })
  const response = await app(open.client).request("/org.plugin/download", { headers: open.headers })
  assertEquals(response.status, 200)
  assertEquals(open.calls.serve, [{ p_plugin_id: "org.plugin", p_viewer_id: API_KEY_OWNER_ID }])
  // Permission-gated plugin: no JWT means no claim to read, so 403 not 200.
  const gated = stubSupabase({
    pluginRows: [pluginRow({ required_permissions: ["dangerous.permission"] })],
    apiKey: true,
  })
  const denied = await app(gated.client).request("/org.plugin/download", { headers: gated.headers })
  assertEquals(denied.status, 403)
})

Deno.test("an admitted caller with no matching version gets the version 404, not a URL", async () => {
  const { client, headers } = stubSupabase({
    pluginRows: [pluginRow()],
    claims: { sub: MEMBER_ID },
    missingVersion: true,
  })
  const latest = await app(client).request("/org.plugin/download", { headers })
  assertEquals(latest.status, 404)
  assertEquals(await latest.json(), { error: "No versions available" })
  const pinned = await app(client).request("/org.plugin/download/9.9.9", { headers })
  assertEquals(pinned.status, 404)
  assertEquals(await pinned.json(), { error: "Version not found" })
})
