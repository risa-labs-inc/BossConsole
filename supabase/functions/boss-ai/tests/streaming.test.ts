import { assert, assertEquals } from "@std/assert"
import { createHandler } from "../app.ts"
import { mintToken, signingKey } from "../auth.ts"
import { Connection, Obj, StreamAdapter } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
const user = "00000000-0000-0000-0000-000000000001"
const anthropic: Connection = {
  id: "anthropic",
  base_url: "https://api.anthropic.com/v1",
  api_type: "anthropic_messages",
  api_key_secret: "BOSS_AI_ANTHROPIC",
}
const openai: Connection = { ...anthropic, id: "oa", api_type: "openai_chat" }
const RESERVED = 100_000
const enc = new TextEncoder()
const sse = (e: Obj) => enc.encode(`data: ${JSON.stringify(e)}\n\n`)
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

// An upstream whose body the test drives; it closes when the gateway aborts.
function live(signal: AbortSignal) {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({ start: (c) => void (controller = c) })
  signal.addEventListener("abort", () => {
    try {
      controller.close()
    } catch { /* already closed */ }
  }, { once: true })
  return {
    body,
    send: (e: Obj) => controller.enqueue(sse(e)),
    raw: (text: string) => controller.enqueue(enc.encode(text)),
    done: () => controller.close(),
  }
}

async function fixture(
  connection: Connection,
  drive: (u: ReturnType<typeof live>) => Promise<void>,
) {
  const calls: { name: string; params: Obj }[] = []
  const audits: string[] = []
  const logs: Obj[] = []
  let requestBytes = 0
  const token = (await mintToken(user, signingKey(secret))).access_token
  const handler = createHandler({
    audit: (event) => void audits.push(event),
    log: (entry) => void logs.push(entry),
    keepaliveMs: 20,
    upstreamTimeoutMs: 5_000,
    settlementTimeoutMs: 50,
    sessionUser: () => Promise.resolve(user),
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : "k",
    rpc(name, params) {
      calls.push({ name, params })
      if (name === "boss_ai_reserve" || name === "boss_ai_lookup") {
        return Promise.resolve({
          model: {
            id: "m",
            upstream_model: "claude-opus-5-5",
            kind: "chat",
            capabilities: ["text"],
            context_length: RESERVED,
            max_output_tokens: 4096,
          },
          connection,
          fallbacks: [],
        })
      }
      return Promise.resolve(null)
    },
    fetch(_url, init) {
      requestBytes = String(init!.body).length
      const u = live(init!.signal!)
      void drive(u)
      return Promise.resolve(new Response(u.body, { status: 200 }))
    },
  })
  const response = await handler(
    new Request("https://x.example/functions/v1/boss-ai/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        model: "m",
        stream: true,
        messages: [{ role: "user", content: "hi" }],
      }),
    }),
  )
  const settles = () =>
    calls.filter((c) => c.name === "boss_ai_settle").map((c) => c.params.p_tokens)
  return { response, audits, logs, settles, requestBytes: () => requestBytes }
}

const parse = (text: string) =>
  text.split("\n\n").filter((x) => x.startsWith("data: {")).map((x) => JSON.parse(x.slice(6)))
const deltas = (chunks: Obj[]) =>
  chunks.flatMap((c) => c.choices as Obj[]).map((c) => c.delta as Obj)

