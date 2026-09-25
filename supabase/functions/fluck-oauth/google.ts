/**
 * The half of the OAuth flow that talks to Google.
 *
 * Isolated from routing so the tests can drive it with a fake `fetch` and never open a socket.
 * Nothing in here logs, returns or throws a token: the only values that leave are the refresh
 * token itself, handed straight to the caller for storage, and the account's email address.
 */

export const TOKEN_URL = "https://oauth2.googleapis.com/token"

export interface ExchangeRequest {
  clientId: string
  clientSecret: string
  code: string
  redirectUri: string
}

export type ExchangeResult =
  | { ok: true; refreshToken: string; email: string }
  | { ok: false; reason: ExchangeFailure }

/**
 * Why an exchange did not produce a stored grant.
 *
 * A closed vocabulary rather than the provider's own error string. Google's wording reaches a
 * phone browser otherwise, where `invalid_grant` is not information, and the three cases below
 * are the only ones with different remedies.
 */
export type ExchangeFailure =
  /** The code was used, expired, or never valid. The remedy is a fresh link. */
  | "bad_code"
  /** Google returned tokens but no refresh token, so there is nothing durable to store. */
  | "no_refresh_token"
  /** The token endpoint could not be reached or answered with something unparseable. */
  | "unreachable"

/**
 * Exchange one authorization code for a refresh token and the account's email.
 *
 * `redirect_uri` is sent again here and Google compares it BYTE FOR BYTE with the one in the
 * authorization request, trailing path included. Both come from the same configured public
 * base URL for that reason: two spellings that agree today would drift the day the custom
 * domain is switched on, and the failure surfaces as `redirect_uri_mismatch` on a consent
 * screen the user has already filled in.
 *
 * No `code_verifier` is sent because the flow uses no PKCE. The exchanging party is this
 * function, which is a CONFIDENTIAL client holding the web client secret in its own
 * environment, and that is precisely what PKCE substitutes for in a public client. The CSRF
 * property PKCE is sometimes reached for is provided instead by the signed, single use,
 * short lived `state`. A verifier would have had to travel to this function inside that same
 * state, adding a second secret to keep and no binding the signature does not already give.
 */
export async function exchangeCode(
  request: ExchangeRequest,
  fetchImpl: typeof fetch,
): Promise<ExchangeResult> {
  const body = new URLSearchParams({
    client_id: request.clientId,
    client_secret: request.clientSecret,
    code: request.code,
    grant_type: "authorization_code",
    redirect_uri: request.redirectUri,
  })
  let payload: Record<string, unknown>
  try {
    const response = await fetchImpl(TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    })
    payload = await response.json() as Record<string, unknown>
  } catch {
    return { ok: false, reason: "unreachable" }
  }
  if (typeof payload !== "object" || payload === null) {
    return { ok: false, reason: "unreachable" }
  }
  const refreshToken = stringClaim(payload.refresh_token)
  if (!refreshToken) {
    // `error` present means Google refused; absent means it issued an access token only,
    // which happens when the account has already consented and `prompt=consent` was dropped.
    // The two have different remedies, so they are different failures.
    return {
      ok: false,
      reason: stringClaim(payload.error) ? "bad_code" : "no_refresh_token",
    }
  }
  return { ok: true, refreshToken, email: emailFromIdToken(stringClaim(payload.id_token)) }
}

/**
 * The account's email, read out of the id_token payload without verifying it.
 *
 * Deliberately unverified, and safe here for one reason: this token arrived over TLS in the
 * body of a direct response from Google's own token endpoint, not from a browser. There is no
 * intermediary whose forgery the signature would catch. Verification would mean fetching and
 * caching Google's JWKS on a path whose only use for the value is a display label on a secret.
 *
 * An unreadable token gives the empty string rather than failing the connect: the email is a
 * label, and refusing a working grant because a label could not be parsed would be the wrong
 * trade. The caller substitutes a placeholder.
 */
export function emailFromIdToken(idToken: string | null): string {
  if (!idToken) return ""
  const parts = idToken.split(".")
  if (parts.length !== 3) return ""
  try {
    const padded = parts[1].replaceAll("-", "+").replaceAll("_", "/") +
      "=".repeat((4 - (parts[1].length % 4)) % 4)
    const claims = JSON.parse(
      new TextDecoder().decode(Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))),
    ) as Record<string, unknown>
    const email = stringClaim(claims.email)
    return email ?? ""
  } catch {
    return ""
  }
}

function stringClaim(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null
}
