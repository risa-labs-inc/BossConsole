import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginListItem, PluginWithStats } from "../types/plugin.ts"

/**
 * Get list of plugins with pagination and sorting
 */
export async function listPlugins(
  supabase: SupabaseClient,
  page: number,
  pageSize: number,
  sortBy: string,
  /**
   * Who is asking, or null for an anonymous browse.
   *
   * `search_plugins` reads `auth.uid()`, and this handler holds a SERVICE ROLE client - so auth.uid()
   * is null however the caller authenticated, and the list was public-only for everybody. That is
   * what hid an `org`-visibility plugin from the very members it exists for, and it is the follow-up
   * 20260803000000 names: "routes/browse.ts needs the *_for_viewer variants".
   *
   * The viewer is passed to the RPC, which applies user_can_view_plugin_row itself. Nothing here
   * decides visibility; this only stops the answer being computed for nobody.
   */
  viewerId: string | null = null
): Promise<{ plugins: PluginListItem[], totalCount: number }> {
  const { data, error } = viewerId
    ? await supabase.rpc('search_plugins_for_viewer', {
      p_viewer_id: viewerId,
      p_query: '',
      p_type: null,
      p_tags: null,
      p_min_rating: 0,
      p_verified_only: false,
      p_page: page,
      p_page_size: pageSize,
      p_sort_by: sortBy
    })
    : await supabase
    .rpc('search_plugins', {
      p_query: '',
      p_type: null,
      p_tags: null,
      p_min_rating: 0,
      p_verified_only: false,
      p_page: page,
      p_page_size: pageSize,
      p_sort_by: sortBy
    })

  if (error) {
    console.error('Error listing plugins:', error)
    throw new Error(`Failed to list plugins: ${error.message}`)
  }

  // The RPC returns { plugins: JSONB, total_count: BIGINT }
  const result = data?.[0] || { plugins: [], total_count: 0 }
  
  return {
    plugins: result.plugins || [],
    totalCount: Number(result.total_count) || 0
  }
}

/**
 * Search plugins with filters
 */
export async function searchPlugins(
  supabase: SupabaseClient,
  query: string,
  type: string | null,
  tags: string[] | null,
  minRating: number,
  verifiedOnly: boolean,
  page: number,
  pageSize: number,
  sortBy: string
): Promise<{ plugins: PluginListItem[], totalCount: number }> {
  const { data, error } = await supabase
    .rpc('search_plugins', {
      p_query: query,
      p_type: type,
      p_tags: tags,
      p_min_rating: minRating,
      p_verified_only: verifiedOnly,
      p_page: page,
      p_page_size: pageSize,
      p_sort_by: sortBy
    })

  if (error) {
    console.error('Error searching plugins:', error)
    throw new Error(`Failed to search plugins: ${error.message}`)
  }

  const result = data?.[0] || { plugins: [], total_count: 0 }
  
  return {
    plugins: result.plugins || [],
    totalCount: Number(result.total_count) || 0
  }
}

/**
 * Look up a plugin by manifest id for the PUBLISH paths, including unpublished rows.
 *
 * getPlugin() goes through the get_plugin_with_stats RPC, whose body ends in
 * `WHERE p.published = true`. That is right for the storefront and wrong for deciding
 * create-vs-update on publish: an unpublished row is invisible to that lookup while still
 * occupying the unique plugin_id index, so the caller took the create branch and the INSERT
 * failed with 23505, surfaced to publishers as "Plugin ID already exists" on an HTTP 500.
 * The effect was that an unpublished plugin could never be published again by any route.
 *
 * Deliberately a direct table read rather than a widened RPC: get_plugin_with_stats backs the
 * public storefront, and dropping its published filter there would list unpublished plugins to
 * everyone.
 */
export async function getPluginForPublish(
  supabase: SupabaseClient,
  pluginId: string
): Promise<{ id: string; authorId: string; published: boolean } | null> {
  const { data, error } = await supabase
    .from('plugins')
    .select('id, author_id, published')
    .eq('plugin_id', pluginId)
    .maybeSingle()

  if (error) {
    console.error('Error getting plugin for publish:', error)
    throw new Error(`Failed to get plugin: ${error.message}`)
  }

  if (!data) {
    return null
  }

  return { id: data.id, authorId: data.author_id, published: data.published }
}

