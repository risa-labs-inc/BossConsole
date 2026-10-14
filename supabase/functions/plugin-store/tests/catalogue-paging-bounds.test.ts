import { assert, assertEquals } from "@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import browse from "../routes/browse.ts"
import { CATALOGUE_PAGE_MAX, CATALOGUE_PAGE_SIZE_MAX } from "../types/schemas.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

/**
 * GET /list and POST /search page the catalogue (BossConsole#1669 items 5 and 6). Both bound
 * `pageSize` and `page`, both refuse a fraction, and both answer a refusal with the
 * ErrorResponseSchema 400 they declare, before the limiter or the database is reached. The SQL
 * wrappers clamp to the same page size (20260924180000); a page past CATALOGUE_PAGE_MAX is
 * refused here rather than silently answered with an empty page there.
 */

function app(client: SupabaseClient) {
  // The catalogue limiter's buckets are module state shared across test files; see
  // popular-tags-bounds.test.ts.
  resetRateLimits()
  const instance = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  instance.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  instance.route("/", browse)
  return instance
}

// If validation let a request through, the route's catch would turn this throw into a 500.
function untouchableSupabase(): SupabaseClient {
  return {
    rpc() {
      throw new Error("database must not be touched for an invalid page")
    },
  } as unknown as SupabaseClient
}

function recordingSupabase() {
  const calls: { fn: string; args: Record<string, unknown> }[] = []
  const client = {
    rpc(fn: string, args: Record<string, unknown>) {
      calls.push({ fn, args })
      return Promise.resolve({ data: [], error: null })
    },
  } as unknown as SupabaseClient
  return { client, calls }
}

function search(client: SupabaseClient, body: Record<string, unknown>) {
  return app(client).request("/search", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  })
}

Deno.test("/list refuses an oversized pageSize with its declared 400, before the database", async () => {
  const response = await app(untouchableSupabase()).request("/list?pageSize=999999999")
  assertEquals(response.status, 400)
  const body = await response.json()
  assertEquals(Object.keys(body), ["error"])
  assert(body.error.includes("pageSize"), `the error names the field: ${body.error}`)
})

Deno.test("/list refuses every out-of-range or non-integer page and pageSize", async () => {
  const cases = [
    "page=0", "page=-1", "page=1.5", "page=abc", `page=${CATALOGUE_PAGE_MAX + 1}`,
    "pageSize=0", "pageSize=1.5", "pageSize=abc", `pageSize=${CATALOGUE_PAGE_SIZE_MAX + 1}`,
  ]
  for (const query of cases) {
    const response = await app(untouchableSupabase()).request(`/list?${query}`)
    assertEquals(response.status, 400, `${query} must be refused`)
    await response.body?.cancel()
  }
})

Deno.test("/list passes its largest page and pageSize to search_plugins unchanged", async () => {
  const { client, calls } = recordingSupabase()
  const response = await app(client).request(`/list?page=${CATALOGUE_PAGE_MAX}&pageSize=${CATALOGUE_PAGE_SIZE_MAX}`)
  assertEquals(response.status, 200)
  await response.body?.cancel()
  assertEquals(calls.map((c) => c.fn), ["search_plugins"])
  assertEquals(calls[0].args.p_page, CATALOGUE_PAGE_MAX)
  assertEquals(calls[0].args.p_page_size, CATALOGUE_PAGE_SIZE_MAX)
})

Deno.test("/search refuses a fractional or out-of-range page and pageSize", async () => {
  const cases: Record<string, unknown>[] = [
    { page: 1.5 }, { page: 0 }, { page: CATALOGUE_PAGE_MAX + 1 },
    { pageSize: 2.5 }, { pageSize: 0 }, { pageSize: CATALOGUE_PAGE_SIZE_MAX + 1 },
  ]
  for (const body of cases) {
    const response = await search(untouchableSupabase(), body)
    assertEquals(response.status, 400, `${JSON.stringify(body)} must be refused`)
    const payload = await response.json()
    assertEquals(Object.keys(payload), ["error"])
  }
})

Deno.test("/search passes its largest page and pageSize to search_plugins unchanged", async () => {
  const { client, calls } = recordingSupabase()
  const response = await search(client, { page: CATALOGUE_PAGE_MAX, pageSize: CATALOGUE_PAGE_SIZE_MAX })
  assertEquals(response.status, 200)
  await response.body?.cancel()
  assertEquals(calls.map((c) => c.fn), ["search_plugins"])
  assertEquals(calls[0].args.p_page, CATALOGUE_PAGE_MAX)
  assertEquals(calls[0].args.p_page_size, CATALOGUE_PAGE_SIZE_MAX)
})
