/**
 * Max apps through the routes: publish (kind, channel), finalize (content-only check, pending_review, unsigned), the
 * review routes (plugins.review; approve signs, reject records the note), and the release gates on download (a version
 * in review is never latest; beta/test and unreviewed versions only for who may have them).
 *
 * The database is an in-memory fake that answers the PostgREST chains and RPCs these routes use; the RPCs that carry
 * the rules in SQL (plugin_channel_access, plugin_unreviewed_access, plugin_version_review) are modelled on their
 * definitions in 20261125120100 so the routes are tested against the same answers.
 */
import { assert, assertEquals, assertExists } from "@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import admin from "../routes/admin.ts"
import publish from "../routes/publish.ts"
import download from "../routes/download.ts"
import browse from "../routes/browse.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"
import { versionAnchor } from "../utils/signing.ts"
import { sha256Hex } from "../services/max-app.ts"
import { sampleJar, sampleVariant, SAMPLE_PLUGIN_ID, text } from "./max-app-fixtures.ts"

const AUTHOR = "11111111-1111-4111-8111-111111111111"
const REVIEWER = "22222222-2222-4222-8222-222222222222"
const OUTSIDER = "33333333-3333-4333-8333-333333333333"
const TESTER = "44444444-4444-4444-8444-444444444444"
const ADMIN = "55555555-5555-4555-8555-555555555555"
const ORG = "66666666-6666-4666-8666-666666666666"
const APP_UUID = "77777777-7777-4777-8777-777777777777"

