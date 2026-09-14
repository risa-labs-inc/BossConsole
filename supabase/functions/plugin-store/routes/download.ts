import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PluginStoreContext } from "../types/context.ts"
import {
  DownloadInfoResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"
import { getPluginForDownload } from "../services/plugins.ts"
import { getLatestVersion, getVersion } from "../services/versions.ts"
import { getSignedDownloadUrl } from "../services/storage.ts"
import { recordDownload, hashIp } from "../services/downloads.ts"
import { getUserFromToken, validateApiKey } from "../utils/auth.ts"
import { isAllowedExternalJarUrl } from "../services/github.ts"
import type { SupabaseClient } from "@supabase/supabase-js"

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

/**
 * The caller's user id for the visibility gate, from a JWT or a plugin API key.
 *
 * `getUserFromToken` resolves user JWTs only, so an API-key caller - CI, the publish tooling -
 * resolved to anonymous. Harmless while every plugin is public+published, because
 * user_can_view_plugin_row short-circuits that case for a NULL subject. The first `org` or
 * `unlisted` plugin would have 404'd for them, and the 404 is deliberately indistinguishable
 * from "no such plugin", so it would have been painful to diagnose from outside.
 *
 * Returns null for an anonymous caller, which is correct and still reaches public plugins.
 */
async function gateSubject(
  supabase: SupabaseClient,
  authHeader: string | undefined,
  apiKeyHeader: string | undefined,
): Promise<{ userId: string | null; user: Awaited<ReturnType<typeof getUserFromToken>> }> {
  const user = await getUserFromToken(supabase, authHeader)
  if (user) return { userId: user.userId, user }

  const viaKey = await validateApiKey(supabase, apiKeyHeader)
  return { userId: viaKey?.userId ?? null, user: null }
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

    const { userId: gateUserId, user } = await gateSubject(
      supabase,
      ctx.req.header('Authorization'),
      ctx.req.header('x-api-key') ?? ctx.req.header('X-API-Key'),
    )

    // The install-info RPC performs user_can_install_plugin and returns only
    // the fields this route needs. It is intentionally wider than the listing
    // RPC for the unlisted case: an ordinary organisation member may install
    // from a direct link without being able to enumerate the plugin.
    // Unauthorized and nonexistent plugins are both 404, so this remains
    // indistinguishable and the permission gate cannot become an oracle.
    const plugin = await getPluginForDownload(supabase, pluginId, gateUserId)
    if (!plugin) {
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

    const { userId: gateUserId, user } = await gateSubject(
      supabase,
      ctx.req.header('Authorization'),
      ctx.req.header('x-api-key') ?? ctx.req.header('X-API-Key'),
    )

    // Keep the same installability gate as the latest-version route, including
    // direct-link installs of unlisted plugins by organisation members.
    const plugin = await getPluginForDownload(supabase, pluginId, gateUserId)
    if (!plugin) {
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
    return ctx.json({ error: (error as Error).message }, 500)
  }
})

export default download
