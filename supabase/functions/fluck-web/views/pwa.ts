/**
 * What makes the portal installable and able to receive Web Push: the service worker, the web app
 * manifest and its icon. Nothing here is per-user, and nothing here sees message content: the push
 * payload is {title: agent name, body: "New message", tag, url}.
 */

import { esc } from "../utils/html.ts"

/** The worker's script. Plain ES2017; `url` is only ever followed on this worker's own origin. */
export const SERVICE_WORKER = `"use strict";
self.addEventListener("install", function () { self.skipWaiting(); });
self.addEventListener("activate", function (event) { event.waitUntil(self.clients.claim()); });
function clean(value, fallback, max) {
  return typeof value === "string" && value.trim() ? value.replace(/[\\u0000-\\u001f\\u007f]/g, " ").trim().slice(0, max) : fallback;
}
function target(value) {
  try {
    var u = new URL(value, self.location.origin);
    if (u.origin === self.location.origin) return u.href;
  } catch (_) {}
  return new URL("./", self.registration.scope).href;
}
self.addEventListener("push", function (event) {
  var data = {};
  try { data = event.data ? event.data.json() : {}; } catch (_) {}
  if (!data || typeof data !== "object") data = {};
  var options = {
    body: clean(data.body, "New message", 200),
    tag: clean(data.tag, "fluck", 128),
    icon: new URL("icon-512.png", self.registration.scope).href,
    data: { url: target(data.url) }
  };
  event.waitUntil(self.registration.showNotification(clean(data.title, "Fluck", 100), options));
});
self.addEventListener("notificationclick", function (event) {
  event.notification.close();
  var url = target(event.notification.data && event.notification.data.url);
  event.waitUntil((async function () {
    var all = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (var i = 0; i < all.length; i++) {
      var c = all[i];
      if (new URL(c.url).origin !== self.location.origin) continue;
      try {
        await c.focus();
        if (c.url !== url && "navigate" in c) await c.navigate(url);
        return;
      } catch (_) {}
    }
    await self.clients.openWindow(url);
  })());
});
`

/** Absolute-path URLs for the page's own base ("" on the vanity host). */
export function manifestJson(basePath: string): string {
  const scope = `${basePath}/`
  return JSON.stringify({
    id: scope,
    name: "Fluck",
    short_name: "Fluck",
    description: "Your Fluck, from any browser",
    start_url: scope,
    scope,
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#ffffff",
    icons: [{ src: `${basePath}/icon-512.png`, sizes: "512x512", type: "image/png", purpose: "any maskable" }],
  })
}

export function pwaHeadTags(basePath: string): string {
  return `<link rel="manifest" href="${esc(basePath)}/manifest.webmanifest">
<link rel="apple-touch-icon" href="${esc(basePath)}/icon-512.png">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Fluck">`
}

const ICON_SIZE = 512
const ACCENT = [0x0f, 0x5b, 0xff]
type Point = [number, number]
/** FLUCK_MARK's two glyph paths in its 32-unit space (the group is translated by 4). */
const GLYPHS: Point[][] = [
  [[3.1, 4.9], [11.2, 12], [3.1, 19.1], [3.1, 15.6], [6.79, 12], [3.1, 8.4]],
  [[17.25, 4.6], [20.95, 4.6], [19.45, 19.4], [15.75, 19.4]],
].map((poly) => poly.map(([x, y]) => [x + 4, y + 4] as Point))

function inside(poly: Point[], x: number, y: number): boolean {
  let hit = false
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i]
    const [xj, yj] = poly[j]
    if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) hit = !hit
  }
  return hit
}

function crc32(bytes: Uint8Array): number {
  let c = ~0
  for (const b of bytes) {
    c ^= b
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1
  }
  return ~c >>> 0
}

function chunk(type: string, data: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + data.length)
  const view = new DataView(out.buffer)
  view.setUint32(0, data.length)
  out.set(new TextEncoder().encode(type), 4)
  out.set(data, 8)
  view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)))
  return out
}

let iconCache: Promise<Uint8Array<ArrayBuffer>> | null = null

/**
 * The Fluck mark as a full-bleed 512 px PNG (iOS and maskable icons crop their own corners),
 * rendered once from the same geometry as FLUCK_MARK so there is no binary asset to drift.
 */
export function iconPng(): Promise<Uint8Array<ArrayBuffer>> {
  return iconCache ??= renderIcon()
}

async function renderIcon(): Promise<Uint8Array<ArrayBuffer>> {
  const n = ICON_SIZE
  const scale = 32 / n
  const ss = 3
  const raw = new Uint8Array((n * 3 + 1) * n)
  for (let py = 0; py < n; py++) {
    const row = py * (n * 3 + 1)
    for (let px = 0; px < n; px++) {
      let white = 0
      for (let sy = 0; sy < ss; sy++) {
        for (let sx = 0; sx < ss; sx++) {
          const x = (px + (sx + 0.5) / ss) * scale
          const y = (py + (sy + 0.5) / ss) * scale
          if (GLYPHS.some((g) => inside(g, x, y))) white++
        }
      }
      const t = white / (ss * ss)
      for (let c = 0; c < 3; c++) raw[row + 1 + px * 3 + c] = Math.round(ACCENT[c] + (255 - ACCENT[c]) * t)
    }
  }
  const zlib = new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new CompressionStream("deflate"))).arrayBuffer())
  const ihdr = new Uint8Array(13)
  const view = new DataView(ihdr.buffer)
  view.setUint32(0, n)
  view.setUint32(4, n)
  ihdr.set([8, 2, 0, 0, 0], 8) // 8-bit RGB
  const parts = [new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", zlib), chunk("IEND", new Uint8Array())]
  const out = new Uint8Array(parts.reduce((s, p) => s + p.length, 0))
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}
