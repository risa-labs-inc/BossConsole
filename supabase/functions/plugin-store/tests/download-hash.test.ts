import { assertEquals, assertNotEquals, assertMatch } from "@std/assert"
import { hashIp, recordDownload } from "../services/downloads.ts"
import { createClient } from "@supabase/supabase-js"

const secretName = "PLUGIN_DOWNLOAD_IP_HASH_KEY"

Deno.test("download IP hashes require a separate secret and omit unavailable analytics", async () => {
  const previous = Deno.env.get(secretName)
  try {
    for (const secret of [undefined, "", "short", "x".repeat(257), "x".repeat(32) + " "]) {
      if (secret === undefined) Deno.env.delete(secretName)
      else Deno.env.set(secretName, secret)
      assertEquals(await hashIp("192.0.2.1"), null)
    }
    let sent: unknown
    const client = createClient("https://example.invalid", "test-key", {
      global: {
        fetch: async (_input, init) => {
          sent = JSON.parse(String(init?.body))
          return new Response(JSON.stringify("download-id"), { headers: { "content-type": "application/json" } })
        },
      },
    })
    const result = await recordDownload(client, "plugin-id", "version-id", null, await hashIp("192.0.2.1"))
    assertEquals(result, "download-id")
    assertEquals(sent, { p_plugin_id: "plugin-id", p_version_id: "version-id", p_user_id: null, p_ip_hash: null })
  } finally {
    if (previous === undefined) Deno.env.delete(secretName)
    else Deno.env.set(secretName, previous)
  }
})

Deno.test("HMAC matches a known vector and rotation changes the pseudonym", async () => {
  const previous = Deno.env.get(secretName)
  try {
    Deno.env.set(secretName, "Jefe".repeat(8))
    const first = await hashIp("192.0.2.1")
    assertEquals(first, "887615b11443cda7e9acebe0536428aaf7b648c8a617fd68b929dea1c44f23bd")
    assertEquals(await hashIp("192.0.2.1"), first)
    assertNotEquals(await hashIp("192.0.2.2"), first)
    Deno.env.set(secretName, "OtherSecret".repeat(4))
    const rotated = await hashIp("192.0.2.1")
    assertMatch(rotated ?? "", /^[a-f0-9]{64}$/)
    assertNotEquals(rotated, first)
    assertEquals(await hashIp(""), null)
    assertEquals(await hashIp("x".repeat(1025)), null)
  } finally {
    if (previous === undefined) Deno.env.delete(secretName)
    else Deno.env.set(secretName, previous)
  }
})
