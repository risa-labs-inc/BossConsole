/**
 * GitHub Service for Plugin Publishing
 *
 * Handles fetching releases and downloading JAR files from GitHub repositories.
 */

import { PluginManifest } from "../types/plugin.ts"
import { createHash } from "node:crypto"

/**
 * Hosts permitted to serve externally-hosted plugin JARs.
 *
 * `github.com` is where `browser_download_url` points; GitHub 302-redirects
 * to `objects.githubusercontent.com` (legacy CDN) or
 * `release-assets.githubusercontent.com` (current CDN). Redirects are
 * followed by `fetch` transparently, but we still allow those hosts for
 * any future code path that stores a resolved URL directly.
 */
const ALLOWED_EXTERNAL_JAR_HOSTS = new Set([
  "github.com",
  "objects.githubusercontent.com",
  "release-assets.githubusercontent.com",
])

/**
 * Returns true if [url] is an HTTPS URL hosted on a GitHub release asset host.
 * Used as an allowlist for both publish and download paths so a corrupted or
 * malicious `jar_path` cannot redirect clients to an arbitrary HTTPS origin.
 */
export function isAllowedExternalJarUrl(url: string): boolean {
  try {
    const parsed = new URL(url)
    return (
      parsed.protocol === "https:" &&
      ALLOWED_EXTERNAL_JAR_HOSTS.has(parsed.hostname)
    )
  } catch {
    return false
  }
}

/**
 * Headers for calls to `api.github.com`.
 *
 * When a `GITHUB_TOKEN` secret is configured, every request is authenticated.
 * This is what lets the store read releases and download assets from **private**
 * plugin repos: GitHub returns 404 for the releases/asset endpoints of a private
 * repo to unauthenticated callers, which surfaces as the misleading
 * "No releases found" error. Public repos work with or without the token, so an
 * unset token preserves the original unauthenticated behavior.
 *
 * NOTE: `GITHUB_TOKEN` here is the **Supabase edge-function secret**
 * (`supabase secrets set GITHUB_TOKEN=…`), which is a different thing from the
 * client-side `GITHUB_TOKEN` in `local.properties` that the desktop app reads.
 * The token needs `contents:read` on the private plugin repos it must fetch.
 *
 * `Bearer` is the correct prefix for fine-grained PATs, GitHub App tokens, and
 * (now) classic PATs. `X-GitHub-Api-Version` pins the REST API version.
 */
function githubApiHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = {
    "User-Agent": "BOSS-Plugin-Store/1.0",
    "X-GitHub-Api-Version": "2022-11-28",
    ...extra,
  }
  const token = Deno.env.get("GITHUB_TOKEN")
  if (token) {
    headers["Authorization"] = `Bearer ${token}`
  }
  return headers
}

/**
 * Upper bound on the byte count we'll hash for a single publish request.
 * Chosen at 500 MB: well above any realistic plugin JAR (including the
 * ~100 MB microkernel runtime) and below what the edge function's CPU-time
 * and network budgets can handle. Guards against a publisher submitting a
 * URL that points to a pathologically large artifact (or a redirect target
 * that starts streaming indefinitely).
 */
const MAX_HASHABLE_BYTES = 500 * 1024 * 1024 // 500 MB

/**
 * Size (in bytes) at or above which we refuse to buffer a JAR in memory and
 * direct the caller to the streaming /github/metadata path instead. Supabase
 * edge functions have ~256 MB of RAM, and a JAR near this threshold plus the
 * upload buffer plus the runtime itself starts to OOM unpredictably.
 *
 * Declared here (near the other byte caps) rather than beside JarTooLargeError
 * so the in-memory download guards above can reference it without a forward
 * jump to the bottom of the file.
 */
export const LARGE_JAR_THRESHOLD = 50 * 1024 * 1024 // 50 MB

/**
 * Stream-compute the SHA-256 of a remote JAR without buffering it in memory.
 *
 * Used by /github/metadata to derive the authoritative hash server-side
 * instead of trusting the publisher's submitted value. Streaming keeps peak
 * memory bounded (one fetch chunk at a time — typically 16–64 KB), so this
 * works for JARs that exceed the edge function's ArrayBuffer limits.
 *
 * Throws if the stream exceeds {@link MAX_HASHABLE_BYTES}; the reader is
 * cancelled before the full body transfers so we don't burn CPU on a
 * runaway response.
 *
 * @param downloadUrl URL of the JAR (must be on an allowed host)
 * @returns The hex-encoded SHA-256 and the number of bytes streamed
 */
