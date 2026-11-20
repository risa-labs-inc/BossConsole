import { assert, assertEquals } from "@std/assert"
import { createHandler, Dependencies } from "../app.ts"
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
  options: Partial<Dependencies> = {},
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
    ...options,
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
  assertEquals(f.logs.find((l) => l.event === "usage_estimated_after_cancel"), {
    event: "usage_estimated_after_cancel",
    request_id: f.logs[0].request_id,
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

// Stream timeouts, scaled down: the answer deadline (upstreamTimeoutMs) is far shorter than the
// streams below, which must not matter once the upstream answered.
const timeouts: Partial<Dependencies> = {
  upstreamTimeoutMs: 100,
  streamFirstByteTimeoutMs: 120,
  streamIdleTimeoutMs: 120,
  streamTimeLimitMs: 10_000,
}
type Upstream = ReturnType<typeof live>
const textStart = (u: Upstream) => {
  u.send({ type: "message_start", message: { usage: { input_tokens: 40, output_tokens: 1 } } })
  u.send({ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } })
}
const text = (u: Upstream, t: string) =>
  u.send({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: t } })
const end = (u: Upstream, output: number) => {
  u.send({
    type: "message_delta",
    delta: { stop_reason: "end_turn" },
    usage: { output_tokens: output },
  })
  u.send({ type: "message_stop" })
  u.done()
}
// Sends a chunk every `every` ms for `until` ms; false once the gateway closed the upstream.
async function steadily(u: Upstream, every: number, until: number, t = "x") {
  const start = Date.now()
  try {
    while (Date.now() - start < until) {
      text(u, t)
      await sleep(every)
    }
    return true
  } catch {
    return false
  }
}
const errorsOf = (chunks: Obj[]) => chunks.filter((c) => c.error).map((c) => c.error as Obj)

Deno.test("a long stream with steady chunks is never cut for its length", async () => {
  const f = await fixture(anthropic, async (u) => {
    textStart(u)
    // 700 ms: 7x the answer deadline and ~6x the idle timeout, with a chunk every 20 ms.
    if (await steadily(u, 20, 700)) end(u, 35)
  }, timeouts)
  const body = await f.response.text()
  assert(body.endsWith("data: [DONE]\n\n"), body.slice(-300))
  assertEquals(errorsOf(parse(body)), [])
  assert(deltas(parse(body)).filter((d) => d.content === "x").length >= 20)
  assert(!f.audits.some((a) => a.startsWith("stream_")), f.audits.join(","))
  assertEquals(f.settles(), [75])
})

Deno.test("upstream comments count as activity while a reply is pending", async () => {
  const f = await fixture(openai, async (u) => {
    // 400 ms of OpenRouter-style processing comments only, then the answer.
    const start = Date.now()
    while (Date.now() - start < 400) {
      u.raw(": OPENROUTER PROCESSING\n\n")
      await sleep(30)
    }
    u.send({ choices: [{ index: 0, delta: { content: "ok" }, finish_reason: "stop" }] })
    u.send({ choices: [], usage: { prompt_tokens: 5, completion_tokens: 1, total_tokens: 6 } })
    u.raw("data: [DONE]\n\n")
    u.done()
  }, timeouts)
  const body = await f.response.text()
  assert(body.endsWith("data: [DONE]\n\n"), body.slice(-300))
  assert(!f.audits.includes("stream_failed"), f.audits.join(","))
})

Deno.test("a stream that stalls mid-answer is cut by the idle timeout and settled on an estimate", async () => {
  const started = Date.now()
  const f = await fixture(anthropic, async (u) => {
    textStart(u)
    text(u, "a".repeat(300))
    await sleep(2_000) // silent upstream; the gateway's own keepalives (every 20 ms) must not hide it
  }, timeouts)
  const body = await f.response.text()
  const elapsed = Date.now() - started
  assert(elapsed < 1_500, `cut after ${elapsed} ms`)
  assertEquals(errorsOf(parse(body)), [{
    code: "stream_failed",
    message: "The model stopped sending output mid-answer. Please retry.",
  }])
  assert(f.audits.includes("stream_failed") && f.audits.includes("stream_idle"))
  assertEquals(f.settles(), [40 + 100])
  const cut = f.logs.find((l) => l.event === "stream_cut")!
  assertEquals([cut.reason, cut.streamed_chars], ["idle", 300])
})

Deno.test("a stream that answers but never sends a byte is cut by the first-byte timeout", async () => {
  const started = Date.now()
  const f = await fixture(anthropic, async () => {
    await sleep(2_000)
  }, timeouts)
  const body = await f.response.text()
  assert(Date.now() - started < 1_500)
  assertEquals(errorsOf(parse(body)), [{
    code: "stream_failed",
    message: "The model accepted the request but sent no output in time. Please retry.",
  }])
  assert(f.audits.includes("stream_first_byte"))
  assert(!f.audits.includes("stream_idle"))
  assertEquals(f.settles(), [Math.ceil(f.requestBytes() / 3)])
})

Deno.test("a stream still running at the wall-clock limit ends with stream_time_limit", async () => {
  const f = await fixture(anthropic, async (u) => {
    textStart(u)
    await steadily(u, 20, 5_000, "y".repeat(30))
  }, { ...timeouts, streamTimeLimitMs: 300 })
  const chunks = parse(await f.response.text())
  const errors = errorsOf(chunks)
  assertEquals(errors.map((e) => e.code), ["stream_time_limit"])
  assert(String(errors[0].message).includes("Continue from the partial answer"))
  assert(deltas(chunks.filter((c) => !c.error)).some((d) => d.content))
  assert(f.audits.includes("stream_time_limit"))
  assert(!f.audits.includes("stream_failed"))
  const [settled] = f.settles() as number[]
  assert(settled > 40 && settled < RESERVED, String(settled))
  assertEquals(f.logs.find((l) => l.event === "timing")?.status, 504)
})

Deno.test("a client that stops reading is not mistaken for a silent upstream", async () => {
  const f = await fixture(anthropic, async (u) => {
    textStart(u)
    if (await steadily(u, 10, 300)) end(u, 50)
  }, timeouts)
  const reader = f.response.body!.getReader()
  await reader.read()
  await sleep(500) // longer than the idle timeout: upstream bytes back up behind the client
  let body = ""
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    body += new TextDecoder().decode(value)
  }
  assert(!f.audits.includes("stream_idle"), f.audits.join(","))
  assert(body.endsWith("data: [DONE]\n\n"), body.slice(-300))
})
