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

    const result = await listPlugins(supabase, page, pageSize, sortBy)

    return ctx.json({
      plugins: result.plugins,
      totalCount: result.totalCount,
      page,
      pageSize
    }, 200)
  } catch (error) {
    console.error('Error listing plugins:', error)
    return ctx.json({ error: (error as Error).message }, 500)
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
    return ctx.json({ error: (error as Error).message }, 500)
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
    return ctx.json({ error: (error as Error).message }, 500)
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
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

export default browse
