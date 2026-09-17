import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PluginStoreContext } from "../types/context.ts"
import {
  ListPluginsQuerySchema,
  SearchPluginsRequestSchema,
  PluginListResponseSchema,
  PluginDetailResponseSchema,
  PopularTagsResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"
import { listPlugins, searchPlugins, getPlugin, getPopularTags } from "../services/plugins.ts"
import { getPluginVersions } from "../services/versions.ts"

const browse = new OpenAPIHono<{ Variables: PluginStoreContext }>()

// ============================================================================
// GET /list - List all plugins
// ============================================================================

const listRoute = createRoute({
  method: 'get',
  path: '/list',
  tags: ['Browse'],
  summary: 'List all plugins',
  description: 'Get a paginated list of all published plugins',
  request: {
    query: ListPluginsQuerySchema
  },
  responses: {
    200: {
      description: 'Plugin list retrieved successfully',
      content: {
        'application/json': {
          schema: PluginListResponseSchema
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

browse.openapi(listRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { page, pageSize, sortBy } = ctx.req.valid('query')

    // OPTIONAL auth. Browsing the store signed out must keep working, so a missing or unusable
    // token is not an error here - it simply yields the public catalogue, which is what every
    // caller got before this. A valid one additionally unlocks the organisation plugins that
    // user_can_view_plugin_row says this reader may see.
    const viewer = await optionalViewer(ctx)

    const result = await listPlugins(supabase, page, pageSize, sortBy, viewer)

    // PRIVATE when the answer depends on who asked. The same URL now returns different rows per
    // reader, so a shared cache holding one reader's copy would serve somebody else's
    // organisation plugins to the next caller. The other follow-up 20260803000000 asked for.
    ctx.header("Cache-Control", viewer ? "private, no-store" : "public, max-age=60")

    return ctx.json({
      plugins: result.plugins,
      totalCount: result.totalCount,
      page,
      pageSize
    }, 200)
  } catch (error) {
    console.error('Error listing plugins:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// POST /search - Search plugins
// ============================================================================

const searchRoute = createRoute({
  method: 'post',
  path: '/search',
  tags: ['Browse'],
  summary: 'Search plugins',
  description: 'Search plugins with filters and sorting options',
  request: {
    body: {
      content: {
        'application/json': {
          schema: SearchPluginsRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Search results retrieved successfully',
      content: {
        'application/json': {
          schema: PluginListResponseSchema
        }
      }
    },
    400: {
      description: 'Invalid request',
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

browse.openapi(searchRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const body = ctx.req.valid('json')

    const result = await searchPlugins(
      supabase,
      body.query,
      body.type || null,
      body.tags || null,
      body.minRating,
      body.verifiedOnly,
      body.page,
      body.pageSize,
      body.sortBy
    )

    return ctx.json({
      plugins: result.plugins,
      totalCount: result.totalCount,
      page: body.page,
      pageSize: body.pageSize
    }, 200)
  } catch (error) {
    console.error('Error searching plugins:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// GET /:pluginId - Get plugin details
// ============================================================================

const getPluginRoute = createRoute({
  method: 'get',
  path: '/{pluginId}',
  tags: ['Browse'],
  summary: 'Get plugin details',
  description: 'Get detailed information about a specific plugin including all versions',
  request: {
    params: z.object({
      pluginId: z.string()
    })
  },
  responses: {
    200: {
      description: 'Plugin details retrieved successfully',
      content: {
        'application/json': {
          schema: PluginDetailResponseSchema
        }
      }
    },
    404: {
      description: 'Plugin not found',
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

browse.openapi(getPluginRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { pluginId } = ctx.req.valid('param')

    const plugin = await getPlugin(supabase, pluginId)
    
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Get all versions
    const versions = await getPluginVersions(supabase, pluginId)

    return ctx.json({
      id: plugin.id,
      pluginId: plugin.pluginId,
      displayName: plugin.displayName,
      description: plugin.description,
      authorId: plugin.authorId,
      authorName: plugin.authorName,
      homepageUrl: plugin.homepageUrl,
      iconUrl: plugin.iconUrl,
      type: plugin.type,
      apiVersion: plugin.apiVersion,
      verified: plugin.verified,
      createdAt: plugin.createdAt,
      updatedAt: plugin.updatedAt,
      latestVersion: plugin.latestVersion,
      avgRating: plugin.avgRating,
      ratingCount: plugin.ratingCount,
      downloadCount: plugin.downloadCount,
      tags: plugin.tags,
      screenshots: plugin.screenshots,
      requiredPermissions: plugin.requiredPermissions,
      versions: versions.map(v => ({
        id: v.id,
        version: v.version,
        changelog: v.changelog,
        minBossVersion: v.minBossVersion,
        minIpcVersion: v.minIpcVersion,
        minApiVersion: v.minApiVersion,
        jarSize: v.jarSize,
        sha256: v.sha256,
        dependencies: v.dependencies,
        publishedAt: v.publishedAt,
        downloadCount: v.downloadCount || 0
      }))
    }, 200)
  } catch (error) {
    console.error('Error getting plugin:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// GET /tags/popular - Get popular tags
// ============================================================================

const popularTagsRoute = createRoute({
  method: 'get',
  path: '/tags/popular',
  tags: ['Browse'],
  summary: 'Get popular tags',
  description: 'Get the most used tags for filtering',
  request: {
    query: z.object({
      limit: z.string().optional().default('20').transform(Number)
    })
  },
  responses: {
    200: {
      description: 'Popular tags retrieved successfully',
      content: {
        'application/json': {
          schema: PopularTagsResponseSchema
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

browse.openapi(popularTagsRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { limit } = ctx.req.valid('query')

    const tags = await getPopularTags(supabase, limit)

    return ctx.json({ tags }, 200)
  } catch (error) {
    console.error('Error getting popular tags:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

export default browse

/**
 * The signed-in reader, or null.
 *
 * Deliberately quiet: every failure - no header, an expired token, a malformed one - answers null
 * and the caller gets the public catalogue. Browsing a store is not a privileged act, and turning a
 * stale session into an error would break the anonymous case this endpoint has always served.
 *
 * An API key is NOT accepted. A CI key exists to publish, and letting one read a catalogue scoped
 * to its owner's memberships would widen what a key in a build server can see for no reason anyone
 * asked for.
 */
async function optionalViewer(ctx: { get: (k: string) => unknown; req: { header: (n: string) => string | undefined } }): Promise<string | null> {
  const header = ctx.req.header("Authorization")
  if (!header || !header.toLowerCase().startsWith("bearer ")) return null
  const token = header.slice(7).trim()
  if (token.length === 0) return null

  // The anon key arrives in this header from some clients. It is a valid JWT and resolves to no
  // user, so getUser refuses it - but checking first saves a network round trip on the common path.
  try {
    const supabase = ctx.get("supabase") as { auth: { getUser: (t: string) => Promise<{ data: { user: { id: string } | null } }> } }
    const { data } = await supabase.auth.getUser(token)
    return data?.user?.id ?? null
  } catch {
    return null
  }
}
