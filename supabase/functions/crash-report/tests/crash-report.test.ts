/**
 * Tests for the crash-report Edge Function — repo routing (host vs system plugin
 * vs store plugin vs unknown), signature deduplication (open issue → comment,
 * closed issue → new issue), plugin-repo fallback to the default repo, and
 * payload validation. No network: the Hono app is driven in-process via
 * app.request() with globalThis.fetch stubbed to serve canned PostgREST rows and
 * a fake GitHub API.
 *
 * Run: cd supabase/functions/crash-report && deno test --allow-env --config deno.json
 */

import { assert, assertEquals } from "jsr:@std/assert"
// Import the app module (not index.ts, which calls Deno.serve) so no listener starts under test.
import {
  allowRequest,
  app,
  clientIp,
  DEFAULT_REPO,
  parseGitHubRepo,
  resetRateLimiter,
  truncateBody,
} from "../app.ts"

Deno.env.set("SUPABASE_URL", "https://supabase.test")
Deno.env.set("SUPABASE_ANON_KEY", "anon-test-key")
Deno.env.set("GITHUB_TOKEN", "ghs_test_token_0000000000000000000000000000")

const SIGNATURE = "a1b2c3d4e5f6"

interface RecordedRequest {
  url: string
  method: string
  body: unknown
}

/**
 * Stub fetch serving:
 * - PostgREST system_plugins / plugins lookups from the given row maps
 * - GitHub open-issues listing returning the open subset of `existingIssues`
 *   (emulating the real endpoint's state=open filter)
 * - GitHub issue / comment creation (records the request)
 */
function stubFetch(opts: {
  systemPlugins?: Record<string, string>
  storePlugins?: Record<string, string>
  existingIssues?: { number: number; title: string; html_url: string; state: string }[]
  failIssueCreationFor?: string[]
}) {
  const recorded: RecordedRequest[] = []
  const originalFetch = globalThis.fetch

  globalThis.fetch = (input: URL | RequestInfo, init?: RequestInit): Promise<Response> => {
    const url = input instanceof Request ? input.url : input.toString()
    const method = init?.method ?? "GET"
    const body = init?.body ? JSON.parse(init.body as string) : undefined
    recorded.push({ url, method, body })

    const json = (data: unknown, status = 200) =>
      Promise.resolve(
        new Response(JSON.stringify(data), {
          status,
          headers: { "Content-Type": "application/json" },
        }),
      )

    if (url.startsWith("https://supabase.test/rest/v1/system_plugins")) {
      const id = decodeURIComponent(url.match(/plugin_id=eq\.([^&]+)/)?.[1] ?? "")
      const repo = opts.systemPlugins?.[id]
      return json(repo ? [{ github_repo: repo }] : [])
    }
    if (url.startsWith("https://supabase.test/rest/v1/plugins")) {
      const id = decodeURIComponent(url.match(/plugin_id=eq\.([^&]+)/)?.[1] ?? "")
      const homepage = opts.storePlugins?.[id]
      return json(homepage ? [{ homepage_url: homepage }] : [])
    }
    const listMatch = url.match(/^https:\/\/api\.github\.com\/repos\/(.+)\/issues\?/)
    if (listMatch && method === "GET") {
      return json((opts.existingIssues ?? []).filter((i) => i.state === "open"))
    }
    const commentMatch = url.match(/^https:\/\/api\.github\.com\/repos\/(.+)\/issues\/\d+\/comments$/)
    if (commentMatch && method === "POST") {
      return json({ id: 1 }, 201)
    }
    const issueMatch = url.match(/^https:\/\/api\.github\.com\/repos\/(.+)\/issues$/)
    if (issueMatch && method === "POST") {
      const repo = issueMatch[1]
      if (opts.failIssueCreationFor?.includes(repo)) {
        return json({ message: "Not Found" }, 404)
      }
      return json({ number: 42, html_url: `https://github.com/${repo}/issues/42`, state: "open" }, 201)
    }
    return json({ message: `Unstubbed request: ${method} ${url}` }, 500)
  }

  return {
    recorded,
    restore: () => {
      globalThis.fetch = originalFetch
    },
  }
}

async function post(payload: unknown, headers: Record<string, string> = {}): Promise<Response> {
  resetRateLimiter()
  return await app.request("/crash-report", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      apikey: "anon-test-key",
      ...headers,
    },
    body: JSON.stringify(payload),
  })
}

