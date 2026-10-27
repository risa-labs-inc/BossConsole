import { assert, assertEquals } from "@std/assert"
import { createHandler } from "../app.ts"
import { mintToken, signingKey } from "../auth.ts"
import { Connection, Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
const user = "00000000-0000-0000-0000-000000000001"
const DECIDER = "https://litellm-cw-tnc7knp4ba-uc.a.run.app/v1/embeddings"
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
const decider: Connection = {
  id: "risa_qwen_internal",
  base_url: "https://litellm-cw-tnc7knp4ba-uc.a.run.app",
  api_type: "openai_chat",
  api_key_secret: "BOSS_AI_RISA_QWEN",
}
const tierModel = (id: string, upstream: string, prices: Obj = {}) => ({
  id,
  upstream_model: upstream,
  connection_id: "x",
  kind: "chat",
  capabilities: ["text", "tools"],
  context_length: 200_000,
  max_output_tokens: 8192,
  provider_routing: null,
  usd_per_mtok_input: 4,
  usd_per_mtok_output: 20,
  usd_per_mtok_cache_read: 0.2,
  usd_per_mtok_cache_write: 5,
  ...prices,
})
const models: Record<string, Obj> = {
  fast: tierModel("optimist-fast", "anthropic/claude-haiku-5.5", {
    usd_per_mtok_input: 0.1,
    usd_per_mtok_output: 0.5,
    usd_per_mtok_cache_read: 0.01,
    usd_per_mtok_cache_write: 0.125,
  }),
  standard: tierModel("optimist-standard", "claude-sonnet-5-5", {
    usd_per_mtok_input: 2,
    usd_per_mtok_output: 10,
    usd_per_mtok_cache_read: 0.2,
    usd_per_mtok_cache_write: 2.5,
  }),
  strong: tierModel("optimist-strong", "claude-opus-5-5"),
}
const connections: Record<string, Connection> = {
  "optimist-fast": openrouter,
  "optimist-standard": anthropic,
  "optimist-strong": anthropic,
}
const router = {
  model_id: "optimist",
  decider_api: "litellm_embeddings",
  decider_model: "risa-pplx-decider-v1-27b",
  decider_temperature: 1.0087417621345625,
  decider_timeout_ms: 500,
  state_char_budget: 12_000,
  question: "Which model tier should handle this request?",
  choices: [
    { tier: "fast", label: "Quick reply", description: "Greeting." },
    { tier: "standard", label: "Everyday task", description: "Routine." },
    { tier: "strong", label: "Hard task", description: "Hard." },
  ],
  min_confidence: 0.55,
  default_tier: "standard",
  fast_model_id: "optimist-fast",
  standard_model_id: "optimist-standard",
  strong_model_id: "optimist-strong",
  exhausted_model_id: null,
  hard_stop_at: null,
  sticky_within_turn: true,
  monthly_usd_limit: 100,
  strong_cap_at: 0.6,
  fast_only_at: 0.85,
}
const scores = (...first: number[]) => [...first, ...Array(255 - first.length).fill(1000)]
const STRONG = scores(0, 0, 8), FAST = scores(8, 0, 0), UNSURE = scores(0.2, 0.1, 0.15)

interface Options {
  spent?: number
  routerPatch?: Obj
  scores?: number[] | "fail" | "hang"
  sticky?: string | null
  reserveError?: Obj
  usage?: Obj
  models?: Record<string, Obj>
  secrets?: Record<string, string>
  stream?: string
  ollaya?: Obj | "fail"
  localUpstream?: boolean
}
const ollayaConn: Connection = {
  id: "ollaya-local",
  base_url: "http://host.docker.internal:11437",
  api_type: "openai_chat",
  api_key_secret: "BOSS_AI_OLLAYA",
}

async function fixture(options: Options = {}) {
  const calls: { name: string; params: Obj }[] = []
  const fetched: { url: string; body: Obj }[] = []
  const audits: string[] = []
  const logs: Obj[] = []
  const tm = options.models ?? models
  const token = (await mintToken(user, signingKey(secret))).access_token
  const limit = 100, spent = options.spent ?? 0
  const rt = {
    ...router,
    ...(options.ollaya ? { decider_api: "systemone", decider_model: "decider:4b" } : {}),
    ...(options.routerPatch ?? {}),
  }
  const exhaustedModel = tm.exhausted ?? tm.fast
  const ctx = {
    router: rt,
    decider_connection: options.ollaya ? ollayaConn : decider,
    models: { ...tm, exhausted: exhaustedModel },
    budget: {
      monthly_usd_limit: limit,
      spent_usd: spent,
      remaining_usd: Math.max(0, limit - spent),
      resets_at: "2026-11-01T00:00:00Z",
      state: "normal",
    },
  }
  const handler = createHandler({
    audit: (event) => void audits.push(event),
    log: (entry) => void logs.push(entry),
    settlementTimeoutMs: 50,
    upstreamTimeoutMs: 5_000,
    sessionUser: () => Promise.resolve(user),
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : options.secrets?.[name] ?? "k",
    rpc(name, params) {
      calls.push({ name, params })
      const virtual = {
        id: "optimist",
        upstream_model: "unused",
        kind: "chat",
        capabilities: ["text", "tools"],
        context_length: 1_000_000,
        max_output_tokens: 64_000,
      }
      if (name === "boss_ai_lookup") {
        return Promise.resolve({
          model: virtual,
          connection: anthropic,
          fallbacks: [],
          router: ctx,
        })
      }
      if (name === "boss_ai_route_get") return Promise.resolve(options.sticky ?? null)
      if (name === "boss_ai_reserve_routed") {
        if (options.reserveError) return Promise.resolve(options.reserveError)
        const served = String(params.p_served_model_id)
        const row = Object.values({ ...tm, exhausted: exhaustedModel }).find((m) =>
          m.id === served
        )!
        return Promise.resolve({
          model: row,
          connection: options.localUpstream
            ? { ...openrouter, base_url: "http://127.0.0.1:4000/v1" }
            : connections[served],
          fallbacks: [],
          routed_from: "optimist",
          tier: params.p_tier,
          route_source: params.p_route_source,
          budget_exhausted: spent >= limit || Number(params.p_est_cost) + spent > limit,
        })
      }
      return Promise.resolve(null)
    },
    async fetch(url, init) {
      const body = JSON.parse(String(init!.body))
      fetched.push({ url: String(url), body })
      if (String(url) === "http://host.docker.internal:11437/v1/systemone") {
        if (options.ollaya === "fail") return new Response("{}", { status: 503 })
        return new Response(JSON.stringify({ answers: { tier: options.ollaya } }), { status: 200 })
      }
      if (String(url) === DECIDER) {
        if (options.scores === "fail") return new Response("{}", { status: 500 })
        if (options.scores === "hang") {
          await new Promise((_, reject) =>
            init!.signal!.addEventListener("abort", () => reject(new Error("aborted")))
          )
        }
        return new Response(
          JSON.stringify({
            model: "risa-pplx-decider-v1-27b",
            data: [{ index: 0, embedding: options.scores ?? STRONG }],
          }),
          { status: 200 },
        )
      }
      if (options.stream) return new Response(options.stream, { status: 200 })
      return new Response(
        JSON.stringify(
          String(url).endsWith("/messages")
            ? {
              type: "message",
              content: [{ type: "text", text: "the answer" }],
              stop_reason: "end_turn",
              usage: options.usage ?? { input_tokens: 100, output_tokens: 50 },
            }
            : {
              choices: [{ index: 0, message: { role: "assistant", content: "the answer" } }],
              usage: options.usage ?? { prompt_tokens: 100, completion_tokens: 50 },
            },
        ),
        { status: 200 },
      )
    },
  })
  const send = (extra: Obj = {}) =>
    handler(
      new Request("https://x.example/functions/v1/boss-ai/v1/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        body: JSON.stringify({
          model: "optimist",
          messages: [{ role: "user", content: "please debug this failing test" }],
          ...extra,
        }),
      }),
    )
  const reserved = () => calls.find((c) => c.name === "boss_ai_reserve_routed")?.params
  const settle = () => calls.filter((c) => c.name === "boss_ai_settle").at(-1)?.params
  const deciderCalls = () => fetched.filter((f) => f.url === DECIDER)
  return { send, calls, fetched, audits, logs, reserved, settle, deciderCalls, token, handler }
}

