/**
 * The shipped page script, run against a tiny fake DOM: opening a Fluck frames it, and the
 * frame's postMessage protocol is honoured only from the frame's own window and origin.
 *
 * Run: cd supabase/functions/fluck-web && deno test --allow-all
 */

import { assert, assertEquals } from "@std/assert"
import { fluckPage } from "../views/page.ts"

const ENDPOINT = "https://mac.example.ts.net"
const OPEN_URL = `${ENDPOINT}/#/t/${"A".repeat(40)}b-_`
// A restarted Fluck: a new tunnel host.
const NEW_ENDPOINT = "https://rotated.example.ts.net"
const NEW_URL = `${NEW_ENDPOINT}/#/t/${"B".repeat(40)}c-_`

type Listener = (ev: unknown) => void

class FakeElement {
  textContent = ""
  innerHTML = ""
  value = ""
  disabled = false
  type = ""
  onclick: Listener | null = null
  style: Record<string, string> = {}
  children: FakeElement[] = []
  attrs: Record<string, string> = {}
  listeners: Record<string, Listener[]> = {}
  contentWindow: object | null = null
  id = ""
  tag = ""
  parent: FakeElement | null = null
  removed = false
  focused = false
  private classes = new Set<string>()
  classList = {
    toggle: (c: string, on?: boolean) => {
      const want = on === undefined ? !this.classes.has(c) : on
      if (want) this.classes.add(c)
      else this.classes.delete(c)
    },
    add: (c: string) => void this.classes.add(c),
    remove: (c: string) => void this.classes.delete(c),
    contains: (c: string) => this.classes.has(c),
  }
  get className() { return [...this.classes].join(" ") }
  set className(v: string) { this.classes = new Set(v.split(/\s+/).filter(Boolean)) }
  setAttribute(name: string, value: string) { this.attrs[name] = value }
  getAttribute(name: string) { return this.attrs[name] ?? null }
  addEventListener(type: string, fn: Listener) { (this.listeners[type] ??= []).push(fn) }
  appendChild(child: FakeElement) { child.parent = this; this.children.push(child) }
  remove() {
    this.removed = true
    if (this.parent) this.parent.children = this.parent.children.filter((c) => c !== this)
    this.parent = null
  }
  focus() { this.focused = true }
}

interface PushOptions {
  permission?: "default" | "granted" | "denied"
  /** What requestPermission() resolves to. */
  grant?: "granted" | "denied" | "default"
  subscribed?: boolean
  userAgent?: string
  standalone?: boolean
  /** Omit serviceWorker, PushManager and Notification altogether. */
  unsupported?: boolean
  config?: { status: number; body: unknown }
}

interface HarnessOptions {
  push?: PushOptions
  /** Answers /api/open after the first (which is OPEN_URL). */
  openUrls?: string[]
}

