import type { MiddlewareHandler } from "npm:hono@^4.9.9"

// Large attestation chains fit, while JSON parsing and base64 decoding have a finite input budget.
export const MAX_PASSKEY_REQUEST_BYTES = 256 * 1024

/** Enforce the bytes actually read, including chunked bodies and misleading Content-Length. */
export const limitPasskeyRequest: MiddlewareHandler = async (ctx, next) => {
  const original = ctx.req.raw
  if (!original.body) return next()
  const reader = original.body.getReader()
  let body = new Uint8Array(0)
  let bytes = 0
  try {
    while (true) {
      const chunk = await reader.read()
      if (chunk.done) break
      const nextSize = bytes + chunk.value.byteLength
      if (nextSize > MAX_PASSKEY_REQUEST_BYTES) {
        await reader.cancel()
        return ctx.json({ error: "Passkey request exceeds the supported size" }, 413)
      }
      // Grow only for bytes actually received; tiny requests do not reserve the ceiling.
      if (nextSize > body.byteLength) {
        const grown = new Uint8Array(Math.min(MAX_PASSKEY_REQUEST_BYTES,
          Math.max(nextSize, body.byteLength * 2, 1024)))
        grown.set(body.subarray(0, bytes))
        body = grown
      }
      body.set(chunk.value, bytes)
      bytes = nextSize
    }
  } finally {
    reader.releaseLock()
  }
  ctx.req.raw = new Request(original, { body: body.subarray(0, bytes) })
  return next()
}
