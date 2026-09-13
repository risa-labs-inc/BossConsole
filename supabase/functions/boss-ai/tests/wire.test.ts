import { assertEquals, assertRejects, assertThrows } from "@std/assert"
import {
  completion,
  endpoint,
  events,
  Model,
  readJson,
  requestBody,
  StreamAdapter,
  upstreamKey,
  usage,
} from "../wire.ts"
import { bearer, HttpError } from "../auth.ts"

const model: Model = {
  id: "boss-test",
  upstream_model: "private-model",
  capabilities: ["text", "tools", "structured_output"],
  context_length: 4096,
  max_output_tokens: 512,
}

Deno.test("Responses tool results normalize text arrays and reject null or images", () => {
  const request = (content: unknown) =>
    requestBody(
      {
        messages: [{ role: "tool", tool_call_id: "call-1", content }],
      },
      model,
      "openai_responses",
    )
  assertEquals(request([{ type: "text", text: "first" }, { type: "text", text: "second" }]).input, [
    { type: "function_call_output", call_id: "call-1", output: "firstsecond" },
  ])
  assertThrows(() => request(null))
  assertThrows(() =>
    request([{ type: "image_url", image_url: { url: "data:image/png;base64,YQ==" } }])
  )
})

Deno.test("upstream secret lookup is safe without calling endpoint first", () => {
  let reads = 0
  for (const api_key_secret of ["BOSS_AI_SIGNING_SECRET", "SUPABASE_SERVICE_ROLE_KEY"]) {
    assertThrows(() =>
      upstreamKey(
        { base_url: "https://example.com", api_type: "openai_chat", api_key_secret },
        () => {
          reads++
          return "forbidden"
        },
      )
    )
  }
  assertEquals(reads, 0)
})
const input = { model: model.id, messages: [{ role: "user", content: "hello" }] }

Deno.test("stream options permit accounting usage but reject disabling it or extensions", () => {
  const input = { messages: [{ role: "user", content: "hello" }], stream: true }
  assertEquals(
    requestBody({ ...input, stream_options: { include_usage: true } }, model, "openai_chat")
      .stream_options,
    { include_usage: true },
  )
  for (
    const stream_options of [null, { include_usage: false }, { include_usage: "true" }, {
      provider: "override",
    }]
  ) {
    assertThrows(() => requestBody({ ...input, stream_options }, model, "openai_chat"))
  }
})

Deno.test("assistant tool calls may omit content without breaking the tool round", () => {
  const input = {
    messages: [{
      role: "assistant",
      tool_calls: [{ id: "call-1", type: "function", function: { name: "test", arguments: "{}" } }],
    }],
  }
  assertEquals(
    (requestBody(input, model, "openai_chat").messages as Record<string, unknown>[])[0].content,
    null,
  )
  assertEquals(
    (requestBody(input, model, "openai_responses").input as Record<string, unknown>[])[0].type,
    "function_call",
  )
  assertThrows(() => requestBody({ messages: [{ role: "user" }] }, model, "openai_chat"))
})

Deno.test("routing overrides and unadvertised capabilities are refused", () => {
  for (const field of ["base_url", "api_key", "provider", "n", "previous_response_id"]) {
    assertThrows(() => requestBody({ ...input, [field]: "override" }, model, "openai_chat"))
  }
  assertThrows(() => requestBody({ ...input, max_tokens: 513 }, model, "openai_chat"))
  assertThrows(() =>
    requestBody(
      {
        ...input,
        messages: [{
          role: "user",
          content: [{ type: "image_url", image_url: { url: "https://example.com/private" } }],
        }],
      },
      model,
      "openai_chat",
    )
  )
  assertThrows(() =>
    requestBody({ ...input, tools: [{ type: "web_search" }] }, model, "openai_responses")
  )
})

Deno.test("upstream mapping replaces model, disables storage, and bounds output", () => {
  const body = requestBody({ ...input, stream: true, max_tokens: 40 }, model, "openai_chat")
  assertEquals(body.model, "private-model")
  assertEquals(body.max_completion_tokens, 40)
  assertEquals(body.store, false)
  assertEquals(body.stream_options, { include_usage: true })
  assertEquals(body.temperature, undefined)
})

Deno.test("Responses adapter retains every tool round with call IDs", () => {
  const body = requestBody(
    {
      ...input,
      messages: [
        { role: "system", content: "system" },
        { role: "user", content: "question" },
        {
          role: "assistant",
          content: null,
          tool_calls: [{
            id: "call-1",
            type: "function",
            function: { name: "lookup", arguments: "{}" },
          }],
        },
        { role: "tool", tool_call_id: "call-1", content: "observation" },
        { role: "assistant", content: "answer" },
        { role: "user", content: "next question" },
      ],
      tools: [{ type: "function", function: { name: "lookup", parameters: { type: "object" } } }],
      tool_choice: { type: "function", function: { name: "lookup" } },
      max_tokens: 128,
    },
    model,
    "openai_responses",
  )
  assertEquals(body.input, [
    { role: "system", content: "system" },
    { role: "user", content: "question" },
    { type: "function_call", call_id: "call-1", name: "lookup", arguments: "{}" },
    { type: "function_call_output", call_id: "call-1", output: "observation" },
    { role: "assistant", content: "answer" },
    { role: "user", content: "next question" },
  ])
  assertEquals(body.tool_choice, { type: "function", name: "lookup" })
  assertEquals(body.max_output_tokens, 128)
  assertEquals(body.truncation, "disabled")
})

