import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import browse from "../routes/browse.ts"
import publish from "../routes/publish.ts"

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

Deno.test("github/metadata masks a GitHub outage behind a fixed envelope", async () => {
  // POST /github/metadata gates on plugins.create before it touches GitHub, and
  // a JWT whose payload carries the permission satisfies that gate from the
  // claim alone (userHasPermission short-circuits, so no RPC has to be
  // stubbed). The first thing that can then fail is the repo-visibility probe:
  // a network call whose raw failure text (upstream hostnames, proxy details)
  // used to land in the response body (issue #770). The 502 must carry the
  // fixed envelope while the detail stays on the server-side log.
  const ownerId = "11111111-1111-1111-1111-111111111111"

  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  const token = `${b64({ alg: "HS256", typ: "JWT" })}.${
    b64({ sub: ownerId, is_admin: false, user_permissions: ["plugins.create"] })
  }.sig`

  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  const client = {
    auth: {
      // getAuthenticatedUser verifies the token here; the RBAC claims it acts
      // on are decoded from the token string itself.
      getUser: (jwt?: string) =>
        Promise.resolve(
          jwt
            ? { data: { user: { id: ownerId, email: "owner@test" } }, error: null }
            : { data: { user: null }, error: new Error("bad token") },
        ),
    },
  } as unknown as SupabaseClient
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", publish)

  const diagnostic = "getaddrinfo ENOTFOUND api.github.com via internal proxy 10.0.0.1"
  const originalFetch = globalThis.fetch
  globalThis.fetch = (() => {
    throw new Error(diagnostic)
  }) as typeof fetch

  const logged: string[] = []
  const originalError = console.error
  console.error = ((...args: unknown[]) => {
    logged.push(args.map((arg) => Deno.inspect(arg)).join(" "))
  }) as typeof console.error

  try {
    const response = await app.request("/github/metadata", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        githubUrl: "https://github.com/example-owner/example-repo",
        sha256: "a".repeat(64),
      }),
    })

    assertEquals(response.status, 502)
    const body = await response.text()
    assertEquals(
      JSON.parse(body),
      { success: false, error: "Could not determine repository visibility" },
    )
    assertEquals(body.includes(diagnostic), false)
  } finally {
    globalThis.fetch = originalFetch
    console.error = originalError
  }

  // The raw failure detail belongs on the server-side log, and must be there.
  assertEquals(
    logged.some((line) => line.includes(diagnostic)),
    true,
    "the GitHub failure detail must reach the server-side log",
  )
})
