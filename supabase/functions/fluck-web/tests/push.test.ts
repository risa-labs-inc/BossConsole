/**
 * Web Push routes and the portal's installable assets, driven through app.request() with
 * `deps.fetch` stubbed so no network is touched.
 *
 * Run: cd supabase/functions/fluck-web && deno test --allow-all
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { app, deps } from "../app.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"
import { b64uDecode, b64uEncode } from "../utils/webpush.ts"

const BASE = "/fluck-web"
const ORIGIN = "https://fluck.risaboss.com"
const SECURE = { "x-forwarded-proto": "https" }
const CSRF = "a".repeat(64)
const TICKET = "A".repeat(40) + "b-_"
const JWT = `${b64uEncode(new TextEncoder().encode('{"alg":"HS256"}'))}.${b64uEncode(new TextEncoder().encode('{"sub":"u1","email":"me@risalabs.ai"}'))}.sig-sig-sig-sig-sig`
const UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
const UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
const UA_AUTH = "BTBZMqHH6r4Tts7J_aSIgg"
const FCM = "https://fcm.googleapis.com/fcm/send/abc"
const APPLE = "https://web.push.apple.com/QAbc"
const SUB_ID = "11111111-1111-4111-8111-111111111111"
const SUB_ID_2 = "22222222-2222-4222-8222-222222222222"
const ENV_VARS = [
  "SUPABASE_URL", "SUPABASE_ANON_KEY", "FLUCK_WEB_PUBLIC_BASE_URL", "FLUCK_WEB_PUBLIC_BASE_PATH", "FLUCK_WEB_ALIAS_SECRET",
  "FLUCK_WEB_VAPID_PUBLIC_KEY", "FLUCK_WEB_VAPID_PRIVATE_KEY", "FLUCK_WEB_VAPID_SUBJECT",
]

type Call = { url: string; init?: RequestInit }

const vapidPair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])
const VAPID_PUBLIC = b64uEncode(new Uint8Array(await crypto.subtle.exportKey("raw", vapidPair.publicKey)))
const VAPID_PRIVATE = (await crypto.subtle.exportKey("jwk", vapidPair.privateKey)).d!

function withEnv(fn: () => Promise<void>, opts: { vapid?: boolean; basePath?: string } = {}): () => Promise<void> {
  return async () => {
    Deno.env.set("SUPABASE_URL", "https://stack.example")
    Deno.env.set("SUPABASE_ANON_KEY", "anon-key")
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_URL", ORIGIN)
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_PATH", opts.basePath ?? "/")
    if (opts.vapid !== false) {
      Deno.env.set("FLUCK_WEB_VAPID_PUBLIC_KEY", VAPID_PUBLIC)
      Deno.env.set("FLUCK_WEB_VAPID_PRIVATE_KEY", VAPID_PRIVATE)
    }
    resetRateLimits()
    try {
      await fn()
    } finally {
      for (const name of ENV_VARS) Deno.env.delete(name)
    }
  }
}

function stubFetch(handler: (call: Call) => Response | Promise<Response>): { calls: Call[]; restore: () => void } {
  const calls: Call[] = []
  const original = deps.fetch
  deps.fetch = (url: string, init?: RequestInit) => {
    const call = { url, init }
    calls.push(call)
    return Promise.resolve(handler(call))
  }
  return { calls, restore: () => (deps.fetch = original) }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })

const GOOD_SUB = { endpoint: FCM, expirationTime: null, keys: { p256dh: UA_PUBLIC, auth: UA_AUTH } }

function userPost(path: string, body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return Promise.resolve(app.request(`${BASE}${path}`, {
    method: "POST",
    headers: {
      ...SECURE,
      "Content-Type": "application/json",
      origin: ORIGIN,
      "x-fluck-web-csrf": CSRF,
      cookie: `__Secure-boss_fluck_at=${JWT}; __Host-boss_fluck_csrf=${CSRF}`,
      ...headers,
    },
    body: JSON.stringify(body),
  }))
}

function notify(body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return Promise.resolve(app.request(`${BASE}/api/push/notify`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "cf-connecting-ip": "203.0.113.9", ...headers },
    body: JSON.stringify(body),
  }))
}

// ---- assets ----

Deno.test("GET /sw.js is JavaScript, revalidated, scoped to the base, and shows only title/body/tag/url", withEnv(async () => {
  const res = await app.request(`${BASE}/sw.js`)
  assertEquals(res.status, 200)
  assertStringIncludes(res.headers.get("content-type")!, "application/javascript")
  assertEquals(res.headers.get("cache-control"), "no-cache")
  assertEquals(res.headers.get("service-worker-allowed"), "/")
  assertEquals(res.headers.get("x-content-type-options"), "nosniff")
  assertEquals(res.headers.get("access-control-allow-origin"), null, "no CORS")
  const js = await res.text()
  new Function(js) // syntax check
  assertStringIncludes(js, 'addEventListener("push"')
  assertStringIncludes(js, "showNotification(")
  assertStringIncludes(js, 'addEventListener("notificationclick"')
  assertStringIncludes(js, "clients.matchAll(")
  assertStringIncludes(js, "c.focus()")
  assertStringIncludes(js, "c.navigate(url)")
  assertStringIncludes(js, "clients.openWindow(url)")
  assertStringIncludes(js, "u.origin === self.location.origin", "a notification only ever opens this portal")
  assert(!/\p{Extended_Pictographic}/u.test(js), "no emoji")
}))

Deno.test("GET /sw.js under a path prefix allows exactly that prefix", withEnv(async () => {
  const res = await app.request(`${BASE}/sw.js`)
  assertEquals(res.headers.get("service-worker-allowed"), "/functions/v1/fluck-web")
}, { basePath: "/functions/v1/fluck-web" }))

Deno.test("the service worker shows the pushed title, body, tag and url, and opens or focuses the portal on click", async () => {
  const js = await (await app.request(`${BASE}/sw.js`)).text()
  const listeners: Record<string, (ev: unknown) => void> = {}
  const shown: { title: string; options: Record<string, unknown> }[] = []
  const clientsLog: string[] = []
  let existing: { url: string; focus: () => Promise<void>; navigate: (u: string) => Promise<void> }[] = []
  const self = {
    location: { origin: ORIGIN },
    registration: {
      scope: `${ORIGIN}/`,
      showNotification: (title: string, options: Record<string, unknown>) => { shown.push({ title, options }); return Promise.resolve() },
    },
    clients: {
      claim: () => Promise.resolve(),
      matchAll: () => Promise.resolve(existing),
      openWindow: (u: string) => { clientsLog.push(`open ${u}`); return Promise.resolve() },
    },
    skipWaiting: () => {},
    addEventListener: (type: string, fn: (ev: unknown) => void) => { listeners[type] = fn },
  }
  new Function("self", js)(self)
  const waits: Promise<unknown>[] = []
  const push = (data: unknown) => listeners.push({ data: { json: () => data }, waitUntil: (p: Promise<unknown>) => waits.push(p) })

  push({ title: "Laya", body: "New message", tag: "fluck-inst-a", url: `${ORIGIN}/?instance=inst-a` })
  push({ title: "x".repeat(300), body: 5, tag: "t", url: "https://evil.example/phish" })
  push(null)
  await Promise.all(waits)
  assertEquals(shown[0].title, "Laya")
  assertEquals(shown[0].options.body, "New message")
  assertEquals(shown[0].options.tag, "fluck-inst-a")
  assertEquals(shown[0].options.data, { url: `${ORIGIN}/?instance=inst-a` })
  assertEquals(shown[0].options.icon, `${ORIGIN}/icon-512.png`)
  assertEquals(shown[1].title.length, 100)
  assertEquals(shown[1].options.body, "New message", "a non-string body falls back")
  assertEquals(shown[1].options.data, { url: `${ORIGIN}/` }, "a foreign url becomes the portal root")
  assertEquals(shown[2].title, "Fluck")

  const click = async (url: string) => {
    let closed = false
    const p: Promise<unknown>[] = []
    listeners.notificationclick({ notification: { close: () => { closed = true }, data: { url } }, waitUntil: (x: Promise<unknown>) => p.push(x) })
    await Promise.all(p)
    assert(closed)
  }
  await click(`${ORIGIN}/?instance=inst-a`)
  assertEquals(clientsLog, [`open ${ORIGIN}/?instance=inst-a`], "no window: open one")

  clientsLog.length = 0
  existing = [{
    url: `${ORIGIN}/?instance=other`,
    focus: () => { clientsLog.push("focus"); return Promise.resolve() },
    navigate: (u) => { clientsLog.push(`navigate ${u}`); return Promise.resolve() },
  }]
  await click(`${ORIGIN}/?instance=inst-a`)
  assertEquals(clientsLog, ["focus", `navigate ${ORIGIN}/?instance=inst-a`], "a window: focus it and navigate")

  clientsLog.length = 0
  await click(`${ORIGIN}/?instance=other`)
  assertEquals(clientsLog, ["focus"], "already there: focus only")
})

Deno.test("GET /manifest.webmanifest and the icon make the portal installable", withEnv(async () => {
  const res = await app.request(`${BASE}/manifest.webmanifest`)
  assertEquals(res.status, 200)
  assertStringIncludes(res.headers.get("content-type")!, "application/manifest+json")
  assertEquals(res.headers.get("cache-control"), "public, max-age=3600")
  const m = await res.json()
  assertEquals(m.display, "standalone")
  assertEquals(m.start_url, "/")
  assertEquals(m.scope, "/")
  assertEquals(m.icons[0].src, "/icon-512.png")
  assertEquals(m.icons[0].sizes, "512x512")

  const icon = await app.request(`${BASE}/icon-512.png`)
  assertEquals(icon.status, 200)
  assertEquals(icon.headers.get("content-type"), "image/png")
  const bytes = new Uint8Array(await icon.arrayBuffer())
  assertEquals(Array.from(bytes.slice(0, 8)), [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  const view = new DataView(bytes.buffer)
  assertEquals([view.getUint32(16), view.getUint32(20)], [512, 512])
}))

Deno.test("the manifest follows the base path", withEnv(async () => {
  const m = await (await app.request(`${BASE}/manifest.webmanifest`)).json()
  assertEquals(m.start_url, "/functions/v1/fluck-web/")
  assertEquals(m.icons[0].src, "/functions/v1/fluck-web/icon-512.png")
}, { basePath: "/functions/v1/fluck-web" }))

Deno.test("the page links the manifest and icon, and its CSP admits only own worker, manifest and icon", withEnv(async () => {
  const res = await app.request(`${BASE}/`)
  const csp = res.headers.get("content-security-policy")!
  assertStringIncludes(csp, "worker-src 'self'")
  assertStringIncludes(csp, "manifest-src 'self'")
  assertStringIncludes(csp, "img-src data: 'self'")
  assertStringIncludes(csp, "default-src 'none'")
  assertStringIncludes(csp, "connect-src 'self'")
  assertStringIncludes(csp, "frame-src https:")
  assert(!csp.includes("unsafe-inline") && !csp.includes("unsafe-eval"))
  const html = await res.text()
  assertStringIncludes(html, '<link rel="manifest" href="/manifest.webmanifest">')
  assertStringIncludes(html, '<link rel="apple-touch-icon" href="/icon-512.png">')
  assertStringIncludes(html, '<meta name="apple-mobile-web-app-capable" content="yes">')
  assertStringIncludes(html, 'id="push-sheet"')
}))

// ---- /api/push/config ----

Deno.test("GET /api/push/config returns the public VAPID key, 503 when unset, refuses cross-site", withEnv(async () => {
  const ok = await app.request(`${BASE}/api/push/config`)
  assertEquals(ok.status, 200)
  assertEquals(await ok.json(), { vapid_public_key: VAPID_PUBLIC })
  assertEquals((await app.request(`${BASE}/api/push/config`, { headers: { "sec-fetch-site": "cross-site" } })).status, 403)
  Deno.env.delete("FLUCK_WEB_VAPID_PRIVATE_KEY")
  const none = await app.request(`${BASE}/api/push/config`)
  assertEquals(none.status, 503)
  assertEquals((await none.json()).error, "not_configured")
}))

// ---- /api/push/subscribe and /unsubscribe ----

Deno.test("POST /api/push/subscribe stores the subscription as the user, with the portal origin", withEnv(async () => {
  const stub = stubFetch(() => new Response(null, { status: 204 }))
  try {
    const res = await userPost("/api/push/subscribe", { subscription: { ...GOOD_SUB, extra: "dropped" } })
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { ok: true })
    assertEquals(stub.calls.length, 1)
    assertEquals(stub.calls[0].url, "https://stack.example/rest/v1/rpc/fluck_web_push_subscribe")
    assertEquals(new Headers(stub.calls[0].init?.headers).get("Authorization"), `Bearer ${JWT}`)
    assertEquals(JSON.parse(stub.calls[0].init!.body as string), {
      p_subscription: { endpoint: FCM, keys: { p256dh: UA_PUBLIC, auth: UA_AUTH } },
      p_origin: ORIGIN,
    })
  } finally {
    stub.restore()
  }
}))

Deno.test("push subscribe and unsubscribe refuse bad Origin, CSRF, cross-site and non-JSON before any upstream call", withEnv(async () => {
  const stub = stubFetch(() => new Response(null, { status: 204 }))
  try {
    for (const path of ["/api/push/subscribe", "/api/push/unsubscribe"]) {
      const body = path.endsWith("subscribe") && !path.endsWith("unsubscribe") ? { subscription: GOOD_SUB } : { endpoint: FCM }
      assertEquals((await userPost(path, body, { origin: "https://evil.example" })).status, 403, path + " origin")
      assertEquals((await userPost(path, body, { origin: "" })).status, 403, path + " no origin")
      assertEquals((await userPost(path, body, { "x-fluck-web-csrf": "b".repeat(64) })).status, 403, path + " csrf")
      assertEquals((await userPost(path, body, { cookie: `__Secure-boss_fluck_at=${JWT}` })).status, 403, path + " no csrf cookie")
      assertEquals((await userPost(path, body, { "sec-fetch-site": "cross-site" })).status, 403, path + " cross-site")
      assertEquals((await userPost(path, body, { "Content-Type": "text/plain" })).status, 415, path + " not json")
    }
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/subscribe rejects subscriptions that are not a push service's", withEnv(async () => {
  const stub = stubFetch(() => new Response(null, { status: 204 }))
  try {
    const bad: unknown[] = [
      { ...GOOD_SUB, endpoint: "https://evil.example/x" },
      { ...GOOD_SUB, endpoint: "http://fcm.googleapis.com/x" },
      { ...GOOD_SUB, endpoint: "https://fcm.googleapis.com.evil.example/x" },
      { ...GOOD_SUB, keys: { p256dh: "short", auth: UA_AUTH } },
      { ...GOOD_SUB, keys: { p256dh: UA_PUBLIC } },
      { endpoint: FCM },
      "nope",
      null,
    ]
    for (const subscription of bad) {
      const res = await userPost("/api/push/subscribe", { subscription })
      assertEquals(res.status, 400, JSON.stringify(subscription))
      assertEquals((await res.json()).error, "invalid_subscription")
    }
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/subscribe is 401 without a session, 503 on an origin that cannot take pushes, 400 when the RPC refuses", withEnv(async () => {
  const stub = stubFetch(() => json({ message: "invalid_subscription" }, 400))
  try {
    assertEquals((await userPost("/api/push/subscribe", { subscription: GOOD_SUB }, { cookie: `__Host-boss_fluck_csrf=${CSRF}` })).status, 401)
    const refused = await userPost("/api/push/subscribe", { subscription: GOOD_SUB })
    assertEquals(refused.status, 400)
    assertEquals((await refused.json()).error, "invalid_subscription")
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_URL", "https://example.com")
    assertEquals((await userPost("/api/push/subscribe", { subscription: GOOD_SUB }, { origin: "https://example.com" })).status, 503)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/subscribe is rate limited", withEnv(async () => {
  const stub = stubFetch(() => new Response(null, { status: 204 }))
  try {
    let last = 0
    for (let i = 0; i < 31; i++) last = (await userPost("/api/push/subscribe", { subscription: GOOD_SUB })).status
    assertEquals(last, 429)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/unsubscribe sends the sha256 of the endpoint, never the endpoint", withEnv(async () => {
  const stub = stubFetch(() => new Response(null, { status: 204 }))
  try {
    const res = await userPost("/api/push/unsubscribe", { endpoint: FCM })
    assertEquals(res.status, 200)
    assertEquals(stub.calls[0].url, "https://stack.example/rest/v1/rpc/fluck_web_push_unsubscribe")
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(FCM)))
    const hex = Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("")
    assertEquals(JSON.parse(stub.calls[0].init!.body as string), { p_endpoint_hash: hex })
    assertEquals((await userPost("/api/push/unsubscribe", { endpoint: 5 })).status, 400)
    assertEquals((await userPost("/api/push/unsubscribe", { endpoint: "x".repeat(2000) })).status, 400)
  } finally {
    stub.restore()
  }
}))

// ---- /api/push/notify ----

/** Decrypt an aes128gcm body as the browser would, with the RFC 8291 example user-agent keys. */
type U8 = Uint8Array<ArrayBuffer>
async function decrypt(body: U8): Promise<string> {
  const hkdf = async (ikm: U8, salt: U8, info: U8, bytes: number) => {
    const key = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"])
    return new Uint8Array(await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt, info }, key, bytes * 8))
  }
  const salt = body.slice(0, 16)
  const idlen = body[20]
  const senderPublic = body.slice(21, 21 + idlen)
  const cipher = body.slice(21 + idlen)
  const uaPublic = b64uDecode(UA_PUBLIC)
  const point = (raw: U8, d?: string): JsonWebKey => ({
    kty: "EC", crv: "P-256", x: b64uEncode(raw.slice(1, 33)), y: b64uEncode(raw.slice(33, 65)), ...(d ? { d } : {}),
  })
  const priv = await crypto.subtle.importKey("jwk", point(uaPublic, UA_PRIVATE), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"])
  const pub = await crypto.subtle.importKey("jwk", point(senderPublic), { name: "ECDH", namedCurve: "P-256" }, false, [])
  const ecdh = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: pub }, priv, 256))
  const te = new TextEncoder()
  const join = (...p: U8[]) => Uint8Array.from(p.flatMap((x) => Array.from(x)))
  const ikm = await hkdf(ecdh, b64uDecode(UA_AUTH), join(te.encode("WebPush: info\0"), uaPublic, senderPublic), 32)
  const cek = await hkdf(ikm, salt, te.encode("Content-Encoding: aes128gcm\0"), 16)
  const nonce = await hkdf(ikm, salt, te.encode("Content-Encoding: nonce\0"), 12)
  const key = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["decrypt"])
  const plain = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce }, key, cipher))
  assertEquals(plain[plain.length - 1], 2, "single-record delimiter")
  return new TextDecoder().decode(plain.slice(0, -1))
}

