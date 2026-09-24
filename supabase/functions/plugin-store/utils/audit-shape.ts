/**
 * Allowlist shaping for the API-key audit trail (plugin_api_key_logs).
 *
 * An audit row is evidence, not a message board. Everything the server puts in
 * one should be either an id/enum kind the SERVER chose, or a bounded forensic
 * value the edge derived from a header a trusted proxy actually set. The
 * caller, though, controls three of the four free-text inputs that reach this
 * table through log_api_key_action():
 *
 *   - p_ip_address   used to come from the LEFTMOST x-forwarded-for entry,
 *                    which is precisely whatever the caller typed (the
 *                    crash-report function documents this exact trap for its
 *                    rate limiter and takes the rightmost entry instead; this
 *                    boundary predates that fix and never got it). A caller
 *                    could make the "For security auditing" column carry any
 *                    string it wanted, of any length, forever.
 *   - p_user_agent   the raw header, unbounded, verbatim. A caller could park
 *                    megabytes of arbitrary content (PII, tokens, CRLF) in the
 *                    audit table, and any downstream consumer that renders or
 *                    line-splits these rows inherits it.
 *   - p_plugin_id    the caller's pluginId, unvalidated text at this boundary.
 *   - p_error_message accepted raw; no call site passes one today, but the
 *                    door was open for the first that does.
 *
 * None of those fields should ever carry caller-chosen prose. The shapes below
 * keep what is legitimately forensic and refuse the rest: a value that fails
 * its shape persists as NULL (or, for the action kind, as the constant
 * 'unknown') rather than as attacker text. Every current honest writer keeps
 * working: real hops set real IPs, real clients send bounded UAs, plugin ids
 * are dotted slugs, and every call site passes a fixed action constant.
 */

/** Longest textual IPv6 form; anything longer is not an address. */
export const AUDIT_IP_MAX = 45
/** Enough for a real UA token string; short of a text channel. */
export const AUDIT_USER_AGENT_MAX = 256
/** Matches crash-report's PLUGIN_ID_RE: dotted plugin ids, slugs, class names. */
export const AUDIT_PLUGIN_ID_RE = /^[A-Za-z0-9._-]{1,200}$/
/** Server-chosen action kinds: lowercase snake_case words like 'publish'. */
export const AUDIT_ACTION_RE = /^[a-z][a-z0-9_]{0,63}$/
/** Bounded server-generated diagnostics, not caller prose. */
export const AUDIT_ERROR_MESSAGE_MAX = 512

/**
 * Remove C0 control characters (including CR/LF) and DEL. A control char in a
 * persisted audit field is never forensic: it is either corruption or an
 * attempt to forge a second line in whatever dumps these rows later.
 */
function stripControlChars(value: string): string {
  // deno-lint-ignore no-control-regex
  return value.replace(/[\u0000-\u001f\u007f]/g, "")
}

function isIPv4(candidate: string): boolean {
  const parts = candidate.split(".")
  if (parts.length !== 4) return false
  return parts.every((p) => /^\d{1,3}$/.test(p) && Number(p) <= 255)
}

function isIPv6(candidate: string): boolean {
  // Allowlist, not a full RFC 3986 parser: hex groups, colons, an optional
  // IPv4 tail, and an optional zone id. Every textual IPv6 form carries at
  // least two colons, which keeps bare "."/":" garbage out. This accepts every
  // address a real proxy writes and refuses everything that is not
  // address-shaped (emails, URLs, markup, prose). Length is bounded before
  // we get here.
  const colons = (candidate.match(/:/g) ?? []).length
  if (colons < 2) return false
  return /^[0-9a-fA-F:.]+$/.test(candidate) ||
    (/^[0-9a-fA-F:.%]+$/.test(candidate) && candidate.includes("%"))
}

/**
 * The address to persist in the audit trail, taken from the hops the edge is
 * expected to have set: cf-connecting-ip first, else the RIGHTMOST
 * x-forwarded-for entry (trusted proxies append on the right; the leftmost
 * is whatever the caller typed). TRUST ASSUMPTION, stated rather than
 * implied: cf-connecting-ip is only CDN-set on the api.risaboss.com route -
 * on the *.supabase.co route it is just a client header, so there it narrows
 * a spoof to an address-shaped string rather than eliminating it (the same
 * assumption the sibling crash-report function documents). Anything that is
 * not address-shaped persists as null — an absent fact, never a fabricated
 * one.
 */
export function auditClientIp(
  cfConnectingIp: string | null | undefined,
  xff: string | null | undefined,
): string | null {
  const candidates: Array<string | null> = [
    cfConnectingIp?.trim() || null,
    xff?.split(",")[xff.split(",").length - 1]?.trim() || null,
  ]
  for (const candidate of candidates) {
    if (!candidate) continue
    if (candidate.length > AUDIT_IP_MAX) continue
    if (isIPv4(candidate) || isIPv6(candidate)) return candidate
  }
  return null
}

/**
 * Truncate without splitting a surrogate pair: a slice that ends on a lone
 * high surrogate is not text, and an unpaired surrogate serialized into the
 * audit payload is rejected by Postgres's JSON input - which the audit
 * caller's catch swallows, so the row would silently never be written.
 */
function truncateWellFormed(value: string, max: number): string {
  if (value.length <= max) return value
  const cut = value.slice(0, max)
  const last = cut.charCodeAt(max - 1)
  return last >= 0xd800 && last <= 0xdbff ? cut.slice(0, max - 1) : cut
}

/**
 * Bounded, control-char-free user agent, or null. The UA exists in the audit
 * trail "for debugging CI/CD issues"; a real one fits in 256 chars, and one
 * that does not is a text channel, not a UA.
 */
export function auditUserAgent(userAgent: string | null | undefined): string | null {
  if (!userAgent) return null
  const shaped = stripControlChars(userAgent).trim()
  if (!shaped) return null
  return truncateWellFormed(shaped, AUDIT_USER_AGENT_MAX)
}

/**
 * The plugin id the action was performed on, or null when the caller sent
 * anything that is not a plugin id shape. The store's ids are dotted slugs and
 * class names; this is the same shape crash-report enforces.
 */
export function auditPluginId(pluginId: string | null | undefined): string | null {
  if (!pluginId) return null
  return AUDIT_PLUGIN_ID_RE.test(pluginId) ? pluginId : null
}

/**
 * The action kind. Call sites pass server-defined constants ('publish',
 * 'version', 'finalize'); a value outside that vocabulary degrades to the
 * constant 'unknown' rather than persisting caller text as an enum kind.
 */
export function auditAction(action: string | null | undefined): string {
  if (!action) return "unknown"
  return AUDIT_ACTION_RE.test(action) ? action : "unknown"
}

/**
 * Bounded, control-char-free diagnostic text for failure rows, or null. Only
 * server-generated messages belong here; the shape is enforced now so the
 * first call site that passes one cannot quietly reopen the door.
 */
export function auditErrorMessage(message: string | null | undefined): string | null {
  if (!message) return null
  const shaped = stripControlChars(message).trim()
  if (!shaped) return null
  return truncateWellFormed(shaped, AUDIT_ERROR_MESSAGE_MAX)
}
