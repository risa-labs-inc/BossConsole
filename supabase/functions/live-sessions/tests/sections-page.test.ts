import { assertEquals, assertStringIncludes } from "@std/assert"
import { livePage } from "../views/page.ts"

Deno.test("shipped session list separates terminal and application entries with independent empty states", () => {
  interface Element {
    innerHTML: string; textContent: string; children: Element[]; attributes: Record<string, string>
    classList: { toggle(): void }; addEventListener(): void; setAttribute(): void
    querySelector(): { setAttribute(name: string, value: string): void; addEventListener(): void }
    appendChild(child: Element): void
  }
  const elements = new Map<string, Element>()
  function element(): Element {
    const attributes: Record<string, string> = {}
    const anchor = { setAttribute(name: string, value: string) { attributes[name] = value }, addEventListener() {} }
    return {
      innerHTML: "", textContent: "", children: [], attributes,
      classList: { toggle() {} }, addEventListener() {}, setAttribute() {},
      querySelector: () => anchor,
      appendChild(child: Element) { this.children.push(child) },
    }
  }
  const get = (id: string) => {
    if (!elements.has(id)) elements.set(id, element())
    return elements.get(id)!
  }
  get("cfg").textContent = JSON.stringify({ basePath: "/live-sessions" })
  const html = livePage({ basePath: "/live-sessions", liveWindowSeconds: 90 }, "test")
  assertStringIncludes(html, "<title>BOSS Live Sessions</title>")
  assertStringIncludes(html, 'id="bossterm-heading">BossTerm live sessions')
  assertStringIncludes(html, 'id="bossconsole-heading">BossConsole live sessions')
  const script = html.match(/<script nonce="test">([\s\S]*?)<\/script>/)![1]
  const source = script.slice(0, script.indexOf("  // Boot:")) + "cancelledAutoOpen = true; return render; })();"
  const render = new Function("document", "window", "location", "setInterval", "clearInterval", "return " + source.trim())(
    { getElementById: get, createElement: element, addEventListener() {} }, { addEventListener() {} }, { search: "" }, () => 1, () => {},
  )
  const terminal = { device_name: "Terminal host", control_url: "https://terminal.example/", scope: "TAB", secure: true }
  const app = { name: "Application host", session_id: "12345678-1234-1234-1234-123456789012" }
  render([terminal], "owner@example.com", [app])
  assertEquals(get("sessions").children.length, 1)
  assertEquals(get("app-sessions").children.length, 1)
  assertStringIncludes(get("sessions").children[0].innerHTML, "Terminal host")
  assertStringIncludes(get("app-sessions").children[0].innerHTML, "Application host")
  assertEquals(get("app-sessions").children[0].attributes.href, "/live-sessions/app-viewer/?session=" + app.session_id)
  render([], "owner@example.com", [])
  assertStringIncludes(get("sessions").innerHTML, "No live terminal sessions")
  assertStringIncludes(get("app-sessions").innerHTML, "No live BossConsole sessions")
})
