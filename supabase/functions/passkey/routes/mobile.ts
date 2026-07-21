import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import { getMobileRegistrationHTML, getMobileAuthenticationHTML, getMobileErrorHTML } from "../utils/html.ts"
import { generateMobileRegistrationPage, generateMobileAuthenticationPage } from "../services/mobile.ts"

const mobile = new OpenAPIHono<{ Variables: PasskeyContext }>()

// ============================================================================
// GET /register/mobile - Mobile registration HTML page
// ============================================================================

const registerMobileRoute = createRoute({
  method: 'get',
  path: '/register/mobile',
  tags: ['Mobile'],
  summary: 'Get mobile registration HTML page',
  description: 'Returns HTML page that performs WebAuthn registration ceremony on mobile device',
  request: {
    query: z.object({
      challenge: z.string().describe('WebAuthn challenge'),
      email: z.string().email().describe('User email'),
      sessionId: z.string().describe('Session ID for tracking'),
      rpId: z.string().optional().describe('Relying party ID'),
      rpName: z.string().optional().describe('Relying party name')
    })
  },
  responses: {
    200: {
      description: 'HTML page for mobile registration',
      content: {
        'text/html': {
          schema: z.string()
        }
      }
    },
    400: {
      description: 'Bad request - missing parameters',
      content: {
        'text/html': {
          schema: z.string()
        }
      }
    },
    404: {
      description: 'Challenge not found or user not found',
      content: {
        'text/html': {
          schema: z.string()
        }
      }
    }
  }
})

mobile.openapi(registerMobileRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { challenge, email, sessionId, rpId = 'api.risaboss.com', rpName = 'BOSS' } = ctx.req.valid('query')

    if (!challenge || !email || !sessionId) {
      return ctx.html(getMobileErrorHTML('Missing required parameters: challenge, email, sessionId'), 400)
    }

    // Generate mobile registration page using service layer
    const result = await generateMobileRegistrationPage(
      supabase,
      challenge,
      email,
      sessionId,
      rpId,
      rpName
    )

    if (!result.success) {
      const statusCode = result.error?.includes('not found') ? 404 : 400
      return ctx.html(getMobileErrorHTML(result.error || 'Failed to generate registration page'), statusCode)
    }

    // Return mobile registration HTML page with no-cache headers
    const html = await getMobileRegistrationHTML(
      result.challenge!,
      result.userId!,
      result.email!,
      result.sessionId!,
      result.rpId!,
      result.rpName!
    )

    ctx.header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
    ctx.header('Pragma', 'no-cache')
    ctx.header('Expires', '0')

    return ctx.html(html, 200)

  } catch (error) {
    console.error('❌ Mobile registration error:', error)
    return ctx.html(getMobileErrorHTML('Internal server error'), 500)
  }
})

// ============================================================================
// GET /auth/mobile - Mobile authentication HTML page
// ============================================================================

const authMobileRoute = createRoute({
  method: 'get',
  path: '/auth/mobile',
  tags: ['Mobile'],
  summary: 'Get mobile authentication HTML page',
  description: 'Returns HTML page that performs WebAuthn authentication ceremony on mobile device',
  request: {
    query: z.object({
      challenge: z.string().describe('WebAuthn challenge'),
      email: z.string().email().describe('User email'),
      sessionId: z.string().describe('Session ID for tracking'),
      credentialId: z.string().describe('Credential ID to use for authentication'),
      rpId: z.string().optional().describe('Relying party ID')
    })
  },
  responses: {
    200: {
      description: 'HTML page for mobile authentication',
      content: {
        'text/html': {
          schema: z.string()
        }
      }
    },
    400: {
      description: 'Bad request - missing parameters or invalid challenge',
      content: {
        'text/html': {
          schema: z.string()
        }
      }
    },
    404: {
      description: 'User not found or credential not found',
      content: {
        'text/html': {
          schema: z.string()
        }
      }
    }
  }
})

mobile.openapi(authMobileRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { challenge, email, sessionId, credentialId, rpId = 'api.risaboss.com' } = ctx.req.valid('query')

    if (!challenge || !email || !sessionId || !credentialId) {
      return ctx.html(getMobileErrorHTML('Missing required parameters for mobile authentication'), 400)
    }

    // Generate mobile authentication page using service layer
    const result = await generateMobileAuthenticationPage(
      supabase,
      challenge,
      email,
      sessionId,
      credentialId,
      rpId
    )

    if (!result.success) {
      const statusCode = result.error?.includes('not found') ? 404 : 400
      return ctx.html(getMobileErrorHTML(result.error || 'Failed to generate authentication page'), statusCode)
    }

    // Return mobile authentication HTML page
    return ctx.html(getMobileAuthenticationHTML(
      result.challenge!,
      result.email!,
      result.sessionId!,
      result.rpId!,
      result.credentialId!,
      result.credentialDisplayName!,
      result.credentialCreatedAt!
    ), 200)

  } catch (error) {
    console.error('❌ Mobile authentication error:', error)
    return ctx.html(getMobileErrorHTML('Internal server error'), 500)
  }
})

export default mobile
