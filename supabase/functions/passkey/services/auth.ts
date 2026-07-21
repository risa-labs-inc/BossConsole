import type { SupabaseClient } from "@supabase/supabase-js"
import { generateChallenge, storeChallenge } from "../utils/challenge.ts"
import { verifyChallenge, findPasskeyByCredentialId, getUserPasskeys, findUserByEmail, getUserWithEmail } from "../utils/database.ts"
import { verifySignature } from "../utils/crypto.ts"
import { ChallengeType } from "../types/challenge.ts"
import { withErrorHandler, withStatusErrorHandler } from "../utils/error-handler.ts"
import { generateSupabaseAccessToken } from "../utils/jwt.ts"
import { getRpId } from "../utils/config.ts"

export const ALLOWED_ORIGINS = [
  'boss://authenticate',
  'http://localhost:3000',
  'http://localhost:54321',  // Supabase local functions
  'https://risaboss.com',
  'https://api.risaboss.com'
]

export interface AuthenticationCredential {
  id: string
  rawId: string
  type: string
  response: {
    clientDataJSON: string
    authenticatorData: string
    signature: string
    userHandle?: string
  }
}

/**
 * Generates an authentication challenge for a user
 */
export const generateAuthChallenge = withErrorHandler(
  async (supabase: SupabaseClient, email: string, sessionId?: string) => {
    console.log('🔑 Generating authentication challenge for email:', email)

    // Use utility function for scalable user lookup
    const userResult = await findUserByEmail(supabase, email)

    if (!userResult.success || !userResult.user) {
      console.error('User not found with email:', email)
      return {
        success: false,
        error: 'User not found'
      }
    }

    const userId = userResult.user.id
    console.log('Resolved email to user ID:', userId)

    // Get user's passkeys
    const passkeyResult = await getUserPasskeys(supabase, userId)

    if (!passkeyResult.success) {
      console.error('Error fetching user passkeys:', passkeyResult.error)
      return {
        success: false,
        error: 'Failed to fetch user credentials'
      }
    }

    const userPasskeys = passkeyResult.passkeys || []

    if (userPasskeys.length === 0) {
      return {
        success: false,
        error: 'No passkeys found for user'
      }
    }

    // Generate and store challenge
    const challenge = generateChallenge()
    const storeResult = await storeChallenge(supabase, challenge, ChallengeType.Authentication, {
      userId,
      sessionId
    })

    if (!storeResult.success) {
      return {
        success: false,
        error: storeResult.error || 'Failed to store challenge'
      }
    }

    // Build allowed credentials list
    const allowedCredentials = userPasskeys.map(pk => ({
      id: pk.credential_id,
      type: 'public-key',
      transports: pk.transports || ['internal']
    }))

    const rpId = getRpId()

    return {
      success: true,
      challenge,
      timeout: 60000,
      rpId,
      userVerification: 'preferred',
      allowCredentials: allowedCredentials,
      sessionId
    }
  },
  'Failed to generate authentication challenge',
  '🔑'
)

/**
 * Completes an authentication ceremony by verifying the credential
 */
