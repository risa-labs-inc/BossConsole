import { timingSafeEqual } from "node:crypto"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import { cleanupExpiredChallenges } from "../utils/challenge.ts"
import { limitPasskeyRequest } from "../utils/request-limits.ts"

const maintenance = new OpenAPIHono<{ Variables: PasskeyContext }>()

maintenance.use("*", limitPasskeyRequest)

// The scheduler receives a dedicated random bearer, never the public anonymous API key.
// The presented token is compared by fixed-size digest, not bytewise: a direct
// comparison would reveal the configured token's length in the response latency
// (and a byteLength pre-check would reveal it in the response itself).
async function tokenDigest(value: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)))
}

maintenance.post('/cleanup', async (ctx) => {
  const expected = Deno.env.get('PASSKEY_MAINTENANCE_TOKEN')
  if (!expected || expected.length < 32 || expected.length > 256 || /\s/.test(expected)) {
    return ctx.json({ error: 'Maintenance is not configured' }, 503)
  }
  const presented = ctx.req.header('Authorization')?.match(/^Bearer (\S{32,256})$/)?.[1]
  if (!presented) {
    return ctx.json({ error: 'Scheduler authentication required' }, 401)
  }
  const [expectedDigest, presentedDigest] = await Promise.all([tokenDigest(expected), tokenDigest(presented)])
  if (!timingSafeEqual(expectedDigest, presentedDigest)) {
    return ctx.json({ error: 'Scheduler authentication required' }, 401)
  }
  const result = await cleanupExpiredChallenges(ctx.get('supabase'))
  return result.success
    ? ctx.json({ message: 'Cleanup completed successfully' }, 200)
    : ctx.json({ error: 'Cleanup failed' }, 500)
})

export default maintenance
