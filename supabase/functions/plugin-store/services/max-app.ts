/**
 * Content-only check for Max app jars (plugins.kind = 'max-app').
 *
 * A Max app is a BOSS plugin jar that carries a web page for Max under META-INF/max-app/. BOSS loads every plugin jar
 * with full JVM trust, so the store refuses any code beyond one inert stub class before a human reviews the version:
 *
 *   - Allowed entries: directories, META-INF/MANIFEST.MF, META-INF/boss-plugin/plugin.json, META-INF/max-app/ app.json,
 *     the entry page app.json names, SKILL.md, icon.svg / icon.png, and exactly one .class: the manifest's mainClass.
 *   - The class is at most 8 KB and is parsed properly (constant pool, fields, methods, attributes, to the last byte).
 *     Every class it can link to (CONSTANT_Class entries, and the types named by NameAndType, field and method
 *     descriptors) must be itself, a class directly in java/lang, or one directly in ai/rever/boss/plugin/api. Within
 *     java/lang the classes that reach the process, threads, class loading or reflection are refused too (DENIED_LANG),
 *     and so are method handles, invokedynamic and condy, and native methods: an inert stub needs none of them.
 *   - app.json follows Max's own rules (boss-plugin-max apps/MaxApps.kt load(), PluginApps.fromJar()), so a version
 *     the store accepts is one Max will list.
 *
 * The zip is read strictly: the central directory must describe every byte before it (no hidden local entries, no
 * prefix, no overlaps), local headers must agree with it, and each entry is inflated with its declared size as a hard
 * cap and checked against its CRC. Anything unexpected is a refusal with a code, never a best effort.
 *
 * Errors name the rule and the entry (entry names are checked to be plain paths first), never file contents.
 */

export class MaxAppJarError extends Error {
  constructor(readonly code: string, message: string) {
    super(message)
    this.name = "MaxAppJarError"
  }
}

const refuse = (code: string, message: string): never => {
  throw new MaxAppJarError(code, message)
}

// ----------------------------------------------------------------------------
// Limits (Max's own where Max has one)
// ----------------------------------------------------------------------------

/** The whole jar as uploaded. An app is a page and a few small files. */
export const MAX_APP_JAR_BYTES = 3 * 1024 * 1024
export const MAX_APP_ENTRIES = 64
export const MAX_CLASS_BYTES = 8 * 1024
/** MaxApps.MAX_ENTRY_BYTES */
export const MAX_APP_ENTRY_BYTES = 2 * 1024 * 1024
/** MaxApps.MAX_MANIFEST_BYTES */
export const MAX_APP_JSON_BYTES = 16 * 1024
export const MAX_PLUGIN_JSON_BYTES = 16 * 1024
export const MAX_MANIFEST_MF_BYTES = 4 * 1024
/** MaxApps.MAX_SKILL_CHARS (Max truncates; the store refuses so the reviewer sees what Max uses). */
export const MAX_SKILL_CHARS = 8_000
/** MaxApps.MAX_ICON_SVG / MAX_ICON_PNG */
export const MAX_ICON_SVG = 16 * 1024
export const MAX_ICON_PNG = 64 * 1024

const APP_PREFIX = "META-INF/max-app/"
const APP_ID = /^[a-z][a-z0-9-]{1,39}$/
const APP_ENTRY = /^[A-Za-z0-9_-]{1,64}\.html$/
const APP_PERMISSIONS = new Set(["store", "ask"])
/** MaxApps.ICONS: the built-in icon names an app may use instead of a file. */
const APP_ICONS = new Set([
  "app", "folder-tree", "video", "terminal", "monitor-check", "notebook-pen", "notebook", "file-text", "heart-pulse",
  "list-checks", "calendar", "chart", "lightbulb",
])
const APP_ICON_FILES = new Set(["icon.svg", "icon.png"])
/** MaxApps BUILTINS and RESERVED: Max skips a plugin app with one of these ids, so it would never show. */
const APP_RESERVED_IDS = new Set(["store", "brainstorm", "docs", "claude-check", "files", "meet", "sessions"])
const MAX_NAME = 40
const MAX_SUMMARY = 200

/** MANIFEST.MF attributes a content-only jar may carry. Class-Path, agents, Add-Opens and the like are refused. */
const MANIFEST_MF_ATTRIBUTES = new Set([
  "manifest-version", "created-by", "built-by", "build-jdk", "build-jdk-spec",
  "implementation-title", "implementation-version", "implementation-vendor",
  "specification-title", "specification-version", "specification-vendor",
])

