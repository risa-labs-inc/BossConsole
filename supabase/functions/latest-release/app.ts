/**
 * Latest-Release Edge Function
 *
 * Public, key-free "what is the latest version?" endpoint over the `app_releases`
 * table, plus a STABLE download link that always 302-redirects to the newest
 * installer. The table is already anon-readable (RLS SELECT true), but PostgREST
 * itself hard-requires an `apikey` header/param — this function removes that
 * requirement so the links work in a bare browser, curl, or a website button.
 *
 * Routes (deployed with verify_jwt=false, like the sibling `redirect` function):
 * - GET /?app=boss                              → JSON of the latest release row
 * - GET /?app=bossterm&download=dmg             → 302 to the newest matching installer
 * - GET /?app=boss&download=deb&arch=arm64      → arch-disambiguated 302
 * - GET /?app=boss-chromium                     → JSON of the latest engine row
 * - GET /?app=boss-chromium&platform=macos-arm64 → 302 to that platform's engine zip
 * - GET /health                                 → health check
 *
 * Query params:
 * - app        (required) 'boss' | 'bossterm' | 'boss-chromium'
 * - download   (optional, apps only) installer extension: dmg | msi | deb | rpm | jar
 * - arch       (optional, apps only, meaningful with download) arm64|aarch64 or
 *              amd64|x86_64|x64 or universal; still validated without it
 * - platform   (optional, boss-chromium only) macos-arm64 | macos-x64 |
 *              windows-x64 | windows-arm64 | linux-x64 | linux-arm64
 * - channel    (optional) filter by release channel (stable | alpha | beta | rc)
 * - prerelease (optional) 'true' to include prereleases (excluded by default)
 *
 * The redirect preserves the versioned filename (the browser saves the file under
 * the final storage URL's name, e.g. BossTerm-1.2.126.dmg), which is why this is
 * a redirect and not a fixed-name copy in the bucket.
 *
 * Responses are Cache-Control: no-store — a cached 302 would keep serving an old
 * installer after a release, defeating the point of a "latest" link.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { cors } from "hono/cors"

export const app = new OpenAPIHono().basePath("/latest-release")

app.use("*", cors({
  origin: "*",
  allowMethods: ["GET", "OPTIONS"],
  allowHeaders: ["Content-Type"],
  maxAge: 600,
}))

const KNOWN_APPS = ["boss", "bossterm", "boss-chromium"] as const
type AppId = typeof KNOWN_APPS[number]

const DOWNLOAD_EXTENSIONS = ["dmg", "msi", "deb", "rpm", "jar"] as const

// The BOSS-branded Chromium engine (app="boss-chromium") is published as
// boss-chromium-<platform>.zip, where <platform> is EXACTLY the client's
// ChromiumAutoDownloader.detectPlatform() output. Unlike the desktop apps it
// carries no version or arch-only spelling in the filename, so it's selected by
// an explicit &platform= rather than the installer download=/arch= model.
const ENGINE_APP = "boss-chromium"
const ENGINE_PLATFORMS = [
  "macos-arm64",
  "macos-x64",
  "windows-x64",
  "windows-arm64",
  "linux-x64",
  "linux-arm64",
] as const

// Channel values reach the PostgREST filter string — whitelist the charset so a
// crafted channel can't smuggle extra `&order=`/`&select=` filter clauses.
const CHANNEL_RE = /^[a-z0-9-]{1,32}$/

export interface ReleaseAsset {
  name: string
  url: string
  size?: number
  sha256?: string
}

interface ReleaseRow {
  app: string
  version: string
  channel: string
  prerelease: boolean
  release_notes: string
  assets: ReleaseAsset[]
  published_at: string
}

// Both apps' CI names the same CPU differently (BOSS: arm64/amd64, BossTerm rpm:
// aarch64/x86_64), so each canonical arch matches all of its spellings.
const ARCH_ALIASES: Record<string, string[]> = {
  arm64: ["arm64", "aarch64"],
  amd64: ["amd64", "x86_64", "x64"],
  universal: ["universal"],
}

function canonicalArch(raw: string): string | null {
  const lowered = raw.toLowerCase()
  for (const [canonical, aliases] of Object.entries(ARCH_ALIASES)) {
    if (aliases.includes(lowered)) return canonical
  }
  return null
}

/**
 * Does the filename carry this arch, bounded by the separators both apps' CI
 * uses? Bounded (not plain substring) so "arm64" can't match inside another
 * word; regex-based (not token-split) because the alias "x86_64" itself
 * contains a separator and would never survive tokenization.
 */
