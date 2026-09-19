/**
 * A plugin's README, fetched from its GitHub repository.
 *
 * THE TEXT IS NEVER RENDERED AS MARKUP. It is escaped and shown in a preformatted block, so what
 * arrives is displayed and nothing more. Rendering markdown would mean turning somebody else's
 * repository content into HTML on a page that also carries an admin control - and the CSP is the
 * second line of defence, not the first. A README is prose; prose survives being shown as prose.
 *
 * Only `github.com` URLs are attempted. `plugins.homepage_url` is publisher-supplied and reaches
 * here from the database, so it is a URL this function must not be willing to fetch in general:
 * without that restriction the page would be a server-side fetcher pointed by whoever published
 * the plugin, which is an SSRF probe with a nice interface.
 */

/** Bytes of README we will show. Enough for a real one, bounded so a page cannot be made enormous. */
const MAX_BYTES = 64 * 1024

/**
 * How many bytes are pulled off the wire at most. A character is at most four bytes in UTF-8, so
 * this always covers [MAX_BYTES] characters; anything past it would only be thrown away.
 */
const MAX_WIRE_BYTES = MAX_BYTES * 4

/**
 * Read the body up to [MAX_WIRE_BYTES] and stop. `response.text()` buffers whatever the origin
 * sends, and the truncation below only happens after the memory is spent. The reader is cancelled
 * at the cap, so at most one chunk over it is ever resident.
 */
async function readTextCapped(response: Response): Promise<{ text: string; cutShort: boolean }> {
  if (!response.body) return { text: "", cutShort: false }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let text = ""
  let total = 0
  let cutShort = false
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      total += value.byteLength
      if (total > MAX_WIRE_BYTES) {
        cutShort = true
        await reader.cancel().catch(() => {})
        break
      }
      text += decoder.decode(value, { stream: true })
    }
    text += decoder.decode()
  } finally {
    reader.releaseLock()
  }
  return { text, cutShort }
}

/** One attempt, short. The page renders without a README rather than waiting on GitHub. */
const TIMEOUT_MS = 4000

/**
 * Per-isolate memo of what GitHub said, successes and failures alike.
 *
 * The page it feeds is readable WITHOUT a session, so anyone can ask for it as often as they
 * like, and every render otherwise meant one outbound GitHub call. GITHUB_TOKEN is shared with
 * plugin-store's github service, so a loop over one popular plugin's page could spend the whole
 * hourly budget and take that service down with it - an availability bug reachable by a stranger.
 *
 * Failures are cached too, and that is the half worth stating: caching only successes leaves a
 * private or missing repository re-asked on every single request, which is the case an attacker
 * would pick. The TTL is short enough that a README edit shows up on its own.
 *
 * An isolate is ephemeral and there may be several, so this bounds the rate, it does not fix it.
 * It is the proportionate move for a page whose worst case is "the README is missing for a
 * while"; a shared cache would be a different piece of infrastructure.
 */
const CACHE_TTL_MS = 10 * 60 * 1000

/** Bounded so a long-lived isolate cannot be walked into unbounded memory, one repo at a time. */
const CACHE_MAX_ENTRIES = 200

const cache = new Map<string, { at: number; value: string | null }>()

function cached(key: string): { value: string | null } | null {
  const hit = cache.get(key)
  if (!hit) return null
  if (Date.now() - hit.at > CACHE_TTL_MS) {
    cache.delete(key)
    return null
  }
  return { value: hit.value }
}

function remember(key: string, value: string | null): string | null {
  // Oldest-first eviction: Map preserves insertion order, so the first key is the oldest write.
  if (cache.size >= CACHE_MAX_ENTRIES) {
    const oldest = cache.keys().next()
    if (!oldest.done) cache.delete(oldest.value)
  }
  cache.set(key, { at: Date.now(), value })
  return value
}

/** Test seam: the cache is module state, and a test that did not clear it would see the last one's. */
export function clearReadmeCacheForTests(): void {
  cache.clear()
}

/**
 * `owner/repo` from a GitHub URL, or null for anything else.
 *
 * Exact host match on `github.com` (and `www.github.com`), never `includes("github.com")`, which
 * `github.com.evil.test` satisfies. The rest of the path is ignored: publishers put
 * `/tree/main/...` and `#readme` on these.
 */
export function githubRepoFromUrl(url: string | null | undefined): { owner: string; repo: string } | null {
  if (!url) return null
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return null
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null
  const host = parsed.hostname.toLowerCase()
  if (host !== "github.com" && host !== "www.github.com") return null

  const parts = parsed.pathname.split("/").filter((p) => p.length > 0)
  if (parts.length < 2) return null

  const owner = parts[0]
  const repo = parts[1].replace(/\.git$/, "")
  // GitHub's own rules. Anything else cannot be a repository, and refusing here keeps the value
  // out of the request path entirely rather than trusting encodeURIComponent to save us.
  if (!/^[A-Za-z0-9._-]+$/.test(owner) || !/^[A-Za-z0-9._-]+$/.test(repo)) return null
  return { owner, repo }
}

/**
 * The README's text, or null when there is not one to show.
 *
 * Null covers every failure alike - not a repository URL, private repo, no README, rate limited,
 * timeout. The page treats them the same, because a plugin page that says "GitHub returned 403"
 * is telling the reader about our infrastructure rather than about the plugin.
 *
 * Authenticated when the `GITHUB_TOKEN` edge-function secret is set, matching plugin-store's
 * github service. Without it this is anonymous and shares one rate limit across every reader, so
 * the failure is expected rather than exceptional - hence the quiet null.
 */
export async function fetchReadme(homepageUrl: string | null | undefined): Promise<string | null> {
  const repo = githubRepoFromUrl(homepageUrl)
  if (!repo) return null

  const key = `${repo.owner}/${repo.repo}`
  const hit = cached(key)
  if (hit) return hit.value

  const headers: Record<string, string> = {
    // The raw media type: GitHub returns the file's bytes rather than a JSON envelope with
    // base64, which saves decoding something we are only going to escape anyway.
    Accept: "application/vnd.github.raw",
    "User-Agent": "boss-organisation-function",
  }
  const token = Deno.env.get("GITHUB_TOKEN")
  if (token) headers.Authorization = `Bearer ${token}`

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const response = await fetch(
      `https://api.github.com/repos/${repo.owner}/${repo.repo}/readme`,
      { headers, signal: controller.signal },
    )
    if (!response.ok) return remember(key, null)

    const { text, cutShort } = await readTextCapped(response)
    if (text.trim().length === 0) return remember(key, null)

    // Truncated by CHARACTERS after the fact rather than by a Range header: a byte range can cut a
    // multi-byte character in half, and the marker below is more honest than a mojibake tail.
    if (cutShort || text.length > MAX_BYTES) {
      return remember(key, text.slice(0, MAX_BYTES) + "\n\n[truncated]")
    }
    return remember(key, text)
  } catch {
    // Includes the abort. See the KDoc: every failure is the same absence to the reader.
    return remember(key, null)
  } finally {
    clearTimeout(timer)
  }
}
