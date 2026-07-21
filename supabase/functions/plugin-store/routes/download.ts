import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PluginStoreContext } from "../types/context.ts"
import {
  DownloadInfoResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"
import { getPlugin, getPluginById } from "../services/plugins.ts"
import { getLatestVersion, getVersion } from "../services/versions.ts"
import { getSignedDownloadUrl } from "../services/storage.ts"
import { recordDownload, hashIp } from "../services/downloads.ts"
import { getUserFromToken } from "../utils/auth.ts"
import { isAllowedExternalJarUrl } from "../services/github.ts"

const download = new OpenAPIHono<{ Variables: PluginStoreContext }>()

/**
 * Install-permission gate. A plugin's `requiredPermissions` lists the effective
 * permissions a user must hold to install/use it (the same list the host uses to
 * gate visibility after install). Empty ⇒ open to all (the `user.read` baseline).
 * Admins bypass. Returns a human-readable error string to deny with (403), or
 * null if the caller is allowed.
 */
function installGateError(
  required: string[] | undefined,
  user: { isAdmin: boolean, permissions: string[] } | null
): string | null {
  if (!required || required.length === 0) return null // open (legacy / baseline)
  if (user?.isAdmin) return null
  const held = new Set(user?.permissions ?? [])
  const missing = required.filter(p => !held.has(p))
  if (missing.length === 0) return null
  return `This plugin requires permission(s): ${missing.join(', ')}. Ask an admin to grant them.`
}

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
    const supabase = ctx.get("supabase")
    const { pluginId } = ctx.req.valid('param')

    // Get plugin
    const plugin = await getPlugin(supabase, pluginId)
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Install-permission gate: deny if this plugin requires permissions the
    // caller doesn't hold (admins bypass; empty requiredPermissions = open).
    const user = await getUserFromToken(supabase, ctx.req.header('Authorization'))
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
      requiredPermissions: plugin.requiredPermissions
    }, 200)
  } catch (error) {
    console.error('Error generating download URL:', error)
    return ctx.json({ error: (error as Error).message }, 500)
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
    const supabase = ctx.get("supabase")
    const { pluginId, version: versionStr } = ctx.req.valid('param')

    // Get plugin
    const plugin = await getPlugin(supabase, pluginId)
    if (!plugin) {
      return ctx.json({ error: 'Plugin not found' }, 404)
    }

    // Install-permission gate (admins bypass; empty requiredPermissions = open).
    const user = await getUserFromToken(supabase, ctx.req.header('Authorization'))
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
      requiredPermissions: plugin.requiredPermissions
    }, 200)
  } catch (error) {
    console.error('Error generating download URL:', error)
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

export default download