function hasArch(asset: ReleaseAsset, canonical: string): boolean {
  return ARCH_ALIASES[canonical].some((alias) =>
    new RegExp(`(^|[-_.])${alias}([-_.]|$)`, "i").test(asset.name)
  )
}

function hasAnyArch(asset: ReleaseAsset): boolean {
  return Object.keys(ARCH_ALIASES).some((canonical) => hasArch(asset, canonical))
}

/**
 * Picks the installer a `download=` link should serve.
 *
 * Only assets whose name contains the release version are considered: BOSS
 * releases also attach SDK jars (boss-ipc-1.0.0.jar, plugin-api-core-1.0.0.jar…)
 * that a bare extension match would happily serve as "the app".
 *
 * With `arch`, assets carrying that arch win; failing that, a Universal build
 * (covers either CPU), then the un-suffixed default installer. Without `arch`,
 * ambiguity resolves deterministically: universal → un-suffixed default
 * installer (BOSS-x.y.z.msi) → amd64 → arm64.
 */
export function pickAsset(
  assets: ReleaseAsset[],
  version: string,
  extension: string,
  arch: string | null,
): ReleaseAsset | null {
  // Version-containment is the ONLY thing separating app jars from the
  // co-published SDK jars (boss-ipc-1.0.0.jar…). That holds while app versions
  // (9.x / 1.2.x) never collide with SDK versions — if one ever does, switch
  // this to matching the installer filename prefix instead.
  const candidates = assets.filter((a) =>
    a.name.toLowerCase().includes(version.toLowerCase()) &&
    a.name.toLowerCase().endsWith(`.${extension}`)
  )
  if (candidates.length === 0) return null

  if (arch) {
    const exact = candidates.filter((a) => hasArch(a, arch))
    if (exact.length > 0) return exact[0]
    // No asset tagged with the requested arch: a Universal build covers both
    // CPUs, and failing that the un-suffixed installer (BOSS-x.y.z.msi,
    // BossTerm-x.y.z.dmg) is the default/only build offered — serve it rather
    // than 404 a "Download for <arch>" button.
    return candidates.find((a) => hasArch(a, "universal")) ??
      candidates.find((a) => !hasAnyArch(a)) ??
      null
  }

  if (candidates.length === 1) return candidates[0]
  return (
    candidates.find((a) => hasArch(a, "universal")) ??
    candidates.find((a) => !hasAnyArch(a)) ??
    candidates.find((a) => hasArch(a, "amd64")) ??
    candidates.find((a) => hasArch(a, "arm64")) ??
    candidates[0]
  )
}

/**
 * Latest release for an app via PostgREST, using the runtime-injected anon key
 * (the table's RLS already allows anon SELECT — this function adds no privilege,
 * it only sheds the apikey requirement for callers).
 */
async function fetchLatest(
  appId: AppId,
  channel: string | null,
  includePrerelease: boolean,
): Promise<ReleaseRow | null> {
  const supabaseUrl = Deno.env.get("SUPABASE_URL")
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")
  if (!supabaseUrl || !anonKey) {
    throw new Error("SUPABASE_URL / SUPABASE_ANON_KEY not configured")
  }

  let url = `${supabaseUrl.replace(/\/$/, "")}/rest/v1/app_releases` +
    `?app=eq.${appId}&order=published_at.desc&limit=1&select=*`
  if (channel) url += `&channel=eq.${channel}`
  if (!includePrerelease) url += `&prerelease=eq.false`

  const response = await fetch(url, {
    headers: { apikey: anonKey, Authorization: `Bearer ${anonKey}` },
  })
  if (!response.ok) {
    throw new Error(`app_releases lookup failed (HTTP ${response.status})`)
  }
  const rows = await response.json() as ReleaseRow[]
  return rows[0] ?? null
}

