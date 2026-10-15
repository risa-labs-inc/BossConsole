import { assert, assertEquals } from "@std/assert"
import { createHandler } from "../app.ts"
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
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : "upstream-secret",
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
              max_output_tokens: options.kind && options.kind !== "chat" ? 120 : 512,
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

Deno.test("truncated streams report failure and retain reserved usage", async () => {
  const f = await fixture({ stream: 'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n' })
  const response = await f.handler(f.request({ ...body, stream: true }))
  const output = await response.text()
  assert(output.includes("stream_failed"))
  assert(!output.includes("[DONE]"))
  assertEquals(f.calls.at(-1)?.params.p_tokens, null)
})

Deno.test("a stalled SSE body times out and settles without hanging the client", async () => {
  const f = await fixture({ stallStream: true })
  const response = await f.handler(f.request({ ...body, stream: true }))
  const output = await response.text()
  assert(output.includes("partial"))
  assert(output.includes("stream_failed"))
  assert(!output.includes("[DONE]"))
  assertEquals(f.calls.filter((call) => call.name === "boss_ai_settle").length, 1)
  assertEquals(f.calls.at(-1)?.params.p_tokens, null)
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
    assertEquals(f.calls.at(-1)?.params.p_tokens, status < 500 && status !== 408 ? 0 : null)
    assert(f.audits.includes(`upstream_status_${status}`))
  }
})

Deno.test("failed dispatch and timeout are observable unknown outcomes not inferred refunds", async () => {
  for (const options of [{ failFetch: true }, { hang: true }]) {
    const f = await fixture(options)
    const response = await f.handler(f.request(body))
    assertEquals(response.status, 503)
    assertEquals(f.calls.at(-1)?.params.p_tokens, null)
    assert(f.audits.includes("usage_unknown_reservation_retained"))
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

Deno.test("stream cancellation retains usage and does not attempt to write to a closed client", async () => {
  const f = await fixture({ stream: 'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n' })
  const response = await f.handler(f.request({ ...body, stream: true }))
  const reader = response.body!.getReader()
  await reader.read()
  await reader.cancel()
  assertEquals(f.calls.filter((call) => call.name === "boss_ai_settle").length, 1)
  assertEquals(f.calls.at(-1)?.params.p_tokens, null)
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
    secret: () => secret,
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
const wav = (seconds: number) => {
  const bytes = new Uint8Array(44 + 32_000 * seconds)
  const view = new DataView(bytes.buffer)
  bytes.set(new TextEncoder().encode("RIFF"), 0)
  bytes.set(new TextEncoder().encode("WAVE"), 8)
  view.setUint32(28, 32_000, true)
  return btoa(String.fromCharCode(...bytes))
}
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
