import { assert, assertEquals } from "@std/assert"
import { createHandler, trips } from "../app.ts"
import { mintToken, signingKey } from "../auth.ts"
import { Connection, Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
const user = "00000000-0000-0000-0000-000000000001"
const PRIVATE = "super private prompt content"
const KEY = "sk-upstream-key-value"

const anthropic: Connection = {
  id: "anthropic",
  base_url: "https://api.anthropic.com/v1",
  api_type: "anthropic_messages",
  api_key_secret: "BOSS_AI_ANTHROPIC",
}
const openrouter: Connection = {
  id: "or",
  base_url: "https://openrouter.ai/api/v1",
  api_type: "openai_chat",
  api_key_secret: "BOSS_AI_OR",
}
const fallbacks = [
  {
    connection_id: "or",
    upstream_model: "anthropic/claude-opus-5.5",
    provider_routing: { order: ["anthropic"], allow_fallbacks: true },
    connection: openrouter,
  },
  { connection_id: "or", upstream_model: "anthropic/claude-haiku-5.5", connection: openrouter },
]

const message = (usage: Obj = { input_tokens: 5, output_tokens: 3 }) =>
  new Response(
    JSON.stringify({
      type: "message",
      content: [{ type: "text", text: "from anthropic" }],
      stop_reason: "end_turn",
      usage,
    }),
    { status: 200 },
  )
const chat = (usage: Obj = { prompt_tokens: 20, completion_tokens: 4 }) =>
  new Response(
    JSON.stringify({
      choices: [{ index: 0, message: { role: "assistant", content: "from openrouter" } }],
      usage,
    }),
    { status: 200 },
  )
const fail = (status: number, error: Obj) => new Response(JSON.stringify({ error }), { status })

type Responder = (url: string, init: RequestInit, n: number) => Response | Promise<Response>

// Prompt estimate for a request that never got an answer: request bytes / 3 of attempt `n`.
const promptEstimate = (f: { fetched: { body: Obj }[] }, n = f.fetched.length - 1) =>
  Math.ceil(JSON.stringify(f.fetched[n].body).length / 3)

async function fixture(
  responders: Responder[],
  options: {
    primary?: Connection
    fallbacks?: unknown[]
    now?: () => number
    attempt?: number
    model?: Obj
  } = {},
) {
  const calls: { name: string; params: Obj }[] = []
  const fetched: { url: string; headers: Headers; body: Obj }[] = []
  const audits: string[] = []
  const logs: Obj[] = []
  const token = (await mintToken(user, signingKey(secret))).access_token
  const handler = createHandler({
    audit: (event) => void audits.push(event),
    log: (entry) => void logs.push(entry),
    now: options.now,
    attemptTimeoutMs: options.attempt,
    upstreamTimeoutMs: 5_000,
    settlementTimeoutMs: 50,
    sessionUser: () => Promise.resolve(user),
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : KEY,
    rpc(name, params) {
      calls.push({ name, params })
      if (name === "boss_ai_reserve" || name === "boss_ai_lookup") {
        return Promise.resolve({
          model: {
            id: "optimist",
            upstream_model: "claude-opus-5-5",
            connection_id: "anthropic",
            kind: "chat",
            capabilities: ["text", "tools"],
            context_length: 100_000,
            max_output_tokens: 4096,
            provider_routing: null,
            fallbacks: [],
            ...options.model,
          },
          connection: options.primary ?? anthropic,
          fallbacks: options.fallbacks ?? fallbacks,
        })
      }
      return Promise.resolve(null)
    },
    async fetch(url, init) {
      const n = fetched.length
      fetched.push({
        url: String(url),
        headers: new Headers(init!.headers),
        body: JSON.parse(String(init!.body)),
      })
      return await responders[Math.min(n, responders.length - 1)](String(url), init!, n)
    },
  })
  const send = (extra: Obj = {}) =>
    handler(
      new Request("https://x.example/functions/v1/boss-ai/v1/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: "optimist",
          messages: [{ role: "user", content: PRIVATE }],
          ...extra,
        }),
      }),
    )
  const settled = () =>
    calls.filter((c) => c.name === "boss_ai_settle").map((c) => c.params.p_tokens)
  const settles = () => calls.filter((c) => c.name === "boss_ai_settle").map((c) => c.params)
  const reserves = () => calls.filter((c) => c.name === "boss_ai_reserve").length
  return { send, fetched, audits, logs, settled, settles, reserves }
}