/** Binary class name as plugin.json writes it (dots). */
const MAIN_CLASS = /^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$/

// ----------------------------------------------------------------------------
// Strict zip reader
// ----------------------------------------------------------------------------

export interface ZipEntry {
  name: string
  dir: boolean
  method: number
  flags: number
  crc: number
  size: number
  csize: number
  /** Offset of the entry's data (after the local header). */
  dataStart: number
}

let CRC_TABLE: Uint32Array | null = null
export function crc32(data: Uint8Array): number {
  if (!CRC_TABLE) {
    CRC_TABLE = new Uint32Array(256)
    for (let n = 0; n < 256; n++) {
      let c = n
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      CRC_TABLE[n] = c >>> 0
    }
  }
  let c = 0xffffffff
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

const badZip = (message: string): never => refuse("max_app_invalid_zip", `The jar is not a plain zip: ${message}.`)

// deno-lint-ignore no-control-regex
const BAD_NAME_CHARS = /[\u0000-\u001f\u007f\\]/

function checkEntryName(name: string): void {
  if (name.length === 0 || name.length > 255) badZip("an entry name has an invalid length")
  if (BAD_NAME_CHARS.test(name)) badZip("an entry name has a forbidden character")
  if (name.startsWith("/") || /^[A-Za-z]:/.test(name)) badZip("an entry path is absolute")
  const parts = name.endsWith("/") ? name.slice(0, -1).split("/") : name.split("/")
  for (const p of parts) {
    if (p === "" || p === "." || p === "..") badZip("an entry path is not a plain relative path")
  }
}

/**
 * Reads the central directory and proves it describes the whole archive: entries' local records follow one another
 * from offset 0 to the central directory with nothing between, before or overlapping them.
 */
export function readZipEntries(zip: Uint8Array): ZipEntry[] {
  if (zip.length < 22) badZip("too short")
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength)
  let eocd = -1
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) {
      eocd = i
      break
    }
  }
  if (eocd < 0) badZip("no end of central directory")
  const disk = dv.getUint16(eocd + 4, true)
  const cdDisk = dv.getUint16(eocd + 6, true)
  const onDisk = dv.getUint16(eocd + 8, true)
  const total = dv.getUint16(eocd + 10, true)
  const cdSize = dv.getUint32(eocd + 12, true)
  const cdOff = dv.getUint32(eocd + 16, true)
  const comment = dv.getUint16(eocd + 20, true)
  if (disk !== 0 || cdDisk !== 0 || onDisk !== total) badZip("multi-disk archive")
  if (total === 0xffff || cdSize === 0xffffffff || cdOff === 0xffffffff) badZip("zip64 archive")
  if (eocd + 22 + comment !== zip.length) badZip("data after the archive")
  if (cdOff + cdSize !== eocd) badZip("the central directory is not where the archive says")
  if (total > MAX_APP_ENTRIES) {
    refuse("max_app_too_many_entries", `A Max app jar may have at most ${MAX_APP_ENTRIES} entries.`)
  }

  const dec = new TextDecoder("utf-8", { fatal: true })
  const entries: ZipEntry[] = []
  const ends: Array<[number, number]> = []
  const seen = new Set<string>()
  let p = cdOff
  for (let n = 0; n < total; n++) {
    if (p + 46 > eocd || dv.getUint32(p, true) !== 0x02014b50) badZip("damaged central directory")
    const madeBy = dv.getUint16(p + 4, true)
    const flags = dv.getUint16(p + 8, true)
    const method = dv.getUint16(p + 10, true)
    const crc = dv.getUint32(p + 16, true)
    const csize = dv.getUint32(p + 20, true)
    const size = dv.getUint32(p + 24, true)
    const nameLen = dv.getUint16(p + 28, true)
    const extraLen = dv.getUint16(p + 30, true)
    const commentLen = dv.getUint16(p + 32, true)
    const diskStart = dv.getUint16(p + 34, true)
    const ext = dv.getUint32(p + 38, true)
    const local = dv.getUint32(p + 42, true)
    const end = p + 46 + nameLen + extraLen + commentLen
    if (end > eocd) badZip("damaged central directory")
    let name: string
    try {
      name = dec.decode(zip.subarray(p + 46, p + 46 + nameLen))
    } catch {
      return badZip("an entry name is not UTF-8")
    }
    checkEntryName(name)
    const dir = name.endsWith("/")
    if (flags & 0x1 || flags & 0x40) badZip("encrypted entry")
    if (method !== 0 && method !== 8) badZip("unsupported compression method")
    if (method === 0 && flags & 0x8) badZip("a stored entry uses a data descriptor")
    if (csize === 0xffffffff || size === 0xffffffff || diskStart !== 0) badZip("zip64 entry")
    if (madeBy >> 8 === 3) {
      const type = (ext >>> 16) & 0xf000
      if (type === 0xa000) badZip("symbolic link")
      if (type !== 0 && type !== 0x8000 && type !== 0x4000) badZip("special file")
    }
    if (dir && size !== 0) badZip("a directory entry has content")
    if (method === 0 && csize !== size) badZip("a stored entry has inconsistent sizes")
    const key = name.toLowerCase().replace(/\/$/, "")
    if (seen.has(key)) badZip("duplicate entry names")
    seen.add(key)

    // The local record must agree with the central directory.
    if (local + 30 > cdOff || dv.getUint32(local, true) !== 0x04034b50) badZip("damaged local header")
    const lflags = dv.getUint16(local + 6, true)
    const lmethod = dv.getUint16(local + 8, true)
    const lnameLen = dv.getUint16(local + 26, true)
    const lextraLen = dv.getUint16(local + 28, true)
    let lname: string
    try {
      lname = dec.decode(zip.subarray(local + 30, local + 30 + lnameLen))
    } catch {
      return badZip("an entry name is not UTF-8")
    }
    if (lname !== name || lmethod !== method || (lflags & 0x8) !== (flags & 0x8)) {
      badZip("a local header does not match the central directory")
    }
    if (!(flags & 0x8)) {
      if (dv.getUint32(local + 14, true) !== crc || dv.getUint32(local + 18, true) !== csize ||
        dv.getUint32(local + 22, true) !== size) {
        badZip("a local header does not match the central directory")
      }
    }
    const dataStart = local + 30 + lnameLen + lextraLen
    let recordEnd = dataStart + csize
    if (recordEnd > cdOff) badZip("an entry overlaps the central directory")
    if (flags & 0x8) {
      // Data descriptor: [signature] crc, csize, size.
      let d = recordEnd
      if (d + 4 <= cdOff && dv.getUint32(d, true) === 0x08074b50) d += 4
      if (d + 12 > cdOff) badZip("damaged data descriptor")
      if (dv.getUint32(d, true) !== crc || dv.getUint32(d + 4, true) !== csize || dv.getUint32(d + 8, true) !== size) {
        badZip("a data descriptor does not match the central directory")
      }
      recordEnd = d + 12
    }
    entries.push({ name, dir, method, flags, crc, size, csize, dataStart })
    ends.push([local, recordEnd])
    p = end
  }
  if (p !== eocd) badZip("damaged central directory")

  // Every byte before the central directory belongs to exactly one entry, in order.
  ends.sort((a, b) => a[0] - b[0])
  let at = 0
  for (const [start, stop] of ends) {
    if (start !== at) badZip(start > at ? "bytes outside any entry" : "overlapping entries")
    at = stop
  }
  if (at !== cdOff) badZip("bytes outside any entry")
  return entries
}

