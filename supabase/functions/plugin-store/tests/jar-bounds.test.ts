/**
 * Bounds tests for the remote-JAR central-directory reader. Fixes #914.
 *
 * Before the fix, the ZIP End-of-Central-Directory record was treated as
 * authoritative: the EOCD-declared `cdSize` and `cdOffset` were used
 * directly as range-fetch bounds and as in-memory slice sizes, and the
 * central-directory-declared `compressedSize` was used as the per-entry
 * range-fetch bound without any cap. A crafted JAR declaring a ~2 GB central
 * directory or a ~2 GB single entry would OOM the edge isolate per request.
 *
 * These tests build small synthetic ZIPs whose EOCD / central-directory
 * entries declare attacker-controlled sizes far above the fix's caps and
 * assert that the parser rejects them, without reading the body bytes. They
 * also exercise the happy path so a regression to the cap surfaces here
 * rather than in production.
 */

import {
  assertEquals,
  assertRejects,
  assertStringIncludes,
} from "@std/assert"
import {
  extractManifestFromJar,
  extractManifestFromRemoteJar,
  MAX_CENTRAL_DIR_BYTES,
  MAX_ENTRY_FETCH_BYTES,
} from "../services/github.ts"

/**
 * Build a single-entry ZIP containing `META-INF/boss-plugin/plugin.json`,
 * with attacker-controlled EOCD / central-directory fields. The returned
 * buffer is well-formed enough that the in-memory parser reaches the EOCD
 * we crafted, but the crafted fields are what the test is exercising.
 */
function buildJarWithManifest(
  manifestJson: string,
  overrides: {
    /** Override the central-directory EOCD size field (uint32, little-endian). */
    eocdCdSize?: number
    /** Override the central-directory EOCD offset field (uint32, little-endian). */
    eocdCdOffset?: number
    /** Override the central-directory entry compressedSize (uint32, little-endian). */
    entryCompressedSize?: number
  } = {}
): Uint8Array {
  const filename = new TextEncoder().encode("META-INF/boss-plugin/plugin.json")
  const payload = new TextEncoder().encode(manifestJson)
  const crc = 0 // not validated by the reader, leave at zero

  // --- Local file header ---
  const lfh = new Uint8Array(30 + filename.length)
  const lfhView = new DataView(lfh.buffer)
  lfhView.setUint32(0, 0x04034b50, true) // signature
  lfhView.setUint16(4, 20, true) // version needed
  lfhView.setUint16(6, 0, true) // flags
  lfhView.setUint16(8, 0, true) // compression = stored
  lfhView.setUint16(10, 0, true) // mtime
  lfhView.setUint16(12, 0, true) // mdate
  lfhView.setUint32(14, crc >>> 0, true) // crc32
  lfhView.setUint32(18, payload.length, true) // compressed size
  lfhView.setUint32(22, payload.length, true) // uncompressed size
  lfhView.setUint16(26, filename.length, true) // filename length
  lfhView.setUint16(28, 0, true) // extra length
  lfh.set(filename, 30)

  // --- Central directory entry ---
  const cd = new Uint8Array(46 + filename.length)
  const cdView = new DataView(cd.buffer)
  cdView.setUint32(0, 0x02014b50, true) // signature
  cdView.setUint16(4, 20, true) // version made by
  cdView.setUint16(6, 20, true) // version needed
  cdView.setUint16(8, 0, true) // flags
  cdView.setUint16(10, 0, true) // compression
  cdView.setUint16(12, 0, true) // mtime
  cdView.setUint16(14, 0, true) // mdate
  cdView.setUint32(16, crc >>> 0, true) // crc32
  cdView.setUint32(
    20,
    overrides.entryCompressedSize ?? payload.length,
    true
  ) // compressed size (attacker-controlled in the bug)
  cdView.setUint32(24, payload.length, true) // uncompressed size
  cdView.setUint16(28, filename.length, true) // filename length
  cdView.setUint16(30, 0, true) // extra length
  cdView.setUint16(32, 0, true) // comment length
  cdView.setUint16(34, 0, true) // disk number start
  cdView.setUint16(36, 0, true) // internal file attributes
  cdView.setUint32(38, 0, true) // external file attributes
  cdView.setUint32(42, 0, true) // local header offset
  cd.set(filename, 46)

  const cdOffset = lfh.length + payload.length
  const cdSize = cd.length

  // --- End of central directory record ---
  const eocd = new Uint8Array(22)
  const eocdView = new DataView(eocd.buffer)
  eocdView.setUint32(0, 0x06054b50, true) // signature
  eocdView.setUint16(4, 0, true) // disk number
  eocdView.setUint16(6, 0, true) // disk with cd start
  eocdView.setUint16(8, 1, true) // entries on this disk
  eocdView.setUint16(10, 1, true) // total entries
  eocdView.setUint32(12, overrides.eocdCdSize ?? cdSize, true) // cd size (attacker-controlled)
  eocdView.setUint32(16, overrides.eocdCdOffset ?? cdOffset, true) // cd offset (attacker-controlled)
  eocdView.setUint16(20, 0, true) // comment length

  // Concat the pieces
  const total = lfh.length + payload.length + cd.length + eocd.length
  const out = new Uint8Array(total)
  out.set(lfh, 0)
  out.set(payload, lfh.length)
  out.set(cd, lfh.length + payload.length)
  out.set(eocd, lfh.length + payload.length + cd.length)
  return out
}