Deno.test("native route: /messages with x-api-key, translated body, settlement on measured tokens", async () => {
  const f = await fixture([() =>
    message({
      input_tokens: 5,
      cache_read_input_tokens: 1000,
      cache_creation_input_tokens: 20,
      output_tokens: 3,
    })])
  const response = await f.send()
  assertEquals(response.status, 200)
  const out = await response.json()
  assertEquals(out.choices[0].message.content, "from anthropic")
  assertEquals(out.usage.total_tokens, 1028)
  assertEquals(out.usage.prompt_tokens_details, { cached_tokens: 1000, cache_write_tokens: 20 })
  assertEquals(f.fetched.length, 1)
  assertEquals(f.fetched[0].url, "https://api.anthropic.com/v1/messages")
  assertEquals(f.fetched[0].headers.get("x-api-key"), KEY)
  assertEquals(f.fetched[0].headers.get("anthropic-version"), "2023-06-01")
  assertEquals(f.fetched[0].headers.get("authorization"), null)
  assertEquals(f.fetched[0].body.model, "claude-opus-5-5")
  assertEquals(f.fetched[0].body.max_tokens, 2000)
  assertEquals(f.settled(), [1028])
  assert(!f.audits.includes("upstream_fallback"))
})

Deno.test("native route: SSE is translated, usage chunk and [DONE] follow, settlement is measured", async () => {
  const frames = [
    { type: "message_start", message: { usage: { input_tokens: 9, output_tokens: 1 } } },
    { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
    { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "Hel" } },
    { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "lo" } },
    { type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 6 } },
    { type: "message_stop" },
  ].map((e) => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join("")
  const f = await fixture([() => new Response(frames, { status: 200 })])
  const response = await f.send({ stream: true })
  const text = await response.text()
  assert(text.endsWith("data: [DONE]\n\n"))
  const chunks = text.split("\n\n").filter((x) => x.startsWith("data: {")).map((x) =>
    JSON.parse(x.slice(6))
  )
  assertEquals(
    chunks.flatMap((c) => c.choices).map((c: Obj) => (c.delta as Obj).content).filter(Boolean),
    ["Hel", "lo"],
  )
  assertEquals(chunks.at(-1).usage.total_tokens, 15)
  assertEquals(f.fetched[0].body.stream, true)
  assertEquals(f.settled(), [15])
})

Deno.test("fallback: any non-2xx or a network error moves to the next route, one reservation", async () => {
  const cases: [string, Responder][] = [
    ["400 usage limit", () =>
      fail(400, {
        type: "invalid_request_error",
        message: "You have reached your specified workspace API usage limits.",
      })],
    ["400 anything", () => fail(400, { type: "invalid_request_error", message: "bad schema" })],
    ["429", () => fail(429, { type: "rate_limit_error", message: "slow down" })],
    ["500", () => fail(500, { type: "api_error", message: "oops" })],
    ["529", () => new Response("overloaded", { status: 529 })],
    ["404", () => fail(404, { type: "not_found_error", message: "model" })],
    ["network", () => {
      throw new TypeError("connection reset")
    }],
  ]
  for (const [name, primary] of cases) {
    const f = await fixture([primary, () => chat({ prompt_tokens: 30, completion_tokens: 5 })])
    const response = await f.send()
    assertEquals(response.status, 200, name)
    assertEquals((await response.json()).choices[0].message.content, "from openrouter", name)
    assertEquals(f.fetched.map((x) => x.url), [
      "https://api.anthropic.com/v1/messages",
      "https://openrouter.ai/api/v1/chat/completions",
    ], name)
    // The fallback is an OpenAI-format route: Bearer auth, its own model and operator routing.
    assertEquals(f.fetched[1].headers.get("authorization"), `Bearer ${KEY}`, name)
    assertEquals(f.fetched[1].body.model, "anthropic/claude-opus-5.5", name)
    assertEquals(f.fetched[1].body.provider, { order: ["anthropic"], allow_fallbacks: true }, name)
    assertEquals(f.reserves(), 1, name)
    // Settled once, with the tokens of the route that served.
    assertEquals(f.settled(), [35], name)
    assert(f.audits.includes("upstream_fallback"), name)
  }
})

