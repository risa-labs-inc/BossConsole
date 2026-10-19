/**
 * The half of the Slack connect that talks to Slack.
 *
 * Same split as `google.ts`: routing stays in app.ts and the tests drive this with a fake
 * `fetch`. The user token leaves only as the return value, handed straight to the caller for
 * storage. Nothing here logs or throws it.
 */
import type { ExchangeFailure } from "./google.ts"

export const SLACK_TOKEN_URL = "https://slack.com/api/oauth.v2.access"

/** A Slack user token. Rotated tokens are `xoxe.xoxp-` and expire, so they do not match. */
const USER_TOKEN_PREFIX = "xoxp-"

/** Slack's own word for an outage, as opposed to a refusal of this code. */
const SLACK_OUTAGE_ERRORS = new Set([
  "ratelimited",
  "request_timeout",
  "service_unavailable",
  "internal_error",
  "fatal_error",
])

export interface SlackExchangeRequest {
  clientId: string
  clientSecret: string
  code: string
  redirectUri: string
}

export type SlackExchangeResult =
  | { ok: true; userToken: string; userId: string; teamId: string; teamName: string }
  | { ok: false; reason: ExchangeFailure }

/**
 * Exchange one authorization code for the authorizing user's token.
 *
 * Slack answers HTTP 200 with `{"ok":false,"error":"…"}` for a refused code, so the body, not the
 * status, decides. Any refusal is `bad_code` (a fresh link is the remedy for every one of them)
 * except Slack's outage errors, which are `unreachable`. A grant with no `xoxp-` user token is
 * `no_refresh_token`: there is nothing lasting to store. A bot token, if Slack sent one, is
 * ignored.
 */
export async function exchangeSlackCode(
  request: SlackExchangeRequest,
  fetchImpl: typeof fetch,
): Promise<SlackExchangeResult> {
  const body = new URLSearchParams({
    client_id: request.clientId,
    client_secret: request.clientSecret,
    code: request.code,
    redirect_uri: request.redirectUri,
  })
  let payload: Record<string, unknown>
  try {
    const response = await fetchImpl(SLACK_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    })
    if (response.status === 429 || response.status >= 500) {
      await response.body?.cancel().catch(() => {})
      return { ok: false, reason: "unreachable" }
    }
    payload = await response.json() as Record<string, unknown>
  } catch {
    return { ok: false, reason: "unreachable" }
  }
  if (typeof payload !== "object" || payload === null || Array.isArray(payload)) {
    return { ok: false, reason: "unreachable" }
  }
  if (payload.ok !== true) {
    const error = stringField(payload.error)
    if (!error || SLACK_OUTAGE_ERRORS.has(error)) return { ok: false, reason: "unreachable" }
    return { ok: false, reason: "bad_code" }
  }
  const user = record(payload.authed_user)
  const team = record(payload.team)
  // An org-wide (Enterprise Grid) install sends `team: null` and names the org under `enterprise`.
  const enterprise = record(payload.enterprise)
  const userToken = stringField(user?.access_token)
  // A rotating grant also carries a refresh token and an expiry; refuse on those, not only on
  // the prefix, so a renamed prefix cannot store a token that dies in twelve hours.
  const rotating = user?.refresh_token !== undefined || user?.expires_in !== undefined
  if (!userToken || !userToken.startsWith(USER_TOKEN_PREFIX) || rotating) {
    return { ok: false, reason: "no_refresh_token" }
  }
  return {
    ok: true,
    userToken,
    userId: stringField(user?.id) ?? "",
    teamId: stringField(team?.id) ?? stringField(enterprise?.id) ?? "",
    teamName: stringField(team?.name) ?? stringField(enterprise?.name) ?? "",
  }
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function stringField(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null
}
