import { assert, assertEquals, assertThrows } from "@std/assert"
import {
  Connection,
  endpoint,
  Model,
  Obj,
  requestBody,
  StreamAdapter,
  upstreamHeaders,
} from "../wire.ts"
import { anthropicCompletion, anthropicUsage } from "../anthropic.ts"
import { HttpError } from "../auth.ts"

const model: Model = {
  id: "boss-test",
  upstream_model: "claude-opus-5-5",
  kind: "chat",
  capabilities: ["text", "tools", "vision", "structured_output"],
  context_length: 100000,
  max_output_tokens: 4096,
}
const BASE = "https://api.anthropic.com/v1"
const body = (input: Obj, m: Model = model) => requestBody(input, m, "anthropic_messages", BASE)
const png = "data:image/png;base64,iVBORw0KGgo="
const tool = (name: string) => ({
  type: "function",
  function: { name, description: `${name} tool`, parameters: { type: "object", properties: {} } },
})
const EPH = { type: "ephemeral" }

Deno.test("anthropic: endpoint is /messages and auth is x-api-key, never Bearer", () => {
  const connection: Connection = {
    base_url: BASE,
    api_type: "anthropic_messages",
    api_key_secret: "BOSS_AI_ANTHROPIC",
  }
  assertEquals(endpoint(connection), "https://api.anthropic.com/v1/messages")
  assertEquals(upstreamHeaders(connection, "k"), {
    "x-api-key": "k",
    "anthropic-version": "2023-06-01",
    "Content-Type": "application/json",
  })
  assertEquals(upstreamHeaders(connection, "k", false).Authorization, "Bearer k")
  assertEquals(
    upstreamHeaders({ ...connection, api_type: "openai_chat" }, "k").Authorization,
    "Bearer k",
  )
})

Deno.test("anthropic: system, developer, images and max_tokens translate", () => {
  const out = body({
    max_tokens: 100,
    messages: [
      { role: "system", content: "be brief" },
      { role: "developer", content: [{ type: "text", text: "rule two" }] },
      {
        role: "user",
        content: [{ type: "text", text: "look" }, { type: "image_url", image_url: { url: png } }],
      },
    ],
  })
  assertEquals(out.model, "claude-opus-5-5")
  assertEquals(out.max_tokens, 100)
  assertEquals(out.stream, false)
  assertEquals((out.system as Obj[]).map((b) => b.text), ["be brief", "rule two"])
  assertEquals(
    (out.messages as Obj[])[0].content,
    [
      { type: "text", text: "look" },
      { type: "image", source: { type: "base64", media_type: "image/png", data: "iVBORw0KGgo=" } },
      // The final user message carries a breakpoint on its last block (checked in the cache test).
    ].map((b, i, all) => i === all.length - 1 ? { ...b, cache_control: EPH } : b),
  )
  // max_tokens is always set, from the existing computation.
  assertEquals(body({ messages: [{ role: "user", content: "hi" }] }).max_tokens, 2000)
  assert(!("store" in out) && !("provider" in out) && !("thinking" in out))
})

Deno.test("anthropic: tools, tool_choice and parallel_tool_calls map", () => {
  const messages = [{ role: "user", content: "hi" }]
  const tools = [tool("a"), {
    type: "function",
    function: { name: "b", parameters: { type: "object", properties: { x: { type: "string" } } } },
  }]
  const out = body({ messages, tools })
  assertEquals((out.tools as Obj[]).map((t) => t.name), ["a", "b"])
  assertEquals((out.tools as Obj[])[0].input_schema, { type: "object", properties: {} })
  assertEquals((out.tools as Obj[])[0].description, "a tool")
  assert(!("function" in (out.tools as Obj[])[0]))
  assert(!("tool_choice" in out))
  const choice = (tool_choice: unknown, extra: Obj = {}) =>
    body({ messages, tools, tool_choice, ...extra }).tool_choice
  assertEquals(choice("auto"), { type: "auto" })
  assertEquals(choice("none"), { type: "none" })
  // Forced choices would 400 on current models: they degrade to auto.
  assertEquals(choice("required"), { type: "auto" })
  assertEquals(choice({ type: "function", function: { name: "b" } }), { type: "auto" })
  assertEquals(choice("required", { parallel_tool_calls: false }), {
    type: "auto",
    disable_parallel_tool_use: true,
  })
  assertEquals(choice("none", { parallel_tool_calls: false }), { type: "none" })
  assertEquals(body({ messages, tools, parallel_tool_calls: false }).tool_choice, {
    type: "auto",
    disable_parallel_tool_use: true,
  })
})

