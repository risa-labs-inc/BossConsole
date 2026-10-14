/**
 * CORS origin allowlist for the plugin-store function.
 *
 * `boss://plugins` is the desktop client's deep-link origin and
 * `https://risaboss.com` the production site; both ship to every deployment.
 *
 * `http://localhost:3000` is whatever machine the caller happens to be on, not
 * an origin BOSS controls, yet it shipped to production in the allowlist
 * alongside `credentials: true` (issue #852) - handing any local page
 * credentialed access to the deployed function. It is now admitted only when
 * the deployment itself looks like a local development stack, or an operator
 * explicitly opts in - the same gate passkey's `isLocalDevEnvironment` applies
 * to its loopback RP IDs and origins.
 */

const PRODUCTION_ORIGINS: readonly string[] = [
  'boss://plugins',
  'https://risaboss.com',
]

/** Origins that only exist on a developer's machine. */
const LOCAL_DEV_ORIGINS: readonly string[] = [
  'http://localhost:3000',
]

/**
 * True when this isolate is serving a local development stack, or a hosted one
 * whose operator explicitly allowed loopback origins.
 *
 * The SUPABASE_URL heuristic mirrors passkey's `isLocalDevEnvironment`: a
 * local `supabase start` points it at 127.0.0.1, a hosted deployment at its
 * real domain.
 */
export function isLocalDevEnvironment(): boolean {
  const optIn = Deno.env.get("PLUGIN_STORE_ALLOW_LOCALHOST")?.trim().toLowerCase()
  if (optIn) {
    return optIn === 'true' || optIn === '1' || optIn === 'yes'
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL") || ""
  return /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?(\/|$)/.test(supabaseUrl)
}

/** Origins the function may send credentialed CORS replies to. */
export function pluginStoreCorsOrigins(): string[] {
  return isLocalDevEnvironment()
    ? [...PRODUCTION_ORIGINS, ...LOCAL_DEV_ORIGINS]
    : [...PRODUCTION_ORIGINS]
}
