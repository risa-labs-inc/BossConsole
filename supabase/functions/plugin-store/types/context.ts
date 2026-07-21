import type { SupabaseClient } from "@supabase/supabase-js"

/**
 * Context variables available in all plugin-store routes
 */
export interface PluginStoreContext {
  supabase: SupabaseClient
  userId?: string
}