export async function computeRemoteSha256(
  downloadUrl: string
): Promise<{ sha256: string; totalBytes: number }> {
  const response = await fetch(downloadUrl, {
    headers: { "User-Agent": "BOSS-Plugin-Store/1.0" },
  })
  if (!response.ok) {
    throw new Error(
      `Failed to fetch JAR for hashing: ${response.status} ${response.statusText}`
    )
  }
  if (!response.body) {
    throw new Error("Remote JAR response has no body")
  }

  const hash = createHash("sha256")
  const reader = response.body.getReader()
  let totalBytes = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      if (value && value.length > 0) {
        totalBytes += value.length
        if (totalBytes > MAX_HASHABLE_BYTES) {
          // Cancel so the remainder of the response isn't transferred.
          try { await reader.cancel() } catch { /* ignore */ }
          throw new Error(
            `Remote JAR exceeds ${MAX_HASHABLE_BYTES}-byte limit at ${totalBytes} bytes; refusing to hash`
          )
        }
        hash.update(value)
      }
    }
  } finally {
    reader.releaseLock()
  }

  return { sha256: hash.digest("hex"), totalBytes }
}

/**
 * GitHub release asset information
 */
interface GitHubAsset {
  name: string
  /** Public CDN URL; works unauthenticated only for public repos. */
  browser_download_url: string
  /**
   * Asset API URL (`.../releases/assets/{id}`). Requesting this with
   * `Accept: application/octet-stream` + auth is the supported way to download
   * an asset from a **private** repo — GitHub 302-redirects to a signed CDN URL.
   */
  url: string
  size: number
  content_type: string
}

/**
 * GitHub release information
 */
interface GitHubRelease {
  tag_name: string
  name: string
  assets: GitHubAsset[]
  published_at: string
  body: string
}

/**
 * Result of fetching a plugin from GitHub
 */
export interface GitHubPluginResult {
  manifest: PluginManifest
  jarData: ArrayBuffer
  jarSize: number
  sha256: string
  releaseNotes: string
  version: string
}

/**
 * Parse GitHub URL to extract owner and repo
 *
 * Supports formats:
 * - https://github.com/owner/repo
 * - https://github.com/owner/repo/releases
 * - https://github.com/owner/repo/releases/tag/v1.0.0
 * - github.com/owner/repo
 */
export function parseGitHubUrl(url: string): { owner: string; repo: string; tag?: string } | null {
  // Normalize URL
  let normalized = url.trim()
  if (!normalized.startsWith("http")) {
    normalized = `https://${normalized}`
  }

  try {
    const parsed = new URL(normalized)
    if (parsed.hostname !== "github.com") {
      return null
    }

    const parts = parsed.pathname.split("/").filter((p) => p.length > 0)
    if (parts.length < 2) {
      return null
    }

    const owner = parts[0]
    const repo = parts[1]

    // Check for specific tag
    if (parts.length >= 4 && parts[2] === "releases" && parts[3] === "tag") {
      return { owner, repo, tag: parts[4] }
    }

    return { owner, repo }
  } catch {
    return null
  }
}

/**
 * Fetch the latest release from a GitHub repository
 */
export async function fetchLatestRelease(
  owner: string,
  repo: string,
  tag?: string
): Promise<GitHubRelease> {
  const apiUrl = tag
    ? `https://api.github.com/repos/${owner}/${repo}/releases/tags/${tag}`
    : `https://api.github.com/repos/${owner}/${repo}/releases/latest`

  const response = await fetch(apiUrl, {
    headers: githubApiHeaders({ Accept: "application/vnd.github.v3+json" }),
  })

  if (!response.ok) {
    if (response.status === 404) {
      throw new Error(
        tag
          ? `Release tag '${tag}' not found for ${owner}/${repo}`
          : `No releases found for ${owner}/${repo}. Make sure the repository has at least one release.`
      )
    }
    // Surface GitHub's own message (and SSO hint header) — a bare "403 Forbidden"
    // hides whether the cause is a missing token scope, org SSO authorization, or
    // rate limiting. Body is small (a JSON {message,...}); truncate defensively.
    let detail = ""
    try {
      const body = (await response.text()).slice(0, 300)
      const sso = response.headers.get("x-github-sso")
      detail = ` — ${body}${sso ? ` [x-github-sso: ${sso}]` : ""}`
    } catch { /* ignore body read errors */ }
    // Also log server-side so the failure is visible in edge-function logs, not
    // only in the publisher's HTTP response.
    console.error(`fetchLatestRelease ${owner}/${repo}${tag ? `@${tag}` : ""} failed: ${response.status} ${response.statusText}${detail}`)
    throw new Error(`GitHub API error: ${response.status} ${response.statusText}${detail}`)
  }

  return await response.json()
}

/**
 * Whether the repo is private, per GitHub's repo API.
 *
 * Used by the metadata-only publish path to reject private repos up front: that
 * path stores the GitHub `browser_download_url` as the client-facing `jar_path`,
 * and a token-less client cannot download a private asset — so a "successful"
 * private metadata publish would yield an uninstallable plugin. Failing fast
 * turns that into a clear error instead of a later 404 nobody can act on.
 *
 * Throws on API errors (surfaced with GitHub's own message) rather than
 * guessing; the caller decides how to handle an indeterminate result.
 */
