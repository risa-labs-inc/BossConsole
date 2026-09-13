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
  stallStream?: boolean
  removeToolsAfterPreflight?: boolean
  keyName?: string
  apiType?: "openai_chat" | "openai_responses"
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
    sessionUser: async (t) => t === "boss-session" ? "00000000-0000-0000-0000-000000000001" : null,
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : "upstream-secret",
    async rpc(name, params) {
      calls.push({ name, params })
      if (name === "boss_ai_lookup" && options.lookupDenied) return null
      if (name === "boss_ai_lookup" && options.lookupMisconfigured) {
        return { error: "misconfigured_allowance" }
      }
      if (name === "boss_ai_settle" && options.failAllSettlements) {
        throw new Error("database unavailable")
      }
      if (name === "boss_ai_settle" && options.failFirstSettlement && ++settlements === 1) {
        throw new Error("private database detail")
      }
      if (name === "boss_ai_catalog") {
        return [{ id: "boss-test", allowance: { day: { remaining: 1024 } } }]
      }
      if (name === "boss_ai_reserve" || name === "boss_ai_lookup") {
        return options.deny && name === "boss_ai_reserve" ? { error: options.deny } : {
          model: {
            id: "boss-test",
            upstream_model: "private-model",
            context_length: 4096,
            max_output_tokens: 512,
            capabilities: options.removeToolsAfterPreflight && name === "boss_ai_lookup"
              ? ["text", "tools"]
              : ["text"],
          },
          connection: {
            base_url: "https://upstream.example/v1",
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
  const request = (body: Obj, credential = token) =>
    new Request("https://api.example/functions/v1/boss-ai/v1/chat/completions", {
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
