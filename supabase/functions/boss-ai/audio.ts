import { HttpError } from "./auth.ts"
import { invalid, Model, Obj, object } from "./wire.ts"

const FORMATS = ["wav", "mp3", "webm", "ogg", "flac", "m4a"]
const MAX_SPEECH_CHARS = 2_000
const CHARS_PER_SECOND = 14
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

/** Seconds of a base64 PCM WAV from its header and length, or null when it cannot be read. */
export function wavSeconds(data: string): number | null {
  try {
    const head = Uint8Array.from(atob(data.slice(0, 64)), (c) => c.charCodeAt(0))
    const text = (at: number) => String.fromCharCode(...head.slice(at, at + 4))
    if (text(0) !== "RIFF" || text(8) !== "WAVE") return null
    const rate = new DataView(head.buffer).getUint32(28, true)
    const padding = data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0
    const bytes = data.length * 3 / 4 - padding - 44
    return rate > 0 && bytes >= 0 ? bytes / rate : null
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
  onlyKeys(input, ["model", "input", "voice"])
  const text = input.input
  if (typeof text !== "string" || !text.length || text.length > MAX_SPEECH_CHARS) {
    throw new HttpError(400, "invalid_input", "Speech input must be 1 to 2000 characters.")
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
