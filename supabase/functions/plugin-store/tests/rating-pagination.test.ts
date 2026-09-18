import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import rating from "../routes/rating.ts"

/**
 * Regression coverage for issue #915: GET /:pluginId/ratings accepted unbounded page/pageSize
 * with no validation, letting a caller request an arbitrarily large PostgREST range (DoS-adjacent
 * cost on a route with no rate limiter) and, since each row carries a raw auth.users UUID, scrape
 * rater identities at whatever scale the query allowed.
 */

// A supabase stub whose rpc() throws proves rejection happens BEFORE any database call - an
// invalid page/pageSize must never even reach get_plugin_with_stats, let alone the ratings range
// query itself.
function untouchableSupabase(): SupabaseClient {
  return {
    rpc() {
      throw new Error("database must not be touched for an invalid page/pageSize")
    },
  } as unknown as SupabaseClient
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
    error: "page must be an integer from 1 to 100000, pageSize from 1 to 100",
  })
})

Deno.test("a deep-offset page is rejected the same way", async () => {
  const response = await app(untouchableSupabase()).request(
    "/some-plugin/ratings?page=1000000000&pageSize=20",
  )
  assertEquals(response.status, 400)
})

Deno.test("zero and negative pageSize are rejected, not turned into malformed range math", async () => {
  for (const pageSize of ["0", "-1"]) {
    const response = await app(untouchableSupabase()).request(
      `/some-plugin/ratings?page=1&pageSize=${pageSize}`,
    )
    assertEquals(response.status, 400, `pageSize=${pageSize} must be rejected`)
  }
})

Deno.test("a non-numeric page or pageSize is rejected rather than coerced to NaN math", async () => {
  const response = await app(untouchableSupabase()).request(
    "/some-plugin/ratings?page=not-a-number&pageSize=20",
  )
  assertEquals(response.status, 400)
})

Deno.test("the maximum allowed page and pageSize pass validation and reach the plugin lookup", async () => {
  // get_plugin_with_stats returning no rows is what a real "plugin not found" looks like - a
  // 404 here proves validation let the request through, not that the database was avoided.
  const client = {
    rpc() {
      return Promise.resolve({ data: [], error: null })
    },
  } as unknown as SupabaseClient
  const response = await app(client).request(
    "/some-plugin/ratings?page=100000&pageSize=100",
  )
  assertEquals(response.status, 404)
})

Deno.test("the default page and pageSize (no query string) pass validation", async () => {
  const client = {
    rpc() {
      return Promise.resolve({ data: [], error: null })
    },
  } as unknown as SupabaseClient
  const response = await app(client).request("/some-plugin/ratings")
  assertEquals(response.status, 404)
})
