import { createRoute, OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import {
  generateRegistrationChallenge,
  completeRegistration,
  ALLOWED_ORIGINS
} from "../services/registration.ts"
import {
  RegisterChallengeRequestSchema,
  RegisterChallengeResponseSchema,
  RegisterCompleteRequestSchema,
  RegisterCompleteResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"

const register = new OpenAPIHono<{ Variables: PasskeyContext }>()

// ============================================================================
// POST /register/challenge - Generate registration challenge
// ============================================================================

const registerChallengeRoute = createRoute({
  method: 'post',
  path: '/challenge',
  tags: ['Registration'],
  summary: 'Generate WebAuthn registration challenge',
  description: 'Generates a challenge for registering a new passkey',
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

    const result = await generateRegistrationChallenge(supabase, userId, sessionId)

    if (!result.success) {
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
  description: 'Completes the registration ceremony by storing the new passkey',
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

    // Parse and validate origin
    const clientData = JSON.parse(atob(credential.response.clientDataJSON))
    if (!ALLOWED_ORIGINS.includes(clientData.origin)) {
      return ctx.json({ error: 'Invalid origin' }, 403)
    }

    const result = await completeRegistration(
      supabase,
      userId,
      credential,
      challenge,
      displayName
    )

    if (!result.success) {
      return ctx.json({ error: result.error || 'Registration failed' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

export default register
