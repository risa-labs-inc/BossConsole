import type { SupabaseClient } from "@supabase/supabase-js"

/**
 * Resolve an optional browser session without turning browsing into a required
 * authentication step. API keys are deliberately not accepted here: they are
 * publishing credentials, not catalogue-reader credentials.
 */
export async function getOptionalViewer(
  supabase: SupabaseClient,
  authHeader: string | undefined
): Promise<string | null> {
  if (!authHeader || !authHeader.toLowerCase().startsWith("bearer ")) return null

  const token = authHeader.slice(7).trim()
  if (token.length === 0) return null

  try {
    const { data, error } = await supabase.auth.getUser(token)
    if (error) return null
    return data?.user?.id ?? null
  } catch {
    return null
  }
}