/** A session JWT; getUserFromToken reads is_admin and user_permissions from its payload. */
function jwt(sub: string, claims: Record<string, unknown> = {}): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b64({ alg: "HS256", typ: "JWT" })}.${b64({ sub, ...claims })}.sig`
}
const bearer = (sub: string, claims: Record<string, unknown> = {}) => ({ Authorization: `Bearer ${jwt(sub, claims)}` })
const subOf = (token: string): string | null => {
  try {
    return JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))).sub ?? null
  } catch {
    return null
  }
}

type Row = Record<string, unknown>

/** In-memory tables and the RPCs the routes call. */
class FakeDb {
  plugins: Row[] = []
  versions: Row[] = []
  audit: Row[] = []
  rpcCalls: Array<{ fn: string; args: Row }> = []
  /** Who holds plugins.channel.test / plugins.review (the SQL helpers' permission arm). */
  testers = new Set<string>([TESTER])
  reviewers = new Set<string>([REVIEWER])
  storage = new Map<string, Uint8Array>()

  client(): SupabaseClient {
    // deno-lint-ignore no-this-alias
    const db = this
    return {
      auth: {
        getUser: (token: string) => {
          const sub = subOf(token)
          return Promise.resolve(sub
            ? { data: { user: { id: sub, email: `${sub}@test` } }, error: null }
            : { data: { user: null }, error: new Error("invalid") })
        },
      },
      rpc: (fn: string, args: Row = {}) => Promise.resolve(db.rpc(fn, args)),
      from: (table: string) => db.query(table),
      storage: {
        from: () => ({
          createSignedUrl: (path: string) =>
            Promise.resolve({ data: { signedUrl: `https://storage.test/${path}` }, error: null }),
          createSignedUploadUrl: (path: string) =>
            Promise.resolve({ data: { signedUrl: `https://storage.test/upload/${path}` }, error: null }),
        }),
      },
    } as unknown as SupabaseClient
  }

  plugin(id: unknown) {
    return this.plugins.find((p) => p.id === id)
  }

  channelAccess(user: unknown, pluginUuid: unknown): boolean {
    const p = this.plugin(pluginUuid)
    return !!user && !!p && (p.author_id === user || this.testers.has(user as string))
  }

  unreviewedAccess(user: unknown, pluginUuid: unknown): boolean {
    const p = this.plugin(pluginUuid)
    return !!user && !!p && (p.author_id === user || this.reviewers.has(user as string))
  }

  rpc(fn: string, args: Row): { data: unknown; error: null | { message: string } } {
    this.rpcCalls.push({ fn, args })
    switch (fn) {
      case "user_can_install_plugin":
        return { data: true, error: null }
      case "plugin_channel_access":
        return { data: this.channelAccess(args.p_user_id, args.p_plugin_uuid), error: null }
      case "plugin_unreviewed_access":
        return { data: this.unreviewedAccess(args.p_user_id, args.p_plugin_uuid), error: null }
      case "plugin_version_review": {
        const v = this.versions.find((x) => x.id === args.p_version_id)
        const p = v && this.plugin(v.plugin_id)
        const note = typeof args.p_note === "string" ? args.p_note.trim() : ""
        if (!args.p_approve && !note) return { data: { error: "note_required" }, error: null }
        if (note.length > 1000) return { data: { error: "note_too_long" }, error: null }
        if (!v || !p || p.kind !== "max-app") return { data: { error: "not_found" }, error: null }
        if (v.review_status !== "pending_review" || v.sha256 === "pending" || v.sha256 !== args.p_sha256) {
          return { data: { error: "not_pending_review" }, error: null }
        }
        v.review_status = args.p_approve ? "approved" : "rejected"
        v.reviewed_by = args.p_reviewer
        v.review_note = args.p_approve ? null : note
        v.signature = args.p_approve ? args.p_signature : null
        this.audit.push({ actor: args.p_reviewer, action: args.p_approve ? "plugin_store.approve" : "plugin_store.reject" })
        return { data: { ok: true, review_status: v.review_status }, error: null }
      }
      default:
        return { data: null, error: null }
    }
  }

  query(table: string) {
    const rows = table === "plugins" ? this.plugins : table === "plugin_versions" ? this.versions : []
    const filters: Array<(r: Row) => boolean> = []
    let joinPlugins = false
    let patch: Row | null = null
    let insert: Row | null = null
    let order: { col: string; asc: boolean } | null = null
    let limit = Infinity
    // deno-lint-ignore no-this-alias
    const db = this
    const get = (r: Row, col: string) => {
      if (col.startsWith("plugins.")) return (db.plugin(r.plugin_id) ?? {})[col.slice(8)]
      return r[col]
    }
    const result = () => {
      if (insert) {
        const row: Row = { id: crypto.randomUUID(), published_at: new Date().toISOString(), ...insert }
        if (table === "plugin_versions") {
          row.channel ??= "stable"
          row.review_status ??= "approved"
        }
        if (table === "plugins") row.kind ??= "plugin"
        rows.push(row)
        return [row]
      }
      let out = rows.filter((r) => filters.every((f) => f(r)))
      if (patch) {
        for (const r of out) Object.assign(r, patch)
      }
      if (order) {
        const { col, asc } = order
        out = [...out].sort((a, b) => (String(a[col]) < String(b[col]) ? -1 : 1) * (asc ? 1 : -1))
      }
      out = out.slice(0, limit)
      return joinPlugins ? out.map((r) => ({ ...r, plugins: db.plugin(r.plugin_id) })) : out
    }
    const chain: Record<string, unknown> = {
      select: (cols?: string) => {
        if (cols?.includes("plugins!inner")) joinPlugins = true
        return chain
      },
      insert: (row: Row) => {
        insert = row
        return chain
      },
      update: (row: Row) => {
        patch = row
        return chain
      },
      eq: (c: string, v: unknown) => (filters.push((r) => get(r, c) === v), chain),
      neq: (c: string, v: unknown) => (filters.push((r) => get(r, c) !== v), chain),
      gt: (c: string, v: number) => (filters.push((r) => Number(get(r, c)) > v), chain),
      in: (c: string, v: unknown[]) => (filters.push((r) => v.includes(get(r, c))), chain),
      order: (col: string, o: { ascending: boolean }) => ((order = { col, asc: o.ascending }), chain),
      limit: (n: number) => ((limit = n), chain),
      single: () => {
        const r = result()
        return Promise.resolve(r.length === 1
          ? { data: r[0], error: null }
          : { data: null, error: { code: "PGRST116", message: "no rows" } })
      },
      maybeSingle: () => {
        const r = result()
        return Promise.resolve({ data: r[0] ?? null, error: null })
      },
      then: (ok: (v: unknown) => unknown, bad?: (e: unknown) => unknown) =>
        Promise.resolve({ data: result(), error: null }).then(ok, bad),
    }
    return chain
  }
}

