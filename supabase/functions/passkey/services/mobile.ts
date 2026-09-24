import type { SupabaseClient } from "@supabase/supabase-js"
import { withErrorHandler } from "../utils/error-handler.ts"
import { normalizeBase64Url } from "../utils/base64.ts"

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
    rpId: string,
    rpName: string
  ) => {
    console.log('📱 Generating mobile registration page for:', email)

    // Verify challenge exists and is valid
    const { data: challengeData, error: challengeError } = await supabase
      .from('passkey_challenges')
      .select('*')
      .eq('challenge', challenge)
      .eq('type', 'registration')
      .gt('expires_at', new Date().toISOString())
      .single()

    if (challengeError || !challengeData) {
      console.error('❌ Invalid or expired challenge:', challengeError)
      return {
        success: false,
        error: 'Invalid or expired registration link'
      }
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

    console.log('✅ Found userId from challenge:', userId)

    // Update challenge with session info
    await supabase
      .from('passkey_challenges')
      .update({
        session_id: sessionId,
        status: 'in_progress'
      })
      .eq('challenge', challenge)

    console.log('✅ Mobile registration page ready for user:', userId)

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
    console.log('📱 Generating mobile authentication page for:', email)

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

    // Get userId from the challenge data - it was stored when the challenge was created
    const userId = challengeData.user_id
    if (!userId) {
      console.error('❌ Challenge does not have user_id')
      return {
        success: false,
        error: 'Invalid authentication challenge'
      }
    }

    console.log('✅ Found userId from challenge:', userId)

    // Resolve the email from the challenge's user, not from the URL parameter.
    // The URL parameter is operator-supplied and can be intercepted/rewritten
    // (BossConsole#924 covers session-id rebinding; this is the matching
    // email-display rebind on the same magic-link surface - the page would
    // otherwise show a victim the wrong account). The challenge row's user_id
    // is the source of truth: it was set by /auth/challenge after Supabase
    // resolved the email server-side.
    //
    // Failing closed here - no `?? email` fallback, no challenge update on
    // lookup failure - closes the regression where an attacker-controlled
    // `email` URL parameter would be shown to a victim on any of: lookup
    // error, no row, or a blank stored email. The generic error returned
    // below is the same one a stale or missing challenge produces, so the
    // caller cannot probe whether a user id is real.
    const { data: userRow, error: userError } = await supabase
      .from('users')
      .select('email')
      .eq('id', userId)
      .single()

    if (userError || !userRow || !userRow.email) {
      console.error('❌ Cannot resolve user email for challenge:', userError)
      return {
        success: false,
        error: 'Invalid authentication challenge'
      }
    }

    const resolvedEmail = userRow.email

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

    // Update challenge with session info
    await supabase
      .from('passkey_challenges')
      .update({
        session_id: sessionId,
        status: 'in_progress'
      })
      .eq('challenge', challenge)

    console.log('✅ Mobile authentication page ready for user:', userId)

    return {
      success: true,
      email: resolvedEmail,
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
