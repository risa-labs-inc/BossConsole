/**
 * `fluck-oauth` - the OAuth redirect target for Fluck's Google connector.
 *
 * ## What this replaces
 *
 * Fluck used to serve its own callback from the machine it runs on, behind a Tailscale Funnel
 * hostname. That works until the machine moves, and it makes a registered redirect URI depend
 * on one person's tailnet. A Supabase edge function has a stable public URL, is reachable
 * whether or not that machine is awake, and is the only piece of the flow that needs to hold
 * the Google web client secret.
 *
 * ## The flow, end to end
 *
 * 1. `connect_service google` on the plugin mints a `state` signed with its install key (see
 *    `state.ts`) and builds
 *    a Google authorization URL. The model texts the link. Nothing is stored yet.
 * 2. The user taps it and approves on Google's own page.
 * 3. Google redirects the phone browser HERE, with `code` and `state`.
 * 4. This function verifies the state's signature and expiry, claims its nonce once, exchanges
 *    the code for a refresh token using the web client secret, and writes that token into BOSS
 *    Secret Manager as the BOSS user named in the state.
 * 5. The browser gets one sentence. Meanwhile the plugin, which has been polling the vault for
 *    that key, notices it, enables the Google connectors and tells the user in Messages.
 *
 * ## The trust boundary
 *
 * This handler is reachable by anyone and every route on it is unauthenticated, because the
 * caller is a browser following a redirect Google issued and carries no header we chose. The
 * `state` is therefore the entire authentication:
 *
 * - it is signed with the Ed25519 key of an install registered in `fluck_vault_instances`, and
 *   the row must be unrevoked and belong to the user the state names, so an install can only
 *   ever write for its own owner;
 * - its nonce is claimed exactly once, in the database, so a link that leaks into a browser
 *   history or a shared screenshot is dead on its second use;
 * - it expires within fifteen minutes.
 *
 * Nothing about the destination of the write is read off the request. `user_id` and the secret
 * key both come from inside the signed token.
 *
 * `POST /refresh` is the other route: an install trades its refresh token for an access token
 * through this function, because the web client secret lives only here. It is authenticated by
 * a signed request (see `signed.ts`), never by the state, and it only redeems a token the
 * callback bound (`fluck_oauth_grants`, by SHA-256) to the same BOSS user that owns the calling
 * install. Without that, any BOSS user who registered an install could redeem a stolen token.
 *
 * ## What is never written down
 *
 * No code, no token, no email, no state, in any log line or any response body. A log line is
 * the route, the outcome, and the first eight characters of the workspace id, which is enough
 * to line two attempts up against each other and nothing else.
 */
import { INSTANCE_ID_PATTERN, parseState, verifyState } from "./state.ts"
import { exchangeCode, type ExchangeFailure, refreshAccessToken } from "./google.ts"
import {
  bodyDigest,
  INSTANCE_HEADER,
  publicKeyBytes,
  SIGNATURE_HEADER,
  TIMESTAMP_HEADER,
  verifySigned,
} from "./signed.ts"

/** The default public base URL. Overridden by `PUBLIC_BASE_URL` when the custom domain lands. */
export const DEFAULT_PUBLIC_BASE_URL =
  "https://pcnwqamqdnsadranufjv.functions.supabase.co/fluck-oauth"

/** The one connector this function serves; part of the secret's key. */
const GOOGLE_CONNECTOR_ID = "google"

/** A refresh request is one small JSON object; anything bigger is not one. */
const MAX_REFRESH_BODY_BYTES = 4096

/** The env var a secret is keyed under, inside the Fluck key scheme. */
const REFRESH_ENV_VAR = "GOOGLE_REFRESH_TOKEN"

/** Matches `HostSecretVault`'s own note, so a Fluck secret reads the same wherever it was made. */
const SECRET_NOTE = "Created by Fluck when a service was connected. Do not edit by hand."

/** Shown as the secret's username when the id_token carried no email. */
const UNKNOWN_ACCOUNT = "google account"

export type NonceClaim = "claimed" | "replay" | "invalid" | "unavailable"

/** One live install: unknown and revoked are both null. */
export interface Instance {
  userId: string
  linkPublicKey: string
}

/** Who a refresh token was issued to, by its SHA-256. */
export type GrantOwner =
  | { status: "bound"; userId: string }
  | { status: "unbound" }
  | { status: "unavailable" }

export interface StoreRequest {
  userId: string
  website: string
  username: string
  refreshToken: string
  notes: string
}

/**
 * Everything the handler touches that is not pure, behind one interface.
 *
 * Same shape and same reason as `boss-ai`: `index.ts` wires the real Supabase client and the
 * real `fetch`, and the tests supply fakes, so the whole routing and decision surface is
 * exercised without a network or a database.
 */