function mount(db: FakeDb) {
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  const client = db.client()
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", admin)
  app.route("/version", publish)
  app.route("/", publish)
  app.route("/", download)
  app.route("/", browse)
  return app
}

/** Serves stored jars over https://storage.test/<path>, with Range support (finalize's manifest read uses it). */
function withStorageFetch<T>(db: FakeDb, fn: () => Promise<T>): Promise<T> {
  const original = globalThis.fetch
  globalThis.fetch = ((input: string | URL | Request, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input)
    const path = url.replace("https://storage.test/", "")
    const body = db.storage.get(path)
    if (!body) return Promise.resolve(new Response("missing", { status: 404 }))
    const range = (init?.headers as Record<string, string> | undefined)?.Range ?? ""
    const m = range.match(/bytes=(\d+)-(\d+)/)
    if (m) {
      const start = +m[1]
      const end = Math.min(+m[2], body.length - 1)
      return Promise.resolve(new Response(body.slice(start, end + 1), {
        status: 206,
        headers: { "Content-Range": `bytes ${start}-${end}/${body.length}`, "Content-Length": String(end - start + 1) },
      }))
    }
    return Promise.resolve(new Response(init?.method === "HEAD" ? null : body.slice(), {
      status: 200,
      headers: { "Content-Length": String(body.length) },
    }))
  }) as typeof fetch
  return fn().finally(() => {
    globalThis.fetch = original
  })
}

/** An ephemeral store signing key, through the same env var production uses. */
const keyPair = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) },
  true,
  ["sign", "verify"],
)
{
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", keyPair.privateKey))
  Deno.env.set(
    "PLUGIN_SIGNING_PRIVATE_KEY",
    `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8))}\n-----END PRIVATE KEY-----`,
  )
}

async function verifies(signature: string, anchor: string): Promise<boolean> {
  const sig = Uint8Array.from(atob(signature), (c) => c.charCodeAt(0))
  return await crypto.subtle.verify("RSASSA-PKCS1-v1_5", keyPair.publicKey, sig, new TextEncoder().encode(anchor))
}

/** A Max app plugin row with the given versions. */
function appDb(versions: Row[] = []): FakeDb {
  const db = new FakeDb()
  db.plugins.push({
    id: APP_UUID, plugin_id: SAMPLE_PLUGIN_ID, author_id: AUTHOR, published: true, kind: "max-app", org_id: ORG,
    required_permissions: [], display_name: "Habits", author_name: "author",
  })
  let t = 0
  for (const v of versions) {
    db.versions.push({
      id: crypto.randomUUID(), plugin_id: APP_UUID, jar_path: `plugins/${v.version}.jar`, jar_size: 100,
      sha256: "a".repeat(64), signature: null, channel: "stable", review_status: "approved", changelog: "",
      min_boss_version: "1.0.0", min_ipc_version: "1.0.0", min_api_version: "", dependencies: [],
      published_at: `2026-11-0${++t}T00:00:00Z`, ...v,
    })
  }
  return db
}

// ----------------------------------------------------------------------------
// Publish and finalize
// ----------------------------------------------------------------------------

Deno.test("publish: kind max-app is stored on the new plugin; a plain publish sends no kind at all", async () => {
  for (const kind of ["max-app", undefined]) {
    const db = new FakeDb()
    const res = await mount(db).request("/publish", {
      method: "POST",
      headers: { ...bearer(AUTHOR, { user_permissions: ["plugins.create"] }), "Content-Type": "application/json" },
      body: JSON.stringify({
        pluginId: SAMPLE_PLUGIN_ID, displayName: "Habits", homepageUrl: "https://example.com", ...(kind ? { kind } : {}),
      }),
    })
    assertEquals(res.status, 201, await res.clone().text())
    assertEquals(db.plugins[0].kind, kind ?? "plugin")
  }
})