Deno.test("anthropic: tool calls become tool_use, results merge and roles alternate", () => {
  const out = body({
    messages: [
      { role: "user", content: "weather in two cities" },
      {
        role: "assistant",
        content: "checking",
        tool_calls: [
          { id: "call.1", type: "function", function: { name: "w", arguments: '{"c":"A"}' } },
          { id: "call_2", type: "function", function: { name: "w", arguments: "" } },
        ],
      },
      { role: "tool", tool_call_id: "call.1", content: "sunny" },
      { role: "tool", tool_call_id: "call_2", content: [{ type: "text", text: "rain" }] },
      { role: "user", content: "thanks" },
      { role: "user", content: "and tomorrow?" },
      { role: "assistant", content: "one" },
      { role: "assistant", content: "two" },
      { role: "user", content: "" },
    ],
    tools: [tool("w")],
  })
  const m = out.messages as Obj[]
  assertEquals(m.map((x) => x.role), ["user", "assistant", "user", "assistant"])
  const strip = (blocks: unknown) => (blocks as Obj[]).map(({ cache_control: _, ...rest }) => rest)
  assertEquals(strip(m[1].content), [
    { type: "text", text: "checking" },
    { type: "tool_use", id: "call_1", name: "w", input: { c: "A" } },
    { type: "tool_use", id: "call_2", name: "w", input: {} },
  ])
  // tool_results (ids sanitized identically) lead the merged user turn, then the user text.
  assertEquals(strip(m[2].content), [
    { type: "tool_result", tool_use_id: "call_1", content: "sunny" },
    { type: "tool_result", tool_use_id: "call_2", content: "rain" },
    { type: "text", text: "thanks" },
    { type: "text", text: "and tomorrow?" },
  ])
  assertEquals(strip(m[3].content), [{ type: "text", text: "one" }, { type: "text", text: "two" }])
})

Deno.test("anthropic: invalid tool-call JSON is a 400, not a crash", () => {
  for (const args of ["{nope", "[1]", "3"]) {
    const e = assertThrows(() =>
      body({
        messages: [
          { role: "user", content: "x" },
          {
            role: "assistant",
            content: null,
            tool_calls: [{ id: "a", type: "function", function: { name: "t", arguments: args } }],
          },
        ],
        tools: [tool("t")],
      })
    )
    assert(e instanceof HttpError)
    assertEquals(e.status, 400)
  }
  assertThrows(() => body({ messages: [{ role: "system", content: "only system" }] }), HttpError)
})

Deno.test("anthropic: structured output maps json_schema; json_object and sampling params", () => {
  const messages = [{ role: "user", content: "hi" }]
  const schema = {
    type: "object",
    properties: { a: { type: "string" } },
    additionalProperties: false,
  }
  const out = body({
    messages,
    temperature: 0.2,
    top_p: 0.5,
    response_format: { type: "json_schema", json_schema: { name: "r", schema, strict: true } },
  })
  assertEquals(out.output_config, { format: { type: "json_schema", schema } })
  // Current Claude models reject non-default sampling: accepted from clients, never forwarded.
  assert(!("temperature" in out) && !("top_p" in out))
  assert(!("output_config" in body({ messages, response_format: { type: "text" } })))
  const e = assertThrows(() => body({ messages, response_format: { type: "json_object" } }))
  assert(e instanceof HttpError)
  assertEquals(e.status, 400)
  assert(e.message.includes("json_object"))
})

const toolLoop = [
  { role: "system", content: "sys" },
  { role: "user", content: "first" },
  {
    role: "assistant",
    content: null,
    tool_calls: [{ id: "t1", type: "function", function: { name: "a", arguments: "{}" } }],
  },
  { role: "tool", tool_call_id: "t1", content: "r1" },
]
const marked = (out: Obj) => {
  const hits: string[] = []
  const visit = (path: string, v: unknown) => {
    if (Array.isArray(v)) v.forEach((x, i) => visit(`${path}[${i}]`, x))
    else if (v && typeof v === "object") {
      if ((v as Obj).cache_control) hits.push(path)
      for (const [k, x] of Object.entries(v)) if (k !== "cache_control") visit(`${path}.${k}`, x)
    }
  }
  for (const k of ["tools", "system", "messages"]) visit(k, out[k])
  return hits
}

