import { bearer, HttpError, mintToken, signingKey, verifyToken } from "./auth.ts"
import {
  completion,
  Connection,
  endpoint,
  events,
  Model,
  Obj,
  readJson,
  requestBody,
  StreamAdapter,
  upstreamKey,
} from "./wire.ts"

export interface Dependencies {
  sessionUser(token: string): Promise<string | null>
  rpc(name: string, params: Obj): Promise<unknown>
  secret(name: string): string | undefined
  fetch: typeof fetch
  upstreamTimeoutMs?: number
  audit?: (event: string, requestId: string) => void
}
const headers = { "Content-Type": "application/json", "Cache-Control": "no-store" }
const json = (body: unknown, status = 200, requestId = "") =>
  new Response(JSON.stringify(body), { status, headers: { ...headers, "X-Request-ID": requestId } })
function failure(error: unknown, requestId: string): Response {
  const e = error instanceof HttpError
    ? error
    : new HttpError(503, "unavailable", "BOSS AI is temporarily unavailable.")
  return json({ error: { code: e.code, message: e.message } }, e.status, requestId)
}

export function createHandler(deps: Dependencies): (request: Request) => Promise<Response> {
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
    let reservation: string | undefined
    let dispatched = false
    let measuredTokens: number | null = null
    let phase = "authentication"
    try {
      const path = new URL(request.url).pathname.replace(/^\/functions\/v1/, "").replace(
        /^\/boss-ai(?=\/|$)/,
        "",
      )
      const token = bearer(request)
      const key = signingKey(deps.secret("BOSS_AI_SIGNING_SECRET"))
      if (request.method === "POST" && path === "/auth/token") {
        const user = await deps.sessionUser(token)
        if (!user) throw new HttpError(401, "unauthorized", "Sign in to BOSS to use AI.")
        return json(await mintToken(user, key), 200, requestId)
      }
      const user = await verifyToken(token, key)
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
      if (request.method !== "POST" || path !== "/v1/chat/completions") {
        throw new HttpError(404, "not_found", "Unknown BOSS AI endpoint.")
      }
      const input = await readJson(request.body)
      if (typeof input.model !== "string" || !/^[a-z0-9][a-z0-9._-]{0,99}$/.test(input.model)) {
        throw new HttpError(400, "invalid_model", "Choose a published BOSS model.")
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
      requestBody(input, lookup.model, lookup.connection.api_type)
      phase = "reservation"
      const result = await deps.rpc("boss_ai_reserve", {
        p_user_id: user,
        p_model_id: input.model,
        p_request_id: requestId,
      }) as { error?: string; model: Model; connection: Connection }
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
          )
        }
        throw new HttpError(429, "busy", "Too many requests for this model. Try again shortly.")
      }
      reservation = requestId
      const body = requestBody(input, result.model, result.connection.api_type)
      const url = endpoint(result.connection)
      const apiKey = upstreamKey(result.connection, deps.secret)
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
      let upstream: Response
      try {
        if (abort.signal.aborted) {
          throw new HttpError(499, "cancelled", "The AI request was cancelled.")
        }
        phase = "upstream_dispatch"
        dispatched = true
        upstream = await deps.fetch(url, {
          method: "POST",
          redirect: "error",
          signal: abort.signal,
          headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
          body: JSON.stringify(body),
        })
      } catch (e) {
        cleanup()
        throw e
      }
      if (!upstream.ok) {
        await upstream.body?.cancel()
        cleanup()
        // Known validation/auth/billing rejections can be refunded. A timeout or 5xx
        // does not prove inference did not run (a proxy can fail after forwarding).
        if ([400, 401, 402, 403, 404, 405, 406, 413, 415, 422, 429].includes(upstream.status)) {
          dispatched = false
        }
        audit(`upstream_status_${upstream.status}`)
        throw new HttpError(
          upstream.status === 429 ? 503 : 502,
          "upstream_error",
          "The model is temporarily unavailable. Please try again.",
        )
      }
      const settle = async (tokens: number | null) => {
        if (tokens === null) audit("usage_unknown_reservation_retained")
        if (tokens !== null && tokens > result.model.context_length) {
          audit("usage_exceeds_configured_context")
        }
        phase = "settlement"
        await deps.rpc("boss_ai_settle", { p_request_id: requestId, p_tokens: tokens })
      }
      const settleWithRetry = async (tokens: number | null) => {
        await settle(tokens).catch(async () => {
          await settle(tokens).catch(() => audit("settlement_failed"))
        })
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
            result.connection.api_type,
            requestId,
          )
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
      const adapter = new StreamAdapter(input.model, result.connection.api_type, requestId)
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
        // If settlement fails the full reservation remains charged. Never refund
        // an unknown outcome merely because a worker or a client disconnected.
        await settleWithRetry(tokens)
      }
      reservation = undefined // The stream now owns cleanup and settlement.
      const stream = new ReadableStream<Uint8Array>({
        async pull(controller) {
          try {
            const next = await iterator.next()
            if (next.done) {
              if (!adapter.finished) throw new Error("Truncated stream")
              await finish(adapter.tokens)
              controller.enqueue(frame("[DONE]"))
              controller.close()
              return
            }
            for (const chunk of adapter.accept(next.value)) controller.enqueue(frame(chunk))
            if (adapter.finished) {
              await finish(adapter.tokens)
              controller.enqueue(frame("[DONE]"))
              controller.close()
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
        const settlement = {
          p_request_id: reservation,
          p_tokens: dispatched ? measuredTokens : 0,
        }
        await deps.rpc("boss_ai_settle", settlement).catch(async () => {
          await deps.rpc("boss_ai_settle", settlement).catch(() => audit("settlement_failed"))
        })
      }
      return failure(error, requestId)
    }
  }
}
