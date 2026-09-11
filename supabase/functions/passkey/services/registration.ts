import { admitChallenge } from "../utils/admission.ts"
import type { SupabaseClient } from "@supabase/supabase-js"
import { generateChallenge, storeChallenge } from "../utils/challenge.ts"
import { verifyAndConsumeChallenge, storePasskeyInDB } from "../utils/database.ts"
import { extractCredentialFromAttestation } from "../utils/crypto.ts"
import { ChallengeType } from "../types/challenge.ts"
import { withErrorHandler } from "../utils/error-handler.ts"
import { ALLOWED_ORIGINS, getAllowedOrigins, getAllowedRpIds, getRpId, rpIdMatchesOrigin } from "../utils/config.ts"
import { encodedValuesMatch, normalizeBase64Url } from "../utils/base64.ts"
import {
  challengeMatches,
  matchRpIdHash,
  parseClientDataJSON,
  SUPPORTED_COSE_ALGS
} from "../utils/webauthn.ts"

// Re-exported for the routes and for callers that imported it from here before
// it moved to utils/config.ts (single source of truth).
export { ALLOWED_ORIGINS }

/**
 * Everything about a completion request that is *not* the ceremony itself.
 *
 * Neither field selects the account: the enrolling user comes from the challenge
 * row. They are cross-checks, so a request that disagrees with the challenge is
 * rejected rather than quietly enrolling somewhere else.
 */