export async function fetchRepoIsPrivate(owner: string, repo: string): Promise<boolean> {
  const response = await fetch(`https://api.github.com/repos/${owner}/${repo}`, {
    headers: githubApiHeaders({ Accept: "application/vnd.github.v3+json" }),
  })
  if (!response.ok) {
    // A 404 on the repo endpoint means it's either genuinely absent OR private
    // and invisible to us (no GITHUB_TOKEN, or a token lacking access). Either
    // way this repo can't be published on the metadata path, so treat it as an
    // inaccessible/private repo with a message that names the likely fix rather
    // than reading as a transient GitHub error.
    if (response.status === 404) {
      throw new Error(
        `${owner}/${repo} was not found via the GitHub API. If it is private, the store's ` +
        `GITHUB_TOKEN secret must be set and granted contents:read on it; otherwise check the URL.`
      )
    }
    let detail = ""
    try { detail = ` — ${(await response.text()).slice(0, 200)}` } catch { /* ignore */ }
    throw new Error(`GitHub repo lookup failed for ${owner}/${repo}: ${response.status} ${response.statusText}${detail}`)
  }
  const info = await response.json()
  return info.private === true
}

/**
 * Find the plugin JAR asset in a release
 */
export function findJarAsset(release: GitHubRelease): GitHubAsset | null {
  // Look for .jar files, prefer ones with "plugin" in the name
  const jarAssets = release.assets.filter((a) => a.name.endsWith(".jar"))

  if (jarAssets.length === 0) {
    return null
  }

  // Prefer assets with "plugin" in name
  const pluginJar = jarAssets.find(
    (a) => a.name.toLowerCase().includes("plugin") || a.name.toLowerCase().includes("boss")
  )

  return pluginJar || jarAssets[0]
}

/**
 * Download a JAR file from GitHub's public CDN URL (unauthenticated).
 * Works for public-repo assets only.
 */
export async function downloadJar(downloadUrl: string): Promise<ArrayBuffer> {
  const response = await fetch(downloadUrl, {
    headers: {
      "User-Agent": "BOSS-Plugin-Store/1.0",
    },
  })

  if (!response.ok) {
    throw new Error(`Failed to download JAR: ${response.status} ${response.statusText}`)
  }

  return await response.arrayBuffer()
}

/**
 * Download a release asset, transparently handling **private** repos.
 *
 * Without a `GITHUB_TOKEN`, falls back to the public `browser_download_url`
 * (unchanged behavior for public plugins). With a token, hits the asset API URL
 * with `Accept: application/octet-stream`; GitHub 302-redirects to a signed CDN
 * URL. We resolve that redirect **manually** and fetch the CDN URL WITHOUT the
 * `Authorization` header — the signed URL carries its own credentials, and
 * forwarding a second auth mechanism to the CDN host is rejected. The redirect
 * target is re-checked against the JAR host allowlist before we follow it.
 */
export async function downloadReleaseAsset(asset: GitHubAsset): Promise<ArrayBuffer> {
  const token = Deno.env.get("GITHUB_TOKEN")
  if (!token) {
    return await downloadJar(asset.browser_download_url)
  }

  // RUNTIME NOTE: `redirect: "manual"` is read here for the real 3xx status and
  // its `Location` header. Per the Fetch spec a manual redirect yields an
  // *opaque-redirect* response (status 0, headers hidden); Deno intentionally
  // deviates and exposes the actual status + Location, which is what this relies
  // on. This runs on the Supabase edge (Deno) runtime — do NOT "simplify" this
  // into a spec-compliant assumption that Location is unreadable.
  const resp = await fetch(asset.url, {
    headers: githubApiHeaders({ Accept: "application/octet-stream" }),
    redirect: "manual",
  })

  // 301/302/307/308 → follow the signed CDN URL WITHOUT auth.
  if (resp.status === 301 || resp.status === 302 || resp.status === 307 || resp.status === 308) {
    const location = resp.headers.get("location")
    if (!location) {
      throw new Error("Asset download redirect is missing a Location header")
    }
    if (!isAllowedExternalJarUrl(location)) {
      throw new Error(`Asset redirect points to a disallowed host: ${location}`)
    }
    const cdn = await fetch(location, {
      headers: { "User-Agent": "BOSS-Plugin-Store/1.0" },
    })
    if (!cdn.ok) {
      throw new Error(`Failed to download asset from CDN: ${cdn.status} ${cdn.statusText}`)
    }
    return await readBoundedArrayBuffer(cdn, "Release asset")
  }

  // Some deployments serve the bytes directly (200) with no redirect.
  if (!resp.ok) {
    throw new Error(`Failed to download asset: ${resp.status} ${resp.statusText}`)
  }
  return await readBoundedArrayBuffer(resp, "Release asset")
}

