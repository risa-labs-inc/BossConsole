/**
 * Tests for the latest-release Edge Function — the asset-selection rules (SDK-jar
 * exclusion, arch aliasing, Universal fallback, un-suffixed default installers)
 * and the route behavior (JSON vs 302, validation, no-store caching). No network:
 * the Hono app is driven in-process via app.request() with globalThis.fetch
 * stubbed to serve canned PostgREST rows.
 *
 * Run: cd supabase/functions/latest-release && deno test --config deno.json
 */

import { assert, assertEquals } from "jsr:@std/assert"
// Import the app module (not index.ts, which calls Deno.serve) so no listener starts under test.
import { app, pickAsset, type ReleaseAsset } from "../app.ts"

// Asset name fixtures copied verbatim from the live boss 9.2.25 / bossterm 1.2.126 rows.
const asset = (name: string): ReleaseAsset => ({
  name,
  url: `https://api.risaboss.com/storage/v1/object/public/app-releases/x/${name}`,
})

const BOSS_ASSETS = [
  "BOSS-9.2.25-Universal.dmg",
  "BOSS-9.2.25-arm64.msi",
  "BOSS-9.2.25.msi",
  "BOSS-9.2.25-amd64.deb",
  "BOSS-9.2.25-arm64.deb",
  "BOSS-9.2.25-amd64.rpm",
  "BOSS-9.2.25-arm64.rpm",
  "BOSS-9.2.25-amd64.jar",
  "BOSS-9.2.25-arm64.jar",
  "boss-ipc-1.0.0.jar",
  "boss-ui-sdk-1.0.0.jar",
  "plugin-api-core-1.0.0.jar",
  "plugin-api-ipc-1.0.0.jar",
].map(asset)

const BOSSTERM_ASSETS = [
  "BossTerm-1.2.126.dmg",
  "bossterm_1.2.126_amd64.deb",
  "bossterm_1.2.126_arm64.deb",
  "bossterm-1.2.126.aarch64.rpm",
  "bossterm-1.2.126.x86_64.rpm",
  "bossterm-1.2.126.jar",
].map(asset)

// (a) pickAsset selection rules.

Deno.test("SDK jars without the release version are never picked", () => {
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "jar", null)?.name, "BOSS-9.2.25-amd64.jar")
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "jar", "arm64")?.name, "BOSS-9.2.25-arm64.jar")
})

Deno.test("Universal dmg satisfies both explicit arches", () => {
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "dmg", "arm64")?.name, "BOSS-9.2.25-Universal.dmg")
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "dmg", "amd64")?.name, "BOSS-9.2.25-Universal.dmg")
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "dmg", null)?.name, "BOSS-9.2.25-Universal.dmg")
})

Deno.test("un-suffixed installer wins when no arch is given (plain msi over arm64.msi)", () => {
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "msi", null)?.name, "BOSS-9.2.25.msi")
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "msi", "arm64")?.name, "BOSS-9.2.25-arm64.msi")
})

Deno.test("arch=amd64 falls back to the un-suffixed default installer (no amd64-tagged msi exists)", () => {
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "msi", "amd64")?.name, "BOSS-9.2.25.msi")
})

Deno.test("un-suffixed dmg satisfies any requested arch (BossTerm ships a single dmg)", () => {
  assertEquals(pickAsset(BOSSTERM_ASSETS, "1.2.126", "dmg", "arm64")?.name, "BossTerm-1.2.126.dmg")
  assertEquals(pickAsset(BOSSTERM_ASSETS, "1.2.126", "dmg", "amd64")?.name, "BossTerm-1.2.126.dmg")
})

Deno.test("ambiguous multi-arch defaults to amd64", () => {
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "deb", null)?.name, "BOSS-9.2.25-amd64.deb")
  assertEquals(pickAsset(BOSS_ASSETS, "9.2.25", "rpm", null)?.name, "BOSS-9.2.25-amd64.rpm")
})

