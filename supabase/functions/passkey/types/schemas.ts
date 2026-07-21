import { z } from "zod"

// ============================================================================
// WebAuthn Credential Schemas
// ============================================================================

export const AuthenticatorResponseSchema = z.object({
  clientDataJSON: z.string(),
  authenticatorData: z.string(),
  signature: z.string(),
  userHandle: z.string().optional()
})

export const RegistrationResponseSchema = z.object({
  clientDataJSON: z.string(),
  attestationObject: z.string()
})

export const AuthenticationCredentialSchema = z.object({
  id: z.string(),
  rawId: z.string(),
  type: z.string(),
  response: AuthenticatorResponseSchema
})

export const RegistrationCredentialSchema = z.object({
  id: z.string(),
  rawId: z.string(),
  type: z.string(),
  response: RegistrationResponseSchema
})

// ============================================================================
// Auth Route Schemas
// ============================================================================

export const AuthChallengeRequestSchema = z.object({
  email: z.string().email("Invalid email format"),
  sessionId: z.string().optional()
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
  sessionId: z.string().optional(),
  error: z.string().optional()
})

export const AuthCompleteRequestSchema = z.object({
  credential: AuthenticationCredentialSchema,
  challenge: z.string()
})

export const AuthCompleteResponseSchema = z.object({
  success: z.boolean(),
  userId: z.string().optional(),
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
  userId: z.string().optional(),
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
  userId: z.string(),
  sessionId: z.string().optional() // For cross-device registration polling
})

export const RegisterChallengeResponseSchema = z.object({
  success: z.boolean(),
  challenge: z.string().optional(),
  rp: z.object({
    name: z.string(),
    id: z.string()
  }).optional(),
  user: z.object({
    id: z.string(),
    name: z.string(),
    displayName: z.string()
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
  sessionId: z.string().optional(), // Return sessionId for cross-device polling
  error: z.string().optional()
})

export const RegisterCompleteRequestSchema = z.object({
  userId: z.string(),
  credential: RegistrationCredentialSchema,
  challenge: z.string(),
  displayName: z.string().optional()
})

export const RegisterCompleteResponseSchema = z.object({
  success: z.boolean(),
  passkeyId: z.string().optional(),
  error: z.string().optional()
})

// ============================================================================
// Management Route Schemas
// ============================================================================

export const ManagementListRequestSchema = z.object({
  userId: z.string()
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
  userId: z.string(),
  passkeyId: z.string()
})

export const ManagementDeleteResponseSchema = z.object({
  success: z.boolean(),
  error: z.string().optional()
})

export const ManagementUpdateRequestSchema = z.object({
  userId: z.string(),
  passkeyId: z.string(),
  displayName: z.string()
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