Deno.test("version: a Max app version is inserted pending_review on the channel asked for", async () => {
  const db = appDb()
  const res = await mount(db).request(`/${SAMPLE_PLUGIN_ID}/version`, {
    method: "POST",
    headers: { ...bearer(AUTHOR, { user_permissions: ["plugins.create"] }), "Content-Type": "application/json" },
    body: JSON.stringify({ version: "1.0.0", channel: "beta" }),
  })
  assertEquals(res.status, 201, await res.clone().text())
  assertEquals(db.versions[0].review_status, "pending_review")
  assertEquals(db.versions[0].channel, "beta")
})

Deno.test("finalize: a good Max app lands pending_review, UNSIGNED, with its review record", async () => {
  const jar = sampleJar()
  const db = appDb([{ version: "1.0.0", sha256: "pending", jar_size: 0, review_status: "pending_review" }])
  // The client's sha256 must match: finalize compares it with the server's hash first.
  const sha = await sha256Hex(jar)
  db.storage.set(db.versions[0].jar_path as string, jar)
  const res = await withStorageFetch(db, () =>
    mount(db).request("/version/finalize", {
      method: "POST",
      headers: { ...bearer(AUTHOR, { user_permissions: ["plugins.create"] }), "Content-Type": "application/json" },
      body: JSON.stringify({ versionId: db.versions[0].id, sha256: sha, jarSize: jar.length }),
    }) as Promise<Response>)
  assertEquals(res.status, 200, await res.clone().text())
  assertEquals((await res.json()).reviewStatus, "pending_review")
  const v = db.versions[0]
  assertEquals(v.sha256, sha)
  assertEquals(v.signature, null)
  assertEquals(v.review_status, "pending_review")
  const meta = v.review_meta as { app: { id: string }; stubClass: { name: string } }
  assertEquals(meta.app.id, "habits")
  assertEquals(meta.stubClass.name, "ai.rever.maxapp.habits.HabitsApp")
})

Deno.test("finalize: a Max app jar with a stray file is refused with its code and stays unfinalized", async () => {
  const jar = await sampleVariant({ "META-INF/max-app/tracker.js": text("x") })
  const db = appDb([{ version: "1.0.0", sha256: "pending", jar_size: 0, review_status: "pending_review" }])
  db.storage.set(db.versions[0].jar_path as string, jar)
  const res = await withStorageFetch(db, async () =>
    await mount(db).request("/version/finalize", {
      method: "POST",
      headers: { ...bearer(AUTHOR, { user_permissions: ["plugins.create"] }), "Content-Type": "application/json" },
      body: JSON.stringify({ versionId: db.versions[0].id, sha256: await sha256Hex(jar), jarSize: jar.length }),
    }))
  assertEquals(res.status, 400)
  assertEquals((await res.json()).code, "max_app_entry_not_allowed")
  assertEquals(db.versions[0].sha256, "pending")
})

Deno.test("finalize: a Max app version already reviewed is not re-opened", async () => {
  const jar = sampleJar()
  const db = appDb([{ version: "1.0.0", review_status: "approved", sha256: await sha256Hex(jar), jar_size: jar.length }])
  db.storage.set(db.versions[0].jar_path as string, jar)
  const res2 = await withStorageFetch(db, async () =>
    await mount(db).request("/version/finalize", {
      method: "POST",
      headers: { ...bearer(AUTHOR, { user_permissions: ["plugins.create"] }), "Content-Type": "application/json" },
      body: JSON.stringify({ versionId: db.versions[0].id, sha256: await sha256Hex(jar), jarSize: jar.length }),
    }))
  assertEquals(res2.status, 409)
  assertEquals((await res2.json()).code, "max_app_already_reviewed")
})

