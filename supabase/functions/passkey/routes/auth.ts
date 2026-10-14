import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import {
  generateAuthChallenge,
  completeAuthentication,
  checkAuthStatus
} from "../services/auth.ts"
import { getAllowedOrigins } from "../utils/config.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { parseClientDataJSON } from "../utils/webauthn.ts"
import {
  AuthChallengeRequestSchema,
  AuthChallengeResponseSchema,
  AuthCompleteRequestSchema,
  AuthCompleteResponseSchema,
  AuthStatusResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"

const auth = new OpenAPIHono<{ Variables: PasskeyContext }>()

// Brake on the cheap loop: an unauthenticated script walking a candidate
// email list (BossConsole#768). Per-isolate, honestly not a defence against
// a distributed attacker; the inert-challenge response is what removes the
// oracle, this only makes bulk probing cost a real rate.
//
// Budget (review follow-up): the desktop sign-in flow spends up to three
// challenge calls per successful sign-in (initial + retry/re-prompt paths),
// so the per-client budget is 60/hour - three full sign-in attempts with
// headroom, while a candidate-list walk still hits the wall after 60 probes.
const AUTH_CHALLENGE_LIMIT = 60
const AUTH_CHALLENGE_WINDOW_SECONDS = 60 * 60

// Same brake for the completion step. /auth/complete verifies an ES256
// signature BEFORE the challenge row is consumed, so a single captured
// challenge replays into unlimited signature-verification CPU until the
// replay loop itself is capped. A sign-in spends one complete per attempt
// (plus a retry when the authenticator bumps its counter), so 120/hour is
// far above any honest client and far below a replay script.
const AUTH_COMPLETE_LIMIT = 120
const AUTH_COMPLETE_WINDOW_SECONDS = 60 * 60

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
    429: {
      description: 'Too many requests - per-client rate limit exceeded',
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
  // Rate limit first, before any lookup: the probe itself is what is being
  // braked, not the failure it produces.
  const limit = rateLimit(
    `authchallenge:${clientKey(ctx.req.raw.headers)}`,
    AUTH_CHALLENGE_LIMIT,
    AUTH_CHALLENGE_WINDOW_SECONDS,
  )
  if (!limit.allowed) {
    return ctx.json({ error: 'Too many requests' }, 429)
  }

  try {
    const supabase = ctx.get("supabase")
    const { email, sessionId } = ctx.req.valid('json')

    const result = await generateAuthChallenge(supabase, email, sessionId)

    if (!result.success) {
      return ctx.json({ error: result.error || 'Failed to generate challenge' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    console.error('Route error:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
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
    429: {
      description: 'Too many requests - per-client rate limit exceeded',
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
  // Rate limit first, before the ES256 verification: the challenge row is
  // consumed only after signature verification succeeds, so a single captured
  // challenge would otherwise replay into unlimited verification CPU.
  const limit = rateLimit(
    `authcomplete:${clientKey(ctx.req.raw.headers)}`,
    AUTH_COMPLETE_LIMIT,
    AUTH_COMPLETE_WINDOW_SECONDS,
  )
  if (!limit.allowed) {
    return ctx.json({ error: 'Too many requests' }, 429)
  }

  try {
    const supabase = ctx.get("supabase")
    const { credential, challenge } = ctx.req.valid('json')

    // Parse and validate origin (base64url-tolerant; see utils/base64.ts).
    // A payload we cannot decode is a bad request, not a server fault.
    let clientData
    try {
      clientData = parseClientDataJSON(credential.response.clientDataJSON).data
    } catch (error) {
      console.error('❌ Malformed clientDataJSON on auth/complete:', (error as Error).message)
      return ctx.json({ error: 'Invalid clientDataJSON' }, 400)
    }

    if (!getAllowedOrigins().includes(clientData.origin)) {
      return ctx.json({ error: 'Invalid origin' }, 403)
    }

    const result = await completeAuthentication(supabase, credential, challenge)

    if (!result.success) {
      return ctx.json({ error: result.error || 'Authentication failed' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    console.error('Route error:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
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
    console.error('Route error:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

export default auth
