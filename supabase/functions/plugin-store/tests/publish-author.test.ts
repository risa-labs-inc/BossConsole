/**
 * The author name on a published plugin is derived from the authenticated
 * user, never from the request.
 *
 * `plugins.author_name` is rendered as "Published by ..." on the plugin page
 * and returned verbatim in store JSON, so honouring `body.authorName` let any
 * authenticated publisher claim "BOSS Team" or an organisation it does not
 * belong to. This drives POST /publish end to end with a forged name and
 * asserts the row that would have been inserted carries the derived name.
 *
 * Run: deno test --allow-all tests/publish-author.test.ts
 */

import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import publish from "../routes/publish.ts"

const USER_ID = "11111111-1111-4111-8111-111111111111"
const USER_EMAIL = "real.publisher@example.com"
const PLUGIN_UUID = "22222222-2222-4222-8222-222222222222"

/** Structurally valid JWT carrying the claims getUserFromToken decodes. */
function jwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b64({ alg: "HS256", typ: "JWT" })}.${b64(claims)}.sig`
}

/**
 * Just enough Supabase for POST /publish: JWT auth, the org resolution rpcs
 * (no memberships, so orgId derives to null), a `plugins` table that is empty
 * on read and captures the row on insert, and the `users` row
 * getUserDisplayName derives the name from.
 */
function stubSupabase(captured: { insert?: Record<string, unknown> }): SupabaseClient {
  // deno-lint-ignore no-explicit-any
  const chain: any = {}
  let table = ""
  let op = "select"
  let payload: unknown

  const exec = () => {
    if (table === "users") {
      return Promise.resolve({ data: { email: USER_EMAIL }, error: null })
    }
    if (table === "plugins" && op === "insert") {
      captured.insert = payload as Record<string, unknown>
      return Promise.resolve({ data: { id: PLUGIN_UUID }, error: null })
    }
    // plugins select -> no existing row; anything else -> empty
    return Promise.resolve({ data: null, error: null })
  }

  for (const m of ["eq", "gt", "lt", "in", "neq", "not", "or", "order", "limit"]) {
    chain[m] = () => chain
  }
  chain.select = () => chain
  chain.insert = (data: unknown) => {
    op = "insert"
    payload = data
    return chain
  }
  chain.update = (data: unknown) => {
    op = "update"
    payload = data
    return chain
  }
  chain.single = exec
  chain.maybeSingle = exec
  chain.then = (
    onfulfilled?: ((v: unknown) => unknown) | null,
    onrejected?: ((r: unknown) => unknown) | null,
  ) => exec().then(onfulfilled, onrejected)

  return {
    auth: {
      getUser: () =>
        Promise.resolve({ data: { user: { id: USER_ID, email: USER_EMAIL } }, error: null }),
    },
    from: (t: string) => {
      table = t
      op = "select"
      payload = undefined
      return chain
    },
    rpc: (fn: string) => {
      if (fn === "get_my_organisations") {
        return Promise.resolve({ data: { success: true, data: [] }, error: null })
      }
      if (fn === "user_has_permission") {
        return Promise.resolve({ data: false, error: null })
      }
      return Promise.resolve({ data: null, error: null })
    },
  } as unknown as SupabaseClient
}

Deno.test("POST /publish ignores a self-asserted authorName and stores the derived name", async () => {
  const captured: { insert?: Record<string, unknown> } = {}
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", stubSupabase(captured))
    await next()
  })
  app.route("/", publish)

  // The JWT carries plugins.create so the org-free path authorises; the point
  // under test is what lands in author_name, not the gate.
  const token = jwt({ sub: USER_ID, user_permissions: ["plugins.create"] })
  const response = await app.request("/publish", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({
      pluginId: "com.example.impersonation",
      displayName: "Harmless Plugin",
      authorName: "BOSS Team",
      homepageUrl: "https://example.com/plugin",
    }),
  })

  assertEquals(response.status, 201)
  assertEquals(captured.insert?.author_id, USER_ID)
  assertEquals(captured.insert?.author_name, "real.publisher")
})
