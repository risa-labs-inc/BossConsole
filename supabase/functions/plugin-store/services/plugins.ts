import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginListItem, PluginWithStats } from "../types/plugin.ts"

/**
 * Get list of plugins with pagination and sorting
 */
export async function listPlugins(
  supabase: SupabaseClient,
  page: number,
  pageSize: number,
  sortBy: string
): Promise<{ plugins: PluginListItem[], totalCount: number }> {
  const { data, error } = await supabase
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
  requiredPermissions: string[] = []
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
      published: true
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