/** Inflates one entry with its declared size as a hard cap, and checks size and CRC. */
export async function readEntry(zip: Uint8Array, e: ZipEntry): Promise<Uint8Array> {
  const raw = zip.subarray(e.dataStart, e.dataStart + e.csize)
  let out: Uint8Array
  if (e.method === 0) {
    out = raw.slice()
  } else {
    out = new Uint8Array(e.size)
    let n = 0
    try {
      const ds = new DecompressionStream("deflate-raw")
      const writer = ds.writable.getWriter()
      writer.write(raw as unknown as BufferSource).catch(() => {})
      writer.close().catch(() => {})
      const reader = ds.readable.getReader()
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        if (n + value.length > e.size) {
          reader.cancel().catch(() => {})
          return badZip(`${e.name} inflates past its declared size`)
        }
        out.set(value, n)
        n += value.length
      }
    } catch (err) {
      if (err instanceof MaxAppJarError) throw err
      return badZip(`${e.name} cannot be inflated`)
    }
    if (n !== e.size) badZip(`${e.name} is shorter than its declared size`)
  }
  if (out.length !== e.size || crc32(out) !== e.crc) badZip(`${e.name} is corrupt`)
  return out
}

// ----------------------------------------------------------------------------
// Class file
// ----------------------------------------------------------------------------

