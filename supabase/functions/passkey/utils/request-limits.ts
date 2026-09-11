import type { MiddlewareHandler } from "npm:hono@^4.9.9"

// Large attestation chains fit, while JSON parsing and base64 decoding have a finite input budget.
export const MAX_PASSKEY_REQUEST_BYTES = 256 * 1024

/** Enforce the bytes actually read, including chunked bodies and misleading Content-Length. */
export const limitPasskeyRequest: MiddlewareHandler = async (ctx, next) => {
  const original = ctx.req.raw
  if (!original.body) return next()
  const reader = original.body.getReader()
  const body = new Uint8Array(MAX_PASSKEY_REQUEST_BYTES)
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
      body.set(chunk.value, bytes)
      bytes = nextSize
    }
  } finally {
    reader.releaseLock()
  }
  ctx.req.raw = new Request(original, { body: body.subarray(0, bytes) })
  return next()
}
