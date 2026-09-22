/**
 * Thrown when a response body is larger than the caller is willing to hold.
 * Distinct from a network failure so callers can word the refusal honestly.
 */
export class BodyTooLargeError extends Error {
  constructor(message: string) {
    super(message)
    this.name = "BodyTooLargeError"
  }
}

/**
 * Read a response body into memory, refusing to hold more than [maxBytes].
 *
 * `await resp.arrayBuffer()` buffers whatever the origin sends and only then
 * lets the caller look at the size. A `Content-Length` header is a claim, not a
 * limit (it is absent on chunked responses and a hostile origin simply lies),
 * so the size check has to happen while reading: the stream is consumed chunk by
 * chunk, and the moment the running total passes the cap the reader is cancelled
 * and the read fails. At most one chunk over the cap is ever resident.
 *
 * A declared length over the cap is refused before a byte is read.
 */
export async function readBodyCapped(resp: Response, maxBytes: number, label: string): Promise<Uint8Array> {
  const declared = Number(resp.headers.get("content-length") ?? "")
  if (Number.isFinite(declared) && declared > maxBytes) {
    await resp.body?.cancel().catch(() => {})
    throw new BodyTooLargeError(`${label} declares ${declared} bytes, over the ${maxBytes}-byte cap`)
  }

  if (!resp.body) return new Uint8Array(0)

  const reader = resp.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      total += value.byteLength
      if (total > maxBytes) {
        await reader.cancel().catch(() => {})
        throw new BodyTooLargeError(`${label} is larger than the ${maxBytes}-byte cap`)
      }
      chunks.push(value)
    }
  } finally {
    reader.releaseLock()
  }

  const out = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    out.set(chunk, offset)
    offset += chunk.byteLength
  }
  return out
}
