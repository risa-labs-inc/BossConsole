import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginVersion, PluginDependency, ReleaseChannel, ReviewStatus } from "../types/plugin.ts"
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
  viewerId: string | null = null,
  /**
   * An explicit ?channel=. Absent keeps the old call (released stable versions only). Present asks
   * get_plugin_versions_channel, which gives a viewer with channel access the finalized versions on the channels up to
   * this one - including those still in review, with their status and the reviewer's note - and everyone else exactly
   * the released stable list.
   */
  channel: ReleaseChannel | null = null
): Promise<PluginVersion[]> {
  const { data, error } = channel
    ? await supabase.rpc('get_plugin_versions_channel', {
      p_plugin_id: pluginId,
      p_viewer_id: viewerId,
      p_channel: channel
    })
    : viewerId
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
    downloadCount: Number(row.download_count) || 0,
    ...(channel ? {
      channel: row.channel as ReleaseChannel,
      reviewStatus: row.review_status as ReviewStatus,
      reviewNote: (row.review_note as string | null) ?? null
    } : {})
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
  pluginUuid: string,
  /**
   * The channels this caller may get (channelsUpTo of what they asked for, already narrowed to what
   * plugin_channel_access allows). Default: stable only, which is what every caller got before channels existed.
   */
  channels: ReleaseChannel[] = ['stable']
): Promise<PluginVersion | null> {
  // The release gate (20261125120100): only an APPROVED version is ever anybody's latest - a Max app version waiting
  // for review, or one that was rejected, never resolves here - and only on the channels the caller may get.
  const base = supabase
    .from('plugin_versions')
    .select('*')
    .eq('plugin_id', pluginUuid)
    .neq('sha256', PENDING_SHA256)
    .gt('jar_size', 0)
    .eq('review_status', 'approved')
  const gated = channels.length === 1 && channels[0] === 'stable'
    ? base.eq('channel', 'stable')
    : base.in('channel', channels)
  const { data, error } = await gated
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
    publishedAt: data.published_at,
    channel: (data.channel as ReleaseChannel | undefined) ?? 'stable',
    reviewStatus: (data.review_status as ReviewStatus | undefined) ?? 'approved',
    reviewNote: (data.review_note as string | null | undefined) ?? null,
    reviewMeta: (data.review_meta as Record<string, unknown> | null | undefined) ?? null
  }
}

/**
 * Get a specific version by plugin UUID and version string.
 *
 * NOT gated on review or channel here: the download route decides who may have a version that is not approved or
 * not on stable (plugin_unreviewed_access / plugin_channel_access), so it needs to see the row's status to decide.
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
    publishedAt: data.published_at,
    channel: (data.channel as ReleaseChannel | undefined) ?? 'stable',
    reviewStatus: (data.review_status as ReviewStatus | undefined) ?? 'approved',
    reviewNote: (data.review_note as string | null | undefined) ?? null,
    reviewMeta: (data.review_meta as Record<string, unknown> | null | undefined) ?? null
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
    publishedAt: data.published_at,
    channel: (data.channel as ReleaseChannel | undefined) ?? 'stable',
    reviewStatus: (data.review_status as ReviewStatus | undefined) ?? 'approved',
    reviewNote: (data.review_note as string | null | undefined) ?? null,
    reviewMeta: (data.review_meta as Record<string, unknown> | null | undefined) ?? null
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
  minApiVersion: string = '',
  /**
   * Channel and review state for the new row. Omitted keys take the column defaults (stable, approved), so a normal
   * plugin's insert is exactly what it always was. A Max app version is inserted pending_review: until finalize has
   * checked it and a reviewer has approved it, it must never be approved by default.
   */
  options: { channel?: ReleaseChannel; reviewStatus?: ReviewStatus } = {}
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
      jar_size: 0,
      ...(options.channel && options.channel !== 'stable' ? { channel: options.channel } : {}),
      ...(options.reviewStatus ? { review_status: options.reviewStatus } : {})
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
 * The store signature for a version, exactly as finalize makes it. Shared by finalizeVersion (a plugin, at publish)
 * and the review approve route (a Max app, when a reviewer approves it).
 */
