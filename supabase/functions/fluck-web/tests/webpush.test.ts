/**
 * Web Push primitives. The encryption test is the RFC 8291 section 5 worked example, byte for byte.
 */

import { assert, assertEquals, assertRejects } from "@std/assert"
import {
  b64uDecode,
  b64uEncode,
  encryptPayload,
  isAllowedPushEndpoint,
  isPushSubscription,
  readVapid,
  sendWebPush,
  sha256Hex,
  vapidJwt,
} from "../utils/webpush.ts"

const RFC = {
  plaintext: "When I grow up, I want to be a watermelon",
  asPrivate: "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
  asPublic: "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
  uaPublic: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
  auth: "BTBZMqHH6r4Tts7J_aSIgg",
  salt: "DGv6ra1nlYgDCS1FRnbzlw",
  body:
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
}

Deno.test("RFC 8291 section 5: the worked example encrypts to the published body", async () => {
  const body = await encryptPayload(new TextEncoder().encode(RFC.plaintext), {
    uaPublic: b64uDecode(RFC.uaPublic),
    authSecret: b64uDecode(RFC.auth),
    senderPublic: b64uDecode(RFC.asPublic),
    senderPrivate: b64uDecode(RFC.asPrivate),
    salt: b64uDecode(RFC.salt),
  })
  assertEquals(b64uEncode(body), RFC.body)
})

Deno.test("a fresh encryption carries the aes128gcm header and a new sender key and salt each time", async () => {
  const inputs = { uaPublic: b64uDecode(RFC.uaPublic), authSecret: b64uDecode(RFC.auth) }
  const a = await encryptPayload(new TextEncoder().encode("x"), inputs)
  const b = await encryptPayload(new TextEncoder().encode("x"), inputs)
  assertEquals(new DataView(a.buffer).getUint32(16), 4096)
  assertEquals(a[20], 65)
  assertEquals(a[21], 4)
  assert(b64uEncode(a.slice(0, 16)) !== b64uEncode(b.slice(0, 16)), "salt differs")
  assert(b64uEncode(a.slice(21, 86)) !== b64uEncode(b.slice(21, 86)), "sender key differs")
  await assertRejects(() => encryptPayload(new Uint8Array(4000), inputs), Error, "too large")
})

async function vapidPair() {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])
  const jwk = await crypto.subtle.exportKey("jwk", pair.privateKey)
  const pub = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey))
  return { pair, vapid: { publicKey: b64uEncode(pub), privateKey: jwk.d!, subject: "mailto:support@risalabs.ai" } }
}

Deno.test("the VAPID JWT verifies against the public key and scopes to the endpoint origin", async () => {
  const { pair, vapid } = await vapidPair()
  const now = 1_800_000_000
  const jwt = await vapidJwt("https://fcm.googleapis.com/fcm/send/abc", vapid, now)
  const [h, c, s] = jwt.split(".")
  assertEquals(JSON.parse(new TextDecoder().decode(b64uDecode(h))), { typ: "JWT", alg: "ES256" })
  assertEquals(JSON.parse(new TextDecoder().decode(b64uDecode(c))), {
    aud: "https://fcm.googleapis.com",
    exp: now + 12 * 3600,
    sub: "mailto:support@risalabs.ai",
  })
  assertEquals(b64uDecode(s).length, 64)
  const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pair.publicKey, b64uDecode(s), new TextEncoder().encode(`${h}.${c}`))
  assert(ok)
  const tampered = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pair.publicKey, b64uDecode(s), new TextEncoder().encode(`${h}.${c}x`))
  assert(!tampered)
})