Deno.test("fallback: walks every route in order and the final failure message is unchanged", async () => {
  const f = await fixture([
    () => fail(500, { message: "a" }),
    () => fail(429, { message: "b" }),
    () => fail(500, { message: "c" }),
  ])
  const response = await f.send()
  assertEquals(f.fetched.length, 3)
  assertEquals(f.fetched.map((x) => x.body.model), [
    "claude-opus-5-5",
    "anthropic/claude-opus-5.5",
    "anthropic/claude-haiku-5.5",
  ])
  assertEquals(response.status, 502)
  assertEquals((await response.json()).error, {
    code: "upstream_error",
    message: "The model is temporarily unavailable. Please try again.",
  })
  // An unknown outcome (5xx) is charged the prompt estimate, not the reservation.
  assertEquals(f.settled(), [promptEstimate(f)])
  assert(f.audits.includes("usage_unknown_estimate_charged"))
})

Deno.test("refunds: every attempt provably rejected refunds, any possible inference does not", async () => {
  const refund = await fixture([
    () => fail(400, { message: "no" }),
    () => fail(402, { message: "no" }),
    () => fail(429, { message: "no" }),
  ])
  assertEquals((await refund.send()).status, 503)
  assertEquals(refund.settled(), [0])
  // 500 first, refundable status last: the first attempt may have run, so no refund.
  const mixed = await fixture([
    () => fail(500, { message: "x" }),
    () => fail(400, { message: "no" }),
  ], {
    fallbacks: [fallbacks[0]],
  })
  await mixed.send()
  assertEquals(mixed.settled(), [promptEstimate(mixed, 0)])
  const net = await fixture([() => {
    throw new TypeError("boom")
  }], { fallbacks: [] })
  assertEquals((await net.send()).status, 503)
  assertEquals(net.settled(), [promptEstimate(net)])
})

// Deno: TypeError("fetch failed") caused by "... client error (Connect): tcp connect error:
// Connection refused (os error 61)" or "... dns error: failed to lookup address information".
const refused = () => {
  throw new TypeError("fetch failed", {
    cause: new Error(
      "error sending request: client error (Connect): tcp connect error: Connection refused (os error 61)",
    ),
  })
}
const noDns = () => {
  throw new TypeError("fetch failed", {
    cause: new Error(
      "error sending request: client error (Connect): dns error: failed to lookup address information",
    ),
  })
}

Deno.test("failed requests: estimate charged, a refused connection or DNS failure refunds", async () => {
  const down = await fixture([
    () => fail(500, { message: "a" }),
    () => fail(503, { message: "b" }),
    () => fail(500, { message: "c" }),
  ], { model: { usd_per_mtok_input: 4, usd_per_mtok_output: 20 } })
  assertEquals((await down.send()).status, 502)
  const estimate = promptEstimate(down)
  assert(estimate > 0 && estimate < 100_000)
  assertEquals(down.settled(), [estimate])
  const s = down.settles()[0]
  assertEquals([s.p_input, s.p_output], [estimate, 0])
  assert(Math.abs(Number(s.p_cost_usd) - estimate * 4 / 1e6) < 1e-12)
  assert(down.audits.includes("usage_unknown_estimate_charged"))
  assert(!down.audits.includes("usage_unknown_reservation_retained"))

  for (const kind of [refused, noDns]) {
    const f = await fixture([kind])
    assertEquals((await f.send()).status, 503)
    assertEquals(f.fetched.length, 3)
    assertEquals(f.settled(), [0])
  }
  // A reset or timeout may have reached the upstream.
  const reset = await fixture([() => {
    throw new TypeError("fetch failed", { cause: new Error("connection reset by peer") })
  }])
  await reset.send()
  assertEquals(reset.settled(), [promptEstimate(reset)])
  // First route refused, second answered 500: the 500 may have run.
  const mixed = await fixture([refused, () => fail(500, { message: "x" }), refused])
  await mixed.send()
  assertEquals(mixed.settled(), [promptEstimate(mixed, 1)])
  // 500 then refused: still unknown.
  const later = await fixture([() => fail(500, { message: "x" }), refused, refused])
  await later.send()
  assertEquals(later.settled(), [promptEstimate(later, 0)])
})

Deno.test("fallback: a response that never arrives times out per attempt and falls back", async () => {
  const hang: Responder = (_url, init) =>
    new Promise((_, reject) =>
      init.signal!.addEventListener("abort", () => reject(new Error("aborted")), { once: true })
    )
  const f = await fixture([hang, () => chat()], { attempt: 20 })
  const response = await f.send({ stream: true })
  assertEquals(response.status, 200)
  assertEquals(f.fetched.length, 2)
  assert(f.audits.includes("upstream_fallback"))
  assertEquals(f.logs[0].type, "timeout")
})

