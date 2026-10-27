import {
  bearer,
  HttpError,
  LIVE_MAX_SECONDS,
  mintToken,
  mintVoiceToken,
  Scope,
  signingKey,
  verifyClaims,
} from "./auth.ts"
import { LiveKit, liveKitClients, LiveKitConfig } from "./livekit.ts"
import {
  capped,
  isMultipart,
  MAX_AUDIO_OUTPUT_BYTES,
  multipartTranscription,
  Remaining,
  smallestRemaining,
  speechBody,
  transcriptionBody,
  transcriptionSeconds,
  wrongKind,
} from "./audio.ts"
import {
  audioEndpoint,
  completion,
  Connection,
  endpoint,
  events,
  Fallback,
  Model,
  Obj,
  readJson,
  requestBody,
  StreamAdapter,
  takesCacheControl,
  upstreamHeaders,
  upstreamKey,
} from "./wire.ts"

class SettlementTimeoutError extends Error {
  constructor() {
    super("settlement_timeout")
    this.name = "SettlementTimeoutError"
  }
}

export interface Dependencies {
  sessionUser(token: string): Promise<string | null>
  rpc(name: string, params: Obj): Promise<unknown>
  secret(name: string): string | undefined
  fetch: typeof fetch
  upstreamTimeoutMs?: number
  settlementTimeoutMs?: number
  audit?: (event: string, requestId: string) => void
  /** Sink for per-attempt upstream error lines; defaults to console.warn(JSON). */
  log?: (entry: Obj) => void
  /** First-response deadline for every route but the last; the overall deadline still applies. */
  attemptTimeoutMs?: number
  now?: () => number
  /** LiveKit clients for /v1/voice/live; tests inject fakes. */
  livekit?: (config: LiveKitConfig) => LiveKit
}
const AUDIO_PATHS = ["/v1/audio/transcriptions", "/v1/audio/speech"]
const LIVE_AGENT = "max-voice-agent"
const LIVE_STT_MODEL = "max-stt"
const LIVE_TTS_MODEL = "max-tts"
const LIVE_MIN_SECONDS = 60
/** A call ends at one hour or when the caller hangs up. */
export { LIVE_MAX_SECONDS } from "./auth.ts"
/** Most seconds one /v1/voice/usage report may charge; the worker reports about every 30 s. */
const LIVE_USAGE_MAX_SECONDS = 60

/** Only operator-controlled callback origins or Cloudflare's HTTPS quick tunnels. */
function validAgentUrl(value: unknown, deps: Dependencies): value is string {
  if (typeof value !== "string" || value.length > 2048) return false
  try {
    const url = new URL(value)
    if (url.username || url.password || url.hash || url.search) return false
    if (
      deps.secret("BOSS_AI_ALLOW_LOCAL_AGENT") === "1" && url.protocol === "http:" &&
      (url.hostname === "127.0.0.1" || url.hostname === "localhost")
    ) return true
    if (url.protocol !== "https:" || url.port) return false
    // Quick tunnels are the existing desktop share transport; Cloudflare owns DNS and TLS.
    if (/^[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com$/.test(url.hostname)) return true
    const origins = (deps.secret("BOSS_AI_AGENT_ORIGINS") ?? "").split(",").map((v) => v.trim())
    return origins.some((origin) => {
      try {
        const trusted = new URL(origin)
        return trusted.protocol === "https:" && !trusted.port && !trusted.username &&
          !trusted.password && !trusted.search && !trusted.hash && trusted.pathname === "/" &&
          trusted.origin === url.origin
      } catch {
        return false
      }
    })
  } catch {
    return false
  }
}
function configuredHttps(value: string, protocols = ["https:"]): string {
  try {
    const url = new URL(value)
    if (
      !protocols.includes(url.protocol) || url.username || url.password || url.search || url.hash
    ) throw new Error()
    return value.replace(/\/+$/, "")
  } catch {
    throw new HttpError(503, "voice_unavailable", "Voice calls are not configured.")
  }
}
const ROUTES: Record<string, Model["kind"]> = {
  "/v1/chat/completions": "chat",
  "/v1/audio/transcriptions": "transcription",
  "/v1/audio/speech": "speech",
}
interface Prepared {
  body: Obj
  audio?: string
  format?: string
  seconds?: number
}
const headers = { "Content-Type": "application/json", "Cache-Control": "no-store" }
const json = (body: unknown, status = 200, requestId = "") =>
  new Response(JSON.stringify(body), { status, headers: { ...headers, "X-Request-ID": requestId } })
function failure(error: unknown, requestId: string): Response {
  const e = error instanceof HttpError
    ? error
    : new HttpError(503, "unavailable", "BOSS AI is temporarily unavailable.")
  return json({ error: { ...e.extra, code: e.code, message: e.message } }, e.status, requestId)
}

// A header value must be an ISO timestamp: Date.parse alone accepts text with control characters.
const validResetsAt = (value: string) =>
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}(:?\d{2})?)$/.test(value) &&
  Number.isFinite(Date.parse(value))

