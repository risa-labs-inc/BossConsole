/**
 * The max-web alias: forwards exactly POST /max-web/api/push/notify to fluck-web's handler and
 * answers 404 for everything else. Lives here so the fluck-web CI job (and its import map) runs it.
 *
 * Run: cd supabase/functions/fluck-web && deno test --allow-all
 */

import { assertEquals } from "@std/assert"
import { maxWebFetch } from "../../max-web/app.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

// The edge runtime hands the function a path that starts at its own name (fluck-web mounts at /fluck-web).
const HOST = "http://localhost"

function post(path: string, body: unknown): Request {
  return new Request(HOST + path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })
}

Deno.test("max-web forwards push/notify to fluck-web's handler", async () => {
  resetRateLimits()
  // An empty body is fluck-web's own 400, proving the request reached the notify route (not a 404).
  const res = await maxWebFetch(post("/max-web/api/push/notify", {}))
  assertEquals(res.status, 400)
  assertEquals(await res.json(), { error: "invalid_request" })
})

Deno.test("max-web forwards the request body intact", async () => {
  resetRateLimits()
  // A well-formed ticket and message id get past validation; with no SUPABASE_URL set the handler stops at not_configured.
  const env = ["SUPABASE_URL", "SUPABASE_ANON_KEY"].map((k) => [k, Deno.env.get(k)] as const)
  env.forEach(([k]) => Deno.env.delete(k))
  try {
    const res = await maxWebFetch(post("/max-web/api/push/notify", { ticket: "A".repeat(40) + "b-_", message_id: "m_0123456789abcdef01234567" }))
    assertEquals(res.status, 503)
    assertEquals(await res.json(), { error: "not_configured" })
  } finally {
    env.forEach(([k, v]) => v !== undefined && Deno.env.set(k, v))
  }
})

Deno.test("max-web exposes no other fluck-web route", async () => {
  resetRateLimits()
  for (const path of ["/max-web/api/otp", "/max-web/api/push/subscribe", "/max-web/api/open", "/max-web/", "/max-web", "/fluck-web/api/push/notify"]) {
    const res = await maxWebFetch(post(path, {}))
    assertEquals(res.status, 404, path)
    await res.body?.cancel()
  }
  for (const method of ["GET", "PUT", "DELETE"]) {
    const res = await maxWebFetch(new Request(HOST + "/max-web/api/push/notify", { method }))
    assertEquals(res.status, 404, method)
    await res.body?.cancel()
  }
})
