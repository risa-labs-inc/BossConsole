import type { SupabaseClient } from "@supabase/supabase-js"
import { ChallengeType } from "../types/challenge.ts"

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
}

export async function verifyChallenge(
  supabase: SupabaseClient,
  challenge: string,
  type: ChallengeType
) {
  console.log('🔍 verifyChallenge called with:', {
    challenge: challenge.substring(0, 20) + '...',
    type
  })

  try {
    const { data: challengeData, error } = await supabase
      .from('passkey_challenges')
      .select('*')
      .eq('challenge', challenge)
      .eq('type', type)
      .single()

    console.log('🔍 verifyChallenge result:', { found: !!challengeData, error: error?.message })

    if (error || !challengeData) {
      console.error('Challenge verification failed:', error)
      return { success: false, error: 'Invalid or expired challenge' }
    }

    const expiresAt = new Date(challengeData.expires_at)
    if (expiresAt < new Date()) {
      return { success: false, error: 'Challenge expired' }
    }

    return { success: true, challengeData }
  } catch (error) {
    console.error('Challenge verification error:', error)
    return { success: false, error: 'Challenge verification failed' }
  }
}

export async function verifyAndConsumeChallenge(
  supabase: SupabaseClient,
  challenge: string,
  type: ChallengeType
) {
  console.log('🔥 verifyAndConsumeChallenge called with:', {
    challenge: challenge.substring(0, 20) + '...',
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
      console.error('Challenge not found or expired:', error)
      return { success: false, error: 'Invalid or expired challenge' }
    }

    // Delete the challenge after successful verification
    await supabase
      .from('passkey_challenges')
      .delete()
      .eq('id', data.id)

    console.log('Challenge verified and consumed successfully')
    return { success: true, challenge: data }
  } catch (error) {
    console.error('Exception verifying challenge:', error)
    return { success: false, error: (error as Error).message }
  }
}

export async function storePasskeyInDB(
  supabase: SupabaseClient,
  passkey: Omit<PasskeyRecord, 'id' | 'created_at' | 'active'>
) {
  console.log('storePasskeyInDB called with credential:', passkey.credential_id)
  console.log('Full passkey data:', JSON.stringify(passkey, null, 2))

  try {
    const insertData = {
      ...passkey,
      created_at: Date.now(),
      active: true
    }

    console.log('About to insert:', JSON.stringify(insertData, null, 2))

    const { data, error } = await supabase
      .from('user_passkeys')
      .insert(insertData)
      .select()

    console.log('Insert result - data:', data)
    console.log('Insert result - error:', error)

    if (error) {
      console.error('Database error storing passkey:', error)
      return { success: false, error: error.message }
    }

    console.log('Passkey stored successfully - returned data:', JSON.stringify(data, null, 2))
    return { success: true, data }
  } catch (error) {
    console.error('Exception storing passkey:', error)
    return { success: false, error: (error as Error).message }
  }
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
      console.error('Database error getting passkeys:', error)
      return { success: false, error: error.message }
    }

    console.log(`Found ${data?.length || 0} existing passkeys`)
    return { success: true, passkeys: data }
  } catch (error) {
    console.error('Exception getting passkeys:', error)
    return { success: false, error: (error as Error).message }
  }
}

export async function findPasskeyByCredentialId(
  supabase: SupabaseClient,
  credentialId: string
) {
  console.log('Finding passkey by credential ID:', credentialId)

  try {
    const { data, error } = await supabase
      .from('user_passkeys')
      .select('*')
      .eq('credential_id', credentialId)
      .eq('active', true)
      .single()

    if (error || !data) {
      console.error('Passkey not found:', error)
      return { success: false, error: 'Passkey not found' }
    }

    console.log('Found passkey for user:', data.user_id)
    return { success: true, passkey: data }
  } catch (error) {
    console.error('Exception finding passkey:', error)
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
      console.error('Error finding user:', error)
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
    console.error('Exception finding user:', error)
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
      console.error('Failed to fetch user email:', userError)
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
    console.error('Exception getting user with email:', error)
    return {
      success: false,
      error: (error as Error).message
    }
  }
}