/** java/lang classes that reach the process, threads, class loading, modules or reflection. */
const DENIED_LANG = new Set([
  "Runtime", "Process", "ProcessBuilder", "ProcessHandle", "System", "Thread", "ThreadGroup", "ThreadBuilders",
  "VirtualThread", "ClassLoader", "Class", "ClassValue", "Module", "ModuleLayer", "Package", "StackWalker",
  "SecurityManager", "Compiler", "ScopedValue", "Shutdown", "ApplicationShutdownHooks", "Thread$Builder",
])
const API_PACKAGE = "ai/rever/boss/plugin/api/"

export interface ClassInfo {
  /** Internal name (slashes). */
  name: string
  /** Every class name the constant pool and descriptors refer to, sorted. */
  references: string[]
}

const badClass = (message: string): never =>
  refuse("max_app_class_invalid", `The stub class could not be read as a class file: ${message}.`)

/** Java's modified UTF-8 (NUL as C0 80, supplementary characters as surrogate pairs). */
function modifiedUtf8(b: Uint8Array): string {
  let s = ""
  for (let i = 0; i < b.length;) {
    const x = b[i]
    if (x === 0) badClass("a NUL in a string constant")
    if (x < 0x80) {
      s += String.fromCharCode(x)
      i += 1
    } else if ((x & 0xe0) === 0xc0) {
      if (i + 1 >= b.length || (b[i + 1] & 0xc0) !== 0x80) badClass("bad modified UTF-8")
      s += String.fromCharCode(((x & 0x1f) << 6) | (b[i + 1] & 0x3f))
      i += 2
    } else if ((x & 0xf0) === 0xe0) {
      if (i + 2 >= b.length || (b[i + 1] & 0xc0) !== 0x80 || (b[i + 2] & 0xc0) !== 0x80) badClass("bad modified UTF-8")
      s += String.fromCharCode(((x & 0x0f) << 12) | ((b[i + 1] & 0x3f) << 6) | (b[i + 2] & 0x3f))
      i += 3
    } else {
      badClass("bad modified UTF-8")
    }
  }
  return s
}

