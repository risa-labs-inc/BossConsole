/**
 * `fluck-vault` — the page on which a password, a card or a CVV is typed.
 *
 * ## What it is for
 *
 * Fluck cannot ask for a password or a card number in Messages. The relay that carries those
 * messages sees plaintext, iMessage keeps them forever on two devices and in a backup, and a
 * model that ever held the value would have it in a dump, a log line and a memory summary. So
 * the agent texts a link instead, and the value is typed here, once, on a page with no script
 * of its own beyond the one that encrypts it.
 *
 * ## The sealed design, and what this function is therefore NOT
 *
 * It is not a secret store and it holds no key that opens anything. The page encrypts in the
 * browser to a public key whose private half lives only on the DGX (`seal.ts`), and this
 * function writes the opaque result into a staging table the DGX drains. It has no grant on
 * `public.secrets`, it cannot call `decrypt_text`, and a full compromise of it yields a queue
 * of blobs it cannot read. That is red team D1 and D2, and it is the single most important
 * property in this file.
 *
 * ## The link is the entire authentication, so:
 *
 * - The token is signed with **Ed25519 by the DGX**. This end holds only a public key, so a
 *   compromise here cannot MINT a link, only check one. (D5.)
 * - The claims are opaque. No merchant, no last four, no total: the URL passes through the
 *   relay, and those come from a row keyed by `jti` instead. (C3.)
 * - **GET does not consume the link.** iMessage, the relay's own unfurler and any middlebox in
 *   between will fetch the URL before the owner ever taps it, and a GET that spent the nonce
 *   would hand the owner a dead link every single time. Consumption happens on POST, inside one
 *   conditional update. (C1.)
 * - The first GET sets an `HttpOnly; Secure; SameSite=Strict` cookie and the POST requires it,
 *   so the link is bound to the device that opened it and a shoulder surfer with a second
 *   phone cannot finish what the owner started. (C2.)
 * - Every failure renders ONE fixed page. Nothing from the token or the row is ever echoed
 *   into an error. (C4.)
 *
 * ## What gets written down
 *
 * A log line is the route, the purpose, eight characters of workspace id, the `jti`, and the
 * outcome. Never a body, never a claim, never a merchant, never anything typed on the page.
 */
import {
  type Kind,
  MAX_CVV_AGE_SECONDS,
  MAX_VAULT_AGE_SECONDS,
  peekJti,
  publicKeyBytes,
  type Purpose,
  verifyLink,
} from "./token.ts"
import { SIGNATURE_HEADER, TIMESTAMP_HEADER, verifySigned } from "./signed.ts"
import { looksSealed, sealPublicKey } from "./seal.ts"
import { form, message } from "./page.ts"
import { isKnownCurrency, MAX_MINOR_AMOUNT, minorUnitExponent } from "./currency.ts"

/**
 * Where the function answers.
 *
 * The variable is `FLUCK_VAULT_BASE_URL`, NOT the project-wide `PUBLIC_BASE_URL` the sibling
 * functions read. Edge secrets are set per project rather than per function, and this one
 * decides the token AUDIENCE: sharing it would mean that pointing another function at a custom
 * domain silently invalidates every vault link the DGX has ever minted, with no failure
 * anywhere except a page that says the link is no longer valid. Found the hard way, on the
 * first link this function ever served.
 */
export const DEFAULT_PUBLIC_BASE_URL = "https://api.risaboss.com/functions/v1/fluck-vault"

/** How long a stored blob waits for the DGX to drain it, by purpose. Minutes. */
export const INBOX_TTL_MINUTES: Record<Purpose, number> = { vault: 15, cvv: 10 }

/** The cookie that binds a link to one device. */
export const COOKIE_NAME = "fv"

/** Cookie lifetime. Longer than any token, so the cookie is never the thing that expires. */
const COOKIE_MAX_AGE_SECONDS = 1800

export type StoreOutcome = "stored" | "gone" | "unavailable"

export interface StoreResult {
  outcome: StoreOutcome
  /** What was stored, so the POST can render the right sentence without holding a token. */
  kind: Kind | "cvv" | null
}

export interface VaultRequestRow {
  jti: string
  ws: string
  purpose: Purpose
  kind: Kind | null
  alias: string | null
  purchaseId: string | null
  merchant: string | null
  brand: string | null
  last4: string | null
  totalCents: number | null
  currency: string | null
  /** The install that minted the row and its public keys; null on the legacy env keys. */
  instance: RowInstance | null
}

export interface RowInstance {
  id: string
  linkPublicKey: string
  sealPublicKey: string
}

/** One registered install. Public keys only, standard base64. */
export interface Instance {
  instanceId: string
  userId: string
  linkPublicKey: string
  sealPublicKey: string
  /**
   * Whether an operator has approved this install to mint CVV and vault links. Registration
   * alone never grants it: a signed-in account is not enough to put a page on this domain.
   */
  issuanceApproved: boolean
}

/** A first registration, or a same-key re-registration. Never a key change. */
export interface Registration {
  instanceId: string
  userId: string
  linkPublicKey: string
  sealPublicKey: string
}

/**
 * `rotation_requires_proof`: the id exists with other keys, and a key change has to go through
 * `rotateInstance` with a signature from the current key. `unauthorized` is only returned by
 * the session-scoped SQL wrapper the plugin calls, never on this function's path.
 */
export type RegisterOutcome =
  | "ok"
  | "conflict"
  | "revoked"
  | "limit"
  | "invalid"
  | "rotation_requires_proof"
  | "unauthorized"
  | "unavailable"

/** A key change, already proven with the current link key by the caller. */
export interface Rotation extends Registration {
  /** The link key the proof verified against. The write is refused if it is no longer current. */
  expectedLinkPublicKey: string
}

/** `stale`: the keys changed between the proof check and the write. */
export type RotateOutcome = "ok" | "conflict" | "revoked" | "stale" | "invalid" | "unavailable"

/** What the DGX asks this function to write when it mints a link. Non secret facts only. */
export interface CreateRequest {
  jti: string
  ws: string
  purpose: Purpose
  kind: Kind | null
  alias: string | null
  purchaseId: string | null
  merchant: string | null
  brand: string | null
  last4: string | null
  totalCents: number | null
  currency: string | null
  /** Unix seconds. The token's own expiry, checked against the policy before it is written. */
  expiresAt: number
  /** The install whose key signed the mint; null on the legacy env key. */
  instanceId: string | null
}

