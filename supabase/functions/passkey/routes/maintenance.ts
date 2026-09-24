/**
 * Maintenance endpoints for the passkey function.
 *
 * The cleanup route deletes expired challenge rows with a full-table DELETE
 * scan. The function is deployed with `verify_jwt = false` (by design: most of
 * its endpoints are reached before the caller has a session), so until now that
 * database write was reachable by ANY caller holding nothing but the project's
 * public anon key - an unauthenticated, unrate-limited scan-and-delete
 * primitive. There are no legitimate callers in the tree (a future scheduled
 * job invokes it with the service-role key), so the endpoint now accepts
 * exactly that key and fails closed when it is absent or misconfigured.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import { cleanupExpiredChallenges } from "../utils/challenge.ts"
import { extractBearerToken } from "../utils/authorization.ts"

const maintenance = new OpenAPIHono<{ Variables: PasskeyContext }>()

/**
 * True when the bearer is this deployment's service-role key.
 *
 * A missing SUPABASE_SERVICE_ROLE_KEY fails closed: every request is rejected,
 * which is the correct state for "this deployment has no service key
 * configured" rather than the reverse.
 */
function isServiceRoleCaller(authorizationHeader: string | null | undefined): boolean {
  const token = extractBearerToken(authorizationHeader)
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
  return !!token && !!serviceKey && token === serviceKey
}

// ============================================================================
// POST /maintenance/cleanup - Delete expired challenge rows (scheduled jobs)
// ============================================================================

maintenance.post("/cleanup", async (ctx) => {
  if (!isServiceRoleCaller(ctx.req.header('Authorization'))) {
    // Deliberately non-specific: no hint about which credential would work.
    return ctx.json({ error: 'Authentication required' }, 401)
  }

  const supabase = ctx.get("supabase")
  const result = await cleanupExpiredChallenges(supabase)

  if (result.success) {
    return ctx.json({ message: 'Cleanup completed successfully' }, 200)
  } else {
    return ctx.json({ error: 'Internal server error' }, 500)
  }
})

export default maintenance
