/**
 * Adversarial tests for the API-key audit persistence boundary.
 *
 * plugin_api_key_logs is the audit trail for plugin-store API-key usage. The
 * caller with a valid key controls several of the raw inputs that reach it
 * through logApiKeyAction() -> log_api_key_action(): the x-forwarded-for chain,
 * the user-agent header, the pluginId string, and (via a future call site)
 * the error message. These tests pin that NONE of those surfaces can persist
 * caller-chosen free text into an audit row:
 *
 *   - ip_address must be the address a TRUSTED hop reported (cf-connecting-ip,
 *     else the rightmost XFF entry — the leftmost is caller-typed), and must
 *     be address-shaped or absent; never "whatever the client sent".
 *   - user_agent must be bounded and free of control characters, so no CRLF
 *     forging and no parking megabytes of payload in the audit table.
 *   - plugin_id must match the plugin-id shape or persist as null.
 *   - action must be a server word; anything else degrades to 'unknown'.
 *   - error_message must be bounded and control-char free, or null.
 *
 * Run: deno test --allow-all tests/audit-shape.test.ts
 */
import { assert, assertEquals } from "@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { logApiKeyAction } from "../utils/auth.ts"
import {
  auditAction,
  auditClientIp,
  auditErrorMessage,
  auditUserAgent,
  AUDIT_USER_AGENT_MAX,
} from "../utils/audit-shape.ts"

const KEY_ID = "22222222-2222-2222-2222-222222222222"

/** Records every log_api_key_action payload the boundary tries to persist. */
function auditRecorder(): { client: SupabaseClient; payloads: Array<Record<string, unknown>> } {
  const payloads: Array<Record<string, unknown>> = []
  const client = {
    rpc: (fn: string, args: Record<string, unknown>) => {
      if (fn === "log_api_key_action") payloads.push(args)
      return Promise.resolve({ data: null, error: null })
    },
  } as unknown as SupabaseClient
  return { client, payloads }
}

function request(headers: Record<string, string>): Request {
  return new Request("https://api.example.com/functions/v1/plugin-store/publish", {
    method: "POST",
    headers,
  })
}

// ---------------------------------------------------------------------------
// ip_address: trusted hop only
// ---------------------------------------------------------------------------

Deno.test("leftmost XFF is caller-typed and never persisted; the rightmost trusted hop wins", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    "ai.rever.boss.form-assist",
    request({ "x-forwarded-for": "attacker@corp.example, 203.0.113.7" }),
  )
  assertEquals(payloads.length, 1)
  assertEquals(payloads[0].p_ip_address, "203.0.113.7")
})

Deno.test("multi-hop XFF resolves to the single address the trusted proxy appended", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    undefined,
    request({ "x-forwarded-for": "8.8.8.8, 10.0.0.1, 198.51.100.2" }),
  )
  assertEquals(payloads[0].p_ip_address, "198.51.100.2")
})

Deno.test("a spoofed first XFF hop is not persisted as the client address", async () => {
  // The exact attack the old code enabled: claim to be someone else's address.
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    undefined,
    request({ "x-forwarded-for": "203.0.113.99, 198.51.100.2" }),
  )
  assertEquals(payloads[0].p_ip_address, "198.51.100.2")
})

Deno.test("cf-connecting-ip (trusted edge) takes precedence over any XFF chain", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    undefined,
    request({
      "cf-connecting-ip": "203.0.113.9",
      "x-forwarded-for": "totally-not-an-ip.example, 8.8.8.8",
    }),
  )
  assertEquals(payloads[0].p_ip_address, "203.0.113.9")
})

Deno.test("an XFF that is not address-shaped persists no ip at all", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    undefined,
    request({ "x-forwarded-for": "victim@corp.example.com" }),
  )
  assertEquals(payloads[0].p_ip_address, null)
})

