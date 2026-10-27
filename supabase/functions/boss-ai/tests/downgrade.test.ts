import { assert, assertEquals } from "@std/assert"
import { createHandler } from "../app.ts"
import { mintToken, signingKey } from "../auth.ts"
import { Connection, Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
const user = "00000000-0000-0000-0000-000000000001"
const openrouter: Connection = {
  id: "or",
  base_url: "https://openrouter.ai/api/v1",
  api_type: "openai_chat",
  api_key_secret: "BOSS_AI_OR",
}
const anthropic: Connection = {
  id: "anthropic",
  base_url: "https://api.anthropic.com/v1",
  api_type: "anthropic_messages",
  api_key_secret: "BOSS_AI_ANTHROPIC",
}
const target = (extra: Obj = {}) => ({
  id: "optimist-lite",
  upstream_model: "anthropic/claude-haiku-5.5",
  connection_id: "or",
  kind: "chat",
  capabilities: ["text", "tools"],
  context_length: 8192,
  max_output_tokens: 1024,
  provider_routing: null,
  ...extra,
})
const requested = {
  ...target(),
  id: "optimist",
  upstream_model: "claude-opus-5-5",
  context_length: 100_000,
  max_output_tokens: 4096,
}

async function fixture(
  options: { downgraded?: boolean; targetModel?: Obj; fetchStatus?: number; stream?: string } = {},
) {
  const calls: { name: string; params: Obj }[] = []
  const fetched: { url: string; body: Obj }[] = []
  const audits: string[] = []
  const token = (await mintToken(user, signingKey(secret))).access_token
  const handler = createHandler({
    audit: (event) => void audits.push(event),
    settlementTimeoutMs: 50,
    sessionUser: () => Promise.resolve(user),
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : "k",
    rpc(name, params) {
      calls.push({ name, params })
      if (name === "boss_ai_lookup") {
        return Promise.resolve({ model: requested, connection: anthropic, fallbacks: [] })
      }
      if (name === "boss_ai_reserve") {
        return Promise.resolve(
          options.downgraded === false
            ? { model: requested, connection: anthropic, fallbacks: [] }
            : {
              model: options.targetModel ?? target(),
              connection: openrouter,
              fallbacks: [],
              downgraded_from: "optimist",
            },
        )
      }
      if (name === "boss_ai_catalog") {
        return Promise.resolve([{
          id: "optimist",
          allowance: { day: { remaining: 0 } },
          over_allowance_model_id: "optimist-lite",
          downgraded: true,
          downgrade_resets_at: "2026-11-01T00:00:00Z",
        }, { id: "plain", allowance: null, over_allowance_model_id: null, downgraded: false }])
      }
      return Promise.resolve(null)
    },
    fetch(url, init) {
      fetched.push({ url: String(url), body: JSON.parse(String(init!.body)) })
      if (options.fetchStatus) {
        return Promise.resolve(new Response("{}", { status: options.fetchStatus }))
      }
      return Promise.resolve(
        new Response(
          options.stream ?? JSON.stringify({
            choices: [{ index: 0, message: { role: "assistant", content: "lite answer" } }],
            usage: { prompt_tokens: 20, completion_tokens: 5 },
          }),
          { status: 200 },
        ),
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
          messages: [{ role: "user", content: "hi" }],
          ...extra,
        }),
      }),
    )
  const get = (path: string) =>
    handler(
      new Request(`https://x.example/functions/v1/boss-ai${path}`, {
        headers: { Authorization: `Bearer ${token}` },
      }),
    )
  const settles = () => calls.filter((c) => c.name === "boss_ai_settle").map((c) => c.params)
  const reserves = () => calls.filter((c) => c.name === "boss_ai_reserve")
  return { send, get, fetched, audits, settles, reserves }
}