function harness(search: string, initialState: unknown = null, options: HarnessOptions = {}) {
  const elements = new Map<string, FakeElement>()
  const get = (id: string) => {
    if (!elements.has(id)) elements.set(id, new FakeElement())
    return elements.get(id)!
  }
  const html = fluckPage({ basePath: "", liveWindowSeconds: 90 }, "test")
  get("cfg").textContent = /<script id="cfg" type="application\/json">([\s\S]*?)<\/script>/.exec(html)![1]
  const posted: { data: unknown; origin: string }[] = []
  const windowWithPost = () => ({ postMessage: (data: unknown, origin: string) => void posted.push({ data, origin }) })
  const frameWindow = windowWithPost()
  get("fluckframe").contentWindow = frameWindow
  get("fluckframe").setAttribute("allow", "clipboard-read; fullscreen")
  get("push-sheet").classList.add("hidden") // as the markup ships
  const frames: FakeElement[] = []
  const body = new FakeElement()
  if (/<body class="launching">/.test(html)) body.classList.add("launching") // as the markup ships
  const main = new FakeElement()
  const rootVars: Record<string, string> = {}
  const documentElement = {
    style: {
      setProperty: (k: string, v: string) => void (rootVars[k] = v),
      removeProperty: (k: string) => void delete rootVars[k],
    },
  }
  // The theme-color metas as the markup ships them.
  for (const m of html.matchAll(/<meta id="(theme-[a-z]+)" name="theme-color" content="([^"]+)"/g)) get(m[1]).setAttribute("content", m[2])
  const docListeners: Record<string, Listener[]> = {}
  const document = {
    title: "Fluck",
    body,
    documentElement,
    getElementById: get,
    createElement: (tag: string) => {
      const el = new FakeElement()
      el.tag = tag
      if (tag === "iframe") { el.contentWindow = windowWithPost(); frames.push(el) }
      return el
    },
    createTextNode: (t: string) => Object.assign(new FakeElement(), { textContent: t }),
    querySelector: (sel: string) => sel === "main" ? main : null,
    addEventListener: (type: string, fn: Listener) => { (docListeners[type] ??= []).push(fn) },
    visibilityState: "visible",
  }
  const winListeners: Record<string, Listener[]> = {}
  const storage = new Map<string, string>()
  const pushOpts = options.push ?? {}
  const pushLog = { registered: [] as string[], subscribeOpts: null as unknown, requested: 0, subscribed: pushOpts.subscribed ?? false }
  const fakeSub = {
    endpoint: "https://fcm.googleapis.com/fcm/send/abc",
    toJSON: () => ({ endpoint: fakeSub.endpoint, expirationTime: null, keys: { p256dh: "p".repeat(87), auth: "a".repeat(22) } }),
    unsubscribe: () => { pushLog.subscribed = false; return Promise.resolve(true) },
  }
  const fakeReg = {
    active: {},
    pushManager: {
      getSubscription: () => Promise.resolve(pushLog.subscribed ? fakeSub : null),
      subscribe: (opts: unknown) => { pushLog.subscribeOpts = opts; pushLog.subscribed = true; return Promise.resolve(fakeSub) },
    },
  }
  const notification = {
    permission: pushOpts.permission ?? "default",
    requestPermission: () => {
      pushLog.requested++
      notification.permission = pushOpts.grant ?? "granted"
      return Promise.resolve(notification.permission)
    },
  }
  const navigator: Record<string, unknown> = {
    userAgent: pushOpts.userAgent ?? "Mozilla/5.0 (Macintosh)",
    platform: "MacIntel",
    maxTouchPoints: 0,
    standalone: pushOpts.standalone,
  }
  if (!pushOpts.unsupported) {
    navigator.serviceWorker = {
      register: (url: string) => { pushLog.registered.push(url); return Promise.resolve(fakeReg) },
      getRegistration: () => Promise.resolve(pushLog.registered.length > 0 || pushLog.subscribed ? fakeReg : undefined),
    }
  }
  const window: Record<string, unknown> = {
    navigator,
    ...(pushOpts.unsupported ? {} : { PushManager: {}, Notification: notification }),
    matchMedia: () => ({ matches: pushOpts.standalone === true }),
    innerHeight: 800,
    addEventListener: (type: string, fn: Listener) => { (winListeners[type] ??= []).push(fn) },
    localStorage: {
      getItem: (k: string) => storage.get(k) ?? null,
      setItem: (k: string, v: string) => void storage.set(k, v),
      removeItem: (k: string) => void storage.delete(k),
    },
  }
  const assigned: string[] = []
  const location = { pathname: "/", search, hash: "", assign: (u: string) => void assigned.push(u) }
  // Fake timers for the page script only; the test's own awaits use the real ones.
  let nextTimer = 1
  const timers = new Map<number, { fn: () => void; ms: number }>()
  const fakeSetTimeout = (fn: () => void, ms: number) => { timers.set(nextTimer, { fn, ms }); return nextTimer++ }
  const fakeClearTimeout = (id: number) => void timers.delete(id)
  const fire = (ms: number) => {
    for (const [id, t] of [...timers]) if (t.ms === ms) { timers.delete(id); t.fn() }
  }
  const pending = (ms: number) => [...timers.values()].filter((t) => t.ms === ms).length
  const pushed: unknown[] = []
  const history = {
    state: initialState,
    pushState(state: unknown, _t: string, url: string) {
      pushed.push(state); this.state = state
      const u = new URL(url, "https://fluck.risaboss.com")
      location.pathname = u.pathname; location.search = u.search; location.hash = u.hash
    },
    replaceState(state: unknown, _t: string, url: string) {
      this.state = state
      const u = new URL(url, "https://fluck.risaboss.com")
      location.pathname = u.pathname; location.search = u.search; location.hash = u.hash
    },
  }
  const calls: string[] = []
  const requests: { url: string; init?: RequestInit }[] = []
  const state = { online: true, openStatus: 200, instancesStatus: 200, subscribeStatus: 200 }
  let opens = 0
  const fetch = (url: string, init?: RequestInit) => {
    calls.push(url)
    requests.push({ url, init })
    if (url === "/api/instances") {
      if (state.instancesStatus !== 200) return Promise.resolve(new Response("{}", { status: state.instancesStatus }))
      return Promise.resolve(Response.json({
        csrf: "c".repeat(64), email: "owner@example.com",
        instances: [{ instance_id: "i1", agent_name: "Fluck", label: "Mac", online: state.online, last_seen_at: "2026-10-13T10:00:00Z" }],
      }))
    }
    if (url === "/api/open") {
      const n = opens++
      if (state.openStatus !== 200) return Promise.resolve(Response.json({ error: "x" }, { status: state.openStatus }))
      return Promise.resolve(Response.json({ url: n === 0 ? OPEN_URL : (options.openUrls?.[n - 1] ?? NEW_URL) }))
    }
    if (url === "/api/push/config") {
      const c = pushOpts.config ?? { status: 200, body: { vapid_public_key: "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8" } }
      return Promise.resolve(Response.json(c.body, { status: c.status }))
    }
    if (url === "/api/push/subscribe") return Promise.resolve(Response.json({ ok: true }, { status: state.subscribeStatus }))
    if (url === "/api/push/unsubscribe") return Promise.resolve(Response.json({ ok: true }))
    return Promise.resolve(new Response("{}", { status: 404 }))
  }
  // The page script's clock: reconnect timing is wall-clock (grace period, give-up), so tests drive it.
  let clock = Date.parse("2026-10-13T10:00:00Z")
  const FakeDate = { now: () => clock, parse: (v: string) => Date.parse(v) }
  const script = /<script nonce="test">([\s\S]*?)<\/script>/.exec(html)![1]
  new Function("document", "window", "location", "history", "fetch", "setInterval", "clearInterval", "setTimeout", "clearTimeout", "Date", script)(
    document, window, location, history, fetch, () => 1, () => {}, fakeSetTimeout, fakeClearTimeout, FakeDate,
  )
  const message = (data: unknown, opts: { origin?: string; source?: object } = {}) => {
    for (const fn of winListeners.message ?? []) fn({ data, origin: opts.origin ?? ENDPOINT, source: opts.source ?? frameWindow })
  }
  const popstate = () => { for (const fn of winListeners.popstate ?? []) fn({}) }
  /** A message from the hidden replacement frame, as its own window on its own origin. */
  const messageFrom = (el: FakeElement, data: unknown, origin = NEW_ENDPOINT) => {
    for (const fn of winListeners.message ?? []) fn({ data, origin, source: el.contentWindow })
  }
  const advance = (ms: number) => void (clock += ms)
  const dispatchDoc = (type: string) => { for (const fn of docListeners[type] ?? []) fn({}) }
  const dispatchWin = (type: string) => { for (const fn of winListeners[type] ?? []) fn({}) }
  const click = (id: string) => { for (const fn of get(id).listeners.click ?? []) fn({}) }
  const pushStates = () => posted.filter((p) => (p.data as { type?: string }).type === "fluck-push-state").map((p) => (p.data as { state: string }).state)
  const settle = async () => { for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 0)) }
  return {
    get, rootVars, body, document, history, location, pushed, calls, frameWindow, message, popstate, settle, assigned, fire, pending,
    posted, frames, messageFrom, advance, dispatchDoc, dispatchWin, click, pushStates, requests, state, pushLog, notification, main,
  }
}

