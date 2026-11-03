import { assert, assertEquals, assertThrows } from "@std/assert"
import { Connection, Model, Obj, requestBody, StreamAdapter, upstreamHeaders } from "../wire.ts"
import { anthropicCompletion, CARRY_FORMAT } from "../anthropic.ts"
import { HttpError } from "../auth.ts"

const model: Model = {
  id: "boss-test",
  upstream_model: "claude-sonnet-5-5",
  kind: "chat",
  capabilities: ["text", "tools", "vision", "structured_output"],
  context_length: 100000,
  max_output_tokens: 4096,
}
const BASE = "https://api.anthropic.com/v1"
const anthropic: Connection = {
  base_url: BASE,
  api_type: "anthropic_messages",
  api_key_secret: "K",
}
const body = (input: Obj, m: Model = model) => requestBody(input, m, "anthropic_messages", BASE)
const tool = { type: "function", function: { name: "look", parameters: { type: "object" } } }

// One tool round as the model answered it: signed thinking, a sentence, a tool call.
const ROUND = [
  { type: "thinking", thinking: "check the screen first", signature: "sig-1" },
  { type: "text", text: "Looking." },
  { type: "tool_use", id: "toolu_1", name: "look", input: { at: "top" } },
]
const carry = (blocks: unknown[] = ROUND) => [{
  type: "reasoning.encrypted",
  format: CARRY_FORMAT,
  index: 0,
  data: JSON.stringify(blocks),
}]
const history = (assistant: Obj) => ({
  tools: [tool],
  messages: [
    { role: "user", content: "what is on top?" },
    {
      role: "assistant",
      content: "Looking.",
      tool_calls: [{
        id: "toolu_1",
        type: "function",
        function: { name: "look", arguments: '{"at":"top"}' },
      }],
      ...assistant,
    },
    { role: "tool", tool_call_id: "toolu_1", content: "a title bar" },
  ],
})
const assistantTurn = (out: Obj) => (out.messages as Obj[])[1].content as Obj[]
const plain = (blocks: Obj[]) => blocks.map(({ cache_control: _, ...b }) => b)

Deno.test("thinking: a tool round's reply carries its content once, signed thinking only", () => {
  const reply = anthropicCompletion(
    {
      type: "message",
      content: [{ type: "thinking", thinking: "", signature: "" }, ...ROUND],
      stop_reason: "tool_use",
      usage: { input_tokens: 1, output_tokens: 1 },
    },
    "boss-test",
    "r",
  )
  const message = ((reply.choices as Obj[])[0].message) as Obj
  // The unsigned block would be rejected on replay, so it is left out.
  assertEquals(message.reasoning_details, carry())
  // A final answer is never replayed, so it carries nothing.
  const final = anthropicCompletion(
    {
      type: "message",
      content: [ROUND[0], { type: "text", text: "done" }],
      stop_reason: "end_turn",
      usage: { input_tokens: 1, output_tokens: 1 },
    },
    "boss-test",
    "r",
  )
  assert(!("reasoning_details" in ((final.choices as Obj[])[0].message as Obj)))
})

Deno.test("thinking: a streamed tool round emits the carry before the finish chunk", () => {
  const adapter = new StreamAdapter("boss-test", "anthropic_messages", "r")
  const events: Obj[] = [
    { type: "message_start", message: { usage: { input_tokens: 1, output_tokens: 1 } } },
    { type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "" } },
    {
      type: "content_block_delta",
      index: 0,
      delta: { type: "thinking_delta", thinking: "check " },
    },
    {
      type: "content_block_delta",
      index: 0,
      delta: { type: "thinking_delta", thinking: "the screen first" },
    },
    {
      type: "content_block_delta",
      index: 0,
      delta: { type: "signature_delta", signature: "sig-1" },
    },
    { type: "content_block_stop", index: 0 },
    { type: "content_block_start", index: 1, content_block: { type: "text", text: "" } },
    { type: "content_block_delta", index: 1, delta: { type: "text_delta", text: "Looking." } },
    { type: "content_block_stop", index: 1 },
    {
      type: "content_block_start",
      index: 2,
      content_block: { type: "tool_use", id: "toolu_1", name: "look", input: {} },
    },
    {
      type: "content_block_delta",
      index: 2,
      delta: { type: "input_json_delta", partial_json: '{"at":' },
    },
    {
      type: "content_block_delta",
      index: 2,
      delta: { type: "input_json_delta", partial_json: '"top"}' },
    },
    { type: "content_block_stop", index: 2 },
    { type: "message_delta", delta: { stop_reason: "tool_use" }, usage: { output_tokens: 9 } },
    { type: "message_stop" },
  ]
  const chunks = events.flatMap((e) => adapter.accept(JSON.stringify(e)) as Obj[])
  const deltas = chunks.map((c) => ((c.choices as Obj[])[0]?.delta ?? {}) as Obj)
  const at = deltas.findIndex((d) => d.reasoning_details !== undefined)
  assertEquals(deltas[at].reasoning_details, carry())
  const finish = chunks.findIndex((c) => (c.choices as Obj[])[0]?.finish_reason === "tool_calls")
  assert(at >= 0 && at < finish)
})

