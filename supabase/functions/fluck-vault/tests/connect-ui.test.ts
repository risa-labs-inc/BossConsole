/**
 * The shared page chrome: field validation, the result page, the stylesheet.
 *
 * Run: cd supabase/functions/fluck-vault && deno task test
 */
import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import {
  agentRef,
  parseAgentName,
  parseReturnFields,
  parseReturnUrl,
  resultHtml,
  sha256Base64,
  STATIC_CSP,
  STYLE,
} from "../../_shared/connect-ui.ts"

Deno.test("agent names are trimmed, 1 to 40 characters, with no control characters", () => {
  assertEquals(parseAgentName("  Max "), "Max")
  assertEquals(parseAgentName("x".repeat(40)), "x".repeat(40))
  assertEquals(parseAgentName("x".repeat(41)), null)
  assertEquals(parseAgentName("   "), null)
  assertEquals(parseAgentName(""), null)
  assertEquals(parseAgentName("a\tb"), null)
  assertEquals(parseAgentName("a\u0085b"), null)
  assertEquals(parseAgentName("a b"), null)
  assertEquals(parseAgentName(undefined), null)
  assertEquals(parseAgentName({}), null)
})

Deno.test("return URLs: https anywhere, http only on loopback, no credentials, 512 at most", () => {
  assertEquals(parseReturnUrl("https://a.example/c"), "https://a.example/c")
  assertEquals(parseReturnUrl("http://localhost:1/c"), "http://localhost:1/c")
  assertEquals(parseReturnUrl("http://127.0.0.1/c"), "http://127.0.0.1/c")
  assertEquals(parseReturnUrl("http://[::1]/c"), "http://[::1]/c")
  for (
    const bad of [
      "http://a.example/c",
      "http://localhost.evil.test/c",
      "https://u@a.example/",
      "https://u:p@a.example/",
      "javascript:alert(1)",
      "data:text/html,x",
      "ftp://a.example/",
      "//a.example/",
      "/relative",
      "",
      "https://a.example/" + "a".repeat(500),
    ]
  ) assertEquals(parseReturnUrl(bad), null, bad)
  assertEquals(parseReturnUrl(null), null)
})

Deno.test("absent fields are silent, unusable ones log once", () => {
  const logs: string[] = []
  assertEquals(parseReturnFields(undefined, null, (l) => logs.push(l)), {
    agentName: null,
    returnUrl: null,
  })
  assertEquals(logs.length, 0)
  parseReturnFields("", "nope", (l) => logs.push(l))
  assertEquals(logs, ["ignored invalid agentName and returnUrl"])
})

Deno.test("the agent reference falls back and capitalises only the fallback", () => {
  assertEquals(agentRef(null), { lower: "your agent", upper: "Your agent" })
  assertEquals(agentRef("max"), { lower: "max", upper: "max" })
})

Deno.test("result pages: success with a way back redirects, without says close", () => {
  const url = "https://a.example/c?x=1&y=2"
  const ok = resultHtml({
    tone: "ok",
    title: "Saved",
    text: "Done.",
    returnUrl: url,
    redirect: true,
  })
  assertStringIncludes(
    ok,
    `<meta http-equiv="refresh" content="2;url=https://a.example/c?x=1&amp;y=2">`,
  )
  assertStringIncludes(ok, "Back to chat")
  assertStringIncludes(ok, "Taking you back to the chat")
  assert(!ok.includes("<script"))

  const closed = resultHtml({ tone: "ok", title: "Saved", text: "Done." })
  assertStringIncludes(closed, "You can close this page.")
  assert(!closed.includes("Back to chat") && !closed.includes("http-equiv"))

  const err = resultHtml({ tone: "bad", title: "Oops", text: "No.", returnUrl: url })
  assertStringIncludes(err, "btn-secondary")
  assert(!err.includes("http-equiv") && !err.includes("Taking you back"))

  const fixedErr = resultHtml({ tone: "bad", title: "Oops", text: "No." })
  assert(!fixedErr.includes("Back to chat") && !fixedErr.includes("close this page"))
})

Deno.test("an invalid return URL handed straight to the renderer is ignored", () => {
  const html = resultHtml({
    tone: "ok",
    title: "t",
    text: "x",
    returnUrl: "javascript:1",
    redirect: true,
  })
  assert(!html.includes("javascript:") && !html.includes("http-equiv"))
})

Deno.test("the static CSP pins the stylesheet and forbids everything else", async () => {
  assertStringIncludes(STATIC_CSP, `style-src 'sha256-${await sha256Base64(STYLE)}'`)
  assertStringIncludes(STATIC_CSP, "default-src 'none'")
  assertStringIncludes(STATIC_CSP, "frame-ancestors 'none'")
  assertStringIncludes(STATIC_CSP, "base-uri 'none'")
  assertStringIncludes(STATIC_CSP, "form-action 'none'")
})
