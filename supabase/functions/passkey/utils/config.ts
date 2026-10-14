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

      // `kong` is the gateway host in a hosted deployment *and* in a local
      // Supabase stack, so this mapping cannot tell them apart on its own. In a
      // hosted deployment the result is that /auth/challenge advertises
      // rpId: "localhost" and every ceremony fails confusingly. Make the
      // misconfiguration loud rather than silent; the value is left alone because
      // "localhost" is the correct answer locally.
      if (!isLocalDevEnvironment()) {
        console.error(
          '❌ Derived rpId "localhost" from SUPABASE_URL, and this does not look like a local ' +
          'deployment. Set PASSKEY_RP_ID to the browser-facing domain (hosted) or ' +
          'PASSKEY_ALLOW_LOCALHOST=true (local). Ceremonies will fail until one is set.'
        )
      }
    }

    console.log(`🔧 Extracted rpId: ${rpId} from SUPABASE_URL: ${supabaseUrl}`)
    return rpId
  } catch (e) {
    console.error('Failed to extract RP ID from URL:', supabaseUrl, 'Error:', e)
    return 'api.risaboss.com' // Fallback to production
  }
}

/**
 * Display names for the relying parties this deployment serves, keyed by rpId.
 *
 * This value lands in `rp.name`, which the OS passkey prompt renders inside a
 * trusted system dialog — so it is derived here from the allow-listed rpId and
 * never taken from a request, where a crafted link could put arbitrary text
 * ("Microsoft Security") into that prompt.
 */
const RP_ID_DISPLAY_NAMES: Record<string, string> = {
  'api.risaboss.com': 'BOSS',
  'localhost': 'BOSS',
}

/**
 * Get display name for the relying party
 */
export function getRpName(rpId?: string): string {
  return (rpId ? RP_ID_DISPLAY_NAMES[rpId] : undefined) ?? 'BOSS'
}

/**
 * Origins a BOSS ceremony may be performed from, in every deployment.
 *
 * `boss://authenticate` is the desktop deep-link origin. It has no effective
 * domain, so `rpIdMatchesOrigin` exempts it from origin/RP-ID correspondence —
 * it stays listed because a native client can still present it.
 */
const PRODUCTION_ORIGINS: readonly string[] = [
  'boss://authenticate',
  'https://risaboss.com',
  'https://api.risaboss.com'
]

/**
 * Origins that only exist on a developer's machine.
 */
const LOCAL_DEV_ORIGINS: readonly string[] = [
  'http://localhost:3000',
  'http://localhost:54321'  // Supabase local functions
]

/**
 * Origins a BOSS ceremony may be performed from.
 *
 * Single source of truth: the routes and the services both check it, and the
 * services re-export it for callers that predate this move. Loopback origins are
 * gated on the same dev signal as loopback RP IDs — a production deployment has
 * no reason to accept a ceremony claiming to come from someone's laptop.
 */
export function getAllowedOrigins(): readonly string[] {
  return isLocalDevEnvironment()
    ? [...PRODUCTION_ORIGINS, ...LOCAL_DEV_ORIGINS]
    : PRODUCTION_ORIGINS
}

/**
 * @deprecated Prefer `getAllowedOrigins()`, which honours the deployment.
 * Kept as a snapshot for call sites that read it as a constant.
 */
export const ALLOWED_ORIGINS: readonly string[] = [
  ...PRODUCTION_ORIGINS,
  ...LOCAL_DEV_ORIGINS
]

/**
 * Hosts that are `localhost`-equivalent, i.e. whatever machine the client
 * happens to be running on rather than a host BOSS controls.
 */
const LOOPBACK_RP_IDS: readonly string[] = ['localhost', '127.0.0.1', '[::1]', '::1']

