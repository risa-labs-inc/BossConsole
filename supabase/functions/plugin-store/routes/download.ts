import { createRoute, z } from "@hono/zod-openapi"
import {
  DownloadInfoResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"
import { getPlugin } from "../services/plugins.ts"
import { getLatestVersion, getVersion } from "../services/versions.ts"
import { getSignedDownloadUrl } from "../services/storage.ts"
import { recordDownload, hashIp } from "../services/downloads.ts"
// The gates live in services/install-gates.ts, shared with the signature route
// (BossConsole#108): both must deny identically, and a pasted copy is how a
// future route ends up gating differently. The wiring guards in
// tests/auth-permissions.test.ts still assert their call order HERE, in the
// handler bodies where the decisions are made.
import { installGateError, canInstall, gateSubject } from "../services/install-gates.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { isAllowedExternalJarUrl } from "../services/github.ts"
import { newRouter } from "../utils/router.ts"

const download = newRouter()

// Per-client limit on the public download-info routes, the same in-isolate
// token bucket as the catalogue routes in browse.ts. A separate key prefix
// means a burst of downloads cannot lock a client out of browsing, and vice
// versa; 60/min is generous for real installs and their version checks.
const DOWNLOAD_INFO_LIMIT = 60
const DOWNLOAD_INFO_WINDOW_SECONDS = 60

// ============================================================================
// GET /:pluginId/download - Download latest version
// ============================================================================

const downloadLatestRoute = createRoute({
  method: 'get',
  path: '/{pluginId}/download',
  tags: ['Download'],
  summary: 'Download latest plugin version',
  description: 'Get a signed download URL for the latest version of a plugin',
  request: {
    params: z.object({
      pluginId: z.string()
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
      description: 'Download URL generated successfully',
      content: {
        'application/json': {
          schema: DownloadInfoResponseSchema
        }
      }
    },
    403: {
      description: 'Caller lacks the permissions required to install this plugin',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    404: {
      description: 'Plugin or version not found',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    502: {
      description: 'Stored JAR URL is not from an allowed host',
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

download.openapi(downloadLatestRoute, async (ctx) => {
  try {
    // The brake before the work: these routes are unauthenticated and every
    // allowed request costs visibility RPCs and a signed URL.
    const limit = rateLimit(
      `download-info:${clientKey(ctx.req.raw.headers)}`,
      DOWNLOAD_INFO_LIMIT,
      DOWNLOAD_INFO_WINDOW_SECONDS,
    )
    if (!limit.allowed) {
      ctx.header("Retry-After", String(limit.retryAfterSeconds))
      return ctx.json({ error: 'Too many requests; try again later' }, 429)
    }

    const supabase = ctx.get("supabase")
    const { pluginId } = ctx.req.valid('param')

    // Get plugin
    const plugin = await getPlugin(supabase, pluginId)
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    const { userId: gateUserId, user } = await gateSubject(
      supabase,
      ctx.req.header('Authorization'),
      ctx.req.header('x-api-key') ?? ctx.req.header('X-API-Key'),
    )

    // Organisation visibility, BEFORE the permission gate and before any
    // download is recorded. 404 rather than 403, deliberately: a plugin the
    // caller may not see has to be indistinguishable from one that does not
    // exist, or this endpoint enumerates other organisations' private plugin
    // ids. The permission gate below can safely say 403, because by then the
    // caller is known to be allowed to see the plugin at all.
    if (!await canInstall(supabase, plugin.id, gateUserId)) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Install-permission gate: deny if this plugin requires permissions the
    // caller doesn't hold (admins bypass; empty requiredPermissions = open).
    const gateError = installGateError(plugin.requiredPermissions, user)
    if (gateError) {
      return ctx.json({ error: gateError }, 403)
    }

    // Get latest version
    const version = await getLatestVersion(supabase, plugin.id)
    if (!version) {
      return ctx.json({ error: 'No versions available' }, 404)
    }

    // Generate download URL — externally-hosted (GitHub) URLs are returned
    // directly, but only if they're on an allowed host; otherwise a corrupted
    // jar_path could redirect the client to an arbitrary origin.
    const isExternal = version.jarPath.startsWith('https://')
    if (isExternal && !isAllowedExternalJarUrl(version.jarPath)) {
      console.error(`Blocked external JAR URL from disallowed host: ${version.jarPath}`)
      return ctx.json({ error: 'Stored JAR URL is not from an allowed host' }, 502)
    }
    const downloadUrl = isExternal
      ? version.jarPath
      : await getSignedDownloadUrl(supabase, version.jarPath)

    // Track download (optional - don't fail if this errors)
    try {
      const ip = ctx.req.header('x-forwarded-for') || ctx.req.header('x-real-ip') || ''
      const ipHash = ip ? await hashIp(ip) : null

      await recordDownload(supabase, plugin.id, version.id, user?.userId || null, ipHash)
    } catch (e) {
      console.error('Error tracking download:', e)
      // Don't fail the request if tracking fails
    }

    return ctx.json({
      downloadUrl,
      sha256: version.sha256,
      signature: version.signature ?? null,
      version: version.version,
      size: version.jarSize,
      versionId: version.id,
      minIpcVersion: version.minIpcVersion,
      // The app-version floor, so a client can refuse a version its host cannot load BEFORE
      // downloading it over the installed jar. Every field above was already here; this one was
      // not, which left the Toolbox with `min_boss_version` on browse rows and nothing at all on
      // the path that actually installs. Clients default it to blank and treat blank as "no
      // opinion", so an older Toolbox is unaffected by its arrival.
      // `?? ''` because `plugin_versions.min_boss_version` is nullable (TEXT DEFAULT '1.0.0', no
      // NOT NULL), and an explicit null in a field a client models as a string fails the whole
      // decode rather than one field - which on a strict client would turn a null floor into a
      // failed install.
      minBossVersion: version.minBossVersion ?? '',
      requiredPermissions: plugin.requiredPermissions
    }, 200)
  } catch (error) {
    console.error('Error generating download URL:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

// ============================================================================
// GET /:pluginId/download/:version - Download specific version
// ============================================================================

const downloadVersionRoute = createRoute({
  method: 'get',
  path: '/{pluginId}/download/{version}',
  tags: ['Download'],
  summary: 'Download specific plugin version',
  description: 'Get a signed download URL for a specific version of a plugin',
  request: {
    params: z.object({
      pluginId: z.string(),
      version: z.string()
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
      description: 'Download URL generated successfully',
      content: {
        'application/json': {
          schema: DownloadInfoResponseSchema
        }
      }
    },
    403: {
      description: 'Caller lacks the permissions required to install this plugin',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    404: {
      description: 'Plugin or version not found',
      content: {
        'application/json': {
          schema: ErrorResponseSchema
        }
      }
    },
    502: {
      description: 'Stored JAR URL is not from an allowed host',
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

download.openapi(downloadVersionRoute, async (ctx) => {
  try {
    const limit = rateLimit(
      `download-info:${clientKey(ctx.req.raw.headers)}`,
      DOWNLOAD_INFO_LIMIT,
      DOWNLOAD_INFO_WINDOW_SECONDS,
    )
    if (!limit.allowed) {
      ctx.header("Retry-After", String(limit.retryAfterSeconds))
      return ctx.json({ error: 'Too many requests; try again later' }, 429)
    }

    const supabase = ctx.get("supabase")
    const { pluginId, version: versionStr } = ctx.req.valid('param')

    // Get plugin
    const plugin = await getPlugin(supabase, pluginId)
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    const { userId: gateUserId, user } = await gateSubject(
      supabase,
      ctx.req.header('Authorization'),
      ctx.req.header('x-api-key') ?? ctx.req.header('X-API-Key'),
    )

    // Organisation visibility, BEFORE the permission gate and before any
    // download is recorded. 404 rather than 403, deliberately: a plugin the
    // caller may not see has to be indistinguishable from one that does not
    // exist, or this endpoint enumerates other organisations' private plugin
    // ids. The permission gate below can safely say 403, because by then the
    // caller is known to be allowed to see the plugin at all.
    if (!await canInstall(supabase, plugin.id, gateUserId)) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Install-permission gate (admins bypass; empty requiredPermissions = open).
    const gateError = installGateError(plugin.requiredPermissions, user)
    if (gateError) {
      return ctx.json({ error: gateError }, 403)
    }

    // Get specific version
    const version = await getVersion(supabase, plugin.id, versionStr)
    if (!version) {
      return ctx.json({ error: 'Version not found' }, 404)
    }

    // Generate download URL — externally-hosted (GitHub) URLs are returned
    // directly, but only if they're on an allowed host.
    const isExternal = version.jarPath.startsWith('https://')
    if (isExternal && !isAllowedExternalJarUrl(version.jarPath)) {
      console.error(`Blocked external JAR URL from disallowed host: ${version.jarPath}`)
      return ctx.json({ error: 'Stored JAR URL is not from an allowed host' }, 502)
    }
    const downloadUrl = isExternal
      ? version.jarPath
      : await getSignedDownloadUrl(supabase, version.jarPath)

    // Track download
    try {
      const ip = ctx.req.header('x-forwarded-for') || ctx.req.header('x-real-ip') || ''
      const ipHash = ip ? await hashIp(ip) : null

      await recordDownload(supabase, plugin.id, version.id, user?.userId || null, ipHash)
    } catch (e) {
      console.error('Error tracking download:', e)
    }

    return ctx.json({
      downloadUrl,
      sha256: version.sha256,
      signature: version.signature ?? null,
      version: version.version,
      size: version.jarSize,
      versionId: version.id,
      minIpcVersion: version.minIpcVersion,
      // The app-version floor, so a client can refuse a version its host cannot load BEFORE
      // downloading it over the installed jar. Every field above was already here; this one was
      // not, which left the Toolbox with `min_boss_version` on browse rows and nothing at all on
      // the path that actually installs. Clients default it to blank and treat blank as "no
      // opinion", so an older Toolbox is unaffected by its arrival.
      // `?? ''` because `plugin_versions.min_boss_version` is nullable (TEXT DEFAULT '1.0.0', no
      // NOT NULL), and an explicit null in a field a client models as a string fails the whole
      // decode rather than one field - which on a strict client would turn a null floor into a
      // failed install.
      minBossVersion: version.minBossVersion ?? '',
      requiredPermissions: plugin.requiredPermissions
    }, 200)
  } catch (error) {
    console.error('Error generating download URL:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

export default download