Deno.test("anthropic: four cache breakpoints, always, on tools, system and the two message points", () => {
  const out = body({ messages: toolLoop, tools: [tool("a"), tool("b")] })
  // Messages: user, assistant(tool_use), user(tool_result). Final = [2]; before the latest user turn = [1].
  assertEquals(marked(out), [
    "tools[1]",
    "system[0]",
    "messages[1].content[0]",
    "messages[2].content[0]",
  ])
  assertEquals(marked(out).length, 4)
  // Earlier turns stay untouched, and the caller's input is not mutated.
  assert(!JSON.stringify((out.messages as Obj[])[0]).includes("cache_control"))
  assertEquals(toolLoop[3], { role: "tool", tool_call_id: "t1", content: "r1" })
})

Deno.test("anthropic: breakpoints dedupe and skip what is absent", () => {
  // One user message: no tools, no earlier turn -> system and the final block only.
  assertEquals(
    marked(body({ messages: [{ role: "system", content: "s" }, { role: "user", content: "u" }] })),
    ["system[0]", "messages[0].content[0]"],
  )
  // The only user turn is first, so nothing precedes it; the final message carries the breakpoint.
  assertEquals(
    marked(body({
      messages: [{ role: "user", content: "u" }, { role: "assistant", content: "a" }],
      tools: [tool("a")],
    })),
    ["tools[0]", "messages[1].content[0]"],
  )
  // Long conversations never exceed four.
  const many = Array.from({ length: 40 }, (_, i) => ({
    role: i % 2 ? "assistant" : "user",
    content: `m${i}`,
  })).concat([{ role: "user", content: "last" }])
  assert(
    marked(body({ messages: [{ role: "system", content: "s" }, ...many], tools: [tool("a")] }))
      .length <= 4,
  )
})

Deno.test("anthropic: usage maps input + cache reads + cache writes", () => {
  assertEquals(
    anthropicUsage({
      input_tokens: 10,
      cache_read_input_tokens: 1000,
      cache_creation_input_tokens: 200,
      output_tokens: 5,
    }),
    {
      prompt_tokens: 1210,
      completion_tokens: 5,
      total_tokens: 1215,
      prompt_tokens_details: { cached_tokens: 1000, cache_write_tokens: 200 },
    },
  )
  assertEquals(
    anthropicUsage({ input_tokens: 3, output_tokens: 4, cache_creation_input_tokens: null }),
    {
      prompt_tokens: 3,
      completion_tokens: 4,
      total_tokens: 7,
      prompt_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
    },
  )
  for (
    const bad of [null, "x", {}, { input_tokens: 1 }, { input_tokens: -1, output_tokens: 1 }, {
      input_tokens: 1,
      output_tokens: 1.5,
    }, { input_tokens: 1, output_tokens: 1, cache_read_input_tokens: "7" }]
  ) assertEquals(anthropicUsage(bad), null)
})

Deno.test("anthropic: non-stream responses become chat.completion", () => {
  const message = (content: unknown[], stop_reason: string) => ({
    id: "msg_1",
    type: "message",
    role: "assistant",
    content,
    stop_reason,
    usage: { input_tokens: 5, cache_read_input_tokens: 10, output_tokens: 3 },
  })
  const text = anthropicCompletion(
    message([{ type: "thinking", thinking: "", signature: "s" }, { type: "text", text: "Hi " }, {
      type: "text",
      text: "there",
    }], "end_turn"),
    "boss-test",
    "req-1",
  )
  assertEquals(text.object, "chat.completion")
  assertEquals(text.id, "req-1")
  assertEquals((text.choices as Obj[])[0], {
    index: 0,
    message: { role: "assistant", content: "Hi there" },
    finish_reason: "stop",
  })
  assertEquals((text.usage as Obj).total_tokens, 18)
  const calls = anthropicCompletion(
    message([{ type: "tool_use", id: "toolu_1", name: "w", input: { c: "A" } }], "tool_use"),
    "boss-test",
    "req-1",
  )
  assertEquals((calls.choices as Obj[])[0], {
    index: 0,
    message: {
      role: "assistant",
      content: null,
      tool_calls: [{
        id: "toolu_1",
        type: "function",
        function: { name: "w", arguments: '{"c":"A"}' },
      }],
    },
    finish_reason: "tool_calls",
  })
  const reason = (stop: string) =>
    (anthropicCompletion(message([], stop), "m", "i").choices as Obj[])[0].finish_reason
  assertEquals(
    ["end_turn", "stop_sequence", "max_tokens", "tool_use", "refusal"].map(reason),
    ["stop", "stop", "length", "tool_calls", "content_filter"],
  )
  assertThrows(
    () => anthropicCompletion({ type: "error", error: { type: "x" } }, "m", "i"),
    HttpError,
  )
})