Deno.test("usage includes output reasoning once and rejects unknown accounting", () => {
  assertEquals(
    usage(
      { input_tokens: 12, output_tokens: 20, output_tokens_details: { reasoning_tokens: 15 } },
      true,
    )?.total_tokens,
    32,
  )
  assertEquals(usage({ prompt_tokens: -1, completion_tokens: 10 }), null)
  assertEquals(usage({}), null)
})

Deno.test("Responses completion translates text and function output", () => {
  const reply = completion(
    {
      status: "completed",
      output: [
        { type: "message", content: [{ type: "output_text", text: "hello" }] },
        { type: "function_call", call_id: "call", name: "lookup", arguments: "{}" },
      ],
      usage: { input_tokens: 1, output_tokens: 2 },
    },
    model.id,
    "openai_responses",
    "request",
  )
  assertEquals(reply.model, model.id)
  assertEquals(reply.usage, { prompt_tokens: 1, completion_tokens: 2, total_tokens: 3 })
  assertEquals((reply.choices as Record<string, unknown>[])[0].finish_reason, "tool_calls")
})

Deno.test("SSE parser handles byte splits, CRLF and multiline data", async () => {
  const bytes = new TextEncoder().encode(
    ': comment\r\n\r\ndata: {"text":\r\ndata: "日"}\r\n\r\ndata: [DONE]\n\n',
  )
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      for (const byte of bytes) c.enqueue(new Uint8Array([byte]))
      c.close()
    },
  })
  const result = []
  for await (const event of events(body)) result.push(event)
  assertEquals(result, ['{"text":\n"日"}', "[DONE]"])
  await assertRejects(async () => {
    for await (const _ of events(new Response("data: unfinished").body!)) { /* drain */ }
  })
})

Deno.test("Responses stream maps sparse output indexes to contiguous tool indexes", () => {
  const adapter = new StreamAdapter(model.id, "openai_responses", "request")
  const first = adapter.accept(
    JSON.stringify({
      type: "response.output_item.added",
      output_index: 3,
      item: { type: "function_call", call_id: "call", name: "lookup" },
    }),
  )
  assertEquals((first[0].choices as any)[0].delta.tool_calls[0].index, 0)
  const delta = adapter.accept(
    JSON.stringify({
      type: "response.function_call_arguments.delta",
      output_index: 3,
      delta: "{}",
    }),
  )
  assertEquals((delta[0].choices as any)[0].delta.tool_calls[0].function.arguments, "{}")
  adapter.accept(
    JSON.stringify({
      type: "response.completed",
      response: { usage: { input_tokens: 2, output_tokens: 4 } },
    }),
  )
  assertEquals(adapter.finished, true)
  assertEquals(adapter.tokens, 6)
  assertThrows(() =>
    new StreamAdapter(model.id, "openai_chat", "r").accept('{"error":{"message":"private prompt"}}')
  )
})

Deno.test("vision-enabled models accept inline images but refuse remote images and extensions", () => {
  const vision = { ...model, capabilities: [...model.capabilities, "vision"] }
  const image = (url: string, extra = {}) => ({
    ...input,
    messages: [{
      role: "user",
      content: [
        { type: "image_url", image_url: { url, detail: "low", ...extra } },
      ],
    }],
  })
  for (const type of ["openai_chat", "openai_responses"] as const) {
    requestBody(image("data:image/png;base64,YQ=="), vision, type)
    assertThrows(() => requestBody(image("https://example.com/private"), vision, type))
    assertThrows(() =>
      requestBody(image("data:image/png;base64,YQ==", { provider: "x" }), vision, type)
    )
    assertThrows(() =>
      requestBody(image("data:image/png;base64,YQ==", { detail: "invalid" }), vision, type)
    )
  }
})

Deno.test("function and format envelopes reject vendor extensions", () => {
  assertThrows(() =>
    requestBody(
      {
        ...input,
        tools: [{
          type: "function",
          function: {
            name: "lookup",
            provider: "override",
          },
        }],
      },
      model,
      "openai_chat",
    )
  )
  assertThrows(() =>
    requestBody(
      {
        ...input,
        response_format: {
          type: "json_object",
          endpoint: "https://attacker",
        },
      },
      model,
      "openai_chat",
    )
  )
})

Deno.test("upstream endpoints and bearer headers fail closed", () => {
  const connection = {
    base_url: "https://example.com/v1",
    api_type: "openai_chat" as const,
    api_key_secret: "BOSS_AI_TEST",
  }
  for (
    const url of [
      "http://example.com",
      "https://u:p@example.com",
      "https://example.com?key=x",
      "https://example.com#x",
    ]
  ) {
    assertThrows(() => endpoint({ ...connection, base_url: url }))
  }
  for (const value of ["Basic x", "Bearer ", `Bearer ${"x".repeat(16385)}`]) {
    assertThrows(() =>
      bearer(new Request("https://example.com", { headers: { authorization: value } }))
    )
  }
})

Deno.test("bounded JSON reads reject oversized bodies", async () => {
  const error = await assertRejects(
    () => readJson(new Response('{"data":"large"}').body, 4),
    HttpError,
  )
  assertEquals(error.status, 413)
})

Deno.test("all stream frames carry a stable created timestamp", () => {
  for (const type of ["openai_chat", "openai_responses"] as const) {
    const adapter = new StreamAdapter(model.id, type, "request")
    const event = type === "openai_chat"
      ? { choices: [{ delta: { content: "hello" } }] }
      : { type: "response.output_text.delta", delta: "hello" }
    const first = adapter.accept(JSON.stringify(event))[0]
    const second = adapter.accept(JSON.stringify(event))[0]
    assertEquals(typeof first.created, "number")
    assertEquals(first.created, second.created)
  }
})