async function opened() {
  const h = harness("?instance=i1")
  await h.settle()
  assertEquals(h.get("fluckframe").getAttribute("src"), OPEN_URL, "the Fluck opens in the frame")
  assert(h.body.classList.contains("viewing"))
  return h
}

Deno.test("?instance= opens the Fluck in the frame in place, keeps ?instance, never navigates", async () => {
  const h = await opened()
  assertEquals(h.pushed, [], "already on this Fluck's address: replaced, not pushed")
  assertEquals(h.history.state, { view: "fluck" })
  assertEquals(h.location.search, "?instance=i1", "?instance stays so a reload reopens it")
  assert(!h.location.hash.includes("/t/"), "the ticket never enters the address bar")
})

Deno.test("fluck-title sets a capped, single-line document title; only from the frame", async () => {
  const h = await opened()
  h.message({ type: "fluck-title", title: "Chat\n<b>one</b>" })
  assertEquals(h.document.title, "Chat <b>one</b>")
  h.message({ type: "fluck-title", title: "x".repeat(500) })
  assertEquals(h.document.title.length, 120)
  h.message({ type: "fluck-title", title: 42 })
  assertEquals(h.document.title.length, 120, "non-string title ignored")
  h.message({ type: "fluck-title", title: "evil" }, { origin: "https://evil.example" })
  h.message({ type: "fluck-title", title: "evil" }, { source: {} })
  assertEquals(h.document.title.length, 120, "wrong origin or source ignored")
})

const themeOf = (h: ReturnType<typeof harness>) =>
  [h.get("theme-light").getAttribute("content"), h.get("theme-dark").getAttribute("content"), h.rootVars["--boot-bg"]]

Deno.test("the page ships the web chat's --surface as theme-color for each scheme, edge to edge", () => {
  const html = fluckPage({ basePath: "", liveWindowSeconds: 90 }, "test")
  assert(html.includes('<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'))
  assert(html.includes('<meta id="theme-light" name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">'))
  assert(html.includes('<meta id="theme-dark" name="theme-color" content="#1c1c1e" media="(prefers-color-scheme: dark)">'))
  assertEquals(themeOf(harness("")), ["#ffffff", "#1c1c1e", undefined])
})

Deno.test("fluck-theme from the frame sets theme-color and the backgrounds; closing the frame restores the defaults", async () => {
  const h = await opened()
  h.message({ type: "fluck-theme", color: "#1C1C1E" })
  assertEquals(themeOf(h), ["#1C1C1E", "#1C1C1E", "#1C1C1E"])
  h.message({ type: "fluck-theme", color: "#2a1f3d" }) // a persona's own background, or a scheme flip
  assertEquals(themeOf(h), ["#2a1f3d", "#2a1f3d", "#2a1f3d"])
  h.message({ type: "fluck-switch" })
  assertEquals(themeOf(h), ["#ffffff", "#1c1c1e", undefined], "the list is back on the defaults")
  h.message({ type: "fluck-theme", color: "#000000" })
  assertEquals(themeOf(h), ["#ffffff", "#1c1c1e", undefined], "nothing framed: ignored")
})

Deno.test("fluck-theme from another origin or window, or with a bad colour, is ignored", async () => {
  const h = await opened()
  h.message({ type: "fluck-theme", color: "#000000" }, { origin: "https://evil.example" })
  h.message({ type: "fluck-theme", color: "#000000" }, { source: {} })
  for (const color of ["#fff", "red", "#1c1c1e;", "#1c1c1e ", "url(x)", "#gggggg", 0x1c1c1e, null]) {
    h.message({ type: "fluck-theme", color })
  }
  assertEquals(themeOf(h), ["#ffffff", "#1c1c1e", undefined])
})

