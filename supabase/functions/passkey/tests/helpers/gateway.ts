/**
 * Test-only gateway assertion minting.
 *
 * Mirrors the gateway's signing contract documented in ADMISSION.md: compact
 * JWS, EdDSA over raw Ed25519 keys, payload bound to method, canonical path,
 * body digest, lane, window, request ID, and times. Production signing lives
 * in the gateway deployment repo; this helper exists so route tests can prove
 * the edge verifies rather than trusts.
 */

const encoder = new TextEncoder()

export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

export function base64UrlDecode(input: string): Uint8Array {
  const padded = input.replace(/-/g, "+").replace(/_/g, "/")
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

export async function sha256BodyDigest(body: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(body))
  return base64UrlEncode(new Uint8Array(digest))
}

export interface MintOptions {
  method?: string
  path?: string
  body?: string
  lane?: string
  windowId?: string
  requestId?: string
  issuedAgoSeconds?: number
  ttlSeconds?: number
  key?: CryptoKey
}

export async function mintGatewayAssertion(privateKey: CryptoKey, options: MintOptions = {}): Promise<string> {
  const nowSeconds = Math.floor(Date.now() / 1000)
  const iat = nowSeconds - (options.issuedAgoSeconds ?? 0)
  const payload = {
    m: options.method ?? "POST",
    p: options.path ?? "/auth/challenge",
    b: await sha256BodyDigest(options.body ?? ""),
    iat,
    exp: iat + (options.ttlSeconds ?? 60),
    jti: options.requestId ?? crypto.randomUUID(),
    w: options.windowId ?? "window-test",
    lane: options.lane ?? "untrusted",
  }
  const header = base64UrlEncode(encoder.encode(JSON.stringify({ alg: "EdDSA", typ: "JWT" })))
  const encodedPayload = base64UrlEncode(encoder.encode(JSON.stringify(payload)))
  const signature = await crypto.subtle.sign({ name: "Ed25519" }, privateKey, encoder.encode(`${header}.${encodedPayload}`))
  return `${header}.${encodedPayload}.${base64UrlEncode(new Uint8Array(signature))}`
}

export async function gatewayTestKeys(): Promise<{ privateKey: CryptoKey; publicKeyRaw: string }> {
  const pair = await crypto.subtle.generateKey({ name: "Ed25519", namedCurve: "Ed25519" }, true, ["sign", "verify"]) as CryptoKeyPair
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey))
  return { privateKey: pair.privateKey, publicKeyRaw: base64UrlEncode(raw) }
}

export function useGatewayTestKeys(publicKeyRaw: string, host = "gateway.test"): void {
  Deno.env.set("GATEWAY_ADMISSION_PUBLIC_KEY", publicKeyRaw)
  Deno.env.set("GATEWAY_PUBLIC_HOSTS", host)
}

export function clearGatewayTestKeys(): void {
  Deno.env.delete("GATEWAY_ADMISSION_PUBLIC_KEY")
  Deno.env.delete("GATEWAY_PUBLIC_HOSTS")
}
