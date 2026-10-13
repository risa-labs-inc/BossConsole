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