/**
 * Buffer a response into memory with a hard {@link LARGE_JAR_THRESHOLD} cap.
 *
 * `downloadReleaseAsset` is only ever called for JARs the caller pre-checked as
 * < 50 MB, but that check trusts the release API's self-reported `size`. This
 * bounds the actual buffer against the declared `Content-Length` (rejecting
 * before the read when present) and against the real byte count (after), so a
 * lying size can't blow the edge function's memory budget.
 */
async function readBoundedArrayBuffer(resp: Response, label: string): Promise<ArrayBuffer> {
  // `>=` mirrors fetchPluginFromGitHub's own size gate so the "too large"
  // boundary is identical on both the pre-download check and this buffer guard.
  const declared = Number(resp.headers.get("content-length") || "0")
  if (Number.isFinite(declared) && declared >= LARGE_JAR_THRESHOLD) {
    throw new Error(`${label} declares ${declared} bytes, at/over the ${LARGE_JAR_THRESHOLD}-byte cap`)
  }
  const buf = await resp.arrayBuffer()
  if (buf.byteLength >= LARGE_JAR_THRESHOLD) {
    throw new Error(`${label} is ${buf.byteLength} bytes, at/over the ${LARGE_JAR_THRESHOLD}-byte cap`)
  }
  return buf
}

/**
 * Download a byte range from a URL.
 * Returns the bytes and the total file size (from Content-Range header).
 */
async function downloadRange(
  url: string,
  start: number,
  end: number
): Promise<{ data: Uint8Array; totalSize: number }> {
  const response = await fetch(url, {
    headers: {
      "User-Agent": "BOSS-Plugin-Store/1.0",
      Range: `bytes=${start}-${end}`,
    },
  })

  if (response.status !== 206 && response.status !== 200) {
    throw new Error(`Range request failed: ${response.status}`)
  }

  const data = new Uint8Array(await response.arrayBuffer())

  // Parse total size from Content-Range: bytes 0-999/12345
  let totalSize = data.length
  const contentRange = response.headers.get("Content-Range")
  if (contentRange) {
    const match = contentRange.match(/\/(\d+)/)
    if (match) totalSize = parseInt(match[1], 10)
  }

  return { data, totalSize }
}

/**
 * Extract plugin manifest from a remote JAR using range requests.
 * Only downloads ~128 KB instead of the full JAR.
 *
 * 1. HEAD the file to get its total size. (GitHub's
 *    release-assets.githubusercontent.com CDN returns 501 on suffix
 *    range requests — `Range: bytes=-N` — but accepts explicit ranges,
 *    so we need the size up front.)
 * 2. Fetch the last 65 KB to find the End of Central Directory.
 * 3. Locate plugin.json in the central directory.
 * 4. Fetch just the local file header + data for that entry.
 */
