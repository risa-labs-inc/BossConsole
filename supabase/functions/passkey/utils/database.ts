import { authFailureDetails } from "./logging.ts"
import type { SupabaseClient } from "@supabase/supabase-js"
import { ChallengeType } from "../types/challenge.ts"
import { normalizeBase64Url } from "./base64.ts"
import { COSE_ALG_ES256 } from "./webauthn.ts"

/**
 * Normalises a PostgREST result that may be a single row or an array of rows.
 */
function rowsOf(data: unknown): unknown[] {
  if (Array.isArray(data)) return data
  return data ? [data] : []
}

export interface PasskeyRecord {
  id: string
  user_id: string
  credential_id: string
  public_key: string
  display_name: string
  transports: string[]
  created_at: number
  last_used_at?: number
  active: boolean
  /** COSE algorithm of public_key (-7 ES256, -257 RS256) */
  public_key_alg?: number
  /** Last signature counter seen from this authenticator (0 = counter unsupported) */
  sign_count?: number
  /** RP ID this credential was registered for */
  rp_id?: string | null
}

export async function verifyChallenge(
  supabase: SupabaseClient,
  challenge: string,
  type: ChallengeType
) {
  console.log('🔍 verifyChallenge called with:', {
    type
  })

  try {
    const { data: challengeData, error } = await supabase
      .from('passkey_challenges')
      .select('*')
      .eq('challenge', challenge)
      .eq('type', type)
      .single()

    console.log('🔍 verifyChallenge result:', { found: !!challengeData, error: authFailureDetails(error) })

    if (error || !challengeData) {
      console.error('Challenge verification failed:', authFailureDetails(error))
      return { success: false, error: 'Invalid or expired challenge' }
    }

    const expiresAt = new Date(challengeData.expires_at)
    if (expiresAt < new Date()) {
      return { success: false, error: 'Challenge expired' }
    }

    return { success: true, challengeData }
  } catch (error) {
    console.error('Challenge verification error:', authFailureDetails(error))
    return { success: false, error: 'Challenge verification failed' }
  }
}

/**
 * Deletes a challenge row by id and reports whether *this* caller is the one
 * that removed it.
 *
 * `DELETE ... RETURNING` is atomic per row, so of two concurrent ceremonies
 * carrying the same challenge exactly one sees a row come back. Treating "no
 * row deleted" as a rejection is what makes single-use deterministic instead of
 * a race that both callers can win.
 */
export async function consumeChallengeRow(
  supabase: SupabaseClient,
  challengeRowId: string
): Promise<{ consumed: boolean; error?: string }> {
  const { data, error } = await supabase
    .from('passkey_challenges')
    .delete()
    .eq('id', challengeRowId)
    .select('id')

  if (error) {
    console.error('❌ Failed to consume challenge:', authFailureDetails(error))
    return { consumed: false, error: error.message }
  }

  if (rowsOf(data).length === 0) {
    console.error('❌ Challenge was already consumed by another request:', challengeRowId)
    return { consumed: false, error: 'Challenge already used' }
  }

  return { consumed: true }
}

export interface CompletedAuthenticationRecord {
  challenge: string
  sessionId: string
  userId: string
  email: string | null
  accessToken: string | null
  refreshToken: string | null
  /** Epoch milliseconds, per the column comment */
  expiresAt: number | null
}

/**
 * Records a completed cross-device ceremony as a single write.
 *
 * Upsert rather than insert: `session_id` is client-supplied, and two rows for
 * one session both wedge the `/auth/status` lookup and split a token write
 * across them. `ON CONFLICT (session_id)` needs the unique index from migration
 * 20260726000000 — Postgres raises 42P10 without it — so if the function is
 * deployed ahead of that migration this falls back to a plain insert rather than
 * failing every cross-device authentication. The fallback is a deployment-order
 * safety net, not a supported state: without the index, concurrent completions
 * for one session can still produce duplicates.
 */
