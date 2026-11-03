import { HttpError } from "./auth.ts"
import {
  ANTHROPIC_VERSION,
  anthropicCompletion,
  anthropicRequest,
  AnthropicStream,
  THINKING_BINDING_BETA,
} from "./anthropic.ts"

const MAX_MESSAGES = 512
const MAX_TOOLS = 128
const MAX_TOOL_CALLS_PER_MESSAGE = 128
const MAX_TOOL_DESCRIPTION_LENGTH = 4_096
const MAX_TOOL_SCHEMA_LENGTH = 65_536
// The client's copy of a turn's reasoning: at most a few entries, bounded like the gateway plugin's.
const MAX_REASONING_DETAILS = 16
const MAX_REASONING_CHARS = 512 * 1024

function boundedToolSchema(value: unknown): void {
  const pending: { value: unknown; depth: number }[] = [{ value: object(value), depth: 0 }]
  let visited = 0
  while (pending.length) {
    const next = pending.pop()!
    if (++visited > 8_192 || next.depth > 32) throw invalid("schema_too_large")
    if (next.value && typeof next.value === "object") {
      const children = Object.values(next.value)
      if (children.length + pending.length + visited > 8_192) throw invalid("schema_too_large")
      for (const child of children) pending.push({ value: child, depth: next.depth + 1 })
    }
  }
  if (JSON.stringify(value).length > MAX_TOOL_SCHEMA_LENGTH) {
    throw invalid("schema_too_large", `at most ${MAX_TOOL_SCHEMA_LENGTH} characters`)
  }
}

export type Obj = Record<string, unknown>
export interface Model {
  id: string
  upstream_model: string
  kind: "chat" | "transcription" | "speech"
  voice?: string | null
  capabilities: string[]
  context_length: number
  max_output_tokens: number
  /** Operator-set upstream routing (OpenRouter `provider`); never caller-supplied. */
  provider_routing?: unknown
  connection_id?: string
}
export interface Connection {
  id?: string
  base_url: string
  api_type: "openai_chat" | "openai_responses" | "anthropic_messages"
  api_key_secret: string
}
/** A resolved model.fallbacks entry: the alternate route and its connection row. */
export interface Fallback {
  connection_id: string
  upstream_model: string
  provider_routing?: unknown
  usd_per_mtok_input?: number | null
  usd_per_mtok_output?: number | null
  usd_per_mtok_cache_read?: number | null
  usd_per_mtok_cache_write?: number | null
  connection: Connection
}
export function object(value: unknown, reason: InvalidReason = "malformed_request"): Obj {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid(reason)
  return value as Obj
}

/**
 * Which validation check refused a request: a stable code, sent as `error.reason` and named in the
 * message. Neither ever quotes the request; [invalid]'s `detail` carries only counts and limits.
 */
export const INVALID_REASONS = {
  malformed_request: "the request is not well-formed JSON of the expected shape",
  unknown_field: "the request has a field this endpoint does not accept",
  invalid_parameter: "a request parameter has an unsupported value",
  no_messages: "the request has no messages",
  too_many_messages: "the request has too many messages",
  invalid_message: "a message is not well-formed",
  invalid_role: "a message has an unsupported role",
  unsupported_content: "a message has a content part this endpoint does not accept",
  image_unsupported: "the selected model does not accept images",
  gif_unsupported: "GIF images are not supported; send PNG, JPEG or WebP",
  image_format_unsupported: "images must be inline PNG, JPEG or WebP data URLs",
  tools_unsupported: "the selected model does not accept tools",
  invalid_tool_message: "a tool message is not well-formed",
  invalid_tool_call: "a tool call is not well-formed",
  too_many_tool_calls: "a message has too many tool calls",
  tool_args_not_object: "tool call arguments must be a JSON object",
  invalid_tools: "the tool list is not well-formed",
  too_many_tools: "the request has too many tools",
  invalid_tool_name: "a tool name is not 1 to 64 letters, digits, _ or -",
  tool_description_too_long: "a tool description is too long",
  schema_too_large: "a tool or response schema is too large or too deep",
  invalid_tool_choice: "tool_choice is not supported for this request",
  invalid_max_tokens: "max_tokens must be a positive integer",
  max_tokens_too_large: "max_tokens exceeds the selected model's output limit",
  structured_output_unsupported: "the selected model does not accept response_format",
  invalid_response_format: "response_format is not well-formed",
} as const
export type InvalidReason = keyof typeof INVALID_REASONS

