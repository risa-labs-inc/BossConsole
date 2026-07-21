import type { SupabaseClient } from "@supabase/supabase-js"

/**
 * Rate a plugin (create or update rating)
 */
export async function ratePlugin(
  supabase: SupabaseClient,
  pluginUuid: string,
  userId: string,
  rating: number,
  review: string
): Promise<{ ratingId: string, created: boolean }> {
  const { data, error } = await supabase
    .rpc('upsert_plugin_rating', {
      p_plugin_id: pluginUuid,
      p_user_id: userId,
      p_rating: rating,
      p_review: review
    })

  if (error) {
    console.error('Error rating plugin:', error)
    throw new Error(`Failed to rate plugin: ${error.message}`)
  }

  const result = data?.[0]
  if (!result) {
    throw new Error('Failed to rate plugin: no result returned')
  }

  return {
    ratingId: result.id,
    created: result.created
  }
}

/**
 * Get a user's rating for a plugin
 */
export async function getUserRating(
  supabase: SupabaseClient,
  pluginUuid: string,
  userId: string
): Promise<{ rating: number, review: string } | null> {
  const { data, error } = await supabase
    .from('plugin_ratings')
    .select('rating, review')
    .eq('plugin_id', pluginUuid)
    .eq('user_id', userId)
    .maybeSingle()

  if (error) {
    console.error('Error getting user rating:', error)
    throw new Error(`Failed to get rating: ${error.message}`)
  }

  return data
}

/**
 * Delete a user's rating for a plugin
 */
export async function deleteRating(
  supabase: SupabaseClient,
  pluginUuid: string,
  userId: string
): Promise<void> {
  const { error } = await supabase
    .from('plugin_ratings')
    .delete()
    .eq('plugin_id', pluginUuid)
    .eq('user_id', userId)

  if (error) {
    console.error('Error deleting rating:', error)
    throw new Error(`Failed to delete rating: ${error.message}`)
  }
}

/**
 * Get all ratings for a plugin with pagination
 */
export async function getPluginRatings(
  supabase: SupabaseClient,
  pluginUuid: string,
  page: number = 1,
  pageSize: number = 20
): Promise<{ ratings: Array<{ userId: string, rating: number, review: string, createdAt: string }>, totalCount: number }> {
  const offset = (page - 1) * pageSize

  // Get total count
  const { count, error: countError } = await supabase
    .from('plugin_ratings')
    .select('*', { count: 'exact', head: true })
    .eq('plugin_id', pluginUuid)

  if (countError) {
    console.error('Error counting ratings:', countError)
    throw new Error(`Failed to count ratings: ${countError.message}`)
  }

  // Get ratings
  const { data, error } = await supabase
    .from('plugin_ratings')
    .select('user_id, rating, review, created_at')
    .eq('plugin_id', pluginUuid)
    .order('created_at', { ascending: false })
    .range(offset, offset + pageSize - 1)

  if (error) {
    console.error('Error getting ratings:', error)
    throw new Error(`Failed to get ratings: ${error.message}`)
  }

  return {
    ratings: (data || []).map(r => ({
      userId: r.user_id,
      rating: r.rating,
      review: r.review,
      createdAt: r.created_at
    })),
    totalCount: count || 0
  }
}
