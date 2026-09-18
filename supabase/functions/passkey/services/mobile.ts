import type { SupabaseClient } from "@supabase/supabase-js"
import { withErrorHandler } from "../utils/error-handler.ts"
import { normalizeBase64Url } from "../utils/base64.ts"

/**
 * Outcome of binding a mobile page load to the challenge row's session.
 */
type SessionBindResult = { success: true } | { success: false; error: string }

/**
 * Binds the challenge row to the session that opened the mobile page — or
 * refuses the page when the row is already bound to a different session.
 *
 * The mobile pages are public and unauthenticated, so the `sessionId` query
 * parameter is attacker-controlled input, while the completed ceremony hands
 * its tokens to whichever session id sits in the challenge row (the desktop
 * claims them through /auth/status). The binding is therefore only ever
 * *written* through a compare-and-set on the still-null column: the first
 * page load of a fresh ceremony can establish the binding, but no page load
 * can re-point an already-bound ceremony at another session. The victim's
 * token handoff always stays on the session that started the ceremony
 * (#924).
 *
 * Fails closed on every ambiguity: a session mismatch, a bind write that
 * errors, or a compare-and-set race lost to a *different* session all refuse
 * the page instead of serving it.
 */
async function bindChallengeToSession(
  supabase: SupabaseClient,
  challenge: string,
  boundSessionId: string | null | undefined,
  sessionId: string
): Promise<SessionBindResult> {
  const existingSessionId = boundSessionId ?? null

  if (existingSessionId !== sessionId) {
    if (existingSessionId !== null) {
      // The ceremony is already bound to another session — usually at
      // challenge creation, by the desktop that started it. Refuse the page
      // rather than silently retarget the handoff.
      console.error('❌ Refusing to rebind challenge to a different session:', challenge)
      return { success: false, error: 'Challenge is already bound to another session' }
    }

    // Row not yet bound: claim it for this session. Keyed on the still-null
    // column, so two racing page loads cannot both win the write.
    const { data: boundRows, error: bindError } = await supabase
      .from('passkey_challenges')
      .update({ session_id: sessionId, status: 'in_progress' })
      .eq('challenge', challenge)
      .is('session_id', null)
      .select()

    if (bindError) {
      // Serving the page anyway would start a ceremony that can never hand
      // its tokens to the waiting session, so fail closed here.
      console.error('❌ Failed to bind challenge session:', bindError)
      return { success: false, error: 'Failed to bind challenge session' }
    }

    if (!boundRows || boundRows.length === 0) {
      // Lost the compare-and-set: the row was bound between the read and
      // this write. Proceed only if it was bound to this same session.
      const { data: current, error: rereadError } = await supabase
        .from('passkey_challenges')
        .select('session_id')
        .eq('challenge', challenge)
        .maybeSingle()

      if (rereadError || !current || current.session_id !== sessionId) {
        console.error('❌ Challenge was concurrently bound to another session:', challenge)
        return { success: false, error: 'Challenge is already bound to another session' }
      }
    }

    return { success: true }
  }

  // Already bound to this same session — a reload, or the desktop bound the
  // row when it created the challenge. Only ever advance the status; the
  // binding itself is never rewritten, so a reload cannot move it either.
  const { error: statusError } = await supabase
    .from('passkey_challenges')
    .update({ status: 'in_progress' })
    .eq('challenge', challenge)
    .eq('session_id', sessionId)

  if (statusError) {
    // The binding is already correct and nothing on this path reads the
    // status column, so a bookkeeping write that fails must not break a
    // working reload.
    console.warn('⚠️ Failed to advance challenge status:', statusError)
  }

  return { success: true }
}

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

    // Bind the challenge to the session that opened this page, or refuse the
    // page when the row is already bound to a different session (#924).
    const bind = await bindChallengeToSession(supabase, challenge, challengeData.session_id, sessionId)
    if (!bind.success) {
      return {
        success: false,
        error: bind.error
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

    // Bind the challenge to the session that opened this page, or refuse the
    // page when the row is already bound to a different session (#924). This
    // runs before the credential lookup so a rebinding attempt never gets to
    // probe further.
    const bind = await bindChallengeToSession(supabase, challenge, challengeData.session_id, sessionId)
    if (!bind.success) {
      return {
        success: false,
        error: bind.error
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