const ASK_STRONG = {
  choice: "strong",
  confidence: 0.92,
  probabilities: { fast: 0.01, standard: 0.07, strong: 0.92 },
}

const headersOf = (r: Response) => ({
  tier: r.headers.get("X-BOSS-AI-Tier"),
  served: r.headers.get("X-BOSS-AI-Served-Model"),
  source: r.headers.get("X-BOSS-AI-Route-Source"),
  budget: r.headers.get("X-BOSS-AI-Budget"),
})

Deno.test("router: the decider picks the tier, the response keeps the requested model", async () => {
  const f = await fixture()
  const response = await f.send()
  assertEquals(response.status, 200)
  assertEquals(headersOf(response), {
    tier: "strong",
    served: "optimist-strong",
    source: "decider",
    budget: null,
  })
  assertEquals((await response.json()).model, "optimist")
  assertEquals(f.deciderCalls().length, 1)
  const input = (f.deciderCalls()[0].body.input as string[])[0]
  assert(input.includes("State:\nLatest user message:\nplease debug this failing test"))
  assert(
    input.includes("A: Quick reply: Greeting.\nB: Everyday task: Routine.\nC: Hard task: Hard."),
  )
  // Served by the strong tier's own route; the reservation is against that model.
  assertEquals(f.fetched.at(-1)!.url, "https://api.anthropic.com/v1/messages")
  assertEquals(f.fetched.at(-1)!.body.model, "claude-opus-5-5")
  const p = f.reserved()!
  assertEquals([p.p_model_id, p.p_served_model_id, p.p_tier, p.p_route_source], [
    "optimist",
    "optimist-strong",
    "strong",
    "decider",
  ])
  assert(Number(p.p_est_cost) > 0)
  // The fast tier routes to OpenRouter.
  const fast = await fixture({ scores: FAST })
  const r = await fast.send()
  assertEquals(headersOf(r).served, "optimist-fast")
  assertEquals(fast.fetched.at(-1)!.url, "https://openrouter.ai/api/v1/chat/completions")
  // The tier is remembered for the turn.
  assertEquals(f.calls.find((c) => c.name === "boss_ai_route_put")?.params.p_tier, "strong")
})

