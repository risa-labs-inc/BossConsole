import { limitPasskeyRequest } from "../utils/request-limits.ts"
import { authFailureDetails } from "../utils/logging.ts"
import { createRoute, OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import {
  generateRegistrationChallenge,
  completeRegistration
} from "../services/registration.ts"
import { getAllowedOrigins } from "../utils/config.ts"
import { parseClientDataJSON } from "../utils/webauthn.ts"
import { requireAuthenticatedCaller, resolveOptionalCaller } from "../utils/authorization.ts"
import {
  RegisterChallengeRequestSchema,
  RegisterChallengeResponseSchema,
  RegisterCompleteRequestSchema,
  RegisterCompleteResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"

const register = new OpenAPIHono<{ Variables: PasskeyContext }>()
register.use("*", limitPasskeyRequest)

// ============================================================================
// POST /register/challenge - Generate registration challenge
// ============================================================================

const registerChallengeRoute = createRoute({
  method: 'post',
  path: '/challenge',
  tags: ['Registration'],
  summary: 'Generate WebAuthn registration challenge',
  description: 'Generates a challenge for registering a new passkey. Requires a signed-in caller: the challenge is bound to the authenticated user, not to a userId in the request body.',
  request: {
    body: {
      content: {
        'application/json': {
          schema: RegisterChallengeRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Challenge generated successfully',
      content: {
        'application/json': {
          schema: RegisterChallengeResponseSchema
        }
      }
    },
    400: {
      description: 'Bad request',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    401: {
      description: 'Authentication required - a valid user session must be presented',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    403: {
      description: 'The requested userId is not the authenticated user',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    429: {
      description: 'Challenge capacity reached; retry after the Retry-After interval',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    503: {
      description: 'Shared admission storage unavailable; retry after the Retry-After interval',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    500: {
      description: 'Internal server error',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    }
  }
})

register.openapi(registerChallengeRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { userId, sessionId } = ctx.req.valid('json')

    // Enrolling a passkey grants a permanent way into an account, so the caller
    // has to prove they own it. Everything downstream — the challenge row, and
    // therefore the credential — is bound to *this* identity, never to the
    // userId in the body.
    const caller = await requireAuthenticatedCaller(supabase, ctx.req.header('Authorization'))
    if (!caller.success || !caller.caller) {
      return ctx.json({ error: caller.error || 'Authentication required' }, caller.status ?? 401)
    }

    // A body userId is still accepted for compatibility, but it may only confirm
    // who the token already says this is.
    if (userId && userId !== caller.caller.userId) {
      console.error('❌ register/challenge body userId does not match the authenticated caller')
      return ctx.json({ error: 'userId does not match the authenticated user' }, 403)
    }

    const result = await generateRegistrationChallenge(supabase, caller.caller.userId, sessionId)

    if (!result.success) {
      if ('status' in result && (result.status === 429 || result.status === 503)) {
        ctx.header('Retry-After', String(result.retryAfterSeconds))
        return ctx.json({ error: result.error }, result.status)
      }
      return ctx.json({ error: result.error || 'Failed to generate challenge' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

// ============================================================================
// POST /register/complete - Complete registration ceremony
// ============================================================================

const registerCompleteRoute = createRoute({
  method: 'post',
  path: '/complete',
  tags: ['Registration'],
  summary: 'Complete WebAuthn registration',
  description: 'Completes the registration ceremony by storing the new passkey. The enrolling user is taken from the challenge issued at /register/challenge; a bearer token, if presented, must match it.',
  request: {
    body: {
      content: {
        'application/json': {
          schema: RegisterCompleteRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Registration successful',
      content: {
        'application/json': {
          schema: RegisterCompleteResponseSchema
        }
      }
    },
    400: {
      description: 'Bad request or registration failed',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    401: {
      description: 'A session was presented but is invalid or expired',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    403: {
      description: 'Invalid origin',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    500: {
      description: 'Internal server error',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    }
  }
})

register.openapi(registerCompleteRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { userId, credential, challenge, displayName } = ctx.req.valid('json')

    // Parse and validate origin (base64url-tolerant; see utils/base64.ts).
    // A payload we cannot decode is a bad request, not a server fault.
    let clientData
    try {
      clientData = parseClientDataJSON(credential.response.clientDataJSON).data
    } catch (error) {
      console.error('❌ Malformed clientDataJSON on register/complete:', authFailureDetails(error))
      return ctx.json({ error: 'Invalid clientDataJSON' }, 400)
    }

    if (!getAllowedOrigins().includes(clientData.origin)) {
      return ctx.json({ error: 'Invalid origin' }, 403)
    }

    // The enrolling user comes from the challenge row, which can only be created
    // by an authenticated caller (see /register/challenge above). A bearer token
    // is honoured when the transport can carry one — the cross-device page runs
    // in a phone browser and cannot — and then has to agree with that row.
    const caller = await resolveOptionalCaller(supabase, ctx.req.header('Authorization'))
    if (!caller.success) {
      return ctx.json({ error: caller.error || 'Invalid or expired session' }, caller.status ?? 401)
    }

    const result = await completeRegistration(supabase, credential, challenge, {
      claimedUserId: userId,
      authenticatedUserId: caller.caller?.userId,
      displayName
    })

    if (!result.success) {
      return ctx.json({ error: result.error || 'Registration failed' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

export default register
