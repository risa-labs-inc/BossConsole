/**
 * The store holds two publisher-controlled URLs per plugin, `homepageUrl` and `iconUrl`, and hands
 * both to every client. `z.string().url()` accepts anything `new URL()` parses, which includes
 * `javascript:`, `data:`, `file:` and `smb:` - so a publisher could store a link that runs script when
 * a web store renders it as an anchor, a UNC-style target for a desktop client that opens the
 * homepage, or an icon URL that is not an image fetch at all.
 *
 * Only http(s) URLs with a host and no embedded credentials belong here. The publish schema refuses
 * anything else, the GitHub-manifest publish paths (which read `url`/`iconUrl` out of a repository's
 * plugin.json and never went through the schema) fall back rather than store it, and the read path
 * sanitises rows that were stored before this existed.
 */

import { assert, assertEquals, assertFalse } from "@std/assert"
import { PublishPluginRequestSchema } from "../types/schemas.ts"
import { httpUrlOr, httpUrlOrNull, isHttpUrl } from "../utils/urls.ts"

const base = {
  pluginId: "acme.tool",
  displayName: "Acme Tool",
  homepageUrl: "https://example.com/acme",
}

const hostile = [
  "javascript:alert(1)",
  "JaVaScRiPt:alert(1)",
  "data:text/html,<script>alert(1)</script>",
  "file:///etc/passwd",
  "smb://attacker/share",
  "ftp://example.com/x",
  "vbscript:msgbox(1)",
  "//example.com/no-scheme",
  "https://",
  "https://user:secret@example.com/",
]

Deno.test("the publish schema refuses a homepageUrl that is not http(s)", () => {
  for (const url of hostile) {
    const parsed = PublishPluginRequestSchema.safeParse({ ...base, homepageUrl: url })
    assertFalse(parsed.success, `homepageUrl should be refused: ${url}`)
  }
})

Deno.test("the publish schema refuses an iconUrl that is not http(s)", () => {
  for (const url of hostile) {
    const parsed = PublishPluginRequestSchema.safeParse({ ...base, iconUrl: url })
    assertFalse(parsed.success, `iconUrl should be refused: ${url}`)
  }
})

Deno.test("the publish schema still accepts ordinary http(s) URLs and an empty icon", () => {
  for (const url of ["https://example.com/acme", "http://example.com:8080/a?b=c#d", "https://github.com/acme/tool"]) {
    assert(PublishPluginRequestSchema.safeParse({ ...base, homepageUrl: url }).success, url)
    assert(PublishPluginRequestSchema.safeParse({ ...base, iconUrl: url }).success, url)
  }
  assert(PublishPluginRequestSchema.safeParse({ ...base, iconUrl: "" }).success)
  assert(PublishPluginRequestSchema.safeParse(base).success, "iconUrl stays optional")
})

Deno.test("isHttpUrl accepts only http(s) with a host and no credentials", () => {
  for (const url of hostile) assertFalse(isHttpUrl(url), url)
  for (const url of [42, null, undefined, {}, ""]) assertFalse(isHttpUrl(url), String(url))
  assert(isHttpUrl("https://example.com/"))
  assert(isHttpUrl("HTTPS://EXAMPLE.COM/x"))
  assertFalse(isHttpUrl("https://example.com/" + "a".repeat(2100)), "an unreasonably long URL is refused")
})

Deno.test("httpUrlOrNull and httpUrlOr keep the URL or fall back, never store a bad one", () => {
  assertEquals(httpUrlOrNull("https://example.com/a"), "https://example.com/a")
  assertEquals(httpUrlOrNull("javascript:alert(1)"), null)
  assertEquals(httpUrlOrNull(undefined), null)
  assertEquals(httpUrlOr("javascript:alert(1)", "https://github.com/acme/tool"), "https://github.com/acme/tool")
  assertEquals(httpUrlOr("https://example.com/a", "https://github.com/acme/tool"), "https://example.com/a")
})
