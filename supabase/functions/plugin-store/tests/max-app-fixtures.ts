/**
 * Builders for the Max app tests: the sample Habits jar (boss-plugin-max samples/apps/habits, built by Gradle; kept
 * as habits-1.0.0.jar.bin because the repository ignores *.jar), a
 * stored-entry zip writer to make variants of it, and a tiny class-file assembler for stub classes.
 */
import { readEntry, readZipEntries } from "../services/max-app.ts"

export const SAMPLE_PLUGIN_ID = "ai.rever.maxapp.habits"
export const SAMPLE_VERSION = "1.0.0"
export const SAMPLE_CLASS = "ai/rever/maxapp/habits/HabitsApp.class"

export const sampleJar = (): Uint8Array =>
  Deno.readFileSync(new URL("./fixtures/habits-1.0.0.jar.bin", import.meta.url))

const enc = new TextEncoder()
export const text = (s: string) => enc.encode(s)

/** The sample jar's entries, in order (directories as empty arrays). */
export async function sampleFiles(): Promise<Array<[string, Uint8Array]>> {
  const jar = sampleJar()
  const out: Array<[string, Uint8Array]> = []
  for (const e of readZipEntries(jar)) out.push([e.name, e.dir ? new Uint8Array(0) : await readEntry(jar, e)])
  return out
}

let CRC: Uint32Array | null = null
function crc32(d: Uint8Array): number {
  if (!CRC) {
    CRC = new Uint32Array(256)
    for (let n = 0; n < 256; n++) {
      let c = n
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      CRC[n] = c >>> 0
    }
  }
  let c = 0xffffffff
  for (const b of d) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** A zip of stored entries, in the given order. `prefix` puts bytes before the first entry. */
export function zip(files: Array<[string, Uint8Array]>, prefix: Uint8Array = new Uint8Array(0)): Uint8Array {
  const locals: Uint8Array[] = [prefix]
  const central: Uint8Array[] = []
  let offset = prefix.length
  for (const [name, data] of files) {
    const n = enc.encode(name)
    const crc = crc32(data)
    const lh = new Uint8Array(30 + n.length)
    const l = new DataView(lh.buffer)
    l.setUint32(0, 0x04034b50, true)
    l.setUint16(4, 20, true)
    l.setUint16(6, 0x800, true)
    l.setUint32(14, crc, true)
    l.setUint32(18, data.length, true)
    l.setUint32(22, data.length, true)
    l.setUint16(26, n.length, true)
    lh.set(n, 30)
    const ch = new Uint8Array(46 + n.length)
    const c = new DataView(ch.buffer)
    c.setUint32(0, 0x02014b50, true)
    c.setUint16(4, 20, true)
    c.setUint16(6, 20, true)
    c.setUint16(8, 0x800, true)
    c.setUint32(16, crc, true)
    c.setUint32(20, data.length, true)
    c.setUint32(24, data.length, true)
    c.setUint16(28, n.length, true)
    c.setUint32(42, offset, true)
    ch.set(n, 46)
    locals.push(lh, data)
    central.push(ch)
    offset += lh.length + data.length
  }
  const cdSize = central.reduce((a, b) => a + b.length, 0)
  const eocd = new Uint8Array(22)
  const e = new DataView(eocd.buffer)
  e.setUint32(0, 0x06054b50, true)
  e.setUint16(8, files.length, true)
  e.setUint16(10, files.length, true)
  e.setUint32(12, cdSize, true)
  e.setUint32(16, offset, true)
  const parts = [...locals, ...central, eocd]
  const out = new Uint8Array(parts.reduce((a, b) => a + b.length, 0))
  let o = 0
  for (const p of parts) {
    out.set(p, o)
    o += p.length
  }
  return out
}

/** The sample's files with some replaced, added (at the end) or removed (value null). */
export async function sampleVariant(
  changes: Record<string, Uint8Array | null> = {},
): Promise<Uint8Array> {
  const files = await sampleFiles()
  const out: Array<[string, Uint8Array]> = []
  for (const [name, data] of files) {
    if (name in changes) {
      const v = changes[name]
      if (v) out.push([name, v])
    } else out.push([name, data])
  }
  for (const [name, v] of Object.entries(changes)) {
    if (v && !files.some(([n]) => n === name)) out.push([name, v])
  }
  return zip(out)
}

/**
 * A class file: `name` extends `superName`, implements `interfaces`, refers to `refs` (CONSTANT_Class entries), and
 * declares one abstract method per descriptor in `methods`. `pad` bytes of string constant make it larger.
 */
export function classFile(opts: {
  name: string
  superName?: string
  interfaces?: string[]
  refs?: string[]
  methods?: Array<{ name: string; desc: string; flags?: number }>
  pad?: number
  extraTag?: number
}): Uint8Array {
  const pool: number[][] = []
  const utf = new Map<string, number>()
  const add = (bytes: number[]) => {
    pool.push(bytes)
    return pool.length
  }
  const u2 = (n: number) => [(n >> 8) & 0xff, n & 0xff]
  const u4 = (n: number) => [(n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff]
  const U = (s: string) => {
    if (!utf.has(s)) {
      const b = [...enc.encode(s)]
      utf.set(s, add([1, ...u2(b.length), ...b]))
    }
    return utf.get(s)!
  }
  const C = (s: string) => add([7, ...u2(U(s))])
  const self = C(opts.name)
  const sup = C(opts.superName ?? "java/lang/Object")
  const ifaces = (opts.interfaces ?? []).map(C)
  for (const r of opts.refs ?? []) C(r)
  if (opts.pad) U("x".repeat(opts.pad))
  if (opts.extraTag === 18) {
    const nat = add([12, ...u2(U("run")), ...u2(U("()V"))])
    add([18, ...u2(0), ...u2(nat)])
  }
  const methods = (opts.methods ?? []).map((m) => [...u2(m.flags ?? 0x0401), ...u2(U(m.name)), ...u2(U(m.desc)), ...u2(0)])
  const bytes = [
    ...u4(0xcafebabe), ...u2(0), ...u2(61),
    ...u2(pool.length + 1), ...pool.flat(),
    ...u2(0x0421), ...u2(self), ...u2(sup),
    ...u2(ifaces.length), ...ifaces.flatMap(u2),
    ...u2(0),
    ...u2(methods.length), ...methods.flat(),
    ...u2(0),
  ]
  return new Uint8Array(bytes)
}
