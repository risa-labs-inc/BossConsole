import { assertEquals } from "jsr:@std/assert"
import { normalizeStorageUrl } from "../services/storage.ts"

Deno.test("local storage URLs use the stack's public URL, and production URLs are untouched", () => {
  const prevUrl = Deno.env.get("PLUGIN_STORE_PUBLIC_URL")
  const prevPort = Deno.env.get("SUPABASE_INTERNAL_HOST_PORT")
  try {
    Deno.env.delete("PLUGIN_STORE_PUBLIC_URL")
    Deno.env.set("SUPABASE_INTERNAL_HOST_PORT", "57321")
    assertEquals(normalizeStorageUrl("http://kong:8000/storage/v1/object/sign/a?token=t"), "http://127.0.0.1:57321/storage/v1/object/sign/a?token=t")
    Deno.env.set("PLUGIN_STORE_PUBLIC_URL", "https://sb.e2e.test/")
    assertEquals(normalizeStorageUrl("http://kong:8000/storage/v1/object/sign/a?token=t"), "https://sb.e2e.test/storage/v1/object/sign/a?token=t")
    assertEquals(normalizeStorageUrl("https://api.risaboss.com/storage/v1/object/sign/a?token=t"), "https://api.risaboss.com/storage/v1/object/sign/a?token=t")
  } finally {
    if (prevUrl === undefined) Deno.env.delete("PLUGIN_STORE_PUBLIC_URL"); else Deno.env.set("PLUGIN_STORE_PUBLIC_URL", prevUrl)
    if (prevPort === undefined) Deno.env.delete("SUPABASE_INTERNAL_HOST_PORT"); else Deno.env.set("SUPABASE_INTERNAL_HOST_PORT", prevPort)
  }
})
