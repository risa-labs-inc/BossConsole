import type { SupabaseClient } from "@supabase/supabase-js"
import { z } from "zod"
import { ChallengeType } from "../types/challenge.ts"
import type { GatewayLane } from "./trusted-gateway.ts"

const AdmissionResponse = z.array(z.object({
  allowed: z.boolean(),
  retry_after_seconds: z.number().int().min(0).max(60),
  duplicate: z.boolean()
})).length(1)

type AdmissionResult = { success: true } | {
  success: false
  status: 403 | 429 | 503
  retryAfterSeconds: number
  error: string
}

/**
 * One database budget across Edge instances, before account lookup or
 * challenge generation.
 *
 * The lane comes only from the verified gateway assertion in the route
 * context, never from the request body or a public header. Registration
 * always uses the untrusted lane. The gateway request ID is recorded with
 * admission so a replayed assertion is refused without consuming another
 * slot or inserting a challenge.
 */
export async function admitChallenge(
  supabase: SupabaseClient,
  type: ChallengeType,
  lane: GatewayLane = "untrusted",
  requestId?: string,
): Promise<AdmissionResult> {
  try {
    const effectiveLane: GatewayLane = type === ChallengeType.Registration ? "untrusted" : lane
    const { data, error } = await supabase.rpc("admit_passkey_challenge", {
      p_type: type,
      p_lane: effectiveLane,
      ...(requestId ? { p_request_id: requestId } : {}),
    })
    if (error) throw error
    const parsed = AdmissionResponse.safeParse(data)
    if (!parsed.success) throw new Error("Invalid challenge admission response")
    const decision = parsed.data[0]
    if (!decision.allowed) {
      if (decision.duplicate) {
        return { success: false, status: 403, retryAfterSeconds: 0,
          error: "This admission grant was already used. Request a new challenge." }
      }
      return { success: false, status: 429, retryAfterSeconds: Math.max(1, decision.retry_after_seconds),
        error: "Challenge capacity is temporarily unavailable. Retry shortly." }
    }
    return { success: true }
  } catch {
    console.error("Challenge admission unavailable")
    return { success: false, status: 503, retryAfterSeconds: 30,
      error: "Challenge admission is temporarily unavailable. Retry shortly." }
  }
}