Deno.test("messages from another origin or window, or of unknown type, do not close the frame", async () => {
  const h = await opened()
  h.message({ type: "fluck-signed-out" }, { origin: "https://evil.example" })
  h.message({ type: "fluck-signed-out" }, { origin: "https://fluck.risaboss.com" })
  h.message({ type: "fluck-switch" }, { source: {} })
  h.message({ type: "something-else" })
  h.message("fluck-signed-out")
  h.message(null)
  assertEquals(h.get("fluckframe").getAttribute("src"), OPEN_URL)
  assert(h.body.classList.contains("viewing"))
})

Deno.test("fluck-signed-out closes the frame and reloads the list without reopening", async () => {
  const h = await opened()
  h.document.title = "Chat"
  const before = h.calls.filter((c) => c === "/api/instances").length
  h.message({ type: "fluck-signed-out" })
  assertEquals(h.get("fluckframe").getAttribute("src"), "about:blank")
  assert(!h.body.classList.contains("viewing"))
  assertEquals(h.document.title, "Fluck")
  await h.settle()
  assertEquals(h.calls.filter((c) => c === "/api/instances").length, before + 1)
  assertEquals(h.calls.filter((c) => c === "/api/open").length, 1, "no auto-reopen")
  assert(!h.get("list").classList.contains("hidden"), "the list is shown")
})

Deno.test("fluck-switch closes the frame and shows the list", async () => {
  const h = await opened()
  h.message({ type: "fluck-switch" })
  assertEquals(h.get("fluckframe").getAttribute("src"), "about:blank")
  assert(!h.get("list").classList.contains("hidden"))
  await h.settle()
  assertEquals(h.calls.filter((c) => c === "/api/open").length, 1, "no auto-reopen")
  h.message({ type: "fluck-title", title: "late" })
  assertEquals(h.document.title, "Fluck", "messages after close are ignored")
})

Deno.test("browser Back closes the frame; a reload of an open Fluck reopens it with a fresh ticket", async () => {
  const h = await opened()
  h.popstate()
  assertEquals(h.get("fluckframe").getAttribute("src"), "about:blank")
  await h.settle()
  assertEquals(h.calls.filter((c) => c === "/api/open").length, 1)

  // Reload while framed: the address still carries ?instance, so the same Fluck opens again.
  const r = harness("?instance=i1", { view: "fluck" })
  await r.settle()
  assertEquals(r.calls.filter((c) => c === "/api/open").length, 1, "a fresh ticket, no list")
  assertEquals(r.get("fluckframe").getAttribute("src"), OPEN_URL)
  assertEquals(r.location.search, "?instance=i1")
})

Deno.test("opening from the list pushes ?instance=<id>; switch and sign-out drop it", async () => {
  for (const close of ["switch", "signed-out"]) {
    const h = harness("")
    await h.settle()
    await h.settle()
    assertEquals(h.pushed, [{ view: "fluck" }], "Back returns to the list")
    assertEquals(h.location.search, "?instance=i1")
    h.message({ type: "fluck-hello" })
    h.message({ type: "fluck-" + close })
    assertEquals(h.location.search, "", close + ": a reload shows the list")
  }
})

Deno.test("one online Fluck opens straight away: no countdown, never the list or the portal chrome", async () => {
  const h = harness("")
  assert(h.body.classList.contains("launching"), "starts launching")
  await h.settle()
  assertEquals(h.pending(1500), 0, "no countdown")
  assertEquals(h.get("fluckframe").getAttribute("src"), OPEN_URL)
  assert(h.get("list").classList.contains("hidden"), "the list never showed")
  assert(!h.body.classList.contains("launching"))
})

Deno.test("the launch splash names the Fluck it opens, from the registry row", async () => {
  const h = harness("")
  assertEquals(h.get("launch-name").textContent, "", "unknown until the list loads")
  await h.settle()
  assertEquals(h.get("launch-name").textContent, "Fluck")
  assertEquals(h.get("launch-status").textContent, "Opening on Mac.")
})

Deno.test("without the reload marker, a single online Fluck still auto-opens in the frame", async () => {
  const h = harness("")
  await h.settle()
  await h.settle()
  assertEquals(h.get("fluckframe").getAttribute("src"), OPEN_URL)
})

const HELLO_MS = 8000

Deno.test("fluck-hello from the frame cancels the top-level fallback", async () => {
  const h = await opened()
  assertEquals(h.pending(HELLO_MS), 1, "opening arms the fallback")
  h.message({ type: "fluck-hello" }, { origin: "https://evil.example" })
  h.message({ type: "fluck-hello" }, { source: {} })
  assertEquals(h.pending(HELLO_MS), 1, "a hello from the wrong origin or window does not count")
  h.message({ type: "fluck-hello" })
  assertEquals(h.pending(HELLO_MS), 0)
  assertEquals(h.assigned, [])
  assert(h.body.classList.contains("viewing"), "the Fluck stays framed")
})

Deno.test("no fluck-hello in time: close the frame, replace the entry with ?list=1, navigate top-level", async () => {
  const h = await opened()
  h.fire(HELLO_MS)
  assertEquals(h.assigned, [OPEN_URL])
  assertEquals(h.get("fluckframe").getAttribute("src"), "about:blank")
  assert(!h.body.classList.contains("viewing"))
  assertEquals(h.location.search, "?list=1")
  assertEquals(h.history.state, null)
  await h.settle()
  assertEquals(h.calls.filter((c) => c === "/api/open").length, 1, "the same ticket, not a new one")
})

