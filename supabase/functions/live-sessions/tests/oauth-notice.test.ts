import { assertEquals } from "@std/assert"
import { livePage } from "../views/page.ts"

/**
 * Boots the shipped page script, whole, against a minimal DOM, the way a browser lands on
 * `/auth?oauth_error=<code>` after a Google or Apple sign-in did not complete.
 */
async function land(search: string, hash: string) {
  const elements = new Map<string, { textContent: string; className: string }>()
  function element(id: string) {
    let value = elements.get(id)
    if (!value) {
      value = {
        textContent: id === "cfg" ? JSON.stringify({ basePath: "/live-sessions" }) : "",
        className: "",
        classList: { toggle() {}, add() {}, remove() {}, contains() { return false } },
        style: {}, addEventListener() {}, setAttribute() {},
      } as unknown as { textContent: string; className: string }
      elements.set(id, value)
    }
    return value
  }
  const location = { pathname: "/live-sessions/auth", search, hash }
  const history = {
    pushState() {},
    replaceState(_state: unknown, _title: string, url: string) {
      const parsed = new URL(url, "https://cli.example")
      location.pathname = parsed.pathname
      location.search = parsed.search
      location.hash = parsed.hash
    },
  }
  const fetched: string[] = []
  const fetch = (url: string) => {
    fetched.push(url)
    return Promise.resolve(new Response("{}", { status: 401 }))
  }
  const html = livePage({ basePath: "/live-sessions", liveWindowSeconds: 90 }, "test")
  const script = html.match(/<script nonce="test">([\s\S]*?)<\/script>/)![1]
  const run = new Function(
    "document", "window", "history", "location", "fetch", "setInterval", "clearInterval", "setTimeout", "clearTimeout",
    script,
  )
  run(
    { getElementById: element, querySelector: () => element("main"), addEventListener() {}, body: element("body"), visibilityState: "visible" },
    { addEventListener() {}, visualViewport: null }, history, location, fetch, () => 1, () => {}, () => 1, () => {},
  )
  // Let the boot chain (harvestFragment, then loadSessions) settle.
  for (let i = 0; i < 20; i++) await Promise.resolve()
  await new Promise((resolve) => setTimeout(resolve, 0))
  return { notice: element("notice"), location, fetched }
}

Deno.test("the oauth_error notice survives the boot's fragment harvest and session load", async () => {
  // GoTrue repeats the error in the fragment, and a browser carries the fragment across the
  // server's redirect to ?oauth_error=, so the page lands with both.
  const { notice, location, fetched } = await land(
    "?oauth_error=cancelled",
    "#error=access_denied&error_code=user_cancelled&error_description=Planted+text&sb",
  )
  assertEquals(notice.textContent, "Sign-in was cancelled.")
  assertEquals(location.search, "")
  assertEquals(location.hash, "")
  assertEquals(fetched.some((url) => url.includes("/api/sessions")), true)
})

Deno.test("an unknown oauth_error code shows the generic failure, never the code", async () => {
  const { notice } = await land("?oauth_error=%3Cb%3Ehi", "")
  assertEquals(notice.textContent, "Sign-in failed. Please try again.")
})
