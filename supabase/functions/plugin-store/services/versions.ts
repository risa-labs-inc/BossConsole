import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginVersion, PluginDependency } from "../types/plugin.ts"
import { signVersionAnchor } from "../utils/signing.ts"

/**
 * The sha256 placeholder `createVersion` writes until the JAR has been uploaded
 * and hashed. `finalizeVersion` is the only thing that replaces it.
 */
export const PENDING_SHA256 = 'pending'

/**
 * Does this version row describe real, downloadable bytes?
 *
 * `createVersion` inserts the row BEFORE the artifact exists — sha256 is this
 * placeholder and jar_size is 0 — yet published_at already reads as now() (the
 * DDL default). An unfinalized row therefore outranks the last good version in
 * any published_at-ordered query, which is #912: "download latest" resolved to
 * a jar key that did not exist yet (or to stale bytes a failed attempt left
 * under that key, mis-anchored under the new version's signature).
 *
 * Both facts are written and replaced together, so one predicate covers the
 * state. Consumers split cleanly:
 *   - read paths (getLatestVersion, getVersion, getPluginVersions) must skip
 *     unfinalized rows — there is nothing to download;
 *   - publish paths (finalizeVersion, versionExists, getVersionById) must
 *     still see them, or an interrupted publish could never be repaired.
 */
export function isFinalizedVersionRow(row: { sha256?: unknown, jar_size?: unknown }): boolean {
  return row.sha256 !== PENDING_SHA256 && Number(row.jar_size) > 0
}

/**
 * Get all versions of a plugin
 *
 * These rows carry jar_path and sha256 (the reason the migration gates them),
 * so the detail page passes the viewer here exactly as it does for the plugin
 * row itself: the service-role client makes the no-viewer variant answer for
 * auth.uid() = NULL, hiding an organisation's own plugins from its members
 * (issue #852).
 */
export async function getPluginVersions(
  supabase: SupabaseClient,
  pluginId: string,
  /** Who is asking, or null for an anonymous lookup. Mirrors listPlugins. */
  viewerId: string | null = null
): Promise<PluginVersion[]> {
  const { data, error } = viewerId
    ? await supabase.rpc('get_plugin_versions_for_viewer', {
      p_plugin_id: pluginId,
      p_viewer_id: viewerId
    })
    : await supabase
    .rpc('get_plugin_versions', {
      p_plugin_id: pluginId
    })

  if (error) {
    console.error('Error getting plugin versions:', error)
    throw new Error(`Failed to get versions: ${error.message}`)
  }

  // The RPC orders by published_at DESC, so an unfinalized row — inserted with
  // published_at = now() (DDL default) while its JAR does not exist yet — would
  // lead this list. It is dropped here (#912): a version with no bytes is not a
  // version a user can download, and showing it advertises the very row that
  // breaks `download latest`.
  return (data || [])
    .filter((row: Record<string, unknown>) => isFinalizedVersionRow(row))
    .map((row: Record<string, unknown>) => ({
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
 *
 * THE #912 FIX. The naive shape was `.eq('plugin_id', …).order('published_at',
 * {ascending: false}).limit(1).single()` — a plain "newest row wins". But
 * createVersion inserts the row BEFORE the artifact exists, with sha256
 * 'pending', jar_size 0, and published_at already now() (DDL default). In that
 * shape the phantom row was *guaranteed* to outrank the last good version: it
 * is newer by construction and there is no state where it is not the newest
 * row until finalize replaces its hash. So every "download latest" resolved to
 * it — sha256 'pending' (every re-verifying host fails), a jar key that 404s,
 * or worse, stale bytes a previous failed finalize left under that exact key,
 * now served under the NEW version's signature anchor.
 *
 * The row is not removed — finalize still needs it, and its
 * UNIQUE(plugin_id, version) squat is the repair trail. It is excluded from the
 * query, so `.limit(1).single()` answers with the newest FINALIZED version
 * instead. A plugin whose every version is unfinalized has no latest — null,
 * which the download routes render as their existing "No versions available"
 * 404.
 *
 * The filter is the query-side mirror of isFinalizedVersionRow() and must stay
 * in sync with it: sha256 <> 'pending' AND jar_size > 0. Both facts are set
 * together by finalizeVersion, so either alone is enough in practice; the pair
 * is belt and braces against a half-written finalize (size written, hash not).
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
 * Get a specific version by plugin UUID and version string
 *
 * The download-by-version route's resolver (#912). An unfinalized row must be
 * indistinguishable from a nonexistent one here: there are no bytes behind it,
 * so serving its jar_path hands the client a 404 download URL — or stale bytes
 * a previous failed attempt left under that key — and its 'pending' sha256
 * fails every host that re-verifies. A pinned download is a cache of a version
 * the user already saw; an unfinalized row never appeared anywhere, so nobody
 * can legitimately hold its name.
 *
 * getVersionById deliberately does NOT filter — finalize needs to find the row
 * to repair it (see finalizeVersion).
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
      sha256: PENDING_SHA256, // Will be updated after upload (finalizeVersion)
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
 *
 * A finalize-time guard (#912): the whole fix rests on "finalized" meaning
 * `sha256 <> 'pending' AND jar_size > 0`. Accepting the placeholder (or a
 * non-positive size) here would write a row that every read path — rightly —
 * refuses to serve, while its UNIQUE(plugin_id, version) row squats the version
 * so the owner can never retry it: a permanently invisible, unrepairable
 * version. Refuse instead, leaving the row pending and the retry open.
 */
export async function finalizeVersion(
  supabase: SupabaseClient,
  versionId: string,
  sha256: string,
  jarSize: number,
  pluginId: string,
  version: string
): Promise<void> {
  if (typeof sha256 !== 'string' || sha256.length === 0 || sha256 === PENDING_SHA256) {
    throw new Error('Refusing to finalize: sha256 must be the real digest of the uploaded JAR, not the pending placeholder')
  }
  if (!Number.isFinite(jarSize) || jarSize <= 0) {
    throw new Error(`Refusing to finalize: jar_size must be positive, got ${jarSize}`)
  }

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
