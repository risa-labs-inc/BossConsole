import type { SupabaseClient } from "@supabase/supabase-js"
import { hashApiKey, isValidApiKeyFormat, type ApiKeyScope } from "./api-key.ts"

/**
 * Authentication result for both JWT and API key auth
 */
export interface AuthResult {
  userId: string
  email: string
  isAdmin: boolean
  /** Only set when authenticated via API key */
  apiKeyId?: string
  /** Only set when authenticated via API key */
  apiKeyScopes?: string[]
  /** Only set when authenticated via API key */
  apiKeyName?: string
}

/**
 * Options for getAuthenticatedUser
 */
export interface AuthOptions {
  /** Allow API key authentication (default: false for backward compatibility) */
  allowApiKey?: boolean
  /** Required scopes when using API key (optional) */
  requiredScopes?: ApiKeyScope[]
}

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

    // Update last_used_at (non-blocking but with error logging for audit trail)
    try {
      await supabase.rpc("update_api_key_last_used", { p_key_id: keyInfo.api_key_id })
    } catch (error) {
      console.warn("Failed to update API key last_used timestamp:", error)
      // Don't fail the request, but log for investigation
    }

    return {
      userId: keyInfo.user_id,
      email: userData?.email || "",
      isAdmin: false, // API keys NEVER have admin access
      apiKeyId: keyInfo.api_key_id,
      apiKeyScopes: keyInfo.scopes,
      apiKeyName: keyInfo.key_name,
    }
  } catch (e) {
    console.error("Exception validating API key:", e)
    return null
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
 * @param supabase - Supabase client with service role
 * @param authHeader - Authorization header (Bearer token)
 * @param apiKeyHeader - X-API-Key header
 * @param options - Auth options (allowApiKey, requiredScopes)
 * @returns AuthResult if authenticated, null otherwise
 */
export async function getAuthenticatedUser(
  supabase: SupabaseClient,
  authHeader: string | undefined,
  apiKeyHeader: string | undefined,
  options: AuthOptions = {}
): Promise<AuthResult | null> {
  const { allowApiKey = false, requiredScopes = [] } = options

  // Try JWT first (preferred)
  const jwtUser = await getUserFromToken(supabase, authHeader)
  if (jwtUser) {
    return {
      ...jwtUser,
      // No API key fields for JWT auth
    }
  }

  // Try API key if allowed
  if (allowApiKey && apiKeyHeader) {
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
          return null
        }
      }
      return apiKeyUser
    }
  }

  return null
}

/**
 * Log an API key action for audit trail
 *
 * @param supabase - Supabase client with service role
 * @param apiKeyId - The API key ID (from AuthResult.apiKeyId)
 * @param action - The action being performed
 * @param pluginId - Optional plugin ID being acted on
 * @param request - Optional request for IP/user-agent extraction
 * @param success - Whether the action succeeded
 * @param errorMessage - Optional error message if action failed
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
  try {
    const ipAddress = request?.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
      request?.headers.get("cf-connecting-ip") ||
      null
    const userAgent = request?.headers.get("user-agent") || null

    await supabase.rpc("log_api_key_action", {
      p_api_key_id: apiKeyId,
      p_action: action,
      p_plugin_id: pluginId || null,
      p_ip_address: ipAddress,
      p_user_agent: userAgent,
      p_success: success,
      p_error_message: errorMessage || null,
    })
  } catch (e) {
    // Don't fail the request if logging fails
    console.error("Error logging API key action:", e)
  }
}
