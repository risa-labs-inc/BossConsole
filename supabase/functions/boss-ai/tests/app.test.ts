import { assert, assertEquals } from "@std/assert"
import { createHandler } from "../app.ts"
import { smallestRemaining, wavSeconds } from "../audio.ts"
import { mintToken, signingKey } from "../auth.ts"
import { Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
async function fixture(options: {
  deny?: string
  stream?: string
  upstreamStatus?: number
  payload?: Obj
  failFirstSettlement?: boolean
  failAllSettlements?: boolean
  lookupDenied?: boolean
  lookupMisconfigured?: boolean
  failFetch?: boolean
  hang?: boolean
  hangSettlement?: boolean
  hangFirstSettlement?: boolean
  stallStream?: boolean
  removeToolsAfterPreflight?: boolean
  keyName?: string
  apiType?: "openai_chat" | "openai_responses"
  eligible?: boolean
  eligibilityError?: boolean
  providerRouting?: unknown
  routingAfterPreflight?: unknown
  baseUrl?: string
  kind?: "chat" | "transcription" | "speech"
  voice?: string | null
  reserveUsage?: unknown
  audio?: ReadableStream<Uint8Array>
  poolUsage?: unknown
  failUsage?: boolean
  reserveMaxOutput?: number
} = {}) {
  const calls: { name: string; params: Obj }[] = []
  const requests: Request[] = []
  const audits: string[] = []
  let settlements = 0
  const token =
    (await mintToken("00000000-0000-0000-0000-000000000001", signingKey(secret))).access_token
  const handler = createHandler({
    audit: (event) => {
      audits.push(event)
    },
    upstreamTimeoutMs: 20,
    settlementTimeoutMs: 5,
    sessionUser: async (t) => t === "boss-session" ? "00000000-0000-0000-0000-000000000001" : null,
    secret: (name) =>
      name === "BOSS_AI_SIGNING_SECRET"
        ? secret
        : name === "BOSS_AI_PUBLIC_URL"
        ? "https://api.risaboss.com/functions/v1/boss-ai"
        : "upstream-secret",
    async rpc(name, params) {
      calls.push({ name, params })
      if (name === "boss_ai_settle") settlements++
      if (name === "boss_ai_lookup" && options.lookupDenied) return null
      if (name === "boss_ai_lookup" && options.lookupMisconfigured) {
        return { error: "misconfigured_allowance" }
      }
      if (name === "boss_ai_settle" && options.failAllSettlements) {
        throw new Error("database unavailable")
      }
      // BossConsole#1252: a settlement RPC that never resolves. The fix
      // bounds the call so the edge function does not hang.
      if (
        name === "boss_ai_settle" &&
        (options.hangSettlement || (options.hangFirstSettlement && settlements === 1))
      ) {
        await new Promise<void>(() => {/* never resolves */})
      }
      if (name === "boss_ai_settle" && options.failFirstSettlement && settlements === 1) {
        throw new Error("private database detail")
      }
      if (name === "boss_ai_usage") {
        if (options.failUsage) throw new Error("database unavailable")
        return options.poolUsage ?? null
      }
      if (name === "boss_ai_catalog") {
        return [{ id: "boss-test", allowance: { day: { remaining: 1024 } } }]
      }
      if (name === "boss_ai_token_eligible") {
        if (options.eligibilityError) throw new Error("database unavailable")
        return options.eligible !== false
      }
      if (name === "boss_ai_reserve" || name === "boss_ai_lookup") {
        return options.deny && name === "boss_ai_reserve"
          ? { error: options.deny, usage: options.reserveUsage }
          : {
            model: {
              id: "boss-test",
              upstream_model: "private-model",
              kind: options.kind ?? "chat",
              voice: options.voice ?? null,
              context_length: 4096,
              max_output_tokens: options.kind && options.kind !== "chat"
                ? (name === "boss_ai_reserve" && options.reserveMaxOutput) || 120
                : 512,
              provider_routing:
                name === "boss_ai_reserve" && options.routingAfterPreflight !== undefined
                  ? options.routingAfterPreflight
                  : options.providerRouting ?? null,
              capabilities: options.removeToolsAfterPreflight && name === "boss_ai_lookup"
                ? ["text", "tools"]
                : ["text"],
            },
            connection: {
              base_url: options.baseUrl ?? "https://upstream.example/v1",
              api_type: options.apiType ?? "openai_chat",
              api_key_secret: options.keyName ?? "BOSS_AI_TEST",
            },
          }
      }
      return null
    },
    fetch: async (url, init) => {
      requests.push(new Request(url, init))
      if (options.failFetch) throw new Error("private network detail")
      if (options.hang) {
        await new Promise<void>((_, reject) => {
          init!.signal!.addEventListener("abort", () => reject(new Error("timeout")), {
            once: true,
          })
        })
      }
      if (options.kind === "speech") {
        return new Response(options.audio ?? new Blob([new Uint8Array([1, 2, 3])]), {
          status: options.upstreamStatus ?? 200,
        })
      }
      if (options.kind === "transcription" && !options.payload) {
        return new Response(JSON.stringify({ text: "hello there", usage: { seconds: 3.4 } }), {
          status: options.upstreamStatus ?? 200,
        })
      }
      return new Response(
        options.stallStream
          ? new ReadableStream<Uint8Array>({
            start(controller) {
              controller.enqueue(
                new TextEncoder().encode('data: {"choices":[{"delta":{"content":"partial"}}]}\n\n'),
              )
            },
          })
          : options.stream ??
            JSON.stringify(
              options.payload ?? {
                choices: [{
                  message: { role: "assistant", content: "hello" },
                  finish_reason: "stop",
                  index: 0,
                }],
                usage: { prompt_tokens: 2, completion_tokens: 3 },
              },
            ),
        { status: options.upstreamStatus ?? 200 },
      )
    },
  })
  const request = (body: Obj, credential = token, path = "chat/completions") =>
    new Request(`https://api.example/functions/v1/boss-ai/v1/${path}`, {
      method: "POST",
      headers: { Authorization: `Bearer ${credential}` },
      body: JSON.stringify(body),
    })
  return { calls, requests, handler, request, token, audits }
}
const body = { model: "boss-test", messages: [{ role: "user", content: "private prompt" }] }

Deno.test("all inference and catalog access requires AI-scoped authentication", async () => {
  const f = await fixture()
  for (const path of ["v1/provider", "v1/models", "v1/usage", "v1/chat/completions"]) {
    assertEquals((await f.handler(new Request(`https://api.example/boss-ai/${path}`))).status, 401)
  }
  for (const token of ["boss-session", f.token.slice(0, -5) + "wrong"]) {
    assertEquals((await f.handler(f.request(body, token))).status, 401)
  }
  assertEquals(f.calls.length, 0)
  assertEquals(f.requests.length, 0)
})

Deno.test("invalid routes and oversized requests fail before database access", async () => {
  const f = await fixture()
  for (const [path, method] of [["auth/token", "GET"], ["v1/models", "POST"], ["unknown", "GET"]]) {
    const response = await f.handler(
      new Request(`https://api.example/boss-ai/${path}`, {
        method,
        headers: { Authorization: `Bearer ${f.token}` },
      }),
    )
    assertEquals(response.status, 404)
    assert(response.headers.get("x-request-id"))
  }
  const response = await f.handler(
    f.request({ ...body, messages: [{ role: "user", content: "x".repeat(5 * 1024 * 1024) }] }),
  )
  assertEquals(response.status, 413)
  assertEquals(f.calls.length, 0)
  assertEquals(f.requests.length, 0)
})

Deno.test("broker accepts a BOSS session but not an AI token", async () => {
  const f = await fixture()
  const req = (t: string) =>
    new Request("https://api.example/boss-ai/auth/token", {
      method: "POST",
      headers: { Authorization: `Bearer ${t}` },
    })
  assertEquals((await f.handler(req(f.token))).status, 401)
  const response = await f.handler(req("boss-session"))
  assertEquals(response.status, 200)
  assertEquals((await response.json()).refresh_after_seconds, 180)
})

Deno.test("/auth/token refuses sessions that /auth/exchange would also refuse", async () => {
  // /auth/exchange already runs the banned / anonymous / ai.use-less predicate
  // through boss_ai_consume_exchange_ticket. /auth/token previously only checked
  // the session was valid - so a banned or anonymous session got a 200 + a token,
  // a distinguishable probe result. The RPC keeps both halves honest.
  const f = await fixture({ eligible: false })
  const req = new Request("https://api.example/boss-ai/auth/token", {
    method: "POST",
    headers: { Authorization: "Bearer boss-session" },
  })
  const response = await f.handler(req)
  assertEquals(response.status, 403)
  assertEquals(f.calls.some((c) => c.name === "boss_ai_token_eligible"), true)
  assertEquals(f.calls.some((c) => c.name === "boss_ai_consume_exchange_ticket"), false)
})

Deno.test("permission and allowance denials never dispatch upstream", async () => {
  for (
    const [deny, status] of [["forbidden", 403], ["allowance_exceeded", 429], [
      "concurrency_exceeded",
      429,
    ]] as const
  ) {
    const f = await fixture({ deny })
    assertEquals((await f.handler(f.request(body))).status, status)
    assertEquals(f.requests.length, 0)
  }
})

Deno.test("server-selected endpoint, model and key; actual usage settlement", async () => {
  const f = await fixture()
  const response = await f.handler(f.request(body))
  assertEquals(response.status, 200)
  assertEquals(f.requests[0].url, "https://upstream.example/v1/chat/completions")
  assertEquals(f.requests[0].headers.get("authorization"), "Bearer upstream-secret")
  assertEquals(f.requests[0].redirect, "error")
  assertEquals((await f.requests[0].json()).model, "private-model")
  assertEquals(f.calls.at(-1)?.params.p_tokens, 5)
  const output = await response.text()
  assert(!output.includes("upstream-secret"))
  assert(!output.includes("private-model"))
  assertEquals(typeof JSON.parse(output).created, "number")
  assert(response.headers.get("x-request-id"))
})

Deno.test("operator provider routing reaches the upstream; callers cannot set it", async () => {
  const routing = { order: ["cerebras", "coreweave"], allow_fallbacks: true, ignore: ["groq"] }
  const openrouter = "https://openrouter.ai/api/v1"
  const routed = await fixture({ providerRouting: routing, baseUrl: openrouter })
  assertEquals((await routed.handler(routed.request(body))).status, 200)
  assertEquals((await routed.requests[0].json()).provider, routing)
  const caller = { ...body, provider: { order: ["attacker"] } }
  assertEquals((await routed.handler(routed.request(caller))).status, 400)
  assertEquals(routed.requests.length, 1)

  const plain = await fixture()
  assertEquals((await plain.handler(plain.request(body))).status, 200)
  assertEquals("provider" in await plain.requests[0].json(), false)

  for (
    const broken of [
      await fixture({ providerRouting: { order: ["cerebras"], extra: 1 }, baseUrl: openrouter }),
      await fixture({ providerRouting: routing }),
    ]
  ) {
    assertEquals((await broken.handler(broken.request(body))).status, 503)
    assert(broken.calls.every((call) => call.name === "boss_ai_lookup"))
    assertEquals(broken.requests.length, 0)
  }
})

Deno.test("routing broken between preflight and admission refunds without dispatch", async () => {
  const f = await fixture({
    providerRouting: { order: ["cerebras"] },
    routingAfterPreflight: { order: ["cerebras"], extra: true },
    baseUrl: "https://openrouter.ai/api/v1",
  })
  assertEquals((await f.handler(f.request(body))).status, 503)
  assertEquals(f.calls.map((c) => c.name), ["boss_ai_lookup", "boss_ai_reserve", "boss_ai_settle"])
  assertEquals(f.calls.at(-1)?.params.p_tokens, 0)
  assertEquals(f.requests.length, 0)
})

Deno.test("invalid inputs never create accounting reservations", async () => {
  const f = await fixture()
  for (
    const invalid of [{ model: body.model }, { ...body, provider: "attacker" }, {
      ...body,
      messages: [{ role: "user", name: {}, content: "text" }],
    }, {
      ...body,
      messages: [{
        role: "user",
        content: [{ type: "image_url", image_url: { url: "data:image/png;base64,YQ==" } }],
      }],
    }]
  ) {
    assertEquals((await f.handler(f.request(invalid))).status, 400)
  }
  assert(f.calls.every((call) => call.name === "boss_ai_lookup"))
  assertEquals(f.requests.length, 0)
})

Deno.test("admission revalidates changed model capabilities and refunds without dispatch", async () => {
  const f = await fixture({ removeToolsAfterPreflight: true })
  const response = await f.handler(
    f.request({ ...body, tools: [{ type: "function", function: { name: "test" } }] }),
  )
  assertEquals(response.status, 400)
  assertEquals(f.calls.map((c) => c.name), ["boss_ai_lookup", "boss_ai_reserve", "boss_ai_settle"])
  assertEquals(f.calls.at(-1)?.params.p_tokens, 0)
  assertEquals(f.requests.length, 0)
})

Deno.test("duplicate admission is a conflict and pre-dispatch abort refunds without fetch", async () => {
  const duplicate = await fixture({ deny: "duplicate" })
  assertEquals((await duplicate.handler(duplicate.request(body))).status, 409)
  assertEquals(duplicate.requests.length, 0)
  const f = await fixture()
  const abort = new AbortController()
  abort.abort()
  const response = await f.handler(new Request(f.request(body), { signal: abort.signal }))
  assertEquals(response.status, 499)
  assertEquals(f.calls.at(-1)?.params.p_tokens, 0)
  assertEquals(f.requests.length, 0)
})

Deno.test("truncated streams report failure and settle an estimate, not the reservation", async () => {
  const f = await fixture({ stream: 'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n' })
  const response = await f.handler(f.request({ ...body, stream: true }))
  const output = await response.text()
  assert(output.includes("stream_failed"))
  assert(!output.includes("[DONE]"))
  const settled = f.calls.at(-1)?.params.p_tokens as number
  assert(Number.isInteger(settled) && settled >= 1 && settled < 4096)
  assert(f.audits.includes("usage_estimated_after_cancel"))
})

Deno.test("a stalled SSE body times out and settles without hanging the client", async () => {
  const f = await fixture({ stallStream: true })
  const response = await f.handler(f.request({ ...body, stream: true }))
  const output = await response.text()
  assert(output.includes("partial"))
  assert(output.includes("stream_failed"))
  assert(!output.includes("[DONE]"))
  assertEquals(f.calls.filter((call) => call.name === "boss_ai_settle").length, 1)
  const settled = f.calls.at(-1)?.params.p_tokens as number
  assert(Number.isInteger(settled) && settled >= 1 && settled < 4096)
  assert(f.audits.includes("usage_estimated_after_cancel"))
})

Deno.test("late stream truncation preserves already reported usage", async () => {
  const f = await fixture({
    stream: 'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5}}\n\n',
  })
  const response = await f.handler(f.request({ ...body, stream: true }))
  assert((await response.text()).includes("stream_failed"))
  assertEquals(f.calls.at(-1)?.params.p_tokens, 17)
})

Deno.test("complete streams settle final usage and terminate once", async () => {
  const f = await fixture({
    stream:
      'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5}}\n\ndata: [DONE]\n\n',
  })
  const response = await f.handler(f.request({ ...body, stream: true }))
  assertEquals((await response.text()).split("[DONE]").length, 2)
  assertEquals(f.calls.at(-1)?.params.p_tokens, 17)
  assertEquals(f.calls.filter((c) => c.name === "boss_ai_settle").length, 1)
})

Deno.test("catalog and usage are accessible inside the declared broker scope", async () => {
  const f = await fixture()
  const get = (path: string) =>
    f.handler(
      new Request(`https://api.example/boss-ai/v1/${path}`, {
        headers: { Authorization: `Bearer ${f.token}` },
      }),
    )
  assertEquals((await (await get("models")).json()).data[0].id, "boss-test")
  assertEquals((await (await get("usage")).json()).data, [
    { model: "boss-test", allowance: { day: { remaining: 1024 } } },
  ])
})

Deno.test("explicit upstream rejections refund while ambiguous server faults remain charged", async () => {
  for (const status of [400, 401, 402, 403, 404, 413, 422, 429, 408, 500, 502, 503, 504]) {
    const f = await fixture({ upstreamStatus: status })
    const response = await f.handler(f.request(body))
    assertEquals(response.status, status === 429 ? 503 : 502)
    const charged = f.calls.at(-1)?.params.p_tokens
    if (status < 500 && status !== 408) assertEquals(charged, 0)
    else assert(typeof charged === "number" && charged > 0 && charged < 1024) // estimate, not the reservation
    assert(f.audits.includes(`upstream_status_${status}`))
  }
})

Deno.test("failed dispatch and timeout are observable unknown outcomes not inferred refunds", async () => {
  for (const options of [{ failFetch: true }, { hang: true }]) {
    const f = await fixture(options)
    const response = await f.handler(f.request(body))
    assertEquals(response.status, 503)
    const charged = f.calls.at(-1)?.params.p_tokens
    assert(typeof charged === "number" && charged > 0 && charged < 1024)
    assert(f.audits.includes("usage_unknown_estimate_charged"))
    assert(!(await response.text()).includes("private"))
  }
})

Deno.test("known usage survives a settlement retry", async () => {
  const f = await fixture({ failFirstSettlement: true })
  const response = await f.handler(f.request(body))
  assertEquals(response.status, 200)
  assertEquals((await response.json()).choices[0].message.content, "hello")
  assertEquals(
    f.calls.filter((call) => call.name === "boss_ai_settle").map((call) => call.params.p_tokens),
    [5, 5],
  )
})

Deno.test("settlement outages preserve the completion and deduplicate accounting diagnostics", async () => {
  const f = await fixture({
    failAllSettlements: true,
    payload: { choices: [{ message: { role: "assistant", content: "hello" } }] },
  })
  const response = await f.handler(f.request(body))
  assertEquals(response.status, 200)
  assertEquals((await response.json()).choices[0].message.content, "hello")
  assertEquals(
    f.calls.filter((call) => call.name === "boss_ai_settle").map((call) => call.params.p_tokens),
    [null, null],
  )
  assertEquals(f.audits.filter((event) => event === "usage_unknown_reservation_retained").length, 1)
  assertEquals(f.audits.filter((event) => event === "settlement_failed").length, 1)
})

Deno.test("preflight permission denial never creates a reservation", async () => {
  const f = await fixture({ lookupDenied: true })
  assertEquals((await f.handler(f.request(body))).status, 403)
  assertEquals(f.calls.map((call) => call.name), ["boss_ai_lookup"])
  assertEquals(f.requests.length, 0)
})

Deno.test("impossible allowances report configuration faults instead of temporary exhaustion", async () => {
  const f = await fixture({ lookupMisconfigured: true })
  const response = await f.handler(f.request(body))
  assertEquals(response.status, 503)
  assertEquals((await response.json()).error.code, "misconfigured_allowance")
  assertEquals(f.calls.map((call) => call.name), ["boss_ai_lookup"])
  assertEquals(f.requests.length, 0)
})

Deno.test("missing usage emits accounting diagnostics and malformed upstream is a 502", async () => {
  const f = await fixture({
    payload: { choices: [{ message: { role: "assistant", content: "hi" } }] },
  })
  assertEquals((await f.handler(f.request(body))).status, 200)
  assert(f.audits.includes("usage_unknown_reservation_retained"))
  assertEquals(f.calls.at(-1)?.params.p_tokens, null)
  const bad = await fixture({ payload: {} })
  assertEquals((await bad.handler(bad.request(body))).status, 502)
})

Deno.test("routing cannot disclose infrastructure or signing secrets", async () => {
  for (const keyName of ["BOSS_AI_SIGNING_SECRET", "SUPABASE_SERVICE_ROLE_KEY"]) {
    const f = await fixture({ keyName })
    assertEquals((await f.handler(f.request(body))).status, 503)
    assertEquals(f.requests.length, 0)
    assertEquals(f.calls.at(-1)?.params.p_tokens, 0)
  }
})

Deno.test("Responses endpoint round trip exposes only the published model", async () => {
  const f = await fixture({
    apiType: "openai_responses",
    payload: {
      status: "completed",
      output: [{ type: "message", content: [{ type: "output_text", text: "hello" }] }],
      usage: { input_tokens: 2, output_tokens: 3 },
    },
  })
  const response = await f.handler(f.request(body))
  assertEquals(response.status, 200)
  assertEquals(f.requests[0].url, "https://upstream.example/v1/responses")
  assertEquals((await response.json()).model, "boss-test")
  assertEquals(f.calls.at(-1)?.params.p_tokens, 5)
})

Deno.test("stream cancellation settles an estimate and does not attempt to write to a closed client", async () => {
  const f = await fixture({ stream: 'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n' })
  const response = await f.handler(f.request({ ...body, stream: true }))
  const reader = response.body!.getReader()
  await reader.read()
  await reader.cancel()
  assertEquals(f.calls.filter((call) => call.name === "boss_ai_settle").length, 1)
  const settled = f.calls.at(-1)?.params.p_tokens as number
  assert(Number.isInteger(settled) && settled >= 1 && settled < 4096)
  assert(f.audits.includes("usage_estimated_after_cancel"))
})

Deno.test("provider publishes discovery metadata without vault or upstream access", async () => {
  const f = await fixture()
  const response = await f.handler(
    new Request("https://api.example/boss-ai/v1/provider", {
      headers: { Authorization: `Bearer ${f.token}` },
    }),
  )
  assertEquals(response.status, 200)
  assertEquals(response.headers.get("cache-control"), "no-store")
  assertEquals(await response.json(), {
    schema: "boss-managed-provider-v1",
    name: "BOSS AI",
    brokerId: "boss-ai",
    baseUrl: "https://api.risaboss.com/functions/v1/boss-ai/v1",
    defaultForNewUsers: true,
  })
  assertEquals(f.calls.length, 0)
  assertEquals(f.requests.length, 0)
})

Deno.test("RPC tickets exchange for AI tokens without a BOSS session", async () => {
  const ticket = "a".repeat(64)
  let consumed = false
  let calls = 0
  const handler = createHandler({
    sessionUser: () => {
      throw new Error("Must not access BOSS session")
    },
    secret: (name) =>
      name === "BOSS_AI_PUBLIC_URL" ? "https://api.risaboss.com/functions/v1/boss-ai" : secret,
    fetch: () => {
      throw new Error("Must not call upstream")
    },
    rpc: async (name, params) => {
      calls++
      assertEquals(name, "boss_ai_consume_exchange_ticket")
      assertEquals(params, { p_ticket: ticket })
      if (consumed) return null
      consumed = true
      return "00000000-0000-0000-0000-000000000001"
    },
  })
  const exchange = (value: string) =>
    handler(
      new Request("https://api.example/boss-ai/auth/exchange", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ticket: value }),
      }),
    )
  assertEquals((await exchange("invalid")).status, 401)
  assertEquals(calls, 0)
  const response = await exchange(ticket)
  assertEquals(response.status, 200)
  const token = (await response.json()).access_token
  const metadata = await handler(
    new Request("https://api.example/boss-ai/v1/provider", {
      headers: { Authorization: `Bearer ${token}` },
    }),
  )
  assertEquals(metadata.status, 200)
  assertEquals((await exchange(ticket)).status, 401)
})

