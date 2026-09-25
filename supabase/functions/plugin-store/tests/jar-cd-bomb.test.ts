import {
  assertEquals,
  assertStringIncludes,
} from "https://deno.land/std@0.224.0/assert/mod.ts"
import { extractManifestFromRemoteJar } from "../services/github.ts"

/**
 * Regression tests for the #914 caps on the remote-JAR central-directory
 * reader: a crafted JAR whose CD declares multi-GB sizes must be refused
 * before any fetch or buffer, not OOM the edge isolate.
 *
 * The bounds live on the two constants the reader consults; the tests call
 * the module's downloadRange via the exported extraction entrypoint with a
 * stubbed fetch that records the requested Range, and with crafted CD bytes.
 */

// The function under test is not exported; exercise the bounds through the
// public extraction path with stubbed fetch. Tests use the SAME module file.

// Minimal helpers to build a fake ZIP byte layout ---------------------------------

function u16(v: number): number[] { return [v & 0xff, (v >> 8) & 0xff] }
function u32(v: number): number[] {
  return [v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff]
}

/** EOCD (22 bytes) with CD offset/size pointing at cdOffset/cdSize. */
function eocd(cdOffset: number, cdSize: number, entries = 1): number[] {
  return [
    ...u32(0x06054b50), ...u16(0), ...u16(0), ...u16(entries), ...u16(entries),
    ...u32(cdSize), ...u32(cdOffset), ...u16(0),
  ]
}

/**
 * A CD entry for plugin.json with the declared sizes and a method byte.
 *
 * Fixed header is 46 bytes (sig 4 + 15 fields totalling 42); every field the
 * parser reads (method at +10, compressedSize at +20, uncompressedSize at +24,
 * name-len at +28, local-offset at +42) must line up with those offsets, or
 * the crafted sizes never reach the cap checks at all and the tests exercise
 * nothing.
 */
function cdEntry(
  compressedSize: number,
  uncompressedSize?: number,
  method: number = 0,
): number[] {
  const name = Array.from(new TextEncoder().encode("META-INF/boss-plugin/plugin.json"))
  const uncomp = uncompressedSize ?? compressedSize
  return [
    ...u32(0x02014b50), // central-dir signature
    ...u16(20), ...u16(20), ...u16(0), // made-by, version-needed, flags
    ...u16(method), // method (read at offset +10)
    ...u16(0), ...u16(0), ...u32(0), // time, date, crc32
    ...u32(compressedSize), // compressed (read at +20)
    ...u32(uncomp), // uncompressed (read at +24)
    ...u16(name.length), // name-len (read at +28)
    ...u16(0), ...u16(0), // extra-len, comment-len
    ...u16(0), ...u16(0), ...u32(0), // disk-start, int-attrs, ext-attrs
    ...u32(0), // local header offset (read at +42)
    ...name, // filename (read at +46)
  ]
}

function fakeFetchWithJar(body: number[], tailBytes: number) {
  const total = body.length
  return (_url: string, init?: RequestInit) => {
    const range = (init?.headers as Record<string, string>)?.Range ?? ""
    const m = range.match(/bytes=(\d+)-(\d+)/)
    if (m) {
      const start = parseInt(m[1], 10), end = Math.min(parseInt(m[2], 10), total - 1)
      const slice = new Uint8Array(body.slice(start, end + 1))
      return Promise.resolve(new Response(slice, {
        status: 206,
        headers: { "Content-Range": `bytes ${start}-${end}/${total}` },
      }))
    }
    return Promise.resolve(new Response(new Uint8Array(body), {
      status: 200,
      headers: { "Content-Length": String(total) },
    }))
  }
}

