/**
 * GET  /o/:slug/plugins/:pluginId          -- one plugin's page
 * POST /o/:slug/plugins/:pluginId/visibility -- an org admin changing who can see it
 *
 * NESTED UNDER THE OWNING ORGANISATION, which is a deliberate choice with a cost worth writing
 * down: the URL moves if a plugin is re-attributed, and that is not hypothetical - 20260812000000
 * moved five plugins from `boss` to `risa`. Links to the old path stop resolving. What it buys is
 * that ownership is visible in the URL and the org session already in hand is exactly the
 * credential the visibility control needs, with no second handoff.
 *
 * THE PLUGIN IS CHECKED AGAINST THE SLUG IN THE PATH, every time, on both verbs. Without that,
 * `/o/my-org/plugins/<someone else's plugin>` would render - and worse, POST would carry an
 * org-admin session for MY organisation into a write on THEIRS. It is the same rule
 * routes/domains.ts states for domain ids, for the same reason.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { loadPlugin } from "../services/plugin.ts"
import { fetchReadme } from "../services/readme.ts"
import { callForActor } from "../utils/org-rpc.ts"
import { htmlResponse, redirectResponse } from "../utils/responses.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { isValidSlug, readRequestFacts } from "../utils/request.ts"
import { consumeHandoffToken } from "./handoff-exchange.ts"
import { requireOrgAdmin } from "./guards.ts"
import { prepare } from "./admin-actions.ts"
import { errorPage, NOT_AVAILABLE_MESSAGE } from "../views/error.ts"
import { pluginPage } from "../views/plugin.ts"

export const pluginPageRoutes = new OpenAPIHono()

/** Values the visibility control may set. Mirrors the column CHECK and the RPC. */
const VISIBILITY_VALUES = ["public", "org", "unlisted"]

/** 30 renders a minute per client. Far beyond a human reader, fatal to a catalogue walk. */
const PAGE_LIMIT = 30
const PAGE_WINDOW_SECONDS = 60

pluginPageRoutes.get("/o/:slug/plugins/:pluginId", async (ctx) => {
  // Same shape as the admin page: a handoff token may arrive here directly, because the desktop
  // app can link straight to a plugin without the reader having visited the org page first.
  const slug = ctx.req.param("slug") ?? ""
  if (!isValidSlug(slug)) return notAvailable()

  const pluginId = ctx.req.param("pluginId") ?? ""
  const facts = await readRequestFacts(ctx)

  const exchanged = await consumeHandoffToken(
    ctx,
    facts,
    slug,
    `/o/${encodeURIComponent(slug)}/plugins/${encodeURIComponent(pluginId)}`,
  )
  if (exchanged) return exchanged

  // THE RENDER IS RATE LIMITED, like every sibling in this function: join pays for its preview
  // RPC, the handoff exchange for its token consumption, the admin write for its authority probe,
  // DNS for its resolve - each limiter set against the cost that route spends. This page spends
  // the most of any of them per request: a get_plugin_with_stats_for_viewer RPC plus, on a cache
  // miss, an authenticated call to api.github.com for the README (services/readme.ts), out of the
  // shared GitHub budget every other GitHub-touching route conserves. And because it is readable
  // with NO session (the widening documented below), a walk over the catalogue - or one hot
  // plugin id - could spend all of that with zero friction: no cookie to forge, no token to
  // guess. The brake sits after the handoff exchange, which keeps its own tighter limit and its
  // own documented order, and BEFORE loadPlugin/fetchReadme - the two costs it exists to protect.
  //
  // A rate-limited caller gets the SAME "Not available" page every invisible plugin already
  // gets, for the same reason join renders one page for every unusable invite: a distinct 429
  // would tell a script it was going fast enough to matter, and would separate "too many" from
  // "not yours to see" - which is a signal on its own.
  const key = clientKey(ctx.req.raw.headers)
  const limit = rateLimit(
    `pluginpage:${key}`,
    PAGE_LIMIT,
    PAGE_WINDOW_SECONDS,
  )
  if (!limit.allowed) {
    // A throttle decided on the "unknown" key means the gateway set no
    // client-IP header and the whole world shares one bucket - a
    // misconfiguration, not an attack. The 404 stays silent to the caller,
    // but the operator gets one grep-able line.
    if (key === "unknown") {
      console.debug(
        "plugin-page throttled on the shared unknown key: no client-IP header reached the function",
      )
    }
    return notAvailable()
  }

  // NO SESSION IS REQUIRED TO READ THIS PAGE, and that is a deliberate widening of the rule the
  // rest of these pages follow.
  //
  // The Toolbox links here from its Store, Installed and Updates lists. Handing off a session
  // needs mint_organisation_handoff_token, which is MEMBERS ONLY - so gating this page on a
  // session would make those links work only for plugins belonging to an organisation the reader
  // has joined. Today that is 40 plugins across `boss` and `risa`, and almost no reader is in
  // either, so the feature would appear to work for whoever built it and do nothing for everybody
  // else.
  //
  // It gives nothing away. The viewer is passed to the RPC as NULL, so the same
  // user_can_view_plugin_row that guards the catalogue decides what is visible, and its anonymous
  // arm is `public AND published` - exactly what the Toolbox itself already reads as `anon` from
  // /plugin-store/list. An `org` or `unlisted` plugin still needs the session, and the control
  // below still needs an admin.
  //
  // A session for ANOTHER organisation reads as no session rather than as an error: it is not
  // this page's business to refuse a stranger who is signed in somewhere else.
  const session = facts.session && facts.session.slug === slug ? facts.session : null

  const plugin = await loadPlugin(pluginId, session?.sub ?? null)

  // One answer for "no such plugin", "not visible to you" and "belongs to another organisation".
  // Telling them apart would confirm a private plugin's existence to somebody who cannot see it.
  if (!plugin) return notAvailable()
  if (session ? plugin.org_id !== session.org : plugin.org_slug !== slug) return notAvailable()

  // Only an admin gets the control. The RPC re-checks, so this decides what to DRAW.
  const isAdmin = session ? (await requireOrgAdmin({ session, facts })).ok : false

  // Fetched on render, best effort. A slow or rate-limited GitHub costs the README, never the
  // page - see services/readme.ts for why every failure is the same absence.
  const readme = await fetchReadme(plugin.homepage_url)

  const url = new URL(ctx.req.url)
  return htmlResponse((nonce) =>
    pluginPage({
      nonce,
      basePath: facts.basePath,
      // The PATH slug, which the check above has just tied to the plugin's own organisation by
      // one route or the other. Reading it off the session would be null for a signed-out reader.
      orgSlug: slug,
      // Empty for a signed-out reader, who gets no form to put it in.
      csrf: session?.csrf ?? "",
      // Told to us by the Toolbox, which is what links here; absent for any other visitor. Only a
      // LABEL depends on it - see views/plugin.ts - so an absent or stale value is not a
      // correctness question. Anything other than the two values we write reads as unknown.
      installed: installedHint(url.searchParams.get("installed")),
      plugin,
      readme,
      canEdit: isAdmin,
      banner: resolvePluginBanner(url.searchParams.get("ok"), url.searchParams.get("err")),
    })
  )
})