Deno.test("closing the frame or Back before the hello timeout cancels the fallback", async () => {
  for (const close of ["switch", "signed-out", "popstate"]) {
    const h = await opened()
    if (close === "popstate") h.popstate()
    else h.message({ type: "fluck-" + close })
    assertEquals(h.pending(HELLO_MS), 0, close)
    await h.settle()
    assertEquals(h.assigned, [], close)
  }
})

Deno.test("the list: each Fluck is one row button (name, status, machine); the account row shows; a tap opens it", async () => {
  const h = harness("?list=1")
  await h.settle()
  assert(!h.get("list").classList.contains("hidden"), "?list=1 shows the list")
  const li = h.get("instances").children[0]!
  const row = li.children[0]!
  assertEquals(row.className, "inst on")
  assertEquals(row.disabled, false)
  assertEquals(row.getAttribute("aria-label"), "Fluck on Mac, online")
  const [icon, text] = row.children
  assertEquals(icon!.className, "inst-icon fluck")
  assertEquals(text!.children[0]!.textContent, "Fluck")
  assertEquals(text!.children[1]!.children[1]!.textContent, "Mac · Online")
  assertEquals(h.get("who").textContent, "owner@example.com")
  assert(!h.get("account").classList.contains("hidden"), "signed-in account row at the bottom")
  for (const fn of row.listeners.click ?? []) fn({})
  await h.settle()
  assertEquals(h.get("fluckframe").getAttribute("src"), OPEN_URL)
  assert(h.body.classList.contains("viewing"), "the Fluck is framed (body.viewing hides the account row)")
  assert(h.get("account").classList.contains("hidden"), "show(\"opening\") hid the account row")
})

// ---- Seamless reconnect ----

const GRACE_MS = 8000
const SWAP_HELLO_MS = 10000
const openCalls = (h: ReturnType<typeof harness>) => h.calls.filter((c) => c === "/api/open").length
const instanceCalls = (h: ReturnType<typeof harness>) => h.calls.filter((c) => c === "/api/instances").length

async function helloed(options: HarnessOptions = {}) {
  const h = harness("?instance=i1", null, options)
  await h.settle()
  h.message({ type: "fluck-hello" })
  return h
}

/** offline -> first poll sees it online -> the 8 s grace passes -> a hidden frame is loading NEW_URL. */
async function swapping(options: HarnessOptions = {}) {
  const h = await helloed(options)
  h.message({ type: "fluck-offline" })
  h.fire(3000)
  await h.settle()
  h.advance(GRACE_MS)
  h.fire(GRACE_MS)
  await h.settle()
  assertEquals(h.frames.length, 1, "a second frame is loading")
  return h
}

Deno.test("fluck-offline polls the registry, and once it is online without a fluck-online loads a hidden second frame", async () => {
  const h = await helloed()
  const before = instanceCalls(h)
  h.message({ type: "fluck-offline" })
  assertEquals(h.pending(3000), 1, "polls every 3 s to start")
  h.fire(3000)
  await h.settle()
  assertEquals(instanceCalls(h), before + 1)
  assertEquals(openCalls(h), 1, "online, but not for 8 s yet: no new ticket")
  assertEquals(h.pending(GRACE_MS), 1, "the 8 s wait for fluck-online is running")
  h.advance(GRACE_MS)
  h.fire(GRACE_MS)
  await h.settle()
  assertEquals(openCalls(h), 2)
  const next = h.frames[0]
  assertEquals(next.getAttribute("src"), NEW_URL)
  assert(next.classList.contains("pending"), "hidden until it says hello")
  assertEquals(next.getAttribute("allow"), "clipboard-read; fullscreen", "same permissions as the live frame")
  assertEquals(h.get("fluckframe").getAttribute("src"), OPEN_URL, "the live frame is untouched")
  assert(h.body.classList.contains("viewing"))
  assertEquals(h.assigned, [], "never a top-level navigation in the background")
})

Deno.test("the replacement frame is swapped in only on its own fluck-hello, then the old frame is removed", async () => {
  const h = await swapping()
  const old = h.get("fluckframe")
  const next = h.frames[0]
  h.messageFrom(next, { type: "fluck-hello" }, "https://evil.example")
  h.message({ type: "fluck-hello" }, { source: {} })
  h.messageFrom(next, { type: "fluck-title", title: "early" })
  assert(next.classList.contains("pending"), "a hello from the wrong origin or window, or any other message, does not swap")
  assertEquals(h.document.title, "Fluck")
  h.messageFrom(next, { type: "fluck-hello" })
  assert(!next.classList.contains("pending"), "visible")
  assert(old.removed, "the old frame is gone")
  assertEquals(next.id, "fluckframe")
  assertEquals(h.assigned, [])
  assertEquals(h.pending(SWAP_HELLO_MS), 0, "the hello timeout is cleared")
  assertEquals(h.pending(3000), 0, "recovery is over")
  // The new frame is now the live one: its messages count, the old frame's no longer do.
  h.messageFrom(next, { type: "fluck-title", title: "Back" })
  assertEquals(h.document.title, "Back")
  h.message({ type: "fluck-title", title: "stale" })
  assertEquals(h.document.title, "Back")
  // ...and replies go to it, at its own origin.
  h.messageFrom(next, { type: "fluck-push", action: "query" })
  await h.settle()
  assertEquals(h.posted.filter((p) => p.origin === NEW_ENDPOINT).length, 1)
})

