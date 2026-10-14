import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import browse from "../routes/browse.ts"

Deno.test("browse masks unexpected database exceptions at the HTTP boundary", async () => {
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  const diagnostic = "private schema and connection details"
  const client = {
    rpc() { throw new Error(diagnostic) },
  } as unknown as SupabaseClient
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", browse)
  const response = await app.request("/list")
  assertEquals(response.status, 500)
  const body = await response.text()
  assertEquals(JSON.parse(body), { error: "Internal server error" })
  assertEquals(body.includes(diagnostic), false)
})
