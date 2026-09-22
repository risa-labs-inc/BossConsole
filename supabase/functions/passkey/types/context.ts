import type { SupabaseClient } from "@supabase/supabase-js"
import type { GatewayLane } from "../utils/trusted-gateway.ts"

/**
 * Context variables available in all passkey routes.
 *
 * gatewayLane and gatewayRequestId are set only by the trusted gateway
 * admission middleware on POST /auth/challenge. Handlers must read them
 * from the context; the request body never carries them.
 */
export type PasskeyContext = {
  supabase: SupabaseClient
  gatewayLane?: GatewayLane
  gatewayRequestId?: string
}
