/**
 * #912 regression: a version row created BEFORE its JAR exists must never be
 * served as a version.
 *
 * `createVersion` inserts the row up front — sha256 'pending', jar_size 0 —
 * while published_at already reads as now() (the DDL default), so the phantom
 * row outranks the last good version in any published_at-ordered query the
 * moment it is inserted. Before the fix, "download latest" resolved to it:
 * a jar key that 404s (or stale bytes a previous failed attempt left under
 * that key, mis-anchored under the new version's signature), a sha256 of
 * 'pending' that fails every re-verifying host, and a version list that
 * advertised an undownloadable release.
 *
 * The stub below is an in-memory stand-in for the slice of PostgREST the
 * version reads use — it applies eq/neq/gt/order/limit/single to a table of
 * rows, with .single() answering PGRST116 for zero rows exactly like the real
 * server. That matters: a stub that ignores the query and returns one canned
 * row would pass the OLD code too, which is how a regression test fails
 * silently. Fed the two-row fixture (a finalized 1.0.0 and a NEWER pending
 * 1.1.0), the old unfiltered chain demonstrably resolves "latest" to the
 * pending row; the assertions here pin that it no longer does.
 *
 * Run: deno test --allow-all tests/version-finalize-gate.test.ts
 */

import { assert, assertEquals, assertRejects } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import {
  PENDING_SHA256,
  finalizeVersion,
  getLatestVersion,
  getPluginVersions,
  getVersion,
  getVersionById,
  isFinalizedVersionRow,
} from "../services/versions.ts"
import download from "../routes/download.ts"
import browse from "../routes/browse.ts"

const PLUGIN_UUID = "11111111-1111-4111-8111-111111111111"
const GOOD_SHA = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

type Row = Record<string, unknown>

/** A finalized 1.0.0 — real digest, real byte count, downloadable. */
const GOOD: Row = {
  id: "v-good",
  plugin_id: PLUGIN_UUID,
  version: "1.0.0",
  changelog: "first",
  min_boss_version: "1.0.0",
  min_ipc_version: "1.0.0",
  min_api_version: "",
  jar_path: "plugins/my-plugin/1.0.0/my-plugin-1.0.0.jar",
  jar_size: 4096,
  sha256: GOOD_SHA,
  signature: "sig-good",
  dependencies: [],
  published_at: "2026-09-01T00:00:00Z",
}

/**
 * An unfinalized 1.1.0 — exactly what POST /:pluginId/version leaves behind
 * when the upload never happens: the placeholder digest, zero bytes, but a
 * published_at NEWER than every finalized version.
 */
const PENDING_ROW: Row = {
  id: "v-pending",
  plugin_id: PLUGIN_UUID,
  version: "1.1.0",
  changelog: "next",
  min_boss_version: "1.0.0",
  min_ipc_version: "1.0.0",
  min_api_version: "",
  jar_path: "plugins/my-plugin/1.1.0/my-plugin-1.1.0.jar",
  jar_size: 0,
  sha256: PENDING_SHA256,
  signature: null,
  dependencies: [],
  published_at: "2026-09-17T00:00:00Z",
}

/** What get_plugin_with_stats returns today: its latest_version subquery has no finalized filter. */
function statsRow(): Row {
  return {
    id: PLUGIN_UUID,
    plugin_id: "my-plugin",
    display_name: "My Plugin",
    description: "",
    author_id: "author-1",
    author_name: "Author",
    homepage_url: "",
    icon_url: "",
    type: "panel",
    api_version: "1.0.0",
    verified: false,
    published: true,
    created_at: "2026-08-01T00:00:00Z",
    updated_at: "2026-09-17T00:00:00Z",
    latest_version: "1.1.0",
    latest_version_id: "v-pending",
    avg_rating: 0,
    rating_count: 0,
    download_count: 0,
    tags: [],
    screenshots: [],
    required_permissions: [],
  }
}

/**
 * In-memory plugin_versions table implementing the builder semantics the
 * services rely on. See the file header for why this must actually filter.
 */
