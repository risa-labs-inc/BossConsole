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
import { clientKey, rateLimit } from "../utils/rate-limit.ts"

const browse = new OpenAPIHono<{ Variables: PluginStoreContext }>()

// Per-client limit on the anonymous catalogue routes (/list, /search,
// /tags/popular): the same in-isolate token bucket the organisation function
// applies to its handoff, invite and DNS routes (utils/rate-limit.ts is a
// verbatim copy of organisation/utils/rate-limit.ts). 60/min matches the
// organisation's admin-write brake - generous for a Toolbox paging through the
// store, small enough that an anon loop burning search_plugins ILIKE CPU and
// edge invocations is cut off quickly. Best effort by design: the util's header
// spells out what this is and is not.
const CATALOGUE_LIMIT = 60
const CATALOGUE_WINDOW_SECONDS = 60

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
    429: {
      description: 'Too many requests from this client',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
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
    // The brake before the work: this route is unauthenticated, so the limit
    // is consumed before a single database call.
    const limit = rateLimit(
      `catalogue:${clientKey(ctx.req.raw.headers)}`,
      CATALOGUE_LIMIT,
      CATALOGUE_WINDOW_SECONDS,
    )
    if (!limit.allowed) {
      ctx.header("Retry-After", String(limit.retryAfterSeconds))
      return ctx.json({ error: 'Too many requests; try again later' }, 429)
    }

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
    429: {
      description: 'Too many requests from this client',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
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
    // search_plugins is ILIKE-backed, so an unthrottled anon client burns DB
    // CPU per request; the limit is consumed before the query is parsed.
    const limit = rateLimit(
      `catalogue:${clientKey(ctx.req.raw.headers)}`,
      CATALOGUE_LIMIT,
      CATALOGUE_WINDOW_SECONDS,
    )
    if (!limit.allowed) {
      ctx.header("Retry-After", String(limit.retryAfterSeconds))
      return ctx.json({ error: 'Too many requests; try again later' }, 429)
    }

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

    // OPTIONAL auth, the same quiet rule as /list: a missing or unusable
    // token answers the public catalogue, a valid one additionally unlocks
    // what user_can_view_plugin_row says this reader may see. Without it the
    // service-role client computes visibility for NOBODY (auth.uid() is
    // NULL), and an organisation member got a 404 for their own
    // organisation's plugin on the very page that lists its versions
    // (issue #852).
    const viewer = await optionalViewer(ctx)

    const plugin = await getPlugin(supabase, pluginId, viewer)
    
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Get all versions
    const versions = await getPluginVersions(supabase, pluginId, viewer)

    // PRIVATE when the answer depends on who asked - the same reason and the
    // same header as /list: a shared cache holding one reader's copy would
    // serve somebody else's organisation plugins to the next caller.
    ctx.header("Cache-Control", viewer ? "private, no-store" : "public, max-age=60")

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
      // #912: plugin.latestVersion comes from the get_plugin_with_stats RPC's
      // own subquery, which orders by published_at with no finalized filter —
      // so a version row created before its JAR exists would headline the
      // detail page while being undownloadable (and absent from `versions`
      // below, which is filtered to finalized rows). The list is newest-first,
      // so its head is the real latest; null when nothing is finalized yet,
      // which matches what the download routes report for the same plugin.
      latestVersion: versions[0]?.version ?? null,
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
    429: {
      description: 'Too many requests from this client',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
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
    const limit = rateLimit(
      `catalogue:${clientKey(ctx.req.raw.headers)}`,
      CATALOGUE_LIMIT,
      CATALOGUE_WINDOW_SECONDS,
    )
    if (!limit.allowed) {
      ctx.header("Retry-After", String(limit.retryAfterSeconds))
      return ctx.json({ error: 'Too many requests; try again later' }, 429)
    }

    const supabase = ctx.get("supabase")
    const { limit: tagLimit } = ctx.req.valid('query')

    const tags = await getPopularTags(supabase, tagLimit)

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
