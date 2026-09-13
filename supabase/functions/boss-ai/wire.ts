import { HttpError } from "./auth.ts"

export type Obj = Record<string, unknown>
export interface Model {
  id: string
  upstream_model: string
  capabilities: string[]
  context_length: number
  max_output_tokens: number
}
export interface Connection {
  base_url: string
  api_type: "openai_chat" | "openai_responses"
  api_key_secret: string
}
export function object(value: unknown): Obj {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid()
  return value as Obj
}
export function invalid(): HttpError {
  return new HttpError(
    400,
    "invalid_request",
    "This request is not supported by the selected BOSS model.",
  )
}

function onlyKeys(value: Obj, keys: string[]) {
  if (Object.keys(value).some((key) => !keys.includes(key))) throw invalid()
}

function functionCall(value: unknown): Obj {
  const call = object(value), f = object(call.function)
  onlyKeys(call, ["id", "type", "function"])
  onlyKeys(f, ["name", "arguments"])
  if (
    call.type !== "function" || typeof call.id !== "string" ||
    typeof f.name !== "string" || typeof f.arguments !== "string"
  ) throw invalid()
  return call
}

// Bounded reads apply even when Content-Length is absent or false.
export async function readJson(
  body: ReadableStream<Uint8Array> | null,
  maxBytes = 4 * 1024 * 1024,
): Promise<Obj> {
  if (!body) throw invalid()
  const reader = body.getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.length
      if (size > maxBytes) {
        throw new HttpError(413, "too_large", "The AI request or response is too large.")
      }
      chunks.push(value)
    }
    const bytes = new Uint8Array(size)
    let offset = 0
    for (const chunk of chunks) {
      bytes.set(chunk, offset)
      offset += chunk.length
    }
    try {
      return object(JSON.parse(new TextDecoder().decode(bytes)))
    } catch {
      throw invalid()
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export function endpoint(connection: Connection): string {
  if (
    !/^BOSS_AI_[A-Z0-9_]+$/.test(connection.api_key_secret) ||
    connection.api_key_secret === "BOSS_AI_SIGNING_SECRET"
  ) {
    throw new HttpError(503, "configuration", "BOSS AI is temporarily unavailable.")
  }
  const url = new URL(connection.base_url)
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) {
    throw new HttpError(503, "configuration", "BOSS AI is temporarily unavailable.")
  }
  return url.toString().replace(/\/$/, "") +
    (connection.api_type === "openai_chat" ? "/chat/completions" : "/responses")
}

/** Validate at the secret lookup itself, independent of endpoint call ordering. */
export function upstreamKey(
  connection: Connection,
  read: (name: string) => string | undefined,
): string {
  if (
    !/^BOSS_AI_[A-Z0-9_]+$/.test(connection.api_key_secret) ||
    connection.api_key_secret === "BOSS_AI_SIGNING_SECRET"
  ) {
    throw new HttpError(503, "configuration", "BOSS AI is temporarily unavailable.")
  }
  const key = read(connection.api_key_secret)
  if (!key) throw new HttpError(503, "configuration", "BOSS AI is temporarily unavailable.")
  return key
}

