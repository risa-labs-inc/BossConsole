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
 * Hash analytics input with an independently provisioned secret.
 * Missing configuration omits this optional field without preventing downloads.
 */
export async function hashIp(ip: string): Promise<string | null> {
  const secret = Deno.env.get('PLUGIN_DOWNLOAD_IP_HASH_KEY')
  if (!secret || secret.length < 32 || secret.length > 256 || /\s/.test(secret)) return null
  if (!ip || ip.length > 1024) return null
  const encoder = new TextEncoder()
  const key = await crypto.subtle.importKey(
    'raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  )
  const hashBuffer = await crypto.subtle.sign('HMAC', key, encoder.encode(ip))
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}