Deno.test("no fallback after the first byte: a stream that dies mid-way ends the request", async () => {
  const encoder = new TextEncoder()
  const first = encoder.encode(
    `data: ${
      JSON.stringify({
        type: "message_start",
        message: { usage: { input_tokens: 4, output_tokens: 1 } },
      })
    }\n\n` +
      `data: ${
        JSON.stringify({
          type: "content_block_delta",
          index: 0,
          delta: { type: "text_delta", text: "par" },
        })
      }\n\n`,
  )
  const broken = () => {
    let sent = false
    return new Response(
      new ReadableStream<Uint8Array>({
        pull(controller) {
          if (!sent) {
            sent = true
            controller.enqueue(first)
          } else controller.error(new Error("socket closed"))
        },
      }),
      { status: 200 },
    )
  }
  const f = await fixture([broken, () => chat()])
  const response = await f.send({ stream: true })
  assertEquals(response.status, 200)
  const text = await response.text()
  assert(text.includes("par"))
  assert(text.includes("stream_failed"))
  assertEquals(f.fetched.length, 1)
  assert(!f.audits.includes("upstream_fallback"))
  assert(f.audits.includes("stream_failed"))
})

Deno.test("circuit breaker: a limit-style failure skips the route for 60 s, then tries it again", async () => {
  let clock = 1_000_000
  const f = await fixture([
    () => fail(400, { type: "invalid_request_error", message: "usage limits reached" }),
    () => chat(),
    () => chat(),
    () => message(),
  ], { now: () => clock })
  await f.send()
  assertEquals(f.fetched.length, 2)
  clock += 30_000
  await f.send()
  // The primary is cooling: straight to the fallback.
  assertEquals(f.fetched.map((x) => x.body.model).slice(2), ["anthropic/claude-opus-5.5"])
  clock += 31_000
  await f.send()
  assertEquals(f.fetched.at(-1)!.url, "https://api.anthropic.com/v1/messages")
  assertEquals(f.fetched.length, 4)
})

Deno.test("circuit breaker: only predictive failures trip it; all routes cooling still tries them", async () => {
  assert(
    trips(401, "") && trips(402, "") && trips(403, "") && trips(429, "") && trips(500, "") &&
      trips(529, ""),
  )
  assert(trips(400, "You have reached your specified workspace API usage limits"))
  assert(trips(400, "Your credit balance is too low to access the API"))
  assert(trips(400, "monthly spend cap exceeded"))
  assert(!trips(400, "prompt is too long: 210000 tokens > 200000 maximum context limit"))
  assert(!trips(400, "messages.3: tool_use ids must be unique"))
  assert(!trips(404, "") && !trips(422, ""))
  // A plain 400 never cools the route.
  const plain = await fixture([() => fail(400, { message: "bad request" }), () => chat()])
  await plain.send()
  await plain.send()
  assertEquals(plain.fetched.map((x) => x.body.model).slice(0, 1), ["claude-opus-5-5"])
  assertEquals(plain.fetched.filter((x) => x.url.endsWith("/messages")).length, 2)
  // Everything cooling: all routes are attempted anyway.
  const all = await fixture([() => fail(429, { message: "x" })], { fallbacks: [fallbacks[0]] })
  await all.send()
  assertEquals(all.fetched.length, 2)
  await all.send()
  assertEquals(all.fetched.length, 4)
})

Deno.test("upstream errors log type and a bounded message, never request content or keys", async () => {
  const long = "x".repeat(2000)
  const f = await fixture([
    () =>
      new Response(
        JSON.stringify({
          type: "error",
          error: { type: "invalid_request_error", message: `${long} ${PRIVATE} ${KEY}` },
        }).padEnd(10_000, " "),
        { status: 400 },
      ),
    () => fail(402, { code: 402, message: "Insufficient credits" }),
    () => chat(),
  ])
  await f.send()
  assertEquals(f.logs.length, 2)
  assertEquals(f.logs[0], {
    event: "upstream_error",
    request_id: f.logs[0].request_id,
    route: "anthropic|claude-opus-5-5",
    status: 400,
    type: "invalid_request_error",
    message: "x".repeat(300),
  })
  assertEquals(f.logs[1].route, "or|anthropic/claude-opus-5.5")
  assertEquals(f.logs[1].status, 402)
  assertEquals(f.logs[1].type, "402")
  assertEquals(f.logs[1].message, "Insufficient credits")
  const all = JSON.stringify(f.logs)
  assert(!all.includes(PRIVATE) && !all.includes(KEY))
  // Non-JSON and oversized bodies degrade to empty fields.
  const odd = await fixture([
    () => new Response("<html>gateway</html>", { status: 502 }),
    () => chat(),
  ])
  await odd.send()
  assertEquals([odd.logs[0].status, odd.logs[0].type, odd.logs[0].message], [502, null, ""])
  // Network failures log the route without any error text.
  const net = await fixture([() => {
    throw new TypeError(`connect to ${KEY} failed`)
  }, () => chat()])
  await net.send()
  assertEquals(net.logs[0].type, "fetch_error")
  assert(!JSON.stringify(net.logs).includes(KEY))
})

