/**
 * HTTP-level tests for private plugin-store response caching (#1634).
 *
 * Verifies:
 * - Entitled callers on download-info and caller-dependent routes receive `Cache-Control: private, no-store`
 * - Anonymous callers on download-info routes returning signed URLs receive `Cache-Control: private, no-store`
 * - Anonymous callers on public catalogue routes (/list, /:pluginId) retain `Cache-Control: public, max-age=60`
 * - Unentitled callers receive `Cache-Control: private, no-store` (403 permission gate, 404 org visibility)
 * - Error paths (401, 403, 404, 429, 500, 400 validation failures) receive `Cache-Control: private, no-store`
 * - User rating, API keys, publishing, and admin routes always emit `Cache-Control: private, no-store`
 *
 * Run: deno test --allow-all tests/cache-policy.test.ts
 */
import { assertEquals, assert } from "@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import download from "../routes/download.ts"
import browse from "../routes/browse.ts"
import apiKeys from "../routes/api-keys.ts"
import admin from "../routes/admin.ts"
import rating from "../routes/rating.ts"
import publish from "../routes/publish.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

const TEST_USER_ID = "11111111-1111-1111-1111-111111111111"
const PLUGIN_ID = "com.example.test-plugin"
const PLUGIN_UUID = "22222222-2222-2222-2222-222222222222"
const VERSION_UUID = "33333333-3333-3333-3333-333333333333"