export async function extractManifestFromRemoteJar(
  downloadUrl: string
): Promise<{ manifest: PluginManifest; totalSize: number }> {
  const tailSize = 65_536

  // Step 1: HEAD for size (redirects are followed by default).
  const headResp = await fetch(downloadUrl, {
    method: "HEAD",
    headers: { "User-Agent": "BOSS-Plugin-Store/1.0" },
  })
  if (!headResp.ok) {
    throw new Error(`HEAD request for JAR size failed: ${headResp.status}`)
  }
  const contentLength = headResp.headers.get("Content-Length")
  if (!contentLength) {
    throw new Error("Remote JAR response has no Content-Length header")
  }
  const totalSize = parseInt(contentLength, 10)
  if (!Number.isFinite(totalSize) || totalSize <= 0) {
    throw new Error(`Invalid Content-Length from remote JAR: ${contentLength}`)
  }

  // Step 2: explicit range for the tail.
  const tailStart = Math.max(0, totalSize - tailSize)
  const tailEnd = totalSize - 1
  const tailResp = await fetch(downloadUrl, {
    headers: {
      "User-Agent": "BOSS-Plugin-Store/1.0",
      Range: `bytes=${tailStart}-${tailEnd}`,
    },
  })

  if (tailResp.status !== 200 && tailResp.status !== 206) {
    throw new Error(`Range request for EOCD failed: ${tailResp.status}`)
  }

  const tailData = new Uint8Array(await tailResp.arrayBuffer())

  // The tail starts at this absolute offset in the file
  const tailOffset = totalSize - tailData.length

  // Find EOCD in the tail
  const tailView = new DataView(tailData.buffer)
  let eocdPos = -1
  for (let i = tailData.length - 22; i >= 0; i--) {
    if (tailView.getUint32(i, true) === 0x06054b50) {
      eocdPos = i
      break
    }
  }

  if (eocdPos === -1) {
    throw new Error("Cannot find EOCD in JAR (range request)")
  }

  let cdSize: number = tailView.getUint32(eocdPos + 12, true)
  let cdOffset: number = tailView.getUint32(eocdPos + 16, true) // absolute offset in file
  let entryCount: number = tailView.getUint16(eocdPos + 10, true)

  // ZIP64 detection — any of these sentinel values means the real numbers
  // live in the ZIP64 EOCD record reachable via the ZIP64 EOCD locator
  // (20 bytes immediately before the standard EOCD).
  const needsZip64 =
    cdSize === 0xffffffff || cdOffset === 0xffffffff || entryCount === 0xffff

  if (needsZip64) {
    const locatorPos = eocdPos - 20
    if (locatorPos < 0 || tailView.getUint32(locatorPos, true) !== 0x07064b50) {
      throw new Error(
        "JAR appears to use ZIP64 but the ZIP64 EOCD locator was not found in the tail"
      )
    }
    // ZIP64 EOCD locator: relative offset of ZIP64 EOCD record is a uint64 at
    // locatorPos+8. JS numbers are safe for byte offsets well under 2^53, so
    // read high/low 32-bit halves and reject archives that actually need >2^53.
    const zip64EocdLo = tailView.getUint32(locatorPos + 8, true)
    const zip64EocdHi = tailView.getUint32(locatorPos + 12, true)
    if (zip64EocdHi > 0x001fffff) {
      throw new Error("ZIP64 EOCD offset exceeds JS safe-integer range")
    }
    const zip64EocdAbs = zip64EocdHi * 0x1_0000_0000 + zip64EocdLo

    // ZIP64 EOCD may live in the tail we already fetched. If not, pull it.
    let z64Data: Uint8Array
    let z64Base: number
    if (zip64EocdAbs >= tailOffset) {
      z64Data = tailData
      z64Base = zip64EocdAbs - tailOffset
    } else {
      const { data } = await downloadRange(downloadUrl, zip64EocdAbs, zip64EocdAbs + 55)
      z64Data = data
      z64Base = 0
    }
    const z64View = new DataView(z64Data.buffer, z64Data.byteOffset, z64Data.byteLength)
    if (z64View.getUint32(z64Base, true) !== 0x06064b50) {
      throw new Error("ZIP64 EOCD record not found at locator offset")
    }
    // Total entries (uint64) at +32, CD size (uint64) at +40, CD offset (uint64) at +48
    const readU64 = (off: number): number => {
      const lo = z64View.getUint32(z64Base + off, true)
      const hi = z64View.getUint32(z64Base + off + 4, true)
      if (hi > 0x001fffff) {
        throw new Error("ZIP64 field exceeds JS safe-integer range")
      }
      return hi * 0x1_0000_0000 + lo
    }
    entryCount = readU64(32)
    cdSize = readU64(40)
    cdOffset = readU64(48)
  }

  // Step 2: fetch the central directory (if not already in tail)
  let cdData: Uint8Array
  let cdBaseOffset: number

  if (cdOffset >= tailOffset) {
    // Central directory is within the tail we already fetched
    const relStart = cdOffset - tailOffset
    cdData = tailData.slice(relStart, relStart + cdSize)
    cdBaseOffset = 0
  } else {
    // Need a separate range request for the central directory
    const { data } = await downloadRange(downloadUrl, cdOffset, cdOffset + cdSize - 1)
    cdData = data
    cdBaseOffset = 0
  }

  // Walk the central directory looking for plugin.json
  const cdView = new DataView(cdData.buffer, cdData.byteOffset, cdData.byteLength)
  const manifestPath = "META-INF/boss-plugin/plugin.json"
  let offset = cdBaseOffset

  while (offset < cdData.length - 46) {
    const sig = cdView.getUint32(offset, true)
    if (sig !== 0x02014b50) break

    const compressionMethod = cdView.getUint16(offset + 10, true)
    let compressedSize: number = cdView.getUint32(offset + 20, true)
    const fileNameLength = cdView.getUint16(offset + 28, true)
    const extraFieldLength = cdView.getUint16(offset + 30, true)
    const commentLength = cdView.getUint16(offset + 32, true)
    let localHeaderOffset: number = cdView.getUint32(offset + 42, true)

    const fnBytes = cdData.slice(offset + 46, offset + 46 + fileNameLength)
    const fileName = new TextDecoder().decode(fnBytes)
    const entryEnd = offset + 46 + fileNameLength + extraFieldLength + commentLength

    // Per-entry ZIP64: if any of compressedSize/uncompressedSize/localHeaderOffset
    // is 0xFFFFFFFF, the real 64-bit value lives in a ZIP64 extra field
    // (headerId 0x0001). plugin.json is small, but its localHeaderOffset can
    // easily exceed 4 GB in a fat JAR even though the file itself is tiny.
    if (
      fileName === manifestPath &&
      (localHeaderOffset === 0xffffffff || compressedSize === 0xffffffff)
    ) {
      const extraStart = offset + 46 + fileNameLength
      const extraEnd = extraStart + extraFieldLength
      let p = extraStart
      let resolved = false
      while (p + 4 <= extraEnd) {
        const headerId = cdView.getUint16(p, true)
        const dataSize = cdView.getUint16(p + 2, true)
        if (headerId === 0x0001) {
          // Order: uncompressedSize(8), compressedSize(8), localHeaderOffset(8), diskStart(4).
          // Each field appears only if its corresponding main value was 0xFFFFFFFF.
          let q = p + 4
          const uncompressedMain = cdView.getUint32(offset + 24, true)
          if (uncompressedMain === 0xffffffff) q += 8
          if (compressedSize === 0xffffffff) {
            const lo = cdView.getUint32(q, true)
            const hi = cdView.getUint32(q + 4, true)
            if (hi > 0x001fffff) {
              throw new Error("ZIP64 compressedSize exceeds JS safe-integer range")
            }
            compressedSize = hi * 0x1_0000_0000 + lo
            q += 8
          }
          if (localHeaderOffset === 0xffffffff) {
            const lo = cdView.getUint32(q, true)
            const hi = cdView.getUint32(q + 4, true)
            if (hi > 0x001fffff) {
              throw new Error("ZIP64 localHeaderOffset exceeds JS safe-integer range")
            }
            localHeaderOffset = hi * 0x1_0000_0000 + lo
          }
          resolved = true
          break
        }
        p += 4 + dataSize
      }
      if (!resolved) {
        throw new Error("plugin.json entry has ZIP64 sentinel without a ZIP64 extra field")
      }
    }

    offset = entryEnd

    if (fileName !== manifestPath) continue

    // Step 3: fetch just this file's local header + data
    // Local header is 30 bytes + filename + extra, then compressed data
    const fetchSize = 30 + fileNameLength + 256 + compressedSize // 256 extra for safety
    const { data: localData } = await downloadRange(
      downloadUrl,
      localHeaderOffset,
      localHeaderOffset + fetchSize - 1
    )
    const localView = new DataView(localData.buffer, localData.byteOffset, localData.byteLength)
    const lhFnLen = localView.getUint16(26, true)
    const lhExtraLen = localView.getUint16(28, true)
    const dataStart = 30 + lhFnLen + lhExtraLen
    const fileData = localData.slice(dataStart, dataStart + compressedSize)

    let content: string
    if (compressionMethod === 0) {
      content = new TextDecoder().decode(fileData)
    } else if (compressionMethod === 8) {
      const ds = new DecompressionStream("deflate-raw")
      const writer = ds.writable.getWriter()
      writer.write(fileData)
      writer.close()
      const reader = ds.readable.getReader()
      const chunks: Uint8Array[] = []
      let len = 0
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        chunks.push(value)
        len += value.length
      }
      const result = new Uint8Array(len)
      let pos = 0
      for (const c of chunks) { result.set(c, pos); pos += c.length }
      content = new TextDecoder().decode(result)
    } else {
      throw new Error(`Unsupported compression: ${compressionMethod}`)
    }

    const manifest = JSON.parse(content) as PluginManifest
    validateManifest(manifest)
    return { manifest, totalSize }
  }

  throw new Error(
    `Plugin manifest not found at ${manifestPath}. Make sure your plugin JAR contains a valid plugin.json.`
  )
}

