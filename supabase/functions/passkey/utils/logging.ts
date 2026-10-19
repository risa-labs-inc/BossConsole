/**
 * Log-safe renderings of user identifiers.
 *
 * Function logs are persistent and these call sites sit on unauthenticated
 * request paths, so raw emails and user ids - attacker-controlled input -
 * must not reach them. The masked forms keep just enough of the value to
 * correlate two lines from one request without storing the identifier.
 */

// Only bounded, single-line ASCII fragments may survive in persistent logs.
function safeFragment(value: string, maxLength: number): string {
  return value.slice(0, maxLength).replace(/[^A-Za-z0-9.-]/g, '_')
}

/** `victim@example.com` -> `v***@example.com` */
export function maskEmail(email: unknown): string {
  if (typeof email !== 'string' || email.length === 0) return '***'
  const at = email.indexOf('@')
  if (at === -1 || at === email.length - 1) return '***'
  const domain = safeFragment(email.slice(at + 1), 100)
  return `${at <= 1 ? '' : safeFragment(email[0], 1)}***@${domain}`
}

/** `de305d54-75b4-431b-adb2-eb6b9e546014` -> `de30…` */
export function maskUserId(userId: unknown): string {
  if (typeof userId !== 'string' || userId.length === 0) return '***'
  return userId.length <= 4 ? '***' : `${safeFragment(userId, 4)}…`
}

/** Passkey row ids use the same bounded prefix as user ids. */
export function maskPasskeyId(passkeyId: unknown): string {
  return maskUserId(passkeyId)
}

/** Keep a short prefix for correlation without persisting the polling secret. */
export function maskSessionId(sessionId: unknown): string {
  if (sessionId === null || sessionId === undefined) return 'none'
  if (typeof sessionId !== 'string' || sessionId.length <= 4) return '***'
  return `${safeFragment(sessionId, 4)}…`
}