/** A 400 naming the failed check: `error.code` stays `invalid_request`, `error.reason` is [reason]. */
export function invalid(reason: InvalidReason = "malformed_request", detail?: string): HttpError {
  return new HttpError(
    400,
    "invalid_request",
    `This request is not supported by the selected BOSS model (${reason}: ${
      INVALID_REASONS[reason]
    }${detail ? `; ${detail}` : ""}).`,
    { reason },
  )
}

function onlyKeys(value: Obj, keys: string[]) {
  if (Object.keys(value).some((key) => !keys.includes(key))) throw invalid("unknown_field")
}

function functionCall(value: unknown): Obj {
  const call = object(value, "invalid_tool_call"), f = object(call.function, "invalid_tool_call")
  onlyKeys(call, ["id", "type", "function"])
  onlyKeys(f, ["name", "arguments"])
  if (
    call.type !== "function" || typeof call.id !== "string" ||
    typeof f.name !== "string" || typeof f.arguments !== "string"
  ) throw invalid("invalid_tool_call")
  // BossConsole#1251: tool-call `arguments` must be an object or the empty
  // string that some upstreams return for a no-argument call. Other strings
  // are malformed requests that the upstream
  // would reject, but only after the reservation has been charged for
  // the full context length. Fail closed here so the bad call never
  // reaches the dispatch path.
  try {
    if (f.arguments !== "") object(JSON.parse(f.arguments))
  } catch {
    throw invalid("tool_args_not_object")
  }
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

/** Hosts a local stack may reach over plain http (BOSS_AI_ALLOW_LOCAL_AGENT=1 only). */
export const LOCAL_HOSTS = ["127.0.0.1", "localhost", "host.docker.internal"]
export const urlAllowed = (url: URL, allowLocal: boolean) =>
  url.protocol === "https:" ||
  (allowLocal && url.protocol === "http:" && LOCAL_HOSTS.includes(url.hostname))

function baseUrl(connection: Connection, allowLocal = false): string {
  if (
    !/^BOSS_AI_[A-Z0-9_]+$/.test(connection.api_key_secret) ||
    connection.api_key_secret === "BOSS_AI_SIGNING_SECRET"
  ) {
    throw new HttpError(503, "configuration", "BOSS AI is temporarily unavailable.")
  }
  const url = new URL(connection.base_url)
  if (!urlAllowed(url, allowLocal) || url.username || url.password || url.search || url.hash) {
    throw new HttpError(503, "configuration", "BOSS AI is temporarily unavailable.")
  }
  return url.toString().replace(/\/$/, "")
}

const CHAT_PATHS = {
  openai_chat: "/chat/completions",
  openai_responses: "/responses",
  anthropic_messages: "/messages",
}
export function endpoint(connection: Connection, allowLocal = false): string {
  return baseUrl(connection, allowLocal) + CHAT_PATHS[connection.api_type]
}

/** Anthropic authenticates with x-api-key, never Bearer; audio routes are always Bearer. */
export function upstreamHeaders(
  connection: Connection,
  key: string,
  chat = true,
  body?: Obj,
): Record<string, string> {
  // Replayed thinking asks for drop-instead-of-400 (block_binding), which needs the beta header.
  const thinking = body?.thinking
  const binding = !!thinking && typeof thinking === "object" && "block_binding" in thinking
  return chat && connection.api_type === "anthropic_messages"
    ? {
      "x-api-key": key,
      "anthropic-version": ANTHROPIC_VERSION,
      ...(binding ? { "anthropic-beta": THINKING_BINDING_BETA } : {}),
      "Content-Type": "application/json",
    }
    : { Authorization: `Bearer ${key}`, "Content-Type": "application/json" }
}

/** Audio routes ignore api_type: every connection serves them under its base URL. */
export function audioEndpoint(
  connection: Connection,
  route: "transcriptions" | "speech",
  allowLocal = false,
): string {
  return `${baseUrl(connection, allowLocal)}/audio/${route}`
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

function isOpenRouter(baseUrl: string): boolean {
  try {
    const url = new URL(baseUrl)
    return url.protocol === "https:" && url.hostname === "openrouter.ai"
  } catch {
    return false
  }
}

const ROUTING_LISTS = ["order", "only", "ignore"]
const ROUTING_FLAGS = ["allow_fallbacks", "require_parameters"]
const ROUTING_SORTS = ["price", "throughput", "latency"]

// Operator-set OpenRouter `provider` object, rebuilt from an allow-list. A malformed row,
// or routing on a non-OpenRouter connection, fails closed before reaching the upstream.
// Mirrors public.boss_ai_provider_routing_valid().
export function providerRouting(value: unknown, baseUrl: string): Obj | undefined {
  if (value === null || value === undefined) return undefined
  const misconfigured = () =>
    new HttpError(503, "configuration", "BOSS AI is temporarily unavailable.")
  if (typeof value !== "object" || Array.isArray(value)) throw misconfigured()
  const out: Obj = {}
  for (const [key, entry] of Object.entries(value as Obj)) {
    if (ROUTING_LISTS.includes(key)) {
      if (
        !Array.isArray(entry) || entry.length < 1 || entry.length > 32 ||
        entry.some((slug) => typeof slug !== "string" || !/^[a-z0-9][a-z0-9._/-]{0,63}$/.test(slug))
      ) throw misconfigured()
      out[key] = [...entry]
    } else if (ROUTING_FLAGS.includes(key)) {
      if (typeof entry !== "boolean") throw misconfigured()
      out[key] = entry
    } else if (key === "sort") {
      if (typeof entry !== "string" || !ROUTING_SORTS.includes(entry)) throw misconfigured()
      out[key] = entry
    } else throw misconfigured()
  }
  if (!Object.keys(out).length) return undefined
  if (!isOpenRouter(baseUrl)) throw misconfigured()
  return out
}

export function requestBody(
  input: Obj,
  model: Model,
  type: Connection["api_type"],
  baseUrl: Connection["base_url"],
): Obj {
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
  if (Object.keys(input).some((key) => !allowed.has(key))) throw invalid("unknown_field")
  if (!Array.isArray(input.messages) || !input.messages.length) throw invalid("no_messages")
  if (input.messages.length > MAX_MESSAGES) {
    throw invalid("too_many_messages", `${input.messages.length} messages, at most ${MAX_MESSAGES}`)
  }
  if (input.stream !== undefined && typeof input.stream !== "boolean") {
    throw invalid("invalid_parameter", "stream")
  }
  if (input.stream_options !== undefined) {
    const options = object(input.stream_options, "invalid_parameter")
    onlyKeys(options, ["include_usage"])
    // Accounting requires usage. Gateway requests true; explicitly declining it is unsupported.
    if (options.include_usage !== undefined && options.include_usage !== true) {
      throw invalid("invalid_parameter", "stream_options.include_usage")
    }
  }
  const messages = input.messages.map((value) => {
    const original = object(value, "invalid_message")
    const m = original.role === "assistant" && original.content === undefined &&
        Array.isArray(original.tool_calls) && original.tool_calls.length > 0
      ? { ...original, content: null }
      : original
    if (
      m.name !== undefined && (typeof m.name !== "string" || !/^[a-zA-Z0-9_-]{1,64}$/.test(m.name))
    ) {
      throw invalid("invalid_message", "name")
    }
    if (!["system", "developer", "user", "assistant", "tool"].includes(String(m.role))) {
      throw invalid("invalid_role")
    }
    if (
      Object.keys(m).some((k) =>
        !["role", "content", "tool_calls", "tool_call_id", "name", "reasoning_details"].includes(k)
      )
    ) throw invalid("unknown_field")
    if (m.reasoning_details !== undefined) {
      const details = m.reasoning_details
      if (
        m.role !== "assistant" || !Array.isArray(details) ||
        details.length > MAX_REASONING_DETAILS ||
        !details.every((d) => d && typeof d === "object" && !Array.isArray(d)) ||
        JSON.stringify(details).length > MAX_REASONING_CHARS
      ) throw invalid("invalid_message", "reasoning_details")
    }
    if (m.content !== null && typeof m.content !== "string") {
      if (!Array.isArray(m.content)) throw invalid("invalid_message", "content")
      for (const part of m.content) {
        const p = object(part, "invalid_message")
        if (p.type === "text" && typeof p.text === "string") {
          onlyKeys(p, ["type", "text"])
          continue
        }
        if (p.type === "text") throw invalid("invalid_message", "text")
        if (p.type !== "image_url") throw invalid("unsupported_content")
        if (!model.capabilities.includes("vision")) throw invalid("image_unsupported")
        onlyKeys(p, ["type", "image_url"])
        const image = object(p.image_url, "image_format_unsupported")
        onlyKeys(image, ["url", "detail"])
        if (
          image.detail !== undefined && !["auto", "low", "high"].includes(String(image.detail))
        ) throw invalid("invalid_parameter", "image_url.detail")
        if (
          typeof image.url === "string" &&
          /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(image.url)
        ) continue
        if (typeof image.url === "string" && /^data:image\/gif[;,]/i.test(image.url)) {
          throw invalid("gif_unsupported")
        }
        throw invalid("image_format_unsupported")
      }
    }
    if (m.role === "tool" && !model.capabilities.includes("tools")) {
      throw invalid("tools_unsupported")
    }
    if (m.role === "tool" && typeof m.tool_call_id !== "string") {
      throw invalid("invalid_tool_message", "tool_call_id")
    }
    if (m.tool_calls !== undefined && !model.capabilities.includes("tools")) {
      throw invalid("tools_unsupported")
    }
    if (m.tool_calls !== undefined && !Array.isArray(m.tool_calls)) {
      throw invalid("invalid_tool_call")
    }
    // BossConsole#1251: cap tool_calls per message. A single message
    // with thousands of tool_calls multiplies the JSON.parse cost
    // in `functionCall` (now mandatory) and produces a request the
    // upstream would reject at the wire size anyway. This is a BOSS policy
    // bound that stops the obvious amplifier; it is not an upstream limit.
    // Keep it high enough to replay the broker's own parallel-call responses.
    if (
      Array.isArray(m.tool_calls) &&
      m.tool_calls.length > MAX_TOOL_CALLS_PER_MESSAGE
    ) {
      throw invalid(
        "too_many_tool_calls",
        `${m.tool_calls.length} tool calls, at most ${MAX_TOOL_CALLS_PER_MESSAGE}`,
      )
    }
    if (Array.isArray(m.tool_calls)) m.tool_calls.forEach(functionCall)
    if (
      m.role === "tool" && (m.content === null ||
        (Array.isArray(m.content) && m.content.some((part) => object(part).type !== "text")))
    ) {
      throw invalid("invalid_tool_message", "content must be text")
    }
    // Only the native Anthropic route reads reasoning back; other upstreams never see it.
    if (m.reasoning_details !== undefined && type !== "anthropic_messages") {
      const { reasoning_details: _, ...rest } = m
      return rest
    }
    return m
  })
  const max = input.max_completion_tokens ?? input.max_tokens ??
    Math.min(2000, model.max_output_tokens)
  if (!Number.isSafeInteger(max) || Number(max) < 1) throw invalid("invalid_max_tokens")
  if (Number(max) > model.max_output_tokens) {
    throw invalid(
      "max_tokens_too_large",
      `max_tokens ${max} exceeds this model's ${model.max_output_tokens}`,
    )
  }
  const common: Obj = { model: model.upstream_model, stream: input.stream === true, store: false }
  const provider = providerRouting(model.provider_routing, baseUrl)
  if (provider) common.provider = provider
  for (const key of ["temperature", "top_p"]) {
    if (input[key] !== undefined) {
      if (typeof input[key] !== "number" || !Number.isFinite(input[key])) {
        throw invalid("invalid_parameter", key)
      }
      // Current Claude models reject non-default sampling parameters: validated, never forwarded.
      if (type !== "anthropic_messages") common[key] = input[key]
    }
  }
  let tools: Obj[] | undefined
  if (input.tools !== undefined) {
    if (!model.capabilities.includes("tools")) throw invalid("tools_unsupported")
    if (!Array.isArray(input.tools)) throw invalid("invalid_tools")
    if (input.tools.length > MAX_TOOLS) {
      throw invalid("too_many_tools", `${input.tools.length} tools, at most ${MAX_TOOLS}`)
    }
    tools = input.tools.map((value) => {
      const t = object(value, "invalid_tools")
      const f = object(t.function, "invalid_tools")
      onlyKeys(t, ["type", "function"])
      onlyKeys(f, ["name", "description", "parameters", "strict"])
      // Host policy bounds each description/schema as well as the 128-tool list.
      // The overall 4 MiB request-body cap remains the aggregate bound.
      if (f.description !== undefined) {
        if (typeof f.description !== "string") throw invalid("invalid_tools", "description")
        if (f.description.length > MAX_TOOL_DESCRIPTION_LENGTH) {
          throw invalid(
            "tool_description_too_long",
            `at most ${MAX_TOOL_DESCRIPTION_LENGTH} characters`,
          )
        }
      }
      if (f.strict !== undefined && typeof f.strict !== "boolean") {
        throw invalid("invalid_tools", "strict")
      }
      if (f.parameters !== undefined) boundedToolSchema(f.parameters)
      if (t.type !== "function") throw invalid("invalid_tools", "type")
      if (typeof f.name !== "string" || !/^[a-zA-Z0-9_-]{1,64}$/.test(f.name)) {
        throw invalid("invalid_tool_name")
      }
      return { type: "function", function: f }
    })
    if (!tools.length) tools = undefined
  }
  if (input.tool_choice !== undefined) {
    if (!tools) throw invalid("invalid_tool_choice", "no tools")
    const choice = input.tool_choice
    if (typeof choice === "string") {
      if (!["auto", "none", "required"].includes(choice)) throw invalid("invalid_tool_choice")
      common.tool_choice = choice
    } else {
      const c = object(choice, "invalid_tool_choice"), f = object(c.function, "invalid_tool_choice")
      onlyKeys(c, ["type", "function"])
      onlyKeys(f, ["name"])
      if (c.type !== "function" || typeof f.name !== "string") throw invalid("invalid_tool_choice")
      common.tool_choice = type === "openai_chat" ? c : { type: "function", name: f.name }
    }
  }
  if (input.parallel_tool_calls !== undefined) {
    if (!tools || typeof input.parallel_tool_calls !== "boolean") {
      throw invalid("invalid_parameter", "parallel_tool_calls")
    }
    common.parallel_tool_calls = input.parallel_tool_calls
  }
  if (input.response_format !== undefined) {
    if (!model.capabilities.includes("structured_output")) {
      throw invalid("structured_output_unsupported")
    }
    const format = object(input.response_format, "invalid_response_format")
    onlyKeys(format, ["type", "json_schema"])
    if (!["json_schema", "json_object", "text"].includes(String(format.type))) {
      throw invalid("invalid_response_format")
    }
    if (format.type === "json_schema") {
      const schema = object(format.json_schema, "invalid_response_format")
      onlyKeys(schema, ["name", "description", "schema", "strict"])
      if (
        typeof schema.name !== "string" ||
        (schema.description !== undefined && typeof schema.description !== "string") ||
        (schema.strict !== undefined && typeof schema.strict !== "boolean")
      ) throw invalid("invalid_response_format")
      boundedToolSchema(schema.schema)
    }
    if (type === "openai_responses") {
      common.text = {
        format: format.type === "json_schema"
          ? { ...object(format.json_schema), type: "json_schema" }
          : format,
      }
    } else common.response_format = format
  }
  if (type === "anthropic_messages") {
    return anthropicRequest({
      upstream: model.upstream_model,
      stream: common.stream === true,
      max: Number(max),
      messages,
      tools,
      toolChoice: common.tool_choice,
      parallel: common.parallel_tool_calls,
      format: common.response_format as Obj | undefined,
    })
  }
  if (type === "openai_chat") {
    if (tools) common.tools = tools
    if (common.stream) common.stream_options = { include_usage: true }
    const cached = takesCacheControl(model.upstream_model, baseUrl)
      ? withPromptCache(messages)
      : messages
    return { ...common, messages: cached, max_completion_tokens: max }
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

// Upstreams that cache only where the request marks it: Anthropic. OpenRouter forwards `cache_control`;
// every other upstream caches automatically or not at all and gets the request unchanged. Gemini is never
// marked: it caches implicitly, and an explicit cache cost +1.4-3.2 s to the first token without paying back
// on reuse (measured on flash-lite, short and 23k-char prompts).
export function takesCacheControl(upstreamModel: string, baseUrl: string): boolean {
  return isOpenRouter(baseUrl) && upstreamModel.toLowerCase().startsWith("anthropic/")
}

const EPHEMERAL = { type: "ephemeral" }

// Three 5-minute breakpoints: the first system message (with the tools ahead of it, the stable
// prefix every turn repeats), the newest user message, and in a tool loop the newest tool result,
// so each round reads the rounds before it. No 1-hour mark: OpenRouter usage does not split 1-hour
// writes out, so they would be billed at the 5-minute rate. Messages are copied, never edited in
// place, and only text parts carry the mark.
export function withPromptCache(messages: Obj[]): Obj[] {
  const system = messages.findIndex((m) => m.role === "system" || m.role === "developer")
  let user = -1
  messages.forEach((m, i) => {
    if (m.role === "user") user = i
  })
  const last = messages.length - 1
  const tail = last > user && messages[last]?.role === "tool" ? last : -1
  return messages.map((m, i) => (i === system || i === user || i === tail) ? markLastText(m) : m)
}

function markLastText(m: Obj): Obj {
  if (typeof m.content === "string") {
    if (!m.content) return m
    return { ...m, content: [{ type: "text", text: m.content, cache_control: EPHEMERAL }] }
  }
  if (!Array.isArray(m.content)) return m
  const parts = m.content as Obj[]
  let last = -1
  parts.forEach((p, i) => {
    if (p.type === "text" && typeof p.text === "string" && p.text) last = i
  })
  if (last < 0) return m
  return { ...m, content: parts.map((p, i) => i === last ? { ...p, cache_control: EPHEMERAL } : p) }
}

// The cache and reasoning breakdowns the gateway prices with, passed through when the upstream
// reports them as non-negative integers. Totals stay prompt + completion: accounting is unchanged.
function details(source: unknown, keys: string[]): Obj | undefined {
  if (!source || typeof source !== "object") return undefined
  const out: Obj = {}
  for (const key of keys) {
    const v = (source as Obj)[key]
    if (Number.isSafeInteger(v) && Number(v) >= 0) out[key] = v
  }
  return Object.keys(out).length ? out : undefined
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
  const promptDetails = details(
    u[responses ? "input_tokens_details" : "prompt_tokens_details"],
    ["cached_tokens", "cache_write_tokens"],
  )
  const completionDetails = details(
    u[responses ? "output_tokens_details" : "completion_tokens_details"],
    ["reasoning_tokens"],
  )
  return {
    prompt_tokens: prompt,
    completion_tokens: completion,
    total_tokens: Number(prompt) + Number(completion),
    ...(promptDetails ? { prompt_tokens_details: promptDetails } : {}),
    ...(completionDetails ? { completion_tokens_details: completionDetails } : {}),
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
  if (type === "anthropic_messages") return anthropicCompletion(value, model, id)
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
  const output = Array.isArray(value.output) ? value.output.map((value) => object(value)) : []
  const calls = output.filter((x) => x.type === "function_call").map((x) => ({
    id: x.call_id,
    type: "function",
    function: { name: x.name, arguments: x.arguments },
  }))
  const text = output.filter((x) => x.type === "message").flatMap((x) =>
    Array.isArray(x.content) ? x.content.map((value) => object(value)) : []
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
  /** Last usage frame seen, for diagnostics. */
  usage: Obj | null = null
  private toolIndexes = new Map<number, number>()
  private created = Math.floor(Date.now() / 1000)
  private anthropic: AnthropicStream
  constructor(private model: string, private type: Connection["api_type"], private id: string) {
    this.anthropic = new AnthropicStream(model, id, this.created)
  }
  private chunk(delta: Obj, finish: string | null = null): Obj {
    return {
      id: this.id,
      object: "chat.completion.chunk",
      created: this.created,
      model: this.model,
      choices: [{ index: 0, delta: { role: "assistant", ...delta }, finish_reason: finish }],
    }
  }
  /** Characters of model output (text, reasoning, tool names and arguments) streamed so far. */
  streamedChars = 0
  /** The assistant text streamed so far, capped, for the local route trace only. */
  replyText = ""
  /** Prompt tokens the upstream reported before the end of the stream, when it did. */
  get promptTokens(): number | null {
    return this.anthropic.promptTokens
  }
  /** A reasoning delta the client counts as an event while the upstream is silent. */
  keepalive(): Obj {
    return this.chunk({ reasoning_content: "\u200b" })
  }
  accept(data: string): Obj[] {
    const out = this.acceptEvent(data)
    for (const chunk of out) {
      for (const choice of (chunk.choices as Obj[] | undefined) ?? []) {
        const d = choice.delta as Obj | undefined
        if (!d) continue
        for (const k of ["content", "reasoning_content", "reasoning"]) {
          if (typeof d[k] === "string") this.streamedChars += (d[k] as string).length
        }
        if (typeof d.content === "string" && this.replyText.length < 2000) {
          this.replyText = (this.replyText + d.content).slice(0, 2000)
        }
        for (const call of (d.tool_calls as Obj[] | undefined) ?? []) {
          const f = (call.function ?? {}) as Obj
          this.streamedChars += String(f.arguments ?? "").length + String(f.name ?? "").length
        }
      }
    }
    return out
  }
  private acceptEvent(data: string): Obj[] {
    if (data === "[DONE]") {
      if (this.type === "openai_chat") this.finished = true
      return []
    }
    const e = object(JSON.parse(data))
    if (e.error || ["error", "response.failed"].includes(String(e.type))) {
      throw new Error("Upstream error")
    }
    if (this.type === "anthropic_messages") {
      const out = this.anthropic.accept(e)
      this.finished = this.anthropic.finished
      this.tokens = this.anthropic.tokens
      this.usage = this.anthropic.usage
      return out
    }
    if (this.type === "openai_chat") {
      const u = usage(e.usage)
      if (u) {
        this.tokens = Number(u.total_tokens)
        this.usage = u
      }
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
      if (u) {
        this.tokens = Number(u.total_tokens)
        this.usage = u
      }
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
