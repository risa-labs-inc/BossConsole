import { z } from "zod"

export const SessionIdentifierSchema = z.string().min(1).max(128)
export const ChallengeSchema = z.string().min(1).max(512).regex(/^[A-Za-z0-9+/_-]+={0,2}$/)
export const CredentialIdentifierSchema = z.string().min(1).max(4096)
const EncodedResponseSchema = z.string()

// ============================================================================
// WebAuthn Credential Schemas
// ============================================================================

export const AuthenticatorResponseSchema = z.object({
  clientDataJSON: EncodedResponseSchema.max(16 * 1024),
  authenticatorData: EncodedResponseSchema.max(16 * 1024),
  signature: EncodedResponseSchema.max(8192),
  userHandle: z.string().max(128).optional()
})

export const RegistrationResponseSchema = z.object({
  clientDataJSON: EncodedResponseSchema.max(16 * 1024),
  attestationObject: EncodedResponseSchema.max(192 * 1024)
})

export const AuthenticationCredentialSchema = z.object({
  id: CredentialIdentifierSchema,
  rawId: CredentialIdentifierSchema,
  type: z.string().max(64),
  response: AuthenticatorResponseSchema
})

export const RegistrationCredentialSchema = z.object({
  id: CredentialIdentifierSchema,
  rawId: CredentialIdentifierSchema,
  type: z.string().max(64),
  response: RegistrationResponseSchema
})

// ============================================================================
// Auth Route Schemas
// ============================================================================

export const AuthChallengeRequestSchema = z.object({
  email: z.string().max(320).email("Invalid email format"),
  sessionId: SessionIdentifierSchema.optional()
})

export const AuthChallengeResponseSchema = z.object({
  success: z.boolean(),
  challenge: z.string().optional(),
  timeout: z.number().optional(),
  rpId: z.string().optional(),
  userVerification: z.string().optional(),
  allowCredentials: z.array(z.object({
    id: z.string(),
    type: z.string(),
    transports: z.array(z.string())
  })).optional(),
  sessionId: SessionIdentifierSchema.optional(),
  error: z.string().optional()
})

export const AuthCompleteRequestSchema = z.object({
  credential: AuthenticationCredentialSchema,
  challenge: ChallengeSchema
})

export const AuthCompleteResponseSchema = z.object({
  success: z.boolean(),
  userId: z.string().min(1).max(128).optional(),
  email: z.string().optional(),
  passkeyId: z.string().optional(),
  error: z.string().optional(),
  // JWT tokens (added in Phase 3 - returned when authentication completes)
  accessToken: z.string().optional(),
  refreshToken: z.string().optional(),
  expiresAt: z.number().optional() // Unix timestamp
})

export const AuthStatusResponseSchema = z.object({
  status: z.enum(['pending', 'completed', 'expired', 'error']),
  userId: z.string().min(1).max(128).optional(),
  email: z.string().optional(),
  completedAt: z.string().optional(),
  expiresAt: z.number().optional(), // Unix timestamp
  message: z.string().optional(),
  // JWT tokens (added in Phase 3 - returned when authentication completes)
  accessToken: z.string().optional(),
  refreshToken: z.string().optional()
})

// ============================================================================
// Registration Route Schemas
// ============================================================================

export const RegisterChallengeRequestSchema = z.object({
  // Optional, and never authoritative: the challenge is bound to the
  // authenticated caller. Sending it asks the server to confirm the caller is
  // who the client thinks they are — a mismatch is rejected with 403.
  userId: z.string().min(1).max(128).optional(),
  sessionId: SessionIdentifierSchema.optional() // For cross-device registration polling
})

export const RegisterChallengeResponseSchema = z.object({
  success: z.boolean(),
  challenge: z.string().optional(),
  // Server-chosen relying party for this ceremony. The client passes it to
  // /register/mobile so registration and authentication cannot pin different
  // relying parties.
  rpId: z.string().optional(),
  rp: z.object({
    name: z.string(),
    id: z.string()
  }).optional(),
  user: z.object({
    id: z.string(),
    name: z.string(),
    displayName: z.string().max(256)
  }).optional(),
  pubKeyCredParams: z.array(z.object({
    type: z.string(),
    alg: z.number()
  })).optional(),
  timeout: z.number().optional(),
  attestation: z.string().optional(),
  authenticatorSelection: z.object({
    authenticatorAttachment: z.string(),
    userVerification: z.string(),
    requireResidentKey: z.boolean()
  }).optional(),
  sessionId: SessionIdentifierSchema.optional(), // Return sessionId for cross-device polling
  error: z.string().optional()
})

export const RegisterCompleteRequestSchema = z.object({
  // Optional, and never authoritative: the enrolling user comes from the
  // challenge issued at /register/challenge. A value that disagrees with it is
  // rejected rather than used.
  userId: z.string().min(1).max(128).optional(),
  credential: RegistrationCredentialSchema,
  challenge: ChallengeSchema,
  displayName: z.string().max(256).optional()
})

export const RegisterCompleteResponseSchema = z.object({
  success: z.boolean(),
  passkeyId: z.string().optional(),
  error: z.string().optional()
})

// ============================================================================
// Management Route Schemas
// ============================================================================

// userId is optional throughout /manage: the account is the authenticated
// caller, and a value that disagrees with the session is rejected with 403.
export const ManagementListRequestSchema = z.object({
  userId: z.string().min(1).max(128).optional()
})

export const PasskeySchema = z.object({
  id: z.string(),
  credential_id: z.string(),
  display_name: z.string(),
  created_at: z.string(),
  last_used_at: z.number().nullable(),
  transports: z.array(z.string())
})

export const ManagementListResponseSchema = z.object({
  success: z.boolean(),
  passkeys: z.array(PasskeySchema).optional(),
  error: z.string().optional()
})

export const ManagementDeleteRequestSchema = z.object({
  userId: z.string().min(1).max(128).optional(),
  passkeyId: z.string().min(1).max(128)
})

export const ManagementDeleteResponseSchema = z.object({
  success: z.boolean(),
  error: z.string().optional()
})

export const ManagementUpdateRequestSchema = z.object({
  userId: z.string().min(1).max(128).optional(),
  passkeyId: z.string().min(1).max(128),
  displayName: z.string().max(256)
})

export const ManagementUpdateResponseSchema = z.object({
  success: z.boolean(),
  error: z.string().optional()
})

// ============================================================================
// Error Response Schema
// ============================================================================

export const ErrorResponseSchema = z.object({
  error: z.string()
})
