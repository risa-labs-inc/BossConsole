import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginVersion, PluginDependency } from "../types/plugin.ts"
import { signVersionAnchor } from "../utils/signing.ts"

/**
 * Get all versions of a plugin
 */
export async function getPluginVersions(
  supabase: SupabaseClient,
  pluginId: string
): Promise<PluginVersion[]> {
  const { data, error } = await supabase
    .rpc('get_plugin_versions', {
      p_plugin_id: pluginId
    })

  if (error) {
    console.error('Error getting plugin versions:', error)
    throw new Error(`Failed to get versions: ${error.message}`)
  }

  return (data || []).map((row: Record<string, unknown>) => ({
    id: row.id as string,
    pluginId: pluginId,
    version: row.version as string,
    changelog: row.changelog as string,
    minBossVersion: row.min_boss_version as string,
    minIpcVersion: (row.min_ipc_version as string) ?? '1.0.0',
    minApiVersion: (row.min_api_version as string) ?? '',
    jarPath: row.jar_path as string,
    jarSize: Number(row.jar_size) || 0,
    sha256: row.sha256 as string,
    dependencies: (row.dependencies as PluginDependency[]) || [],
    publishedAt: row.published_at as string,
    downloadCount: Number(row.download_count) || 0
  }))
}

/**
 * Get the latest version of a plugin
 */
export async function getLatestVersion(
  supabase: SupabaseClient,
  pluginUuid: string
): Promise<PluginVersion | null> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('*')
    .eq('plugin_id', pluginUuid)
    .order('published_at', { ascending: false })
    .limit(1)
    .single()

  if (error) {
    if (error.code === 'PGRST116') return null // Not found
    console.error('Error getting latest version:', error)
    throw new Error(`Failed to get latest version: ${error.message}`)
  }

  return {
    id: data.id,
    pluginId: data.plugin_id,
    version: data.version,
    changelog: data.changelog,
    minBossVersion: data.min_boss_version,
    minIpcVersion: (data.min_ipc_version as string) ?? '1.0.0',
    minApiVersion: (data.min_api_version as string) ?? '',
    jarPath: data.jar_path,
    jarSize: Number(data.jar_size) || 0,
    sha256: data.sha256,
    signature: (data.signature as string | null) ?? null,
    dependencies: data.dependencies || [],
    publishedAt: data.published_at
  }
}

/**
 * Get a specific version by plugin UUID and version string
 */
export async function getVersion(
  supabase: SupabaseClient,
  pluginUuid: string,
  version: string
): Promise<PluginVersion | null> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('*')
    .eq('plugin_id', pluginUuid)
    .eq('version', version)
    .single()

  if (error) {
    if (error.code === 'PGRST116') return null // Not found
    console.error('Error getting version:', error)
    throw new Error(`Failed to get version: ${error.message}`)
  }

  return {
    id: data.id,
    pluginId: data.plugin_id,
    version: data.version,
    changelog: data.changelog,
    minBossVersion: data.min_boss_version,
    minIpcVersion: (data.min_ipc_version as string) ?? '1.0.0',
    minApiVersion: (data.min_api_version as string) ?? '',
    jarPath: data.jar_path,
    jarSize: Number(data.jar_size) || 0,
    sha256: data.sha256,
    signature: (data.signature as string | null) ?? null,
    dependencies: data.dependencies || [],
    publishedAt: data.published_at
  }
}

/**
 * Get a version by its UUID
 */
export async function getVersionById(
  supabase: SupabaseClient,
  versionId: string
): Promise<PluginVersion | null> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('*')
    .eq('id', versionId)
    .single()

  if (error) {
    if (error.code === 'PGRST116') return null // Not found
    console.error('Error getting version by ID:', error)
    throw new Error(`Failed to get version: ${error.message}`)
  }

  return {
    id: data.id,
    pluginId: data.plugin_id,
    version: data.version,
    changelog: data.changelog,
    minBossVersion: data.min_boss_version,
    minIpcVersion: (data.min_ipc_version as string) ?? '1.0.0',
    minApiVersion: (data.min_api_version as string) ?? '',
    jarPath: data.jar_path,
    jarSize: Number(data.jar_size) || 0,
    sha256: data.sha256,
    signature: (data.signature as string | null) ?? null,
    dependencies: data.dependencies || [],
    publishedAt: data.published_at
  }
}

/**
 * Create a new version (pending JAR upload)
 */
export async function createVersion(
  supabase: SupabaseClient,
  pluginUuid: string,
  version: string,
  changelog: string,
  minBossVersion: string,
  minIpcVersion: string,
  dependencies: PluginDependency[],
  jarPath: string,
  minApiVersion: string = ''
): Promise<{ id: string }> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .insert({
      plugin_id: pluginUuid,
      version,
      changelog,
      min_boss_version: minBossVersion,
      min_ipc_version: minIpcVersion,
      min_api_version: minApiVersion,
      dependencies,
      jar_path: jarPath,
      sha256: 'pending', // Will be updated after upload
      jar_size: 0
    })
    .select('id')
    .single()

  if (error) {
    console.error('Error creating version:', error)
    if (error.code === '23505') {
      throw new Error('Version already exists')
    }
    throw new Error(`Failed to create version: ${error.message}`)
  }

  return { id: data.id }
}

/**
 * Finalize a version after JAR upload
 */
export async function finalizeVersion(
  supabase: SupabaseClient,
  versionId: string,
  sha256: string,
  jarSize: number,
  pluginId: string,
  version: string
): Promise<void> {
  // Sign the canonical anchor pluginId|version|sha256 — binding identity and
  // version, not just the digest, so store-signed artifacts aren't mutually
  // substitutable. Null (no signing key configured) leaves the version
  // unsigned, which hosts currently treat as warn-only.
  const signature = await signVersionAnchor(pluginId, version, sha256)
  if (signature === null) {
    // Deliberate never-block-publish behavior, but the degraded state must
    // be observable: this version ships unsigned (warn-only on hosts) until
    // a backfill --re-sign-all pass.
    console.error(`PUBLISHED UNSIGNED: ${pluginId} v${version} (versionId=${versionId}) — signing unavailable`)
  }

  const { error } = await supabase
    .from('plugin_versions')
    .update({
      sha256,
      jar_size: jarSize,
      signature
    })
    .eq('id', versionId)

  if (error) {
    console.error('Error finalizing version:', error)
    throw new Error(`Failed to finalize version: ${error.message}`)
  }
}

/**
 * Check if a version exists
 */
export async function versionExists(
  supabase: SupabaseClient,
  pluginUuid: string,
  version: string
): Promise<boolean> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('id')
    .eq('plugin_id', pluginUuid)
    .eq('version', version)
    .maybeSingle()

  if (error) {
    console.error('Error checking version existence:', error)
    throw new Error(`Failed to check version: ${error.message}`)
  }

  return data !== null
}