/**
 * Extract plugin.json from a JAR file (which is a ZIP)
 */
export async function extractManifestFromJar(jarData: ArrayBuffer): Promise<PluginManifest> {
  // JAR files are ZIP files - we need to parse the ZIP to find plugin.json
  const uint8Array = new Uint8Array(jarData)

  // Find the plugin.json entry in the ZIP
  const manifestPath = "META-INF/boss-plugin/plugin.json"
  const manifestContent = await extractFileFromZip(uint8Array, manifestPath)

  if (!manifestContent) {
    throw new Error(
      `Plugin manifest not found at ${manifestPath}. Make sure your plugin JAR contains a valid plugin.json.`
    )
  }

  try {
    const manifest = JSON.parse(manifestContent) as PluginManifest
    validateManifest(manifest)
    return manifest
  } catch (e) {
    if (e instanceof Error && e.message.startsWith("Invalid plugin manifest")) {
      throw e
    }
    throw new Error(`Failed to parse plugin.json: ${(e as Error).message}`)
  }
}

/**
 * Extract a file from a ZIP archive using the central directory.
 *
 * Reads the End of Central Directory record at the tail of the ZIP to locate
 * the central directory, then looks up the target file by name. This is
 * reliable for large JARs (e.g., 95 MB fat JARs with 35k+ entries) where a
 * linear scan of local file headers can break on data descriptors or ZIP64
 * extended fields.
 */
