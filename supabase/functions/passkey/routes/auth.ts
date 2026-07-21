import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import {
  generateAuthChallenge,
  completeAuthentication,
  checkAuthStatus,
  ALLOWED_ORIGINS
} from "../services/auth.ts"
import {
  AuthChallengeRequestSchema,
  AuthChallengeResponseSchema,
  AuthCompleteRequestSchema,
  AuthCompleteResponseSchema,
  AuthStatusResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"

const auth = new OpenAPIHono<{ Variables: PasskeyContext }>()

// ============================================================================
// POST /auth/challenge - Generate authentication challenge
// ============================================================================

const authChallengeRoute = createRoute({
  method: 'post',
  path: '/challenge',
  tags: ['Authentication'],
  summary: 'Generate WebAuthn authentication challenge',
  description: 'Generates a challenge for authenticating with a passkey',
  request: {
    body: {
      content: {
        'application/json': {
          schema: AuthChallengeRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Challenge generated successfully',
      content: {
        'application/json': {
          schema: AuthChallengeResponseSchema
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

auth.openapi(authChallengeRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { email, sessionId } = ctx.req.valid('json')

    const result = await generateAuthChallenge(supabase, email, sessionId)

    if (!result.success) {
      return ctx.json({ error: result.error || 'Failed to generate challenge' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

// ============================================================================
// POST /auth/complete - Complete authentication ceremony
// ============================================================================

const authCompleteRoute = createRoute({
  method: 'post',
  path: '/complete',
  tags: ['Authentication'],
  summary: 'Complete WebAuthn authentication',
  description: 'Completes the authentication ceremony by verifying the credential signature',
  request: {
    body: {
      content: {
        'application/json': {
          schema: AuthCompleteRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Authentication successful',
      content: {
        'application/json': {
          schema: AuthCompleteResponseSchema
        }
      }
    },
    400: {
      description: 'Bad request or authentication failed',
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

auth.openapi(authCompleteRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { credential, challenge } = ctx.req.valid('json')

    // Parse and validate origin
    const clientData = JSON.parse(atob(credential.response.clientDataJSON))
    if (!ALLOWED_ORIGINS.includes(clientData.origin)) {
      return ctx.json({ error: 'Invalid origin' }, 403)
    }

    const result = await completeAuthentication(supabase, credential, challenge)

    if (!result.success) {
      return ctx.json({ error: result.error || 'Authentication failed' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

// ============================================================================
// GET /auth/status/:sessionId - Check authentication status
// ============================================================================

const authStatusRoute = createRoute({
  method: 'get',
  path: '/status/{sessionId}',
  tags: ['Authentication'],
  summary: 'Check authentication session status',
  description: 'Checks whether an authentication session is pending, completed, or expired',
  request: {
    params: z.object({
      sessionId: z.string()
    })
  },
  responses: {
    200: {
      description: 'Status retrieved successfully',
      content: {
        'application/json': {
          schema: AuthStatusResponseSchema
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

auth.openapi(authStatusRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { sessionId } = ctx.req.valid('param')

    const result = await checkAuthStatus(supabase, sessionId)

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

export default auth