// BossConsole#1252: settlement must be bounded by a timeout, not a hung DB.
// Without it, a slow settlement RPC hangs the whole edge function - the
// upstream response has already been streamed, but the request cannot
// return until the settle() call resolves.

Deno.test("a hung settlement does not block the response (BossConsole#1252)", async () => {
  const f = await fixture({ hangSettlement: true })
  const start = Date.now()
  const response = await f.handler(f.request(body))
  const elapsed = Date.now() - start
  assertEquals(response.status, 200)
  assertEquals((await response.json()).choices[0].message.content, "hello")
  // The response must arrive in bounded time even though the settlement
  // RPC never resolves. Allow a generous bound for CI jitter, but anything
  // that takes more than a few seconds means the function hung on settle.
  assert(elapsed < 1_000, `response took ${elapsed}ms; settlement hung the function`)
  assertEquals(f.calls.filter((call) => call.name === "boss_ai_settle").length, 2)
  assert(f.audits.includes("settlement_timeout"))
  assert(f.audits.includes("settlement_failed_reservation_retained"))
})

Deno.test("a timed-out settlement is safely retried with identical accounting arguments", async () => {
  const f = await fixture({ hangFirstSettlement: true })
  assertEquals((await f.handler(f.request(body))).status, 200)
  const attempts = f.calls.filter((call) => call.name === "boss_ai_settle")
  assertEquals(attempts.length, 2)
  assertEquals(attempts[0].params, attempts[1].params)
  assert(!f.audits.includes("settlement_failed"))
})