export function requestBody(input: Obj, model: Model, type: Connection["api_type"]): Obj {
  const allowed = new Set([
    "model",
    "messages",
    "stream",
    "stream_options",
    "max_tokens",
    "max_completion_tokens",
    "temperature",
    "top_p",
    "tools",
    "tool_choice",
    "parallel_tool_calls",
    "response_format",
  ])
  if (Object.keys(input).some((key) => !allowed.has(key))) throw invalid()
  if (!Array.isArray(input.messages) || !input.messages.length || input.messages.length > 512) {
    throw invalid()
  }
  if (input.stream !== undefined && typeof input.stream !== "boolean") throw invalid()
  if (input.stream_options !== undefined) {
    const options = object(input.stream_options)
    onlyKeys(options, ["include_usage"])
    // Accounting requires usage. Gateway requests true; explicitly declining it is unsupported.
    if (options.include_usage !== undefined && options.include_usage !== true) throw invalid()
  }
  const messages = input.messages.map((value) => {
    const original = object(value)
    const m = original.role === "assistant" && original.content === undefined &&
        Array.isArray(original.tool_calls) && original.tool_calls.length > 0
      ? { ...original, content: null }
      : original
    if (
      m.name !== undefined && (typeof m.name !== "string" || !/^[a-zA-Z0-9_-]{1,64}$/.test(m.name))
    ) {
      throw invalid()
    }
    if (!["system", "developer", "user", "assistant", "tool"].includes(String(m.role))) {
      throw invalid()
    }
    if (
      Object.keys(m).some((k) =>
        !["role", "content", "tool_calls", "tool_call_id", "name"].includes(k)
      )
    ) throw invalid()
    if (m.content !== null && typeof m.content !== "string") {
      if (!Array.isArray(m.content)) throw invalid()
      for (const part of m.content) {
        const p = object(part)
        if (p.type === "text" && typeof p.text === "string") {
          onlyKeys(p, ["type", "text"])
          continue
        }
        if (p.type === "image_url" && model.capabilities.includes("vision")) {
          onlyKeys(p, ["type", "image_url"])
          const image = object(p.image_url)
          onlyKeys(image, ["url", "detail"])
          if (
            image.detail !== undefined && !["auto", "low", "high"].includes(String(image.detail))
          ) throw invalid()
          if (
            typeof image.url === "string" &&
            /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(image.url)
          ) continue
        }
        throw invalid()
      }
    }
    if (
      m.role === "tool" &&
      (typeof m.tool_call_id !== "string" || !model.capabilities.includes("tools"))
    ) throw invalid()
    if (
      m.tool_calls !== undefined &&
      (!Array.isArray(m.tool_calls) || !model.capabilities.includes("tools"))
    ) throw invalid()
    if (Array.isArray(m.tool_calls)) m.tool_calls.forEach(functionCall)
    if (
      m.role === "tool" && (m.content === null ||
        (Array.isArray(m.content) && m.content.some((part) => object(part).type !== "text")))
    ) {
      throw invalid()
    }
    return m
  })
  const max = input.max_completion_tokens ?? input.max_tokens ??
    Math.min(2000, model.max_output_tokens)
  if (!Number.isSafeInteger(max) || Number(max) < 1 || Number(max) > model.max_output_tokens) {
    throw invalid()
  }
  const common: Obj = { model: model.upstream_model, stream: input.stream === true, store: false }
  for (const key of ["temperature", "top_p"]) {
    if (input[key] !== undefined) {
      if (typeof input[key] !== "number" || !Number.isFinite(input[key])) throw invalid()
      common[key] = input[key]
    }
  }
  let tools: Obj[] | undefined
  if (input.tools !== undefined) {
    if (
      !model.capabilities.includes("tools") || !Array.isArray(input.tools) ||
      input.tools.length > 128
    ) throw invalid()
    tools = input.tools.map((value) => {
      const t = object(value)
      const f = object(t.function)
      onlyKeys(t, ["type", "function"])
      onlyKeys(f, ["name", "description", "parameters", "strict"])
      if (f.description !== undefined && typeof f.description !== "string") throw invalid()
      if (f.strict !== undefined && typeof f.strict !== "boolean") throw invalid()
      if (f.parameters !== undefined) object(f.parameters)
      if (
        t.type !== "function" || typeof f.name !== "string" || !/^[a-zA-Z0-9_-]{1,64}$/.test(f.name)
      ) throw invalid()
      return { type: "function", function: f }
    })
    if (!tools.length) tools = undefined
  }
  if (input.tool_choice !== undefined) {
    if (!tools) throw invalid()
    const choice = input.tool_choice
    if (typeof choice === "string") {
      if (!["auto", "none", "required"].includes(choice)) throw invalid()
      common.tool_choice = choice
    } else {
      const c = object(choice), f = object(c.function)
      onlyKeys(c, ["type", "function"])
      onlyKeys(f, ["name"])
      if (c.type !== "function" || typeof f.name !== "string") throw invalid()
      common.tool_choice = type === "openai_chat" ? c : { type: "function", name: f.name }
    }
  }
  if (input.parallel_tool_calls !== undefined) {
    if (!tools || typeof input.parallel_tool_calls !== "boolean") throw invalid()
    common.parallel_tool_calls = input.parallel_tool_calls
  }
  if (input.response_format !== undefined) {
    if (!model.capabilities.includes("structured_output")) throw invalid()
    const format = object(input.response_format)
    onlyKeys(format, ["type", "json_schema"])
    if (!["json_schema", "json_object", "text"].includes(String(format.type))) throw invalid()
    if (format.type === "json_schema") {
      const schema = object(format.json_schema)
      onlyKeys(schema, ["name", "description", "schema", "strict"])
      if (
        typeof schema.name !== "string" ||
        (schema.description !== undefined && typeof schema.description !== "string") ||
        (schema.strict !== undefined && typeof schema.strict !== "boolean")
      ) throw invalid()
      object(schema.schema)
    }
    if (type === "openai_chat") common.response_format = format
    else {common.text = {
        format: format.type === "json_schema"
          ? { ...object(format.json_schema), type: "json_schema" }
          : format,
      }}
  }
  if (type === "openai_chat") {
    if (tools) common.tools = tools
    if (common.stream) common.stream_options = { include_usage: true }
    return { ...common, messages, max_completion_tokens: max }
  }
  const items: Obj[] = []
  for (const m of messages) {
    if (m.role === "tool") {
      const output = typeof m.content === "string"
        ? m.content
        : (m.content as Obj[]).map((part) => part.text).join("")
      items.push({ type: "function_call_output", call_id: m.tool_call_id, output })
      continue
    }
    if (m.content !== null && m.content !== "") {
      const content = typeof m.content === "string"
        ? m.content
        : (m.content as unknown[]).map((value) => {
          const p = object(value)
          return p.type === "text"
            ? { type: m.role === "assistant" ? "output_text" : "input_text", text: p.text }
            : {
              type: "input_image",
              image_url: object(p.image_url).url,
              detail: object(p.image_url).detail ?? "auto",
            }
        })
      items.push({ role: m.role, content })
    }
    for (const call of (m.tool_calls as unknown[] | undefined) ?? []) {
      const c = object(call), f = object(c.function)
      items.push({ type: "function_call", call_id: c.id, name: f.name, arguments: f.arguments })
    }
  }
  if (tools) common.tools = tools.map((t) => ({ ...object(t.function), type: "function" }))
  return { ...common, input: items, max_output_tokens: max, truncation: "disabled" }
}