/** One drained inbox row. `ciphertext` is base64; nothing here can open it. */
export interface ClaimedItem {
  id: string
  jti: string
  purpose: Purpose
  kind: string | null
  alias: string | null
  purchaseId: string | null
  ciphertext: string
  createdAt: string
}

export interface StoreRequest {
  jti: string
  ciphertext: string
  cookieHash: string
  ttlMinutes: number
}

export interface Dependencies {
  env(name: string): string | undefined
  /** The non secret half of a request row, for rendering. Null if it is missing or spent. */
  describeRequest(jti: string): Promise<VaultRequestRow | null>
  /** Consume the request and stage the blob, in one statement. */
  store(request: StoreRequest): Promise<StoreResult>
  /** Write the row for a link the DGX is about to sign. False if the id is already taken. */
  createRequest(request: CreateRequest): Promise<boolean>
  /** Return and delete every unclaimed row for one workspace and install (null: legacy rows). */
  claimInbox(ws: string, instanceId: string | null): Promise<ClaimedItem[]>
  /** One live install, or null if it is unknown or revoked. */
  instance(instanceId: string): Promise<Instance | null>
  /** Register an install for its owner. Refuses to change an existing install's keys. */
  registerInstance(registration: Registration): Promise<RegisterOutcome>
  /** Change an install's keys, compare-and-swap on the current link key. */
  rotateInstance(rotation: Rotation): Promise<RotateOutcome>
  /** The Supabase user id an access token belongs to, or null. */
  userFromToken(accessToken: string): Promise<string | null>
  /** Milliseconds. Injected so the tests can sit on either side of an expiry. */
  now(): number
  log(line: string): void
}

// --------------------------------------------------------------------------------------------
// Copy. Every string a browser can see is here, and every one of them is a constant.
// --------------------------------------------------------------------------------------------

const COPY = {
  passwordTitle: "Save a login",
  passwordIntro: "Fluck never sees it in Messages.",
  passwordSubmit: "Save",
  passwordNote: "Encrypted in this browser before it is sent.",
  cardTitle: "Add a card",
  cardIntro: "Add a card and the most Fluck may spend on it. Fluck never sees it in Messages.",
  cardSubmit: "Add card",
  cardNote:
    "The card and its security code are encrypted on this device, so only your Fluck can read them.",
  // This page only seals the code and stages it for the install that minted the link. It does
  // not charge anything, so nothing here may read as a payment.
  cvvTitle: "Card security code",
  cvvIntro: "Enter the card security code.",
  cvvSubmit: "Send code",
  cvvNote:
    "This does not charge your card. Only continue if these details match a purchase you asked Fluck to make, and do not forward this link. The code is encrypted on this device and held for up to ten minutes until your Fluck collects it; Fluck keeps it, encrypted, for purchases you approve.",
  savedTitle: "Saved",
  saved: "Saved. You can go back to Messages.",
  cardSavedTitle: "Card added",
  cardSaved: "Card added. It is only used when you approve a purchase.",
  cvvDoneTitle: "Code sent",
  cvvDone:
    "Code sent to your Fluck, encrypted. Your card has not been charged. You can go back to Messages.",
  badTitle: "Link problem",
  bad: "That link is not valid any more. Ask Fluck for a fresh one.",
  busyTitle: "Too many tries",
  busy: "Too many attempts. Wait a few minutes and ask Fluck for a fresh link.",
  downTitle: "Try again",
  down: "That could not be saved just now. Ask Fluck for a fresh link and try again.",
  unsetTitle: "Not set up",
  unset: "This page is not set up yet. Whoever runs this BOSS has to finish setting it up.",
  notFoundTitle: "Nothing here",
  notFound: "There is nothing at this address.",
} as const

/** Exported so the tests assert the rendered copy rather than a paraphrase of it. */
export const PAGES = COPY

// --------------------------------------------------------------------------------------------
// Rate limiting
// --------------------------------------------------------------------------------------------

/**
 * Best effort, in memory, per isolate.
 *
 * ## The limitation, stated plainly
 *
 * The edge runtime may run several isolates and recycles them, so these counters are neither
 * shared nor durable. An attacker with enough patience or enough luck gets more than the
 * numbers below. They are here to stop the ordinary cases: a stuck retry loop, someone jabbing
 * refresh, a naive script. They are NOT the control that makes a leaked link safe. That is the
 * signature, the device cookie, the ten minute expiry and the single use consume, all of which
 * hold however many isolates there are.
 *
 * A table would make them exact and would cost a round trip on the one path that has to work
 * while someone stands at a checkout. If the DGX side ever sees this being abused, the right
 * answer is a limit where the link is MINTED (red team C6), not a slower page.
 */
const JTI_GET_LIMIT = 5
const JTI_POST_LIMIT = 3
const IP_LIMIT = 20
const IP_WINDOW_MS = 10 * 60 * 1000

interface Counter {
  count: number
  resetAt: number
}

const counters = new Map<string, Counter>()

/** Exported for the tests, which need a clean slate between cases. */
export function resetRateLimits(): void {
  counters.clear()
}

function hit(key: string, limit: number, windowMs: number, nowMs: number): boolean {
  // Bounded work, bounded memory: expired entries are dropped whenever the map gets large,
  // so a long lived isolate cannot be grown without limit by hammering distinct keys.
  if (counters.size > 4096) {
    for (const [k, v] of counters) if (v.resetAt <= nowMs) counters.delete(k)
  }
  const existing = counters.get(key)
  if (!existing || existing.resetAt <= nowMs) {
    counters.set(key, { count: 1, resetAt: nowMs + windowMs })
    return true
  }
  existing.count += 1
  return existing.count <= limit
}

/**
 * The client address, as far as it can be known.
 *
 * Behind the edge gateway the socket peer is the gateway, so the forwarded header is all there
 * is. It is client supplied and therefore spoofable, which is another reason the IP limit is a
 * courtesy rather than a control.
 */
function clientAddress(request: Request): string {
  const forwarded = request.headers.get("x-forwarded-for")
  if (forwarded) return forwarded.split(",")[0].trim()
  return request.headers.get("cf-connecting-ip") ?? "unknown"
}

// --------------------------------------------------------------------------------------------
// Unfurlers
// --------------------------------------------------------------------------------------------

const UNFURLER_PATTERN =
  /bot|crawler|spider|preview|scraper|facebookexternalhit|slackbot|twitterbot|whatsapp|telegram|discord|linkedin|skype|applebot|curl|wget|python-requests|okhttp|go-http-client|open-graph/i