Deno.test("finalize: a plain plugin jar carrying META-INF/max-app/ is refused; one without is signed as before", async () => {
  const plainDb = () => {
    const db = new FakeDb()
    db.plugins.push({
      id: APP_UUID, plugin_id: SAMPLE_PLUGIN_ID, author_id: AUTHOR, published: true, kind: "plugin", org_id: null,
      required_permissions: [], display_name: "Habits", author_name: "author",
    })
    db.versions.push({
      id: crypto.randomUUID(), plugin_id: APP_UUID, version: "1.0.0", jar_path: "plugins/1.0.0.jar", jar_size: 0,
      sha256: "pending", signature: null, channel: "stable", review_status: "approved", changelog: "",
      min_boss_version: "1.0.0", min_ipc_version: "1.0.0", min_api_version: "", dependencies: [], published_at: "2026-11-01T00:00:00Z",
    })
    return db
  }
  const finalize = async (db: FakeDb, jar: Uint8Array) => {
    db.storage.set(db.versions[0].jar_path as string, jar)
    return await withStorageFetch(db, async () =>
      await mount(db).request("/version/finalize", {
        method: "POST",
        headers: { ...bearer(AUTHOR, { user_permissions: ["plugins.create"] }), "Content-Type": "application/json" },
        body: JSON.stringify({ versionId: db.versions[0].id, sha256: await sha256Hex(jar), jarSize: jar.length }),
      }))
  }
  // The sample Max app jar published as kind 'plugin': it would be signed and approved with no check or review, and
  // Max lists every signed jar's META-INF/max-app/.
  const sneaky = plainDb()
  const res = await finalize(sneaky, sampleJar())
  assertEquals(res.status, 400)
  assertEquals((await res.json()).code, "max_app_in_plugin")
  assertEquals(sneaky.versions[0].sha256, "pending")
  assertEquals(sneaky.versions[0].signature, null)

  const appFiles = ["META-INF/max-app/", "META-INF/max-app/app.json", "META-INF/max-app/index.html", "META-INF/max-app/SKILL.md", "META-INF/max-app/icon.svg"]
  const plain = plainDb()
  const ok = await finalize(plain, await sampleVariant(Object.fromEntries(appFiles.map((n) => [n, null]))))
  assertEquals(ok.status, 200, await ok.clone().text())
  assertEquals(plain.versions[0].review_status, "approved")
  assert(typeof plain.versions[0].signature === "string")
})

// ----------------------------------------------------------------------------
// Review routes
// ----------------------------------------------------------------------------

const pendingDb = () =>
  appDb([
    { version: "1.0.0", review_status: "approved", signature: "old-sig" },
    { version: "1.1.0", review_status: "pending_review", sha256: "b".repeat(64), review_meta: { app: { id: "habits" } } },
  ])

Deno.test("review queue: plugins.review required (401 / 403), admins and reviewers see pending finalized versions", async () => {
  const db = pendingDb()
  db.versions.push({ ...db.versions[1], id: crypto.randomUUID(), version: "1.2.0", sha256: "pending", jar_size: 0 })
  const app = mount(db)
  assertEquals((await app.request("/admin/review/queue")).status, 401)
  assertEquals((await app.request("/admin/review/queue", { headers: bearer(OUTSIDER) })).status, 403)
  for (const headers of [bearer(REVIEWER, { user_permissions: ["plugins.review"] }), bearer(ADMIN, { is_admin: true })]) {
    const res = await app.request("/admin/review/queue", { headers })
    assertEquals(res.status, 200)
    const body = await res.json()
    assertEquals(body.versions.map((v: { version: string }) => v.version), ["1.1.0"])
    assertEquals(body.versions[0].reviewMeta, { app: { id: "habits" } })
    assertEquals(body.versions[0].pluginId, SAMPLE_PLUGIN_ID)
  }
})

Deno.test("approve signs the version over pluginId|version|sha256 and audits it", async () => {
  const db = pendingDb()
  const v = db.versions[1]
  const res = await mount(db).request(`/admin/review/${v.id}/approve`, {
    method: "POST",
    headers: bearer(REVIEWER, { user_permissions: ["plugins.review"] }),
  })
  assertEquals(res.status, 200, await res.clone().text())
  const body = await res.json()
  assertEquals(body.reviewStatus, "approved")
  assertEquals(body.signed, true)
  assertEquals(v.review_status, "approved")
  assertEquals(v.reviewed_by, REVIEWER)
  assertExists(v.signature)
  assert(await verifies(v.signature as string, versionAnchor(SAMPLE_PLUGIN_ID, "1.1.0", "b".repeat(64))))
  assertEquals(db.audit, [{ actor: REVIEWER, action: "plugin_store.approve" }])
  const call = db.rpcCalls.find((c) => c.fn === "plugin_version_review")!
  assertEquals(call.args.p_sha256, "b".repeat(64), "the decision is bound to the bytes the reviewer saw")
})

