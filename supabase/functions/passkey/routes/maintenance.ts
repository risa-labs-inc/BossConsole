import { timingSafeEqual } from "node:crypto"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import { cleanupExpiredChallenges } from "../utils/challenge.ts"

const maintenance = new OpenAPIHono<{ Variables: PasskeyContext }>()

// The scheduler receives a dedicated random bearer, never the public anonymous API key.
maintenance.post('/cleanup', async (ctx) => {
  const expected = Deno.env.get('PASSKEY_MAINTENANCE_TOKEN')
  if (!expected || expected.length < 32 || expected.length > 256 || /\s/.test(expected)) {
    return ctx.json({ error: 'Maintenance is not configured' }, 503)
  }
  const presented = ctx.req.header('Authorization')?.match(/^Bearer (\S{32,256})$/)?.[1]
  const encoder = new TextEncoder()
  const trusted = encoder.encode(expected)
  const candidate = encoder.encode(presented ?? '')
  if (trusted.byteLength !== candidate.byteLength || !timingSafeEqual(trusted, candidate)) {
    return ctx.json({ error: 'Scheduler authentication required' }, 401)
  }
  const result = await cleanupExpiredChallenges(ctx.get('supabase'))
  return result.success
    ? ctx.json({ message: 'Cleanup completed successfully' }, 200)
    : ctx.json({ error: 'Cleanup failed' }, 500)
})

export default maintenance
