import { assert, assertEquals, assertRejects, assertThrows } from "@std/assert"
import {
  completion,
  endpoint,
  events,
  isUpstreamContextError,
  LIMITS,
  Model,
  Obj,
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
  // An over-cap output request is clamped, not refused; zero and non-integers still are.
  assertEquals(
    requestBody({ ...input, max_tokens: 513 }, model, "openai_chat").max_completion_tokens,
    512,
  )
  for (const bad of [0, -1, 1.5, "100"]) {
    assertThrows(() => requestBody({ ...input, max_tokens: bad }, model, "openai_chat"))
  }
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

// BossConsole#1251: tool call arguments must be parseable JSON, tool
// descriptions must be length-bounded, and a single message must not carry
// an unbounded number of tool_calls.

Deno.test("tool call arguments must be parseable JSON (BossConsole#1251)", () => {
  const input = {
    messages: [{
      role: "assistant",
      tool_calls: [{
        id: "call-1",
        type: "function",
        function: { name: "lookup", arguments: "not-json{{{" },
      }],
    }],
  }
  assertThrows(
    () => requestBody(input, model, "openai_chat"),
    HttpError,
  )
  assertThrows(
    () => requestBody(input, model, "openai_responses"),
    HttpError,
  )
})

Deno.test("empty and object tool arguments replay, but JSON primitives do not", () => {
  for (const args of ["", "{}", '{"id":1}']) {
    const input = {
      messages: [{
        role: "assistant",
        tool_calls: [{
          id: "call-1",
          type: "function",
          function: { name: "lookup", arguments: args },
        }],
      }],
    }
    requestBody(input, model, "openai_chat")
  }
  for (const args of ["null", "5", "[]"]) {
    const input = {
      messages: [{
        role: "assistant",
        tool_calls: [{
          id: "call-1",
          type: "function",
          function: { name: "lookup", arguments: args },
        }],
      }],
    }
    assertThrows(() => requestBody(input, model, "openai_chat"), HttpError)
  }
})

const roomy: Model = { ...model, context_length: 10_000_000, max_output_tokens: 512 }
const deepSchema = (depth: number) => {
  let deep: unknown = {}
  for (let i = 0; i < depth; i++) deep = { nested: deep }
  return deep
}

Deno.test("structured-output schemas share bounded schema work limits", () => {
  const input = (schema: unknown) => ({
    messages: [{ role: "user", content: "hi" }],
    response_format: { type: "json_schema", json_schema: { name: "answer", schema } },
  })
  requestBody(input({ type: "object" }), roomy, "openai_chat")
  requestBody(input(deepSchema(LIMITS.schemaDepth - 1)), roomy, "openai_chat")
  for (
    const schema of [
      deepSchema(LIMITS.schemaDepth + 2),
      { enum: Array(LIMITS.schemaNodes + 1).fill("x") },
      { description: "x".repeat(LIMITS.schemaLength) },
    ]
  ) {
    assertThrows(() => requestBody(input(schema), roomy, "openai_chat"), HttpError)
  }
})

Deno.test("agent-sized tool lists, descriptions, schemas and transcripts are accepted", () => {
  const tool = (parameters: unknown, description = "x".repeat(LIMITS.toolDescription)) => ({
    type: "function",
    function: { name: "lookup", description, parameters },
  })
  const input = (tools: unknown[]) => ({ messages: [{ role: "user", content: "hi" }], tools })
  const exactSchema = {
    description: "x".repeat(LIMITS.schemaLength - JSON.stringify({ description: "" }).length),
  }
  requestBody(input([tool(exactSchema)]), roomy, "openai_chat")
  requestBody(
    input(Array.from({ length: LIMITS.tools }, () => tool({}, "d"))),
    roomy,
    "openai_chat",
  )
  const many = Array.from({ length: LIMITS.messages }, (_, i) => ({
    role: i % 2 ? "assistant" : "user",
    content: `turn ${i}`,
  }))
  requestBody({ messages: many }, roomy, "openai_chat")
  requestBody(
    {
      messages: [{
        role: "assistant",
        tool_calls: Array.from({ length: LIMITS.toolCallsPerMessage }, (_, i) => ({
          id: `call-${i}`,
          type: "function",
          function: { name: "lookup", arguments: "{}" },
        })),
      }],
    },
    roomy,
    "openai_chat",
  )
})

Deno.test("size limits still have a ceiling one past each bound", () => {
  const tool = (parameters: unknown, description = "d") => ({
    type: "function",
    function: { name: "lookup", description, parameters },
  })
  const input = (tools: unknown[]) => ({ messages: [{ role: "user", content: "hi" }], tools })
  const refused = (body: Obj) => {
    const error = assertThrows(() => requestBody(body, roomy, "openai_chat"), HttpError)
    assertEquals(error.status, 400)
  }
  refused(input(Array.from({ length: LIMITS.tools + 1 }, () => tool({}))))
  refused(input([tool({}, "x".repeat(LIMITS.toolDescription + 1))]))
  refused(input([tool({ description: "x".repeat(LIMITS.schemaLength) })]))
  refused(input([tool(deepSchema(LIMITS.schemaDepth + 2))]))
  refused(input([tool({ enum: Array(LIMITS.schemaNodes + 1).fill("x") })]))
  refused({
    messages: Array.from({ length: LIMITS.messages + 1 }, () => ({ role: "user", content: "x" })),
  })
  refused({
    messages: [{
      role: "assistant",
      tool_calls: Array.from({ length: LIMITS.toolCallsPerMessage + 1 }, (_, i) => ({
        id: `call-${i}`,
        type: "function",
        function: { name: "lookup", arguments: "{}" },
      })),
    }],
  })
})

Deno.test("a 216-tool agent request like Fluck's is accepted (the v72 128-tool cap refused it)", () => {
  const tools = Array.from({ length: 216 }, (_, i) => ({
    type: "function",
    function: {
      name: `mcp__boss__tool_${i}`,
      description: "Does one thing. ".repeat(100),
      parameters: { type: "object", properties: { id: { type: "string" } }, required: ["id"] },
    },
  }))
  const body = requestBody(
    {
      model: "optimist",
      max_tokens: 4096,
      stream: true,
      stream_options: { include_usage: true },
      tools,
      messages: [{ role: "system", content: "s".repeat(11_896) }, { role: "user", content: "hi" }],
    },
    { ...model, context_length: 131_072, max_output_tokens: 32_768 },
    "openai_chat",
  )
  assertEquals((body.tools as unknown[]).length, 216)
  assertEquals(body.max_completion_tokens, 4096)
})

Deno.test("standard OpenAI agent fields are accepted, validated and forwarded", () => {
  const reasoning: Model = { ...model, capabilities: [...model.capabilities, "reasoning"] }
  const chat = requestBody(
    {
      ...input,
      reasoning_effort: "high",
      stop: ["END"],
      seed: 7,
      presence_penalty: 0.5,
      frequency_penalty: -0.5,
    },
    reasoning,
    "openai_chat",
  )
  assertEquals(chat.reasoning_effort, "high")
  assertEquals(chat.stop, ["END"])
  assertEquals(chat.seed, 7)
  assertEquals(chat.presence_penalty, 0.5)
  assertEquals(chat.frequency_penalty, -0.5)
  assertEquals(requestBody({ ...input, stop: "END" }, model, "openai_chat").stop, ["END"])
  assertEquals(
    requestBody({ ...input, reasoning_effort: "low" }, reasoning, "openai_responses").reasoning,
    { effort: "low" },
  )
  for (
    const [extra, m, type] of [
      [{ reasoning_effort: "high" }, model, "openai_chat"],
      [{ reasoning_effort: "max" }, reasoning, "openai_chat"],
      [{ stop: [] }, model, "openai_chat"],
      [{ stop: ["a", "b", "c", "d", "e"] }, model, "openai_chat"],
      [{ stop: [""] }, model, "openai_chat"],
      [{ stop: 1 }, model, "openai_chat"],
      [{ stop: "END" }, model, "openai_responses"],
      [{ seed: 1.5 }, model, "openai_chat"],
      [{ seed: 1 }, model, "openai_responses"],
      [{ presence_penalty: 3 }, model, "openai_chat"],
      [{ frequency_penalty: "1" }, model, "openai_chat"],
      [{ frequency_penalty: NaN }, model, "openai_chat"],
    ] as const
  ) {
    assertThrows(() => requestBody({ ...input, ...extra }, m, type), HttpError)
  }
})

Deno.test("prompt size within the body cap is left to the model's own context check", () => {
  // No local token estimate: images and padded text tokenize far below their JSON size.
  const small: Model = { ...model, context_length: 1_000 }
  requestBody({ messages: [{ role: "user", content: " ".repeat(200_000) }] }, small, "openai_chat")
})

Deno.test("upstream context-length rejections are recognised without leaking their text", async () => {
  const res = (status: number, body: string) => new Response(body, { status })
  for (
    const body of [
      `{"error":{"message":"This endpoint's maximum context length is 32768 tokens."}}`,
      `{"error":{"code":"context_length_exceeded"}}`,
      `{"error":{"message":"prompt is too long: 40000 tokens > 32768 maximum"}}`,
      `{"error":{"message":"This model's maximum context length is 32768 tokens. However, you requested 40000 tokens"}}`,
      `{"error":{"message":"Input exceeds the context window of this model"}}`,
    ]
  ) assert(await isUpstreamContextError(res(400, body)))
  // Echoed user text and non-context limits are not mistaken for an overflow.
  for (
    const body of [
      `{"error":"bad tool schema"}`,
      `{"error":{"message":"invalid schema for tool: 'keeps the context window small'"}}`,
      `{"error":{"message":"max_tokens is too large: 99999. This model supports at most 4096 completion tokens"}}`,
      `{"error":{"message":"too many tokens in stop sequence"}}`,
    ]
  ) assertEquals(await isUpstreamContextError(res(400, body)), false)
  assertEquals(await isUpstreamContextError(res(500, "maximum context length")), false)
  assertEquals(await isUpstreamContextError(res(403, "context length")), false)
})