Deno.test("reject records the note, leaves it unsigned, and needs a note", async () => {
  const db = pendingDb()
  const v = db.versions[1]
  const app = mount(db)
  const headers = { ...bearer(REVIEWER, { user_permissions: ["plugins.review"] }), "Content-Type": "application/json" }
  const empty = await app.request(`/admin/review/${v.id}/reject`, { method: "POST", headers, body: JSON.stringify({ note: "  " }) })
  assertEquals(empty.status, 400)
  const long = await app.request(`/admin/review/${v.id}/reject`, {
    method: "POST", headers, body: JSON.stringify({ note: "x".repeat(1001) }),
  })
  assertEquals(long.status, 400)
  const res = await app.request(`/admin/review/${v.id}/reject`, {
    method: "POST", headers, body: JSON.stringify({ note: "The page loads a remote script." }),
  })
  assertEquals(res.status, 200)
  assertEquals(v.review_status, "rejected")
  assertEquals(v.review_note, "The page loads a remote script.")
  assertEquals(v.signature, null)
  assertEquals(db.audit, [{ actor: REVIEWER, action: "plugin_store.reject" }])
  // Decided once: a second decision is a conflict.
  const again = await app.request(`/admin/review/${v.id}/approve`, { method: "POST", headers })
  assertEquals(again.status, 409)
})

Deno.test("review: no permission is 403, your own app is 403 (admins excepted), a plugin version is 404", async () => {
  const db = pendingDb()
  const v = db.versions[1]
  const app = mount(db)
  assertEquals((await app.request(`/admin/review/${v.id}/approve`, { method: "POST", headers: bearer(OUTSIDER) })).status, 403)
  const own = await app.request(`/admin/review/${v.id}/approve`, {
    method: "POST", headers: bearer(AUTHOR, { user_permissions: ["plugins.review"] }),
  })
  assertEquals(own.status, 403)
  db.plugins[0].kind = "plugin"
  const notApp = await app.request(`/admin/review/${v.id}/approve`, {
    method: "POST", headers: bearer(REVIEWER, { user_permissions: ["plugins.review"] }),
  })
  assertEquals(notApp.status, 404)
  assertEquals(v.review_status, "pending_review")
})

// ----------------------------------------------------------------------------
// Release gates on download
// ----------------------------------------------------------------------------

async function latest(db: FakeDb, headers: HeadersInit = {}, channel = "") {
  resetRateLimits()
  const res = await mount(db).request(`/${SAMPLE_PLUGIN_ID}/download${channel ? `?channel=${channel}` : ""}`, { headers })
  return { status: res.status, body: await res.json() }
}

Deno.test("a pending version is never latest, not even for its author or a reviewer", async () => {
  const db = pendingDb()
  for (const h of [{}, bearer(AUTHOR), bearer(REVIEWER), bearer(ADMIN, { is_admin: true })]) {
    const r = await latest(db, h)
    assertEquals(r.status, 200)
    assertEquals(r.body.version, "1.0.0")
  }
  // Approved, it becomes latest.
  db.versions[1].review_status = "approved"
  assertEquals((await latest(db)).body.version, "1.1.0")
})

Deno.test("a pending or rejected version downloads by number only for its publisher and reviewers", async () => {
  const db = pendingDb()
  const get = async (headers: HeadersInit) => {
    resetRateLimits()
    return (await mount(db).request(`/${SAMPLE_PLUGIN_ID}/download/1.1.0`, { headers })).status
  }
  assertEquals(await get({}), 404)
  assertEquals(await get(bearer(OUTSIDER)), 404)
  assertEquals(await get(bearer(AUTHOR)), 200)
  assertEquals(await get(bearer(REVIEWER)), 200)
  db.versions[1].review_status = "rejected"
  assertEquals(await get(bearer(OUTSIDER)), 404)
  assertEquals(await get(bearer(AUTHOR)), 200)
})

