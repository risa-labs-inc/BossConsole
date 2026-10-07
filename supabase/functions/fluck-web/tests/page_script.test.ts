/**
 * The page's inline script, executed against a minimal fake DOM: ?instance= and ?reopen= highlight
 * and focus a row without auto-opening; ?reopen= adds its one line.
 */

import { assert, assertEquals } from "@std/assert"
import { app } from "../app.ts"

type Listener = (ev?: unknown) => unknown

class El {
  children: El[] = []
  className = ""
  textContent = ""
  disabled = false
  type = ""
  value = ""
  onclick: Listener | null = null
  listeners: Record<string, Listener[]> = {}
  private classes = new Set<string>()
  constructor(private dom: FakeDom) {}
  classList = {
    toggle: (c: string, on?: boolean) => {
      const want = on ?? !this.classes.has(c)
      if (want) this.classes.add(c)
      else this.classes.delete(c)
    },
    contains: (c: string) => this.classes.has(c),
  }
  set innerHTML(_: string) {
    this.children = []
  }
  appendChild(c: El): El {
    this.children.push(c)
    return c
  }
  addEventListener(t: string, f: Listener) {
    ;(this.listeners[t] ??= []).push(f)
  }
  focus() {
    this.dom.document.activeElement = this
  }
}

class FakeDom {
  els = new Map<string, El>()
  body = new El(this)
  document = {
    activeElement: null as unknown,
    visibilityState: "visible",
    getElementById: (id: string) => {
      if (!this.els.has(id)) this.els.set(id, new El(this))
      return this.els.get(id)!
    },
    createElement: () => new El(this),
    createTextNode: (t: string) => Object.assign(new El(this), { textContent: t }),
    addEventListener: () => {},
  }
  constructor() {
    this.document.activeElement = this.body
    // deno-lint-ignore no-explicit-any
    ;(this.document as any).body = this.body
  }
}

const ROWS = [
  { instance_id: "inst-a", label: "MacBook", agent_name: "Alpha", app_version: null, started_at: null, last_seen_at: new Date().toISOString(), online: true },
  { instance_id: "inst-b", label: "Mini", agent_name: "Bravo", app_version: null, started_at: null, last_seen_at: new Date().toISOString(), online: true },
]

async function runPage(search: string, rows = ROWS) {
  Deno.env.set("FLUCK_WEB_PUBLIC_BASE_URL", "https://fluck.risaboss.com")
  Deno.env.set("FLUCK_WEB_PUBLIC_BASE_PATH", "/portal")
  let html: string
  try {
    html = await (await app.request("/fluck-web/")).text()
  } finally {
    Deno.env.delete("FLUCK_WEB_PUBLIC_BASE_URL")
    Deno.env.delete("FLUCK_WEB_PUBLIC_BASE_PATH")
  }
  const script = /<script nonce="[^"]+">([\s\S]*?)<\/script>/.exec(html)![1]
  const cfg = /<script id="cfg" type="application\/json">([\s\S]*?)<\/script>/.exec(html)![1]

  const dom = new FakeDom()
  dom.document.getElementById("cfg").textContent = cfg
  const fetches: string[] = []
  const timeouts: number[] = []
  const location = { search, pathname: "/portal/", hash: "", assign: (u: string) => fetches.push(`navigate ${u}`) }
  const history = { replaceState: (_s: unknown, _t: string, url: string) => (location.search = url.includes("?") ? url.slice(url.indexOf("?")) : "") }
  const storage = new Map<string, string>()
  const window = {
    addEventListener: () => {},
    localStorage: { getItem: (k: string) => storage.get(k) ?? null, setItem: (k: string, v: string) => storage.set(k, v), removeItem: (k: string) => storage.delete(k) },
  }
  const fetch = (url: string) => {
    fetches.push(url)
    return Promise.resolve(Response.json({ instances: rows, email: "me@risalabs.ai", csrf: "c".repeat(64) }))
  }
  const fakeTimeout = (_f: unknown, ms: number) => (timeouts.push(ms), 1)
  new Function("document", "window", "location", "history", "fetch", "setTimeout", "clearTimeout", "setInterval", "clearInterval", script)(
    dom.document, window, location, history, fetch, fakeTimeout, () => {}, () => 1, () => {},
  )
  for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0))

  const items = dom.document.getElementById("instances").children
  const row = (id: string) => items[ROWS.findIndex((r) => r.instance_id === id)]
  return { dom, fetches, timeouts, location, row, notice: dom.document.getElementById("notice").textContent }
}

Deno.test("?instance=<id> highlights and focuses that row, with no 'address changed' line and no auto-open", async () => {
  const page = await runPage("?instance=inst-b")
  assertEquals(page.fetches, ["/portal/api/instances"], "no /api/open, no navigation")
  assertEquals(page.row("inst-b").className, "reopen")
  assertEquals(page.row("inst-a").className, "")
  assert(page.dom.document.activeElement === page.row("inst-b").children[1], "its Open button has focus")
  assertEquals(page.notice, "")
  assertEquals(page.location.search, "", "the parameter leaves the address bar")
})

Deno.test("?instance=<id> does not auto-open even when it is the only online Fluck", async () => {
  const page = await runPage("?instance=inst-a", [ROWS[0]])
  assertEquals(page.fetches, ["/portal/api/instances"])
  assertEquals(page.timeouts.filter((ms) => ms === 1500), [], "no open countdown")
  assertEquals(page.row("inst-a").className, "reopen")
})

Deno.test("?instance=<unknown> says it is not this account's Fluck", async () => {
  const page = await runPage("?instance=inst-z")
  assertEquals(page.notice, "That Fluck is not signed in with this account.")
})

Deno.test("?reopen=<id> highlights that row and says the address changed", async () => {
  const page = await runPage("?reopen=inst-a")
  assertEquals(page.fetches, ["/portal/api/instances"])
  assertEquals(page.row("inst-a").className, "reopen")
  assertEquals(page.notice, "Your BOSS's address changed; open it again.")
})

Deno.test("with no parameter a single online Fluck still gets the open countdown", async () => {
  const page = await runPage("", [ROWS[0]])
  assert(page.timeouts.includes(1500))
})