/**
 * Is this a browser someone is looking at, or a machine fetching a preview?
 *
 * Two independent signals, and either one is enough to decline. A client that does not say it
 * wants HTML is not rendering a page for a human, and a user agent that names itself a bot is
 * taking us at our word. Both are answered with 204 and nothing else: no body to cache, no
 * preview image, no Open Graph tags for Apple to keep. (Red team C1.)
 *
 * Getting this WRONG in the cautious direction is cheap, because a GET does not consume
 * anything: a real browser misdetected as a bot just needs a reload. Getting it wrong the other
 * way costs a cached preview of a payment page on someone else's servers.
 */
export function isUnfurler(request: Request): boolean {
  if (!(request.headers.get("accept") ?? "").includes("text/html")) return true
  const agent = request.headers.get("user-agent") ?? ""
  if (agent.length === 0) return true
  return UNFURLER_PATTERN.test(agent)
}

// --------------------------------------------------------------------------------------------
// Device binding
// --------------------------------------------------------------------------------------------

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
}

/**
 * One cookie NAME per link, which is the whole of the fix for the 2026-09-20 report.
 *
 * The value has always been `<jti>.<32 random bytes>`, and the comment here used to claim that
 * was enough for two links at once — "the browser would otherwise send whichever cookie was
 * written last". It is not. A single name at a single path holds exactly ONE value: the second
 * link's GET does not sit alongside the first one's cookie, it REPLACES it. The `jti` prefix
 * only makes the loss detectable, and what it detects is read as a refusal.
 *
 * That is exactly what the owner hit. Three links were texted 88 and 121 seconds apart, they
 * opened more than one, typed into a page that was no longer the one holding the cookie, and
 * got the fixed "this link is no longer valid" page with `no device cookie` in the log — for a
 * link that was inside its ten minutes, unconsumed, and correctly signed.
 *
 * So the name carries the `jti` and every open link keeps its own binding. The ceiling on
 * outstanding links is five, the lifetime is half an hour, and a POST clears its own, so the
 * jar cannot grow without bound. `SameSite=Strict` still stops a page on another origin from
 * POSTing this form with the owner's cookie attached — the submit is same-site, from our page
 * to our origin, so Strict was never the thing in the way — and `HttpOnly` keeps it away from
 * any script at all, including ours, which has no use for it.
 */
export function cookieName(jti: string): string {
  return `${COOKIE_NAME}_${jti}`
}

export function cookieValue(jti: string): string {
  return `${jti}.${randomToken()}`
}

export function setCookieHeader(jti: string, path: string): string {
  return `${cookieName(jti)}=${cookieValue(jti)}; Path=${path}` +
    `; Max-Age=${COOKIE_MAX_AGE_SECONDS}; Secure; HttpOnly; SameSite=Strict`
}

/** The header that retires one link's cookie. Same attributes, or a browser keeps the old one. */
export function clearCookieHeader(jti: string): string {
  return `${cookieName(jti)}=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict`
}

/**
 * This link's device cookie, or null.
 *
 * The bare `fv=` name is still read, because a page rendered by the previous deployment is
 * sitting in somebody's browser right now with that cookie and its own value, and refusing it
 * would turn this fix into a second outage for exactly the person it is for. It is still
 * required to carry this link's `jti`, so it authorises nothing it did not already authorise.
 */
export function readCookie(request: Request, jti: string): string | null {
  const header = request.headers.get("cookie")
  if (!header) return null
  const names = [cookieName(jti), COOKIE_NAME]
  for (const part of header.split(";")) {
    const trimmed = part.trim()
    const name = names.find((n) => trimmed.startsWith(`${n}=`))
    if (!name) continue
    const value = trimmed.slice(name.length + 1)
    if (value.startsWith(`${jti}.`) && value.length > jti.length + 1) return value
  }
  return null
}

/** Is there an `fv` cookie at all, for another link? A refusal reason, never a value. */
export function hasForeignCookie(request: Request, jti: string): boolean {
  const header = request.headers.get("cookie")
  if (!header) return false
  return header.split(";").some((part) => {
    const trimmed = part.trim()
    return trimmed.startsWith(`${COOKIE_NAME}=`) || trimmed.startsWith(`${COOKIE_NAME}_`)
  }) && readCookie(request, jti) === null
}

/** SHA-256 hex. What is stored on consume, so the cookie itself is not at rest anywhere. */
export async function hashCookie(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value) as BufferSource,
  )
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("")
}

// --------------------------------------------------------------------------------------------
// Plaintext refusal
// --------------------------------------------------------------------------------------------

function luhn(value: string): boolean {
  if (value.length < 13 || value.length > 19) return false
  let sum = 0
  let alternate = false
  for (let i = value.length - 1; i >= 0; i--) {
    let digit = value.charCodeAt(i) - 48
    if (digit < 0 || digit > 9) return false
    if (alternate) {
      digit *= 2
      if (digit > 9) digit -= 9
    }
    sum += digit
    alternate = !alternate
  }
  return sum % 10 === 0
}

/**
 * Does this look like a secret somebody sent in the clear?
 *
 * The page's script is the thing that is supposed to make plaintext impossible, and it disables
 * every plaintext input before it submits. But a script can fail to run: an old browser, an
 * extension, a CSP mistake of our own making, or somebody with curl and good intentions. If
 * that happens the form still posts, and a bare PAN would land in this function's memory and,
 * worse, possibly in a platform request log we do not control (red team C5).
 *
 * So the POST refuses any field it does not expect, and refuses loudly if what it was handed
 * looks like a card number. The refusal is checked BEFORE anything is stored or consumed, and
 * the offending value is never logged, never echoed and never kept.
 */
export function looksLikePlaintext(value: string): boolean {
  const digits = value.replace(/[^0-9]/g, "")
  if (digits.length >= 13 && digits.length <= 19 && luhn(digits)) return true
  return /(?:^|[^0-9])[0-9]{13,19}(?:[^0-9]|$)/.test(value)
}

// --------------------------------------------------------------------------------------------
// Routing
// --------------------------------------------------------------------------------------------

/**
 * The path this handler routes on.
 *
 * Both prefixes are stripped because the edge runtime serves a function at
 * `/functions/v1/<name>` while a custom domain may map it at `/<name>` or at the root, and all
 * three have to be the same code. Which one is public is then purely `FLUCK_VAULT_BASE_URL`.
 */
