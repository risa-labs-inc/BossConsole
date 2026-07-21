/**
 * API Key Utilities for Plugin Store
 *
 * Key Format: boss_pk_<32-random-chars> (40 chars total)
 * Example: boss_pk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6
 *
 * Security:
 * - Keys are hashed with SHA-256 before storage
 * - Full key is shown only once at creation
 * - Key prefix (first 16 chars) used for identification
 */

const API_KEY_PREFIX = "boss_pk_"
const RANDOM_CHARS_LENGTH = 32
const CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

/**
 * Generate a new API key with the boss_pk_ prefix
 *
 * @returns The full API key (boss_pk_<32-random-chars>)
 */
export function generateApiKey(): string {
  const randomChars = generateSecureRandomString(RANDOM_CHARS_LENGTH)
  return `${API_KEY_PREFIX}${randomChars}`
}

/**
 * Generate a cryptographically secure random string
 *
 * @param length - Number of characters to generate
 * @returns Random string from the charset
 */
function generateSecureRandomString(length: number): string {
  const array = new Uint8Array(length)
  crypto.getRandomValues(array)

  let result = ""
  for (let i = 0; i < length; i++) {
    result += CHARSET[array[i] % CHARSET.length]
  }
  return result
}

/**
 * Hash an API key using SHA-256 for storage
 *
 * @param apiKey - The full API key to hash
 * @returns Hex-encoded SHA-256 hash
 */
export async function hashApiKey(apiKey: string): Promise<string> {
  const encoder = new TextEncoder()
  const data = encoder.encode(apiKey)
  const hashBuffer = await crypto.subtle.digest("SHA-256", data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("")
}

/**
 * Extract the key prefix for display/identification
 * Format: boss_pk_XXXXXXXX (first 16 chars total)
 *
 * @param apiKey - The full API key
 * @returns The key prefix (boss_pk_ + first 8 random chars)
 */
export function getKeyPrefix(apiKey: string): string {
  // Return first 16 chars: "boss_pk_" (8) + first 8 random chars
  return apiKey.substring(0, 16)
}

/**
 * Validate that a string looks like a valid API key format
 *
 * @param apiKey - String to validate
 * @returns true if the format is valid
 */
export function isValidApiKeyFormat(apiKey: string): boolean {
  if (!apiKey || typeof apiKey !== "string") {
    return false
  }

  // Must start with boss_pk_
  if (!apiKey.startsWith(API_KEY_PREFIX)) {
    return false
  }

  // Must be exactly 40 chars (8 prefix + 32 random)
  if (apiKey.length !== API_KEY_PREFIX.length + RANDOM_CHARS_LENGTH) {
    return false
  }

  // Random portion must only contain valid charset characters
  const randomPart = apiKey.substring(API_KEY_PREFIX.length)
  for (const char of randomPart) {
    if (!CHARSET.includes(char)) {
      return false
    }
  }

  return true
}

/**
 * Valid scopes that can be assigned to API keys
 */
export const VALID_API_KEY_SCOPES = ["publish", "version", "finalize"] as const

export type ApiKeyScope = (typeof VALID_API_KEY_SCOPES)[number]

/**
 * Validate that provided scopes are all valid
 *
 * @param scopes - Array of scope strings to validate
 * @returns true if all scopes are valid
 */
export function areValidScopes(scopes: string[]): boolean {
  if (!Array.isArray(scopes) || scopes.length === 0) {
    return false
  }
  return scopes.every((scope) =>
    VALID_API_KEY_SCOPES.includes(scope as ApiKeyScope)
  )
}
