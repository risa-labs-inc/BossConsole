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

    // The row, not the URL, decides which session this ceremony hands its
    // result to (#924). The sessionId query parameter may only *agree* with
    // the binding already stored on the challenge row — it can never move it.
    // Until now the page wrote its sessionId into the row on every load, so a
    // second load of the same page URL with a different sessionId silently
    // rebound the ceremony and the completed handoff (the minted session)
    // went to whoever last won the write.
    const boundSessionId = (challengeData.session_id ?? null) as string | null

    if (boundSessionId !== null && boundSessionId !== sessionId) {
      console.error('❌ Refusing to rebind an already-bound challenge to another session:', {
        bound: boundSessionId,
        requested: sessionId
      })
      return {
        success: false,
        error: 'Session mismatch - this passkey link is already bound to another session'
      }
    }

    if (boundSessionId === null) {
      // First binding, compare-and-set: the update only lands while the row
      // is still unbound, so of two concurrent page loads with different
      // sessionIds exactly one can win. A load that updated zero rows lost
      // the race and must not render the page as if it owned the ceremony —
      // and must not fall back to an unconditional write, which would
      // reintroduce the rebinding this check exists to prevent.
      const { data: boundRows, error: bindError } = await supabase
        .from('passkey_challenges')
        .update({
          session_id: sessionId,
          status: 'in_progress'
        })
        .eq('challenge', challenge)
        .is('session_id', null)
        .select('id')

      const boundCount = Array.isArray(boundRows) ? boundRows.length : boundRows ? 1 : 0

      if (bindError || boundCount === 0) {
        console.error('❌ Challenge session binding was claimed by another request:', bindError)
        return {
          success: false,
          error: 'Session binding conflict - this passkey link was opened from another session'
        }
      }
    } else {
      // The bound session loading the page again (refresh, re-scan). The
      // write carries no session_id at all and is scoped to the bound
      // session, so it can only refresh the status — it cannot move the
      // binding even if the row changed between the read above and here.
      const { error: statusError } = await supabase
        .from('passkey_challenges')
        .update({ status: 'in_progress' })
        .eq('challenge', challenge)
        .eq('session_id', sessionId)

      if (statusError) {
        console.error('❌ Failed to mark challenge in progress:', statusError)
        return {
          success: false,
          error: 'Failed to update challenge status'
        }
      }
    }

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

    // Same rule as the registration page (#924): the challenge row's binding
    // decides which session the completed authentication's tokens are handed
    // to, and the sessionId query parameter may only agree with it — a page
    // load carrying a different sessionId is a rebinding attempt and gets
    // nothing, not even a partial render.
    const boundSessionId = (challengeData.session_id ?? null) as string | null

    if (boundSessionId !== null && boundSessionId !== sessionId) {
      console.error('❌ Refusing to rebind an already-bound challenge to another session:', {
        bound: boundSessionId,
        requested: sessionId
      })
      return {
        success: false,
        error: 'Session mismatch - this passkey link is already bound to another session'
      }
    }

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

    if (boundSessionId === null) {
      // First binding, compare-and-set (see the registration page): the write
      // only lands while the row is unbound, and losing it is an error rather
      // than a fallback to an unconditional update.
      const { data: boundRows, error: bindError } = await supabase
        .from('passkey_challenges')
        .update({
          session_id: sessionId,
          status: 'in_progress'
        })
        .eq('challenge', challenge)
        .is('session_id', null)
        .select('id')

      const boundCount = Array.isArray(boundRows) ? boundRows.length : boundRows ? 1 : 0

      if (bindError || boundCount === 0) {
        console.error('❌ Challenge session binding was claimed by another request:', bindError)
        return {
          success: false,
          error: 'Session binding conflict - this passkey link was opened from another session'
        }
      }
    } else {
      // The bound session reloading the page: refresh the status only, via a
      // write that carries no session_id, so the binding cannot move.
      const { error: statusError } = await supabase
        .from('passkey_challenges')
        .update({ status: 'in_progress' })
        .eq('challenge', challenge)
        .eq('session_id', sessionId)

      if (statusError) {
        console.error('❌ Failed to mark challenge in progress:', statusError)
        return {
          success: false,
          error: 'Failed to update challenge status'
        }
      }
    }

    console.log('✅ Mobile authentication page ready for user:', userId)

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