export function routePath(pathname: string): string {
  const stripped = pathname
    .replace(/^\/functions\/v1/, "")
    .replace(/^\/fluck-vault(?=\/|$)/, "")
  return stripped === "" ? "/" : stripped.replace(/\/+$/, "") || "/"
}

/**
 * The short link id: the request's own sixteen bytes, base64url, twenty two characters.
 *
 * ## Why the link stopped carrying a token
 *
 * A signed link was six hundred characters and wrapped over eight lines in Messages. The
 * signature was never what made the link safe to send: the DGX already authenticates the MINT
 * over `POST /requests`, and the row it writes there is the only thing a link can reach. So the
 * link needs to be UNGUESSABLE and nothing more, and sixteen random bytes are unguessable.
 *
 * ## Why this is not a new column
 *
 * The row's primary key is a `uuid`, which IS sixteen bytes. The id in the path is those same
 * bytes in base64url instead of hyphenated hex, so `/v/<id>` resolves by primary key with no
 * second index, no second unique constraint, and no window in which one exists and not the
 * other. The DGX draws the bytes with `SecureRandom` and sends them in the signed mint body, so
 * this function never chooses one: a function that could mint an id could mint a link.
 *
 * Note that a uuid drawn by `randomUUID` spends six of its bits on version and variant tags.
 * These bytes do not: the DGX writes all sixteen at random and only formats them as a uuid.
 */
export const SHORT_ID_PATTERN = /^[A-Za-z0-9_-]{22}$/

/** The twenty two character id for one request, or null if the jti is not a uuid. */
export function shortId(jti: string): string | null {
  if (!UUID_PATTERN.test(jti)) return null
  const hex = jti.replaceAll("-", "")
  let binary = ""
  for (let i = 0; i < 32; i += 2) binary += String.fromCharCode(parseInt(hex.slice(i, i + 2), 16))
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
}

/**
 * The uuid one short id names, or null.
 *
 * Strict about the alphabet and the length before it decodes anything, so a path that is not an
 * id is refused by a regular expression rather than by `atob` throwing somewhere further in.
 */
