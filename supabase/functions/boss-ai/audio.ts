import { HttpError } from "./auth.ts"
import { invalid, Model, Obj, object } from "./wire.ts"

const FORMATS = ["wav", "mp3", "webm", "ogg", "flac", "m4a"]
const MAX_SPEECH_CHARS = 2_000
const CHARS_PER_SECOND = 14
const WAV_HEAD_CHARS = 8_192
const MIN_WAV_BYTE_RATE = 2_000
const MAX_WAV_BYTE_RATE = 1_536_000
export const MAX_AUDIO_OUTPUT_BYTES = 16 * 1024 * 1024

function onlyKeys(value: Obj, keys: string[]) {
  if (Object.keys(value).some((key) => !keys.includes(key))) throw invalid()
}

export function wrongKind(): HttpError {
  return new HttpError(400, "wrong_model_kind", "This endpoint does not serve the selected model.")
}

/** Upstream body for a transcription. The payload is forwarded undecoded. */
export function transcriptionBody(
  input: Obj,
  model: Model,
): { body: Obj; audio: string; format: string } {
  onlyKeys(input, ["model", "input_audio", "language"])
  const audio = object(input.input_audio)
  onlyKeys(audio, ["data", "format"])
  const { data, format } = audio
  if (
    typeof data !== "string" || data.length < 4 || data.length % 4 !== 0 ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(data) || typeof format !== "string" || !FORMATS.includes(format)
  ) throw invalid()
  const body: Obj = { model: model.upstream_model, input_audio: { data, format } }
  if (input.language !== undefined) {
    if (
      typeof input.language !== "string" ||
      !/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(input.language)
    ) {
      throw invalid()
    }
    body.language = input.language
  }
  return { body, audio: data, format }
}

/**
 * Seconds of a base64 PCM WAV, or null when it cannot be trusted. Walks the RIFF chunks of the first
 * decoded kilobytes, requires a self-consistent PCM `fmt ` chunk, and divides the bytes that really
 * follow the `data` header by the byte rate (the declared sizes are client-controlled).
 */
export function wavSeconds(data: string): number | null {
  try {
    const head = Uint8Array.from(
      atob(data.slice(0, WAV_HEAD_CHARS)),
      (c) => c.charCodeAt(0),
    )
    const view = new DataView(head.buffer)
    const text = (at: number) => String.fromCharCode(...head.subarray(at, at + 4))
    if (head.length < 12 || text(0) !== "RIFF" || text(8) !== "WAVE") return null
    let rate = 0
    let at = 12
    while (at + 8 <= head.length) {
      const id = text(at)
      const size = view.getUint32(at + 4, true)
      const body = at + 8
      if (id === "fmt ") {
        if (size < 16 || body + 16 > head.length) return null
        const format = view.getUint16(body, true)
        const channels = view.getUint16(body + 2, true)
        const sampleRate = view.getUint32(body + 4, true)
        const byteRate = view.getUint32(body + 8, true)
        const blockAlign = view.getUint16(body + 12, true)
        const bits = view.getUint16(body + 14, true)
        if (format === 0xFFFE) {
          if (size < 40 || body + 26 > head.length || view.getUint16(body + 24, true) !== 1) {
            return null
          }
        } else if (format !== 1) return null
        if (
          channels < 1 || channels > 8 || bits < 8 || bits % 8 !== 0 || bits > 32 ||
          blockAlign !== channels * bits / 8 || sampleRate < 1 ||
          byteRate !== sampleRate * blockAlign || byteRate < MIN_WAV_BYTE_RATE ||
          byteRate > MAX_WAV_BYTE_RATE
        ) return null
        rate = byteRate
      } else if (id === "data") {
        if (rate === 0) return null
        const padding = data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0
        const bytes = data.length * 3 / 4 - padding - body
        return bytes >= 0 ? bytes / rate : null
      }
      at = body + size + (size & 1)
    }
    return null
  } catch {
    return null
  }
}

/** Upstream-reported seconds, else the WAV estimate, else null (keep the reservation). */
export function transcriptionSeconds(payload: Obj, audio: string, format: string): number | null {
  const reported = (payload.usage as Obj | undefined)?.seconds
  if (typeof reported === "number" && Number.isFinite(reported) && reported >= 0) return reported
  return format === "wav" ? wavSeconds(audio) : null
}

export interface Remaining {
  remaining_seconds: number
  resets_at: string
}

/**
 * The least time left across a `boss_ai_usage` result's periods, and when it actually grows back: the latest
 * reset among the periods tied at that minimum (a day resetting frees nothing while the month is spent).
 */
