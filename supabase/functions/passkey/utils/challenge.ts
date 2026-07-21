import { encodeBase64Url } from "@std/encoding/base64url"
import type { SupabaseClient } from "@supabase/supabase-js"
import { ChallengeType } from "../types/challenge.ts"

/**
 * Generates a random challenge for WebAuthn ceremonies
 */
export function generateChallenge(): string {
  const buffer = new Uint8Array(32)
  crypto.getRandomValues(buffer)
  return encodeBase64Url(buffer)
}

/**
 * Stores a challenge in the database for later verification
 */
export async function storeChallenge(
  supabase: SupabaseClient,
  challenge: string,
  type: ChallengeType,
  options?: {
    sessionId?: string
    userId?: string
  }
) {
  console.log('Storing challenge:', {
    challenge: challenge.substring(0, 20) + '...',
    type,
    sessionId: options?.sessionId,
    userId: options?.userId
  })

  try {
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000) // 5 minutes from now

    const insertData: Record<string, unknown> = {
      challenge,
      type,
      expires_at: expiresAt.toISOString(),
      created_at: new Date().toISOString()
    }

    if (options?.sessionId) {
      insertData.session_id = options.sessionId
    }

    if (options?.userId) {
      insertData.user_id = options.userId
    }

    const { data, error } = await supabase
      .from('passkey_challenges')
      .insert(insertData)
      .select()

    if (error) {
      console.error('Database error storing challenge:', error)
      return { success: false, error: error.message }
    }

    console.log('Challenge stored successfully')
    return { success: true, data }
  } catch (error) {
    console.error('Exception storing challenge:', error)
    return { success: false, error: (error as Error).message }
  }
}

/**
 * Cleans up expired challenges from the database
 */
export async function cleanupExpiredChallenges(supabase: SupabaseClient) {
  try {
    const { error } = await supabase
      .from('passkey_challenges')
      .delete()
      .lt('expires_at', new Date().toISOString())

    if (error) {
      console.error('Error cleaning up expired challenges:', error)
      return { success: false, error: error.message }
    }

    return { success: true }
  } catch (error) {
    console.error('Exception cleaning up challenges:', error)
    return { success: false, error: (error as Error).message }
  }
}