/** Pool quota after settlement. Best effort: a failure or slow read omits it. */
async function remainingQuota(
  deps: Dependencies,
  user: string,
  modelId: string,
): Promise<Remaining | null> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const usage = await Promise.race([
      deps.rpc("boss_ai_usage", { p_user_id: user, p_model_id: modelId }),
      new Promise<never>((_, reject) => {
        timer = setTimeout(
          () => reject(new SettlementTimeoutError()),
          deps.settlementTimeoutMs ?? 2_000,
        )
      }),
    ])
    return smallestRemaining(usage)
  } catch {
    return null
  } finally {
    clearTimeout(timer)
  }
}

async function settleReservation(
  deps: Dependencies,
  requestId: string,
  tokens: number | null,
  audit: (event: string) => void,
): Promise<boolean> {
  // boss_ai_settle updates only WHERE NOT settled. Retrying the same request ID is safe
  // even if a timed-out first attempt commits late. Two bounded attempts cost at most
  // four seconds by default, on success, streaming cleanup, AND upstream failure paths.
  for (let attempt = 0; attempt < 2; attempt++) {
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      await Promise.race([
        deps.rpc("boss_ai_settle", { p_request_id: requestId, p_tokens: tokens }),
        new Promise<never>((_, reject) => {
          timer = setTimeout(
            () => reject(new SettlementTimeoutError()),
            deps.settlementTimeoutMs ?? 2_000,
          )
        }),
      ])
      return true
    } catch (error) {
      if (error instanceof SettlementTimeoutError) audit("settlement_timeout")
      if (attempt === 1) {
        // Reservation accounting is conservative: the full reserved allowance remains
        // charged, not unbilled. Alert operators for reconciliation by this request ID.
        audit("settlement_failed_reservation_retained")
        audit("settlement_failed")
      }
    } finally {
      if (timer !== undefined) clearTimeout(timer)
    }
  }
  return false
}

/**
 * The function's public address, for the voice worker to call back: inside the edge runtime the request URL is
 * an internal host. BOSS_AI_PUBLIC_URL (e.g. https://api.risaboss.com/functions/v1/boss-ai) wins, else SUPABASE_URL.
 */
function publicBase(deps: Dependencies): string {
  const explicit = deps.secret("BOSS_AI_PUBLIC_URL")?.replace(/\/+$/, "")
  if (explicit) return configuredHttps(explicit)
  const supabase = deps.secret("SUPABASE_URL")?.replace(/\/+$/, "")
  if (!supabase) throw new HttpError(503, "voice_unavailable", "Voice calls are not configured.")
  return `${configuredHttps(supabase)}/functions/v1/boss-ai`
}

interface Route {
  key: string
  model: Model
  connection: Connection
}
const COOLDOWN_MS = 60_000
const routeKey = (connection: string | undefined, upstream: string) =>
  `${connection ?? "?"}|${upstream}`

/** Primary first, then the model's resolved fallbacks, each with its own upstream model and routing. */
function routesOf(
  result: { model: Model; connection: Connection; fallbacks?: Fallback[] },
): Route[] {
  const { model, connection } = result
  return [
    {
      key: routeKey(connection.id ?? model.connection_id, model.upstream_model),
      model,
      connection,
    },
    ...(Array.isArray(result.fallbacks) ? result.fallbacks.slice(0, 4) : []).map((f) => ({
      key: routeKey(f.connection_id, f.upstream_model),
      model: {
        ...model,
        upstream_model: f.upstream_model,
        provider_routing: f.provider_routing ?? null,
      },
      connection: f.connection,
    })),
  ]
}

