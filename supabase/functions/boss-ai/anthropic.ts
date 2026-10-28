import { HttpError } from "./auth.ts"
import { invalid, Obj, object } from "./wire.ts"

// Native Anthropic Messages translation. Clients stay on OpenAI Chat Completions.
// Thinking text is streamed to clients as reasoning deltas, but never replayed: OpenAI-format
// clients cannot round-trip thinking blocks/signatures, so history is sent without them.

export const ANTHROPIC_VERSION = "2023-06-01"
// Models that always think: requesting a summarized display only makes the thinking visible (it is
// streamed as it arrives, so the client never sits through minutes of silence) and changes no cost.
// Unlisted models get no `thinking` field at all: on models where thinking is optional it would
// switch it on.
const ALWAYS_THINKS = new Set([
  "claude-opus-5-5",
  "claude-opus-5",
  "claude-sonnet-5-5",
  "claude-sonnet-5",
  "claude-haiku-5-5",
  "claude-fable-5-1",
  "claude-fable-5",
])
const EPHEMERAL = { type: "ephemeral" }
const CACHEABLE = ["text", "image", "tool_use", "tool_result"]

// Anthropic ids allow [a-zA-Z0-9_-] only; the same mapping applies to tool_use and tool_result.
const safeId = (id: unknown) => String(id).replace(/[^a-zA-Z0-9_-]/g, "_")

function texts(content: unknown): Obj[] {
  if (typeof content === "string") return content ? [{ type: "text", text: content }] : []
  if (!Array.isArray(content)) return []
  return (content as Obj[]).filter((p) => p.type === "text" && p.text).map((p) => ({
    type: "text",
    text: p.text,
  }))
}

function userBlocks(content: unknown): Obj[] {
  if (!Array.isArray(content)) return texts(content)
  const out: Obj[] = []
  for (const p of content as Obj[]) {
    if (p.type === "text") {
      if (p.text) out.push({ type: "text", text: p.text })
      continue
    }
    const m = /^data:(image\/(?:png|jpeg|webp));base64,(.+)$/.exec(
      String(object(p.image_url).url),
    )
    if (!m) throw invalid()
    out.push({ type: "image", source: { type: "base64", media_type: m[1], data: m[2] } })
  }
  return out
}

export function anthropicMessages(input: Obj[]): { system: Obj[]; messages: Obj[] } {
  const system: Obj[] = []
  const messages: Obj[] = []
  const push = (role: string, blocks: Obj[]) => {
    if (!blocks.length) return
    const last = messages.at(-1)
    if (!last || last.role !== role) return void messages.push({ role, content: blocks })
    // Same-role neighbours merge so roles alternate; tool_results must lead a user turn.
    const all = [...last.content as Obj[], ...blocks]
    last.content = role === "user"
      ? [
        ...all.filter((b) => b.type === "tool_result"),
        ...all.filter((b) => b.type !== "tool_result"),
      ]
      : all
  }
  const isSystem = (m: Obj) => m.role === "system" || m.role === "developer"
  let leading = true
  for (let i = 0; i < input.length; i++) {
    const m = input[i]
    if (isSystem(m)) {
      // Only the leading run is top-level `system`: a per-turn system message later on must not
      // change the cached prefix. Later ones stay in place, as a run merged into one note.
      if (leading) {
        system.push(...texts(m.content))
        continue
      }
      const note = [...texts(m.content)]
      while (i + 1 < input.length && isSystem(input[i + 1])) note.push(...texts(input[++i].content))
      if (!note.length) continue
      // The API accepts a mid-conversation system message only right after a user turn and either
      // last or before an assistant turn; anywhere else it becomes a note in the user turn.
      const next = input[i + 1]
      if (messages.at(-1)?.role === "user" && (!next || next.role === "assistant")) {
        messages.push({ role: "system", content: note })
      } else {
        push("user", note.map((b) => ({ ...b, text: `System note: ${b.text}` })))
      }
      continue
    }
    leading = false
    if (m.role === "user") push("user", userBlocks(m.content))
    else if (m.role === "assistant") {
      const blocks = texts(m.content)
      for (const value of (m.tool_calls as unknown[] | undefined) ?? []) {
        const f = object(object(value).function)
        let args: unknown = {}
        try {
          if (f.arguments !== "") args = object(JSON.parse(String(f.arguments)))
        } catch {
          throw invalid()
        }
        blocks.push({
          type: "tool_use",
          id: safeId(object(value).id),
          name: f.name,
          input: args,
        })
      }
      push("assistant", blocks)
    } else {
      const text = texts(m.content).map((b) => b.text).join("")
      push("user", [{
        type: "tool_result",
        tool_use_id: safeId(m.tool_call_id),
        ...(text ? { content: text } : {}),
      }])
    }
  }
  if (!messages.length) throw invalid()
  return { system, messages }
}

