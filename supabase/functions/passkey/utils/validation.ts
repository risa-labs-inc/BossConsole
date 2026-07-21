/**
 * Validates the origin of a WebAuthn request
 */
export function validateOrigin(origin: string, allowedOrigins: string[]): boolean {
  console.log('Validating origin:', origin)
  console.log('Allowed origins:', allowedOrigins)

  const isValid = allowedOrigins.includes(origin)
  console.log('Origin validation result:', isValid)

  return isValid
}

/**
 * Validates required fields in registration request
 */
export function validateRegistrationRequest(body: unknown): {
  valid: boolean
  error?: string
  data?: {
    userId: string
    credential: {
      id: string
      rawId: string
      type: string
      response: {
        clientDataJSON: string
        attestationObject: string
      }
    }
    challenge: string
    displayName?: string
  }
} {
  if (!body || typeof body !== 'object') {
    return { valid: false, error: 'Invalid request body' }
  }

  const req = body as Record<string, unknown>

  if (!req.userId || typeof req.userId !== 'string') {
    return { valid: false, error: 'Missing or invalid userId' }
  }

  if (!req.credential || typeof req.credential !== 'object') {
    return { valid: false, error: 'Missing or invalid credential' }
  }

  if (!req.challenge || typeof req.challenge !== 'string') {
    return { valid: false, error: 'Missing or invalid challenge' }
  }

  const credential = req.credential as Record<string, unknown>

  if (!credential.id || typeof credential.id !== 'string') {
    return { valid: false, error: 'Missing or invalid credential.id' }
  }

  if (!credential.rawId || typeof credential.rawId !== 'string') {
    return { valid: false, error: 'Missing or invalid credential.rawId' }
  }

  if (!credential.type || credential.type !== 'public-key') {
    return { valid: false, error: 'Invalid credential type' }
  }

  if (!credential.response || typeof credential.response !== 'object') {
    return { valid: false, error: 'Missing or invalid credential.response' }
  }

  const response = credential.response as Record<string, unknown>

  if (!response.clientDataJSON || typeof response.clientDataJSON !== 'string') {
    return { valid: false, error: 'Missing or invalid clientDataJSON' }
  }

  if (!response.attestationObject || typeof response.attestationObject !== 'string') {
    return { valid: false, error: 'Missing or invalid attestationObject' }
  }

  return {
    valid: true,
    data: {
      userId: req.userId as string,
      credential: {
        id: credential.id as string,
        rawId: credential.rawId as string,
        type: credential.type as string,
        response: {
          clientDataJSON: response.clientDataJSON as string,
          attestationObject: response.attestationObject as string,
        }
      },
      challenge: req.challenge as string,
      displayName: req.displayName as string | undefined
    }
  }
}

/**
 * Validates required fields in authentication request
 */
export function validateAuthenticationRequest(body: unknown): {
  valid: boolean
  error?: string
  data?: {
    credential: {
      id: string
      rawId: string
      type: string
      response: {
        clientDataJSON: string
        authenticatorData: string
        signature: string
        userHandle?: string
      }
    }
    challenge: string
  }
} {
  if (!body || typeof body !== 'object') {
    return { valid: false, error: 'Invalid request body' }
  }

  const req = body as Record<string, unknown>

  if (!req.credential || typeof req.credential !== 'object') {
    return { valid: false, error: 'Missing or invalid credential' }
  }

  if (!req.challenge || typeof req.challenge !== 'string') {
    return { valid: false, error: 'Missing or invalid challenge' }
  }

  const credential = req.credential as Record<string, unknown>

  if (!credential.id || typeof credential.id !== 'string') {
    return { valid: false, error: 'Missing or invalid credential.id' }
  }

  if (!credential.rawId || typeof credential.rawId !== 'string') {
    return { valid: false, error: 'Missing or invalid credential.rawId' }
  }

  if (!credential.type || credential.type !== 'public-key') {
    return { valid: false, error: 'Invalid credential type' }
  }

  if (!credential.response || typeof credential.response !== 'object') {
    return { valid: false, error: 'Missing or invalid credential.response' }
  }

  const response = credential.response as Record<string, unknown>

  if (!response.clientDataJSON || typeof response.clientDataJSON !== 'string') {
    return { valid: false, error: 'Missing or invalid clientDataJSON' }
  }

  if (!response.authenticatorData || typeof response.authenticatorData !== 'string') {
    return { valid: false, error: 'Missing or invalid authenticatorData' }
  }

  if (!response.signature || typeof response.signature !== 'string') {
    return { valid: false, error: 'Missing or invalid signature' }
  }

  return {
    valid: true,
    data: {
      credential: {
        id: credential.id as string,
        rawId: credential.rawId as string,
        type: credential.type as string,
        response: {
          clientDataJSON: response.clientDataJSON as string,
          authenticatorData: response.authenticatorData as string,
          signature: response.signature as string,
          userHandle: response.userHandle as string | undefined
        }
      },
      challenge: req.challenge as string
    }
  }
}

/**
 * Validates session ID format
 */
export function validateSessionId(sessionId: unknown): boolean {
  if (!sessionId || typeof sessionId !== 'string') {
    return false
  }

  // Session ID should be a UUID or similar format
  return sessionId.length > 0 && sessionId.length < 256
}
