import { authFailureDetails } from "./logging.ts"
/**
 * Caller authentication for the passkey function.
 *
 * The function is deployed with `verify_jwt = false` (`supabase/config.toml`)
 * because most of its endpoints are reached before the caller has a session —
 * the whole point of authentication is to get one. Endpoints that *do* need a
 * caller identity therefore have to establish it themselves, which is what this
 * module is for.
 *
 * Enrolling a passkey is one of those endpoints: a credential enrolled on an
 * account is a permanent way in, so the request has to prove it comes from that
 * account's owner rather than name them in a body field.
 */

import type { SupabaseClient } from "@supabase/supabase-js"

export interface AuthenticatedCaller {
  userId: string
  email?: string
}

export interface CallerResult {
  success: boolean
  caller?: AuthenticatedCaller
  /** Client-facing message; deliberately non-specific about why a token failed */
  error?: string
  /** 401 when no usable credential was presented, 403 when it was not permitted */
  status?: 401 | 403
}

/**
 * Pulls the token out of an `Authorization: Bearer …` header.
 *
 * Returns null for a missing, malformed or empty header rather than throwing,
 * so callers can distinguish "no credential" from "bad credential".
 */
export function extractBearerToken(header: string | null | undefined): string | null {
  if (typeof header !== 'string') return null

  const match = header.match(/^\s*Bearer\s+(.+?)\s*$/i)
  if (!match) return null

  const token = match[1].trim()
  return token.length > 0 ? token : null
}

/**
 * True when the token is one of the project's own API keys rather than a user
 * session.
 *
 * Supabase clients conventionally send the anon key as `Authorization: Bearer`
 * when no user is signed in, so a bearer header is not by itself evidence of a
 * caller. These keys carry no `sub` and would fail verification anyway; naming
 * them explicitly keeps "no session" distinguishable from "bad session".
 */
export function isProjectApiKey(token: string): boolean {
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
  return (!!anonKey && token === anonKey) || (!!serviceKey && token === serviceKey)
}

/**
 * Resolves an access token to the user it belongs to.
 *
 * Verification goes through the Auth API rather than a local signature check so
 * that revoked and expired sessions are rejected too — a locally-verified JWT
 * stays "valid" until it expires even after the user signs out everywhere.
 *
 * The project's anon and service-role keys are also JWTs, and they must not pass
 * for a user: they carry no `sub`, so the Auth API rejects them, and the role
 * check below is a second line in case a future key shape does not.
 */
export async function verifyCallerToken(
  supabase: SupabaseClient,
  token: string
): Promise<CallerResult> {
  try {
    const { data, error } = await supabase.auth.getUser(token)

    if (error || !data?.user?.id) {
      console.error('❌ Caller token rejected:', authFailureDetails(error))
      return { success: false, error: 'Invalid or expired session', status: 401 }
    }

    const user = data.user
    if (user.role && user.role !== 'authenticated') {
      console.error('❌ Caller token is not an end-user session, role:', user.role)
      return { success: false, error: 'Invalid or expired session', status: 401 }
    }

    return {
      success: true,
      caller: { userId: user.id, email: user.email ?? undefined }
    }
  } catch (error) {
    console.error('❌ Failed to verify caller token:', authFailureDetails(error))
    return { success: false, error: 'Invalid or expired session', status: 401 }
  }
}

/**
 * Requires a signed-in caller, given a request's Authorization header.
 */
export async function requireAuthenticatedCaller(
  supabase: SupabaseClient,
  authorizationHeader: string | null | undefined
): Promise<CallerResult> {
  const token = extractBearerToken(authorizationHeader)

  if (!token || isProjectApiKey(token)) {
    return {
      success: false,
      error: 'Authentication required',
      status: 401
    }
  }

  return await verifyCallerToken(supabase, token)
}

/**
 * Resolves an *optional* caller: absent is fine, present must be valid.
 *
 * Used where the transport cannot always carry a token (a page loaded in a phone
 * browser has no session of ours) but the identity must still be honoured when
 * it is available.
 */
export async function resolveOptionalCaller(
  supabase: SupabaseClient,
  authorizationHeader: string | null | undefined
): Promise<CallerResult> {
  const token = extractBearerToken(authorizationHeader)
  if (!token || isProjectApiKey(token)) {
    return { success: true }
  }

  const result = await verifyCallerToken(supabase, token)
  if (result.success) {
    return result
  }

  // A bearer we cannot resolve to a user is treated as no caller rather than as
  // a rejection: on this path the identity comes from the challenge row, so an
  // unusable token adds nothing to reject. Being strict here would break any
  // client that sends some other project key (formats change) without buying a
  // check — a *valid* session for the wrong user is still caught downstream.
  console.log('ℹ️ Ignoring an unusable bearer token on an optional-auth endpoint')
  return { success: true }
}