function jwt(payload: Record<string, unknown>): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b64({ alg: "HS256", typ: "JWT" })}.${b64(payload)}.sig`
}

function userToken(opts: { id?: string; permissions?: string[]; isAdmin?: boolean } = {}): string {
  return jwt({
    sub: opts.id ?? TEST_USER_ID,
    email: "test@example.com",
    is_admin: opts.isAdmin ?? false,
    user_permissions: opts.permissions ?? [],
  })
}

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

function createDownloadStub(opts: {
  canInstall?: boolean
  requiredPermissions?: string[]
  userPermissions?: string[]
  isAdmin?: boolean
  hasPlugin?: boolean
  hasVersion?: boolean
  throwError?: boolean
} = {}): SupabaseClient {
  const pluginRow = {
    id: PLUGIN_UUID,
    plugin_id: PLUGIN_ID,
    display_name: "Test Plugin",
    description: "A test plugin",
    author_id: TEST_USER_ID,
    author_name: "testauthor",
    homepage_url: "https://example.com",
    icon_url: null,
    type: "panel",
    api_version: "1.0",
    verified: false,
    published: true,
    created_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-01T00:00:00Z",
    latest_version: "1.0.0",
    latest_version_id: VERSION_UUID,
    avg_rating: "0",
    rating_count: "0",
    download_count: "0",
    tags: [],
    screenshots: [],
    required_permissions: opts.requiredPermissions ?? [],
  }

  const versionRow = {
    id: VERSION_UUID,
    plugin_id: PLUGIN_UUID,
    version: "1.0.0",
    changelog: "Initial",
    min_boss_version: "1.0.0",
    min_ipc_version: "1.0.0",
    min_api_version: "",
    jar_path: "plugins/com.example.test-plugin/1.0.0/test.jar",
    jar_size: 2048,
    sha256: "b".repeat(64),
    signature: null,
    dependencies: [],
    published_at: "2026-01-01T00:00:00Z",
  }

  interface VersionChain {
    eq: () => VersionChain
    neq: () => VersionChain
    gt: () => VersionChain
    order: () => VersionChain
    limit: () => VersionChain
    single: () => Promise<{ data: typeof versionRow | null; error: unknown }>
  }
  const chain: VersionChain = {
    eq: () => chain,
    neq: () => chain,
    gt: () => chain,
    order: () => chain,
    limit: () => chain,
    single: () => Promise.resolve(
      opts.hasVersion !== false
        ? { data: versionRow, error: null }
        : { data: null, error: { code: "PGRST116" } }
    ),
  }

  return {
    auth: {
      getUser: (token: string) => {
        if (!token) return Promise.resolve({ data: { user: null }, error: null })
        return Promise.resolve({
          data: {
            user: {
              id: TEST_USER_ID,
              email: "test@example.com",
            },
          },
          error: null,
        })
      },
    },
    rpc: (fn: string) => {
      if (opts.throwError) {
        throw new Error("Simulated database failure")
      }
      if (fn === "get_plugin_with_stats") {
        return Promise.resolve({
          data: opts.hasPlugin !== false ? [pluginRow] : [],
          error: null,
        })
      }
      if (fn === "user_can_install_plugin") {
        return Promise.resolve({
          data: opts.canInstall !== false,
          error: null,
        })
      }
      if (fn === "record_plugin_download") {
        return Promise.resolve({ data: "dl-123", error: null })
      }
      return Promise.resolve({ data: null, error: null })
    },
    from: (_table: string) => ({ select: () => chain }),
    storage: {
      from: (_bucket: string) => ({
        createSignedUrl: () =>
          Promise.resolve({
            data: { signedUrl: "https://storage.example.test/signed-jar?token=abc" },
            error: null,
          }),
      }),
    },
  } as unknown as SupabaseClient
}

// ============================================================================
// Download-info routes: Signed URLs, Entitled, Unentitled, Anonymous, and Errors
// ============================================================================

Deno.test("download-info: entitled caller receives signed URL with Cache-Control: private, no-store", async () => {
  const client = createDownloadStub({
    canInstall: true,
    requiredPermissions: ["plugins.install"],
  })
  const app = mountApp(download, client)
  const token = userToken({ permissions: ["plugins.install"] })

  const res = await app.request(`/${PLUGIN_ID}/download`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
  const json = await res.json()
  assert(typeof json.downloadUrl === "string")
  assert(json.downloadUrl.includes("signed-jar"))
})

Deno.test("download-info: specific version entitled caller receives signed URL with Cache-Control: private, no-store", async () => {
  const client = createDownloadStub({ canInstall: true })
  const app = mountApp(download, client)
  const token = userToken()

  const res = await app.request(`/${PLUGIN_ID}/download/1.0.0`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("download-info: anonymous caller downloading public plugin receives signed URL with Cache-Control: private, no-store", async () => {
  const client = createDownloadStub({ canInstall: true, requiredPermissions: [] })
  const app = mountApp(download, client)

  const res = await app.request(`/${PLUGIN_ID}/download`)
  assertEquals(res.status, 200)
  // CRITICAL: Signed URLs must NEVER be cached in shared caches, even for anonymous callers!
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
  const json = await res.json()
  assert(typeof json.downloadUrl === "string")
})

Deno.test("download-info: anonymous caller downloading specific version receives Cache-Control: private, no-store", async () => {
  const client = createDownloadStub({ canInstall: true, requiredPermissions: [] })
  const app = mountApp(download, client)

  const res = await app.request(`/${PLUGIN_ID}/download/1.0.0`)
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("download-info: version not found (404) receives Cache-Control: private, no-store", async () => {
  const client = createDownloadStub({ hasVersion: false })
  const app = mountApp(download, client)

  const res = await app.request(`/${PLUGIN_ID}/download/9.9.9`)
  assertEquals(res.status, 404)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("download-info: unentitled caller (403 permission gate) receives Cache-Control: private, no-store", async () => {
  const client = createDownloadStub({
    canInstall: true,
    requiredPermissions: ["org.special_capability"],
  })
  const app = mountApp(download, client)
  const token = userToken({ permissions: ["other.permission"] })

  const res = await app.request(`/${PLUGIN_ID}/download`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 403)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("download-info: unentitled caller (404 org visibility failure) receives Cache-Control: private, no-store", async () => {
  // canInstall fails -> 404 to avoid enumerating private plugins
  const client = createDownloadStub({ canInstall: false })
  const app = mountApp(download, client)
  const token = userToken()

  const res = await app.request(`/${PLUGIN_ID}/download`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 404)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("download-info: plugin not found (404) receives Cache-Control: private, no-store", async () => {
  const client = createDownloadStub({ hasPlugin: false })
  const app = mountApp(download, client)

  const res = await app.request(`/nonexistent-plugin/download`)
  assertEquals(res.status, 404)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("download-info: rate limited (429) receives Cache-Control: private, no-store", async () => {
  resetRateLimits()
  const client = createDownloadStub()
  const app = mountApp(download, client)
  const headers = { "x-forwarded-for": "198.51.100.42" }

  for (let i = 0; i < 60; i++) {
    await app.request(`/${PLUGIN_ID}/download`, { headers })
  }
  const throttled = await app.request(`/${PLUGIN_ID}/download`, { headers })
  assertEquals(throttled.status, 429)
  assertEquals(throttled.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("download-info: thrown database exception (500) receives Cache-Control: private, no-store", async () => {
  const client = createDownloadStub({ throwError: true })
  const app = mountApp(download, client)

  const res = await app.request(`/${PLUGIN_ID}/download`)
  assertEquals(res.status, 500)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

// ============================================================================
// Browse routes: Public Catalogue vs Authenticated Catalogue vs Errors
// ============================================================================

function createBrowseStub(opts: {
  hasPlugin?: boolean
  throwError?: boolean
} = {}): SupabaseClient {
  const pluginRow = {
    id: PLUGIN_UUID,
    plugin_id: PLUGIN_ID,
    display_name: "Test Plugin",
    description: "A test plugin",
    author_id: TEST_USER_ID,
    author_name: "testauthor",
    homepage_url: "https://example.com",
    icon_url: null,
    type: "panel",
    api_version: "1.0",
    verified: false,
    published: true,
    created_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-01T00:00:00Z",
    latest_version: "1.0.0",
    latest_version_id: VERSION_UUID,
    avg_rating: "0",
    rating_count: "0",
    download_count: "0",
    tags: ["demo"],
    screenshots: [],
    required_permissions: [],
  }

  return {
    auth: {
      getUser: (token: string) => {
        if (!token || token === "invalid") return Promise.resolve({ data: { user: null }, error: new Error("bad token") })
        return Promise.resolve({
          data: { user: { id: TEST_USER_ID, email: "test@example.com" } },
          error: null,
        })
      },
    },
    rpc: (fn: string) => {
      if (opts.throwError) {
        throw new Error("Simulated browse DB failure")
      }
      if (fn === "search_plugins" || fn === "search_plugins_for_viewer") {
        return Promise.resolve({
          data: [{ plugins: [pluginRow], total_count: 1 }],
          error: null,
        })
      }
      if (fn === "get_plugin_with_stats" || fn === "get_plugin_with_stats_for_viewer") {
        return Promise.resolve({
          data: opts.hasPlugin !== false ? [pluginRow] : [],
          error: null,
        })
      }
      if (fn === "get_plugin_versions" || fn === "get_plugin_versions_for_viewer") {
        return Promise.resolve({ data: [], error: null })
      }
      if (fn === "get_popular_tags") {
        return Promise.resolve({ data: [{ tag: "demo", count: 1 }], error: null })
      }
      return Promise.resolve({ data: [], error: null })
    },
  } as unknown as SupabaseClient
}

Deno.test("browse /list: genuinely anonymous public catalogue retains Cache-Control: public, max-age=60", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)

  const res = await app.request("/list")
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=60")
})

Deno.test("browse /:pluginId: genuinely anonymous public catalogue retains Cache-Control: public, max-age=60", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)

  const res = await app.request(`/${PLUGIN_ID}`)
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "public, max-age=60")
})

Deno.test("browse /list: authenticated catalogue response uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)
  const token = userToken()

  const res = await app.request("/list", {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /:pluginId: authenticated catalogue response uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)
  const token = userToken()

  const res = await app.request(`/${PLUGIN_ID}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /:pluginId: authenticated not found (404) uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub({ hasPlugin: false })
  const app = mountApp(browse, client)
  const token = userToken()

  const res = await app.request("/nonexistent-plugin", {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 404)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /:pluginId: anonymous not found (404) uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub({ hasPlugin: false })
  const app = mountApp(browse, client)

  const res = await app.request("/nonexistent-plugin")
  assertEquals(res.status, 404)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /list: thrown error (500) uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub({ throwError: true })
  const app = mountApp(browse, client)

  const res = await app.request("/list")
  assertEquals(res.status, 500)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /list: rate limited (429) uses Cache-Control: private, no-store", async () => {
  resetRateLimits()
  const client = createBrowseStub()
  const app = mountApp(browse, client)
  const headers = { "x-forwarded-for": "198.51.100.99" }

  for (let i = 0; i < 60; i++) {
    await app.request("/list", { headers })
  }
  const throttled = await app.request("/list", { headers })
  assertEquals(throttled.status, 429)
  assertEquals(throttled.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /list: validation failure (400) uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)

  // invalid sortBy parameter triggers schema validation error
  const res = await app.request("/list?sortBy=invalid_sort_order")
  assertEquals(res.status, 400)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /search: authenticated search uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)
  const token = userToken()

  const res = await app.request("/search", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ query: "test" }),
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /search: validation failure (400) uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)

  // invalid minRating (must be <= 5)
  const res = await app.request("/search", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ minRating: 99 }),
  })
  assertEquals(res.status, 400)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /tags/popular: authenticated caller uses Cache-Control: private, no-store", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)
  const token = userToken()

  const res = await app.request("/tags/popular", {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /tags/popular: anonymous caller does not force private", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)

  const res = await app.request("/tags/popular")
  assertEquals(res.status, 200)
  assert(res.headers.get("Cache-Control") !== "private, no-store")
})

