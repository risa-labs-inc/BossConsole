import { assertEquals } from "@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import browse from "../routes/browse.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

/**
 * GET /tags/popular is public and sends the anon key. Its `limit` used to reach
 * `LIMIT p_limit` in get_popular_tags with nothing bounding it anywhere on the way, so an
 * oversized value asked for an arbitrarily large window - and a non-numeric one removed the
 * bound entirely, because Number('abc') is NaN, JSON serialises NaN as null, and PostgreSQL
 * treats LIMIT NULL as LIMIT ALL.
 */

function app(client: SupabaseClient) {
  // The popular-tags route is now behind the catalogue rate limit, whose buckets are module
  // state shared with every other test file. Without a reset, requests made elsewhere spend
  // this key's budget and a validation test here gets a 429 instead of the 400 it checks.
  // The limiter itself is tested in hardening.test.ts.
  resetRateLimits()
  const instance = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  instance.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  instance.route("/", browse)
  return instance
}

// A client whose rpc throws. If validation let a request through, the route's own catch would
// turn the throw into a 500, so a 400 here proves the refusal happened before the database.
function untouchableSupabase(): SupabaseClient {
  return {
    rpc() {
      throw new Error("database must not be touched for an invalid limit")
    },
  } as unknown as SupabaseClient
}

// Records what the service actually asked the database for, so a test can check the value
// that reaches p_limit rather than only the status code.
function recordingSupabase() {
  const limits: unknown[] = []
  const client = {
    rpc(_fn: string, args: Record<string, unknown>) {
      limits.push(args.p_limit)
      return Promise.resolve({ data: [], error: null })
    },
  } as unknown as SupabaseClient
  return { client, limits }
}

Deno.test("an oversized limit is refused with the route's own 400, before the database", async () => {
  const response = await app(untouchableSupabase()).request("/tags/popular?limit=99999999")
  assertEquals(response.status, 400)
})

Deno.test("a non-numeric limit is refused rather than reaching the RPC as null", async () => {
  // The case that mattered most: this is the one that used to remove the bound entirely.
  const response = await app(untouchableSupabase()).request("/tags/popular?limit=abc")
  assertEquals(response.status, 400)
})

Deno.test("zero, negative, fractional, empty and beyond-cap limits are all refused", async () => {
  for (const limit of ["0", "-1", "1.5", "", "101", "Infinity", "NaN"]) {
    const response = await app(untouchableSupabase()).request(
      `/tags/popular?limit=${encodeURIComponent(limit)}`,
    )
    assertEquals(response.status, 400, `limit=${JSON.stringify(limit)} must be refused`)
  }
})

Deno.test("the maximum allowed limit reaches the RPC as that integer", async () => {
  const { client, limits } = recordingSupabase()
  const response = await app(client).request("/tags/popular?limit=100")
  assertEquals(response.status, 200)
  assertEquals(limits, [100])
})

Deno.test("with no limit the default reaches the RPC, unchanged from before", async () => {
  const { client, limits } = recordingSupabase()
  const response = await app(client).request("/tags/popular")
  assertEquals(response.status, 200)
  assertEquals(limits, [20])
})

Deno.test("a valid limit is forwarded as a number, never as a string or NaN", async () => {
  const { client, limits } = recordingSupabase()
  await app(client).request("/tags/popular?limit=7")
  assertEquals(limits, [7])
  assertEquals(typeof limits[0], "number")
})
