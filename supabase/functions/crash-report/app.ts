/**
 * Crash-Report Edge Function
 *
 * Server-side proxy for BOSS desktop crash reports → GitHub Issues. The desktop
 * client used to create issues directly against the GitHub API, which required a
 * GitHub token on the user's machine (env var / local.properties / gh CLI) — end
 * users have none of those, so crash reporting failed with "GitHub authentication
 * required". This function holds the token server-side (GITHUB_TOKEN secret) and
 * the client posts here key-free instead.
 *
 * Routing: a crash attributed to a dynamically loaded plugin (the client sends
 * `pluginId` when a stack frame resolves to a PluginClassLoader) is filed in that
 * plugin's own repository, resolved from Supabase data:
 *   1. system_plugins.github_repo            (owner/repo, system plugins)
 *   2. plugins.homepage_url                  (store plugins published from GitHub)
 *   3. fallback: risa-labs-inc/BossConsole-Releases (also for host crashes)
 * If filing in the plugin repo fails (repo gone, issues disabled), the report
 * falls back to the default repo rather than being lost.
 *
 * Deduplication: issues are titled "[<12-hex signature>] Crash: <Type>". An open
 * issue with the same signature gets a comment instead of a duplicate issue. A
 * closed issue does NOT swallow new reports — a recurrence after a "fix" opens a
 * fresh issue.
 *
 * Abuse guards (verify_jwt=false, so the function gates itself):
 * - POST requires the `apikey` header (or Bearer) to equal SUPABASE_ANON_KEY.
 *   The key ships inside the desktop app so it is not a hard secret, but it
 *   stops drive-by POSTs from arbitrary web pages / curl one-liners.
 * - Per-IP sliding-window rate limit (in-memory, per isolate — best-effort).
 * - Deliberately NO CORS headers: the desktop client is a plain HTTP client
 *   (CORS does not apply to it), so cross-origin browser requests — the only
 *   thing CORS headers would enable — have no legitimate use here and
 *   preflights fail closed.
 *
 * Routes (deployed with verify_jwt=false, like the sibling latest-release):
 * - POST /            → file the crash report, returns { issueUrl, isNewIssue, repo }
 * - GET  /health      → health check
 */

import { OpenAPIHono } from "@hono/zod-openapi"

export const app = new OpenAPIHono().basePath("/crash-report")

const GITHUB_API = "https://api.github.com"
export const DEFAULT_REPO = "risa-labs-inc/BossConsole-Releases"

// 12-hex CrashSignature.generate() output; ranged so a future length change
// doesn't break older clients.
const SIGNATURE_RE = /^[a-f0-9]{8,64}$/
const PLUGIN_ID_RE = /^[A-Za-z0-9._-]{1,200}$/
const REPO_RE = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/
const MAX_TITLE_LENGTH = 300
const MAX_BODY_LENGTH = 256_000 // GitHub caps issue bodies at 65536 chars — we truncate below, this just bounds the request

// Sliding-window per-IP rate limit. In-memory, so it is per-isolate and resets
// on cold start — best-effort volume damping, not a hard quota. 20/hour is far
// above any honest crash loop (dedup turns those into comments) while capping
// what a single source can file.
const RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000
const RATE_LIMIT_MAX_PER_WINDOW = 20
const rateBuckets = new Map<string, number[]>()

/** Exposed for tests. */
export function resetRateLimiter() {
  rateBuckets.clear()
}

export function allowRequest(ip: string, now: number = Date.now()): boolean {
  // Bound memory under address-spraying: dropping all state is acceptable for
  // a best-effort limiter.
  if (rateBuckets.size > 10_000) rateBuckets.clear()
  const cutoff = now - RATE_LIMIT_WINDOW_MS
  const stamps = (rateBuckets.get(ip) ?? []).filter((t) => t > cutoff)
  if (stamps.length >= RATE_LIMIT_MAX_PER_WINDOW) {
    rateBuckets.set(ip, stamps)
    return false
  }
  stamps.push(now)
  rateBuckets.set(ip, stamps)
  return true
}

/**
 * Rate-limit key from headers the TRUSTED edge controls, never the client.
 * X-Forwarded-For is "client, proxy1, …" — the LEFTMOST entry is whatever the
 * caller typed and rotating it would fully evade the limiter (and spray unique
 * keys to bloat the bucket map); trusted proxies append on the RIGHT. So:
 * cf-connecting-ip (Cloudflare-set on the api.risaboss.com route) first, else
 * the rightmost XFF entry (appended by the Supabase edge for *.supabase.co
 * calls), else a shared "unknown" bucket.
 */
export function clientIp(cfConnectingIp?: string, xff?: string): string {
  if (cfConnectingIp?.trim()) return cfConnectingIp.trim()
  const parts = xff?.split(",") ?? []
  return parts[parts.length - 1]?.trim() || "unknown"
}

export interface CrashReportRequest {
  signature: string
  title: string
  body: string
  commentBody?: string
  pluginId?: string
  appVersion?: string
}

