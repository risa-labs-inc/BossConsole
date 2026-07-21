/**
 * Configuration utilities for extracting dynamic values from environment
 */

/**
 * Extract RP ID from Supabase URL
 * Examples:
 * - https://api.risaboss.com -> api.risaboss.com
 * - http://127.0.0.1:54321 -> 127.0.0.1
 */
export function getRpId(): string {
  // Check for custom rpId first (for production with custom domains)
  const customRpId = Deno.env.get("PASSKEY_RP_ID")
  if (customRpId) {
    console.log(`🔧 Using custom PASSKEY_RP_ID: ${customRpId}`)
    return customRpId
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL") || ""

  try {
    // Remove protocol
    const withoutProtocol = supabaseUrl
      .replace(/^https?:\/\//, '')

    // Remove port if present
    const host = withoutProtocol.split(':')[0]

    // Remove path if present
    let rpId = host.split('/')[0]

    // WebAuthn requires "localhost" for local development
    // Convert both "127.0.0.1" and "kong" (internal gateway) to "localhost"
    if (rpId === '127.0.0.1' || rpId === 'kong') {
      const original = rpId
      rpId = 'localhost'
      console.log(`🔧 Converted ${original} -> localhost for WebAuthn compatibility`)
    }

    console.log(`🔧 Extracted rpId: ${rpId} from SUPABASE_URL: ${supabaseUrl}`)
    return rpId
  } catch (e) {
    console.error('Failed to extract RP ID from URL:', supabaseUrl, 'Error:', e)
    return 'api.risaboss.com' // Fallback to production
  }
}

/**
 * Get display name for the relying party
 */
export function getRpName(): string {
  return 'BOSS'
}
