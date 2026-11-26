/**
 * The content-only check for Max app jars (services/max-app.ts). The sample Habits jar is the real Gradle build from
 * boss-plugin-max; every refusal below is a small edit of it, so a passing refusal means the rule bit, not that the
 * input was broken some other way.
 */
import { assert, assertEquals, assertRejects } from "@std/assert"
import {
  checkMaxAppJar,
  isMaxAppEntryName,
  MAX_CLASS_BYTES,
  maxAppEntryInZip,
  MaxAppJarError,
  parseClass,
  referenceAllowed,
  validateAppJson,
} from "../services/max-app.ts"
import {
  classFile,
  SAMPLE_CLASS,
  SAMPLE_PLUGIN_ID,
  SAMPLE_VERSION,
  sampleFiles,
  sampleJar,
  sampleVariant,
  text,
  zip,
} from "./max-app-fixtures.ts"

const ID = { pluginId: SAMPLE_PLUGIN_ID, version: SAMPLE_VERSION }
const SELF = "ai/rever/maxapp/habits/HabitsApp"
const API = "ai/rever/boss/plugin/api/"

async function code(jar: Uint8Array): Promise<string> {
  try {
    await checkMaxAppJar(jar, ID)
    return "ok"
  } catch (e) {
    if (e instanceof MaxAppJarError) return e.code
    throw e
  }
}

const stub = (over: Partial<Parameters<typeof classFile>[0]> = {}) =>
  classFile({
    name: SELF,
    interfaces: [API + "DynamicPlugin"],
    methods: [{ name: "register", desc: `(L${API}PluginContext;)V` }, { name: "getPluginId", desc: "()Ljava/lang/String;" }],
    ...over,
  })

const appJson = (over: Record<string, unknown> = {}) =>
  text(JSON.stringify({
    schema: 1, id: "habits", name: "Habits", summary: "Tick off habits.", icon: "icon.svg", entry: "index.html",
    permissions: ["store", "ask"], ...over,
  }))

Deno.test("the sample Habits jar passes and yields the review record", async () => {
  const meta = await checkMaxAppJar(sampleJar(), ID)
  assertEquals(meta.app.id, "habits")
  assertEquals(meta.app.entry, "index.html")
  assertEquals(meta.app.permissions, ["store", "ask"])
  assertEquals(meta.stubClass.name, "ai.rever.maxapp.habits.HabitsApp")
  assertEquals(meta.stubClass.references, [
    API + "DynamicPlugin", API + "PluginContext", SELF, "java/lang/Object", "java/lang/String",
  ])
  assertEquals(meta.pluginJson.mainClass, "ai.rever.maxapp.habits.HabitsApp")
  assert(meta.entries.some((e) => e.name === "META-INF/max-app/index.html" && e.size > 0))
  assert(new TextEncoder().encode(JSON.stringify(meta)).length < 65536, "fits review_meta's 64 KB cap")
})

Deno.test("a rebuilt copy of the sample (stored entries) passes too", async () => {
  assertEquals(await code(await sampleVariant()), "ok")
})

Deno.test("a stub assembled here passes: the assembler and the parser agree", async () => {
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: stub() })), "ok")
})

Deno.test("a second class is refused", async () => {
  const jar = await sampleVariant({ "ai/rever/maxapp/habits/Evil.class": stub({ name: "ai/rever/maxapp/habits/Evil" }) })
  assertEquals(await code(jar), "max_app_extra_class")
})

Deno.test("a class somewhere else (not the mainClass) is refused", async () => {
  const jar = await sampleVariant({ [SAMPLE_CLASS]: null, "x/Y.class": stub({ name: "x/Y" }) })
  assertEquals(await code(jar), "max_app_extra_class")
})

Deno.test("a missing stub class is refused", async () => {
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: null })), "max_app_class_missing")
})

Deno.test("references outside java.lang and the plugin API are refused", async () => {
  for (const ref of ["java/io/File", "java/lang/reflect/Method", "java/lang/invoke/MethodHandles", "kotlin/Unit",
    "ai/rever/boss/plugin/api/internal/Secret", "java/net/URL"]) {
    assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: stub({ refs: [ref] }) })), "max_app_class_reference", ref)
  }
})

Deno.test("capability classes inside java.lang are refused", async () => {
  for (const ref of ["java/lang/Runtime", "java/lang/ProcessBuilder", "java/lang/System", "java/lang/Class",
    "java/lang/ClassLoader", "java/lang/Thread", "java/lang/ProcessBuilder$Redirect"]) {
    assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: stub({ refs: [ref] }) })), "max_app_class_reference", ref)
  }
})

Deno.test("a reference hidden in a method descriptor or an array type is refused", async () => {
  const viaDesc = stub({ methods: [{ name: "f", desc: "(Ljava/io/File;)V" }] })
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: viaDesc })), "max_app_class_reference")
  const viaArray = stub({ refs: ["[[Ljava/net/Socket;"] })
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: viaArray })), "max_app_class_reference")
  const viaSuper = stub({ superName: "java/lang/Thread" })
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: viaSuper })), "max_app_class_reference")
})