// Content blocks that cannot carry cache_control (thinking, unknown types) are left unmarked.
// Tool definitions have no `type` and always can.
function markLast<T extends Obj>(blocks: T[], any = false): T[] {
  const last = blocks.length - 1
  if (last < 0 || !(any || CACHEABLE.includes(String(blocks[last].type)))) return blocks
  return blocks.map((b, i) => i === last ? { ...b, cache_control: EPHEMERAL } : b)
}

// At most four breakpoints, always: last tool, last system block, the final message and the one
// before the most recent user turn (the previous request's write point, kept inside the 20-block
// lookback). Coinciding targets collapse; copies only, never in-place edits.
export function withAnthropicCache(
  tools: Obj[] | undefined,
  system: Obj[],
  messages: Obj[],
): { tools?: Obj[]; system: Obj[]; messages: Obj[] } {
  let user = -1
  messages.forEach((m, i) => {
    if (m.role === "user") user = i
  })
  // A mid-conversation system message is volatile and not marked: use the turn before it.
  const real = (i: number) => {
    while (i >= 0 && messages[i].role === "system") i--
    return i
  }
  const targets = new Set([real(messages.length - 1), real(user - 1)].filter((i) => i >= 0))
  return {
    ...(tools ? { tools: markLast(tools, true) } : {}),
    system: markLast(system),
    messages: messages.map((m, i) =>
      targets.has(i) ? { ...m, content: markLast(m.content as Obj[]) } : m
    ),
  }
}

function toolChoice(choice: unknown, parallel: unknown): Obj | undefined {
  let out: Obj | undefined
  if (choice === "auto") out = { type: "auto" }
  else if (choice === "none") return { type: "none" }
  // Opus 5.5 / Sonnet 5.5 reject forced `any`/`tool`: required and named choices stay `auto`.
  else if (choice) out = { type: "auto" }
  if (parallel === false) out = { ...(out ?? { type: "auto" }), disable_parallel_tool_use: true }
  return out
}

export function anthropicRequest(a: {
  upstream: string
  stream: boolean
  max: number
  messages: Obj[]
  tools?: Obj[]
  toolChoice?: unknown
  parallel?: unknown
  format?: Obj
}): Obj {
  if (a.format?.type === "json_object") {
    throw new HttpError(
      400,
      "invalid_request",
      "response_format json_object is not supported by this model; use json_schema.",
    )
  }
  const converted = anthropicMessages(a.messages)
  const tools = a.tools?.map((t) => {
    const f = object(t.function)
    return {
      name: f.name,
      ...(f.description !== undefined ? { description: f.description } : {}),
      input_schema: f.parameters ?? { type: "object", properties: {} },
    }
  })
  const cached = withAnthropicCache(tools, converted.system, converted.messages)
  const choice = tools ? toolChoice(a.toolChoice, a.parallel) : undefined
  return {
    model: a.upstream,
    max_tokens: a.max,
    stream: a.stream,
    ...(ALWAYS_THINKS.has(a.upstream)
      ? { thinking: { type: "adaptive", display: "summarized" } }
      : {}),
    ...(cached.system.length ? { system: cached.system } : {}),
    messages: cached.messages,
    ...(cached.tools ? { tools: cached.tools } : {}),
    ...(choice ? { tool_choice: choice } : {}),
    ...(a.format?.type === "json_schema"
      ? {
        output_config: {
          format: { type: "json_schema", schema: object(a.format.json_schema).schema },
        },
      }
      : {}),
  }
}

function count(value: unknown): number | null {
  if (value === undefined || value === null) return 0
  return Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : null
}

// prompt = input + cache reads + cache writes, so settlement keeps charging every token processed.
export function anthropicUsage(value: unknown): Obj | null {
  if (!value || typeof value !== "object") return null
  const u = value as Obj
  const input = count(u.input_tokens), output = count(u.output_tokens)
  const read = count(u.cache_read_input_tokens), write = count(u.cache_creation_input_tokens)
  if (
    input === null || output === null || read === null || write === null ||
    u.input_tokens === undefined || u.output_tokens === undefined
  ) return null
  const prompt = input + read + write
  if (!Number.isSafeInteger(prompt + output)) return null
  return {
    prompt_tokens: prompt,
    completion_tokens: output,
    total_tokens: prompt + output,
    prompt_tokens_details: { cached_tokens: read, cache_write_tokens: write },
  }
}

