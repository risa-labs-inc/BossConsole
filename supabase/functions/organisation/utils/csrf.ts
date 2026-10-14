/**
 * CSRF: cookie-bound double-submit.
 *
 * WHY SameSite=Lax IS NOT ENOUGH HERE. api.risaboss.com also serves
 * plugin-store's Swagger UI, which loads third-party CDN JavaScript, and
 * passkey's HTML pages. Those are the SAME ORIGIN as this function, so
 * SameSite gives nothing against them and `Path` scoping gives nothing either
 * (same-origin script can fetch any path with credentials). Lax only stops the
 * cross-SITE case.
 *
 * The nonce lives INSIDE the signed HttpOnly session cookie, so page script
 * cannot read it from document.cookie, and because it is bound into the session
 * payload it cannot be swapped between sessions: a token minted for session A
 * fails against session B.
 *
 * BUT cookie placement alone does not keep the nonce from the adversary it is
 * meant for: csrfField() (views/layout.ts) renders it into every mutating form
 * on the admin and plugin pages, and those pages embed third-party JavaScript.
 * A same-origin script can fetch the admin page with credentials, parse the
 * nonce out of the response HTML, and post it back - carrying a VALID,
 * session-matched nonce. The token check alone cannot tell that post from a
 * real form submission, so it is not, by itself, a same-origin defence.
 *
 * The second leg is the fetch-metadata signal page script cannot forge
 * outright: Sec-Fetch-Mode and Sec-Fetch-Dest are set by the browser and are
 * forbidden headers for script to set. A real form submission always arrives
 * with Sec-Fetch-Mode: navigate and Sec-Fetch-Dest: document; fetch()/XHR /
 * sendBeacon never send "navigate" (they send "cors", "same-origin" or
 * "no-cors"). A mutating request whose Sec-Fetch-Mode is PRESENT and not
 * "navigate" is rejected as script-driven, whatever nonce it harvested.
 *
 * That mode check does NOT stop every script-driven post: page script can
 * also cause a NAVIGATION - build a form, target it at a hidden iframe and
 * call submit() - and a navigation legitimately arrives with Sec-Fetch-Mode:
 * navigate. That variant is caught by the second signal: a form pointed at a
 * frame sends Sec-Fetch-Dest: iframe, not document, so a present Dest header
 * that is not "document" is rejected too. What remains is a script forcing a
 * VISIBLE top-level navigation (dest: document), which navigates the
 * operator's own window - noisy, and the harvest-and-post loop cannot hide.
 *
 * These headers are defence in depth, not the durable fix: the hole closes
 * when the nonce and third-party CDN script stop sharing an origin (no
 * Swagger UI / third-party JS on the admin origin, and a CSP that stops the
 * CDN script executing on these pages).
 *
 * When Sec-Fetch-Mode is ABSENT the request did not come from browser-driven
 * script (or is from a browser old enough not to ship the header): curl, CLI
 * integrations, and this function's own test suite. Those fall through to the
 * existing checks unchanged - the gate only ever adds a signal.
 *
 * Order in every mutating handler, and the order matters:
 *   requireCsrf  ->  live is_org_admin probe  ->  rate limit  ->  validate  ->  RPC
 * CSRF first, because a forged request should be rejected before it can consume
 * rate-limit budget or reach a probe that touches the database.
 */

import type { SessionPayload } from "./session.ts"

/** Hidden form field carrying the nonce back. */
export const CSRF_FIELD = "csrf_token"

export type CsrfFailure =
  | "missing_token"
  | "bad_token"
  | "bad_origin"
  | "bad_fetch_mode"
  | "bad_fetch_dest"

/** Constant-time string comparison over UTF-8 bytes. */
function timingSafeEqualStrings(a: string, b: string): boolean {
  const enc = new TextEncoder()
  const x = enc.encode(a)
  const y = enc.encode(b)
  if (x.length !== y.length) return false
  let diff = 0
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i]
  return diff === 0
}

/**
 * True when the request's declared initiator is this same site.
 *
 * Sec-Fetch-Site is the reliable signal where it exists; every browser that
 * ships SameSite also ships it. `Origin` is the fallback for the rest.
 *
 * A request with NEITHER header is refused. That is stricter than the common
 * "no Origin means same-origin" convention, and deliberately so: form posts
 * from a browser always carry at least one, so the header-less case is a
 * non-browser client, which has no business posting to an HTML admin form.
 */
export function originIsSameSite(
  secFetchSite: string | null,
  origin: string | null,
  expectedOrigin: string | null,
): boolean {
  if (secFetchSite) {
    return secFetchSite === "same-origin" || secFetchSite === "same-site" || secFetchSite === "none"
  }
  if (origin && expectedOrigin) {
    return origin.toLowerCase() === expectedOrigin.toLowerCase()
  }
  return false
}

/**
 * Validate a mutating request's CSRF posture.
 *
 * `submitted` is the form field; `session` supplies the expected nonce. Returns
 * null when the request is acceptable, or the reason it is not.
 *
 * Sec-Fetch-Site: "none" is accepted because it means a user-initiated
 * navigation with no initiator (typed URL, bookmark), which cannot be forged by
 * another page. It never accompanies a cross-origin form post.
 *
 * `secFetchMode` is the gate against same-origin script carrying a HARVESTED
 * valid nonce (see the file header). A browser form post always sends
 * "navigate"; fetch()/XHR/sendBeacon never can. Script can still submit a
 * form programmatically - a navigation, which honestly reports "navigate" -
 * so `secFetchDest` adds the second signal: a real form post targets the top
 * level and sends "document", while a form hidden in an iframe sends
 * "iframe". Absent means a non-browser client, and such a request is judged
 * by the remaining checks alone, exactly as before.
 */
export function checkCsrf(input: {
  session: SessionPayload
  submitted: unknown
  secFetchSite: string | null
  secFetchMode: string | null
  secFetchDest: string | null
  origin: string | null
  expectedOrigin: string | null
}): CsrfFailure | null {
  if (
    !originIsSameSite(input.secFetchSite, input.origin, input.expectedOrigin)
  ) {
    return "bad_origin"
  }
  // Runs before the token checks on purpose: a script-driven post with a valid
  // harvested nonce must be refused (and logged) as what it is, not fall
  // through on a token that happens to match.
  if (input.secFetchMode !== null && input.secFetchMode !== "navigate") {
    return "bad_fetch_mode"
  }
  // A form post is always a TOP-LEVEL navigation (dest "document"); script
  // that hides its navigation in an iframe sends "iframe" and dies here.
  if (input.secFetchDest !== null && input.secFetchDest !== "document") {
    return "bad_fetch_dest"
  }
  if (typeof input.submitted !== "string" || input.submitted.length === 0) {
    return "missing_token"
  }
  if (!timingSafeEqualStrings(input.submitted, input.session.csrf)) {
    return "bad_token"
  }
  return null
}