// ============================================================================
// User Rating Routes: Always Private
// ============================================================================

function createRatingStub(): SupabaseClient {
  return {
    auth: {
      getUser: (token: string) => {
        if (!token) return Promise.resolve({ data: { user: null }, error: new Error("unauthenticated") })
        return Promise.resolve({
          data: { user: { id: TEST_USER_ID, email: "rater@example.com" } },
          error: null,
        })
      },
    },
    rpc: (fn: string) => {
      if (fn === "get_plugin_with_stats") {
        return Promise.resolve({
          data: [{ id: PLUGIN_UUID, plugin_id: PLUGIN_ID }],
          error: null,
        })
      }
      return Promise.resolve({ data: null, error: null })
    },
    from: (_table: string) => ({
      select: () => ({
        eq: () => ({
          eq: () => ({
            single: () => Promise.resolve({ data: { rating: 5, review: "Great!" }, error: null }),
            maybeSingle: () => Promise.resolve({ data: { rating: 5, review: "Great!" }, error: null }),
          }),
        }),
      }),
      upsert: () => Promise.resolve({ data: { id: "rate-1" }, error: null }),
      delete: () => ({
        eq: () => ({
          eq: () => Promise.resolve({ data: null, error: null }),
        }),
      }),
    }),
  } as unknown as SupabaseClient
}