const INTERNAL_NAME = /^[^.;[\/]+(\/[^.;[\/]+)*$/

/** Class names in a field or method descriptor; refuses a malformed one. */
function descriptorClasses(desc: string, method: boolean): string[] {
  const out: string[] = []
  let i = 0
  const field = (allowVoid: boolean) => {
    while (desc[i] === "[") i++
    const c = desc[i]
    if (c === undefined) badClass("a truncated descriptor")
    if ("BCDFIJSZ".includes(c) || (allowVoid && c === "V")) {
      i++
      return
    }
    if (c !== "L") badClass("a malformed descriptor")
    const semi = desc.indexOf(";", i)
    if (semi < 0) badClass("a malformed descriptor")
    const name = desc.slice(i + 1, semi)
    if (!INTERNAL_NAME.test(name)) badClass("a malformed class name in a descriptor")
    out.push(name)
    i = semi + 1
  }
  if (method) {
    if (desc[i] !== "(") badClass("a malformed method descriptor")
    i++
    while (desc[i] !== ")") {
      if (i >= desc.length) badClass("a malformed method descriptor")
      field(false)
    }
    i++
    field(true)
  } else {
    field(false)
  }
  if (i !== desc.length) badClass("a malformed descriptor")
  return out
}

/** Parses a class file to its last byte and returns its name and every class it can link to. */
export function parseClass(b: Uint8Array): ClassInfo {
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength)
  let p = 0
  const need = (n: number) => {
    if (p + n > b.length) badClass("truncated")
  }
  const u1 = () => {
    need(1)
    return b[p++]
  }
  const u2 = () => {
    need(2)
    const v = dv.getUint16(p)
    p += 2
    return v
  }
  const u4 = () => {
    need(4)
    const v = dv.getUint32(p)
    p += 4
    return v
  }
  if (u4() !== 0xcafebabe) badClass("no class file magic")
  u2() // minor
  const major = u2()
  if (major < 45 || major > 80) badClass("an unknown class file version")

  type Cp = { tag: number; a: number; b: number; s?: string }
  const count = u2()
  if (count === 0) badClass("an empty constant pool")
  const cp: Array<Cp | null> = [null]
  for (let i = 1; i < count; i++) {
    const tag = u1()
    switch (tag) {
      case 1: { // Utf8
        const len = u2()
        need(len)
        cp.push({ tag, a: 0, b: 0, s: modifiedUtf8(b.subarray(p, p + len)) })
        p += len
        break
      }
      case 3: case 4: // Integer, Float
        u4()
        cp.push({ tag, a: 0, b: 0 })
        break
      case 5: case 6: // Long, Double take two slots
        u4()
        u4()
        cp.push({ tag, a: 0, b: 0 }, null)
        i++
        if (i >= count) badClass("a long constant runs past the pool")
        break
      case 7: case 8: // Class, String
        cp.push({ tag, a: u2(), b: 0 })
        break
      case 9: case 10: case 11: case 12: // Fieldref, Methodref, InterfaceMethodref, NameAndType
        cp.push({ tag, a: u2(), b: u2() })
        break
      case 15: case 16: case 17: case 18: case 19: case 20:
        return refuse(
          "max_app_class_reference",
          "The stub class uses method handles, invokedynamic, dynamic constants or module data; an inert stub needs none.",
        )
      default:
        return badClass("an unknown constant pool tag")
    }
  }
  const at = (i: number, tag: number): Cp => {
    const e = i > 0 && i < cp.length ? cp[i] : null
    if (!e || e.tag !== tag) badClass("a constant pool index points at the wrong kind of entry")
    return e!
  }
  const utf = (i: number) => at(i, 1).s!

  const refs = new Set<string>()
  const addClassName = (name: string) => {
    if (name.startsWith("[")) {
      for (const c of descriptorClasses(name, false)) refs.add(c)
    } else {
      if (!INTERNAL_NAME.test(name)) badClass("a malformed class name")
      refs.add(name)
    }
  }
  for (let i = 1; i < cp.length; i++) {
    const e = cp[i]
    if (!e) continue
    switch (e.tag) {
      case 7:
        addClassName(utf(e.a))
        break
      case 8:
        utf(e.a)
        break
      case 9: case 10: case 11:
        at(e.a, 7)
        at(e.b, 12)
        break
      case 12: {
        const name = utf(e.a)
        const desc = utf(e.b)
        for (const c of descriptorClasses(desc, desc.startsWith("("))) refs.add(c)
        if (name.length === 0) badClass("an empty member name")
        break
      }
    }
  }

  u2() // access flags
  const thisName = utf(at(u2(), 7).a)
  const superIdx = u2()
  if (superIdx !== 0) at(superIdx, 7)
  const interfaces = u2()
  for (let i = 0; i < interfaces; i++) at(u2(), 7)

  const skipAttributes = () => {
    const n = u2()
    for (let i = 0; i < n; i++) {
      utf(u2())
      const len = u4()
      need(len)
      p += len
    }
  }
  const members = (isMethod: boolean) => {
    const n = u2()
    for (let i = 0; i < n; i++) {
      const flags = u2()
      utf(u2())
      const desc = utf(u2())
      for (const c of descriptorClasses(desc, isMethod)) refs.add(c)
      if (isMethod && flags & 0x0100) {
        refuse("max_app_class_reference", "The stub class declares a native method.")
      }
      skipAttributes()
    }
  }
  members(false)
  members(true)
  skipAttributes()
  if (p !== b.length) badClass("trailing bytes after the class")

  return { name: thisName, references: [...refs].sort() }
}

/** Whether the stub may refer to class `name` (internal form). */
export function referenceAllowed(name: string, self: string): boolean {
  if (name === self) return true
  if (name.startsWith("java/lang/")) {
    const rest = name.slice("java/lang/".length)
    if (rest.includes("/")) return false // java/lang/reflect, invoke, ref, ...: not java/lang itself
    return !DENIED_LANG.has(rest) && !DENIED_LANG.has(rest.split("$")[0])
  }
  if (name.startsWith(API_PACKAGE)) return !name.slice(API_PACKAGE.length).includes("/")
  return false
}

// ----------------------------------------------------------------------------
// Max's file rules
// ----------------------------------------------------------------------------

/** No control or bidi-override characters (MaxApps.clean). */
function clean(s: string): boolean {
  for (const ch of s) {
    const c = ch.charCodeAt(0)
    if (c <= 0x1f || (c >= 0x7f && c <= 0x9f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) {
      return false
    }
  }
  return true
}

/** ThemePackage.safeSvg: usable as an <img>/background: no script, foreign content, handlers or outside references. */
export function safeSvg(bytes: Uint8Array, max: number): boolean {
  if (bytes.length > max) return false
  const lower = new TextDecoder("utf-8").decode(bytes).toLowerCase()
  if (!lower.includes("<svg")) return false
  const banned = [
    "<script", "<foreignobject", "<iframe", "<object", "<embed", "<!entity", "<!doctype", "javascript:", "url(",
    "@import", "<image", "<use", "<animate", "<set",
  ]
  if (banned.some((b) => lower.includes(b))) return false
  // deno-lint-ignore no-control-regex
  if (/[ \t\n\x0B\f\r]on[a-z]+[ \t\n\x0B\f\r]*=/.test(lower)) return false
  // deno-lint-ignore no-control-regex
  const href = /(?:xlink:)?href[ \t\n\x0B\f\r]*=[ \t\n\x0B\f\r]*(["'])([^\n\r\u0085\u2028\u2029]*?)\1/g
  for (const m of lower.matchAll(href)) if (!m[2].startsWith("#")) return false
  return true
}

const isPng = (b: Uint8Array) =>
  b.length >= 8 && b.length <= MAX_ICON_PNG && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47

const UTF8 = new TextDecoder("utf-8", { fatal: true })
function utf8(bytes: Uint8Array, code: string, what: string): string {
  try {
    return UTF8.decode(bytes)
  } catch {
    return refuse(code, `${what} is not UTF-8 text.`)
  }
}

function parseJsonObject(text: string, code: string, what: string): Record<string, unknown> {
  let v: unknown
  try {
    v = JSON.parse(text)
  } catch {
    return refuse(code, `${what} is not valid JSON.`)
  }
  if (typeof v !== "object" || v === null || Array.isArray(v)) refuse(code, `${what} must be a JSON object.`)
  return v as Record<string, unknown>
}

export interface AppJson {
  id: string
  name: string
  summary: string
  icon: string | null
  entry: string
  permissions: string[]
}

/**
 * app.json with Max's rules (MaxApps.load for a plugin bundle; PluginApps.fromJar finds id and entry with a regex over
 * the raw text, so those must agree with the parsed values). Where Max quietly degrades a bad optional field (summary,
 * icon) the store refuses instead, so the reviewer and the publisher see the problem.
 */
export function validateAppJson(text: string): AppJson {
  const bad = (m: string): never => refuse("max_app_app_json_invalid", `app.json: ${m}`)
  const m = parseJsonObject(text, "max_app_app_json_invalid", "app.json")
  if (m.schema !== 1) bad("schema must be 1.")
  const id = m.id
  if (typeof id !== "string" || !APP_ID.test(id)) bad("id: 2 to 40 lowercase letters, digits and dashes, starting with a letter.")
  if (APP_RESERVED_IDS.has(id as string)) bad("id is taken by one of Max's own apps.")
  const rawId = /"id"\s*:\s*"([a-z][a-z0-9-]{1,39})"/.exec(text)?.[1]
  if (rawId !== id) bad("the first \"id\" in the file must be the app's id.")

  if (typeof m.name !== "string") bad("name is required.")
  const name = (m.name as string).trim()
  if (name.length === 0 || name.length > MAX_NAME || !clean(name)) bad(`name: 1 to ${MAX_NAME} characters, no control characters.`)

  let summary = ""
  if ("summary" in m && m.summary !== null) {
    if (typeof m.summary !== "string") bad("summary must be text.")
    summary = (m.summary as string).trim()
    if (summary.length > MAX_SUMMARY || !clean(summary)) bad(`summary: at most ${MAX_SUMMARY} characters, no control characters.`)
  }

  let icon: string | null = null
  if ("icon" in m && m.icon !== null) {
    if (typeof m.icon !== "string" || !(APP_ICONS.has(m.icon) || APP_ICON_FILES.has(m.icon))) {
      bad("icon: icon.svg, icon.png or one of Max's icon names.")
    }
    icon = m.icon as string
  }

  let permissions: string[] = []
  if ("permissions" in m && m.permissions !== null) {
    const p = m.permissions
    if (!Array.isArray(p) || p.some((x) => typeof x !== "string")) bad("permissions must be a list of names.")
    for (const x of p as string[]) if (!APP_PERMISSIONS.has(x)) bad("permissions may only be store and ask.")
    permissions = [...new Set(p as string[])]
  }

  let entry = "index.html"
  if ("entry" in m && m.entry !== null) {
    if (typeof m.entry !== "string") bad("entry must be a file name.")
    entry = m.entry as string
  }
  if (!APP_ENTRY.test(entry)) bad("entry: a NAME.html file (letters, digits, - and _).")
  const rawEntry = /"entry"\s*:\s*"([^"]{1,80})"/.exec(text)?.[1]
  if ((rawEntry ?? "index.html") !== entry) bad("the first \"entry\" in the file must be the app's entry.")

  return { id: id as string, name, summary, icon, entry, permissions }
}

// ----------------------------------------------------------------------------
// The whole check
// ----------------------------------------------------------------------------

export interface MaxAppReviewMeta {
  /** plugin.json as published. */
  pluginJson: Record<string, unknown>
  /** app.json as published. */
  appJson: Record<string, unknown>
  /** What the checker understood: the values Max will use. */
  app: AppJson
  /** Every entry, in archive order. */
  entries: Array<{ name: string; size: number; compressedSize: number }>
  /** The stub class: binary name, size and what it links to. */
  stubClass: { name: string; size: number; references: string[] }
  checker: string
}

export const CHECKER_VERSION = "content-only/1"

/**
 * Checks a Max app jar and returns what the reviewer is shown. Throws MaxAppJarError with a code on the first rule
 * broken. `expected` is the plugin row's identity: plugin.json must declare it (the finalize route checks the same).
 */
export async function checkMaxAppJar(
  jar: Uint8Array,
  expected: { pluginId: string; version: string },
): Promise<MaxAppReviewMeta> {
  if (jar.length > MAX_APP_JAR_BYTES) {
    refuse("max_app_jar_too_large", `A Max app jar may be at most ${MAX_APP_JAR_BYTES / (1024 * 1024)} MB.`)
  }
  const entries = readZipEntries(jar)
  const files = new Map<string, ZipEntry>()
  for (const e of entries) if (!e.dir) files.set(e.name, e)

  const read = async (name: string, max: number, code: string, what: string): Promise<Uint8Array> => {
    const e = files.get(name)
    if (!e) return refuse(code, `${what} is missing.`)
    if (e.size > max) refuse(code, `${what} is larger than ${max} bytes.`)
    return await readEntry(jar, e)
  }

  // plugin.json: identity and the one class it may name.
  const pluginText = utf8(
    await read("META-INF/boss-plugin/plugin.json", MAX_PLUGIN_JSON_BYTES, "max_app_plugin_json_invalid", "META-INF/boss-plugin/plugin.json"),
    "max_app_plugin_json_invalid",
    "plugin.json",
  )
  const pluginJson = parseJsonObject(pluginText, "max_app_plugin_json_invalid", "plugin.json")
  if (pluginJson.pluginId !== expected.pluginId || pluginJson.version !== expected.version) {
    refuse("max_app_plugin_json_invalid", "plugin.json does not declare this plugin and version.")
  }
  const mainClass = pluginJson.mainClass
  if (typeof mainClass !== "string" || !MAIN_CLASS.test(mainClass)) {
    refuse("max_app_plugin_json_invalid", "plugin.json mainClass must be a class name.")
  }
  const classPath = (mainClass as string).replace(/\./g, "/") + ".class"

  // app.json first: it names the entry page, which decides what else is allowed.
  const appText = utf8(
    await read(APP_PREFIX + "app.json", MAX_APP_JSON_BYTES, "max_app_app_json_invalid", "META-INF/max-app/app.json"),
    "max_app_app_json_invalid",
    "app.json",
  )
  const app = validateAppJson(appText)
  const appJson = JSON.parse(appText) as Record<string, unknown>

  const allowed = new Set([
    "META-INF/MANIFEST.MF",
    "META-INF/boss-plugin/plugin.json",
    APP_PREFIX + "app.json",
    APP_PREFIX + app.entry,
    APP_PREFIX + "SKILL.md",
    APP_PREFIX + "icon.svg",
    APP_PREFIX + "icon.png",
    classPath,
  ])
  for (const e of entries) {
    if (e.dir) continue
    if (e.name.endsWith(".class") && e.name !== classPath) {
      refuse(
        "max_app_extra_class",
        `${e.name} is a second class. A Max app jar has exactly one class: plugin.json's mainClass.`,
      )
    }
    if (!allowed.has(e.name)) {
      refuse("max_app_entry_not_allowed", `${e.name} is not allowed in a Max app jar.`)
    }
  }

  // MANIFEST.MF (optional): plain attributes only.
  if (files.has("META-INF/MANIFEST.MF")) {
    const mf = utf8(
      await read("META-INF/MANIFEST.MF", MAX_MANIFEST_MF_BYTES, "max_app_manifest_mf_invalid", "META-INF/MANIFEST.MF"),
      "max_app_manifest_mf_invalid",
      "MANIFEST.MF",
    )
    for (const line of mf.split(/\r\n|\r|\n/)) {
      if (line === "") continue
      if (line.startsWith(" ")) continue // continuation of the previous (allowed) attribute
      const attr = line.slice(0, line.indexOf(":")).toLowerCase()
      if (line.indexOf(":") <= 0 || !MANIFEST_MF_ATTRIBUTES.has(attr)) {
        refuse("max_app_manifest_mf_invalid", "MANIFEST.MF may only carry plain attributes such as Manifest-Version.")
      }
    }
  }

  // The stub class.
  const classEntry = files.get(classPath)
  if (!classEntry) refuse("max_app_class_missing", `${classPath} (plugin.json's mainClass) is missing.`)
  if (classEntry!.size > MAX_CLASS_BYTES) {
    refuse("max_app_class_too_large", `The stub class may be at most ${MAX_CLASS_BYTES} bytes.`)
  }
  const classBytes = await readEntry(jar, classEntry!)
  const info = parseClass(classBytes)
  const internal = (mainClass as string).replace(/\./g, "/")
  if (info.name !== internal) refuse("max_app_class_invalid", "The class file is not plugin.json's mainClass.")
  const outside = info.references.filter((r) => !referenceAllowed(r, internal))
  if (outside.length > 0) {
    refuse(
      "max_app_class_reference",
      `The stub class refers to ${outside[0].replace(/\//g, ".")}; it may use only java.lang and the plugin API.`,
    )
  }

  // The page, the guide and the icon.
  const page = await read(APP_PREFIX + app.entry, MAX_APP_ENTRY_BYTES, "max_app_entry_invalid", `META-INF/max-app/${app.entry}`)
  utf8(page, "max_app_entry_invalid", app.entry)
  if (files.has(APP_PREFIX + "SKILL.md")) {
    const skill = utf8(
      await read(APP_PREFIX + "SKILL.md", MAX_SKILL_CHARS * 3, "max_app_skill_invalid", "SKILL.md"),
      "max_app_skill_invalid",
      "SKILL.md",
    )
    if (skill.length > MAX_SKILL_CHARS) refuse("max_app_skill_invalid", `SKILL.md may be at most ${MAX_SKILL_CHARS} characters.`)
  }
  if (app.icon && APP_ICON_FILES.has(app.icon)) {
    const svg = app.icon === "icon.svg"
    const bytes = await read(APP_PREFIX + app.icon, svg ? MAX_ICON_SVG : MAX_ICON_PNG, "max_app_icon_invalid", app.icon)
    if (svg ? !safeSvg(bytes, MAX_ICON_SVG) : !isPng(bytes)) {
      refuse(
        "max_app_icon_invalid",
        svg
          ? "icon.svg was refused: no scripts, links, images or event handlers, at most 16 KB."
          : "icon.png is not a PNG of at most 64 KB.",
      )
    }
  }

  return {
    pluginJson,
    appJson,
    app,
    entries: entries.map((e) => ({ name: e.name, size: e.size, compressedSize: e.csize })),
    stubClass: { name: mainClass as string, size: classBytes.length, references: info.references },
    checker: CHECKER_VERSION,
  }
}

/** Fetches a jar of at most `cap` bytes into memory (finalize already streamed and hashed it once). */
export async function fetchJarBytes(url: string, cap: number = MAX_APP_JAR_BYTES): Promise<Uint8Array> {
  const resp = await fetch(url)
  if (!resp.ok || !resp.body) {
    try { await resp.body?.cancel() } catch { /* ignore */ }
    throw new Error(`Failed to fetch the JAR: ${resp.status}`)
  }
  const reader = resp.body.getReader()
  const chunks: Uint8Array[] = []
  let n = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    n += value.length
    if (n > cap) {
      reader.cancel().catch(() => {})
      throw new MaxAppJarError("max_app_jar_too_large", `A Max app jar may be at most ${cap / (1024 * 1024)} MB.`)
    }
    chunks.push(value)
  }
  const out = new Uint8Array(n)
  let o = 0
  for (const c of chunks) {
    out.set(c, o)
    o += c.length
  }
  return out
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes as unknown as BufferSource))
  return [...d].map((x) => x.toString(16).padStart(2, "0")).join("")
}
