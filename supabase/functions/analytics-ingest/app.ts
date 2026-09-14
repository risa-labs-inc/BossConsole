/**
 * Analytics ingest: the one place the analytics credential lives.
 *
 * BOSS plugins cannot hold this key. Anything stored or injected on a user's machine
 * is readable by that user — `chmod 0600` protects a file from *other* accounts, not
 * from its owner; an environment variable is in `printenv`; the BOSS secret vault is
 * the user's own vault and lists the entry in their Secret Manager panel. So the
 * analytics plugin posts already-sanitized, vendor-neutral batches here, and this
 * function holds `AMPLITUDE_API_KEY` and does the vendor mapping. Same shape as the
 * sibling crash-report function, which exists for the same reason (`GITHUB_TOKEN`).
 *
 * A second benefit: the vendor now sees *this function's* address rather than the end
 * user's. A request IP is itself identifying, and Amplitude derives geolocation from
 * it by default, so routing through the function keeps client IPs out of what a third
 * party receives.
 *
 * Vendor mapping lives here rather than in the client so that swapping Amplitude for
 * another backend is a function deploy instead of a plugin release for every install.
 *
 * Abuse guards (verify_jwt=false, so the function gates itself):
 * - Strict shape validation. Event names, ids, property keys and value sizes are all
 *   bounded, and anything unexpected is refused rather than forwarded. The client has
 *   already reduced and scrubbed this data; the job here is to refuse to become an
 *   open relay into someone's analytics project.
 * - Per-IP sliding-window rate limit (in-memory, per isolate — best-effort).
 * - An OPTIONAL shared secret in the `apikey` header, enforced only when
 *   `ANALYTICS_INGEST_KEY` is configured. This is where it differs from
 *   crash-report, which requires the Supabase anon key: a *plugin* has no way to read
 *   the app's Supabase configuration — no `PluginContext` surface exposes the URL or
 *   the anon key — so requiring a header the client cannot produce would reject every
 *   honest request. A deployment that wants the guard sets the same value on both
 *   sides (`BOSS_ANALYTICS_INGEST_KEY` on the client).
 * - Deliberately NO CORS headers: the caller is a desktop HTTP client (CORS does not
 *   apply to it), so cross-origin browser requests — the only thing CORS headers
 *   would enable — have no legitimate use here and preflights fail closed.
 *
 * Trust boundary: treat `identity.distinctId` as a **client-asserted label**, not an
 * authenticated principal, and do not build anything downstream that assumes
 * otherwise. Strengthening it depends on a host-provided token that no PluginContext
 * surface offers today; the analysis and the plan live in the private tracker
 * (boss-plugin-analytics#7) rather than here.
 *
 * Routes (deployed with verify_jwt=false, like the sibling crash-report):
 * - POST /            → forward a batch, returns { accepted }
 * - GET  /health      → health check
 */
import { OpenAPIHono } from "@hono/zod-openapi"

export const app = new OpenAPIHono().basePath("/analytics-ingest")

const DEFAULT_AMPLITUDE_ENDPOINT = "https://api2.amplitude.com/2/httpapi"
const LIBRARY = "boss-analytics"

// Amplitude 400s the ENTIRE request when an id is shorter than this, so a short id
// is padded rather than allowed to poison the batch it travels in.
const MIN_ID_LENGTH = 5
const ID_PAD_PREFIX = "boss-"

// Shape limits. The client's own batch size is 20; the headroom is for a spool replay
// and for the batch size being tuned without a coordinated function deploy.
const MAX_BATCH_EVENTS = 200
const MAX_PROPERTIES = 40
const MAX_STRING_LENGTH = 512
const MAX_BODY_BYTES = 512_000

// Dot-namespaced lowercase, as produced by ApplicationEventCollector
// ("auth.signed_in", "browser.interaction.rage_click").
const EVENT_NAME_RE = /^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)*$/
// Supabase UUIDs, or the plugin's "anon-<uuid>" device id.
const DISTINCT_ID_RE = /^[A-Za-z0-9._:-]{1,200}$/
// SHA-256 prefix from core/EventId.
const INSERT_ID_RE = /^[a-f0-9]{16,64}$/
const SOURCE_RE = /^[A-Z][A-Z_]{0,31}$/
const PROPERTY_KEY_RE = /^[A-Za-z0-9_.$\[\]-]{1,64}$/

// Timestamps outside this window are a broken clock, not a real event. The lower
// bound is well before BOSS existed; the upper allows modest clock skew.
const MIN_TIMESTAMP_MS = Date.UTC(2020, 0, 1)
const FUTURE_SKEW_MS = 24 * 60 * 60 * 1000

// Sliding-window per-IP rate limit. In-memory, so it is per-isolate and resets on
// cold start — best-effort volume damping, not a hard quota. Sized well above an
// honest client: a 20-event batch every 10s is 6 requests/minute, so 600/hour leaves
// a hundredfold margin for spool replay bursts while capping a single source.
const RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000
const RATE_LIMIT_MAX_PER_WINDOW = 600
const rateBuckets = new Map<string, number[]>()

