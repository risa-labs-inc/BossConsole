/**
 * Web Push with WebCrypto only: RFC 8291 aes128gcm payload encryption and an RFC 8292 VAPID
 * ES256 JWT. No third-party dependency; the only network call is sendWebPush's single POST.
 */

const enc = new TextEncoder()
const VAPID_PUBLIC_RE = /^[A-Za-z0-9_-]{87}$/
const VAPID_PRIVATE_RE = /^[A-Za-z0-9_-]{43}$/
const DEFAULT_SUBJECT = "mailto:support@risalabs.ai"
/** RFC 8292: a token is valid for at most 24 h; stay well inside it. */
const JWT_LIFETIME_SECONDS = 12 * 3600
/** RFC 8291 record size. One record only: plaintext + 1 delimiter + 16 tag must fit. */
const RECORD_SIZE = 4096
export const MAX_PAYLOAD_BYTES = RECORD_SIZE - 17 - 86

export interface PushSubscription {
  endpoint: string
  keys: { p256dh: string; auth: string }
}

export interface VapidConfig {
  publicKey: string
  privateKey: string
  subject: string
}

/** Push services only; keep aligned with the endpoint CHECK in 20261013100000_fluck_web_push.sql. */
const PUSH_HOSTS = new Set([
  "fcm.googleapis.com",
  "android.googleapis.com",
  "web.push.apple.com",
  "updates.push.services.mozilla.com",
])

export function isAllowedPushEndpoint(endpoint: unknown): endpoint is string {
  if (typeof endpoint !== "string" || endpoint.length > 1024 || /\s/.test(endpoint)) return false
  let u: URL
  try {
    u = new URL(endpoint)
  } catch {
    return false
  }
  if (u.protocol !== "https:" || u.username || u.password || u.port) return false
  if (!endpoint.startsWith(`https://${u.hostname}/`)) return false
  const host = u.hostname
  return PUSH_HOSTS.has(host) || /^([a-z0-9-]+\.)+notify\.windows\.com$/.test(host)
}

export function isPushSubscription(value: unknown): value is PushSubscription {
  if (!value || typeof value !== "object") return false
  const v = value as Record<string, unknown>
  const keys = v.keys as Record<string, unknown> | null | undefined
  return isAllowedPushEndpoint(v.endpoint) && !!keys && typeof keys === "object" &&
    typeof keys.p256dh === "string" && /^[A-Za-z0-9_-]{80,100}$/.test(keys.p256dh) &&
    typeof keys.auth === "string" && /^[A-Za-z0-9_-]{16,32}$/.test(keys.auth)
}

/** VAPID keys from the function's secrets, or null unless both are well formed. */
export function readVapid(): VapidConfig | null {
  const publicKey = Deno.env.get("FLUCK_WEB_VAPID_PUBLIC_KEY")?.trim() ?? ""
  const privateKey = Deno.env.get("FLUCK_WEB_VAPID_PRIVATE_KEY")?.trim() ?? ""
  if (!VAPID_PUBLIC_RE.test(publicKey) || !VAPID_PRIVATE_RE.test(privateKey)) return null
  const subject = Deno.env.get("FLUCK_WEB_VAPID_SUBJECT")?.trim() || DEFAULT_SUBJECT
  return { publicKey, privateKey, subject }
}

export function b64uEncode(bytes: Uint8Array): string {
  let s = ""
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

export function b64uDecode(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4)
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}

/** `bytes` as a BufferSource-typed copy (WebCrypto's typings reject Uint8Array<ArrayBufferLike>). */
function buf(bytes: Uint8Array): Uint8Array<ArrayBuffer> {
  return new Uint8Array(bytes)
}

function jwkFor(publicKey: Uint8Array, d?: Uint8Array): JsonWebKey {
  if (publicKey.length !== 65 || publicKey[0] !== 4) throw new Error("expected an uncompressed P-256 point")
  const jwk: JsonWebKey = { kty: "EC", crv: "P-256", x: b64uEncode(publicKey.slice(1, 33)), y: b64uEncode(publicKey.slice(33, 65)) }
  if (d) jwk.d = b64uEncode(d)
  return jwk
}

async function hkdf(ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, bytes: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", buf(ikm), "HKDF", false, ["deriveBits"])
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: buf(salt), info: buf(info) }, key, bytes * 8))
}

export interface EncryptInputs {
  /** The subscription's keys.p256dh (65-byte uncompressed point) and keys.auth (16 bytes). */
  uaPublic: Uint8Array
  authSecret: Uint8Array
  /** Test seam: a fixed sender key pair and salt. Production passes neither. */
  senderPublic?: Uint8Array
  senderPrivate?: Uint8Array
  salt?: Uint8Array
}