Deno.test("stream cleanup and upstream-failure settlement are both bounded", async () => {
  for (const failure of [false, true]) {
    const f = await fixture({
      hangSettlement: true,
      failFetch: failure,
      stream:
        'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5}}\n\ndata: [DONE]\n\n',
    })
    const start = Date.now()
    const response = await f.handler(f.request({ ...body, stream: true }))
    const output = await response.text()
    assert(elapsedSince(start) < 1_000)
    assertEquals(response.status, failure ? 503 : 200)
    if (!failure) assert(output.includes("[DONE]"))
    assertEquals(f.calls.filter((call) => call.name === "boss_ai_settle").length, 2)
    assert(f.audits.includes("settlement_failed_reservation_retained"))
  }
})

function elapsedSince(start: number): number {
  return Date.now() - start
}

Deno.test("eligibility RPC failures fail closed without minting or dispatching", async () => {
  const f = await fixture({ eligibilityError: true })
  const response = await f.handler(
    new Request("https://api.example/boss-ai/auth/token", {
      method: "POST",
      headers: { Authorization: "Bearer boss-session" },
    }),
  )
  assertEquals(response.status, 503)
  assertEquals((await response.json()).access_token, undefined)
  assertEquals(f.requests.length, 0)
})

const settled = (f: { calls: { name: string; params: Obj }[] }) =>
  f.calls.filter((call) => call.name === "boss_ai_settle").map((call) => call.params.p_tokens)
