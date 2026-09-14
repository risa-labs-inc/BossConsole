/**
 * Viewer scoping for the service-role-backed plugin store.
 *
 * The edge function cannot rely on auth.uid(): its Supabase client uses the
 * service role. These tests pin the explicit viewer RPC contracts and the
 * route wiring that supplies the verified session subject.
 *
 * Run: deno test --allow-all tests/viewer-scoping.test.ts
 */

import { assert, assertEquals, assertRejects } from "@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import {
  getPlugin,
  getPluginForDownload,
  searchPlugins,
} from "../services/plugins.ts"
import { getPluginVersions } from "../services/versions.ts"
import { getOptionalViewer } from "../utils/viewer.ts"

const VIEWER = "11111111-1111-4111-8111-111111111111"
const PLUGIN_UUID = "22222222-2222-4222-8222-222222222222"

interface RpcCall {
  name: string
  args: Record<string, unknown>
}

interface RpcResponse {
  data: unknown
  error: { message: string } | null
}

function stub(responses: Record<string, RpcResponse>): {
  client: SupabaseClient
  calls: RpcCall[]
} {
  const calls: RpcCall[] = []
  const client = {
    rpc: (name: string, args: Record<string, unknown>) => {
      calls.push({ name, args })
      const response = responses[name]
      if (!response) throw new Error(`unexpected RPC ${name}`)
      return Promise.resolve(response)
    },
  }
  return { client: client as unknown as SupabaseClient, calls }
}

const detailRow = {
  id: PLUGIN_UUID,
  plugin_id: "org.example.plugin",
  display_name: "Organisation Plugin",
  description: "A fixture",
  author_id: VIEWER,
  author_name: "Author",
  homepage_url: "https://example.test/plugin",
  icon_url: "https://example.test/icon.png",
  type: "panel",
  api_version: "1.0.0",
  verified: true,
  published: true,
  created_at: "2026-01-01T00:00:00Z",
  updated_at: "2026-01-01T00:00:00Z",
  latest_version: "1.2.0",
  latest_version_id: "33333333-3333-4333-8333-333333333333",
  avg_rating: "4.5",
  rating_count: 2,
  download_count: 3,
  tags: ["org"],
  screenshots: [],
  required_permissions: ["plugin.read"],
}

Deno.test("viewer-aware browse services select explicit viewer RPCs", async () => {
  const { client, calls } = stub({
    search_plugins_for_viewer: { data: [{ plugins: [], total_count: 0 }], error: null },
    get_plugin_with_stats_for_viewer: { data: [detailRow], error: null },
    get_plugin_versions_for_viewer: {
      data: [{
        id: "33333333-3333-4333-8333-333333333333",
        version: "1.2.0",
        changelog: "Initial",
        min_boss_version: "1.0.0",
        min_ipc_version: "1.0.0",
        min_api_version: "",
        jar_path: "plugins/org.example.plugin.jar",
        jar_size: 10,
        sha256: "hash",
        dependencies: [],
        published_at: "2026-01-01T00:00:00Z",
        download_count: 1,
      }],
      error: null,
    },
  })

  await searchPlugins(client, "org", null, null, 0, false, 2, 10, "name", VIEWER)
  const plugin = await getPlugin(client, "org.example.plugin", VIEWER)
  const versions = await getPluginVersions(client, "org.example.plugin", VIEWER)

  assertEquals(plugin?.id, PLUGIN_UUID)
  assertEquals(versions[0]?.pluginId, "org.example.plugin")
  assertEquals(calls, [
    {
      name: "search_plugins_for_viewer",
      args: {
        p_viewer_id: VIEWER,
        p_query: "org",
        p_type: null,
        p_tags: null,
        p_min_rating: 0,
        p_verified_only: false,
        p_page: 2,
        p_page_size: 10,
        p_sort_by: "name",
      },
    },
    {
      name: "get_plugin_with_stats_for_viewer",
      args: { p_plugin_id: "org.example.plugin", p_viewer_id: VIEWER },
    },
    {
      name: "get_plugin_versions_for_viewer",
      args: { p_plugin_id: "org.example.plugin", p_viewer_id: VIEWER },
    },
  ])
})

Deno.test("anonymous browse services keep the public RPCs", async () => {
  const { client, calls } = stub({
    search_plugins: { data: [{ plugins: [], total_count: 0 }], error: null },
    get_plugin_with_stats: { data: [detailRow], error: null },
    get_plugin_versions: { data: [], error: null },
  })

  await searchPlugins(client, "", null, null, 0, false, 1, 20, "downloads")
  await getPlugin(client, "org.example.plugin")
  await getPluginVersions(client, "org.example.plugin")

  assertEquals(calls.map((call) => call.name), [
    "search_plugins",
    "get_plugin_with_stats",
    "get_plugin_versions",
  ])
})

Deno.test("download lookup returns only install metadata and preserves permission types", async () => {
  const { client, calls } = stub({
    get_plugin_install_info_for_viewer: {
      data: [{
        id: PLUGIN_UUID,
        required_permissions: ["plugin.read", 42, null],
      }],
      error: null,
    },
  })

  assertEquals(
    await getPluginForDownload(client, "org.example.plugin", VIEWER),
    { id: PLUGIN_UUID, requiredPermissions: ["plugin.read"] },
  )
  assertEquals(calls, [{
    name: "get_plugin_install_info_for_viewer",
    args: { p_plugin_id: "org.example.plugin", p_viewer_id: VIEWER },
  }])
})