export interface Dependencies {
  env(name: string): string | undefined
  fetch: typeof fetch
  /** Claim a nonce exactly once. `replay` means this state has already been spent. */
  claimNonce(nonce: string, expiresAtSeconds: number): Promise<NonceClaim>
  /** A live install by id (`fluck_vault_instance`, which drops revoked rows), or null. */
  instance(instanceId: string): Promise<Instance | null>
  /** Write the refresh token as the named BOSS user, replacing any earlier row for the key. */
  storeRefreshToken(request: StoreRequest): Promise<boolean>
  /** Record that the token with this SHA-256 hex was issued to this user. */
  bindGrant(tokenSha256: string, userId: string): Promise<boolean>
  /** The user a token hash is bound to. */
  grantOwner(tokenSha256: string): Promise<GrantOwner>
  /** Drop a binding whose grant Google has declared dead. */
  forgetGrant(tokenSha256: string): Promise<void>
  /** Milliseconds. Injected so the tests can sit on either side of an expiry. */
  now(): number
  log(line: string): void
}

const PAGE_CONNECTED = "Connected. You can close this and go back to Messages."
const PAGE_DECLINED = "The sign in was declined, so nothing was connected."
const PAGE_STALE = "That sign in link is no longer valid. Ask Fluck for a fresh one."
const PAGE_REPLAY = "That sign in link has already been used. Ask Fluck for a fresh one."
const PAGE_BAD_CODE = "That sign in did not finish. Ask Fluck for a fresh link."
const PAGE_NO_REFRESH =
  "Google did not hand over lasting access. Ask Fluck for a fresh link and approve everything it asks for."
const PAGE_UNREACHABLE =
  "Google could not be reached to finish the sign in. Worth trying again in a minute."
const PAGE_STORE_FAILED =
  "The sign in worked, but the credential could not be saved. Ask Fluck to try again."
const PAGE_UNCONFIGURED =
  "This sign in service is not set up yet. Whoever runs this BOSS has to finish setting it up."
const PAGE_NOT_FOUND = "There is nothing at this address."
const PAGE_NOT_A_BROWSER = "That link has to be opened in a browser."

const FAILURE_PAGES: Record<ExchangeFailure, string> = {
  bad_code: PAGE_BAD_CODE,
  no_refresh_token: PAGE_NO_REFRESH,
  unreachable: PAGE_UNREACHABLE,
}

export function createHandler(deps: Dependencies): (request: Request) => Promise<Response> {
  return async (request: Request) => {
    const path = routePath(new URL(request.url).pathname)
    if (path === "/health") return health(deps)
    if (path === "/client") return await client(request, deps)
    if (path === "/callback") return await callback(request, deps)
    if (path === "/refresh") return await refresh(request, deps)
    return page(404, "Not found", PAGE_NOT_FOUND)
  }
}

/**
 * The path this handler routes on.
 *
 * Both prefixes are stripped because the edge runtime serves a function at
 * `/functions/v1/<name>` while a custom domain may map it at `/<name>` or at the root. Routing
 * on whatever is left of the path makes all three deployments the same code, and the redirect
 * URI registered with Google is then purely a matter of which base URL is configured.
 */
export function routePath(pathname: string): string {
  const stripped = pathname
    .replace(/^\/functions\/v1/, "")
    .replace(/^\/fluck-oauth(?=\/|$)/, "")
  return stripped === "" ? "/" : stripped.replace(/\/+$/, "") || "/"
}

function health(deps: Dependencies): Response {
  // Reports whether the function CAN work, not whether any particular secret is correct.
  // A boolean per variable, never a value, so this stays safe to curl from anywhere.
  const configured = {
    clientId: Boolean(deps.env("GOOGLE_WEB_CLIENT_ID")),
    clientSecret: Boolean(deps.env("GOOGLE_WEB_CLIENT_SECRET")),
    githubClientId: Boolean(deps.env("GITHUB_OAUTH_CLIENT_ID")),
  }
  // GitHub is optional, so only the Google pair gates readiness.
  const ok = configured.clientId && configured.clientSecret
  return new Response(JSON.stringify({ ok, configured }), {
    status: ok ? 200 : 503,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  })
}

// The plugin builds the authorization URL with this id, so it must match the exchange's client.
async function client(request: Request, deps: Dependencies): Promise<Response> {
  if (request.method !== "GET") {
    await request.body?.cancel().catch(() => {})
    return json(405, { error: "method" })
  }
  // Untrimmed: the exchange sends the env value as is, and the two must be the same string.
  const clientId = deps.env("GOOGLE_WEB_CLIENT_ID")
  if (!clientId?.trim()) {
    deps.log("client unconfigured")
    return json(503, { error: "unconfigured" })
  }
  // The GitHub device flow id is public too and optional: omitted, not null, when unset.
  const githubClientId = deps.env("GITHUB_OAUTH_CLIENT_ID")?.trim()
  return json(
    200,
    githubClientId
      ? { client_id: clientId, github_client_id: githubClientId }
      : { client_id: clientId },
  )
}