const wav = (seconds: number) => riff({ seconds })
const stt = { model: "boss-test", input_audio: { data: wav(1), format: "wav" }, language: "en" }
const tts = { model: "boss-test", input: "x".repeat(29) }

Deno.test("transcription forwards audio undecoded and settles ceil(upstream seconds)", async () => {
  const f = await fixture({ kind: "transcription" })
  const response = await f.handler(f.request(stt, f.token, "audio/transcriptions"))
  assertEquals(response.status, 200)
  assertEquals(await response.json(), {
    text: "hello there",
    usage: { seconds: 3.4, billed_seconds: 4 },
  })
  assertEquals(f.requests[0].url, "https://upstream.example/v1/audio/transcriptions")
  assertEquals(await f.requests[0].json(), {
    model: "private-model",
    input_audio: stt.input_audio,
    language: "en",
  })
  assertEquals(settled(f), [4])
})

Deno.test("transcription without upstream usage estimates a WAV and otherwise keeps the reservation", async () => {
  const wavOnly = await fixture({ kind: "transcription", payload: { text: "a" } })
  const estimated = await wavOnly.handler(
    wavOnly.request(stt, wavOnly.token, "audio/transcriptions"),
  )
  assertEquals((await estimated.json()).usage, { seconds: 1, billed_seconds: 1 })
  assertEquals(settled(wavOnly), [1])
  const mp3 = await fixture({ kind: "transcription", payload: { text: "a" } })
  const kept = await mp3.handler(
    mp3.request(
      { ...stt, input_audio: { data: "AAAA", format: "mp3" } },
      mp3.token,
      "audio/transcriptions",
    ),
  )
  assertEquals((await kept.json()).usage, { seconds: null, billed_seconds: 120 })
  assertEquals(settled(mp3), [null])
  assert(mp3.audits.includes("usage_unknown_reservation_retained"))
})