pluginPageRoutes.post("/o/:slug/plugins/:pluginId/visibility", async (ctx) => {
  // prepare() is session -> CSRF -> admin probe -> rate limit, in that order and for the reasons
  // stated where it lives: a forged post costs one HMAC verification and stops, and the authority
  // probe is the last thing to touch the database. Reused rather than restated so this route
  // cannot end up with a different order from every other write in this function.
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const pluginId = ctx.req.param("pluginId") ?? ""
  const back = `${facts.basePath}/o/${encodeURIComponent(session.slug)}/plugins/${
    encodeURIComponent(pluginId)
  }`

  // RULE: the plugin must be this organisation's. Loaded before the write so an admin of org A
  // cannot carry their session into a write on org B's plugin by putting its id in the path.
  const plugin = await loadPlugin(pluginId, session.sub)
  if (!plugin || plugin.org_id !== session.org) return notAvailable()

  const visibility = body["visibility"]
  if (typeof visibility !== "string" || !VISIBILITY_VALUES.includes(visibility)) {
    return redirectResponse(`${back}?err=invalid_input`)
  }

  const result = await callForActor("set_plugin_visibility", session.sub, {
    p_plugin_id: plugin.id,
    p_visibility: visibility,
  })

  return redirectResponse(`${back}?${result.ok ? "ok=visibility_saved" : "err=rejected"}`)
})

/**
 * A banner from a fixed key, exactly as the admin page does it.
 *
 * The key is echoed, never the message: nothing a caller writes into the query string can reach
 * the page.
 */
function resolvePluginBanner(
  okKey: string | null,
  errKey: string | null,
): { kind: "ok" | "error"; message: string } | null {
  const ok: Record<string, string> = {
    visibility_saved: "Visibility updated.",
  }
  const err: Record<string, string> = {
    invalid_input: "That visibility value was not accepted.",
    rejected: "The change was refused.",
  }
  if (errKey && Object.hasOwn(err, errKey)) return { kind: "error", message: err[errKey] }
  if (okKey && Object.hasOwn(ok, okKey)) return { kind: "ok", message: ok[okKey] }
  return null
}

function notAvailable(): Response {
  return htmlResponse(
    (nonce) =>
      errorPage({
        nonce,
        title: "Not available - BOSS",
        heading: "Not available",
        message: NOT_AVAILABLE_MESSAGE,
      }),
    { status: 404 },
  )
}

/** `1` or `0` from the Toolbox; anything else, including absent, means "not known". */
function installedHint(raw: string | null): boolean | null {
  if (raw === "1") return true
  if (raw === "0") return false
  return null
}