async function extractFileFromZip(
  zipData: Uint8Array,
  targetPath: string
): Promise<string | null> {
  const view = new DataView(zipData.buffer)

  // --- Locate End of Central Directory (EOCD) record ---
  // Signature: 0x06054b50.  The EOCD is at most 65535 + 22 bytes from the end.
  const eocdMinSize = 22
  const maxCommentLen = 65535
  const searchStart = Math.max(0, zipData.length - eocdMinSize - maxCommentLen)
  let eocdOffset = -1

  for (let i = zipData.length - eocdMinSize; i >= searchStart; i--) {
    if (view.getUint32(i, true) === 0x06054b50) {
      eocdOffset = i
      break
    }
  }

  if (eocdOffset === -1) {
    // Fallback: try the linear scan for small JARs
    return extractFileFromZipLinear(zipData, targetPath)
  }

  const cdEntries = view.getUint16(eocdOffset + 10, true)
  const cdSize = view.getUint32(eocdOffset + 12, true)
  const cdOffset = view.getUint32(eocdOffset + 16, true)

  // ZIP64 — sentinel values indicate the real fields live in a ZIP64 EOCD
  // record. JARs that hit this path in memory are rare (50 MB+ goes through
  // the range-request path), but fail loudly instead of silently corrupting.
  if (cdEntries === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
    throw new Error(
      "JAR uses ZIP64 format; use extractManifestFromRemoteJar (range-request path) instead"
    )
  }

  // --- Walk the central directory to find our file ---
  let offset = cdOffset
  for (let i = 0; i < cdEntries; i++) {
    if (offset + 46 > zipData.length) break
    const sig = view.getUint32(offset, true)
    if (sig !== 0x02014b50) break // not a central dir entry

    const compressionMethod = view.getUint16(offset + 10, true)
    const compressedSize = view.getUint32(offset + 20, true)
    const fileNameLength = view.getUint16(offset + 28, true)
    const extraFieldLength = view.getUint16(offset + 30, true)
    const commentLength = view.getUint16(offset + 32, true)
    const localHeaderOffset = view.getUint32(offset + 42, true)

    const fileNameBytes = zipData.slice(offset + 46, offset + 46 + fileNameLength)
    const fileName = new TextDecoder().decode(fileNameBytes)

    offset += 46 + fileNameLength + extraFieldLength + commentLength

    if (fileName !== targetPath) continue

    // --- Read from the local file header to get the actual data ---
    const lhOffset = localHeaderOffset
    if (lhOffset + 30 > zipData.length) return null
    const lhFileNameLen = view.getUint16(lhOffset + 26, true)
    const lhExtraLen = view.getUint16(lhOffset + 28, true)
    const dataOffset = lhOffset + 30 + lhFileNameLen + lhExtraLen
    const fileData = zipData.slice(dataOffset, dataOffset + compressedSize)

    if (compressionMethod === 0) {
      return new TextDecoder().decode(fileData)
    } else if (compressionMethod === 8) {
      try {
        const ds = new DecompressionStream("deflate-raw")
        const writer = ds.writable.getWriter()
        writer.write(fileData)
        writer.close()

        const reader = ds.readable.getReader()
        const chunks: Uint8Array[] = []
        let totalLength = 0

        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          chunks.push(value)
          totalLength += value.length
        }

        const result = new Uint8Array(totalLength)
        let position = 0
        for (const chunk of chunks) {
          result.set(chunk, position)
          position += chunk.length
        }

        return new TextDecoder().decode(result)
      } catch {
        throw new Error("Failed to decompress plugin.json from JAR")
      }
    } else {
      throw new Error(`Unsupported compression method: ${compressionMethod}`)
    }
  }

  return null
}

/**
 * Legacy linear scan fallback for small JARs without a valid EOCD.
 */
