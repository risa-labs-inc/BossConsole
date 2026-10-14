import { createRoute, OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import { requireAuthenticatedCaller } from "../utils/authorization.ts"
import { listUserPasskeys, deleteUserPasskey, updatePasskeyDisplayName } from "../services/management.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import {
  ManagementListRequestSchema,
  ManagementListResponseSchema,
  ManagementDeleteRequestSchema,
  ManagementDeleteResponseSchema,
  ManagementUpdateRequestSchema,
  ManagementUpdateResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"

const management = new OpenAPIHono<{ Variables: PasskeyContext }>()

// Cost cap for the management routes. They are authenticated, so this is a
// brake on a script (or a stuck client loop) driving unlimited service-role
// lookups through one isolate, not a security boundary. Checked before the
// auth relay so an over-budget caller spends nothing. 240/hour is far above
// any honest management UI (a few calls per screen) and far below a hammer.
// Per-isolate; see utils/rate-limit.ts for the honest scope of that.
const MANAGE_LIMIT = 240
const MANAGE_WINDOW_SECONDS = 60 * 60

/**
 * True when this client is over the shared management budget.
 *
 * All three routes draw on one per-client bucket: they are one feature
 * (the passkey management screen) reached through one credential, and a
 * single bucket is what stops a script from interleaving them.
 */
function manageLimitExceeded(ctx: { req: { raw: { headers: Headers } } }): boolean {
  const limit = rateLimit(
    `manage:${clientKey(ctx.req.raw.headers)}`,
    MANAGE_LIMIT,
    MANAGE_WINDOW_SECONDS,
  )
  return !limit.allowed
}

/**
 * Resolves the account these management routes may act on.
 *
 * They act on somebody's credentials — listing them, disabling them, renaming
 * them — against the service-role client on a function deployed with
 * `verify_jwt = false`. Taking `userId` from the request body therefore let any
 * caller enumerate and de-enrol another account's passkeys, which is
 * de-enrolment and a forced downgrade to email sign-in. The account is now the
 * authenticated caller; a body `userId` may only agree with it.
 */
async function resolveManagementUser(
  // deno-lint-ignore no-explicit-any
  supabase: any,
  authorizationHeader: string | null | undefined,
  claimedUserId?: string
): Promise<{ userId: string } | { error: string; status: 401 | 403 }> {
  const caller = await requireAuthenticatedCaller(supabase, authorizationHeader)
  if (!caller.success || !caller.caller) {
    return { error: caller.error || 'Authentication required', status: caller.status ?? 401 }
  }

  if (claimedUserId && claimedUserId !== caller.caller.userId) {
    console.error('❌ manage: body userId does not match the authenticated caller')
    return { error: 'userId does not match the authenticated user', status: 403 }
  }

  return { userId: caller.caller.userId }
}

// ============================================================================
// POST /manage/list - List user passkeys
// ============================================================================

const listPasskeysRoute = createRoute({
  method: 'post',
  path: '/list',
  tags: ['Management'],
  summary: 'List user passkeys',
  description: 'Lists all active passkeys for a user',
  request: {
    body: {
      content: {
        'application/json': {
          schema: ManagementListRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Passkeys listed successfully',
      content: {
        'application/json': {
          schema: ManagementListResponseSchema
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

management.openapi(listPasskeysRoute, async (ctx) => {
  if (manageLimitExceeded(ctx)) {
    return ctx.json({ error: 'Too many requests' }, 429)
  }

  try {
    const supabase = ctx.get("supabase")
    const { userId } = ctx.req.valid('json')

    const resolved = await resolveManagementUser(supabase, ctx.req.header('Authorization'), userId)
    if ('error' in resolved) {
      return ctx.json({ error: resolved.error }, resolved.status)
    }

    const result = await listUserPasskeys(supabase, resolved.userId)

    if (!result.success) {
      return ctx.json({ error: result.error || 'Failed to list passkeys' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    console.error('Route error:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// POST /manage/delete - Delete a passkey
// ============================================================================

const deletePasskeyRoute = createRoute({
  method: 'post',
  path: '/delete',
  tags: ['Management'],
  summary: 'Delete a passkey',
  description: 'Deletes a passkey for a user',
  request: {
    body: {
      content: {
        'application/json': {
          schema: ManagementDeleteRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Passkey deleted successfully',
      content: {
        'application/json': {
          schema: ManagementDeleteResponseSchema
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

management.openapi(deletePasskeyRoute, async (ctx) => {
  if (manageLimitExceeded(ctx)) {
    return ctx.json({ error: 'Too many requests' }, 429)
  }

  try {
    const supabase = ctx.get("supabase")
    const { userId, passkeyId } = ctx.req.valid('json')

    const resolved = await resolveManagementUser(supabase, ctx.req.header('Authorization'), userId)
    if ('error' in resolved) {
      return ctx.json({ error: resolved.error }, resolved.status)
    }

    const result = await deleteUserPasskey(supabase, resolved.userId, passkeyId)

    if (!result.success) {
      return ctx.json({ error: result.error || 'Failed to delete passkey' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    console.error('Route error:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// POST /manage/update - Update passkey display name
// ============================================================================

const updatePasskeyRoute = createRoute({
  method: 'post',
  path: '/update',
  tags: ['Management'],
  summary: 'Update passkey display name',
  description: 'Updates the display name of a passkey',
  request: {
    body: {
      content: {
        'application/json': {
          schema: ManagementUpdateRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Passkey updated successfully',
      content: {
        'application/json': {
          schema: ManagementUpdateResponseSchema
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

management.openapi(updatePasskeyRoute, async (ctx) => {
  if (manageLimitExceeded(ctx)) {
    return ctx.json({ error: 'Too many requests' }, 429)
  }

  try {
    const supabase = ctx.get("supabase")
    const { userId, passkeyId, displayName } = ctx.req.valid('json')

    const resolved = await resolveManagementUser(supabase, ctx.req.header('Authorization'), userId)
    if ('error' in resolved) {
      return ctx.json({ error: resolved.error }, resolved.status)
    }

    const result = await updatePasskeyDisplayName(
      supabase,
      resolved.userId,
      passkeyId,
      displayName
    )

    if (!result.success) {
      return ctx.json({ error: result.error || 'Failed to update passkey' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    console.error('Route error:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

export default management
