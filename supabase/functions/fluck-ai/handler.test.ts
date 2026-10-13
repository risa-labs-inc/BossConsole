import { assertEquals, assertStringIncludes } from "@std/assert"
import { handleRequest } from "./handler.ts"

const aliasSecret = "fixture-secret-".repeat(5)
Deno.env.set("FLUCK_WEB_ALIAS_SECRET", aliasSecret)
Deno.env.set("SUPABASE_URL", "https://test.supabase.co")
Deno.env.set("FLUCK_WEB_AUTH_PUBLIC_URL", "https://api.risaboss.com")

function request(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers)
  headers.set("x-fluck-web-alias", "fluck.ai")
  headers.set("x-fluck-web-alias-secret", aliasSecret)
  headers.set("x-forwarded-proto", "https")
  return new Request("https://api.risaboss.com/fluck-ai" + path, { ...init, headers })
}

Deno.test("new domain serves the portal and health through the shared router", async () => {
  const page = await handleRequest(request("/"))
  assertEquals(page.status, 200)
  assertStringIncludes(await page.text(), "Continue with Apple")
  const health = await handleRequest(request("/health"))
  assertEquals(health.status, 200)
  assertEquals(await health.json(), { status: "healthy" })
  assertEquals((await handleRequest(new Request("https://example.com/fluck-ai-other/"))).status, 404)
})

Deno.test("Apple and Google return to fluck.ai with host-only PKCE cookies", async () => {
  for (const provider of ["apple", "google"]) {
    const response = await handleRequest(request("/api/oauth/" + provider))
    assertEquals(response.status, 302)
    const location = new URL(response.headers.get("location")!)
    assertEquals(location.origin, "https://api.risaboss.com")
    assertEquals(location.searchParams.get("redirect_to"), "https://fluck.ai/auth")
    assertEquals(location.searchParams.get("code_challenge_method"), "s256")
    assertStringIncludes(response.headers.get("set-cookie")!, "Path=/;")
    assertEquals(response.headers.get("set-cookie")!.includes("Domain="), false)
  }
})

Deno.test("alias authentication and cross-site checks remain enforced", async () => {
  const unverified = request("/")
  unverified.headers.delete("x-fluck-web-alias-secret")
  assertEquals((await handleRequest(unverified)).status, 503)
  assertEquals((await handleRequest(request("/api/oauth/apple", {
    headers: { "sec-fetch-site": "cross-site" },
  }))).status, 403)
})

Deno.test("fluck.ai serves the push worker, manifest and icon at the root, and push stays on the portal origin", async () => {
  const sw = await handleRequest(request("/sw.js"))
  assertEquals(sw.status, 200)
  assertEquals(sw.headers.get("cache-control"), "no-cache")
  assertEquals(sw.headers.get("service-worker-allowed"), "/")
  assertStringIncludes(sw.headers.get("content-type")!, "application/javascript")
  const manifest = await (await handleRequest(request("/manifest.webmanifest"))).json()
  assertEquals(manifest.start_url, "/")
  assertEquals(manifest.icons[0].src, "/icon-512.png")
  assertEquals((await handleRequest(request("/icon-512.png"))).headers.get("content-type"), "image/png")
  const page = await handleRequest(request("/"))
  assertStringIncludes(page.headers.get("content-security-policy")!, "worker-src 'self'")
  assertStringIncludes(await page.text(), '<link rel="manifest" href="/manifest.webmanifest">')
  // Unconfigured VAPID: 503, and a bad body is refused before anything else.
  const bad = await handleRequest(request("/api/push/notify", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" }))
  assertEquals(bad.status, 400)
  const config = await handleRequest(request("/api/push/config"))
  assertEquals(config.status, 503)
})