async function extractFileFromZipLinear(
  zipData: Uint8Array,
  targetPath: string
): Promise<string | null> {
  const view = new DataView(zipData.buffer)
  let offset = 0

  while (offset < zipData.length - 4) {
    const signature = view.getUint32(offset, true)
    if (signature !== 0x04034b50) break

    const compressionMethod = view.getUint16(offset + 8, true)
    const compressedSize = view.getUint32(offset + 18, true)
    const fileNameLength = view.getUint16(offset + 26, true)
    const extraFieldLength = view.getUint16(offset + 28, true)
    const fileNameBytes = zipData.slice(offset + 30, offset + 30 + fileNameLength)
    const fileName = new TextDecoder().decode(fileNameBytes)
    const dataOffset = offset + 30 + fileNameLength + extraFieldLength

    if (fileName === targetPath) {
      const fileData = zipData.slice(dataOffset, dataOffset + compressedSize)
      if (compressionMethod === 0) {
        return new TextDecoder().decode(fileData)
      } else if (compressionMethod === 8) {
        try {
          const ds = new DecompressionStream("deflate-raw")
          const writer = ds.writable.getWriter()
          writer.write(fileData)
          writer.close()

          const reader = ds.readable.getReader()
          const chunks: Uint8Array[] = []
          let totalLength = 0

          while (true) {
            const { done, value } = await reader.read()
            if (done) break
            chunks.push(value)
            totalLength += value.length
          }

          const result = new Uint8Array(totalLength)
          let position = 0
          for (const chunk of chunks) {
            result.set(chunk, position)
            position += chunk.length
          }

          return new TextDecoder().decode(result)
        } catch {
          throw new Error("Failed to decompress plugin.json from JAR")
        }
      }
    }

    offset = dataOffset + compressedSize
  }

  return null
}

/**
 * Validate a plugin manifest
 */
function validateManifest(manifest: PluginManifest): void {
  const errors: string[] = []

  if (!manifest.pluginId || typeof manifest.pluginId !== "string") {
    errors.push("pluginId is required")
  } else if (!/^[a-zA-Z][a-zA-Z0-9_-]*(?:\.[a-zA-Z0-9_-]+)+$/.test(manifest.pluginId)) {
    errors.push("pluginId must follow reverse domain notation (e.g., com.example.plugin)")
  }

  if (!manifest.displayName || typeof manifest.displayName !== "string") {
    errors.push("displayName is required")
  }

  if (!manifest.version || typeof manifest.version !== "string") {
    errors.push("version is required")
  } else if (!/^\d+\.\d+\.\d+/.test(manifest.version)) {
    errors.push("version must follow semantic versioning (e.g., 1.0.0)")
  }

  if (!manifest.apiVersion || typeof manifest.apiVersion !== "string") {
    errors.push("apiVersion is required")
  }

  if (!manifest.mainClass || typeof manifest.mainClass !== "string") {
    errors.push("mainClass is required")
  }

  if (errors.length > 0) {
    throw new Error(`Invalid plugin manifest: ${errors.join("; ")}`)
  }
}

/**
 * Calculate SHA-256 hash of data
 */
export async function calculateSha256(data: ArrayBuffer): Promise<string> {
  const hashBuffer = await crypto.subtle.digest("SHA-256", data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("")
}

/**
 * Thrown by {@link fetchPluginFromGitHub} when the JAR exceeds the buffer
 * threshold and should be published via /github/metadata instead.
 */
export class JarTooLargeError extends Error {
  constructor(public readonly size: number) {
    super(
      `JAR size (${size} bytes) exceeds the ${LARGE_JAR_THRESHOLD}-byte limit ` +
        `for /github. Use POST /github/metadata — it streams the hash from the ` +
        `release instead of buffering the JAR server-side.`
    )
    this.name = "JarTooLargeError"
  }
}

/**
 * Fetch plugin from GitHub - main entry point
 *
 * Buffers the JAR in memory to compute SHA-256 and upload to Supabase Storage,
 * so callers with JARs ≥ {@link LARGE_JAR_THRESHOLD} get a {@link JarTooLargeError}
 * and should publish via POST /github/metadata, which streams the hash and keeps
 * the JAR on GitHub.
 *
 * @param githubUrl - GitHub repository URL
 * @returns Plugin manifest, JAR data, and metadata
 */
export async function fetchPluginFromGitHub(githubUrl: string): Promise<GitHubPluginResult> {
  // Parse GitHub URL
  const parsed = parseGitHubUrl(githubUrl)
  if (!parsed) {
    throw new Error(
      "Invalid GitHub URL. Expected format: https://github.com/owner/repo"
    )
  }

  // Fetch release
  const release = await fetchLatestRelease(parsed.owner, parsed.repo, parsed.tag)

  // Find JAR asset
  const jarAsset = findJarAsset(release)
  if (!jarAsset) {
    throw new Error(
      `No JAR file found in release ${release.tag_name}. Make sure your release includes a .jar file.`
    )
  }

  if (jarAsset.size >= LARGE_JAR_THRESHOLD) {
    throw new JarTooLargeError(jarAsset.size)
  }

  // Download fully and extract manifest from memory. Use the asset-API path so
  // this works for private repos too (falls back to the public CDN URL when no
  // GITHUB_TOKEN is set).
  const jarData = await downloadReleaseAsset(jarAsset)
  const manifest = await extractManifestFromJar(jarData)
  const sha256 = await calculateSha256(jarData)

  return {
    manifest,
    jarData,
    jarSize: jarData.byteLength,
    sha256,
    releaseNotes: release.body || "",
    version: manifest.version,
  }
}