/** RFC 8291 section 3 with one aes128gcm record (RFC 8188); returns the complete request body. */
export async function encryptPayload(plaintext: Uint8Array, inputs: EncryptInputs): Promise<Uint8Array> {
  if (plaintext.length > MAX_PAYLOAD_BYTES) throw new Error("payload too large")
  if (inputs.authSecret.length !== 16) throw new Error("auth secret must be 16 bytes")
  const ua = await crypto.subtle.importKey("jwk", jwkFor(inputs.uaPublic), { name: "ECDH", namedCurve: "P-256" }, false, [])
  let senderPublic: Uint8Array
  let senderKey: CryptoKey
  if (inputs.senderPublic && inputs.senderPrivate) {
    senderPublic = inputs.senderPublic
    senderKey = await crypto.subtle.importKey("jwk", jwkFor(senderPublic, inputs.senderPrivate), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"])
  } else {
    const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"])
    senderPublic = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey))
    senderKey = pair.privateKey
  }
  const salt = inputs.salt ?? crypto.getRandomValues(new Uint8Array(16))
  const ecdh = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: ua }, senderKey, 256))
  const ikm = await hkdf(ecdh, inputs.authSecret, concat(enc.encode("WebPush: info\0"), inputs.uaPublic, senderPublic), 32)
  const cek = await hkdf(ikm, salt, enc.encode("Content-Encoding: aes128gcm\0"), 16)
  const nonce = await hkdf(ikm, salt, enc.encode("Content-Encoding: nonce\0"), 12)
  const aes = await crypto.subtle.importKey("raw", buf(cek), "AES-GCM", false, ["encrypt"])
  // 0x02 closes the only record.
  const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: buf(nonce) }, aes, buf(concat(plaintext, new Uint8Array([2])))))
  const header = new Uint8Array(21)
  header.set(salt, 0)
  new DataView(header.buffer).setUint32(16, RECORD_SIZE)
  header[20] = senderPublic.length
  return concat(header, senderPublic, cipher)
}

/** RFC 8292 ES256 JWT for `endpoint`'s origin. */
export async function vapidJwt(endpoint: string, vapid: VapidConfig, nowSeconds: number = Math.floor(Date.now() / 1000)): Promise<string> {
  const header = b64uEncode(enc.encode(JSON.stringify({ typ: "JWT", alg: "ES256" })))
  const claims = b64uEncode(enc.encode(JSON.stringify({
    aud: new URL(endpoint).origin,
    exp: nowSeconds + JWT_LIFETIME_SECONDS,
    sub: vapid.subject,
  })))
  const key = await crypto.subtle.importKey(
    "jwk",
    jwkFor(b64uDecode(vapid.publicKey), b64uDecode(vapid.privateKey)),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  )
  const input = `${header}.${claims}`
  // WebCrypto's ECDSA output is already the raw r||s form JWS wants.
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, enc.encode(input)))
  return `${input}.${b64uEncode(sig)}`
}

export interface SendOptions {
  fetch: (input: string, init?: RequestInit) => Promise<Response>
  ttlSeconds?: number
  urgency?: "very-low" | "low" | "normal" | "high"
  timeoutMs?: number
}

/**
 * Encrypt `payload` for `sub` and POST it to its push service. Resolves with the service's status;
 * rejects on a network error or timeout. Never follows redirects: the allow-listed host is the
 * only host this function talks to. The caller decides what 404/410 mean (a dead subscription).
 */
export async function sendWebPush(sub: PushSubscription, payload: Uint8Array, vapid: VapidConfig, opts: SendOptions): Promise<number> {
  if (!isPushSubscription(sub)) throw new Error("subscription refused")
  const body = await encryptPayload(payload, { uaPublic: b64uDecode(sub.keys.p256dh), authSecret: b64uDecode(sub.keys.auth) })
  return await sendEncryptedWebPush(sub, body, vapid, opts)
}

/**
 * POST `body`, an aes128gcm body somebody else already encrypted for `sub`, unchanged. This
 * function never sees the plaintext, so it cannot validate the bytes beyond their size.
 */
export async function sendEncryptedWebPush(sub: PushSubscription, body: Uint8Array, vapid: VapidConfig, opts: SendOptions): Promise<number> {
  if (!isPushSubscription(sub)) throw new Error("subscription refused")
  const jwt = await vapidJwt(sub.endpoint, vapid)
  const resp = await opts.fetch(sub.endpoint, {
    method: "POST",
    headers: {
      "Content-Encoding": "aes128gcm",
      "Content-Type": "application/octet-stream",
      "TTL": String(opts.ttlSeconds ?? 86400),
      "Urgency": opts.urgency ?? "normal",
      "Authorization": `vapid t=${jwt}, k=${vapid.publicKey}`,
    },
    body: buf(body),
    redirect: "manual",
    signal: AbortSignal.timeout(opts.timeoutMs ?? 8000),
  })
  await resp.body?.cancel()
  return resp.status
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(value)))
  return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("")
}
