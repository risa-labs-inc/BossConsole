import type { SupabaseClient } from "@supabase/supabase-js"
import { withErrorHandler } from "../utils/error-handler.ts"
import { normalizeBase64Url } from "../utils/base64.ts"
import { getRpName } from "../utils/config.ts"
import { maskEmail, maskUserId } from "../utils/logging.ts"

function loadRegistrationChallenge(
  supabase: SupabaseClient,
  challenge: string
) {
  return supabase
    .from('passkey_challenges')
    .select('*')
    .eq('challenge', challenge)
    .eq('type', 'registration')
    .gt('expires_at', new Date().toISOString())
    .single()
}

export const generateLegacyMobileRegistrationPage = withErrorHandler(
  async (
    supabase: SupabaseClient,
    challenge: string,
    email: string,
    sessionId: string,
    rpId: string
  ) => {
    console.log('📱 Generating legacy mobile registration page for:', maskEmail(email))

    const rpName = getRpName(rpId)
    const { data: challengeData, error: challengeError } =
      await loadRegistrationChallenge(supabase, challenge)

    if (challengeError || !challengeData) {
      return {
        success: false,
        error: 'Invalid or expired registration link'
      }
    }

    let effectiveChallenge = challengeData

    if (!challengeData.session_id) {
      const { data: claimedChallenge, error: claimError } = await supabase
        .from('passkey_challenges')
        .update({
          session_id: sessionId,
          status: 'in_progress'
        })
        .eq('challenge', challenge)
        .eq('type', 'registration')
        .is('session_id', null)
        .select('*')
        .single()

      if (claimError || !claimedChallenge) {
        console.error('❌ Legacy registration challenge claim rejected')
        return { success: false, error: 'Invalid registration session' }
      }

      effectiveChallenge = claimedChallenge
    }

    if (effectiveChallenge.session_id !== sessionId) {
      console.error('❌ Legacy registration challenge session binding rejected')
      return { success: false, error: 'Invalid registration session' }
    }

    const userId = effectiveChallenge.user_id
    if (!userId) {
      return {
        success: false,
        error: 'Invalid registration challenge'
      }
    }

    return {
      success: true,
      userId,
      email,
      challenge,
      sessionId,
      rpId,
      rpName
    }
  },
  'Failed to generate legacy mobile registration page',
  '📱'
)

/**
 * Mobile Registration Service
 * Handles business logic for mobile registration HTML page generation
 */
export const generateMobileRegistrationPage = withErrorHandler(
  async (
    supabase: SupabaseClient,
    challenge: string,
    email: string,
    sessionId: string,
    rpId: string
  ) => {
    console.log('📱 Generating mobile registration page for:', maskEmail(email))

    // rp.name is rendered by the OS passkey prompt, so it is derived from the
    // (allow-listed) rpId — a request-supplied name would be UI spoofing.
    const rpName = getRpName(rpId)

    // Verify challenge exists and is valid
    const { data: challengeData, error: challengeError } =
      await loadRegistrationChallenge(supabase, challenge)

    if (challengeError || !challengeData) {
      console.error('❌ Invalid or expired challenge:', challengeError)
      return {
        success: false,
        error: 'Invalid or expired registration link'
      }
    }

    // The page is public. A session supplied in its URL must only confirm the
    // session bound when the challenge was issued; it must never establish one.
    // In particular, a direct-login challenge has no session and cannot be
    // turned into a cross-device token handoff by opening a crafted page URL.
    if (!challengeData.session_id || challengeData.session_id !== sessionId) {
      console.error('❌ Registration challenge session binding rejected')
      return { success: false, error: 'Invalid registration session' }
    }

    // Get userId from the challenge data - it was stored when the challenge was created
    const userId = challengeData.user_id
    if (!userId) {
      console.error('❌ Challenge does not have user_id')
      return {
        success: false,
        error: 'Invalid registration challenge'
      }
    }

    console.log('✅ Found userId from challenge:', maskUserId(userId))

    console.log('✅ Mobile registration page ready for user:', maskUserId(userId))

    return {
      success: true,
      userId,
      email,
      challenge,
      sessionId,
      rpId,
      rpName
    }
  },
  'Failed to generate mobile registration page',
  '📱'
)

/**
 * Mobile Authentication Service
 * Handles business logic for mobile authentication HTML page generation
 */
export const generateMobileAuthenticationPage = withErrorHandler(
  async (
    supabase: SupabaseClient,
    challenge: string,
    email: string,
    sessionId: string,
    credentialId: string,
    rpId: string
  ) => {
    console.log('📱 Generating mobile authentication page for:', maskEmail(email))

    // Verify challenge is valid
    const { data: challengeData, error: challengeError } = await supabase
      .from('passkey_challenges')
      .select('*')
      .eq('challenge', challenge)
      .eq('type', 'authentication')
      .gt('expires_at', new Date().toISOString())
      .single()

    if (challengeError || !challengeData) {
      console.error('❌ Invalid or expired challenge:', challengeError)
      return {
        success: false,
        error: 'Invalid or expired authentication challenge'
      }
    }

    if (!challengeData.session_id || challengeData.session_id !== sessionId) {
      console.error('❌ Authentication challenge session binding rejected')
      return { success: false, error: 'Invalid authentication session' }
    }

    // Get userId from the challenge data - it was stored when the challenge was created
    const userId = challengeData.user_id
    if (!userId) {
      console.error('❌ Challenge does not have user_id')
      return {
        success: false,
        error: 'Invalid authentication challenge'
      }
    }

    console.log('✅ Found userId from challenge:', maskUserId(userId))

    // Get user's passkey credential
    const { data: passkey, error: passkeyError } = await supabase
      .from('user_passkeys')
      .select('*')
      // Canonicalised, like findPasskeyByCredentialId: credential ids are stored
      // as unpadded base64url, so an exact match on a padded or standard-base64
      // parameter would miss the row
      .eq('credential_id', normalizeBase64Url(credentialId))
      .eq('user_id', userId)
      .eq('active', true)
      .single()

    if (passkeyError || !passkey) {
      console.error('❌ Credential not found:', passkeyError)
      return {
        success: false,
        error: 'Authentication credential not found'
      }
    }

    console.log('✅ Mobile authentication page ready for user:', maskUserId(userId))

    return {
      success: true,
      email,
      challenge,
      sessionId,
      rpId,
      credentialId,
      credentialDisplayName: passkey.display_name,
      credentialCreatedAt: passkey.created_at
    }
  },
  'Failed to generate mobile authentication page',
  '📱'
)