Deno.test("router: a tool loop continuation reuses the turn's tier without asking the decider", async () => {
  const loop = [
    { role: "user", content: "do the thing" },
    {
      role: "assistant",
      content: null,
      tool_calls: [{ id: "c1", type: "function", function: { name: "t", arguments: "{}" } }],
    },
    { role: "tool", tool_call_id: "c1", content: "result" },
  ]
  const f = await fixture({ sticky: "strong", scores: FAST })
  const response = await f.send({
    messages: loop,
    tools: [{ type: "function", function: { name: "t" } }],
  })
  assertEquals(headersOf(response), {
    tier: "strong",
    served: "optimist-strong",
    source: "sticky",
    budget: null,
  })
  assertEquals(f.deciderCalls().length, 0)
  const get = f.calls.find((c) => c.name === "boss_ai_route_get")!.params
  assert(/^[0-9a-f]{64}$/.test(String(get.p_key)))
  // Not remembered yet (e.g. a new isolate or an expired entry): the decider is asked after all.
  const cold = await fixture({ sticky: null, scores: FAST })
  assertEquals(headersOf(await cold.send({ messages: loop })).source, "decider")
  assertEquals(cold.deciderCalls().length, 1)
  // A new user message is never a continuation, whatever is stored.
  const fresh = await fixture({ sticky: "strong", scores: FAST })
  await fresh.send()
  assertEquals(fresh.deciderCalls().length, 1)
  assertEquals(fresh.calls.some((c) => c.name === "boss_ai_route_get"), false)
  // sticky_within_turn off: always decide, nothing stored.
  const off = await fixture({ sticky: "strong", routerPatch: { sticky_within_turn: false } })
  await off.send({ messages: loop })
  assertEquals(off.deciderCalls().length, 1)
  assertEquals(
    off.calls.some((c) => c.name === "boss_ai_route_put" || c.name === "boss_ai_route_get"),
    false,
  )
})

