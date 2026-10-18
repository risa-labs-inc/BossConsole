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
  appendChild(child: FakeElement) { this.children.push(child) }
}

function harness(search: string, initialState: unknown = null, logoutStatus = 200) {
  const elements = new Map<string, FakeElement>()
  const get = (id: string) => {
    if (!elements.has(id)) elements.set(id, new FakeElement())
    return elements.get(id)!
  }
  const html = fluckPage({ basePath: "", liveWindowSeconds: 90 }, "test")
  get("cfg").textContent = /<script id="cfg" type="application\/json">([\s\S]*?)<\/script>/.exec(html)![1]
  const frameWindow = {}
  get("fluckframe").contentWindow = frameWindow
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
  const document = {
    title: "Fluck",
    body,
    documentElement,
    getElementById: get,
    createElement: () => new FakeElement(),
    createTextNode: (t: string) => Object.assign(new FakeElement(), { textContent: t }),
    querySelector: (sel: string) => sel === "main" ? main : null,
    addEventListener() {},
    visibilityState: "visible",
  }
  const winListeners: Record<string, Listener[]> = {}
  const storage = new Map<string, string>()
  const window = {
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
  const fetch = (url: string) => {
    calls.push(url)
    if (url === "/api/instances") {
      return Promise.resolve(Response.json({
        csrf: "c".repeat(64), email: "owner@example.com",
        instances: [{ instance_id: "i1", agent_name: "Fluck", label: "Mac", online: true, last_seen_at: new Date().toISOString() }],
      }))
    }
    if (url === "/api/open") return Promise.resolve(Response.json({ url: OPEN_URL }))
    if (url === "/api/logout") return Promise.resolve(Response.json({ ok: logoutStatus === 200 }, { status: logoutStatus }))
    return Promise.resolve(new Response("{}", { status: 404 }))
  }
  const script = /<script nonce="test">([\s\S]*?)<\/script>/.exec(html)![1]
  new Function("document", "window", "location", "history", "fetch", "setInterval", "clearInterval", "setTimeout", "clearTimeout", script)(
    document, window, location, history, fetch, () => 1, () => {}, fakeSetTimeout, fakeClearTimeout,
  )
  const message = (data: unknown, opts: { origin?: string; source?: object } = {}) => {
    for (const fn of winListeners.message ?? []) fn({ data, origin: opts.origin ?? ENDPOINT, source: opts.source ?? frameWindow })
  }
  const popstate = () => { for (const fn of winListeners.popstate ?? []) fn({}) }
  const settle = async () => { for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 0)) }
  return { get, rootVars, body, document, history, location, pushed, calls, frameWindow, message, popstate, settle, assigned, fire, pending }
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
  assert(!h.calls.includes("/api/logout"), "untrusted messages cannot sign out")
})

Deno.test("fluck-signed-out clears the portal session and shows sign-in without reopening", async () => {
  const h = await opened()
  h.document.title = "Chat"
  const before = h.calls.filter((c) => c === "/api/instances").length
  h.message({ type: "fluck-signed-out" })
  assertEquals(h.get("fluckframe").getAttribute("src"), "about:blank")
  assert(!h.body.classList.contains("viewing"))
  assertEquals(h.document.title, "Fluck")
  await h.settle()
  assertEquals(h.calls.filter((c) => c === "/api/logout").length, 1)
  assertEquals(h.calls.filter((c) => c === "/api/instances").length, before)
  assertEquals(h.calls.filter((c) => c === "/api/open").length, 1, "no auto-reopen")
  assert(!h.get("signin").classList.contains("hidden"), "sign-in is shown")
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

Deno.test("portal logout failure reports the error and does not claim sign-out", async () => {
  const h = harness("?instance=i1", null, 503)
  await h.settle()
  h.message({ type: "fluck-signed-out" })
  await h.settle()
  assert(h.get("signin").classList.contains("hidden"))
  assertEquals(h.get("notice").textContent, "Could not sign out. Please try again.")
  assertEquals(h.calls.filter((c) => c === "/api/open").length, 1)
})
