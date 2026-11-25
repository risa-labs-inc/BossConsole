import { createRoute, z } from "@hono/zod-openapi"
import { ErrorResponseSchema } from "../types/schemas.ts"
import { getAuthenticatedUser, getUserFromToken } from "../utils/auth.ts"
import { deleteJar } from "../services/storage.ts"
import { getPluginById } from "../services/plugins.ts"
import {
  getVersionById,
  listReviewQueue,
  PENDING_SHA256,
  recordReviewDecision,
  signVersion,
} from "../services/versions.ts"
import { newRouter } from "../utils/router.ts"

const admin = newRouter()

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
    return ctx.json({ error: 'Internal server error' }, 500)
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
      return ctx.json({ error: 'Internal server error' }, 500)
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
    return ctx.json({ error: 'Internal server error' }, 500)
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
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// Max app review: GET /admin/review/queue, POST /admin/review/:versionId/approve|reject
// ============================================================================
//
// Permission plugins.review (the marketplace_reviewer role holds it; platform admins hold every permission). Checked
// with getAuthenticatedUser's requiredPermission, i.e. userHasPermission: the JWT's user_permissions claim, admins
// bypass. Session auth only, no API keys: a review is a person's decision.
//
// Approve signs the version with the same code finalize uses for a plugin (signVersion) and makes it eligible for
// latest; reject records the note the publisher sees. Both are one plugin_version_review call, which only acts on
// the still-pending row with the bytes the reviewer was shown and writes platform_audit_log (plugin_store.approve /
// plugin_store.reject) in the same statement. A reviewer may not decide on their own plugin (admins excepted).

export const REVIEW_PERMISSION = 'plugins.review'

const ReviewQueueResponseSchema = z.object({
  versions: z.array(z.object({
    versionId: z.string().uuid(),
    pluginId: z.string(),
    pluginUuid: z.string().uuid(),
    displayName: z.string(),
    authorId: z.string().uuid().nullable(),
    authorName: z.string(),
    orgId: z.string().uuid().nullable(),
    version: z.string(),
    channel: z.enum(['stable', 'beta', 'test']),
    changelog: z.string(),
    jarSize: z.number(),
    sha256: z.string(),
    publishedAt: z.string(),
    reviewMeta: z.record(z.unknown()).nullable()
  }))
}).openapi('ReviewQueueResponse')

const ReviewDecisionResponseSchema = z.object({
  success: z.boolean(),
  versionId: z.string().uuid(),
  pluginId: z.string(),
  version: z.string(),
  reviewStatus: z.enum(['approved', 'rejected']),
  signed: z.boolean().optional()
}).openapi('ReviewDecisionResponse')

export const RejectRequestSchema = z.object({
  note: z.string().trim().min(1, 'A note for the publisher is required').max(1000)
}).openapi('RejectVersionRequest')

const reviewErrorResponses = {
  400: { description: 'Invalid request', content: { 'application/json': { schema: ErrorResponseSchema } } },
  401: { description: 'Unauthorized', content: { 'application/json': { schema: ErrorResponseSchema } } },
  403: {
    description: 'Forbidden - plugins.review required, or the version is your own plugin\'s',
    content: { 'application/json': { schema: ErrorResponseSchema } }
  },
  404: { description: 'No such Max app version', content: { 'application/json': { schema: ErrorResponseSchema } } },
  409: { description: 'The version is not waiting for review', content: { 'application/json': { schema: ErrorResponseSchema } } },
  500: { description: 'Internal server error', content: { 'application/json': { schema: ErrorResponseSchema } } }
}

const reviewQueueRoute = createRoute({
  method: 'get',
  path: '/admin/review/queue',
  tags: ['Admin'],
  summary: 'Max app versions waiting for review (plugins.review)',
  description: 'Finalized Max app versions in pending_review, oldest first, with what the content-only check recorded.',
  responses: {
    200: { description: 'The queue', content: { 'application/json': { schema: ReviewQueueResponseSchema } } },
    401: reviewErrorResponses[401],
    403: reviewErrorResponses[403],
    500: reviewErrorResponses[500]
  }
})

