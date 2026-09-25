import type { SupabaseClient } from "@supabase/supabase-js"
import { hashApiKey, isValidApiKeyFormat, type ApiKeyScope } from "./api-key.ts"
import {
  auditAction,
  auditClientIp,
  auditErrorMessage,
  auditPluginId,
  auditUserAgent,
} from "./audit-shape.ts"
import { permissionDeniedMessage } from "./permissions.ts"

/**
 * Authentication result for both JWT and API key auth
 */
export interface AuthResult {
  userId: string
  email: string
  isAdmin: boolean
  /**
   * Effective RBAC permissions from the JWT `user_permissions` claim.
   *
   * `null` when authenticated by API key: there is no token to read a claim
   * from, so the caller's permissions must be resolved from the key OWNER's
   * current roles in the database. Use `userHasPermission()` rather than
   * reading this field — an empty array and `null` mean different things
   * ("holds nothing" vs "not carried inline").
   */
  jwtPermissions: string[] | null
  /** Only set when authenticated via API key */
  apiKeyId?: string
  /** Only set when authenticated via API key */
  apiKeyScopes?: string[]
  /**
   * The organisation this API key may publish for. Only set when authenticated via API key.
   *
   * `validate_plugin_api_key` COALESCEs it to the boss organisation, so keys minted before
   * 20260803000000 keep working rather than losing the ability to publish.
   *
   * IT IS NOT AUTHORITY ON ITS OWN. The key is not an independent principal: the publish path
   * still gates on `user_can_publish_org_plugin(user_id, org_id)`, so revoking the human's
   * membership kills their CI key's publish rights too. That is the RPC's own stated contract.
   */
  apiKeyOrgId?: string
}

/**
 * Options for getAuthenticatedUser
 */
export interface AuthOptions {
  /** Allow API key authentication (default: false for backward compatibility) */
  allowApiKey?: boolean
  /** Required scopes when using API key (optional) */
  requiredScopes?: ApiKeyScope[]
  /**
   * RBAC permission the caller must effectively hold (optional).
   *
   * For API-key auth this is checked against the key owner's CURRENT roles, so
   * revoking a role stops that key immediately rather than at key expiry.
   */
  /**
   * A permission the caller must hold, checked after authentication.
   *
   * NO ROUTE PASSES THIS TODAY. The five publish handlers did, until publishing
   * grew a second way to be allowed (an organisation whose own publish policy
   * admits you), which cannot be evaluated before the owning organisation is
   * known - so that decision moved to services/publish-authz.ts, which calls
   * `userHasPermission` below directly. Kept because it is the right shape for
   * any route whose answer really is one permission, and because dropping it
   * would leave `userHasPermission`'s 403-not-401 contract untested.
   */
  requiredPermission?: string
}

/**
 * Why authentication/authorization failed.
 *
 * `unauthenticated` maps to 401; the other two are authenticated callers who
 * are not allowed to do this, and map to 403.
 */
export type AuthFailureReason =
  | "unauthenticated"
  | "insufficient_scope"
  | "insufficient_permission"

export type AuthOutcome =
  | { ok: true; user: AuthResult }
  | { ok: false; reason: AuthFailureReason; status: 401 | 403; error: string }

// Simple JWT payload decoder (no verification - Supabase already verified)
function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const payload = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(payload)
  } catch {
    return null
  }
}

/**
 * Extract and verify JWT token from Authorization header
 * Returns the user ID if valid, null if invalid or not present
 */
export async function getUserFromToken(
  supabase: SupabaseClient,
  authHeader: string | undefined
): Promise<{ userId: string, email: string, isAdmin: boolean, permissions: string[] } | null> {
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return null
  }

  const token = authHeader.substring(7)

  try {
    const { data: { user }, error } = await supabase.auth.getUser(token)

    if (error || !user) {
      console.error('Error verifying token:', error)
      return null
    }

    // Decode JWT to get is_admin + user_permissions claims (injected by the
    // custom_access_token_hook; same claim the desktop client reads).
    const payload = decodeJwtPayload(token)
    const isAdmin = payload?.is_admin === true
    const rawPerms = payload?.user_permissions
    const permissions = Array.isArray(rawPerms)
      ? rawPerms.filter((p): p is string => typeof p === 'string')
      : []

    return {
      userId: user.id,
      email: user.email || '',
      isAdmin,
      permissions
    }
  } catch (e) {
    console.error('Exception verifying token:', e)
    return null
  }
}

/**
 * Get user's display name from public.users table
 */
export async function getUserDisplayName(
  supabase: SupabaseClient,
  userId: string
): Promise<string> {
  const { data, error } = await supabase
    .from('users')
    .select('email')
    .eq('id', userId)
    .single()

  if (error || !data) {
    console.error('Error getting user display name:', error)
    return 'Unknown'
  }

  // Use email username as display name
  return data.email?.split('@')[0] || 'Unknown'
}