const sse = (events: Obj[]) => events.map((e) => JSON.stringify(e))

Deno.test("anthropic: stream events become chunks with stable tool indexes and a usage chunk", () => {
  const adapter = new StreamAdapter("boss-test", "anthropic_messages", "req-1")
  const out: Obj[] = []
  for (
    const frame of sse([
      {
        type: "message_start",
        message: { usage: { input_tokens: 7, cache_read_input_tokens: 100, output_tokens: 1 } },
      },
      { type: "ping" },
      { type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "" } },
      { type: "content_block_delta", index: 0, delta: { type: "thinking_delta", thinking: "hmm" } },
      { type: "content_block_stop", index: 0 },
      { type: "content_block_start", index: 1, content_block: { type: "text", text: "" } },
      { type: "content_block_delta", index: 1, delta: { type: "text_delta", text: "Let me " } },
      { type: "content_block_delta", index: 1, delta: { type: "text_delta", text: "check." } },
      { type: "content_block_stop", index: 1 },
      {
        type: "content_block_start",
        index: 2,
        content_block: { type: "tool_use", id: "toolu_a", name: "w", input: {} },
      },
      {
        type: "content_block_delta",
        index: 2,
        delta: { type: "input_json_delta", partial_json: '{"c":' },
      },
      {
        type: "content_block_delta",
        index: 2,
        delta: { type: "input_json_delta", partial_json: '"A"}' },
      },
      { type: "content_block_stop", index: 2 },
      {
        type: "content_block_start",
        index: 3,
        content_block: { type: "tool_use", id: "toolu_b", name: "w", input: {} },
      },
      {
        type: "content_block_delta",
        index: 3,
        delta: { type: "input_json_delta", partial_json: "{}" },
      },
      { type: "message_delta", delta: { stop_reason: "tool_use" }, usage: { output_tokens: 42 } },
      { type: "message_stop" },
    ])
  ) {
    assertEquals(adapter.finished, false)
    out.push(...adapter.accept(frame))
  }
  assert(adapter.finished)
  assertEquals(adapter.tokens, 7 + 100 + 42)
  const choices = out.flatMap((c) => c.choices as Obj[])
  const deltas = choices.map((c) => c.delta as Obj)
  assertEquals(
    deltas.filter((d) => typeof d.content === "string" && d.content).map((d) => d.content),
    [
      "Let me ",
      "check.",
    ],
  )
  const toolChunks = deltas.flatMap((d) => (d.tool_calls as Obj[] | undefined) ?? [])
  assertEquals(toolChunks, [
    { index: 0, id: "toolu_a", type: "function", function: { name: "w", arguments: "" } },
    { index: 0, function: { arguments: '{"c":' } },
    { index: 0, function: { arguments: '"A"}' } },
    { index: 1, id: "toolu_b", type: "function", function: { name: "w", arguments: "" } },
    { index: 1, function: { arguments: "{}" } },
  ])
  // Thinking never reaches the client; the finish chunk precedes the usage chunk.
  assert(!JSON.stringify(out).includes("hmm"))
  const finish = choices.filter((c) => c.finish_reason)
  assertEquals(finish.map((c) => c.finish_reason), ["tool_calls"])
  const last = out.at(-1)!
  assertEquals(last.choices, [])
  assertEquals(last.usage, {
    prompt_tokens: 107,
    completion_tokens: 42,
    total_tokens: 149,
    prompt_tokens_details: { cached_tokens: 100, cache_write_tokens: 0 },
  })
  assertEquals(adapter.usage, last.usage)
})