const VALID_PAYLOAD = {
  signature: SIGNATURE,
  title: `[${SIGNATURE}] Crash: RuntimeException`,
  body: "## Exception\nsomething broke",
  commentBody: "## Additional Occurrence\nsomething broke again",
  appVersion: "9.2.42",
}

// (a) Unit helpers.

Deno.test("parseGitHubRepo extracts owner/repo from homepage variants", () => {
  assertEquals(parseGitHubRepo("https://github.com/risa-labs-inc/terminal-tab"), "risa-labs-inc/terminal-tab")
  assertEquals(parseGitHubRepo("https://github.com/risa-labs-inc/terminal-tab.git"), "risa-labs-inc/terminal-tab")
  assertEquals(parseGitHubRepo("https://github.com/risa-labs-inc/terminal-tab/releases"), "risa-labs-inc/terminal-tab")
  assertEquals(parseGitHubRepo("https://www.github.com/owner/repo#readme"), "owner/repo")
  assertEquals(parseGitHubRepo("https://example.com/not-github"), null)
  assertEquals(parseGitHubRepo(""), null)
})

Deno.test("truncateBody respects the GitHub 65536-char cap", () => {
  assertEquals(truncateBody("short"), "short")
  const long = "x".repeat(70_000)
  const truncated = truncateBody(long)
  assert(truncated.length < 65_536)
  assert(truncated.endsWith("… (truncated)"))
})

// (b) Validation.

Deno.test("rejects malformed signature", async () => {
  const stub = stubFetch({})
  try {
    const res = await post({ ...VALID_PAYLOAD, signature: "not hex!" })
    assertEquals(res.status, 400)
  } finally {
    stub.restore()
  }
})

Deno.test("rejects missing body", async () => {
  const stub = stubFetch({})
  try {
    const res = await post({ signature: SIGNATURE, title: "t" })
    assertEquals(res.status, 400)
  } finally {
    stub.restore()
  }
})

Deno.test("rejects malformed pluginId", async () => {
  const stub = stubFetch({})
  try {
    const res = await post({ ...VALID_PAYLOAD, pluginId: "bad plugin id!" })
    assertEquals(res.status, 400)
  } finally {
    stub.restore()
  }
})

// (c) Host crashes → default repo.

Deno.test("host crash (no pluginId) files a new issue in the default repo", async () => {
  const stub = stubFetch({})
  try {
    const res = await post(VALID_PAYLOAD)
    assertEquals(res.status, 201)
    const result = await res.json()
    assertEquals(result.repo, DEFAULT_REPO)
    assertEquals(result.isNewIssue, true)
    assertEquals(result.issueUrl, `https://github.com/${DEFAULT_REPO}/issues/42`)

    const creation = stub.recorded.find((r) => r.method === "POST" && r.url.endsWith("/issues"))
    assert(creation, "expected an issue-creation call")
    const body = creation.body as { title: string; labels: string[] }
    assertEquals(body.title, VALID_PAYLOAD.title)
    assertEquals(body.labels, ["crash-report", "automated"])
  } finally {
    stub.restore()
  }
})

// (d) Deduplication.

Deno.test("open issue with same signature gets a comment, not a duplicate", async () => {
  const stub = stubFetch({
    existingIssues: [{
      number: 7,
      title: `[${SIGNATURE}] Crash: RuntimeException`,
      html_url: `https://github.com/${DEFAULT_REPO}/issues/7`,
      state: "open",
    }],
  })
  try {
    const res = await post(VALID_PAYLOAD)
    assertEquals(res.status, 200)
    const result = await res.json()
    assertEquals(result.isNewIssue, false)
    assertEquals(result.issueUrl, `https://github.com/${DEFAULT_REPO}/issues/7`)

    const comment = stub.recorded.find((r) => r.url.includes("/issues/7/comments"))
    assert(comment, "expected a comment call")
    assertEquals((comment.body as { body: string }).body, VALID_PAYLOAD.commentBody)
  } finally {
    stub.restore()
  }
})

Deno.test("closed issue does not swallow the report — a new issue is created", async () => {
  const stub = stubFetch({
    existingIssues: [{
      number: 7,
      title: `[${SIGNATURE}] Crash: RuntimeException`,
      html_url: `https://github.com/${DEFAULT_REPO}/issues/7`,
      state: "closed",
    }],
  })
  try {
    const res = await post(VALID_PAYLOAD)
    assertEquals(res.status, 201)
    assertEquals((await res.json()).isNewIssue, true)
  } finally {
    stub.restore()
  }
})

