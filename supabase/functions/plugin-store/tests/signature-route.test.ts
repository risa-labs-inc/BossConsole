/**
 * Wiring guards + limiter tests for the signature-only route (BossConsole#108).
 *
 * Why the wiring guards and not only behavioural tests: the route's decisions
 * all sit behind a live database (user_can_install_plugin, plugin_versions),
 * which no unit test can reach — the same reasoning as the download guards in
 * tests/auth-permissions.test.ts. Deleting a gate call here leaves every
 * behavioural test green AND type-checking, so the only test that can notice
 * is one that reads this file and asserts the wiring.
 *
 * What these lock down:
 *   - The signature handler gates callers exactly like a download handler:
 *     organisation visibility first, denied with the same 404 a missing
 *     plugin gets, then the install-permission gate. The route reveals the
 *     same per-version facts as a download response, so it must not be the
 *     weak sibling an enumeration attempt switches to.
 *   - The route NEVER calls recordDownload and NEVER mints a signed download
 *     URL. That absence is the entire point of the route: the host's sidecar
 *     backfill already holds the JAR and previously booked a plugin_downloads
 *     row per attempt through the download endpoint — unbounded for answers
 *     that stay retryable, like a row published before store signing.
 *   - The route opens with the 60/min per-client brake, in its own
 *     `signature-info:` bucket, BEFORE any database work — pinned both as a
 *     source-scan wiring guard and behaviourally (the 61st request from one
 *     client is a 429 that never reaches the database stub).
 *   - The response contract cannot carry a downloadUrl without this file
 *     failing CI — the source scan here is the guard; response schemas in
 *     @hono/zod-openapi are documentation, so the schema alone would not
 *     stop a handler from adding the field to its ctx.json body.
 *   - index.ts still mounts the route; a route defined but never mounted is
 *     how a wiring fix ships silently inert.
 *
 * Run: deno test --allow-all tests/signature-route.test.ts
 */
import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import { SignatureInfoResponseSchema, DownloadInfoResponseSchema } from "../types/schemas.ts"
import type { PluginStoreContext } from "../types/context.ts"
import signature from "../routes/signature.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

const routeSource = (name: string) =>
  Deno.readTextFileSync(new URL(`../routes/${name}`, import.meta.url))

