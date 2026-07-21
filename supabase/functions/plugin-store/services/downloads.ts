import type { SupabaseClient } from "@supabase/supabase-js"

/**
 * Record a plugin download
 */
export async function recordDownload(
  supabase: SupabaseClient,
  pluginUuid: string,
  versionId: string,
  userId: string | null = null,
  ipHash: string | null = null
): Promise<string> {
  const { data, error } = await supabase
    .rpc('record_plugin_download', {
      p_plugin_id: pluginUuid,
      p_version_id: versionId,
      p_user_id: userId,
      p_ip_hash: ipHash
    })

  if (error) {
    console.error('Error recording download:', error)
    throw new Error(`Failed to record download: ${error.message}`)
  }

  return data
}

/**
 * Hash an IP address for privacy-preserving analytics
 */
export async function hashIp(ip: string): Promise<string> {
  const encoder = new TextEncoder()
  const data = encoder.encode(ip + Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')?.substring(0, 16))
  const hashBuffer = await crypto.subtle.digest('SHA-256', data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}