export function shortIdToJti(id: string): string | null {
  if (!SHORT_ID_PATTERN.test(id)) return null
  let binary: string
  try {
    binary = atob(id.replaceAll("-", "+").replaceAll("_", "/") + "==")
  } catch {
    return null
  }
  if (binary.length !== 16) return null
  const hex = Array.from(binary).map((c) => c.charCodeAt(0).toString(16).padStart(2, "0")).join("")
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-` +
    hex.slice(20)
}

/**
 * What a log line is allowed to say about a link.
 *
 * The id is now the whole of the capability, so a full one in a log is a link in a log. Eight
 * characters tie a refusal to a mint in the DGX's own ledger and open nothing.
 */
function tag(jti: string): string {
  return jti.slice(0, 8)
}

/** Names the install on a signed request. Absent means the legacy env key. */
export const INSTANCE_HEADER = "x-fluck-instance"

export const INSTANCE_ID_PATTERN = /^[A-Za-z0-9_-]{16,64}$/

function baseUrl(deps: Dependencies): string {
  return (deps.env("FLUCK_VAULT_BASE_URL") || DEFAULT_PUBLIC_BASE_URL).replace(/\/+$/, "")
}

/** The audience a token must name: the HOST of the public base URL, not the whole thing. */
export function audience(deps: Dependencies): string | null {
  try {
    return new URL(baseUrl(deps)).host
  } catch {
    return null
  }
}

function workspacePrefix(ws: string): string {
  return ws.slice(0, 8)
}

function noContent(): Response {
  return new Response(null, {
    status: 204,
    headers: {
      "Cache-Control": "no-store",
      "X-Robots-Tag": "noindex, nofollow, noarchive",
      "Referrer-Policy": "no-referrer",
      "Strict-Transport-Security": "max-age=63072000; includeSubDomains; preload",
    },
  })
}

export function createHandler(deps: Dependencies): (request: Request) => Promise<Response> {
  return async (request: Request) => {
    const path = routePath(new URL(request.url).pathname)
    if (path === "/health") return health(deps)
    if (path === "/pubkey") return pubkey(deps)
    if (path === "/requests" || path === "/inbox/claim") {
      if (request.method !== "POST") {
        await request.body?.cancel().catch(() => {})
        return json(405, { error: "method" })
      }
      return await signedRoute(request, deps, path)
    }
    if (path === "/instances") {
      if (request.method !== "POST") {
        await request.body?.cancel().catch(() => {})
        return json(405, { error: "method" })
      }
      return await instancesRoute(request, deps)
    }
    // The short link. `/v/<id>` and `/c/<id>` carry no token: the id IS the request's primary
    // key, and the row behind it is the only thing the link can reach.
    const short = /^\/(v|c)\/([^/]+)$/.exec(path)
    if (short) {
      const purpose: Purpose = short[1] === "v" ? "vault" : "cvv"
      const jti = shortIdToJti(short[2])
      if (jti === null) {
        await request.body?.cancel().catch(() => {})
        deps.log(`${purpose} refused: id`)
        return await message(400, COPY.badTitle, COPY.bad)
      }
      if (request.method === "GET") return await get(request, deps, purpose, jti)
      if (request.method === "POST") return await post(request, deps, purpose)
      await request.body?.cancel().catch(() => {})
      return await message(405, COPY.badTitle, COPY.bad)
    }
    // The old signed link, kept alive for one expiry window's worth of links already texted.
    if (path === "/vault" || path === "/cvv") {
      const purpose: Purpose = path === "/vault" ? "vault" : "cvv"
      if (request.method === "GET") return await get(request, deps, purpose, null)
      if (request.method === "POST") return await post(request, deps, purpose)
      // Drained first: an unread body on a keep alive connection desyncs the next request on
      // it, which shows up later as a bewildering 501 from an unrelated route.
      await request.body?.cancel().catch(() => {})
      return await message(405, COPY.badTitle, COPY.bad)
    }
    await request.body?.cancel().catch(() => {})
    return await message(404, COPY.notFoundTitle, COPY.notFound)
  }
}

/**
 * Liveness, and whether the function CAN work. Booleans only, never a value.
 *
 * Also the warm up ping: the DGX hits this when it opens a purchase gate, so the five minute
 * CVV window is not spent on a cold start while the owner is already typing. (Red team I1.)
 */
function health(deps: Dependencies): Response {
  let linkKey = false
  let sealKey = false
  try {
    publicKeyBytes(deps.env("FLUCK_LINK_PUBLIC_KEY"))
    linkKey = true
  } catch { /* reported as false */ }
  try {
    sealPublicKey(deps.env("FLUCK_SEAL_PUBLIC_KEY"))
    sealKey = true
  } catch { /* reported as false */ }
  const configured = { linkKey, sealKey, baseUrl: audience(deps) !== null }
  const ok = linkKey && sealKey && configured.baseUrl
  return new Response(JSON.stringify({ ok, configured }), {
    status: ok ? 200 : 503,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  })
}

/**
 * The sealing public key, for anyone who wants to check what the page was given.
 *
 * It is public by definition, and publishing it is the point: the DGX operator can compare
 * this against the key they generated and know, without trusting a deploy log, that the pages
 * being served encrypt to them and not to somebody who swapped the environment variable.
 */
function pubkey(deps: Dependencies): Response {
  let key: string
  try {
    key = btoa(String.fromCharCode(...sealPublicKey(deps.env("FLUCK_SEAL_PUBLIC_KEY"))))
  } catch {
    return new Response(JSON.stringify({ error: "unconfigured" }), {
      status: 503,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    })
  }
  return new Response(
    JSON.stringify({ alg: "ECDH-P256-HKDF-SHA256-AES256GCM", format: "x962-uncompressed", key }),
    { headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } },
  )
}

/**
 * Everything a GET needs to agree on: configuration, the id or the token, and the row.
 *
 * `shortJti` is the id a short link already resolved, and null on the old signed route, which
 * has to verify a token to learn the same thing. The keys come from the ROW: its install's keys
 * when an install minted it, the env keys otherwise. On the signed route the jti is read from
 * the unverified token only to find that row, and the token is then verified with its key.
 *
 * Returns a Response on every failure, and that Response is always the same fixed page. The
 * caller cannot accidentally render a reason, because there is no reason to render.
 */
async function verified(
  request: Request,
  deps: Dependencies,
  purpose: Purpose,
  shortJti: string | null,
): Promise<{ row: VaultRequestRow; sealKey: Uint8Array } | Response> {
  const aud = audience(deps)
  if (!aud) {
    deps.log(`${purpose} unconfigured: base url`)
    return await message(503, COPY.unsetTitle, COPY.unset)
  }

  let token: string | null = null
  let jti: string
  if (shortJti !== null) {
    jti = shortJti
  } else {
    token = request.method === "GET" ? new URL(request.url).searchParams.get("t") : null
    const peeked = token ? peekJti(token) : null
    if (peeked === null) {
      deps.log(`${purpose} refused: token`)
      return await message(400, COPY.badTitle, COPY.bad)
    }
    jti = peeked
  }

  const row = await deps.describeRequest(jti)
  if (!row) {
    deps.log(`${purpose} refused: no request [${tag(jti)}]`)
    return await message(400, COPY.badTitle, COPY.bad)
  }
  // The route and the row must agree on what this link is for. A `/c/<id>` naming a vault row
  // would otherwise render a payment page for something nobody is paying for.
  if (row.purpose !== purpose) {
    deps.log(`${purpose} refused: mismatch [${tag(jti)}]`)
    return await message(400, COPY.badTitle, COPY.bad)
  }

  let linkKey: Uint8Array
  let sealKey: Uint8Array
  try {
    linkKey = publicKeyBytes(row.instance?.linkPublicKey ?? deps.env("FLUCK_LINK_PUBLIC_KEY"))
    sealKey = sealPublicKey(row.instance?.sealPublicKey ?? deps.env("FLUCK_SEAL_PUBLIC_KEY"))
  } catch {
    deps.log(`${purpose} unconfigured`)
    return await message(503, COPY.unsetTitle, COPY.unset)
  }

  // On the signed route the signature and the row must agree as well.
  if (token !== null) {
    const claims = await verifyLink(linkKey, token, aud, Math.floor(deps.now() / 1000))
    if (!claims || claims.purpose !== purpose || claims.jti !== jti) {
      deps.log(`${purpose} refused: token`)
      return await message(400, COPY.badTitle, COPY.bad)
    }
    if (row.ws !== claims.ws) {
      deps.log(`${purpose} refused: mismatch [${tag(jti)}]`)
      return await message(400, COPY.badTitle, COPY.bad)
    }
  }
  return { row, sealKey }
}

/**
 * A minor unit amount in the row's own currency: `₹290.69`, `$487.32`, `¥1,200`, `KWD 1.250`.
 *
 * `minor` is in the currency's ISO 4217 minor unit (see currency.ts for the contract), so the
 * divisor is `10 ** exponent` from that table and the number of decimals shown is the same
 * exponent. A missing or unknown code renders nothing rather than a guessed amount: on a page
 * that asks for a card's security code, no amount is better than a wrong one.
 */
export function money(minor: number | null, currency: string | null): string | null {
  if (minor === null || !Number.isSafeInteger(minor) || minor < 0) return null
  const code = currency?.toUpperCase() ?? ""
  const exponent = minorUnitExponent(code)
  if (exponent === null) return null
  return new Intl.NumberFormat("en", {
    style: "currency",
    currency: code,
    minimumFractionDigits: exponent,
    maximumFractionDigits: exponent,
  }).format(minor / 10 ** exponent)
}

/**
 * Render the form. Idempotent, and it spends nothing.
 *
 * This is the whole of red team C1: the owner's phone, the relay's preview fetcher and any
 * middlebox on the way may each GET this URL, and the link has to still work when a human
 * finally taps it. So no nonce is claimed, no row is consumed, and a reload is free.
 */
async function get(
  request: Request,
  deps: Dependencies,
  purpose: Purpose,
  shortJti: string | null,
): Promise<Response> {
  if (isUnfurler(request)) return noContent()

  const outcome = await verified(request, deps, purpose, shortJti)
  if (outcome instanceof Response) return outcome
  const { row } = outcome
  const nowMs = deps.now()

  if (
    !hit(`g:${row.jti}`, JTI_GET_LIMIT, 15 * 60 * 1000, nowMs) ||
    !hit(`ip:${clientAddress(request)}`, IP_LIMIT, IP_WINDOW_MS, nowMs)
  ) {
    deps.log(`${purpose} limited: get [${workspacePrefix(row.ws)}] [${tag(row.jti)}]`)
    return await message(429, COPY.busyTitle, COPY.busy)
  }

  // The row's install, or the env key: the blob must open on the box that minted the link.
  const sealKey = standardBase64(outcome.sealKey)
  // The runtime sees `/fluck-vault/vault`; the phone must post to the public URL, or the
  // gateway answers "requested path is invalid" before this code runs.
  // A short link posts back to ITSELF, so the id never has to be reconstructed and the old
  // route is free to be deleted without taking the new pages down with it.
  const action = shortJti !== null
    ? `${baseUrl(deps)}/${purpose === "cvv" ? "c" : "v"}/${shortId(row.jti)}`
    : `${baseUrl(deps)}${purpose === "cvv" ? "/cvv" : "/vault"}`
  const cookie = setCookieHeader(row.jti, "/")

  const page = purpose === "cvv"
    ? {
      title: COPY.cvvTitle,
      intro: cvvIntro(row),
      kind: "cvv" as const,
      submit: COPY.cvvSubmit,
      note: COPY.cvvNote,
    }
    : row.kind === "card"
    ? {
      title: COPY.cardTitle,
      intro: COPY.cardIntro,
      kind: "card" as const,
      submit: COPY.cardSubmit,
      note: COPY.cardNote,
    }
    : {
      title: row.alias ? `Save your ${row.alias} login` : COPY.passwordTitle,
      intro: COPY.passwordIntro,
      kind: "password" as const,
      submit: COPY.passwordSubmit,
      note: COPY.passwordNote,
    }

  const response = await form({ ...page, jti: row.jti, sealKey, action })
  response.headers.append("Set-Cookie", cookie)
  deps.log(`${purpose} rendered [${workspacePrefix(row.ws)}] [${tag(row.jti)}]`)
  return response
}

/**
 * The CVV page is the confirmation surface: `Visa ••4242 · $487.32 · delta.com`.
 *
 * The row is minted by the DGX and rendered outside model control. Missing parts are omitted.
 */
export function cvvIntro(row: VaultRequestRow): string {
  const card = [row.brand, row.last4 ? `••${row.last4}` : null].filter(Boolean).join(" ")
  const parts = [card, money(row.totalCents, row.currency), row.merchant]
    .filter((part): part is string => typeof part === "string" && part.length > 0)
  return parts.length > 0 ? parts.join(" · ") : COPY.cvvIntro
}

/**
 * Take the sealed blob, consume the link, stage the blob. In that order, and only once.
 *
 * The token is NOT re verified here, and it deliberately cannot be: a POST carries no `t`. What
 * authorises this write is the pair of the `jti` in the body and the device cookie that was set
 * when the form was rendered, and the database decides whether that pair may still be spent.
 * Doing it that way means the token never travels in a request body, never reaches a POST
 * access log, and never has to be re parsed from something a page could have rewritten.
 */
async function post(request: Request, deps: Dependencies, purpose: Purpose): Promise<Response> {
  let body: FormData
  try {
    body = await request.formData()
  } catch {
    await request.body?.cancel().catch(() => {})
    // Named, because this catch fires for a body that is not a form at all and a silent 400
    // here is indistinguishable from a bad jti when somebody is standing at a checkout.
    deps.log(`${purpose} refused: body`)
    return await message(400, COPY.badTitle, COPY.bad)
  }

  const jti = String(body.get("j") ?? "")
  const ciphertext = body.get("c")
  const nowMs = deps.now()

  if (!hit(`ip:${clientAddress(request)}`, IP_LIMIT, IP_WINDOW_MS, nowMs)) {
    deps.log(`${purpose} limited: ip`)
    return await message(429, COPY.busyTitle, COPY.busy)
  }
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(jti)) {
    deps.log(`${purpose} refused: jti`)
    return await message(400, COPY.badTitle, COPY.bad)
  }
  if (!hit(`p:${jti}`, JTI_POST_LIMIT, 15 * 60 * 1000, nowMs)) {
    deps.log(`${purpose} limited: post [${tag(jti)}]`)
    return await message(429, COPY.busyTitle, COPY.busy)
  }

  // Anything beyond the two expected fields means the page's script did not run and the browser
  // serialised the plaintext inputs. Refuse before storing, consuming or logging anything.
  for (const [name, value] of body.entries()) {
    if (name === "c" || name === "j") continue
    if (typeof value === "string" && value.length > 0) {
      deps.log(`${purpose} refused: plaintext field [${tag(jti)}]`)
      return await message(400, COPY.badTitle, COPY.bad)
    }
  }
  if (typeof ciphertext !== "string" || !looksSealed(ciphertext)) {
    deps.log(`${purpose} refused: not sealed [${tag(jti)}]`)
    return await message(400, COPY.badTitle, COPY.bad)
  }
  if (looksLikePlaintext(ciphertext)) {
    deps.log(`${purpose} refused: plaintext shaped [${tag(jti)}]`)
    return await message(400, COPY.badTitle, COPY.bad)
  }

  const cookie = readCookie(request, jti)
  if (!cookie) {
    // Two reason codes, because they were one and the distinction is the whole bug: a jar with
    // no `fv` cookie in it is a browser that never rendered the form, and a jar holding ANOTHER
    // link's cookie was, until this deployment, the ordinary result of opening two links.
    // Neither logs a value.
    deps.log(
      hasForeignCookie(request, jti)
        ? `${purpose} refused: device cookie for another link [${tag(jti)}]`
        : `${purpose} refused: no device cookie [${tag(jti)}]`,
    )
    return await message(400, COPY.badTitle, COPY.bad)
  }

  const result = await deps.store({
    jti,
    ciphertext,
    cookieHash: await hashCookie(cookie),
    ttlMinutes: INBOX_TTL_MINUTES[purpose],
  })
  if (result.outcome === "unavailable") {
    deps.log(`${purpose} failed: store [${tag(jti)}]`)
    return await message(503, COPY.downTitle, COPY.down)
  }
  if (result.outcome === "gone") {
    // Expired, already spent, or a purpose the row does not agree with. All one page: a replay
    // must not be able to tell itself apart from a typo.
    deps.log(`${purpose} refused: spent [${tag(jti)}]`)
    return await message(400, COPY.badTitle, COPY.bad)
  }

  deps.log(`${purpose} stored [${tag(jti)}]`)
  // The kind comes back from the database, not from the request, because a POST carries no
  // token and the browser is not asked what it just sent.
  const done = result.kind === "cvv"
    ? await message(200, COPY.cvvDoneTitle, COPY.cvvDone, clearSiteData())
    : result.kind === "card"
    ? await message(200, COPY.cardSavedTitle, COPY.cardSaved, clearSiteData())
    : await message(200, COPY.savedTitle, COPY.saved, clearSiteData())
  // The cookie has done its job and there is nothing left for it to authorise.
  // Both names: the one this deployment sets, and the bare one a page from the previous
  // deployment is still carrying. Clearing only the new name would leave the old one behind.
  done.headers.append("Set-Cookie", clearCookieHeader(jti))
  done.headers.append(
    "Set-Cookie",
    `${COOKIE_NAME}=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict`,
  )
  return done
}

/**
 * Tell the browser to forget this origin once the value has been sent.
 *
 * Not a security boundary, because a browser that ignores it is not going to be argued with,
 * but it clears the form's own bfcache entry, which is where a back button would otherwise
 * bring a filled card field back onto the screen.
 */
function clearSiteData(): Record<string, string> {
  return { "Clear-Site-Data": '"cache", "storage"' }
}

// --------------------------------------------------------------------------------------------
// The DGX's own routes
// --------------------------------------------------------------------------------------------

/** Machine answers. No page, no copy, nothing to unfurl, nothing to read off a failure. */
function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  })
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * `POST /requests` and `POST /inbox/claim`, both proving themselves with a detached Ed25519
 * signature over the raw body rather than with a Supabase credential the DGX would otherwise
 * have to hold. See signed.ts for why.
 */
async function signedRoute(
  request: Request,
  deps: Dependencies,
  path: string,
): Promise<Response> {
  const body = await request.text()
  const nowSeconds = Math.floor(deps.now() / 1000)

  if (!hit(`ip:${clientAddress(request)}`, IP_LIMIT, IP_WINDOW_MS, deps.now())) {
    deps.log(`signed limited: ip`)
    return json(429, { error: "busy" })
  }
  // With the header the signature must be that install's; without it, the legacy env key's.
  const instanceId = request.headers.get(INSTANCE_HEADER)
  let instance: Instance | null = null
  if (instanceId !== null) {
    instance = INSTANCE_ID_PATTERN.test(instanceId) ? await deps.instance(instanceId) : null
    if (!instance) {
      deps.log(`signed refused: instance ${path}`)
      return json(401, { error: "unauthorized" })
    }
  }
  const ok = await verifySigned({
    publicKey: instance ? instance.linkPublicKey : deps.env("FLUCK_LINK_PUBLIC_KEY"),
    method: "POST",
    path,
    body,
    timestamp: request.headers.get(TIMESTAMP_HEADER),
    signature: request.headers.get(SIGNATURE_HEADER),
    nowSeconds,
  })
  if (!ok) {
    // One answer for a bad signature, a stale timestamp and a missing header alike.
    deps.log(`signed refused: ${path}`)
    return json(401, { error: "unauthorized" })
  }

  let parsed: unknown
  try {
    parsed = body.length === 0 ? {} : JSON.parse(body)
  } catch {
    return json(400, { error: "body" })
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return json(400, { error: "body" })
  }

  // Minting is a privilege of the operator: the env key, or an install an operator approved.
  // Without this any signed-in account could register a key and put its own merchant, card and
  // amount on a first-party page that seals what is typed to that account.
  if (path === "/requests" && instance && !instance.issuanceApproved) {
    deps.log(`requests refused: issuance not approved [${instance.instanceId.slice(0, 8)}]`)
    return json(403, { error: "issuance" })
  }

  const id = instance?.instanceId ?? null
  return path === "/requests"
    ? await createRequestRoute(deps, parsed as Record<string, unknown>, nowSeconds, id)
    : await claimRoute(deps, parsed as Record<string, unknown>, id)
}

function optionalString(value: unknown): string | null | undefined {
  if (value === undefined || value === null) return null
  if (typeof value !== "string" || value.length === 0 || value.length > 200) return undefined
  return value
}

/**
 * Write the row for a link the DGX is about to sign.
 *
 * The policy ceilings are checked HERE as well as on the DGX. A minting bug that asked for a
 * week long link would otherwise write a row that outlives every token that could reach it, and
 * `verifyLink` would be the only thing standing between that row and a link good for a week.
 * Two checks of the same rule, on either side of the wire, is the point.
 */
async function createRequestRoute(
  deps: Dependencies,
  body: Record<string, unknown>,
  nowSeconds: number,
  instanceId: string | null,
): Promise<Response> {
  const jti = typeof body.jti === "string" ? body.jti : ""
  if (!UUID_PATTERN.test(jti)) return json(400, { error: "jti" })

  const ws = typeof body.ws === "string" ? body.ws : ""
  if (ws.length === 0 || ws.length > 200) return json(400, { error: "ws" })

  const purpose = body.purpose
  if (purpose !== "vault" && purpose !== "cvv") return json(400, { error: "purpose" })

  const kind = purpose === "vault" ? body.kind : null
  if (purpose === "vault" && kind !== "password" && kind !== "card") {
    return json(400, { error: "kind" })
  }

  const expiresAt = body.expiresAt
  if (typeof expiresAt !== "number" || !Number.isFinite(expiresAt)) {
    return json(400, { error: "expiresAt" })
  }
  const maxAge = purpose === "vault" ? MAX_VAULT_AGE_SECONDS : MAX_CVV_AGE_SECONDS
  if (expiresAt <= nowSeconds || expiresAt - nowSeconds > maxAge) {
    return json(400, { error: "expiresAt" })
  }

  const alias = optionalString(body.alias)
  const purchaseId = optionalString(body.purchaseId)
  const merchant = optionalString(body.merchant)
  const brand = optionalString(body.brand)
  const last4 = optionalString(body.last4)
  const currency = optionalString(body.currency)
  for (const value of [alias, purchaseId, merchant, brand, last4, currency]) {
    if (value === undefined) return json(400, { error: "field" })
  }
  if (last4 !== null && !/^[0-9]{4}$/.test(last4 as string)) return json(400, { error: "last4" })
  if (currency !== null && !isKnownCurrency(currency as string)) {
    return json(400, { error: "currency" })
  }
  if (purpose === "cvv" && purchaseId === null) return json(400, { error: "purchase" })
  if (purpose === "vault" && purchaseId !== null) return json(400, { error: "purchase" })

  // ISO 4217 minor units of `currency` (currency.ts), bounded so the double is exact.
  const totalCents = body.totalCents ?? null
  if (
    totalCents !== null &&
    (typeof totalCents !== "number" || !Number.isSafeInteger(totalCents) || totalCents < 0 ||
      totalCents > MAX_MINOR_AMOUNT)
  ) {
    return json(400, { error: "total" })
  }
  // A CVV page is the owner's only check of what is being bought, so it is never rendered
  // without the merchant and the amount; a vault row is not a purchase and carries neither.
  if (purpose === "cvv") {
    if (merchant === null) return json(400, { error: "merchant" })
    if (totalCents === null || totalCents === 0 || currency === null) {
      return json(400, { error: "total" })
    }
  } else if (
    merchant !== null || brand !== null || last4 !== null || totalCents !== null ||
    currency !== null
  ) {
    return json(400, { error: "field" })
  }

  const created = await deps.createRequest({
    jti,
    ws,
    purpose,
    kind: (kind as Kind) ?? null,
    alias: alias as string | null,
    purchaseId: purchaseId as string | null,
    merchant: merchant as string | null,
    brand: brand as string | null,
    last4: last4 as string | null,
    totalCents: totalCents as number | null,
    currency: currency as string | null,
    expiresAt,
    instanceId,
  })
  if (!created) {
    deps.log(`requests refused: taken [${workspacePrefix(ws)}] [${tag(jti)}]`)
    return json(409, { error: "taken" })
  }
  deps.log(`requests created [${workspacePrefix(ws)}] [${tag(jti)}]`)
  return json(201, { ok: true })
}

/**
 * Drain one workspace's queue. The rows are deleted by the same statement that returns them.
 *
 * Only the calling install's rows: an install never sees another's blobs, and the legacy key
 * sees only rows no install minted.
 */
async function claimRoute(
  deps: Dependencies,
  body: Record<string, unknown>,
  instanceId: string | null,
): Promise<Response> {
  const ws = typeof body.ws === "string" ? body.ws : ""
  if (ws.length === 0 || ws.length > 200) return json(400, { error: "ws" })
  const items = await deps.claimInbox(ws, instanceId)
  deps.log(`inbox claimed [${workspacePrefix(ws)}] [${items.length}]`)
  return json(200, { items })
}

function standardBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
}

/**
 * `POST /instances`: an install registers its public keys against the signed-in BOSS user.
 *
 * Authenticated by the user's Supabase access token. An id already owned by another user is
 * refused, so one user cannot take over another's install. Keys are validated and stored
 * normalised: Ed25519 as 32 raw bytes, P-256 as the 65 byte uncompressed point.
 *
 * ## Changing the keys of an install that already exists
 *
 * A session is not enough. A stolen session could otherwise swap in its own keys, and every
 * unconsumed link of that install would then seal to them. A key change must also carry the
 * usual signed-request headers (signed.ts) made with the install's CURRENT link key over this
 * exact body, so it names the new keys and is at most two minutes old. The write is then a
 * compare-and-swap on that current key, so a captured proof stops working the moment the keys
 * it was made for are replaced, and replaying it before then only repeats the same change.
 * A lost current key is not recoverable here: register a fresh instance id instead.
 */
async function instancesRoute(request: Request, deps: Dependencies): Promise<Response> {
  const body = await request.text()
  if (!hit(`ip:${clientAddress(request)}`, IP_LIMIT, IP_WINDOW_MS, deps.now())) {
    deps.log(`instances limited: ip`)
    return json(429, { error: "busy" })
  }
  const bearer = /^Bearer\s+(\S+)$/i.exec(request.headers.get("authorization") ?? "")
  const userId = bearer ? await deps.userFromToken(bearer[1]) : null
  if (!userId) {
    deps.log(`instances refused: token`)
    return json(401, { error: "unauthorized" })
  }

  let parsed: Record<string, unknown>
  try {
    const value = JSON.parse(body)
    if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error()
    parsed = value
  } catch {
    return json(400, { error: "body" })
  }
  const instanceId = typeof parsed.instanceId === "string" ? parsed.instanceId : ""
  if (!INSTANCE_ID_PATTERN.test(instanceId)) return json(400, { error: "instanceId" })
  let linkPublicKey: string
  let sealKey: string
  try {
    linkPublicKey = standardBase64(publicKeyBytes(stringOrUndefined(parsed.linkPublicKey)))
  } catch {
    return json(400, { error: "linkPublicKey" })
  }
  try {
    sealKey = standardBase64(sealPublicKey(stringOrUndefined(parsed.sealPublicKey)))
  } catch {
    return json(400, { error: "sealPublicKey" })
  }
  const registration: Registration = {
    instanceId,
    userId,
    linkPublicKey,
    sealPublicKey: sealKey,
  }
  const who = `[${instanceId.slice(0, 8)}] [${userId.slice(0, 8)}]`

  const outcome = await deps.registerInstance(registration)
  if (outcome !== "rotation_requires_proof") {
    deps.log(`instances ${outcome} ${who}`)
    return registerResponse(outcome)
  }

  // A key change. Prove possession of the current link key before anything is written.
  const current = await deps.instance(instanceId)
  if (!current || current.userId !== userId) {
    // Gone, revoked or owned by someone else since the register call: nothing to rotate.
    deps.log(`instances refused: rotation target ${who}`)
    return json(403, { error: "conflict" })
  }
  const proven = await verifySigned({
    publicKey: current.linkPublicKey,
    method: "POST",
    path: "/instances",
    body,
    timestamp: request.headers.get(TIMESTAMP_HEADER),
    signature: request.headers.get(SIGNATURE_HEADER),
    nowSeconds: Math.floor(deps.now() / 1000),
  })
  if (!proven) {
    deps.log(`instances refused: rotation without proof ${who}`)
    return json(401, { error: "rotation_requires_proof" })
  }
  const rotated = await deps.rotateInstance({
    ...registration,
    expectedLinkPublicKey: current.linkPublicKey,
  })
  deps.log(`instances rotate ${rotated} ${who}`)
  switch (rotated) {
    case "ok":
      return json(200, { ok: true, rotated: true })
    case "conflict":
    case "revoked":
      return json(403, { error: rotated })
    case "stale":
      return json(409, { error: rotated })
    case "invalid":
      return json(400, { error: rotated })
    default:
      return json(503, { error: "unavailable" })
  }
}

function registerResponse(outcome: RegisterOutcome): Response {
  switch (outcome) {
    case "ok":
      return json(200, { ok: true })
    case "conflict":
    case "revoked":
      return json(403, { error: outcome })
    case "limit":
      return json(429, { error: outcome })
    case "invalid":
      return json(400, { error: outcome })
    default:
      return json(503, { error: "unavailable" })
  }
}

function stringOrUndefined(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined
}