/** Extract "owner/repo" from a github.com URL (homepage_url of store plugins). */
export function parseGitHubRepo(url: string): string | null {
  const m = url.match(
    /^https?:\/\/(?:www\.)?github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+?)(?:\.git)?(?:[/#?].*)?$/,
  )
  return m ? `${m[1]}/${m[2]}` : null
}

/** GitHub hard-caps issue bodies at 65536 characters. */
export function truncateBody(body: string): string {
  const MAX = 65_000
  return body.length <= MAX ? body : body.slice(0, MAX) + "\n… (truncated)"
}

function pgHeaders(): { apikey: string; Authorization: string } {
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")
  if (!anonKey) throw new Error("SUPABASE_ANON_KEY not configured")
  return { apikey: anonKey, Authorization: `Bearer ${anonKey}` }
}

async function pgSelect(pathAndQuery: string): Promise<Record<string, unknown>[]> {
  const supabaseUrl = Deno.env.get("SUPABASE_URL")
  if (!supabaseUrl) throw new Error("SUPABASE_URL not configured")
  const response = await fetch(
    `${supabaseUrl.replace(/\/$/, "")}/rest/v1/${pathAndQuery}`,
    { headers: pgHeaders() },
  )
  if (!response.ok) {
    throw new Error(`PostgREST lookup failed (HTTP ${response.status})`)
  }
  return await response.json() as Record<string, unknown>[]
}

/**
 * Resolve the GitHub repo a plugin's crashes should be filed in.
 * Returns null when the plugin is unknown or has no usable GitHub link
 * (e.g. a local-only plugin) — the caller then uses DEFAULT_REPO.
 */
export async function resolvePluginRepo(pluginId: string): Promise<string | null> {
  const id = encodeURIComponent(pluginId)

  const system = await pgSelect(
    `system_plugins?plugin_id=eq.${id}&select=github_repo&limit=1`,
  )
  const systemRepo = system[0]?.github_repo
  if (typeof systemRepo === "string" && REPO_RE.test(systemRepo)) return systemRepo

  const store = await pgSelect(
    `plugins?plugin_id=eq.${id}&select=homepage_url&limit=1`,
  )
  const homepage = store[0]?.homepage_url
  if (typeof homepage === "string" && homepage) return parseGitHubRepo(homepage)

  return null
}

function githubHeaders(token: string, appVersion: string | undefined) {
  return {
    Accept: "application/vnd.github.v3+json",
    Authorization: `Bearer ${token}`,
    "User-Agent": `BOSS-CrashReport-Proxy/${appVersion ?? "unknown"}`,
    "Content-Type": "application/json",
  }
}

interface GitHubIssue {
  number: number
  title: string
  html_url: string
  state: string
  pull_request?: unknown
}

/**
 * Find an OPEN issue carrying this signature in its title, if any.
 *
 * Uses the issues LIST endpoint, not /search/issues: the search index lags new
 * issues by seconds-to-minutes, so a crash loop would file a duplicate per
 * crash. The list endpoint is read-after-write consistent. One page of the 100
 * newest open crash-report issues is plenty — dedup is best-effort.
 */
async function findOpenIssue(
  repo: string,
  signature: string,
  token: string,
  appVersion: string | undefined,
): Promise<GitHubIssue | null> {
  const response = await fetch(
    `${GITHUB_API}/repos/${repo}/issues?state=open&labels=crash-report&per_page=100&sort=created&direction=desc`,
    { headers: githubHeaders(token, appVersion) },
  )
  // Lookup failing (rate limit etc.) must not lose the report — fall through to
  // creating a possibly-duplicate issue, which is the lesser evil.
  if (!response.ok) {
    console.warn(`Issue lookup failed for ${repo}: HTTP ${response.status}`)
    return null
  }
  const issues = await response.json() as GitHubIssue[]
  return issues.find((issue) =>
    issue.pull_request === undefined && issue.title.includes(`[${signature}]`)
  ) ?? null
}

type FileResult =
  | { ok: true; issueUrl: string; isNewIssue: boolean }
  | { ok: false; status: number }

/** Create the issue (or comment on the open duplicate) in the given repo. */
async function fileInRepo(
  repo: string,
  req: CrashReportRequest,
  token: string,
): Promise<FileResult> {
  const existing = await findOpenIssue(repo, req.signature, token, req.appVersion)

  if (existing) {
    const response = await fetch(
      `${GITHUB_API}/repos/${repo}/issues/${existing.number}/comments`,
      {
        method: "POST",
        headers: githubHeaders(token, req.appVersion),
        body: JSON.stringify({ body: truncateBody(req.commentBody ?? req.body) }),
      },
    )
    if (!response.ok) return { ok: false, status: response.status }
    return { ok: true, issueUrl: existing.html_url, isNewIssue: false }
  }

  const labels = ["crash-report", "automated"]
  if (req.pluginId) {
    // GitHub caps labels at 50 chars. Reverse-DNS plugin ids discriminate at
    // the TAIL, so keep the end when truncating (the head would collide across
    // ai.rever.boss.plugin.* ids). The issue body carries the full id.
    const label = `plugin:${req.pluginId}`
    labels.push(label.length <= 50 ? label : `plugin:…${req.pluginId.slice(-(50 - 8))}`)
  }

  const response = await fetch(`${GITHUB_API}/repos/${repo}/issues`, {
    method: "POST",
    headers: githubHeaders(token, req.appVersion),
    body: JSON.stringify({
      title: req.title.slice(0, MAX_TITLE_LENGTH),
      body: truncateBody(req.body),
      labels,
    }),
  })
  if (!response.ok) {
    console.warn(`Issue creation failed for ${repo}: HTTP ${response.status}`)
    return { ok: false, status: response.status }
  }
  const issue = await response.json() as GitHubIssue
  return { ok: true, issueUrl: issue.html_url, isNewIssue: true }
}

function validate(body: unknown): CrashReportRequest | string {
  if (typeof body !== "object" || body === null) return "Request body must be a JSON object"
  const b = body as Record<string, unknown>

  if (typeof b.signature !== "string" || !SIGNATURE_RE.test(b.signature)) {
    return "Missing or malformed 'signature'"
  }
  if (typeof b.title !== "string" || !b.title.trim() || b.title.length > MAX_TITLE_LENGTH) {
    return "Missing or oversized 'title'"
  }
  if (typeof b.body !== "string" || !b.body.trim() || b.body.length > MAX_BODY_LENGTH) {
    return "Missing or oversized 'body'"
  }
  if (b.commentBody !== undefined &&
    (typeof b.commentBody !== "string" || b.commentBody.length > MAX_BODY_LENGTH)) {
    return "Malformed 'commentBody'"
  }
  if (b.pluginId !== undefined &&
    (typeof b.pluginId !== "string" || !PLUGIN_ID_RE.test(b.pluginId))) {
    return "Malformed 'pluginId'"
  }
  if (b.appVersion !== undefined &&
    (typeof b.appVersion !== "string" || !/^[A-Za-z0-9.+-]{1,64}$/.test(b.appVersion))) {
    return "Malformed 'appVersion'"
  }

  return {
    signature: b.signature,
    title: b.title,
    body: b.body,
    commentBody: b.commentBody as string | undefined,
    pluginId: b.pluginId as string | undefined,
    appVersion: b.appVersion as string | undefined,
  }
}

app.get("/health", (c) => {
  return c.json({ status: "healthy", timestamp: new Date().toISOString() }, 200)
})

app.post("/", async (c) => {
  // Gate on the project anon key: not a hard secret (it ships in the app), but
  // it blocks anonymous drive-by POSTs to an issue-creating endpoint.
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")
  if (!anonKey) {
    console.error("SUPABASE_ANON_KEY is not configured")
    return c.json({ error: "Crash reporting is not configured on the server" }, 503)
  }
  const provided = c.req.header("apikey") ||
    c.req.header("authorization")?.replace(/^Bearer\s+/i, "")
  if (provided !== anonKey) {
    return c.json({ error: "Missing or invalid apikey" }, 401)
  }

  const token = Deno.env.get("GITHUB_TOKEN")
  if (!token) {
    console.error("GITHUB_TOKEN secret is not configured")
    return c.json({ error: "Crash reporting is not configured on the server" }, 503)
  }

  let parsed: unknown
  try {
    parsed = await c.req.json()
  } catch {
    return c.json({ error: "Request body must be valid JSON" }, 400)
  }

  const request = validate(parsed)
  if (typeof request === "string") {
    return c.json({ error: request }, 400)
  }

  // Rate-limit only requests that would actually reach GitHub — rejected
  // (401/400/503) requests cost nothing here, so junk can't burn an honest
  // client's budget behind the same NAT.
  const ip = clientIp(c.req.header("cf-connecting-ip"), c.req.header("x-forwarded-for"))
  if (!allowRequest(ip)) {
    return c.json({ error: "Too many crash reports from this address; try again later" }, 429)
  }

  // Resolve target repo; any resolution failure degrades to the default repo —
  // losing a crash report is worse than filing it in the umbrella repo.
  let repo = DEFAULT_REPO
  if (request.pluginId) {
    try {
      repo = (await resolvePluginRepo(request.pluginId)) ?? DEFAULT_REPO
    } catch (e) {
      console.warn(`Plugin repo resolution failed for ${request.pluginId}: ${e}`)
    }
  }

  try {
    let result = await fileInRepo(repo, request, token)
    if (!result.ok && repo !== DEFAULT_REPO) {
      console.warn(`Filing in ${repo} failed (HTTP ${result.status}), retrying in ${DEFAULT_REPO}`)
      repo = DEFAULT_REPO
      result = await fileInRepo(repo, request, token)
    }
    if (!result.ok) {
      return c.json({ error: `GitHub rejected the report (HTTP ${result.status})` }, 502)
    }
    return c.json(
      { issueUrl: result.issueUrl, isNewIssue: result.isNewIssue, repo },
      result.isNewIssue ? 201 : 200,
    )
  } catch (e) {
    console.error(`Crash report submission failed: ${e}`)
    return c.json({ error: "Failed to reach GitHub" }, 502)
  }
})