Deno.test("no hello from the replacement in time: it is dropped, the live frame stays, and the next poll retries", async () => {
  const h = await swapping()
  const old = h.get("fluckframe")
  h.fire(SWAP_HELLO_MS)
  assert(h.frames[0].removed, "the replacement is discarded")
  assert(!old.removed && old.getAttribute("src") === OPEN_URL, "the live frame is untouched")
  assertEquals(h.assigned, [], "the top-level fallback is for the first open only")
  assert(h.body.classList.contains("viewing"))
  // The next poll (still online for 8 s or more) tries again with a fresh ticket.
  h.advance(GRACE_MS)
  h.fire(4500)
  await h.settle()
  assertEquals(openCalls(h), 3)
  assertEquals(h.frames.length, 2)
  h.messageFrom(h.frames[1], { type: "fluck-hello" })
  assert(old.removed)
  assertEquals(h.assigned, [])
})

Deno.test("fluck-online cancels the poll, the wait and a loading replacement", async () => {
  const h = await helloed()
  h.message({ type: "fluck-offline" })
  h.fire(3000)
  await h.settle()
  assertEquals(h.pending(GRACE_MS), 1)
  h.message({ type: "fluck-online" })
  assertEquals(h.pending(GRACE_MS), 0)
  assertEquals(h.pending(4500), 0)
  assertEquals(openCalls(h), 1, "no new ticket")

  const s = await swapping()
  s.message({ type: "fluck-online" })
  assert(s.frames[0].removed, "the loading replacement is dropped")
  assertEquals(s.pending(SWAP_HELLO_MS), 0)
  assertEquals(s.get("fluckframe").getAttribute("src"), OPEN_URL)
})

Deno.test("while the Fluck is not online yet the poll backs off from 3 s to 15 s and stops after 10 minutes", async () => {
  const h = await helloed()
  h.state.online = false
  h.message({ type: "fluck-offline" })
  const delays = [3000, 4500, 6750, 10125, 15000, 15000]
  for (const [i, d] of delays.entries()) {
    assertEquals(h.pending(d), 1, `poll ${i} at ${d} ms`)
    h.fire(d)
    await h.settle()
  }
  assertEquals(openCalls(h), 1, "never online: never a ticket")
  assertEquals(h.pending(GRACE_MS), 0)
  h.advance(10 * 60 * 1000 + 1)
  h.fire(15000)
  await h.settle()
  assertEquals(h.pending(15000), 0, "gave up")
  assert(h.body.classList.contains("viewing"), "the frame is left as it was")
})

Deno.test("becoming visible or the network returning while offline polls at once; online frames do not", async () => {
  const h = await helloed()
  h.state.online = false
  const base = instanceCalls(h)
  h.dispatchDoc("visibilitychange")
  h.dispatchWin("online")
  await h.settle()
  assertEquals(instanceCalls(h), base, "not marked offline: nothing to re-check")
  h.message({ type: "fluck-offline" })
  h.dispatchDoc("visibilitychange")
  await h.settle()
  assertEquals(instanceCalls(h), base + 1, "visible again")
  h.dispatchWin("online")
  await h.settle()
  assertEquals(instanceCalls(h), base + 2, "network back")
  h.message({ type: "fluck-online" })
  h.dispatchDoc("visibilitychange")
  await h.settle()
  assertEquals(instanceCalls(h), base + 2, "back online: nothing further")
})

Deno.test("a re-check after giving up starts a new 10 minute window", async () => {
  const h = await helloed()
  h.state.online = false
  h.message({ type: "fluck-offline" })
  h.advance(10 * 60 * 1000 + 1)
  h.fire(3000)
  await h.settle()
  assertEquals(h.pending(3000) + h.pending(4500), 0, "gave up")
  h.dispatchDoc("visibilitychange")
  await h.settle()
  assertEquals(h.pending(3000), 1, "polling again")
})

Deno.test("a 401 while recovering falls back to the signed-out behaviour", async () => {
  const h = await helloed()
  h.state.instancesStatus = 401
  h.message({ type: "fluck-offline" })
  h.fire(3000)
  await h.settle()
  assertEquals(h.get("fluckframe").getAttribute("src"), "about:blank")
  assertEquals(h.location.search, "")
})

Deno.test("fluck-session-lost re-mints at once and swaps on hello; a 4th loss in 2 minutes behaves as signed out", async () => {
  const h = await helloed()
  // Each swapped-in frame is the live one, so it is the one that reports the next loss.
  let live: FakeElement | null = null
  for (let i = 1; i <= 3; i++) {
    h.message({ type: "fluck-session-lost" }, live ? { origin: NEW_ENDPOINT, source: live.contentWindow! } : {})
    await h.settle()
    assertEquals(openCalls(h), i + 1)
    live = h.frames[h.frames.length - 1]
    h.messageFrom(live, { type: "fluck-hello" })
    await h.settle()
  }
  assertEquals(h.assigned, [])
  h.message({ type: "fluck-session-lost" }, { origin: NEW_ENDPOINT, source: live!.contentWindow! })
  await h.settle()
  assertEquals(openCalls(h), 4, "no fourth ticket")
  assertEquals(live!.getAttribute("src"), "about:blank", "closed like a sign-out")
  assertEquals(h.location.search, "")
})