Deno.test("silent thinking: keepalive reasoning deltas, then real reasoning and the answer", async () => {
  const f = await fixture(anthropic, async (u) => {
    u.send({ type: "message_start", message: { usage: { input_tokens: 50, output_tokens: 1 } } })
    u.send({
      type: "content_block_start",
      index: 0,
      content_block: { type: "thinking", thinking: "" },
    })
    await sleep(110)
    u.send({
      type: "content_block_delta",
      index: 0,
      delta: { type: "signature_delta", signature: "x" },
    })
    u.send({
      type: "content_block_delta",
      index: 0,
      delta: { type: "thinking_delta", thinking: "Plan: " },
    })
    u.send({ type: "content_block_stop", index: 0 })
    u.send({ type: "content_block_start", index: 1, content_block: { type: "text", text: "" } })
    u.send({ type: "content_block_delta", index: 1, delta: { type: "text_delta", text: "Done." } })
    u.send({
      type: "message_delta",
      delta: { stop_reason: "end_turn" },
      usage: { output_tokens: 9 },
    })
    u.send({ type: "message_stop" })
    u.done()
  })
  const text = await f.response.text()
  assert(text.endsWith("data: [DONE]\n\n"))
  const d = deltas(parse(text))
  const reasoning = d.map((x) => x.reasoning_content).filter(Boolean)
  // Several keepalives (110 ms of silence at 20 ms) precede the real thinking text.
  const keepalives = reasoning.filter((r) => r === "​")
  assert(keepalives.length >= 2 && keepalives.length <= 6, `keepalives: ${keepalives.length}`)
  assertEquals(reasoning.at(-1), "Plan: ")
  assertEquals(reasoning.indexOf("Plan: "), keepalives.length)
  assertEquals(d.map((x) => x.content).filter(Boolean), ["Done."])
  assertEquals(f.settles(), [59])
})

Deno.test("cancel mid-stream settles message_start prompt tokens plus streamed output, not the reservation", async () => {
  const f = await fixture(anthropic, (u) => {
    u.send({
      type: "message_start",
      message: { usage: { input_tokens: 7, cache_read_input_tokens: 5000, output_tokens: 1 } },
    })
    u.send({ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } })
    u.send({
      type: "content_block_delta",
      index: 0,
      delta: { type: "text_delta", text: "a".repeat(300) },
    })
    return Promise.resolve()
  })
  const reader = f.response.body!.getReader()
  let seen = ""
  while (!seen.includes("aaaa")) seen += new TextDecoder().decode((await reader.read()).value)
  await reader.cancel()
  assertEquals(f.settles(), [5007 + 100])
  assert(f.audits.includes("usage_estimated_after_cancel"))
  assertEquals(f.logs.at(-1), {
    event: "usage_estimated_after_cancel",
    request_id: f.logs.at(-1)!.request_id,
    estimate: 5107,
    prompt: 5007,
    output: 100,
  })
})

Deno.test("cancel on an OpenAI-format stream estimates from the request size and streamed output", async () => {
  const f = await fixture(openai, (u) => {
    u.send({ choices: [{ index: 0, delta: { content: "b".repeat(90) } }] })
    return Promise.resolve()
  })
  const reader = f.response.body!.getReader()
  let seen = ""
  while (!seen.includes("bbbb")) seen += new TextDecoder().decode((await reader.read()).value)
  await reader.cancel()
  const [settled] = f.settles() as number[]
  assertEquals(settled, Math.ceil(f.requestBytes() / 3) + 30)
  assert(settled < RESERVED)
})

Deno.test("a stream that completed without usage still keeps the full reservation", async () => {
  const f = await fixture(openai, (u) => {
    u.send({ choices: [{ index: 0, delta: { content: "x" } }] })
    u.raw("data: [DONE]\n\n")
    u.done()
    return Promise.resolve()
  })
  assert((await f.response.text()).endsWith("data: [DONE]\n\n"))
  assertEquals(f.settles(), [null])
  assert(!f.audits.includes("usage_estimated_after_cancel"))
})

Deno.test("OpenRouter reasoning deltas pass through the adapter and count as output", () => {
  const adapter = new StreamAdapter("m", "openai_chat", "i")
  const out = adapter.accept(
    JSON.stringify({ choices: [{ index: 0, delta: { reasoning: "thinking it over" } }] }),
  )
  assertEquals(((out[0].choices as Obj[])[0].delta as Obj).reasoning, "thinking it over")
  assertEquals(adapter.streamedChars, 16)
  assertEquals((adapter.keepalive().choices as Obj[])[0], {
    index: 0,
    delta: { role: "assistant", reasoning_content: "​" },
    finish_reason: null,
  })
})