export async function storeCompletedAuthentication(
  supabase: SupabaseClient,
  record: CompletedAuthenticationRecord
): Promise<{ success: boolean; error?: string }> {
  const row = {
    challenge: record.challenge,  // Required NOT NULL field
    session_id: record.sessionId,
    user_id: record.userId,
    email: record.email,
    access_token: record.accessToken,
    refresh_token: record.refreshToken,
    expires_at: record.expiresAt,
    expires_at_timestamp: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
    created_at: new Date().toISOString()
  }

  const { error } = await supabase
    .from('completed_authentications')
    .upsert(row, { onConflict: 'session_id' })
    .select()

  if (!error) {
    return { success: true }
  }

  if (isMissingConflictTargetError(error)) {
    console.error(
      '⚠️ completed_authentications has no unique index on session_id — apply migration ' +
      '20260726000000_completed_auth_session_unique.sql. Falling back to a plain insert; ' +
      'duplicate rows for one session are possible until it is applied.',
      authFailureDetails(error)
    )

    const retry = await supabase
      .from('completed_authentications')
      .insert(row)
      .select()

    if (retry.error) {
      return { success: false, error: retry.error.message || retry.error.code }
    }

    return { success: true }
  }

  return { success: false, error: error.message || error.code }
}

/**
 * True when Postgres rejected an ON CONFLICT clause because no matching unique
 * index exists (42P10).
 */
function isMissingConflictTargetError(error: { code?: string; message?: string }): boolean {
  if (error.code === '42P10') return true
  const message = error.message?.toLowerCase() ?? ''
  return message.includes('on conflict') && message.includes('constraint')
}

/**
 * Claims the session parked on a `completed_authentications` row, clearing the
 * token columns so they can only be handed over once.
 *
 * Compare-and-set on `access_token IS NOT NULL`: of two concurrent polls only
 * the one that actually cleared the columns may serve the pair, so a stored
 * refresh token is never returned twice from data at rest. The row itself stays
 * (the ceremony still completed) — only the credentials are removed.
 */
export async function claimStoredSession(
  supabase: SupabaseClient,
  completedAuthId: string
): Promise<{ claimed: boolean; error?: string }> {
  const { data, error } = await supabase
    .from('completed_authentications')
    .update({ access_token: null, refresh_token: null, expires_at: null })
    .eq('id', completedAuthId)
    .not('access_token', 'is', null)
    .select('id')

  if (error) {
    console.error('❌ Failed to claim the stored session:', authFailureDetails(error))
    return { claimed: false, error: error.message }
  }

  if (rowsOf(data).length === 0) {
    console.log('ℹ️ Stored session was already claimed by another poll')
    return { claimed: false, error: 'Session already claimed' }
  }

  return { claimed: true }
}

export async function verifyAndConsumeChallenge(
  supabase: SupabaseClient,
  challenge: string,
  type: ChallengeType
) {
  console.log('🔥 verifyAndConsumeChallenge called with:', {
    type
  })

  try {
    const { data, error } = await supabase
      .from('passkey_challenges')
      .select('*')
      .eq('challenge', challenge)
      .eq('type', type)
      .gt('expires_at', new Date().toISOString())
      .single()

    if (error || !data) {
      console.error('Challenge not found or expired:', authFailureDetails(error))
      return { success: false, error: 'Invalid or expired challenge' }
    }

    // Consume it, and only proceed if this request is the one that consumed it
    const consumeResult = await consumeChallengeRow(supabase, data.id)
    if (!consumeResult.consumed) {
      return { success: false, error: consumeResult.error || 'Invalid or expired challenge' }
    }

    console.log('Challenge verified and consumed successfully')
    return { success: true, challenge: data }
  } catch (error) {
    console.error('Exception verifying challenge:', authFailureDetails(error))
    return { success: false, error: (error as Error).message }
  }
}