Deno.test("router: low confidence, decider failure and timeout use the default tier and still serve", async () => {
  const unsure = await fixture({ scores: UNSURE })
  const r1 = await unsure.send()
  assertEquals(r1.status, 200)
  assertEquals(headersOf(r1), {
    tier: "standard",
    served: "optimist-standard",
    source: "default",
    budget: null,
  })
  assert(unsure.audits.includes("route_low_confidence"))
  const failed = await fixture({ scores: "fail" })
  const r2 = await failed.send()
  assertEquals(r2.status, 200)
  assertEquals(headersOf(r2).source, "default")
  assert(failed.audits.includes("route_decider_failed"))
  const hung = await fixture({ scores: "hang", routerPatch: { decider_timeout_ms: 50 } })
  const r3 = await hung.send()
  assertEquals(r3.status, 200)
  assertEquals(headersOf(r3).tier, "standard")
  assert(hung.audits.includes("route_decider_failed"))
  assertEquals(hung.deciderCalls().length, 1)
  // A different default tier is honoured; a missing decider key is also just a default.
  const strongDefault = await fixture({ scores: "fail", routerPatch: { default_tier: "strong" } })
  assertEquals(headersOf(await strongDefault.send()).tier, "strong")
})

Deno.test("budget: 60% caps strong, 85% forces fast, both reported as source budget", async () => {
  const none = await fixture({ spent: 59 })
  assertEquals(headersOf(await none.send()).tier, "strong")
  const capped = await fixture({ spent: 60 })
  assertEquals(headersOf(await capped.send()), {
    tier: "standard",
    served: "optimist-standard",
    source: "budget",
    budget: null,
  })
  const fastOnly = await fixture({ spent: 85 })
  assertEquals(headersOf(await fastOnly.send()), {
    tier: "fast",
    served: "optimist-fast",
    source: "budget",
    budget: null,
  })
  // A call that would not fit under the limit steps down by itself.
  const tight = await fixture({
    spent: 99.9999,
    routerPatch: { strong_cap_at: 1, fast_only_at: 1 },
  })
  const r = await tight.send({ max_tokens: 8000 })
  assertEquals(headersOf(r).source, "budget")
})

Deno.test("budget: exhausted is served on the exhausted model, never refused", async () => {
  const f = await fixture({ spent: 100 })
  const response = await f.send()
  assertEquals(response.status, 200)
  assertEquals(headersOf(response), {
    tier: "fast",
    served: "optimist-fast",
    source: "budget",
    budget: "exhausted",
  })
  assert(f.audits.includes("budget_exhausted_served_fast"))
  assertEquals(f.reserved()!.p_served_model_id, "optimist-fast")
  // A separate exhausted model is used when configured.
  const cheap = {
    ...models,
    exhausted: tierModel("optimist-free", "free/model", { usd_per_mtok_input: 0 }),
  }
  const g = await fixture({
    spent: 250,
    models: cheap,
    routerPatch: { exhausted_model_id: "optimist-free" },
  })
  connections["optimist-free"] = openrouter
  const r = await g.send()
  assertEquals(headersOf(r).served, "optimist-free")
  assertEquals(headersOf(r).budget, "exhausted")
})

Deno.test("budget: only hard_stop_at refuses, with 429 budget_exhausted and resets_at", async () => {
  const f = await fixture({ spent: 150, routerPatch: { hard_stop_at: 1.5 } })
  const response = await f.send()
  assertEquals(response.status, 429)
  const body = await response.json()
  assertEquals(body.error.code, "budget_exhausted")
  assertEquals(body.error.budget.resets_at, "2026-11-01T00:00:00Z")
  assertEquals(f.reserved(), undefined)
  assertEquals(f.fetched.filter((x) => !x.url.includes("litellm")).length, 0)
  // Below the stop, an exhausted user is still served.
  const below = await fixture({ spent: 120, routerPatch: { hard_stop_at: 1.5 } })
  assertEquals((await below.send()).status, 200)
  // The database re-checks under its lock and can refuse too.
  const db = await fixture({
    reserveError: {
      error: "budget_exhausted",
      budget: { monthly_usd_limit: 100, resets_at: "2026-11-01T00:00:00Z" },
    },
  })
  const refused = await db.send()
  assertEquals(refused.status, 429)
  assertEquals((await refused.json()).error.code, "budget_exhausted")
  // Refused at admission: nothing was reserved, so nothing is settled.
  assertEquals(db.settle(), undefined)
})