// Failures that predict the next request failing too: auth, billing, throttling, outages, and a
// 400 that names a usage/spend/credit limit (the 2026-10-27 incident). Other 400s are per request.
const LIMIT_WORDING =
  /(usage|spend(ing)?|credit|billing|quota|balance).{0,40}(limit|cap|exceed|exhaust|reach)|(limit|cap|exceed|reach).{0,40}(usage|spend|credit|quota|billing)|credit balance|insufficient (funds|credit)/i
export const trips = (status: number, message: string) =>
  [401, 402, 403, 429].includes(status) || status >= 500 ||
  (status === 400 && LIMIT_WORDING.test(message))

/** At most 4 KB of an upstream error body; only error.type/code/message are kept, never content. */
async function errorInfo(response: Response): Promise<{ type: string | null; message: string }> {
  const none = { type: null, message: "" }
  const reader = response.body?.getReader()
  if (!reader) return none
  try {
    const chunks: Uint8Array[] = []
    let size = 0
    while (size < 4096) {
      const { done, value } = await reader.read()
      if (done) break
      chunks.push(value)
      size += value.length
    }
    const bytes = new Uint8Array(size)
    let offset = 0
    for (const chunk of chunks) {
      bytes.set(chunk, offset)
      offset += chunk.length
    }
    const error = (JSON.parse(new TextDecoder().decode(bytes.subarray(0, 4096))) as Obj).error
    if (!error || typeof error !== "object") return none
    const e = error as Obj
    const type = typeof e.type === "string"
      ? e.type
      : typeof e.code === "string" || typeof e.code === "number"
      ? String(e.code)
      : null
    return {
      type: type && type.slice(0, 64),
      message: typeof e.message === "string" ? e.message.slice(0, 300) : "",
    }
  } catch {
    return none
  } finally {
    await reader.cancel().catch(() => {})
  }
}

// Routes where a large uncached prompt means caching silently regressed.
const expectsCache = (r: Route) =>
  r.connection.api_type === "anthropic_messages" ||
  (r.connection.api_type === "openai_chat" &&
    takesCacheControl(r.model.upstream_model, r.connection.base_url))
const cacheMissLarge = (value: unknown) => {
  if (!value || typeof value !== "object") return false
  const u = value as Obj, d = (u.prompt_tokens_details ?? {}) as Obj
  return Number(u.prompt_tokens) > 8000 && !Number(d.cached_tokens) && !Number(d.cache_write_tokens)
}