export async function storePasskeyInDB(
  supabase: SupabaseClient,
  passkey: Omit<PasskeyRecord, 'id' | 'created_at' | 'active'>
) {
  console.log('Storing verified passkey')

  try {
    const insertData = {
      ...passkey,
      created_at: Date.now(),
      active: true
    }

    const { data, error } = await supabase
      .from('user_passkeys')
      .insert(insertData)
      .select()

    if (error) {
      // The verification columns (public_key_alg, sign_count, rp_id) arrive with
      // migration 20260725000000. If the function is deployed ahead of it,
      // registration would otherwise break outright — degrade instead, loudly.
      if (isUnknownColumnError(error)) {
        // ...but only for ES256. An RS256 key is stored as SPKI DER, and without
        // public_key_alg every future assertion would read it as a raw EC point
        // and fail — a credential that registers and can never authenticate.
        // A retryable error is strictly better than a silently dead credential.
        const alg = passkey.public_key_alg ?? COSE_ALG_ES256
        if (alg !== COSE_ALG_ES256) {
          console.error(
            `❌ Cannot store an alg ${alg} credential without the passkey verification columns — ` +
            'apply migration 20260725000000_passkey_verification_columns.sql',
            authFailureDetails(error)
          )
          return {
            success: false,
            error: 'Passkey storage is not ready for this credential type - please try again later'
          }
        }

        console.error(
          '⚠️ user_passkeys is missing the passkey verification columns — apply migration ' +
          '20260725000000_passkey_verification_columns.sql. Storing the credential without them; ' +
          'signature-counter and rpId checks will be unavailable for it.',
          authFailureDetails(error)
        )

        const { public_key_alg: _alg, sign_count: _count, rp_id: _rpId, ...legacyData } = insertData
        const retry = await supabase
          .from('user_passkeys')
          .insert(legacyData)
          .select()

        if (retry.error) {
          console.error('Database error storing passkey:', authFailureDetails(retry.error))
          return { success: false, error: retry.error.message }
        }

        return { success: true, data: retry.data }
      }

      console.error('Database error storing passkey:', authFailureDetails(error))
      return { success: false, error: error.message }
    }

    console.log('Passkey stored successfully')
    return { success: true, data }
  } catch (error) {
    console.error('Exception storing passkey:', authFailureDetails(error))
    return { success: false, error: (error as Error).message }
  }
}

/**
 * Logs a failed passkey-use write, distinguishing the one expected cause.
 *
 * A missing column is a deployment-ordering symptom that resolves once migration
 * 20260725000000 is applied. Anything else means clone detection is silently
 * off on a correctly migrated database, which deserves a louder line.
 */
function logPasskeyUseFailure(error: { code?: string; message?: string }): void {
  if (isUnknownColumnError(error)) {
    console.error(
      '⚠️ Cannot record the signature counter: user_passkeys is missing the verification ' +
      'columns. Apply migration 20260725000000_passkey_verification_columns.sql — until then ' +
      'cloned-authenticator detection is unavailable.',
      authFailureDetails(error)
    )
    return
  }

  console.error(
    '❌ Failed to record passkey use on a migrated schema — cloned-authenticator detection ' +
    'is not engaging for this credential:',
    authFailureDetails(error)
  )
}

/**
 * True when PostgREST/Postgres rejected the statement because a column does not
 * exist (schema cache miss PGRST204, or undefined_column 42703).
 */
function isUnknownColumnError(error: { code?: string; message?: string }): boolean {
  if (error.code === 'PGRST204' || error.code === '42703') return true
  const message = error.message?.toLowerCase() ?? ''
  return message.includes('column') && (message.includes('does not exist') || message.includes('not find'))
}

/**
 * Records a successful assertion against a passkey.
 *
 * `signCount` is only written when the authenticator maintains a counter — see
 * `evaluateSignCounter` in utils/webauthn.ts, which returns null for
 * authenticators that always report 0.
 *
 * When there *is* a counter the write is a compare-and-set: the row only
 * advances if the stored counter is still below the new value. Two assertions
 * carrying the same counter, submitted concurrently, would both verify — the
 * CAS is what makes the second one observable (`advanced: false`) instead of
 * silently overwriting the first.
 */
export async function recordPasskeyUse(
  supabase: SupabaseClient,
  passkeyId: string,
  signCount: number | null
): Promise<{ success: boolean; advanced: boolean; error?: string }> {
  if (signCount === null) {
    // Counter-less authenticator: nothing to compare, just record the use
    const { error } = await supabase
      .from('user_passkeys')
      .update({ last_used_at: Date.now() })
      .eq('id', passkeyId)

    if (error) {
      // Non-fatal: the assertion itself was verified.
      logPasskeyUseFailure(error)
      return { success: false, advanced: true, error: error.message }
    }

    return { success: true, advanced: true }
  }

  const { data, error } = await supabase
    .from('user_passkeys')
    .update({ last_used_at: Date.now(), sign_count: signCount })
    .eq('id', passkeyId)
    // The column has DEFAULT 0, so a migrated table holds 0 rather than NULL
    // here; the is.null arm covers a row written while the column did not exist
    // (the degraded path above), since no comparison operator matches NULL.
    .or(`sign_count.is.null,sign_count.lt.${signCount}`)
    .select('id')

  if (error) {
    // Non-fatal: losing the counter update only costs clone detection on the
    // *next* assertion, so log and let the ceremony stand.
    logPasskeyUseFailure(error)
    return { success: false, advanced: true, error: error.message }
  }

  if (rowsOf(data).length === 0) {
    console.error('❌ Signature counter was already advanced past', signCount, 'for passkey', passkeyId)
    return { success: false, advanced: false, error: 'Signature counter did not advance' }
  }

  return { success: true, advanced: true }
}