Deno.test("markup, emails and non-address XFF chains never reach the audit row", () => {
  assertEquals(auditClientIp(null, "<svg onload=alert(1)>"), null)
  assertEquals(auditClientIp(null, "https://evil.example/path"), null)
  assertEquals(auditClientIp(null, "1.2.3.4, <script>alert(1)</script>"), null)
  assertEquals(auditClientIp("not-an-ip.example", null), null)
  // Char-allowlist garbage that is neither IPv4 nor IPv6.
  assertEquals(auditClientIp(null, "..."), null)
  assertEquals(auditClientIp(":", null), null)
  assertEquals(auditClientIp(null, ".., 1.2.3.4.999"), null)
})

Deno.test("IPv6 and IPv6-mapped addresses persist in full", () => {
  assertEquals(auditClientIp("2001:db8::1", null), "2001:db8::1")
  assertEquals(auditClientIp(null, "::ffff:203.0.113.5"), "::ffff:203.0.113.5")
})

Deno.test("an address longer than any textual IP form is refused", () => {
  assertEquals(auditClientIp(null, `2001:db8::${"1".repeat(60)}`), null)
})

// ---------------------------------------------------------------------------
// user_agent: bounded, control-char free
// ---------------------------------------------------------------------------

Deno.test("a CRLF payload in the user agent is stripped before persistence (no forged second line)", () => {
  // The Request constructor refuses raw CR/LF in header values, so this payload
  // is tested against the shaper directly: the boundary must strip it, not
  // lean on the transport to have rejected it.
  assertEquals(
    auditUserAgent("Mozilla/5.0\r\nX-Injected-By: attacker\r\n"),
    "Mozilla/5.0X-Injected-By: attacker",
  )
})

Deno.test("control chars that can ride in a real header (tabs) never reach the audit row", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    undefined,
    request({ "user-agent": "boss/1.0\tX-Forge:\tvalue" }),
  )
  const ua = payloads[0].p_user_agent as string
  assertEquals(ua, "boss/1.0X-Forge:value")
  assert(!ua.includes("\t"))
})

Deno.test("a megabyte user agent is truncated, not persisted in full", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    undefined,
    request({ "user-agent": `${"A".repeat(1000)} sk-live-SECRETTOKEN ${"B".repeat(1_000_000)}` }),
  )
  const ua = payloads[0].p_user_agent as string
  assertEquals(ua.length, AUDIT_USER_AGENT_MAX)
  assert(!ua.includes("sk-live-SECRETTOKEN"))
})

Deno.test("empty and control-only user agents persist as null", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(client, KEY_ID, "publish", undefined, request({ "user-agent": "" }))
  assertEquals(payloads[0].p_user_agent, null)
})

Deno.test("an honest user agent persists unchanged", async () => {
  const { client, payloads } = auditRecorder()
  const ua = "boss-desktop/1.2.3 (linux; x86_64)"
  await logApiKeyAction(client, KEY_ID, "publish", undefined, request({ "user-agent": ua }))
  assertEquals(payloads[0].p_user_agent, ua)
})

// ---------------------------------------------------------------------------
// plugin_id: id-shaped or refused
// ---------------------------------------------------------------------------

Deno.test("path traversals, emails and oversized strings never persist as plugin ids", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(client, KEY_ID, "publish", "../../etc/passwd", request({}))
  await logApiKeyAction(client, KEY_ID, "publish", "plugin@evil.example", request({}))
  await logApiKeyAction(client, KEY_ID, "publish", "a b c", request({}))
  await logApiKeyAction(client, KEY_ID, "publish", `${"x".repeat(201)}`, request({}))
  assertEquals(payloads.map((p) => p.p_plugin_id), [null, null, null, null])
})

Deno.test("a shaped plugin id persists so the audit row still names its subject", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(client, KEY_ID, "publish", "ai.rever.boss.form-assist", request({}))
  assertEquals(payloads[0].p_plugin_id, "ai.rever.boss.form-assist")
})

// ---------------------------------------------------------------------------
// action + error_message: server words and bounded diagnostics
// ---------------------------------------------------------------------------