Deno.test("anthropic: stream errors and orphan tool deltas fail the stream", () => {
  const adapter = new StreamAdapter("m", "anthropic_messages", "i")
  assertThrows(() => adapter.accept('{"type":"error","error":{"type":"overloaded_error"}}'))
  assertThrows(() =>
    adapter.accept(
      '{"type":"content_block_delta","index":9,"delta":{"type":"input_json_delta","partial_json":"{"}}',
    )
  )
  // No message_delta means no measured usage: settlement keeps the reservation.
  const bare = new StreamAdapter("m", "anthropic_messages", "i")
  bare.accept('{"type":"message_start","message":{"usage":{"input_tokens":5,"output_tokens":1}}}')
  bare.accept('{"type":"message_stop"}')
  assertEquals(bare.finished, true)
  assertEquals(bare.tokens, null)
})

Deno.test("anthropic: only the leading system run is top-level, so a per-turn system note keeps the prefix", () => {
  const turn = (note: string) =>
    body({
      messages: [
        { role: "system", content: "stable" },
        { role: "developer", content: "also stable" },
        { role: "user", content: "q1" },
        { role: "assistant", content: "a1" },
        { role: "user", content: "q2" },
        { role: "system", content: note },
      ],
    })
  const one = turn("time is 10:00"), two = turn("time is 10:01")
  assertEquals(JSON.stringify(one.system), JSON.stringify(two.system))
  assertEquals((one.system as Obj[]).map((b) => b.text), ["stable", "also stable"])
  assertEquals(
    JSON.stringify((one.messages as Obj[]).slice(0, 3)),
    JSON.stringify((two.messages as Obj[]).slice(0, 3)),
  )
  assert(!JSON.stringify(one.system).includes("10:00"))
})

Deno.test("anthropic: a later system message is a mid-conversation message or a folded note", () => {
  const roles = (messages: Obj[]) =>
    (body({ messages }).messages as Obj[]).map((
      m,
    ) => [m.role, (m.content as Obj[]).map((b) => b.text)])
  // After a user turn, last: kept in place as a system message.
  assertEquals(roles([{ role: "user", content: "q" }, { role: "system", content: "n" }]), [
    ["user", ["q"]],
    ["system", ["n"]],
  ])
  // After a user turn, before an assistant turn: also in place; consecutive ones merge.
  assertEquals(
    roles([
      { role: "user", content: "q" },
      { role: "system", content: "n1" },
      { role: "developer", content: "n2" },
      { role: "assistant", content: "a" },
    ]),
    [["user", ["q"]], ["system", ["n1", "n2"]], ["assistant", ["a"]]],
  )
  // Before a user turn: folded into the user turns with a prefix; alternation holds.
  assertEquals(
    roles([
      { role: "user", content: "q" },
      { role: "system", content: "n" },
      { role: "user", content: "q2" },
    ]),
    [["user", ["q", "System note: n", "q2"]]],
  )
  // After an assistant turn: folded into the next user turn.
  assertEquals(
    roles([
      { role: "user", content: "q" },
      { role: "assistant", content: "a" },
      { role: "system", content: "n" },
      { role: "user", content: "q2" },
    ]),
    [["user", ["q"]], ["assistant", ["a"]], ["user", ["System note: n", "q2"]]],
  )
  // Between an assistant turn and a tool result: the tool_result still leads the user turn.
  const folded = body({
    tools: [tool("t")],
    messages: [
      { role: "user", content: "q" },
      {
        role: "assistant",
        content: null,
        tool_calls: [{ id: "c", type: "function", function: { name: "t", arguments: "{}" } }],
      },
      { role: "system", content: "n" },
      { role: "tool", tool_call_id: "c", content: "r" },
    ],
  }).messages as Obj[]
  assertEquals(folded.map((m) => m.role), ["user", "assistant", "user"])
  assertEquals((folded[2].content as Obj[]).map((b) => b.type), ["tool_result", "text"])
})

Deno.test("anthropic: breakpoints skip a trailing system message and land on the turn before it", () => {
  const out = body({
    messages: [
      { role: "system", content: "s" },
      { role: "user", content: "q1" },
      { role: "assistant", content: "a1" },
      { role: "user", content: "q2" },
      { role: "system", content: "volatile" },
    ],
  })
  assertEquals(marked(out), ["system[0]", "messages[1].content[0]", "messages[2].content[0]"])
  assertEquals((out.messages as Obj[]).map((m) => m.role), ["user", "assistant", "user", "system"])
  assert(!JSON.stringify((out.messages as Obj[])[3]).includes("cache_control"))
})