const USAGE = {
  usage: "/?app=<boss|bossterm>[&download=<dmg|msi|deb|rpm|jar>][&arch=<arm64|amd64|universal>][&channel=<channel>][&prerelease=true]  |  /?app=boss-chromium[&platform=<macos-arm64|macos-x64|windows-x64|windows-arm64|linux-x64|linux-arm64>]",
  examples: [
    "/latest-release?app=boss",
    "/latest-release?app=bossterm&download=dmg",
    "/latest-release?app=boss&download=deb&arch=arm64",
    "/latest-release?app=boss-chromium",
    "/latest-release?app=boss-chromium&platform=macos-arm64",
  ],
}

app.get("/health", (c) => {
  return c.json({ status: "healthy", timestamp: new Date().toISOString() }, 200)
})

app.get("/", async (c) => {
  const appParam = c.req.query("app") ?? ""
  if (!(KNOWN_APPS as readonly string[]).includes(appParam)) {
    return c.json({ error: "Missing or unknown 'app' parameter", ...USAGE }, 400)
  }
  const appId = appParam as AppId

  const channel = c.req.query("channel") ?? null
  if (channel !== null && !CHANNEL_RE.test(channel)) {
    return c.json({ error: "Invalid 'channel' parameter", ...USAGE }, 400)
  }

  const download = c.req.query("download")?.toLowerCase() ?? null
  if (download !== null && !(DOWNLOAD_EXTENSIONS as readonly string[]).includes(download)) {
    return c.json({ error: "Invalid 'download' parameter", ...USAGE }, 400)
  }

  const archParam = c.req.query("arch") ?? null
  const arch = archParam === null ? null : canonicalArch(archParam)
  if (archParam !== null && arch === null) {
    return c.json({ error: "Invalid 'arch' parameter", ...USAGE }, 400)
  }

  // Keep the engine and app download models from being mixed: boss-chromium uses
  // &platform=, the apps use &download=/&arch=.
  const platformParam = c.req.query("platform") ?? null
  if (appId === ENGINE_APP) {
    if (download !== null || archParam !== null) {
      return c.json({ error: "app=boss-chromium selects builds with &platform=, not download/arch", ...USAGE }, 400)
    }
    if (platformParam !== null && !(ENGINE_PLATFORMS as readonly string[]).includes(platformParam)) {
      return c.json({ error: "Invalid 'platform' parameter", ...USAGE }, 400)
    }
  } else if (platformParam !== null) {
    return c.json({ error: "'platform' is only valid for app=boss-chromium", ...USAGE }, 400)
  }

  const release = await fetchLatest(appId, channel, c.req.query("prerelease") === "true")
  if (!release) {
    return c.json({ error: `No release found for '${appId}'` }, 404, {
      "Cache-Control": "no-store",
    })
  }

  // Engine download: the asset name is deterministic (boss-chromium-<platform>.zip),
  // so match it exactly rather than through the apps' version/arch heuristics.
  if (appId === ENGINE_APP && platformParam) {
    const name = `boss-chromium-${platformParam}.zip`
    const asset = release.assets.find((a) => a.name === name)
    if (!asset) {
      return c.json({
        error: `No ${name} in boss-chromium ${release.version}`,
        available: release.assets.map((a) => a.name),
      }, 404, { "Cache-Control": "no-store" })
    }
    return c.body(null, 302, {
      Location: asset.url,
      "Cache-Control": "no-store",
      "X-App-Version": release.version,
    })
  }

  if (download) {
    const asset = pickAsset(release.assets, release.version, download, arch)
    if (!asset) {
      return c.json({
        error: `No .${download} asset${arch ? ` for arch '${arch}'` : ""} in ${appId} ${release.version}`,
        available: release.assets.map((a) => a.name),
      }, 404, { "Cache-Control": "no-store" })
    }
    return c.body(null, 302, {
      Location: asset.url,
      "Cache-Control": "no-store",
      "X-App-Version": release.version,
    })
  }

  return c.json({
    app: release.app,
    version: release.version,
    channel: release.channel,
    prerelease: release.prerelease,
    published_at: release.published_at,
    release_notes: release.release_notes,
    assets: release.assets,
  }, 200, { "Cache-Control": "no-store" })
})

app.notFound((c) => {
  return c.json({ error: "Not Found" }, 404)
})

app.onError((err, c) => {
  console.error("Global error:", err)
  return c.json({ error: err.message }, 500)
})