const SAMPLE_MANIFEST = JSON.stringify({
  pluginId: "com.example.fix914",
  displayName: "Fix 914 Test",
  version: "1.0.0",
  apiVersion: "1.0.0",
  mainClass: "com.example.Main",
})

Deno.test("jar bounds: a normal JAR's manifest is parsed", async () => {
  const jar = buildJarWithManifest(SAMPLE_MANIFEST)
  // Uint8Array.buffer is typed ArrayBufferLike (includes SharedArrayBuffer),
  // so slice first to hand the parser a plain ArrayBuffer.
  const manifest = await extractManifestFromJar(jar.slice().buffer)
  assertEquals(manifest.pluginId, "com.example.fix914")
})

Deno.test("jar bounds: an EOCD-declared cdSize above MAX_CENTRAL_DIR_BYTES is rejected", async () => {
  const oversize = MAX_CENTRAL_DIR_BYTES + 1
  const jar = buildJarWithManifest(SAMPLE_MANIFEST, { eocdCdSize: oversize })
  // Uint8Array.buffer is typed ArrayBufferLike (includes SharedArrayBuffer),
  // so slice first to hand the parser a plain ArrayBuffer.
  const err = await assertRejects(() =>
    Promise.resolve(extractManifestFromJar(jar.slice().buffer))
  )
  // The fix raises from the in-memory path before any allocation happens;
  // the rejection must name the cap so an operator can recognise it.
  assertStringIncludes(
    (err as Error).message,
    `${MAX_CENTRAL_DIR_BYTES}-byte cap`
  )
})

Deno.test("jar bounds: an EOCD-declared cdOffset past end of JAR is rejected", async () => {
  // Use a cdOffset well past the actual JAR end (the JAR is tiny).
  const jar = buildJarWithManifest(SAMPLE_MANIFEST, { eocdCdOffset: 0xfffffffe })
  await assertRejects(() => Promise.resolve(extractManifestFromJar(jar.slice().buffer)))
})

Deno.test("jar bounds: a central-directory entry compressedSize above MAX_ENTRY_FETCH_BYTES is rejected", async () => {
  const oversize = MAX_ENTRY_FETCH_BYTES + 1
  const jar = buildJarWithManifest(SAMPLE_MANIFEST, {
    entryCompressedSize: oversize,
  })
  const err = await assertRejects(() =>
    Promise.resolve(extractManifestFromJar(jar.slice().buffer))
  )
  assertStringIncludes(
    (err as Error).message,
    `${MAX_ENTRY_FETCH_BYTES}-byte cap`
  )
})

Deno.test("jar bounds: the remote path rejects a 2 GB EOCD-declared cdSize without buffering it", async () => {
  // The remote path makes HEAD + range requests. We simulate a hostile server
  // by having fetch return a small EOCD with a crafted (huge) cdSize field.
  // The fix must reject on the EOCD read before any range request goes out
  // for the central directory, and must never call arrayBuffer() with a
  // 2 GB-bound size.
  let totalSize = 1000
  const eocd = new Uint8Array(22)
  const v = new DataView(eocd.buffer)
  v.setUint32(0, 0x06054b50, true)
  v.setUint16(10, 1, true) // 1 entry
  v.setUint32(12, 0x7fffffff, true) // cdSize ~ 2 GB (attacker-controlled)
  v.setUint32(16, 0, true) // cdOffset
  v.setUint16(20, 0, true)

  // Stuff the EOCD at the end of the tail
  const tailLen = 65_536
  const tail = new Uint8Array(tailLen)
  tail.set(eocd, tailLen - eocd.length)

  let bytesRequested = 0
  const originalFetch = globalThis.fetch
  globalThis.fetch = ((
    input: RequestInfo | URL,
    init?: RequestInit
  ) => {
    const url = typeof input === "string" ? input : (input as URL).toString()
    if (init?.method === "HEAD") {
      return Promise.resolve(
        new Response(null, {
          status: 200,
          headers: { "Content-Length": String(totalSize) },
        })
      )
    }
    if (init?.headers) {
      const h = init.headers as Record<string, string>
      const range = h["Range"] || h["range"]
      if (range && range.startsWith("bytes=")) {
        const [s, e] = range.slice(6).split("-").map(Number)
        const len = e - s + 1
        bytesRequested += len
        return Promise.resolve(
          new Response(tail.slice(s, e + 1), { status: 206 })
        )
      }
    }
    return Promise.resolve(new Response("", { status: 404 }))
  }) as typeof fetch

  try {
    await assertRejects(() =>
      extractManifestFromRemoteJar("https://example.com/hostile.jar")
    )
    // Critical: the fix must reject before requesting the huge range, so we
    // never spend 2 GB of network or memory on the attacker-controlled cdSize.
    assertEquals(
      bytesRequested <= tailLen,
      true,
      `fetched ${bytesRequested} bytes; fix must not honour the 2 GB cdSize`
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})