/** Exposed for tests. */
export function resetRateLimiter() {
  rateBuckets.clear()
}

export function allowRequest(ip: string, now: number = Date.now()): boolean {
  // Bound memory under address-spraying: dropping all state is acceptable for a
  // best-effort limiter.
  if (rateBuckets.size > 10_000) rateBuckets.clear()
  const cutoff = now - RATE_LIMIT_WINDOW_MS
  const stamps = (rateBuckets.get(ip) ?? []).filter((t) => t > cutoff)
  if (stamps.length >= RATE_LIMIT_MAX_PER_WINDOW) {
    rateBuckets.set(ip, stamps)
    return false
  }
  stamps.push(now)
  rateBuckets.set(ip, stamps)
  return true
}

/**
 * Rate-limit key from headers the TRUSTED edge controls, never the client.
 * X-Forwarded-For is "client, proxy1, …" — the LEFTMOST entry is whatever the caller
 * typed and rotating it would fully evade the limiter (and spray unique keys to bloat
 * the bucket map); trusted proxies append on the RIGHT. So: cf-connecting-ip
 * (Cloudflare-set on the api.risaboss.com route) first, else the rightmost XFF entry
 * (appended by the Supabase edge for *.supabase.co calls), else a shared "unknown"
 * bucket. Same reasoning as crash-report.
 */
export function clientIp(cfConnectingIp?: string, xff?: string): string {
  if (cfConnectingIp?.trim()) return cfConnectingIp.trim()
  const parts = xff?.split(",") ?? []
  return parts[parts.length - 1]?.trim() || "unknown"
}

export interface IngestIdentity {
  distinctId: string
  isAnonymous: boolean
}

export interface IngestEvent {
  name: string
  timestampMs: number
  source: string
  insertId: string
  properties: Record<string, unknown>
}

export interface IngestRequest {
  identity: IngestIdentity
  events: IngestEvent[]
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v)
}

/**
 * Bounded property value: primitives, or one level of array/object of primitives.
 *
 * Depth is capped rather than recursive-without-limit so a nested payload cannot cost
 * unbounded validation work, and strings are length-capped because the client's
 * sanitizer bounds what it *emits* — it says nothing about what an arbitrary caller
 * might post here.
 */
function validValue(v: unknown, depth = 0): boolean {
  if (v === null) return true
  const t = typeof v
  if (t === "boolean" || t === "number") return t !== "number" || Number.isFinite(v as number)
  if (t === "string") return (v as string).length <= MAX_STRING_LENGTH
  if (depth >= 1) return false
  if (Array.isArray(v)) return v.length <= MAX_PROPERTIES && v.every((x) => validValue(x, depth + 1))
  if (isPlainObject(v)) {
    const keys = Object.keys(v)
    return keys.length <= MAX_PROPERTIES &&
      keys.every((k) => PROPERTY_KEY_RE.test(k) && validValue(v[k], depth + 1))
  }
  return false
}

/** Returns the parsed request, or a human-readable reason string. */
export function validate(parsed: unknown, now: number = Date.now()): IngestRequest | string {
  if (!isPlainObject(parsed)) return "Request body must be a JSON object"

  const identity = parsed.identity
  if (!isPlainObject(identity)) return "identity is required"
  const distinctId = identity.distinctId
  if (typeof distinctId !== "string" || !DISTINCT_ID_RE.test(distinctId)) {
    return "identity.distinctId must be a short opaque id"
  }
  if (typeof identity.isAnonymous !== "boolean") return "identity.isAnonymous must be a boolean"

  const events = parsed.events
  if (!Array.isArray(events)) return "events must be an array"
  if (events.length === 0) return "events must not be empty"
  if (events.length > MAX_BATCH_EVENTS) return `events must contain at most ${MAX_BATCH_EVENTS} entries`

  const validated: IngestEvent[] = []
  for (const raw of events) {
    if (!isPlainObject(raw)) return "each event must be a JSON object"

    const name = raw.name
    if (typeof name !== "string" || !EVENT_NAME_RE.test(name)) {
      return "event.name must be a dot-namespaced lowercase identifier"
    }

    const timestampMs = raw.timestampMs
    if (typeof timestampMs !== "number" || !Number.isInteger(timestampMs)) {
      return "event.timestampMs must be an integer"
    }
    if (timestampMs < MIN_TIMESTAMP_MS || timestampMs > now + FUTURE_SKEW_MS) {
      return "event.timestampMs is outside the plausible range"
    }

    const source = raw.source
    if (typeof source !== "string" || !SOURCE_RE.test(source)) {
      return "event.source must be an upper-snake-case identifier"
    }

    const insertId = raw.insertId
    if (typeof insertId !== "string" || !INSERT_ID_RE.test(insertId)) {
      return "event.insertId must be a hex digest"
    }

    const properties = raw.properties ?? {}
    if (!isPlainObject(properties)) return "event.properties must be a JSON object"
    const keys = Object.keys(properties)
    if (keys.length > MAX_PROPERTIES) return `event.properties may hold at most ${MAX_PROPERTIES} keys`
    for (const k of keys) {
      if (!PROPERTY_KEY_RE.test(k)) return `event.properties key '${k}' is not an allowed property name`
      if (!validValue(properties[k])) return `event.properties['${k}'] is not an allowed property value`
    }

    validated.push({ name, timestampMs, source, insertId, properties })
  }

  return { identity: { distinctId, isAnonymous: identity.isAnonymous }, events: validated }
}