export interface CompleteRegistrationOptions {
  /** userId from the request body, if the client sent one */
  claimedUserId?: string
  /** Verified caller identity, when the transport could carry a session */
  authenticatedUserId?: string
  displayName?: string
}

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
    const admission = await admitChallenge(supabase, ChallengeType.Registration)
    if (!admission.success) return admission

    console.log('🔑 Generating registration challenge for user:', userId)

    // Generate and store challenge
    const challenge = generateChallenge()
    const storeResult = await storeChallenge(supabase, challenge, ChallengeType.Registration, {
      userId,
      sessionId
    })

    if (!storeResult.success) return storeResult

    // rpId comes from the server, so registration and authentication cannot pin
    // different relying parties.
    //
    // This used to be omitted deliberately, on the reasoning that only the client
    // knows the domain the browser will load. The cost was two sources of truth:
    // the client fell back to its own SUPABASE_FUNCTION_URL host for
    // /register/mobile?rpId=, while /auth/challenge returned the server's
    // getRpId(). If those disagree and both are in the allow-list, the credential
    // is pinned to one and every later assertion advertises the other — a
    // permanently unusable passkey whose only fix is re-enrolment, and nothing
    // reveals it until the next login.

    return {
      success: true,
      challenge,
      // The client passes this straight to /register/mobile?rpId=
      rpId: getRpId(),
      // Algorithms the server can actually verify at /register/complete
      pubKeyCredParams: SUPPORTED_COSE_ALGS.map(alg => ({ type: 'public-key', alg })),
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
    credential: RegistrationCredential,
    challenge: string,
    options: CompleteRegistrationOptions = {}
  ) => {
    const { claimedUserId, authenticatedUserId, displayName } = options
    console.log('🔐 Starting registration completion')

    const { clientDataJSON, attestationObject } = credential.response

    // Parse client data. Decoding is base64url-tolerant: the reference web
    // client sends base64url, so atob() failed for any payload that happened to
    // encode a '+' or '/'.
    const { data: clientData } = parseClientDataJSON(clientDataJSON)

    // Verify ceremony type
    if (clientData.type !== 'webauthn.create') {
      return {
        success: false,
        error: 'Invalid ceremony type - expected webauthn.create'
      }
    }

    // Verify origin here too, not only in the route: the service is the layer
    // that decides whether an attestation is acceptable.
    if (!getAllowedOrigins().includes(clientData.origin)) {
      console.error('❌ Registration origin is not allowed')
      return {
        success: false,
        error: 'Invalid origin'
      }
    }

    // Verify the challenge inside the signed client data is the one this
    // ceremony was issued. Registration uses "none" attestation, so this is the
    // only thing tying the credential to our challenge.
    //
    // Who the credential is enrolled *for* is a separate question, answered
    // below by the challenge row rather than by anything in this request: a
    // registration challenge can only be minted by a caller holding that user's
    // session (see routes/register.ts).
    if (!challengeMatches(clientData.challenge, challenge)) {
      console.error('❌ Registration challenge mismatch between clientDataJSON and request body')
      return {
        success: false,
        error: 'Challenge mismatch - clientDataJSON challenge does not match the issued challenge'
      }
    }

    // Verify and consume challenge (keyed on the value the authenticator signed)
    const challengeResult = await verifyAndConsumeChallenge(
      supabase,
      normalizeBase64Url(clientData.challenge),
      ChallengeType.Registration
    )

    if (!challengeResult.success) {
      return {
        success: false,
        error: challengeResult.error || 'Invalid challenge'
      }
    }

    // The enrolling user is whoever the challenge was issued to. That row can
    // only be created by a caller holding that user's session, so this is the
    // one value in the ceremony that a caller cannot choose for themselves —
    // which is exactly why the credential is bound to it rather than to
    // anything in this request.
    const enrollingUserId = challengeResult.challenge?.user_id
    if (!enrollingUserId) {
      // Fails closed. A registration challenge with no user_id cannot be
      // produced by /register/challenge; a row like that is either corrupt or
      // came from somewhere it should not have.
      console.error('❌ Registration challenge has no user_id - refusing to enrol')
      return {
        success: false,
        error: 'Challenge is not bound to a user'
      }
    }

    // A body userId is accepted for compatibility but may only agree with the
    // challenge; it never selects the account.
    if (claimedUserId && claimedUserId !== enrollingUserId) {
      console.error('❌ Registration challenge was issued for a different user')
      return {
        success: false,
        error: 'Challenge does not belong to this user'
      }
    }

    // If the transport could present a session, it has to be the same user.
    // (The cross-device page runs in a phone browser and has no session of
    // ours, so this is checked when available rather than required.)
    if (authenticatedUserId && authenticatedUserId !== enrollingUserId) {
      console.error('❌ Authenticated caller does not own this registration challenge')
      return {
        success: false,
        error: 'Challenge does not belong to this user'
      }
    }

    // Extract public key, algorithm, initial counter, flags and rpIdHash
    const attested = await extractCredentialFromAttestation(attestationObject)

    // User Presence is mandatory (WebAuthn L2 §7.1 step 16). A browser will not
    // produce an attestation without it; a non-browser client can.
    if (!attested.userPresent) {
      console.error('❌ Attestation has no User Present flag')
      return {
        success: false,
        error: 'User presence flag not set'
      }
    }

    // Verify the authenticator signed for one of our relying party IDs
    const matchedRpId = await matchRpIdHash(attested.rpIdHash, getAllowedRpIds())
    if (!matchedRpId) {
      console.error('❌ Registration rpIdHash does not match any allowed relying party')
      return {
        success: false,
        error: 'Relying party mismatch - credential was created for a different rpId'
      }
    }

    // ...and the RP ID has to correspond to where the ceremony was performed,
    // otherwise the two allow-lists can be satisfied by an unrelated pairing.
    if (!rpIdMatchesOrigin(matchedRpId, clientData.origin)) {
      console.error('❌ Registration rpId is not a registrable suffix of its origin')
      return {
        success: false,
        error: 'Relying party mismatch - rpId does not correspond to the ceremony origin'
      }
    }

    // The credential id we store is the lookup key at assertion time, so it has
    // to be the id the authenticator actually attested — not an arbitrary one
    // supplied next to the attestation.
    if (attested.credentialId && !encodedValuesMatch(credential.id, attested.credentialId)) {
      console.error('❌ Registration credential id does not match the attested credential')
      return {
        success: false,
        error: 'Credential id does not match the attestation'
      }
    }

    // Store passkey. The credential id is canonicalised (unpadded base64url) so
    // that a client emitting standard base64 at login still resolves to this row
    // and cannot register the same physical credential twice under two
    // encodings — `findPasskeyByCredentialId` normalises the lookup to match.
    const passkeyData = {
      user_id: enrollingUserId,
      credential_id: normalizeBase64Url(credential.id),
      public_key: attested.publicKey,
      public_key_alg: attested.alg,
      sign_count: attested.signCount,
      rp_id: matchedRpId,
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

    console.log('✅ Registration successful for user:', enrollingUserId)

    return {
      success: true,
      passkeyId: storeResult.data?.[0]?.id
    }
  },
  'Failed to complete registration',
  '🔐'
)