Deno.test("cost: settlement carries the token breakdown and USD cost with cache prices", async () => {
  const f = await fixture({
    usage: {
      input_tokens: 1_000_000,
      cache_read_input_tokens: 10_000_000,
      cache_creation_input_tokens: 200_000,
      output_tokens: 100_000,
    },
  })
  await (await f.send()).text()
  const s = f.settle()!
  assertEquals(s.p_input, 1_000_000)
  assertEquals(s.p_output, 100_000)
  assertEquals(s.p_cache_read, 10_000_000)
  assertEquals(s.p_cache_write, 200_000)
  // Opus 5.5: 1M*$4 + 0.1M*$20 + 10M*$0.2 + 0.2M*$5 = 4 + 2 + 2 + 1
  assert(Math.abs(Number(s.p_cost_usd) - 9) < 1e-9)
  assertEquals(s.p_tokens, 11_300_000)
  // OpenRouter reports cached tokens inside prompt_tokens.
  const or = await fixture({
    scores: FAST,
    usage: {
      prompt_tokens: 1_000_000,
      completion_tokens: 1_000_000,
      prompt_tokens_details: { cached_tokens: 400_000, cache_write_tokens: 100_000 },
    },
  })
  await (await or.send()).text()
  const o = or.settle()!
  assertEquals([o.p_input, o.p_cache_read, o.p_cache_write], [500_000, 400_000, 100_000])
  // Haiku 5.5: 0.5M*0.1 + 1M*0.5 + 0.4M*0.01 + 0.1M*0.125
  assert(Math.abs(Number(o.p_cost_usd) - (0.05 + 0.5 + 0.004 + 0.0125)) < 1e-9)
  // An unpriced model: tokens recorded, cost unknown, price_missing logged.
  const unpriced = await fixture({
    models: {
      ...models,
      strong: tierModel("optimist-strong", "claude-opus-5-5", { usd_per_mtok_input: null }),
    },
  })
  await (await unpriced.send()).text()
  assertEquals(unpriced.settle()!.p_input, 100)
  assert(!("p_cost_usd" in unpriced.settle()!))
  assert(unpriced.audits.includes("price_missing"))
})

Deno.test("cost: a stream cut before usage settles the estimate with its cost", async () => {
  const sse = `data: ${
    JSON.stringify({
      type: "message_start",
      message: { usage: { input_tokens: 1000, output_tokens: 1 } },
    })
  }\n\n` +
    `data: ${
      JSON.stringify({
        type: "content_block_delta",
        index: 0,
        delta: { type: "text_delta", text: "x".repeat(300) },
      })
    }\n\n`
  const f = await fixture({ stream: sse })
  const response = await f.send({ stream: true })
  const text = await response.text()
  assert(text.includes("stream_failed"))
  const s = f.settle()!
  assertEquals([s.p_input, s.p_output, s.p_tokens], [1000, 100, 1100])
  assert(Math.abs(Number(s.p_cost_usd) - (1000 * 4 + 100 * 20) / 1e6) < 1e-9)
})

Deno.test("trace is off by default and logs the decision, state and reply only when enabled", async () => {
  const off = await fixture()
  await (await off.send()).text()
  assertEquals(off.logs.filter((l) => l.event === "route_trace").length, 0)
  const on = await fixture({ spent: 12.5, secrets: { BOSS_AI_ROUTE_TRACE: "1" } })
  await (await on.send()).text()
  const trace = on.logs.find((l) => l.event === "route_trace")!
  assertEquals(trace.requested_model, "optimist")
  assertEquals(trace.served_model, "optimist-strong")
  assertEquals([trace.route_source, trace.tier, trace.user], ["decider", "strong", user])
  assertEquals(trace.reply, "the answer")
  assert(String(trace.decider_state).includes("please debug this failing test"))
  assert(typeof trace.decider_latency_ms === "number")
  assert(Number(trace.confidence) > 0.9)
  assertEquals(Object.keys(trace.probabilities as Obj), [
    "Quick reply",
    "Everyday task",
    "Hard task",
  ])
  assertEquals([trace.input_tokens, trace.output_tokens], [100, 50])
  assert(Math.abs(Number(trace.cost_usd) - (100 * 4 + 50 * 20) / 1e6) < 1e-9)
  assertEquals(trace.month_spend_usd, 12.5 + Number(trace.cost_usd))
  // Streamed replies are traced too, truncated to 2000 characters.
  const sse =
    `data: ${
      JSON.stringify({ choices: [{ index: 0, delta: { content: "z".repeat(5000) } }] })
    }\n\n` +
    `data: ${
      JSON.stringify({ choices: [], usage: { prompt_tokens: 10, completion_tokens: 20 } })
    }\n\ndata: [DONE]\n\n`
  const streamed = await fixture({
    scores: FAST,
    stream: sse,
    secrets: { BOSS_AI_ROUTE_TRACE: "1" },
  })
  await (await streamed.send({ stream: true })).text()
  assertEquals(String(streamed.logs.find((l) => l.event === "route_trace")!.reply).length, 2000)
})

