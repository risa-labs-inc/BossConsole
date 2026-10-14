import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import rating from "../routes/rating.ts"

/**
 * Regression coverage for issue #915: GET /:pluginId/ratings is unauthenticated, used to
 * accept unbounded page/pageSize (fed straight into a PostgREST .range() call) and returned
 * each rater's raw auth.users UUID, so it doubled as a mass identity-scraping surface. The
 * route must reject out-of-range pagination up front and keep rater identities off the wire.
 */

const PLUGIN_UUID = "11111111-1111-4111-8111-111111111111"
const RATER_UUID = "22222222-2222-4222-8222-222222222222"

// A client whose every method throws proves rejection happens before any database call -
// invalid pagination must not even reach the plugin lookup, let alone the range query.
function untouchableSupabase(): SupabaseClient {
  return {
    rpc() {
      throw new Error("database must not be touched for invalid pagination")
    },
    from() {
      throw new Error("database must not be touched for invalid pagination")
    },
  } as unknown as SupabaseClient
}

// A PostgREST-shaped stub: chainable, thenable, and it records what the service actually
// asked the database for - which columns were selected, and what .range() bounds were used.
function ratingsSupabase(rows: Array<Record<string, unknown>> = []) {
  const selectedColumns: string[] = []
  let requestedRange: [number, number] | null = null
  const builder: Record<string, unknown> = {
    select(columnList: unknown) {
      if (typeof columnList === "string") selectedColumns.push(columnList)
      return builder
    },
    eq: () => builder,
    order: () => builder,
    range: (from: number, to: number) => {
      requestedRange = [from, to]
      return builder
    },
    then: (
      onFulfilled: (value: unknown) => unknown,
      onRejected?: (reason: unknown) => unknown,
    ) =>
      Promise.resolve({ data: rows, count: rows.length, error: null }).then(
        onFulfilled,
        onRejected,
      ),
  }
  const client = {
    // get_plugin_with_stats returning a row is what "plugin exists" looks like.
    rpc: () =>
      Promise.resolve({
        data: [{ id: PLUGIN_UUID, plugin_id: "some-plugin" }],
        error: null,
      }),
    from: () => builder,
  } as unknown as SupabaseClient
  return { client, selectedColumns, range: () => requestedRange }
}

function app(client: SupabaseClient) {
  const instance = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  instance.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  instance.route("/", rating)
  return instance
}

Deno.test("an oversized pageSize is rejected with a fixed 400 envelope, without touching the database", async () => {
  const response = await app(untouchableSupabase()).request(
    "/some-plugin/ratings?page=1&pageSize=99999999",
  )
  assertEquals(response.status, 400)
  assertEquals(await response.json(), {
    error: "page must be an integer from 1 to 500 and pageSize an integer from 1 to 50",
  })
})

Deno.test("zero, negative, fractional, non-numeric and beyond-cap pageSize are rejected, not turned into range math", async () => {
  for (const pageSize of ["0", "-1", "1.5", "abc", "", "51"]) {
    const response = await app(untouchableSupabase()).request(
      `/some-plugin/ratings?page=1&pageSize=${encodeURIComponent(pageSize)}`,
    )
    assertEquals(response.status, 400, `pageSize=${JSON.stringify(pageSize)} must be rejected`)
  }
})

Deno.test("zero, negative, fractional, non-numeric and beyond-cap page values are rejected", async () => {
  for (const page of ["0", "-5", "1.5", "not-a-number", "501", "1000000000"]) {
    const response = await app(untouchableSupabase()).request(
      `/some-plugin/ratings?page=${encodeURIComponent(page)}&pageSize=20`,
    )
    assertEquals(response.status, 400, `page=${JSON.stringify(page)} must be rejected`)
  }
})

Deno.test("the maximum allowed page and pageSize pass validation and reach the plugin lookup", async () => {
  // The plugin lookup coming back empty is what "plugin not found" looks like - a 404 here
  // proves the bounds check let the request through, not that the database was avoided.
  const client = {
    rpc: () => Promise.resolve({ data: [], error: null }),
  } as unknown as SupabaseClient
  const response = await app(client).request("/some-plugin/ratings?page=500&pageSize=50")
  assertEquals(response.status, 404)
})

Deno.test("the default pagination (no query string) still passes validation", async () => {
  const client = {
    rpc: () => Promise.resolve({ data: [], error: null }),
  } as unknown as SupabaseClient
  const response = await app(client).request("/some-plugin/ratings")
  assertEquals(response.status, 404)
})

Deno.test("a normal ratings page still works and never asks the database for rater identities", async () => {
  // The stub rows deliberately still carry user_id, as the real table does: if a future
  // regression re-selects or re-maps it, the assertions below fail.
  const { client, selectedColumns, range } = ratingsSupabase([
    { user_id: RATER_UUID, rating: 5, review: "works great", created_at: "2026-09-18T00:00:00Z" },
    { user_id: "33333333-3333-4333-8333-333333333333", rating: 3, review: "okay", created_at: "2026-09-17T00:00:00Z" },
  ])
  const response = await app(client).request("/some-plugin/ratings?page=1&pageSize=20")

  assertEquals(response.status, 200)
  assertEquals(await response.json(), {
    ratings: [
      { rating: 5, review: "works great", createdAt: "2026-09-18T00:00:00Z" },
      { rating: 3, review: "okay", createdAt: "2026-09-17T00:00:00Z" },
    ],
    totalCount: 2,
    page: 1,
    pageSize: 20,
  })

  // The service must not even request the identity column, so it cannot reach the wire.
  assertEquals(selectedColumns.join(" ").includes("user_id"), false)
  // First page at pageSize 20 is exactly rows 0..19 of the range query.
  assertEquals(range(), [0, 19])
})

Deno.test("an unauthenticated caller cannot scrape rater UUIDs from the response", async () => {
  const { client } = ratingsSupabase([
    { user_id: RATER_UUID, rating: 5, review: "works great", created_at: "2026-09-18T00:00:00Z" },
  ])
  // No Authorization header anywhere in this suite - the route is public.
  const raw = await (await app(client).request("/some-plugin/ratings")).text()
  assertEquals(raw.includes(RATER_UUID), false)
  assertEquals(raw.includes("userId"), false)
})

Deno.test("the range ceiling at max pageSize is exactly 50 rows", async () => {
  const { client, range } = ratingsSupabase([])
  const response = await app(client).request("/some-plugin/ratings?page=2&pageSize=50")
  assertEquals(response.status, 200)
  assertEquals(range(), [50, 99])
})