Deno.test("cache_miss_large_prompt audits native and OpenRouter-anthropic routes only", async () => {
  const big = { input_tokens: 9000, output_tokens: 5 }
  const miss = await fixture([() => message(big)])
  await miss.send()
  assert(miss.audits.includes("cache_miss_large_prompt"))
  const read = await fixture([() => message({ ...big, cache_read_input_tokens: 8000 })])
  await read.send()
  assert(!read.audits.includes("cache_miss_large_prompt"))
  const wrote = await fixture([() => message({ ...big, cache_creation_input_tokens: 8000 })])
  await wrote.send()
  assert(!wrote.audits.includes("cache_miss_large_prompt"))
  const small = await fixture([() => message({ input_tokens: 8000, output_tokens: 5 })])
  await small.send()
  assert(!small.audits.includes("cache_miss_large_prompt"))
  // OpenRouter anthropic/* (including as a served fallback), streaming and not.
  const viaFallback = await fixture([
    () => fail(500, { message: "x" }),
    () => chat({ prompt_tokens: 12000, completion_tokens: 2 }),
  ])
  await viaFallback.send()
  assert(viaFallback.audits.includes("cache_miss_large_prompt"))
  const cachedOr = await fixture([() => fail(500, { message: "x" }), () =>
    chat({
      prompt_tokens: 12000,
      completion_tokens: 2,
      prompt_tokens_details: { cached_tokens: 11000 },
    })])
  await cachedOr.send()
  assert(!cachedOr.audits.includes("cache_miss_large_prompt"))
  const sse = [
    { type: "message_start", message: { usage: big } },
    { type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 5 } },
    { type: "message_stop" },
  ].map((e) => `data: ${JSON.stringify(e)}\n\n`).join("")
  const streamed = await fixture([() => new Response(sse, { status: 200 })])
  await (await streamed.send({ stream: true })).text()
  assert(streamed.audits.includes("cache_miss_large_prompt"))
  // Other upstreams are not expected to report caching.
  const plainRoute = await fixture([() => chat({ prompt_tokens: 12000, completion_tokens: 2 })], {
    primary: { ...openrouter, base_url: "https://example.com/v1" },
    fallbacks: [],
  })
  await plainRoute.send()
  assert(!plainRoute.audits.includes("cache_miss_large_prompt"))
})

Deno.test("a fallback route that cannot be built is skipped, not fatal", async () => {
  const f = await fixture([() => fail(500, { message: "x" }), () => chat()], {
    fallbacks: [
      {
        ...fallbacks[0],
        provider_routing: { order: ["a"] },
        connection: { ...openrouter, base_url: "https://example.com/v1" },
      },
      fallbacks[1],
    ],
  })
  const response = await f.send()
  assertEquals(response.status, 200)
  assertEquals(f.fetched.map((x) => x.body.model), [
    "claude-opus-5-5",
    "anthropic/claude-haiku-5.5",
  ])
  assert(f.audits.includes("route_unusable"))
})

Deno.test("attempt deadline applies to streams only: a slow non-stream answer is not re-run", async () => {
  const slow: Responder = async () => {
    await new Promise((r) => setTimeout(r, 80))
    return message()
  }
  const plain = await fixture([slow, () => chat()], { attempt: 20 })
  const response = await plain.send()
  assertEquals((await response.json()).choices[0].message.content, "from anthropic")
  assertEquals(plain.fetched.length, 1)
  assert(!plain.audits.includes("upstream_fallback"))
  const hang: Responder = (_url, init) =>
    new Promise((_, reject) =>
      init.signal!.addEventListener("abort", () => reject(new Error("aborted")), { once: true })
    )
  const streamed = await fixture([hang, () => chat()], { attempt: 20 })
  assertEquals((await streamed.send({ stream: true })).status, 200)
  assertEquals(streamed.fetched.length, 2)
})