Deno.test("transcription rejects malformed audio before admission", async () => {
  for (
    const input_audio of [
      { data: "AAAA", format: "aiff" },
      { data: "not base64!", format: "wav" },
      { data: "AAA", format: "wav" },
      { data: "", format: "wav" },
      { data: "AAAA", format: "wav", extra: 1 },
      "AAAA",
    ]
  ) {
    const f = await fixture({ kind: "transcription" })
    const response = await f.handler(
      f.request({ ...stt, input_audio }, f.token, "audio/transcriptions"),
    )
    assertEquals(response.status, 400)
    assertEquals(f.calls.filter((call) => call.name === "boss_ai_reserve").length, 0)
    assertEquals(f.requests.length, 0)
  }
})

Deno.test("speech streams mp3 through, settles chars/14 up front and sets the voice", async () => {
  const f = await fixture({ kind: "speech", voice: "sarah" })
  const response = await f.handler(f.request(tts, f.token, "audio/speech"))
  assertEquals(response.status, 200)
  assertEquals(response.headers.get("Content-Type"), "audio/mpeg")
  assertEquals(response.headers.get("X-Billed-Seconds"), "3")
  assertEquals(new Uint8Array(await response.arrayBuffer()), new Uint8Array([1, 2, 3]))
  assertEquals(f.requests[0].url, "https://upstream.example/v1/audio/speech")
  assertEquals(await f.requests[0].json(), {
    model: "private-model",
    input: tts.input,
    response_format: "mp3",
    voice: "sarah",
  })
  assertEquals(settled(f), [3])
  const override = await fixture({ kind: "speech", voice: "sarah" })
  await override.handler(
    override.request({ ...tts, voice: "adam" }, override.token, "audio/speech"),
  )
  assertEquals((await override.requests[0].json()).voice, "adam")
  const none = await fixture({ kind: "speech" })
  await none.handler(none.request({ ...tts, input: "hi" }, none.token, "audio/speech"))
  assertEquals((await none.requests[0].json()).voice, undefined)
  assertEquals(settled(none), [1])
})

Deno.test("speech validates input length and caps streamed output", async () => {
  for (const input of ["", "x".repeat(2001), 5]) {
    const f = await fixture({ kind: "speech" })
    const response = await f.handler(f.request({ ...tts, input }, f.token, "audio/speech"))
    assertEquals(response.status, 400)
    assertEquals((await response.json()).error.code, "invalid_input")
    assertEquals(f.requests.length, 0)
  }
  const chunk = new Uint8Array(1024 * 1024)
  let sent = 0
  const f = await fixture({
    kind: "speech",
    audio: new ReadableStream<Uint8Array>({
      pull(controller) {
        sent++
        controller.enqueue(chunk)
      },
    }),
  })
  const response = await f.handler(f.request(tts, f.token, "audio/speech"))
  let received = 0
  let failed = false
  try {
    for await (const part of response.body!) received += part.length
  } catch {
    failed = true
  }
  assert(failed)
  assert(received <= 16 * 1024 * 1024 && sent <= 18)
})

Deno.test("audio upstream failure refunds and kinds are enforced per route", async () => {
  const failing = await fixture({ kind: "speech", upstreamStatus: 400 })
  assertEquals(
    (await failing.handler(failing.request(tts, failing.token, "audio/speech"))).status,
    502,
  )
  assertEquals(settled(failing), [0])
  const chatOnSpeech = await fixture({ kind: "speech" })
  const wrong = await chatOnSpeech.handler(chatOnSpeech.request(body))
  assertEquals(wrong.status, 400)
  assertEquals((await wrong.json()).error.code, "wrong_model_kind")
  assertEquals(chatOnSpeech.calls.map((call) => call.name), ["boss_ai_lookup"])
  const speechOnChat = await fixture()
  assertEquals(
    (await (await speechOnChat.handler(
      speechOnChat.request(tts, speechOnChat.token, "audio/speech"),
    ))
      .json()).error.code,
    "wrong_model_kind",
  )
  const sttOnSpeech = await fixture({ kind: "speech" })
  assertEquals(
    (await sttOnSpeech.handler(sttOnSpeech.request(stt, sttOnSpeech.token, "audio/transcriptions")))
      .status,
    400,
  )
  assertEquals(sttOnSpeech.requests.length, 0)
})

Deno.test("allowance_exceeded carries the pool usage", async () => {
  const usage = {
    month: { limit: 3600, used: 3600, remaining: 0, resets_at: "2026-11-01T00:00:00Z" },
  }
  const f = await fixture({ kind: "speech", deny: "allowance_exceeded", reserveUsage: usage })
  const response = await f.handler(f.request(tts, f.token, "audio/speech"))
  assertEquals(response.status, 429)
  const error = (await response.json()).error
  assertEquals(error.code, "allowance_exceeded")
  assertEquals(error.usage, usage)
  assertEquals(f.requests.length, 0)
})

const pool = {
  day: { limit: 3600, used: 100, remaining: 3500, resets_at: "2026-10-16T00:00:00Z" },
  week: { limit: 3600, used: 100, remaining: 3500, resets_at: "2026-10-19T00:00:00Z" },
  month: { limit: 3600, used: 3000, remaining: 600, resets_at: "2026-11-01T00:00:00Z" },
}

