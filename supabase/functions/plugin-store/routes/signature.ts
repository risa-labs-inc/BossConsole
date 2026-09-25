import { createRoute, z } from "@hono/zod-openapi"
import {
  SignatureInfoResponseSchema,
  ErrorResponseSchema
} from "../types/schemas.ts"
import { getPlugin } from "../services/plugins.ts"
import { getVersion } from "../services/versions.ts"
// The same gates, in the same order, as the download handlers: this route
// reveals the same per-version facts (sha256 + signature), so a caller the
// download route would deny must be denied here too, and by the same 404.
import { installGateError, canInstall, gateSubject } from "../services/install-gates.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { newRouter } from "../utils/router.ts"

/**
 * Signature-only lookup for an already-held JAR (BossConsole#108).
 *
 * The host's sidecar backfill already has the bytes — from GitHub releases, a
 * prior install, or a bundle copy — and needs exactly one thing from the store:
 * its verdict on those bytes, so it can bind a signature sidecar or settle for
 * "unsigned". Before this route existed the only endpoint carrying that verdict
 * was the download endpoint, whose contract includes `recordDownload`, so every
 * backfill attempt booked a plugin_downloads row — unbounded for retryable
 * answers like a row published before store signing — and minted a signed
 * storage URL nobody ever used.
 *
 * This handler therefore MUST NOT call recordDownload and MUST NOT mint a
 * signed download URL; tests/signature-route.test.ts pins both absences. It
 * also cannot 403 before the visibility gate passes, for the same
 * enumeration-safety reason as the download handlers, which the same test
 * file asserts. Like the download-info handlers it opens with a per-client
 * rate limit — same brake, its own bucket — because it is unauthenticated-
 * reachable and every allowed request costs visibility RPCs and a version
 * lookup.
 */
const signature = newRouter()

// Per-client limit, the same in-isolate token bucket as the download-info
// routes in download.ts. A separate key prefix means a burst of signature
// probes cannot lock a client out of downloads or browsing, and vice versa;
// 60/min is generous for a lookup that runs once per plugin per launch.
const SIGNATURE_INFO_LIMIT = 60
const SIGNATURE_INFO_WINDOW_SECONDS = 60

// ============================================================================
// GET /:pluginId/signature/:version - Store verdict on a held JAR's bytes
// ============================================================================

const signatureVersionRoute = createRoute({
  method: 'get',
  path: '/{pluginId}/signature/{version}',
  tags: ['Signature'],
  summary: 'Get the store signature for a specific version',
  description:
    'Returns the sha256 and signature the store holds for a version, WITHOUT recording a download. ' +
    'For hosts that already hold the JAR (system-plugin sidecar backfill) and only need the store verdict on those bytes.',
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
      description: 'The sha256 and signature the store holds for this version',
      content: {
        'application/json': {
          schema: SignatureInfoResponseSchema
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

signature.openapi(signatureVersionRoute, async (ctx) => {
  try {
    // The brake before the work, identically to the download handlers: this
    // route is unauthenticated-reachable on the same surface and every
    // allowed request costs visibility RPCs and a version lookup.
    const limit = rateLimit(
      `signature-info:${clientKey(ctx.req.raw.headers)}`,
      SIGNATURE_INFO_LIMIT,
      SIGNATURE_INFO_WINDOW_SECONDS,
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
    // version facts leave the building. 404 rather than 403, deliberately and
    // identically to the download handlers: a plugin the caller may not see has
    // to be indistinguishable from one that does not exist, or this endpoint
    // enumerates other organisations' private plugin ids. The permission gate
    // below can safely say 403, because by then the caller is known to be
    // allowed to see the plugin at all.
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

    // The verdict, and nothing else: no recordDownload, no signed URL. The
    // caller decides what to do with the answer; the host compares sha256
    // against its own bytes before binding anything (a mismatch means the
    // GitHub asset and the store artifact genuinely differ, and the JAR stays
    // unsigned rather than getting a present-but-invalid sidecar).
    return ctx.json({
      pluginId: plugin.pluginId,
      version: version.version,
      sha256: version.sha256,
      signature: version.signature ?? null,
      versionId: version.id
    }, 200)
  } catch (error) {
    console.error('Error resolving plugin signature:', error)
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

export default signature
