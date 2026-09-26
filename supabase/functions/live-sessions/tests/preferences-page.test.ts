import { assertEquals } from "@std/assert"
import { livePage } from "../views/page.ts"

/** Execute the shipped page script with a minimal DOM; no browser/network is required. */
function harness() {
  const elements = new Map<string, any>()
  const posts: unknown[] = []
  function element(id: string): any {
    if (!elements.has(id)) elements.set(id, {
      textContent: id === "cfg" ? JSON.stringify({ basePath: "/live-sessions" }) : "",
      classList: { toggle() {}, add() {}, remove() {}, contains() { return false } },
      style: {}, addEventListener() {}, setAttribute() {},
      contentWindow: { postMessage(value: unknown) { posts.push(value) } },
    })
    return elements.get(id)
  }
  const requests: Array<(response: Response) => void> = []
  const html = livePage({ basePath: "/live-sessions", liveWindowSeconds: 90 }, "test")
  const script = html.match(/<script nonce="test">([\s\S]*?)<\/script>/)![1]
  const source = script.slice(0, script.indexOf("  // Boot:")) + `
    return {loadSessions, refreshTerminalPreferences, acceptTerminalPreferences, signOut,
      postTerminalPreferences, openSession,
      state: () => ({terminalPreferences, preferencesOwner, viewing}),
      view: () => { viewing = {url: "https://terminal.example/", label: "test"}; }
    };
  })();`
  const run = new Function("document", "window", "history", "location", "fetch", "setInterval", "clearInterval", "setTimeout", "clearTimeout", "return " + source.trim())
  const page = run(
    {getElementById: element, querySelector: () => element("main"), addEventListener() {}, body: element("body"), visibilityState: "visible"},
    {addEventListener() {}, visualViewport: null}, {pushState() {}}, {pathname: "/", search: ""},
    () => new Promise<Response>(resolve => requests.push(resolve)), () => 1, () => {}, () => 1, () => {},
  )
  return {page, requests, posts}
}
const prefs = (owner: string, revision: number, mode = "preview") => ({
  terminal_preferences_owner: owner,
  terminal_preferences: {unfocused_mode: mode, unfocused_fps: 7, revision},
})
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), {status})

Deno.test("account preference cache retains only its owner's validated newest revision", () => {
  const {page} = harness()
  page.acceptTerminalPreferences(prefs("alice", 3))
  page.acceptTerminalPreferences(prefs("alice", 2, "batch"))
  assertEquals(page.state().terminalPreferences.revision, 3)
  page.acceptTerminalPreferences({terminal_preferences_owner: "alice"})
  assertEquals(page.state().terminalPreferences.revision, 3)
  page.acceptTerminalPreferences({terminal_preferences_owner: "bob"})
  assertEquals(page.state().terminalPreferences, {unfocused_mode: "batch", unfocused_fps: 4, revision: 0})
  page.acceptTerminalPreferences(prefs("bob", 1, "invalid"))
  assertEquals(page.state().terminalPreferences.revision, 0)
  page.acceptTerminalPreferences({terminal_preferences: prefs("alice", 9).terminal_preferences})
  assertEquals(page.state().terminalPreferences.revision, 0)
})

Deno.test("overlapping viewer refresh ignores the older account response", async () => {
  const {page, requests, posts} = harness()
  page.view()
  const first = page.refreshTerminalPreferences()
  const second = page.refreshTerminalPreferences()
  requests[1](reply(prefs("bob", 2)))
  await second
  requests[0](reply(prefs("alice", 1)))
  await first
  assertEquals(page.state().preferencesOwner, "bob")
  assertEquals(posts.length, 1)
})

Deno.test("signout closes the viewer and invalidates an in-flight preferences refresh", async () => {
  const {page, requests, posts} = harness()
  page.acceptTerminalPreferences(prefs("alice", 1))
  page.view()
  const pending = page.refreshTerminalPreferences()
  const logout = page.signOut()
  requests[0](reply(prefs("alice", 2)))
  await pending
  requests[1](reply({}))
  await logout
  assertEquals(page.state(), {terminalPreferences: null, preferencesOwner: null, viewing: null})
  assertEquals(posts.length, 0)
})