Deno.test("transcription and speech report the smallest remaining period of the pool", async () => {
  const text = await fixture({ kind: "transcription", poolUsage: pool })
  const transcribed = await text.handler(text.request(stt, text.token, "audio/transcriptions"))
  assertEquals((await transcribed.json()).usage, {
    seconds: 3.4,
    billed_seconds: 4,
    remaining_seconds: 600,
    resets_at: "2026-11-01T00:00:00Z",
  })
  const usage = text.calls.filter((call) => call.name === "boss_ai_usage")
  assertEquals(usage.map((call) => call.params), [{
    p_user_id: "00000000-0000-0000-0000-000000000001",
    p_model_id: "boss-test",
  }])
  assert(
    text.calls.findIndex((c) => c.name === "boss_ai_usage") >
      text.calls.findIndex((c) => c.name === "boss_ai_settle"),
  )
  const speech = await fixture({ kind: "speech", poolUsage: pool })
  const spoken = await speech.handler(speech.request(tts, speech.token, "audio/speech"))
  assertEquals(spoken.headers.get("X-Remaining-Seconds"), "600")
  assertEquals(spoken.headers.get("X-Resets-At"), "2026-11-01T00:00:00Z")
  await spoken.arrayBuffer()
})

Deno.test("audio responses omit quota when the usage read fails or is unreadable", async () => {
  for (const options of [{ failUsage: true }, { poolUsage: null }, { poolUsage: { day: 5 } }]) {
    const text = await fixture({ kind: "transcription", ...options })
    const transcribed = await text.handler(text.request(stt, text.token, "audio/transcriptions"))
    assertEquals(transcribed.status, 200)
    assertEquals((await transcribed.json()).usage, { seconds: 3.4, billed_seconds: 4 })
    const speech = await fixture({ kind: "speech", ...options })
    const spoken = await speech.handler(speech.request(tts, speech.token, "audio/speech"))
    assertEquals(spoken.status, 200)
    assertEquals(spoken.headers.get("X-Remaining-Seconds"), null)
    assertEquals(spoken.headers.get("X-Resets-At"), null)
    await spoken.arrayBuffer()
  }
})

Deno.test("smallestRemaining reports the latest reset among periods tied at the minimum", () => {
  const usage = {
    day: { limit: 3600, used: 9, remaining: 3591, resets_at: "2026-10-16T00:00:00+00:00" },
    week: { limit: 3600, used: 9, remaining: 3591, resets_at: "2026-10-19T00:00:00+00:00" },
    month: { limit: 3600, used: 9, remaining: 3591, resets_at: "2026-11-01T00:00:00+00:00" },
  }
  assertEquals(smallestRemaining(usage), {
    remaining_seconds: 3591,
    resets_at: "2026-11-01T00:00:00+00:00",
  })
  assertEquals(
    smallestRemaining({ ...usage, day: { ...usage.day, remaining: 10 } })?.resets_at,
    "2026-10-16T00:00:00+00:00",
  )
})

function riff(
  options: {
    format?: number
    channels?: number
    sampleRate?: number
    byteRate?: number
    bits?: number
    extensible?: boolean
    before?: number
    declared?: number
    seconds?: number
  } = {},
) {
  const channels = options.channels ?? 1
  const bits = options.bits ?? 16
  const sampleRate = options.sampleRate ?? 16_000
  const align = channels * bits / 8
  const fmtSize = options.extensible ? 40 : 16
  const audio = 32_000 * (options.seconds ?? 1)
  const bytes = new Uint8Array(
    12 + 8 + fmtSize + (options.before ? 8 + options.before + (options.before & 1) : 0) + 8 + audio,
  )
  const view = new DataView(bytes.buffer)
  const tag = (at: number, text: string) => bytes.set(new TextEncoder().encode(text), at)
  tag(0, "RIFF")
  tag(8, "WAVE")
  tag(12, "fmt ")
  view.setUint32(16, fmtSize, true)
  view.setUint16(20, options.extensible ? 0xFFFE : options.format ?? 1, true)
  view.setUint16(22, channels, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, options.byteRate ?? sampleRate * align, true)
  view.setUint16(32, align, true)
  view.setUint16(34, bits, true)
  if (options.extensible) {
    view.setUint16(36, 22, true)
    view.setUint16(44, 1, true) // PCM sub-format GUID prefix
  }
  let at = 20 + fmtSize
  if (options.before) {
    tag(at, "LIST")
    view.setUint32(at + 4, options.before, true)
    at += 8 + options.before + (options.before & 1)
  }
  tag(at, "data")
  view.setUint32(at + 4, options.declared ?? audio, true)
  let binary = ""
  for (let i = 0; i < bytes.length; i += 8192) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 8192))
  }
  return btoa(binary)
}

Deno.test("wavSeconds parses chunks, requires consistent PCM, and handles broken declared sizes", () => {
  assertEquals(wavSeconds(riff()), 1)
  assertEquals(wavSeconds(riff({ seconds: 3 })), 3)
  assertEquals(wavSeconds(riff({ before: 26 })), 1) // LIST chunk before data
  assertEquals(wavSeconds(riff({ before: 25 })), 1) // odd chunk is padded
  assertEquals(wavSeconds(riff({ extensible: true })), 1)
  assertEquals(wavSeconds(riff({ declared: 0xFFFFFFFF })), 1) // real length, not the header's
  assertEquals(wavSeconds(riff({ declared: 0 })), 1)
  // Hostile or inconsistent headers fall back to keeping the reservation.
  assertEquals(wavSeconds(riff({ byteRate: 4_000_000_000 })), null)
  assertEquals(wavSeconds(riff({ byteRate: 0 })), null)
  assertEquals(wavSeconds(riff({ byteRate: 32_001 })), null)
  assertEquals(wavSeconds(riff({ sampleRate: 1_000, byteRate: 2_000 })), 16)
  assertEquals(wavSeconds(riff({ sampleRate: 8_000, byteRate: 1_000, bits: 8 })), null)
  assertEquals(wavSeconds(riff({ sampleRate: 1_000_000, bits: 32, channels: 2 })), null)
  assertEquals(wavSeconds(riff({ format: 3 })), null) // float
  assertEquals(wavSeconds(riff({ format: 0x55 })), null) // mp3-in-wav
  assertEquals(wavSeconds(riff({ before: 100_000 })), 1) // metadata before data is not speech
  assertEquals(wavSeconds(btoa("RIFF\0\0\0\0WAVE")), null)
  const mp3 = new Uint8Array(4_096)
  mp3.set(new TextEncoder().encode("ID3"), 0)
  mp3.set([0xFF, 0xFB, 0x90, 0x00], 3)
  assertEquals(wavSeconds(btoa(String.fromCharCode(...mp3))), null)
})