Deno.test("downgrade: served from the target, response keeps the requested model, headers and audit", async () => {
  const f = await fixture()
  const response = await f.send()
  assertEquals(response.status, 200)
  assertEquals(response.headers.get("X-BOSS-AI-Downgraded-From"), "optimist")
  assertEquals(response.headers.get("X-BOSS-AI-Served-Model"), "optimist-lite")
  const out = await response.json()
  assertEquals(out.model, "optimist")
  assertEquals(out.choices[0].message.content, "lite answer")
  // The target's connection, upstream model and auth are used.
  assertEquals(f.fetched.length, 1)
  assertEquals(f.fetched[0].url, "https://openrouter.ai/api/v1/chat/completions")
  assertEquals(f.fetched[0].body.model, "anthropic/claude-haiku-5.5")
  assert(f.audits.includes("allowance_downgrade"))
  // One reservation, settled once on the measured tokens (the reserved row is the target's).
  assertEquals(f.reserves().length, 1)
  assertEquals(f.reserves()[0].params.p_model_id, "optimist")
  assertEquals(f.settles().length, 1)
  assertEquals(f.settles()[0].p_tokens, 25)
  assertEquals(f.settles()[0].p_request_id, f.reserves()[0].params.p_request_id)
})

Deno.test("downgrade: streams carry the headers and the requested model id", async () => {
  const sse = `data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: "hi" } }] })}\n\n` +
    `data: ${
      JSON.stringify({ choices: [], usage: { prompt_tokens: 3, completion_tokens: 4 } })
    }\n\ndata: [DONE]\n\n`
  const f = await fixture({ stream: sse })
  const response = await f.send({ stream: true })
  assertEquals(response.headers.get("X-BOSS-AI-Served-Model"), "optimist-lite")
  const text = await response.text()
  assert(text.includes('"model":"optimist"'))
  assert(!text.includes("optimist-lite"))
  assertEquals(f.settles()[0].p_tokens, 7)
})

Deno.test("no downgrade: no headers and no audit", async () => {
  const f = await fixture({ downgraded: false })
  const response = await f.send()
  assertEquals(response.headers.get("X-BOSS-AI-Downgraded-From"), null)
  assertEquals(response.headers.get("X-BOSS-AI-Served-Model"), null)
  assert(!f.audits.includes("allowance_downgrade"))
  assertEquals(f.fetched[0].url, "https://api.anthropic.com/v1/messages")
})

Deno.test("downgrade: max_tokens above the target's limit is clamped; without a downgrade it stays a 400", async () => {
  for (const key of ["max_tokens", "max_completion_tokens"]) {
    const f = await fixture({ targetModel: target({ max_output_tokens: 100 }) })
    const response = await f.send({ [key]: 2000 })
    assertEquals(response.status, 200)
    assertEquals(f.fetched[0].body.max_completion_tokens, 100)
    assertEquals(f.settles()[0].p_tokens, 25)
  }
  // A value within the target's limit is untouched.
  const within = await fixture({ targetModel: target({ max_output_tokens: 100 }) })
  await within.send({ max_tokens: 50 })
  assertEquals(within.fetched[0].body.max_completion_tokens, 50)
  // Not downgraded: the requested model's own limit still rejects, before any reservation.
  const plain = await fixture({ downgraded: false })
  const rejected = await plain.send({ max_tokens: 5000 })
  assertEquals(rejected.status, 400)
  assertEquals(plain.fetched.length, 0)
  assertEquals(plain.reserves().length, 0)
})

Deno.test("downgrade: an upstream failure on the target keeps the usual refund rules", async () => {
  const f = await fixture({ fetchStatus: 500 })
  const response = await f.send()
  assertEquals(response.status, 502)
  assertEquals(typeof f.settles()[0].p_tokens, "number") // prompt estimate, not the reservation
})

Deno.test("/v1/usage and /v1/models expose the effective target and downgraded state", async () => {
  const f = await fixture()
  const usage = await (await f.get("/v1/usage")).json()
  assertEquals(usage.data[0], {
    model: "optimist",
    allowance: { day: { remaining: 0 } },
    over_allowance_model_id: "optimist-lite",
    downgraded: true,
    downgrade_resets_at: "2026-11-01T00:00:00Z",
  })
  assertEquals(usage.data[1].over_allowance_model_id, null)
  assertEquals(usage.data[1].downgraded, false)
  const models = await (await f.get("/v1/models")).json()
  assertEquals(models.data[0].over_allowance_model_id, "optimist-lite")
})