Deno.test("action kinds outside the server vocabulary degrade to the constant 'unknown'", () => {
  assertEquals(auditAction("publish"), "publish")
  assertEquals(auditAction("version"), "version")
  assertEquals(auditAction("finalize"), "finalize")
  assertEquals(auditAction("publish\r\nX-Forge: yes"), "unknown")
  assertEquals(auditAction("PRESS RELEASE buy now"), "unknown")
  assertEquals(auditAction(undefined), "unknown")
})

Deno.test("error messages are bounded, stripped of control characters, or null", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    undefined,
    request({}),
    false,
    `deploy failed for /home/${"u".repeat(1_000)}@corp.example\r\npath: /etc/passwd\n${"E".repeat(5_000)}`,
  )
  const msg = payloads[0].p_error_message as string
  assertEquals(msg.length <= 512, true)
  assert(!msg.includes("\n") && !msg.includes("\r"))
  // The leading real diagnostic survives; the overflow and second line do not.
  assert(msg.startsWith("deploy failed for /home/"))
})

Deno.test("a null error message stays null, not an empty string", () => {
  assertEquals(auditErrorMessage(null), null)
  assertEquals(auditErrorMessage(undefined), null)
  assertEquals(auditErrorMessage("\r\n \u0000"), null)
})

Deno.test("a hostile request produces a fully shaped audit row end to end", async () => {
  const { client, payloads } = auditRecorder()
  await logApiKeyAction(
    client,
    KEY_ID,
    "publish",
    "../steal?from=victim@corp.example",
    request({
      "x-forwarded-for": "victim@corp.example.com, 203.0.113.7",
      "user-agent": "boss/1.0\troot@corp.example: secret-token sk-apisec-0123456789abcdef",
    }),
    false,
    "upstream 500 at https://internal.corp.example/secret-path",
  )
  assertEquals(payloads.length, 1)
  const row = payloads[0]
  assertEquals(row.p_ip_address, "203.0.113.7")
  assertEquals(row.p_plugin_id, null)
  assertEquals(row.p_action, "publish")
  assertEquals(row.p_success, false)
  assert((row.p_user_agent as string).length <= AUDIT_USER_AGENT_MAX)
  assert(!(row.p_user_agent as string).includes("\t"))
  assert((row.p_error_message as string).length <= 512)
})

Deno.test("logApiKeyAction safely logs resolved and rejected audit failures without failing publish", async () => {
  const logged: unknown[][] = []
  const originalError = console.error
  const resolvedFailure = {
    rpc: () =>
      Promise.resolve({
        data: null,
        error: {
          code: "42501",
          message: "permission denied; sensitive value was secret-token",
          details: "secret-token",
          hint: "secret-token",
        },
      }),
  } as unknown as SupabaseClient
  const transportError = new TypeError("request body contained secret-token")
  transportError.name = "secret-token"
  const rejectedFailure = {
    rpc: () => Promise.reject(transportError),
  } as unknown as SupabaseClient

  console.error = (...args: unknown[]) => {
    if (args[0] === "Error logging API key action:") {
      logged.push(args)
    } else {
      originalError(...args)
    }
  }
  try {
    await logApiKeyAction(resolvedFailure, KEY_ID, "publish", "ai.rever.boss.form-assist", request({}))
    await logApiKeyAction(rejectedFailure, KEY_ID, "publish", "ai.rever.boss.form-assist", request({}))
  } finally {
    console.error = originalError
  }

  assertEquals(logged, [
    ["Error logging API key action:", { code: "42501" }],
    ["Error logging API key action:", "TypeError"],
  ])
})

Deno.test("a user agent truncated mid surrogate pair stays well-formed", () => {
  // 255 ASCII chars + one non-BMP char: a code-unit slice would leave a lone
  // high surrogate, which Postgres's JSON input rejects. The RPC resolves
  // with that failure; the best-effort audit caller logs it and keeps going.
  const ua = "A".repeat(255) + "\u{1F600}" + "tail"
  const shaped = auditUserAgent(ua)
  assert(shaped !== null)
  assert(shaped.length <= 256)
  const last = shaped.charCodeAt(shaped.length - 1)
  assert(!(last >= 0xd800 && last <= 0xdbff), "must not end on a lone high surrogate")
})