Deno.test("invokedynamic and native methods are refused", async () => {
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: stub({ extraTag: 18 }) })), "max_app_class_reference")
  const native = stub({ methods: [{ name: "n", desc: "()V", flags: 0x0101 }] })
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: native })), "max_app_class_reference")
})

Deno.test("an oversized class is refused", async () => {
  const big = stub({ pad: MAX_CLASS_BYTES })
  assert(big.length > MAX_CLASS_BYTES)
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: big })), "max_app_class_too_large")
})

Deno.test("a class file that does not parse is refused, never waved through", async () => {
  const good = stub()
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: good.slice(0, good.length - 3) })), "max_app_class_invalid")
  const trailing = new Uint8Array([...good, 0])
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: trailing })), "max_app_class_invalid")
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: text("not a class") })), "max_app_class_invalid")
  // A class whose own name is not the mainClass.
  assertEquals(await code(await sampleVariant({ [SAMPLE_CLASS]: stub({ name: "ai/rever/maxapp/habits/Other" }) })),
    "max_app_class_invalid")
})

Deno.test("stray files are refused", async () => {
  for (const name of ["README.md", "META-INF/max-app/extra.js", "META-INF/services/x.Plugin", "META-INF/SIG.SF",
    "META-INF/max-app/other.html", "lib/native.so"]) {
    assertEquals(await code(await sampleVariant({ [name]: text("x") })), "max_app_entry_not_allowed", name)
  }
})

Deno.test("bad app.json is refused", async () => {
  const bad = async (over: Record<string, unknown>) =>
    await code(await sampleVariant({ "META-INF/max-app/app.json": appJson(over) }))
  assertEquals(await bad({}), "ok")
  assertEquals(await bad({ permissions: ["store", "files.read"] }), "max_app_app_json_invalid")
  assertEquals(await bad({ permissions: "store" }), "max_app_app_json_invalid")
  assertEquals(await bad({ id: "Habits" }), "max_app_app_json_invalid")
  assertEquals(await bad({ id: "brainstorm" }), "max_app_app_json_invalid")
  assertEquals(await bad({ schema: 2 }), "max_app_app_json_invalid")
  assertEquals(await bad({ name: "x".repeat(41) }), "max_app_app_json_invalid")
  assertEquals(await bad({ name: "Hab‮its" }), "max_app_app_json_invalid")
  assertEquals(await bad({ entry: "../index.html" }), "max_app_app_json_invalid")
  assertEquals(await bad({ entry: "page.htm" }), "max_app_app_json_invalid")
  assertEquals(await bad({ icon: "https://example.com/x.png" }), "max_app_app_json_invalid")
  assertEquals(await code(await sampleVariant({ "META-INF/max-app/app.json": text("{nope") })), "max_app_app_json_invalid")
  assertEquals(await code(await sampleVariant({ "META-INF/max-app/app.json": null })), "max_app_app_json_invalid")
})

Deno.test("app.json's first raw \"id\" must be its id (Max finds it with a regex)", () => {
  const sneaky = `{"x":{"id":"other"},"schema":1,"id":"habits","name":"Habits"}`
  try {
    validateAppJson(sneaky)
    throw new Error("accepted")
  } catch (e) {
    assert(e instanceof MaxAppJarError && e.code === "max_app_app_json_invalid")
  }
})

Deno.test("the entry page app.json names must be there and within Max's limit", async () => {
  const jar = await sampleVariant({ "META-INF/max-app/app.json": appJson({ entry: "main.html" }) })
  assertEquals(await code(jar), "max_app_entry_not_allowed") // index.html is now a stray file
  const moved = await sampleVariant({
    "META-INF/max-app/app.json": appJson({ entry: "main.html" }),
    "META-INF/max-app/index.html": null,
  })
  assertEquals(await code(moved), "max_app_entry_invalid")
})

Deno.test("an unsafe icon.svg is refused", async () => {
  const svg = text(`<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>`)
  assertEquals(await code(await sampleVariant({ "META-INF/max-app/icon.svg": svg })), "max_app_icon_invalid")
  const href = text(`<svg><a href="https://evil.example">x</a></svg>`)
  assertEquals(await code(await sampleVariant({ "META-INF/max-app/icon.svg": href })), "max_app_icon_invalid")
})

Deno.test("an over-long SKILL.md is refused", async () => {
  assertEquals(await code(await sampleVariant({ "META-INF/max-app/SKILL.md": text("x".repeat(8001)) })), "max_app_skill_invalid")
})

Deno.test("MANIFEST.MF may not pull in a class path or an agent", async () => {
  const mf = (s: string) => sampleVariant({ "META-INF/MANIFEST.MF": text(s) })
  assertEquals(await code(await mf("Manifest-Version: 1.0\r\nCreated-By: Gradle\r\n\r\n")), "ok")
  assertEquals(await code(await mf("Manifest-Version: 1.0\r\nClass-Path: ../../evil.jar\r\n")), "max_app_manifest_mf_invalid")
  assertEquals(await code(await mf("Manifest-Version: 1.0\r\nLauncher-Agent-Class: a.B\r\n")), "max_app_manifest_mf_invalid")
})

