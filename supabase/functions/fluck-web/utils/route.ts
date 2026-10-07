/**
 * `__Host-fluck_route`: which BOSS the alias Worker proxies fluck.risaboss.com/* to.
 *
 * Set on a successful POST /api/open when FLUCK_ROUTE_SECRET is configured. Value is
 * `<payload>.<sig>`: payload = base64url(JSON {"u": user_id, "i": instance_id, "e": unix-seconds expiry}),
 * sig = base64url(HMAC-SHA256(FLUCK_ROUTE_SECRET, payload)). The Worker verifies it (same secret,
 * infra/cloudflare/fluck-web-alias/worker.js) and resolves the endpoint via GET /internal/endpoint.
 * `u` is the authenticated caller (instance_id is unique only per owner), so a route can never
 * resolve to another account's row with the same instance id. Path=/ because the chat is served from the root; HttpOnly, so the chat's own scripts never see it.
 */

export const ROUTE_COOKIE_NAME = "__Host-fluck_route"
export const ROUTE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60

export function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

export async function routeCookieValue(
  secret: string,
  userId: string,
  instanceId: string,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<string> {
  const payload = base64Url(new TextEncoder().encode(JSON.stringify({ u: userId, i: instanceId, e: nowSeconds + ROUTE_MAX_AGE_SECONDS })))
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"])
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload)))
  return `${payload}.${base64Url(sig)}`
}

export async function routeCookieHeader(secret: string, userId: string, instanceId: string, nowSeconds?: number): Promise<string> {
  const value = await routeCookieValue(secret, userId, instanceId, nowSeconds)
  return `${ROUTE_COOKIE_NAME}=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=${ROUTE_MAX_AGE_SECONDS}`
}