Deno.test("a hostile WAV header cannot shrink the bill", async () => {
  const f = await fixture({ kind: "transcription", payload: { text: "a" } })
  const response = await f.handler(
    f.request(
      { ...stt, input_audio: { data: riff({ byteRate: 4_000_000_000 }), format: "wav" } },
      f.token,
      "audio/transcriptions",
    ),
  )
  assertEquals((await response.json()).usage, { seconds: null, billed_seconds: 120 })
  assertEquals(settled(f), [null])
})

Deno.test("transcription bills real seconds beyond the reservation and ignores bad usage", async () => {
  const over = await fixture({
    kind: "transcription",
    payload: { text: "a", usage: { seconds: 500.2 } },
  })
  const response = await over.handler(over.request(stt, over.token, "audio/transcriptions"))
  assertEquals((await response.json()).usage, { seconds: 500.2, billed_seconds: 120 })
  assertEquals(settled(over), [501])
  assert(over.audits.includes("usage_exceeds_reservation"))
  assert(!over.audits.includes("usage_exceeds_configured_context"))
  for (const seconds of [-1, "3", null, {}, 1e999]) {
    const f = await fixture({ kind: "transcription", payload: { text: "a", usage: { seconds } } })
    const bad = await f.handler(f.request(stt, f.token, "audio/transcriptions"))
    // Treated as missing: the one-second WAV estimate applies.
    assertEquals((await bad.json()).usage, { seconds: 1, billed_seconds: 1 })
    assertEquals(settled(f), [1])
  }
  const mp3 = await fixture({
    kind: "transcription",
    payload: { text: "a", usage: { seconds: -1 } },
  })
  const kept = await mp3.handler(
    mp3.request(
      { ...stt, input_audio: { data: "AAAA", format: "mp3" } },
      mp3.token,
      "audio/transcriptions",
    ),
  )
  assertEquals((await kept.json()).usage, { seconds: null, billed_seconds: 120 })
  assertEquals(settled(mp3), [null])
})

Deno.test("chat keeps its own exceeds-context audit event", async () => {
  const f = await fixture({
    payload: {
      choices: [{ message: { role: "assistant", content: "x" }, finish_reason: "stop", index: 0 }],
      usage: { prompt_tokens: 99_998, completion_tokens: 1 },
    },
  })
  assertEquals((await f.handler(f.request(body))).status, 200)
  assert(f.audits.includes("usage_exceeds_configured_context"))
  assert(!f.audits.includes("usage_exceeds_reservation"))
})

Deno.test("speech input beyond max_output_tokens x 14 is rejected, before and after reserve", async () => {
  const limit = 120 * 14
  const ok = await fixture({ kind: "speech" })
  const accepted = await ok.handler(
    ok.request({ ...tts, input: "x".repeat(limit) }, ok.token, "audio/speech"),
  )
  assertEquals(accepted.headers.get("X-Billed-Seconds"), "120")
  await accepted.arrayBuffer()
  assertEquals(settled(ok), [120])
  const early = await fixture({ kind: "speech" })
  const tooLong = await early.handler(
    early.request({ ...tts, input: "x".repeat(limit + 1) }, early.token, "audio/speech"),
  )
  assertEquals(tooLong.status, 400)
  const error = (await tooLong.json()).error
  assertEquals(error.code, "invalid_input")
  assertEquals(error.message, "Speech input is too long for one request.")
  assertEquals(early.calls.filter((call) => call.name === "boss_ai_reserve").length, 0)
  // The model row shrank between lookup and reserve: refused after admission and refunded.
  const late = await fixture({ kind: "speech", reserveMaxOutput: 10 })
  const refused = await late.handler(
    late.request({ ...tts, input: "x".repeat(141) }, late.token, "audio/speech"),
  )
  assertEquals(refused.status, 400)
  assertEquals((await refused.json()).error.message, "Speech input is too long for one request.")
  assertEquals(late.requests.length, 0)
  assertEquals(settled(late), [0])
})

const spyCleanup = (request: Request) => {
  const counts = { removed: 0 }
  const remove = request.signal.removeEventListener.bind(request.signal)
  request.signal.removeEventListener = ((...args: Parameters<typeof remove>) => {
    counts.removed++
    return remove(...args)
  }) as typeof remove
  return counts
}

Deno.test("speech cancels the upstream body and cleans up once when the response cannot be built", async () => {
  let cancelled = 0
  const audio = new ReadableStream<Uint8Array>({
    pull(controller) {
      controller.enqueue(new Uint8Array([1]))
    },
    cancel() {
      cancelled++
    },
  })
  const f = await fixture({ kind: "speech", audio })
  const request = f.request(tts, f.token, "audio/speech")
  const counts = spyCleanup(request)
  const NativeResponse = Response
  globalThis.Response = class extends NativeResponse {
    constructor(bodyInit?: BodyInit | null, init?: ResponseInit) {
      if ((init?.headers as Record<string, string> | undefined)?.["X-Billed-Seconds"]) {
        throw new TypeError("cannot build response")
      }
      super(bodyInit, init)
    }
  } as typeof Response
  let response: Response
  try {
    response = await f.handler(request)
  } finally {
    globalThis.Response = NativeResponse
  }
  assertEquals(response.status, 503)
  assertEquals(cancelled, 1)
  assertEquals(counts.removed, 1)
  // Settlement happened before the failure, so nothing is left to refund.
  assertEquals(settled(f), [3])
})

Deno.test("speech still streams and cleans up once when settlement fails", async () => {
  const f = await fixture({ kind: "speech", failAllSettlements: true })
  const request = f.request(tts, f.token, "audio/speech")
  const counts = spyCleanup(request)
  const response = await f.handler(request)
  assertEquals(response.status, 200)
  assertEquals(counts.removed, 0) // the stream owns cleanup until it ends
  await response.arrayBuffer()
  assertEquals(counts.removed, 1)
  assert(f.audits.includes("settlement_failed"))
})

Deno.test("speech omits quota headers when resets_at is not a date", async () => {
  const bad = {
    month: { limit: 1, used: 0, remaining: 1, resets_at: "tomorrow\r\nX-Evil: 1" },
  }
  const f = await fixture({ kind: "speech", poolUsage: bad })
  const response = await f.handler(f.request(tts, f.token, "audio/speech"))
  assertEquals(response.status, 200)
  assertEquals(response.headers.get("X-Remaining-Seconds"), null)
  assertEquals(response.headers.get("X-Resets-At"), null)
  await response.arrayBuffer()
})

Deno.test("prototype keys are not routes and extra cannot override the error envelope", async () => {
  const f = await fixture()
  for (const path of ["__proto__", "constructor", "toString", "hasOwnProperty"]) {
    const response = await f.handler(f.request(body, f.token, path))
    assertEquals(response.status, 404)
    assertEquals((await response.json()).error.code, "not_found")
  }
  assertEquals(f.calls.length, 0)
  const exceeded = await fixture({
    deny: "allowance_exceeded",
    reserveUsage: { code: "x", message: "y" },
  })
  const error = (await (await exceeded.handler(exceeded.request(body))).json()).error
  assertEquals(error.code, "allowance_exceeded")
  assertEquals(error.usage, { code: "x", message: "y" })
})