export async function signVersion(
  versionId: string,
  pluginId: string,
  version: string,
  sha256: string
): Promise<string | null> {
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
  return signature
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
  const signature = await signVersion(versionId, pluginId, version, sha256)

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
 * Finalize a Max app version: record the hash and size like finalizeVersion, but UNSIGNED and pending_review, with
 * what the content-only check found (review_meta). It is signed only when a plugins.review holder approves it
 * (approveVersion). Any earlier decision on the row is cleared: these are new bytes.
 */
export async function finalizeVersionForReview(
  supabase: SupabaseClient,
  versionId: string,
  sha256: string,
  jarSize: number,
  reviewMeta: Record<string, unknown>
): Promise<void> {
  const { error } = await supabase
    .from('plugin_versions')
    .update({
      sha256,
      jar_size: jarSize,
      signature: null,
      review_status: 'pending_review',
      review_meta: reviewMeta,
      reviewed_by: null,
      reviewed_at: null,
      review_note: null
    })
    .eq('id', versionId)

  if (error) {
    console.error('Error finalizing version for review:', error)
    throw new Error(`Failed to finalize version: ${error.message}`)
  }
}

/** One entry of the review queue. */
export interface ReviewQueueItem {
  versionId: string
  pluginId: string
  pluginUuid: string
  displayName: string
  authorId: string | null
  authorName: string
  orgId: string | null
  version: string
  channel: ReleaseChannel
  changelog: string
  jarSize: number
  sha256: string
  publishedAt: string
  reviewMeta: Record<string, unknown> | null
}

/**
 * The review queue: finalized Max app versions waiting for a decision, oldest first. Unfinalized rows (sha256
 * 'pending') are pending_review from their insert but have nothing to review yet, so they are left out.
 */
export async function listReviewQueue(supabase: SupabaseClient, limit = 100): Promise<ReviewQueueItem[]> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('id, version, channel, changelog, jar_size, sha256, published_at, review_meta, ' +
      'plugins!inner(id, plugin_id, display_name, author_id, author_name, org_id, kind)')
    .eq('review_status', 'pending_review')
    .neq('sha256', PENDING_SHA256)
    .gt('jar_size', 0)
    .eq('plugins.kind', 'max-app')
    .order('published_at', { ascending: true })
    .limit(limit)

  if (error) {
    console.error('Error reading the review queue:', error)
    throw new Error(`Failed to read the review queue: ${error.message}`)
  }

  return ((data || []) as unknown as Array<Record<string, unknown>>).map((row) => {
    const p = (Array.isArray(row.plugins) ? row.plugins[0] : row.plugins) as Record<string, unknown>
    return {
      versionId: row.id as string,
      pluginId: p.plugin_id as string,
      pluginUuid: p.id as string,
      displayName: p.display_name as string,
      authorId: (p.author_id as string | null) ?? null,
      authorName: (p.author_name as string) ?? '',
      orgId: (p.org_id as string | null) ?? null,
      version: row.version as string,
      channel: (row.channel as ReleaseChannel) ?? 'stable',
      changelog: (row.changelog as string) ?? '',
      jarSize: Number(row.jar_size) || 0,
      sha256: row.sha256 as string,
      publishedAt: row.published_at as string,
      reviewMeta: (row.review_meta as Record<string, unknown> | null) ?? null
    }
  })
}

/** Refusal codes plugin_version_review answers with. */
export type ReviewRefusal = 'bad_input' | 'note_required' | 'note_too_long' | 'not_found' | 'not_pending_review'

/**
 * Records a review decision through plugin_version_review (20261125120100): one statement that updates the row only
 * if it is still the pending, finalized Max app version with the bytes the reviewer saw (sha256), and writes the
 * platform_audit_log row. Approve stores `signature`; reject stores `note`.
 */
export async function recordReviewDecision(
  supabase: SupabaseClient,
  args: {
    versionId: string
    reviewerId: string
    approve: boolean
    sha256: string
    signature?: string | null
    note?: string | null
  }
): Promise<{ ok: true; reviewStatus: ReviewStatus } | { ok: false; error: ReviewRefusal }> {
  const { data, error } = await supabase.rpc('plugin_version_review', {
    p_version_id: args.versionId,
    p_reviewer: args.reviewerId,
    p_approve: args.approve,
    p_sha256: args.sha256,
    p_signature: args.signature ?? null,
    p_note: args.note ?? null
  })
  if (error) {
    console.error('plugin_version_review failed:', error.message)
    throw new Error(`Failed to record the review: ${error.message}`)
  }
  const r = (data ?? {}) as { ok?: boolean; error?: string; review_status?: string }
  if (r.ok === true) return { ok: true, reviewStatus: r.review_status as ReviewStatus }
  return { ok: false, error: (r.error as ReviewRefusal) ?? 'bad_input' }
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
 * deleted. The match is the exact negation of the finalization gate, so a
 * hidden row is always reapable. Returns true only when a row was actually
 * removed.
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
    // The exact negation of the finalization gate in getLatestVersion /
    // getVersion: a row the gate hides must also be reapable, or the version
    // slot wedges forever - a real sha with jar_size 0/NULL is wreckage, not
    // an in-flight state (finalize writes both columns in one UPDATE).
    .or(`sha256.eq.${PENDING_SHA256},jar_size.is.null,jar_size.lte.0`)
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