const FINISH: Record<string, string> = {
  end_turn: "stop",
  stop_sequence: "stop",
  pause_turn: "stop",
  max_tokens: "length",
  tool_use: "tool_calls",
  refusal: "content_filter",
}
const finishReason = (reason: unknown) => FINISH[String(reason)] ?? "stop"

export function anthropicCompletion(value: Obj, model: string, id: string): Obj {
  if (value.type === "error" || value.error || value.type !== "message") {
    throw new HttpError(502, "upstream_error", "The model could not complete this request.")
  }
  const blocks = Array.isArray(value.content) ? value.content.map(object) : []
  const text = blocks.filter((b) => b.type === "text").map((b) => String(b.text ?? "")).join("")
  const calls = blocks.filter((b) => b.type === "tool_use").map((b) => ({
    id: b.id,
    type: "function",
    function: { name: b.name, arguments: JSON.stringify(b.input ?? {}) },
  }))
  return {
    id,
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{
      index: 0,
      message: {
        role: "assistant",
        content: text || null,
        ...(calls.length ? { tool_calls: calls } : {}),
      },
      finish_reason: finishReason(value.stop_reason),
    }],
    usage: anthropicUsage(value.usage),
  }
}

export class AnthropicStream {
  finished = false
  tokens: number | null = null
  usage: Obj | null = null
  /** input + cache tokens from message_start, known before any output. */
  promptTokens: number | null = null
  private start: Obj = {}
  private stop: unknown
  private tools = new Map<number, number>()
  // Tool blocks that streamed argument text; one with none gets "{}" at its stop.
  private argued = new Set<number>()
  constructor(
    private model: string,
    private id: string,
    private created: number,
  ) {}
  private chunk(delta: Obj, finish: string | null = null): Obj {
    return {
      id: this.id,
      object: "chat.completion.chunk",
      created: this.created,
      model: this.model,
      choices: [{ index: 0, delta: { role: "assistant", ...delta }, finish_reason: finish }],
    }
  }
  accept(e: Obj): Obj[] {
    switch (e.type) {
      case "message_start":
        this.start = object(object(e.message).usage ?? {})
        this.promptTokens = Number(anthropicUsage(this.start)?.prompt_tokens ?? NaN)
        if (!Number.isFinite(this.promptTokens)) this.promptTokens = null
        return [this.chunk({ content: "" })]
      case "content_block_start": {
        const block = object(e.content_block)
        if (block.type !== "tool_use") return []
        const index = this.tools.size
        this.tools.set(Number(e.index), index)
        return [this.chunk({
          tool_calls: [{
            index,
            id: block.id,
            type: "function",
            function: { name: block.name, arguments: "" },
          }],
        })]
      }
      case "content_block_delta": {
        const d = object(e.delta)
        if (d.type === "text_delta") return [this.chunk({ content: String(d.text ?? "") })]
        if (d.type === "thinking_delta") {
          return d.thinking ? [this.chunk({ reasoning_content: String(d.thinking) })] : []
        }
        if (d.type !== "input_json_delta") return []
        const index = this.tools.get(Number(e.index))
        if (index === undefined) throw new Error("Unknown tool")
        const text = String(d.partial_json ?? "")
        if (!text) return []
        this.argued.add(index)
        return [this.chunk({ tool_calls: [{ index, function: { arguments: text } }] })]
      }
      case "content_block_stop": {
        // A tool without parameters streams no JSON; "" is not valid arguments for OpenAI clients.
        const index = this.tools.get(Number(e.index))
        if (index === undefined || this.argued.has(index)) return []
        this.argued.add(index)
        return [this.chunk({ tool_calls: [{ index, function: { arguments: "{}" } }] })]
      }
      case "message_delta": {
        this.stop = object(e.delta).stop_reason
        // Cumulative: fields reported here replace those from message_start.
        this.usage = anthropicUsage({ ...this.start, ...object(e.usage ?? {}) })
        if (this.usage) this.tokens = Number(this.usage.total_tokens)
        return []
      }
      case "message_stop": {
        this.finished = true
        return [
          this.chunk({}, finishReason(this.stop)),
          ...(this.usage
            ? [{
              id: this.id,
              object: "chat.completion.chunk",
              created: this.created,
              model: this.model,
              choices: [],
              usage: this.usage,
            }]
            : []),
        ]
      }
      default:
        return []
    }
  }
}