async function callback(request: Request, deps: Dependencies): Promise<Response> {
  if (request.method !== "GET") {
    // Drained first: an unread body on a keep alive connection desyncs the next request on it.
    await request.body?.cancel().catch(() => {})
    return page(405, "Sign in problem", PAGE_NOT_A_BROWSER)
  }
  const query = new URL(request.url).searchParams
  const error = query.get("error")
  const code = query.get("code")
  const state = query.get("state")

  // Google's own word for it is not shown. `access_denied` is not information on a phone, and
  // the remedy is the same for every value it takes.
  if (error) {
    deps.log("callback declined")
    return page(400, "Sign in problem", PAGE_DECLINED)
  }

  const clientId = deps.env("GOOGLE_WEB_CLIENT_ID")
  const clientSecret = deps.env("GOOGLE_WEB_CLIENT_SECRET")
  if (!clientId || !clientSecret) {
    deps.log("callback unconfigured: client")
    return page(503, "Sign in problem", PAGE_UNCONFIGURED)
  }

  const nowSeconds = Math.floor(deps.now() / 1000)
  const parsed = state ? parseState(state) : null
  const instance = parsed ? await deps.instance(parsed.claims.iid) : null
  const publicKey = instance ? publicKeyBytes(instance.linkPublicKey) : null
  // The key lives on the desktop and its holder may sign any claims, so the install row, not
  // the token, decides whose secrets it may write.
  const verified = parsed && instance && publicKey && instance.userId === parsed.claims.uid &&
    await verifyState(publicKey, parsed, nowSeconds)
  if (!parsed || !verified || !code) {
    deps.log("callback refused: state")
    return page(400, "Sign in problem", PAGE_STALE)
  }
  const claims = parsed.claims
  const workspace = workspacePrefix(claims.ws)

  // Claimed BEFORE the exchange, not after. A code is single use at Google anyway, but the
  // nonce is what makes a REPLAYED link dead even when the first attempt failed, and claiming
  // it only on success would leave a link that can be retried until one attempt lands.
  // Scoped by install so one install's nonces can never collide with another's.
  const claim = await deps.claimNonce(`${claims.iid}.${claims.nonce}`, claims.exp)
  if (claim === "invalid") {
    deps.log(`callback refused: nonce validity [${workspace}]`)
    return page(400, "Sign in problem", PAGE_STALE)
  }
  if (claim === "replay") {
    deps.log(`callback refused: replay [${workspace}]`)
    return page(400, "Sign in problem", PAGE_REPLAY)
  }
  if (claim === "unavailable") {
    deps.log(`callback failed: nonce store [${workspace}]`)
    return page(503, "Sign in problem", PAGE_STORE_FAILED)
  }

  const redirectUri = (deps.env("PUBLIC_BASE_URL") || DEFAULT_PUBLIC_BASE_URL).replace(/\/+$/, "") +
    "/callback"
  const exchanged = await exchangeCode(
    { clientId, clientSecret, code, redirectUri },
    deps.fetch,
  )
  if (!exchanged.ok) {
    deps.log(`callback failed: ${exchanged.reason} [${workspace}]`)
    return page(400, "Sign in problem", FAILURE_PAGES[exchanged.reason])
  }

  // Bound BEFORE the secret is written: a failed bind then leaves no secret behind for the
  // plugin to find, whereas a failed store only leaves a hash nobody can redeem.
  const bound = await deps.bindGrant(await bodyDigest(exchanged.refreshToken), claims.uid)
  if (!bound) {
    deps.log(`callback failed: bind [${workspace}]`)
    return page(503, "Sign in problem", PAGE_STORE_FAILED)
  }

  const stored = await deps.storeRefreshToken({
    userId: claims.uid,
    website: `fluck/${claims.ws}/${GOOGLE_CONNECTOR_ID}/${REFRESH_ENV_VAR}`,
    username: exchanged.email || UNKNOWN_ACCOUNT,
    refreshToken: exchanged.refreshToken,
    notes: SECRET_NOTE,
  })
  if (!stored) {
    deps.log(`callback failed: store [${workspace}]`)
    return page(503, "Sign in problem", PAGE_STORE_FAILED)
  }
  deps.log(`callback connected [${workspace}]`)
  return page(200, "Connected", PAGE_CONNECTED)
}

/**
 * `POST /refresh`: `{"refresh_token":"…"}` in, `{"access_token","expires_in","scope"}` out.
 *
 * Every auth failure is the same 401. `{"error":"invalid_grant"}` (400) is Google saying the
 * grant is dead, the one answer the plugin acts on; any other Google refusal or outage is a
 * 502 `unavailable`, which is ours to fix and not a reason to drop the grant. A token not bound
 * to the install's owner is also `invalid_grant`, without asking Google, so the plugin's answer
 * (ask the owner to reconnect) is the same.
 *
 * Neither token is logged or stored. The log line is the outcome and an install id prefix.
 */
