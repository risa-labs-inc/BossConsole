/**
 * Tests for the redirect Edge Function — the security-sensitive output escaping
 * and the per-app (BOSS Console vs BossTerm) brand/scheme/token-param selection
 * added in risa-labs-inc/BossConsole#787. No network: the Hono app is driven
 * in-process via app.request().
 *
 * Run: cd supabase/functions/redirect && deno test --config deno.json
 */

import { assert, assertEquals, assertStringIncludes } from "jsr:@std/assert"
// Import the app module (not index.ts, which calls Deno.serve) so no listener starts under test.
import { app, htmlAttr, jsStringLiteral } from "../app.ts"

async function pageFor(path: string): Promise<string> {
  return await (await app.request(path)).text()
}

// (a) Output escaping neutralizes quote / double-quote / < / </script>.

Deno.test("htmlAttr neutralizes ' \" < > &", () => {
  assertEquals(htmlAttr(`a'b"c<d>e&f`), "a&#39;b&quot;c&lt;d&gt;e&amp;f")
})

Deno.test("jsStringLiteral is a double-quoted literal escaping \" and <", () => {
  assertEquals(jsStringLiteral("a'b"), `"a'b"`)          // ' is harmless inside a "…" literal
  assertEquals(jsStringLiteral(`a"b`), `"a\\"b"`)        // " is JSON-escaped
  assertEquals(jsStringLiteral("</script>"), `"\\u003c/script>"`) // < escaped → can't close <script>
})

Deno.test("a crafted single-quote token can't break out of the JS string or the href", async () => {
  // encodeURIComponent leaves ' ( ) - . unescaped, so this is the real reflected-XSS payload.
  const html = await pageFor("/redirect?app=bossterm&token=" + encodeURIComponent("'-alert(document.cookie)-'"))
  assertStringIncludes(html, 'window.location.href = "')                    // double-quoted JSON literal
  assert(!html.includes("window.location.href = '"), "must not emit a single-quoted JS string")
  assertStringIncludes(html, "&#39;-alert(document.cookie)-&#39;")          // href escapes the quotes
})

// (b) app=bossterm and redirect_to=bossterm://auth/verify both select BossTerm.

Deno.test("app=bossterm → BossTerm brand + scheme + token_hash", async () => {
  const html = await pageFor("/redirect?app=bossterm&token=abc&type=magiclink")
  assertStringIncludes(html, "bossterm://auth/verify?token_hash=abc&type=magiclink")
  assertStringIncludes(html, "<h1>BossTerm</h1>")
})

Deno.test("top-level token_hash param is accepted as the token", async () => {
  const html = await pageFor("/redirect?app=bossterm&token_hash=xyz&type=magiclink")
  assertStringIncludes(html, "bossterm://auth/verify?token_hash=xyz&type=magiclink")
})

Deno.test("redirect_to=bossterm://auth/verify → BossTerm", async () => {
  const html = await pageFor("/redirect?token=abc&redirect_to=" + encodeURIComponent("bossterm://auth/verify"))
  assertStringIncludes(html, "bossterm://auth/verify?token_hash=abc")
  assertStringIncludes(html, "<h1>BossTerm</h1>")
})

Deno.test("type passes through verbatim (signup, not magiclink)", async () => {
  assertStringIncludes(
    await pageFor("/redirect?app=bossterm&token=t&type=signup"),
    "bossterm://auth/verify?token_hash=t&type=signup",
  )
})

Deno.test("non-exact redirect_to falls back to BOSS (exact-match contract)", async () => {
  assertStringIncludes(
    await pageFor("/redirect?token=abc&redirect_to=" + encodeURIComponent("bossterm://auth/verify/x")),
    "boss://auth/verify?token=abc",
  )
})

// (c) Default falls back to boss://…?token=.

Deno.test("default → BOSS Console brand + scheme + token param", async () => {
  const html = await pageFor("/redirect?token=abc&type=magiclink")
  assertStringIncludes(html, "boss://auth/verify?token=abc&type=magiclink")
  assertStringIncludes(html, "<h1>BOSS Console</h1>")
})

// (d) Token extracted from the url= confirmation URL.

Deno.test("extracts token + type + app from a percent-encoded url= confirmation URL", async () => {
  const conf =
    "https://api.risaboss.com/auth/v1/verify?token=tok123&type=magiclink&redirect_to=bossterm://auth/verify"
  assertStringIncludes(
    await pageFor("/redirect?url=" + encodeURIComponent(conf)),
    "bossterm://auth/verify?token_hash=tok123&type=magiclink",
  )
})

Deno.test("real email flow: unencoded ConfirmationURL (&-split) preserves type=signup", async () => {
  // GoTrue's mailer uses text/template, so the email's ?url={{ .ConfirmationURL }} is NOT
  // percent-encoded — the confirmation URL's &type=&redirect_to= split into top-level params on
  // this request. A first-time user's link carries type=signup; it must survive into the deep
  // link (the bug: it was being overwritten with "magiclink", which GoTrue then rejects).
  const html = await pageFor(
    "/redirect?url=https://proj.supabase.co/auth/v1/verify?token=HASH&type=signup&redirect_to=bossterm://auth/verify",
  )
  assertStringIncludes(html, "bossterm://auth/verify?token_hash=HASH&type=signup")
  assertStringIncludes(html, "<h1>BossTerm</h1>")
})

Deno.test("missing token → 400", async () => {
  const res = await app.request("/redirect")
  assertEquals(res.status, 400)
  assertStringIncludes(await res.text(), "Missing 'token' parameter")
})

Deno.test("health check", async () => {
  const res = await app.request("/redirect/health")
  assertEquals(res.status, 200)
  assertStringIncludes(await res.text(), "healthy")
})
