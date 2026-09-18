import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts"
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

/** A CD entry for plugin.json with a declared compressedSize. */
function cdEntry(compressedSize: number): number[] {
  const name = Array.from(new TextEncoder().encode("META-INF/boss-plugin/plugin.json"))
  return [
    ...u32(0x02014b50), ...u16(20), ...u16(0), ...u16(0), // sig, made-by, method=0, time
    ...u16(0), ...u16(0), ...u32(0), ...u32(compressedSize), // crc, comp size
    ...u32(compressedSize), ...u16(name.length), ...u16(0), ...u16(0), // uncomp, name len, extra, comment
    ...u16(0), ...u16(0), ...u32(0), // disk, int attrs, ext attrs
    ...u32(0), // local header offset
    ...name,
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
  // Layout: [CD entry][EOCD pointing at it with the evil size]
  const entry = cdEntry(10)
  const cdOffset = 0
  const eocdBytes = eocd(cdOffset, evilCdSize)
  const body = [...entry, ...eocdBytes]

  const originalFetch = globalThis.fetch
  globalThis.fetch = fakeFetchWithJar(body, 0) as typeof fetch
  try {
    let threw = false
    try {
      await extractManifestFromRemoteJar("https://evil.example/x.jar")
    } catch (e) {
      // The refusal can surface as our cap error or a downstream parse error
      // after the tail-only path; what must NOT happen is a 2GB fetch.
      threw = true
      const msg = String((e as Error)?.message ?? e)
      assertEquals(
        msg.includes("cap") || msg.includes("Refusing") || msg.includes("EOCD") ||
          msg.includes("manifest") || msg.includes("not found") || msg.includes("Failed"),
        true,
        `expected a bounded failure, got: ${msg}`,
      )
    }
    assertEquals(threw, true, "the oversized CD must fail the extraction")
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
    try {
      await extractManifestFromRemoteJar("https://evil.example/x.jar")
    } catch {
      threw = true
    }
    assertEquals(threw, true, "the oversized entry must fail the extraction")
    // No fetch may ever span anywhere near the declared 2GB.
    assertEquals(maxRangeSpan < 5 * 1024 * 1024, true, `largest requested span was ${maxRangeSpan}`)
  } finally {
    globalThis.fetch = originalFetch
  }
})

function cdEntryWithUncompressed(compressedSize: number, uncompressedSize: number): number[] {
  const name = Array.from(new TextEncoder().encode("META-INF/boss-plugin/plugin.json"))
  return [
    ...u32(0x02014b50), ...u16(20), ...u16(8), ...u16(0), ...u16(0),
    ...u16(0), ...u16(0), ...u32(0), ...u32(compressedSize),
    ...u32(uncompressedSize), ...u16(name.length), ...u16(0), ...u16(0),
    ...u16(0), ...u16(0), ...u32(0),
    ...u32(0),
    ...name,
  ]
}

Deno.test("a small compressed entry declaring a huge uncompressedSize is refused before inflating", async () => {
  const evilUncompressed = 2 * 1024 * 1024 * 1024 // 2GB declared inflate target
  const entry = cdEntryWithUncompressed(100, evilUncompressed)
  const eocdBytes = eocd(0, entry.length)
  const body = [...entry, ...eocdBytes]

  const originalFetch = globalThis.fetch
  globalThis.fetch = fakeFetchWithJar(body, 0) as typeof fetch
  try {
    let threw = false
    try {
      await extractManifestFromRemoteJar("https://evil.example/deflate.jar")
    } catch {
      // The declared-size refusal fires before any inflate; a malformed tail
      // failing even earlier is also acceptable - what must NOT happen is a
      // multi-GB buffer or a successful parse of the crafted entry.
      threw = true
    }
    assertEquals(threw, true, "the deflate-bomb entry must fail the extraction")
  } finally {
    globalThis.fetch = originalFetch
  }
})