export const completeAuthentication = withErrorHandler(
  async (
    supabase: SupabaseClient,
    credential: AuthenticationCredential,
    challenge: string
  ) => {
    console.log('🔐 Starting authentication completion')

    const { clientDataJSON, authenticatorData, signature } = credential.response

    // Decode the base64-encoded clientDataJSON to get the raw JSON string
    const clientDataJSONString = atob(clientDataJSON)

    // Parse and validate client data
    const clientData = JSON.parse(clientDataJSONString)
    console.log('Client data:', clientData)

    // Verify challenge type
    if (clientData.type !== 'webauthn.get') {
      return {
        success: false,
        error: 'Invalid ceremony type - expected webauthn.get'
      }
    }

    // Verify challenge (but don't consume yet)
    const challengeResult = await verifyChallenge(
      supabase,
      challenge,
      ChallengeType.Authentication
    )

    if (!challengeResult.success) {
      return {
        success: false,
        error: challengeResult.error || 'Invalid challenge'
      }
    }

    // Find passkey by credential ID
    const passkeyResult = await findPasskeyByCredentialId(
      supabase,
      credential.id
    )

    if (!passkeyResult.success || !passkeyResult.passkey) {
      return {
        success: false,
        error: 'Passkey not found'
      }
    }

    const passkey = passkeyResult.passkey

    // Verify signature - pass the decoded JSON string, not the base64-encoded version
    const signatureValid = await verifySignature(
      passkey.public_key,
      signature,
      authenticatorData,
      clientDataJSONString
    )

    if (!signatureValid) {
      return {
        success: false,
        error: 'Invalid signature'
      }
    }

    // Update last used timestamp
    await supabase
      .from('user_passkeys')
      .update({ last_used_at: Date.now() })
      .eq('id', passkey.id)

    // Store completed authentication if there's a session_id
    const challengeData = challengeResult.challengeData
    console.log('🔍 Challenge data:', {
      has_session_id: !!challengeData.session_id,
      session_id: challengeData.session_id,
      user_id: passkey.user_id
    })

    if (challengeData.session_id) {
      console.log('💾 Storing completed authentication for session:', challengeData.session_id)
      const { data: insertData, error: insertError } = await supabase
        .from('completed_authentications')
        .insert({
          challenge: challenge,  // Required NOT NULL field
          session_id: challengeData.session_id,
          user_id: passkey.user_id,
          created_at: new Date().toISOString()
        })
        .select()

      if (insertError) {
        console.error('❌ Failed to store completed authentication:', insertError)
        console.error('❌ Insert error details:', JSON.stringify(insertError, null, 2))
        console.error('❌ Insert error code:', insertError.code)
        console.error('❌ Insert error message:', insertError.message)
        // Don't consume challenge if we failed to store completed auth
        return {
          success: false,
          error: `Failed to store authentication result: ${insertError.message || insertError.code || 'Unknown error'}`
        }
      } else {
        console.log('✅ Stored completed authentication:', insertData)

        // Now it's safe to delete the challenge
        console.log('🗑️ Deleting consumed challenge')
        await supabase
          .from('passkey_challenges')
          .delete()
          .eq('id', challengeData.id)
      }
    } else {
      console.warn('⚠️ No session_id in challenge data - completed authentication not stored')
    }

    console.log('✅ Authentication successful for user:', passkey.user_id)

    // Fetch user email using helper function (DRY)
    const userResult = await getUserWithEmail(supabase, passkey.user_id)

    if (!userResult.success || !userResult.user) {
      console.error('❌ Failed to fetch user email for session:', userResult.error)
      return {
        success: true,
        userId: passkey.user_id,
        passkeyId: passkey.id
      }
    }

    const userEmail = userResult.user.email

    // Generate Supabase session for authenticated user
    console.log('🎫 Generating Supabase session for user:', userEmail)

    const tokens = await generateSupabaseAccessToken(supabase, userEmail)

    console.log('✅ Generated Supabase session successfully')
    console.log('   Access token length:', tokens.accessToken.length)
    console.log('   Refresh token length:', tokens.refreshToken.length)

    return {
      success: true,
      userId: passkey.user_id,
      email: userEmail,
      passkeyId: passkey.id,
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      expiresAt: tokens.expiresAt
    }
  },
  'Failed to complete authentication',
  '🔐'
)

/**
 * Checks the status of an authentication session
 */
export const checkAuthStatus = withStatusErrorHandler(
  async (supabase: SupabaseClient, sessionId: string) => {
    console.log('🔍 Checking auth status for session:', sessionId)

    const { data, error } = await supabase
      .from('passkey_challenges')
      .select('*')
      .eq('session_id', sessionId)
      .eq('type', ChallengeType.Authentication)
      .single()

    if (error || !data) {
      console.log('🔍 Challenge not found or consumed, checking completed_authentications')
      console.log('🔍 Challenge query error:', error)

      // Session might be consumed (authentication complete)
      // Check if there's a completed authentication record
      const { data: completedAuth, error: completedError } = await supabase
        .from('completed_authentications')
        .select('*')
        .eq('session_id', sessionId)
        .single()

      console.log('🔍 Completed auth query result:', {
        found: !!completedAuth,
        error: completedError?.message || completedError?.code,
        data: completedAuth
      })

      if (completedError || !completedAuth) {
        console.log('❌ No completed authentication found for session:', sessionId)
        return {
          status: 'expired' as const,
          message: 'Session not found or expired'
        }
      }

      console.log('✅ Found completed authentication:', completedAuth.user_id)

      // Fetch user email using helper function (DRY)
      const userResult = await getUserWithEmail(supabase, completedAuth.user_id)

      if (!userResult.success || !userResult.user) {
        console.error('❌ Failed to fetch user email:', userResult.error)
        return {
          status: 'completed' as const,
          userId: completedAuth.user_id,
          completedAt: completedAuth.created_at
          // Email will be null, but at least we return the status
        }
      }

      // Generate Supabase session for passkey authentication
      console.log('🎫 Generating Supabase session for passkey auth:', userResult.user.email)

      const tokens = await generateSupabaseAccessToken(supabase, userResult.user.email)

      console.log('✅ Generated Supabase session successfully')

      return {
        status: 'completed' as const,
        userId: completedAuth.user_id,
        email: userResult.user.email,
        completedAt: completedAuth.created_at,
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
        expiresAt: tokens.expiresAt
      }
    }

    // Check if expired
    const expiresAt = new Date(data.expires_at)
    if (expiresAt < new Date()) {
      return {
        status: 'expired' as const,
        message: 'Session expired'
      }
    }

    return {
      status: 'pending' as const,
      expiresAt: Math.floor(expiresAt.getTime() / 1000) // Convert to Unix timestamp
    }
  },
  'Failed to check authentication status',
  '🔍'
)