Deno.test("transcription accepts OpenAI's multipart upload and forwards it as input_audio", async () => {
  const f = await fixture({ kind: "transcription" })
  const bytes = Uint8Array.from(atob(stt.input_audio.data), (c) => c.charCodeAt(0))
  const form = new FormData()
  form.append("file", new File([bytes], "file.wav", { type: "audio/wav" }))
  form.append("model", "boss-test")
  form.append("language", "en")
  form.append("response_format", "json")
  const response = await f.handler(
    new Request("https://boss-ai.example/functions/v1/boss-ai/v1/audio/transcriptions", {
      method: "POST",
      headers: { Authorization: `Bearer ${f.token}` },
      body: form,
    }),
  )
  assertEquals(response.status, 200)
  assertEquals(await f.requests[0].json(), {
    model: "private-model",
    input_audio: stt.input_audio,
    language: "en",
  })
})

Deno.test("speech accepts and ignores OpenAI's optional fields; only mp3 output", async () => {
  const f = await fixture({ kind: "speech" })
  const ok = await f.handler(
    f.request(
      { ...tts, response_format: "mp3", speed: 1, stream_format: "audio", instructions: "calm" },
      f.token,
      "audio/speech",
    ),
  )
  assertEquals(ok.status, 200)
  await ok.body?.cancel()
  const sent = await f.requests[0].json()
  assertEquals(sent.response_format, "mp3")
  assertEquals("speed" in sent || "stream_format" in sent || "instructions" in sent, false)
  const g = await fixture({ kind: "speech" })
  assertEquals(
    (await g.handler(g.request({ ...tts, response_format: "wav" }, g.token, "audio/speech")))
      .status,
    400,
  )
})

Deno.test("multipart actual byte cap cancels chunked and dishonest uploads before parsing or admission", async () => {
  for (const contentLength of [undefined, "1"]) {
    const f = await fixture({ kind: "transcription" })
    let pulls = 0
    let cancelled = false
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulls++
        controller.enqueue(new Uint8Array(1024 * 1024))
      },
      cancel() {
        cancelled = true
      },
    })
    const headers: Record<string, string> = {
      Authorization: `Bearer ${f.token}`,
      "Content-Type": "multipart/form-data; boundary=x",
    }
    if (contentLength) headers["Content-Length"] = contentLength
    const response = await f.handler(
      new Request("https://boss-ai.example/v1/audio/transcriptions", {
        method: "POST",
        headers,
        body: stream,
      }),
    )
    assertEquals(response.status, 413)
    assert(cancelled)
    assert(pulls <= 7)
    assertEquals(f.calls.length, 0)
  }
})

Deno.test("multipart MIME names cannot inherit object properties", async () => {
  const f = await fixture({ kind: "transcription" })
  const form = new FormData()
  form.append("file", new File(["x"], "unknown", { type: "constructor" }))
  form.append("model", "boss-test")
  const response = await f.handler(
    new Request("https://boss-ai.example/v1/audio/transcriptions", {
      method: "POST",
      headers: { Authorization: `Bearer ${f.token}` },
      body: form,
    }),
  )
  assertEquals(response.status, 400)
  assertEquals(f.calls.length, 0)
})

Deno.test("speech rejects unsupported speed and SSE before admission", async () => {
  for (const option of [{ speed: 2 }, { stream_format: "sse" }]) {
    const f = await fixture({ kind: "speech" })
    assertEquals(
      (await f.handler(f.request({ ...tts, ...option }, f.token, "audio/speech"))).status,
      400,
    )
    assertEquals(f.calls.filter((c) => c.name === "boss_ai_reserve").length, 0)
  }
})

Deno.test("WAV metadata after audio is not billed as speech", () => {
  const audio = atob(riff())
  const tail = new Uint8Array(8 + 32000)
  tail.set(new TextEncoder().encode("LIST"))
  new DataView(tail.buffer).setUint32(4, 32000, true)
  assertEquals(wavSeconds(btoa(audio + String.fromCharCode(...tail))), 1)
})

Deno.test("/v1/provider accepts a loopback http base only with BOSS_AI_ALLOW_LOCAL_AGENT=1", async () => {
  const token = (await mintToken("00000000-0000-0000-0000-000000000001", signingKey(secret)))
    .access_token
  const provider = async (env: Record<string, string>) => {
    const handler = createHandler({
      sessionUser: () => Promise.resolve(null),
      secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : env[name],
      rpc: () => Promise.resolve(null),
      fetch: () => {
        throw new Error("Must not call upstream")
      },
    })
    return await handler(
      new Request("https://api.example/functions/v1/boss-ai/v1/provider", {
        headers: { Authorization: `Bearer ${token}` },
      }),
    )
  }
  const local = { BOSS_AI_PUBLIC_URL: "http://127.0.0.1:54321/functions/v1/boss-ai" }
  const ok = await provider({ ...local, BOSS_AI_ALLOW_LOCAL_AGENT: "1" })
  assertEquals(ok.status, 200)
  assertEquals((await ok.json()).baseUrl, "http://127.0.0.1:54321/functions/v1/boss-ai/v1")
  const localhost = await provider({
    BOSS_AI_PUBLIC_URL: "http://localhost:8000/functions/v1/boss-ai",
    BOSS_AI_ALLOW_LOCAL_AGENT: "1",
  })
  assertEquals((await localhost.json()).baseUrl, "http://localhost:8000/functions/v1/boss-ai/v1")
  // SUPABASE_URL is accepted the same way.
  const viaSupabase = await provider({
    SUPABASE_URL: "http://127.0.0.1:54321",
    BOSS_AI_ALLOW_LOCAL_AGENT: "1",
  })
  assertEquals((await viaSupabase.json()).baseUrl, "http://127.0.0.1:54321/functions/v1/boss-ai/v1")
  // Without the flag, or for a non-loopback http host, production behaviour is unchanged.
  assertEquals((await provider(local)).status, 503)
  for (const url of ["http://example.com/functions/v1/boss-ai", "http://10.0.0.5/x"]) {
    assertEquals(
      (await provider({ BOSS_AI_PUBLIC_URL: url, BOSS_AI_ALLOW_LOCAL_AGENT: "1" })).status,
      503,
    )
  }
  const prod = await provider({
    BOSS_AI_PUBLIC_URL: "https://api.risaboss.com/functions/v1/boss-ai",
  })
  assertEquals((await prod.json()).baseUrl, "https://api.risaboss.com/functions/v1/boss-ai/v1")
})