export function smallestRemaining(usage: unknown): Remaining | null {
  if (typeof usage !== "object" || usage === null) return null
  let best: Remaining | null = null
  for (const period of Object.values(usage as Obj)) {
    const { remaining, resets_at } = (period ?? {}) as Obj
    if (
      typeof remaining !== "number" || !Number.isFinite(remaining) ||
      typeof resets_at !== "string"
    ) continue
    if (
      best === null || remaining < best.remaining_seconds ||
      (remaining === best.remaining_seconds && Date.parse(resets_at) > Date.parse(best.resets_at))
    ) {
      best = { remaining_seconds: remaining, resets_at }
    }
  }
  return best
}

export function speechBody(input: Obj, model: Model): { body: Obj; seconds: number } {
  // OpenAI's optional speech fields, as SDK clients send them, are accepted and ignored: the output is always mp3
  // streamed as bytes, at normal speed.
  onlyKeys(input, [
    "model",
    "input",
    "voice",
    "response_format",
    "speed",
    "stream_format",
    "instructions",
  ])
  if (input.response_format !== undefined && input.response_format !== "mp3") throw invalid()
  const text = input.input
  if (typeof text !== "string" || !text.length || text.length > MAX_SPEECH_CHARS) {
    throw new HttpError(400, "invalid_input", "Speech input must be 1 to 2000 characters.")
  }
  if (text.length > model.max_output_tokens * CHARS_PER_SECOND) {
    throw new HttpError(400, "invalid_input", "Speech input is too long for one request.")
  }
  if (
    input.voice !== undefined &&
    (typeof input.voice !== "string" || !/^[A-Za-z0-9._:-]{1,64}$/.test(input.voice))
  ) throw invalid()
  const body: Obj = { model: model.upstream_model, input: text, response_format: "mp3" }
  const voice = input.voice ?? model.voice
  if (voice) body.voice = voice
  return { body, seconds: Math.max(1, Math.ceil(text.length / CHARS_PER_SECOND)) }
}

/** Streams bytes through, erroring past the cap. `done` runs once on end, error or cancel. */
export function capped(
  body: ReadableStream<Uint8Array>,
  max: number,
  done: () => void,
): ReadableStream<Uint8Array> {
  const reader = body.getReader()
  let size = 0
  let finished = false
  const end = () => {
    if (finished) return
    finished = true
    done()
  }
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const next = await reader.read()
        if (next.done) {
          end()
          controller.close()
          return
        }
        size += next.value.length
        if (size > max) throw new Error("Audio response too large")
        controller.enqueue(next.value)
      } catch (error) {
        end()
        await reader.cancel().catch(() => {})
        controller.error(error)
      }
    },
    async cancel(reason) {
      end()
      await reader.cancel(reason).catch(() => {})
    },
  })
}

const MAX_UPLOAD_BYTES = 4 * 1024 * 1024
const UPLOAD_FORMATS: Record<string, string> = {
  "audio/wav": "wav",
  "audio/x-wav": "wav",
  "audio/wave": "wav",
  "audio/mpeg": "mp3",
  "audio/mp3": "mp3",
  "audio/webm": "webm",
  "audio/ogg": "ogg",
  "audio/flac": "flac",
  "audio/mp4": "m4a",
  "audio/x-m4a": "m4a",
}

export function isMultipart(request: Request): boolean {
  return /^multipart\/form-data\b/i.test(request.headers.get("content-type") ?? "")
}

/**
 * OpenAI's own upload shape (multipart `file`, `model`, optional `language`), as SDK clients send it, turned into the
 * JSON body transcriptionBody validates. Other OpenAI fields (response_format, prompt, temperature) are ignored.
 */
export async function multipartTranscription(request: Request): Promise<Obj> {
  const declared = Number(request.headers.get("content-length") ?? "0")
  if (declared > MAX_UPLOAD_BYTES) {
    throw new HttpError(413, "too_large", "The AI request or response is too large.")
  }
  let form: FormData
  try {
    form = await request.formData()
  } catch {
    throw invalid()
  }
  const file = form.get("file")
  const model = form.get("model")
  if (!(file instanceof File) || typeof model !== "string") throw invalid()
  if (file.size === 0 || file.size > MAX_UPLOAD_BYTES) {
    throw new HttpError(413, "too_large", "The AI request or response is too large.")
  }
  const ext = file.name.split(".").pop()?.toLowerCase() ?? ""
  const format = UPLOAD_FORMATS[file.type.toLowerCase()] ?? (FORMATS.includes(ext) ? ext : "")
  if (!format) throw invalid()
  const bytes = new Uint8Array(await file.arrayBuffer())
  let binary = ""
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  }
  const body: Obj = { model, input_audio: { data: btoa(binary), format } }
  const language = form.get("language")
  if (typeof language === "string" && language) body.language = language
  return body
}