async function refresh(request: Request, deps: Dependencies): Promise<Response> {
  if (request.method !== "POST") {
    await request.body?.cancel().catch(() => {})
    return json(405, { error: "method" })
  }
  const body = await request.text()
  const instanceId = request.headers.get(INSTANCE_HEADER)
  const instance = instanceId && INSTANCE_ID_PATTERN.test(instanceId)
    ? await deps.instance(instanceId)
    : null
  const ok = instance !== null && await verifySigned({
    publicKey: instance.linkPublicKey,
    method: "POST",
    path: "/refresh",
    body,
    timestamp: request.headers.get(TIMESTAMP_HEADER),
    signature: request.headers.get(SIGNATURE_HEADER),
    nowSeconds: Math.floor(deps.now() / 1000),
  })
  if (!ok || !instance || !instanceId) {
    deps.log("refresh refused: unauthorized")
    return json(401, { error: "unauthorized" })
  }
  const tag = instanceId.slice(0, 8)

  let refreshToken: unknown
  try {
    if (body.length > MAX_REFRESH_BODY_BYTES) throw new Error()
    const parsed = JSON.parse(body)
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error()
    refreshToken = parsed.refresh_token
  } catch {
    return json(400, { error: "body" })
  }
  if (typeof refreshToken !== "string" || refreshToken.length === 0) {
    return json(400, { error: "body" })
  }

  const clientId = deps.env("GOOGLE_WEB_CLIENT_ID")
  const clientSecret = deps.env("GOOGLE_WEB_CLIENT_SECRET")
  if (!clientId || !clientSecret) {
    deps.log("refresh unconfigured: client")
    return json(503, { error: "unconfigured" })
  }

  const tokenSha256 = await bodyDigest(refreshToken)
  const owner = await deps.grantOwner(tokenSha256)
  if (owner.status === "unavailable") {
    deps.log(`refresh failed: grant store [${tag}]`)
    return json(502, { error: "unavailable" })
  }
  if (owner.status === "unbound" || owner.userId !== instance.userId) {
    deps.log(`refresh refused: unbound [${tag}]`)
    return json(400, { error: "invalid_grant" })
  }

  const result = await refreshAccessToken({ clientId, clientSecret, refreshToken }, deps.fetch)
  if (!result.ok) {
    deps.log(`refresh failed: ${result.reason} [${tag}]`)
    if (result.reason === "invalid_grant") await deps.forgetGrant(tokenSha256).catch(() => {})
    return result.reason === "invalid_grant"
      ? json(400, { error: "invalid_grant" })
      : json(502, { error: "unavailable" })
  }
  deps.log(`refresh ok [${tag}]`)
  return json(200, {
    access_token: result.accessToken,
    expires_in: result.expiresIn,
    scope: result.scope,
  })
}

function json(status: number, value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  })
}

/**
 * The first eight characters of a workspace id, for a log line.
 *
 * Enough to tell two attempts apart while reading a log, and short of identifying anyone: a
 * Fluck workspace id is already a derived value, and a prefix of one is not a phone number.
 */
function workspacePrefix(workspaceId: string): string {
  return workspaceId.slice(0, 8)
}

/**
 * The whole user interface: one sentence, no assets, no script, no third party.
 *
 * Nothing is interpolated except [message], which is only ever one of this module's own
 * constants. It is never a query parameter, never the provider's error, and never the code.
 */
export function page(status: number, title: string, message: string): Response {
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex"><title>${escapeHtml(title)}</title>
<style>body{margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;
padding:24px;background:#fff;color:#111;font:17px/1.5 -apple-system,BlinkMacSystemFont,
"Segoe UI",Roboto,sans-serif}p{max-width:22em;text-align:center}
@media(prefers-color-scheme:dark){body{background:#111;color:#eee}}</style>
</head><body><p>${escapeHtml(message)}</p></body></html>`
  return new Response(html, {
    status,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
    },
  })
}

function escapeHtml(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#39;")
}

export const PAGES = {
  connected: PAGE_CONNECTED,
  declined: PAGE_DECLINED,
  stale: PAGE_STALE,
  replay: PAGE_REPLAY,
  badCode: PAGE_BAD_CODE,
  noRefresh: PAGE_NO_REFRESH,
  unreachable: PAGE_UNREACHABLE,
  storeFailed: PAGE_STORE_FAILED,
  unconfigured: PAGE_UNCONFIGURED,
  notFound: PAGE_NOT_FOUND,
  notABrowser: PAGE_NOT_A_BROWSER,
}
