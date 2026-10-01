/**
 * session-fork: mint a second, independent session for the CALLER'S OWN user.
 *
 * Used by BOSS when it opens a profile (a separate BOSS process and window) that shares the
 * signed-in account. The two processes cannot share one session: Supabase rotates refresh tokens,
 * so the first refresh by either process would invalidate the other's. This function gives the
 * profile a session of its own, created the same way the passkey function mints sessions
 * (Admin API generateLink + verifyOtp), so it lives in auth.sessions, refreshes normally and is
 * revoked independently.
 *
 * POST, no body. Authorization: Bearer <the caller's access token>.
 *
 * Who can call it, and what it can be turned into:
 * - The access token is validated by GoTrue (auth.getUser), so it must be live, unexpired and
 *   belong to an existing user. The gateway also verifies it (verify_jwt = true in config.toml).
 * - The session is always for the token's own user; nothing in the request names a user.
 * - The token must have been issued within FRESH_TOKEN_MAX_AGE_S. BOSS refreshes immediately
 *   before calling, so this costs it nothing, while an access token that leaked from a log or a
 *   proxy an hour ago cannot be converted into a long-lived refresh token.
 * - Tokens are never logged; the response is marked no-store.
 *
 * Response 200: { access_token, refresh_token, expires_in, user_id }.
 */
import type { SupabaseClient } from "@supabase/supabase-js"

export const FRESH_TOKEN_MAX_AGE_S = 120
const CLOCK_SKEW_S = 30

const noStore = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
}

function reply(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), { status, headers: noStore })
}

/** The JWT's claims, unverified - only read after GoTrue has validated the same token. */
export function claimsOf(token: string): Record<string, unknown> | null {
  const parts = token.split(".")
  if (parts.length !== 3) return null
  try {
    const padded = parts[1].replace(/-/g, "+").replace(/_/g, "/")
    const json = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4))
    const claims = JSON.parse(json)
    return typeof claims === "object" && claims !== null ? claims : null
  } catch {
    return null
  }
}

/** Why a validated token is still not acceptable here, or null when it is. */
export function freshnessRefusal(claims: Record<string, unknown>, nowS: number): string | null {
  if (claims.role !== "authenticated") return "Only a signed-in user's token can fork a session"
  const iat = claims.iat
  if (typeof iat !== "number" || !Number.isFinite(iat)) return "The token carries no issue time"
  if (iat > nowS + CLOCK_SKEW_S) return "The token was issued in the future"
  if (nowS - iat > FRESH_TOKEN_MAX_AGE_S) return "Refresh the session before forking it"
  return null
}

export function createHandler(
  admin: () => SupabaseClient,
  now: () => number = () => Math.floor(Date.now() / 1000),
): (req: Request) => Promise<Response> {
  return async (req) => {
    if (req.method !== "POST") return reply(405, { error: "method_not_allowed" })

    const header = req.headers.get("Authorization") ?? ""
    const match = /^Bearer\s+(\S+)$/i.exec(header)
    if (!match) return reply(401, { error: "missing_token" })
    const token = match[1]

    const claims = claimsOf(token)
    if (!claims) return reply(401, { error: "malformed_token" })

    const client = admin()
    const { data: userData, error: userError } = await client.auth.getUser(token)
    const user = userData?.user
    if (userError || !user) return reply(401, { error: "invalid_token" })
    if (claims.sub !== user.id) return reply(401, { error: "invalid_token" })

    const refusal = freshnessRefusal(claims, now())
    if (refusal) return reply(401, { error: "stale_token", message: refusal })

    if (!user.email) return reply(422, { error: "no_email", message: "This account has no email to mint a session for" })

    try {
      const { data: link, error: linkError } = await client.auth.admin.generateLink({
        type: "magiclink",
        email: user.email,
      })
      if (linkError || !link?.properties?.hashed_token) throw new Error(linkError?.message ?? "no link")

      const { data: verified, error: verifyError } = await client.auth.verifyOtp({
        token_hash: link.properties.hashed_token,
        type: "magiclink",
      })
      const session = verified?.session
      if (verifyError || !session) throw new Error(verifyError?.message ?? "no session")
      if (session.user?.id !== user.id) throw new Error("minted session belongs to another user")

      console.log("session-fork: minted a session", { user: user.id.slice(0, 8) })
      return reply(200, {
        access_token: session.access_token,
        refresh_token: session.refresh_token,
        expires_in: session.expires_in,
        user_id: user.id,
      })
    } catch (error) {
      console.error("session-fork: mint failed", { message: (error as Error).message })
      return reply(502, { error: "mint_failed" })
    }
  }
}
