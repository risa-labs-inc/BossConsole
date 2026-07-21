import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginManifest } from "../types/plugin.ts"

/**
 * Validate a publishing plugin's declared permissions.
 *
 * Rejects a "dangling" requirement: a `requiredPermissions` entry that is neither
 * an existing catalog permission nor declared in this plugin's `definedPermissions`
 * — i.e. a permission nobody provides, which would leave the plugin invisible to
 * every non-admin with no way to grant it.
 *
 * Returns { ok: true } on success, or { ok: false, error } to reject the publish.
 * Fails open on an unexpected DB error (logs + allows) so registration issues
 * never hard-block publishing.
 */
export async function validateDeclaredPermissions(
  supabase: SupabaseClient,
  manifest: Pick<PluginManifest, "pluginId" | "requiredPermissions" | "definedPermissions">,
): Promise<{ ok: true } | { ok: false; error: string }> {
  const required = manifest.requiredPermissions ?? []
  if (required.length === 0) return { ok: true }

  const definedNames = new Set((manifest.definedPermissions ?? []).map((p) => p.name))

  try {
    const { data, error } = await supabase
      .from("permissions")
      .select("name")
      .in("name", required)
    if (error) {
      console.warn(`validateDeclaredPermissions: catalog lookup failed, allowing publish: ${error.message}`)
      return { ok: true }
    }
    const catalog = new Set((data ?? []).map((r: { name: string }) => r.name))
    const missing = required.filter((name) => !catalog.has(name) && !definedNames.has(name))
    if (missing.length > 0) {
      return {
        ok: false,
        error:
          `Dangling required permission(s): ${missing.join(", ")}. ` +
          `Declare them in the plugin manifest's "definedPermissions", or reference an existing permission.`,
      }
    }
    return { ok: true }
  } catch (e) {
    console.warn(`validateDeclaredPermissions: unexpected error, allowing publish: ${(e as Error).message}`)
    return { ok: true }
  }
}

/**
 * Auto-register the permissions a plugin introduces (`definedPermissions`) into
 * the RBAC catalog as non-system, UNGRANTED entries, via the
 * register_plugin_permission() RPC (which enforces namespacing, reserved-domain
 * blocking, and the never-grant / never-shadow-system-permission rules).
 *
 * Best-effort: a single bad entry is logged and skipped — it never fails the
 * publish (the plugin is already published; an admin grants perms separately).
 * Returns the names that were registered or already present.
 */
export async function registerDefinedPermissions(
  supabase: SupabaseClient,
  manifest: Pick<PluginManifest, "pluginId" | "requiredPermissions" | "definedPermissions">,
): Promise<string[]> {
  const defined = manifest.definedPermissions ?? []
  if (defined.length === 0) return []

  const registered: string[] = []
  for (const perm of defined) {
    try {
      const { data, error } = await supabase.rpc("register_plugin_permission", {
        p_name: perm.name,
        p_description: perm.description ?? null,
        p_plugin_id: manifest.pluginId,
      })
      if (error) {
        console.warn(`register_plugin_permission(${perm.name}) RPC error: ${error.message}`)
        continue
      }
      if (data?.success) {
        registered.push(perm.name)
      } else {
        console.warn(`register_plugin_permission(${perm.name}) rejected: ${data?.error ?? "unknown"}`)
      }
    } catch (e) {
      console.warn(`register_plugin_permission(${perm.name}) threw: ${(e as Error).message}`)
    }
  }
  if (registered.length > 0) {
    console.log(`Registered ${registered.length} plugin permission(s) for ${manifest.pluginId}: ${registered.join(", ")}`)
  }
  return registered
}
