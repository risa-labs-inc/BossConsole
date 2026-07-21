import type { SupabaseClient } from "@supabase/supabase-js"

/**
 * Generates Supabase session using Admin API for passkey authentication
 *
 * FIXES ISSUE #75: Refresh Token Bug
 * ===================================
 * Uses Supabase Admin API to create proper sessions with unique string refresh tokens
 * stored in auth.sessions table, enabling automatic token refresh.
 *
 * WHY ADMIN API APPROACH:
 * =======================
 * Using admin.generateLink() + verifyOtp() creates proper Supabase sessions with:
 * 1. Unique string refresh tokens (not JWTs)
 * 2. Tokens stored in auth.sessions database table
 * 3. Single-use tokens with automatic rotation
 * 4. RBAC claims injected via auth hooks (maintains authorization)
 *
 * HOW IT WORKS:
 * =============
 * 1. Client performs passkey authentication (Touch ID, Windows Hello, etc.)
 * 2. Edge Function verifies the passkey signature cryptographically
 * 3. This function uses Admin API to create a proper Supabase session
 * 4. Auth hook runs and injects RBAC claims (user_role, user_roles, is_admin)
 * 5. Client imports the session using auth.importSession()
 * 6. Auto-refresh works correctly because refresh token is in auth.sessions
 *
 * SECURITY CONSIDERATIONS:
 * ========================
 * - Requires admin client capabilities (service role key)
 * - Auth hook must be enabled for RBAC claims injection
 * - Refresh tokens are single-use with automatic rotation
 * - Sessions are properly tracked in auth.sessions table
 *
 * RELATED DOCUMENTATION:
 * ======================
 * - See SessionManager.kt (client) for session establishment
 * - See UserDataStorage.kt (client) for user data persistence
 * - See auth.ts (server) for authentication flow
 * - See crypto.ts (server) for signature verification
 *
 * @param supabase - Supabase client with admin capabilities
 * @param email - The authenticated user's email address
 * @returns Object containing accessToken, refreshToken, expiresAt, and expiresIn
 */
export async function generateSupabaseAccessToken(
  supabase: SupabaseClient,
  email: string
): Promise<{ accessToken: string; refreshToken: string; expiresAt: number; expiresIn: number }> {
  console.log('🔐 Generating Supabase session using Admin API')

  try {
    // Step 1: Generate a magic link using Admin API
    // This creates a hashed token we can verify to establish a session
    const { data: linkData, error: linkError } = await supabase.auth.admin.generateLink({
      type: 'magiclink',
      email: email,
    })

    if (linkError || !linkData) {
      console.error('❌ Failed to generate magic link:', linkError)
      throw new Error(`Failed to generate session link: ${linkError?.message || 'Unknown error'}`)
    }

    console.log('✅ Generated magic link token for session creation')

    // Step 2: Verify the OTP token to create a proper Supabase session
    // This creates a session with:
    // - Unique string refresh token stored in auth.sessions table
    // - Access token with RBAC claims (injected by auth hook)
    // - Proper expiration and rotation
    const { data: sessionData, error: sessionError } = await supabase.auth.verifyOtp({
      token_hash: linkData.properties.hashed_token,
      type: 'magiclink',
    })

    if (sessionError || !sessionData?.session) {
      console.error('❌ Failed to verify OTP and create session:', sessionError)
      throw new Error(`Failed to create session: ${sessionError?.message || 'Unknown error'}`)
    }

    const session = sessionData.session
    console.log('✅ Created Supabase session with proper refresh token')
    console.log(`   Session expires in: ${session.expires_in} seconds`)
    console.log(`   Access token length: ${session.access_token.length}`)
    console.log(`   Refresh token format: ${session.refresh_token.substring(0, 20)}... (unique string)`)

    // Calculate expiration timestamp
    const now = Math.floor(Date.now() / 1000)
    const expiresAt = now + session.expires_in

    return {
      accessToken: session.access_token,
      refreshToken: session.refresh_token,
      expiresAt: expiresAt,
      expiresIn: session.expires_in
    }
  } catch (error) {
    console.error('❌ Exception in generateSupabaseAccessToken:', error)
    throw error
  }
}