Deno.test("session-lost attempts older than 2 minutes do not count", async () => {
  const h = await helloed()
  let live: FakeElement | null = null
  for (let i = 1; i <= 4; i++) {
    h.advance(61 * 1000)
    h.message({ type: "fluck-session-lost" }, live ? { origin: NEW_ENDPOINT, source: live.contentWindow! } : {})
    await h.settle()
    live = h.frames[h.frames.length - 1]
    h.messageFrom(live, { type: "fluck-hello" })
    await h.settle()
  }
  assertEquals(openCalls(h), 5, "every loss was allowed a swap")
  assert(h.body.classList.contains("viewing"))
})

Deno.test("a failed session-lost swap retries after 4 s, and counts toward the cap", async () => {
  const h = await helloed()
  h.state.openStatus = 500
  h.message({ type: "fluck-session-lost" })
  await h.settle()
  assertEquals(openCalls(h), 2)
  assertEquals(h.frames.length, 0)
  assertEquals(h.pending(4000), 1)
  h.fire(4000)
  await h.settle()
  h.fire(4000)
  await h.settle()
  assertEquals(openCalls(h), 4, "three attempts")
  h.fire(4000)
  await h.settle()
  assertEquals(h.get("fluckframe").getAttribute("src"), "about:blank", "out of attempts: signed-out behaviour")
})

Deno.test("closing the frame or switching Fluck mid-recovery removes the replacement and stops polling", async () => {
  const h = await swapping()
  h.message({ type: "fluck-switch" })
  assert(h.frames[0].removed)
  assertEquals(h.pending(3000) + h.pending(4500) + h.pending(GRACE_MS) + h.pending(SWAP_HELLO_MS), 0)
  await h.settle()
  assertEquals(h.assigned, [])
})

Deno.test("the first open still falls back to top-level navigation when no hello ever arrives", async () => {
  const h = await opened()
  h.fire(8000)
  assertEquals(h.assigned, [OPEN_URL])
})

// ---- Push bridge ----

const reqInit = (h: ReturnType<typeof harness>, url: string) => h.requests.filter((r) => r.url === url)