Deno.test("arch aliases: aarch64 rpm found via arch=arm64, x86_64 via amd64/x64", () => {
  assertEquals(pickAsset(BOSSTERM_ASSETS, "1.2.126", "rpm", "arm64")?.name, "bossterm-1.2.126.aarch64.rpm")
  assertEquals(pickAsset(BOSSTERM_ASSETS, "1.2.126", "rpm", "amd64")?.name, "bossterm-1.2.126.x86_64.rpm")
})

Deno.test("underscore-separated deb names tokenize (bossterm_1.2.126_arm64.deb)", () => {
  assertEquals(pickAsset(BOSSTERM_ASSETS, "1.2.126", "deb", "arm64")?.name, "bossterm_1.2.126_arm64.deb")
})

Deno.test("no matching extension → null", () => {
  assertEquals(pickAsset(BOSSTERM_ASSETS, "1.2.126", "msi", null), null)
})

// (b) Routes, with PostgREST stubbed out.

const BOSSTERM_ROW = {
  app: "bossterm",
  version: "1.2.126",
  channel: "stable",
  prerelease: false,
  release_notes: "notes",
  assets: BOSSTERM_ASSETS,
  published_at: "2026-07-03T09:39:32.010341+00:00",
}

/** Runs `fn` with fetch stubbed to return `rows` and env vars set, restoring both after. */
async function withPostgrest(
  rows: unknown[],
  fn: (requested: URL[]) => Promise<void>,
): Promise<void> {
  const realFetch = globalThis.fetch
  const requested: URL[] = []
  Deno.env.set("SUPABASE_URL", "https://stub.supabase.test")
  Deno.env.set("SUPABASE_ANON_KEY", "stub-anon-key")
  globalThis.fetch = ((input: URL | Request | string) => {
    requested.push(new URL(input instanceof Request ? input.url : input.toString()))
    return Promise.resolve(new Response(JSON.stringify(rows), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }))
  }) as typeof fetch
  try {
    await fn(requested)
  } finally {
    globalThis.fetch = realFetch
  }
}

Deno.test("?app=bossterm returns latest-release JSON with no-store", async () => {
  await withPostgrest([BOSSTERM_ROW], async () => {
    const res = await app.request("/latest-release?app=bossterm")
    assertEquals(res.status, 200)
    assertEquals(res.headers.get("Cache-Control"), "no-store")
    const body = await res.json()
    assertEquals(body.version, "1.2.126")
    assertEquals(body.assets.length, BOSSTERM_ASSETS.length)
  })
})

Deno.test("?download=dmg 302-redirects to the versioned asset URL", async () => {
  await withPostgrest([BOSSTERM_ROW], async () => {
    const res = await app.request("/latest-release?app=bossterm&download=dmg")
    assertEquals(res.status, 302)
    assert(res.headers.get("Location")!.endsWith("/BossTerm-1.2.126.dmg"))
    assertEquals(res.headers.get("X-App-Version"), "1.2.126")
    assertEquals(res.headers.get("Cache-Control"), "no-store")
  })
})

Deno.test("prereleases are excluded unless prerelease=true", async () => {
  await withPostgrest([BOSSTERM_ROW], async (requested) => {
    await app.request("/latest-release?app=bossterm")
    assert(requested[0].search.includes("prerelease=eq.false"))
    await app.request("/latest-release?app=bossterm&prerelease=true")
    assert(!requested[1].search.includes("prerelease=eq.false"))
  })
})

Deno.test("channel filter is forwarded; hostile channel is rejected before any query", async () => {
  await withPostgrest([BOSSTERM_ROW], async (requested) => {
    await app.request("/latest-release?app=bossterm&channel=beta")
    assert(requested[0].search.includes("channel=eq.beta"))
    const res = await app.request(
      "/latest-release?app=bossterm&channel=" + encodeURIComponent("stable&select=secret"),
    )
    assertEquals(res.status, 400)
    assertEquals(requested.length, 1, "invalid channel must not reach PostgREST")
  })
})

