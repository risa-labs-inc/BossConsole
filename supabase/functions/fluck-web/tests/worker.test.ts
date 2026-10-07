/**
 * The fluck.risaboss.com alias Worker (infra/cloudflare/fluck-web-alias/worker.js), with the global
 * fetch stubbed. Lives here so the fluck-web CI job runs it; the last test chains it into the app.
 */

import { assert, assertEquals } from "@std/assert"
import worker, { isOwnCookie } from "../../../../infra/cloudflare/fluck-web-alias/worker.js"
import { app } from "../app.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

const SECRET = "s".repeat(64)
const ENV = { FLUCK_WEB_ALIAS_SECRET: SECRET }

async function through(
  request: Request,
  upstream: (req: Request) => Response | Promise<Response>,
  env: Record<string, string> = ENV,
): Promise<{ seen: Request; res: Response }> {
  const original = globalThis.fetch
  let seen: Request | null = null
  globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
    seen = new Request(input, init)
    return Promise.resolve(upstream(seen))
  }
  try {
    const res = await worker.fetch(request, env)
    return { seen: seen!, res }
  } finally {
    globalThis.fetch = original
  }
}

Deno.test("worker maps the path onto the function and strips visitor-supplied trust headers", async () => {
  const req = new Request("https://fluck.risaboss.com/api/instances?x=1", {
    headers: {
      "CF-Connecting-IP": "198.51.100.7",
      "X-Fluck-Web-Client-Ip": "10.9.9.9",
      "X-Fluck-Web-Alias-Secret": "forged",
      "X-Forwarded-Proto": "http",
      cookie: "a=b",
    },
  })
  const { seen } = await through(req, () => new Response("{}"))
  assertEquals(seen.url, "https://api.risaboss.com/functions/v1/fluck-web/api/instances?x=1")
  assertEquals(seen.headers.get("x-fluck-web-client-ip"), "198.51.100.7")
  assertEquals(seen.headers.get("x-fluck-web-alias-secret"), SECRET)
  assertEquals(seen.headers.get("x-fluck-web-alias"), "fluck.risaboss.com")
  assertEquals(seen.headers.get("x-forwarded-proto"), "https")
  assertEquals(seen.headers.get("cookie"), "a=b")
})

Deno.test("worker drops a visitor's X-Fluck-Web-Client-Ip when CF-Connecting-IP is absent, and the inbound Host", async () => {
  const req = new Request("https://fluck.risaboss.com/", { headers: { "X-Fluck-Web-Client-Ip": "10.9.9.9", host: "fluck.risaboss.com" } })
  const { seen } = await through(req, () => new Response("ok"))
  assertEquals(seen.url, "https://api.risaboss.com/functions/v1/fluck-web")
  assertEquals(seen.headers.get("x-fluck-web-client-ip"), null)
  assertEquals(seen.headers.get("host"), null)
})

Deno.test("worker without its secret forwards no secret (the function then refuses, never loops)", async () => {
  const req = new Request("https://fluck.risaboss.com/", { headers: { "X-Fluck-Web-Alias-Secret": "forged" } })
  const { seen } = await through(req, () => new Response("ok"), {})
  assertEquals(seen.headers.get("x-fluck-web-alias-secret"), null)
})

Deno.test("worker keeps the function's cookies and drops upstream cookies scoped to another domain", async () => {
  const upstream = new Headers()
  upstream.append("Set-Cookie", "__Secure-boss_fluck_at=t; Path=/; HttpOnly; Secure; SameSite=Lax")
  upstream.append("Set-Cookie", "__cf_bm=abc; HttpOnly; SameSite=None; Secure; Path=/; Domain=api.risaboss.com")
  upstream.append("Set-Cookie", "own=1; Domain=.Fluck.Risaboss.com; Path=/")
  upstream.set("Content-Type", "application/json")
  const { res } = await through(new Request("https://fluck.risaboss.com/api/session", { method: "POST", body: "{}" }), () => new Response("{}", { status: 201, headers: upstream }))
  assertEquals(res.status, 201)
  assertEquals(res.headers.get("content-type"), "application/json")
  const cookies = res.headers.getSetCookie()
  assertEquals(cookies.length, 2)
  assert(cookies[0].startsWith("__Secure-boss_fluck_at="))
  assert(cookies[1].startsWith("own="))
})

Deno.test("isOwnCookie", () => {
  assert(isOwnCookie("a=b; Path=/", "fluck.risaboss.com"))
  assert(isOwnCookie("a=b; domain=fluck.risaboss.com", "fluck.risaboss.com"))
  assert(!isOwnCookie("a=b; Domain=risaboss.com", "fluck.risaboss.com"))
  assert(!isOwnCookie("a=b; Domain=api.risaboss.com", "fluck.risaboss.com"))
})

Deno.test("worker answers 502 when the upstream fetch throws", async () => {
  const original = console.error
  console.error = () => {}
  try {
    const { res } = await through(new Request("https://fluck.risaboss.com/health"), () => {
      throw new Error("boom")
    })
    assertEquals(res.status, 502)
  } finally {
    console.error = original
  }
})

Deno.test("worker -> function end to end: the page is served via the alias, and a forged direct marker is not", async () => {
  Deno.env.set("SUPABASE_URL", "https://stack.example")
  Deno.env.set("SUPABASE_ANON_KEY", "anon-key")
  Deno.env.set("FLUCK_WEB_PUBLIC_BASE_URL", "https://fluck.risaboss.com")
  Deno.env.set("FLUCK_WEB_PUBLIC_BASE_PATH", "/")
  Deno.env.set("FLUCK_WEB_ALIAS_SECRET", SECRET)
  resetRateLimits()
  // The edge gateway strips /functions/v1; the function sees the API host.
  const toApp = (req: Request) => {
    const u = new URL(req.url)
    const headers = new Headers(req.headers)
    headers.set("host", u.host)
    return app.request(u.pathname.replace("/functions/v1", "") + u.search, { method: req.method, headers })
  }
  try {
    const { res } = await through(new Request("https://fluck.risaboss.com/?instance=inst-a", { headers: { "CF-Connecting-IP": "198.51.100.7" } }), toApp)
    assertEquals(res.status, 200)
    assert((await res.text()).includes('"basePath":""'))
    const forged = await app.request("/fluck-web/", { headers: { host: "api.risaboss.com", "x-fluck-web-alias": "fluck.risaboss.com" } })
    assertEquals(forged.status, 503)
    await forged.body?.cancel()
  } finally {
    for (const n of ["SUPABASE_URL", "SUPABASE_ANON_KEY", "FLUCK_WEB_PUBLIC_BASE_URL", "FLUCK_WEB_PUBLIC_BASE_PATH", "FLUCK_WEB_ALIAS_SECRET"]) Deno.env.delete(n)
  }
})
