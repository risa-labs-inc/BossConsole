/**
 * The finalization gate (#912): a version row is inserted BEFORE its JAR
 * exists — sha256='pending', jar_size=0 — and only the finalize route replaces
 * those once the uploaded bytes have been re-hashed and manifest-checked.
 * Until then the row must be invisible to every consumer:
 *
 *   - "latest" must resolve to the newest FINALIZED version (falling back,
 *     never forward), and a plugin with no finalized row has no latest at
 *     all — the download-info route 404s instead of serving sha 'pending';
 *   - a direct version lookup must refuse an unfinalized row the same way;
 *   - a stale pending row must be reapable so its UNIQUE(plugin_id, version)
 *     slot can be re-published after a publish that died mid-way.
 *
 * These tests pin the PostgREST filter chain itself, because the fix IS the
 * presence of two filters: a stub client records every .eq/.neq/.gt/.lt, so
 * dropping either half of the gate (sentinel sha, non-zero size) fails here.
 */

import { assert, assertEquals, assertRejects } from "@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import {
  PENDING_SHA256,
  PENDING_VERSION_STALE_MS,
  deleteStalePendingVersion,
  getLatestVersion,
  getVersion,
} from "../services/versions.ts"

const PLUGIN_UUID = "11111111-2222-4333-8444-555555555555"

interface Filter {
  op: string
  column: string
  value: unknown
}

interface StubResult {
  /** What supabase-js would resolve from the terminal builder call. */
  data: unknown
  error: { code?: string; message: string } | null
}

/**
 * A stub supabase client whose .from() returns a chainable PostgREST builder
 * that records every filter instead of building a URL. `read` answers
 * .single() on read chains; `deleted` answers the terminal .select() of a
 * delete chain.
 */
function stubClient(read: StubResult, deleted: StubResult = { data: null, error: null }) {
  const recorded: {
    table: string
    filters: Filter[]
    order: Array<{ column: string; ascending: boolean }>
    limit: number | null
    deleted: boolean
  } = { table: "", filters: [], order: [], limit: null, deleted: false }

  const chain = (): Record<string, (...args: never[]) => unknown> => ({
    select: (_columns: string) => {
      // Terminal only on a delete chain; on a read chain it just continues.
      if (recorded.deleted) return Promise.resolve({ data: deleted.data, error: deleted.error })
      return chain()
    },
    delete: () => {
      recorded.deleted = true
      return chain()
    },
    eq: (column: string, value: unknown) => {
      recorded.filters.push({ op: "eq", column, value })
      return chain()
    },
    neq: (column: string, value: unknown) => {
      recorded.filters.push({ op: "neq", column, value })
      return chain()
    },
    gt: (column: string, value: unknown) => {
      recorded.filters.push({ op: "gt", column, value })
      return chain()
    },
    lt: (column: string, value: unknown) => {
      recorded.filters.push({ op: "lt", column, value })
      return chain()
    },
    or: (expr: string) => {
      recorded.filters.push({ op: "or", column: "", value: expr })
      return chain()
    },
    order: (column: string, opts: { ascending: boolean }) => {
      recorded.order.push({ column, ascending: opts.ascending })
      return chain()
    },
    limit: (n: number) => {
      recorded.limit = n
      return chain()
    },
    single: () => Promise.resolve({ data: read.data, error: read.error }),
  })

  const client = {
    from: (table: string) => {
      recorded.table = table
      recorded.filters = []
      recorded.order = []
      recorded.limit = null
      recorded.deleted = false
      return chain()
    },
  }

  return { client: client as unknown as SupabaseClient, recorded }
}

/** A finalized version row, as PostgREST would return it. */
const finalizedRow = {
  id: "99999999-8888-4777-8666-555555555555",
  plugin_id: PLUGIN_UUID,
  version: "2.0.0",
  changelog: "notes",
  min_boss_version: "1.0.0",
  min_ipc_version: "1.1.0",
  min_api_version: "2.0",
  jar_path: "plugins/demo/2.0.0.jar",
  jar_size: 2048,
  sha256: "a".repeat(64),
  signature: "store-sig",
  dependencies: [{ pluginId: "dep", version: "1.0.0" }],
  published_at: "2026-09-01T00:00:00.000Z",
}