Deno.test("fluck-push query answers with the current state, to the frame's own origin only", async () => {
  const cases: [PushOptions, string][] = [
    [{ unsupported: true }, "unsupported"],
    [{ permission: "denied" }, "denied"],
    [{ permission: "default" }, "off"],
    [{ permission: "granted" }, "off"],
    [{ permission: "granted", subscribed: true }, "on"],
    [{ permission: "default", subscribed: true }, "off"],
    [{ userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) Safari", standalone: false, permission: "granted", subscribed: true }, "unsupported"],
    [{ userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) Safari", standalone: true, permission: "granted", subscribed: true }, "on"],
  ]
  for (const [push, want] of cases) {
    const h = await helloed({ push })
    h.message({ type: "fluck-push", action: "query" })
    await h.settle()
    assertEquals(h.pushStates(), [want], JSON.stringify(push))
    assertEquals(h.posted.map((p) => p.origin), [ENDPOINT], "targetOrigin is the frame's exact origin, never *")
  }
})

Deno.test("fluck-push from another origin or window, or with an unknown action, is ignored", async () => {
  const h = await helloed({ push: { permission: "granted" } })
  h.message({ type: "fluck-push", action: "query" }, { origin: "https://evil.example" })
  h.message({ type: "fluck-push", action: "query" }, { source: {} })
  h.message({ type: "fluck-push", action: "bogus" })
  h.message({ type: "fluck-push" })
  await h.settle()
  assertEquals(h.posted, [])
})

Deno.test("on, with permission already granted: subscribe and tell the server without asking", async () => {
  const h = await helloed({ push: { permission: "granted" } })
  h.message({ type: "fluck-push", action: "on" })
  await h.settle()
  assert(h.get("push-sheet").classList.contains("hidden"), "no sheet")
  assertEquals(h.notification.permission, "granted")
  assertEquals(h.pushLog.requested, 0, "no permission prompt")
  assertEquals(h.pushLog.registered, ["/sw.js"])
  const opts = h.pushLog.subscribeOpts as { userVisibleOnly: boolean; applicationServerKey: Uint8Array }
  assertEquals(opts.userVisibleOnly, true)
  assertEquals(opts.applicationServerKey.length, 65)
  assertEquals(opts.applicationServerKey[0], 4)
  const post = reqInit(h, "/api/push/subscribe")[0]
  assertEquals(new Headers(post.init!.headers).get("X-Fluck-Web-CSRF"), "c".repeat(64))
  assertEquals(JSON.parse(post.init!.body as string).subscription.endpoint, "https://fcm.googleapis.com/fcm/send/abc")
  assertEquals(h.pushStates(), ["busy", "on"])
})

Deno.test("on, permission not yet asked: a sheet; Turn on calls requestPermission synchronously, before any await", async () => {
  const h = await helloed({ push: { permission: "default" } })
  h.message({ type: "fluck-push", action: "on" })
  await h.settle()
  assert(!h.get("push-sheet").classList.contains("hidden"), "the sheet is shown")
  assertEquals(h.get("push-title").textContent, "Turn on notifications for Fluck?")
  assertEquals(h.get("push-no").textContent, "Not now")
  assert(!h.get("push-yes").classList.contains("hidden"))
  assertEquals(h.pushLog.requested, 0, "nothing is requested without a tap")
  assertEquals(h.pushStates(), ["busy"])
  h.click("push-yes")
  assertEquals(h.pushLog.requested, 1, "requested inside the click handler, before any await")
  assert(h.get("push-sheet").classList.contains("hidden"))
  await h.settle()
  assertEquals(h.pushLog.registered, ["/sw.js"])
  assertEquals(reqInit(h, "/api/push/subscribe").length, 1)
  assertEquals(h.pushStates(), ["busy", "on"])
})

Deno.test("the sheet names the Fluck being framed", async () => {
  const h = harness("?instance=i1", null, { push: { permission: "default" } })
  h.state.online = true
  await h.settle()
  h.message({ type: "fluck-push", action: "on" })
  assertEquals(h.get("push-title").textContent, "Turn on notifications for Fluck?")
})

Deno.test("Not now closes the sheet and reports the unchanged state; denying reports denied", async () => {
  const no = await helloed({ push: { permission: "default" } })
  no.message({ type: "fluck-push", action: "on" })
  no.click("push-no")
  await no.settle()
  assert(no.get("push-sheet").classList.contains("hidden"))
  assertEquals(no.pushLog.requested, 0)
  assertEquals(no.pushStates(), ["busy", "off"])
  no.message({ type: "fluck-push", action: "on" })
  assert(!no.get("push-sheet").classList.contains("hidden"), "can be asked again")

  const denied = await helloed({ push: { permission: "default", grant: "denied" } })
  denied.message({ type: "fluck-push", action: "on" })
  denied.click("push-yes")
  await denied.settle()
  assertEquals(denied.pushStates(), ["busy", "denied"])
  assertEquals(reqInit(denied, "/api/push/subscribe").length, 0)
  denied.message({ type: "fluck-push", action: "on" })
  await denied.settle()
  assertEquals(denied.pushStates().slice(-1), ["denied"], "denied stays denied without a sheet")
})

Deno.test("on in iOS Safari outside the Home Screen explains Add to Home Screen instead", async () => {
  const h = await helloed({ push: { userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) Safari", standalone: false, unsupported: true } })
  h.message({ type: "fluck-push", action: "on" })
  await h.settle()
  assert(!h.get("push-sheet").classList.contains("hidden"))
  assertEquals(h.get("push-title").textContent, "Add Fluck to your Home Screen")
  assert(h.get("push-body").textContent.includes("Add to Home Screen"))
  assert(h.get("push-yes").classList.contains("hidden"), "nothing to turn on yet")
  assertEquals(h.get("push-no").textContent, "Got it")
  assertEquals(h.pushStates(), ["unsupported"])
  assertEquals(h.pushLog.requested, 0)
})

Deno.test("on without push support, or when the server has no VAPID key, reports unsupported", async () => {
  const none = await helloed({ push: { unsupported: true } })
  none.message({ type: "fluck-push", action: "on" })
  await none.settle()
  assertEquals(none.pushStates(), ["unsupported"])
  assert(none.get("push-sheet").classList.contains("hidden"))

  const noKey = await helloed({ push: { permission: "granted", config: { status: 503, body: { error: "not_configured" } } } })
  noKey.message({ type: "fluck-push", action: "on" })
  await noKey.settle()
  assertEquals(noKey.pushStates(), ["busy", "unsupported"])
  assertEquals(noKey.pushLog.registered, [])
})

Deno.test("a server that refuses the subscription leaves nothing subscribed locally", async () => {
  const h = await helloed({ push: { permission: "granted" } })
  h.state.subscribeStatus = 400
  h.message({ type: "fluck-push", action: "on" })
  await h.settle()
  assertEquals(h.pushStates(), ["busy", "off"])
  assertEquals(h.pushLog.subscribed, false)
})

Deno.test("off unsubscribes locally and tells the server the endpoint; the busy state blocks a second request", async () => {
  const h = await helloed({ push: { permission: "granted", subscribed: true } })
  h.message({ type: "fluck-push", action: "off" })
  await h.settle()
  assertEquals(h.pushLog.subscribed, false)
  assertEquals(JSON.parse(reqInit(h, "/api/push/unsubscribe")[0].init!.body as string), { endpoint: "https://fcm.googleapis.com/fcm/send/abc" })
  assertEquals(h.pushStates(), ["off"])

  const p = await helloed({ push: { permission: "default" } })
  p.message({ type: "fluck-push", action: "on" })
  p.message({ type: "fluck-push", action: "on" })
  await p.settle()
  assertEquals(p.pushStates(), ["busy", "busy"], "the second ask while the sheet is open is busy")
})

Deno.test("the page script has no wildcard postMessage target and no notification ask outside a tap", () => {
  const html = fluckPage({ basePath: "", liveWindowSeconds: 90 }, "test")
  const script = /<script nonce="test">([\s\S]*?)<\/script>/.exec(html)![1]
  assert(!/postMessage\([^)]*"\*"/.test(script), "never targetOrigin *")
  assertEquals(script.match(/requestPermission\(/g)?.length, 1, "one place asks")
  const tap = /\$\("push-yes"\)\.addEventListener\("click", function \(\) \{\s*\/\/[^\n]*\n\s*var asked = window\.Notification\.requestPermission\(\);/
  assert(tap.test(script), "requestPermission is the first statement of the tap handler")
  assert(!/\p{Extended_Pictographic}/u.test(html), "no emoji")
  assert(!html.includes("—"), "no em-dash")
})