Deno.test("thinking: a carry handed back unchanged is replayed verbatim, drop-not-fail", () => {
  const out = body(history({ reasoning_details: carry() }))
  assertEquals(plain(assistantTurn(out)), ROUND)
  assertEquals(out.thinking, {
    type: "adaptive",
    display: "summarized",
    block_binding: { prefix_mismatch_behavior: "drop_block" },
  })
  assertEquals(
    upstreamHeaders(anthropic, "k", true, out)["anthropic-beta"],
    "thinking-binding-controls-2026-08-01",
  )
  // Without replayed thinking, no binding and no beta header.
  const bare = body(history({}))
  assertEquals(bare.thinking, { type: "adaptive", display: "summarized" })
  assert(!("anthropic-beta" in upstreamHeaders(anthropic, "k", true, bare)))
})

Deno.test("thinking: a carry that no longer matches the turn is ignored", () => {
  const rebuilt = [
    { type: "text", text: "Looking." },
    { type: "tool_use", id: "toolu_1", name: "look", input: { at: "top" } },
  ]
  // Edited text, another tool name, a missing call, junk data, an unknown block type, another format.
  for (
    const details of [
      carry([ROUND[0], { type: "text", text: "Looking!" }, ROUND[2]]),
      carry([ROUND[0], ROUND[1], { ...ROUND[2], name: "peek" }]),
      carry([ROUND[0], ROUND[1]]),
      [{ ...carry()[0], data: "{not json" }],
      carry([...ROUND, { type: "server_tool_use", id: "x" }]),
      [{ ...carry()[0], format: "anthropic-claude-v1" }],
    ]
  ) {
    const out = body(history({ reasoning_details: details }))
    assertEquals(plain(assistantTurn(out)), rebuilt)
    assert(!("block_binding" in (out.thinking as Obj)))
  }
})

Deno.test("thinking: a client that renumbers tool ids still gets its thinking back", () => {
  // Max replaces provider tool ids with its own and sends no text for a whitespace-only reply.
  const round = [ROUND[0], { type: "text", text: "\n\n" }, ROUND[2]]
  const out = body({
    tools: [tool],
    messages: [
      { role: "user", content: "what is on top?" },
      {
        role: "assistant",
        content: null,
        tool_calls: [{
          id: "a1b2c3d4-0",
          type: "function",
          function: { name: "look", arguments: '{"at":"top"}' },
        }],
        reasoning_details: carry(round),
      },
      { role: "tool", tool_call_id: "a1b2c3d4-0", content: "a title bar" },
    ],
  })
  // The saved content comes back as the model wrote it, its call under the client's id.
  assertEquals(plain(assistantTurn(out)), [ROUND[0], round[1], { ...ROUND[2], id: "a1b2c3d4-0" }])
  assertEquals(((out.messages as Obj[])[2].content as Obj[])[0].tool_use_id, "a1b2c3d4-0")
  assert("block_binding" in (out.thinking as Obj))
})

Deno.test("thinking: no replay where thinking is off, and other upstreams never see it", () => {
  const haiku = { ...model, upstream_model: "claude-haiku-5-5" }
  const off = body(history({ reasoning_details: carry() }), haiku)
  assertEquals(off.thinking, { type: "disabled" })
  assert(!assistantTurn(off).some((b) => b.type === "thinking"))
  const or = requestBody(
    history({ reasoning_details: carry() }),
    { ...model, upstream_model: "anthropic/claude-sonnet-5.5" },
    "openai_chat",
    "https://openrouter.ai/api/v1",
  )
  assert(!("reasoning_details" in (or.messages as Obj[])[1]))
})

Deno.test("thinking: reasoning_details is accepted only on assistant messages, bounded", () => {
  const refused = (input: Obj) => {
    const e = assertThrows(() => body(input), HttpError)
    assertEquals(e.status, 400)
  }
  refused({ messages: [{ role: "user", content: "x", reasoning_details: carry() }] })
  refused(history({ reasoning_details: "nope" }))
  refused(history({ reasoning_details: [1] }))
  refused(history({ reasoning_details: Array(17).fill(carry()[0]) }))
  refused(history({ reasoning_details: [{ ...carry()[0], data: "x".repeat(600_000) }] }))
})

Deno.test("thinking: dropped blocks are reported in usage", () => {
  const reply = anthropicCompletion(
    {
      type: "message",
      content: [{ type: "text", text: "ok" }],
      stop_reason: "end_turn",
      usage: { input_tokens: 1, output_tokens: 1 },
      input_transformations: [
        {
          type: "thinking_dropped",
          path: "messages.1.content.0",
          reason: "prefix_binding_mismatch",
        },
        { type: "thinking_mismatch_allowed", path: "messages.3.content.0" },
      ],
    },
    "boss-test",
    "r",
  )
  assertEquals(((reply.usage as Obj).prompt_tokens_details as Obj).thinking_dropped, 1)
})
