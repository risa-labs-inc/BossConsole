import { assert, assertRejects } from "@std/assert"
import {
  downloadJar,
  downloadReleaseAsset,
  extractManifestFromRemoteJar,
  LARGE_JAR_THRESHOLD,
} from "../services/github.ts"

const CHUNK = 64 * 1024
const HARD_STOP = 100 * 1024 * 1024

/**
 * A response body that never ends and reports how much of it was pulled. There
 * is no Content-Length, which is exactly what a hostile or misbehaving origin
 * sends: the only defence is to stop reading.
 */
function endlessBody(counter: { pulled: number }, status = 200, headers: HeadersInit = {}): Response {
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      // "Endless" for the purposes of every cap under test, but finite so that
      // running this against unbounded code fails instead of exhausting memory.
      if (counter.pulled >= HARD_STOP) {
        controller.close()
        return
      }
      counter.pulled += CHUNK
      controller.enqueue(new Uint8Array(CHUNK))
    },
  })
  return new Response(stream, { status, headers })
}

async function withFetch<T>(stub: typeof fetch, body: () => Promise<T>): Promise<T> {
  const original = globalThis.fetch
  globalThis.fetch = stub
  try {
    return await body()
  } finally {
    globalThis.fetch = original
  }
}

function assertStoppedNearCap(counter: { pulled: number }, cap: number) {
  // A few chunks of read-ahead are fine; hundreds of megabytes are not.
  assert(
    counter.pulled <= cap + 8 * CHUNK,
    `pulled ${counter.pulled} bytes from an endless body, expected to stop near the ${cap}-byte cap`,
  )
}

Deno.test("downloadJar stops reading an endless body at the size cap", async () => {
  const counter = { pulled: 0 }
  await withFetch(() => Promise.resolve(endlessBody(counter)), async () => {
    await assertRejects(() => downloadJar("https://github.com/x/y/releases/download/v1/a.jar"))
  })
  assertStoppedNearCap(counter, LARGE_JAR_THRESHOLD)
})

Deno.test("downloadReleaseAsset stops reading an endless body at the size cap", async () => {
  const counter = { pulled: 0 }
  Deno.env.delete("GITHUB_TOKEN")
  await withFetch(() => Promise.resolve(endlessBody(counter)), async () => {
    await assertRejects(() =>
      downloadReleaseAsset({
        id: 1,
        name: "a.jar",
        size: 1024,
        url: "https://api.github.com/repos/x/y/releases/assets/1",
        browser_download_url: "https://github.com/x/y/releases/download/v1/a.jar",
      } as never)
    )
  })
  assertStoppedNearCap(counter, LARGE_JAR_THRESHOLD)
})

Deno.test("downloadJar still returns a normal jar", async () => {
  const bytes = new Uint8Array(1234).fill(7)
  const result = await withFetch(() => Promise.resolve(new Response(bytes)), () => downloadJar("https://github.com/x/y/a.jar"))
  assert(result.byteLength === 1234)
})

Deno.test("a server that ignores Range cannot make the manifest reader buffer the whole jar", async () => {
  const counter = { pulled: 0 }
  const stub: typeof fetch = (_input, init) => {
    if (init?.method === "HEAD") {
      return Promise.resolve(new Response(null, { headers: { "Content-Length": String(200 * 1024 * 1024) } }))
    }
    // Range is ignored: 200 with an endless body instead of a 206 slice.
    return Promise.resolve(endlessBody(counter))
  }
  await withFetch(stub, async () => {
    await assertRejects(() => extractManifestFromRemoteJar("https://github.com/x/y/releases/download/v1/a.jar"))
  })
  // The tail request asks for 64 KiB; nothing close to the jar should be read.
  assertStoppedNearCap(counter, 65_536 + 4096)
})