Deno.test("download lookup fails closed when its authorization RPC errors", async () => {
  const { client } = stub({
    get_plugin_install_info_for_viewer: {
      data: null,
      error: { message: "connection reset" },
    },
  })

  await assertRejects(
    () => getPluginForDownload(client, "org.example.plugin", VIEWER),
    Error,
    "Plugin install lookup unavailable",
  )
})

Deno.test("optional viewer resolution is quiet and never treats an API key as a session", async () => {
  const tokens: string[] = []
  const client = {
    auth: {
      getUser: (token: string) => {
        tokens.push(token)
        return Promise.resolve({ data: { user: { id: VIEWER } }, error: null })
      },
    },
  } as unknown as SupabaseClient

  assertEquals(await getOptionalViewer(client, undefined), null)
  assertEquals(await getOptionalViewer(client, "X-API-Key"), null)
  assertEquals(await getOptionalViewer(client, "Bearer session-token"), VIEWER)
  assertEquals(tokens, ["session-token"])
})

Deno.test("optional viewer resolution rejects an auth response carrying an error", async () => {
  const client = {
    auth: {
      getUser: () => Promise.resolve({
        data: { user: { id: VIEWER } },
        error: new Error("expired"),
      }),
    },
  } as unknown as SupabaseClient

  assertEquals(await getOptionalViewer(client, "Bearer expired-session"), null)
})

Deno.test("browse and rating routes pass the optional viewer to detail lookups", () => {
  const source = (name: string) =>
    Deno.readTextFileSync(new URL(`../routes/${name}`, import.meta.url))
  const browse = source("browse.ts")
  const rating = source("rating.ts")

  assertEquals(
    browse.match(/getOptionalViewer\(supabase, ctx\.req\.header\("Authorization"\)\)/g)?.length ?? 0,
    3,
  )
  assert(
    /searchPlugins\([\s\S]*?body\.sortBy,\s*viewer\s*\)/.test(browse),
    "search passes the authenticated viewer to the RPC wrapper",
  )
  assert(
    /getPlugin\(supabase,\s*pluginId,\s*viewer\)/.test(browse),
    "detail passes the authenticated viewer to the RPC wrapper",
  )
  assert(
    /getPluginVersions\(supabase,\s*pluginId,\s*viewer\)/.test(browse),
    "version metadata uses the same viewer scope as plugin detail",
  )

  assertEquals(rating.match(/getPlugin\(supabase, pluginId, user\.userId\)/g)?.length ?? 0, 3)
  assert(
    rating.includes("getOptionalViewer(supabase, ctx.req.header('Authorization'))") &&
      rating.includes("getPlugin(supabase, pluginId, viewer)"),
    "public ratings use the same viewer-aware plugin lookup",
  )
})

Deno.test("viewer-scoped GETs preserve CORS Vary and partition anonymous cache entries", async () => {
  const { OpenAPIHono } = await import("@hono/zod-openapi")
  const { default: browse } = await import("../routes/browse.ts")
  const { default: rating } = await import("../routes/rating.ts")
  const { client } = stub({
    search_plugins: { data: [{ plugins: [], total_count: 0 }], error: null },
    get_plugin_with_stats: { data: [detailRow], error: null },
    get_plugin_versions: { data: [], error: null },
  })
  const query = {
    select() { return this }, eq() { return this }, order() { return this },
    range() { return this },
    then(resolve: (value: unknown) => unknown) {
      return Promise.resolve({ data: [], count: 0, error: null }).then(resolve)
    },
  }
  Object.assign(client, { from: () => query })
  const app = new OpenAPIHono()
  app.use("*", async (ctx, next) => {
    ctx.set("supabase" as never, client as never)
    ctx.header("Vary", "Origin")
    await next()
  })
  app.route("/", rating)
  app.route("/", browse)
  for (const path of ["/list", "/org.example.plugin", "/org.example.plugin/ratings"]) {
    const response = await app.request(path)
    assertEquals(response.status, 200)
    assertEquals(response.headers.get("cache-control"), "public, max-age=60")
    assertEquals(response.headers.get("vary"), "Origin, Authorization")
  }
})

Deno.test("missing detail and ratings partition anonymous and signed-in cache entries", async () => {
  const { OpenAPIHono } = await import("@hono/zod-openapi")
  const { default: browse } = await import("../routes/browse.ts")
  const { default: rating } = await import("../routes/rating.ts")
  const { client } = stub({
    get_plugin_with_stats: { data: [], error: null },
    get_plugin_with_stats_for_viewer: { data: [], error: null },
  })
  Object.assign(client, { auth: {
    getUser: () => Promise.resolve({ data: { user: { id: VIEWER } }, error: null }),
  } })
  const app = new OpenAPIHono()
  app.use("*", async (ctx, next) => {
    ctx.set("supabase" as never, client as never)
    ctx.header("Vary", "Origin")
    await next()
  })
  app.route("/", rating)
  app.route("/", browse)
  for (const path of ["/org.example.plugin", "/org.example.plugin/ratings"]) {
    for (const signedIn of [false, true]) {
      const response = await app.request(path, {
        headers: signedIn ? { Authorization: "Bearer session-token" } : {},
      })
      assertEquals(response.status, 404)
      assertEquals(response.headers.get("vary"), "Origin, Authorization")
      assertEquals(response.headers.get("cache-control"),
        signedIn ? "private, no-store" : "public, max-age=60")
    }
  }
})