/**
 * Validate an API key from the X-API-Key header
 *
 * IMPORTANT: API keys NEVER have admin access (isAdmin is always false)
 *
 * @param supabase - Supabase client with service role
 * @param apiKeyHeader - The X-API-Key header value
 * @returns AuthResult if valid, null if invalid
 */
export async function validateApiKey(
  supabase: SupabaseClient,
  apiKeyHeader: string | undefined
): Promise<AuthResult | null> {
  if (!apiKeyHeader) {
    return null
  }

  // Validate format first (fast fail)
  if (!isValidApiKeyFormat(apiKeyHeader)) {
    console.error("Invalid API key format")
    return null
  }

  try {
    // Hash the key
    const keyHash = await hashApiKey(apiKeyHeader)

    // Look up in database using the validation function
    const { data, error } = await supabase.rpc("validate_plugin_api_key", {
      p_key_hash: keyHash,
    })

    if (error) {
      console.error("Error validating API key:", error)
      return null
    }

    if (!data || data.length === 0) {
      console.error("API key not found or expired/revoked")
      return null
    }

    const keyInfo = data[0]

    // Get user email for logging/display
    const { data: userData, error: userError } = await supabase
      .from("users")
      .select("email")
      .eq("id", keyInfo.user_id)
      .single()

    if (userError) {
      console.error("Error getting user for API key:", userError)
      return null
    }

    // `key_id`, NOT `api_key_id`. 20260803000000 DROPped and recreated validate_plugin_api_key
    // with `RETURNS TABLE(key_id, user_id, scopes, org_id)`, and this file was never updated - so
    // `keyInfo.api_key_id` has been `undefined` ever since. Two things broke silently: this RPC
    // was called with `p_key_id: undefined`, so `last_used_at` stopped advancing, and every
    // `if (user.apiKeyId)` audit-log call in publish.ts became a no-op, which means API-key
    // publishes have not been recorded in the audit trail at all.
    //
    // `key_name` went away in the same migration and is not read back: the one consumer was a log
    // string that already falls back to the id.
    try {
      await supabase.rpc("update_api_key_last_used", { p_key_id: keyInfo.key_id })
    } catch (error) {
      console.warn("Failed to update API key last_used timestamp:", error)
      // Don't fail the request, but log for investigation
    }

    return {
      userId: keyInfo.user_id,
      email: userData?.email || "",
      isAdmin: false, // API keys NEVER have admin access
      // No JWT, so no user_permissions claim — resolved from the owner's roles
      // on demand by userHasPermission().
      jwtPermissions: null,
      apiKeyId: keyInfo.key_id,
      apiKeyScopes: keyInfo.scopes,
      apiKeyOrgId: keyInfo.org_id ?? undefined,
    }
  } catch (e) {
    console.error("Exception validating API key:", e)
    return null
  }
}

/**
 * Does this caller effectively hold `permission`?
 *
 * Session auth reads the JWT `user_permissions` claim (no round trip). API-key
 * auth asks the database about the key's OWNER via the `user_has_permission`
 * probe, so a role revoked after the key was minted takes effect immediately.
 *
 * Fails CLOSED on an unexpected DB error — unlike validateDeclaredPermissions,
 * this gates writes, so an outage must not become an open door.
 *
 * `data === true` is correct and not an oversight: PostgREST returns a bare
 * value for a scalar-returning function, which routes/api-keys.ts already relies
 * on for `get_user_api_key_count` (`RETURNS INTEGER`, compared directly against
 * MAX_API_KEYS_PER_USER). Only SETOF/TABLE functions come back as arrays, as
 * `validate_plugin_api_key` does above. Do not "harden" this into an unwrap.
 */
export async function userHasPermission(
  supabase: SupabaseClient,
  user: AuthResult,
  permission: string,
): Promise<boolean> {
  if (user.isAdmin) return true

  if (user.jwtPermissions !== null) {
    return user.jwtPermissions.includes(permission)
  }

  try {
    const { data, error } = await supabase.rpc("user_has_permission", {
      p_user_id: user.userId,
      p_permission: permission,
    })
    if (error) {
      console.error(`user_has_permission(${permission}) RPC error:`, error)
      return false
    }
    return data === true
  } catch (e) {
    console.error(`user_has_permission(${permission}) threw:`, e)
    return false
  }
}

/**
 * Combined authentication that checks both JWT and API key
 *
 * Priority: JWT (Authorization header) > API Key (X-API-Key header)
 *
 * Use this for endpoints that should accept both auth methods.
 * For admin-only endpoints, always use getUserFromToken() directly.
 *
 * Returns a discriminated outcome rather than `AuthResult | null` so callers can
 * tell "who are you?" (401) from "not allowed" (403). A valid key presented with
 * the wrong scope used to collapse into the same null as no credentials at all
 * and was reported as 401; it is an authenticated caller and now yields 403.
 *
 * @param supabase - Supabase client with service role
 * @param authHeader - Authorization header (Bearer token)
 * @param apiKeyHeader - X-API-Key header
 * @param options - Auth options (allowApiKey, requiredScopes, requiredPermission)
 */