/**
 * Get plugin details by plugin ID string
 */
export async function getPlugin(
  supabase: SupabaseClient,
  pluginId: string
): Promise<PluginWithStats | null> {
  const { data, error } = await supabase
    .rpc('get_plugin_with_stats', {
      p_plugin_id: pluginId
    })

  if (error) {
    console.error('Error getting plugin:', error)
    throw new Error(`Failed to get plugin: ${error.message}`)
  }

  if (!data || data.length === 0) {
    return null
  }

  const row = data[0]
  
  return {
    id: row.id,
    pluginId: row.plugin_id,
    displayName: row.display_name,
    description: row.description,
    authorId: row.author_id,
    authorName: row.author_name,
    homepageUrl: row.homepage_url,
    iconUrl: row.icon_url,
    type: row.type,
    apiVersion: row.api_version,
    verified: row.verified,
    published: row.published,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    latestVersion: row.latest_version,
    latestVersionId: row.latest_version_id,
    avgRating: Number(row.avg_rating) || 0,
    ratingCount: Number(row.rating_count) || 0,
    downloadCount: Number(row.download_count) || 0,
    tags: row.tags || [],
    screenshots: row.screenshots || [],
    requiredPermissions: row.required_permissions || []
  }
}

/**
 * Get plugin by internal UUID
 */
export async function getPluginById(
  supabase: SupabaseClient,
  id: string
): Promise<{ id: string, pluginId: string, authorId: string | null } | null> {
  const { data, error } = await supabase
    .from('plugins')
    .select('id, plugin_id, author_id')
    .eq('id', id)
    .single()

  if (error) {
    if (error.code === 'PGRST116') return null // Not found
    console.error('Error getting plugin by ID:', error)
    throw new Error(`Failed to get plugin: ${error.message}`)
  }

  return data ? {
    id: data.id,
    pluginId: data.plugin_id,
    authorId: data.author_id
  } : null
}

/**
 * The organisation a plugin already belongs to, by internal UUID.
 *
 * Its own one-column read rather than a field on `getPlugin`: that goes through the
 * `get_plugin_with_stats` RPC, whose `RETURNS TABLE` cannot be `CREATE OR REPLACE`d - adding a
 * column means a DROP, and 20260803000000_plugins_org_ownership.sql's header spells out why that
 * is not something to do casually (the grants go with it, and the store browses anonymously).
 *
 * Null is a real answer: `plugins.org_id` is nullable, with `plugins_default_org` filling it in on
 * INSERT. `authorizeExistingPluginPublish` treats null as "not an organisation you can claim",
 * which is right in both directions - the trigger's answer for it would have been `@boss`.
 *
 * Throws on an unexpected failure rather than returning null, so an outage can never read as
 * "belongs to no organisation" and widen a gate.
 */
export async function getPluginOrgId(
  supabase: SupabaseClient,
  id: string
): Promise<string | null> {
  const { data, error } = await supabase
    .from('plugins')
    .select('org_id')
    .eq('id', id)
    .single()

  if (error) {
    console.error('Error getting plugin organisation:', error)
    throw new Error(`Failed to get plugin organisation: ${error.message}`)
  }

  return (data?.org_id as string | null) ?? null
}

/**
 * Create a new plugin
 */
