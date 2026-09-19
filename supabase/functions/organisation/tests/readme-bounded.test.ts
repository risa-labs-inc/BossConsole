import { assert, assertEquals } from "@std/assert"
import { fetchReadme } from "../services/readme.ts"

const CHUNK = 64 * 1024
const HARD_STOP = 100 * 1024 * 1024

function endlessText(counter: { pulled: number }): Response {
  const line = new TextEncoder().encode("readme line\n".repeat(CHUNK / 12))
  return new Response(
    new ReadableStream<Uint8Array>({
      pull(controller) {
        if (counter.pulled >= HARD_STOP) {
          controller.close()
          return
        }
        counter.pulled += line.byteLength
        controller.enqueue(line)
      },
    }),
  )
}

Deno.test("a README body is read only up to the display cap, not buffered whole", async () => {
  const original = globalThis.fetch
  const counter = { pulled: 0 }
  globalThis.fetch = () => Promise.resolve(endlessText(counter))
  try {
    const text = await fetchReadme("https://github.com/acme/endless-readme")
    assert(text !== null)
    assert(text.endsWith("[truncated]"), "an oversized README is shown truncated")
    assert(text.length < 70 * 1024)
  } finally {
    globalThis.fetch = original
  }
  // The shown cap is 64 KiB of text; a few hundred KiB of read-ahead is fine, 100 MiB is not.
  assert(counter.pulled <= 1024 * 1024, `pulled ${counter.pulled} bytes`)
})

Deno.test("a small README is returned whole", async () => {
  const original = globalThis.fetch
  globalThis.fetch = () => Promise.resolve(new Response("# Hello\n\nworld"))
  try {
    assertEquals(await fetchReadme("https://github.com/acme/small-readme"), "# Hello\n\nworld")
  } finally {
    globalThis.fetch = original
  }
})