Deno.test("unknown app / download / arch are 400 with usage", async () => {
  await withPostgrest([BOSSTERM_ROW], async () => {
    for (
      const path of [
        "/latest-release?app=evil",
        "/latest-release",
        "/latest-release?app=boss&download=exe",
        "/latest-release?app=boss&download=dmg&arch=mips",
      ]
    ) {
      const res = await app.request(path)
      assertEquals(res.status, 400, path)
      assert((await res.json()).usage)
    }
  })
})

Deno.test("asset miss returns 404 listing available names", async () => {
  await withPostgrest([BOSSTERM_ROW], async () => {
    const res = await app.request("/latest-release?app=bossterm&download=msi")
    assertEquals(res.status, 404)
    const body = await res.json()
    assertEquals(body.available.length, BOSSTERM_ASSETS.length)
  })
})

Deno.test("empty catalog returns 404", async () => {
  await withPostgrest([], async () => {
    const res = await app.request("/latest-release?app=boss")
    assertEquals(res.status, 404)
  })
})

// (c) boss-chromium engine: &platform= selection over boss-chromium-<platform>.zip.

const ENGINE_ASSETS = [
  "boss-chromium-macos-arm64.zip",
  "boss-chromium-macos-x64.zip",
  "boss-chromium-windows-x64.zip",
  "boss-chromium-windows-arm64.zip",
  "boss-chromium-linux-x64.zip",
  "boss-chromium-linux-arm64.zip",
].map(asset)

const ENGINE_ROW = {
  app: "boss-chromium",
  version: "9.3.0",
  channel: "stable",
  prerelease: false,
  release_notes: "engine",
  assets: ENGINE_ASSETS,
  published_at: "2026-07-10T09:39:00.000000+00:00",
}

Deno.test("?app=boss-chromium returns engine JSON and queries app=eq.boss-chromium", async () => {
  await withPostgrest([ENGINE_ROW], async (requested) => {
    const res = await app.request("/latest-release?app=boss-chromium")
    assertEquals(res.status, 200)
    assertEquals(res.headers.get("Cache-Control"), "no-store")
    const body = await res.json()
    assertEquals(body.version, "9.3.0")
    assertEquals(body.assets.length, ENGINE_ASSETS.length)
    assert(requested[0].search.includes("app=eq.boss-chromium"))
  })
})

Deno.test("?app=boss-chromium&platform=macos-arm64 302s to that platform's zip", async () => {
  await withPostgrest([ENGINE_ROW], async () => {
    const res = await app.request("/latest-release?app=boss-chromium&platform=macos-arm64")
    assertEquals(res.status, 302)
    assert(res.headers.get("Location")!.endsWith("/boss-chromium-macos-arm64.zip"))
    assertEquals(res.headers.get("X-App-Version"), "9.3.0")
    assertEquals(res.headers.get("Cache-Control"), "no-store")
  })
})

Deno.test("engine rejects the apps' download/arch model and apps reject platform", async () => {
  await withPostgrest([ENGINE_ROW], async () => {
    for (
      const path of [
        "/latest-release?app=boss-chromium&platform=solaris-sparc", // unknown platform
        "/latest-release?app=boss-chromium&download=zip", // wrong model for engine
        "/latest-release?app=boss-chromium&arch=arm64", // wrong model for engine
        "/latest-release?app=boss&platform=macos-arm64", // platform not valid for apps
      ]
    ) {
      const res = await app.request(path)
      assertEquals(res.status, 400, path)
      assert((await res.json()).usage)
    }
  })
})

Deno.test("engine platform miss returns 404 listing available names", async () => {
  await withPostgrest([{ ...ENGINE_ROW, assets: [asset("boss-chromium-linux-x64.zip")] }], async () => {
    const res = await app.request("/latest-release?app=boss-chromium&platform=macos-arm64")
    assertEquals(res.status, 404)
    const body = await res.json()
    assertEquals(body.available, ["boss-chromium-linux-x64.zip"])
  })
})