export async function getUserPasskeys(supabase: SupabaseClient, userId: string) {
  console.log('Getting passkeys for user:', userId)

  try {
    const { data, error } = await supabase
      .from('user_passkeys')
      .select('*')
      .eq('user_id', userId)
      .eq('active', true)

    if (error) {
      console.error('Database error getting passkeys:', authFailureDetails(error))
      return { success: false, error: error.message }
    }

    console.log(`Found ${data?.length || 0} existing passkeys`)
    return { success: true, passkeys: data }
  } catch (error) {
    console.error('Exception getting passkeys:', authFailureDetails(error))
    return { success: false, error: (error as Error).message }
  }
}

export async function findPasskeyByCredentialId(
  supabase: SupabaseClient,
  credentialId: string
) {
  console.log('Finding passkey by credential ID')

  // credential_id is stored canonicalised (unpadded base64url), so a client that
  // emits standard base64 or padding still resolves to the same row. Everything
  // else in the ceremony is alphabet-tolerant; an exact-match lookup here would
  // otherwise fail as a misleading "Passkey not found".
  const canonicalId = normalizeBase64Url(credentialId)

  const lookupIds = canonicalId && canonicalId !== credentialId
    ? [canonicalId, credentialId] // fall back to the raw form for rows written before normalisation
    : [credentialId]

  try {
    for (const lookupId of lookupIds) {
      const { data, error } = await supabase
        .from('user_passkeys')
        .select('*')
        .eq('credential_id', lookupId)
        .eq('active', true)
        .single()

      if (!error && data) {
        console.log('Found passkey for user:', data.user_id)
        return { success: true, passkey: data }
      }

      if (lookupId === lookupIds[lookupIds.length - 1]) {
        console.error('Passkey not found:', authFailureDetails(error))
      }
    }

    return { success: false, error: 'Passkey not found' }
  } catch (error) {
    console.error('Exception finding passkey:', authFailureDetails(error))
    return { success: false, error: (error as Error).message }
  }
}

/**
 * Find user by email - uses RPC function with SECURITY DEFINER for auth.users access
 * This is more scalable than using auth.admin.listUsers()
 */
export async function findUserByEmail(
  supabase: SupabaseClient,
  email: string
) {
  console.log('Finding user by email:', email)

  try {
    const { data, error } = await supabase
      .rpc('find_user_by_email', { p_email: email })

    if (error) {
      console.error('Error finding user:', authFailureDetails(error))
      return { success: false, error: error.message }
    }

    // RPC returns array, get first result
    const user = data && data.length > 0 ? data[0] : null

    if (!user) {
      console.log('User not found with email:', email)
      return { success: false, error: 'User not found' }
    }

    console.log('Found user:', user.id)
    return { success: true, user }
  } catch (error) {
    console.error('Exception finding user:', authFailureDetails(error))
    return { success: false, error: (error as Error).message }
  }
}

/**
 * Get user with email from public.users table
 *
 * This helper function consolidates the common pattern of fetching user email
 * from the public.users table. Used in authentication flows to retrieve user
 * information for JWT token generation.
 *
 * @param supabase - Supabase client instance
 * @param userId - User ID to look up
 * @returns Object with success status and user data (id + email) or error
 */
export async function getUserWithEmail(
  supabase: SupabaseClient,
  userId: string
) {
  console.log('Getting user with email for user ID:', userId)

  try {
    const { data: userData, error: userError } = await supabase
      .from('users')
      .select('email')
      .eq('id', userId)
      .single()

    if (userError || !userData?.email) {
      console.error('Failed to fetch user email:', authFailureDetails(userError))
      return {
        success: false,
        error: userError?.message || 'User email not found'
      }
    }

    console.log('Found user email:', userData.email)
    return {
      success: true,
      user: {
        id: userId,
        email: userData.email
      }
    }
  } catch (error) {
    console.error('Exception getting user with email:', authFailureDetails(error))
    return {
      success: false,
      error: (error as Error).message
    }
  }
}