function redeemed(subs: { id: string; endpoint: string; origin?: string }[]) {
  return {
    instance_id: "inst-a",
    agent_name: "Laya",
    subscriptions: subs.map((s) => ({
      id: s.id,
      subscription: { endpoint: s.endpoint, keys: { p256dh: UA_PUBLIC, auth: UA_AUTH } },
      portal_origin: s.origin ?? ORIGIN,
    })),
  }
}

Deno.test("POST /api/push/notify redeems with the anon key, sends an encrypted generic push to every subscription, in parallel", withEnv(async () => {
  const pushed: Call[] = []
  const stub = stubFetch((call) => {
    if (call.url.endsWith("/rpc/fluck_web_push_redeem")) {
      return json(redeemed([{ id: SUB_ID, endpoint: FCM }, { id: SUB_ID_2, endpoint: APPLE, origin: "https://fluck.ai" }]))
    }
    pushed.push(call)
    return new Response(null, { status: 201 })
  })
  try {
    const res = await notify({ ticket: TICKET, message_id: "m-1:2.x" })
    assertEquals(res.status, 202)
    assertEquals(await res.json(), { sent: 2, pruned: 0 })
    assertEquals(stub.calls[0].url, "https://stack.example/rest/v1/rpc/fluck_web_push_redeem")
    const redeemHeaders = new Headers(stub.calls[0].init?.headers)
    assertEquals(redeemHeaders.get("Authorization"), "Bearer anon-key", "the anon key, no user token")
    assertEquals(redeemHeaders.get("cookie"), null)
    assertEquals(JSON.parse(stub.calls[0].init!.body as string), { p_ticket: TICKET })

    assertEquals(pushed.map((c) => c.url).sort(), [APPLE, FCM].sort())
    for (const call of pushed) {
      const h = new Headers(call.init?.headers)
      assertEquals(h.get("content-encoding"), "aes128gcm")
      assertEquals(h.get("ttl"), "86400")
      assertEquals(h.get("urgency"), "normal")
      assert(h.get("authorization")!.startsWith("vapid t="))
      assertEquals(call.init?.redirect, "manual")
      assert(call.init?.signal, "each push POST has a timeout")
      const payload = JSON.parse(await decrypt(new Uint8Array(call.init!.body as Uint8Array)))
      assertEquals(Object.keys(payload).sort(), ["body", "tag", "title", "url"])
      assertEquals(payload.title, "Laya")
      assertEquals(payload.body, "New message")
      assertEquals(payload.tag, "fluck-inst-a")
      assertEquals(payload.url, call.url === FCM ? `${ORIGIN}/?instance=inst-a` : "https://fluck.ai/?instance=inst-a")
      assert(!JSON.stringify(payload).includes("m-1:2.x"), "the message id is not forwarded")
    }
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/notify prunes subscriptions the push service reports gone (404/410), and only those", withEnv(async () => {
  const stub = stubFetch((call) => {
    if (call.url.endsWith("/rpc/fluck_web_push_redeem")) return json(redeemed([{ id: SUB_ID, endpoint: FCM }, { id: SUB_ID_2, endpoint: APPLE }]))
    if (call.url.endsWith("/rpc/fluck_web_push_prune")) return json(1)
    return new Response(null, { status: call.url === FCM ? 410 : 201 })
  })
  try {
    const res = await notify({ ticket: TICKET, message_id: "m1" })
    assertEquals(res.status, 202)
    assertEquals(await res.json(), { sent: 1, pruned: 1 })
    const prune = stub.calls.find((c) => c.url.endsWith("/rpc/fluck_web_push_prune"))!
    assertEquals(JSON.parse(prune.init!.body as string), { p_ticket: TICKET, p_ids: [SUB_ID] })
    assertEquals(new Headers(prune.init?.headers).get("Authorization"), "Bearer anon-key")
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/notify does not prune on a transient failure, a throw, or a refused subscription", withEnv(async () => {
  const stub = stubFetch((call) => {
    if (call.url.endsWith("/rpc/fluck_web_push_redeem")) {
      return json(redeemed([
        { id: SUB_ID, endpoint: FCM },
        { id: SUB_ID_2, endpoint: APPLE },
        { id: "33333333-3333-4333-8333-333333333333", endpoint: "https://evil.example/x" },
      ]))
    }
    if (call.url === FCM) return new Response(null, { status: 503 })
    throw new Error("socket closed")
  })
  try {
    const res = await notify({ ticket: TICKET, message_id: "m1" })
    assertEquals(res.status, 202)
    assertEquals(await res.json(), { sent: 0, pruned: 0 })
    assert(!stub.calls.some((c) => c.url.includes("evil.example")), "a non-push host is never contacted")
    assert(!stub.calls.some((c) => c.url.endsWith("/rpc/fluck_web_push_prune")))
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/notify with no subscriptions is a quiet 202", withEnv(async () => {
  const stub = stubFetch(() => json(redeemed([])))
  try {
    const res = await notify({ ticket: TICKET, message_id: "m1" })
    assertEquals(res.status, 202)
    assertEquals(await res.json(), { sent: 0, pruned: 0 })
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/notify answers an unknown, spent or expired ticket with one bare 410", withEnv(async () => {
  const stub = stubFetch(() => json({ code: "P0001", message: "ticket_invalid", details: null, hint: null }, 400))
  try {
    const res = await notify({ ticket: TICKET, message_id: "m1" })
    assertEquals(res.status, 410)
    assertEquals(await res.json(), { error: "unknown_ticket" })
    assertEquals(stub.calls.length, 1)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/notify maps an undeployed or broken redeem to 502, not to 404", withEnv(async () => {
  const stub = stubFetch(() => json({ code: "PGRST202", message: "Could not find the function" }, 404))
  try {
    const res = await notify({ ticket: TICKET, message_id: "m1" })
    assertEquals(res.status, 502)
    assertEquals((await res.json()).error, "upstream")
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/notify validates shapes before touching the database", withEnv(async () => {
  const stub = stubFetch(() => json(redeemed([])))
  try {
    const bad: unknown[] = [
      {},
      { ticket: TICKET },
      { message_id: "m1" },
      { ticket: "short", message_id: "m1" },
      { ticket: TICKET + "x", message_id: "m1" },
      { ticket: "!".repeat(43), message_id: "m1" },
      { ticket: TICKET, message_id: "" },
      { ticket: TICKET, message_id: "a b" },
      { ticket: TICKET, message_id: "x".repeat(129) },
      { ticket: TICKET, message_id: 7 },
    ]
    for (const body of bad) assertEquals((await notify(body)).status, 400, JSON.stringify(body))
    assertEquals((await notify({ ticket: TICKET, message_id: "m1" }, { "Content-Type": "text/plain" })).status, 415)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/push/notify is 503 before redeeming when the VAPID secrets are missing, so the ticket is not burned", withEnv(async () => {
  const stub = stubFetch(() => json(redeemed([])))
  try {
    const res = await notify({ ticket: TICKET, message_id: "m1" })
    assertEquals(res.status, 503)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}, { vapid: false }))

Deno.test("POST /api/push/notify needs no cookie and ignores one, and is rate limited by client", withEnv(async () => {
  const stub = stubFetch(() => json(redeemed([])))
  try {
    const res = await notify({ ticket: TICKET, message_id: "m1" }, { cookie: `__Secure-boss_fluck_at=${JWT}` })
    assertEquals(res.status, 202)
    assertEquals(new Headers(stub.calls[0].init?.headers).get("Authorization"), "Bearer anon-key")
    let last = 0
    for (let i = 0; i < 125; i++) last = (await notify({ ticket: TICKET, message_id: "m1" })).status
    assertEquals(last, 429)
    assertEquals((await notify({ ticket: TICKET, message_id: "m1" }, { "cf-connecting-ip": "198.51.100.4" })).status, 202, "another client is unaffected")
  } finally {
    stub.restore()
  }
}))

Deno.test("no push route sends CORS headers or caches", withEnv(async () => {
  for (const path of ["/api/push/config", "/api/push/subscribe", "/api/push/notify"]) {
    const res = await app.request(`${BASE}${path}`, { method: path.endsWith("config") ? "GET" : "OPTIONS", headers: { origin: "https://evil.example" } })
    assertEquals(res.headers.get("access-control-allow-origin"), null, path)
  }
}))
