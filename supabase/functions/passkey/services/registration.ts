import type { SupabaseClient } from "@supabase/supabase-js"
import { generateChallenge, storeChallenge } from "../utils/challenge.ts"
import { verifyAndConsumeChallenge, storePasskeyInDB } from "../utils/database.ts"
import { extractPublicKeyFromAttestation } from "../utils/crypto.ts"
import { ChallengeType } from "../types/challenge.ts"
import { withErrorHandler } from "../utils/error-handler.ts"
import { getRpId, getRpName } from "../utils/config.ts"

export const ALLOWED_ORIGINS = [
  'boss://authenticate',
  'http://localhost:3000',
  'http://localhost:54321',  // Supabase local functions
  'https://risaboss.com',
  'https://api.risaboss.com'
]

export interface RegistrationCredential {
  id: string
  rawId: string
  type: string
  response: {
    clientDataJSON: string
    attestationObject: string
  }
}

/**
 * Generates a registration challenge for a new passkey
 *
 * NOTE: This endpoint returns the challenge but NOT the rpId.
 * The rpId must be provided by the client when calling the mobile registration page.
 * This is because the server's SUPABASE_URL points to internal kong gateway,
 * not the external domain where the browser accesses the page.
 */
export const generateRegistrationChallenge = withErrorHandler(
  async (supabase: SupabaseClient, userId: string, sessionId?: string) => {
    console.log('🔑 Generating registration challenge for user:', userId, 'sessionId:', sessionId)

    // Generate and store challenge
    const challenge = generateChallenge()
    const storeResult = await storeChallenge(supabase, challenge, ChallengeType.Registration, {
      userId,
      sessionId
    })

    if (!storeResult.success) {
      return {
        success: false,
        error: storeResult.error || 'Failed to store challenge'
      }
    }

    // NOTE: rpId is intentionally NOT included here.
    // The client must provide the correct rpId when opening the mobile registration page,
    // because only the client knows the actual domain where the browser will access the page.

    return {
      success: true,
      challenge,
      // rpId will be provided by client when calling /register/mobile
      sessionId // Return sessionId for cross-device polling
    }
  },
  'Failed to generate registration challenge',
  '🔑'
)

/**
 * Completes a registration ceremony by storing the new passkey
 */
export const completeRegistration = withErrorHandler(
  async (
    supabase: SupabaseClient,
    userId: string,
    credential: RegistrationCredential,
    challenge: string,
    displayName?: string
  ) => {
    console.log('🔐 Starting registration completion for user:', userId)

    const { clientDataJSON, attestationObject } = credential.response

    // Parse and validate client data
    const clientData = JSON.parse(atob(clientDataJSON))
    console.log('Client data:', clientData)

    // Verify challenge type
    if (clientData.type !== 'webauthn.create') {
      return {
        success: false,
        error: 'Invalid ceremony type - expected webauthn.create'
      }
    }

    // Verify and consume challenge
    const challengeResult = await verifyAndConsumeChallenge(
      supabase,
      challenge,
      ChallengeType.Registration
    )

    if (!challengeResult.success) {
      return {
        success: false,
        error: challengeResult.error || 'Invalid challenge'
      }
    }

    // Extract public key from attestation
    const publicKey = extractPublicKeyFromAttestation(attestationObject)

    // Store passkey
    const passkeyData = {
      user_id: userId,
      credential_id: credential.id,
      public_key: publicKey,
      display_name: displayName || 'My Passkey',
      transports: ['internal']
    }

    const storeResult = await storePasskeyInDB(supabase, passkeyData)

    if (!storeResult.success) {
      return {
        success: false,
        error: storeResult.error || 'Failed to store passkey'
      }
    }

    console.log('✅ Registration successful for user:', userId)

    return {
      success: true,
      passkeyId: storeResult.data?.[0]?.id
    }
  },
  'Failed to complete registration',
  '🔐'
)
