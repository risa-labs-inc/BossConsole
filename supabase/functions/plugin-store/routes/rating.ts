import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PluginStoreContext } from "../types/context.ts"
import {
  RatePluginRequestSchema,
  RatePluginResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"
import { getPlugin } from "../services/plugins.ts"
import { ratePlugin, getUserRating, deleteRating, getPluginRatings } from "../services/ratings.ts"
import { getUserFromToken } from "../utils/auth.ts"

const rating = new OpenAPIHono<{ Variables: PluginStoreContext }>()

// ============================================================================
// POST /:pluginId/rate - Rate a plugin
// ============================================================================

const rateRoute = createRoute({
  method: 'post',
  path: '/{pluginId}/rate',
  tags: ['Rating'],
  summary: 'Rate a plugin',
  description: 'Create or update your rating for a plugin. Requires authentication.',
  request: {
    params: z.object({
      pluginId: z.string()
    }),
    body: {
      content: {
        'application/json': {
          schema: RatePluginRequestSchema
        }
      }
    }
  },
  responses: {
    200: {
      description: 'Rating submitted successfully',
      content: {
        'application/json': {
          schema: RatePluginResponseSchema
        }
      }
    },
    401: {
      description: 'Authentication required',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
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

rating.openapi(rateRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { pluginId } = ctx.req.valid('param')
    const body = ctx.req.valid('json')

    // Verify authentication
    const authHeader = ctx.req.header('Authorization')
    const user = await getUserFromToken(supabase, authHeader)
    
    if (!user) {
      return ctx.json({ error: 'Authentication required' }, 401)
    }

    // Get plugin
    const plugin = await getPlugin(supabase, pluginId)
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Rate the plugin
    const result = await ratePlugin(supabase, plugin.id, user.userId, body.rating, body.review)

    return ctx.json({
      success: true,
      ratingId: result.ratingId,
      created: result.created
    }, 200)
  } catch (error) {
    console.error('Error rating plugin:', error)
    return ctx.json({ 
      success: false, 
      error: 'Internal server error'
    }, 500)
  }
})

// ============================================================================
// GET /:pluginId/rating - Get user's rating for a plugin
// ============================================================================

const getUserRatingRoute = createRoute({
  method: 'get',
  path: '/{pluginId}/rating',
  tags: ['Rating'],
  summary: 'Get your rating for a plugin',
  description: 'Get the current user\'s rating for a plugin. Requires authentication.',
  request: {
    params: z.object({
      pluginId: z.string()
    })
  },
  responses: {
    200: {
      description: 'Rating retrieved successfully',
      content: {
        'application/json': {
          schema: z.object({
            rating: z.number().nullable(),
            review: z.string().nullable()
          })
        }
      }
    },
    401: {
      description: 'Authentication required',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
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

rating.openapi(getUserRatingRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { pluginId } = ctx.req.valid('param')

    // Verify authentication
    const authHeader = ctx.req.header('Authorization')
    const user = await getUserFromToken(supabase, authHeader)
    
    if (!user) {
      return ctx.json({ error: 'Authentication required' }, 401)
    }

    // Get plugin
    const plugin = await getPlugin(supabase, pluginId)
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Get user's rating
    const userRating = await getUserRating(supabase, plugin.id, user.userId)

    return ctx.json({
      rating: userRating?.rating || null,
      review: userRating?.review || null
    }, 200)
  } catch (error) {
    console.error('Error getting user rating:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// DELETE /:pluginId/rating - Delete user's rating
// ============================================================================

const deleteRatingRoute = createRoute({
  method: 'delete',
  path: '/{pluginId}/rating',
  tags: ['Rating'],
  summary: 'Delete your rating for a plugin',
  description: 'Remove your rating for a plugin. Requires authentication.',
  request: {
    params: z.object({
      pluginId: z.string()
    })
  },
  responses: {
    200: {
      description: 'Rating deleted successfully',
      content: {
        'application/json': {
          schema: z.object({
            success: z.boolean()
          })
        }
      }
    },
    401: {
      description: 'Authentication required',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
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

rating.openapi(deleteRatingRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { pluginId } = ctx.req.valid('param')

    // Verify authentication
    const authHeader = ctx.req.header('Authorization')
    const user = await getUserFromToken(supabase, authHeader)
    
    if (!user) {
      return ctx.json({ error: 'Authentication required' }, 401)
    }

    // Get plugin
    const plugin = await getPlugin(supabase, pluginId)
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Delete rating
    await deleteRating(supabase, plugin.id, user.userId)

    return ctx.json({ success: true }, 200)
  } catch (error) {
    console.error('Error deleting rating:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// GET /:pluginId/ratings - Get all ratings for a plugin
// ============================================================================

// Mirrors the /search route's cap (SearchPluginsRequestSchema.pageSize). Unbounded, a single
// request could ask PostgREST for a massive range - defeating the cap this repo already
// established as correct for catalogue routes - and every row returned carries a raw
// auth.users UUID, so a large page also scales how many rater identities one call exposes.
const RATINGS_PAGE_SIZE_MAX = 100

// Ratings paginate a single plugin's own review list, not the whole catalogue - there is no
// legitimate reason to ask for a page far past any real plugin's rating count. Bounding it closes
// the "page=1e9" deep-offset scan the pageSize cap alone does not: 100,000 pages at the max page
// size is 10,000,000 rows, already far beyond anything a real plugin will ever accumulate.
const RATINGS_PAGE_MAX = 100_000

const getPluginRatingsRoute = createRoute({
  method: 'get',
  path: '/{pluginId}/ratings',
  tags: ['Rating'],
  summary: 'Get all ratings for a plugin',
  description: 'Get paginated list of all ratings for a plugin',
  request: {
    params: z.object({
      pluginId: z.string()
    }),
    query: z.object({
      page: z.string().optional().default('1'),
      pageSize: z.string().optional().default('20')
    })
  },
  responses: {
    200: {
      description: 'Ratings retrieved successfully',
      content: {
        'application/json': {
          schema: z.object({
            ratings: z.array(z.object({
              userId: z.string(),
              rating: z.number(),
              review: z.string(),
              createdAt: z.string()
            })),
            totalCount: z.number(),
            page: z.number(),
            pageSize: z.number()
          })
        }
      }
    },
    400: {
      description: 'Invalid page or pageSize',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
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

rating.openapi(getPluginRatingsRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { pluginId } = ctx.req.valid('param')
    const { page: rawPage, pageSize: rawPageSize } = ctx.req.valid('query')

    // Parsed and bounds-checked here, not via a schema `.transform(Number)`, so an out-of-range
    // or non-integer value gets this route's own fixed error envelope rather than whatever a
    // library-default validation-failure response happens to shape itself as.
    const page = Number(rawPage)
    const pageSize = Number(rawPageSize)
    const pageValid = Number.isInteger(page) && page >= 1 && page <= RATINGS_PAGE_MAX
    const pageSizeValid = Number.isInteger(pageSize) && pageSize >= 1 && pageSize <= RATINGS_PAGE_SIZE_MAX
    if (!pageValid || !pageSizeValid) {
      return ctx.json({ error: 'page must be an integer from 1 to 100000, pageSize from 1 to 100' }, 400)
    }

    // Get plugin
    const plugin = await getPlugin(supabase, pluginId)
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Get ratings
    const result = await getPluginRatings(supabase, plugin.id, page, pageSize)

    return ctx.json({
      ratings: result.ratings,
      totalCount: result.totalCount,
      page,
      pageSize
    }, 200)
  } catch (error) {
    console.error('Error getting plugin ratings:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

export default rating