export async function createPlugin(
  supabase: SupabaseClient,
  authorId: string,
  authorName: string,
  pluginId: string,
  displayName: string,
  description: string,
  homepageUrl: string,
  iconUrl: string,
  type: string,
  apiVersion: string,
  requiredPermissions: string[] = [],
  /**
   * Owning organisation, already authorised by `resolvePublishOrg`.
   *
   * OMITTED from the insert when null, not sent as null: `plugins_default_org` is a BEFORE INSERT
   * trigger keyed on `NEW.org_id IS NULL`, so an explicit null and an absent key both reach it and
   * both become the boss organisation. Leaving the key out keeps that fallback visibly the
   * trigger's decision rather than looking like this function chose it.
   */
  orgId: string | null = null,
  /**
   * Store visibility, or null to take the column default.
   *
   * OMITTED when null for the same reason as `org_id` above: `plugins.visibility` defaults to
   * `'public'` and rule (3) of 20260803000000_plugins_org_ownership.sql says it must, because this
   * function did not set it and any other default would have unpublished every new plugin. Passing
   * `'org'` is how an org-scoped publish - one allowed by an organisation's own publish policy
   * rather than by `plugins.create` - lands on that organisation's shelf instead of the global one.
   */
  visibility: 'public' | 'org' | 'unlisted' | null = null
): Promise<{ id: string }> {
  const { data, error } = await supabase
    .from('plugins')
    .insert({
      author_id: authorId,
      author_name: authorName,
      plugin_id: pluginId,
      display_name: displayName,
      description,
      homepage_url: homepageUrl,
      icon_url: iconUrl,
      type,
      api_version: apiVersion,
      required_permissions: requiredPermissions,
      published: true,
      ...(orgId ? { org_id: orgId } : {}),
      ...(visibility ? { visibility } : {})
    })
    .select('id')
    .single()

  if (error) {
    console.error('Error creating plugin:', error)
    if (error.code === '23505') {
      throw new Error('Plugin ID already exists')
    }
    throw new Error(`Failed to create plugin: ${error.message}`)
  }

  return { id: data.id }
}

/**
 * Add tags to a plugin
 */
export async function setPluginTags(
  supabase: SupabaseClient,
  pluginUuid: string,
  tags: string[]
): Promise<void> {
  // Delete existing tags
  await supabase
    .from('plugin_tags')
    .delete()
    .eq('plugin_id', pluginUuid)

  // Insert new tags
  if (tags.length > 0) {
    const { error } = await supabase
      .from('plugin_tags')
      .insert(tags.map(tag => ({
        plugin_id: pluginUuid,
        tag: tag.toLowerCase().trim()
      })))

    if (error) {
      console.error('Error setting plugin tags:', error)
      throw new Error(`Failed to set tags: ${error.message}`)
    }
  }
}

/**
 * Get popular tags
 */
export async function getPopularTags(
  supabase: SupabaseClient,
  limit: number = 20
): Promise<{ tag: string, count: number }[]> {
  const { data, error } = await supabase
    .rpc('get_popular_tags', {
      p_limit: limit
    })

  if (error) {
    console.error('Error getting popular tags:', error)
    throw new Error(`Failed to get popular tags: ${error.message}`)
  }

  return data || []
}

/**
 * Update an existing plugin's metadata
 */
export async function updatePlugin(
  supabase: SupabaseClient,
  pluginUuid: string,
  updates: {
    displayName?: string
    description?: string
    homepageUrl?: string
    iconUrl?: string
    type?: string
    apiVersion?: string
    requiredPermissions?: string[]
    published?: boolean
  }
): Promise<void> {
  const updateData: Record<string, unknown> = {}

  if (updates.displayName !== undefined) updateData.display_name = updates.displayName
  if (updates.description !== undefined) updateData.description = updates.description
  if (updates.homepageUrl !== undefined) updateData.homepage_url = updates.homepageUrl
  if (updates.iconUrl !== undefined) updateData.icon_url = updates.iconUrl
  if (updates.type !== undefined) updateData.type = updates.type
  if (updates.apiVersion !== undefined) updateData.api_version = updates.apiVersion
  if (updates.requiredPermissions !== undefined) updateData.required_permissions = updates.requiredPermissions
  if (updates.published !== undefined) updateData.published = updates.published

  if (Object.keys(updateData).length === 0) {
    return // Nothing to update
  }

  const { error } = await supabase
    .from('plugins')
    .update(updateData)
    .eq('id', pluginUuid)

  if (error) {
    console.error('Error updating plugin:', error)
    throw new Error(`Failed to update plugin: ${error.message}`)
  }
}
