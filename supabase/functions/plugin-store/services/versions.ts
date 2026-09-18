import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginVersion, PluginDependency } from "../types/plugin.ts"
import { signVersionAnchor } from "../utils/signing.ts"

/**
 * Sentinel sha256 that `createVersion` stamps on a row whose JAR has not been
 * uploaded and verified yet. Only `finalizeVersion` replaces it (together with
 * the real byte count), so any row still carrying it — or a zero jar_size —
 * is an UNFINALIZED publish (#912): its artifact may not exist yet, and its
 * sha anchor is meaningless. Store consumers must never resolve such a row
 * as a downloadable version; see `getLatestVersion` and `getVersion`.
 */
export const PENDING_SHA256 = 'pending'

/**
 * How long a pending version row may squat on its UNIQUE(plugin_id, version)
 * slot before a fresh publish of the same version string may reap it
 * (`deleteStalePendingVersion`). Aligned with the signed upload URL's 1h TTL
 * (services/storage.ts): once that URL has expired the original attempt can
 * never finalize, so the row can only ever be retried, never completed.
 */
export const PENDING_VERSION_STALE_MS = 60 * 60 * 1000

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
 * Get the latest version of a plugin.
 *
 * Finalization gate (#912): a version row exists BEFORE its JAR does —
 * publish.ts inserts it with sha256='pending' and jar_size=0, and only the
 * finalize route replaces those once the uploaded bytes have been hashed and
 * manifest-checked. Without a gate, the newest row wins the published_at
 * ordering the moment it is inserted, so a publish that dies mid-way leaves
 * every consumer of "latest" pointing at a sha of 'pending' and a jar key
 * that 404s (or holds bytes a prior failed attempt left behind) — permanently.
 * Gating on BOTH the sentinel sha and a non-zero size is fail-closed: either
 * half alone would leave a partially-finalized row resolvable.
 */
export async function getLatestVersion(
  supabase: SupabaseClient,
  pluginUuid: string
): Promise<PluginVersion | null> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('*')
    .eq('plugin_id', pluginUuid)
    .neq('sha256', PENDING_SHA256)
    .gt('jar_size', 0)
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
 * Get a specific version by plugin UUID and version string.
 *
 * Same finalization gate as getLatestVersion (#912). This lookup backs the
 * download-info route for an explicit version, so serving an unfinalized row
 * here would hand out sha256='pending' and a signed URL to an artifact that
 * does not exist yet. A pending version must be indistinguishable from a
 * nonexistent one — the route 404s, fail-closed. (The finalize route reads the
 * row by id via getVersionById instead, which deliberately stays ungated.)
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
    .neq('sha256', PENDING_SHA256)
    .gt('jar_size', 0)
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
      sha256: PENDING_SHA256, // Replaced only by finalizeVersion, once the JAR is verified
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
 * Reap a stale unfinalized version row so its UNIQUE(plugin_id, version) slot
 * can be re-published (#912).
 *
 * A publish that dies between createVersion and finalizeVersion leaves a
 * pending row that is invisible to every consumer (see the gates above) but
 * still squats on the version string, so the publisher's next attempt 400s
 * with "Version already exists" forever. This only touches rows that are
 * STILL pending (sentinel sha AND zero bytes) and whose upload window has
 * provably lapsed: published_at is the insert time, and once it is older than
 * the signed upload URL's TTL the original attempt can never finalize. A
 * finalized row, or a pending row still inside its upload window, is never
 * deleted. Returns true only when a row was actually removed.
 */
export async function deleteStalePendingVersion(
  supabase: SupabaseClient,
  pluginUuid: string,
  version: string,
  staleAfterMs: number = PENDING_VERSION_STALE_MS
): Promise<boolean> {
  const cutoff = new Date(Date.now() - staleAfterMs).toISOString()

  const { data, error } = await supabase
    .from('plugin_versions')
    .delete()
    .eq('plugin_id', pluginUuid)
    .eq('version', version)
    .eq('sha256', PENDING_SHA256)
    .eq('jar_size', 0)
    .lt('published_at', cutoff)
    .select('id')

  if (error) {
    console.error('Error reaping stale pending version:', error)
    throw new Error(`Failed to reap stale pending version: ${error.message}`)
  }

  return (data?.length ?? 0) > 0
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
