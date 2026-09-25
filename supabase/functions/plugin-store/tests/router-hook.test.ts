import { assert, assertEquals } from "@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import admin from "../routes/admin.ts"
import apiKeys from "../routes/api-keys.ts"
import browse from "../routes/browse.ts"
import publish from "../routes/publish.ts"
import rating from "../routes/rating.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

/**
 * Every router answers a request that fails its route's schema with the ErrorResponseSchema 400
 * it declares - `{ error }` naming the field - never the validator's own
 * `{ success: false, error: <ZodError> }`. The hook lives on each router (utils/router.ts), not on
 * the app, so this checks one schema failure per router, and a source scan checks that every
 * router is built by newRouter at all.
 */

function mount(router: OpenAPIHono<{ Variables: PluginStoreContext }>) {
  resetRateLimits()
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  app.use("*", async (ctx, next) => {
    // Any database or auth call is a failure here: the request must be refused before either.
    ctx.set("supabase", {
      rpc() {
        throw new Error("database must not be touched for an invalid request")
      },
      from() {
        throw new Error("database must not be touched for an invalid request")
      },
      auth: {
        getUser() {
          throw new Error("auth must not be consulted for an invalid request")
        },
      },
    } as unknown as SupabaseClient)
    await next()
  })
  app.route("/", router)
  return app
}

function post(path: string, body: unknown): RequestInit & { path: string } {
  return {
    path,
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  }
}

const cases: { router: string; app: OpenAPIHono<{ Variables: PluginStoreContext }>; request: RequestInit & { path: string }; field: string }[] = [
  { router: "browse", app: mount(browse), request: { path: "/list?pageSize=0" }, field: "pageSize" },
  { router: "rating", app: mount(rating), request: post("/test.plugin/rate", { rating: 9 }), field: "rating" },
  { router: "admin", app: mount(admin), request: post("/admin/test.plugin/publish", { published: "yes" }), field: "published" },
  { router: "api-keys", app: mount(apiKeys), request: post("/api-keys", { name: "", scopes: ["publish"] }), field: "name" },
  { router: "publish", app: mount(publish), request: post("/github", { githubUrl: "not a url" }), field: "githubUrl" },
]

for (const { router, app, request, field } of cases) {
  Deno.test(`${router}: a schema failure is a 400 in the declared { error } shape, naming ${field}`, async () => {
    const { path, ...init } = request
    const response = await app.request(path, init)
    assertEquals(response.status, 400)
    const body = await response.json()
    assertEquals(Object.keys(body), ["error"], `the body is ErrorResponseSchema, not the validator's: ${JSON.stringify(body)}`)
    assertEquals(typeof body.error, "string")
    assert(body.error.includes(field), `the error names the field: ${body.error}`)
  })
}

// download's routes take only path strings, which no value fails, so it has no schema failure to
// provoke; the scan below is what covers it, and any router added later.
Deno.test("every router is built by newRouter, so none falls back to the validator's own 400 body", () => {
  const routesDir = new URL("../routes/", import.meta.url)
  const routers: string[] = []
  for (const entry of Deno.readDirSync(routesDir)) {
    if (!entry.isFile || !entry.name.endsWith(".ts")) continue
    const source = Deno.readTextFileSync(new URL(entry.name, routesDir))
    assert(!source.includes("new OpenAPIHono"), `${entry.name} constructs OpenAPIHono directly; use newRouter()`)
    if (source.includes("newRouter()")) routers.push(entry.name)
  }
  assertEquals(routers.sort(), ["admin.ts", "api-keys.ts", "browse.ts", "download.ts", "publish.ts", "rating.ts", "signature.ts"])
})
