import type { SupabaseClient } from "@supabase/supabase-js"
import { getUserFromToken, validateApiKey } from "../utils/auth.ts"

/**
 * The install gates every route that serves per-version store artifacts must
 * pass callers through, in this order: organisation visibility, then install
 * permissions, then the artifact data itself.
 *
 * Extracted from routes/download.ts when the signature route (BossConsole#108)
 * became the second consumer: the two routes must deny identically, and a
 * pasted-almost-copy is how a future third route ends up gating differently.
 * The wiring guards in tests/auth-permissions.test.ts and
 * tests/signature-route.test.ts each assert the gate order in their own file —
 * a cross-file comparison would be brittle, so what keeps the two orders in
 * step is this shared module, not a test that compares them — and moving a
 * gate out of order in either file fails its own guard even though no
 * behavioural test can reach this without a live database.
 */

/**
 * Install-permission gate. A plugin's `requiredPermissions` lists the effective
 * permissions a user must hold to install/use it (the same list the host uses to
 * gate visibility after install). Empty ⇒ open to all (the `user.read` baseline).
 * Admins bypass. Returns a human-readable error string to deny with (403), or
 * null if the caller is allowed.
 */
export function installGateError(
  required: string[] | undefined,
  user: { isAdmin: boolean, permissions: string[] } | null
): string | null {
  if (!required || required.length === 0) return null // open (legacy / baseline)
  if (user?.isAdmin) return null
  const held = new Set(user?.permissions ?? [])
  const missing = required.filter(p => !held.has(p))
  if (missing.length === 0) return null
  return `This plugin requires permission(s): ${missing.join(', ')}. Ask an admin to grant them.`
}

/**
 * Organisation-visibility gate.
 *
 * Every plugin is owned by an organisation and carries a visibility
 * (`public` / `org` / `unlisted`). The store's LISTING paths are already gated
 * by `user_can_view_plugin_row`, so a private organisation's plugins do not
 * appear in search -- but a download URL is guessable from a plugin id, and
 * without this the listing gate was decoration: anyone who learned an id could
 * fetch the jar.
 *
 * `user_can_install_plugin`, not `user_can_view_plugin`: `unlisted` means
 * "absent from listings", NOT "un-installable", so the install predicate is
 * wider by exactly that case. See 20260805000000.
 *
 * FAILS CLOSED. A transport error, a missing function or any non-`true` answer
 * denies. The cost of a false deny is a retry; the cost of a false allow is
 * handing out another organisation's private plugin.
 *
 * Returns true when the caller may download.
 */
export async function canInstall(
  supabase: SupabaseClient,
  pluginRowId: string,
  userId: string | null
): Promise<boolean> {
  const { data, error } = await supabase.rpc('user_can_install_plugin', {
    p_user_id: userId,
    p_plugin_id: pluginRowId
  })
  if (error) {
    console.error('user_can_install_plugin failed:', error.message)
    return false
  }
  return data === true
}

/**
 * The caller's user id for the visibility gate, from a JWT or a plugin API key.
 *
 * `getUserFromToken` resolves user JWTs only, so an API-key caller - CI, the publish tooling -
 * resolved to anonymous. Harmless while every plugin is public+published, because
 * user_can_view_plugin_row short-circuits that case for a NULL subject. The first `org` or
 * `unlisted` plugin would have 404'd for them, and the 404 is deliberately indistinguishable
 * from "no such plugin", so it would have been painful to diagnose from outside.
 *
 * Returns null for an anonymous caller, which is correct and still reaches public plugins.
 */
export async function gateSubject(
  supabase: SupabaseClient,
  authHeader: string | undefined,
  apiKeyHeader: string | undefined,
): Promise<{ userId: string | null; user: Awaited<ReturnType<typeof getUserFromToken>> }> {
  const user = await getUserFromToken(supabase, authHeader)
  if (user) return { userId: user.userId, user }

  const viaKey = await validateApiKey(supabase, apiKeyHeader)
  return { userId: viaKey?.userId ?? null, user: null }
}