export function usage(value: unknown, responses = false): Obj | null {
  if (!value || typeof value !== "object") return null
  const u = value as Obj
  const prompt = u[responses ? "input_tokens" : "prompt_tokens"]
  const completion = u[responses ? "output_tokens" : "completion_tokens"]
  if (
    !Number.isSafeInteger(prompt) || !Number.isSafeInteger(completion) || Number(prompt) < 0 ||
    Number(completion) < 0 || !Number.isSafeInteger(Number(prompt) + Number(completion))
  ) return null
  return {
    prompt_tokens: prompt,
    completion_tokens: completion,
    total_tokens: Number(prompt) + Number(completion),
  }
}

export function completion(
  value: Obj,
  model: string,
  type: Connection["api_type"],
  id: string,
): Obj {
  try {
    return decodeCompletion(value, model, type, id)
  } catch {
    // Every decoding failure here belongs to the upstream, never to the caller.
    throw new HttpError(502, "upstream_error", "The model returned an invalid response.")
  }
}

function decodeCompletion(
  value: Obj,
  model: string,
  type: Connection["api_type"],
  id: string,
): Obj {
  if (value.error) {
    throw new HttpError(502, "upstream_error", "The model could not complete this request.")
  }
  if (type === "openai_chat") {
    if (!Array.isArray(value.choices) || value.choices.length !== 1) {
      throw new HttpError(502, "upstream_error", "The model returned an invalid response.")
    }
    return {
      id,
      object: "chat.completion",
      created: Math.floor(Date.now() / 1000),
      model,
      choices: value.choices,
      usage: usage(value.usage),
    }
  }
  if (!["completed", "incomplete"].includes(String(value.status))) {
    throw new HttpError(502, "upstream_error", "The model could not complete this request.")
  }
  const output = Array.isArray(value.output) ? value.output.map(object) : []
  const calls = output.filter((x) => x.type === "function_call").map((x) => ({
    id: x.call_id,
    type: "function",
    function: { name: x.name, arguments: x.arguments },
  }))
  const text = output.filter((x) => x.type === "message").flatMap((x) =>
    Array.isArray(x.content) ? x.content.map(object) : []
  )
    .map((x) => x.type === "output_text" ? x.text : x.type === "refusal" ? x.refusal : "").join("")
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
      finish_reason: value.status === "incomplete"
        ? "length"
        : calls.length
        ? "tool_calls"
        : "stop",
    }],
    usage: usage(value.usage, true),
  }
}