export async function getAuthenticatedUser(
  supabase: SupabaseClient,
  authHeader: string | undefined,
  apiKeyHeader: string | undefined,
  options: AuthOptions = {}
): Promise<AuthOutcome> {
  const { allowApiKey = false, requiredScopes = [], requiredPermission } = options

  const unauthenticated = {
    ok: false,
    reason: "unauthenticated",
    status: 401,
    error: "Authentication required",
  } as const

  let user: AuthResult | null = null

  // Try JWT first (preferred)
  const jwtUser = await getUserFromToken(supabase, authHeader)
  if (jwtUser) {
    user = {
      userId: jwtUser.userId,
      email: jwtUser.email,
      isAdmin: jwtUser.isAdmin,
      jwtPermissions: jwtUser.permissions,
      // No API key fields for JWT auth
    }
  } else if (allowApiKey && apiKeyHeader) {
    const apiKeyUser = await validateApiKey(supabase, apiKeyHeader)
    if (apiKeyUser) {
      // Check required scopes
      if (requiredScopes.length > 0) {
        const hasAllScopes = requiredScopes.every((scope) =>
          apiKeyUser.apiKeyScopes?.includes(scope)
        )
        if (!hasAllScopes) {
          console.error(
            `API key missing required scopes: ${requiredScopes.join(", ")}`
          )
          return {
            ok: false,
            reason: "insufficient_scope",
            status: 403,
            error: `API key is missing required scope(s): ${requiredScopes.join(", ")}`,
          }
        }
      }
      user = apiKeyUser
    }
  }

  if (!user) return unauthenticated

  if (requiredPermission && !(await userHasPermission(supabase, user, requiredPermission))) {
    console.error(
      `User ${user.userId} lacks required permission ${requiredPermission}` +
        // The id, not a name: `key_name` stopped being returned by validate_plugin_api_key in
        // 20260803000000, so the `?? apiKeyName` branch had been dead and always fell through.
        (user.apiKeyId ? ` (via API key ${user.apiKeyId})` : "")
    )
    return {
      ok: false,
      reason: "insufficient_permission",
      status: 403,
      error: permissionDeniedMessage(requiredPermission),
    }
  }

  return { ok: true, user }
}

/**
 * Log an API key action for audit trail
 *
 * plugin_api_key_logs is an audit table: every field is allowlist-shaped at
 * this single persistence boundary before it reaches the database
 * (utils/audit-shape.ts). The client address comes only from a hop the edge
 * trusts (cf-connecting-ip, else the RIGHTMOST x-forwarded-for entry - the
 * leftmost is whatever the caller typed); the user agent is bounded and
 * control-char free; the plugin id persists only when id-shaped; the action
 * must be a server word; error text is bounded. A client with a valid key
 * can no longer park arbitrary caller-chosen content in the audit trail
 * through headers or ids.
 *
 * @param supabase - Supabase client with service role
 * @param apiKeyId - The API key ID (from AuthResult.apiKeyId)
 * @param action - The action being performed (server constant; shaped)
 * @param pluginId - Optional plugin ID being acted on (shaped; null when non-id)
 * @param request - Optional request for IP/user-agent extraction (trusted hops only)
 * @param success - Whether the action succeeded
 * @param errorMessage - Optional error message if action failed (bounded)
 */
export async function logApiKeyAction(
  supabase: SupabaseClient,
  apiKeyId: string,
  action: string,
  pluginId?: string,
  request?: Request,
  success = true,
  errorMessage?: string
): Promise<void> {
  const logFailure = (error: unknown, thrownKind?: string) => {
    // Supabase RPC errors resolve as { data, error }; thrown request failures
    // reach the catch below. Log only a database error code, never its message
    // or details, which may contain values from the audit record.
    const code = typeof error === "object" && error !== null && "code" in error
      ? error.code
      : undefined
    const safeCode = typeof code === "string" && /^(?:[0-9A-Z]{5}|PGRST\d{3})$/.test(code)
      ? code
      : undefined

    console.error(
      "Error logging API key action:",
      safeCode ? { code: safeCode } : thrownKind ?? "Audit logging failed",
    )
  }

  try {
    const ipAddress = auditClientIp(
      request?.headers.get("cf-connecting-ip"),
      request?.headers.get("x-forwarded-for"),
    )
    const userAgent = auditUserAgent(request?.headers.get("user-agent"))

    const { error } = await supabase.rpc("log_api_key_action", {
      p_api_key_id: apiKeyId,
      p_action: auditAction(action),
      p_plugin_id: auditPluginId(pluginId),
      p_ip_address: ipAddress,
      p_user_agent: userAgent,
      p_success: success,
      p_error_message: auditErrorMessage(errorMessage),
    })
    if (error) {
      logFailure(error)
    }
  } catch (e) {
    // Don't fail the request if logging fails
    // Keep the error class useful for diagnosing local shaping/runtime bugs,
    // but never log the error's mutable name or message.
    const thrownKind = e instanceof TypeError
      ? "TypeError"
      : e instanceof Error
      ? "Error"
      : typeof e
    logFailure(e, thrownKind)
  }
}