export function createHandler(deps: Dependencies): (request: Request) => Promise<Response> {
  const cooldown = new Map<string, number>()
  const now = deps.now ?? Date.now
  const startLiveCall = async (
    request: Request,
    user: string,
    key: Uint8Array,
    requestId: string,
  ): Promise<Response> => {
    const input = await readJson(request.body, 8 * 1024)
    if (Object.keys(input).some((k) => !["agent_url", "agent_token", "max_seconds"].includes(k))) {
      throw new HttpError(400, "invalid_request", "The request body is not valid.")
    }
    if (!validAgentUrl(input.agent_url, deps)) {
      throw new HttpError(400, "invalid_agent_url", "agent_url must use an approved HTTPS origin.")
    }
    if (
      typeof input.agent_token !== "string" || !/^[\x21-\x7e]{32,256}$/.test(input.agent_token)
    ) {
      throw new HttpError(400, "invalid_agent_token", "agent_token must be 32 to 256 characters.")
    }
    const maxSeconds = input.max_seconds === undefined ? LIVE_MAX_SECONDS : input.max_seconds
    if (
      typeof maxSeconds !== "number" || !Number.isInteger(maxSeconds) || maxSeconds < 60 ||
      maxSeconds > LIVE_MAX_SECONDS
    ) {
      throw new HttpError(
        400,
        "invalid_max_seconds",
        `max_seconds must be 60 to ${LIVE_MAX_SECONDS}.`,
      )
    }
    const url = deps.secret("LIVEKIT_URL")
    const apiKey = deps.secret("LIVEKIT_API_KEY")
    const apiSecret = deps.secret("LIVEKIT_API_SECRET")
    if (!url || !apiKey || !apiSecret) {
      throw new HttpError(503, "voice_unavailable", "Voice calls are temporarily unavailable.")
    }
    configuredHttps(url, ["https:", "wss:"])
    const inferenceBase = publicBase(deps)
    // The caller needs a policy for the voice pool, and at least a minute left in it.
    const usage = await deps.rpc("boss_ai_usage", { p_user_id: user, p_model_id: LIVE_STT_MODEL })
    if (usage === null || usage === undefined) {
      throw new HttpError(403, "forbidden", "Voice calls are not available for your account.")
    }
    const model = await deps.rpc("boss_ai_lookup", {
      p_user_id: user,
      p_model_id: LIVE_STT_MODEL,
    }) as { error?: string; model?: Model } | null
    if (!model?.model || model.error || model.model.kind !== "transcription") {
      throw new HttpError(503, "voice_unavailable", "Voice calls are not configured.")
    }
    const quota = smallestRemaining(usage)
    if (
      !quota || quota.remaining_seconds < Math.max(LIVE_MIN_SECONDS, model.model.max_output_tokens)
    ) {
      throw new HttpError(
        429,
        "allowance_exceeded",
        "Your voice allowance has insufficient remaining time. Check BOSS AI usage for reset times.",
        { usage },
      )
    }
    // Durable throttle is shared across isolates, including failed/empty calls. Never release it early.
    if (await deps.rpc("boss_ai_claim_voice_start", { p_user_id: user }) !== true) {
      throw new HttpError(429, "busy", "Wait before starting another voice call.")
    }
    const livekit = (deps.livekit ?? liveKitClients)({ url, apiKey, apiSecret })
    const room = `fluck-call-${user}`
    if (await livekit.roomExists(room)) {
      throw new HttpError(429, "busy", "A voice call is already active.")
    }
    const call = await mintVoiceToken(user, key, maxSeconds)
    const metadata = JSON.stringify({
      agent_url: input.agent_url,
      agent_token: input.agent_token,
      inference: {
        base_url: `${inferenceBase}/v1`,
        token: call.token,
      },
      stt_model: LIVE_STT_MODEL,
      tts_model: LIVE_TTS_MODEL,
      max_seconds: maxSeconds,
      user,
    })
    const participantToken = await livekit.participantToken({
      room,
      identity: `user-${user.slice(0, 8)}`,
      name: "You",
      ttlSeconds: maxSeconds + 300,
    })
    try {
      await livekit.createRoom({
        name: room,
        emptyTimeout: 60,
        // A dropped caller (network change, locked phone) has two minutes to come back before the room closes.
        departureTimeout: 120,
        maxParticipants: 2,
      })
      await livekit.createDispatch(room, LIVE_AGENT, metadata)
    } catch (error) {
      // createRoom can time out after committing: delete even if its response never arrived.
      await livekit.deleteRoom(room).catch(() => {})
      throw error
    }
    return json(
      {
        server_url: url,
        participant_token: participantToken,
        room,
        expires_at: call.expires_at,
        quota,
      },
      200,
      requestId,
    )
  }

  return async (request) => {
    const requestId = crypto.randomUUID()
    const auditedEvents = new Set<string>()
    const audit = (event: string) => {
      if (auditedEvents.has(event)) return
      auditedEvents.add(event)
      try {
        if (deps.audit) deps.audit(event, requestId)
        else console.warn(JSON.stringify({ event, request_id: requestId }))
      } catch { /* Observability must not change accounting or response cleanup. */ }
    }
    const upstreamError = (
      route: Route,
      status: number | null,
      type: string | null,
      message = "",
    ) => {
      const entry = {
        event: "upstream_error",
        request_id: requestId,
        route: route.key,
        status,
        type,
        message,
      }
      try {
        if (deps.log) deps.log(entry)
        else console.warn(JSON.stringify(entry))
      } catch { /* Observability must not change accounting or response cleanup. */ }
    }
    let reservation: string | undefined
    let dispatched = false
    let measuredTokens: number | null = null
    let phase = "authentication"
    try {
      const path = new URL(request.url).pathname.replace(/^\/functions\/v1/, "").replace(
        /^\/boss-ai(?=\/|$)/,
        "",
      )
      const key = signingKey(deps.secret("BOSS_AI_SIGNING_SECRET"))
      if (request.method === "POST" && path === "/auth/exchange") {
        const input = await readJson(request.body, 1024)
        if (typeof input.ticket !== "string" || !/^[a-f0-9]{64}$/.test(input.ticket)) {
          throw new HttpError(401, "unauthorized", "Request a new BOSS AI sign-in ticket.")
        }
        const user = await deps.rpc("boss_ai_consume_exchange_ticket", { p_ticket: input.ticket })
        if (typeof user !== "string" || !user) {
          throw new HttpError(401, "unauthorized", "Request a new BOSS AI sign-in ticket.")
        }
        return json(await mintToken(user, key), 200, requestId)
      }
      const token = bearer(request)
      if (request.method === "POST" && path === "/auth/token") {
        const user = await deps.sessionUser(token)
        if (!user) throw new HttpError(401, "unauthorized", "Sign in to BOSS to use AI.")
        // Same predicate /auth/exchange applies via boss_ai_consume_exchange_ticket:
        // banned, anonymous, or ai.use-less sessions do not get an AI token at all.
        // Without this, a banned user holding a token could probe /v1/models and
        // /v1/usage (downstream refused them anyway) - the token-mint was a
        // distinguishable signal. The RPC keeps both halves of the function
        // honest about who is allowed.
        const eligible = await deps.rpc("boss_ai_token_eligible", { p_user_id: user })
        if (eligible !== true) {
          throw new HttpError(403, "forbidden", "This account is not permitted to use BOSS AI.")
        }
        return json(await mintToken(user, key), 200, requestId)
      }
      const scopes: Scope[] = path === "/v1/voice/usage"
        ? ["voice"]
        : AUDIO_PATHS.includes(path)
        ? ["inference", "voice"]
        : ["inference"]
      const { user, scope } = await verifyClaims(token, key, scopes)
      if (request.method === "GET" && path === "/v1/provider") {
        return json(
          {
            schema: "boss-managed-provider-v1",
            name: "BOSS AI",
            brokerId: "boss-ai",
            baseUrl: "https://api.risaboss.com/functions/v1/boss-ai/v1",
            defaultForNewUsers: true,
          },
          200,
          requestId,
        )
      }
      if (request.method === "GET" && (path === "/v1/models" || path === "/v1/usage")) {
        phase = "catalog"
        const data = await deps.rpc("boss_ai_catalog", { p_user_id: user })
        return json(
          {
            object: "list",
            data: path === "/v1/models"
              ? data
              : (data as Obj[]).map((model) => ({ model: model.id, allowance: model.allowance })),
          },
          200,
          requestId,
        )
      }
      if (request.method === "POST" && path === "/v1/voice/live") {
        phase = "live_call"
        return await startLiveCall(request, user, key, requestId)
      }
      if (request.method === "POST" && path === "/v1/voice/usage") {
        // A live call's STT runs on LiveKit Inference; the worker reports the caller's speech seconds here
        // so they are charged to the same max-stt pool the proxied transcriptions used.
        phase = "voice_usage"
        const input = await readJson(request.body, 1024)
        const reported = input.seconds
        if (
          Object.keys(input).some((k) => k !== "seconds") || typeof reported !== "number" ||
          !Number.isFinite(reported) || reported <= 0 || reported > LIVE_USAGE_MAX_SECONDS
        ) {
          throw new HttpError(400, "invalid_seconds", "seconds must be a positive number.")
        }
        const seconds = Math.ceil(reported)
        phase = "reservation"
        const result = await deps.rpc("boss_ai_reserve", {
          p_user_id: user,
          p_model_id: LIVE_STT_MODEL,
          p_request_id: requestId,
        }) as { error?: string; usage?: unknown; model: Model } | null
        if (!result || result.error === "forbidden") {
          throw new HttpError(403, "forbidden", "Voice calls are not available for your account.")
        }
        if (result.error === "allowance_exceeded") {
          throw new HttpError(
            429,
            result.error,
            "Your voice allowance has insufficient remaining time. Check BOSS AI usage for reset times.",
            result.usage === undefined ? {} : { usage: result.usage },
          )
        }
        if (result.error) {
          throw new HttpError(429, "busy", "Too many requests for this model. Try again shortly.")
        }
        const settled = await settleReservation(deps, requestId, seconds, audit)
        const quota = await remainingQuota(deps, user, LIVE_STT_MODEL)
        return json(
          {
            billed_seconds: settled ? Math.min(seconds, result.model.max_output_tokens) : null,
            ...(settled ? {} : { billing_status: "reservation_retained" }),
            ...quota,
          },
          200,
          requestId,
        )
      }
      const kind = request.method === "POST" && Object.hasOwn(ROUTES, path)
        ? ROUTES[path]
        : undefined
      if (!kind) throw new HttpError(404, "not_found", "Unknown BOSS AI endpoint.")
      const input = kind === "transcription" && isMultipart(request)
        ? await multipartTranscription(request)
        : await readJson(request.body)
      if (typeof input.model !== "string" || !/^[a-z0-9][a-z0-9._-]{0,99}$/.test(input.model)) {
        throw new HttpError(400, "invalid_model", "Choose a published BOSS model.")
      }
      if (
        scope === "voice" && input.model !== (kind === "speech" ? LIVE_TTS_MODEL : LIVE_STT_MODEL)
      ) {
        throw new HttpError(
          403,
          "forbidden",
          "This token is restricted to the call's voice models.",
        )
      }
      phase = "validation"
      const lookup = await deps.rpc("boss_ai_lookup", {
        p_user_id: user,
        p_model_id: input.model,
      }) as { error?: string; model: Model; connection: Connection } | null
      if (!lookup) {
        throw new HttpError(403, "forbidden", "This model is not available for your account.")
      }
      if (lookup.error) {
        throw new HttpError(
          503,
          "misconfigured_allowance",
          "This model's allowance cannot fit a request. Contact your BOSS administrator.",
        )
      }
      if (lookup.model.kind !== kind) throw wrongKind()
      const prepare = (model: Model, connection: Connection): Prepared =>
        kind === "transcription"
          ? transcriptionBody(input, model)
          : kind === "speech"
          ? speechBody(input, model)
          : { body: requestBody(input, model, connection.api_type, connection.base_url) }
      prepare(lookup.model, lookup.connection)
      phase = "reservation"
      const result = await deps.rpc("boss_ai_reserve", {
        p_user_id: user,
        p_model_id: input.model,
        p_request_id: requestId,
      }) as {
        error?: string
        usage?: unknown
        model: Model
        connection: Connection
        fallbacks?: Fallback[]
      }
      if (result.error) {
        if (result.error === "duplicate") {
          throw new HttpError(409, "duplicate", "This request has already been admitted.")
        }
        if (result.error === "forbidden") {
          throw new HttpError(403, "forbidden", "This model is not available for your account.")
        }
        if (result.error === "allowance_exceeded") {
          throw new HttpError(
            429,
            result.error,
            "Your model allowance has insufficient remaining capacity. Check BOSS AI usage for reset times.",
            result.usage === undefined ? {} : { usage: result.usage },
          )
        }
        throw new HttpError(429, "busy", "Too many requests for this model. Try again shortly.")
      }
      reservation = requestId
      if (result.model.kind !== kind) throw wrongKind()
      const routes = routesOf(result)
      const live = routes.filter((r) => (cooldown.get(r.key) ?? 0) <= now())
      // A route that failed in a way that predicts the next request failing is skipped for a while;
      // when every route is cooling, all of them are tried.
      const order = live.length ? live : routes
      // Never follow redirects with an upstream credential. Only configured servers
      // receive it; request-supplied routing and provider overrides are rejected.
      const abort = new AbortController()
      const cancel = () => abort.abort()
      request.signal.addEventListener("abort", cancel, { once: true })
      if (request.signal.aborted) abort.abort()
      const timeout = setTimeout(cancel, deps.upstreamTimeoutMs ?? 240_000)
      const cleanup = () => {
        clearTimeout(timeout)
        request.signal.removeEventListener("abort", cancel)
      }
      let won: { route: Route; prepared: Prepared; upstream: Response } | undefined
      let failed: unknown
      // An earlier attempt may have run inference: a later refundable status cannot refund it.
      let unknownOutcome = false
      for (let i = 0; i < order.length && !won; i++) {
        const route = order[i]
        if (abort.signal.aborted) {
          failed ??= new HttpError(499, "cancelled", "The AI request was cancelled.")
          break
        }
        let prepared: Prepared, url: string, apiKey: string
        try {
          prepared = prepare(route.model, route.connection)
          url = kind === "chat"
            ? endpoint(route.connection)
            : audioEndpoint(route.connection, kind === "speech" ? "speech" : "transcriptions")
          apiKey = upstreamKey(route.connection, deps.secret)
        } catch (e) {
          audit("route_unusable")
          failed = e
          continue
        }
        // Non-final routes get a first-response deadline so a hung route leaves time for the next.
        const attempt = new AbortController()
        const link = () => attempt.abort()
        abort.signal.addEventListener("abort", link, { once: true })
        const timer = i < order.length - 1
          ? setTimeout(link, deps.attemptTimeoutMs ?? 90_000)
          : undefined
        const release = () => {
          clearTimeout(timer)
          abort.signal.removeEventListener("abort", link)
        }
        let upstream: Response
        try {
          phase = "upstream_dispatch"
          dispatched = true
          upstream = await deps.fetch(url, {
            method: "POST",
            redirect: "error",
            signal: attempt.signal,
            headers: upstreamHeaders(route.connection, apiKey, kind === "chat"),
            body: JSON.stringify(prepared.body),
          })
        } catch (e) {
          release()
          unknownOutcome = true
          failed = e
          upstreamError(
            route,
            null,
            abort.signal.aborted || attempt.signal.aborted ? "timeout" : "fetch_error",
          )
          // A client cancel or the overall deadline ends the request; only a failed route falls back.
          if (abort.signal.aborted) break
          continue
        }
        clearTimeout(timer)
        if (!upstream.ok) {
          const info = await errorInfo(upstream)
          release()
          upstreamError(route, upstream.status, info.type, info.message)
          // Known validation/auth/billing rejections can be refunded. A timeout or 5xx
          // does not prove inference did not run (a proxy can fail after forwarding).
          if ([400, 401, 402, 403, 404, 405, 406, 413, 415, 422, 429].includes(upstream.status)) {
            dispatched = unknownOutcome
          } else unknownOutcome = true
          if (trips(upstream.status, info.message)) cooldown.set(route.key, now() + COOLDOWN_MS)
          audit(`upstream_status_${upstream.status}`)
          failed = new HttpError(
            upstream.status === 429 ? 503 : 502,
            "upstream_error",
            "The model is temporarily unavailable. Please try again.",
          )
          continue
        }
        cooldown.delete(route.key)
        won = { route, prepared, upstream }
      }
      if (!won) {
        cleanup()
        throw failed ?? new HttpError(503, "unavailable", "BOSS AI is temporarily unavailable.")
      }
      const { route: served, prepared, upstream } = won
      if (served !== routes[0]) audit("upstream_fallback")
      const cacheMiss = (u: unknown) => {
        if (kind === "chat" && expectsCache(served) && cacheMissLarge(u)) {
          audit("cache_miss_large_prompt")
        }
      }
      const settleWithRetry = async (tokens: number | null) => {
        if (tokens === null) audit("usage_unknown_reservation_retained")
        const reserved = kind === "chat"
          ? result.model.context_length
          : result.model.max_output_tokens
        if (tokens !== null && tokens > reserved) {
          audit(kind === "chat" ? "usage_exceeds_configured_context" : "usage_exceeds_reservation")
        }
        phase = "settlement"
        await settleReservation(deps, requestId, tokens, audit)
      }
      if (kind === "transcription") {
        try {
          phase = "upstream_response"
          const payload = await readJson(upstream.body, 1024 * 1024).catch(() => {
            throw new HttpError(502, "upstream_error", "The model returned an invalid response.")
          })
          if (typeof payload.text !== "string") {
            throw new HttpError(502, "upstream_error", "The model returned an invalid response.")
          }
          const seconds = transcriptionSeconds(payload, prepared.audio!, prepared.format!)
          measuredTokens = seconds === null ? null : Math.ceil(seconds)
          await settleWithRetry(measuredTokens)
          reservation = undefined
          // A retained reservation (unknown duration) bills the full reserved window.
          const billed = Math.min(measuredTokens ?? Infinity, result.model.max_output_tokens)
          const quota = await remainingQuota(deps, user, input.model as string)
          return json(
            { text: payload.text, usage: { seconds, billed_seconds: billed, ...quota } },
            200,
            requestId,
          )
        } finally {
          cleanup()
        }
      }
      if (kind === "speech") {
        const source = upstream.body
        if (!source) {
          cleanup()
          throw new HttpError(502, "upstream_error", "The model returned an invalid response.")
        }
        let out: ReadableStream<Uint8Array> | undefined
        try {
          // prepare() bounded the input by max_output_tokens, so this never exceeds the reservation.
          measuredTokens = prepared.seconds!
          await settleWithRetry(measuredTokens)
          reservation = undefined
          const quota = await remainingQuota(deps, user, input.model as string)
          const resets = quota && validResetsAt(quota.resets_at) ? quota : null
          out = capped(source, MAX_AUDIO_OUTPUT_BYTES, cleanup)
          return new Response(out, {
            headers: {
              ...(resets && {
                "X-Remaining-Seconds": String(resets.remaining_seconds),
                "X-Resets-At": resets.resets_at,
              }),
              "Content-Type": "audio/mpeg",
              "Cache-Control": "no-store",
              "X-Request-ID": requestId,
              "X-Billed-Seconds": String(measuredTokens),
            },
          })
        } catch (error) {
          // capped owns the reader and runs cleanup once on cancel; otherwise do both here.
          if (out) await out.cancel().catch(() => {})
          else {
            await source.cancel().catch(() => {})
            cleanup()
          }
          throw error
        }
      }
      if (input.stream !== true) {
        try {
          phase = "upstream_response"
          const payload = await readJson(upstream.body, 16 * 1024 * 1024).catch(() => {
            throw new HttpError(502, "upstream_error", "The model returned an invalid response.")
          })
          const output = completion(
            payload,
            input.model,
            served.connection.api_type,
            requestId,
          )
          cacheMiss(output.usage)
          const tokens = (output.usage as Obj | null)?.total_tokens
          measuredTokens = typeof tokens === "number" ? tokens : null
          await settleWithRetry(measuredTokens)
          reservation = undefined
          return json(output, 200, requestId)
        } finally {
          cleanup()
        }
      }
      if (!upstream.body) {
        cleanup()
        throw new Error("Missing stream")
      }
      const adapter = new StreamAdapter(input.model, served.connection.api_type, requestId)
      const iterator = events(upstream.body, abort.signal)[Symbol.asyncIterator]()
      const encoder = new TextEncoder()
      const frame = (data: unknown) =>
        encoder.encode(`data: ${typeof data === "string" ? data : JSON.stringify(data)}\n\n`)
      let closed = false
      let clientCancelled = false
      const finish = async (tokens: number | null) => {
        if (closed) return
        closed = true
        abort.abort()
        cleanup()
        await iterator.return?.(undefined).catch(() => {})
        if (adapter.finished) cacheMiss(adapter.usage)
        // If settlement fails the full reservation remains charged. Never refund
        // an unknown outcome merely because a worker or a client disconnected.
        await settleWithRetry(tokens)
      }
      reservation = undefined // The stream now owns cleanup and settlement.
      const stream = new ReadableStream<Uint8Array>({
        async pull(controller) {
          try {
            // A pull that enqueues nothing is never called again: keep reading until a frame is out.
            while (true) {
              const next = await iterator.next()
              if (next.done) {
                if (!adapter.finished) throw new Error("Truncated stream")
                await finish(adapter.tokens)
                controller.enqueue(frame("[DONE]"))
                controller.close()
                return
              }
              const chunks = adapter.accept(next.value)
              for (const chunk of chunks) controller.enqueue(frame(chunk))
              if (adapter.finished) {
                await finish(adapter.tokens)
                controller.enqueue(frame("[DONE]"))
                controller.close()
                return
              }
              if (chunks.length) return
            }
          } catch {
            audit("stream_failed")
            await finish(adapter.tokens)
            if (clientCancelled) return
            controller.enqueue(
              frame({
                error: {
                  code: "stream_failed",
                  message: "The model response was interrupted. Please retry.",
                },
              }),
            )
            controller.close()
          }
        },
        async cancel() {
          clientCancelled = true
          await finish(adapter.tokens)
        },
      })
      return new Response(stream, {
        headers: {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-store",
          "X-Request-ID": requestId,
        },
      })
    } catch (error) {
      if (!(error instanceof HttpError) || error.status >= 500) audit(`${phase}_failed`)
      if (reservation) {
        if (dispatched && measuredTokens === null) audit("usage_unknown_reservation_retained")
        await settleReservation(deps, reservation, dispatched ? measuredTokens : 0, audit)
      }
      return failure(error, requestId)
    }
  }
}