Deno.test("a central directory declaring a multi-GB size is refused, not fetched", async () => {
  const evilCdSize = 2 * 1024 * 1024 * 1024 // 2GB in the CD header
  // Layout: [CD entry][padding > 65KB][EOCD pointing at the CD with evil size].
  // The padding pushes the CD out of the 65KB tail window so the parser takes
  // the downloadRange path; the 2GB span then trips MAX_CD_FETCH_BYTES.
  const entry = cdEntry(10)
  const cdOffset = 0
  const padding = new Array(70_000).fill(0)
  const eocdBytes = eocd(cdOffset, evilCdSize)
  const body = [...entry, ...padding, ...eocdBytes]

  const originalFetch = globalThis.fetch
  globalThis.fetch = fakeFetchWithJar(body, 0) as typeof fetch
  try {
    let threw = false
    let msg = ""
    try {
      await extractManifestFromRemoteJar("https://evil.example/x.jar")
    } catch (e) {
      threw = true
      msg = String((e as Error)?.message ?? e)
    }
    assertEquals(threw, true, "the oversized CD must fail the extraction")
    // Asserting on the specific cap message (not a bag of substrings) makes
    // removing the span check a regression: without it, the parser walks a
    // truncated CD and exits with "manifest not found".
    assertEquals(
      msg.startsWith(
        "Refusing a 2147483648-byte range fetch above the 4194304-byte cap",
      ),
      true,
      `expected the cdSize cap message, got: ${msg}`,
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})

Deno.test("a plugin.json entry declaring a multi-GB compressedSize is refused before its fetch", async () => {
  const evilEntrySize = 2 * 1024 * 1024 * 1024
  const entry = cdEntry(evilEntrySize)
  const cdOffset = 0
  const eocdBytes = eocd(cdOffset, entry.length)
  const body = [...entry, ...eocdBytes]

  let maxRangeSpan = 0
  const originalFetch = globalThis.fetch
  const inner = fakeFetchWithJar(body, 0)
  globalThis.fetch = ((url: string, init?: RequestInit) => {
    const range = (init?.headers as Record<string, string>)?.Range ?? ""
    const m = range.match(/bytes=(\d+)-(\d+)/)
    if (m) {
      const span = parseInt(m[2], 10) - parseInt(m[1], 10) + 1
      if (span > maxRangeSpan) maxRangeSpan = span
    }
    return inner(url, init)
  }) as typeof fetch
  try {
    let threw = false
    let msg = ""
    try {
      await extractManifestFromRemoteJar("https://evil.example/x.jar")
    } catch (e) {
      threw = true
      msg = e instanceof Error ? e.message : String(e)
    }
    assertEquals(threw, true, "the oversized entry must fail the extraction")
    // The declared-size entry guard itself must fire, not a later clamp.
    assertStringIncludes(msg, "compressed bytes, above the 524288-byte manifest bound")
    // No fetch may ever span anywhere near the declared 2GB.
    assertEquals(maxRangeSpan < 5 * 1024 * 1024, true, `largest requested span was ${maxRangeSpan}`)
  } finally {
    globalThis.fetch = originalFetch
  }
})

Deno.test("a small compressed entry declaring a huge uncompressedSize is refused before inflating", async () => {
  const evilUncompressed = 2 * 1024 * 1024 * 1024 // 2GB declared inflate target
  const entry = cdEntry(100, evilUncompressed, 8) // method=8 = deflate
  const eocdBytes = eocd(0, entry.length)
  const body = [...entry, ...eocdBytes]

  const originalFetch = globalThis.fetch
  globalThis.fetch = fakeFetchWithJar(body, 0) as typeof fetch
  try {
    let threw = false
    let msg = ""
    try {
      await extractManifestFromRemoteJar("https://evil.example/deflate.jar")
    } catch (e) {
      threw = true
      msg = e instanceof Error ? e.message : String(e)
    }
    assertEquals(threw, true, "the deflate-bomb entry must fail the extraction")
    // The declared-uncompressed-size guard must fire before any inflate.
    assertStringIncludes(msg, "uncompressed bytes, above the 524288-byte manifest bound")
  } finally {
    globalThis.fetch = originalFetch
  }
})