/**
 * True when `rpId` is a legitimate relying party ID for a ceremony performed at
 * `origin` — equal to the origin's effective domain, or a registrable-domain
 * suffix of it (WebAuthn Level 2, §5.1.3 step 8 / §5.1.4 step 12).
 *
 * Origin and RP ID are otherwise checked against unrelated allow-lists, so
 * `http://localhost:3000` paired with `rpId: api.risaboss.com` satisfies both
 * lists while satisfying no browser. This ties them together.
 *
 * Non-web origins (`boss://authenticate`) have no effective domain to compare
 * against, so they are exempt rather than rejected.
 */
export function rpIdMatchesOrigin(rpId: string, origin: unknown): boolean {
  if (!rpId || typeof origin !== 'string' || origin.length === 0) return false

  let host: string
  try {
    const url = new URL(origin)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return true // no effective domain (custom scheme) — nothing to correspond to
    }
    host = url.hostname.toLowerCase()
  } catch {
    return false
  }

  const candidate = rpId.toLowerCase()
  return host === candidate || host.endsWith(`.${candidate}`)
}

/**
 * True when this deployment is a local development one.
 *
 * Deliberately *not* inferred from `getRpId()`: that maps the hosted gateway
 * host `kong` to `localhost` for WebAuthn compatibility, so a hosted deployment
 * looks local by that measure. Only an explicit opt-in or a genuinely loopback
 * `SUPABASE_URL` counts.
 */
export function isLocalDevEnvironment(): boolean {
  const optIn = Deno.env.get("PASSKEY_ALLOW_LOCALHOST")?.trim().toLowerCase()
  if (optIn) {
    return optIn === 'true' || optIn === '1' || optIn === 'yes'
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL") || ""
  return /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?(\/|$)/.test(supabaseUrl)
}

/**
 * RP IDs that BOSS ceremonies may legitimately be bound to.
 *
 * Why a set and not just `getRpId()`: inside the edge runtime `SUPABASE_URL`
 * points at the internal gateway, not at the domain the browser actually loads
 * (see the note on `generateRegistrationChallenge`). The mobile pages take
 * `rpId` as a query parameter and default it to `api.risaboss.com`, so the
 * effective RP ID of a ceremony is not always derivable from the environment.
 *
 * Every entry here is a BOSS-owned domain. `localhost` is **not** one — it is
 * whatever host the client runs on — so loopback RP IDs are only accepted in a
 * local development deployment, including when `getRpId()` derived `localhost`
 * from the `kong` gateway host. Deployments can add hosts with `PASSKEY_RP_ID` /
 * `PASSKEY_RP_ID_ALIASES` (comma-separated) without a code change.
 */
const BOSS_RP_IDS: readonly string[] = ['api.risaboss.com', 'risaboss.com']

export function getAllowedRpIds(): string[] {
  const allowed = new Set<string>()

  // Explicitly configured RP IDs. Kept separate so the loopback purge below
  // cannot drop a host an operator deliberately configured.
  const configured = new Set<string>()

  const customRpId = Deno.env.get("PASSKEY_RP_ID")
  if (customRpId?.trim()) {
    configured.add(customRpId.trim())
  }

  const aliases = Deno.env.get("PASSKEY_RP_ID_ALIASES")
  if (aliases) {
    for (const alias of aliases.split(',')) {
      const trimmed = alias.trim()
      if (trimmed) configured.add(trimmed)
    }
  }

  // Derived from the environment — may be `localhost`, because getRpId() maps
  // the `kong` gateway host to it even in a hosted deployment.
  const derived = getRpId()
  if (derived) {
    allowed.add(derived)
  }

  for (const rpId of BOSS_RP_IDS) {
    allowed.add(rpId)
  }

  if (isLocalDevEnvironment()) {
    // WebAuthn requires the literal host "localhost" for loopback ceremonies
    allowed.add('localhost')
  } else {
    for (const loopback of LOOPBACK_RP_IDS) {
      allowed.delete(loopback)
    }
  }

  for (const rpId of configured) {
    allowed.add(rpId)
  }

  return Array.from(allowed)
}