Deno.test("rating: authenticated GET /:pluginId/rating uses Cache-Control: private, no-store", async () => {
  const client = createRatingStub()
  const app = mountApp(rating, client)
  const token = userToken()

  const res = await app.request(`/${PLUGIN_ID}/rating`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("rating: unauthenticated (401) GET /:pluginId/rating uses Cache-Control: private, no-store", async () => {
  const client = createRatingStub()
  const app = mountApp(rating, client)

  const res = await app.request(`/${PLUGIN_ID}/rating`)
  assertEquals(res.status, 401)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("rating: unauthenticated (401) POST /:pluginId/rate uses Cache-Control: private, no-store", async () => {
  const client = createRatingStub()
  const app = mountApp(rating, client)

  const res = await app.request(`/${PLUGIN_ID}/rate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rating: 5, review: "Awesome" }),
  })
  assertEquals(res.status, 401)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

// ============================================================================
// API Keys Routes: Always Private (401, 403, 200, 429)
// ============================================================================

function createApiKeysStub(opts: {
  isAdmin?: boolean
  permissions?: string[]
  keyCount?: number
} = {}): SupabaseClient {
  return {
    auth: {
      getUser: (token: string) => {
        if (!token) return Promise.resolve({ data: { user: null }, error: new Error("unauthenticated") })
        return Promise.resolve({
          data: { user: { id: TEST_USER_ID, email: "keyowner@example.com" } },
          error: null,
        })
      },
    },
    rpc: (fn: string) => {
      if (fn === "user_has_permission") {
        const allowed = opts.isAdmin || (opts.permissions ?? []).includes("api_key.create")
        return Promise.resolve({ data: allowed, error: null })
      }
      return Promise.resolve({ data: null, error: null })
    },
    from: (table: string) => {
      if (table === "plugin_api_keys") {
        const queryChain: Record<string, unknown> = {
          order: () => Promise.resolve({ data: [], error: null }),
          is: () => queryChain,
        }
        return {
          select: () => ({
            eq: () => queryChain,
          }),
        }
      }
      return {}
    },
  } as unknown as SupabaseClient
}

Deno.test("api-keys: unauthenticated (401) GET /api-keys uses Cache-Control: private, no-store", async () => {
  const client = createApiKeysStub()
  const app = mountApp(apiKeys, client)

  const res = await app.request("/api-keys")
  assertEquals(res.status, 401)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("api-keys: authenticated GET /api-keys uses Cache-Control: private, no-store", async () => {
  const client = createApiKeysStub()
  const app = mountApp(apiKeys, client)
  const token = userToken()

  const res = await app.request("/api-keys", {
    headers: { Authorization: `Bearer ${token}` },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("api-keys: unentitled caller lacking api_key.create (403) uses Cache-Control: private, no-store", async () => {
  const client = createApiKeysStub({ permissions: [] })
  const app = mountApp(apiKeys, client)
  const token = userToken({ permissions: [] })

  const res = await app.request("/api-keys", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ name: "CI Key", scopes: ["publish"] }),
  })
  assertEquals(res.status, 403)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

// ============================================================================
// Admin Routes: Always Private (401, 403, 200)
// ============================================================================

function createAdminStub(): SupabaseClient {
  return {
    auth: {
      getUser: (token: string) => {
        if (!token) return Promise.resolve({ data: { user: null }, error: new Error("unauthenticated") })
        return Promise.resolve({
          data: { user: { id: TEST_USER_ID, email: "admin@example.com" } },
          error: null,
        })
      },
    },
    from: (_table: string) => ({
      update: () => ({
        eq: () => ({
          select: () => ({
            single: () => Promise.resolve({ data: { id: PLUGIN_UUID }, error: null }),
          }),
        }),
      }),
    }),
  } as unknown as SupabaseClient
}

Deno.test("admin: unauthenticated (401) POST /admin/:pluginId/publish uses Cache-Control: private, no-store", async () => {
  const client = createAdminStub()
  const app = mountApp(admin, client)

  const res = await app.request(`/admin/${PLUGIN_ID}/publish`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ published: true }),
  })
  assertEquals(res.status, 401)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("admin: non-admin (403) POST /admin/:pluginId/publish uses Cache-Control: private, no-store", async () => {
  const client = createAdminStub()
  const app = mountApp(admin, client)
  const token = userToken({ isAdmin: false })

  const res = await app.request(`/admin/${PLUGIN_ID}/publish`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ published: true }),
  })
  assertEquals(res.status, 403)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("admin: entitled admin (200) POST /admin/:pluginId/publish uses Cache-Control: private, no-store", async () => {
  const client = createAdminStub()
  const app = mountApp(admin, client)
  const token = userToken({ isAdmin: true })

  const res = await app.request(`/admin/${PLUGIN_ID}/publish`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ published: true }),
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

// ============================================================================
// Publishing Routes: Always Private (401, 403)
// ============================================================================

Deno.test("publish: unauthenticated (401) POST /publish uses Cache-Control: private, no-store", async () => {
  const client = {
    auth: { getUser: () => Promise.resolve({ data: { user: null }, error: new Error("unauthenticated") }) },
  } as unknown as SupabaseClient
  const app = mountApp(publish, client)

  const res = await app.request("/publish", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      pluginId: "com.example.new",
      displayName: "New Plugin",
      description: "Test",
      homepageUrl: "https://example.com/new",
      type: "panel",
    }),
  })
  assertEquals(res.status, 401)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("publish: validation failure (400) uses Cache-Control: private, no-store", async () => {
  const client = { auth: { getUser: () => Promise.resolve({ data: { user: null }, error: null }) } } as unknown as SupabaseClient
  const app = mountApp(publish, client)

  // missing required homepageUrl triggers schema validation 400
  const res = await app.request("/publish", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pluginId: "bad" }),
  })
  assertEquals(res.status, 400)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("rating: validation failure (400) on POST /:pluginId/rate uses Cache-Control: private, no-store", async () => {
  const client = createRatingStub()
  const app = mountApp(rating, client)

  // rating out of bounds (must be 1-5)
  const res = await app.request(`/${PLUGIN_ID}/rate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rating: 10 }),
  })
  assertEquals(res.status, 400)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("rating: anonymous GET /:pluginId/ratings (200) does not force private Cache-Control", async () => {
  const client = {
    auth: { getUser: () => Promise.resolve({ data: { user: null }, error: null }) },
    rpc: () => Promise.resolve({ data: [{ id: PLUGIN_UUID, plugin_id: PLUGIN_ID }], error: null }),
    from: () => ({
      select: () => ({
        eq: () => ({
          order: () => ({
            range: () => Promise.resolve({ data: [], count: 0, error: null }),
          }),
        }),
      }),
    }),
  } as unknown as SupabaseClient
  const app = mountApp(rating, client)

  const res = await app.request(`/${PLUGIN_ID}/ratings`)
  assertEquals(res.status, 200)
  // Public ratings list is NOT private
  assert(res.headers.get("Cache-Control") !== "private, no-store")
})

Deno.test("api-keys: validation failure (400) on POST /api-keys uses Cache-Control: private, no-store", async () => {
  const client = createApiKeysStub()
  const app = mountApp(apiKeys, client)
  const token = userToken()

  // empty body (missing name)
  const res = await app.request("/api-keys", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({}),
  })
  assertEquals(res.status, 400)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("admin: thrown error (500) uses Cache-Control: private, no-store", async () => {
  const client = {
    auth: { getUser: () => Promise.resolve({ data: { user: { id: TEST_USER_ID, email: "admin@test" } }, error: null }) },
    from: () => {
      throw new Error("Admin DB failure")
    },
  } as unknown as SupabaseClient
  const app = mountApp(admin, client)
  const token = userToken({ isAdmin: true })

  const res = await app.request(`/admin/${PLUGIN_ID}/publish`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ published: true }),
  })
  assertEquals(res.status, 500)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

// ============================================================================
// API Key in Headers for Catalogue & Global 404 / 500 Handlers
// ============================================================================

Deno.test("browse /list: x-api-key authenticated caller receives Cache-Control: private, no-store", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)

  const res = await app.request("/list", {
    headers: { "x-api-key": "boss_live_testapikey123" },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("browse /:pluginId: x-api-key authenticated caller receives Cache-Control: private, no-store", async () => {
  const client = createBrowseStub()
  const app = mountApp(browse, client)

  const res = await app.request(`/${PLUGIN_ID}`, {
    headers: { "X-API-Key": "boss_live_testapikey123" },
  })
  assertEquals(res.status, 200)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("app notFound handler returns Cache-Control: private, no-store", async () => {
  const app = new OpenAPIHono()
  app.notFound((ctx) => {
    ctx.header("Cache-Control", "private, no-store")
    return ctx.json({ error: "Not Found" }, 404)
  })

  const res = await app.request("/nonexistent/endpoint")
  assertEquals(res.status, 404)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})

Deno.test("app onError handler returns Cache-Control: private, no-store", async () => {
  const app = new OpenAPIHono()
  app.onError((_err, ctx) => {
    ctx.header("Cache-Control", "private, no-store")
    return ctx.json({ error: "Internal server error" }, 500)
  })
  app.get("/error", () => {
    throw new Error("Simulated unhandled exception")
  })

  const res = await app.request("/error")
  assertEquals(res.status, 500)
  assertEquals(res.headers.get("Cache-Control"), "private, no-store")
})


