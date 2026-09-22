import type { MiddlewareHandler } from "npm:hono@^4.9.9"
import { MAX_PASSKEY_REQUEST_BYTES } from "./request-limits.ts"

/**
 * Trusted gateway admission for POST /auth/challenge.
 *
 * The gateway is the only network route to the challenge endpoint. It strips
 * every inbound internal header, admits the request against its own counters,
 * and adds a short-lived asymmetric JWS assertion covering the method, the
 * canonical path, a digest of the exact request body, the lane, the time
 * window, and a unique request ID. This middleware verifies that assertion
 * before any credential parsing or database RPC runs.
 *
 * Missing, forged, expired, replayed (handled at admission), wrong-lane,
 * body-mismatched, or direct-origin requests are refused here with no
 * database call. When verification itself is unavailable (no key or no
 * configured public hosts), the endpoint fails closed with 503.
 *
 * Logs carry only the lane, the request ID, and the refusal reason. Raw IP
 * addresses, emails, grants, assertions, and passkey material are never
 * logged here.
 */

export const GATEWAY_ADMISSION_HEADER = "x-boss-gateway-admission"

export type GatewayLane = "untrusted" | "trusted"

export interface GatewayAdmission {
  lane: GatewayLane
  requestId?: string
  windowId?: string
}

const MAX_ASSERTION_BYTES = 8 * 1024
const MAX_CLOCK_SKEW_SECONDS = 60
const MAX_ASSERTION_TTL_SECONDS = 120
const MAX_REQUEST_ID_BYTES = 128
const MAX_WINDOW_ID_BYTES = 64

interface AssertionPayload {
  m?: unknown
  p?: unknown
  b?: unknown
  iat?: unknown
  exp?: unknown
  jti?: unknown
  w?: unknown
  lane?: unknown
}

function base64UrlDecode(input: string): Uint8Array {
  const padded = input.replace(/-/g, "+").replace(/_/g, "/")
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

async function sha256HexDigest(data: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", data as BufferSource)
  return base64UrlEncode(new Uint8Array(digest))
}

function sameDigest(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return diff === 0
}

function text(value: unknown, maxBytes: number): string | null {
  if (typeof value !== "string" || value.length === 0) return null
  if (new TextEncoder().encode(value).byteLength > maxBytes) return null
  return value
}

async function importVerifyKey(raw: string): Promise<CryptoKey | null> {
  try {
    const bytes = base64UrlDecode(raw.trim())
    if (bytes.length !== 32) return null
    return await crypto.subtle.importKey("raw", bytes as BufferSource, { name: "Ed25519" }, false, ["verify"])
  } catch {
    return null
  }
}

function configuredHosts(): string[] | null {
  const raw = Deno.env.get("GATEWAY_PUBLIC_HOSTS") ?? ""
  const hosts = raw.split(",").map((host) => host.trim().toLowerCase()).filter((host) => host.length > 0)
  return hosts.length > 0 ? hosts : null
}

function logRefusal(lane: string, requestId: string, reason: string): void {
  console.warn(JSON.stringify({ gateway: "admission", lane, requestId, reason }))
}

export const trustedGatewayAdmission: MiddlewareHandler = async (ctx, next) => {
  const hosts = configuredHosts()
  const keyMaterial = Deno.env.get("GATEWAY_ADMISSION_PUBLIC_KEY") ?? ""
  if (!hosts || !keyMaterial) {
    console.error(JSON.stringify({ gateway: "admission", reason: "gateway_unconfigured" }))
    ctx.header("Retry-After", "30")
    return ctx.json({ error: "Challenge admission is temporarily unavailable. Retry shortly." }, 503)
  }

  const hostname = new URL(ctx.req.url).hostname.toLowerCase()
  if (!hosts.includes(hostname)) {
    logRefusal("unknown", "", "direct_origin")
    return ctx.json({ error: "Direct function access is not permitted." }, 403)
  }

  const assertion = ctx.req.raw.headers.get(GATEWAY_ADMISSION_HEADER) ?? ""
  if (!assertion || new TextEncoder().encode(assertion).byteLength > MAX_ASSERTION_BYTES) {
    logRefusal("unknown", "", "missing_assertion")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }

  const verifyKey = await importVerifyKey(keyMaterial)
  if (!verifyKey) {
    console.error(JSON.stringify({ gateway: "admission", reason: "gateway_unconfigured" }))
    ctx.header("Retry-After", "30")
    return ctx.json({ error: "Challenge admission is temporarily unavailable. Retry shortly." }, 503)
  }

  const parts = assertion.split(".")
  if (parts.length !== 3) {
    logRefusal("unknown", "", "malformed_assertion")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }

  let header: { alg?: unknown }
  let payload: AssertionPayload
  try {
    header = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[0])))
    payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[1])))
  } catch {
    logRefusal("unknown", "", "malformed_assertion")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }
  if (header.alg !== "EdDSA") {
    logRefusal("unknown", "", "unexpected_algorithm")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }

  const lane = payload.lane === "untrusted" || payload.lane === "trusted" ? payload.lane : null
  const requestId = text(payload.jti, MAX_REQUEST_ID_BYTES) ?? ""
  const windowId = text(payload.w, MAX_WINDOW_ID_BYTES) ?? ""
  if (!lane || !requestId || !windowId) {
    logRefusal("unknown", "", "wrong_lane_or_shape")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }

  if (payload.m !== ctx.req.method.toUpperCase()) {
    logRefusal(lane, requestId, "method_mismatch")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }
  if (payload.p !== new URL(ctx.req.url).pathname) {
    logRefusal(lane, requestId, "path_mismatch")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }

  if (typeof payload.iat !== "number" || typeof payload.exp !== "number" || !(payload.exp > payload.iat)) {
    logRefusal(lane, requestId, "expired_assertion")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }
  const nowSeconds = Date.now() / 1000
  if (
    payload.exp - payload.iat > MAX_ASSERTION_TTL_SECONDS ||
    nowSeconds > payload.exp + MAX_CLOCK_SKEW_SECONDS ||
    payload.iat - MAX_CLOCK_SKEW_SECONDS > nowSeconds
  ) {
    logRefusal(lane, requestId, "expired_assertion")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }

  // Hash a size-capped clone of the raw body so verification precedes OpenAPI
  // JSON validation without consuming the handler's body stream. The request
  // size middleware already capped this body at MAX_PASSKEY_REQUEST_BYTES.
  let rawBody = new Uint8Array(0)
  try {
    const clone = ctx.req.raw.clone()
    const buffer = await clone.arrayBuffer()
    if (buffer.byteLength > MAX_PASSKEY_REQUEST_BYTES) {
      logRefusal(lane, requestId, "body_too_large")
      return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
    }
    rawBody = new Uint8Array(buffer)
  } catch {
    logRefusal(lane, requestId, "unreadable_body")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }
  if (typeof payload.b !== "string" || !sameDigest(payload.b, await sha256HexDigest(rawBody))) {
    logRefusal(lane, requestId, "body_mismatch")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }

  let signature: Uint8Array
  try {
    signature = base64UrlDecode(parts[2])
  } catch {
    logRefusal(lane, requestId, "malformed_assertion")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }
  const signed = new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
  const valid = await crypto.subtle.verify({ name: "Ed25519" }, verifyKey, signature as BufferSource, signed as BufferSource).catch(() => false)
  if (!valid) {
    logRefusal(lane, requestId, "forged_assertion")
    return ctx.json({ error: "Gateway admission is missing or invalid." }, 403)
  }

  ctx.set("gatewayLane", lane)
  ctx.set("gatewayRequestId", requestId)
  await next()
}
