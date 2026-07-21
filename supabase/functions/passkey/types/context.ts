import type { SupabaseClient } from "@supabase/supabase-js"

/**
 * Context variables available in all passkey routes
 */
export type PasskeyContext = {
  supabase: SupabaseClient
}
