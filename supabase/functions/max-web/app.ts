/**
 * Max Web Edge Function: a narrow alias of fluck-web for the Max plugin's push ping.
 *
 * Max (boss-plugin-max) POSTs its "a message arrived" ping to
 * /functions/v1/max-web/api/push/notify, while the route lives in fluck-web. Released Max builds
 * already carry that URL, so this alias makes their notifications work without a plugin release.
 *
 * Only POST /api/push/notify is forwarded (ticket-only, no cookie, no page). Every other path is a
 * 404, so this function adds no portal, auth or session surface of its own.
 */
import { app } from "../fluck-web/app.ts"

export const ALIAS_BASE = "/max-web"
export const TARGET_BASE = "/fluck-web"
export const FORWARDED = new Set(["/api/push/notify"])

function notFound(): Response {
  return new Response(JSON.stringify({ error: "not_found" }), {
    status: 404,
    headers: { "content-type": "application/json; charset=utf-8" },
  })
}

export function maxWebFetch(req: Request): Response | Promise<Response> {
  const url = new URL(req.url)
  const route = url.pathname.startsWith(ALIAS_BASE) ? url.pathname.slice(ALIAS_BASE.length) : null
  if (req.method !== "POST" || route === null || !FORWARDED.has(route)) return notFound()
  url.pathname = TARGET_BASE + route
  return app.fetch(new Request(url, req))
}