Deno.test("/v1/usage reports the router model's budget", async () => {
  const f = await fixture()
  const budget = { monthly_usd_limit: 100, spent_usd: 100, state: "exhausted (serving fast model)" }
  const custom = createHandler({
    sessionUser: () => Promise.resolve(user),
    secret: (n) => n === "BOSS_AI_SIGNING_SECRET" ? secret : "k",
    rpc: (name) =>
      Promise.resolve(
        name === "boss_ai_catalog" ? [{ id: "optimist", allowance: null, budget }] : null,
      ),
    fetch,
  })
  const response = await custom(
    new Request("https://x.example/functions/v1/boss-ai/v1/usage", {
      headers: { Authorization: `Bearer ${f.token}` },
    }),
  )
  assertEquals((await response.json()).data[0].budget, budget)
})

Deno.test("systemone decider: tier from answers.tier, no Bearer without a key, local http needs the flag", async () => {
  const local = { BOSS_AI_ALLOW_LOCAL_AGENT: "1", BOSS_AI_OLLAYA: "", BOSS_AI_ROUTE_TRACE: "1" }
  const f = await fixture({ ollaya: ASK_STRONG, secrets: local })
  const response = await f.send({ tools: [{ type: "function", function: { name: "t" } }] })
  assertEquals(response.status, 200)
  assertEquals(headersOf(response), {
    tier: "strong",
    served: "optimist-strong",
    source: "decider",
    budget: null,
  })
  const asked = f.fetched.find((x) => x.url.endsWith("/v1/systemone"))!
  assertEquals(asked.body.model, "decider:4b")
  assertEquals((asked.body.state as Obj).message, "please debug this failing test")
  assertEquals((asked.body.state as Obj).tools_offered, true)
  assertEquals(Object.keys(((asked.body.questions as Obj).tier as Obj).criteria as Obj), [
    "fast",
    "standard",
    "strong",
  ])
  // Trace carries the same fields as the hosted decider, the state as JSON.
  const trace = f.logs.find((l) => l.event === "route_trace")!
  assertEquals(trace.probabilities, ASK_STRONG.probabilities)
  assertEquals(trace.confidence, 0.92)
  assert(typeof trace.decider_latency_ms === "number")
  assertEquals(JSON.parse(String(trace.decider_state)).message, "please debug this failing test")
  // Without the local flag the http decider is refused and the default tier serves the call.
  const prod = await fixture({ ollaya: ASK_STRONG, secrets: { BOSS_AI_OLLAYA: "" } })
  const r = await prod.send()
  assertEquals(r.status, 200)
  assertEquals(headersOf(r).source, "default")
  assert(prod.audits.includes("route_decider_failed"))
  assertEquals(prod.fetched.some((x) => x.url.endsWith("/v1/systemone")), false)
  // A failing local decider also falls back to the default tier.
  const down = await fixture({ ollaya: "fail", secrets: local })
  assertEquals(headersOf(await down.send()).tier, "standard")
  assert(down.audits.includes("route_decider_failed"))
  // Low confidence uses the default tier here as well.
  const unsure = await fixture({
    ollaya: { choice: "strong", confidence: 0.4, probabilities: { strong: 0.4 } },
    secrets: local,
  })
  assertEquals(headersOf(await unsure.send()).source, "default")
  assert(unsure.audits.includes("route_low_confidence"))
})

Deno.test("regular upstream connections may use local http only with BOSS_AI_ALLOW_LOCAL_AGENT=1", async () => {
  const on = await fixture({ localUpstream: true, secrets: { BOSS_AI_ALLOW_LOCAL_AGENT: "1" } })
  assertEquals((await on.send()).status, 200)
  assertEquals(on.fetched.at(-1)!.url, "http://127.0.0.1:4000/v1/chat/completions")
  const off = await fixture({ localUpstream: true })
  assertEquals((await off.send()).status, 503)
  assertEquals(off.fetched.some((x) => x.url.startsWith("http://127.0.0.1")), false)
})