Deno.test("open issue with a different signature does not swallow the report", async () => {
  const stub = stubFetch({
    existingIssues: [{
      number: 7,
      title: "[ffffffffffff] Crash: OtherException",
      html_url: `https://github.com/${DEFAULT_REPO}/issues/7`,
      state: "open",
    }],
  })
  try {
    const res = await post(VALID_PAYLOAD)
    assertEquals(res.status, 201)
    assertEquals((await res.json()).isNewIssue, true)
  } finally {
    stub.restore()
  }
})

// (e) Plugin routing.

Deno.test("system plugin crash files in its system_plugins.github_repo", async () => {
  const stub = stubFetch({
    systemPlugins: { "ai.rever.boss.plugin.dynamic.terminaltab": "risa-labs-inc/boss-plugin-terminal-tab" },
  })
  try {
    const res = await post({ ...VALID_PAYLOAD, pluginId: "ai.rever.boss.plugin.dynamic.terminaltab" })
    assertEquals(res.status, 201)
    const result = await res.json()
    assertEquals(result.repo, "risa-labs-inc/boss-plugin-terminal-tab")

    const creation = stub.recorded.find((r) => r.method === "POST" && r.url.endsWith("/issues"))
    assert(creation!.url.includes("risa-labs-inc/boss-plugin-terminal-tab"))
    const labels = (creation!.body as { labels: string[] }).labels
    assert(labels.some((l) => l.startsWith("plugin:")), "expected a plugin label")
  } finally {
    stub.restore()
  }
})

Deno.test("store plugin crash resolves repo from homepage_url", async () => {
  const stub = stubFetch({
    storePlugins: { "jupyter-notebook": "https://github.com/risa-labs-inc/jupyter-notebook" },
  })
  try {
    const res = await post({ ...VALID_PAYLOAD, pluginId: "jupyter-notebook" })
    assertEquals(res.status, 201)
    assertEquals((await res.json()).repo, "risa-labs-inc/jupyter-notebook")
  } finally {
    stub.restore()
  }
})

Deno.test("unknown plugin falls back to the default repo", async () => {
  const stub = stubFetch({})
  try {
    const res = await post({ ...VALID_PAYLOAD, pluginId: "some.local.only.plugin" })
    assertEquals(res.status, 201)
    assertEquals((await res.json()).repo, DEFAULT_REPO)
  } finally {
    stub.restore()
  }
})

Deno.test("plugin repo rejecting the issue falls back to the default repo", async () => {
  const stub = stubFetch({
    systemPlugins: { "ai.rever.boss.plugin.dynamic.editortab": "risa-labs-inc/boss-plugin-editor-tab" },
    failIssueCreationFor: ["risa-labs-inc/boss-plugin-editor-tab"],
  })
  try {
    const res = await post({ ...VALID_PAYLOAD, pluginId: "ai.rever.boss.plugin.dynamic.editortab" })
    assertEquals(res.status, 201)
    assertEquals((await res.json()).repo, DEFAULT_REPO)
  } finally {
    stub.restore()
  }
})

// (f) Abuse guards.

Deno.test("request without a valid apikey is rejected", async () => {
  const stub = stubFetch({})
  try {
    assertEquals((await post(VALID_PAYLOAD, { apikey: "" })).status, 401)
    assertEquals((await post(VALID_PAYLOAD, { apikey: "wrong-key" })).status, 401)
  } finally {
    stub.restore()
  }
})

Deno.test("Bearer form of the anon key is accepted", async () => {
  const stub = stubFetch({})
  try {
    const res = await post(VALID_PAYLOAD, { apikey: "", Authorization: "Bearer anon-test-key" })
    assertEquals(res.status, 201)
  } finally {
    stub.restore()
  }
})

Deno.test("per-IP rate limit trips after the window cap", async () => {
  const stub = stubFetch({})
  resetRateLimiter()
  try {
    let lastStatus = 0
    for (let i = 0; i < 21; i++) {
      const res = await app.request("/crash-report", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          apikey: "anon-test-key",
          "x-forwarded-for": "10.0.0.1",
        },
        body: JSON.stringify({ ...VALID_PAYLOAD, signature: `deadbeef${String(i).padStart(4, "0")}` }),
      })
      lastStatus = res.status
      if (i < 20) assertEquals(res.status, 201, `request ${i} should pass`)
    }
    assertEquals(lastStatus, 429)
  } finally {
    resetRateLimiter()
    stub.restore()
  }
})