Deno.test("plugin.json must declare this plugin and version", async () => {
  await assertRejects(() => checkMaxAppJar(sampleJar(), { pluginId: "other.plugin", version: SAMPLE_VERSION }), MaxAppJarError)
  await assertRejects(() => checkMaxAppJar(sampleJar(), { pluginId: SAMPLE_PLUGIN_ID, version: "1.0.1" }), MaxAppJarError)
})

Deno.test("zip tricks are refused: prefix bytes, hidden local entries, duplicates", async () => {
  const files = await sampleFiles()
  assertEquals(await code(zip(files, text("#!/bin/sh\n"))), "max_app_invalid_zip")
  // A class present as a local record but not in the central directory: rebuild, then drop its CD record.
  const withHidden = zip([...files, ["ai/rever/maxapp/habits/Hidden.class", stub({ name: "ai/rever/maxapp/habits/Hidden" })]])
  const hidden = dropLastCentralRecord(withHidden)
  assertEquals(await code(hidden), "max_app_invalid_zip")
  assertEquals(await code(zip([...files, ["META-INF/max-app/SKILL.md", text("again")]])), "max_app_invalid_zip")
})

Deno.test("referenceAllowed: java.lang itself and the API package itself, nothing below them", () => {
  assert(referenceAllowed("java/lang/Object", SELF))
  assert(referenceAllowed("java/lang/String", SELF))
  assert(referenceAllowed(API + "DynamicPlugin", SELF))
  assert(referenceAllowed(SELF, SELF))
  assert(!referenceAllowed("java/lang/reflect/Field", SELF))
  assert(!referenceAllowed(API + "sub/Thing", SELF))
  assert(!referenceAllowed("ai/rever/maxapp/habits/Other", SELF))
  assert(!referenceAllowed("java/lang/Runtime", SELF))
})

Deno.test("parseClass reads the sample's real class file", async () => {
  const files = await sampleFiles()
  const cls = files.find(([n]) => n === SAMPLE_CLASS)![1]
  assertEquals(parseClass(cls).name, SELF)
})

/** Removes the last central directory record and fixes the end record, leaving its local record orphaned. */
function dropLastCentralRecord(z: Uint8Array): Uint8Array {
  const dv = new DataView(z.buffer, z.byteOffset, z.byteLength)
  const eocd = z.length - 22
  const total = dv.getUint16(eocd + 10, true)
  const cdOff = dv.getUint32(eocd + 16, true)
  let p = cdOff
  let last = cdOff
  for (let i = 0; i < total; i++) {
    last = p
    p += 46 + dv.getUint16(p + 28, true) + dv.getUint16(p + 30, true) + dv.getUint16(p + 32, true)
  }
  const out = new Uint8Array(z.length - (p - last))
  out.set(z.subarray(0, last), 0)
  out.set(z.subarray(p), last)
  const o = new DataView(out.buffer)
  const e = out.length - 22
  o.setUint16(e + 8, total - 1, true)
  o.setUint16(e + 10, total - 1, true)
  o.setUint32(e + 12, last - cdOff, true)
  return out
}

// ----------------------------------------------------------------------------
// Plain plugins must not carry a Max app (the finalize and GitHub routes refuse them)
// ----------------------------------------------------------------------------

const APP_FILES = ["META-INF/max-app/app.json", "META-INF/max-app/index.html", "META-INF/max-app/SKILL.md", "META-INF/max-app/icon.svg"]

Deno.test("max-app entry names: the folder and anything in it, whatever the case or slashes", () => {
  for (const n of ["META-INF/max-app/app.json", "META-INF/max-app/", "META-INF/max-app", "meta-inf/MAX-APP/index.html", "META-INF\\max-app\\app.json", "/META-INF/max-app/x"]) {
    assert(isMaxAppEntryName(n), n)
  }
  for (const n of ["META-INF/boss-plugin/plugin.json", "META-INF/max-apps/app.json", "max-app/app.json", "META-INF/MANIFEST.MF"]) {
    assert(!isMaxAppEntryName(n), n)
  }
})

Deno.test("maxAppEntryInZip finds the app in the sample, and nothing in a plain plugin jar", async () => {
  assert(maxAppEntryInZip(sampleJar())?.startsWith("META-INF/max-app/"))
  const plain = await sampleVariant(Object.fromEntries([...APP_FILES, "META-INF/max-app/"].map((n) => [n, null])))
  assertEquals(maxAppEntryInZip(plain), null)
  // A single, oddly-cased entry is enough.
  const sneaky = await sampleVariant({ ...Object.fromEntries([...APP_FILES, "META-INF/max-app/"].map((n) => [n, null])), "Meta-Inf/Max-App/app.json": text("{}") })
  assertEquals(maxAppEntryInZip(sneaky), "Meta-Inf/Max-App/app.json")
  assertEquals(maxAppEntryInZip(new Uint8Array(10)), null)
})