function fakeVersionsTable(
  rows: Row[],
  updates: Array<{ payload: Row; matched: Row[] }>,
): Record<string, unknown> {
  let filtered = [...rows]
  let limitCount = Infinity

  const self: Record<string, unknown> = {
    select: () => self,
    eq: (col: string, value: unknown) => {
      filtered = filtered.filter((r) => r[col] === value)
      return self
    },
    neq: (col: string, value: unknown) => {
      filtered = filtered.filter((r) => r[col] !== value)
      return self
    },
    gt: (col: string, value: unknown) => {
      filtered = filtered.filter((r) => Number(r[col]) > Number(value))
      return self
    },
    order: (col: string, opts?: { ascending?: boolean }) => {
      const sign = opts?.ascending === false ? -1 : 1
      filtered = [...filtered].sort((a, b) => {
        const av = String(a[col])
        const bv = String(b[col])
        return av < bv ? -sign : av > bv ? sign : 0
      })
      return self
    },
    limit: (n: number) => {
      limitCount = n
      return self
    },
    single: () => {
      const out = filtered.slice(0, limitCount)
      if (out.length !== 1) {
        // PostgREST's real zero/multiple-rows answer, which the services map to null.
        return Promise.resolve({
          data: null,
          error: { code: "PGRST116", message: out.length === 0 ? "no rows" : "multiple rows" },
        })
      }
      return Promise.resolve({ data: out[0], error: null })
    },
    maybeSingle: () => {
      const out = filtered.slice(0, limitCount)
      return Promise.resolve(out.length === 0 ? { data: null, error: null } : { data: out[0], error: null })
    },
    // finalizeVersion's write: `.update(payload).eq('id', versionId)`, awaited.
    update: (payload: Row) => ({
      eq: (col: string, value: unknown) => {
        const matched = rows.filter((r) => r[col] === value)
        updates.push({ payload, matched })
        return Promise.resolve({ error: null })
      },
    }),
  }
  return self
}

function stubStore(versions: Row[]): { client: SupabaseClient; updates: Array<{ payload: Row; matched: Row[] }> } {
  const updates: Array<{ payload: Row; matched: Row[] }> = []

  const client = {
    rpc: (fn: string, _args: Record<string, unknown>) => {
      if (fn === "get_plugin_with_stats") return Promise.resolve({ data: [statsRow()], error: null })
      // The SQL is unfiltered; the edge function must do the gating. DESC by
      // published_at, exactly like the real function's ORDER BY.
      if (fn === "get_plugin_versions") {
        const desc = [...versions].sort((a, b) =>
          String(b.published_at).localeCompare(String(a.published_at))
        )
        return Promise.resolve({ data: desc, error: null })
      }
      if (fn === "user_can_install_plugin") return Promise.resolve({ data: true, error: null })
      if (fn === "record_plugin_download") return Promise.resolve({ data: "dl-1", error: null })
      return Promise.resolve({ data: null, error: { message: `unexpected rpc ${fn}` } })
    },
    from: (table: string) => {
      if (table !== "plugin_versions") throw new Error(`unexpected table ${table}`)
      return fakeVersionsTable(versions, updates)
    },
    storage: {
      from: () => ({
        createSignedUrl: (path: string) =>
          Promise.resolve({ data: { signedUrl: `https://signed.example/${path}` }, error: null }),
      }),
    },
  }
  return { client: client as unknown as SupabaseClient, updates }
}