Deno.test("getLatestVersion resolves only finalized rows and falls back, never forward", async () => {
  const { client, recorded } = stubClient({ data: finalizedRow, error: null })

  const latest = await getLatestVersion(client, PLUGIN_UUID)

  // THE GATE: both halves must be present in the query.
  assertEquals(recorded.table, "plugin_versions")
  assertEquals(recorded.filters, [
    { op: "eq", column: "plugin_id", value: PLUGIN_UUID },
    { op: "neq", column: "sha256", value: PENDING_SHA256 },
    { op: "gt", column: "jar_size", value: 0 },
  ])
  // Ordering among the survivors is unchanged healthy behaviour.
  assertEquals(recorded.order, [{ column: "published_at", ascending: false }])
  assertEquals(recorded.limit, 1)

  assert(latest !== null)
  assertEquals(latest.version, "2.0.0")
  assertEquals(latest.sha256, "a".repeat(64))
  assertEquals(latest.jarSize, 2048)
  assertEquals(latest.signature, "store-sig")
  assertEquals(latest.minIpcVersion, "1.1.0")
})

Deno.test("a plugin whose newest row is still pending has no latest at all", async () => {
  // The gate filters every row out; PostgREST answers .single() with PGRST116.
  const { client } = stubClient({ data: null, error: { code: "PGRST116", message: "no rows" } })

  assertEquals(await getLatestVersion(client, PLUGIN_UUID), null)
})

Deno.test("getVersion applies the same gate, so a pending version is indistinguishable from a nonexistent one", async () => {
  const { client, recorded } = stubClient({ data: finalizedRow, error: null })

  await getVersion(client, PLUGIN_UUID, "2.0.0")

  assertEquals(recorded.filters, [
    { op: "eq", column: "plugin_id", value: PLUGIN_UUID },
    { op: "eq", column: "version", value: "2.0.0" },
    { op: "neq", column: "sha256", value: PENDING_SHA256 },
    { op: "gt", column: "jar_size", value: 0 },
  ])
})

Deno.test("a pending version requested directly is refused (404 at the route), not served", async () => {
  const { client } = stubClient({ data: null, error: { code: "PGRST116", message: "no rows" } })

  assertEquals(await getVersion(client, PLUGIN_UUID, "3.0.0"), null)
})

Deno.test("deleteStalePendingVersion reaps every row the finalization gate hides", async () => {
  const { client, recorded } = stubClient(
    { data: null, error: null },
    { data: [{ id: finalizedRow.id }], error: null },
  )

  assertEquals(await deleteStalePendingVersion(client, PLUGIN_UUID, "2.0.0"), true)

  assert(recorded.deleted)
  assertEquals(recorded.table, "plugin_versions")
  assertEquals(recorded.filters.slice(0, 2), [
    { op: "eq", column: "plugin_id", value: PLUGIN_UUID },
    { op: "eq", column: "version", value: "2.0.0" },
  ])
  // The exact negation of the gate: a hidden row (pending sha, or jar_size
  // zero/NULL with a real sha) must be reapable, or the slot wedges forever.
  assertEquals(recorded.filters[2], {
    op: "or",
    column: "",
    value: `sha256.eq.${PENDING_SHA256},jar_size.is.null,jar_size.lte.0`,
  })

  // The staleness cutoff is the signed upload URL's TTL ago: inside that
  // window the original attempt could still finalize, so the row is protected.
  assertEquals(recorded.filters.length, 4)
  assertEquals(recorded.filters[3].op, "lt")
  assertEquals(recorded.filters[3].column, "published_at")
  const cutoff = Date.parse(recorded.filters[3].value as string)
  const age = Date.now() - cutoff
  assert(age > PENDING_VERSION_STALE_MS - 10_000, `cutoff ${age}ms ago, expected ~${PENDING_VERSION_STALE_MS}ms`)
  assert(age < PENDING_VERSION_STALE_MS + 10_000, `cutoff ${age}ms ago, expected ~${PENDING_VERSION_STALE_MS}ms`)
})

Deno.test("the reap matches nothing when the row is finalized or still inside its upload window", async () => {
  const matchedNone = stubClient({ data: null, error: null }, { data: null, error: null })
  assertEquals(await deleteStalePendingVersion(matchedNone.client, PLUGIN_UUID, "2.0.0"), false)

  const matchedEmpty = stubClient({ data: null, error: null }, { data: [], error: null })
  assertEquals(await deleteStalePendingVersion(matchedEmpty.client, PLUGIN_UUID, "2.0.0"), false)
})

Deno.test("the reap surfaces storage failures instead of silently reporting success", async () => {
  const failing = stubClient({ data: null, error: null }, { data: null, error: { message: "boom" } })

  await assertRejects(() => deleteStalePendingVersion(failing.client, PLUGIN_UUID, "2.0.0"))
})