Deno.test("sendWebPush posts an encrypted body with VAPID, TTL and Urgency headers, without following redirects", async () => {
  const { vapid } = await vapidPair()
  let seen: { url: string; init: RequestInit } | null = null
  const status = await sendWebPush(
    { endpoint: "https://web.push.apple.com/QAbc", keys: { p256dh: RFC.uaPublic, auth: RFC.auth } },
    new TextEncoder().encode('{"title":"Fluck"}'),
    vapid,
    { fetch: (url, init) => { seen = { url, init: init! }; return Promise.resolve(new Response(null, { status: 201 })) } },
  )
  assertEquals(status, 201)
  const h = new Headers(seen!.init.headers)
  assertEquals(seen!.url, "https://web.push.apple.com/QAbc")
  assertEquals(h.get("content-encoding"), "aes128gcm")
  assertEquals(h.get("ttl"), "86400")
  assertEquals(h.get("urgency"), "normal")
  assert(h.get("authorization")!.startsWith("vapid t="))
  assert(h.get("authorization")!.endsWith(`, k=${vapid.publicKey}`))
  assertEquals(seen!.init.redirect, "manual")
  assert((seen!.init.body as Uint8Array).length > 86)
})

Deno.test("only the push services' own hosts are accepted as endpoints", () => {
  for (const ok of [
    "https://fcm.googleapis.com/fcm/send/abc",
    "https://android.googleapis.com/gcm/send/abc",
    "https://web.push.apple.com/QAbc",
    "https://updates.push.services.mozilla.com/wpush/v2/abc",
    "https://wns2-by3p.notify.windows.com/w/?token=abc",
  ]) assert(isAllowedPushEndpoint(ok), ok)
  for (const bad of [
    "http://fcm.googleapis.com/x",
    "https://fcm.googleapis.com",
    "https://fcm.googleapis.com.evil.example/x",
    "https://evil.example/fcm.googleapis.com/",
    "https://user@fcm.googleapis.com/x",
    "https://fcm.googleapis.com:8443/x",
    "https://notify.windows.com/x",
    "https://evilnotify.windows.com.example/x",
    "https://fcm.googleapis.com/a b",
    "https://localhost/x",
    42,
    null,
  ]) assert(!isAllowedPushEndpoint(bad), String(bad))
})

Deno.test("isPushSubscription bounds the key lengths and charset", () => {
  const good = { endpoint: "https://fcm.googleapis.com/fcm/send/abc", keys: { p256dh: RFC.uaPublic, auth: RFC.auth } }
  assert(isPushSubscription(good))
  assert(!isPushSubscription({ ...good, keys: { p256dh: "short", auth: RFC.auth } }))
  assert(!isPushSubscription({ ...good, keys: { p256dh: RFC.uaPublic, auth: "a+b/c=" + "x".repeat(16) } }))
  assert(!isPushSubscription({ ...good, keys: { p256dh: RFC.uaPublic, auth: "x".repeat(40) } }))
  assert(!isPushSubscription({ endpoint: good.endpoint }))
  assert(!isPushSubscription(null))
})

Deno.test("readVapid needs both keys in shape; the subject defaults to support@", async () => {
  const { vapid } = await vapidPair()
  const names = ["FLUCK_WEB_VAPID_PUBLIC_KEY", "FLUCK_WEB_VAPID_PRIVATE_KEY", "FLUCK_WEB_VAPID_SUBJECT"]
  try {
    for (const n of names) Deno.env.delete(n)
    assertEquals(readVapid(), null)
    Deno.env.set("FLUCK_WEB_VAPID_PUBLIC_KEY", vapid.publicKey)
    assertEquals(readVapid(), null, "private key missing")
    Deno.env.set("FLUCK_WEB_VAPID_PRIVATE_KEY", "not a key")
    assertEquals(readVapid(), null, "private key malformed")
    Deno.env.set("FLUCK_WEB_VAPID_PRIVATE_KEY", vapid.privateKey)
    assertEquals(readVapid(), { ...vapid, subject: "mailto:support@risalabs.ai" })
    Deno.env.set("FLUCK_WEB_VAPID_SUBJECT", "mailto:ops@example.com")
    assertEquals(readVapid()!.subject, "mailto:ops@example.com")
  } finally {
    for (const n of names) Deno.env.delete(n)
  }
})

Deno.test("sha256Hex matches the SQL digest encoding", async () => {
  assertEquals(await sha256Hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
})