Deno.test("the signature handler gates on organisation visibility before anything else", () => {
  const src = routeSource("signature.ts")

  const handlers = src.match(/signature\.openapi\(/g)?.length ?? 0
  assertEquals(handlers, 1, "the one signature handler")

  const gated = src.match(/await canInstall\(supabase,/g)?.length ?? 0
  assertEquals(
    gated,
    handlers,
    "an ungated signature handler hands another organisation's plugin verdict to " +
      "anyone who can guess a plugin id",
  )

  // Order is the property, not just presence, and matches the download
  // handlers: visibility BEFORE the permission gate, so a plugin the caller
  // may not see never reaches a 403 that confirms it exists.
  const chunk = src.split(/signature\.openapi\(/).slice(1)[0]
  const gate = chunk.indexOf("canInstall(")
  const permission = chunk.indexOf("installGateError(")
  assertEquals(gate >= 0, true, "handler has no visibility gate")
  assertEquals(gate < permission, true, "handler gates permissions before visibility")
})

Deno.test("a plugin the caller cannot see gets the same 404, never a 403", () => {
  const src = routeSource("signature.ts")

  // 403 from the visibility gate would confirm the plugin exists — the
  // enumeration surface the download handlers are careful to avoid, and this
  // route must not become the easier door.
  const gateBlocks = src.match(/if \(!await canInstall\([^)]*\)\) \{\s*return ctx\.json\(\{ error: 'Plugin not found' \}, 404\)/g)
  assertEquals(gateBlocks?.length ?? 0, 1, "the visibility gate must deny with the download handlers' 404")
})

Deno.test("the signature route never books a download nor mints a signed URL", () => {
  const src = routeSource("signature.ts")

  // Call form, not bare identifier: the route's comments legitimately explain
  // why recordDownload must never run, and a bare-identifier scan would fail on
  // that documentation. Same convention as the download guards in
  // tests/auth-permissions.test.ts, which also match the call form.
  assertEquals(
    src.includes("recordDownload("),
    false,
    "recordDownload here is the bug this route exists to remove: the sidecar backfill " +
      "books a plugin_downloads row per attempt on a JAR it already holds",
  )
  assertEquals(
    src.includes("getSignedDownloadUrl"),
    false,
    "a signed URL nobody consumes still costs a storage round trip and widens the surface",
  )
  assertEquals(
    src.includes("downloadUrl"),
    false,
    "the response contract is the verdict, not a URL; THIS source scan is the wiring " +
      "guard — @hono/zod-openapi validates requests only, so the schema below documents " +
      "the shape but would not fail a handler that grew the field",
  )
})

Deno.test("the signature route is rate limited before any work, in its own bucket", () => {
  const src = routeSource("signature.ts")
  const chunk = src.split(/signature\.openapi\(/).slice(1)[0]

  // Its own bucket, not the shared download-info one, for the same reason
  // download-info has its own: a burst of signature probes must not spend a
  // client's download budget, and a download flood must not lock out the
  // backfill.
  assertEquals(
    src.includes("`signature-info:${clientKey("),
    true,
    "the brake must key on its own signature-info: bucket",
  )
  assertEquals(
    src.includes("download-info:"),
    false,
    "sharing the download bucket would couple the two routes' budgets",
  )

  // Order is the property, exactly like the gate order above: the brake runs
  // before getPlugin's visibility RPCs, or an attacker's flood is billed to
  // the database.
  const brake = chunk.indexOf("rateLimit(")
  const work = chunk.indexOf("getPlugin(")
  assertEquals(brake >= 0, true, "handler has no rate limit")
  assertEquals(brake < work, true, "the handler does work before the rate limit")

  // The 429 is part of the declared contract, not an implementation detail,
  // and it tells the client when to retry.
  assertEquals(src.includes("429:"), true, "the 429 must be declared in the OpenAPI responses")
  assertEquals(
    chunk.includes('ctx.header("Retry-After"'),
    true,
    "a 429 must carry Retry-After",
  )
})

Deno.test("the sixty-first request from one client is a 429 that never reaches the database", async () => {
  resetRateLimits()

  // The stub answers every RPC with "no rows", so each ungated request walks
  // all the way to the first gate (getPlugin) and returns its 404 — the RPC
  // counter is what proves how far each request got.
  const rpcs: string[] = []
  const client = {
    rpc: (fn: string) => {
      rpcs.push(fn)
      return Promise.resolve({ data: [], error: null })
    },
    from: (_table: string) => {
      throw new Error("signature lookup must not read the database directly")
    },
    auth: {
      getUser: () => Promise.resolve({ data: { user: null }, error: null }),
    },
  } as unknown as SupabaseClient

  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", signature)

  const flood = { "x-forwarded-for": "203.0.113.9" }
  for (let i = 0; i < 60; i++) {
    const response = await app.request("/com.example.demo/signature/1.0.0", { headers: flood })
    assertEquals(response.status, 404, "inside the window the stub's empty answer is a plain 404")
  }
  assertEquals(rpcs.length, 60, "each of the first sixty requests reached getPlugin")

  const throttled = await app.request("/com.example.demo/signature/1.0.0", { headers: flood })
  assertEquals(throttled.status, 429)
  assertEquals(await throttled.json(), { error: "Too many requests; try again later" })
  assertEquals(Number(throttled.headers.get("Retry-After")) > 0, true)
  assertEquals(rpcs.length, 60, "the throttled request must not have reached the database")

  // A different client has an independent budget — a brake per client, not a
  // global switch.
  const otherClient = await app.request("/com.example.demo/signature/1.0.0", {
    headers: { "x-forwarded-for": "198.51.100.7" },
  })
  assertEquals(otherClient.status, 404)
  assertEquals(rpcs.length, 61, "a fresh client still gets its own lookups")
})

Deno.test("the signature response schema carries the verdict and nothing else", () => {
  const shape = SignatureInfoResponseSchema.shape

  // The fields the host binds a sidecar from, or settles "unsigned" with.
  assertEquals(typeof shape.sha256 !== "undefined", true, "sha256 is the bytes the store vouches for")
  assertEquals(typeof shape.signature !== "undefined", true, "the signature itself")
  assertEquals(typeof shape.version !== "undefined", true, "version echo")
  assertEquals(typeof shape.pluginId !== "undefined", true, "pluginId echo")
  assertEquals(typeof shape.versionId !== "undefined", true, "versionId for diagnostics")

  // Absence is the contract: no URL to hand out, so nothing to mint or gate
  // beyond the install checks, and nothing to accidentally start booking.
  // Documentation, not enforcement — the enforcement is the source scan in
  // the no-booking test above; keeping the field out of the schema is what
  // documents the contract the scan checks for.
  assertEquals("downloadUrl" in shape, false, "a downloadUrl here would re-couple the route to the download path")

  // The download contract itself must keep carrying the signature field the
  // host's verification depends on (see types/schemas.ts): moving the field
  // here-only would silently strip it from the real download path.
  assertEquals("signature" in DownloadInfoResponseSchema.shape, true)
})

Deno.test("index mounts the signature route", () => {
  const src = Deno.readTextFileSync(new URL("../index.ts", import.meta.url))

  // A route defined but never mounted is how a fix ships silently inert: the
  // client would call it forever and only ever see the generic 404.
  assertEquals(
    src.includes('app.route("/", signature)'),
    true,
    "the signature route must be mounted",
  )
})