admin.openapi(reviewQueueRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const auth = await getAuthenticatedUser(supabase, ctx.req.header('Authorization'), undefined, {
      requiredPermission: REVIEW_PERMISSION,
    })
    if (!auth.ok) {
      return ctx.json({ error: auth.error }, auth.status)
    }
    const versions = await listReviewQueue(supabase)
    ctx.header('Cache-Control', 'private, no-store')
    return ctx.json({ versions }, 200)
  } catch (error) {
    console.error('Error reading the review queue:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

const VersionIdParamSchema = z.object({ versionId: z.string().uuid() })

const approveRoute = createRoute({
  method: 'post',
  path: '/admin/review/{versionId}/approve',
  tags: ['Admin'],
  summary: 'Approve a Max app version (plugins.review)',
  description: 'Signs the version (pluginId|version|sha256, as finalize signs a plugin) and makes it eligible for latest.',
  request: { params: VersionIdParamSchema },
  responses: {
    200: { description: 'Approved', content: { 'application/json': { schema: ReviewDecisionResponseSchema } } },
    ...reviewErrorResponses
  }
})

const rejectRoute = createRoute({
  method: 'post',
  path: '/admin/review/{versionId}/reject',
  tags: ['Admin'],
  summary: 'Reject a Max app version (plugins.review)',
  description: 'Records the note the publisher sees. The version stays unsigned and is never served as latest.',
  request: {
    params: VersionIdParamSchema,
    body: { content: { 'application/json': { schema: RejectRequestSchema } } }
  },
  responses: {
    200: { description: 'Rejected', content: { 'application/json': { schema: ReviewDecisionResponseSchema } } },
    ...reviewErrorResponses
  }
})

type ReviewOutcome =
  | { ok: true; body: z.infer<typeof ReviewDecisionResponseSchema> }
  | { ok: false; status: 400 | 401 | 403 | 404 | 409; error: string }

/** Shared by approve and reject: who may decide, on what, and the decision itself. */
async function decide(
  supabase: Parameters<typeof getVersionById>[0],
  authHeader: string | undefined,
  versionId: string,
  approve: boolean,
  note: string | null
): Promise<ReviewOutcome> {
  const auth = await getAuthenticatedUser(supabase, authHeader, undefined, { requiredPermission: REVIEW_PERMISSION })
  if (!auth.ok) return { ok: false, status: auth.status, error: auth.error }
  const user = auth.user

  const version = await getVersionById(supabase, versionId)
  const plugin = version ? await getPluginById(supabase, version.pluginId) : null
  if (!version || !plugin || plugin.kind !== 'max-app') {
    return { ok: false, status: 404, error: 'Max app version not found' }
  }
  if (version.reviewStatus !== 'pending_review' || version.sha256 === PENDING_SHA256 || !(version.jarSize > 0)) {
    return { ok: false, status: 409, error: 'This version is not waiting for review' }
  }
  if (plugin.authorId === user.userId && !user.isAdmin) {
    return { ok: false, status: 403, error: 'You cannot review your own Max app' }
  }

  const signature = approve ? await signVersion(version.id, plugin.pluginId, version.version, version.sha256) : null
  const r = await recordReviewDecision(supabase, {
    versionId: version.id,
    reviewerId: user.userId,
    approve,
    sha256: version.sha256,
    signature,
    note
  })
  if (!r.ok) {
    if (r.error === 'not_found') return { ok: false, status: 404, error: 'Max app version not found' }
    if (r.error === 'not_pending_review') return { ok: false, status: 409, error: 'This version is not waiting for review' }
    return { ok: false, status: 400, error: `Review refused: ${r.error}` }
  }
  return {
    ok: true,
    body: {
      success: true,
      versionId: version.id,
      pluginId: plugin.pluginId,
      version: version.version,
      reviewStatus: approve ? 'approved' : 'rejected',
      ...(approve ? { signed: signature !== null } : {})
    }
  }
}

admin.openapi(approveRoute, async (ctx) => {
  try {
    const { versionId } = ctx.req.valid('param')
    const r = await decide(ctx.get("supabase"), ctx.req.header('Authorization'), versionId, true, null)
    if (!r.ok) return ctx.json({ error: r.error }, r.status)
    return ctx.json(r.body, 200)
  } catch (error) {
    console.error('Error approving a version:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

admin.openapi(rejectRoute, async (ctx) => {
  try {
    const { versionId } = ctx.req.valid('param')
    const { note } = ctx.req.valid('json')
    const r = await decide(ctx.get("supabase"), ctx.req.header('Authorization'), versionId, false, note)
    if (!r.ok) return ctx.json({ error: r.error }, r.status)
    return ctx.json(r.body, 200)
  } catch (error) {
    console.error('Error rejecting a version:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

export default admin