function appWith(
  client: SupabaseClient,
  routes: OpenAPIHono<{ Variables: PluginStoreContext }>,
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
// The predicate itself
// ---------------------------------------------------------------------------

Deno.test("isFinalizedVersionRow requires both a real digest and bytes", () => {
  assert(isFinalizedVersionRow({ sha256: GOOD_SHA, jar_size: 4096 }))
  // The two half-written finalizes: either fact alone must not count.
  assertEquals(isFinalizedVersionRow({ sha256: GOOD_SHA, jar_size: 0 }), false)
  assertEquals(isFinalizedVersionRow({ sha256: PENDING_SHA256, jar_size: 4096 }), false)
  assertEquals(isFinalizedVersionRow({ sha256: PENDING_SHA256, jar_size: 0 }), false)
  // jar_size arrives numeric through PostgREST but may come back as a string
  // from other RPC shapes — the predicate coerces, so both spellings agree.
  assert(isFinalizedVersionRow({ sha256: GOOD_SHA, jar_size: "4096" }))
  assertEquals(isFinalizedVersionRow({ sha256: PENDING_SHA256, jar_size: "0" }), false)
})

// ---------------------------------------------------------------------------
// Service layer: the query-side gate
// ---------------------------------------------------------------------------

Deno.test("getLatestVersion resolves to the last GOOD version, never a newer unfinalized one (#912)", async () => {
  const { client } = stubStore([GOOD, PENDING_ROW])
  const latest = await getLatestVersion(client, PLUGIN_UUID)
  assertEquals(latest?.id, "v-good")
  assertEquals(latest?.version, "1.0.0")
  assertEquals(latest?.sha256, GOOD_SHA)
})

Deno.test("getLatestVersion is null when every version is unfinalized", async () => {
  // A plugin whose first publish died mid-flight: there is no latest at all,
  // which the download routes render as their existing 404 — never the phantom.
  const { client } = stubStore([PENDING_ROW])
  assertEquals(await getLatestVersion(client, PLUGIN_UUID), null)
})

Deno.test("getVersion treats an unfinalized version as absent, a finalized one as present", async () => {
  const { client } = stubStore([GOOD, PENDING_ROW])
  assertEquals(await getVersion(client, PLUGIN_UUID, "1.1.0"), null)
  assertEquals((await getVersion(client, PLUGIN_UUID, "1.0.0"))?.id, "v-good")
})

Deno.test("getVersionById still finds unfinalized rows — finalize's repair path must not be gated", async () => {
  // The other half of the split: read paths skip pending rows, publish paths
  // must see them, or an interrupted publish could never be finalized.
  const { client } = stubStore([PENDING_ROW])
  assertEquals((await getVersionById(client, "v-pending"))?.sha256, PENDING_SHA256)
})

Deno.test("getPluginVersions lists only finalized versions", async () => {
  const { client } = stubStore([GOOD, PENDING_ROW])
  const versions = await getPluginVersions(client, "my-plugin")
  assertEquals(versions.map((v) => v.version), ["1.0.0"])
  assertEquals(versions[0].sha256, GOOD_SHA)
  assertEquals(versions[0].jarSize, 4096)
})

// ---------------------------------------------------------------------------
// Service layer: the finalize-time guard
// ---------------------------------------------------------------------------

Deno.test("finalizeVersion refuses a placeholder digest or zero bytes and leaves the row retryable", async () => {
  const { client, updates } = stubStore([PENDING_ROW])
  await assertRejects(() => finalizeVersion(client, "v-pending", PENDING_SHA256, 4096, "my-plugin", "1.1.0"))
  await assertRejects(() => finalizeVersion(client, "v-pending", "", 4096, "my-plugin", "1.1.0"))
  await assertRejects(() => finalizeVersion(client, "v-pending", GOOD_SHA, 0, "my-plugin", "1.1.0"))
  assertEquals(updates.length, 0, "a refused finalize must not touch the row")
})

Deno.test("finalizeVersion writes the observed digest and byte count once accepted", async () => {
  const { client, updates } = stubStore([PENDING_ROW])
  await finalizeVersion(client, "v-pending", GOOD_SHA, 4096, "my-plugin", "1.1.0")
  assertEquals(updates.length, 1)
  assertEquals(updates[0].payload.sha256, GOOD_SHA)
  assertEquals(updates[0].payload.jar_size, 4096)
  assertEquals(updates[0].matched.map((r) => r.id), ["v-pending"])
})

// ---------------------------------------------------------------------------
// HTTP layer: the routes a client actually hits
// ---------------------------------------------------------------------------

Deno.test("download-info resolves to the last GOOD version while a newer row is unfinalized (#912)", async () => {
  const { client } = stubStore([GOOD, PENDING_ROW])
  const app = appWith(client, download)

  const response = await app.request("/my-plugin/download")
  assertEquals(response.status, 200)
  const body = await response.json()
  assertEquals(body.version, "1.0.0")
  assertEquals(body.sha256, GOOD_SHA)
  assertEquals(body.versionId, "v-good")
  assert(String(body.downloadUrl).includes("1.0.0"), "must sign the good version's jar, not the phantom key")
})

Deno.test("a pinned download of an unfinalized version 404s like a nonexistent one", async () => {
  const { client } = stubStore([GOOD, PENDING_ROW])
  const app = appWith(client, download)

  const pending = await app.request("/my-plugin/download/1.1.0")
  assertEquals(pending.status, 404)
  assertEquals((await pending.json()).error, "Version not found")

  const good = await app.request("/my-plugin/download/1.0.0")
  assertEquals(good.status, 200)
  assertEquals((await good.json()).version, "1.0.0")
})

Deno.test("the public detail route never lists an unfinalized version or headlines it as latest", async () => {
  const { client } = stubStore([GOOD, PENDING_ROW])
  const app = appWith(client, browse)

  const response = await app.request("/my-plugin")
  assertEquals(response.status, 200)
  const body = await response.json()
  assertEquals(
    body.versions.map((v: { version: string }) => v.version),
    ["1.0.0"],
    "the pending 1.1.0 must not appear in the public version list",
  )
  assertEquals(body.latestVersion, "1.0.0", "the headline must be the newest FINALIZED version")
  // And the phantom must not leak through any per-version field either.
  assertEquals(JSON.stringify(body).includes(PENDING_SHA256), false)
})