Deno.test("malformed requests don't burn the rate-limit budget", async () => {
  const stub = stubFetch({})
  resetRateLimiter()
  try {
    for (let i = 0; i < 25; i++) {
      const res = await app.request("/crash-report", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          apikey: "anon-test-key",
          "x-forwarded-for": "10.0.0.2",
        },
        body: JSON.stringify({ junk: true }),
      })
      assertEquals(res.status, 400)
    }
    const ok = await app.request("/crash-report", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        apikey: "anon-test-key",
        "x-forwarded-for": "10.0.0.2",
      },
      body: JSON.stringify(VALID_PAYLOAD),
    })
    assertEquals(ok.status, 201)
  } finally {
    resetRateLimiter()
    stub.restore()
  }
})

Deno.test("long plugin ids truncate labels from the tail, not the head", async () => {
  const longId = "ai.rever.boss.plugin.dynamic.some.extremely.long.plugin.identifier"
  const stub = stubFetch({})
  try {
    const res = await post({ ...VALID_PAYLOAD, pluginId: longId })
    assertEquals(res.status, 201)
    const creation = stub.recorded.find((r) => r.method === "POST" && r.url.endsWith("/issues"))
    const label = (creation!.body as { labels: string[] }).labels.find((l) => l.startsWith("plugin:"))
    assert(label, "expected a plugin label")
    assert(label.length <= 50, `label too long: ${label.length}`)
    assert(label.endsWith("identifier"), `expected tail-truncation, got: ${label}`)
  } finally {
    stub.restore()
  }
})

Deno.test("clientIp ignores the client-controlled leftmost XFF entry", () => {
  // cf-connecting-ip (trusted edge) wins outright.
  assertEquals(clientIp("203.0.113.9", "spoofed, 198.51.100.7"), "203.0.113.9")
  // Otherwise the RIGHTMOST XFF entry — appended by the trusted proxy — is used.
  assertEquals(clientIp(undefined, "spoofed-a, spoofed-b, 198.51.100.7"), "198.51.100.7")
  assertEquals(clientIp(undefined, "198.51.100.7"), "198.51.100.7")
  // No usable header → shared bucket, not a per-request unique key.
  assertEquals(clientIp(undefined, undefined), "unknown")
  assertEquals(clientIp("", "  "), "unknown")
})

Deno.test("rotating the leftmost XFF entry does not evade the rate limit", async () => {
  const stub = stubFetch({})
  resetRateLimiter()
  try {
    let lastStatus = 0
    for (let i = 0; i < 21; i++) {
      const res = await app.request("/crash-report", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          apikey: "anon-test-key",
          // Attacker varies the left (client-supplied) part; trusted edge
          // appends the same real IP on the right.
          "x-forwarded-for": `evil-${i}, 10.0.0.4`,
        },
        body: JSON.stringify({ ...VALID_PAYLOAD, signature: `feedface${String(i).padStart(4, "0")}` }),
      })
      lastStatus = res.status
    }
    assertEquals(lastStatus, 429)
  } finally {
    resetRateLimiter()
    stub.restore()
  }
})

Deno.test("allowRequest window slides — old entries expire", () => {
  resetRateLimiter()
  const base = 1_000_000_000
  for (let i = 0; i < 20; i++) assert(allowRequest("ip-a", base + i))
  assertEquals(allowRequest("ip-a", base + 100), false)
  // One hour later the window has drained.
  assert(allowRequest("ip-a", base + 60 * 60 * 1000 + 200))
  resetRateLimiter()
})

// (g) Server configuration.

Deno.test("missing GITHUB_TOKEN yields 503, not a crash", async () => {
  const stub = stubFetch({})
  Deno.env.delete("GITHUB_TOKEN")
  try {
    const res = await post(VALID_PAYLOAD)
    assertEquals(res.status, 503)
  } finally {
    Deno.env.set("GITHUB_TOKEN", "ghs_test_token_0000000000000000000000000000")
    stub.restore()
  }
})

Deno.test("health endpoint responds", async () => {
  const res = await app.request("/crash-report/health")
  assertEquals(res.status, 200)
})