/**
 * Amplitude rejects the whole request for an id shorter than MIN_ID_LENGTH. Prefixing
 * is deterministic, so a short id still maps to exactly one Amplitude identity rather
 * than being dropped. Mirrors AmplitudeSink.normalizeId on the client.
 */
export function normalizeId(id: string): string {
  return id.length >= MIN_ID_LENGTH ? id : ID_PAD_PREFIX + id
}

/**
 * Vendor mapping. Three things about Amplitude's HTTP V2 contract are easy to get
 * wrong and are all pinned by tests: `time` is epoch **millis as a number** (not
 * ISO-8601); identity splits into `user_id` (signed in) vs `device_id` (anonymous)
 * rather than one `distinct_id`; and `insert_id` must be passed through from the
 * client, because the client derives it from event content and that is what makes a
 * batch replayed from its on-disk spool de-duplicate instead of double-counting.
 */
export function toAmplitudePayload(apiKey: string, request: IngestRequest): string {
  const resolvedId = normalizeId(request.identity.distinctId)
  return JSON.stringify({
    api_key: apiKey,
    events: request.events.map((e) => ({
      event_type: e.name,
      ...(request.identity.isAnonymous ? { device_id: resolvedId } : { user_id: resolvedId }),
      time: e.timestampMs,
      insert_id: e.insertId,
      library: LIBRARY,
      event_properties: { boss_source: e.source, ...e.properties },
    })),
  })
}

app.get("/health", (c) => {
  return c.json({ status: "healthy", timestamp: new Date().toISOString() }, 200)
})

app.post("/", async (c) => {
  const apiKey = Deno.env.get("AMPLITUDE_API_KEY")
  if (!apiKey) {
    console.error("AMPLITUDE_API_KEY secret is not configured")
    return c.json({ error: "Analytics ingest is not configured on the server" }, 503)
  }

  // Optional shared secret. Enforced only when the server has one, because the
  // client cannot read the app's anon key — see the module doc.
  const sharedKey = Deno.env.get("ANALYTICS_INGEST_KEY")
  if (sharedKey) {
    const provided = c.req.header("apikey") ||
      c.req.header("authorization")?.replace(/^Bearer\s+/i, "")
    if (provided !== sharedKey) {
      return c.json({ error: "Missing or invalid apikey" }, 401)
    }
  }

  const declaredLength = Number(c.req.header("content-length") ?? "0")
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) {
    return c.json({ error: "Payload too large" }, 413)
  }

  let parsed: unknown
  try {
    parsed = await c.req.json()
  } catch {
    return c.json({ error: "Request body must be valid JSON" }, 400)
  }

  const request = validate(parsed)
  if (typeof request === "string") {
    return c.json({ error: request }, 400)
  }

  // Rate-limit only requests that would actually reach the vendor — rejected
  // (401/400/503) requests cost nothing upstream, so junk cannot burn an honest
  // client's budget behind the same NAT. Same choice as crash-report.
  const ip = clientIp(c.req.header("cf-connecting-ip"), c.req.header("x-forwarded-for"))
  if (!allowRequest(ip)) {
    return c.json({ error: "Too many events from this address; try again later" }, 429)
  }

  const endpoint = Deno.env.get("AMPLITUDE_ENDPOINT") || DEFAULT_AMPLITUDE_ENDPOINT

  try {
    const upstream = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "*/*" },
      body: toAmplitudePayload(apiKey, request),
    })

    if (upstream.ok) {
      return c.json({ accepted: request.events.length }, 200)
    }

    // Anything the vendor refuses comes back to the client as 503, which its sink
    // classifies as RETRYABLE and therefore keeps on its spool.
    //
    // Deliberately NOT proxying the upstream status: a 400 from Amplitude means
    // *our* api key or *our* mapping is wrong, and the client would read a 4xx as
    // "permanently rejected" and discard the batch — losing a deployment's telemetry
    // over a server-side misconfiguration it cannot see or fix. Holding the events
    // until the server is fixed is the better failure, and the client's spool is
    // size-capped so a long outage cannot grow without bound. Client 4xx codes stay
    // reserved for genuinely malformed requests, which are permanent and should drop.
    const body = await upstream.text().catch(() => "")
    console.error(`Amplitude rejected a batch (HTTP ${upstream.status}): ${body.slice(0, 200)}`)
    return c.json({ error: "Upstream analytics backend rejected the batch" }, 503)
  } catch (e) {
    console.error(`Analytics ingest failed to reach the backend: ${e}`)
    return c.json({ error: "Failed to reach the analytics backend" }, 503)
  }
})