Deno.test("channels: beta/test latest only for who may test; everyone else gets stable", async () => {
  const db = appDb([
    { version: "1.0.0" },
    { version: "1.1.0-beta", channel: "beta" },
    { version: "1.2.0-test", channel: "test" },
    { version: "1.3.0", channel: "test", review_status: "pending_review" },
  ])
  assertEquals((await latest(db)).body.version, "1.0.0")
  assertEquals((await latest(db, {}, "beta")).body.version, "1.0.0", "anonymous: stable")
  assertEquals((await latest(db, bearer(OUTSIDER), "test")).body.version, "1.0.0", "no access: stable")
  const beta = await latest(db, bearer(TESTER), "beta")
  assertEquals(beta.body.version, "1.1.0-beta")
  assertEquals(beta.body.channel, "beta")
  assertEquals((await latest(db, bearer(TESTER), "test")).body.version, "1.2.0-test", "never the pending one")
  assertEquals((await latest(db, bearer(AUTHOR), "test")).body.version, "1.2.0-test")
  // Without ?channel= even a tester gets stable, and the stable answer carries no release fields.
  const plain = await latest(db, bearer(TESTER))
  assertEquals(plain.body.version, "1.0.0")
  assertEquals("channel" in plain.body, false)
  // A bad channel is a 400 from the schema.
  resetRateLimits()
  assertEquals((await mount(db).request(`/${SAMPLE_PLUGIN_ID}/download?channel=nightly`)).status, 400)
})

Deno.test("channels: an explicit beta version downloads only for who may test", async () => {
  const db = appDb([{ version: "1.0.0" }, { version: "1.1.0", channel: "beta" }])
  const get = async (headers: HeadersInit) => {
    resetRateLimits()
    return (await mount(db).request(`/${SAMPLE_PLUGIN_ID}/download/1.1.0`, { headers })).status
  }
  assertEquals(await get({}), 404)
  assertEquals(await get(bearer(OUTSIDER)), 404)
  assertEquals(await get(bearer(TESTER)), 200)
  assertEquals(await get(bearer(AUTHOR)), 200)
})

Deno.test("plain plugins: no extra release calls on the old download paths", async () => {
  const db = appDb([{ version: "1.0.0" }])
  db.plugins[0].kind = "plugin"
  await latest(db)
  resetRateLimits()
  await mount(db).request(`/${SAMPLE_PLUGIN_ID}/download/1.0.0`)
  assertEquals(db.rpcCalls.filter((c) => c.fn.startsWith("plugin_")).length, 0)
})

Deno.test("catalogue: ?kind= / ?channel= use search_plugins_channel; without them the old RPC", async () => {
  const db = appDb()
  const app = mount(db)
  resetRateLimits()
  await app.request("/list")
  await app.request("/list?kind=max-app&channel=beta", { headers: bearer(TESTER) })
  await app.request("/search?kind=max-app", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ query: "habit" }),
  })
  const fns = db.rpcCalls.map((c) => c.fn)
  assertEquals(fns, ["search_plugins", "search_plugins_channel", "search_plugins_channel"])
  const listed = db.rpcCalls[1].args
  assertEquals([listed.p_viewer_id, listed.p_kind, listed.p_channel], [TESTER, "max-app", "beta"])
  assertEquals([db.rpcCalls[2].args.p_query, db.rpcCalls[2].args.p_channel], ["habit", "stable"])
})

Deno.test("both GitHub publish paths refuse to version a Max app (they would sign it without review)", () => {
  // Those routes need a real GitHub release to reach the check, so the wiring is pinned on the source.
  const src = Deno.readTextFileSync(new URL("../routes/publish.ts", import.meta.url))
  assertEquals(src.match(/existingPlugin\.kind === 'max-app'/g)?.length, 2)
})

Deno.test("both GitHub publish paths refuse a jar that carries a Max app (it would be signed as a plain plugin)", () => {
  const src = Deno.readTextFileSync(new URL("../routes/publish.ts", import.meta.url))
  assert(/if \(maxAppEntryInZip\(new Uint8Array\(jarData\)\)\)/.test(src), "/github")
  assert(/maxAppEntry = extracted\.maxAppEntry/.test(src) && /if \(maxAppEntry\) \{/.test(src), "/github-metadata")
  assertEquals(src.match(/MAX_APP_IN_PLUGIN_CODE, error: MAX_APP_IN_PLUGIN_ERROR/g)?.length, 3)
})
