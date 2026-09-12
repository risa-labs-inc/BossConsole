import type { SupabaseClient } from "@supabase/supabase-js"
import { z } from "zod"
import type { ChallengeType } from "../types/challenge.ts"

const AdmissionResponse = z.array(z.object({
  allowed: z.boolean(),
  retry_after_seconds: z.number().int().min(0).max(60)
})).length(1)

type AdmissionResult = { success: true } | {
  success: false
  status: 429 | 503
  retryAfterSeconds: number
  error: string
}

/** One database budget across Edge instances, before account lookup or challenge generation. */
export async function admitChallenge(supabase: SupabaseClient, type: ChallengeType): Promise<AdmissionResult> {
  try {
    const { data, error } = await supabase.rpc("admit_passkey_challenge", { p_type: type })
    if (error) throw error
    const parsed = AdmissionResponse.safeParse(data)
    if (!parsed.success) throw new Error("Invalid challenge admission response")
    const decision = parsed.data[0]
    if (!decision.allowed) {
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
