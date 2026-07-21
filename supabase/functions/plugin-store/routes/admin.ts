import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PluginStoreContext } from "../types/context.ts"
import { ErrorResponseSchema } from "../types/schemas.ts"
import { getUserFromToken } from "../utils/auth.ts"
import { deleteJar } from "../services/storage.ts"

const admin = new OpenAPIHono<{ Variables: PluginStoreContext }>()

// ============================================================================
// Schemas
// ============================================================================

const AdminActionResponseSchema = z.object({
  success: z.boolean(),
  pluginId: z.string().optional(),
  published: z.boolean().optional(),
  verified: z.boolean().optional(),
  error: z.string().optional()
}).openapi('AdminActionResponse')

const SetPublishedRequestSchema = z.object({
  published: z.boolean()
}).openapi('SetPublishedRequest')

const SetVerifiedRequestSchema = z.object({
  verified: z.boolean()
}).openapi('SetVerifiedRequest')

// ============================================================================
// POST /admin/:pluginId/publish - Enable/disable a plugin (admin only)
// ============================================================================

const setPublishedRoute = createRoute({
  method: 'post',
  path: '/admin/{pluginId}/publish',
  tags: ['Admin'],
  summary: 'Enable/disable a plugin (admin only)',
  description: 'Set the published status of a plugin. Disabled plugins are hidden from regular users.',
  request: {
    params: z.object({
      pluginId: z.string()
    }),
    body: {
      content: {
        'application/json': {
          schema: SetPublishedRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Plugin status updated successfully',
      content: { 'application/json': { schema: AdminActionResponseSchema } }
    },
    401: {
      description: 'Unauthorized',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    403: {
      description: 'Forbidden - Admin access required',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    404: {
      description: 'Plugin not found',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    500: {
      description: 'Internal server error',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    }
  }
})

admin.openapi(setPublishedRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")

    // Verify authentication and admin role
    const authHeader = ctx.req.header('Authorization')
    const user = await getUserFromToken(supabase, authHeader)

    if (!user) {
      return ctx.json({ error: 'Authentication required' }, 401)
    }

    if (!user.isAdmin) {
      return ctx.json({ error: 'Admin access required' }, 403)
    }

    const { pluginId } = ctx.req.valid('param')
    const { published } = ctx.req.valid('json')

    // Update plugin - RLS allows admins to update any plugin
    const { data, error } = await supabase
      .from('plugins')
      .update({ published, updated_at: new Date().toISOString() })
      .eq('plugin_id', pluginId)
      .select('id')
      .single()

    if (error || !data) {
      console.error('Error updating plugin:', error)
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    return ctx.json({ success: true, pluginId, published }, 200)
  } catch (error) {
    console.error('Error in set published:', error)
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

// ============================================================================
// DELETE /admin/:pluginId - Delete a plugin (admin only)
// ============================================================================

const deletePluginRoute = createRoute({
  method: 'delete',
  path: '/admin/{pluginId}',
  tags: ['Admin'],
  summary: 'Delete a plugin (admin only)',
  description: 'Permanently delete a plugin and all its versions, ratings, and downloads.',
  request: {
    params: z.object({
      pluginId: z.string()
    })
  },
  responses: {
    200: {
      description: 'Plugin deleted successfully',
      content: { 'application/json': { schema: AdminActionResponseSchema } }
    },
    401: {
      description: 'Unauthorized',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    403: {
      description: 'Forbidden - Admin access required',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    404: {
      description: 'Plugin not found',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    500: {
      description: 'Internal server error',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    }
  }
})

admin.openapi(deletePluginRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")

    // Verify authentication and admin role
    const authHeader = ctx.req.header('Authorization')
    const user = await getUserFromToken(supabase, authHeader)

    if (!user) {
      return ctx.json({ error: 'Authentication required' }, 401)
    }

    if (!user.isAdmin) {
      return ctx.json({ error: 'Admin access required' }, 403)
    }

    const { pluginId } = ctx.req.valid('param')

    // Get plugin UUID and JAR paths before deletion
    const { data: plugin, error: findError } = await supabase
      .from('plugins')
      .select('id')
      .eq('plugin_id', pluginId)
      .single()

    if (findError || !plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Get all version JAR paths for cleanup
    const { data: versions } = await supabase
      .from('plugin_versions')
      .select('jar_path')
      .eq('plugin_id', plugin.id)

    // Delete plugin (cascades to versions, tags, etc.)
    const { error: deleteError } = await supabase
      .from('plugins')
      .delete()
      .eq('id', plugin.id)

    if (deleteError) {
      console.error('Error deleting plugin:', deleteError)
      return ctx.json({ error: deleteError.message }, 500)
    }

    // Clean up JAR files from storage
    if (versions && versions.length > 0) {
      for (const version of versions) {
        if (version.jar_path) {
          try {
            await deleteJar(supabase, version.jar_path)
          } catch (storageError) {
            console.error('Error deleting JAR from storage:', storageError)
          }
        }
      }
    }

    return ctx.json({ success: true, pluginId }, 200)
  } catch (error) {
    console.error('Error in delete plugin:', error)
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

// ============================================================================
// POST /admin/:pluginId/verify - Verify/unverify a plugin (admin only)
// ============================================================================

const setVerifiedRoute = createRoute({
  method: 'post',
  path: '/admin/{pluginId}/verify',
  tags: ['Admin'],
  summary: 'Verify/unverify a plugin (admin only)',
  description: 'Set the verified status of a plugin. Verified plugins show a checkmark.',
  request: {
    params: z.object({
      pluginId: z.string()
    }),
    body: {
      content: {
        'application/json': {
          schema: SetVerifiedRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Plugin verification status updated successfully',
      content: { 'application/json': { schema: AdminActionResponseSchema } }
    },
    401: {
      description: 'Unauthorized',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    403: {
      description: 'Forbidden - Admin access required',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    404: {
      description: 'Plugin not found',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    },
    500: {
      description: 'Internal server error',
      content: { 'application/json': { schema: ErrorResponseSchema } }
    }
  }
})

admin.openapi(setVerifiedRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")

    // Verify authentication and admin role
    const authHeader = ctx.req.header('Authorization')
    const user = await getUserFromToken(supabase, authHeader)

    if (!user) {
      return ctx.json({ error: 'Authentication required' }, 401)
    }

    if (!user.isAdmin) {
      return ctx.json({ error: 'Admin access required' }, 403)
    }

    const { pluginId } = ctx.req.valid('param')
    const { verified } = ctx.req.valid('json')

    // Update plugin
    const { data, error } = await supabase
      .from('plugins')
      .update({ verified, updated_at: new Date().toISOString() })
      .eq('plugin_id', pluginId)
      .select('id')
      .single()

    if (error || !data) {
      console.error('Error updating plugin:', error)
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    return ctx.json({ success: true, pluginId, verified }, 200)
  } catch (error) {
    console.error('Error in set verified:', error)
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

export default admin
