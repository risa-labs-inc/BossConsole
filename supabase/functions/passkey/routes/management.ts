import { createRoute, OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import { listUserPasskeys, deleteUserPasskey, updatePasskeyDisplayName } from "../services/management.ts"
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
  try {
    const supabase = ctx.get("supabase")
    const { userId } = ctx.req.valid('json')

    const result = await listUserPasskeys(supabase, userId)

    if (!result.success) {
      return ctx.json({ error: result.error || 'Failed to list passkeys' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
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
  try {
    const supabase = ctx.get("supabase")
    const { userId, passkeyId } = ctx.req.valid('json')

    const result = await deleteUserPasskey(supabase, userId, passkeyId)

    if (!result.success) {
      return ctx.json({ error: result.error || 'Failed to delete passkey' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
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
  try {
    const supabase = ctx.get("supabase")
    const { userId, passkeyId, displayName } = ctx.req.valid('json')

    const result = await updatePasskeyDisplayName(
      supabase,
      userId,
      passkeyId,
      displayName
    )

    if (!result.success) {
      return ctx.json({ error: result.error || 'Failed to update passkey' }, 400)
    }

    return ctx.json(result, 200)
  } catch (error) {
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

export default management
