import { createClient } from "@supabase/supabase-js"
import type { SupabaseClient } from "@supabase/supabase-js"

/**
 * Service-role client for the passkey function.
 *
 * Created lazily so tests can substitute a stub via setPasskeyClientForTests
 * (the organisation function's pattern); in production the client is built
 * once, on the first request, from the same environment variables.
 */
let productionClient: SupabaseClient | null = null
let testClient: SupabaseClient | null = null

export function passkeyClient(): SupabaseClient {
  if (testClient) return testClient
  if (!productionClient) {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") || ""
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || ""
    productionClient = createClient(supabaseUrl, supabaseServiceKey)
  }
  return productionClient
}

export function setPasskeyClientForTests(client: SupabaseClient | null): void {
  testClient = client
}

/**
 * Drops both the test stub and any cached production client, so a test that
 * depends on the lazy first-creation path (e.g. throwing from Deno.env.get)
 * does not depend on module-instance ordering within the shared deno test
 * process.
 */
export function resetPasskeyClientForTests(): void {
  testClient = null
  productionClient = null
}