// SSE frames can split anywhere, including within UTF-8 characters and CRLF pairs.
export async function* events(
  body: ReadableStream<Uint8Array>,
  signal?: AbortSignal,
): AsyncGenerator<string> {
  const reader = body.pipeThrough(new TextDecoderStream()).getReader()
  const cancel = () => {
    void reader.cancel().catch(() => {})
  }
  signal?.addEventListener("abort", cancel, { once: true })
  if (signal?.aborted) cancel()
  let buffer = ""
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += value
      if (buffer.length > 4 * 1024 * 1024) throw new Error("Oversized frame")
      while (true) {
        const match = /\r?\n\r?\n/.exec(buffer)
        if (!match) break
        const frame = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        const data = frame.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) =>
          line.slice(5).replace(/^ /, "")
        ).join("\n")
        if (data) yield data
      }
    }
    // Comments contain no application data and need not turn a completed stream into an error.
    if (buffer.split(/\r?\n/).some((line) => line.trim() && !line.startsWith(":"))) {
      throw new Error("Truncated frame")
    }
  } finally {
    signal?.removeEventListener("abort", cancel)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export class StreamAdapter {
  finished = false
  tokens: number | null = null
  private toolIndexes = new Map<number, number>()
  private created = Math.floor(Date.now() / 1000)
  constructor(private model: string, private type: Connection["api_type"], private id: string) {}
  private chunk(delta: Obj, finish: string | null = null): Obj {
    return {
      id: this.id,
      object: "chat.completion.chunk",
      created: this.created,
      model: this.model,
      choices: [{ index: 0, delta: { role: "assistant", ...delta }, finish_reason: finish }],
    }
  }
  accept(data: string): Obj[] {
    if (data === "[DONE]") {
      if (this.type === "openai_chat") this.finished = true
      return []
    }
    const e = object(JSON.parse(data))
    if (e.error || ["error", "response.failed"].includes(String(e.type))) {
      throw new Error("Upstream error")
    }
    if (this.type === "openai_chat") {
      const u = usage(e.usage)
      if (u) this.tokens = Number(u.total_tokens)
      return [{
        id: this.id,
        object: "chat.completion.chunk",
        created: this.created,
        model: this.model,
        choices: e.choices ?? [],
        ...(u ? { usage: u } : {}),
      }]
    }
    if (e.type === "response.output_text.delta") return [this.chunk({ content: e.delta })]
    if (e.type === "response.refusal.delta") return [this.chunk({ content: e.delta })]
    if (e.type === "response.output_item.added") {
      const item = object(e.item)
      if (item.type !== "function_call") return []
      const index = this.toolIndexes.size
      this.toolIndexes.set(Number(e.output_index), index)
      return [
        this.chunk({
          tool_calls: [{
            index,
            id: item.call_id,
            type: "function",
            function: { name: item.name, arguments: item.arguments ?? "" },
          }],
        }),
      ]
    }
    if (e.type === "response.function_call_arguments.delta") {
      const index = this.toolIndexes.get(Number(e.output_index))
      if (index === undefined) throw new Error("Unknown tool")
      return [this.chunk({ tool_calls: [{ index, function: { arguments: e.delta } }] })]
    }
    if (["response.completed", "response.incomplete"].includes(String(e.type))) {
      const response = object(e.response), u = usage(response.usage, true)
      if (u) this.tokens = Number(u.total_tokens)
      this.finished = true
      return [
        this.chunk(
          {},
          e.type === "response.incomplete"
            ? "length"
            : this.toolIndexes.size
            ? "tool_calls"
            : "stop",
        ),
        {
          id: this.id,
          object: "chat.completion.chunk",
          created: this.created,
          model: this.model,
          choices: [],
          usage: u,
        },
      ]
    }
    return []
  }
}
